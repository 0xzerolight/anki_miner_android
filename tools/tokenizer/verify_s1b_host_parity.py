#!/usr/bin/env python3
"""Compare the native S1b wire against committed desktop token goldens."""

from __future__ import annotations

import argparse
import json
import re
import struct
import subprocess
import sys
import types
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT / "app/src/main/python"))
sys.path.insert(0, str(PROJECT_ROOT / "tools/engine-sync"))

from android_bridge.tokenizer_contract import (  # noqa: E402
    UNIDIC_FEATURE_FIELDS,
)
from android_bridge.tokenizer_s1b import create_s1b_tagger  # noqa: E402
from android_bridge.unidic_resource import (  # noqa: E402
    UNIDIC_REQUIRED_FILES,
    RegisteredUniDic,
)
from engine_sync.golden_contract import (  # noqa: E402
    GoldenContractError,
    sha256_tree,
)

_SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
_MAX_WIRE_BYTES = (1 << 31) - 1


def _read_exact(stream: object, size: int) -> bytes:
    read = stream.read
    output = read(size)
    if len(output) != size:
        raise RuntimeError("native parity driver returned a truncated frame")
    return output


def _golden_feature(value: object) -> object:
    """Apply the canonical JSON rule without changing the engine token."""

    return None if value == "*" else value


def _actual_token(token: object) -> dict[str, object]:
    feature = token.feature
    return {
        "surface": token.surface,
        "features": {name: _golden_feature(getattr(feature, name)) for name in UNIDIC_FEATURE_FIELDS},
        "is_unknown": token.is_unk,
        "offsets": {
            "codepoint_start": token.codepoint_start,
            "codepoint_end": token.codepoint_end,
            "utf16_start": token.utf16_start,
            "utf16_end": token.utf16_end,
        },
    }


