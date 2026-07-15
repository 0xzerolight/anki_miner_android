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
        self.assertIn(("com.chaquo.python", "target", "3.13.9-0"), components)
        self.assertIn(
            ("com.chaquo.python.runtime", "bootstrap", "17.0.0"),
            components,
        )
        self.assertIn(
            ("org.jetbrains.kotlin", "kotlin-gradle-plugin", "2.2.21"),
            components,
        )
        self.assertIsNone(root.find(".//dv:trusted-artifacts", NAMESPACE))

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


if __name__ == "__main__":
    unittest.main()
