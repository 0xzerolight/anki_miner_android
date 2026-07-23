from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = REPO_ROOT / ".github" / "workflows" / "pull-request.yml"
PACKAGES_LOCK = REPO_ROOT / "scripts" / "android-sdk-packages.lock"

# CI installs a subset of the local toolchain on purpose:
#   emulator      - reactivecircus/android-emulator-runner supplies its own
#   system-images - the same action provisions the AVD image it needs
# Everything else must match the lock, or CI silently builds against a
# different NDK/build-tools than scripts/health.sh does.
CI_OMITS_ON_PURPOSE = frozenset(
    {
        "emulator",
        "system-images;android-26;google_apis;x86_64",
        "system-images;android-36;google_apis;x86_64",
        "system-images;android-36;google_apis_ps16k;x86_64",
    }
)


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
    start = source.index("sdkmanager --install")
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

    def test_lock_and_workflow_agree_on_versioned_packages(self) -> None:
        # `platforms;android-36` style entries carry their version in the name,
        # so subset equality above already pins them. Guard the one unversioned
        # entry so it cannot silently drift to "whatever is latest".
        self.assertIn("platform-tools", _workflow_sdkmanager_packages())


if __name__ == "__main__":
    unittest.main()
