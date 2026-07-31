#!/usr/bin/env python3
"""Validate source identity and vendored wheels used by Android build variants."""

from __future__ import annotations

import argparse
import contextlib
import io
import re
import subprocess
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


def _git(source_root: Path, *args: str) -> str:
    try:
        result = subprocess.run(
            [
                "git",
                "--no-optional-locks",
                "--no-replace-objects",
                "-C",
                str(source_root),
                *args,
            ],
            check=False,
            capture_output=True,
            text=True,
        )
    except OSError as exc:
        raise ReleaseBuildIntegrityError(f"Cannot execute Git to verify release source: {exc}") from exc
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip() or "Git command failed"
        raise ReleaseBuildIntegrityError(f"Cannot verify release source with Git: {detail}")
    return result.stdout.strip()


def _validate_source_identity(source_root: Path, source_commit: str) -> None:
    source_root = source_root.resolve()
    try:
        resolved_commit = _git(
            source_root,
            "rev-parse",
            "--verify",
            f"{source_commit}^{{commit}}",
        )
    except ReleaseBuildIntegrityError as exc:
        raise ReleaseBuildIntegrityError(f"Release source commit does not identify a commit: {source_commit}") from exc
    if resolved_commit != source_commit:
        raise ReleaseBuildIntegrityError(
            f"Release source commit does not name the commit object exactly: {source_commit}"
        )

    checkout_root = Path(_git(source_root, "rev-parse", "--show-toplevel")).resolve()
    if checkout_root != source_root:
        raise ReleaseBuildIntegrityError(f"Release source root must name the checkout root exactly: {checkout_root}")

    head_commit = _git(source_root, "rev-parse", "--verify", "HEAD^{commit}")
    if source_commit != head_commit:
        raise ReleaseBuildIntegrityError(
            "Release source commit does not equal checkout HEAD: " f"expected {head_commit}, received {source_commit}"
        )

    index_entries = _git(source_root, "ls-files", "-v", "-z")
    hidden_paths: list[str] = []
    for entry in index_entries.split("\0"):
        if not entry:
            continue
        if len(entry) < 3 or entry[1] != " ":
            raise ReleaseBuildIntegrityError("Cannot parse Git index flags while verifying release source.")
        if entry[0] != "H":
            hidden_paths.append(entry[2:])
    if hidden_paths:
        raise ReleaseBuildIntegrityError(
            "Git index hides tracked source paths from cleanliness checks: " + ", ".join(hidden_paths)
        )

    status = _git(
        source_root,
        "status",
        "--porcelain=v1",
        "--untracked-files=all",
        "--ignore-submodules=none",
    )
    if status:
        raise ReleaseBuildIntegrityError(
            "Release source checkout is dirty; commit or remove tracked and " "untracked changes before building."
        )


def validate_build(
    build_type: str,
    source_commit: str | None,
    wheels_root: Path,
    manifest: Path,
    source_root: Path = REPO_ROOT,
) -> str:
    resolved = source_commit_for(build_type, source_commit)
    if build_type == "release":
        _validate_source_identity(source_root, resolved)
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
