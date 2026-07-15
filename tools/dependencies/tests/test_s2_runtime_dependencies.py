from __future__ import annotations

import json
import re
import tomllib
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
MANIFEST_PATH = REPO_ROOT / "third_party/s2-runtime-dependencies/manifest.json"
LOCK_PATH = REPO_ROOT / "app/gradle.lockfile"
VERIFICATION_PATH = REPO_ROOT / "gradle/verification-metadata.xml"
CATALOG_PATH = REPO_ROOT / "gradle/libs.versions.toml"
APP_BUILD_PATH = REPO_ROOT / "app/build.gradle.kts"
RUNTIME_CONFIGURATION = "emulatorDebugRuntimeClasspath"
XML_NAMESPACE = {"v": "https://schema.gradle.org/dependency-verification"}


def _runtime_lock_coordinates() -> set[str]:
    coordinates: set[str] = set()
    for line in LOCK_PATH.read_text(encoding="utf-8").splitlines():
        if line.startswith("empty=") or RUNTIME_CONFIGURATION not in line:
            continue
        coordinates.add(line.split("=", 1)[0])
    return coordinates


def _verification_artifacts() -> dict[str, dict[str, str]]:
    root = ET.parse(VERIFICATION_PATH).getroot()
    result: dict[str, dict[str, str]] = {}
    for component in root.findall(".//v:component", XML_NAMESPACE):
        coordinate = ":".join(
            component.attrib[key] for key in ("group", "name", "version")
        )
        artifacts: dict[str, str] = {}
        for artifact in component.findall("v:artifact", XML_NAMESPACE):
            checksums = artifact.findall("v:sha256", XML_NAMESPACE)
            if len(checksums) == 1:
                artifacts[artifact.attrib["name"]] = checksums[0].attrib["value"]
        result[coordinate] = artifacts
    return result


class S2RuntimeDependenciesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        self.components = {
            component["coordinate"]: component
            for component in self.manifest["components"]
        }

    def test_direct_catalog_pins_and_declarations_are_exact(self) -> None:
        catalog = tomllib.loads(CATALOG_PATH.read_text(encoding="utf-8"))
        self.assertEqual("1.17.0", catalog["versions"]["androidx-core"])
        self.assertEqual("2.21.5", catalog["versions"]["jackson"])
        self.assertEqual("1.11.0", catalog["versions"]["kotlinx-coroutines"])
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

        libraries = catalog["libraries"]
        forbidden_modules = {
            "androidx.core:core-ktx",
            "com.fasterxml.jackson.core:jackson-databind",
            "org.jetbrains.kotlinx:kotlinx-coroutines-android",
        }
        self.assertTrue(
            forbidden_modules.isdisjoint(
                entry["module"] for entry in libraries.values() if "module" in entry
            )
        )

        app_build = APP_BUILD_PATH.read_text(encoding="utf-8")
        for declaration in (
            "implementation(libs.androidx.core)",
            "implementation(libs.jackson.core)",
            "implementation(libs.kotlinx.coroutines.core)",
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
                "androidx.core:core:1.17.0",
                "com.fasterxml.jackson.core:jackson-core:2.21.5",
                "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0",
            },
            set(self.manifest["directCoordinates"]),
        )
        for coordinate, component in self.components.items():
            with self.subTest(coordinate=coordinate):
                self.assertTrue(component["licenses"])
                self.assertTrue(component["artifacts"])

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
                "kotlinx-coroutines-android-1.11.0.jar":
                    "c2cb206d27017c7d1bf5ff179787397543d13748dbabb0d7237e1585e0b29044",
                "kotlinx-coroutines-android-1.11.0.module":
                    "7b7d1dddf188817deaad738e92e11faa5abdf937c87120cdf036102566ad4be3",
            },
            self.components[
                "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0"
            ]["artifacts"],
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
