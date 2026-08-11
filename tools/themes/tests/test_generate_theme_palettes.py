from __future__ import annotations

import importlib.util
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parents[3]
PALETTES_DIRECTORY = REPO_ROOT / "tools/themes/palettes"
GENERATOR = REPO_ROOT / "tools/themes/generate_theme_palettes.py"
GENERATED_KOTLIN = REPO_ROOT / "app/src/main/kotlin/com/ankiminer/android/ui/theme/generated/ThemePalettes.kt"
SYNC_TOOL = REPO_ROOT / "tools/themes/sync_themes.py"
SYNC_SPEC = importlib.util.spec_from_file_location("sync_themes_test", SYNC_TOOL)
assert SYNC_SPEC is not None and SYNC_SPEC.loader is not None
sync_themes = importlib.util.module_from_spec(SYNC_SPEC)
SYNC_SPEC.loader.exec_module(sync_themes)

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

    @staticmethod
    def _git_result(
        command: list[str],
        payload: bytes,
        *,
        text: bool,
    ) -> subprocess.CompletedProcess[bytes] | subprocess.CompletedProcess[str]:
        if text:
            return subprocess.CompletedProcess(
                command,
                0,
                stdout=payload.decode("utf-8"),
                stderr="",
            )
        return subprocess.CompletedProcess(command, 0, stdout=payload, stderr=b"")

    def test_theme_sync_rejects_dirty_desktop_before_writing(self) -> None:
        revision = "a" * 40
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            desktop = root / "desktop"
            source = desktop / sync_themes.DESKTOP_THEMES_RELATIVE_PATH
            source.mkdir(parents=True)
            (source / "dark.json").write_bytes(b"dirty worktree")
            palettes = root / "palettes"
            palettes.mkdir()
            (palettes / "dark.json").write_bytes(b"vendored")
            lock = root / "themes.lock"
            lock.write_text("b" * 40 + "\n", encoding="ascii")

            def fake_run(command: list[str], **kwargs: object):
                if "rev-parse" in command:
                    payload = f"{revision}\n".encode()
                elif "status" in command:
                    payload = b" M anki_miner/gui/resources/styles/themes/dark.json\n"
                else:
                    self.fail(f"unexpected Git command: {command}")
                return self._git_result(
                    command,
                    payload,
                    text=bool(kwargs.get("text")),
                )

            with (
                mock.patch.object(sync_themes, "PALETTES_DIRECTORY", palettes),
                mock.patch.object(sync_themes, "THEMES_LOCK_PATH", lock),
                mock.patch.object(sync_themes.subprocess, "run", side_effect=fake_run),
                self.assertRaisesRegex(sync_themes.ThemeSyncError, "clean"),
            ):
                sync_themes.sync(desktop)

            self.assertEqual(b"vendored", (palettes / "dark.json").read_bytes())
            self.assertEqual("b" * 40 + "\n", lock.read_text(encoding="ascii"))

    def test_theme_check_uses_the_exact_locked_git_tree(self) -> None:
        revision = "a" * 40
        relative = (sync_themes.DESKTOP_THEMES_RELATIVE_PATH / "dark.json").as_posix()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            desktop = root / "desktop"
            source = desktop / sync_themes.DESKTOP_THEMES_RELATIVE_PATH
            source.mkdir(parents=True)
            (source / "dark.json").write_bytes(b"dirty worktree")
            palettes = root / "palettes"
            palettes.mkdir()
            (palettes / "dark.json").write_bytes(b"committed bytes")
            lock = root / "themes.lock"
            lock.write_text(f"{revision}\n", encoding="ascii")

            def fake_run(command: list[str], **kwargs: object):
                if "ls-tree" in command:
                    payload = f"{relative}\0".encode()
                elif "show" in command:
                    payload = b"committed bytes"
                else:
                    self.fail(f"unexpected Git command: {command}")
                return self._git_result(
                    command,
                    payload,
                    text=bool(kwargs.get("text")),
                )

            with (
                mock.patch.object(sync_themes, "PALETTES_DIRECTORY", palettes),
                mock.patch.object(sync_themes, "THEMES_LOCK_PATH", lock),
                mock.patch.object(sync_themes.subprocess, "run", side_effect=fake_run),
            ):
                drift = sync_themes.check(desktop)

        self.assertEqual([], drift)

    def test_theme_sync_copies_the_exact_head_git_tree(self) -> None:
        revision = "a" * 40
        relative = (sync_themes.DESKTOP_THEMES_RELATIVE_PATH / "dark.json").as_posix()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            desktop = root / "desktop"
            source = desktop / sync_themes.DESKTOP_THEMES_RELATIVE_PATH
            source.mkdir(parents=True)
            (source / "dark.json").write_bytes(b"mutable worktree")
            palettes = root / "palettes"
            lock = root / "themes.lock"

            def fake_run(command: list[str], **kwargs: object):
                if "rev-parse" in command:
                    payload = f"{revision}\n".encode()
                elif "status" in command:
                    payload = b""
                elif "ls-tree" in command:
                    payload = f"{relative}\0".encode()
                elif "show" in command:
                    payload = b"committed bytes"
                else:
                    self.fail(f"unexpected Git command: {command}")
                return self._git_result(
                    command,
                    payload,
                    text=bool(kwargs.get("text")),
                )

            with (
                mock.patch.object(sync_themes, "PALETTES_DIRECTORY", palettes),
                mock.patch.object(sync_themes, "THEMES_LOCK_PATH", lock),
                mock.patch.object(sync_themes.subprocess, "run", side_effect=fake_run),
            ):
                sync_themes.sync(desktop)

            self.assertEqual(b"committed bytes", (palettes / "dark.json").read_bytes())
            self.assertEqual(f"{revision}\n", lock.read_text(encoding="ascii"))


if __name__ == "__main__":
    unittest.main()
