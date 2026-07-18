from __future__ import annotations

import platform
import stat
import sys
import tempfile
import unittest
from pathlib import Path

TOOL_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = TOOL_ROOT.parents[1]
sys.path.insert(0, str(TOOL_ROOT))

from anki_contract_codegen import html_entities_core  # noqa: E402
from anki_contract_codegen.core import ContractError  # noqa: E402


class HtmlEntitiesCodegenTest(unittest.TestCase):
    def test_synthetic_generation_is_sorted_deterministic_and_escaped(self) -> None:
        entries = (("A;", "\U0001d504"), ("amp;", "&"), ("quot;", '"'))
        first = html_entities_core.generate_kotlin(entries)
        self.assertEqual(first, html_entities_core.generate_kotlin(entries))
        source = first.decode("utf-8")
        self.assertIn("ENTRY_COUNT = 3", source)
        self.assertIn("\\uD835\\uDD04", source)
        self.assertIn('\\"', source)
        with self.assertRaisesRegex(ContractError, "sorted"):
            html_entities_core.generate_kotlin(tuple(reversed(entries)))

    def test_pinned_runtime_and_checked_output_match_exactly(self) -> None:
        self.assertEqual("Chaquopy CPython 3.12.12-0", html_entities_core.TARGET_RUNTIME)
        self.assertEqual(
            html_entities_core.CHARREF_PATTERN_SHA256,
            __import__("hashlib").sha256(html_entities_core.CHARREF_PATTERN.encode()).hexdigest(),
        )
        if platform.python_implementation() != "CPython" or sys.version_info[:3] != (3, 12, 13):
            with self.assertRaisesRegex(ContractError, "pinned CPython 3.12.13"):
                html_entities_core.load_pinned_table()
            return
        entries = html_entities_core.load_pinned_table()
        self.assertEqual(html_entities_core.TABLE_ENTRY_COUNT, len(entries))
        self.assertEqual(
            html_entities_core.generate_kotlin(entries),
            (REPO_ROOT / html_entities_core.KOTLIN_OUTPUT_PATH).read_bytes(),
        )

    def test_refresh_is_atomic_and_check_rejects_drift(self) -> None:
        if platform.python_implementation() != "CPython" or sys.version_info[:3] != (3, 12, 13):
            self.skipTest("requires the pinned generation interpreter")
        with tempfile.TemporaryDirectory() as temporary_directory:
            repo = Path(temporary_directory) / "repo"
            repo.mkdir()
            html_entities_core.refresh(repo)
            output = repo / html_entities_core.KOTLIN_OUTPUT_PATH
            expected = output.read_bytes()
            self.assertEqual(0o644, stat.S_IMODE(output.stat().st_mode))
            html_entities_core.check(repo)
            output.write_bytes(expected + b"// drift\n")
            with self.assertRaisesRegex(ContractError, "drifted"):
                html_entities_core.check(repo)


if __name__ == "__main__":
    unittest.main()
