from __future__ import annotations

import shutil
import stat
import sys
import tempfile
import unittest
from pathlib import Path

TOOL_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = TOOL_ROOT.parents[1]
sys.path.insert(0, str(TOOL_ROOT))

from anki_contract_codegen import unicode_core  # noqa: E402
from anki_contract_codegen.core import ContractError  # noqa: E402


class UnicodeContractCodegenTest(unittest.TestCase):
    def test_table_counts_and_outputs_are_byte_deterministic(self) -> None:
        tables = unicode_core.load_tables(REPO_ROOT)

        self.assertEqual(712, len(tables.category_c_ranges))
        self.assertEqual(10, len(tables.whitespace_ranges))
        self.assertEqual(388, len(tables.combining_ranges))
        self.assertEqual(73, len(tables.nfc_no_ranges))
        self.assertEqual(42, len(tables.nfc_maybe_ranges))
        self.assertEqual(2061, len(tables.decompositions))
        self.assertEqual(941, len(tables.compositions))

        python_first = unicode_core.generate_python(tables)
        kotlin_first = unicode_core.generate_kotlin(tables)
        self.assertEqual(python_first, unicode_core.generate_python(tables))
        self.assertEqual(kotlin_first, unicode_core.generate_kotlin(tables))
        self.assertEqual(
            python_first,
            (REPO_ROOT / unicode_core.PYTHON_OUTPUT_PATH).read_bytes(),
        )
        self.assertEqual(
            kotlin_first,
            (REPO_ROOT / unicode_core.KOTLIN_OUTPUT_PATH).read_bytes(),
        )

    def test_refresh_check_and_drift_detection_are_reproducible(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repo = Path(temporary_directory) / "repo"
            self._copy_inputs(repo)
            (repo / unicode_core.PYTHON_OUTPUT_PATH.parent).mkdir(parents=True)
            (repo / unicode_core.KOTLIN_OUTPUT_PATH.parent).mkdir(parents=True)

            unicode_core.refresh(repo)
            python_output = repo / unicode_core.PYTHON_OUTPUT_PATH
            kotlin_output = repo / unicode_core.KOTLIN_OUTPUT_PATH
            first_python = python_output.read_bytes()
            first_kotlin = kotlin_output.read_bytes()
            self.assertEqual(0o644, stat.S_IMODE(python_output.stat().st_mode))
            self.assertEqual(0o644, stat.S_IMODE(kotlin_output.stat().st_mode))
            unicode_core.check(repo)

            unicode_core.refresh(repo)
            self.assertEqual(first_python, python_output.read_bytes())
            self.assertEqual(first_kotlin, kotlin_output.read_bytes())

            drifted = first_python + b"# local drift\n"
            python_output.write_bytes(drifted)
            with self.assertRaisesRegex(
                ContractError,
                "generated Python Unicode contract drifted",
            ):
                unicode_core.check(repo)
            self.assertEqual(drifted, python_output.read_bytes())

    def test_pinned_input_hashes_are_enforced(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            repo = Path(temporary_directory) / "repo"
            self._copy_inputs(repo)
            unicode_data = repo / unicode_core.UNICODE_ROOT / "UnicodeData.txt"
            original = unicode_data.read_bytes()
            unicode_data.write_bytes(b"X" + original[1:])

            with self.assertRaisesRegex(
                ContractError,
                "Unicode input hash drifted: UnicodeData.txt",
            ):
                unicode_core.load_tables(repo)

    def test_generated_runtimes_do_not_delegate_to_host_unicode_tables(self) -> None:
        tables = unicode_core.load_tables(REPO_ROOT)
        python_source = unicode_core.generate_python(tables).decode("utf-8")
        kotlin_source = unicode_core.generate_kotlin(tables).decode("utf-8")

        for forbidden in (
            "import unicodedata",
            "from unicodedata",
            ".isspace(",
            ".strip(",
        ):
            with self.subTest(runtime="Python", forbidden=forbidden):
                self.assertNotIn(forbidden, python_source)
        for forbidden in (
            "java.lang.Character",
            "java.text.Normalizer",
            "Character.getType",
            "Normalizer.normalize",
            ".trim(",
        ):
            with self.subTest(runtime="Kotlin", forbidden=forbidden):
                self.assertNotIn(forbidden, kotlin_source)

    @staticmethod
    def _copy_inputs(repo: Path) -> None:
        source = REPO_ROOT / unicode_core.UNICODE_ROOT
        destination = repo / unicode_core.UNICODE_ROOT
        destination.parent.mkdir(parents=True)
        shutil.copytree(source, destination)


if __name__ == "__main__":
    unittest.main()
