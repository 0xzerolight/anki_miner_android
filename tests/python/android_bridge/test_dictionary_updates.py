"""Dictionary updates over the bridge: ``resource.updates.check`` and ``resource.update.install``.

The check asks each updatable slot's publisher for its latest ``index.json``
through the vendored ``resource_updates``; the install rebuilds one slot in
place from the archive Kotlin downloaded. Runtime lane only: the vendored
module imports ``requests``, and every install runs a real engine importer.
Hebrew is the non-Japanese probe, as in ``test_resource_languages.py``: its
profile needs no download.
"""

from __future__ import annotations

import hashlib
import io
import json
import sqlite3
import zipfile
from dataclasses import replace
from pathlib import Path

import android_bridge.dictionary_updates as dictionary_updates
import android_bridge.local_resources as local_resources
import android_bridge.resources as resources
import pytest
from android_bridge import boundary
from android_bridge.protocol import BridgeProtocolError, decode_envelope, encode_message
from android_bridge.resource_catalog import YomitanResource, load_resource_catalog

pytest.importorskip("pysubs2", reason="runtime dependency lane")
requests = pytest.importorskip("requests", reason="runtime dependency lane")


@pytest.fixture
def home(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, initialized_bridge_home: Path) -> Path:
    del initialized_bridge_home  # the engine (and its registry) needs the bootstrap
    files = tmp_path / "android-files"
    files.mkdir()
    for module in (resources, local_resources, dictionary_updates):
        monkeypatch.setattr(module, "require_initialized", lambda: str(files))
    return files


def _index_url(slot: str) -> str:
    return f"https://publisher.example/{slot}/index.json"


def _index(title: str, revision: str, *, slot: str, **overrides: object) -> dict[str, object]:
    return {
        "title": title,
        "revision": revision,
        "format": 3,
        "isUpdatable": True,
        "indexUrl": _index_url(slot),
        "downloadUrl": f"https://publisher.example/{slot}/latest.zip",
        **overrides,
    }


def _zip(path: Path, index: dict[str, object], bank: str, rows: list[object]) -> Path:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("index.json", json.dumps(index, ensure_ascii=False))
        archive.writestr(bank, json.dumps(rows, ensure_ascii=False))
    return path


def _dictionary_zip(path: Path, index: dict[str, object], *, term: str = "猫", meaning: str = "cat") -> Path:
    return _zip(path, index, "term_bank_1.json", [[term, "", "", "", 0, [meaning], 1, ""]])


def _frequency_zip(path: Path, index: dict[str, object]) -> Path:
    return _zip(path, index, "term_meta_bank_1.json", [["猫", "freq", 10], ["犬", "freq", 20]])


def _pitch_zip(path: Path, index: dict[str, object]) -> Path:
    rows = [["猫", "pitch", {"reading": "ねこ", "pitches": [{"position": 1}]}]]
    return _zip(path, index, "term_meta_bank_1.json", rows)


def _import_dictionary(
    source: Path,
    slot_id: str,
    *,
    language: str = "ja",
    catalog_id: str | None = None,
) -> dict[str, object]:
    return decode_envelope(
        resources.import_dictionary(
            {
                "operationId": f"install-{slot_id}",
                "sourcePath": str(source),
                "slotId": slot_id,
                "overwrite": False,
                "catalogResourceId": catalog_id,
                "language": language,
            }
        ),
        expected_type="resource.dictionary.imported",
    ).payload


def _import_local(kind: str, source: Path, slot_id: str, *, language: str = "ja") -> None:
    importer = local_resources.import_frequency if kind == "frequency" else local_resources.import_pitch
    importer(
        {
            "operationId": f"install-{slot_id}",
            "sourcePath": str(source),
            "sourceId": slot_id,
            "sourceName": slot_id,
            "sourceFormat": "zip",
            "overwrite": False,
            "language": language,
        }
    )


