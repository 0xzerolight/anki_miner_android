"""Materialize the reviewed Android v2 exporter from pinned desktop sources."""

from __future__ import annotations

import hashlib
import os
from pathlib import Path
import stat

from .core import EngineSyncError


class GoldenExporterOverlayError(EngineSyncError):
    """The desktop exporter does not match the reviewed overlay base."""


SOURCE_ATTESTATIONS = {
    "dump_engine_goldens.py": (
        "b873ab6517f61443c8cb0669817c54501c68f7733343c95f80681ba5fd9a0762",
        "720e0133dc011c8174f417d30d7739b4c64830c7",
    ),
    "engine_golden_contract_v2.py": (
        "ef51d7f63f7c5828d41f36c0b04ed9562222bf95634a2fc0308ea505099be379",
        "796c49e7613a694a1ab7682b5536f28e426fea7d",
    ),
    "prepare_golden_unidic.py": (
        "fd51f42ff6ee9210f239f68bd8cf1b3aec87deda930af7ec17e673200e34eee2",
        "ef4085b24deb5a1c2e0349d34d07e4e915d83b2b",
    ),
}
SCHEMA_ATTESTATION = (
    "05e611d5e2c10168a8dfd93d318fd007c67d2eecd7d67adbe72d1de49ee52115",
    "ef944789c28f206c1480cae5f6e8cd983f1776dd",
)
MATERIALIZED_SHA256 = {
    "dump_engine_goldens.py": SOURCE_ATTESTATIONS["dump_engine_goldens.py"][0],
    "engine_golden_contract_v2.py": (
        "a6fba4c51ffc3fafdab14f47bd0b227d7f08fa4f716dc8ef779fd1381d6c9d86"
    ),
    "prepare_golden_unidic.py": SOURCE_ATTESTATIONS["prepare_golden_unidic.py"][0],
}
DESKTOP_REVISION_LINE = (
    b'PINNED_ENGINE_REVISION = "ba3b3cfbcc53e57a440c8b9f157209851408c62a"'
)
ANDROID_REVISION_LINE = (
    b'PINNED_ENGINE_REVISION = "edad8e503ded5b33e56a33822693b239a057b88d"'
)


def _git_blob_sha1(content: bytes) -> str:
    header = f"blob {len(content)}\0".encode("ascii")
    return hashlib.sha1(header + content, usedforsecurity=False).hexdigest()


def _read_attested(path: Path, *, sha256: str, git_blob: str) -> bytes:
    try:
        value = path.lstat()
        content = path.read_bytes()
    except OSError as exc:
        raise GoldenExporterOverlayError(f"cannot read exporter source {path}: {exc}") from exc
    if stat.S_ISLNK(value.st_mode) or not stat.S_ISREG(value.st_mode):
        raise GoldenExporterOverlayError(f"exporter source must be a regular file: {path}")
    if hashlib.sha256(content).hexdigest() != sha256 or _git_blob_sha1(content) != git_blob:
        raise GoldenExporterOverlayError(
            f"desktop exporter source changed since review: {path.name}"
        )
    return content


def materialize_golden_exporter(exporter: Path, output_dir: Path) -> Path:
    """Copy the attested exporter trio and apply the one reviewed revision patch."""

    if exporter.name != "dump_engine_goldens.py":
        raise GoldenExporterOverlayError(
            "v2 exporter must be named dump_engine_goldens.py"
        )
    output_dir.mkdir(parents=True, exist_ok=False)
    for name, (sha256, git_blob) in SOURCE_ATTESTATIONS.items():
        content = _read_attested(exporter.parent / name, sha256=sha256, git_blob=git_blob)
        if name == "engine_golden_contract_v2.py":
            if content.count(DESKTOP_REVISION_LINE) != 1:
                raise GoldenExporterOverlayError(
                    "desktop v2 exporter revision seam changed since review"
                )
            content = content.replace(DESKTOP_REVISION_LINE, ANDROID_REVISION_LINE)
        actual = hashlib.sha256(content).hexdigest()
        if actual != MATERIALIZED_SHA256[name]:
            raise GoldenExporterOverlayError(
                f"materialized exporter hash mismatch: {name}"
            )
        destination = output_dir / name
        destination.write_bytes(content)
        os.chmod(destination, 0o644)
    schema_source = (
        exporter.parent.parent / "tests/fixtures/goldens/engine-v2.schema.json"
    )
    schema = _read_attested(
        schema_source,
        sha256=SCHEMA_ATTESTATION[0],
        git_blob=SCHEMA_ATTESTATION[1],
    )
    schema_destination = (
        output_dir.parent / "tests/fixtures/goldens/engine-v2.schema.json"
    )
    schema_destination.parent.mkdir(parents=True)
    schema_destination.write_bytes(schema)
    os.chmod(schema_destination, 0o644)
    return output_dir / "dump_engine_goldens.py"
