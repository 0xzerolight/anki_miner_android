"""Verified, process-immutable registration of an external UniDic directory."""

from __future__ import annotations

import hashlib
import os
import re
import stat
import threading
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

from .tokenizer_contract import TokenizerContractError

UNIDIC_REQUIRED_FILES = (
    "char.bin",
    "dicrc",
    "matrix.bin",
    "mecabrc",
    "sys.dic",
    "unk.dic",
)

_RESOURCE_ID_RE = re.compile(
    r"^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,126}[A-Za-z0-9_-])?$"
)
_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
_HASH_CHUNK_SIZE = 1024 * 1024
_LOCK = threading.Lock()
_registration: RegisteredUniDic | None = None


@dataclass(frozen=True, slots=True)
class RegisteredUniDic:
    """Provenance captured before either tokenizer opens the dictionary."""

    resource_id: str
    dicdir: Path
    mecabrc: Path
    sys_dic: Path
    tree_sha256: str
    file_count: int
    total_bytes: int

    @property
    def mecab_arguments(self) -> tuple[str, str, str, str]:
        """Return explicit option elements for fugashi/S1a configuration."""

        return ("-r", os.fspath(self.mecabrc), "-d", os.fspath(self.dicdir))

    @property
    def mecab_new_argv(self) -> tuple[str, str, str, str, str, str]:
        """Return a complete ``mecab_new`` argv for the native S1b backend.

        libmecab treats element zero as the program name and starts parsing
        options at element one. ``-C`` matches fugashi's copied-node allocation
        mode; omitting either element makes the two candidate backends diverge.
        """

        return ("anki_miner", "-C", *self.mecab_arguments)


@dataclass(frozen=True, slots=True)
class _TreeIdentity:
    sha256: str
    file_count: int
    total_bytes: int


@dataclass(frozen=True, slots=True)
class _StatIdentity:
    device: int
    inode: int
    mode: int
    size: int
    modified_ns: int
    changed_ns: int

    @classmethod
    def from_stat(cls, value: os.stat_result) -> _StatIdentity:
        return cls(
            device=value.st_dev,
            inode=value.st_ino,
            mode=value.st_mode,
            size=value.st_size,
            modified_ns=value.st_mtime_ns,
            changed_ns=value.st_ctime_ns,
        )


def _invalid(code: str, detail: str) -> TokenizerContractError:
    return TokenizerContractError(code, detail)


def _canonical_root(dicdir: str | os.PathLike[str]) -> Path:
    try:
        path = Path(dicdir)
    except TypeError as exc:
        raise _invalid("invalid_unidic_path", "UniDic path must be path-like") from exc
    if not path.is_absolute():
        raise _invalid("invalid_unidic_path", "UniDic path must be absolute")
    try:
        root_stat = path.lstat()
    except OSError as exc:
        raise _invalid("invalid_unidic_path", f"Cannot inspect UniDic path: {exc}") from exc
    if stat.S_ISLNK(root_stat.st_mode) or not stat.S_ISDIR(root_stat.st_mode):
        raise _invalid(
            "invalid_unidic_path", "UniDic path must be a real directory, not a symlink"
        )
    try:
        return path.resolve(strict=True)
    except OSError as exc:
        raise _invalid("invalid_unidic_path", f"Cannot resolve UniDic path: {exc}") from exc


