from __future__ import annotations

import hashlib
import os
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import android_bridge.unidic_resource as unidic_resource
import pytest
from android_bridge.tokenizer_contract import TokenizerContractError
from android_bridge.unidic_resource import (
    UNIDIC_REQUIRED_FILES,
    calculate_unidic_tree_sha256,
    register_unidic,
    require_registered_unidic,
    validate_loaded_dictionary_filenames,
)


@pytest.fixture(autouse=True)
def _clear_process_registration(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(unidic_resource, "_registration", None)


def _make_dicdir(parent: Path, name: str = "dicdir") -> Path:
    root = parent / name
    root.mkdir()
    for filename in UNIDIC_REQUIRED_FILES:
        (root / filename).write_bytes(f"fixture:{filename}\n".encode())
    nested = root / "metadata"
    nested.mkdir()
    (nested / "COPYING").write_text("BSD-3-Clause\n", encoding="utf-8")
    return root


def _manual_tree_hash(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        relative = path.relative_to(root).as_posix().encode("utf-8")
        content = path.read_bytes()
        digest.update(len(relative).to_bytes(8, "big"))
        digest.update(relative)
        digest.update(len(content).to_bytes(8, "big"))
        digest.update(content)
    return digest.hexdigest()


def test_registration_verifies_tree_and_freezes_explicit_mecab_paths(
    tmp_path: Path,
) -> None:
    root = _make_dicdir(tmp_path)
    expected_hash = calculate_unidic_tree_sha256(root)

    registered = register_unidic(
        root,
        resource_id="unidic-lite-1.0.8",
        expected_tree_sha256=expected_hash,
    )

    assert registered is require_registered_unidic()
    assert registered.dicdir == root.resolve()
    assert registered.tree_sha256 == _manual_tree_hash(root)
    assert registered.file_count == len(UNIDIC_REQUIRED_FILES) + 1
    assert registered.total_bytes == sum(path.stat().st_size for path in root.rglob("*") if path.is_file())
    assert registered.mecab_arguments == (
        "-r",
        str(root / "mecabrc"),
        "-d",
        str(root),
    )
    assert registered.mecab_new_argv == (
        "anki_miner",
        "-C",
        "-r",
        str(root / "mecabrc"),
        "-d",
        str(root),
    )


def test_identical_registration_is_idempotent_but_switching_is_forbidden(
    tmp_path: Path,
) -> None:
    root = _make_dicdir(tmp_path, "one")
    other = _make_dicdir(tmp_path, "two")
    expected_hash = calculate_unidic_tree_sha256(root)
    first = register_unidic(root, resource_id="unidic-v1", expected_tree_sha256=expected_hash)

    assert register_unidic(root, resource_id="unidic-v1", expected_tree_sha256=expected_hash) is first
    with pytest.raises(TokenizerContractError) as changed:
        register_unidic(
            other,
            resource_id="unidic-v2",
            expected_tree_sha256=calculate_unidic_tree_sha256(other),
        )
    assert changed.value.code == "unidic_already_registered"


def test_identical_request_rejects_a_replaced_dictionary_tree(
    tmp_path: Path,
) -> None:
    root = _make_dicdir(tmp_path, "live")
    expected_hash = calculate_unidic_tree_sha256(root)
    register_unidic(root, resource_id="unidic-v1", expected_tree_sha256=expected_hash)
    retired = tmp_path / "retired"
    root.rename(retired)
    replacement = _make_dicdir(tmp_path, "replacement")
    replacement.rename(root)
    assert calculate_unidic_tree_sha256(root) == expected_hash

    with pytest.raises(TokenizerContractError) as replaced:
        register_unidic(
            root,
            resource_id="unidic-v1",
            expected_tree_sha256=expected_hash,
        )

    assert replaced.value.code == "unidic_tree_replaced"


def test_registration_rejects_wrong_provenance_without_freezing_it(
    tmp_path: Path,
) -> None:
    root = _make_dicdir(tmp_path)

    with pytest.raises(TokenizerContractError) as mismatch:
        register_unidic(root, resource_id="unidic-v1", expected_tree_sha256="0" * 64)
    assert mismatch.value.code == "unidic_provenance_mismatch"

    with pytest.raises(TokenizerContractError) as missing:
        require_registered_unidic()
    assert missing.value.code == "unidic_registration_required"


@pytest.mark.parametrize(
    ("resource_id", "tree_hash"),
    [
        ("", "0" * 64),
        ("spaces are invalid", "0" * 64),
        ("valid", "ABC" + "0" * 61),
        ("valid", "not-a-hash"),
    ],
)
def test_registration_rejects_untrusted_identity_syntax(tmp_path: Path, resource_id: str, tree_hash: str) -> None:
    root = _make_dicdir(tmp_path)

    with pytest.raises(TokenizerContractError) as error:
        register_unidic(root, resource_id=resource_id, expected_tree_sha256=tree_hash)
    assert error.value.code == "invalid_unidic_identity"


def test_tree_validation_rejects_missing_files_symlinks_and_special_files(
    tmp_path: Path,
) -> None:
    missing = _make_dicdir(tmp_path, "missing")
    (missing / "sys.dic").unlink()
    with pytest.raises(TokenizerContractError, match="missing"):
        calculate_unidic_tree_sha256(missing)

    linked = _make_dicdir(tmp_path, "linked")
    (linked / "alias").symlink_to(linked / "sys.dic")
    with pytest.raises(TokenizerContractError, match="symlink"):
        calculate_unidic_tree_sha256(linked)

    special = _make_dicdir(tmp_path, "special")
    os.mkfifo(special / "fifo")
    with pytest.raises(TokenizerContractError, match="non-file"):
        calculate_unidic_tree_sha256(special)


def test_registration_rejects_relative_and_symlinked_roots(tmp_path: Path) -> None:
    root = _make_dicdir(tmp_path)

    with pytest.raises(TokenizerContractError) as relative:
        calculate_unidic_tree_sha256(Path("relative/dicdir"))
    assert relative.value.code == "invalid_unidic_path"

    alias = tmp_path / "alias"
    alias.symlink_to(root, target_is_directory=True)
    with pytest.raises(TokenizerContractError, match="symlink"):
        calculate_unidic_tree_sha256(alias)


def test_loaded_dictionary_must_be_exact_registered_sys_dic(tmp_path: Path) -> None:
    root = _make_dicdir(tmp_path)
    registered = register_unidic(
        root,
        resource_id="unidic-v1",
        expected_tree_sha256=calculate_unidic_tree_sha256(root),
    )

    validate_loaded_dictionary_filenames([registered.sys_dic])

    with pytest.raises(TokenizerContractError) as none_loaded:
        validate_loaded_dictionary_filenames([])
    assert none_loaded.value.code == "invalid_loaded_dictionary"

    with pytest.raises(TokenizerContractError) as extra_loaded:
        validate_loaded_dictionary_filenames([registered.sys_dic, registered.dicdir / "unk.dic"])
    assert extra_loaded.value.code == "invalid_loaded_dictionary"

    with pytest.raises(TokenizerContractError) as wrong:
        validate_loaded_dictionary_filenames([registered.dicdir / "unk.dic"])
    assert wrong.value.code == "unidic_provenance_mismatch"


def test_competing_registrations_are_atomic(tmp_path: Path) -> None:
    one = _make_dicdir(tmp_path, "one")
    two = _make_dicdir(tmp_path, "two")
    requests = (
        (one, "unidic-one", calculate_unidic_tree_sha256(one)),
        (two, "unidic-two", calculate_unidic_tree_sha256(two)),
    )

    def register(request: tuple[Path, str, str]) -> object:
        path, resource_id, tree_hash = request
        try:
            return register_unidic(path, resource_id=resource_id, expected_tree_sha256=tree_hash)
        except TokenizerContractError as exc:
            return exc

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(register, requests))

    winners = [result for result in results if not isinstance(result, Exception)]
    losers = [result for result in results if isinstance(result, TokenizerContractError)]
    assert len(winners) == 1
    assert len(losers) == 1
    assert losers[0].code == "unidic_already_registered"
    assert require_registered_unidic() is winners[0]
