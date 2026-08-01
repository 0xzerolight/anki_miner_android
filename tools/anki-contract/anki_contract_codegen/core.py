"""Strictly generate Kotlin constants from the v1 Anki limits manifest."""

from __future__ import annotations

import json
import os
import secrets
import stat
from contextlib import contextmanager, suppress
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterator, NoReturn

LIMITS_MANIFEST_PATH = PurePosixPath("app/src/main/python/android_bridge/anki_limits_v1.json")
GENERATED_KOTLIN_PATH = PurePosixPath("app/src/main/kotlin/com/ankiminer/android/anki/generated/AnkiLimitsV1.kt")
MAX_MANIFEST_BYTES = 128 * 1024
MAX_GENERATED_KOTLIN_BYTES = 512 * 1024
MAX_KOTLIN_INT = 2_147_483_647


class ContractError(RuntimeError):
    """Raised when the limits contract or generated Kotlin cannot be trusted."""


class FieldSpec:
    """Marker base class for the frozen v1 manifest shape."""


@dataclass(frozen=True)
class IntegerSpec(FieldSpec):
    json_name: str
    kotlin_name: str
    exact_value: int | None = None


@dataclass(frozen=True)
class StringSpec(FieldSpec):
    json_name: str
    exact_value: str


@dataclass(frozen=True)
class ObjectSpec(FieldSpec):
    json_name: str
    kotlin_name: str | None
    fields: tuple[FieldSpec, ...]


@dataclass(frozen=True)
class GeneratedConstant:
    source_path: tuple[str, ...]
    kotlin_path: tuple[str, ...]
    value: int


def _integer(
    json_name: str,
    kotlin_name: str,
    *,
    exact_value: int | None = None,
) -> IntegerSpec:
    return IntegerSpec(json_name, kotlin_name, exact_value)


def _object(
    json_name: str,
    kotlin_name: str | None,
    *fields: FieldSpec,
) -> ObjectSpec:
    return ObjectSpec(json_name, kotlin_name, fields)


