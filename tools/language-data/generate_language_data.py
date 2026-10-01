#!/usr/bin/env python3
"""Generate the ``language-data`` entries of the per-language resource catalogs.

Source of truth is the vendored ``anki_miner/languages/<code>/pack.py`` of every
language in ``tools/engine-sync/composition.toml``: its URL, SHA-256, member
prefix, excludes, sentinels and inner digests are copied verbatim, so an engine
re-pin that moves a pack fails ``--check`` until the catalogs are regenerated.
The manifests are read as syntax, never imported: the vendored package imports
the engine.

Every component of every vendored pack must be classified in ``pins.json``:

- ``data``: downloaded as a ``language-data`` catalog entry. The pin holds only
  what desktop leaves unpinned (the archive's byte length, a display name and
  the attribution) and the SHA-256 the length was measured against, so a moved
  pin cannot keep a stale length.
- ``apk``: engine code, which ships in the APK or not at all (decision 2), with
  the reason.
- ``split``: a component whose code ships in the APK (``apk`` holds the reason)
  but whose models the APK wheel is repacked without. The models are a
  ``language-data`` entry of their own (``data``): the component's own pinned
  archive (``platform`` picks one per-platform artifact, all carry the same
  models), extracted with an Android selection (member prefix, excludes,
  sentinels, inner digests) under an Android import name. An engine override
  loads them from there by path.

A data component must look like data in its manifest: one universal artifact,
a zip, wheel or sdist, no root members and no ABI pin. A pin may name code
members of its archive to drop (``dropCode``: an sdist's ``__init__.py``, which
desktop imports and Android never does); they follow the vendored excludes in
the catalog entry. The bridge's extraction pre-filter refuses any code member
left over regardless.

``--check`` reports drift without writing; ``--refresh`` rewrites the catalog
files (hand-pinned entries are kept in place; the language-data entries are
placed first).
"""

from __future__ import annotations

import argparse
import ast
import json
import sys
import tomllib
from collections.abc import Mapping
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]
PINS_PATH = Path("tools/language-data/pins.json")
COMPOSITION_PATH = Path("tools/engine-sync/composition.toml")
LANGUAGES_PATH = Path("app/src/main/python/anki_miner/languages")
CATALOG_DIR = Path("app/src/main/python/android_bridge/resource_catalog")
CATALOG_SCHEMA_VERSION = 3
DATA_KINDS = frozenset({"zip", "wheel", "sdist"})
#: What ``dropCode`` may name: code members only, never data (mirrors the bridge's rule).
CODE_SUFFIXES = (".py", ".pyc", ".so")


class GenerationError(Exception):
    """The manifests, the pins and the catalogs cannot be reconciled."""


# --------------------------------------------------------------- pack.py as syntax

_CONSTRUCTORS = frozenset({"ArtifactSpec", "PackComponent", "LanguagePack"})


class _Helper:
    """A module-level ``def`` whose body is a single ``return`` (zh's per-ABI opencc factory)."""

    def __init__(self, node: ast.FunctionDef) -> None:
        body = node.body[1:] if ast.get_docstring(node) is not None else node.body
        arguments = node.args
        plain = not (
            arguments.posonlyargs or arguments.vararg or arguments.kwonlyargs or arguments.kwarg or arguments.defaults
        )
        if not plain or len(body) != 1 or not isinstance(body[0], ast.Return) or body[0].value is None:
            raise GenerationError(f"helper {node.name} must take plain parameters and only return (line {node.lineno})")
        self.name = node.name
        self.parameters = [argument.arg for argument in arguments.args]
        self.result = body[0].value


def _bind(target: ast.expr, value: Any, scope: dict[str, Any]) -> None:
    if isinstance(target, ast.Name):
        scope[target.id] = value
    elif isinstance(target, ast.Tuple) and isinstance(value, tuple) and len(value) == len(target.elts):
        for element, item in zip(target.elts, value, strict=True):
            _bind(element, item, scope)
    else:
        raise GenerationError(f"cannot bind the comprehension target (line {target.lineno})")


