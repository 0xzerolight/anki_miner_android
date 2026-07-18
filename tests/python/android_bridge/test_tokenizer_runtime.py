from __future__ import annotations

from pathlib import Path

import pytest

import android_bridge.bootstrap as bootstrap
import android_bridge.tokenizer_runtime as tokenizer_runtime
import android_bridge.tokenizer_selection as tokenizer_selection
import android_bridge.unidic_resource as unidic_resource
from android_bridge import boundary
from android_bridge.protocol import decode_envelope, encode_message
from android_bridge.unidic_resource import (
    UNIDIC_REQUIRED_FILES,
    calculate_unidic_tree_sha256,
    require_registered_unidic,
)


@pytest.fixture(autouse=True)
def _reset_tokenizer_process_state(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(unidic_resource, "_registration", None)
    monkeypatch.setattr(tokenizer_selection, "_selected_backend", None)
    monkeypatch.setattr(tokenizer_runtime, "_configuration_requires_restart", False)


def _make_dicdir(parent: Path, name: str = "dicdir") -> Path:
    root = parent / name
    root.mkdir()
    for filename in UNIDIC_REQUIRED_FILES:
        (root / filename).write_bytes(f"fixture:{filename}\n".encode())
    metadata = root / "metadata"
    metadata.mkdir()
    (metadata / "COPYING").write_text("BSD-3-Clause\n", encoding="utf-8")
    return root


def _payload(
    dic_dir: object,
    *,
    resource_id: object = "unidic-lite-1.0.8",
    tree_sha256: object = "0" * 64,
    backend: object = "s1a",
) -> dict[str, object]:
    return {
        "dicDir": dic_dir,
        "resourceId": resource_id,
        "treeSha256": tree_sha256,
        "backend": backend,
    }


def _dispatch(payload: dict[str, object]) -> str:
    return boundary.dispatch(encode_message("tokenizer.configure", payload))


def test_tokenizer_configuration_requires_bootstrap_before_any_mutation(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    del initialized_bridge_home
    registrations: list[object] = []
    monkeypatch.setattr(bootstrap, "_initialized_home", None)
    monkeypatch.setattr(
        tokenizer_runtime,
        "register_unidic",
        lambda *args, **kwargs: registrations.append((args, kwargs)),
    )

    response = decode_envelope(_dispatch(_payload("/unidic")), expected_type="bridge.error")

    assert response.payload["code"] == "bootstrap_required"
    assert registrations == []


@pytest.mark.parametrize(
    "payload",
    [
        {
            "dicDir": "/unidic",
            "resourceId": "unidic-lite-1.0.8",
            "treeSha256": "0" * 64,
        },
        {
            **_payload("/unidic"),
            "unknown": True,
        },
    ],
)
def test_tokenizer_configuration_requires_exact_fields_before_mutation(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
    payload: dict[str, object],
) -> None:
    del initialized_bridge_home
    registrations: list[object] = []
    monkeypatch.setattr(
        tokenizer_runtime,
        "register_unidic",
        lambda *args, **kwargs: registrations.append((args, kwargs)),
    )

    response = decode_envelope(_dispatch(payload), expected_type="bridge.error")

    assert response.payload["code"] == "invalid_tokenizer_request"
    assert registrations == []


@pytest.mark.parametrize(
    ("payload", "expected_code"),
    [
        (_payload(7), "invalid_unidic_path"),
        (_payload("relative/unidic"), "invalid_unidic_path"),
        (_payload("/unidic\x00hidden"), "invalid_unidic_path"),
        (
            _payload("/unidic", resource_id="unidic lite."),
            "invalid_unidic_identity",
        ),
        (
            _payload("/unidic", tree_sha256="A" * 64),
            "invalid_unidic_identity",
        ),
        (_payload("/unidic", backend="s1b"), "invalid_tokenizer_backend"),
        (_payload("/unidic", backend=None), "invalid_tokenizer_backend"),
    ],
)
def test_cheap_tokenizer_fields_and_s1a_only_are_checked_before_registration(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
    payload: dict[str, object],
    expected_code: str,
) -> None:
    del initialized_bridge_home
    registrations: list[object] = []
    monkeypatch.setattr(
        tokenizer_runtime,
        "register_unidic",
        lambda *args, **kwargs: registrations.append((args, kwargs)),
    )

    response = decode_envelope(_dispatch(payload), expected_type="bridge.error")

    assert response.payload["code"] == expected_code
    assert registrations == []


def test_tokenizer_configuration_returns_canonical_ready_payload(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    del initialized_bridge_home
    root = _make_dicdir(tmp_path)
    tree_sha256 = calculate_unidic_tree_sha256(root)
    selected: list[str] = []

    def configure(backend: str) -> str:
        selected.append(backend)
        return backend

    monkeypatch.setattr(tokenizer_runtime, "configure_tokenizer_backend", configure)

    response = decode_envelope(
        _dispatch(_payload(str(root), tree_sha256=tree_sha256)),
        expected_type="tokenizer.ready",
    )

    assert response.payload == {
        "backend": "s1a",
        "resourceId": "unidic-lite-1.0.8",
        "dicDir": str(root.resolve()),
        "treeSha256": tree_sha256,
        "fileCount": len(UNIDIC_REQUIRED_FILES) + 1,
        "totalBytes": sum(path.stat().st_size for path in root.rglob("*") if path.is_file()),
    }
    assert selected == ["s1a"]


def test_exact_tokenizer_configuration_is_idempotent(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    del initialized_bridge_home
    root = _make_dicdir(tmp_path)
    tree_sha256 = calculate_unidic_tree_sha256(root)
    selected: list[str] = []

    def configure(backend: str) -> str:
        selected.append(backend)
        return backend

    monkeypatch.setattr(tokenizer_runtime, "configure_tokenizer_backend", configure)
    request = _payload(str(root), tree_sha256=tree_sha256)

    first = _dispatch(request)
    registration = require_registered_unidic()
    second = _dispatch(request)

    assert first == second
    assert decode_envelope(second, expected_type="tokenizer.ready").payload["backend"] == "s1a"
    assert require_registered_unidic() is registration
    assert selected == ["s1a", "s1a"]


def test_provenance_mismatch_uses_stable_redacted_contract_error(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    del initialized_bridge_home
    root = _make_dicdir(tmp_path)
    monkeypatch.setattr(
        tokenizer_runtime,
        "configure_tokenizer_backend",
        lambda backend: pytest.fail(f"unexpected backend configuration: {backend}"),
    )

    raw = _dispatch(_payload(str(root), tree_sha256="0" * 64))
    response = decode_envelope(raw, expected_type="bridge.error")

    assert response.payload == {
        "code": "unidic_provenance_mismatch",
        "message": "Tokenizer resource configuration was rejected",
        "requestType": "tokenizer.configure",
    }
    assert str(root) not in raw
    assert "trusted resource catalog" not in raw


def test_different_registration_is_rejected_without_identity_leak(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    del initialized_bridge_home
    first_root = _make_dicdir(tmp_path, "one")
    second_root = _make_dicdir(tmp_path, "two")
    monkeypatch.setattr(tokenizer_runtime, "configure_tokenizer_backend", lambda backend: backend)

    first = _dispatch(
        _payload(
            str(first_root),
            resource_id="unidic-one",
            tree_sha256=calculate_unidic_tree_sha256(first_root),
        )
    )
    raw = _dispatch(
        _payload(
            str(second_root),
            resource_id="unidic-two",
            tree_sha256=calculate_unidic_tree_sha256(second_root),
        )
    )
    response = decode_envelope(raw, expected_type="bridge.error")

    assert decode_envelope(first, expected_type="tokenizer.ready").payload["resourceId"] == "unidic-one"
    assert response.payload == {
        "code": "unidic_already_registered",
        "message": "Tokenizer resource configuration was rejected",
        "requestType": "tokenizer.configure",
    }
    assert str(first_root) not in raw
    assert str(second_root) not in raw
    assert require_registered_unidic().resource_id == "unidic-one"


def test_post_registration_failure_poison_requires_process_restart(
    initialized_bridge_home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    del initialized_bridge_home
    root = _make_dicdir(tmp_path)
    tree_sha256 = calculate_unidic_tree_sha256(root)
    attempts: list[str] = []

    def explode(backend: str) -> str:
        attempts.append(backend)
        raise RuntimeError(f"secret backend failure at {root}")

    monkeypatch.setattr(tokenizer_runtime, "configure_tokenizer_backend", explode)
    raw = _dispatch(_payload(str(root), tree_sha256=tree_sha256))
    response = decode_envelope(raw, expected_type="bridge.error")

    assert response.payload == {
        "code": "tokenizer_restart_required",
        "message": ("Tokenizer setup cannot continue until the Python process is restarted"),
        "requestType": "tokenizer.configure",
    }
    assert str(root) not in raw
    assert "RuntimeError" not in raw
    assert "secret" not in raw
    assert require_registered_unidic().tree_sha256 == tree_sha256

    monkeypatch.setattr(
        tokenizer_runtime,
        "configure_tokenizer_backend",
        lambda backend: attempts.append(f"retry:{backend}") or backend,
    )
    retried = decode_envelope(
        _dispatch(_payload(str(root), tree_sha256=tree_sha256)),
        expected_type="bridge.error",
    )
    assert retried.payload == response.payload
    assert attempts == ["s1a"]


def test_registration_internal_error_is_redacted_by_guarded_boundary(
    initialized_bridge_home: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    del initialized_bridge_home

    def explode(*args: object, **kwargs: object) -> object:
        del args, kwargs
        raise RuntimeError("secret filesystem failure at /private/unidic")

    monkeypatch.setattr(tokenizer_runtime, "register_unidic", explode)
    raw = _dispatch(_payload("/unidic"))
    response = decode_envelope(raw, expected_type="bridge.error")

    assert response.payload == {
        "code": "internal_error",
        "message": "Internal bridge failure",
        "requestType": "tokenizer.configure",
    }
    assert "secret" not in raw
    assert "private" not in raw
    assert "RuntimeError" not in raw
    assert tokenizer_runtime._configuration_requires_restart is False
