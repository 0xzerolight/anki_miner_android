from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from engine_sync.head_golden_exporter import (
    HeadGoldenExporterError,
    materialize_desktop_head_exporter,
)

RUNNER_PATH = Path(__file__).resolve().parents[1] / "run_head_goldens_v2.py"
RUNNER_SPEC = importlib.util.spec_from_file_location(
    "run_head_goldens_v2_test",
    RUNNER_PATH,
)
assert RUNNER_SPEC is not None and RUNNER_SPEC.loader is not None
run_head_goldens_v2 = importlib.util.module_from_spec(RUNNER_SPEC)
RUNNER_SPEC.loader.exec_module(run_head_goldens_v2)


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


class HeadGoldenSemanticDriftTests(unittest.TestCase):
    @staticmethod
    def _fixture() -> dict[str, object]:
        return {
            "schema_version": 2,
            "section_status": {
                "cards": {"state": "implemented"},
                "tokenization": {"state": "implemented"},
            },
            "cases": {
                "cards": [{"id": "card-one", "output": {"value": 1}}],
                "dictionaries": [{"queries": [{"id": "lookup", "output": 1}]}],
                "tokenization": [
                    {"id": "variant-yaru", "tokens": [{"surface": "殺る"}]}
                ],
            },
            "provenance": {"engine": {"revision": "a" * 40}},
        }

    def _report(
        self,
        committed: dict[str, object],
        derived: dict[str, object],
    ) -> tuple[tuple[str, ...], str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            committed_path = root / "committed.json"
            derived_path = root / "derived.json"
            committed_path.write_text(json.dumps(committed), encoding="utf-8")
            derived_path.write_text(json.dumps(derived), encoding="utf-8")
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                drift = run_head_goldens_v2.report_semantic_drift(
                    committed_path,
                    derived_path,
                )
        return drift, output.getvalue()

    def test_runner_reports_no_drift_for_provenance_only_changes(self) -> None:
        committed = self._fixture()
        derived = json.loads(json.dumps(committed))
        derived["provenance"]["engine"]["revision"] = "b" * 40

        drift, output = self._report(committed, derived)

        self.assertEqual((), drift)
        self.assertEqual("desktop HEAD semantic drift: none\n", output)

    def test_runner_reports_changed_case_ids_and_status_sections(self) -> None:
        committed = self._fixture()
        derived = json.loads(json.dumps(committed))
        derived["section_status"]["cards"]["state"] = "staged"
        derived["cases"]["tokenization"][0]["tokens"][0]["surface"] = "遣る"
        derived["cases"]["dictionaries"][0]["queries"][0]["output"] = 2

        drift, output = self._report(committed, derived)

        self.assertEqual(
            (
                "section_status.cards",
                "cases.dictionaries",
                "cases.tokenization.variant-yaru",
            ),
            drift,
        )
        self.assertEqual(
            "desktop HEAD semantic drift detected:\n"
            "  section_status.cards\n"
            "  cases.dictionaries\n"
            "  cases.tokenization.variant-yaru\n",
            output,
        )

    def test_runner_main_compares_output_with_the_committed_fixture(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            python = root / "python"
            python.write_text("#!/bin/sh\n", encoding="utf-8")
            python.chmod(0o755)
            desktop = root / "desktop"
            desktop.mkdir()
            dicdir = root / "dicdir"
            dicdir.mkdir()
            exporter = root / "dump_engine_goldens.py"
            output = root / "derived.json"
            arguments = [
                str(RUNNER_PATH),
                "--python",
                str(python),
                "--desktop-root",
                str(desktop),
                "--dicdir",
                str(dicdir),
                "--output",
                str(output),
            ]

            with (
                mock.patch.object(run_head_goldens_v2.sys, "argv", arguments),
                mock.patch.object(
                    run_head_goldens_v2,
                    "materialize_desktop_head_exporter",
                    return_value=(exporter, "a" * 40),
                ),
                mock.patch.object(
                    run_head_goldens_v2.subprocess,
                    "run",
                    return_value=subprocess.CompletedProcess(arguments, 0, "", ""),
                ),
                mock.patch.object(
                    run_head_goldens_v2,
                    "report_semantic_drift",
                    return_value=(),
                ) as report,
            ):
                result = run_head_goldens_v2.main()

            self.assertEqual(0, result)
            report.assert_called_once_with(
                RUNNER_PATH.parents[2] / "golden/engine-v2.json",
                output.absolute(),
            )

    def test_runner_rejects_the_committed_fixture_as_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            python = root / "python"
            python.write_text("#!/bin/sh\n", encoding="utf-8")
            python.chmod(0o755)
            desktop = root / "desktop"
            desktop.mkdir()
            dicdir = root / "dicdir"
            dicdir.mkdir()
            output = RUNNER_PATH.parents[2] / "golden/engine-v2.json"
            arguments = [
                str(RUNNER_PATH),
                "--python",
                str(python),
                "--desktop-root",
                str(desktop),
                "--dicdir",
                str(dicdir),
                "--output",
                str(output),
            ]
            warning = io.StringIO()

            with (
                mock.patch.object(run_head_goldens_v2.sys, "argv", arguments),
                mock.patch.object(
                    run_head_goldens_v2,
                    "materialize_desktop_head_exporter",
                    return_value=(root / "exporter.py", "a" * 40),
                ) as materialize,
                mock.patch.object(
                    run_head_goldens_v2.subprocess,
                    "run",
                    return_value=subprocess.CompletedProcess(arguments, 0, "", ""),
                ),
                mock.patch.object(
                    run_head_goldens_v2,
                    "report_semantic_drift",
                    return_value=(),
                ),
                contextlib.redirect_stderr(warning),
            ):
                result = run_head_goldens_v2.main()

            self.assertEqual(1, result)
            self.assertIn("--output must not replace", warning.getvalue())
            materialize.assert_not_called()

    def test_output_alias_detection_covers_symlinks_and_hardlinks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture = root / "fixture.json"
            fixture.write_text("{}", encoding="utf-8")
            symlink = root / "symlink.json"
            symlink.symlink_to(fixture)
            hardlink = root / "hardlink.json"
            hardlink.hardlink_to(fixture)
            distinct = root / "distinct.json"
            distinct.write_text("{}", encoding="utf-8")

            self.assertTrue(run_head_goldens_v2.paths_alias(fixture, fixture))
            self.assertTrue(run_head_goldens_v2.paths_alias(symlink, fixture))
            self.assertTrue(run_head_goldens_v2.paths_alias(hardlink, fixture))
            self.assertFalse(run_head_goldens_v2.paths_alias(distinct, fixture))


if __name__ == "__main__":
    unittest.main()
