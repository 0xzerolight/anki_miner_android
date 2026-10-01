"""Install the data-only components of a vendored language pack.

Desktop downloads a language's pack with ``install_language_pack`` and puts the
pack root on ``sys.path``. Android never does either: engine code ships in the
APK, and only data is downloaded (decision 2). Kotlin downloads a pinned
``language-data`` catalog entry with ``PinnedResourceDownloader``; this module
re-verifies the archive, refuses it outright if the vendored extractor would
write a single code member, and then runs the vendored
``pack_installer._extract_component`` into ``language_packs/<code>/`` -- the
exact layout ``language_pack_installer.component_path`` reads, so the engine's
own availability probe answers for it.

Split models (``languages.SPLIT_DATA_COMPONENTS``) are the exception to "a
vendored pack component": their code ships in the APK, repacked without the
models, so the models are extracted from the component's own pinned archive
with Android's member selection, under an import name of their own, and an
engine override loads them from there.

Nothing here appends to ``sys.path``: a data directory is read by path.

Engine imports are function-local (``bootstrap`` must set ``ANKI_MINER_HOME``
first).
"""

from __future__ import annotations

import logging
import posixpath
import stat
import tarfile
import zipfile
from collections.abc import Mapping
from dataclasses import replace
from pathlib import Path, PurePosixPath
from typing import Any

from . import resources as core
from .bootstrap import require_initialized
from .protocol import BridgeProtocolError, encode_message
from .resource_catalog import (
    LanguageDataResource,
    find_catalog_resource,
    load_resource_catalog,
    load_resource_catalogs,
)

logger = logging.getLogger(__name__)

#: Executable members the extraction never writes (decision 2). A versioned
#: shared object (``libfoo.so.1``) is caught by the ``.so.`` infix. Matched on
#: every lowercased component of the normalised path, so ``a.PY``,
#: ``lib.So.1`` and ``evil.py/junk/..`` (which the extractor writes as
#: ``evil.py``) are code too.
_CODE_SUFFIXES = (".py", ".pyc", ".so")


def _fail(code: str, message: str) -> BridgeProtocolError:
    return BridgeProtocolError(code, message)


def _normalised(name: str) -> str:
    """*name* as the extractor's ``Path.resolve()`` lands it: separators unified, ``.``/``..`` collapsed.

    Lexical collapsing equals ``resolve()`` here: staging is a fresh directory
    and the extractor never creates a symlink.
    """

    return posixpath.normpath(name.replace("\\", "/"))


def is_code_member(name: str) -> bool:
    """True when any component of the normalised member path is named like code."""

    return any(part.endswith(_CODE_SUFFIXES) or ".so." in part for part in _normalised(name).lower().split("/") if part)


def escapes(target: str) -> bool:
    """True when the path the extractor writes would leave its directory."""

    path = _normalised(target)
    return path.startswith("/") or path == ".." or path.startswith("../")


def _split_component(language: str, resource: LanguageDataResource, source: str) -> tuple[Any, Any]:
    """The ``(PackComponent, ArtifactSpec)`` that extracts split models (``languages.SPLIT_DATA_COMPONENTS``).

    The archive must be one *source* pins in its vendored manifest. The
    selection (member prefix, excludes, sentinels, inner digests) and the import
    name are Android's, from the catalog entry: the vendored component would
    extract the package's code too.
    """

    from anki_miner.languages.pack_spec import ArtifactSpec, PackComponent
    from anki_miner.services.language_pack_installer import load_pack

    pack = load_pack(language)
    component = None if pack is None else next((item for item in pack.components if item.import_name == source), None)
    artifacts = () if component is None else (component.universal, *(component.per_platform or {}).values())
    archive = resource.archive
    if not any(
        artifact is not None
        and (artifact.url, artifact.sha256, artifact.kind) == (archive.url, archive.sha256, archive.format)
        for artifact in artifacts
    ):
        raise _fail(
            "resource_catalog_mismatch",
            "Pinned language data does not match the vendored pack manifest",
        )
    install = resource.install
    spec = ArtifactSpec(
        url=archive.url,
        sha256=archive.sha256,
        kind=archive.format,
        member_prefix=install.member_prefix,
        exclude=tuple(install.exclude),
        inner_sha256=tuple((digest.path, digest.sha256) for digest in install.inner_sha256),
    )
    return (
        PackComponent(import_name=resource.import_name, required=True, sentinels=install.sentinels, universal=spec),
        spec,
    )


