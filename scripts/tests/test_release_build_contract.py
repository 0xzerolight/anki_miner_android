from __future__ import annotations

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


class ReleaseBuildContractTests(unittest.TestCase):
    def test_ci_uses_both_pinned_python_lanes(self) -> None:
        workflow = (ROOT / ".github/workflows/pull-request.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn('python-version: "3.12.13"', workflow)
        self.assertIn('python-version: "3.13"', workflow)
        self.assertIn('"$ANKI_MINER_CHAQUOPY_BUILD_PYTHON"', workflow)
        self.assertIn("tools/anki-contract/generate_html5_entities.py --check", workflow)
        self.assertIn("ANKI_MINER_CHAQUOPY_BUILD_PYTHON:", workflow)

    def test_ci_gradle_gate_is_serialized_and_never_uploaded(self) -> None:
        workflow = (ROOT / ".github/workflows/pull-request.yml").read_text(
            encoding="utf-8"
        )
        self.assertEqual(1, workflow.count("anki_miner_run_gradle ./gradlew"))
        for task in (
            ":app:testEmulatorDebugUnitTest",
            ":app:lintEmulatorDebug",
            ":app:assembleEmulatorDebug",
            ":app:assembleEmulatorRelease",
            ":app:assembleDeviceRelease",
        ):
            self.assertIn(task, workflow)
        self.assertNotIn("upload-artifact", workflow)
        self.assertNotIn("--require-s1a", workflow)

    def test_every_release_task_depends_on_fail_closed_validation(self) -> None:
        build = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        for environment_name in (
            "ANKI_MINER_VERSION_CODE",
            "ANKI_MINER_VERSION_NAME",
            "ANKI_MINER_SOURCE_COMMIT",
            "ANKI_MINER_RELEASE_CHANNEL",
            "ANKI_MINER_S1A_ARM64_ACCEPTED",
            "ANKI_MINER_KEYSTORE",
            "ANKI_MINER_KEYSTORE_PASSWORD",
            "ANKI_MINER_KEY_ALIAS",
            "ANKI_MINER_KEY_PASSWORD",
        ):
            self.assertIn(environment_name, build)
        self.assertIn("val validateReleaseConfiguration by tasks.registering", build)
        self.assertIn('name.contains("Release")', build)
        self.assertIn("dependsOn(validateReleaseConfiguration)", build)

    def test_explicit_signing_inputs_override_ignored_local_configuration(self) -> None:
        build = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertRegex(
            build,
            r"val storePath\s*=\s*releaseStorePath\s*"
            r'\?: keystoreProps\.getProperty\("storeFile"\)',
        )
        self.assertIn(
            'storePassword = releaseStorePassword ?: keystoreProps.getProperty("storePassword")',
            build,
        )
        self.assertIn(
            'val configuredStorePath =\n            releaseStorePath ?: keystoreProps.getProperty("storeFile")',
            build,
        )

    def test_health_builds_and_audits_one_release_without_an_emulator(self) -> None:
        health = (ROOT / "scripts/health.sh").read_text(encoding="utf-8")
        self.assertEqual(1, health.count("anki_miner_run_gradle ./gradlew"))
        self.assertIn(":app:assembleEmulatorRelease", health)
        self.assertNotIn("--require-s1a", health)
        self.assertIn("lib/x86_64/libanki_miner_mecab.so", health)
        self.assertIn("apksigner verify", health)

    def test_exact_candidate_keeps_s1a_manifest_and_zip_alignment_gates(self) -> None:
        candidate = (ROOT / "scripts/verify_release_candidate.py").read_text(
            encoding="utf-8"
        )
        wrapper = (ROOT / "scripts/check-native-artifact.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn('"--require-s1a"', candidate)
        self.assertIn('"--s1a-manifest"', candidate)
        self.assertIn('repo_root / "scripts/check-native-artifact.sh"', candidate)
        self.assertIn('zipalign -c -P 16 -v 4 "$ARTIFACT"', wrapper)

    def test_run_app_builds_first_and_never_uninstalls(self) -> None:
        launcher = (ROOT / "scripts/run-app.sh").read_text(encoding="utf-8")
        self.assertNotIn('adb -s "$SERIAL" uninstall', launcher)
        self.assertLess(
            launcher.index("anki_miner_run_gradle ./gradlew"),
            launcher.index('echo "Starting emulator'),
        )
        self.assertIn('adb -s "$SERIAL" install -r "$APK"', launcher)


if __name__ == "__main__":
    unittest.main()