_MANIFEST_FIELDS: tuple[FieldSpec, ...] = (
    _integer("schemaVersion", "SCHEMA_VERSION", exact_value=1),
    _object(
        "units",
        None,
        StringSpec(
            "codePoints",
            "Unicode scalar values counted by JSON Schema maxLength",
        ),
        StringSpec("items", "array entries"),
        StringSpec(
            "utf8Bytes",
            "bytes after strict UTF-8 encoding of the decoded string or complete JSON envelope",
        ),
    ),
    _object(
        "wire",
        "Wire",
        _integer("numericTokenMaxChars", "NUMERIC_TOKEN_MAX_CHARS"),
    ),
    _object(
        "names",
        "Names",
        _object(
            "deck",
            "Deck",
            _integer("maxCodePoints", "MAX_CODE_POINTS"),
            _integer("maxUtf8Bytes", "MAX_UTF8_BYTES"),
        ),
        _object(
            "model",
            "Model",
            _integer("maxCodePoints", "MAX_CODE_POINTS"),
            _integer("maxUtf8Bytes", "MAX_UTF8_BYTES"),
        ),
        _object(
            "field",
            "Field",
            _integer("maxCodePoints", "MAX_CODE_POINTS"),
            _integer("maxUtf8Bytes", "MAX_UTF8_BYTES"),
        ),
        _object(
            "targetFields",
            "TargetFields",
            _integer("maxItems", "MAX_ITEM_COUNT"),
            _integer("maxTotalUtf8Bytes", "MAX_TOTAL_UTF8_BYTES"),
        ),
        _object(
            "excludedDecks",
            "ExcludedDecks",
            _integer("maxItems", "MAX_ITEM_COUNT"),
            _integer("maxTotalUtf8Bytes", "MAX_TOTAL_UTF8_BYTES"),
        ),
    ),
    _object(
        "targetModel",
        "TargetModel",
        _integer("allowedType", "ALLOWED_TYPE_CODE"),
        _integer("maxTemplates", "MAX_TEMPLATE_COUNT"),
        _integer("cssMaxUtf8Bytes", "CSS_MAX_UTF8_BYTES"),
        _integer("latexPreMaxUtf8Bytes", "LATEX_PRE_MAX_UTF8_BYTES"),
        _integer("latexPostMaxUtf8Bytes", "LATEX_POST_MAX_UTF8_BYTES"),
        _integer(
            "templateQuestionFormatMaxUtf8Bytes",
            "TEMPLATE_QUESTION_FORMAT_MAX_UTF8_BYTES",
        ),
        _integer(
            "templateAnswerFormatMaxUtf8Bytes",
            "TEMPLATE_ANSWER_FORMAT_MAX_UTF8_BYTES",
        ),
        _integer(
            "templateBrowserQuestionFormatMaxUtf8Bytes",
            "TEMPLATE_BROWSER_QUESTION_FORMAT_MAX_UTF8_BYTES",
        ),
        _integer(
            "templateBrowserAnswerFormatMaxUtf8Bytes",
            "TEMPLATE_BROWSER_ANSWER_FORMAT_MAX_UTF8_BYTES",
        ),
        _integer(
            "providerTextTotalMaxUtf8Bytes",
            "PROVIDER_TEXT_TOTAL_MAX_UTF8_BYTES",
        ),
    ),
    _object(
        "verifyTarget",
        "VerifyTarget",
        _integer("requestEnvelopeMaxUtf8Bytes", "REQUEST_ENVELOPE_MAX_UTF8_BYTES"),
        _integer("resultEnvelopeMaxUtf8Bytes", "RESULT_ENVELOPE_MAX_UTF8_BYTES"),
    ),
    _object(
        "scanFirstFields",
        "ScanFirstFields",
        _integer("requestEnvelopeMaxUtf8Bytes", "REQUEST_ENVELOPE_MAX_UTF8_BYTES"),
        _integer("resultEnvelopeMaxUtf8Bytes", "RESULT_ENVELOPE_MAX_UTF8_BYTES"),
        _integer("duplicateKeyMaxCodePoints", "DUPLICATE_KEY_MAX_CODE_POINTS"),
        _integer(
            "duplicateFirstFieldMaxCodePoints",
            "DUPLICATE_FIRST_FIELD_MAX_CODE_POINTS",
        ),
        _integer(
            "duplicateCandidatesMaxItems",
            "DUPLICATE_CANDIDATE_MAX_ITEM_COUNT",
        ),
        _integer(
            "duplicateHitsPerCandidateMaxItems",
            "DUPLICATE_HIT_PER_CANDIDATE_MAX_ITEM_COUNT",
        ),
        _integer("duplicateHitsTotalMaxItems", "DUPLICATE_HIT_TOTAL_MAX_ITEM_COUNT"),
        _integer("firstFieldMaxCodePoints", "FIRST_FIELD_MAX_CODE_POINTS"),
        _integer("firstFieldMaxUtf8Bytes", "FIRST_FIELD_MAX_UTF8_BYTES"),
        _integer(
            "duplicateHitsTotalMaxUtf8Bytes",
            "DUPLICATE_HIT_TOTAL_MAX_UTF8_BYTES",
        ),
        _integer("knownPageMaxItems", "KNOWN_PAGE_MAX_ITEM_COUNT"),
        _integer("knownPageMaxUtf8Bytes", "KNOWN_PAGE_MAX_UTF8_BYTES"),
        _integer(
            "knownTotalScannedNotes",
            "KNOWN_TOTAL_SCANNED_NOTE_MAX_COUNT",
        ),
        _integer(
            "knownTotalScannedExcludedRows",
            "KNOWN_TOTAL_SCANNED_EXCLUDED_ROW_MAX_COUNT",
        ),
        _integer("knownCursorMaxCodePoints", "KNOWN_CURSOR_MAX_CODE_POINTS"),
        _integer("knownCursorMaxUtf8Bytes", "KNOWN_CURSOR_MAX_UTF8_BYTES"),
    ),
    _object(
        "storeMedia",
        "StoreMedia",
        _integer("requestEnvelopeMaxUtf8Bytes", "REQUEST_ENVELOPE_MAX_UTF8_BYTES"),
        _integer("resultEnvelopeMaxUtf8Bytes", "RESULT_ENVELOPE_MAX_UTF8_BYTES"),
        _integer("maxAssets", "MAX_ASSET_COUNT"),
        _integer("maxAssetBytes", "MAX_ASSET_BYTES"),
        _integer("maxTotalBytes", "MAX_TOTAL_BYTES"),
        _integer("filenameMaxCodePoints", "FILENAME_MAX_CODE_POINTS"),
        _integer("filenameMaxUtf8Bytes", "FILENAME_MAX_UTF8_BYTES"),
        _integer("sourcePathMaxCodePoints", "SOURCE_PATH_MAX_CODE_POINTS"),
        _integer("sourcePathMaxUtf8Bytes", "SOURCE_PATH_MAX_UTF8_BYTES"),
    ),
    _object(
        "createNotes",
        "CreateNotes",
        _integer("requestEnvelopeMaxUtf8Bytes", "REQUEST_ENVELOPE_MAX_UTF8_BYTES"),
        _integer("resultEnvelopeMaxUtf8Bytes", "RESULT_ENVELOPE_MAX_UTF8_BYTES"),
        _integer("maxNotes", "MAX_NOTE_COUNT"),
        _integer("maxFieldsPerNote", "MAX_FIELD_COUNT_PER_NOTE"),
        _integer("maxCardsPerNote", "MAX_CARD_COUNT_PER_NOTE"),
        _integer("fieldNameMaxUtf8Bytes", "FIELD_NAME_MAX_UTF8_BYTES"),
        _integer("fieldValueMaxCodePoints", "FIELD_VALUE_MAX_CODE_POINTS"),
        _integer("fieldValueMaxUtf8Bytes", "FIELD_VALUE_MAX_UTF8_BYTES"),
        _integer("maxTagsPerNote", "MAX_TAG_COUNT_PER_NOTE"),
        _integer("tagMaxCodePoints", "TAG_MAX_CODE_POINTS"),
        _integer("tagMaxUtf8Bytes", "TAG_MAX_UTF8_BYTES"),
        _integer("tagsPerNoteMaxUtf8Bytes", "TAGS_PER_NOTE_MAX_UTF8_BYTES"),
        _integer("noteContentMaxUtf8Bytes", "NOTE_CONTENT_MAX_UTF8_BYTES"),
        _integer("callbackContentMaxUtf8Bytes", "CALLBACK_CONTENT_MAX_UTF8_BYTES"),
        _integer(
            "maxMediaBindingsPerNote",
            "MAX_MEDIA_BINDING_COUNT_PER_NOTE",
        ),
        _integer(
            "maxMediaBindingsTotal",
            "MAX_MEDIA_BINDING_TOTAL_COUNT",
        ),
    ),
    _object(
        "releaseRunState",
        "ReleaseRunState",
        _integer("requestEnvelopeMaxUtf8Bytes", "REQUEST_ENVELOPE_MAX_UTF8_BYTES"),
        _integer("resultEnvelopeMaxUtf8Bytes", "RESULT_ENVELOPE_MAX_UTF8_BYTES"),
    ),
    _object(
        "createCall",
        "CreateCall",
        _integer("maxSourceItems", "MAX_SOURCE_ITEM_COUNT"),
        _integer("sourceMaxUtf8Bytes", "SOURCE_MAX_UTF8_BYTES"),
        _integer("builtNotesMaxUtf8Bytes", "BUILT_NOTES_MAX_UTF8_BYTES"),
        _integer("maxMediaReferences", "MAX_MEDIA_REFERENCE_COUNT"),
        _integer("mediaWorkMaxBytes", "MEDIA_WORK_MAX_BYTES"),
    ),
)


