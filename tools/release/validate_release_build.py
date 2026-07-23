#!/usr/bin/env python3
"""Validate source identity and vendored wheels used by Android build variants."""

from __future__ import annotations

import argparse
import contextlib
import io
import re
import sys
from pathlib import Path

TOOL_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TOOL_ROOT.parents[1]
WHEEL_TOOL_ROOT = REPO_ROOT / "tools/wheels"
sys.path.insert(0, str(WHEEL_TOOL_ROOT))

from vendored_wheel_manifest import (  # noqa: E402
    DEFAULT_MANIFEST,
    DEFAULT_WHEELS_ROOT,
    ManifestError,
)
from vendored_wheel_manifest import (  # noqa: E402
    check as check_wheel_manifest,
)

FULL_GIT_SHA = re.compile(r"^[0-9a-f]{40}$")


class ReleaseBuildIntegrityError(RuntimeError):
    """Raised when a build variant lacks required release provenance."""


def source_commit_for(build_type: str, source_commit: str | None) -> str:
    resolved = source_commit or "development"
    if build_type == "release" and FULL_GIT_SHA.fullmatch(resolved) is None:
        raise ReleaseBuildIntegrityError(
            "Release builds require a full lowercase Git SHA via "
            "-PankiMinerSourceCommit=<sha> or ANKI_MINER_SOURCE_COMMIT.",
        )
    return resolved


def validate_build(
    build_type: str,
    source_commit: str | None,
    wheels_root: Path,
    manifest: Path,
) -> str:
    resolved = source_commit_for(build_type, source_commit)
    if build_type == "release":
        with contextlib.redirect_stdout(io.StringIO()):
            check_wheel_manifest(wheels_root, manifest)
    return resolved


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-type", choices=("debug", "release"), required=True)
    parser.add_argument("--source-commit")
    parser.add_argument("--wheels-root", type=Path, default=DEFAULT_WHEELS_ROOT)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    args = parser.parse_args(argv)
    try:
        commit = validate_build(
            args.build_type,
            args.source_commit,
            args.wheels_root,
            args.manifest,
        )
    except (ManifestError, ReleaseBuildIntegrityError) as error:
        print(f"release-build-integrity: {error}", file=sys.stderr)
        return 1
    print(commit)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