class _NativeDriverApi:
    """NativeTokenizerApi implementation backed by the compiled host driver."""

    def __init__(self, driver: Path, dicdir: Path) -> None:
        self._dicdir = dicdir
        self._argv = (
            "anki_miner",
            "-C",
            "-r",
            str(dicdir / "mecabrc"),
            "-d",
            str(dicdir),
        )
        self._process = subprocess.Popen(
            [str(driver), str(dicdir)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
        )
        if self._process.stdin is None or self._process.stdout is None:
            self.abort()
            raise RuntimeError("native parity driver has no binary pipes")

    def _require_argv(self, argv: tuple[str, ...]) -> None:
        if argv != self._argv:
            raise RuntimeError("S1b adapter changed the verified native argv")

    def loaded_dictionary_filenames(self, argv: tuple[str, ...]) -> tuple[str, ...]:
        self._require_argv(argv)
        return (str(self._dicdir / "sys.dic"),)

    def tokenize(self, input_utf8: bytes, argv: tuple[str, ...]) -> bytes:
        self._require_argv(argv)
        if len(input_utf8) > 0xFFFFFFFF:
            raise RuntimeError("native parity input exceeds its framing domain")
        process_stdin = self._process.stdin
        process_stdout = self._process.stdout
        if process_stdin is None or process_stdout is None:
            raise RuntimeError("native parity driver is closed")
        try:
            process_stdin.write(struct.pack("<I", len(input_utf8)) + input_utf8)
            process_stdin.flush()
        except BrokenPipeError as exc:
            raise RuntimeError("native parity driver exited before parsing") from exc
        wire_size = struct.unpack("<I", _read_exact(process_stdout, 4))[0]
        if wire_size > _MAX_WIRE_BYTES:
            raise RuntimeError("native parity driver returned an oversized frame")
        return _read_exact(process_stdout, wire_size)

    def close(self) -> None:
        process_stdin = self._process.stdin
        if process_stdin is not None and not process_stdin.closed:
            process_stdin.close()
        if self._process.wait() != 0:
            raise RuntimeError("native parity driver failed")

    def abort(self) -> None:
        if self._process.poll() is None:
            self._process.kill()
            self._process.wait()


def _load_engine_compound_pipeline() -> tuple[object, object, object, object, object, object]:
    """Load the vendored pure token pipeline without broad service imports."""

    import anki_miner  # noqa: PLC0415

    package_name = "anki_miner.services"
    if package_name not in sys.modules:
        services = types.ModuleType(package_name)
        services.__package__ = package_name
        services.__path__ = [str(Path(anki_miner.__file__).parent / "services")]
        sys.modules[package_name] = services

    from anki_miner.models.word import select_mined_form  # noqa: PLC0415
    from anki_miner.services.compound_matcher import (  # noqa: PLC0415
        CompoundDictionaryMatcher,
    )
    from anki_miner.services.morphology import (  # noqa: PLC0415
        TokenInclusionRule,
        extract_lemma,
        merge_compound_suffixes,
        mining_base,
    )

    return (
        CompoundDictionaryMatcher,
        TokenInclusionRule,
        extract_lemma,
        merge_compound_suffixes,
        mining_base,
        select_mined_form,
    )


def _verify_engine_star_semantics(tagged_cases: dict[str, list[object]]) -> None:
    tokens = tagged_cases["astral-oov-offsets"]
    unknown_tokens = [token for token in tokens if token.is_unk]
    if len(unknown_tokens) != 1:
        raise RuntimeError("astral-oov-offsets must contain exactly one unknown token")
    oov = unknown_tokens[0]
    if oov.feature.pos3 != "*":
        raise RuntimeError("S1b collapsed MeCab's explicit star before the engine")
    if oov.feature.lForm is not None:
        raise RuntimeError("S1b did not preserve an actually absent trailing field")


def _verify_seeded_compound(
    tagger: object,
    document: dict[str, object],
) -> None:
    (
        matcher_type,
        rule_type,
        extract_lemma,
        merge_compound_suffixes,
        mining_base,
        select_mined_form,
    ) = _load_engine_compound_pipeline()
    tokenization_cases = document["cases"]["tokenization"]
    compound_cases = document["cases"]["compounds"]
    token_case = next(case for case in tokenization_cases if case["id"] == "compound-hashiridasu")
    expected_case = next(case for case in compound_cases if case["id"] == "compound-hashiridasu")
    dictionary_terms = set(expected_case["input"]["dictionary_terms"])
    rule = rule_type(
        allowed_pos=frozenset({"名詞", "動詞", "形容詞", "副詞", "代名詞"}),
        excluded_subtypes=frozenset(),
    )
    matcher = matcher_type(
        lambda candidates: set(candidates) & dictionary_terms,
        rule,
    )
    engine_tokens = merge_compound_suffixes(list(tagger(token_case["text"])))
    merged = matcher.merge_line(token_case["text"], engine_tokens)
    matches = [token for token in merged if token.surface == "走り出し"]
    if len(matches) != 1:
        raise RuntimeError("native S1b tokens did not produce the seeded compound")
    token = matches[0]
    lemma = extract_lemma(token)
    orth_base = mining_base(token)
    actual = {
        "surface": token.surface,
        "lemma": lemma,
        "orth_base": orth_base,
        "mined_form": select_mined_form(
            token.feature.pos1,
            orth_base,
            lemma,
            token.surface,
        ),
        "pos": token.feature.pos1,
    }
    expected_word = next(word for word in expected_case["output"]["words"] if word["surface"] == "走り出し")
    expected = {name: expected_word[name] for name in actual}
    if actual != expected:
        raise RuntimeError(
            "native S1b engine compound mismatch:\n"
            f"expected={json.dumps(expected, ensure_ascii=False)}\n"
            f"actual={json.dumps(actual, ensure_ascii=False)}"
        )


def verify_dictionary_provenance(
    dicdir: Path,
    document: dict[str, object],
) -> str:
    """Bind a parity run to the exact dictionary recorded by the golden."""

    try:
        provenance = document["provenance"]
        assert isinstance(provenance, dict)
        data = provenance["data"]
        assert isinstance(data, dict)
        assets = data["assets_sha256"]
        assert isinstance(assets, dict)
        expected = assets["unidic_dicdir"]
    except (AssertionError, KeyError, TypeError) as exc:
        raise RuntimeError("golden has no UniDic dictionary provenance hash") from exc
    if not isinstance(expected, str) or _SHA256_RE.fullmatch(expected) is None:
        raise RuntimeError("golden UniDic dictionary hash is malformed")

    missing = [name for name in UNIDIC_REQUIRED_FILES if not (dicdir / name).is_file()]
    if missing:
        raise RuntimeError(f"UniDic directory is incomplete: {missing!r}")
    try:
        actual = sha256_tree(dicdir)
    except GoldenContractError as exc:
        raise RuntimeError(f"UniDic directory cannot be verified: {exc}") from exc
    if actual != expected:
        raise RuntimeError(f"UniDic dictionary provenance mismatch: {actual} != {expected}")
    return actual


def verify(driver: Path, dicdir: Path, golden: Path) -> None:
    document = json.loads(golden.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise RuntimeError("golden root is not an object")
    tree_sha256 = verify_dictionary_provenance(dicdir, document)
    registration = RegisteredUniDic(
        resource_id=f"golden-unidic-{tree_sha256[:16]}",
        dicdir=dicdir,
        mecabrc=dicdir / "mecabrc",
        sys_dic=dicdir / "sys.dic",
        tree_sha256=tree_sha256,
        file_count=0,
        total_bytes=0,
    )
    cases = document["cases"]["tokenization"]
    native = _NativeDriverApi(driver, dicdir)
    try:
        tagger = create_s1b_tagger(registration, native=native)
        tagged_cases: dict[str, list[object]] = {}
        for case in cases:
            text = case["text"]
            engine_tokens = list(tagger(text))
            tagged_cases[case["id"]] = engine_tokens
            actual = [_actual_token(token) for token in engine_tokens]
            if actual != case["tokens"]:
                raise RuntimeError(
                    f"native parity mismatch in {case['id']}:\n"
                    f"expected={json.dumps(case['tokens'], ensure_ascii=False)}\n"
                    f"actual={json.dumps(actual, ensure_ascii=False)}"
                )
        _verify_engine_star_semantics(tagged_cases)
        _verify_seeded_compound(tagger, document)
        native.close()
    finally:
        native.abort()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--driver", type=Path)
    parser.add_argument("--dicdir", type=Path, required=True)
    parser.add_argument(
        "--golden",
        type=Path,
        default=PROJECT_ROOT / "golden/engine-v1.json",
    )
    parser.add_argument("--check-dictionary-only", action="store_true")
    args = parser.parse_args()
    try:
        dicdir = args.dicdir.resolve(strict=True)
        golden = args.golden.resolve(strict=True)
        if args.check_dictionary_only:
            document = json.loads(golden.read_text(encoding="utf-8"))
            if not isinstance(document, dict):
                raise RuntimeError("golden root is not an object")
            verify_dictionary_provenance(dicdir, document)
        else:
            if args.driver is None:
                parser.error("--driver is required unless checking only")
            verify(args.driver.resolve(strict=True), dicdir, golden)
    except (OSError, RuntimeError, subprocess.SubprocessError, ValueError) as exc:
        print(f"S1b host parity: {exc}", file=sys.stderr)
        return 1
    print("S1b host parity: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
