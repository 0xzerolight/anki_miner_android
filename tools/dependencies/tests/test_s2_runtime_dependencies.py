from __future__ import annotations

import json
import re
import tomllib
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

from tools.dependencies.generate_runtime_dependency_manifest import generate

REPO_ROOT = Path(__file__).resolve().parents[3]
MANIFEST_PATH = REPO_ROOT / "third_party/s2-runtime-dependencies/manifest.json"
LOCK_PATH = REPO_ROOT / "app/gradle.lockfile"
VERIFICATION_PATH = REPO_ROOT / "gradle/verification-metadata.xml"
CATALOG_PATH = REPO_ROOT / "gradle/libs.versions.toml"
APP_BUILD_PATH = REPO_ROOT / "app/build.gradle.kts"
RUNTIME_CONFIGURATION = "emulatorDebugRuntimeClasspath"
RELEASE_RUNTIME_CONFIGURATION = "deviceReleaseRuntimeClasspath"
XML_NAMESPACE = {"v": "https://schema.gradle.org/dependency-verification"}


def _runtime_lock_coordinates(
    configuration: str = RUNTIME_CONFIGURATION,
) -> set[str]:
    coordinates: set[str] = set()
    for line in LOCK_PATH.read_text(encoding="utf-8").splitlines():
        if line.startswith("empty=") or "=" not in line:
            continue
        coordinate, configurations = line.split("=", 1)
        if configuration in configurations.split(","):
            coordinates.add(coordinate)
    return coordinates


def _verification_artifacts() -> dict[str, dict[str, str]]:
    root = ET.parse(VERIFICATION_PATH).getroot()
    result: dict[str, dict[str, str]] = {}
    for component in root.findall(".//v:component", XML_NAMESPACE):
        coordinate = ":".join(component.attrib[key] for key in ("group", "name", "version"))
        artifacts: dict[str, str] = {}
        for artifact in component.findall("v:artifact", XML_NAMESPACE):
            checksums = artifact.findall("v:sha256", XML_NAMESPACE)
            if len(checksums) == 1:
                artifacts[artifact.attrib["name"]] = checksums[0].attrib["value"]
        result[coordinate] = artifacts
    return result


class RuntimeDependenciesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        self.components = {component["coordinate"]: component for component in self.manifest["components"]}

    def test_direct_catalog_pins_and_declarations_are_exact(self) -> None:
        catalog = tomllib.loads(CATALOG_PATH.read_text(encoding="utf-8"))
        self.assertEqual("1.18.0", catalog["versions"]["androidx-core"])
        self.assertEqual("1.13.0", catalog["versions"]["androidx-activity"])
        self.assertEqual("2.10.0", catalog["versions"]["androidx-lifecycle"])
        self.assertEqual("2026.06.00", catalog["versions"]["androidx-compose-bom"])
        self.assertEqual("1.2.1", catalog["versions"]["androidx-datastore"])
        self.assertEqual("2.9.8", catalog["versions"]["androidx-navigation"])
        self.assertEqual("2.21.5", catalog["versions"]["jackson"])
        self.assertEqual("1.11.0", catalog["versions"]["kotlinx-coroutines"])
        self.assertEqual("1.10.1", catalog["versions"]["media3"])
        self.assertEqual("1.10.1-0.13.0", catalog["versions"]["nextlib"])
        self.assertEqual(
            "androidx.core:core",
            catalog["libraries"]["androidx-core"]["module"],
        )
        self.assertEqual(
            "com.fasterxml.jackson.core:jackson-core",
            catalog["libraries"]["jackson-core"]["module"],
        )
        self.assertEqual(
            "org.jetbrains.kotlinx:kotlinx-coroutines-core",
            catalog["libraries"]["kotlinx-coroutines-core"]["module"],
        )
        self.assertEqual(
            "androidx.activity:activity-compose",
            catalog["libraries"]["androidx-activity-compose"]["module"],
        )
        self.assertEqual(
            "androidx.lifecycle:lifecycle-runtime-compose",
            catalog["libraries"]["androidx-lifecycle-runtime-compose"]["module"],
        )
        self.assertEqual(
            "androidx.lifecycle:lifecycle-viewmodel-compose",
            catalog["libraries"]["androidx-lifecycle-viewmodel-compose"]["module"],
        )
        self.assertEqual(
            "androidx.compose:compose-bom",
            catalog["libraries"]["androidx-compose-bom"]["module"],
        )
        self.assertEqual(
            "androidx.datastore:datastore-preferences",
            catalog["libraries"]["androidx-datastore-preferences"]["module"],
        )
        self.assertEqual(
            "androidx.navigation:navigation-compose",
            catalog["libraries"]["androidx-navigation-compose"]["module"],
        )
        self.assertEqual(
            "androidx.media3:media3-exoplayer",
            catalog["libraries"]["media3-exoplayer"]["module"],
        )
        self.assertEqual(
            "androidx.media3:media3-ui-compose",
            catalog["libraries"]["media3-ui-compose"]["module"],
        )
        self.assertEqual(
            "io.github.anilbeesetti:nextlib-media3ext",
            catalog["libraries"]["nextlib-media3ext"]["module"],
        )

        libraries = catalog["libraries"]
        forbidden_modules = {
            "androidx.core:core-ktx",
            "com.fasterxml.jackson.core:jackson-databind",
            "org.jetbrains.kotlinx:kotlinx-coroutines-android",
        }
        self.assertTrue(
            forbidden_modules.isdisjoint(entry["module"] for entry in libraries.values() if "module" in entry)
        )

        app_build = APP_BUILD_PATH.read_text(encoding="utf-8")
        for declaration in (
            "implementation(libs.androidx.core)",
            "implementation(libs.androidx.activity.compose)",
            "implementation(libs.androidx.lifecycle.runtime.compose)",
            "implementation(libs.androidx.lifecycle.viewmodel.compose)",
            "implementation(libs.androidx.datastore.preferences)",
            "implementation(libs.androidx.navigation.compose)",
            "implementation(composeBom)",
            "implementation(libs.androidx.compose.material3)",
            "implementation(libs.androidx.compose.ui.tooling.preview)",
            "implementation(libs.jackson.core)",
            "implementation(libs.kotlinx.coroutines.core)",
            "implementation(libs.media3.exoplayer)",
            "implementation(libs.media3.ui.compose)",
            "implementation(libs.nextlib.media3ext)",
            "debugImplementation(libs.androidx.compose.ui.tooling)",
            "debugImplementation(libs.androidx.compose.ui.test.manifest)",
        ):
            self.assertEqual(1, app_build.count(declaration), declaration)
        self.assertNotIn("kotlinx.coroutines.android", app_build)

    def test_inventory_is_the_complete_locked_runtime_closure(self) -> None:
        self.assertEqual(1, self.manifest["formatVersion"])
        self.assertEqual(RUNTIME_CONFIGURATION, self.manifest["configuration"])
        self.assertEqual(_runtime_lock_coordinates(), set(self.components))
        self.assertEqual(len(self.components), len(self.manifest["components"]))
        self.assertEqual(
            {
                "androidx.activity:activity-compose:1.13.0",
                "androidx.compose.material3:material3:1.4.0",
                "androidx.compose.ui:ui-test-manifest:1.11.3",
                "androidx.compose.ui:ui-tooling-preview:1.11.3",
                "androidx.compose.ui:ui-tooling:1.11.3",
                "androidx.compose:compose-bom:2026.06.00",
                "androidx.core:core:1.18.0",
                "androidx.datastore:datastore-preferences:1.2.1",
                "androidx.lifecycle:lifecycle-runtime-compose:2.10.0",
                "androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0",
                "androidx.media3:media3-exoplayer:1.10.1",
                "androidx.media3:media3-ui-compose:1.10.1",
                "androidx.navigation:navigation-compose:2.9.8",
                "com.fasterxml.jackson.core:jackson-core:2.21.5",
                "io.github.anilbeesetti:nextlib-media3ext:1.10.1-0.13.0",
                "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0",
            },
            set(self.manifest["directCoordinates"]),
        )
        for coordinate, component in self.components.items():
            with self.subTest(coordinate=coordinate):
                self.assertTrue(component["licenses"])
                self.assertTrue(component["artifacts"])

    def test_inventory_is_deterministically_generated_from_locked_evidence(self) -> None:
        self.assertEqual(generate(), self.manifest)

    def test_release_runtime_is_debug_inventory_without_debug_tooling(self) -> None:
        debug = _runtime_lock_coordinates()
        release = _runtime_lock_coordinates(RELEASE_RUNTIME_CONFIGURATION)
        self.assertEqual(
            {
                "androidx.compose.ui:ui-test-manifest:1.11.3",
                "androidx.compose.ui:ui-tooling-android:1.11.3",
                "androidx.compose.ui:ui-tooling-data-android:1.11.3",
                "androidx.compose.ui:ui-tooling-data:1.11.3",
                "androidx.compose.ui:ui-tooling:1.11.3",
            },
            debug - release,
        )
        self.assertEqual(set(), release - debug)

    def test_inventory_hashes_equal_strict_gradle_verification(self) -> None:
        verification = ET.parse(VERIFICATION_PATH).getroot()
        configuration = verification.find("v:configuration", XML_NAMESPACE)
        self.assertIsNotNone(configuration)
        assert configuration is not None
        self.assertEqual(
            "true",
            configuration.findtext("v:verify-metadata", namespaces=XML_NAMESPACE),
        )
        self.assertIsNone(configuration.find("v:trusted-artifacts", XML_NAMESPACE))
        self.assertIsNone(configuration.find("v:ignored-keys", XML_NAMESPACE))

        verified = _verification_artifacts()
        sha256_pattern = re.compile(r"[0-9a-f]{64}")
        for coordinate, component in self.components.items():
            with self.subTest(coordinate=coordinate):
                self.assertEqual(component["artifacts"], verified[coordinate])
                for artifact, checksum in component["artifacts"].items():
                    self.assertIn(
                        Path(artifact).suffix,
                        {".aar", ".jar", ".module", ".pom"},
                    )
                    self.assertIsNotNone(sha256_pattern.fullmatch(checksum))

    def test_android_dispatcher_is_transitive_and_pinned_with_core(self) -> None:
        self.assertNotIn(
            "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0",
            self.manifest["directCoordinates"],
        )
        self.assertEqual(
            {
                "kotlinx-coroutines-android-1.11.0.jar": "c2cb206d27017c7d1bf5ff179787397543d13748dbabb0d7237e1585e0b29044",
                "kotlinx-coroutines-android-1.11.0.module": "7b7d1dddf188817deaad738e92e11faa5abdf937c87120cdf036102566ad4be3",
            },
            self.components["org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0"]["artifacts"],
        )
        self.assertIn(
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0",
            self.components,
        )
        self.assertIn(
            "org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.11.0",
            self.components,
        )


if __name__ == "__main__":
    unittest.main()
