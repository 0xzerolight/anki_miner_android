from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from scripts import resolve_ankidroid_canary as canary


class AnkiDroidPrereleaseCanaryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def _release(
        version: str = "2.25.0alpha2",
        *,
        release_id: int = 2502,
        published_at: str = "2026-07-09T18:12:00Z",
        digest: str | None = None,
    ) -> dict[str, object]:
        tag = f"v{version}"
        name = f"variant-abi-AnkiDroid-{version}-x86_64.apk"
        return {
            "id": release_id,
            "draft": False,
            "prerelease": True,
            "tag_name": tag,
            "published_at": published_at,
            "html_url": f"https://github.com/{canary.REPOSITORY}/releases/tag/{tag}",
            "assets": [
                {
                    "id": release_id * 10,
                    "name": name,
                    "browser_download_url": (
                        f"https://github.com/{canary.REPOSITORY}/releases/download/{tag}/{name}"
                    ),
                    "digest": digest if digest is not None else f"sha256:{'a' * 64}",
                    "size": 40 * 1024 * 1024,
                    "state": "uploaded",
                },
            ],
        }

    def test_latest_supported_prerelease_resolves_to_hashed_exact_asset(self) -> None:
        stable = dict(self._release("2.24.0beta4", release_id=2404))
        stable["prerelease"] = False
        older = self._release(
            "2.25.0alpha1",
            release_id=2501,
            published_at="2026-06-09T01:53:00Z",
        )
        document = canary.resolve_release([stable, older, self._release()])

        self.assertEqual(canary.SCHEMA, document["schema"])
        self.assertEqual("v2.25.0alpha2", document["release"]["tag"])
        self.assertEqual("a" * 64, document["asset"]["sha256"])
        self.assertEqual(canary.OFFICIAL_CERT_SHA256, document["signing_certificate_sha256"])
        self.assertEqual(document, canary.validate_manifest(document))

    def test_missing_digest_ambiguous_asset_and_manifest_tampering_fail(self) -> None:
        missing = self._release(digest="")
        with self.assertRaisesRegex(canary.CanaryError, "digest"):
            canary.resolve_release([missing])

        ambiguous = self._release()
        ambiguous["assets"].append(dict(ambiguous["assets"][0]))  # type: ignore[union-attr,index]
        with self.assertRaisesRegex(canary.CanaryError, "unique"):
            canary.resolve_release([ambiguous])

        document = canary.resolve_release([self._release()])
        document["asset"]["size_bytes"] += 1  # type: ignore[index,operator]
        with self.assertRaisesRegex(canary.CanaryError, "payload hash"):
            canary.validate_manifest(document)

    def test_downloaded_apk_requires_exact_manifest_and_official_single_signer(self) -> None:
        apk = self.root / "candidate.apk"
        apk.write_bytes(b"x" * (1024 * 1024))
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        document = canary.resolve_release([self._release(digest=f"sha256:{digest}")])
        document["asset"]["size_bytes"] = apk.stat().st_size  # type: ignore[index]
        document["payload_sha256"] = hashlib.sha256(canary._canonical_payload(document)).hexdigest()
        manifest = self.root / "manifest.json"
        manifest.write_text(json.dumps(document), encoding="utf-8")

        outputs = iter(
            (
                "com.ichi2.anki",
                "2.25.0alpha2",
                "425000200",
                "24",
                "Signer #1 certificate SHA-256 digest: " + canary.OFFICIAL_CERT_SHA256,
            ),
        )
        with mock.patch.object(canary, "_run", side_effect=lambda _command: next(outputs)):
            identity = canary.verify_apk(manifest, apk, "apkanalyzer", "apksigner")
        self.assertEqual(425000200, identity["version_code"])

        bad_outputs = iter(
            (
                "com.ichi2.anki",
                "2.25.0alpha2",
                "425000200",
                "24",
                "Signer #1 certificate SHA-256 digest: " + "b" * 64,
            ),
        )
        with (
            mock.patch.object(canary, "_run", side_effect=lambda _command: next(bad_outputs)),
            self.assertRaisesRegex(canary.CanaryError, "certificate"),
        ):
            canary.verify_apk(manifest, apk, "apkanalyzer", "apksigner")

    def test_connected_runner_preserves_stable_receipt_and_passes_exact_version(self) -> None:
        runner = (
            Path(__file__).resolve().parents[1] / "run-s2-ankidroid-prerelease-canary.sh"
        ).read_text(encoding="utf-8")
        workflow = (
            Path(__file__).resolve().parents[2]
            / ".github/workflows/ankidroid-prerelease-canary.yml"
        ).read_text(encoding="utf-8")

        self.assertIn('--ankidroid-apk "$STABLE_APK"', runner)
        self.assertIn("--require-s2", runner)
        self.assertIn("--s2-reset-opt-in", runner)
        self.assertIn('ANKI_MINER_S2_ALLOW_COLLECTION_RESET:-', runner)
        self.assertIn("verify-apk", runner)
        self.assertIn("ankiMinerExpectedAnkiDroidVersionName", runner)
        self.assertIn("ankiMinerExpectedAnkiDroidVersionCode", runner)
        self.assertIn("api.github.com/repos/ankidroid/Anki-Android/releases", workflow)
        self.assertIn("group: anki-miner-android-hardware-ci", workflow)
        self.assertIn("resolve_ankidroid_canary.py verify-apk", workflow)
        self.assertNotRegex(workflow, r"actions/[a-z-]+@v[0-9]")


if __name__ == "__main__":
    unittest.main()
