"""The emulator language smoke's harness, replayed on the host engine.

``LanguageSmokeInstrumentedTest`` compares the packaged engine's rows against
``EXPECTED``; this proves those rows are what the vendored engine produces here,
so an emulator mismatch points at packaging, not at a stale expectation.
"""

from __future__ import annotations

import ast
import json
import shutil
import sys
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parents[3]
DEBUG_PYTHON_ROOT = PROJECT_ROOT / "app/src/debug/python"
HARNESS_PATH = DEBUG_PYTHON_ROOT / "language_smoke_instrumented.py"
LANGUAGE_TESTS = Path(__file__).resolve().parent / "languages"
sys.path.insert(0, str(DEBUG_PYTHON_ROOT))
sys.path.insert(0, str(LANGUAGE_TESTS))

import language_smoke_instrumented as harness  # noqa: E402
from language_data_fixtures import cached_archive, language_data_home, read_token_fixture  # noqa: E402

#: Local codes whose rows come from a desktop-exported fixture's smoke-sentence row (not ar, fa).
FIXTURE_CODES = tuple(code for code in harness.LOCAL_CODES if code not in {"ar", "fa"})


def test_harness_does_not_import_engine_at_module_scope() -> None:
    tree = ast.parse(HARNESS_PATH.read_text(encoding="utf-8"), filename=str(HARNESS_PATH))
    offenders = [
        node.lineno
        for node in tree.body
        if isinstance(node, (ast.Import, ast.ImportFrom))
        and any(
            name.split(".")[0] in {"anki_miner", "android_bridge"}
            for name in ([alias.name for alias in node.names] if isinstance(node, ast.Import) else [node.module or ""])
        )
    ]
    assert offenders == []


def test_every_smoked_code_has_expected_rows() -> None:
    assert set(harness.EXPECTED) == set(harness.CI_CODES) | set(harness.LOCAL_CODES)
    assert set(harness.CI_CODES).isdisjoint(harness.LOCAL_CODES)


def test_every_vendored_language_but_ja_is_smoked_on_the_lane_its_data_decides() -> None:
    from android_bridge.resource_catalog import CATALOG_LANGUAGES, load_resource_catalog

    assert set(harness.CI_CODES) | set(harness.LOCAL_CODES) == set(CATALOG_LANGUAGES) - {"ja"}
    assert [code for code in harness.CI_CODES if load_resource_catalog(code).language_data] == []
    assert [code for code in harness.LOCAL_CODES if not load_resource_catalog(code).language_data] == []


def test_local_codes_push_their_pinned_archives_under_the_catalog_url_names() -> None:
    assert harness.archive_names("ar") == ["morphology_db_calima-msa-r13-0.4.0.zip"]
    assert harness.archive_names("fa") == ["hazm-0.12.1-py3-none-any.whl"]
    assert harness.archive_names("ru") == [
        "ru_core_news_sm-3.8.0-py3-none-any.whl",
        "pymorphy3_dicts_ru-2.4.417150.4580142-py2.py3-none-any.whl",
    ]
    assert harness.archive_names("ko") == ["kiwipiepy_model-0.23.0.tar.gz"]


def _smoke_fields(token: list[str] | dict[str, object]) -> list[object]:
    """A fixture token's ``[surface, pos1, pos2, lemma]``: a list row, or a dict row (vi, yue)."""

    return token[:4] if isinstance(token, list) else [token[key] for key in ("surface", "pos1", "pos2", "lemma")]


def _fixture_smoke_rows(code: str, sentence: str) -> list[list[object]]:
    """*sentence*'s rows in ``languages/fixtures/<code>/tokens.jsonl``, which may list it twice (smoke + corpus)."""

    rows = {
        json.dumps([_smoke_fields(token) for token in row["tokens"]], ensure_ascii=False)
        for row in read_token_fixture(LANGUAGE_TESTS / "fixtures" / code / "tokens.jsonl")
        if row.get("sentence", row.get("line")) == sentence
    }
    assert len(rows) == 1, f"{code}: {len(rows)} distinct fixture rows for the smoke sentence"
    return json.loads(rows.pop())


