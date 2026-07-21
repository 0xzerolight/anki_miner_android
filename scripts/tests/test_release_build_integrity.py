from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = REPO_ROOT / "tools/release/validate_release_build.py"
BUILD_SCRIPT = REPO_ROOT / "app/build.gradle.kts"
GRADLE_HARNESS = REPO_ROOT / "scripts/tests/test-release-build-integrity-gradle.sh"
VALID_SHA = "0123456789abcdef0123456789abcdef01234567"


class ReleaseBuildIntegrityTests(unittest.TestCase):
    def _run_validator(
        self,
        build_type: str,
        source_commit: str | None = None,
    ) -> subprocess.CompletedProcess[str]:
        command = [sys.executable, str(VALIDATOR), "--build-type", build_type]
        if source_commit is not None:
            command.extend(("--source-commit", source_commit))
        return subprocess.run(
            command,
            cwd=REPO_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )

    def test_debug_defaults_to_development_without_source_commit(self) -> None:
        result = self._run_validator("debug")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("development\n", result.stdout)

    def test_release_without_source_commit_fails_closed(self) -> None:
        result = self._run_validator("release")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("full lowercase Git SHA", result.stderr)

    def test_release_with_invalid_source_commit_fails_closed(self) -> None:
        result = self._run_validator("release", "development")

        self.assertNotEqual(0, result.returncode)
        self.assertIn("full lowercase Git SHA", result.stderr)

    def test_release_with_valid_source_commit_and_current_wheels_passes(self) -> None:
        result = self._run_validator("release", VALID_SHA)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(f"{VALID_SHA}\n", result.stdout)

    def test_release_variant_consumes_integrity_provider_in_manifest_and_build_config(self) -> None:
        script = BUILD_SCRIPT.read_text(encoding="utf-8")
        provider_start = script.index("val releaseBuildIntegrityScript")
        provider_end = script.index("val validateReleaseSourceCommit")
        variant_start = script.index("androidComponents {")
        provider = script[provider_start:provider_end]
        variants = script[variant_start : script.index("\nkotlin {")]

        self.assertIn("providers.exec", provider)
        self.assertIn("tools/release/validate_release_build.py", provider)
        self.assertNotIn("validateReleaseSourceCommit", provider)
        self.assertNotIn("verifyVendoredWheelManifest", provider)
        self.assertIn('selector().withBuildType("release")', variants)
        self.assertIn("variant.buildConfigFields.put", variants)
        self.assertIn("variant.manifestPlaceholders.put", variants)
        self.assertGreaterEqual(variants.count("validatedReleaseSourceCommit"), 2)

    def test_release_lifecycle_tasks_keep_early_validation_wiring(self) -> None:
        script = BUILD_SCRIPT.read_text(encoding="utf-8")

        self.assertIn("dependsOn(validateReleaseSourceCommit)", script)
        self.assertIn('tasks.named("preBuild")', script)
        self.assertIn("dependsOn(verifyVendoredWheelManifest)", script)

    def test_excluding_named_validation_tasks_cannot_remove_variant_enforcement(self) -> None:
        script = BUILD_SCRIPT.read_text(encoding="utf-8")
        variants = script[script.index("androidComponents {") : script.index("\nkotlin {")]

        self.assertIn("validatedReleaseSourceCommit", variants)
        self.assertNotIn("validateReleaseSourceCommit", variants)
        self.assertNotIn("verifyVendoredWheelManifest", variants)

    def test_gradle_harness_exercises_artifacts_wiring_and_exclusion_bypasses(self) -> None:
        harness = GRADLE_HARNESS.read_text(encoding="utf-8")

        self.assertIn(":app:assembleEmulatorDebug", harness)
        self.assertIn(":app:assembleDeviceRelease", harness)
        self.assertIn(":app:assembleEmulatorRelease", harness)
        self.assertIn(":app:bundleDeviceRelease", harness)
        self.assertIn("--dry-run", harness)
        self.assertIn("-x validateReleaseSourceCommit", harness)
        self.assertIn("-x verifyVendoredWheelManifest", harness)
        self.assertIn('invalid release SHA', harness)
        self.assertIn('missing release SHA', harness)
        self.assertIn('manifest drift', harness)

    def test_release_callers_pass_current_source_commit(self) -> None:
        tokenizer = (REPO_ROOT / "tools/tokenizer/build-s1b-android.sh").read_text(
            encoding="utf-8",
        )
        launcher = (REPO_ROOT / "scripts/run-app.sh").read_text(encoding="utf-8")
        relinking = (REPO_ROOT / "third_party/ankidroid-api/RELINKING.md").read_text(
            encoding="utf-8",
        )
        packaged_relinking = (
            REPO_ROOT / "app/src/main/assets/notices/ankidroid-RELINKING.md"
        ).read_text(encoding="utf-8")

        for content in (tokenizer, launcher, relinking, packaged_relinking):
            self.assertIn('source_commit="$(git rev-parse HEAD)"', content)
            self.assertIn('-PankiMinerSourceCommit="$source_commit"', content)


if __name__ == "__main__":
    unittest.main()
