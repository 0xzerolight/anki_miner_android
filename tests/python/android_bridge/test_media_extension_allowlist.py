"""Cross-language guard: Kotlin's staged-media extension allowlist must cover every producer.

AnkiDroid names a stored media file after ``getExtensionFromMimeType(ContentResolver.getType(uri))``.
An extension missing from ``AnkiMediaExtensions`` is not rejected — it is staged as ``.stage``, whose
MIME is ``application/octet-stream``, which AnkiDroid stores as ``.bin`` (Issue #2). The producers are
three vendored Python constants plus Android's offline TTS, and nothing else couples them to the
Kotlin side, so an engine re-sync that widens one silently reintroduces the bug.

The constants are read with :mod:`ast` from the source files rather than imported:
``anki_miner.services.audio_fetch_common`` does a module-level ``import requests``, which is absent
from ``requirements-host-test.lock`` — the only lock CI installs. Importing it would either redden CI
or, via the ``pytest.importorskip`` idiom this suite uses elsewhere, skip in CI and guard nothing.
Reading Kotlin and Python source side by side follows ``test_tokenizer_selection.py``.

Every lookup below asserts it matched exactly once. A rename, a reformat, or a shape change must fail
red rather than silently narrowing what the guard covers.
"""

from __future__ import annotations

import ast
import re
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[3]
PYTHON_ROOT = PROJECT_ROOT / "app" / "src" / "main" / "python"

_KOTLIN_EXTENSIONS = PROJECT_ROOT / "app/src/main/kotlin/com/ankiminer/android/anki/provider/AnkiMediaExtensions.kt"
_AUDIO_FETCH_COMMON = PYTHON_ROOT / "anki_miner/services/audio_fetch_common.py"
_AUDIO_PACK_FORMATS = PYTHON_ROOT / "anki_miner/services/audio_packs/formats.py"
_YOMITAN_IMPORTER = PYTHON_ROOT / "anki_miner/services/dictionary/importers/yomitan_importer.py"

# Android offline TTS publishes WAV and has no shared constant to read: CACHE_PREFIX and
# PUBLISHED_FILENAME are private inside a private companion in CachedSentenceAudioSynthesizer, which
# is why CachedSentenceAudioSynthesizerTest re-states the pattern too.
_ANDROID_TTS_EXTENSIONS = {".wav"}


def _constant_node(source: Path, name: str) -> ast.expr:
    """Return the single assigned value node for a module-level constant."""
    tree = ast.parse(source.read_text(encoding="utf-8"), filename=str(source))
    matches = [
        node.value
        for node in tree.body
        if isinstance(node, ast.AnnAssign)
        and isinstance(node.target, ast.Name)
        and node.target.id == name
        and node.value is not None
    ] + [
        node.value
        for node in tree.body
        if isinstance(node, ast.Assign)
        and len(node.targets) == 1
        and isinstance(node.targets[0], ast.Name)
        and node.targets[0].id == name
    ]
    assert len(matches) == 1, f"expected exactly one module-level {name} in {source}, found {len(matches)}"
    return matches[0]


def _literal_collection(source: Path, name: str) -> set[str]:
    """Read a set/frozenset/dict-valued constant, unwrapping a frozenset(...) call."""
    node = _constant_node(source, name)
    if isinstance(node, ast.Call):
        # frozenset({...}) is a Call, not a literal, so literal_eval cannot read it directly.
        # Assert the callee and arity: `frozenset(A) | B` must fail rather than silently read A.
        assert isinstance(node.func, ast.Name), f"{name} in {source} is called on a non-name"
        assert node.func.id in {"frozenset", "set"}, f"{name} in {source} calls {node.func.id!r}"
        assert len(node.args) == 1 and not node.keywords, f"{name} in {source} has an unexpected arity"
        node = node.args[0]
    value = ast.literal_eval(node)
    if isinstance(value, dict):
        value = value.values()
    return {str(item) for item in value}


def _kotlin_extensions(name: str) -> set[str]:
    """Read a `linkedSetOf("a", "b", ...)` Kotlin val, which may wrap across lines."""
    source = _KOTLIN_EXTENSIONS.read_text(encoding="utf-8")
    # Anchored on the `val` declaration: the same names appear in the file's comments and KDoc, and a
    # looser pattern would match those too and trip the exactly-once assertion on a healthy file.
    matches = re.findall(
        rf"\bval\s+{re.escape(name)}\s*(?::[^=]*)?=\s*\n?\s*linkedSetOf\(([^)]*)\)",
        source,
    )
    assert len(matches) == 1, f"expected exactly one {name} linkedSetOf, found {len(matches)}"
    return set(re.findall(r'"([^"]+)"', matches[0]))


def _producer_audio_extensions() -> set[str]:
    downloaded = _literal_collection(_AUDIO_FETCH_COMMON, "AUDIO_MEDIA_TYPE_EXTENSIONS")
    packs = _literal_collection(_AUDIO_PACK_FORMATS, "AUDIO_EXTENSIONS")
    return {item.lstrip(".") for item in downloaded | packs | _ANDROID_TTS_EXTENSIONS}


def _producer_image_extensions() -> set[str]:
    whitelist = _literal_collection(_YOMITAN_IMPORTER, "_MEDIA_EXTENSION_WHITELIST")
    return {item.lstrip(".") for item in whitelist}


def test_kotlin_audio_allowlist_covers_every_audio_producer() -> None:
    covered = _kotlin_extensions("AUDIO_EXTENSIONS") | _kotlin_extensions("ALWAYS_FALLBACK_EXTENSIONS")
    missing = _producer_audio_extensions() - covered
    assert not missing, (
        f"AnkiMediaExtensions.AUDIO_EXTENSIONS is missing {sorted(missing)}; media with those "
        "suffixes stages as .stage and AnkiDroid stores it as .bin"
    )


def test_kotlin_image_allowlist_matches_the_dictionary_media_whitelist() -> None:
    # Equality, not a superset: a new upstream suffix must fail closed, and every exclusion must be a
    # deliberate ALWAYS_FALLBACK_EXTENSIONS entry carrying its API 26 baseline measurement.
    covered = _kotlin_extensions("IMAGE_EXTENSIONS") | _kotlin_extensions("ALWAYS_FALLBACK_EXTENSIONS")
    assert covered == _producer_image_extensions()


def test_always_fallback_extensions_never_evicts_a_downloaded_audio_format() -> None:
    # The exclusion set is image-only by construction. Excluding opus in particular would reintroduce
    # Issue #2 for local-audio-yomichan's default collection.
    excluded = _kotlin_extensions("ALWAYS_FALLBACK_EXTENSIONS")
    assert not excluded & _producer_audio_extensions()
