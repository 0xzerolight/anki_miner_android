from __future__ import annotations

import json
import sys
import zipfile
from pathlib import Path
from types import SimpleNamespace

import pytest

PROJECT_ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(PROJECT_ROOT / "tools/tokenizer"))
sys.path.insert(0, str(PROJECT_ROOT / "tools/engine-sync"))

from android_bridge.tokenizer_contract import UNIDIC_FEATURE_FIELDS  # noqa: E402
from android_bridge.unidic_resource import UNIDIC_REQUIRED_FILES  # noqa: E402
from engine_sync.golden_contract import sha256_tree  # noqa: E402
from package_s1b_test_unidic import package_dictionary  # noqa: E402
from verify_s1b_host_parity import (  # noqa: E402
    _actual_token,
    verify_dictionary_provenance,
)


def _dictionary(root: Path, *, suffix: str = "") -> None:
    root.mkdir()
    for index, name in enumerate(UNIDIC_REQUIRED_FILES):
        (root / name).write_bytes(f"{name}:{index}:{suffix}".encode())


def _golden(root: Path) -> dict[str, object]:
    return {
        "provenance": {
            "data": {
                "assets_sha256": {"unidic_dicdir": sha256_tree(root)},
            },
        },
    }


def test_dictionary_provenance_rejects_mutated_and_arbitrary_trees(
    tmp_path: Path,
) -> None:
    trusted = tmp_path / "trusted"
    arbitrary = tmp_path / "arbitrary"
    _dictionary(trusted)
    _dictionary(arbitrary, suffix="other")
    document = _golden(trusted)

    assert verify_dictionary_provenance(trusted, document) == sha256_tree(trusted)
    with pytest.raises(RuntimeError, match="provenance mismatch"):
        verify_dictionary_provenance(arbitrary, document)

    (trusted / "sys.dic").write_bytes(b"mutated")
    with pytest.raises(RuntimeError, match="provenance mismatch"):
        verify_dictionary_provenance(trusted, document)


def test_dictionary_provenance_rejects_incomplete_and_linked_trees(
    tmp_path: Path,
) -> None:
    trusted = tmp_path / "trusted"
    _dictionary(trusted)
    document = _golden(trusted)

    (trusted / "char.bin").unlink()
    with pytest.raises(RuntimeError, match="incomplete"):
        verify_dictionary_provenance(trusted, document)

    (trusted / "char.bin").symlink_to(trusted / "sys.dic")
    with pytest.raises(RuntimeError, match="cannot be verified"):
        verify_dictionary_provenance(trusted, document)


def test_external_dictionary_zip_is_deterministic_and_never_bundled(
    tmp_path: Path,
) -> None:
    trusted = tmp_path / "trusted"
    _dictionary(trusted)
    document = _golden(trusted)
    golden = tmp_path / "golden.json"
    golden.write_text(json.dumps(document), encoding="utf-8")
    first = tmp_path / "first.zip"
    second = tmp_path / "second.zip"

    package_dictionary(trusted, golden, first)
    package_dictionary(trusted, golden, second)

    assert first.read_bytes() == second.read_bytes()
    with zipfile.ZipFile(first) as archive:
        assert archive.namelist() == sorted(UNIDIC_REQUIRED_FILES)
        assert all(info.compress_type == zipfile.ZIP_STORED for info in archive.infolist())
    asset_files = list((PROJECT_ROOT / "app/src").glob("**/assets/**/*"))
    assert not any(path.name in UNIDIC_REQUIRED_FILES for path in asset_files)


def test_golden_serialization_does_not_mutate_engine_star_semantics() -> None:
    feature_values = dict.fromkeys(UNIDIC_FEATURE_FIELDS)
    feature_values["pos1"] = "*"
    token = SimpleNamespace(
        surface="猫",
        feature=SimpleNamespace(**feature_values),
        is_unk=False,
        codepoint_start=0,
        codepoint_end=1,
        utf16_start=0,
        utf16_end=1,
    )

    serialized = _actual_token(token)

    assert serialized["features"]["pos1"] is None
    assert serialized["features"]["pos2"] is None
    assert token.feature.pos1 == "*"
    assert token.feature.pos2 is None


def test_android_harness_crosses_all_layers_with_external_provisioning() -> None:
    kotlin = (
        PROJECT_ROOT / "app/src/androidTest/kotlin/com/ankiminer/android/tokenizer/"
        "MecabNativeTokenizerInstrumentedTest.kt"
    ).read_text(encoding="utf-8")
    shared_runtime = (
        PROJECT_ROOT / "app/src/androidTest/kotlin/com/ankiminer/android/PythonInstrumentationRuntime.kt"
    ).read_text(encoding="utf-8")
    python = (PROJECT_ROOT / "app/src/debug/python/tokenizer_s1b_instrumented.py").read_text(encoding="utf-8")
    selection = (PROJECT_ROOT / "app/src/debug/python/tokenizer_instrumented_selection.py").read_text(encoding="utf-8")
    gradle = (PROJECT_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    environment = (PROJECT_ROOT / "scripts/android-env.sh").read_text(encoding="utf-8")
    provision = (PROJECT_ROOT / "scripts/provision-s1b-test-unidic.sh").read_text(encoding="utf-8")

    assert 'assets.srcDir(rootProject.file("golden"))' in gradle
    assert "BuildConfig.S1B_TEST_UNIDIC_ARCHIVE" in kotlin
    assert 'getModule("tokenizer_s1b_instrumented")' in kotlin
    assert "PythonInstrumentationRuntime.stageExternalUniDic" in kotlin
    assert "ZipInputStream" in shared_runtime
    compact_python = " ".join(python.split())
    assert 'acquire_tagger_for_instrumentation( "s1b", registration )' in compact_python
    assert "configure_tokenizer_backend(backend)" in selection
    assert "get_shared_tagger()" in selection
    assert "debug_direct_fallback_after_" in selection
    assert 'Path("/proc/self/maps")' in python
    assert 'registration.dicdir / "matrix.bin"' in python
    assert '"unidic_feature_fields"' in python
    assert 'case["id"] == "astral-oov-offsets"' in python
    assert "ANDROID_S1B_TEST_UNIDIC_ARCHIVE" in environment
    assert "package_s1b_test_unidic.py" in provision
