from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys

PROJECT_ROOT = Path(__file__).resolve().parents[3]
MECAB_ROOT = PROJECT_ROOT / "third_party/mecab"
CPP_ROOT = PROJECT_ROOT / "app/src/main/cpp"


def test_mecab_source_subset_is_byte_pinned_and_contains_no_flutter() -> None:
    subprocess.run(
        [
            sys.executable,
            str(PROJECT_ROOT / "tools/tokenizer/vendor_s1b_mecab.py"),
            "--check",
        ],
        check=True,
    )
    manifest = json.loads(
        (MECAB_ROOT / "source-manifest.json").read_text(encoding="utf-8")
    )

    assert manifest["upstream"]["tag"] == "d2.0.0"
    assert manifest["upstream"]["revision"] == (
        "453d4deb7e3857f32c1ab6c1ced574d9f73a2233"
    )
    names = set(manifest["files"])
    assert "dart_ffi.cpp" not in names
    assert not any("flutter" in name.lower() for name in names)
    assert not any(
        "dart" in name.lower() and name != "darts.h" for name in names
    )


def test_cmake_forces_mmap_locked_ndk_abis_and_16k_alignment() -> None:
    cmake = (CPP_ROOT / "CMakeLists.txt").read_text(encoding="utf-8")
    config = (MECAB_ROOT / "src/config.h").read_text(encoding="utf-8")

    assert "HAVE_MMAP=1" in cmake
    assert "HAVE_SYS_MMAN_H=1" in cmake
    assert "HAVE_SYS_TYPES_H=1" in cmake
    assert "28.2.13676358" in cmake
    assert "arm64-v8a|x86_64" in cmake
    assert "max-page-size=16384" in cmake
    assert "common-page-size=16384" in cmake
    assert "#define HAVE_MMAP" not in config
    assert "#define HAVE_SYS_MMAN_H" not in config


def test_native_api_keeps_complete_argv_and_copies_frozen_wire_fields() -> None:
    wire = (CPP_ROOT / "tokenizer_wire.cpp").read_text(encoding="utf-8")
    jni = (CPP_ROOT / "tokenizer_jni.cpp").read_text(encoding="utf-8")

    for required in (
        'argv[0] != "anki_miner"',
        'argv[1] != "-C"',
        "mecab_new(",
        "node.rlength",
        "node.posid",
        "node.char_type",
        "node.stat",
        "mecab_node_t::lcAttr",
        "strnlen(node->feature",
        "std::memcmp",
    ):
        assert required in wire
    assert "jlong" not in wire + jni
    assert "node->next" in wire
    assert "nativeTokenize" in jni
    assert "nativeDictionaryFilename" in jni


def test_gradle_wires_locked_cmake_without_selecting_s1b_for_engine() -> None:
    gradle = (PROJECT_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    adapter = (
        PROJECT_ROOT
        / "app/src/main/python/android_bridge/tokenizer_s1b.py"
    ).read_text(encoding="utf-8")
    vendored_tagger = (
        PROJECT_ROOT / "app/src/main/python/anki_miner/services/tagger.py"
    ).read_text(encoding="utf-8")

    assert 'path = file("src/main/cpp/CMakeLists.txt")' in gradle
    assert 'version = "3.22.1"' in gradle
    assert "create_s1b_tagger" in adapter
    assert "android_bridge.tokenizer_s1b" not in vendored_tagger