def _vendored_component(language: str, resource: LanguageDataResource) -> tuple[Any, Any]:
    """The vendored ``(PackComponent, ArtifactSpec)`` the catalog entry was generated from.

    The catalog's ``exclude`` is the vendored list, optionally followed by the code
    members Android drops (an sdist's ``__init__.py``: desktop imports the model
    package, Android reads it by path). Those extra entries may only name code, so
    they never drop data; the returned spec carries them into the extraction.
    """

    from anki_miner.services.language_pack_installer import load_pack

    from .languages import SPLIT_DATA_COMPONENTS

    source = SPLIT_DATA_COMPONENTS.get((language, resource.import_name))
    if source is not None:
        return _split_component(language, resource, source)
    pack = load_pack(language)
    component = (
        None
        if pack is None
        else next((item for item in pack.components if item.import_name == resource.import_name), None)
    )
    spec = None if component is None else component.universal
    install = resource.install
    vendored_exclude = () if spec is None else tuple(spec.exclude)
    dropped_code = install.exclude[len(vendored_exclude) :]
    if (
        spec is None
        or component.per_platform is not None
        or component.abi is not None
        or spec.root_members
        or spec.url != resource.archive.url
        or spec.sha256 != resource.archive.sha256
        or spec.kind != resource.archive.format
        or spec.member_prefix != install.member_prefix
        or install.exclude[: len(vendored_exclude)] != vendored_exclude
        or not all(is_code_member(entry) and not entry.endswith("/") for entry in dropped_code)
        or tuple(component.sentinels) != install.sentinels
        or tuple(spec.inner_sha256) != tuple((digest.path, digest.sha256) for digest in install.inner_sha256)
    ):
        raise _fail(
            "resource_catalog_mismatch",
            "Pinned language data does not match the vendored pack manifest",
        )
    return component, replace(spec, exclude=install.exclude)


def _member_names(archive: Path, kind: str) -> list[str]:
    """Every member name of *archive*, read the way the vendored extractor opens it."""

    try:
        if kind == "sdist":
            with tarfile.open(archive, mode="r:gz") as bundle:
                return bundle.getnames()
        with zipfile.ZipFile(archive) as bundle:
            # Directory entries too: a ``x/evil.py/./`` entry is judged like the file it names.
            return bundle.namelist()
    except (OSError, EOFError, tarfile.TarError, zipfile.BadZipFile) as exc:
        raise _fail("language_data_install_failed", "The language data archive is not a valid archive") from exc


def _refuse_code_members(archive: Path, spec: Any) -> None:
    """Fail closed if the vendored extractor would write any code member.

    Uses the extractor's own member selection (``_wanted`` / ``_wanted_root``),
    so the check covers exactly what ``_extract_component`` writes.
    """

    from anki_miner.services.pack_installer import _wanted, _wanted_root

    refused = []
    for name in _member_names(archive, spec.kind):
        target = _wanted(name, spec) or _wanted_root(name, spec)
        if target is not None and (is_code_member(name) or escapes(target)):
            refused.append(name)
    if refused:
        logger.warning(
            "language_data_code_refused outcome=fail members=%d first=%s",
            len(refused),
            PurePosixPath(_normalised(refused[0])).name,
        )
        raise _fail("language_data_rejected", "The language data archive contains executable code or an escaping path")


