from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path

import android_bridge
import android_bridge.boundary as boundary
import pytest
from android_bridge.faults import FAULT_ID_PATTERN
from android_bridge.protocol import decode_envelope, encode_message

PROJECT_ROOT = Path(__file__).resolve().parents[3]
PYTHON_ROOT = PROJECT_ROOT / "app" / "src" / "main" / "python"


def test_package_exposes_one_guarded_kotlin_entry_point() -> None:
    assert android_bridge.__all__ == ["BRIDGE_SCHEMA_VERSION", "dispatch"]
    assert android_bridge.dispatch is boundary.dispatch


def test_dispatch_serializes_malformed_and_protocol_errors() -> None:
    malformed = decode_envelope(boundary.dispatch("not json"), expected_type="bridge.error")
    invalid_request = decode_envelope(
        boundary.dispatch(encode_message("bootstrap.initialize", {})),
        expected_type="bridge.error",
    )

    assert malformed.payload["code"] == "invalid_json"
    assert "requestType" not in malformed.payload
    assert invalid_request.payload == {
        "code": "invalid_bootstrap_request",
        "message": "Expected payload fields: ['filesDir']",
        "requestType": "bootstrap.initialize",
    }


def test_dispatch_serializes_internal_errors_without_leaking_exception(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    def explode(*_: object) -> str:
        raise RuntimeError("secret filesystem detail")

    monkeypatch.setattr(boundary, "_dispatch_validated", explode)
    with caplog.at_level("ERROR", logger=boundary.logger.name):
        raw = boundary.dispatch(encode_message("job.cancel", {"runId": "run_" + "0" * 32}))
    decoded = decode_envelope(raw, expected_type="bridge.error")

    fault_id = decoded.payload.pop("faultId")
    assert decoded.payload == {
        "code": "internal_error",
        "message": "Internal bridge failure",
        "requestType": "job.cancel",
    }
    assert re.fullmatch(FAULT_ID_PATTERN, fault_id)
    assert "secret" not in raw
    assert "RuntimeError" not in raw

    # The id is only worth anything if the traceback it labels is in the same record.
    faults = [record for record in caplog.records if fault_id in record.getMessage()]
    assert len(faults) == 1
    assert faults[0].exc_info is not None
    assert faults[0].exc_info[0] is RuntimeError
    assert "secret filesystem detail" in caplog.text


def test_dispatch_does_not_swallow_process_control_exceptions(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    def stop(*_: object) -> str:
        raise SystemExit(7)

    monkeypatch.setattr(boundary, "_dispatch_validated", stop)

    with caplog.at_level("ERROR", logger=boundary.logger.name):
        with pytest.raises(SystemExit) as stopped:
            boundary.dispatch(encode_message("job.cancel", {"runId": "run_" + "0" * 32}))
    assert stopped.value.code == 7

    escaping = [record for record in caplog.records if record.exc_info]
    assert len(escaping) == 1
    assert "job.cancel" in escaping[0].getMessage()
    assert escaping[0].exc_info[0] is SystemExit


@pytest.mark.parametrize(
    ("literal", "code"),
    [
        ("1e309", "non_finite_number"),
        ("-1e309", "non_finite_number"),
        ("9223372036854775808", "integer_out_of_range"),
        ("-9223372036854775809", "integer_out_of_range"),
    ],
)
def test_dispatch_serializes_numeric_policy_errors_without_raw_value_error(
    literal: str,
    code: str,
) -> None:
    raw = (
        '{"schemaVersion":1,"type":"job.cancel",'
        '"payload":{"runId":"run_00000000000000000000000000000000",'
        '"nested":{"value":' + literal + "}}}"
    )

    response = boundary.dispatch(raw)
    decoded = decode_envelope(response, expected_type="bridge.error")

    assert decoded.payload["code"] == code
    assert "ValueError" not in response


def test_dispatch_bootstrap_success_returns_versioned_envelope(tmp_path: Path) -> None:
    env = dict(os.environ)
    env["PYTHONPATH"] = str(PYTHON_ROOT)
    env.pop("ANKI_MINER_HOME", None)
    script = """
import json, sys
from android_bridge import dispatch
from android_bridge.protocol import encode_message
print(dispatch(encode_message("bootstrap.initialize", {"filesDir": sys.argv[1]})))
"""
    result = subprocess.run(
        [sys.executable, "-c", script, str(tmp_path)],
        check=False,
        capture_output=True,
        text=True,
        env=env,
    )

    assert result.returncode == 0, result.stderr
    envelope = json.loads(result.stdout)
    assert envelope == {
        "schemaVersion": 1,
        "type": "bootstrap.ready",
        "payload": {"home": str(tmp_path)},
    }


def test_dispatch_routes_callback_bearing_video_run_only_with_callbacks(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import android_bridge.mining as mining

    callback = object()
    request = encode_message("mining.video.run", {})
    received: list[tuple[str, object]] = []

    def run_video(raw: str, callbacks: object) -> str:
        received.append((raw, callbacks))
        return encode_message("mining.terminal", {"sentinel": True})

    monkeypatch.setattr(mining, "run_video", run_video)

    missing = decode_envelope(boundary.dispatch(request), expected_type="bridge.error")
    returned = boundary.dispatch(request, callback)

    assert missing.payload["code"] == "missing_callbacks"
    assert decode_envelope(returned, expected_type="mining.terminal").payload == {"sentinel": True}
    assert received == [(request, callback)]


def test_dispatch_log_level_set_raises_only_the_first_party_trees(
    initialized_bridge_home: Path,
) -> None:
    import logging

    from android_bridge import log_context

    try:
        raw = boundary.dispatch(encode_message("diagnostics.loglevel.set", {"level": "debug"}))

        assert decode_envelope(raw, expected_type="diagnostics.loglevel.applied").payload == {"level": "debug"}
        assert logging.getLogger("anki_miner").level == logging.DEBUG
        assert logging.getLogger("android_bridge").level == logging.DEBUG
        # The pins bootstrap installed are the only thing keeping a percent-encoded mined term
        # out of an exported bundle, and root is what would lift the libraries with no pin.
        assert logging.getLogger().level == logging.INFO
        assert logging.getLogger("urllib3.connectionpool").level == logging.ERROR

        back = boundary.dispatch(encode_message("diagnostics.loglevel.set", {"level": "info"}))

        assert decode_envelope(back, expected_type="diagnostics.loglevel.applied").payload == {"level": "info"}
        assert logging.getLogger("anki_miner").level == logging.INFO
    finally:
        log_context.set_first_party_log_level(logging.INFO)


@pytest.mark.parametrize(
    "payload",
    [
        {"level": "trace"},
        {"level": "DEBUG"},
        {"level": ""},
        {"level": True},
        {"level": 10},
        {"level": "debug", "extra": 1},
    ],
)
# The empty payload belongs to the fall-through test below, which stubs
# jobs.shutdown: it is the one payload that reaches the real shutdown() if the
# dispatch branch is ever removed, so asserting it here unguarded would take the
# job registry down mid-session on the very regression it is meant to catch.
def test_dispatch_rejects_a_log_level_outside_the_wire_vocabulary(
    initialized_bridge_home: Path,
    payload: dict[str, object],
) -> None:
    import logging

    raw = boundary.dispatch(encode_message("diagnostics.loglevel.set", payload))

    assert decode_envelope(raw, expected_type="bridge.error").payload["code"] == "invalid_log_level_request"
    assert logging.getLogger("anki_miner").level == logging.INFO


@pytest.mark.parametrize("payload", [{"level": "debug"}, {}])
def test_dispatch_log_level_set_does_not_fall_through_to_registry_shutdown(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
    payload: dict[str, object],
) -> None:
    """The tail of ``_dispatch_validated`` is an unguarded fall-through to ``shutdown()``.

    Declaring a request type supported without routing it therefore does not
    fail -- it tears the job registry down, which looks like a mining run that
    simply stopped.

    The empty payload is the case that matters, and it is the reason this stub
    exists at all: with the branch deleted, ``{"level": "debug"}`` is rejected
    by the tail's own ``_exact_payload(payload, set())`` *before* the shutdown
    import, so a stub guarding only that payload can never fire. ``{}`` passes
    that check and reaches the real ``shutdown()``.
    """

    import logging

    import android_bridge.jobs as jobs

    def shutdown() -> str:
        raise AssertionError("the log level request reached the shutdown fall-through")

    monkeypatch.setattr(jobs, "shutdown", shutdown)
    try:
        raw = boundary.dispatch(encode_message("diagnostics.loglevel.set", payload))
    finally:
        from android_bridge import log_context

        log_context.set_first_party_log_level(logging.INFO)

    decoded = decode_envelope(raw)
    if payload:
        assert decoded.message_type == "diagnostics.loglevel.applied"
    else:
        assert decoded.message_type == "bridge.error"
        assert decoded.payload["code"] == "invalid_log_level_request"


def test_dispatch_log_level_set_requires_bootstrap() -> None:
    """Nothing engine-adjacent runs before ``bootstrap.initialize``, this included.

    A subprocess, because bootstrap state is a process global that any earlier
    test in the session may already have established.
    """

    env = dict(os.environ)
    env["PYTHONPATH"] = str(PYTHON_ROOT)
    env.pop("ANKI_MINER_HOME", None)
    script = """
import json
from android_bridge import dispatch
from android_bridge.protocol import encode_message
print(dispatch(encode_message("diagnostics.loglevel.set", {"level": "debug"})))
"""
    result = subprocess.run(
        [sys.executable, "-c", script],
        check=False,
        capture_output=True,
        text=True,
        env=env,
    )

    assert result.returncode == 0, result.stderr
    envelope = json.loads(result.stdout)
    assert envelope["type"] == "bridge.error"
    assert envelope["payload"]["code"] == "bootstrap_required"


def test_dispatch_routes_paged_curation_control_without_callbacks(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import android_bridge.jobs as jobs

    request = encode_message(
        "curation.page.response",
        {
            "runId": "run_" + "a" * 32,
            "requestId": "curation_" + "b" * 32,
            "pageIndex": 0,
            "selection": [],
        },
    )
    received: list[str] = []

    def submit(raw: str) -> str:
        received.append(raw)
        return encode_message(
            "curation.page.accepted",
            {
                "runId": "run_" + "a" * 32,
                "requestId": "curation_" + "b" * 32,
                "pageIndex": 0,
                "finalPage": False,
            },
        )

    monkeypatch.setattr(jobs, "submit_curation", submit)
    returned = boundary.dispatch(request)

    assert decode_envelope(returned).message_type == "curation.page.accepted"
    assert received == [request]


def test_dispatch_routes_resource_delete_to_its_owning_module(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Both ops must be routed, not merely declared supported.

    A request type listed in ``supported_after_bootstrap`` but absent from a handler
    table falls through to the tail of ``_dispatch_validated``; this pins the routing
    itself rather than the allowlist entry.
    """

    import android_bridge.local_resources as local_resources
    import android_bridge.resources as resources

    seen: list[tuple[str, dict[str, object]]] = []
    monkeypatch.setattr(
        local_resources,
        "delete_local_resource",
        lambda payload: seen.append(("local", dict(payload))) or encode_message("resource.local.deleted", {}),
    )
    monkeypatch.setattr(
        resources,
        "delete_dictionary",
        lambda payload: seen.append(("dictionary", dict(payload))) or encode_message("resource.dictionary.deleted", {}),
    )

    boundary.dispatch(
        encode_message(
            "resource.local.delete",
            {"operationId": "delete-route", "kind": "pitch", "slotId": "fixture"},
        )
    )
    boundary.dispatch(
        encode_message("resource.dictionary.delete", {"operationId": "delete-route", "slotId": "fixture"})
    )

    assert [entry[0] for entry in seen] == ["local", "dictionary"]
    assert seen[0][1]["kind"] == "pitch"
    assert seen[1][1]["slotId"] == "fixture"


def test_dispatch_refuses_a_unidic_delete(initialized_bridge_home: Path) -> None:
    """UniDic is the tokenizer the whole engine depends on; it has no delete."""

    raw = boundary.dispatch(encode_message("resource.unidic.delete", {"operationId": "delete-unidic"}))

    assert decode_envelope(raw, expected_type="bridge.error").payload["code"] == "unsupported_operation"


_PROGRESS_ROUTES = [
    ("resource.audiopack.import", "local_resources", "import_audio_pack"),
    ("resource.frequency.import", "local_resources", "import_frequency"),
    ("resource.knownwords.import", "local_resources", "import_known_words"),
    ("resource.pitch.import", "local_resources", "import_pitch"),
    ("resource.dictionary.import", "resources", "import_dictionary"),
    ("resource.unidic.install", "resources", "install_unidic"),
]


def test_dispatch_forwards_callbacks_to_the_six_progress_handlers(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Task 5 wires these into progress reporting; this only pins the plumbing."""

    import android_bridge.local_resources as local_resources
    import android_bridge.resources as resources

    modules = {"local_resources": local_resources, "resources": resources}
    callback = object()
    seen: dict[str, object] = {}

    def make_handler(request_type: str):
        def handler(payload: dict[str, object], *, callbacks: object | None = None) -> str:
            seen[request_type] = callbacks
            return encode_message("resource.progress.stub", {})

        return handler

    for request_type, module_name, attr in _PROGRESS_ROUTES:
        monkeypatch.setattr(modules[module_name], attr, make_handler(request_type))

    for request_type, _module_name, _attr in _PROGRESS_ROUTES:
        raw = boundary.dispatch(encode_message(request_type, {}), callback)
        assert decode_envelope(raw).message_type == "resource.progress.stub"

    assert seen == {request_type: callback for request_type, _module_name, _attr in _PROGRESS_ROUTES}


def test_dispatch_does_not_forward_callbacks_to_a_non_import_resource_handler(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """``resource.dictionary.list`` is not one of the six import/install types."""

    import android_bridge.resources as resources

    calls: list[dict[str, object]] = []

    def list_dictionaries(payload: dict[str, object]) -> str:
        calls.append(payload)
        return encode_message("resource.dictionary.listed", {"dictionaries": []})

    monkeypatch.setattr(resources, "list_dictionaries", list_dictionaries)

    raw = boundary.dispatch(encode_message("resource.dictionary.list", {}), object())

    # If callbacks were forwarded, the fake above -- which takes no such kwarg
    # -- would raise TypeError, which dispatch turns into an internal_error
    # instead of the listed envelope asserted here.
    assert decode_envelope(raw).message_type == "resource.dictionary.listed"
    assert calls == [{}]


def test_dispatch_import_type_still_works_with_callbacks_none(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import android_bridge.resources as resources

    seen: list[object] = []

    def install_unidic(payload: dict[str, object], *, callbacks: object | None = None) -> str:
        seen.append(callbacks)
        return encode_message("resource.progress.stub", {})

    monkeypatch.setattr(resources, "install_unidic", install_unidic)

    raw = boundary.dispatch(encode_message("resource.unidic.install", {}))

    assert decode_envelope(raw).message_type == "resource.progress.stub"
    assert seen == [None]