def _evaluate(node: ast.expr, names: Mapping[str, Any]) -> Any:
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in _CONSTRUCTORS:
        if node.args:
            raise GenerationError(f"{node.func.id} takes keyword arguments only (line {node.lineno})")
        return {
            "__type__": node.func.id,
            **{keyword.arg: _evaluate(keyword.value, names) for keyword in node.keywords if keyword.arg},
        }
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and isinstance(names.get(node.func.id), _Helper):
        helper = names[node.func.id]
        if node.keywords or len(node.args) != len(helper.parameters):
            raise GenerationError(f"call {helper.name} with its positional arguments only (line {node.lineno})")
        scope = dict(names)
        scope.update(zip(helper.parameters, (_evaluate(argument, names) for argument in node.args), strict=True))
        return _evaluate(helper.result, scope)
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr == "items":
        mapping = _evaluate(node.func.value, names)
        if node.args or node.keywords or not isinstance(mapping, dict):
            raise GenerationError(f"unsupported .items() call (line {node.lineno})")
        return tuple(mapping.items())
    if isinstance(node, ast.DictComp):
        if len(node.generators) != 1 or node.generators[0].ifs or node.generators[0].is_async:
            raise GenerationError(f"only one unfiltered comprehension clause is supported (line {node.lineno})")
        clause = node.generators[0]
        result = {}
        for item in _evaluate(clause.iter, names):
            scope = dict(names)
            _bind(clause.target, item, scope)
            result[_evaluate(node.key, scope)] = _evaluate(node.value, scope)
        return result
    if isinstance(node, ast.Name):
        if node.id not in names:
            raise GenerationError(f"unresolved name {node.id!r} (line {node.lineno})")
        return names[node.id]
    if isinstance(node, ast.Tuple | ast.List):
        return tuple(_evaluate(element, names) for element in node.elts)
    if isinstance(node, ast.Dict):
        return {
            _evaluate(key, names): _evaluate(value, names) for key, value in zip(node.keys, node.values, strict=True)
        }
    return ast.literal_eval(node)


def read_pack(path: Path) -> dict[str, Any]:
    """The ``PACK`` a manifest declares, as nested dicts tagged with ``__type__``."""

    names: dict[str, Any] = {}
    for statement in ast.parse(path.read_text(encoding="utf-8")).body:
        if isinstance(statement, ast.FunctionDef):
            names[statement.name] = _Helper(statement)
            continue
        if isinstance(statement, ast.Assign) and len(statement.targets) == 1:
            target, value = statement.targets[0], statement.value
        elif isinstance(statement, ast.AnnAssign) and statement.value is not None:
            target, value = statement.target, statement.value
        else:
            continue
        if isinstance(target, ast.Name) and target.id != "__all__":
            names[target.id] = _evaluate(value, names)
    pack = names.get("PACK")
    if not isinstance(pack, dict) or pack.get("__type__") != "LanguagePack":
        raise GenerationError(f"{path}: no PACK = LanguagePack(...)")
    return pack


# --------------------------------------------------------------- generation


def _load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise GenerationError(f"cannot read {path}: {exc}") from exc


def vendored_languages(repo: Path) -> tuple[str, ...]:
    with (repo / COMPOSITION_PATH).open("rb") as stream:
        languages = tomllib.load(stream).get("languages")
    if not isinstance(languages, list) or not all(isinstance(code, str) for code in languages):
        raise GenerationError(f"{COMPOSITION_PATH}: languages must be a list of codes")
    return tuple(languages)


def _resource_id(code: str, import_name: str) -> str:
    return f"{code}-{import_name.replace('_', '-')}"