def install_language_data(payload: Mapping[str, object]) -> str:
    core._exact(payload, {"operationId", "resourceId", "archivePath"}, code="invalid_resource_request")
    operation_id = core._operation_id(payload["operationId"])
    resource_id = core._bounded_text(payload["resourceId"], name="resourceId", max_bytes=64)
    archive = core._absolute_path(payload["archivePath"], name="archivePath")
    language, resource = find_catalog_resource(resource_id)
    if not isinstance(resource, LanguageDataResource):
        raise _fail("invalid_resource_kind", "Requested resource is not language data")
    require_initialized()

    with core._OPERATIONS.begin(operation_id) as operation:
        operation.check()
        core._hash_archive(
            archive,
            operation,
            maximum_bytes=resource.archive.size_bytes,
            expected_size=resource.archive.size_bytes,
            expected_sha256=resource.archive.sha256,
        )
        component, spec = _vendored_component(language, resource)
        _refuse_code_members(archive, spec)
        operation.check()

        from anki_miner.exceptions import SetupError
        from anki_miner.services._install_common import sweep_stale
        from anki_miner.services.language_pack_installer import language_pack_root
        from anki_miner.services.pack_installer import _extract_component

        root = language_pack_root(language)
        with core._PROMOTION_LOCK:
            try:
                root.mkdir(parents=True, exist_ok=True)
                # A killed install leaves a .staging-* tree inside the root.
                sweep_stale(root)
                _extract_component(archive, root, component, spec)
            except SetupError as exc:
                core._raise_if_storage_exhausted(exc.__cause__ or exc)
                raise _fail("language_data_install_failed", "The language data could not be installed") from exc
            except OSError as exc:
                core._raise_if_storage_exhausted(exc)
                raise _fail("language_data_install_failed", "The language data could not be installed") from exc
        if data_component_path(language, resource.import_name) is None:
            raise _fail("language_data_install_failed", "The installed language data is incomplete")
    logger.info(
        "language_data_installed outcome=ok resource=%s language=%s component=%s",
        resource_id,
        language,
        resource.import_name,
    )
    return encode_message(
        "resource.languagedata.installed",
        {"resourceId": resource_id, "language": language, "importName": resource.import_name},
    )


def _component_complete(directory: Path, sentinels: tuple[str, ...]) -> bool:
    """The engine's sentinel rule (``pack_installer.component_complete``), without the engine."""

    try:
        if stat.S_ISLNK(directory.lstat().st_mode) or not directory.is_dir():
            return False
        for name in sentinels:
            mode = (directory / name).lstat().st_mode
            if stat.S_ISLNK(mode) or not stat.S_ISREG(mode):
                return False
    except OSError:
        return False
    return True


def data_component_path(language: str, import_name: str) -> Path | None:
    """The directory an installed language-data component reads from, or None.

    A vendored pack component answers through the engine's own
    ``component_path``. Split models are no pack component, so their catalog
    entry's sentinels decide, by the same rule, in the same layout
    (``language_packs/<code>/<import name>/``).
    """

    from anki_miner.services.language_pack_installer import component_path, language_pack_root

    from .languages import SPLIT_DATA_COMPONENTS

    if (language, import_name) not in SPLIT_DATA_COMPONENTS:
        return component_path(language, import_name)
    resource = next(
        (entry for entry in load_resource_catalog(language).language_data if entry.import_name == import_name),
        None,
    )
    directory = language_pack_root(language) / import_name
    return directory if resource is not None and _component_complete(directory, resource.install.sentinels) else None


def installed_language_data(home: Path) -> list[str]:
    """Resource ids of every pinned language-data component complete on disk."""

    return [
        resource.resource_id
        for catalog in load_resource_catalogs()
        for resource in catalog.language_data
        if _component_complete(
            home / "language_packs" / catalog.language / resource.import_name,
            resource.install.sentinels,
        )
    ]
