from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = REPO_ROOT / ".github" / "workflows" / "pull-request.yml"
HEALTH = REPO_ROOT / "scripts" / "health.sh"
PACKAGES_LOCK = REPO_ROOT / "scripts" / "android-sdk-packages.lock"

# CI installs every lock entry itself. The emulator action must consume these
# preflight-verified revisions rather than resolving mutable package paths.
CI_OMITS_ON_PURPOSE = frozenset()


def _locked_packages() -> set[str]:
    packages = set()
    for line in PACKAGES_LOCK.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        packages.add(line.split("|", 1)[0])
    return packages


def _workflow_sdkmanager_packages() -> set[str]:
    """Package arguments of the workflow's `sdkmanager --install` invocation."""
    source = WORKFLOW.read_text(encoding="utf-8")
    command_start = source.index('sdkmanager --sdk_root="$ANDROID_HOME" --channel=0 --install')
    start = source.index("--install", command_start) + len("--install")
    # One backslash-continued shell command: consume lines until one does not
    # continue. Running off the end means the workflow is malformed.
    packages: set[str] = set()
    for line in source[start:].splitlines():
        packages.update(re.findall(r'"([^"]+)"', line))
        if not line.rstrip().endswith("\\"):
            return packages
    raise AssertionError("unterminated sdkmanager --install invocation")


class CiSdkPackagesTest(unittest.TestCase):
    def test_ci_installs_only_locked_packages(self) -> None:
        unlocked = _workflow_sdkmanager_packages() - _locked_packages()
        self.assertEqual(
            set(),
            unlocked,
            "CI installs SDK packages absent from scripts/android-sdk-packages.lock: " f"{sorted(unlocked)}",
        )

    def test_ci_omits_only_the_packages_the_emulator_action_supplies(self) -> None:
        omitted = _locked_packages() - _workflow_sdkmanager_packages()
        self.assertEqual(
            CI_OMITS_ON_PURPOSE,
            omitted,
            "the set of locked packages CI skips changed; update CI_OMITS_ON_PURPOSE "
            "only if the emulator action really does supply them",
        )

    def test_ci_preflights_and_verifies_locked_sdk_revisions(self) -> None:
        source = WORKFLOW.read_text(encoding="utf-8")

        self.assertIn('sdkmanager --sdk_root="$ANDROID_HOME" --channel=0 --list', source)
        self.assertIn("scripts/preflight_android_packages.py", source)
        self.assertIn("--sdkmanager-list", source)
        self.assertIn('sdkmanager --sdk_root="$ANDROID_HOME" --channel=0 --list_installed', source)
        self.assertIn("scripts/verify_android_toolchain.py", source)
        self.assertIn("--installed-list", source)
        self.assertIn("--lock scripts/android-sdk-packages.lock", source)
        self.assertEqual(set(), CI_OMITS_ON_PURPOSE)

    def test_health_preflights_stable_sdk_revisions(self) -> None:
        source = HEALTH.read_text(encoding="utf-8")

        self.assertIn("preflight_android_packages.py", source)
        self.assertIn("--sdkmanager-list", source)
        self.assertIn("--lock \"$SCRIPT_DIR/android-sdk-packages.lock\"", source)


if __name__ == "__main__":
    unittest.main()
