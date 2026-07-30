from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

PROVISION_SCRIPT = Path(__file__).resolve().parents[1] / "provision-android.sh"


class ProvisionAndroidCleanupTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.checkout = self.root / "checkout"
        self.scripts = self.checkout / "scripts"
        self.fake_bin = self.root / "bin"
        self.toolchain = self.root / "toolchain"
        self.scripts.mkdir(parents=True)
        self.fake_bin.mkdir()
        (self.checkout / "tools/runtime-wheels").mkdir(parents=True)
        shutil.copy2(PROVISION_SCRIPT, self.scripts / "provision-android.sh")

        self._write_script(
            self.scripts / "android-env.sh",
            """
export ANDROID_HOME="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/sdk"
export ANDROID_USER_HOME="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/android-user-home"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export GRADLE_USER_HOME="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/gradle-user-home"
export JAVA_HOME="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/jdk-17"
export ANDROID_CMDLINE_TOOLS_VERSION="14742923"
export ANDROID_CMDLINE_TOOLS_HOME="$ANDROID_HOME/cmdline-tools/$ANDROID_CMDLINE_TOOLS_VERSION"
export ANDROID_AVD_API26_NAME="anki_miner_api26"
export ANDROID_AVD_4K_NAME="anki_miner_api36"
export ANDROID_AVD_16K_NAME="anki_miner_api36_ps16k"
export ANDROID_SYSTEM_IMAGE_API26="system-images;android-26;google_apis;x86_64"
export ANDROID_SYSTEM_IMAGE_4K="system-images;android-36;google_apis;x86_64"
export ANDROID_SYSTEM_IMAGE_16K="system-images;android-36;google_apis_ps16k;x86_64"
export PATH="$FAKE_BIN:$JAVA_HOME/bin:$ANDROID_CMDLINE_TOOLS_HOME/bin:$PATH"
""",
        )
        for relative_path in (
            "provision-chaquopy-build-python.sh",
            "install-android-sdk-packages.sh",
            "verify-android-toolchain.sh",
        ):
            self._write_script(self.scripts / relative_path, "exit 0\n")
        self._write_script(
            self.checkout / "tools/runtime-wheels/build-runtime-wheels.sh",
            "exit 0\n",
        )
        self._write_script(self.fake_bin / "sha1sum", "exit 0\n")
        self._write_script(self.fake_bin / "sha256sum", "exit 0\n")
        self._write_script(
            self.fake_bin / "emulator",
            """
if [[ "${1:-}" == "-list-avds" ]]; then
    printf '%s\n' anki_miner_api26 anki_miner_api36 anki_miner_api36_ps16k
fi
""",
        )
        self._write_script(self.fake_bin / "adb", "exit 0\n")
        self._write_script(
            self.fake_bin / "unzip",
            """
destination="${@: -1}"
mkdir -p "$destination/cmdline-tools/bin"
touch "$destination/cmdline-tools/bin/sdkmanager"
chmod +x "$destination/cmdline-tools/bin/sdkmanager"
""",
        )
        self._write_script(
            self.fake_bin / "tar",
            """
destination=""
while (($#)); do
    if [[ "$1" == "-C" ]]; then
        shift
        destination="$1"
    fi
    shift
done
mkdir -p "$destination/bin"
printf '#!/usr/bin/env bash\nexit 0\n' >"$destination/bin/java"
chmod +x "$destination/bin/java"
""",
        )
        downloads = self.toolchain / "downloads"
        downloads.mkdir(parents=True)
        (downloads / "temurin-17.0.19_10.tar.gz").touch()
        (downloads / "commandlinetools-linux-14742923_latest.zip").touch()

    @staticmethod
    def _write_script(path: Path, body: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(f"#!/usr/bin/env bash\n{body.lstrip()}", encoding="utf-8")
        path.chmod(0o755)

    def _environment(self) -> dict[str, str]:
        environment = os.environ.copy()
        environment.update(
            {
                "ANKI_MINER_ANDROID_TOOLCHAIN_ROOT": str(self.toolchain),
                "FAKE_BIN": str(self.fake_bin),
                "PATH": f"{self.fake_bin}:{environment['PATH']}",
            },
        )
        return environment

    def _run(self, environment: dict[str, str]) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["bash", str(self.scripts / "provision-android.sh")],
            cwd=self.checkout,
            check=False,
            capture_output=True,
            env=environment,
            text=True,
        )

    def _victim(self, name: str) -> Path:
        victim = self.root / name
        victim.mkdir()
        (victim / "keep").write_text("unrelated project\n", encoding="utf-8")
        return victim

    def test_inherited_jdk_staging_is_never_cleaned_when_only_tools_install(self) -> None:
        self._write_script(self.toolchain / "jdk-17/bin/java", "exit 0\n")
        victim = self._victim("jdk-victim")
        environment = self._environment()
        environment["jdk_staging"] = str(victim)

        result = self._run(environment)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((victim / "keep").is_file())
        self.assertEqual(
            "unrelated project\n",
            (victim / "keep").read_text(encoding="utf-8"),
        )

    def test_inherited_tools_staging_is_never_cleaned_when_only_jdk_installs(self) -> None:
        self._write_script(
            self.toolchain / "sdk/cmdline-tools/14742923/bin/sdkmanager",
            "exit 0\n",
        )
        victim = self._victim("tools-victim")
        environment = self._environment()
        environment["tools_staging"] = str(victim)

        result = self._run(environment)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue((victim / "keep").is_file())
        self.assertEqual(
            "unrelated project\n",
            (victim / "keep").read_text(encoding="utf-8"),
        )

    def test_cleanup_rejects_staging_path_outside_toolchain_root(self) -> None:
        self._write_script(
            self.toolchain / "sdk/cmdline-tools/14742923/bin/sdkmanager",
            "exit 0\n",
        )
        victim = self._victim("mktemp-victim")
        self._write_script(self.fake_bin / "mktemp", f"printf '%s\\n' '{victim}'\n")
        self._write_script(self.fake_bin / "tar", "exit 42\n")

        result = self._run(self._environment())

        self.assertNotEqual(0, result.returncode)
        self.assertIn("Refusing to clean unexpected JDK staging path", result.stderr)
        self.assertTrue((victim / "keep").is_file())
        self.assertEqual(
            "unrelated project\n",
            (victim / "keep").read_text(encoding="utf-8"),
        )


if __name__ == "__main__":
    unittest.main()