def _data_entry(code: str, component: Mapping[str, Any], pin: Mapping[str, Any]) -> dict[str, Any]:
    name = component["import_name"]
    spec = component.get("universal")
    where = f"{code}/{name}"
    if not isinstance(spec, dict) or component.get("per_platform") is not None:
        raise GenerationError(f"{where}: a data component needs exactly one universal artifact")
    if component.get("abi") is not None or spec.get("root_members"):
        raise GenerationError(f"{where}: an ABI pin or root members mean code, not data")
    if spec["kind"] not in DATA_KINDS:
        raise GenerationError(f"{where}: artifact kind {spec['kind']!r} is not a data archive")
    if pin.get("sha256") != spec["sha256"]:
        raise GenerationError(
            f"{where}: pins.json measured {pin.get('sha256')!r} but pack.py pins {spec['sha256']!r}; "
            "re-measure sizeBytes for the new artifact"
        )
    drop_code = pin.get("dropCode", [])
    if not isinstance(drop_code, list) or not all(
        isinstance(member, str) and member.lower().endswith(CODE_SUFFIXES) for member in drop_code
    ):
        raise GenerationError(f"{where}: dropCode may only name code members (.py, .pyc, .so)")
    return {
        "resourceId": _resource_id(code, name),
        "kind": "language-data",
        "displayName": pin["displayName"],
        "importName": name,
        "archive": {
            "url": spec["url"],
            "sha256": spec["sha256"],
            "sizeBytes": pin["sizeBytes"],
            "format": spec["kind"],
        },
        "install": {
            "memberPrefix": spec["member_prefix"],
            "exclude": [*spec.get("exclude", ()), *drop_code],
            "sentinels": list(component["sentinels"]),
            "innerSha256": [{"path": path, "sha256": digest} for path, digest in spec.get("inner_sha256", ())],
        },
        "attribution": pin["attribution"],
    }


def _require_reason(key: str, reason: object) -> None:
    if not isinstance(reason, str) or not reason.strip():
        raise GenerationError(f"{key}: an apk classification needs its reason")


_SPLIT_DATA_KEYS = frozenset(
    {
        "importName",
        "sha256",
        "sizeBytes",
        "displayName",
        "memberPrefix",
        "exclude",
        "sentinels",
        "innerSha256",
        "attribution",
    }
)


def _split_entry(code: str, component: Mapping[str, Any], pin: Mapping[str, Any]) -> dict[str, Any]:
    """The ``language-data`` entry for the models of a component whose code ships in the APK."""

    where = f"{code}/{component['import_name']}"
    if not isinstance(pin, dict) or set(pin) != {"apk", "data"}:
        raise GenerationError(f"{where}: a split classification holds exactly apk and data")
    _require_reason(where, pin["apk"])
    data = pin["data"]
    if not isinstance(data, dict) or not _SPLIT_DATA_KEYS <= set(data) <= _SPLIT_DATA_KEYS | {"platform"}:
        raise GenerationError(f"{where}: split data needs exactly {sorted(_SPLIT_DATA_KEYS)} (and optionally platform)")
    platform = data.get("platform")
    if platform is None:
        spec = component.get("universal")
    else:
        spec = (component.get("per_platform") or {}).get(tuple(platform))
    if not isinstance(spec, dict):
        raise GenerationError(f"{where}: pack.py pins no artifact for platform {platform!r}")
    if spec["kind"] not in DATA_KINDS:
        raise GenerationError(f"{where}: artifact kind {spec['kind']!r} is not a data archive")
    if data["sha256"] != spec["sha256"]:
        raise GenerationError(
            f"{where}: pins.json measured {data['sha256']!r} but pack.py pins {spec['sha256']!r}; "
            "re-measure sizeBytes and the selection for the new artifact"
        )
    name = data["importName"]
    if name == component["import_name"] or not isinstance(name, str) or not name.isidentifier():
        raise GenerationError(f"{where}: the models need an import name of their own, not {name!r}")
    if not data["sentinels"]:
        raise GenerationError(f"{where}: split data needs sentinels")
    return {
        "resourceId": _resource_id(code, name),
        "kind": "language-data",
        "displayName": data["displayName"],
        "importName": name,
        "archive": {
            "url": spec["url"],
            "sha256": spec["sha256"],
            "sizeBytes": data["sizeBytes"],
            "format": spec["kind"],
        },
        "install": {
            "memberPrefix": data["memberPrefix"],
            "exclude": list(data["exclude"]),
            "sentinels": list(data["sentinels"]),
            "innerSha256": [{"path": item["path"], "sha256": item["sha256"]} for item in data["innerSha256"]],
        },
        "attribution": data["attribution"],
    }


