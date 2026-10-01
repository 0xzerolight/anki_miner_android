"""Resource imports and the known-words store follow the mining language.

Every resource op takes an optional ``language`` (absent = ja, today's Kotlin).
A new import stamps the language it was made for; a startup rebuild of a
schema-stale slot (Kotlin re-sending the import against the slot's own
``source.<ext>``) keeps the slot's stamp, so a Hebrew index never comes back
stamped "ja" and silently drops out of the Hebrew chain. Hebrew is the probe:
its profile needs no download. Everything here runs on the runtime lane.
"""

from __future__ import annotations

import dataclasses
import hashlib
import json
import shutil
import sqlite3
import zipfile
from pathlib import Path

import android_bridge.local_resources as local_resources
import android_bridge.resources as resources
import pytest
from android_bridge.protocol import BridgeProtocolError, decode_envelope

pytest.importorskip("pysubs2", reason="runtime dependency lane")
pytest.importorskip("requests", reason="runtime dependency lane")


@pytest.fixture
def home(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, initialized_bridge_home: Path) -> Path:
    del initialized_bridge_home  # the engine (and its registry) needs the bootstrap
    files = tmp_path / "android-files"
    files.mkdir()
    monkeypatch.setattr(local_resources, "require_initialized", lambda: str(files))
    monkeypatch.setattr(resources, "require_initialized", lambda: str(files))
    return files


def _meta_language(slot: Path) -> str:
    from anki_miner.services._sqlite_index import read_slot_language

    return read_slot_language(slot)


def _frequency_request(source: Path, **extra: object) -> dict[str, object]:
    return {
        "operationId": "frequency-op",
        "sourcePath": str(source),
        "sourceId": "hebrew-freq",
        "sourceName": "Hebrew Frequency",
        "sourceFormat": "csv",
        "overwrite": False,
        **extra,
    }


# ---------------------------------------------------------------- frequency


def test_a_frequency_list_is_stamped_with_the_language_it_was_imported_for(home: Path, tmp_path: Path) -> None:
    source = tmp_path / "frequency.csv"
    source.write_text("word,rank\nספר,10\nילד,20\n", encoding="utf-8")

    local_resources.import_frequency(_frequency_request(source, language="he"))

    assert _meta_language(home / "freqs" / "hebrew-freq") == "he"


def test_a_frequency_list_without_a_language_stays_japanese(home: Path, tmp_path: Path) -> None:
    source = tmp_path / "frequency.csv"
    source.write_text("word,rank\n猫,10\n", encoding="utf-8")

    local_resources.import_frequency(_frequency_request(source))

    meta = json.loads((home / "freqs" / "hebrew-freq" / "meta.json").read_text(encoding="utf-8"))
    assert meta["language"] == "ja"


def test_a_stale_frequency_slot_is_rebuilt_with_its_own_language(home: Path, tmp_path: Path) -> None:
    """Kotlin's startup rebuild sends no language: the slot's stamp must survive it."""
    source = tmp_path / "frequency.csv"
    source.write_text("word,rank\nספר,10\nילד,20\n", encoding="utf-8")
    local_resources.import_frequency(_frequency_request(source, language="he"))
    slot = home / "freqs" / "hebrew-freq"

    rebuilt = decode_envelope(
        local_resources.import_frequency(
            {**_frequency_request(slot / "source.csv"), "operationId": "frequency-rebuild", "overwrite": True}
        ),
        expected_type="resource.frequency.imported",
    )

    assert rebuilt.payload["entryCount"] == 2
    assert _meta_language(slot) == "he"


def test_a_rebuild_keeps_the_slot_language_even_when_the_request_names_another(home: Path, tmp_path: Path) -> None:
    source = tmp_path / "frequency.csv"
    source.write_text("word,rank\nספר,10\n", encoding="utf-8")
    local_resources.import_frequency(_frequency_request(source, language="he"))
    slot = home / "freqs" / "hebrew-freq"

    local_resources.import_frequency(
        {**_frequency_request(slot / "source.csv", language="ja"), "operationId": "rebuild", "overwrite": True}
    )

    assert _meta_language(slot) == "he"


