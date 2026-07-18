from __future__ import annotations

from argparse import Namespace
import json
from pathlib import Path
import sys
import tempfile
import unittest


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

import verify_release_candidate as verifier  # noqa: E402


COMMIT = "1" * 40
CERTIFICATE = "ab" * 32
RUNTIME_BUILD = "2" * 64
S1A_BUILD = "3" * 64


class ReleasePolicyTests(unittest.TestCase):
    def test_github_alpha_requires_arm64_acceptance_before_closed_testing(self) -> None:
        with self.assertRaisesRegex(verifier.CandidateError, "distribution requires"):
            verifier.validate_policy(
                mode="distribution",
                channel="github-alpha",
                version_code=1,
                version_name="0.1.0-alpha.1",
                source_commit=COMMIT,
                s1a_accepted=False,
            )

    def test_production_requires_arm64_acceptance(self) -> None:
        with self.assertRaisesRegex(verifier.CandidateError, "distribution requires"):
            verifier.validate_policy(
                mode="distribution",
                channel="production",
                version_code=1,
                version_name="0.1.0",
                source_commit=COMMIT,
                s1a_accepted=False,
            )

    def test_test_mode_is_explicitly_non_distributable(self) -> None:
        with self.assertRaisesRegex(verifier.CandidateError, "non-distributable ci"):
            verifier.validate_policy(
                mode="test",
                channel="github-alpha",
                version_code=1,
                version_name="0.0.0-ci",
                source_commit=COMMIT,
                s1a_accepted=False,
            )

    def test_release_identity_is_not_inferred(self) -> None:
        for version_code, version_name, commit in (
            (0, "0.1.0", COMMIT),
            (1, "development", COMMIT),
            (1, "0.1.0", "development"),
        ):
            with self.subTest(
                version_code=version_code,
                version_name=version_name,
                commit=commit,
            ):
                with self.assertRaises(verifier.CandidateError):
                    verifier.validate_policy(
                        mode="distribution",
                        channel="github-alpha",
                        version_code=version_code,
                        version_name=version_name,
                        source_commit=commit,
                        s1a_accepted=False,
                    )


class CandidateVerificationTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.artifact = self.root / "app.apk"
        self.artifact.write_bytes(b"fixture")
        self.runtime_manifest = self._manifest("runtime-wheels", RUNTIME_BUILD)
        self.s1a_manifest = self._manifest("s1a-wheels", S1A_BUILD)
        self.commands: list[list[str]] = []
        self.dirty = False
        self.certificate = CERTIFICATE
        self.metadata_override: dict[str, str] = {}

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _manifest(self, prefix: str, build_key: str) -> Path:
        directory = self.root / f"{prefix}-{build_key}"
        directory.mkdir()
        manifest = directory / "manifest.json"
        manifest.write_text(json.dumps({"build_key": build_key}), encoding="utf-8")
        return manifest

    def _arguments(self, **overrides: object) -> Namespace:
        values: dict[str, object] = {
            "artifact": self.artifact,
            "mode": "distribution",
            "abi": "arm64-v8a",
            "runtime_manifest": self.runtime_manifest,
            "s1a_manifest": self.s1a_manifest,
            "expected_cert_sha256": CERTIFICATE,
            "expected_version_code": 1,
            "expected_version_name": "0.1.0-alpha.1",
            "expected_source_commit": COMMIT,
            "expected_channel": "github-alpha",
            "expected_s1a_arm64_accepted": "true",
            "repo_root": self.root,
            "apkanalyzer": "apkanalyzer",
            "apksigner": "apksigner",
        }
        values.update(overrides)
        return Namespace(**values)

    def _manifest_xml(self) -> str:
        metadata = {
            "com.ankiminer.android.SOURCE_COMMIT": COMMIT,
            "com.ankiminer.android.RELEASE_CHANNEL": "github-alpha",
            "com.ankiminer.android.S1A_ARM64_ACCEPTED": "true",
            "com.ankiminer.android.RUNTIME_WHEEL_BUILD_KEY": RUNTIME_BUILD,
            "com.ankiminer.android.S1A_PUBLICATION_BUILD_KEY": S1A_BUILD,
        }
        metadata.update(self.metadata_override)
        entries = "".join(
            f'<meta-data android:name="{name}" android:value="{value}" />'
            for name, value in metadata.items()
        )
        return (
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
            f"<application>{entries}</application></manifest>"
        )

    def _run(self, command: list[str], cwd: Path) -> str:
        self.assertEqual(self.root, cwd)
        self.commands.append(command)
        if command[:3] == ["git", "rev-parse", "HEAD"]:
            return COMMIT
        if command[:3] == ["git", "status", "--porcelain=v1"]:
            return " M tracked" if self.dirty else ""
        if command[0] == "apkanalyzer":
            values = {
                "application-id": verifier.APPLICATION_ID,
                "version-code": "1",
                "version-name": "0.1.0-alpha.1",
                "min-sdk": "26",
                "target-sdk": "36",
                "print": self._manifest_xml(),
            }
            return values[command[2]]
        if command[0] == "apksigner":
            return (
                "Verified using v2 scheme (APK Signature Scheme v2): true\n"
                f"Signer #1 certificate SHA-256 digest: {self.certificate}\n"
            )
        return ""

    def test_exact_distribution_candidate_runs_every_artifact_gate(self) -> None:
        verifier.verify_candidate(self._arguments(), self._run)
        flattened = [value for command in self.commands for value in command]
        self.assertIn("--require-s1a", flattened)
        self.assertIn("lib/arm64-v8a/libffmpeg.so", flattened)
        self.assertIn("lib/arm64-v8a/libffprobe.so", flattened)
        self.assertIn(str(self.runtime_manifest), flattened)
        self.assertIn(str(self.s1a_manifest), flattened)
        self.assertTrue(
            any("runtime_wheels.py" in value for value in flattened),
        )
        self.assertTrue(
            any("s1a_wheels.py" in value for value in flattened),
        )
        self.assertTrue(any(command[0] == "apksigner" for command in self.commands))

    def test_distribution_rejects_dirty_or_untracked_state(self) -> None:
        self.dirty = True
        with self.assertRaisesRegex(verifier.CandidateError, "clean Git"):
            verifier.verify_candidate(self._arguments(), self._run)
        self.assertTrue(
            any("--untracked-files=all" in command for command in self.commands),
        )

    def test_distribution_rejects_embedded_identity_mismatch(self) -> None:
        self.metadata_override["com.ankiminer.android.SOURCE_COMMIT"] = "4" * 40
        with self.assertRaisesRegex(verifier.CandidateError, "SOURCE_COMMIT"):
            verifier.verify_candidate(self._arguments(), self._run)

    def test_distribution_rejects_unexpected_certificate(self) -> None:
        self.certificate = "cd" * 32
        with self.assertRaisesRegex(verifier.CandidateError, "certificate differs"):
            verifier.verify_candidate(self._arguments(), self._run)

    def test_manifest_must_be_in_immutable_build_directory(self) -> None:
        moved = self.root / "manifest.json"
        moved.write_text(self.runtime_manifest.read_text(encoding="utf-8"), encoding="utf-8")
        with self.assertRaisesRegex(verifier.CandidateError, "immutable build-key"):
            verifier.verify_candidate(
                self._arguments(runtime_manifest=moved),
                self._run,
            )


if __name__ == "__main__":
    unittest.main()
