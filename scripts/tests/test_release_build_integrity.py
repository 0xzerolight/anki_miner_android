from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = REPO_ROOT / "tools/release/validate_release_build.py"
BUILD_SCRIPT = REPO_ROOT / "app/build.gradle.kts"
GRADLE_HARNESS = REPO_ROOT / "scripts/tests/test-release-build-integrity-gradle.sh"
HEALTH_SCRIPT = REPO_ROOT / "scripts/health.sh"
CI_WORKFLOW = REPO_ROOT / ".github/workflows/pull-request.yml"
FABRICATED_SHA = "0123456789abcdef0123456789abcdef01234567"

sys.path.insert(0, str(REPO_ROOT / "tools/release"))
import validate_release_build as release_validator  # noqa: E402


class ReleaseBuildIntegrityTests(unittest.TestCase):
    def setUp(self) -> None:
        self._temporary = tempfile.TemporaryDirectory()
        self.source_root = Path(self._temporary.name) / "source"
        self.source_root.mkdir()
        self._git("init", "-q")
        self._git("config", "user.email", "tests@example.invalid")
        self._git("config", "user.name", "Release Integrity Tests")
        (self.source_root / "tracked.txt").write_text("first\n", encoding="utf-8")
        self._git("add", ".")
        self._git("commit", "-qm", "first")
        self.stale_sha = self._git("rev-parse", "HEAD")
        (self.source_root / "tracked.txt").write_text("second\n", encoding="utf-8")
        self._git("commit", "-qam", "second")
        self.head_sha = self._git("rev-parse", "HEAD")

    def tearDown(self) -> None:
        self._temporary.cleanup()

    def _git(self, *args: str) -> str:
        return subprocess.run(
            ("git", *args),
            cwd=self.source_root,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

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

    def _validate_release(self, source_commit: str) -> str:
        return release_validator.validate_build(
            "release",
            source_commit,
            release_validator.DEFAULT_WHEELS_ROOT,
            release_validator.DEFAULT_MANIFEST,
            self.source_root,
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
        self.assertEqual(self.head_sha, self._validate_release(self.head_sha))

    def test_release_with_fabricated_source_commit_fails_closed(self) -> None:
        with self.assertRaisesRegex(
            release_validator.ReleaseBuildIntegrityError,
            "does not identify a commit",
        ):
            self._validate_release(FABRICATED_SHA)

    def test_release_with_non_head_source_commit_fails_closed(self) -> None:
        with self.assertRaisesRegex(
            release_validator.ReleaseBuildIntegrityError,
            "does not equal checkout HEAD",
        ):
            self._validate_release(self.stale_sha)

    def test_release_with_dirty_tracked_source_fails_closed(self) -> None:
        (self.source_root / "tracked.txt").write_text("dirty\n", encoding="utf-8")

        with self.assertRaisesRegex(
            release_validator.ReleaseBuildIntegrityError,
            "source checkout is dirty",
        ):
            self._validate_release(self.head_sha)

    def test_release_with_untracked_source_fails_closed(self) -> None:
        (self.source_root / "untracked.txt").write_text("dirty\n", encoding="utf-8")

        with self.assertRaisesRegex(
            release_validator.ReleaseBuildIntegrityError,
            "source checkout is dirty",
        ):
            self._validate_release(self.head_sha)

    def test_release_with_assume_unchanged_source_fails_closed(self) -> None:
        self._git("update-index", "--assume-unchanged", "tracked.txt")
        (self.source_root / "tracked.txt").write_text("hidden dirty\n", encoding="utf-8")
        self.assertEqual("", self._git("status", "--porcelain=v1"))

        with self.assertRaisesRegex(
            release_validator.ReleaseBuildIntegrityError,
            "Git index hides tracked source",
        ):
            self._validate_release(self.head_sha)

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
        self.assertIn("variant.buildConfigFields!!.put", variants)
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
        self.assertIn("invalid release SHA", harness)
        self.assertIn("missing release SHA", harness)
        self.assertIn("manifest drift", harness)

    def test_ci_and_health_audit_unsigned_device_release_apks(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        health = HEALTH_SCRIPT.read_text(encoding="utf-8")

        self.assertIn(':app:assembleDeviceRelease', workflow)
        self.assertIn('-PankiMinerSourceCommit="$source_commit"', workflow)
        self.assertIn('app/build/outputs/apk/device/release/app-device-release-unsigned.apk', workflow)
        self.assertIn('--allow-abi arm64-v8a', workflow)
        self.assertIn('check_runtime_artifact.py', workflow)
        self.assertIn('--vendored-manifest app/wheels/manifest.json', workflow)

        # health.sh deliberately does NOT build a release variant. Doing so runs
        # validate_release_build.py, which fails closed unless the checkout is
        # completely clean, so every local health run would require a committed
        # tree. The shipped ARM64 artifact is audited by CI, where that holds.
        self.assertNotIn(':app:assembleDeviceRelease', health)
        self.assertIn('check_runtime_artifact.py', health)
        self.assertIn('--vendored-manifest "$REPO_ROOT/app/wheels/manifest.json"', health)
        self.assertIn('--allow-abi x86_64', health)

    def test_release_callers_pass_current_source_commit(self) -> None:
        tokenizer = (REPO_ROOT / "tools/tokenizer/build-s1b-android.sh").read_text(
            encoding="utf-8",
        )
        launcher = (REPO_ROOT / "scripts/run-app.sh").read_text(encoding="utf-8")
        relinking = (REPO_ROOT / "third_party/ankidroid-api/RELINKING.md").read_text(
            encoding="utf-8",
        )
        packaged_relinking = (REPO_ROOT / "app/src/main/assets/notices/ankidroid-RELINKING.md").read_text(
            encoding="utf-8"
        )

        for content in (tokenizer, launcher, relinking, packaged_relinking):
            self.assertIn('source_commit="$(git rev-parse HEAD)"', content)
            self.assertIn('-PankiMinerSourceCommit="$source_commit"', content)


if __name__ == "__main__":
    unittest.main()
