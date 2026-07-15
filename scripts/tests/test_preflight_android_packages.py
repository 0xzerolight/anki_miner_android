from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

from preflight_android_packages import preflight  # noqa: E402
from verify_android_toolchain import VerificationError  # noqa: E402


LOCKED_PACKAGES = {
    "platform-tools": "37.0.0",
    "emulator": "36.6.11",
    "platforms;android-36": "2",
    "build-tools;36.0.0": "36.0.0",
    "cmake;3.22.1": "3.22.1",
    "ndk;28.2.13676358": "28.2.13676358",
    "system-images;android-36;google_apis;x86_64": "7",
    "system-images;android-36;google_apis_ps16k;x86_64": "7",
}


def write_listing(path: Path, revisions: dict[str, str]) -> None:
    rows = "\n".join(
        f"{package} | {revision} | fixture" for package, revision in revisions.items()
    )
    path.write_text(
        "Available Packages:\nPath | Version | Description\n"
        "------- | ------- | -------\n"
        f"{rows}\n",
        encoding="utf-8",
    )


class PackagePreflightTest(unittest.TestCase):
    def test_accepts_exact_stable_channel_revisions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            listing = Path(directory) / "sdkmanager-list.txt"
            write_listing(listing, LOCKED_PACKAGES)
            preflight(SCRIPTS_DIR / "android-sdk-packages.lock", listing)

    def test_rejects_remote_revision_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            listing = Path(directory) / "sdkmanager-list.txt"
            revisions = dict(LOCKED_PACKAGES)
            revisions["emulator"] = "99.0.0"
            write_listing(listing, revisions)
            with self.assertRaisesRegex(VerificationError, "stable-channel revision"):
                preflight(SCRIPTS_DIR / "android-sdk-packages.lock", listing)

    def test_rejects_update_beyond_lock_from_exact_install(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            listing = Path(directory) / "sdkmanager-list.txt"
            installed_rows = "\n".join(
                f"{package} | {revision} | fixture"
                for package, revision in LOCKED_PACKAGES.items()
            )
            listing.write_text(
                "Installed packages:\nPath | Version | Description\n"
                "------- | ------- | -------\n"
                f"{installed_rows}\n"
                "Available Updates:\nID | Installed | Available\n"
                "------- | ------- | -------\n"
                "emulator | 36.6.11 | 99.0.0\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(VerificationError, "stable-channel revision"):
                preflight(SCRIPTS_DIR / "android-sdk-packages.lock", listing)

    def test_installer_does_not_mutate_when_preflight_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            listing = root / "remote-list.txt"
            revisions = dict(LOCKED_PACKAGES)
            revisions["emulator"] = "99.0.0"
            write_listing(listing, revisions)

            sdkmanager = (
                root
                / "sdk"
                / "cmdline-tools"
                / "14742923"
                / "bin"
                / "sdkmanager"
            )
            sdkmanager.parent.mkdir(parents=True)
            sdkmanager.write_text(
                """#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$*" >>"$FAKE_CALLS"
if [[ "${*: -1}" == "--list" ]]; then
    cat "$FAKE_LIST"
else
    touch "$FAKE_MUTATION"
fi
""",
                encoding="utf-8",
            )
            sdkmanager.chmod(0o755)

            license_dir = root / "sdk" / "licenses"
            license_dir.mkdir(parents=True)
            (license_dir / "android-sdk-license").write_text(
                "24333f8a63b6825ea9c5514f83c2829b004d1fee\n",
                encoding="utf-8",
            )

            calls = root / "calls.txt"
            mutation = root / "mutation"
            environment = os.environ.copy()
            environment.update(
                {
                    "ANKI_MINER_ANDROID_TOOLCHAIN_ROOT": str(root),
                    "FAKE_CALLS": str(calls),
                    "FAKE_LIST": str(listing),
                    "FAKE_MUTATION": str(mutation),
                },
            )
            result = subprocess.run(
                [str(SCRIPTS_DIR / "install-android-sdk-packages.sh")],
                check=False,
                capture_output=True,
                env=environment,
                text=True,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("stable-channel revision", result.stderr)
            self.assertFalse(mutation.exists())
            self.assertEqual(1, len(calls.read_text(encoding="utf-8").splitlines()))


if __name__ == "__main__":
    unittest.main()