@pytest.mark.parametrize("code", FIXTURE_CODES)
def test_local_rows_are_the_desktop_fixture_smoke_rows(code: str, initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")
    from anki_miner.languages.registry import get_profile

    assert harness.EXPECTED[code] == _fixture_smoke_rows(code, get_profile(code).smoke_sentence)


def test_zh_rows_are_the_desktop_fixture_cases() -> None:
    cases = json.loads((LANGUAGE_TESTS / "fixtures/zh/tokens.json").read_text(encoding="utf-8"))["cases"]
    by_id = {case["id"]: case for case in cases}
    assert [row[:4] for row in by_id["smoke"]["tokens"]] == harness.EXPECTED["zh"]
    assert by_id["smoke"]["sentence"] == harness.ZH_SIMPLIFIED_SENTENCE
    assert by_id["smoke-traditional"]["sentence"] == harness.ZH_TRADITIONAL_SENTENCE
    assert by_id["smoke-traditional"]["tokens"] == harness.ZH_TRADITIONAL_EXPECTED


#: The package each CI code's tagger needs beyond the engine (runtime dependency lane only).
_TAGGER_PACKAGES = {"th": "pythainlp", "tr": "zeyrek", "zh": "jieba"}


@pytest.mark.parametrize("code", ["he", "id", "th", "tr", "zh"])
def test_ci_code_tokenises_its_smoke_sentence_as_expected(code: str, initialized_bridge_home: Path) -> None:
    del initialized_bridge_home
    pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")
    if code in _TAGGER_PACKAGES:
        pytest.importorskip(_TAGGER_PACKAGES[code], reason=f"runtime dependency lane: {code} needs its tagger")
    assert code in harness.CI_CODES
    result = json.loads(harness.smoke(code))
    assert result["unavailable_reason"] is None
    assert result["tokens"] == harness.EXPECTED[code]
    assert result["expected"] == harness.EXPECTED[code]


def test_thai_footprint_reports_the_read_only_package(initialized_bridge_home: Path) -> None:
    pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")
    pytest.importorskip("pythainlp", reason="runtime dependency lane: Thai tokenises with pythainlp")
    harness.smoke("th")
    footprint = json.loads(harness.thai_footprint(str(initialized_bridge_home)))
    assert footprint["env_read_only"] == "1"
    assert footprint["env_offline"] == "1"
    assert footprint["file_count"] > 0
    assert str(initialized_bridge_home / "pythainlp-data") not in footprint["pythainlp_data_dirs"]


def test_zh_package_data_opens_every_opencc_config_and_reads_traditional_text(
    initialized_bridge_home: Path,
) -> None:
    del initialized_bridge_home
    pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")
    for package in ("jieba", "opencc", "pypinyin"):
        pytest.importorskip(package, reason=f"runtime dependency lane: zh needs {package}")
    result = json.loads(harness.zh_package_data())
    assert result["opencc_configs"] == ["hk2s", "s2hk", "s2t", "s2tw", "t2s", "tw2s"]
    assert result["tokens"] == harness.ZH_TRADITIONAL_EXPECTED
    assert result["expected"] == harness.ZH_TRADITIONAL_EXPECTED


@pytest.mark.parametrize("code", ["en", "ru"])
def test_a_local_code_installs_smokes_and_uninstalls_from_pushed_archives(
    code: str, initialized_bridge_home: Path, tmp_path: Path
) -> None:
    """The local lane's Python side on the host, from archives named as ``adb push`` names them."""

    del initialized_bridge_home
    pytest.importorskip("pysubs2", reason="runtime dependency lane: the registry imports the subtitle parser")
    pytest.importorskip("spacy", reason="runtime dependency lane: the spaCy family ships in the APK")
    from android_bridge.resource_catalog import load_resource_catalog

    pushed = tmp_path / "pushed"
    pushed.mkdir()
    for entry, name in zip(load_resource_catalog(code).language_data, harness.archive_names(code), strict=True):
        archive = cached_archive(entry)
        if archive is None:
            pytest.skip(f"{entry.resource_id} is not cached: run tools/language-data/fetch_language_data.py {code}")
        shutil.copyfile(archive, pushed / name)  # the bridge refuses a symlinked archive
    with language_data_home(tmp_path / "home") as home:
        installed = [json.loads(message)["payload"]["importName"] for message in harness.install(code, str(pushed))]
        assert installed == [entry.import_name for entry in load_resource_catalog(code).language_data]
        result = json.loads(harness.smoke(code))
        assert result["unavailable_reason"] is None
        assert result["tokens"] == harness.EXPECTED[code]
        harness.uninstall(code)
        assert not (home / "language_packs" / code).exists()
