"""Relock the host pip pins that have a patched release for a known vulnerability.

Run from the repository root by .github/workflows/security-relock.yml. Each lock is
audited with pip-audit against OSV (the GitHub advisories Dependabot alerts on). A
vulnerable pin moves to the lowest release that fixes all of its advisories: the
requirements-*.in pin is rewritten when the package is pinned there, otherwise the
lock takes a constraint, and the lock is recompiled with the flags that reproduce it
byte for byte. Prints one markdown line per moved pin; no output means no change.

The host-test and runtime locks pin one hash per package, the file their Linux x86_64
lane installs, while uv emits the hash of every wheel and the sdist; a recompiled
entry with more than one hash is cut back to that file's.
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
import tempfile
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from packaging.tags import compatible_tags, cpython_tags, platform_tags
from packaging.utils import canonicalize_name, parse_wheel_filename
from packaging.version import Version


@dataclass(frozen=True)
class Lock:
    name: str
    python: str
    compile_args: tuple[str, ...]
    one_hash: bool = False
    # The runtime lane's pins that also ship in app/wheels must match the device
    # (runtime_dependencies_probe.py), and moving a device wheel is the full
    # runtime-wheel rebuild, so those stay for a hand bump.
    skip_shipped: bool = False


LOCKS = (
    Lock("requirements-lint", "3.13", ("--only-binary", ":all:")),
    Lock("requirements-host-test", "3.13", ("--only-binary", ":all:"), one_hash=True),
    # pymorphy3's Russian dictionaries are language data on Android, so the lock omits them.
    Lock(
        "requirements-runtime-host-test",
        "3.12",
        ("--no-emit-package", "pymorphy3-dicts-ru"),
        one_hash=True,
        skip_shipped=True,
    ),
)

ENTRY = re.compile(r"^([A-Za-z0-9][A-Za-z0-9._-]*)==(\S+)((?: \\\n    --hash=sha256:[0-9a-f]{64})+)$", re.MULTILINE)


def shipped_packages() -> set[str]:
    return {canonicalize_name(wheel.name.split("-")[0]) for wheel in Path("app/wheels").glob("*/*.whl")}


def audit(lock: Path) -> list[dict]:
    # pip-audit exits 1 when it finds anything; a real failure leaves no JSON and json.loads raises.
    result = subprocess.run(
        ["pip-audit", "-s", "osv", "--disable-pip", "-r", str(lock), "-f", "json"],
        capture_output=True,
        text=True,
    )
    return json.loads(result.stdout)["dependencies"]


def fixed_version(vulns: list[dict]) -> Version | None:
    """The lowest release that fixes every advisory, or None if one has no fix."""
    floors = []
    for vuln in vulns:
        if not vuln["fix_versions"]:
            return None
        floors.append(min(Version(v) for v in vuln["fix_versions"]))
    return max(floors)


def installed_file_hash(name: str, version: str, python: str) -> str:
    """The sha256 of the file pip installs for name==version on this CPython and platform."""
    with urllib.request.urlopen(f"https://pypi.org/pypi/{name}/{version}/json") as response:
        files = json.load(response)["urls"]
    major, minor = (int(part) for part in python.split("."))
    platforms = list(platform_tags())
    supported = [
        *cpython_tags((major, minor), platforms=platforms),
        *compatible_tags((major, minor), f"cp{major}{minor}", platforms),
    ]
    rank = {tag: index for index, tag in enumerate(supported)}
    wheels = []
    for file in files:
        if file["packagetype"] == "bdist_wheel":
            ranks = [rank[tag] for tag in parse_wheel_filename(file["filename"])[3] if tag in rank]
            if ranks:
                wheels.append((min(ranks), file["digests"]["sha256"]))
    if wheels:
        return min(wheels)[1]
    if any(file["packagetype"] == "bdist_wheel" for file in files):
        # The lanes install with --only-binary, so an sdist hash would fail them.
        raise SystemExit(f"{name}=={version} has no wheel for CPython {python} on this platform")
    # Published as an sdist alone, like kiwipiepy-model.
    (sdist,) = (file["digests"]["sha256"] for file in files if file["packagetype"] == "sdist")
    return sdist


def keep_one_hash(body: str, python: str) -> str:
    def trim(entry: re.Match[str]) -> str:
        name, version, hashes = entry.groups()
        if hashes.count("--hash") == 1:
            return entry.group(0)
        return f"{name}=={version} \\\n    --hash=sha256:{installed_file_hash(name, version, python)}"

    return ENTRY.sub(trim, body)


def relock(lock: Lock, moves: dict[str, Version]) -> None:
    in_path = Path(f"{lock.name}.in")
    lock_path = Path(f"{lock.name}.lock")
    constraints = []
    in_text = in_path.read_text()
    for name, version in moves.items():
        pin = re.compile(rf"^{re.escape(name).replace('-', '[-_.]')}==\S+$", re.IGNORECASE | re.MULTILINE)
        in_text, count = pin.subn(f"{name}=={version}", in_text)
        if not count:
            constraints.append(f"{name}=={version}\n")
    in_path.write_text(in_text)

    lock_text = lock_path.read_text()
    first_pin = re.search(r"^[A-Za-z0-9]", lock_text, re.MULTILINE)
    header = lock_text[: first_pin.start()] if first_pin else ""
    with tempfile.TemporaryDirectory() as tmp:
        # Seeded with the current lock, so every pin not being moved stays where it is.
        output = Path(tmp, "out.txt")
        output.write_text(lock_text)
        constraint_file = Path(tmp, "constraints.txt")
        constraint_file.write_text("".join(constraints))
        upgrades = [arg for name in moves for arg in ("--upgrade-package", name)]
        subprocess.run(
            [
                "uv", "pip", "compile", str(in_path), "--generate-hashes", "--no-header", "--no-annotate",
                "--quiet", "--python-version", lock.python, *lock.compile_args, *upgrades,
                "-c", str(constraint_file), "-o", str(output),
            ],
            check=True,
        )  # fmt: skip
        # uv ends the file with a comment naming the --no-emit-package exclusions.
        body = output.read_text().split("\n\n", 1)[0].rstrip("\n") + "\n"
    if lock.one_hash:
        body = keep_one_hash(body, lock.python)
    lock_path.write_text(header + body)


def main() -> int:
    shipped = shipped_packages()
    for lock in LOCKS:
        moves: dict[str, Version] = {}
        lines = []
        for dep in audit(Path(f"{lock.name}.lock")):
            if not dep.get("vulns"):
                continue
            name = canonicalize_name(dep["name"])
            ids = ", ".join(dict.fromkeys(vuln["id"] for vuln in dep["vulns"]))
            target = fixed_version(dep["vulns"])
            if target is None or (lock.skip_shipped and name in shipped):
                print(f"skipped {name} {dep['version']} in {lock.name}.lock: {ids}", file=sys.stderr)
                continue
            moves[name] = target
            lines.append(f"- {name} {dep['version']} → {target} in `{lock.name}.lock`: {ids}")
        if moves:
            relock(lock, moves)
            print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main())
