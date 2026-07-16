#!/usr/bin/env python3
"""Generate the committed Android runtime dependency inventory.

The dependency lock proves the selected component closure, while Gradle's
strict verification metadata proves the artifact bytes.  Keeping generation
as a deterministic repository tool prevents the human-readable inventory from
silently drifting when the Compose closure changes.
"""

from __future__ import annotations

import argparse
import json
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
LOCK_PATH = REPO_ROOT / "app/gradle.lockfile"
VERIFICATION_PATH = REPO_ROOT / "gradle/verification-metadata.xml"
MANIFEST_PATH = REPO_ROOT / "third_party/s2-runtime-dependencies/manifest.json"
RUNTIME_CONFIGURATION = "emulatorDebugRuntimeClasspath"
XML_NAMESPACE = {"v": "https://schema.gradle.org/dependency-verification"}

# These are the declarations which intentionally participate in the debug
# runtime inventory.  The BOMs are included because they constrain the runtime
# versions even though they contain no executable code.
DIRECT_COORDINATES = (
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
    "androidx.navigation:navigation-compose:2.9.8",
    "com.fasterxml.jackson.core:jackson-core:2.21.5",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0",
)


class InventoryError(RuntimeError):
    """The locked runtime closure cannot produce a trustworthy inventory."""


def _locked_coordinates() -> tuple[str, ...]:
    coordinates: set[str] = set()
    for line in LOCK_PATH.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#") or line.startswith("empty="):
            continue
        coordinate, separator, raw_configurations = line.partition("=")
        if not separator:
            raise InventoryError(f"Malformed dependency-lock line: {line!r}")
        configurations = raw_configurations.split(",")
        if RUNTIME_CONFIGURATION in configurations:
            coordinates.add(coordinate)
    if not coordinates:
        raise InventoryError(f"No components locked for {RUNTIME_CONFIGURATION}")
    return tuple(sorted(coordinates))


def _verified_artifacts() -> dict[str, dict[str, str]]:
    root = ET.parse(VERIFICATION_PATH).getroot()
    components: dict[str, dict[str, str]] = {}
    for component in root.findall(".//v:component", XML_NAMESPACE):
        coordinate = ":".join(
            component.attrib[key] for key in ("group", "name", "version")
        )
        artifacts: dict[str, str] = {}
        for artifact in component.findall("v:artifact", XML_NAMESPACE):
            checksums = artifact.findall("v:sha256", XML_NAMESPACE)
            if len(checksums) != 1:
                raise InventoryError(
                    f"Expected one SHA-256 for {coordinate}/{artifact.attrib['name']}"
                )
            artifacts[artifact.attrib["name"]] = checksums[0].attrib["value"]
        if coordinate in components:
            raise InventoryError(f"Duplicate verification component: {coordinate}")
        components[coordinate] = dict(sorted(artifacts.items()))
    return components


def _licenses(coordinate: str) -> list[str]:
    group = coordinate.split(":", 1)[0]
    if coordinate.startswith("com.fasterxml.jackson.core:jackson-core:"):
        # jackson-core's packaged NOTICE identifies the small bundled works
        # covered by these additional licenses.
        return ["Apache-2.0", "MIT", "BSL-1.0", "BSD-2-Clause"]
    if group in {
        "androidx.activity",
        "androidx.annotation",
        "androidx.arch.core",
        "androidx.autofill",
        "androidx.collection",
        "androidx.compose",
        "androidx.compose.animation",
        "androidx.compose.foundation",
        "androidx.compose.material",
        "androidx.compose.material3",
        "androidx.compose.runtime",
        "androidx.compose.ui",
        "androidx.concurrent",
        "androidx.core",
        "androidx.customview",
        "androidx.datastore",
        "androidx.documentfile",
        "androidx.dynamicanimation",
        "androidx.emoji2",
        "androidx.graphics",
        "androidx.interpolator",
        "androidx.legacy",
        "androidx.lifecycle",
        "androidx.loader",
        "androidx.localbroadcastmanager",
        "androidx.navigationevent",
        "androidx.navigation",
        "androidx.print",
        "androidx.profileinstaller",
        "androidx.savedstate",
        "androidx.startup",
        "androidx.tracing",
        "androidx.transition",
        "androidx.versionedparcelable",
        "androidx.window",
        "com.fasterxml.jackson",
        "com.google.guava",
        "com.squareup.okio",
        "org.jetbrains",
        "org.jetbrains.kotlin",
        "org.jetbrains.kotlinx",
        "org.jspecify",
    }:
        return ["Apache-2.0"]
    raise InventoryError(f"License classification is missing for {coordinate}")


def generate() -> dict[str, Any]:
    locked = _locked_coordinates()
    verified = _verified_artifacts()
    missing_direct = set(DIRECT_COORDINATES).difference(locked)
    if missing_direct:
        raise InventoryError(
            f"Direct runtime components are not locked: {sorted(missing_direct)!r}"
        )

    components: list[dict[str, Any]] = []
    for coordinate in locked:
        artifacts = verified.get(coordinate)
        if not artifacts:
            raise InventoryError(
                f"Locked runtime component has no verified artifacts: {coordinate}"
            )
        components.append(
            {
                "coordinate": coordinate,
                "licenses": _licenses(coordinate),
                "artifacts": artifacts,
            }
        )
    return {
        "configuration": RUNTIME_CONFIGURATION,
        "directCoordinates": list(DIRECT_COORDINATES),
        "formatVersion": 1,
        "components": components,
    }


def _render(document: dict[str, Any]) -> str:
    return json.dumps(document, ensure_ascii=False, indent=2) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--write",
        action="store_true",
        help="replace the committed manifest instead of checking it",
    )
    args = parser.parse_args()
    expected = _render(generate())
    if args.write:
        MANIFEST_PATH.write_text(expected, encoding="utf-8")
        return 0
    actual = MANIFEST_PATH.read_text(encoding="utf-8")
    if actual != expected:
        raise InventoryError(
            "Runtime dependency manifest is stale; rerun with --write"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