def _scan_tree(
    root: Path,
) -> tuple[
    list[tuple[str, Path, _StatIdentity]],
    dict[Path, _StatIdentity],
]:
    files: list[tuple[str, Path, _StatIdentity]] = []
    directories: dict[Path, _StatIdentity] = {}
    pending: list[tuple[Path, str]] = [(root, "")]

    while pending:
        directory, relative_directory = pending.pop()
        try:
            directory_stat = directory.stat(follow_symlinks=False)
            entries = sorted(os.scandir(directory), key=lambda item: item.name)
        except OSError as exc:
            raise _invalid(
                "invalid_unidic_tree", f"Cannot inspect UniDic directory: {exc}"
            ) from exc
        if not stat.S_ISDIR(directory_stat.st_mode):
            raise _invalid("invalid_unidic_tree", "UniDic directory changed during scan")
        directories[directory] = _StatIdentity.from_stat(directory_stat)

        for entry in entries:
            relative = (
                f"{relative_directory}/{entry.name}"
                if relative_directory
                else entry.name
            )
            try:
                entry_stat = entry.stat(follow_symlinks=False)
            except OSError as exc:
                raise _invalid(
                    "invalid_unidic_tree", f"Cannot inspect UniDic entry {relative}: {exc}"
                ) from exc
            mode = entry_stat.st_mode
            if stat.S_ISLNK(mode):
                raise _invalid(
                    "invalid_unidic_tree", f"UniDic tree contains a symlink: {relative}"
                )
            if stat.S_ISDIR(mode):
                pending.append((Path(entry.path), relative))
            elif stat.S_ISREG(mode):
                files.append(
                    (relative, Path(entry.path), _StatIdentity.from_stat(entry_stat))
                )
            else:
                raise _invalid(
                    "invalid_unidic_tree",
                    f"UniDic tree contains a non-file entry: {relative}",
                )

    files.sort(key=lambda item: item[0])
    return files, directories


def _tree_identity(root: Path) -> _TreeIdentity:
    files, directories = _scan_tree(root)
    top_level_files = {relative for relative, _, _ in files if "/" not in relative}
    missing = set(UNIDIC_REQUIRED_FILES) - top_level_files
    if missing:
        raise _invalid(
            "invalid_unidic_tree", f"UniDic directory is missing {sorted(missing)!r}"
        )

    digest = hashlib.sha256()
    total_bytes = 0
    for relative, path, scanned_identity in files:
        try:
            encoded_relative = relative.encode("utf-8")
        except UnicodeEncodeError as exc:
            raise _invalid(
                "invalid_unidic_tree",
                f"UniDic path is not canonical UTF-8: {relative!r}",
            ) from exc
        try:
            with path.open("rb", buffering=0) as stream:
                opened_identity = _StatIdentity.from_stat(os.fstat(stream.fileno()))
                if opened_identity != scanned_identity or not stat.S_ISREG(
                    opened_identity.mode
                ):
                    raise _invalid(
                        "unidic_tree_changed",
                        f"UniDic file changed before hashing: {relative}",
                    )

                digest.update(len(encoded_relative).to_bytes(8, "big"))
                digest.update(encoded_relative)
                digest.update(opened_identity.size.to_bytes(8, "big"))
                read_size = 0
                for chunk in iter(lambda: stream.read(_HASH_CHUNK_SIZE), b""):
                    digest.update(chunk)
                    read_size += len(chunk)
                final_identity = _StatIdentity.from_stat(os.fstat(stream.fileno()))
        except TokenizerContractError:
            raise
        except OSError as exc:
            raise _invalid(
                "invalid_unidic_tree", f"Cannot hash UniDic file {relative}: {exc}"
            ) from exc

        if read_size != opened_identity.size or final_identity != opened_identity:
            raise _invalid(
                "unidic_tree_changed", f"UniDic file changed while hashing: {relative}"
            )
        total_bytes += read_size

    for directory, scanned_identity in directories.items():
        try:
            final_identity = _StatIdentity.from_stat(
                directory.stat(follow_symlinks=False)
            )
        except OSError as exc:
            raise _invalid(
                "unidic_tree_changed", f"UniDic directory changed while hashing: {exc}"
            ) from exc
        if final_identity != scanned_identity:
            raise _invalid(
                "unidic_tree_changed", "UniDic directory changed while hashing"
            )

    return _TreeIdentity(digest.hexdigest(), len(files), total_bytes)


def calculate_unidic_tree_sha256(dicdir: str | os.PathLike[str]) -> str:
    """Calculate the canonical path/length/content hash used at registration."""

    return _tree_identity(_canonical_root(dicdir)).sha256


def validate_unidic_identity_inputs(
    resource_id: object, expected_tree_sha256: object
) -> None:
    """Validate the cheap catalog identity fields without touching process state."""

    if not isinstance(resource_id, str) or not _RESOURCE_ID_RE.fullmatch(resource_id):
        raise _invalid("invalid_unidic_identity", "Invalid UniDic resource id")
    if not isinstance(expected_tree_sha256, str) or not _SHA256_RE.fullmatch(
        expected_tree_sha256
    ):
        raise _invalid(
            "invalid_unidic_identity", "Expected UniDic tree hash must be lowercase SHA-256"
        )