def _fail(message: str) -> NoReturn:
    raise ContractError(message)


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            _fail(f"limits manifest contains duplicate key: {key}")
        result[key] = value
    return result


def _describe_path(path: tuple[str, ...]) -> str:
    return ".".join(path) if path else "manifest"


def _validate_fields(
    value: Any,
    fields: tuple[FieldSpec, ...],
    path: tuple[str, ...] = (),
) -> None:
    if type(value) is not dict:
        _fail(f"{_describe_path(path)} must be an object")
    expected_keys = {field.json_name for field in fields}
    actual_keys = set(value)
    if actual_keys != expected_keys:
        missing = sorted(expected_keys - actual_keys)
        unknown = sorted(actual_keys - expected_keys)
        details = []
        if missing:
            details.append(f"missing {missing}")
        if unknown:
            details.append(f"unknown {unknown}")
        _fail(f"{_describe_path(path)} has invalid keys: {', '.join(details)}")

    for field in fields:
        field_path = (*path, field.json_name)
        field_value = value[field.json_name]
        if isinstance(field, IntegerSpec):
            if type(field_value) is not int:
                _fail(f"{_describe_path(field_path)} must be an integer; " "booleans are not integers")
            if not 0 <= field_value <= MAX_KOTLIN_INT:
                _fail(f"{_describe_path(field_path)} must fit a non-negative Kotlin Int")
            if field.exact_value is not None and field_value != field.exact_value:
                _fail(f"{_describe_path(field_path)} must equal {field.exact_value}")
        elif isinstance(field, StringSpec):
            if type(field_value) is not str:
                _fail(f"{_describe_path(field_path)} must be a string")
            if field_value != field.exact_value:
                _fail(f"{_describe_path(field_path)} does not match the frozen v1 meaning")
        elif isinstance(field, ObjectSpec):
            _validate_fields(field_value, field.fields, field_path)
        else:  # pragma: no cover - the frozen local specification owns this branch
            raise AssertionError(f"unsupported field specification: {field!r}")


