from __future__ import annotations

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parents[1]
CONNECTED = SCRIPTS / "run-s5-video-probe.sh"
OWNER = SCRIPTS / "run-s5-video-acceptance.sh"
PREPARER = SCRIPTS / "prepare-s5-video-probe.sh"


class S5ProbeRunnerTest(unittest.TestCase):
    def test_connected_runner_locks_destructive_identity_and_evidence_order(self) -> None:
        source = CONNECTED.read_text(encoding="utf-8")
        opt_in = source.index("ANKI_MINER_S5_ALLOW_COLLECTION_RESET")
        apk_hash = source.index('sha256sum "$APK"')
        receipt = source.index("--require-s2")
        s1a = source.index("manifests.s1a.path")
        identity = source.index('verify-emulator-runtime.sh" --lane 4k')
        reset = source.index("rm -rf -- /storage/emulated/0/AnkiDroid")
        provider_permission = source.index("com.ichi2.anki.permission.READ_WRITE_DATABASE")
        notification_permission = source.index("android.permission.POST_NOTIFICATIONS")
        unidic = source.index("provision-tokenizer-test-unidic.sh")
        selector = source.index("-e ankiMinerRunS5 true")
        evidence = source.index("mapfile -t evidence_lines")

        self.assertLess(opt_in, apk_hash)
        self.assertLess(apk_hash, receipt)
        self.assertLess(receipt, s1a)
        self.assertLess(s1a, identity)
        self.assertLess(identity, reset)
        self.assertLess(reset, provider_permission)
        self.assertLess(provider_permission, notification_permission)
        self.assertLess(notification_permission, unidic)
        self.assertLess(unidic, selector)
        self.assertLess(selector, evidence)
        self.assertIn('"${#evidence_lines[@]}" != 1', source)
        self.assertIn("ANKI_MINER_S5_PROBE=", source)

    def test_owner_maps_s5_opt_in_to_the_existing_owned_emulator_lifecycle(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            receipt = root / "receipt.json"
            receipt.write_text("{}\n", encoding="utf-8")
            dicdir = root / "dicdir"
            dicdir.mkdir()
            owner = root / "owner.sh"
            owner.write_text(
                "#!/usr/bin/env bash\n"
                "printf '%s\\n' \"$@\" >\"$S5_OWNER_ARGS\"\n"
                "printf '%s\\n' \"$ANKI_MINER_S2_ALLOW_COLLECTION_RESET\" "
                "\"$ANKI_MINER_S2_CONNECTED_RUNNER\" \"$ANKI_MINER_TEST_UNIDIC_DIR\" "
                ">\"$S5_OWNER_ENV\"\n",
                encoding="utf-8",
            )
            owner.chmod(0o755)
            environment = os.environ.copy()
            environment.update(
                {
                    "ANKI_MINER_S5_ALLOW_COLLECTION_RESET": "true",
                    "ANKI_MINER_S5_OWNER_RUNNER": str(owner),
                    "S5_OWNER_ARGS": str(root / "args"),
                    "S5_OWNER_ENV": str(root / "env"),
                },
            )
            result = subprocess.run(
                [
                    str(OWNER),
                    "--receipt",
                    str(receipt),
                    "--unidic-dir",
                    str(dicdir),
                ],
                check=False,
                capture_output=True,
                env=environment,
                text=True,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                ["--s2", "--receipt", str(receipt)],
                (root / "args").read_text(encoding="utf-8").splitlines(),
            )
            mapped = (root / "env").read_text(encoding="utf-8").splitlines()
            self.assertEqual("true", mapped[0])
            self.assertEqual(str(CONNECTED), mapped[1])
            self.assertEqual(str(dicdir), mapped[2])

    def test_preparation_requires_s1a_and_binds_pinned_ankidroid_receipt(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake = root / "prepare.sh"
            fake.write_text(
                "#!/usr/bin/env bash\nprintf '%s\\n' \"$@\" >\"$S5_PREPARE_LOG\"\n",
                encoding="utf-8",
            )
            fake.chmod(0o755)
            receipt = root / "receipt.json"
            apk = root / "AnkiDroid.apk"
            manifest = root / "manifest.json"
            environment = os.environ.copy()
            environment.update(
                {
                    "ANKI_MINER_ANDROID_TOOLCHAIN_ROOT": str(root),
                    "ANKI_MINER_EMULATOR_PREPARER": str(fake),
                    "ANKI_MINER_ANKIDROID_APK": str(apk),
                    "ANKI_MINER_S5_ALLOW_COLLECTION_RESET": "true",
                    "ORG_GRADLE_PROJECT_ankiMinerS1aManifest": str(manifest),
                    "S5_PREPARE_LOG": str(root / "prepare.log"),
                },
            )
            result = subprocess.run(
                [str(PREPARER), "--receipt", str(receipt)],
                check=False,
                capture_output=True,
                env=environment,
                text=True,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(
                [
                    "--receipt",
                    str(receipt),
                    "--ankidroid-apk",
                    str(apk),
                    "--s2-reset-opt-in",
                ],
                (root / "prepare.log").read_text(encoding="utf-8").splitlines(),
            )


if __name__ == "__main__":
    unittest.main()
