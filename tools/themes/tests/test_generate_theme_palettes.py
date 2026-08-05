from __future__ import annotations

import json
import re
import subprocess
import sys
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
PALETTES_DIRECTORY = REPO_ROOT / "tools/themes/palettes"
GENERATOR = REPO_ROOT / "tools/themes/generate_theme_palettes.py"
GENERATED_KOTLIN = REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/ui/theme/generated/ThemePalettes.kt"

# Keep this set aligned with desktop's theme.py REQUIRED_COLOR_KEYS.
REQUIRED_COLOR_KEYS = frozenset(
    {
        "primary",
        "primary-hover",
        "primary-pressed",
        "primary-light",
        "primary-dark",
        "secondary",
        "background",
        "surface",
        "surface-hover",
        "surface-alt",
        "text",
        "text-muted",
        "text-disabled",
        "text-on-primary",
        "border",
        "border-focus",
        "border-subtle",
        "disabled",
        "input-bg",
        "input-disabled-bg",
        "error",
        "error-hover",
        "success",
        "warning",
        "info",
        "scrollbar",
        "scrollbar-hover",
        "tooltip-bg",
        "tooltip-text",
        "tooltip-border",
        "divider",
        "update-banner-bg",
        "update-banner-text",
        "decorative",
        "badge-success-bg",
        "badge-success-text",
        "badge-warning-bg",
        "badge-warning-text",
        "badge-error-bg",
        "badge-error-text",
        "badge-info-bg",
        "badge-info-text",
        "badge-pending-bg",
        "badge-pending-text",
        "table-selected-bg",
        "table-selected-text",
    }
)
HEX_COLOR = re.compile(r"#[0-9a-fA-F]{6}")


def _palette_paths() -> list[Path]:
    return sorted(PALETTES_DIRECTORY.glob("*.json"))


class ThemePaletteGeneratorTest(unittest.TestCase):
    """`unittest discover` is how every tools/ suite runs; pytest is not on the host path."""

    def test_vendored_palettes_match_desktop_color_contract(self) -> None:
        paths = _palette_paths()

        self.assertEqual(29, len(paths))
        for path in paths:
            with self.subTest(palette=path.stem):
                palette = json.loads(path.read_text(encoding="utf-8"))
                self.assertIsInstance(palette.get("name"), str)
                self.assertTrue(palette["name"].strip())
                self.assertIsInstance(palette.get("colors"), dict)
                self.assertEqual(REQUIRED_COLOR_KEYS, set(palette["colors"]))
                for slot, color in palette["colors"].items():
                    with self.subTest(slot=slot):
                        self.assertIsInstance(color, str)
                        self.assertTrue(HEX_COLOR.fullmatch(color), color)
                        int(color[1:], 16)

    def test_generator_check_accepts_committed_kotlin(self) -> None:
        result = subprocess.run(
            [sys.executable, str(GENERATOR), "--check"],
            cwd=REPO_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )

        self.assertEqual(0, result.returncode, result.stderr)

    def test_generated_kotlin_contains_every_vendored_palette_key(self) -> None:
        generated = GENERATED_KOTLIN.read_text(encoding="utf-8")

        for path in _palette_paths():
            with self.subTest(palette=path.stem):
                self.assertIn(f'key = "{path.stem}"', generated)

    def test_generated_kotlin_has_real_newlines(self) -> None:
        generated = GENERATED_KOTLIN.read_text(encoding="utf-8")

        self.assertNotIn("\\n", generated)


if __name__ == "__main__":
    unittest.main()