def _check(
    language: str = "ja",
    *,
    dictionaries: tuple[str, ...] = (),
    frequencies: tuple[str, ...] = (),
    pitch: tuple[str, ...] = (),
    operation: str = "update-check",
) -> dict[str, object]:
    return decode_envelope(
        dictionary_updates.check_updates(
            {
                "operationId": operation,
                "language": language,
                "dictionaryIds": list(dictionaries),
                "frequencyIds": list(frequencies),
                "pitchIds": list(pitch),
            }
        ),
        expected_type="resource.updates.checked",
    ).payload


def _publishers(monkeypatch: pytest.MonkeyPatch, answers: dict[str, object]) -> list[str]:
    """Answer each index URL with its index, or raise its exception; returns the URLs asked, in order."""

    asked: list[str] = []

    def fetch(url: str) -> dict[str, object]:
        asked.append(url)
        answer = answers[url]
        if isinstance(answer, BaseException):
            raise answer
        assert isinstance(answer, dict)
        return answer

    monkeypatch.setattr(dictionary_updates, "fetch_https_index", fetch)
    return asked


def _install(kind: str, slot_id: str, source: Path, *, language: str = "ja", display_name: str = "Updated") -> str:
    return dictionary_updates.install_update(
        {
            "operationId": f"update-{slot_id}",
            "kind": kind,
            "slotId": slot_id,
            "sourcePath": str(source),
            "displayName": display_name,
            "language": language,
        }
    )


def _listed(slot_id: str) -> dict[str, object]:
    listed = decode_envelope(resources.list_dictionaries({}), expected_type="resource.dictionary.listed")
    return next(item for item in listed.payload["dictionaries"] if item["slotId"] == slot_id)


def _slot_language(slot: Path) -> str:
    from anki_miner.services._sqlite_index import read_slot_language

    return read_slot_language(slot)


# --------------------------------------------------------------------- check


