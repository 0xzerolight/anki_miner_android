from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from engine_sync import golden_exporter_overlay as overlay


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
        ].replace(overlay.DESKTOP_REVISION_LINE, overlay.ANDROID_REVISION_LINE)
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
            overlay.ANDROID_REVISION_LINE, overlay.DESKTOP_REVISION_LINE
        )
        self.assertEqual(self.contents["engine_golden_contract_v2.py"], reconstructed)

    def test_materializer_rejects_a_changed_desktop_source(self) -> None:
        (self.source / "prepare_golden_unidic.py").write_bytes(b"changed\n")
        with self.assertRaisesRegex(
            overlay.GoldenExporterOverlayError, "changed since review"
        ):
            self.materialize()

    def test_production_patch_targets_the_frozen_revision_exactly(self) -> None:
        self.assertIn(b"ba3b3cfbcc53e57a440c8b9f157209851408c62a", overlay.DESKTOP_REVISION_LINE)
        self.assertIn(b"40f2f5c714421585f68504f6027d85dc3843e90e", overlay.ANDROID_REVISION_LINE)
        self.assertEqual(
            "e49d93a50cc2ce54dc2e2335b158960eaaa0c84839f03c6b8d6d768081afc268",
            overlay.MATERIALIZED_SHA256["engine_golden_contract_v2.py"],
        )


if __name__ == "__main__":
    unittest.main()
