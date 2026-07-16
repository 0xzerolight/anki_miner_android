from __future__ import annotations

from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


REPO_ROOT = Path(__file__).resolve().parents[2]
VERIFICATION_METADATA = REPO_ROOT / "gradle" / "verification-metadata.xml"
NAMESPACE = {"dv": "https://schema.gradle.org/dependency-verification"}


class GradleReproducibilityTest(unittest.TestCase):
    def test_every_verified_artifact_has_sha256(self) -> None:
        root = ET.parse(VERIFICATION_METADATA).getroot()
        artifacts = root.findall(".//dv:artifact", NAMESPACE)
        self.assertGreater(len(artifacts), 100)
        for artifact in artifacts:
            checksums = artifact.findall("dv:sha256", NAMESPACE)
            self.assertGreaterEqual(len(checksums), 1, artifact.attrib.get("name"))
            for checksum in checksums:
                value = checksum.attrib.get("value", "")
                self.assertEqual(64, len(value), artifact.attrib.get("name"))
                int(value, 16)

    def test_plugin_artifacts_are_verified_without_trust_exceptions(self) -> None:
        root = ET.parse(VERIFICATION_METADATA).getroot()
        components = {
            (item.attrib["group"], item.attrib["name"], item.attrib["version"])
            for item in root.findall(".//dv:component", NAMESPACE)
        }
        self.assertIn(("com.android.tools.build", "gradle", "8.13.2"), components)
        self.assertIn(("com.chaquo.python", "gradle", "17.0.0"), components)
        self.assertIn(("com.chaquo.python", "target", "3.12.12-0"), components)
        self.assertNotIn(("com.chaquo.python", "target", "3.13.9-0"), components)
        self.assertIn(
            ("com.chaquo.python.runtime", "bootstrap", "17.0.0"),
            components,
        )
        self.assertIn(
            ("org.jetbrains.kotlin", "kotlin-gradle-plugin", "2.2.21"),
            components,
        )
        artifact_names = {
            artifact.attrib["name"]
            for artifact in root.findall(".//dv:artifact", NAMESPACE)
        }
        self.assertIn("bootstrap-17.0.0-3.12.imy", artifact_names)
        self.assertIn("chaquopy-17.0.0-3.12-x86_64.so", artifact_names)
        self.assertIn("chaquopy-17.0.0-3.12-arm64-v8a.so", artifact_names)
        obsolete_prefixes = (
            "target-3.13",
            "bootstrap-17.0.0-3.13",
            "chaquopy-17.0.0-3.13",
            "libchaquopy_java-17.0.0-3.13",
        )
        self.assertFalse(
            any(name.startswith(obsolete_prefixes) for name in artifact_names),
        )
        self.assertIsNone(root.find(".//dv:trusted-artifacts", NAMESPACE))

    def test_embedded_and_build_python_pins_are_separate(self) -> None:
        catalog = (REPO_ROOT / "gradle" / "libs.versions.toml").read_text(
            encoding="utf-8",
        )
        app_build = (REPO_ROOT / "app" / "build.gradle.kts").read_text(
            encoding="utf-8",
        )
        android_env = (REPO_ROOT / "scripts" / "android-env.sh").read_text(
            encoding="utf-8",
        )
        self.assertIn('python = "3.12"', catalog)
        self.assertIn('val pythonTargetVersion = "3.12.12-0"', app_build)
        self.assertIn("buildPython(chaquopyBuildPython)", app_build)
        self.assertIn('"python3.13"', app_build)
        self.assertIn("verify_chaquopy_build_python.py", app_build)
        self.assertIn(
            'ANKI_MINER_CHAQUOPY_BUILD_PYTHON="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/',
            android_env,
        )
        self.assertNotIn("chaquopy-build-python/bin:$PATH", android_env)

    def test_runtime_wheels_are_mandatory_verified_and_selected_by_flavor(self) -> None:
        app_build = (REPO_ROOT / "app" / "build.gradle.kts").read_text(
            encoding="utf-8",
        )

        self.assertIn('gradleProperty("ankiMinerRuntimeManifest")', app_build)
        self.assertIn(
            'file("tools/runtime-wheels/out/current/manifest.json")',
            app_build,
        )
        self.assertIn('"verify-publication"', app_build)
        self.assertIn('"groups"', app_build)
        self.assertIn('filesFor("common", 6)', app_build)
        self.assertIn('filesFor(abi, 7)', app_build)
        emulator_block, device_and_rest = app_build.split(
            'getByName("emulator") {', 1
        )[1].split('getByName("device") {', 1)
        device_block = device_and_rest.split("\n        }\n    }\n}", 1)[0]
        for block, abi, opposite_abi in (
            (emulator_block, "x86_64", "arm64-v8a"),
            (device_block, "arm64-v8a", "x86_64"),
        ):
            self.assertEqual(1, block.count("pip {"))
            self.assertIn("runtimeWheels.common", block)
            self.assertIn(f'runtimeWheels.byAbi.getValue("{abi}")', block)
            self.assertIn(f's1aWheels?.byAbi?.getValue("{abi}")', block)
            self.assertNotIn(f'getValue("{opposite_abi}")', block)
            self.assertEqual(1, block.count('options("--no-index")'))
            self.assertEqual(1, block.count('options("--no-deps")'))
        self.assertEqual(2, app_build.count("pip {"))
        self.assertEqual(2, app_build.count('options("--no-index")'))
        self.assertEqual(2, app_build.count('options("--no-deps")'))
        self.assertIn('"RUNTIME_WHEEL_BUILD_KEY"', app_build)

    def test_production_tokenizer_and_release_acceptance_fail_closed(self) -> None:
        app_build = (REPO_ROOT / "app" / "build.gradle.kts").read_text(
            encoding="utf-8",
        )
        debug_factory = (
            REPO_ROOT
            / "app/src/debug/kotlin/com/ankiminer/android/mining/MiningRepositoryFactory.kt"
        ).read_text(encoding="utf-8")
        release_factory = (
            REPO_ROOT
            / "app/src/release/kotlin/com/ankiminer/android/mining/MiningRepositoryFactory.kt"
        ).read_text(encoding="utf-8")

        self.assertIn('gradleProperty("ankiMinerS1aManifest")', app_build)
        self.assertIn(
            'gradleProperty("ankiMinerS1aArm64AcceptanceReceipt")',
            app_build,
        )
        self.assertIn('file("tools/wheels/s1a_acceptance.py")', app_build)
        self.assertIn(
            '(s1aArm64Accepted && runtimeAbi == "device" && variant.buildType == "release")',
            app_build,
        )
        self.assertIn(
            '(runtimeAbi == "emulator" && variant.buildType == "debug")',
            app_build,
        )
        self.assertIn("BuildConfig.S1A_PUBLICATION_VERIFIED", debug_factory)
        self.assertIn("FakeMiningRepository()", debug_factory)
        self.assertIn("BuildConfig.S1A_PUBLICATION_VERIFIED", release_factory)
        self.assertIn("BuildConfig.S1A_ARM64_ACCEPTED", release_factory)
        self.assertIn("BuildConfig.S1A_PUBLICATION_BUILD_KEY", release_factory)

    def test_setup_builds_runtime_wheels_but_health_only_verifies_them(self) -> None:
        provision = (REPO_ROOT / "scripts" / "provision-android.sh").read_text(
            encoding="utf-8",
        )
        health = (REPO_ROOT / "scripts" / "health.sh").read_text(
            encoding="utf-8",
        )

        toolchain_gate = provision.index('"$SCRIPT_DIR/verify-android-toolchain.sh"')
        runtime_build = provision.index(
            '"$CHECKOUT_ROOT/tools/runtime-wheels/build-runtime-wheels.sh"',
        )
        self.assertLess(toolchain_gate, runtime_build)
        self.assertIn("runtime_wheels.py\" verify-publication", health)
        self.assertIn("ORG_GRADLE_PROJECT_ankiMinerRuntimeManifest", health)
        self.assertNotIn(
            '"$REPO_ROOT/tools/runtime-wheels/build-runtime-wheels.sh"', health
        )
        self.assertLess(
            health.index('"$SCRIPT_DIR/verify-android-toolchain.sh"'),
            health.index('runtime_wheels.py" verify-publication'),
        )
        self.assertEqual(3, health.count('check_runtime_artifact.py"'))

    def test_complete_lockfiles_are_committed(self) -> None:
        app_lock = (REPO_ROOT / "app" / "gradle.lockfile").read_text(encoding="utf-8")
        settings_lock = (REPO_ROOT / "settings-gradle.lockfile").read_text(
            encoding="utf-8",
        )
        self.assertIn(
            "org.jetbrains.kotlin:kotlin-stdlib:2.2.21=",
            app_lock,
        )
        self.assertIn("junit:junit:4.13.2=", app_lock)
        self.assertIn("androidx.test:runner:1.7.0=", app_lock)
        self.assertIn("empty=incomingCatalogForLibs0", settings_lock)

    def test_locking_is_enabled_for_every_project_configuration(self) -> None:
        root_build = (REPO_ROOT / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn("allprojects", root_build)
        self.assertIn("lockAllConfigurations()", root_build)

    def test_local_gradle_defaults_bound_resource_use(self) -> None:
        properties = (REPO_ROOT / "gradle.properties").read_text(encoding="utf-8")

        self.assertIn("org.gradle.jvmargs=-Xmx2g", properties)
        self.assertIn("org.gradle.daemon=false", properties)
        self.assertIn("org.gradle.parallel=false", properties)
        self.assertIn("org.gradle.workers.max=1", properties)

    def test_host_and_connected_android_phases_cannot_overlap(self) -> None:
        health = (REPO_ROOT / "scripts" / "health.sh").read_text(encoding="utf-8")
        connected = (
            REPO_ROOT / "scripts" / "run-connected-emulator-tests.sh"
        ).read_text(encoding="utf-8")
        runner = (REPO_ROOT / "scripts" / "run-emulator-tests.sh").read_text(
            encoding="utf-8",
        )

        self.assertIn("anki_miner_run_gradle ./gradlew", health)
        self.assertNotIn("--connected", health)
        self.assertNotIn("connectedEmulatorDebugAndroidTest", health)
        self.assertNotIn("gradlew", connected)
        self.assertNotIn("gradlew", runner)
        self.assertIn("android_test_receipt.py", health)
        self.assertIn("android_test_receipt.py", connected)

    def test_every_repository_gradle_script_uses_the_shared_resource_wrapper(self) -> None:
        paths = (
            REPO_ROOT / "scripts" / "health.sh",
            REPO_ROOT / "scripts" / "run-s1a-arm64-tests.sh",
            REPO_ROOT / "scripts" / "run-s1b-arm64-tests.sh",
            REPO_ROOT / "tools" / "tokenizer" / "build-s1b-android.sh",
        )
        for path in paths:
            with self.subTest(path=path):
                source = path.read_text(encoding="utf-8")
                self.assertIn("anki_miner_run_gradle", source)
                self.assertNotIn('\n"$GRADLEW_COMMAND" \\\n', source)

if __name__ == "__main__":
    unittest.main()
