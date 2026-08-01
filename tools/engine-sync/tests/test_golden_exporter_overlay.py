from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from engine_sync import golden_exporter_overlay as overlay

PROJECT_ROOT = Path(__file__).resolve().parents[3]
LOCK_PATH = PROJECT_ROOT / "tools/engine-sync/engine.lock"
FIXTURE_PATH = PROJECT_ROOT / "golden/engine-v2.json"


def _attestation(content: bytes) -> tuple[str, str]:
    return hashlib.sha256(content).hexdigest(), overlay._git_blob_sha1(content)


class GoldenExporterOverlayTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source = self.root / "source/scripts"
        self.source.mkdir(parents=True)
        self.contents = {
            "dump_engine_goldens.py": b"import engine_golden_contract_v2\n",
            "engine_golden_contract_v2.py": (
                b"before\n" + overlay.DESKTOP_REVISION_LINE + b"\nafter\n"
            ),
            "prepare_golden_unidic.py": b"VALUE = 1\n",
        }
        for name, content in self.contents.items():
            (self.source / name).write_bytes(content)
        self.schema = b"{}\n"
        schema_path = self.root / "source/tests/fixtures/goldens/engine-v2.schema.json"
        schema_path.parent.mkdir(parents=True)
        schema_path.write_bytes(self.schema)
        self.attestations = {
            name: _attestation(content) for name, content in self.contents.items()
        }
        self.expected = dict(self.contents)
        self.expected["engine_golden_contract_v2.py"] = self.expected[
            "engine_golden_contract_v2.py"
        ].replace(overlay.DESKTOP_REVISION_LINE, overlay.android_revision_line())
        self.materialized_sha256 = {
            name: hashlib.sha256(content).hexdigest()
            for name, content in self.expected.items()
        }

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def materialize(self) -> Path:
        with mock.patch.object(
            overlay, "SOURCE_ATTESTATIONS", self.attestations
        ), mock.patch.object(
            overlay, "MATERIALIZED_SHA256", self.materialized_sha256
        ), mock.patch.object(overlay, "SCHEMA_ATTESTATION", _attestation(self.schema)):
            return overlay.materialize_golden_exporter(
                self.source / "dump_engine_goldens.py", self.root / "output/scripts"
            )

    def test_materializer_applies_only_the_reviewed_revision_change(self) -> None:
        exporter = self.materialize()
        self.assertEqual(self.root / "output/scripts/dump_engine_goldens.py", exporter)
        for name, expected in self.expected.items():
            actual = (self.root / "output/scripts" / name).read_bytes()
            self.assertEqual(expected, actual)
        self.assertEqual(
            self.schema,
            (self.root / "output/tests/fixtures/goldens/engine-v2.schema.json").read_bytes(),
        )
        reconstructed = self.expected["engine_golden_contract_v2.py"].replace(
            overlay.android_revision_line(), overlay.DESKTOP_REVISION_LINE
        )
        self.assertEqual(self.contents["engine_golden_contract_v2.py"], reconstructed)

    def test_materializer_rejects_a_changed_desktop_source(self) -> None:
        (self.source / "prepare_golden_unidic.py").write_bytes(b"changed\n")
        with self.assertRaisesRegex(
            overlay.GoldenExporterOverlayError, "changed since review"
        ):
            self.materialize()


class ProductionOverlayPinTests(unittest.TestCase):
    """Every expectation here is derived from engine.lock or the committed fixture."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.revision = LOCK_PATH.read_text(encoding="ascii").strip()
        fixture = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
        cls.fixture_files = fixture["provenance"]["tool"]["files_sha256"]

    def test_overlay_reads_the_repository_engine_lock(self) -> None:
        self.assertEqual(LOCK_PATH, overlay.LOCK_PATH)

    def test_android_revision_line_tracks_engine_lock(self) -> None:
        expected = b'PINNED_ENGINE_REVISION = "' + self.revision.encode("ascii") + b'"'
        self.assertEqual(expected, overlay.android_revision_line())
        self.assertNotEqual(overlay.DESKTOP_REVISION_LINE, overlay.android_revision_line())

    def test_materialized_hashes_match_the_committed_fixture(self) -> None:
        # The fixture records the hashes of the files the exporter actually ran,
        # so a stale overlay cannot describe the pin the fixture was derived at.
        self.assertEqual(self.fixture_files, overlay.MATERIALIZED_SHA256)

    def test_unpatched_sources_pass_through_to_the_fixture_unchanged(self) -> None:
        for name in ("dump_engine_goldens.py", "prepare_golden_unidic.py"):
            with self.subTest(name=name):
                self.assertEqual(
                    overlay.SOURCE_ATTESTATIONS[name][0], self.fixture_files[name]
                )
        # The one patched file must differ from its desktop source attestation.
        patched = "engine_golden_contract_v2.py"
        self.assertNotEqual(
            overlay.SOURCE_ATTESTATIONS[patched][0], self.fixture_files[patched]
        )


if __name__ == "__main__":
    unittest.main()
