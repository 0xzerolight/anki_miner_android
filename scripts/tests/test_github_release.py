from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest import mock
import zipfile

from scripts import github_release as release


SOURCE = "a" * 40
CERTIFICATE = "b" * 64


class GithubReleaseTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def _write_apk(
        path: Path,
        entries: list[tuple[str, bytes]],
        compression: int = zipfile.ZIP_STORED,
    ) -> None:
        with zipfile.ZipFile(path, "w", compression=compression) as archive:
            for name, payload in entries:
                archive.writestr(name, payload)

    def _valid_apk(self, name: str = "candidate.apk") -> Path:
        path = self.root / name
        self._write_apk(
            path,
            [
                ("AndroidManifest.xml", b"manifest"),
                ("classes.dex", b"dex"),
                ("lib/arm64-v8a/libprobe.so", b"elf"),
            ],
        )
        return path

    @staticmethod
    def _manifest(source: str = SOURCE) -> dict[str, str]:
        return {
            "application_id": release.APPLICATION_ID,
            "version_name": release.EXPECTED_VERSION_NAME,
            "version_code": release.EXPECTED_VERSION_CODE,
            "min_sdk": release.EXPECTED_MIN_SDK,
            "target_sdk": release.EXPECTED_TARGET_SDK,
            "source_commit": source,
            "release_channel": release.RELEASE_CHANNEL,
        }

    def test_payload_digest_ignores_only_standard_signature_entries(self) -> None:
        unsigned = self.root / "unsigned.apk"
        signed = self.root / "signed.apk"
        payload = [
            ("AndroidManifest.xml", b"manifest"),
            ("classes.dex", b"dex"),
            ("lib/arm64-v8a/libprobe.so", b"elf"),
        ]
        self._write_apk(unsigned, payload)
        self._write_apk(
            signed,
            [
                ("META-INF/SIGNER.RSA", b"certificate"),
                *reversed(payload),
                ("META-INF/SIGNER.SF", b"signature"),
                ("META-INF/MANIFEST.MF", b"signature manifest"),
            ],
            compression=zipfile.ZIP_DEFLATED,
        )

        unsigned_digest, unsigned_inventory, unsigned_abis = release.payload_inventory(
            unsigned
        )
        signed_digest, signed_inventory, signed_abis = release.payload_inventory(signed)

        self.assertEqual(unsigned_digest, signed_digest)
        self.assertEqual(unsigned_inventory, signed_inventory)
        self.assertEqual([release.EXPECTED_ABI], unsigned_abis)
        self.assertEqual(unsigned_abis, signed_abis)

    def test_payload_rejects_unsafe_duplicate_debug_and_unidic_entries(self) -> None:
        cases = {
            "debug.apk": [("assets/engine-v2.json", b"fixture")],
            "unidic.apk": [("assets/UniDic/sys.dic", b"dictionary")],
        }
        for filename, extra in cases.items():
            with self.subTest(filename=filename):
                path = self.root / filename
                self._write_apk(
                    path,
                    [("lib/arm64-v8a/libprobe.so", b"elf"), *extra],
                )
                with self.assertRaises(release.ReleaseError):
                    release.payload_inventory(path)

        duplicate = self.root / "duplicate.apk"
        with zipfile.ZipFile(duplicate, "w") as archive:
            archive.writestr("classes.dex", b"one")
            with self.assertWarns(UserWarning):
                archive.writestr("classes.dex", b"two")
        with self.assertRaisesRegex(release.ReleaseError, "duplicate APK entry"):
            release.payload_inventory(duplicate)

    def test_unsigned_and_signed_verification_bind_identity_and_certificate(self) -> None:
        apk = self._valid_apk()
        failed_signature = subprocess.CompletedProcess(
            ["apksigner"], returncode=1, stdout="", stderr="unsigned"
        )
        valid_signature = subprocess.CompletedProcess(
            ["apksigner"], returncode=0, stdout="", stderr=""
        )
        with (
            mock.patch.object(release, "_manifest_identity", return_value=self._manifest()),
            mock.patch.object(release, "_run", return_value=failed_signature),
        ):
            result = release.inspect_apk(apk, expected_source=SOURCE, signed=False)
        self.assertIsNone(result["signing_certificate_sha256"])

        with (
            mock.patch.object(release, "_manifest_identity", return_value=self._manifest()),
            mock.patch.object(release, "_run", return_value=valid_signature),
            mock.patch.object(
                release, "_signature_fingerprint", return_value=CERTIFICATE
            ),
        ):
            result = release.inspect_apk(
                apk,
                expected_source=SOURCE,
                signed=True,
                expected_certificate=CERTIFICATE,
            )
        self.assertEqual(CERTIFICATE, result["signing_certificate_sha256"])

        with (
            mock.patch.object(release, "_manifest_identity", return_value=self._manifest()),
            mock.patch.object(release, "_run", return_value=valid_signature),
            mock.patch.object(release, "_signature_fingerprint", return_value=CERTIFICATE),
            self.assertRaisesRegex(release.ReleaseError, "unexpected"),
        ):
            release.inspect_apk(
                apk,
                expected_source=SOURCE,
                signed=True,
                expected_certificate="c" * 64,
            )

    def test_apk_rejects_more_than_one_signer(self) -> None:
        apk = self._valid_apk()
        output = "\n".join(
            [
                f"Signer #1 certificate SHA-256 digest: {CERTIFICATE}",
                f"Signer #2 certificate SHA-256 digest: {'c' * 64}",
            ]
        )
        with (
            mock.patch.object(
                release,
                "_run",
                return_value=subprocess.CompletedProcess([], 0, output, ""),
            ),
            self.assertRaisesRegex(release.ReleaseError, "exactly one"),
        ):
            release._signature_fingerprint(apk)

    def test_certificate_rejects_extra_pem_blocks_before_openssl(self) -> None:
        certificate = self.root / "certificate.pem"
        valid = (
            b"-----BEGIN CERTIFICATE-----\n"
            b"QUJD\n"
            b"-----END CERTIFICATE-----\n"
        )
        release._validate_single_certificate_pem(valid)
        certificate.write_bytes(
            valid
            + b"-----BEGIN PRIVATE KEY-----\n"
            + b"QUJD\n"
            + b"-----END PRIVATE KEY-----\n"
        )
        with (
            mock.patch.object(release, "_run") as run,
            self.assertRaisesRegex(release.ReleaseError, "exactly one"),
        ):
            release._canonical_certificate(certificate)
        run.assert_not_called()

    def test_manifest_mismatch_and_wrong_abi_fail_closed(self) -> None:
        apk = self._valid_apk()
        unsigned = subprocess.CompletedProcess([], 1, "", "unsigned")
        mismatched = self._manifest()
        mismatched["release_channel"] = "development"
        with (
            mock.patch.object(release, "_manifest_identity", return_value=mismatched),
            mock.patch.object(release, "_run", return_value=unsigned),
            self.assertRaisesRegex(release.ReleaseError, "manifest identity"),
        ):
            release.inspect_apk(apk, expected_source=SOURCE, signed=False)

        wrong_abi = self.root / "wrong.apk"
        self._write_apk(wrong_abi, [("lib/x86_64/libprobe.so", b"elf")])
        with self.assertRaisesRegex(release.ReleaseError, "ABI set"):
            release.inspect_apk(wrong_abi, expected_source=SOURCE, signed=False)

    def test_receipt_v2_is_source_bound_and_hash_bound_without_copying_it(self) -> None:
        path = self.root / "receipt.json"
        document = {
            "schema": release.ACCEPTANCE_SCHEMA,
            "source": {"commit": SOURCE, "tree": "e" * 40},
            "publication": {"build_key": "4" * 64},
            "artifact": {"filename": "accepted.apk", "sha256": "5" * 64, "size_bytes": 1},
            "device": {
                "manufacturer": "Example",
                "model": "Phone",
                "api_level": 36,
                "abi": "arm64-v8a",
                "page_size_bytes": 4096,
                "total_memory_bytes": 4 * 1024**3,
            },
            "measurements": {"cold_init_ms": [1.0, 2.0, 3.0]},
            "representative_mining": {
                "workload_id": "reading-process-reading-v1",
                "selected_count": 100,
                "cards_created": 100,
                "elapsed_ms": 1000.0,
                "peak_rss_bytes": 100,
                "completed": True,
            },
            "thresholds": {
                "cold_init_max_ms_exclusive": 4000.0,
                "peak_rss_max_bytes_inclusive": 384 * 1024**2,
            },
        }
        document["payload_sha256"] = hashlib.sha256(
            release._canonical_json(document)
        ).hexdigest()
        path.write_text(json.dumps(document), encoding="utf-8")

        identity = release._load_acceptance(path, SOURCE)

        self.assertEqual(release.ACCEPTANCE_SCHEMA, identity["schema"])
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), identity["sha256"])
        self.assertNotIn("path", identity)
        with self.assertRaisesRegex(release.ReleaseError, "another source"):
            release._load_acceptance(path, "d" * 40)

    def test_corresponding_source_archive_is_complete_and_identity_bound(self) -> None:
        staging = self.root / "staging" / "bundle"
        staging.mkdir(parents=True)
        tracked_payloads = {
            relative: relative.encode("utf-8")
            for relative in release.REQUIRED_SOURCE_PATHS
        }
        tracked_payloads["app/src/main/kotlin/Example.kt"] = b"class Example\n"
        tracked_tree: dict[str, tuple[str, str]] = {}
        for relative, payload in tracked_payloads.items():
            path = staging / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(payload)
            mode = "100755" if relative == "gradlew" else "100644"
            path.chmod(0o755 if mode == "100755" else 0o644)
            tracked_tree[relative] = (
                mode,
                hashlib.sha1(
                    f"blob {len(payload)}\0".encode("ascii") + payload
                ).hexdigest(),
            )
        external_path = staging / "external/ffmpeg-source.tar.xz"
        external_path.parent.mkdir(parents=True)
        external_payload = b"reviewed external source"
        external_path.write_bytes(external_payload)
        external_path.chmod(0o644)
        external_inventory = release._external_source_inventory_payload(
            [
                {
                    "mode": "0644",
                    "path": "external/ffmpeg-source.tar.xz",
                    "sha256": hashlib.sha256(external_payload).hexdigest(),
                    "size": len(external_payload),
                    "type": "file",
                }
            ]
        )
        (staging / release.EXTERNAL_SOURCE_INVENTORY_NAME).write_bytes(
            external_inventory
        )
        manifest = release._source_manifest(
            source={"commit": SOURCE, "tree": "e" * 40},
            engine_revision="d" * 40,
            runtime_build_key="4" * 64,
            s1a_build_key="5" * 64,
            tracked_tree_inventory_sha256=release._tracked_tree_inventory_sha256(
                tracked_tree
            ),
            external_source_inventory_sha256=hashlib.sha256(
                external_inventory
            ).hexdigest(),
        )
        (staging / release.SOURCE_MANIFEST_NAME).write_text(
            json.dumps(manifest), encoding="utf-8"
        )
        uncompressed = self.root / "source.tar"
        compressed = self.root / "source.tar.zst"
        with tarfile.open(uncompressed, "w") as archive:
            archive.add(staging, arcname="bundle")
        subprocess.run(
            ["zstd", "--quiet", "--force", str(uncompressed), "-o", str(compressed)],
            check=True,
        )

        self.assertEqual(
            manifest,
            release._inspect_source_archive(compressed, manifest, tracked_tree),
        )
        changed = dict(manifest)
        changed["source_commit"] = "c" * 40
        with self.assertRaisesRegex(release.ReleaseError, "identity differs"):
            release._inspect_source_archive(compressed, changed, tracked_tree)
        missing_tree = dict(tracked_tree)
        missing_tree["app/src/main/kotlin/Missing.kt"] = ("100644", "f" * 40)
        with self.assertRaisesRegex(release.ReleaseError, "exact tracked tree"):
            release._inspect_source_archive(compressed, manifest, missing_tree)

    def test_asset_verifier_enforces_exact_allowlist_and_checksums(self) -> None:
        directory = self.root / "assets"
        directory.mkdir()
        version = release.EXPECTED_VERSION_NAME
        apk_name = f"anki-miner-android-{version}-arm64-v8a.apk"
        apk = directory / apk_name
        self._write_apk(apk, [("lib/arm64-v8a/libprobe.so", b"elf")])
        assets: dict[str, dict[str, object]] = {}
        mappings = {
            "apk": apk_name,
            "certificate": "app-signing-certificate.pem",
            "certificate_fingerprint": "app-signing-certificate.sha256",
            "corresponding_source": f"anki-miner-android-{version}-corresponding-source.tar.zst",
            "notices": f"anki-miner-android-{version}-notices.tar.zst",
        }
        for key, filename in mappings.items():
            path = directory / filename
            if key != "apk":
                path.write_bytes(
                    (CERTIFICATE + "\n").encode()
                    if key == "certificate_fingerprint"
                    else key.encode()
                )
            assets[key] = {
                "filename": filename,
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "size": path.stat().st_size,
            }
        def approval(required: bool = True) -> dict[str, object]:
            return {
                "required": required,
                "outcome": "passed" if required else "not_applicable_to_channel",
                "procedure": "release/CHECKLIST.md",
                "completed_utc": "2026-07-17T12:00:00Z",
                "operator": "tester",
                "evidence_sha256": "9" * 64,
            }

        source_name = mappings["corresponding_source"]
        tracked_tree = {"README.md": ("100644", "f" * 40)}
        source_manifest = release._source_manifest(
            source={"commit": SOURCE, "tree": "e" * 40},
            engine_revision="d" * 40,
            runtime_build_key="4" * 64,
            s1a_build_key="5" * 64,
            tracked_tree_inventory_sha256=release._tracked_tree_inventory_sha256(
                tracked_tree
            ),
            external_source_inventory_sha256="a" * 64,
        )
        expected_names = sorted([*mappings.values(), "release.json", "SHA256SUMS"])
        record = {
            "releaseSchemaVersion": release.RECORD_SCHEMA_VERSION,
            "channel": "github-apk-prerelease",
            "tag": f"v{version}",
            "releaseUrl": f"https://github.com/example/repo/releases/tag/v{version}",
            "versionName": version,
            "versionCode": int(release.EXPECTED_VERSION_CODE),
            "sourceCommit": SOURCE,
            "sourceTree": "e" * 40,
            "engineRevision": "d" * 40,
            "runtimeWheelBuildKey": "4" * 64,
            "s1aPublicationBuildKey": "5" * 64,
            "s1aAcceptance": {
                "schema": release.ACCEPTANCE_SCHEMA,
                "sha256": "1" * 64,
                "payload_sha256": "2" * 64,
                "source_commit": SOURCE,
                "source_tree": "e" * 40,
                "publication_build_key": "5" * 64,
            },
            "artifacts": [{
                "filename": apk_name,
                "applicationId": release.APPLICATION_ID,
                "versionName": version,
                "versionCode": int(release.EXPECTED_VERSION_CODE),
                "minSdk": int(release.EXPECTED_MIN_SDK),
                "targetSdk": int(release.EXPECTED_TARGET_SDK),
                "abis": [release.EXPECTED_ABI],
                "unsignedSha256": "3" * 64,
                "signedSha256": assets["apk"]["sha256"],
                "signedSize": apk.stat().st_size,
                "payloadInventoryBeforeSigning": "f" * 64,
                "payloadInventoryAfterSigning": "f" * 64,
                "signingCertificateSha256": CERTIFICATE,
                "zipalignVerified": True,
                "signatureVerified": True,
            }],
            "assetAllowlist": expected_names,
            "sourceArchive": {
                **assets["corresponding_source"],
                "manifest": source_manifest,
                "url": f"https://github.com/example/repo/releases/download/v{version}/{source_name}",
            },
            "toolchain": {
                "androidBuildTools": "36.0.0",
                "androidNdk": "28.2.13676358",
                "runtimeManifestSha256": "6" * 64,
                "s1aManifestSha256": "7" * 64,
            },
            "declarations": {
                name: approval(name in release.REQUIRED_DECLARATIONS)
                for name in release.REQUIRED_DECLARATIONS | release.PLAY_ONLY_DECLARATIONS
            },
            "gates": {name: approval() for name in release.REQUIRED_GATES},
        }
        (directory / "release.json").write_text(json.dumps(record), encoding="utf-8")
        checksummed = [*mappings.values(), "release.json"]
        (directory / "SHA256SUMS").write_text(
            "".join(
                f"{hashlib.sha256((directory / name).read_bytes()).hexdigest()}  {name}\n"
                for name in sorted(checksummed)
            ),
            encoding="utf-8",
        )
        signed_identity = {
            "apk_sha256": assets["apk"]["sha256"],
            "apk_size": apk.stat().st_size,
            "payload_inventory_sha256": "f" * 64,
        }
        with (
            mock.patch.object(
                release,
                "_canonical_certificate",
                return_value=(b"certificate", CERTIFICATE),
            ),
            mock.patch.object(release, "inspect_apk", return_value=signed_identity),
            mock.patch.object(
                release,
                "_source_identity",
                return_value={"commit": SOURCE, "tree": "e" * 40},
            ) as source_identity,
            mock.patch.object(release, "_tracked_tree", return_value=tracked_tree),
            mock.patch.object(
                release, "_inspect_source_archive", return_value=source_manifest
            ),
        ):
            release.verify_assets_directory(
                directory,
                f"v{version}",
                CERTIFICATE,
                expected_repository="example/repo",
            )
            source_identity.return_value = {"commit": "c" * 40, "tree": "e" * 40}
            with self.assertRaisesRegex(release.ReleaseError, "checked-out tag"):
                release.verify_assets_directory(
                    directory,
                    f"v{version}",
                    CERTIFICATE,
                    expected_repository="example/repo",
                )
            source_identity.return_value = {"commit": SOURCE, "tree": "e" * 40}
            record["releaseUrl"] = (
                f"https://github.com/another/repo/releases/tag/v{version}"
            )
            (directory / "release.json").write_text(json.dumps(record), encoding="utf-8")
            (directory / "SHA256SUMS").write_text(
                "".join(
                    f"{hashlib.sha256((directory / name).read_bytes()).hexdigest()}  {name}\n"
                    for name in sorted(checksummed)
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(release.ReleaseError, "URL"):
                release.verify_assets_directory(
                    directory,
                    f"v{version}",
                    CERTIFICATE,
                    expected_repository="example/repo",
                )
            record["releaseUrl"] = (
                f"https://github.com/example/repo/releases/tag/v{version}"
            )
            (directory / "release.json").write_text(json.dumps(record), encoding="utf-8")
            (directory / "SHA256SUMS").write_text(
                "".join(
                    f"{hashlib.sha256((directory / name).read_bytes()).hexdigest()}  {name}\n"
                    for name in sorted(checksummed)
                ),
                encoding="utf-8",
            )
            (directory / "unexpected.aab").write_bytes(b"forbidden")
            with self.assertRaisesRegex(release.ReleaseError, "allowlist"):
                release.verify_assets_directory(
                    directory,
                    f"v{version}",
                    CERTIFICATE,
                    expected_repository="example/repo",
                )

    def test_non_passing_or_stale_release_approval_is_rejected(self) -> None:
        template = json.loads(
            (
                Path(release.__file__).resolve().parents[1]
                / "release/approval-template.json"
            ).read_text(encoding="utf-8")
        )
        with self.assertRaisesRegex(release.ReleaseError, "tag, source, or signed APK"):
            release._validate_approvals(
                template,
                tag=f"v{release.EXPECTED_VERSION_NAME}",
                source_commit=SOURCE,
                signed_apk_sha256="3" * 64,
            )
        template["source_commit"] = SOURCE
        template["signed_apk_sha256"] = "3" * 64
        with self.assertRaisesRegex(release.ReleaseError, "has not passed"):
            release._validate_approvals(
                template,
                tag=f"v{release.EXPECTED_VERSION_NAME}",
                source_commit=SOURCE,
                signed_apk_sha256="3" * 64,
            )

    def test_only_explicit_private_rehearsal_allows_its_gate_not_run(self) -> None:
        template = json.loads(
            (
                Path(release.__file__).resolve().parents[1]
                / "release/approval-template.json"
            ).read_text(encoding="utf-8")
        )
        template["source_commit"] = SOURCE
        template["signed_apk_sha256"] = "3" * 64
        for name, entry in template["declarations"].items():
            entry["completed_utc"] = "2026-07-17T12:00:00Z"
            entry["operator"] = "tester"
            entry["evidence_sha256"] = "9" * 64
            entry["outcome"] = (
                "passed"
                if name in release.REQUIRED_DECLARATIONS
                else "not_applicable_to_channel"
            )
        for name, entry in template["gates"].items():
            entry["completed_utc"] = "2026-07-17T12:00:00Z"
            entry["operator"] = "tester"
            entry["evidence_sha256"] = "9" * 64
            entry["outcome"] = (
                "not_run" if name == "private_repository_rehearsal" else "passed"
            )
        arguments = {
            "tag": f"v{release.EXPECTED_VERSION_NAME}",
            "source_commit": SOURCE,
            "signed_apk_sha256": "3" * 64,
        }

        release._validate_approvals(
            template,
            **arguments,
            allow_private_rehearsal_pending=True,
        )
        with self.assertRaisesRegex(release.ReleaseError, "has not passed"):
            release._validate_approvals(template, **arguments)

    def test_signing_script_never_places_password_values_on_command_line(self) -> None:
        script = (Path(release.__file__).with_name("sign-github-apk.sh")).read_text(
            encoding="utf-8"
        )
        self.assertIn("--ks-pass env:ANKI_MINER_SIGNING_STORE_PASSWORD", script)
        self.assertIn("--key-pass env:ANKI_MINER_SIGNING_KEY_PASSWORD", script)
        self.assertNotIn("--ks-pass pass:", script)
        self.assertNotIn("--key-pass pass:", script)

    def test_local_publish_verifies_download_before_publishing(self) -> None:
        script = (
            Path(release.__file__).resolve().parent
            / "publish-github-prerelease.sh"
        ).read_text(encoding="utf-8")
        download = script.index('gh release download "$tag"')
        verify = script.index('github_release.py" verify-assets')
        publish = script.index('gh release edit "$tag" --draft=false --prerelease')
        self.assertLess(download, verify)
        self.assertLess(verify, publish)
        self.assertIn(
            "repos/$repository/compare/$remote_tag_commit...$default_branch", script
        )
        self.assertIn("A private repository must publish through", script)


if __name__ == "__main__":
    unittest.main()
