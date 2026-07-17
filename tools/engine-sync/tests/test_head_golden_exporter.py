from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from engine_sync.head_golden_exporter import (
    HeadGoldenExporterError,
    materialize_desktop_head_exporter,
)


class HeadGoldenExporterTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.desktop = self.root / "desktop"
        self.desktop.mkdir()
        subprocess.run(["git", "init", "-q", str(self.desktop)], check=True)
        subprocess.run(
            ["git", "-C", str(self.desktop), "config", "user.email", "test@example.invalid"],
            check=True,
        )
        subprocess.run(
            ["git", "-C", str(self.desktop), "config", "user.name", "Test"],
            check=True,
        )
        scripts = self.desktop / "scripts"
        scripts.mkdir()
        (scripts / "dump_engine_goldens.py").write_text("# dumper\n", encoding="utf-8")
        (scripts / "engine_golden_contract_v2.py").write_text(
            'PINNED_ENGINE_REVISION = "1111111111111111111111111111111111111111"\n',
            encoding="utf-8",
        )
        (scripts / "prepare_golden_unidic.py").write_text("# unidic\n", encoding="utf-8")
        schema = self.desktop / "tests/fixtures/goldens/engine-v2.schema.json"
        schema.parent.mkdir(parents=True)
        schema.write_text("{}\n", encoding="utf-8")
        subprocess.run(["git", "-C", str(self.desktop), "add", "."], check=True)
        subprocess.run(
            ["git", "-C", str(self.desktop), "commit", "-qm", "fixture"], check=True
        )
        self.revision = subprocess.run(
            ["git", "-C", str(self.desktop), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_materializes_head_with_only_the_revision_guard_changed(self) -> None:
        exporter, revision = materialize_desktop_head_exporter(
            self.desktop, self.root / "output"
        )
        self.assertEqual(self.revision, revision)
        self.assertEqual(self.root / "output/scripts/dump_engine_goldens.py", exporter)
        companion = exporter.with_name("engine_golden_contract_v2.py").read_text(
            encoding="utf-8"
        )
        self.assertEqual(f'PINNED_ENGINE_REVISION = "{self.revision}"\n', companion)

    def test_rejects_dirty_source_and_an_ambiguous_revision_seam(self) -> None:
        (self.desktop / "untracked").write_text("dirty", encoding="utf-8")
        with self.assertRaisesRegex(HeadGoldenExporterError, "clean"):
            materialize_desktop_head_exporter(self.desktop, self.root / "dirty-output")
        (self.desktop / "untracked").unlink()
        companion = self.desktop / "scripts/engine_golden_contract_v2.py"
        companion.write_text("PINNED_ENGINE_REVISION = value\n", encoding="utf-8")
        subprocess.run(["git", "-C", str(self.desktop), "add", "."], check=True)
        subprocess.run(
            ["git", "-C", str(self.desktop), "commit", "-qm", "change seam"], check=True
        )
        with self.assertRaisesRegex(HeadGoldenExporterError, "unique revision seam"):
            materialize_desktop_head_exporter(self.desktop, self.root / "seam-output")


if __name__ == "__main__":
    unittest.main()