def _relative_parts(relative_path: PurePosixPath, description: str) -> tuple[str, ...]:
    if relative_path.is_absolute() or not relative_path.parts:
        _fail(f"{description} must be a non-empty repository-relative path")
    if any(part in {"", ".", ".."} for part in relative_path.parts):
        _fail(f"{description} escapes the repository: {relative_path}")
    return relative_path.parts


def _open_flags(*, directory: bool = False, nonblocking: bool = False) -> int:
    flags = os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW
    if directory:
        flags |= os.O_DIRECTORY
    if nonblocking:
        flags |= os.O_NONBLOCK
    return flags


@contextmanager
def _open_repo(repo_root: Path) -> Iterator[int]:
    try:
        resolved_root = repo_root.resolve()
        descriptor = os.open(resolved_root, _open_flags(directory=True))
    except (OSError, RuntimeError) as exc:
        _fail(f"repository root is not a safe directory: {repo_root}: {exc}")
    try:
        yield descriptor
    finally:
        os.close(descriptor)


def _path_entry_kind(parent_fd: int, name: str) -> int | None:
    try:
        return os.stat(name, dir_fd=parent_fd, follow_symlinks=False).st_mode
    except FileNotFoundError:
        return None


def _open_child_directory(
    parent_fd: int,
    name: str,
    *,
    create: bool,
    description: str,
) -> int:
    while True:
        try:
            return os.open(name, _open_flags(directory=True), dir_fd=parent_fd)
        except FileNotFoundError:
            if not create:
                _fail(f"{description} is missing a directory: {name}")
            try:
                os.mkdir(name, mode=0o755, dir_fd=parent_fd)
            except FileExistsError:
                pass
            except OSError as exc:
                _fail(f"cannot create {description} directory {name}: {exc}")
        except OSError as exc:
            mode = _path_entry_kind(parent_fd, name)
            if mode is not None and stat.S_ISLNK(mode):
                _fail(f"{description} traverses a symlink: {name}")
            _fail(f"{description} traverses a non-directory entry {name}: {exc}")


