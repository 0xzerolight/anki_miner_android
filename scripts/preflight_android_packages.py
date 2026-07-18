#!/usr/bin/env python3
"""Fail before sdkmanager mutates packages when remote revisions drift."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from verify_android_toolchain import VerificationError, read_lock


def add_unique(table: dict[str, str], path: str, version: str, source: Path) -> None:
    previous = table.get(path)
    if previous is not None and previous != version:
        raise VerificationError(
            f"{source}: conflicting revisions for {path}: {previous!r} and {version!r}",
        )
    table[path] = version


def read_sdkmanager_list(
    path: Path,
) -> tuple[dict[str, str], dict[str, str], dict[str, tuple[str, str]]]:
    installed: dict[str, str] = {}
    available: dict[str, str] = {}
    updates: dict[str, tuple[str, str]] = {}
    section = ""
    headings = {
        "Installed packages:": "installed",
        "Available Packages:": "available",
        "Available Updates:": "updates",
    }
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if line in headings:
            section = headings[line]
            continue
        if not section or "|" not in line:
            continue
        fields = [field.strip() for field in line.split("|")]
        if fields[0] in {"Path", "ID", ""} or set(fields[0]) == {"-"}:
            continue
        if section == "updates":
            if len(fields) < 3:
                raise VerificationError(f"{path}: malformed available-update row: {line}")
            previous = updates.get(fields[0])
            revision_pair = (fields[1], fields[2])
            if previous is not None and previous != revision_pair:
                raise VerificationError(f"{path}: conflicting updates for {fields[0]}")
            updates[fields[0]] = revision_pair
        else:
            if len(fields) < 2:
                raise VerificationError(f"{path}: malformed package row: {line}")
            target = installed if section == "installed" else available
            add_unique(target, fields[0], fields[1], path)
    return installed, available, updates


def preflight(lock: Path, sdkmanager_list: Path) -> None:
    installed, available, updates = read_sdkmanager_list(sdkmanager_list)
    for package_path, expected_revision, _ in read_lock(lock):
        update = updates.get(package_path)
        installed_revision = installed.get(package_path)
        available_revision = available.get(package_path)

        if available_revision not in {None, expected_revision}:
            raise VerificationError(
                f"{package_path}: stable-channel revision {available_revision!r}, "
                f"expected locked revision {expected_revision!r}",
            )
        if update is not None and installed_revision is not None and update[0] != installed_revision:
            raise VerificationError(
                f"{package_path}: sdkmanager update row starts at {update[0]!r}, "
                f"but installed revision is {installed_revision!r}",
            )
        if update is not None:
            candidate_revision = update[1]
        elif installed_revision is not None:
            candidate_revision = installed_revision
        else:
            candidate_revision = available_revision

        if candidate_revision is None:
            raise VerificationError(
                f"{package_path}: package is absent from sdkmanager's stable-channel list",
            )
        if candidate_revision != expected_revision:
            raise VerificationError(
                f"{package_path}: stable-channel revision {candidate_revision!r}, "
                f"expected locked revision {expected_revision!r}",
            )
        if installed_revision not in {None, expected_revision} and update is None:
            raise VerificationError(
                f"{package_path}: installed revision {installed_revision!r} cannot be "
                f"converged to {expected_revision!r}",
            )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--sdkmanager-list", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        preflight(args.lock, args.sdkmanager_list)
    except (OSError, VerificationError, ValueError) as error:
        print(f"Android SDK package preflight failed: {error}", file=sys.stderr)
        return 1
    print("Android SDK stable-channel revisions match the package lock")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
