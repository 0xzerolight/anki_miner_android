from __future__ import annotations

import copy
import json
import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path, PurePosixPath
from unittest import mock

TOOL_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = TOOL_ROOT.parents[1]
sys.path.insert(0, str(TOOL_ROOT))

from anki_contract_codegen import core  # noqa: E402


class AnkiLimitsCodegenTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.temp_root = Path(self.temporary_directory.name)
        self.repo = self.temp_root / "repo"
        self.repo.mkdir()

        source_manifest = REPO_ROOT / core.LIMITS_MANIFEST_PATH
        self.original_manifest_bytes = source_manifest.read_bytes()
        self.original_manifest = json.loads(self.original_manifest_bytes)
        self.manifest_path = self.repo / core.LIMITS_MANIFEST_PATH
        self.manifest_path.parent.mkdir(parents=True)
        self.manifest_path.write_bytes(self.original_manifest_bytes)

    def _write_manifest(self, value: object) -> None:
        self.manifest_path.write_text(
            json.dumps(value, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    @staticmethod
    def _numeric_leaves(
        value: object,
        path: tuple[str, ...] = (),
    ) -> dict[tuple[str, ...], int]:
        leaves: dict[tuple[str, ...], int] = {}
        if type(value) is dict:
            for key, child in value.items():
                leaves.update(AnkiLimitsCodegenTest._numeric_leaves(child, (*path, key)))
        elif type(value) is int:
            leaves[path] = value
        return leaves

    @staticmethod
    def _file_snapshot(root: Path) -> dict[str, bytes]:
        return {
            path.relative_to(root).as_posix(): path.read_bytes()
            for path in root.rglob("*")
            if path.is_file()
        }

    def test_every_numeric_leaf_has_one_explicit_unit_bearing_kotlin_mapping(self) -> None:
        manifest = core.load_manifest(self.repo)
        constants = list(core.iter_constants(manifest))
        numeric_leaves = self._numeric_leaves(self.original_manifest)

        self.assertEqual(
            numeric_leaves,
            {constant.source_path: constant.value for constant in constants},
        )
        self.assertEqual(60, len(constants))
        self.assertEqual(
            len(constants),
            len({constant.kotlin_path for constant in constants}),
        )
        for constant in constants:
            self.assertTrue(
                any(
                    unit in constant.kotlin_path[-1]
                    for unit in ("VERSION", "CODE", "COUNT", "CODE_POINTS", "UTF8_BYTES", "BYTES")
                ),
                constant.kotlin_path,
            )

        by_source = {constant.source_path: constant for constant in constants}
        self.assertEqual(
            ("AnkiLimitsV1", "Names", "Deck", "MAX_CODE_POINTS"),
            by_source[("names", "deck", "maxCodePoints")].kotlin_path,
        )
        self.assertEqual(
            (
                "AnkiLimitsV1",
                "ScanFirstFields",
                "KNOWN_TOTAL_SCANNED_NOTE_MAX_COUNT",
            ),
            by_source[("scanFirstFields", "knownTotalScannedNotes")].kotlin_path,
        )
        self.assertEqual(
            ("AnkiLimitsV1", "CreateCall", "MEDIA_WORK_MAX_BYTES"),
            by_source[("createCall", "mediaWorkMaxBytes")].kotlin_path,
        )

        rendered = core.generate_kotlin(manifest).decode("utf-8")
        self.assertEqual(len(constants), rendered.count("// Manifest:"))
        for constant in constants:
            indent = "    " * (len(constant.kotlin_path) - 1)
            source_path = ".".join(constant.source_path)
            expected = (
                f"{indent}// Manifest: {source_path}\n"
                f"{indent}const val {constant.kotlin_path[-1]}: Int = {constant.value}"
            )
            self.assertIn(expected, rendered)

    def test_manifest_shape_types_version_and_unit_meanings_are_strict(self) -> None:
        cases: list[tuple[str, object, str]] = []

        missing = copy.deepcopy(self.original_manifest)
        del missing["storeMedia"]["maxAssets"]
        cases.append(("missing", missing, "missing"))

        unknown = copy.deepcopy(self.original_manifest)
        unknown["createCall"]["surprise"] = 1
        cases.append(("unknown", unknown, "unknown"))

        boolean = copy.deepcopy(self.original_manifest)
        boolean["names"]["deck"]["maxCodePoints"] = True
        cases.append(("boolean", boolean, "booleans are not integers"))

        floating = copy.deepcopy(self.original_manifest)
        floating["names"]["deck"]["maxCodePoints"] = 1024.0
        cases.append(("float", floating, "must be an integer"))

        wrong_object_type = copy.deepcopy(self.original_manifest)
        wrong_object_type["verifyTarget"] = []
        cases.append(("object type", wrong_object_type, "must be an object"))

        wrong_version = copy.deepcopy(self.original_manifest)
        wrong_version["schemaVersion"] = 2
        cases.append(("version", wrong_version, "must equal 1"))

        wrong_unit_type = copy.deepcopy(self.original_manifest)
        wrong_unit_type["units"]["items"] = 1
        cases.append(("unit type", wrong_unit_type, "must be a string"))

        changed_unit_meaning = copy.deepcopy(self.original_manifest)
        changed_unit_meaning["units"]["codePoints"] = "UTF-16 code units"
        cases.append(("unit meaning", changed_unit_meaning, "frozen v1 meaning"))

        for name, value, message in cases:
            with self.subTest(name=name):
                self._write_manifest(value)
                with self.assertRaisesRegex(core.ContractError, message):
                    core.load_manifest(self.repo)

    def test_duplicate_json_keys_are_rejected_at_every_depth(self) -> None:
        duplicate = self.original_manifest_bytes.replace(
            b'"maxAssets": 50,',
            b'"maxAssets": 50,\n    "maxAssets": 50,',
            1,
        )
        self.manifest_path.write_bytes(duplicate)

        with self.assertRaisesRegex(core.ContractError, "duplicate key: maxAssets"):
            core.load_manifest(self.repo)

    def test_generation_is_byte_deterministic_and_ignores_json_key_order(self) -> None:
        core.refresh(self.repo)
        generated = self.repo / core.GENERATED_KOTLIN_PATH
        first = generated.read_bytes()

        self.manifest_path.write_text(
            json.dumps(self.original_manifest, ensure_ascii=False, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        core.refresh(self.repo)
        second = generated.read_bytes()
        core.refresh(self.repo)

        self.assertEqual(first, second)
        self.assertEqual(second, generated.read_bytes())
        self.assertEqual(0o644, stat.S_IMODE(generated.stat().st_mode))
        core.check(self.repo)

    def test_check_reports_drift_without_rewriting_it(self) -> None:
        core.refresh(self.repo)
        generated = self.repo / core.GENERATED_KOTLIN_PATH
        drifted = generated.read_bytes() + b"// local drift\n"
        generated.write_bytes(drifted)

        with self.assertRaisesRegex(core.ContractError, "Kotlin limits drifted"):
            core.check(self.repo)

        self.assertEqual(drifted, generated.read_bytes())

    def test_check_does_not_create_a_missing_destination_tree(self) -> None:
        before = self._file_snapshot(self.repo)

        with self.assertRaisesRegex(core.ContractError, "missing a directory"):
            core.check(self.repo)

        self.assertEqual(before, self._file_snapshot(self.repo))
        self.assertFalse((self.repo / core.GENERATED_KOTLIN_PATH.parent).exists())

    def test_escape_and_symlink_destinations_are_rejected_without_external_writes(self) -> None:
        outside_escape = self.temp_root / "outside-escape.kt"
        with self.assertRaisesRegex(core.ContractError, "escapes the repository"):
            core.refresh(
                self.repo,
                destination_path=PurePosixPath("../outside-escape.kt"),
            )
        self.assertFalse(outside_escape.exists())

        external = self.temp_root / "outside-leaf.kt"
        external.write_bytes(b"external leaf must survive\n")
        generated = self.repo / core.GENERATED_KOTLIN_PATH
        generated.parent.mkdir(parents=True)
        generated.symlink_to(external)
        before = external.read_bytes()

        for action in (core.refresh, core.check):
            with self.subTest(action=action.__name__):
                with self.assertRaisesRegex(core.ContractError, "symlink"):
                    action(self.repo)
                self.assertEqual(before, external.read_bytes())

    def test_static_symlinked_ancestor_is_rejected_without_external_writes(self) -> None:
        external = self.temp_root / "outside-static"
        external.mkdir()
        (external / "AnkiLimitsV1.kt").write_bytes(b"external output must survive\n")
        before = self._file_snapshot(external)

        generated_parent = self.repo / core.GENERATED_KOTLIN_PATH.parent
        generated_parent.parent.mkdir(parents=True)
        generated_parent.symlink_to(external, target_is_directory=True)

        for action in (core.refresh, core.check):
            with self.subTest(action=action.__name__):
                with self.assertRaisesRegex(core.ContractError, "traverses a symlink"):
                    action(self.repo)
                self.assertEqual(before, self._file_snapshot(external))

    def test_leaf_swap_to_fifo_is_rejected_without_blocking(self) -> None:
        real_open = core.os.open
        swapped = False

        def swap_before_leaf_open(path: object, flags: int, *args: object, **kwargs: object) -> int:
            nonlocal swapped
            if path == core.LIMITS_MANIFEST_PATH.name and not swapped:
                self.manifest_path.unlink()
                os.mkfifo(self.manifest_path)
                swapped = True
            return real_open(path, flags, *args, **kwargs)

        with mock.patch.object(core.os, "open", side_effect=swap_before_leaf_open):
            with self.assertRaisesRegex(core.ContractError, "not a regular file"):
                core.load_manifest(self.repo)

        self.assertTrue(swapped)

    def test_refresh_detects_ancestor_swap_without_redirecting_atomic_write(self) -> None:
        core.refresh(self.repo)
        generated_parent = self.repo / core.GENERATED_KOTLIN_PATH.parent
        detached_parent = self.temp_root / "detached-refresh"
        external = self.temp_root / "outside-refresh-race"
        external.mkdir()
        external_output = external / core.GENERATED_KOTLIN_PATH.name
        external_output.write_bytes(b"external output must survive refresh race\n")
        before = self._file_snapshot(external)
        real_open_parent = core._open_parent_directory
        swapped = False

        def open_then_swap(*args: object, **kwargs: object) -> object:
            nonlocal swapped
            result = real_open_parent(*args, **kwargs)
            relative_path = args[1]
            if relative_path == core.GENERATED_KOTLIN_PATH and not swapped:
                generated_parent.rename(detached_parent)
                generated_parent.symlink_to(external, target_is_directory=True)
                swapped = True
            return result

        with mock.patch.object(core, "_open_parent_directory", side_effect=open_then_swap):
            with self.assertRaisesRegex(core.ContractError, "traverses a symlink"):
                core.refresh(self.repo)

        self.assertTrue(swapped)
        self.assertEqual(before, self._file_snapshot(external))

    def test_check_detects_ancestor_swap_without_reading_external_output(self) -> None:
        core.refresh(self.repo)
        generated_parent = self.repo / core.GENERATED_KOTLIN_PATH.parent
        detached_parent = self.temp_root / "detached-check"
        external = self.temp_root / "outside-check-race"
        external.mkdir()
        external_output = external / core.GENERATED_KOTLIN_PATH.name
        external_output.write_bytes(b"not valid generated Kotlin\n")
        before = self._file_snapshot(external)
        real_open_parent = core._open_parent_directory
        swapped = False

        def open_then_swap(*args: object, **kwargs: object) -> object:
            nonlocal swapped
            result = real_open_parent(*args, **kwargs)
            relative_path = args[1]
            if relative_path == core.GENERATED_KOTLIN_PATH and not swapped:
                generated_parent.rename(detached_parent)
                generated_parent.symlink_to(external, target_is_directory=True)
                swapped = True
            return result

        with mock.patch.object(core, "_open_parent_directory", side_effect=open_then_swap):
            with self.assertRaisesRegex(core.ContractError, "traverses a symlink"):
                core.check(self.repo)

        self.assertTrue(swapped)
        self.assertEqual(before, self._file_snapshot(external))


if __name__ == "__main__":
    unittest.main()
