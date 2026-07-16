from __future__ import annotations

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
RUNNER = SCRIPTS_DIR / "run-s2-ankidroid-probe.sh"


class S2ProbeRunnerTest(unittest.TestCase):
    def run_runner(self, **overrides: str) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as directory:
            environment = os.environ.copy()
            environment.pop("ANKI_MINER_S2_ALLOW_COLLECTION_RESET", None)
            environment.update(
                {
                    "ANKI_MINER_ANDROID_TOOLCHAIN_ROOT": directory,
                    "ANKI_MINER_ANKIDROID_APK": str(Path(directory) / "missing.apk"),
                    "ANKI_MINER_S2_SERIAL": "emulator-5554",
                    **overrides,
                },
            )
            return subprocess.run(
                [str(RUNNER)],
                check=False,
                capture_output=True,
                env=environment,
                text=True,
            )

    def test_collection_reset_requires_exact_explicit_opt_in(self) -> None:
        result = self.run_runner()

        self.assertEqual(2, result.returncode)
        self.assertIn("requires a disposable emulator collection", result.stderr)
        self.assertIn("/storage/emulated/0/AnkiDroid", result.stderr)
        self.assertIn("ANKI_MINER_S2_ALLOW_COLLECTION_RESET=true", result.stderr)

    def test_invalid_apk_stops_before_any_emulator_operation(self) -> None:
        result = self.run_runner(ANKI_MINER_S2_ALLOW_COLLECTION_RESET="true")

        self.assertEqual(1, result.returncode)
        self.assertIn("Pinned AnkiDroid APK is missing", result.stderr)

    def test_runner_locks_identity_setup_mutation_and_evidence_order(self) -> None:
        source = RUNNER.read_text(encoding="utf-8")

        opt_in = source.index("ANKI_MINER_S2_ALLOW_COLLECTION_RESET")
        apk_hash = source.index('sha256sum "$APK"')
        certificate = source.index("apksigner verify --print-certs")
        lane_identity = source.index('verify-emulator-runtime.sh" --lane 4k')
        collection_reset = source.index("rm -rf -- /storage/emulated/0/AnkiDroid")
        install_ankidroid = source.index('install --no-streaming "$APK"')
        storage_app_op = source.index(
            "appops set com.ichi2.anki MANAGE_EXTERNAL_STORAGE allow",
        )
        install_miner = source.index('install --no-streaming "$app_apk"')
        provider_grant = source.index(
            "com.ankiminer.android com.ichi2.anki.permission.READ_WRITE_DATABASE",
        )
        selector = source.index("-e ankiMinerRunS2 true")
        evidence = source.index("mapfile -t evidence_lines")

        self.assertLess(opt_in, apk_hash)
        self.assertLess(apk_hash, certificate)
        self.assertLess(certificate, lane_identity)
        self.assertLess(lane_identity, collection_reset)
        self.assertLess(collection_reset, install_ankidroid)
        self.assertLess(install_ankidroid, storage_app_op)
        self.assertLess(storage_app_op, install_miner)
        self.assertLess(install_miner, provider_grant)
        self.assertLess(provider_grant, selector)
        self.assertLess(selector, evidence)
        self.assertIn('"${#evidence_lines[@]}" != 1', source)
        self.assertIn("S2_ANKIDROID_STORAGE_PRECONDITION=emulator-only", source)
        self.assertIn("ANKI_MINER_S2_PROBE=", source)


if __name__ == "__main__":
    unittest.main()
