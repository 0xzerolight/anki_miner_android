from __future__ import annotations

import argparse
from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

from scripts import android_test_receipt as receipt


SOURCE = {
    "head": "a" * 40,
    "tree": "b" * 40,
    "status": "",
    "fingerprint": "c" * 64,
}


class AndroidTestReceiptTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.runtime_manifest = self.root / "runtime-manifest.json"
        self.runtime_manifest.write_text("{}\n", encoding="utf-8")
        self.app_apk = self.root / "app-emulator-debug.apk"
        self.test_apk = self.root / "app-emulator-debug-androidTest.apk"
        self._write_zip(self.app_apk, "lib/x86_64/libprobe.so")
        self._write_zip(self.test_apk, "classes.dex")
        self.receipt_path = self.root / "receipt.json"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def _write_zip(path: Path, member: str) -> None:
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(member, b"payload")

    def _manifest_value(self, path: Path, field: str) -> str:
        self.assertEqual("application-id", field)
        if path == self.test_apk:
            return receipt.EXPECTED_TEST_APP_ID
        return receipt.EXPECTED_APP_ID

    def _write_args(self) -> argparse.Namespace:
        return argparse.Namespace(
            repo_root=str(self.root),
            receipt=str(self.receipt_path),
            runtime_manifest=str(self.runtime_manifest),
            s1a_manifest=None,
            task=receipt.EXPECTED_TASKS,
            gradle_argument=receipt.EXPECTED_GRADLE_ARGUMENTS,
            artifact=[
                f"app_emulator_debug={self.app_apk}",
                f"test_emulator_debug={self.test_apk}",
            ],
            ankidroid_apk=None,
            s2_reset_opt_in=False,
        )

    def _validate_args(self) -> argparse.Namespace:
        return argparse.Namespace(
            repo_root=str(self.root),
            receipt=str(self.receipt_path),
            require_s2=False,
            ankidroid_apk=None,
            s2_reset_opt_in=False,
        )

    def _write(self) -> None:
        with (
            mock.patch.object(receipt, "_source_identity", return_value=SOURCE),
            mock.patch.object(
                receipt,
                "_apk_manifest_value",
                side_effect=self._manifest_value,
            ),
            redirect_stdout(io.StringIO()),
        ):
            receipt.write_receipt(self._write_args())

    def test_receipt_binds_clean_source_tasks_manifests_and_artifacts(self) -> None:
        self._write()
        payload = json.loads(self.receipt_path.read_text(encoding="utf-8"))

        self.assertEqual(receipt.SCHEMA, payload["schema"])
        self.assertEqual(SOURCE, payload["source"])
        self.assertEqual(
            receipt.EXPECTED_TASKS,
            payload["gradle"]["tasks"],
        )
        self.assertEqual(
            str(self.runtime_manifest),
            payload["manifests"]["runtime"]["path"],
        )
        self.assertEqual(
            ["x86_64"],
            payload["artifacts"]["app_emulator_debug"]["abis"],
        )
        with mock.patch.object(receipt, "_source_identity", return_value=SOURCE):
            receipt.validate_receipt(self._validate_args())

    def test_changed_artifact_and_stale_source_fail_closed(self) -> None:
        self._write()
        self.app_apk.write_bytes(b"changed")
        with mock.patch.object(receipt, "_source_identity", return_value=SOURCE):
            with self.assertRaisesRegex(receipt.ReceiptError, "changed after"):
                receipt.validate_receipt(self._validate_args())

        self._write_zip(self.app_apk, "lib/x86_64/libprobe.so")
        stale = {**SOURCE, "head": "d" * 40}
        with mock.patch.object(receipt, "_source_identity", return_value=stale):
            with self.assertRaisesRegex(receipt.ReceiptError, "fingerprint is stale"):
                receipt.validate_receipt(self._validate_args())

    def test_payload_tampering_is_detected_before_use(self) -> None:
        self._write()
        payload = json.loads(self.receipt_path.read_text(encoding="utf-8"))
        payload["connected"]["abi"] = "arm64-v8a"
        self.receipt_path.write_text(json.dumps(payload), encoding="utf-8")

        with self.assertRaisesRegex(receipt.ReceiptError, "payload hash mismatch"):
            receipt.validate_receipt(self._validate_args())

    def test_incomplete_host_gate_cannot_write_a_receipt(self) -> None:
        args = self._write_args()
        args.task = [":app:assembleEmulatorDebug"]
        with (
            mock.patch.object(receipt, "_source_identity", return_value=SOURCE),
            self.assertRaisesRegex(receipt.ReceiptError, "authoritative host health"),
        ):
            receipt.write_receipt(args)

    def test_receipt_may_record_separately_gated_release_tasks(self) -> None:
        args = self._write_args()
        args.task = receipt.EXPECTED_TASKS + receipt.EXPECTED_RELEASE_TASKS
        with (
            mock.patch.object(receipt, "_source_identity", return_value=SOURCE),
            mock.patch.object(
                receipt,
                "_apk_manifest_value",
                side_effect=self._manifest_value,
            ),
            redirect_stdout(io.StringIO()),
        ):
            receipt.write_receipt(args)
        payload = json.loads(self.receipt_path.read_text(encoding="utf-8"))
        self.assertEqual(args.task, payload["gradle"]["tasks"])

    def test_s2_identity_binds_path_hash_certificate_version_and_reset(self) -> None:
        apk = self.root / "AnkiDroid.apk"
        apk.write_bytes(b"signed-apk")
        manifest_values = {
            "application-id": "com.ichi2.anki",
            "version-name": "2.24.0",
            "version-code": "422400300",
            "min-sdk": "24",
        }
        cert_output = (
            "Signer #1 certificate SHA-256 digest: "
            f"{receipt.EXPECTED_ANKIDROID['certificate_sha256']}\n"
        )
        with (
            mock.patch.object(
                receipt,
                "_sha256",
                return_value=receipt.EXPECTED_ANKIDROID["sha256"],
            ),
            mock.patch.object(receipt, "_run", return_value=cert_output),
            mock.patch.object(
                receipt,
                "_apk_manifest_value",
                side_effect=lambda _path, field: manifest_values[field],
            ),
        ):
            identity = receipt._ankidroid_identity(str(apk), True)

        self.assertEqual(str(apk), identity["path"])
        self.assertTrue(identity["destructive_reset_opt_in"])
        for field, expected in receipt.EXPECTED_ANKIDROID.items():
            self.assertEqual(expected, identity[field])
        with self.assertRaisesRegex(receipt.ReceiptError, "reset opt-in"):
            with (
                mock.patch.object(
                    receipt,
                    "_sha256",
                    return_value=receipt.EXPECTED_ANKIDROID["sha256"],
                ),
                mock.patch.object(receipt, "_run", return_value=cert_output),
                mock.patch.object(
                    receipt,
                    "_apk_manifest_value",
                    side_effect=lambda _path, field: manifest_values[field],
                ),
            ):
                receipt._ankidroid_identity(str(apk), False)


if __name__ == "__main__":
    unittest.main()
