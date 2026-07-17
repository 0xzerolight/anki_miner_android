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
        self.assertIn(
            'gradleProperty("ankiMinerS1aArm64AcceptanceApk")',
            app_build,
        )
        self.assertNotIn(
            'rootProject.file("app/build/outputs/apk/device/debug/app-device-debug.apk")',
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
        self.assertEqual(2, health.count('check_runtime_artifact.py"'))
        self.assertIn("--release-acceptance-receipt", health)
        self.assertIn("s1a_acceptance.py\" verify", health)
        emulator_tasks = health.split("tasks=(", 1)[1].split(")", 1)[0]
        self.assertNotIn("DeviceRelease", emulator_tasks)
        self.assertIn('if [[ -n "$RELEASE_ACCEPTANCE_RECEIPT" ]]', health)

    def test_health_checks_runtime_pinned_html_entities(self) -> None:
        health = (REPO_ROOT / "scripts" / "health.sh").read_text(encoding="utf-8")

        self.assertIn(
            '"$ANKI_MINER_CHAQUOPY_BUILD_PYTHON" \\\n'
            '    "$REPO_ROOT/tools/anki-contract/generate_html5_entities.py" --check',
            health,
        )

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

    def test_ci_serializes_build_and_emulators_and_runs_complete_parity(self) -> None:
        ci = (REPO_ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        nightly = (REPO_ROOT / ".github/workflows/parity-nightly.yml").read_text(
            encoding="utf-8",
        )
        emulator_runner = (REPO_ROOT / "scripts/run-emulator-tests.sh").read_text(
            encoding="utf-8",
        )

        self.assertIn("group: anki-miner-android-hardware-ci", ci)
        self.assertIn("runs-on: [self-hosted, Linux, X64, anki-miner-android]", ci)
        self.assertEqual(1, ci.count("scripts/health.sh"))
        for lane in ("api26", "4k", "16k"):
            self.assertIn(f"--lane {lane}", ci)
        self.assertIn("run_goldens_v2.py", ci)
        self.assertIn("run_reading_goldens.py", ci)
        self.assertIn(
            'Path("tools/engine-sync/engine.lock").read_text(encoding="utf-8").strip()',
            ci,
        )
        self.assertNotIn('json.loads(Path("tools/engine-sync/engine.lock")', ci)
        self.assertIn("--s2-fallback", ci)
        self.assertIn("run-s5-video-acceptance.sh", ci)
        self.assertIn("ANKI_MINER_S5_ALLOW_COLLECTION_RESET", ci)
        self.assertLess(
            ci.index("scripts/run-emulator-tests.sh --s2-fallback"),
            ci.index("scripts/run-s5-video-acceptance.sh"),
        )
        self.assertIn("S2_FALLBACK_CONNECTED_RUNNER", emulator_runner)
        self.assertIn("run_head_goldens_v2.py", nightly)
        self.assertIn('--desktop-root "$GITHUB_WORKSPACE/desktop"', nightly)
        self.assertIn("ANKI_MINER_DESKTOP_READ_TOKEN", nightly)
        self.assertIn(
            'Path("android/tools/engine-sync/engine.lock").read_text(encoding="utf-8").strip()',
            nightly,
        )
        self.assertNotIn(
            'json.loads(Path("android/tools/engine-sync/engine.lock")',
            nightly,
        )
        self.assertIn("semantic_drift", nightly)
        self.assertNotRegex(ci + nightly, r"actions/[a-z-]+@v[0-9]")

        prerelease = (
            REPO_ROOT / ".github/workflows/ankidroid-prerelease-canary.yml"
        ).read_text(encoding="utf-8")
        apk_candidate = (
            REPO_ROOT / ".github/workflows/apk-candidate.yml"
        ).read_text(encoding="utf-8")
        apk_publish = (
            REPO_ROOT / ".github/workflows/apk-publish.yml"
        ).read_text(encoding="utf-8")
        self.assertIn("group: anki-miner-android-hardware-ci", prerelease)
        self.assertIn("group: anki-miner-android-hardware-ci", apk_candidate)
        self.assertIn("environment: apk-candidate", apk_candidate)
        self.assertIn("if: github.ref == 'refs/heads/main'", apk_candidate)
        self.assertIn("git merge-base --is-ancestor HEAD", apk_candidate)
        self.assertIn("persist-credentials: false", apk_candidate)
        self.assertIn("environment: github-prerelease", apk_publish)
        self.assertIn("github.ref == 'refs/heads/main'", apk_publish)
        self.assertIn("github.event.repository.private", apk_publish)
        self.assertIn("git merge-base --is-ancestor HEAD", apk_publish)
        self.assertIn("persist-credentials: false", apk_publish)
        self.assertGreaterEqual(nightly.count("persist-credentials: false"), 2)
        self.assertIn("resolve_ankidroid_canary.py resolve", prerelease)
        self.assertIn("run-s2-ankidroid-prerelease-canary.sh", prerelease)
        self.assertIn("--receipt-ankidroid-apk", prerelease)

if __name__ == "__main__":
    unittest.main()
