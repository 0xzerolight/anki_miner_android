#!/usr/bin/env python3
"""Verify pinned Android SDK package revisions and AVD definitions."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


class VerificationError(RuntimeError):
    pass


def read_lock(path: Path) -> list[tuple[str, str, Path]]:
    packages: list[tuple[str, str, Path]] = []
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        fields = line.split("|")
        if len(fields) != 3 or not all(fields):
            raise VerificationError(f"{path}:{line_number}: malformed package lock entry")
        packages.append((fields[0], fields[1], Path(fields[2])))
    if not packages:
        raise VerificationError(f"{path}: package lock is empty")
    return packages


def child(element: ET.Element, local_name: str) -> ET.Element | None:
    return next((item for item in element if item.tag.rsplit("}", 1)[-1] == local_name), None)


def package_xml_revision(package_xml: Path, expected_path: str) -> str:
    if not package_xml.is_file():
        raise VerificationError(f"missing package metadata: {package_xml}")
    root = ET.parse(package_xml).getroot()
    local_packages = [
        element
        for element in root.iter()
        if element.tag.rsplit("}", 1)[-1] == "localPackage"
    ]
    if len(local_packages) != 1:
        raise VerificationError(
            f"{package_xml}: expected one localPackage element, found {len(local_packages)}",
        )
    local_package = local_packages[0]
    if local_package.attrib.get("path") != expected_path:
        raise VerificationError(
            f"{package_xml}: path is {local_package.attrib.get('path')!r}, "
            f"expected {expected_path!r}",
        )
    revision = child(local_package, "revision")
    if revision is None:
        raise VerificationError(f"{package_xml}: revision is missing")
    components: list[str] = []
    for name in ("major", "minor", "micro", "preview"):
        value = child(revision, name)
        if value is not None:
            components.append(str(int(value.text or "0")))
    if not components:
        raise VerificationError(f"{package_xml}: revision has no components")
    return ".".join(components)


def read_installed_list(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise VerificationError(f"missing sdkmanager installed-package list: {path}")
    installed: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        fields = [field.strip() for field in line.split("|")]
        if len(fields) < 2 or fields[0] in {"Path", ""} or set(fields[0]) == {"-"}:
            continue
        installed[fields[0]] = fields[1]
    return installed


def read_properties(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise VerificationError(f"missing AVD configuration: {path}")
    properties: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        if not raw_line or raw_line.lstrip().startswith("#") or "=" not in raw_line:
            continue
        key, value = raw_line.split("=", 1)
        properties[key.strip()] = value.strip()
    return properties


def verify_avd(avd_home: Path, specification: str) -> None:
    fields = specification.split("|")
    if len(fields) != 2:
        raise VerificationError(f"malformed AVD specification: {specification!r}")
    name, image_package = fields
    image_fields = image_package.split(";")
    if len(image_fields) != 4 or image_fields[0] != "system-images":
        raise VerificationError(f"malformed AVD image package: {image_package!r}")
    _, platform, image_tag, abi = image_fields
    if image_tag == "google_apis_ps16k":
        primary_tag = "page_size_16kb"
        required_tags = {"page_size_16kb", "google_apis"}
    else:
        primary_tag = image_tag
        required_tags = None

    avd_dir = avd_home / f"{name}.avd"
    root_config = read_properties(avd_home / f"{name}.ini")
    config = read_properties(avd_dir / "config.ini")
    expected_image_dir = image_package.replace(";", "/")
    actual_image_dir = config.get("image.sysdir.1", "").rstrip("/")

    expected_values = {
        "AvdId": name,
        "abi.type": abi,
        "image.sysdir.1": expected_image_dir,
        "tag.id": primary_tag,
    }
    actual_values = {
        "AvdId": config.get("AvdId"),
        "abi.type": config.get("abi.type"),
        "image.sysdir.1": actual_image_dir,
        "tag.id": config.get("tag.id"),
    }
    for key, expected in expected_values.items():
        if actual_values[key] != expected:
            raise VerificationError(
                f"{avd_dir}/config.ini: {key} is {actual_values[key]!r}, expected {expected!r}",
            )

    if required_tags is not None:
        actual_tags = {
            tag.strip()
            for tag in config.get("tag.ids", "").split(",")
            if tag.strip()
        }
        if actual_tags != required_tags:
            raise VerificationError(
                f"{avd_dir}/config.ini: tag.ids is {sorted(actual_tags)!r}, "
                f"expected {sorted(required_tags)!r}",
            )

    if root_config.get("target") != platform:
        raise VerificationError(
            f"{avd_home}/{name}.ini: target is {root_config.get('target')!r}, expected {platform!r}",
        )
    configured_path = root_config.get("path")
    if configured_path is None or Path(configured_path).resolve() != avd_dir.resolve():
        raise VerificationError(
            f"{avd_home}/{name}.ini: path does not resolve to {avd_dir}",
        )


def verify(args: argparse.Namespace) -> None:
    packages = read_lock(args.lock)
    installed = read_installed_list(args.installed_list)
    for package_path, expected_revision, relative_xml in packages:
        xml_revision = package_xml_revision(args.sdk_root / relative_xml, package_path)
        if xml_revision != expected_revision:
            raise VerificationError(
                f"{package_path}: package.xml revision {xml_revision!r}, "
                f"expected {expected_revision!r}",
            )
        listed_revision = installed.get(package_path)
        if listed_revision != expected_revision:
            raise VerificationError(
                f"{package_path}: sdkmanager revision {listed_revision!r}, "
                f"expected {expected_revision!r}",
            )

    for avd in args.avd:
        verify_avd(args.avd_home, avd)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk-root", type=Path, required=True)
    parser.add_argument("--installed-list", type=Path, required=True)
    parser.add_argument("--avd-home", type=Path, required=True)
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--avd", action="append", default=[])
    return parser.parse_args()


def main() -> int:
    try:
        verify(parse_args())
    except (OSError, ET.ParseError, VerificationError, ValueError) as error:
        print(f"toolchain verification failed: {error}", file=sys.stderr)
        return 1
    print("Android SDK packages and AVD definitions match the lock")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
