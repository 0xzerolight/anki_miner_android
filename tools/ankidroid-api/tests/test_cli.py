from __future__ import annotations

import contextlib
import io
import sys
import unittest
from pathlib import Path
from unittest import mock

TOOL_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOL_ROOT))

from ankidroid_api_sync import cli  # noqa: E402


class CliTest(unittest.TestCase):
    def test_manifest_check_output_does_not_claim_upstream_proof(self) -> None:
        output = io.StringIO()
        with mock.patch.object(cli, "check"), contextlib.redirect_stdout(output):
            result = cli.main(["--check", "--repo-root", "/unused"])

        self.assertEqual(0, result)
        self.assertIn("manifest check OK", output.getvalue())
        self.assertNotIn("upstream byte equality", output.getvalue())

    def test_upstream_mode_is_explicitly_labelled(self) -> None:
        output = io.StringIO()
        with mock.patch.object(cli, "check_upstream"), contextlib.redirect_stdout(output):
            result = cli.main(
                [
                    "--check-upstream",
                    "--source",
                    "/verified-source",
                    "--repo-root",
                    "/unused",
                ]
            )

        self.assertEqual(0, result)
        self.assertIn("verified-upstream byte equality OK", output.getvalue())


if __name__ == "__main__":
    unittest.main()
