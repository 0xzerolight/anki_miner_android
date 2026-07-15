"""Pinned Git-tree import closure and generated-vendor synchronization."""

from __future__ import annotations

import ast
import hashlib
import json
import os
import subprocess
import tempfile
import tomllib
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterator, Mapping


MANIFEST_NAME = ".engine-sync-manifest.json"
MANIFEST_VERSION = 1


class EngineSyncError(RuntimeError):
    """A deterministic sync invariant was violated."""


@dataclass(frozen=True)
class TypeCheckingException:
    importer: str
    target: str


@dataclass(frozen=True)
class Composition:
    path: Path
    roots: tuple[str, ...]
    assets: tuple[str, ...]
    protected_verbatim: frozenset[str]
    allowed_external: frozenset[str]
    allowed_deferred_external: frozenset[str]
    allowed_stdlib: frozenset[str]
    local_only_imports: frozenset[str]
    forbidden_imports: tuple[str, ...]
    forbidden_type_checking_exceptions: frozenset[TypeCheckingException]
    sha256: str


@dataclass(frozen=True)
class ImportRef:
    importer: str
    target: str
    line: int
    type_checking_only: bool
    deferred: bool
    member_candidate: bool = False


@dataclass(frozen=True)
class ModuleFile:
    module: str
    path: str
    is_package: bool
    origin: str
    content: bytes


@dataclass(frozen=True)
class SnapshotFile:
    path: str
    content: bytes
    origin: str


@dataclass(frozen=True)
class EngineSnapshot:
    revision: str
    composition_sha256: str
    files: Mapping[str, SnapshotFile]
    modules: tuple[str, ...]
    eager_external_imports: tuple[str, ...]
    deferred_external_imports: tuple[str, ...]
    type_checking_exceptions: tuple[TypeCheckingException, ...]

    def manifest_bytes(self) -> bytes:
        payload = {
            "format_version": MANIFEST_VERSION,
            "engine_revision": self.revision,
            "composition_sha256": self.composition_sha256,
            "modules": list(self.modules),
            "external_imports": {
                "eager": list(self.eager_external_imports),
                "deferred": list(self.deferred_external_imports),
            },
            "files": {
                path: {
                    "origin": item.origin,
                    "sha256": _sha256(item.content),
                }
                for path, item in sorted(self.files.items())
            },
        }
        return _canonical_json(payload)

    def expected_files(self) -> dict[str, bytes]:
        expected = {path: item.content for path, item in self.files.items()}
        expected[MANIFEST_NAME] = self.manifest_bytes()
        return expected


