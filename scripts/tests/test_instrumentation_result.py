from __future__ import annotations

from pathlib import Path
import subprocess
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "instrumentation-result.sh"


def validate(output: str, expected_count: int = 1) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            "bash",
            "-c",
            'source "$1"; payload="$(cat)"; ' 'android_instrumentation_output_passed "$payload" "$2"',
            "instrumentation-result-test",
            str(SCRIPT),
            str(expected_count),
        ],
        input=output,
        text=True,
        capture_output=True,
        check=False,
    )


def validate_any(output: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            "bash",
            "-c",
            'source "$1"; payload="$(cat)"; ' 'android_instrumentation_output_passed_any "$payload"',
            "instrumentation-result-test",
            str(SCRIPT),
        ],
        input=output,
        text=True,
        capture_output=True,
        check=False,
    )


class InstrumentationResultTest(unittest.TestCase):
    def test_accepts_one_exact_summary_and_success_terminal_code(self) -> None:
        result = validate(
            "INSTRUMENTATION_STATUS_CODE: 0\r\n"
            "Time: 0.1\r\n\r\n"
            "OK (1 test)\r\n\r\n"
            "INSTRUMENTATION_CODE: -1\r\n",
        )

        self.assertEqual(0, result.returncode, result.stderr)

    def test_accepts_exact_plural_summary(self) -> None:
        result = validate("OK (2 tests)\nINSTRUMENTATION_CODE: -1\n", 2)

        self.assertEqual(0, result.returncode, result.stderr)

    def test_rejects_false_success_transcripts(self) -> None:
        cases = (
            "OK (1 test)\nINSTRUMENTATION_CODE: 0\n",
            "prefix OK (1 test)\nINSTRUMENTATION_CODE: -1\n",
            "OK (1 test) extra\nINSTRUMENTATION_CODE: -1\n",
            "OK (1 test)\n",
            "OK (1 test)\nINSTRUMENTATION_CODE: -1\nINSTRUMENTATION_CODE: -1\n",
            "OK (1 test)\nshortMsg=Process crashed\nINSTRUMENTATION_CODE: -1\n",
            "FAILURES!!!\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n",
            "OK (2 tests)\nINSTRUMENTATION_CODE: -1\n",
        )
        for output in cases:
            with self.subTest(output=output):
                self.assertNotEqual(0, validate(output).returncode)

    def test_rejects_invalid_expected_count(self) -> None:
        self.assertEqual(
            2,
            validate("OK (1 test)\nINSTRUMENTATION_CODE: -1\n", 0).returncode,
        )

    def test_any_count_contract_is_positive_exact_and_crash_safe(self) -> None:
        self.assertEqual(
            0,
            validate_any("OK (37 tests)\nINSTRUMENTATION_CODE: -1\n").returncode,
        )
        rejected = (
            "OK (0 tests)\nINSTRUMENTATION_CODE: -1\n",
            "OK (1 test)\nOK (2 tests)\nINSTRUMENTATION_CODE: -1\n",
            "OK (2 tests)\nProcess crashed\nINSTRUMENTATION_CODE: -1\n",
            "OK (2 tests)\nINSTRUMENTATION_CODE: 0\n",
            "OK (2 tests)\n",
        )
        for output in rejected:
            with self.subTest(output=output):
                self.assertNotEqual(0, validate_any(output).returncode)


if __name__ == "__main__":
    unittest.main()
