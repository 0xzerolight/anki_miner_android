from __future__ import annotations

from pathlib import Path
import re
import unittest

REPO_ROOT = Path(__file__).resolve().parents[2]


class RuntimeHostLaneTests(unittest.TestCase):
    def test_lock_is_exact_cpython_312_runtime_closure(self) -> None:
        lock = (REPO_ROOT / "requirements-runtime-host-test.lock").read_text(
            encoding="utf-8"
        )
        records = re.findall(
            r"^([A-Za-z0-9_-]+)==([^ ]+) \\\n" r"    --hash=sha256:([0-9a-f]+)$",
            lock,
            flags=re.MULTILINE,
        )
        self.assertEqual(19, len(records))
        self.assertTrue(all(len(sha256) == 64 for _, _, sha256 in records))
        versions = {name.lower(): version for name, version, _ in records}
        direct = {
            name.lower(): version
            for name, version in re.findall(
                r"^([A-Za-z0-9_-]+)==([^\s]+)$",
                (REPO_ROOT / "requirements-runtime-host-test.in").read_text(
                    encoding="utf-8"
                ),
                flags=re.MULTILINE,
            )
        }
        self.assertEqual(direct, {name: versions[name] for name in direct})
        self.assertEqual(
            {
                "certifi": "2026.6.17",
                "charset-normalizer": "3.4.7",
                "idna": "3.18",
                "lxml": "6.1.1",
                "pillow": "12.2.0",
                "pysubs2": "1.8.1",
                "requests": "2.34.2",
                "urllib3": "2.7.0",
            },
            {
                name: versions[name]
                for name in (
                    "certifi",
                    "charset-normalizer",
                    "idna",
                    "lxml",
                    "pillow",
                    "pysubs2",
                    "requests",
                    "urllib3",
                )
            },
        )
        self.assertNotIn("fugashi", versions)
        self.assertNotIn("unidic-lite", versions)

    def test_provisioning_and_health_keep_the_lane_separate(self) -> None:
        provision = (REPO_ROOT / "scripts/provision-runtime-host-tests.sh").read_text(
            encoding="utf-8"
        )
        health = (REPO_ROOT / "scripts/health.sh").read_text(encoding="utf-8")
        environment = (REPO_ROOT / "scripts/android-env.sh").read_text(encoding="utf-8")

        self.assertIn(
            'RUNTIME_VENV="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/runtime-host-tests"',
            provision,
        )
        self.assertIn('"$ANKI_MINER_CHAQUOPY_BUILD_PYTHON" -m venv', provision)
        self.assertIn("--only-binary=:all:", provision)
        self.assertIn("--require-hashes", provision)
        self.assertIn("runtime-host-tests/bin/python", health)
        self.assertIn("check-python-runtime.py", health)
        self.assertNotIn("runtime-host-tests/bin", environment)

    def test_runtime_provisioning_uses_safe_lock_order(self) -> None:
        runtime_provision = (
            REPO_ROOT / "scripts/provision-runtime-host-tests.sh"
        ).read_text(encoding="utf-8")
        build_provision = (
            REPO_ROOT / "scripts/provision-chaquopy-build-python.sh"
        ).read_text(encoding="utf-8")

        provision_build = runtime_provision.index(
            '"$SCRIPT_DIR/provision-chaquopy-build-python.sh"'
        )
        shared_build_lock = runtime_provision.index(
            'flock --shared "$build_python_lock_fd"'
        )
        runtime_lock = runtime_provision.index('flock --exclusive "$runtime_lock_fd"')
        runtime_swap = runtime_provision.index('mv "$staging" "$RUNTIME_VENV"')
        self.assertLess(provision_build, shared_build_lock)
        self.assertLess(shared_build_lock, runtime_lock)
        self.assertLess(runtime_lock, runtime_swap)

        exclusive_build_lock = build_provision.index(
            'flock --exclusive "$build_python_lock_fd"'
        )
        first_verification = build_provision.index(
            'python3.13 "$VERIFIER" --lock "$LOCK" verify'
        )
        build_swap = build_provision.index('mv "$staging" "$install_root"')
        self.assertLess(exclusive_build_lock, first_verification)
        self.assertLess(first_verification, build_swap)

    def test_current_runtime_environment_reuses_full_identity_probe(self) -> None:
        provision = (REPO_ROOT / "scripts/provision-runtime-host-tests.sh").read_text(
            encoding="utf-8"
        )
        self.assertGreaterEqual(provision.count("verify_runtime_environment"), 3)
        self.assertIn('pip check || return 1', provision)
        self.assertIn("if verify_runtime_environment", provision)
        self.assertIn("failed verification; rebuilding it", provision)


if __name__ == "__main__":
    unittest.main()
