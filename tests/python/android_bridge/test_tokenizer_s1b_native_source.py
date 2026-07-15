from __future__ import annotations

import hashlib
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
    assert not any("dart" in name.lower() and name != "darts.h" for name in names)


def test_original_and_wrapper_licenses_are_separate_exact_provenance_domains() -> None:
    manifest = json.loads(
        (MECAB_ROOT / "source-manifest.json").read_text(encoding="utf-8")
    )
    expected = {
        "mecab": {
            "path": "LICENSE.mecab",
            "sha256": "62f4b23450a9ad40e4db8063d45c1d13c78d07ca27c9ada62c4ca9c11c1f3e7b",
            "copyright": "Taku Kudo and Nippon Telegraph and Telephone Corporation",
            "repository": "https://github.com/taku910/mecab",
            "revision": "61b90ba6e669dc2d7d533d4a80d206f3b31d52b1",
            "source_path": "mecab/BSD",
        },
        "mecab_for_dart": {
            "path": "LICENSE.mecab_for_dart",
            "sha256": "06a8d42f64731d4e96c1fc958c5194b49fe24a003b16f66c7d6800679a4cad0e",
            "copyright": "2024 CaptainDario",
            "repository": "https://github.com/dariyooo/mecab_for_dart",
            "revision": "453d4deb7e3857f32c1ab6c1ced574d9f73a2233",
            "source_path": "LICENSE",
        },
    }

    assert set(manifest["licenses"]) == set(expected)
    assert not (MECAB_ROOT / "LICENSE").exists()
    for name, claim in expected.items():
        record = manifest["licenses"][name]
        content = (MECAB_ROOT / claim["path"]).read_bytes()
        assert hashlib.sha256(content).hexdigest() == claim["sha256"]
        assert record["path"] == claim["path"]
        assert record["sha256"] == claim["sha256"]
        assert record["copyright"] == claim["copyright"]
        assert record["spdx"] == "BSD-3-Clause"
        assert record["source"] == {
            "path": claim["source_path"],
            "repository": claim["repository"],
            "revision": claim["revision"],
        }
    assert (
        (MECAB_ROOT / "LICENSE.mecab")
        .read_text(encoding="utf-8")
        .startswith(
            "Copyright (c) 2001-2008, Taku Kudo\n"
            "Copyright (c) 2004-2008, Nippon Telegraph and Telephone Corporation\n"
        )
    )
    assert (
        (MECAB_ROOT / "LICENSE.mecab_for_dart")
        .read_text(encoding="utf-8")
        .startswith("Copyright 2024 CaptainDario\n")
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


def test_gradle_wires_locked_cmake_and_s1b_uses_engine_tagger_seam() -> None:
    gradle = (PROJECT_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    adapter = (
        PROJECT_ROOT / "app/src/main/python/android_bridge/tokenizer_s1b.py"
    ).read_text(encoding="utf-8")
    vendored_tagger = (
        PROJECT_ROOT / "app/src/main/python/anki_miner/services/tagger.py"
    ).read_text(encoding="utf-8")
    selection = (
        PROJECT_ROOT / "app/src/main/python/android_bridge/tokenizer_selection.py"
    ).read_text(encoding="utf-8")
    harness = (
        PROJECT_ROOT / "app/src/debug/python/tokenizer_s1b_instrumented.py"
    ).read_text(encoding="utf-8")
    instrumentation_selection = (
        PROJECT_ROOT / "app/src/debug/python/tokenizer_instrumented_selection.py"
    ).read_text(encoding="utf-8")

    assert 'path = file("src/main/cpp/CMakeLists.txt")' in gradle
    assert 'version = "3.22.1"' in gradle
    assert "create_s1b_tagger" in adapter
    assert "import fugashi" not in vendored_tagger
    assert "configure_tagger_factory" in vendored_tagger
    assert "create_s1b_tagger" in selection
    compact_harness = " ".join(harness.split())
    assert (
        'acquire_tagger_for_instrumentation( "s1b", registration )'
        in compact_harness
    )
    assert "configure_tokenizer_backend(backend)" in instrumentation_selection
    assert "get_shared_tagger()" in instrumentation_selection


def test_android_build_gate_requires_the_exact_s1b_library_for_both_abis() -> None:
    build = (PROJECT_ROOT / "tools/tokenizer/build-s1b-android.sh").read_text(
        encoding="utf-8"
    )

    assert "--require-entry lib/x86_64/libanki_miner_mecab.so" in build
    assert "--require-entry lib/arm64-v8a/libanki_miner_mecab.so" in build
