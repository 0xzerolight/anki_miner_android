from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from tools.wheels import s1a_acceptance as acceptance
from tools.wheels import s1a_acceptance_collect as collector

PROJECT_ROOT = Path(__file__).resolve().parents[2]


class S1aAcceptanceCollectorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.log = Path(self.temporary.name) / "instrumentation.log"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_identical_duplicate_markers_are_accepted(self) -> None:
        value = {
            "assertion_count": 9,
            "corpus_sha256": "a" * 64,
            "passed": True,
            "test_class": acceptance.EXPECTED_TEST_CLASS,
        }
        rendered = json.dumps(value, separators=(",", ":"))
        self.log.write_text(
            f"I/Probe: {collector.PARITY_MARKER}{rendered}\n"
            f"{collector.PARITY_MARKER}{rendered}\n",
            encoding="utf-8",
        )
        actual = collector._read_marker(
            self.log,
            collector.PARITY_MARKER,
            {"assertion_count", "corpus_sha256", "passed", "test_class"},
        )
        self.assertEqual(value, actual)

    def test_ambiguous_or_trailing_marker_data_is_rejected(self) -> None:
        first = {
            "cold_init_ms": 1000.0,
            "dictionary_sha256": "a" * 64,
            "pid": 10,
            "process_start_uptime_ms": 20,
        }
        second = dict(first, pid=11)
        self.log.write_text(
            f"{collector.COLD_MARKER}{json.dumps(first)}\n"
            f"{collector.COLD_MARKER}{json.dumps(second)}\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(acceptance.AcceptanceError, "ambiguous"):
            collector._read_marker(
                self.log,
                collector.COLD_MARKER,
                {"cold_init_ms", "dictionary_sha256", "pid", "process_start_uptime_ms"},
            )

        self.log.write_text(
            f"{collector.COLD_MARKER}{json.dumps(first)} garbage\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(acceptance.AcceptanceError, "trailing"):
            collector._read_marker(
                self.log,
                collector.COLD_MARKER,
                {"cold_init_ms", "dictionary_sha256", "pid", "process_start_uptime_ms"},
            )

    def test_manifest_identity_reads_source_and_channel_metadata(self) -> None:
        manifest = """\
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <application>
    <meta-data android:name="com.ankiminer.android.SOURCE_COMMIT" android:value="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" />
    <meta-data android:name="com.ankiminer.android.RELEASE_CHANNEL" android:value="device-acceptance" />
  </application>
</manifest>
"""
        with mock.patch.object(
            collector,
            "_run",
            side_effect=["com.ankiminer.android", manifest],
        ):
            identity = collector._manifest_identity("apkanalyzer", Path("accepted.apk"))
        self.assertEqual(
            {
                "application_id": "com.ankiminer.android",
                "source_commit": "a" * 40,
                "release_channel": "device-acceptance",
            },
            identity,
        )

    def test_physical_collection_binds_source_before_gradle_runner(self) -> None:
        source = (PROJECT_ROOT / "scripts/collect-s1a-arm64-acceptance.sh").read_text(
            encoding="utf-8"
        )
        export = 'export ORG_GRADLE_PROJECT_ankiMinerSourceCommit="$source_commit"'
        self.assertIn('source_commit="$(git -C "$REPO_ROOT" rev-parse HEAD)"', source)
        self.assertIn(export, source)
        self.assertLess(source.index(export), source.index('parity_output="$("$PARITY_RUNNER"'))


if __name__ == "__main__":
    unittest.main()
