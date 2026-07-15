from __future__ import annotations

import argparse
from pathlib import Path
import sys
import tempfile
import unittest


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

from verify_android_toolchain import VerificationError, verify  # noqa: E402


class ToolchainVerificationTest(unittest.TestCase):
    def fixture(self, root: Path, installed_revision: str = "37.0.0") -> argparse.Namespace:
        sdk_root = root / "sdk"
        package_dir = sdk_root / "platform-tools"
        package_dir.mkdir(parents=True)
        (package_dir / "package.xml").write_text(
            """<?xml version="1.0" encoding="UTF-8"?>
<sdk:repository xmlns:sdk="http://schemas.android.com/repository/android/common/02">
  <sdk:localPackage path="platform-tools">
    <sdk:revision>
      <sdk:major>37</sdk:major>
      <sdk:minor>0</sdk:minor>
      <sdk:micro>0</sdk:micro>
    </sdk:revision>
  </sdk:localPackage>
</sdk:repository>""",
            encoding="utf-8",
        )
        lock = root / "packages.lock"
        lock.write_text(
            "platform-tools|37.0.0|platform-tools/package.xml\n",
            encoding="utf-8",
        )
        installed = root / "installed.txt"
        installed.write_text(
            "Installed packages:\nPath | Version | Description\n"
            f"platform-tools | {installed_revision} | tools\n",
            encoding="utf-8",
        )
        avd_home = root / "avd"
        avd_dir = avd_home / "fixture.avd"
        avd_dir.mkdir(parents=True)
        (avd_home / "fixture.ini").write_text(
            f"path={avd_dir}\npath.rel=avd/fixture.avd\ntarget=android-36\n",
            encoding="utf-8",
        )
        (avd_dir / "config.ini").write_text(
            "avd.id=<build>\n"
            "abi.type=x86_64\n"
            "image.sysdir.1=system-images/android-36/google_apis_ps16k/x86_64/\n"
            "tag.id=page_size_16kb\n"
            "tag.ids=page_size_16kb,google_apis\n",
            encoding="utf-8",
        )
        return argparse.Namespace(
            sdk_root=sdk_root,
            installed_list=installed,
            lock=lock,
            avd_home=avd_home,
            avd=["fixture|system-images;android-36;google_apis_ps16k;x86_64"],
        )

    def test_accepts_exact_package_and_avd_lock(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            verify(self.fixture(Path(directory)))

    def test_rejects_sdkmanager_revision_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(VerificationError, "sdkmanager revision"):
                verify(self.fixture(Path(directory), installed_revision="38.0.0"))

    def test_rejects_package_xml_path_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            arguments = self.fixture(Path(directory))
            package_xml = arguments.sdk_root / "platform-tools" / "package.xml"
            package_xml.write_text(
                package_xml.read_text(encoding="utf-8").replace(
                    'path="platform-tools"',
                    'path="platform-tools-preview"',
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(VerificationError, "path is"):
                verify(arguments)

    def test_rejects_wrong_ps16k_primary_tag(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            arguments = self.fixture(Path(directory))
            config = arguments.avd_home / "fixture.avd" / "config.ini"
            config.write_text(
                config.read_text(encoding="utf-8").replace(
                    "tag.id=page_size_16kb",
                    "tag.id=google_apis",
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(VerificationError, "tag.id is"):
                verify(arguments)

    def test_rejects_missing_ps16k_secondary_tag(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            arguments = self.fixture(Path(directory))
            config = arguments.avd_home / "fixture.avd" / "config.ini"
            config.write_text(
                config.read_text(encoding="utf-8").replace(
                    "tag.ids=page_size_16kb,google_apis",
                    "tag.ids=page_size_16kb",
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(VerificationError, "tag.ids is"):
                verify(arguments)

    def test_rejects_relative_avd_identity_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            arguments = self.fixture(Path(directory))
            root_config = arguments.avd_home / "fixture.ini"
            root_config.write_text(
                root_config.read_text(encoding="utf-8").replace(
                    "path.rel=avd/fixture.avd",
                    "path.rel=avd/other.avd",
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(VerificationError, "path.rel is"):
                verify(arguments)

    def test_rejects_mismatched_legacy_avd_id_when_present(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            arguments = self.fixture(Path(directory))
            config = arguments.avd_home / "fixture.avd" / "config.ini"
            config.write_text(
                f"AvdId=other\n{config.read_text(encoding='utf-8')}",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(VerificationError, "AvdId is"):
                verify(arguments)


if __name__ == "__main__":
    unittest.main()