def _canonical_json(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def _sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _require_string_list(data: Mapping[str, Any], key: str) -> tuple[str, ...]:
    value = data.get(key)
    if not isinstance(value, list) or not all(
        isinstance(item, str) and item for item in value
    ):
        raise EngineSyncError(f"{key} must be a non-empty-string array")
    if len(value) != len(set(value)):
        raise EngineSyncError(f"{key} contains duplicates")
    return tuple(value)


def load_composition(path: Path) -> Composition:
    try:
        raw = path.read_bytes()
        data = tomllib.loads(raw.decode("utf-8"))
    except (OSError, UnicodeDecodeError, tomllib.TOMLDecodeError) as exc:
        raise EngineSyncError(f"cannot read composition {path}: {exc}") from exc
    if data.get("format_version") != 1:
        raise EngineSyncError("unsupported composition format_version")
    roots = _require_string_list(data, "roots")
    assets = _require_string_list(data, "assets")
    protected = frozenset(_require_string_list(data, "protected_verbatim"))
    allowed_external = frozenset(_require_string_list(data, "allowed_external"))
    allowed_deferred = frozenset(
        _require_string_list(data, "allowed_deferred_external")
    )
    allowed_stdlib = frozenset(_require_string_list(data, "allowed_stdlib"))
    local_only = frozenset(_require_string_list(data, "local_only_imports"))
    forbidden = _require_string_list(data, "forbidden_imports")
    exceptions_data = data.get("forbidden_type_checking_exceptions", [])
    if not isinstance(exceptions_data, list):
        raise EngineSyncError(
            "forbidden_type_checking_exceptions must be an array of tables"
        )
    exceptions: set[TypeCheckingException] = set()
    for entry in exceptions_data:
        if not isinstance(entry, dict) or set(entry) != {"importer", "target"}:
            raise EngineSyncError(
                "each forbidden TYPE_CHECKING exception needs exactly importer and target"
            )
        importer, target = entry["importer"], entry["target"]
        if (
            not isinstance(importer, str)
            or not isinstance(target, str)
            or not importer
            or not target
        ):
            raise EngineSyncError(
                "TYPE_CHECKING exception importer and target must be strings"
            )
        exceptions.add(TypeCheckingException(importer, target))
    if protected - set(roots):
        raise EngineSyncError(
            "protected_verbatim modules must also be composition roots"
        )
    return Composition(
        path=path,
        roots=roots,
        assets=assets,
        protected_verbatim=protected,
        allowed_external=allowed_external,
        allowed_deferred_external=allowed_deferred,
        allowed_stdlib=allowed_stdlib,
        local_only_imports=local_only,
        forbidden_imports=forbidden,
        forbidden_type_checking_exceptions=frozenset(exceptions),
        sha256=_sha256(raw),
    )


def load_lock(path: Path) -> str:
    try:
        revision = path.read_text(encoding="ascii").strip()
    except OSError as exc:
        raise EngineSyncError(f"cannot read engine lock {path}: {exc}") from exc
    if len(revision) != 40 or any(char not in "0123456789abcdef" for char in revision):
        raise EngineSyncError(
            "engine.lock must contain exactly one lowercase 40-character Git SHA"
        )
    return revision


class GitTree:
    def __init__(self, repo: Path, revision: str):
        self.repo = repo
        self.revision = self._resolve_revision(revision)
        self.paths = self._list_paths()
        self._cache: dict[str, bytes] = {}

    def _git(self, *args: str) -> bytes:
        try:
            result = subprocess.run(
                ["git", "-C", os.fspath(self.repo), *args],
                check=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
        except (OSError, subprocess.CalledProcessError) as exc:
            detail = (
                exc.stderr.decode("utf-8", "replace").strip()
                if isinstance(exc, subprocess.CalledProcessError)
                else str(exc)
            )
            raise EngineSyncError(f"git {' '.join(args)} failed: {detail}") from exc
        return result.stdout

    def _resolve_revision(self, revision: str) -> str:
        resolved = (
            self._git("rev-parse", f"{revision}^{{commit}}").decode("ascii").strip()
        )
        if resolved != revision:
            raise EngineSyncError(
                f"engine.lock must name a full commit SHA (resolved {revision} to {resolved})"
            )
        return resolved

    def _list_paths(self) -> frozenset[str]:
        raw = self._git(
            "ls-tree", "-r", "--name-only", "-z", self.revision, "--", "anki_miner"
        )
        paths = frozenset(part.decode("utf-8") for part in raw.split(b"\0") if part)
        if "anki_miner/__init__.py" not in paths:
            raise EngineSyncError("pinned commit has no anki_miner package")
        return paths

    def read(self, path: str) -> bytes:
        if path not in self.paths:
            raise EngineSyncError(f"pinned engine asset does not exist: {path}")
        if path not in self._cache:
            self._cache[path] = self._git("show", f"{self.revision}:{path}")
        return self._cache[path]


def _path_to_module(path: str) -> tuple[str, bool] | None:
    pure = PurePosixPath(path)
    if pure.suffix != ".py":
        return None
    parts = list(pure.parts)
    if parts[-1] == "__init__.py":
        return ".".join(parts[:-1]), True
    parts[-1] = pure.stem
    return ".".join(parts), False


def _overlay_files(root: Path) -> dict[str, bytes]:
    if not root.is_dir():
        raise EngineSyncError(f"overlay directory does not exist: {root}")
    result: dict[str, bytes] = {}
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise EngineSyncError(f"overlay symlinks are not allowed: {path}")
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
        if rel.startswith(".") or "/." in rel:
            raise EngineSyncError(f"hidden overlay files are not allowed: {rel}")
        result[rel] = path.read_bytes()
    return result


def _module_index(
    tree: GitTree, overlays: Mapping[str, bytes]
) -> dict[str, ModuleFile]:
    index: dict[str, ModuleFile] = {}
    for path in sorted(tree.paths):
        info = _path_to_module(path)
        if info is None:
            continue
        module, is_package = info
        index[module] = ModuleFile(module, path, is_package, "desktop", tree.read(path))
    for path, content in sorted(overlays.items()):
        info = _path_to_module(path)
        if info is None:
            continue
        module, is_package = info
        index[module] = ModuleFile(module, path, is_package, "overlay", content)
    return index


def _is_type_checking_test(node: ast.expr) -> bool:
    return (isinstance(node, ast.Name) and node.id == "TYPE_CHECKING") or (
        isinstance(node, ast.Attribute)
        and isinstance(node.value, ast.Name)
        and node.value.id == "typing"
        and node.attr == "TYPE_CHECKING"
    )


def _absolute_from_target(module: ModuleFile, node: ast.ImportFrom) -> str:
    if node.level == 0:
        return node.module or ""
    package = module.module if module.is_package else module.module.rpartition(".")[0]
    parts = package.split(".") if package else []
    up = node.level - 1
    if up > len(parts):
        raise EngineSyncError(
            f"{module.path}:{node.lineno}: relative import escapes package"
        )
    base = parts[: len(parts) - up]
    if node.module:
        base.extend(node.module.split("."))
    return ".".join(base)


def _resolve_dynamic_target(module: ModuleFile, target: str) -> str:
    if not target.startswith("."):
        return target
    level = len(target) - len(target.lstrip("."))
    package = module.module if module.is_package else module.module.rpartition(".")[0]
    parts = package.split(".") if package else []
    up = level - 1
    if up > len(parts):
        raise EngineSyncError(f"{module.path}: dynamic relative import escapes package")
    suffix = target[level:]
    base = parts[: len(parts) - up]
    if suffix:
        base.extend(suffix.split("."))
    return ".".join(base)


def _iter_imports(module: ModuleFile) -> Iterator[ImportRef]:
    try:
        tree = ast.parse(module.content, filename=module.path)
    except (SyntaxError, ValueError) as exc:
        raise EngineSyncError(f"cannot parse {module.path}: {exc}") from exc

    class Collector(ast.NodeVisitor):
        def __init__(self) -> None:
            self.refs: list[ImportRef] = []
            self.type_checking = False
            self.deferred = False

        def _append(
            self, target: str, line: int, *, member_candidate: bool = False
        ) -> None:
            self.refs.append(
                ImportRef(
                    module.module,
                    target,
                    line,
                    self.type_checking,
                    self.deferred,
                    member_candidate,
                )
            )

        def _visit_with_state(
            self,
            nodes: list[ast.stmt],
            *,
            type_checking: bool | None = None,
            deferred: bool | None = None,
        ) -> None:
            previous = self.type_checking, self.deferred
            if type_checking is not None:
                self.type_checking = type_checking
            if deferred is not None:
                self.deferred = deferred
            for child in nodes:
                self.visit(child)
            self.type_checking, self.deferred = previous

        def visit_Import(self, node: ast.Import) -> None:
            for alias in node.names:
                self._append(alias.name, node.lineno)

        def visit_ImportFrom(self, node: ast.ImportFrom) -> None:
            target = _absolute_from_target(module, node)
            if target:
                self._append(target, node.lineno)
            for alias in node.names:
                if alias.name != "*":
                    child = f"{target}.{alias.name}" if target else alias.name
                    self._append(child, node.lineno, member_candidate=True)

        def visit_If(self, node: ast.If) -> None:
            self.visit(node.test)
            guarded = self.type_checking or _is_type_checking_test(node.test)
            self._visit_with_state(node.body, type_checking=guarded)
            self._visit_with_state(node.orelse)

        def _visit_function(self, node: ast.FunctionDef | ast.AsyncFunctionDef) -> None:
            for decorator in node.decorator_list:
                self.visit(decorator)
            for default in [*node.args.defaults, *node.args.kw_defaults]:
                if default is not None:
                    self.visit(default)
            self._visit_with_state(node.body, deferred=True)

        def visit_FunctionDef(self, node: ast.FunctionDef) -> None:
            self._visit_function(node)

        def visit_AsyncFunctionDef(self, node: ast.AsyncFunctionDef) -> None:
            self._visit_function(node)

        def visit_Lambda(self, node: ast.Lambda) -> None:
            previous = self.deferred
            self.deferred = True
            self.visit(node.body)
            self.deferred = previous

        def visit_Call(self, node: ast.Call) -> None:
            name: str | None = None
            if isinstance(node.func, ast.Name) and node.func.id in {
                "__import__",
                "import_module",
            }:
                name = node.func.id
            elif (
                isinstance(node.func, ast.Attribute)
                and isinstance(node.func.value, ast.Name)
                and node.func.value.id == "importlib"
                and node.func.attr == "import_module"
            ):
                name = node.func.attr
            if name and node.args and isinstance(node.args[0], ast.Constant):
                target = node.args[0].value
                if isinstance(target, str) and target:
                    self._append(_resolve_dynamic_target(module, target), node.lineno)
            self.generic_visit(node)

    collector = Collector()
    collector.visit(tree)
    yield from collector.refs


def _prefix_matches(target: str, prefix: str) -> bool:
    return target == prefix or target.startswith(prefix + ".")


def _parent_modules(module: str, index: Mapping[str, ModuleFile]) -> Iterator[str]:
    parts = module.split(".")
    for end in range(1, len(parts)):
        parent = ".".join(parts[:end])
        item = index.get(parent)
        if item is not None and item.is_package:
            yield parent


def build_snapshot(
    *,
    source_repo: Path,
    lock_path: Path,
    composition_path: Path,
    overlays_path: Path,
) -> EngineSnapshot:
    composition = load_composition(composition_path)
    revision = load_lock(lock_path)
    tree = GitTree(source_repo, revision)
    overlays = _overlay_files(overlays_path)
    index = _module_index(tree, overlays)

    for protected in sorted(composition.protected_verbatim):
        item = index.get(protected)
        if item is None:
            raise EngineSyncError(f"protected module is missing: {protected}")
        if item.origin != "desktop":
            raise EngineSyncError(f"protected module cannot be overlaid: {protected}")
        if item.content != tree.read(item.path):
            raise EngineSyncError(
                f"protected module differs from the pinned desktop source: {protected}"
            )

    selected: set[str] = set()
    pending = list(composition.roots)
    eager_external: set[str] = set()
    deferred_external: set[str] = set()
    used_exceptions: set[TypeCheckingException] = set()

    while pending:
        module_name = pending.pop()
        if module_name in selected:
            continue
        item = index.get(module_name)
        if item is None:
            raise EngineSyncError(
                f"composition root/local dependency is missing: {module_name}"
            )
        selected.add(module_name)
        pending.extend(
            parent
            for parent in _parent_modules(module_name, index)
            if parent not in selected
        )
        refs = list(_iter_imports(item))
        for ref in refs:
            forbidden = next(
                (
                    name
                    for name in composition.forbidden_imports
                    if _prefix_matches(ref.target, name)
                ),
                None,
            )
            if forbidden is not None:
                exception = TypeCheckingException(ref.importer, forbidden)
                if (
                    ref.type_checking_only
                    and exception in composition.forbidden_type_checking_exceptions
                ):
                    used_exceptions.add(exception)
                    continue
                raise EngineSyncError(
                    f"{item.path}:{ref.line}: forbidden import {ref.target}"
                    + (" (TYPE_CHECKING)" if ref.type_checking_only else "")
                )
            if ref.type_checking_only:
                continue
            if ref.target in index:
                pending.append(ref.target)
                continue
            if ref.member_candidate:
                continue
            top = ref.target.partition(".")[0]
            if top in composition.local_only_imports:
                raise EngineSyncError(
                    f"{item.path}:{ref.line}: {top} must resolve from an overlay"
                )
            if top in composition.allowed_stdlib:
                continue
            if top in composition.allowed_external:
                (deferred_external if ref.deferred else eager_external).add(top)
                continue
            if ref.deferred and top in composition.allowed_deferred_external:
                deferred_external.add(top)
                continue
            raise EngineSyncError(
                f"{item.path}:{ref.line}: unresolved {'deferred ' if ref.deferred else ''}import {ref.target}"
            )

    unused_exceptions = composition.forbidden_type_checking_exceptions - used_exceptions
    if unused_exceptions:
        rendered = ", ".join(
            f"{item.importer} -> {item.target}"
            for item in sorted(unused_exceptions, key=lambda x: (x.importer, x.target))
        )
        raise EngineSyncError(f"unused forbidden TYPE_CHECKING exceptions: {rendered}")

    files: dict[str, SnapshotFile] = {}
    for module_name in sorted(selected):
        item = index[module_name]
        files[item.path] = SnapshotFile(item.path, item.content, item.origin)
    for asset in composition.assets:
        content = overlays.get(asset)
        origin = "overlay"
        if content is None:
            content = tree.read(asset)
            origin = "desktop"
        files[asset] = SnapshotFile(asset, content, origin)

    return EngineSnapshot(
        revision=tree.revision,
        composition_sha256=composition.sha256,
        files=dict(sorted(files.items())),
        modules=tuple(sorted(selected)),
        eager_external_imports=tuple(sorted(eager_external)),
        deferred_external_imports=tuple(sorted(deferred_external)),
        type_checking_exceptions=tuple(
            sorted(used_exceptions, key=lambda item: (item.importer, item.target))
        ),
    )


def _managed_roots(snapshot: EngineSnapshot) -> frozenset[str]:
    return frozenset(path.partition("/")[0] for path in snapshot.files)


def _actual_managed_files(destination: Path, snapshot: EngineSnapshot) -> set[str]:
    actual: set[str] = set()
    for root_name in _managed_roots(snapshot):
        root = destination / root_name
        if root.is_symlink():
            raise EngineSyncError(
                f"managed destination root may not be a symlink: {root}"
            )
        if not root.exists():
            continue
        for path in root.rglob("*"):
            if path.is_symlink():
                raise EngineSyncError(
                    f"managed destination may not contain symlinks: {path}"
                )
            if path.is_file():
                actual.add(path.relative_to(destination).as_posix())
    if (destination / MANIFEST_NAME).is_file():
        actual.add(MANIFEST_NAME)
    return actual


def check_destination(destination: Path, snapshot: EngineSnapshot) -> tuple[str, ...]:
    expected = snapshot.expected_files()
    actual_paths = _actual_managed_files(destination, snapshot)
    differences: list[str] = []
    for path in sorted(set(expected) - actual_paths):
        differences.append(f"missing {path}")
    for path in sorted(actual_paths - set(expected)):
        differences.append(f"unexpected {path}")
    for path in sorted(set(expected) & actual_paths):
        actual = (destination / path).read_bytes()
        if actual != expected[path]:
            differences.append(f"modified {path}")
    return tuple(differences)


def sync_destination(destination: Path, snapshot: EngineSnapshot) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    expected = snapshot.expected_files()
    actual_paths = _actual_managed_files(destination, snapshot)
    for path in sorted(actual_paths - set(expected), reverse=True):
        (destination / path).unlink()
    for path, content in sorted(expected.items()):
        target = destination / path
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.is_file() and target.read_bytes() == content:
            continue
        fd, temporary_name = tempfile.mkstemp(
            prefix=f".{target.name}.", dir=target.parent
        )
        try:
            with os.fdopen(fd, "wb") as stream:
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary_name, target)
        except BaseException:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass
            raise
    for root_name in _managed_roots(snapshot):
        root = destination / root_name
        for directory in sorted(
            (path for path in root.rglob("*") if path.is_dir()), reverse=True
        ):
            try:
                directory.rmdir()
            except OSError:
                pass
