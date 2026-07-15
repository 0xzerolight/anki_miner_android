from __future__ import annotations

import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from engine_sync.core import (
    EngineSyncError,
    build_snapshot,
    check_destination,
    discover_source_repo,
    sync_destination,
)


def _run(*args: str, cwd: Path) -> str:
    result = subprocess.run(args, cwd=cwd, check=True, capture_output=True, text=True)
    return result.stdout.strip()


class EngineSyncTests(unittest.TestCase):
    def setUp(self) -> None:
        self._temporary = tempfile.TemporaryDirectory()
        self.root = Path(self._temporary.name)

    def tearDown(self) -> None:
        self._temporary.cleanup()

    def _fixture(self, root_source: str | None = None) -> dict[str, Path]:
        repo = self.root / "desktop"
        (repo / "anki_miner").mkdir(parents=True)
        (repo / "anki_miner/__init__.py").write_text("", encoding="utf-8")
        (repo / "anki_miner/dep.py").write_text("VALUE = 1\n", encoding="utf-8")
        (repo / "anki_miner/root.py").write_text(
            root_source
            or (
                "from typing import TYPE_CHECKING\n"
                "from PyQt6.QtCore import QCoreApplication\n"
                "from anki_miner import dep\n"
                "if TYPE_CHECKING:\n"
                "    from anki_miner.services.youtube_fetcher import YouTubeFetcherService\n"
            ),
            encoding="utf-8",
        )
        (repo / "anki_miner/data.txt").write_text("asset\n", encoding="utf-8")
        (repo / "LICENSE").write_text("license\n", encoding="utf-8")
        _run("git", "init", "-q", cwd=repo)
        _run("git", "config", "user.email", "tests@example.invalid", cwd=repo)
        _run("git", "config", "user.name", "Engine Sync Tests", cwd=repo)
        _run("git", "add", ".", cwd=repo)
        _run("git", "commit", "-qm", "fixture", cwd=repo)
        revision = _run("git", "rev-parse", "HEAD", cwd=repo)

        tooling = self.root / "tooling"
        overlay = tooling / "overrides/PyQt6"
        overlay.mkdir(parents=True)
        (overlay / "__init__.py").write_text("", encoding="utf-8")
        (overlay / "QtCore.py").write_text(
            "class QCoreApplication: pass\n", encoding="utf-8"
        )
        lock = tooling / "engine.lock"
        lock.write_text(revision + "\n", encoding="ascii")
        composition = tooling / "composition.toml"
        composition.write_text(
            """format_version = 1
roots = ["anki_miner.root"]
assets = ["anki_miner/data.txt"]
protected_verbatim = ["anki_miner.root"]
allowed_external = []
allowed_deferred_external = []
allowed_stdlib = ["typing"]
local_only_imports = ["PyQt6"]
forbidden_imports = ["gtts", "mpv", "yt_dlp", "anki_miner.services.youtube_fetcher"]
overlay_allowlist = ["PyQt6/QtCore.py", "PyQt6/__init__.py"]
[[mapped_assets]]
source = "LICENSE"
destination = "anki_miner/LICENSE"
[[forbidden_type_checking_exceptions]]
importer = "anki_miner.root"
target = "anki_miner.services.youtube_fetcher"
""",
            encoding="utf-8",
        )
        return {
            "repo": repo,
            "lock": lock,
            "composition": composition,
            "overlays": tooling / "overrides",
        }

    def _snapshot(self, fixture: dict[str, Path]):
        return build_snapshot(
            source_repo=fixture["repo"],
            lock_path=fixture["lock"],
            composition_path=fixture["composition"],
            overlays_path=fixture["overlays"],
        )

    def _drop_type_checking_exception(self, fixture: dict[str, Path]) -> None:
        composition = fixture["composition"]
        composition.write_text(
            composition.read_text(encoding="utf-8").replace(
                '[[forbidden_type_checking_exceptions]]\nimporter = "anki_miner.root"\n'
                'target = "anki_miner.services.youtube_fetcher"\n',
                "",
            ),
            encoding="utf-8",
        )

    def test_closure_uses_overlay_and_records_exact_origins(self) -> None:
        snapshot = self._snapshot(self._fixture())

        self.assertEqual(
            snapshot.modules,
            (
                "PyQt6",
                "PyQt6.QtCore",
                "anki_miner",
                "anki_miner.dep",
                "anki_miner.root",
            ),
        )
        self.assertEqual(snapshot.files["PyQt6/QtCore.py"].origin, "overlay")
        self.assertEqual(snapshot.files["anki_miner/root.py"].origin, "desktop")
        self.assertEqual(snapshot.files["anki_miner/LICENSE"].source_path, "LICENSE")
        self.assertEqual(snapshot.files["anki_miner/LICENSE"].content, b"license\n")
        self.assertNotIn("anki_miner/services/youtube_fetcher.py", snapshot.files)

        manifest = json.loads(snapshot.manifest_bytes())
        self.assertEqual(manifest["engine_revision"], snapshot.revision)
        self.assertEqual(
            manifest["files"]["anki_miner/root.py"]["sha256"],
            hashlib.sha256(snapshot.files["anki_miner/root.py"].content).hexdigest(),
        )
        self.assertEqual(
            manifest["files"]["anki_miner/root.py"]["source"],
            "anki_miner/root.py",
        )

    def test_sync_check_detects_and_repairs_direct_edits(self) -> None:
        snapshot = self._snapshot(self._fixture())
        destination = self.root / "vendor"
        sync_destination(destination, snapshot)
        self.assertEqual(check_destination(destination, snapshot), ())

        (destination / "anki_miner/root.py").write_text("edited\n", encoding="utf-8")
        (destination / "anki_miner/unexpected.py").write_text("", encoding="utf-8")
        (destination / "anki_miner/__pycache__").mkdir()
        (destination / "anki_miner/__pycache__/root.cpython-313.pyc").write_bytes(
            b"cache"
        )
        (destination / "PyQt6/QtCore.py").unlink()
        self.assertEqual(
            check_destination(destination, snapshot),
            (
                "missing PyQt6/QtCore.py",
                "unexpected anki_miner/unexpected.py",
                "modified anki_miner/root.py",
            ),
        )

        sync_destination(destination, snapshot)
        self.assertEqual(check_destination(destination, snapshot), ())

    def test_runtime_forbidden_import_is_rejected_semantically(self) -> None:
        fixture = self._fixture("import gtts\n")
        with self.assertRaisesRegex(EngineSyncError, r"forbidden import gtts"):
            self._snapshot(fixture)

    def test_literal_dynamic_forbidden_import_is_rejected(self) -> None:
        fixture = self._fixture(
            "import importlib\n\n"
            "def load():\n"
            "    return importlib.import_module('gtts')\n"
        )
        fixture["composition"].write_text(
            fixture["composition"]
            .read_text(encoding="utf-8")
            .replace(
                'allowed_stdlib = ["typing"]',
                'allowed_stdlib = ["importlib", "typing"]',
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(EngineSyncError, r"forbidden import gtts"):
            self._snapshot(fixture)

    def test_missing_internal_import_does_not_fall_back_to_package(self) -> None:
        fixture = self._fixture("import anki_miner.does_not_exist\n")
        with self.assertRaisesRegex(
            EngineSyncError, r"unresolved import anki_miner.does_not_exist"
        ):
            self._snapshot(fixture)

    def test_missing_from_import_member_is_rejected(self) -> None:
        fixture = self._fixture("from anki_miner import missing\n")
        with self.assertRaisesRegex(
            EngineSyncError, r"unresolved internal import member anki_miner.missing"
        ):
            self._snapshot(fixture)

    def test_defined_from_import_member_is_accepted(self) -> None:
        fixture = self._fixture(
            "from PyQt6.QtCore import QCoreApplication\n"
            "from anki_miner.dep import VALUE\n"
        )
        self._drop_type_checking_exception(fixture)
        snapshot = self._snapshot(fixture)
        self.assertIn("anki_miner.dep", snapshot.modules)

    def test_non_literal_dynamic_import_is_rejected(self) -> None:
        fixture = self._fixture(
            "from importlib import import_module as load\n\n"
            "def dynamic(name):\n"
            "    return load(name)\n"
        )
        fixture["composition"].write_text(
            fixture["composition"]
            .read_text(encoding="utf-8")
            .replace(
                'allowed_stdlib = ["typing"]',
                'allowed_stdlib = ["importlib", "typing"]',
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(EngineSyncError, "dynamic import target must be"):
            self._snapshot(fixture)

    def test_deferred_only_external_cannot_be_imported_eagerly(self) -> None:
        fixture = self._fixture("import numpy\n")
        fixture["composition"].write_text(
            fixture["composition"]
            .read_text(encoding="utf-8")
            .replace(
                "allowed_deferred_external = []",
                'allowed_deferred_external = ["numpy"]',
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(EngineSyncError, "unresolved import numpy"):
            self._snapshot(fixture)

    def test_protected_module_cannot_be_overlaid(self) -> None:
        fixture = self._fixture()
        protected = fixture["overlays"] / "anki_miner/root.py"
        protected.parent.mkdir(parents=True)
        protected.write_text("VALUE = 'forked'\n", encoding="utf-8")
        fixture["composition"].write_text(
            fixture["composition"]
            .read_text(encoding="utf-8")
            .replace(
                'overlay_allowlist = ["PyQt6/QtCore.py", "PyQt6/__init__.py"]',
                'overlay_allowlist = ["PyQt6/QtCore.py", "PyQt6/__init__.py", "anki_miner/root.py"]',
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(
            EngineSyncError, r"protected module cannot be overlaid"
        ):
            self._snapshot(fixture)

    def test_pyqt_must_be_satisfied_by_the_overlay(self) -> None:
        fixture = self._fixture()
        (fixture["overlays"] / "PyQt6/QtCore.py").unlink()
        (fixture["overlays"] / "PyQt6/__init__.py").unlink()
        with self.assertRaisesRegex(
            EngineSyncError, r"allowlisted overlay files are missing"
        ):
            self._snapshot(fixture)

    def test_unallowlisted_and_unused_overlays_are_rejected(self) -> None:
        fixture = self._fixture()
        extra = fixture["overlays"] / "anki_miner/typo.py"
        extra.parent.mkdir(parents=True)
        extra.write_text("", encoding="utf-8")
        with self.assertRaisesRegex(
            EngineSyncError, "overlay files are not allowlisted"
        ):
            self._snapshot(fixture)

        extra.unlink()
        (fixture["repo"] / "anki_miner/root.py").write_text(
            "VALUE = 1\n", encoding="utf-8"
        )
        _run("git", "add", ".", cwd=fixture["repo"])
        _run("git", "commit", "-qm", "drop overlay import", cwd=fixture["repo"])
        fixture["lock"].write_text(
            _run("git", "rev-parse", "HEAD", cwd=fixture["repo"]) + "\n",
            encoding="ascii",
        )
        self._drop_type_checking_exception(fixture)
        with self.assertRaisesRegex(EngineSyncError, "allowlisted overlays are unused"):
            self._snapshot(fixture)

    def test_destination_and_manifest_symlinks_are_rejected(self) -> None:
        snapshot = self._snapshot(self._fixture())
        real_destination = self.root / "real-vendor"
        real_destination.mkdir()
        linked_destination = self.root / "linked-vendor"
        linked_destination.symlink_to(real_destination, target_is_directory=True)
        with self.assertRaisesRegex(
            EngineSyncError, "destination may not be a symlink"
        ):
            sync_destination(linked_destination, snapshot)

        manifest_target = self.root / "manifest-target"
        manifest_target.write_text("{}", encoding="utf-8")
        (real_destination / ".engine-sync-manifest.json").symlink_to(manifest_target)
        with self.assertRaisesRegex(EngineSyncError, "manifest may not be a symlink"):
            check_destination(real_destination, snapshot)

    def test_source_discovery_honors_environment_override(self) -> None:
        fixture = self._fixture()
        self.assertEqual(
            discover_source_repo(
                self.root, {"ANKI_MINER_DESKTOP_REPO": str(fixture["repo"])}
            ),
            fixture["repo"].resolve(),
        )

    def test_manifest_is_byte_deterministic(self) -> None:
        fixture = self._fixture()
        first = self._snapshot(fixture)
        second = self._snapshot(fixture)
        self.assertEqual(first.manifest_bytes(), second.manifest_bytes())
        self.assertEqual(first.files, second.files)


if __name__ == "__main__":
    unittest.main()