def test_a_replacing_import_from_a_new_file_takes_the_requested_language(home: Path, tmp_path: Path) -> None:
    hebrew = tmp_path / "hebrew.csv"
    hebrew.write_text("word,rank\nספר,10\n", encoding="utf-8")
    local_resources.import_frequency(_frequency_request(hebrew, language="he"))
    thai = tmp_path / "thai.csv"
    thai.write_text("word,rank\nหนังสือ,10\n", encoding="utf-8")

    local_resources.import_frequency(
        {**_frequency_request(thai, language="th"), "operationId": "replace", "overwrite": True}
    )

    assert _meta_language(home / "freqs" / "hebrew-freq") == "th"


def test_an_unavailable_language_import_is_refused(home: Path, tmp_path: Path) -> None:
    source = tmp_path / "frequency.csv"
    source.write_text("word,rank\n猫,10\n", encoding="utf-8")

    with pytest.raises(BridgeProtocolError) as error:
        local_resources.import_frequency(_frequency_request(source, language="eo"))

    assert error.value.code == "unsupported_language"
    assert not (home / "freqs" / "hebrew-freq").exists()


def test_a_lemmatising_language_rebuild_replays_its_recorded_options(
    home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """``repair_frequency_source``: a lemmatised list must not come back re-ranked by surface counts."""
    import anki_miner.services.frequency.lemmatize as lemmatize
    import anki_miner.services.frequency.source_importer as source_importer

    built: list[tuple[str, object]] = []

    def fake_lemmatizer(language: str, dicts_root: object = None):  # type: ignore[no-untyped-def]
        built.append((language, dicts_root))
        return lambda words: list(words)

    calls: list[dict[str, object]] = []
    real_import = source_importer.import_frequency_source

    def recording_import(*args: object, **kwargs: object):  # type: ignore[no-untyped-def]
        calls.append(kwargs)
        return real_import(*args, **kwargs)

    monkeypatch.setattr(lemmatize, "build_frequency_lemmatizer", fake_lemmatizer)
    monkeypatch.setattr(source_importer, "import_frequency_source", recording_import)
    monkeypatch.setattr(source_importer, "slot_import_options", lambda _slot: ("occurrence", True))
    source = tmp_path / "frequency.csv"
    source.write_text("word,rank\nספר,10\n", encoding="utf-8")
    local_resources.import_frequency(_frequency_request(source, language="he"))
    slot = home / "freqs" / "hebrew-freq"
    calls.clear()

    local_resources.import_frequency(
        {**_frequency_request(slot / "source.csv"), "operationId": "rebuild", "overwrite": True}
    )

    assert calls[0]["language"] == "he"
    assert calls[0]["declared_mode"] == "occurrence"
    assert callable(calls[0]["lemmatize"])
    assert built == [("he", home / "dicts")]


def test_a_new_list_for_a_lemmatising_language_is_lemmatised(
    home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Desktop ``manual_import_lemmatizer``: ar/fa declare ``lemmatised_frequency``; he does not."""
    import anki_miner.services.frequency.lemmatize as lemmatize

    asked: list[str] = []

    def manual(language: str, dicts_root: object = None):  # type: ignore[no-untyped-def]
        asked.append(language)
        return None

    monkeypatch.setattr(lemmatize, "manual_import_lemmatizer", manual)
    source = tmp_path / "frequency.csv"
    source.write_text("word,rank\nספר,10\n", encoding="utf-8")
    local_resources.import_frequency(_frequency_request(source, language="he"))
    local_resources.import_frequency({**_frequency_request(source), "operationId": "ja-import", "sourceId": "ja-freq"})

    # Asked for he (which answers None); never for ja, whose call keeps its pre-S17 shape.
    assert asked == ["he"]


def test_a_catalog_list_follows_its_desktop_row(
    home: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Desktop ``_lemmatise_kwargs``: a ``lemmatise=True`` row is summed per lemma in occurrence mode.

    id declares no ``lemmatised_frequency``, so only the catalogue's own bytes in its own slot take
    the row's rule; a hand-picked file replacing that slot keeps the manual one.
    """
    import android_bridge.resource_catalog as catalog
    import anki_miner.services.frequency.lemmatize as lemmatize
    import anki_miner.services.frequency.source_importer as source_importer

    pinned_source = tmp_path / "id_50k.txt"
    pinned_source.write_text("kamu 10\n", encoding="utf-8")
    real_load = catalog.load_resource_catalog
    indonesian = real_load("id")
    pinned_bytes = dataclasses.replace(
        indonesian,
        resources=tuple(
            (
                dataclasses.replace(
                    resource,
                    archive=dataclasses.replace(
                        resource.archive,
                        sha256=hashlib.sha256(pinned_source.read_bytes()).hexdigest(),
                    ),
                )
                if isinstance(resource, catalog.FrequencyResource)
                else resource
            )
            for resource in indonesian.resources
        ),
    )
    monkeypatch.setattr(
        catalog,
        "load_resource_catalog",
        lambda language="ja": pinned_bytes if language == "id" else real_load(language),
    )
    monkeypatch.setattr(lemmatize, "build_frequency_lemmatizer", lambda language, dicts_root=None: list)
    calls: list[dict[str, object]] = []
    real_import = source_importer.import_frequency_source

    def recording_import(*args: object, **kwargs: object):  # type: ignore[no-untyped-def]
        calls.append(kwargs)
        return real_import(*args, **kwargs)

    monkeypatch.setattr(source_importer, "import_frequency_source", recording_import)
    request = {
        **_frequency_request(pinned_source, language="id"),
        "sourceId": "opensubtitles-id",
        "sourceFormat": "txt",
    }
    local_resources.import_frequency(request)
    hand_picked = tmp_path / "mine.txt"
    hand_picked.write_text("saya 10\n", encoding="utf-8")
    local_resources.import_frequency(
        {**request, "operationId": "hand-picked", "sourcePath": str(hand_picked), "overwrite": True}
    )

    assert calls[0]["declared_mode"] == "occurrence-based"
    assert calls[0]["lemmatize"] is list
    assert "declared_mode" not in calls[1] and "lemmatize" not in calls[1]


# ---------------------------------------------------------------- pitch


def test_a_stale_pitch_slot_is_rebuilt_with_its_own_language(home: Path, tmp_path: Path) -> None:
    source = tmp_path / "pitch.csv"
    source.write_text("reading,term,pattern\nせんせい,先生,3\n", encoding="utf-8")
    request = {
        "operationId": "pitch-op",
        "sourcePath": str(source),
        "sourceId": "fixture-pitch",
        "sourceName": "Fixture Pitch",
        "sourceFormat": "csv",
        "overwrite": False,
        "language": "th",
    }
    local_resources.import_pitch(request)
    slot = home / "pitch" / "fixture-pitch"
    assert _meta_language(slot) == "th"

    local_resources.import_pitch(
        {
            **{key: value for key, value in request.items() if key != "language"},
            "operationId": "pitch-rebuild",
            "sourcePath": str(slot / "source.csv"),
            "overwrite": True,
        }
    )

    assert _meta_language(slot) == "th"


# ---------------------------------------------------------------- dictionaries


def _hebrew_dictionary(path: Path) -> Path:
    index = {"title": "Hebrew Fixture", "revision": "1", "format": 3}
    rows = [["ספר", "", "", "", 0, ["book"], 1, ""]]
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("index.json", json.dumps(index, ensure_ascii=False))
        archive.writestr("term_bank_1.json", json.dumps(rows, ensure_ascii=False))
    return path


def _import_dictionary(source: Path, **extra: object) -> None:
    resources.import_dictionary(
        {
            "operationId": "dictionary-op",
            "sourcePath": str(source),
            "slotId": "hebrew-dict",
            "overwrite": False,
            "catalogResourceId": None,
            **extra,
        }
    )


def test_a_dictionary_is_stamped_and_rebuilt_with_its_language(home: Path, tmp_path: Path) -> None:
    _import_dictionary(_hebrew_dictionary(tmp_path / "hebrew.zip"), language="he")
    slot = home / "dicts" / "hebrew-dict"
    assert _meta_language(slot) == "he"
    retained = next(path for path in slot.iterdir() if path.name.startswith("source") and path.suffix == ".zip")

    _import_dictionary(retained, operationId="dictionary-rebuild", overwrite=True)

    assert _meta_language(slot) == "he"


def test_the_settings_lookup_folds_with_the_slots_own_language(home: Path, tmp_path: Path) -> None:
    """A Hebrew index stores unpointed keys: the pointed form only finds it under Hebrew folding."""
    _import_dictionary(_hebrew_dictionary(tmp_path / "hebrew.zip"), language="he")

    found = decode_envelope(
        resources.lookup_dictionary({"slotId": "hebrew-dict", "term": "סֵפֶר"}),
        expected_type="resource.dictionary.lookup.result",
    )

    assert "book" in found.payload["html"]


# ---------------------------------------------------------------- audio packs


def test_an_audio_pack_is_stamped_with_the_language_it_was_imported_for(home: Path, tmp_path: Path) -> None:
    pack = tmp_path / "pack"
    (pack / "audio").mkdir(parents=True)
    (pack / "audio" / "sefer.mp3").write_bytes(b"ID3fixture-audio")
    (pack / "index.json").write_text(
        json.dumps(
            {
                "meta": {"name": "Hebrew Pack", "year": 2026, "version": 1, "media_dir": "audio"},
                "headwords": {"ספר": ["sefer.mp3"]},
                "files": {"sefer.mp3": {}},
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    archive = shutil.make_archive(str(tmp_path / "pack-archive"), "zip", pack)

    local_resources.import_audio_pack(
        {
            "operationId": "audio-op",
            "sourcePath": archive,
            "packId": "hebrew-pack",
            "packPath": "",
            "overwrite": False,
            "language": "he",
        }
    )

    assert _meta_language(home / "audio_packs" / "hebrew-pack") == "he"


# ---------------------------------------------------------------- known words


def _known_words_file(tmp_path: Path, data: bytes) -> Path:
    path = tmp_path / "known.txt"
    path.write_bytes(data)
    return path


def test_known_words_import_lands_in_the_languages_own_database(home: Path, tmp_path: Path) -> None:
    from anki_miner.languages.registry import get_profile

    # cp1255, the Windows Hebrew codepage: only the Hebrew decode ladder reads it.
    source = _known_words_file(tmp_path, "סֵפֶר\nילד\n".encode("cp1255"))
    imported = decode_envelope(
        local_resources.import_known_words(
            {"operationId": "known-op", "sourcePath": str(source), "sourceFormat": "txt", "language": "he"}
        ),
        expected_type="resource.knownwords.imported",
    )

    assert imported.payload["importedCount"] == 2
    assert not (home / "known_words.db").exists()
    rows = sqlite3.connect(home / "known_words.he.db").execute("SELECT lemma, source FROM known_words").fetchall()
    fold = get_profile("he").dedup_fold
    assert sorted(rows) == sorted([(fold("סֵפֶר"), "user"), (fold("ילד"), "user")])


def test_known_words_management_reads_and_writes_the_languages_own_database(home: Path, tmp_path: Path) -> None:
    source = _known_words_file(tmp_path, "ספר\nילד\n".encode())
    local_resources.import_known_words(
        {"operationId": "known-op", "sourcePath": str(source), "sourceFormat": "txt", "language": "he"}
    )

    listed = decode_envelope(
        local_resources.list_known_words(
            {"operationId": "list-op", "query": "", "offset": 0, "limit": 10, "language": "he"}
        ),
        expected_type="resource.knownwords.listed",
    )
    japanese = decode_envelope(
        local_resources.list_known_words({"operationId": "list-ja", "query": "", "offset": 0, "limit": 10}),
        expected_type="resource.knownwords.listed",
    )
    inventory = decode_envelope(
        local_resources.list_local_resources({"language": "he"}),
        expected_type="resource.local.listed",
    )

    assert sorted(listed.payload["words"]) == ["ילד", "ספר"]
    assert japanese.payload["words"] == []
    assert inventory.payload["knownWords"]["userCount"] == 2

    local_resources.remove_known_words({"operationId": "remove-op", "words": ["ילד"], "language": "he"})
    after = decode_envelope(
        local_resources.list_known_words(
            {"operationId": "list-after", "query": "", "offset": 0, "limit": 10, "language": "he"}
        ),
        expected_type="resource.knownwords.listed",
    )
    assert after.payload["words"] == ["ספר"]

    exported = decode_envelope(
        local_resources.export_known_words({"operationId": "export-op", "language": "he"}),
        expected_type="resource.knownwords.exported",
    )
    assert Path(exported.payload["exportPath"]).read_text(encoding="utf-8") == "ספר\n"

    local_resources.reset_known_words({"operationId": "reset-op", "scope": "user", "language": "he"})
    reset = decode_envelope(
        local_resources.list_local_resources({"language": "he"}),
        expected_type="resource.local.listed",
    )
    assert reset.payload["knownWords"]["userCount"] == 0


def test_known_words_preview_reads_with_the_languages_ladder(home: Path, tmp_path: Path) -> None:
    source = _known_words_file(tmp_path, "ספר\n".encode("cp1255"))

    preview = decode_envelope(
        local_resources.preview_known_words(
            {"operationId": "preview-op", "sourcePath": str(source), "sourceFormat": "txt", "language": "he"}
        ),
        expected_type="resource.knownwords.previewed",
    )

    assert preview.payload["sampleWords"] == ["ספר"]


# ---------------------------------------------------------------- inventory stamps


def _listed_local(**payload: object) -> dict:
    return decode_envelope(local_resources.list_local_resources(payload), expected_type="resource.local.listed").payload


def test_every_inventory_reports_each_slots_language_stamp(home: Path, tmp_path: Path) -> None:
    """Kotlin offers a chain only the active language's slots; the stamp is how it tells them apart."""
    he_list = tmp_path / "he.csv"
    he_list.write_text("word,rank\nספר,10\n", encoding="utf-8")
    ja_list = tmp_path / "ja.csv"
    ja_list.write_text("word,rank\n猫,10\n", encoding="utf-8")
    local_resources.import_frequency(_frequency_request(he_list, language="he"))
    local_resources.import_frequency({**_frequency_request(ja_list), "operationId": "ja-op", "sourceId": "ja-freq"})
    _import_dictionary(_hebrew_dictionary(tmp_path / "hebrew.zip"), language="he")

    listed = _listed_local(language="he")
    dictionaries = decode_envelope(resources.list_dictionaries({}), expected_type="resource.dictionary.listed")

    assert {item["sourceId"]: item["language"] for item in listed["frequencies"]} == {
        "hebrew-freq": "he",
        "ja-freq": "ja",
    }
    assert [(item["slotId"], item["language"]) for item in dictionaries.payload["dictionaries"]] == [
        ("hebrew-dict", "he")
    ]


def test_an_unreadable_or_unstamped_slot_reports_japanese(home: Path) -> None:
    slot = home / "dicts" / "broken"
    slot.mkdir(parents=True)
    (slot / "index.sqlite").write_bytes(b"not sqlite")

    listed = decode_envelope(resources.list_dictionaries({}), expected_type="resource.dictionary.listed")

    assert listed.payload["dictionaries"][0]["language"] == "ja"


# ---------------------------------------------------------------- missing language data


def test_a_lemmatised_list_for_a_language_missing_its_data_is_refused_as_unavailable(
    home: Path, tmp_path: Path
) -> None:
    """Arabic lemmatises its lists with a tagger its downloaded data feeds; without it, no crash."""
    source = tmp_path / "ar.txt"
    source.write_text("كتاب 10\n", encoding="utf-8")

    with pytest.raises(BridgeProtocolError) as error:
        local_resources.import_frequency(
            {**_frequency_request(source, language="ar"), "sourceId": "ar-freq", "sourceFormat": "txt"}
        )

    assert (error.value.code, str(error.value)) == ("language_unavailable", "language_data_required")
    assert not (home / "freqs" / "ar-freq").exists()


def test_rebuilding_a_lemmatised_slot_whose_data_went_missing_leaves_the_slot_alone(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The startup rebuild of one slot must fail on its own, never wedge recovery (Kotlin degrades it)."""
    import anki_miner.services.frequency.lemmatize as lemmatize

    monkeypatch.setattr(lemmatize, "build_frequency_lemmatizer", lambda language, dicts_root=None: list)
    monkeypatch.setattr(local_resources, "unavailable_reason_code", lambda profile: None)
    source = tmp_path / "ar.txt"
    source.write_text("كتاب 10\n", encoding="utf-8")
    request = {**_frequency_request(source, language="ar"), "sourceId": "ar-freq", "sourceFormat": "txt"}
    local_resources.import_frequency(request)
    slot = home / "freqs" / "ar-freq"
    before = sorted(path.name for path in slot.iterdir())
    monkeypatch.undo()

    with pytest.raises(BridgeProtocolError) as error:
        local_resources.import_frequency(
            {**request, "sourcePath": str(slot / "source.txt"), "operationId": "rebuild", "overwrite": True}
        )

    assert error.value.code == "language_unavailable"
    assert sorted(path.name for path in slot.iterdir()) == before
    assert _meta_language(slot) == "ar"


def test_known_words_need_no_language_data(home: Path, tmp_path: Path) -> None:
    local_resources.import_known_words(
        {
            "operationId": "ar-known",
            "sourcePath": str(_known_words_file(tmp_path, "كتاب\n".encode())),
            "sourceFormat": "txt",
            "language": "ar",
        }
    )

    inventory = _listed_local(language="ar")["knownWords"]
    assert (inventory["totalCount"], inventory["schemaOk"]) == (1, True)


# ---------------------------------------------------------------- the Japanese known-words ladder


def test_japanese_known_words_read_with_the_profiles_three_encoding_ladder(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Desktop's Manage Known Words passes ``profile.import_encodings`` for ja too, in its order."""
    import anki_miner.services.known_words_import as known_words_import

    seen: list[dict[str, object]] = []
    real = known_words_import.parse_known_words_file

    def spy(path: Path, **kwargs: object):  # type: ignore[no-untyped-def]
        seen.append(kwargs)
        return real(path, **kwargs)

    monkeypatch.setattr(known_words_import, "parse_known_words_file", spy)
    local_resources.preview_known_words(
        {
            "operationId": "ja-ladder",
            "sourcePath": str(_known_words_file(tmp_path, "猫\n".encode())),
            "sourceFormat": "txt",
        }
    )

    assert seen[0]["encodings"] == ("utf-8-sig", "cp932", "euc_jp")
    assert "script_check" not in seen[0]


def test_a_japanese_list_only_euc_jp_decodes_is_imported(home: Path, tmp_path: Path) -> None:
    data = "食べる\n日本語\n".encode("euc_jp")
    for codec in ("utf-8", "cp932"):
        with pytest.raises(UnicodeDecodeError):
            data.decode(codec)

    imported = decode_envelope(
        local_resources.import_known_words(
            {"operationId": "ja-euc", "sourcePath": str(_known_words_file(tmp_path, data)), "sourceFormat": "txt"}
        ),
        expected_type="resource.knownwords.imported",
    )

    assert imported.payload["importedCount"] == 2
    from anki_miner.services.known_word_db import KnownWordDB

    assert KnownWordDB(home / "known_words.db").get_known_words() == {"食べる", "日本語"}


def _declaring_dictionary(path: Path, source_language: str) -> Path:
    index = {"title": "Declaring Fixture", "revision": "1", "format": 3, "sourceLanguage": source_language}
    rows = [["猫", "ねこ", "", "", 0, ["cat"], 1, ""]]
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("index.json", json.dumps(index, ensure_ascii=False))
        archive.writestr("term_bank_1.json", json.dumps(rows, ensure_ascii=False))
    return path


def _imported(source: Path, **extra: object) -> dict[str, object]:
    return decode_envelope(
        resources.import_dictionary(
            {
                "operationId": "dictionary-op",
                "sourcePath": str(source),
                "slotId": "declaring-dict",
                "overwrite": False,
                "catalogResourceId": None,
                **extra,
            }
        ),
        expected_type="resource.dictionary.imported",
    ).payload


def test_a_japanese_dictionary_imported_for_hebrew_says_so(home: Path, tmp_path: Path) -> None:
    """Desktop's receipt note; the slot is still imported and stamped for Hebrew."""
    payload = _imported(_declaring_dictionary(tmp_path / "ja.zip", "ja-JP"), language="he")

    assert payload["sourceLanguage"] == "ja"
    assert payload["sourceLanguageMismatch"] is True
    assert _meta_language(home / "dicts" / "declaring-dict") == "he"


def test_a_dictionary_declaring_the_mining_language_is_no_mismatch(home: Path, tmp_path: Path) -> None:
    payload = _imported(_declaring_dictionary(tmp_path / "ja.zip", "ja"))

    assert payload["sourceLanguage"] == "ja"
    assert payload["sourceLanguageMismatch"] is False


def test_an_undeclared_or_malformed_language_crosses_as_empty(home: Path, tmp_path: Path) -> None:
    payload = _imported(_declaring_dictionary(tmp_path / "odd.zip", "<b>klingon</b>"), language="he")

    assert payload["sourceLanguage"] == ""