def _open_parent_directory(
    repo_fd: int,
    relative_path: PurePosixPath,
    *,
    create: bool,
    description: str,
) -> tuple[int, tuple[str, ...], str]:
    parts = _relative_parts(relative_path, description)
    current_fd = os.dup(repo_fd)
    try:
        for part in parts[:-1]:
            child_fd = _open_child_directory(
                current_fd,
                part,
                create=create,
                description=description,
            )
            os.close(current_fd)
            current_fd = child_fd
        return current_fd, parts[:-1], parts[-1]
    except BaseException:
        os.close(current_fd)
        raise


def _verify_parent_binding(
    repo_fd: int,
    parent_parts: tuple[str, ...],
    stable_parent_fd: int,
    description: str,
) -> None:
    current_fd = os.dup(repo_fd)
    try:
        for part in parent_parts:
            child_fd = _open_child_directory(
                current_fd,
                part,
                create=False,
                description=description,
            )
            os.close(current_fd)
            current_fd = child_fd
        stable_stat = os.fstat(stable_parent_fd)
        current_stat = os.fstat(current_fd)
        if (stable_stat.st_dev, stable_stat.st_ino) != (
            current_stat.st_dev,
            current_stat.st_ino,
        ):
            _fail(f"{description} changed during the operation")
    finally:
        os.close(current_fd)


def _read_regular_file(
    repo_fd: int,
    relative_path: PurePosixPath,
    *,
    description: str,
    max_bytes: int,
) -> bytes:
    parent_fd, parent_parts, leaf = _open_parent_directory(
        repo_fd,
        relative_path,
        create=False,
        description=description,
    )
    try:
        mode = _path_entry_kind(parent_fd, leaf)
        if mode is None or not stat.S_ISREG(mode):
            kind = "symlink" if mode is not None and stat.S_ISLNK(mode) else "non-regular file"
            _fail(f"{description} is missing or is a {kind}: {relative_path}")
        try:
            # The entry can be swapped after the lstat-style check above. Opening
            # non-blocking prevents a replacement FIFO or device from hanging a
            # health check before fstat rejects it as non-regular.
            descriptor = os.open(
                leaf,
                _open_flags(nonblocking=True),
                dir_fd=parent_fd,
            )
        except OSError as exc:
            _fail(f"cannot safely open {description} {relative_path}: {exc}")
        try:
            opened_stat = os.fstat(descriptor)
            if not stat.S_ISREG(opened_stat.st_mode):
                _fail(f"{description} is not a regular file: {relative_path}")
            with os.fdopen(descriptor, "rb", closefd=False) as stream:
                data = stream.read(max_bytes + 1)
                finished_stat = os.fstat(stream.fileno())
        finally:
            os.close(descriptor)
        if len(data) > max_bytes:
            _fail(f"{description} exceeds {max_bytes} bytes")
        opened_state = (
            opened_stat.st_dev,
            opened_stat.st_ino,
            opened_stat.st_size,
            opened_stat.st_mtime_ns,
            opened_stat.st_ctime_ns,
        )
        finished_state = (
            finished_stat.st_dev,
            finished_stat.st_ino,
            finished_stat.st_size,
            finished_stat.st_mtime_ns,
            finished_stat.st_ctime_ns,
        )
        if opened_state != finished_state:
            _fail(f"{description} changed while it was being read")

        current_stat = os.stat(leaf, dir_fd=parent_fd, follow_symlinks=False)
        current_state = (
            current_stat.st_dev,
            current_stat.st_ino,
            current_stat.st_size,
            current_stat.st_mtime_ns,
            current_stat.st_ctime_ns,
        )
        if not stat.S_ISREG(current_stat.st_mode) or finished_state != current_state:
            _fail(f"{description} changed during the operation")
        _verify_parent_binding(repo_fd, parent_parts, parent_fd, description)
        return data
    except FileNotFoundError:
        _fail(f"{description} changed during the operation")
    finally:
        os.close(parent_fd)


def _decode_manifest(raw: bytes) -> dict[str, Any]:
    try:
        decoded = json.loads(raw.decode("utf-8"), object_pairs_hook=_strict_object)
    except ContractError:
        raise
    except UnicodeDecodeError as exc:
        _fail(f"limits manifest is not UTF-8: {exc}")
    except json.JSONDecodeError as exc:
        _fail(f"limits manifest is not valid JSON: {exc}")
    _validate_fields(decoded, _MANIFEST_FIELDS)
    return decoded