def _same_request(
    registered: RegisteredUniDic,
    *,
    resource_id: str,
    dicdir: Path,
    expected_tree_sha256: str,
) -> bool:
    return (
        registered.resource_id == resource_id
        and registered.dicdir == dicdir
        and registered.tree_sha256 == expected_tree_sha256
    )


def register_unidic(
    dicdir: str | os.PathLike[str],
    *,
    resource_id: str,
    expected_tree_sha256: str,
) -> RegisteredUniDic:
    """Verify and freeze one external dictionary identity for this process.

    Hashing approximately 250 MiB is intentionally synchronous here; callers
    must invoke registration on the parked Python worker, never Android's main
    thread.  Repeating the exact request is idempotent.  Switching path,
    resource id, or hash requires a fresh process so mapped dictionary files
    can never be replaced underneath a backend.
    """

    global _registration
    validate_unidic_identity_inputs(resource_id, expected_tree_sha256)
    root = _canonical_root(dicdir)

    with _LOCK:
        existing = _registration
        if existing is not None:
            if _same_request(
                existing,
                resource_id=resource_id,
                dicdir=root,
                expected_tree_sha256=expected_tree_sha256,
            ):
                return existing
            raise _invalid(
                "unidic_already_registered",
                "A different UniDic identity is already registered in this process",
            )

    identity = _tree_identity(root)
    if identity.sha256 != expected_tree_sha256:
        raise _invalid(
            "unidic_provenance_mismatch",
            "UniDic tree hash does not match the trusted resource catalog",
        )

    candidate = RegisteredUniDic(
        resource_id=resource_id,
        dicdir=root,
        mecabrc=root / "mecabrc",
        sys_dic=root / "sys.dic",
        tree_sha256=identity.sha256,
        file_count=identity.file_count,
        total_bytes=identity.total_bytes,
    )
    with _LOCK:
        existing = _registration
        if existing is None:
            _registration = candidate
            return candidate
        if _same_request(
            existing,
            resource_id=resource_id,
            dicdir=root,
            expected_tree_sha256=expected_tree_sha256,
        ):
            return existing
        raise _invalid(
            "unidic_already_registered",
            "A different UniDic identity won concurrent registration",
        )


def require_registered_unidic() -> RegisteredUniDic:
    """Return the frozen dictionary identity or fail before backend import."""

    with _LOCK:
        if _registration is None:
            raise _invalid(
                "unidic_registration_required",
                "UniDic must be verified and registered before tokenizer creation",
            )
        return _registration


def validate_loaded_dictionary_filenames(
    filenames: Iterable[str | os.PathLike[str]],
    *,
    registration: RegisteredUniDic | None = None,
) -> None:
    """Prove a backend loaded only the registered ``sys.dic`` file."""

    if isinstance(filenames, (str, bytes, os.PathLike)):
        raise _invalid(
            "invalid_loaded_dictionary", "Dictionary filenames must be an iterable"
        )
    try:
        candidates = tuple(Path(filename) for filename in filenames)
    except (TypeError, ValueError) as exc:
        raise _invalid(
            "invalid_loaded_dictionary", "Backend returned an invalid dictionary path"
        ) from exc
    if len(candidates) != 1:
        raise _invalid(
            "invalid_loaded_dictionary", "Backend must load exactly one system dictionary"
        )

    expected = registration or require_registered_unidic()
    candidate = candidates[0]
    if not candidate.is_absolute():
        raise _invalid(
            "invalid_loaded_dictionary", "Backend returned a relative dictionary path"
        )
    try:
        resolved = candidate.resolve(strict=True)
    except OSError as exc:
        raise _invalid(
            "invalid_loaded_dictionary", f"Cannot resolve loaded dictionary: {exc}"
        ) from exc
    if resolved != expected.sys_dic:
        raise _invalid(
            "unidic_provenance_mismatch",
            "Backend loaded a dictionary outside the registered UniDic directory",
        )
