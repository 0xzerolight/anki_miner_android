"""Guard the Kotlin mirror of the engine's own configuration defaults.

``EngineDefaults.kt`` restates a slice of ``AnkiMinerConfig`` so the settings screen can show the
value a blank field inherits. That display is only trustworthy while the two agree: a stale mirror
replaces an honest blank with a confidently wrong number, and an ``engine.lock`` bump that moves a
dataclass default is exactly when it would go stale.

Both sides are read with ``ast``/text parsing rather than imported, so this runs in the secretless
host job without the engine's runtime dependencies.
"""

from __future__ import annotations

import ast
import re
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[3]
CONFIG_PY = REPO_ROOT / "app/src/main/python/anki_miner/config/config.py"
DEFAULTS_KT = (
    REPO_ROOT
    / "app/src/main/kotlin/com/ankiminer/android/data/settings/EngineDefaults.kt"
)

# Kotlin constant -> engine dataclass field. Every entry is checked in both directions:
# a missing Kotlin constant and a missing engine field both fail.
MIRRORED_FIELDS = {
    "DECK_NAME": "anki_deck_name",
    "TAGS": "anki_tags",
    "AUDIO_PADDING_SECONDS": "audio_padding",
    "SCREENSHOT_OFFSET_SECONDS": "screenshot_offset",
    "SUBTITLE_OFFSET_SECONDS": "subtitle_offset",
    "AUDIO_BITRATE_KBPS": "audio_bitrate",
    "AUDIO_FORMAT": "audio_format",
    "ANIMATED_SCREENSHOTS_ENABLED": "screenshot_animated",
    "ANIMATED_SCREENSHOT_MATCH_AUDIO": "screenshot_animated_match_audio",
    "ANIMATED_SCREENSHOT_DURATION_SECONDS": "screenshot_animated_clip_duration",
    "ANIMATED_SCREENSHOT_QUALITY": "screenshot_animated_quality",
    "STRIP_SUBTITLE_ANNOTATIONS": "strip_subtitle_annotations",
    "SUBTITLE_REGEX_FILTER": "subtitle_regex_filter",
    "SUBTITLE_REGEX_REPLACEMENT": "subtitle_regex_replacement",
    "USE_SUBTITLE_REGEX_FILTER": "use_subtitle_regex_filter",
    "USE_KNOWN_WORDS_DATABASE": "use_known_words_db",
    "USE_BLACKLIST": "use_blacklist",
    "USE_WHITELIST": "use_whitelist",
    "EXCLUDE_HIRAGANA_ONLY": "exclude_hiragana_only_words",
    "EXCLUDE_KATAKANA_ONLY": "exclude_katakana_only_words",
    "BOLD_TARGET_IN_SENTENCE": "bold_target_in_sentence",
    "DEDUPLICATE_SENTENCES": "deduplicate_sentences",
    "USE_I_PLUS_ONE_FILTER": "use_i_plus_one_filter",
    "USE_SENTENCE_LENGTH_FILTER": "use_sentence_length_filter",
    "MAX_SENTENCE_DURATION_SECONDS": "max_sentence_duration_seconds",
    "MAX_SENTENCE_CHARACTERS": "max_sentence_chars",
    "MAX_FREQUENCY_RANK": "max_frequency_rank",
    "READING_MINIMUM_OCCURRENCE": "reading_min_occurrence",
    "MAX_PARALLEL_WORKERS": "max_parallel_workers",
    "PITCH_CATEGORY_FORMAT": "pitch_category_format",
}

# Kotlin enum entry -> the wire string the engine stores. Keeps the two enum-valued mirrors
# comparable with the plain literals.
KOTLIN_ENUM_WIRE_VALUES = {
    "AudioFormat.MP3": "mp3",
    "AudioFormat.OPUS": "opus",
    "PitchCategoryFormat.JAPANESE": "jp",
    "PitchCategoryFormat.ROMAJI": "romaji",
}

_KOTLIN_CONST = re.compile(
    r"^\s*(?:const\s+)?val\s+(?P<name>[A-Z][A-Z0-9_]*)\s*:\s*\w+\s*=\s*(?P<value>.+?)\s*$",
    re.MULTILINE,
)


def _engine_defaults() -> dict[str, object]:
    """Every ``AnkiMinerConfig`` field that has a literal default."""
    tree = ast.parse(CONFIG_PY.read_text(encoding="utf-8"))
    for node in ast.walk(tree):
        if isinstance(node, ast.ClassDef) and node.name == "AnkiMinerConfig":
            found: dict[str, object] = {}
            for statement in node.body:
                if not isinstance(statement, ast.AnnAssign):
                    continue
                if not isinstance(statement.target, ast.Name) or statement.value is None:
                    continue
                try:
                    found[statement.target.id] = ast.literal_eval(statement.value)
                except ValueError:
                    # Non-literal defaults (field factories, computed values) are not mirrored.
                    continue
            return found
    raise AssertionError(f"AnkiMinerConfig not found in {CONFIG_PY}")


def _kotlin_defaults() -> dict[str, object]:
    """Parse the mirror's constants into comparable Python values."""
    found: dict[str, object] = {}
    for match in _KOTLIN_CONST.finditer(DEFAULTS_KT.read_text(encoding="utf-8")):
        raw = match.group("value")
        if raw in KOTLIN_ENUM_WIRE_VALUES:
            value: object = KOTLIN_ENUM_WIRE_VALUES[raw]
        elif raw in ("true", "false"):
            value = raw == "true"
        elif raw.startswith('"') and raw.endswith('"'):
            value = raw[1:-1]
        else:
            try:
                value = int(raw) if "." not in raw else float(raw)
            except ValueError:
                raise AssertionError(
                    f"{match.group('name')} = {raw!r} is not a literal this guard can compare. "
                    "Keep EngineDefaults literal, or extend KOTLIN_ENUM_WIRE_VALUES."
                ) from None
        found[match.group("name")] = value
    return found


class EngineDefaultsMirrorTests(unittest.TestCase):
    def test_every_mirrored_constant_matches_the_engine(self) -> None:
        engine = _engine_defaults()
        kotlin = _kotlin_defaults()
        mismatches = []
        for constant, field in MIRRORED_FIELDS.items():
            self.assertIn(
                constant, kotlin, f"EngineDefaults.kt is missing {constant} ({field})"
            )
            self.assertIn(
                field, engine, f"AnkiMinerConfig no longer has {field} ({constant})"
            )
            if kotlin[constant] != engine[field]:
                mismatches.append(
                    f"  {constant} = {kotlin[constant]!r} but {field} = {engine[field]!r}"
                )
        self.assertEqual(
            [],
            mismatches,
            "EngineDefaults.kt has drifted from the engine. The settings screen shows these as\n"
            "the value a blank field inherits, so a stale mirror lies to the user:\n"
            + "\n".join(mismatches),
        )

    def test_mirror_declares_nothing_the_map_does_not_cover(self) -> None:
        """A new constant must be mapped, or it is guarded by nothing."""
        unmapped = sorted(set(_kotlin_defaults()) - set(MIRRORED_FIELDS))
        self.assertEqual(
            [],
            unmapped,
            f"EngineDefaults.kt declares {unmapped} with no entry in MIRRORED_FIELDS",
        )


if __name__ == "__main__":
    unittest.main()