def _load_manifest_from_fd(
    repo_fd: int,
    manifest_path: PurePosixPath,
) -> dict[str, Any]:
    raw = _read_regular_file(
        repo_fd,
        manifest_path,
        description="limits manifest",
        max_bytes=MAX_MANIFEST_BYTES,
    )
    return _decode_manifest(raw)


def load_manifest(
    repo_root: Path,
    manifest_path: PurePosixPath = LIMITS_MANIFEST_PATH,
) -> dict[str, Any]:
    """Load and strictly validate the complete frozen v1 manifest shape."""

    with _open_repo(repo_root) as repo_fd:
        return _load_manifest_from_fd(repo_fd, manifest_path)


def _walk_constants(
    value: dict[str, Any],
    fields: tuple[FieldSpec, ...],
    source_path: tuple[str, ...],
    kotlin_path: tuple[str, ...],
) -> Iterator[GeneratedConstant]:
    for field in fields:
        child_source_path = (*source_path, field.json_name)
        if isinstance(field, IntegerSpec):
            yield GeneratedConstant(
                source_path=child_source_path,
                kotlin_path=(*kotlin_path, field.kotlin_name),
                value=value[field.json_name],
            )
        elif isinstance(field, ObjectSpec):
            child_kotlin_path = kotlin_path
            if field.kotlin_name is not None:
                child_kotlin_path = (*kotlin_path, field.kotlin_name)
            yield from _walk_constants(
                value[field.json_name],
                field.fields,
                child_source_path,
                child_kotlin_path,
            )


def iter_constants(manifest: dict[str, Any]) -> Iterator[GeneratedConstant]:
    """Yield every numeric manifest leaf with its explicit Kotlin destination."""

    _validate_fields(manifest, _MANIFEST_FIELDS)
    yield from _walk_constants(
        manifest,
        _MANIFEST_FIELDS,
        source_path=(),
        kotlin_path=("AnkiLimitsV1",),
    )


def _render_fields(
    value: dict[str, Any],
    fields: tuple[FieldSpec, ...],
    source_path: tuple[str, ...],
    indent: str,
) -> list[list[str]]:
    blocks: list[list[str]] = []
    for field in fields:
        child_source_path = (*source_path, field.json_name)
        if isinstance(field, IntegerSpec):
            blocks.append(
                [
                    f"{indent}// Manifest: {_describe_path(child_source_path)}",
                    f"{indent}const val {field.kotlin_name}: Int = {value[field.json_name]}",
                ]
            )
        elif isinstance(field, ObjectSpec) and field.kotlin_name is not None:
            child_blocks = _render_fields(
                value[field.json_name],
                field.fields,
                child_source_path,
                f"{indent}    ",
            )
            body: list[str] = []
            for index, child_block in enumerate(child_blocks):
                if index:
                    body.append("")
                body.extend(child_block)
            blocks.append([f"{indent}object {field.kotlin_name} {{", *body, f"{indent}}}"])
    return blocks


def generate_kotlin(manifest: dict[str, Any]) -> bytes:
    """Render deterministic Kotlin bytes independent of manifest key order."""

    _validate_fields(manifest, _MANIFEST_FIELDS)
    blocks = _render_fields(manifest, _MANIFEST_FIELDS, (), "    ")
    body: list[str] = []
    for index, block in enumerate(blocks):
        if index:
            body.append("")
        body.extend(block)
    lines = [
        "// Generated by tools/anki-contract/generate_anki_limits.py --refresh.",
        f"// Source: {LIMITS_MANIFEST_PATH.as_posix()}",
        "// Do not edit by hand.",
        "",
        "package com.ankiminer.android.anki.generated",
        "",
        "internal object AnkiLimitsV1 {",
        *body,
        "}",
        "",
    ]
    return "\n".join(lines).encode("utf-8")