def test_check_reports_a_newer_published_revision_for_each_family_in_chain_order(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _import_dictionary(
        _dictionary_zip(tmp_path / "d.zip", _index("Pub [2026-07-09]", "2026.07.09.0", slot="pub")), "pub"
    )
    _import_local("frequency", _frequency_zip(tmp_path / "f.zip", _index("Freq", "1.2", slot="freq")), "freq")
    _import_local("pitch", _pitch_zip(tmp_path / "p.zip", _index("Pitch", "2026-07-01", slot="pitch")), "pitch")
    asked = _publishers(
        monkeypatch,
        {
            _index_url("pub"): {
                "title": "Pub [2026-10-03]",
                "revision": "2026.10.03.0",
                "downloadUrl": "https://mirror.example/pub-2026-10-03.zip",
            },
            _index_url("freq"): {"title": "Freq", "revision": "1.10"},
            _index_url("pitch"): {"title": "Pitch", "revision": "2026-10-01"},
        },
    )

    checked = _check(dictionaries=("pub",), frequencies=("freq",), pitch=("pitch",))

    assert asked == [_index_url("pub"), _index_url("freq"), _index_url("pitch")]
    assert checked == {
        "checked": 3,
        "reached": True,
        "failedCount": 0,
        "updates": [
            {
                "kind": "dictionary",
                "slotId": "pub",
                "currentRevision": "2026.07.09.0",
                "latestRevision": "2026.10.03.0",
                "title": "Pub [2026-10-03]",
                "downloadUrl": "https://mirror.example/pub-2026-10-03.zip",
                "maxArchiveBytes": resources._MAX_CUSTOM_DICTIONARY_ARCHIVE_BYTES,
            },
            {
                "kind": "frequency",
                "slotId": "freq",
                "currentRevision": "1.2",
                "latestRevision": "1.10",
                "title": "Freq",
                "downloadUrl": "https://publisher.example/freq/latest.zip",
                "maxArchiveBytes": local_resources._FREQUENCY_ARCHIVE_LIMIT,
            },
            {
                "kind": "pitch",
                "slotId": "pitch",
                "currentRevision": "2026-07-01",
                "latestRevision": "2026-10-01",
                "title": "Pitch",
                "downloadUrl": "https://publisher.example/pitch/latest.zip",
                "maxArchiveBytes": local_resources._PITCH_ARCHIVE_LIMIT,
            },
        ],
    }


def test_check_reports_no_update_when_the_publisher_serves_an_older_or_equal_revision(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _import_dictionary(_dictionary_zip(tmp_path / "a.zip", _index("A", "2026.07.09.0", slot="pub-a")), "pub-a")
    _import_dictionary(_dictionary_zip(tmp_path / "b.zip", _index("B", "2026.07.09.0", slot="pub-b")), "pub-b")
    _publishers(
        monkeypatch,
        {
            _index_url("pub-a"): {"title": "A", "revision": "2026.07.01.0"},
            _index_url("pub-b"): {"title": "B", "revision": "2026.07.09.0"},
        },
    )

    checked = _check(dictionaries=("pub-a", "pub-b"))

    assert checked == {"checked": 2, "reached": True, "failedCount": 0, "updates": []}


def test_check_skips_a_slot_stamped_for_another_language(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _import_dictionary(_dictionary_zip(tmp_path / "d.zip", _index("Pub", "1", slot="pub")), "pub")
    asked = _publishers(monkeypatch, {})

    checked = _check("he", dictionaries=("pub",))

    assert checked == {"checked": 0, "reached": True, "failedCount": 0, "updates": []}
    assert asked == []


def test_check_skips_a_slot_whose_index_names_an_http_url(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    index = _index("Pub", "1", slot="pub", indexUrl="http://publisher.example/pub/index.json")
    _import_dictionary(_dictionary_zip(tmp_path / "d.zip", index), "pub")
    asked = _publishers(monkeypatch, {})

    checked = _check(dictionaries=("pub",))

    assert checked["checked"] == 0
    assert asked == []


def test_one_failing_publisher_is_counted_without_stopping_the_others_or_leaking_its_error(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _import_dictionary(_dictionary_zip(tmp_path / "a.zip", _index("A", "1", slot="pub-a")), "pub-a")
    _import_dictionary(_dictionary_zip(tmp_path / "b.zip", _index("B", "1", slot="pub-b")), "pub-b")
    _publishers(
        monkeypatch,
        {
            _index_url("pub-a"): requests.ConnectionError("secret-host refused the connection"),
            _index_url("pub-b"): {"title": "B", "revision": "2"},
        },
    )

    raw = dictionary_updates.check_updates(
        {
            "operationId": "update-check",
            "language": "ja",
            "dictionaryIds": ["pub-a", "pub-b"],
            "frequencyIds": [],
            "pitchIds": [],
        }
    )
    checked = decode_envelope(raw, expected_type="resource.updates.checked").payload

    assert checked["checked"] == 2
    assert checked["reached"] is True
    assert checked["failedCount"] == 1
    assert [update["slotId"] for update in checked["updates"]] == ["pub-b"]
    assert "secret-host" not in raw


def test_check_is_unreached_when_every_publisher_fails(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _import_dictionary(_dictionary_zip(tmp_path / "a.zip", _index("A", "1", slot="pub-a")), "pub-a")
    _publishers(monkeypatch, {_index_url("pub-a"): ValueError("index.json is not a JSON object")})

    checked = _check(dictionaries=("pub-a",))

    assert checked == {"checked": 1, "reached": False, "failedCount": 1, "updates": []}


def test_a_publisher_answer_that_cannot_cross_the_bridge_is_counted_as_a_failure(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A title Kotlin could not take falls back to the slot's own; an unbounded revision fails its publisher."""
    _import_dictionary(_dictionary_zip(tmp_path / "a.zip", _index("Pub A", "1", slot="pub-a")), "pub-a")
    _import_dictionary(_dictionary_zip(tmp_path / "b.zip", _index("Pub B", "1", slot="pub-b")), "pub-b")
    _publishers(
        monkeypatch,
        {
            _index_url("pub-a"): {"title": "Bell\x07 [2026]", "revision": "2"},
            _index_url("pub-b"): {"title": "Pub B", "revision": "2" * 5000},
        },
    )

    checked = _check(dictionaries=("pub-a", "pub-b"))

    assert checked["failedCount"] == 1
    assert checked["reached"] is True
    assert [(update["slotId"], update["title"]) for update in checked["updates"]] == [("pub-a", "Pub A")]


def test_cancel_stops_the_check_between_publishers(home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    _import_dictionary(_dictionary_zip(tmp_path / "a.zip", _index("A", "1", slot="pub-a")), "pub-a")
    _import_dictionary(_dictionary_zip(tmp_path / "b.zip", _index("B", "1", slot="pub-b")), "pub-b")
    asked: list[str] = []

    def fetch(url: str) -> dict[str, object]:
        asked.append(url)
        # resource.operation.cancel, as Kotlin's control executor sends it mid-check.
        resources.cancel_operation({"operationId": "update-cancel"})
        return {"title": "A", "revision": "2"}

    monkeypatch.setattr(dictionary_updates, "fetch_https_index", fetch)

    with pytest.raises(BridgeProtocolError) as cancelled:
        _check(dictionaries=("pub-a", "pub-b"), operation="update-cancel")

    assert cancelled.value.code == "resource_operation_cancelled"
    assert asked == [_index_url("pub-a")]


def test_check_refuses_a_malformed_request(home: Path) -> None:
    request = {
        "operationId": "update-check",
        "language": "ja",
        "dictionaryIds": ["../escape"],
        "frequencyIds": [],
        "pitchIds": [],
    }
    with pytest.raises(BridgeProtocolError) as invalid:
        dictionary_updates.check_updates(request)
    assert invalid.value.code == "invalid_resource_request"

    with pytest.raises(BridgeProtocolError) as extra:
        dictionary_updates.check_updates({**request, "dictionaryIds": [], "extra": True})
    assert extra.value.code == "invalid_resource_request"


# ------------------------------------------------------------------- fetcher


class _Publisher(requests.adapters.BaseAdapter):
    """Serves canned responses per URL and records every URL the session sends."""

    def __init__(self, routes: dict[str, tuple[int, dict[str, str], bytes]]) -> None:
        super().__init__()
        self.routes = routes
        self.sent: list[str] = []

    def send(self, request, **kwargs):  # type: ignore[no-untyped-def, override]
        self.sent.append(request.url)
        status, headers, body = self.routes[request.url]
        response = requests.Response()
        response.status_code = status
        response.headers.update(headers)
        response.raw = io.BytesIO(body)
        response.url = request.url
        response.request = request
        return response

    def close(self) -> None:
        pass


def _serve(monkeypatch: pytest.MonkeyPatch, routes: dict[str, tuple[int, dict[str, str], bytes]]) -> _Publisher:
    publisher = _Publisher(routes)

    def session() -> requests.Session:
        value = requests.Session()
        value.mount("https://", publisher)
        value.mount("http://", publisher)
        return value

    monkeypatch.setattr(dictionary_updates, "_session", session)
    return publisher


def test_fetcher_follows_an_https_redirect(monkeypatch: pytest.MonkeyPatch) -> None:
    publisher = _serve(
        monkeypatch,
        {
            "https://github.example/releases/latest/download/index.json": (
                302,
                {"Location": "https://objects.example/index.json"},
                b"",
            ),
            "https://objects.example/index.json": (200, {}, b'{"revision": "2026.10.03.0"}'),
        },
    )

    index = dictionary_updates.fetch_https_index("https://github.example/releases/latest/download/index.json")

    assert index == {"revision": "2026.10.03.0"}
    assert publisher.sent == [
        "https://github.example/releases/latest/download/index.json",
        "https://objects.example/index.json",
    ]


def test_fetcher_refuses_a_redirect_to_http(monkeypatch: pytest.MonkeyPatch) -> None:
    publisher = _serve(
        monkeypatch,
        {
            "https://github.example/index.json": (301, {"Location": "http://objects.example/index.json"}, b""),
            "http://objects.example/index.json": (200, {}, b'{"revision": "2"}'),
        },
    )

    with pytest.raises(ValueError):
        dictionary_updates.fetch_https_index("https://github.example/index.json")

    assert publisher.sent == ["https://github.example/index.json"]


def test_fetcher_keeps_the_vendored_size_cap(monkeypatch: pytest.MonkeyPatch) -> None:
    from anki_miner.services import resource_updates

    oversized = b'{"revision": "' + b"9" * resource_updates._MAX_REMOTE_INDEX_BYTES + b'"}'
    _serve(monkeypatch, {"https://publisher.example/index.json": (200, {}, oversized)})

    with pytest.raises(ValueError):
        dictionary_updates.fetch_https_index("https://publisher.example/index.json")


# ------------------------------------------------------------------- install


def _pinned_catalog_entry(monkeypatch: pytest.MonkeyPatch, source: Path, *, slot_id: str) -> YomitanResource:
    """A catalog entry pinning *source*, carrying JMdict's CC BY-SA attribution, served for its own id only."""

    with zipfile.ZipFile(source) as archive:
        members = archive.infolist()
        index = json.loads(archive.read("index.json"))
    jmdict = load_resource_catalog().get("jmdict-en-2026-07-17")
    assert isinstance(jmdict, YomitanResource)
    pinned = replace(
        jmdict,
        resource_id=f"{slot_id}-catalog",
        slot_id=slot_id,
        archive=replace(
            jmdict.archive,
            sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
            size_bytes=source.stat().st_size,
        ),
        dictionary=replace(
            jmdict.dictionary,
            title=index["title"],
            revision=index["revision"],
            member_count=len(members),
            uncompressed_bytes=sum(member.file_size for member in members),
        ),
    )
    real_find = resources.find_catalog_resource

    def find(resource_id: str):  # type: ignore[no-untyped-def]
        return ("ja", pinned) if resource_id == pinned.resource_id else real_find(resource_id)

    monkeypatch.setattr(resources, "find_catalog_resource", find)
    return pinned


def _updated_catalog_slot(home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> YomitanResource:
    """The catalog's ``pub`` dictionary after its publisher's update: new title, new revision, new bytes."""

    first = _dictionary_zip(tmp_path / "pub-v1.zip", _index("Pub.org [2026-07-09]", "2026.07.09.0", slot="pub"))
    pinned = _pinned_catalog_entry(monkeypatch, first, slot_id="pub")
    _import_dictionary(first, "pub", catalog_id=pinned.resource_id)
    assert _listed("pub")["catalogResourceId"] == pinned.resource_id
    assert _listed("pub")["publisherUpdate"] is False

    second = _dictionary_zip(
        tmp_path / "pub-v2.zip",
        _index("Pub.org [2026-10-03]", "2026.10.03.0", slot="pub"),
        term="犬",
        meaning="dog",
    )
    installed = decode_envelope(
        _install("dictionary", "pub", second, display_name="Pub.org [2026-10-03]"),
        expected_type="resource.dictionary.imported",
    ).payload
    assert installed["slotId"] == "pub"
    assert installed["catalogResourceId"] == pinned.resource_id
    assert installed["sourceName"] == "Pub.org [2026-10-03]"
    assert installed["sourceRevision"] == "2026.10.03.0"
    assert installed["attribution"] == [item.payload() for item in pinned.attribution]
    return pinned


def test_a_catalog_dictionary_updates_in_place_and_keeps_its_catalog_identity(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    pinned = _updated_catalog_slot(home, tmp_path, monkeypatch)
    slot = home / "dicts" / "pub"

    listed = _listed("pub")
    assert listed["valid"] is True
    assert listed["sourceName"] == "Pub.org [2026-10-03]"
    assert listed["sourceRevision"] == "2026.10.03.0"
    assert listed["catalogResourceId"] == pinned.resource_id
    assert listed["attribution"] == [item.payload() for item in pinned.attribution]
    assert listed["publisherUpdate"] is True
    assert listed["language"] == "ja"
    sidecar = json.loads((slot / "android-resource.json").read_text(encoding="utf-8"))
    assert sidecar["schemaVersion"] == 2
    assert sidecar["publisherUpdate"] is True
    assert _slot_language(slot) == "ja"
    dog = decode_envelope(
        resources.lookup_dictionary({"slotId": "pub", "term": "犬"}),
        expected_type="resource.dictionary.lookup.result",
    )
    assert "dog" in dog.payload["html"]
    assert not any((home / "resource-work" / "dictionary-backups").iterdir())


def test_a_forced_stale_schema_rebuild_of_an_updated_catalog_dictionary_succeeds(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Review Focus 3: startup recovery re-sends the slot's catalogResourceId with its own source.zip."""
    pinned = _updated_catalog_slot(home, tmp_path, monkeypatch)
    index = home / "dicts" / "pub" / "index.sqlite"
    connection = sqlite3.connect(index)
    try:
        connection.execute("UPDATE meta SET value = '4' WHERE key = 'schema_version'")
        connection.commit()
    finally:
        connection.close()
    stale = _listed("pub")
    assert stale["schemaOk"] is False
    assert stale["catalogResourceId"] == pinned.resource_id
    assert stale["rebuildSourcePath"] is not None

    # Exactly what ResourceManager.rebuildStaleDictionaries dispatches.
    rebuilt = decode_envelope(
        resources.import_dictionary(
            {
                "operationId": "rebuild-pub",
                "sourcePath": stale["rebuildSourcePath"],
                "slotId": "pub",
                "overwrite": True,
                "catalogResourceId": stale["catalogResourceId"],
                "language": stale["language"],
            }
        ),
        expected_type="resource.dictionary.imported",
    ).payload

    assert rebuilt["catalogResourceId"] == pinned.resource_id
    relisted = _listed("pub")
    assert relisted["schemaOk"] is True
    assert relisted["valid"] is True
    assert relisted["sourceRevision"] == "2026.10.03.0"
    assert relisted["catalogResourceId"] == pinned.resource_id
    assert relisted["attribution"] == [item.payload() for item in pinned.attribution]
    assert relisted["publisherUpdate"] is True


def test_an_update_keeps_the_slot_and_its_language_stamp(home: Path, tmp_path: Path) -> None:
    _import_dictionary(_dictionary_zip(tmp_path / "v1.zip", _index("Hebrew", "1", slot="heb")), "heb", language="he")

    _install(
        "dictionary", "heb", _dictionary_zip(tmp_path / "v2.zip", _index("Hebrew", "2", slot="heb")), language="he"
    )

    listed = _listed("heb")
    assert listed["sourceRevision"] == "2"
    assert listed["language"] == "he"
    assert listed["catalogResourceId"] is None
    assert listed["publisherUpdate"] is True
    assert _slot_language(home / "dicts" / "heb") == "he"


def test_an_update_for_another_language_than_the_slot_stamp_is_refused(home: Path, tmp_path: Path) -> None:
    _import_dictionary(_dictionary_zip(tmp_path / "v1.zip", _index("Hebrew", "1", slot="heb")), "heb", language="he")

    with pytest.raises(BridgeProtocolError) as refused:
        _install("dictionary", "heb", _dictionary_zip(tmp_path / "v2.zip", _index("Hebrew", "2", slot="heb")))

    assert refused.value.code == "resource_update_stale"
    assert _listed("heb")["sourceRevision"] == "1"


@pytest.mark.parametrize("kind", ["dictionary", "frequency", "pitch"])
def test_an_update_to_a_slot_the_app_does_not_own_is_refused(home: Path, tmp_path: Path, kind: str) -> None:
    root = {"dictionary": "dicts", "frequency": "freqs", "pitch": "pitch"}[kind]
    stray = home / root / "stray"
    stray.mkdir(parents=True)
    (stray / "index.sqlite").write_bytes(b"not an index")
    source = _dictionary_zip(tmp_path / "v2.zip", _index("Stray", "2", slot="stray"))

    for slot_id in ("stray", "absent"):
        with pytest.raises(BridgeProtocolError) as refused:
            _install(kind, slot_id, source)
        assert refused.value.code == "resource_update_stale"

    assert (stray / "index.sqlite").read_bytes() == b"not an index"
    assert not (home / root / "absent").exists()


def test_an_update_request_is_validated(home: Path, tmp_path: Path) -> None:
    source = tmp_path / "v2.zip"
    request = {
        "operationId": "update-x",
        "kind": "dictionary",
        "slotId": "pub",
        "sourcePath": str(source),
        "displayName": "Pub",
        "language": "ja",
    }
    for bad, code in (
        ({**request, "kind": "audio-pack"}, "invalid_resource_request"),
        ({**request, "displayName": " padded "}, "invalid_resource_request"),
        ({key: value for key, value in request.items() if key != "language"}, "invalid_resource_request"),
        ({**request, "sourcePath": "relative.zip"}, "invalid_resource_path"),
        ({**request, "language": "eo"}, "unsupported_language"),
    ):
        with pytest.raises(BridgeProtocolError) as invalid:
            dictionary_updates.install_update(bad)
        assert invalid.value.code == code


def test_a_frequency_update_replays_how_the_slot_was_built(
    home: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Desktop ``UpdatableResource.lemmatised``: a lemmatised list must not come back ranked by surface counts."""
    import anki_miner.services.frequency.lemmatize as lemmatize
    import anki_miner.services.frequency.source_importer as source_importer

    _import_local(
        "frequency", _frequency_zip(tmp_path / "v1.zip", _index("Freq", "1", slot="freq")), "freq", language="he"
    )
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

    updated = decode_envelope(
        _install(
            "frequency",
            "freq",
            _frequency_zip(tmp_path / "v2.zip", _index("Freq 2", "2", slot="freq")),
            language="he",
            display_name="Freq 2",
        ),
        expected_type="resource.frequency.imported",
    ).payload

    assert updated["sourceId"] == "freq"
    # A zip names itself: the new index.json's title, as the check reported it.
    assert updated["sourceName"] == "Freq 2"
    assert calls[0]["language"] == "he"
    assert calls[0]["declared_mode"] == "occurrence"
    assert callable(calls[0]["lemmatize"])
    assert built == [("he", home / "dicts")]
    assert _slot_language(home / "freqs" / "freq") == "he"


def test_a_pitch_update_replaces_the_slot_in_place(home: Path, tmp_path: Path) -> None:
    _import_local("pitch", _pitch_zip(tmp_path / "v1.zip", _index("Pitch", "1", slot="pitch")), "pitch")

    updated = decode_envelope(
        _install(
            "pitch",
            "pitch",
            _pitch_zip(tmp_path / "v2.zip", _index("Pitch 2", "2", slot="pitch")),
            display_name="Pitch 2",
        ),
        expected_type="resource.pitch.imported",
    ).payload

    assert updated["sourceId"] == "pitch"
    assert updated["sourceName"] == "Pitch 2"
    assert updated["sourceRevision"] == "2"
    assert _slot_language(home / "pitch" / "pitch") == "ja"


# ------------------------------------------------------------------ boundary


def test_boundary_routes_both_update_ops_and_forwards_callbacks_to_the_install(
    initialized_bridge_home: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    seen: list[tuple[str, object]] = []
    monkeypatch.setattr(
        dictionary_updates,
        "check_updates",
        lambda payload: seen.append(("check", None)) or encode_message("resource.updates.checked", {}),
    )

    def install(payload: dict[str, object], *, callbacks: object | None = None) -> str:
        seen.append(("install", callbacks))
        return encode_message("resource.dictionary.imported", {})

    monkeypatch.setattr(dictionary_updates, "install_update", install)
    callback = object()

    checked = boundary.dispatch(encode_message("resource.updates.check", {}), callback)
    installed = boundary.dispatch(encode_message("resource.update.install", {}), callback)

    assert decode_envelope(checked).message_type == "resource.updates.checked"
    assert decode_envelope(installed).message_type == "resource.dictionary.imported"
    assert seen == [("check", None), ("install", callback)]


def test_dictionary_updates_has_no_eager_engine_imports() -> None:
    source = Path(dictionary_updates.__file__).read_text(encoding="utf-8")
    prefix = source.split("\ndef ", 1)[0]
    assert "from anki_miner" not in prefix
    assert "import anki_miner" not in prefix
    assert "import requests" not in prefix
