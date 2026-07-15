from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

import android_bridge
import android_bridge.boundary as boundary
from android_bridge.protocol import decode_envelope, encode_message

PROJECT_ROOT = Path(__file__).resolve().parents[3]
PYTHON_ROOT = PROJECT_ROOT / "app" / "src" / "main" / "python"
DESKTOP_ROOT = Path("/home/light/Projects/anki_miner")


def test_package_exposes_one_guarded_kotlin_entry_point() -> None:
    assert android_bridge.__all__ == ["BRIDGE_SCHEMA_VERSION", "dispatch"]
    assert android_bridge.dispatch is boundary.dispatch


def test_dispatch_serializes_malformed_and_protocol_errors() -> None:
    malformed = decode_envelope(
        boundary.dispatch("not json"), expected_type="bridge.error"
    )
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


def test_dispatch_bootstrap_success_returns_versioned_envelope(tmp_path: Path) -> None:
    env = dict(os.environ)
    env["PYTHONPATH"] = os.pathsep.join((str(PYTHON_ROOT), str(DESKTOP_ROOT)))
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