def generate_entries(repo: Path) -> dict[str, list[dict[str, Any]]]:
    """``{code: [language-data entry, ...]}`` for every vendored language with a pack."""

    pins = _load_json(repo / PINS_PATH).get("components")
    if not isinstance(pins, dict):
        raise GenerationError(f"{PINS_PATH}: expected a components object")
    entries: dict[str, list[dict[str, Any]]] = {}
    seen: set[str] = set()
    for code in vendored_languages(repo):
        manifest = repo / LANGUAGES_PATH / code / "pack.py"
        if not manifest.is_file():
            continue
        for component in read_pack(manifest).get("components", ()):
            key = f"{code}/{component['import_name']}"
            seen.add(key)
            pin = pins.get(key)
            if not isinstance(pin, dict) or len(pin) != 1 or not ({"data", "apk", "split"} & set(pin)):
                raise GenerationError(f"{key}: classify it in {PINS_PATH} as data, apk or split")
            if "apk" in pin:
                _require_reason(key, pin["apk"])
                continue
            if "split" in pin:
                entries.setdefault(code, []).append(_split_entry(code, component, pin["split"]))
                continue
            entries.setdefault(code, []).append(_data_entry(code, component, pin["data"]))
    stale = sorted(set(pins) - seen)
    if stale:
        raise GenerationError(f"{PINS_PATH} classifies components no vendored pack declares: {stale}")
    return entries


def _render(document: Mapping[str, Any]) -> str:
    return json.dumps(document, indent=2, ensure_ascii=False) + "\n"


def expected_catalogs(repo: Path) -> dict[Path, str | None]:
    """Each catalog file's expected text; None removes a file holding only stale data."""

    entries = generate_entries(repo)
    directory = repo / CATALOG_DIR
    codes = {path.stem for path in directory.glob("*.json")} | set(entries)
    result: dict[Path, str | None] = {}
    for code in sorted(codes):
        path = directory / f"{code}.json"
        if path.exists():
            document = _load_json(path)
        else:
            document = {"schemaVersion": CATALOG_SCHEMA_VERSION, "language": code, "resources": [], "recommended": []}
        hand = [entry for entry in document.get("resources", []) if entry.get("kind") != "language-data"]
        resources = entries.get(code, []) + hand
        if not resources:
            result[path] = None
            continue
        result[path] = _render({**document, "resources": resources})
    return result


def drift(repo: Path) -> list[str]:
    problems = []
    for path, text in expected_catalogs(repo).items():
        current = path.read_text(encoding="utf-8") if path.exists() else None
        if current != text:
            relative = path.relative_to(repo)
            problems.append(f"{relative} is {'stale' if text is not None else 'obsolete'}")
    return problems


def refresh(repo: Path) -> None:
    for path, text in expected_catalogs(repo).items():
        if text is None:
            path.unlink(missing_ok=True)
            continue
        temporary = path.with_suffix(".json.tmp")
        temporary.write_text(text, encoding="utf-8")
        temporary.replace(path)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo-root", type=Path, default=REPO_ROOT, help=argparse.SUPPRESS)
    actions = parser.add_mutually_exclusive_group(required=True)
    actions.add_argument("--check", action="store_true", help="report drift without writing")
    actions.add_argument("--refresh", action="store_true", help="rewrite the catalog files")
    args = parser.parse_args(argv)
    try:
        if args.check:
            problems = drift(args.repo_root)
            if problems:
                for problem in problems:
                    print(f"language-data drift: {problem}", file=sys.stderr)
                print("Run tools/language-data/generate_language_data.py --refresh", file=sys.stderr)
                return 1
            print("Language-data catalog check OK")
        else:
            refresh(args.repo_root)
            print("Language-data catalog entries refreshed")
    except GenerationError as exc:
        print(f"Language-data generation failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