def _create_temporary_file(parent_fd: int, destination_name: str) -> tuple[int, str]:
    for _ in range(128):
        temporary_name = f".{destination_name}.{secrets.token_hex(12)}.tmp"
        try:
            descriptor = os.open(
                temporary_name,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC | os.O_NOFOLLOW,
                mode=0o600,
                dir_fd=parent_fd,
            )
            return descriptor, temporary_name
        except FileExistsError:
            continue
    _fail("cannot allocate a unique temporary Kotlin output file")


def _atomic_write(
    repo_fd: int,
    destination_path: PurePosixPath,
    data: bytes,
) -> None:
    parent_fd, parent_parts, destination_name = _open_parent_directory(
        repo_fd,
        destination_path,
        create=True,
        description="generated Kotlin destination parent",
    )
    temporary_name: str | None = None
    try:
        mode = _path_entry_kind(parent_fd, destination_name)
        if mode is not None and stat.S_ISLNK(mode):
            _fail(f"generated Kotlin destination is a symlink: {destination_path}")
        if mode is not None and not stat.S_ISREG(mode):
            _fail(f"generated Kotlin destination is not a regular file: {destination_path}")

        descriptor, temporary_name = _create_temporary_file(parent_fd, destination_name)
        with os.fdopen(descriptor, "wb") as temporary:
            temporary.write(data)
            temporary.flush()
            os.fsync(temporary.fileno())
            os.fchmod(temporary.fileno(), 0o644)
        _verify_parent_binding(
            repo_fd,
            parent_parts,
            parent_fd,
            "generated Kotlin destination parent",
        )
        mode = _path_entry_kind(parent_fd, destination_name)
        if mode is not None and stat.S_ISLNK(mode):
            _fail(f"generated Kotlin destination is a symlink: {destination_path}")
        if mode is not None and not stat.S_ISREG(mode):
            _fail(f"generated Kotlin destination is not a regular file: {destination_path}")
        os.replace(
            temporary_name,
            destination_name,
            src_dir_fd=parent_fd,
            dst_dir_fd=parent_fd,
        )
        temporary_name = None
        os.fsync(parent_fd)
        _verify_parent_binding(
            repo_fd,
            parent_parts,
            parent_fd,
            "generated Kotlin destination parent",
        )
    except BaseException:
        if temporary_name is not None:
            with suppress(FileNotFoundError):
                os.unlink(temporary_name, dir_fd=parent_fd)
        raise
    finally:
        os.close(parent_fd)


def _check_from_fd(
    repo_fd: int,
    manifest_path: PurePosixPath,
    destination_path: PurePosixPath,
) -> None:
    manifest = _load_manifest_from_fd(repo_fd, manifest_path)
    expected = generate_kotlin(manifest)
    actual = _read_regular_file(
        repo_fd,
        destination_path,
        description="generated Kotlin limits",
        max_bytes=MAX_GENERATED_KOTLIN_BYTES,
    )
    if actual != expected:
        _fail("generated Kotlin limits drifted; run " "tools/anki-contract/generate_anki_limits.py --refresh")


def refresh(
    repo_root: Path,
    manifest_path: PurePosixPath = LIMITS_MANIFEST_PATH,
    destination_path: PurePosixPath = GENERATED_KOTLIN_PATH,
) -> None:
    """Atomically regenerate Kotlin constants from the strict source manifest."""

    with _open_repo(repo_root) as repo_fd:
        manifest = _load_manifest_from_fd(repo_fd, manifest_path)
        _atomic_write(repo_fd, destination_path, generate_kotlin(manifest))
        _check_from_fd(repo_fd, manifest_path, destination_path)


def check(
    repo_root: Path,
    manifest_path: PurePosixPath = LIMITS_MANIFEST_PATH,
    destination_path: PurePosixPath = GENERATED_KOTLIN_PATH,
) -> None:
    """Detect generated Kotlin drift without modifying the repository."""

    with _open_repo(repo_root) as repo_fd:
        _check_from_fd(repo_fd, manifest_path, destination_path)
