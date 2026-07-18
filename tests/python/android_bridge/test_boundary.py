from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import android_bridge
import android_bridge.boundary as boundary
import pytest
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
) -> None:
    def explode(*_: object) -> str:
        raise RuntimeError("secret filesystem detail")

    monkeypatch.setattr(boundary, "_dispatch_validated", explode)
    raw = boundary.dispatch(encode_message("job.cancel", {"runId": "run_" + "0" * 32}))
    decoded = decode_envelope(raw, expected_type="bridge.error")

    assert decoded.payload == {
        "code": "internal_error",
        "message": "Internal bridge failure",
        "requestType": "job.cancel",
    }
    assert "secret" not in raw
    assert "RuntimeError" not in raw


def test_dispatch_does_not_swallow_process_control_exceptions(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def stop(*_: object) -> str:
        raise SystemExit(7)

    monkeypatch.setattr(boundary, "_dispatch_validated", stop)

    with pytest.raises(SystemExit) as stopped:
        boundary.dispatch(encode_message("job.cancel", {"runId": "run_" + "0" * 32}))
    assert stopped.value.code == 7


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
