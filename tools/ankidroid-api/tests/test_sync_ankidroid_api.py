from __future__ import annotations

import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path, PurePosixPath
from unittest import mock

TOOL_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOL_ROOT))

from ankidroid_api_sync import core as sync_core  # noqa: E402
from ankidroid_api_sync.core import (  # noqa: E402
    GENERATED_LICENSE_ROOT,
    GENERATED_SOURCE_ROOT,
    MANIFEST_PATH,
    VENDORED_FILES,
    SyncError,
    UpstreamPin,
    _ManagedRepository,
    _remove_extraneous_outputs,
    check,
    check_upstream,
    refresh,
    verify_checkout,
)


class AnkiDroidApiSyncTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        root = Path(self.temporary_directory.name)
        self.checkout = root / "upstream"
        self.repo = root / "consumer"
        self.checkout.mkdir()
        self.repo.mkdir()

        self._git("init", "--quiet")
        self._git("config", "user.name", "Anki API sync test")
        self._git("config", "user.email", "sync-test@example.invalid")
        for index, entry in enumerate(VENDORED_FILES):
            source = self.checkout / entry.source_path
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_bytes(f"fixture {index}: {entry.source_path}\n".encode())
        self._git("add", ".")
        self._git("commit", "--quiet", "-m", "fixture")
        commit = self._git("rev-parse", "HEAD").stdout.strip()
        self._git("tag", "fixture-v1")
        self.pin = UpstreamPin(
            repository="https://example.invalid/upstream.git",
            tag="fixture-v1",
            commit=commit,
        )

    def _git(self, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["git", "-C", str(self.checkout), *args],
            check=check,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )

    @staticmethod
    def _file_snapshot(root: Path) -> dict[str, bytes]:
        return {path.relative_to(root).as_posix(): path.read_bytes() for path in root.rglob("*") if path.is_file()}

    def test_refresh_is_deterministic_and_manifest_check_is_offline(self) -> None:
        refresh(self.repo, self.checkout, self.pin)
        first_manifest = (self.repo / MANIFEST_PATH).read_bytes()

        refresh(self.repo, self.checkout, self.pin)

        self.assertEqual(first_manifest, (self.repo / MANIFEST_PATH).read_bytes())
        with mock.patch(
            "ankidroid_api_sync.core._run_git",
            side_effect=AssertionError("manifest-only check invoked git"),
        ):
            check(self.repo, self.pin)
        manifest = json.loads(first_manifest)
        self.assertEqual(len(VENDORED_FILES), len(manifest["files"]))
        self.assertEqual(self.pin.commit, manifest["component"]["commit"])
        self.assertEqual(
            [entry.source_path.as_posix() for entry in VENDORED_FILES],
            [entry["sourcePath"] for entry in manifest["files"]],
        )
        for entry in VENDORED_FILES:
            mode = (self.repo / entry.destination_path).stat().st_mode
            self.assertEqual(0, mode & (stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH))

    def test_verified_upstream_mode_proves_byte_equality(self) -> None:
        refresh(self.repo, self.checkout, self.pin)

        check_upstream(self.repo, self.checkout, self.pin)

    def test_verified_upstream_mode_detects_a_relabelled_generated_file(self) -> None:
        refresh(self.repo, self.checkout, self.pin)
        generated = self.repo / VENDORED_FILES[0].destination_path
        relabelled = generated.read_bytes() + b"locally relabelled\n"
        generated.write_bytes(relabelled)

        manifest_path = self.repo / MANIFEST_PATH
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["files"][0]["byteCount"] = len(relabelled)
        manifest["files"][0]["sha256"] = hashlib.sha256(relabelled).hexdigest()
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        check(self.repo, self.pin)

        with self.assertRaisesRegex(SyncError, "verified upstream bytes differ"):
            check_upstream(self.repo, self.checkout, self.pin)

    def test_wrong_commit_is_rejected(self) -> None:
        wrong_pin = UpstreamPin(
            repository=self.pin.repository,
            tag=self.pin.tag,
            commit="0" * 40,
        )

        with self.assertRaisesRegex(SyncError, "wrong upstream commit"):
            verify_checkout(self.checkout, wrong_pin)

    def test_dirty_source_is_rejected_for_tracked_and_untracked_changes(self) -> None:
        tracked = self.checkout / VENDORED_FILES[0].source_path
        original = tracked.read_bytes()
        tracked.write_bytes(original + b"dirty\n")
        with self.assertRaisesRegex(SyncError, "checkout is dirty"):
            verify_checkout(self.checkout, self.pin)
        tracked.write_bytes(original)

        untracked = self.checkout / "untracked.txt"
        untracked.write_text("dirty\n", encoding="utf-8")
        with self.assertRaisesRegex(SyncError, "checkout is dirty"):
            verify_checkout(self.checkout, self.pin)

    def test_assume_unchanged_source_is_rejected(self) -> None:
        victim = VENDORED_FILES[0].source_path.as_posix()
        self._git("update-index", "--assume-unchanged", victim)
        (self.checkout / victim).write_bytes(b"hidden dirty bytes\n")
        self.assertEqual("", self._git("status", "--porcelain=v1").stdout.strip())

        with self.assertRaisesRegex(SyncError, "Git index hides or alters"):
            verify_checkout(self.checkout, self.pin)

    def test_refresh_reads_immutable_blob_after_post_verification_mutation(
        self,
    ) -> None:
        entry = VENDORED_FILES[0]
        pinned_bytes = (self.checkout / entry.source_path).read_bytes()
        original_verify = sync_core.verify_checkout

        def verify_then_mutate(checkout: Path, pin: UpstreamPin) -> Path:
            verified = original_verify(checkout, pin)
            (verified / entry.source_path).write_bytes(b"changed after verification\n")
            return verified

        with mock.patch.object(sync_core, "verify_checkout", side_effect=verify_then_mutate):
            refresh(self.repo, self.checkout, self.pin)

        self.assertEqual(pinned_bytes, (self.repo / entry.destination_path).read_bytes())

    def test_commit_replacement_cannot_relabel_the_pinned_tree(self) -> None:
        entry = VENDORED_FILES[0]
        pinned_bytes = (self.checkout / entry.source_path).read_bytes()
        (self.checkout / entry.source_path).write_bytes(b"replacement commit bytes\n")
        self._git("add", ".")
        self._git("commit", "--quiet", "-m", "replacement commit")
        replacement_commit = self._git("rev-parse", "HEAD").stdout.strip()
        self._git("reset", "--hard", "--quiet", self.pin.commit)
        self._git("replace", self.pin.commit, replacement_commit)

        verified = verify_checkout(self.checkout, self.pin)
        source_bytes = sync_core._read_pinned_source_bytes(verified, self.pin)

        self.assertEqual(pinned_bytes, source_bytes[entry.source_path])

    def test_blob_replacement_cannot_relabel_pinned_source_bytes(self) -> None:
        entry = VENDORED_FILES[0]
        source = self.checkout / entry.source_path
        pinned_bytes = source.read_bytes()
        pinned_blob = self._git(
            "rev-parse",
            f"{self.pin.commit}:{entry.source_path.as_posix()}",
        ).stdout.strip()
        source.write_bytes(b"replacement blob bytes\n")
        replacement_blob = self._git("hash-object", "-w", str(source)).stdout.strip()
        source.write_bytes(pinned_bytes)
        self._git("replace", pinned_blob, replacement_blob)

        verified = verify_checkout(self.checkout, self.pin)
        source_bytes = sync_core._read_pinned_source_bytes(verified, self.pin)

        self.assertEqual(pinned_bytes, source_bytes[entry.source_path])

    def test_pinned_symlink_entry_is_rejected_before_refresh(self) -> None:
        victim = self.checkout / VENDORED_FILES[0].source_path
        victim.unlink()
        victim.symlink_to("symlink-target.kt")
        self._git("add", "-A")
        self._git("commit", "--quiet", "-m", "symlink source")
        commit = self._git("rev-parse", "HEAD").stdout.strip()
        self._git("tag", "fixture-symlink")
        pin = UpstreamPin(self.pin.repository, "fixture-symlink", commit)

        with self.assertRaisesRegex(SyncError, "100644 blob.*120000 blob"):
            verify_checkout(self.checkout, pin)

    def test_pinned_gitlink_entry_is_rejected_before_refresh(self) -> None:
        victim = VENDORED_FILES[0].source_path.as_posix()
        (self.checkout / victim).unlink()
        self._git("update-index", "--force-remove", "--", victim)
        self._git(
            "update-index",
            "--add",
            "--cacheinfo",
            f"160000,{self.pin.commit},{victim}",
        )
        self._git("commit", "--quiet", "-m", "gitlink source")
        commit = self._git("rev-parse", "HEAD").stdout.strip()
        self._git("tag", "fixture-gitlink")
        pin = UpstreamPin(self.pin.repository, "fixture-gitlink", commit)

        with self.assertRaisesRegex(SyncError, "100644 blob.*160000 commit"):
            verify_checkout(self.checkout, pin)

    def test_generated_drift_is_rejected(self) -> None:
        refresh(self.repo, self.checkout, self.pin)
        generated = self.repo / VENDORED_FILES[0].destination_path
        generated.write_bytes(generated.read_bytes() + b"drift\n")

        with self.assertRaisesRegex(SyncError, "byte count drifted"):
            check(self.repo, self.pin)

    def test_extraneous_generated_source_is_rejected_and_refresh_removes_it(
        self,
    ) -> None:
        refresh(self.repo, self.checkout, self.pin)
        extra = self.repo / GENERATED_SOURCE_ROOT / "com/ichi2/anki/provider/Implementation.kt"
        extra.parent.mkdir(parents=True)
        extra.write_text("implementation\n", encoding="utf-8")

        with self.assertRaisesRegex(SyncError, "extraneous files"):
            check(self.repo, self.pin)

        refresh(self.repo, self.checkout, self.pin)
        self.assertFalse(extra.exists())
        check(self.repo, self.pin)

    def test_extraneous_generated_license_file_is_rejected(self) -> None:
        refresh(self.repo, self.checkout, self.pin)
        extra = self.repo / GENERATED_LICENSE_ROOT / "EXTRA"
        extra.write_text("unexpected\n", encoding="utf-8")

        with self.assertRaisesRegex(SyncError, "extraneous files"):
            check(self.repo, self.pin)

    def test_manifest_metadata_cannot_expand_the_vendored_surface(self) -> None:
        refresh(self.repo, self.checkout, self.pin)
        manifest_path = self.repo / MANIFEST_PATH
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["files"][0]["destinationPath"] = "../outside.kt"
        manifest_path.write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )

        with self.assertRaisesRegex(SyncError, "does not match the pinned file set"):
            check(self.repo, self.pin)

    def test_oversized_manifest_is_rejected_before_json_parsing(self) -> None:
        refresh(self.repo, self.checkout, self.pin)
        (self.repo / MANIFEST_PATH).write_bytes(b" " * (128 * 1024 + 1))

        with self.assertRaisesRegex(SyncError, "manifest exceeds"):
            check(self.repo, self.pin)

    def test_fifo_manifest_is_rejected_without_blocking(self) -> None:
        manifest = self.repo / MANIFEST_PATH
        manifest.parent.mkdir(parents=True)
        os.mkfifo(manifest)

        with self.assertRaisesRegex(SyncError, "manifest is missing or not a regular file"):
            check(self.repo, self.pin)

    def test_regular_file_swapped_to_fifo_during_open_is_rejected(self) -> None:
        manifest = self.repo / MANIFEST_PATH
        manifest.parent.mkdir(parents=True)
        manifest.write_bytes(b"{}\n")
        original_open = sync_core.os.open
        swapped = False

        def open_after_swap(
            path: str | bytes | Path,
            flags: int,
            mode: int = 0o777,
            *,
            dir_fd: int | None = None,
        ) -> int:
            nonlocal swapped
            if path == MANIFEST_PATH.name and dir_fd is not None and not flags & os.O_DIRECTORY and not swapped:
                os.unlink(path, dir_fd=dir_fd)
                os.mkfifo(path, dir_fd=dir_fd)
                swapped = True
            return original_open(path, flags, mode, dir_fd=dir_fd)

        with mock.patch.object(sync_core.os, "open", side_effect=open_after_swap):
            with self.assertRaisesRegex(SyncError, "manifest is missing or not a regular file"):
                check(self.repo, self.pin)

        self.assertTrue(swapped)
        self.assertTrue(stat.S_ISFIFO(manifest.stat().st_mode))

    def test_symlink_in_generated_tree_is_rejected(self) -> None:
        refresh(self.repo, self.checkout, self.pin)
        link = self.repo / GENERATED_SOURCE_ROOT / "unexpected-link"
        link.symlink_to(self.repo / MANIFEST_PATH)

        with self.assertRaisesRegex(SyncError, "contains a symlink"):
            check(self.repo, self.pin)

    def test_refresh_rejects_symlinked_root_ancestor_without_external_mutation(
        self,
    ) -> None:
        refresh(self.repo, self.checkout, self.pin)
        external = Path(self.temporary_directory.name) / "external-refresh"
        external_source_root = external / GENERATED_SOURCE_ROOT.name
        external_source_root.mkdir(parents=True)
        sentinel = external_source_root / "outside-only.kt"
        sentinel.write_bytes(b"must survive refresh\n")
        expected_destination = external / VENDORED_FILES[0].destination_path.relative_to(GENERATED_SOURCE_ROOT.parent)
        expected_destination.parent.mkdir(parents=True, exist_ok=True)
        expected_destination.write_bytes(b"must not be overwritten\n")
        before = self._file_snapshot(external)

        ancestor = self.repo / GENERATED_SOURCE_ROOT.parent
        shutil.rmtree(ancestor)
        ancestor.symlink_to(external, target_is_directory=True)

        with self.assertRaisesRegex(SyncError, "managed output root traverses a symlink"):
            refresh(self.repo, self.checkout, self.pin)

        self.assertEqual(before, self._file_snapshot(external))

    def test_managed_repository_rejects_real_root_replacement_before_enter(
        self,
    ) -> None:
        destination = VENDORED_FILES[0].destination_path
        external = Path(self.temporary_directory.name) / "external-root-replacement"
        external_destination = external / destination
        external_destination.parent.mkdir(parents=True)
        external_destination.write_bytes(b"external sentinel\n")
        managed = _ManagedRepository(self.repo)
        original_repo = Path(self.temporary_directory.name) / "original-consumer"
        self.repo.rename(original_repo)
        external.rename(self.repo)
        external_before = self._file_snapshot(self.repo)

        with self.assertRaisesRegex(
            SyncError,
            "repository root changed between construction and opening",
        ):
            with managed as repository:
                repository.atomic_write(destination, b"must not be written\n")

        self.assertEqual(external_before, self._file_snapshot(self.repo))
        self.assertFalse((original_repo / destination).exists())

    def test_managed_repository_rejects_real_ancestor_swap_before_enter(self) -> None:
        base = Path(self.temporary_directory.name)
        ancestor = base / "managed-parent"
        managed_root = ancestor / "consumer"
        managed_root.mkdir(parents=True)
        replacement_ancestor = base / "replacement-parent"
        replacement_root = replacement_ancestor / "consumer"
        destination = VENDORED_FILES[0].destination_path
        replacement_destination = replacement_root / destination
        replacement_destination.parent.mkdir(parents=True)
        replacement_destination.write_bytes(b"replacement sentinel\n")
        managed = _ManagedRepository(managed_root)
        original_ancestor = base / "original-managed-parent"
        ancestor.rename(original_ancestor)
        replacement_ancestor.rename(ancestor)
        replacement_before = self._file_snapshot(managed_root)

        with self.assertRaisesRegex(
            SyncError,
            "repository root changed between construction and opening",
        ):
            with managed as repository:
                repository.atomic_write(destination, b"must not be written\n")

        self.assertEqual(replacement_before, self._file_snapshot(managed_root))
        self.assertFalse((original_ancestor / "consumer" / destination).exists())

    def test_managed_repository_rejects_reentrant_enter_without_fd_leak(self) -> None:
        managed = _ManagedRepository(self.repo)

        with managed:
            original_fd = managed.root_fd
            original_open = sync_core.os.open
            with mock.patch.object(
                sync_core.os,
                "open",
                wraps=original_open,
            ) as opened:
                with self.assertRaisesRegex(SyncError, "already open"):
                    managed.__enter__()
            opened.assert_not_called()
            self.assertEqual(original_fd, managed.root_fd)
            os.fstat(original_fd)

        with self.assertRaises(OSError):
            os.fstat(original_fd)

    def test_refresh_pins_root_before_upstream_verification_direct_swap(self) -> None:
        base = Path(self.temporary_directory.name)
        destination = VENDORED_FILES[0].destination_path
        replacement = base / "replacement-consumer-during-refresh"
        replacement_destination = replacement / destination
        replacement_destination.parent.mkdir(parents=True)
        replacement_destination.write_bytes(b"replacement sentinel\n")
        replacement_before = self._file_snapshot(replacement)
        original_repo = base / "original-consumer-during-refresh"
        original_verify = sync_core.verify_checkout
        swapped = False

        def verify_then_swap(checkout: Path, pin: UpstreamPin) -> Path:
            nonlocal swapped
            verified = original_verify(checkout, pin)
            self.repo.rename(original_repo)
            replacement.rename(self.repo)
            swapped = True
            return verified

        with mock.patch.object(
            sync_core,
            "verify_checkout",
            side_effect=verify_then_swap,
        ):
            with self.assertRaisesRegex(
                SyncError,
                "repository root changed between construction and opening",
            ):
                refresh(self.repo, self.checkout, self.pin)

        self.assertTrue(swapped)
        self.assertEqual(replacement_before, self._file_snapshot(self.repo))
        self.assertFalse((original_repo / destination).exists())

    def test_refresh_pins_root_before_upstream_verification_ancestor_swap(self) -> None:
        base = Path(self.temporary_directory.name)
        ancestor = base / "managed-parent-during-refresh"
        ancestor.mkdir()
        managed_root = ancestor / "consumer"
        self.repo.rename(managed_root)
        replacement_ancestor = base / "replacement-parent-during-refresh"
        replacement_root = replacement_ancestor / "consumer"
        destination = VENDORED_FILES[0].destination_path
        replacement_destination = replacement_root / destination
        replacement_destination.parent.mkdir(parents=True)
        replacement_destination.write_bytes(b"replacement sentinel\n")
        replacement_before = self._file_snapshot(replacement_root)
        original_ancestor = base / "original-managed-parent-during-refresh"
        original_verify = sync_core.verify_checkout
        swapped = False

        def verify_then_swap(checkout: Path, pin: UpstreamPin) -> Path:
            nonlocal swapped
            verified = original_verify(checkout, pin)
            ancestor.rename(original_ancestor)
            replacement_ancestor.rename(ancestor)
            swapped = True
            return verified

        with mock.patch.object(
            sync_core,
            "verify_checkout",
            side_effect=verify_then_swap,
        ):
            with self.assertRaisesRegex(
                SyncError,
                "repository root changed between construction and opening",
            ):
                refresh(managed_root, self.checkout, self.pin)

        self.assertTrue(swapped)
        self.assertEqual(replacement_before, self._file_snapshot(managed_root))
        self.assertFalse((original_ancestor / "consumer" / destination).exists())

    def test_check_upstream_pins_root_before_upstream_verification(self) -> None:
        refresh(self.repo, self.checkout, self.pin)
        base = Path(self.temporary_directory.name)
        replacement = base / "replacement-consumer-during-upstream-check"
        shutil.copytree(self.repo, replacement)
        replacement_before = self._file_snapshot(replacement)
        original_repo = base / "original-consumer-during-upstream-check"
        original_verify = sync_core.verify_checkout

        def verify_then_swap(checkout: Path, pin: UpstreamPin) -> Path:
            verified = original_verify(checkout, pin)
            self.repo.rename(original_repo)
            replacement.rename(self.repo)
            return verified

        with mock.patch.object(
            sync_core,
            "verify_checkout",
            side_effect=verify_then_swap,
        ):
            with self.assertRaisesRegex(
                SyncError,
                "repository root changed between construction and opening",
            ):
                check_upstream(self.repo, self.checkout, self.pin)

        self.assertEqual(replacement_before, self._file_snapshot(self.repo))

    def test_descriptor_relative_write_survives_ancestor_swap_without_escape(
        self,
    ) -> None:
        destination = VENDORED_FILES[0].destination_path
        (self.repo / destination.parent).mkdir(parents=True)
        external = Path(self.temporary_directory.name) / "external-write-race"
        external_destination = external / destination.relative_to(PurePosixPath("app"))
        external_destination.parent.mkdir(parents=True)
        external_destination.write_bytes(b"external sentinel\n")

        with _ManagedRepository(self.repo) as repository:
            original_open = repository._open_directory
            swapped = False

            def open_then_swap(*args: object, **kwargs: object) -> int | None:
                nonlocal swapped
                descriptor = original_open(*args, **kwargs)
                if not swapped:
                    app = self.repo / "app"
                    app.rename(self.repo / "app-before-write-race")
                    app.symlink_to(external, target_is_directory=True)
                    swapped = True
                return descriptor

            with mock.patch.object(repository, "_open_directory", side_effect=open_then_swap):
                repository.atomic_write(destination, b"descriptor-rooted write\n")

        self.assertEqual(b"external sentinel\n", external_destination.read_bytes())
        written = self.repo / "app-before-write-race" / destination.relative_to(PurePosixPath("app"))
        self.assertEqual(b"descriptor-rooted write\n", written.read_bytes())

    def test_descriptor_relative_read_survives_ancestor_swap_without_escape(
        self,
    ) -> None:
        destination = VENDORED_FILES[0].destination_path
        original = self.repo / destination
        original.parent.mkdir(parents=True)
        original.write_bytes(b"descriptor-rooted read\n")
        external = Path(self.temporary_directory.name) / "external-read-race"
        external_destination = external / destination.relative_to(PurePosixPath("app"))
        external_destination.parent.mkdir(parents=True)
        external_destination.write_bytes(b"external bytes\n")

        with _ManagedRepository(self.repo) as repository:
            original_open = repository._open_directory
            swapped = False

            def open_then_swap(*args: object, **kwargs: object) -> int | None:
                nonlocal swapped
                descriptor = original_open(*args, **kwargs)
                if not swapped:
                    app = self.repo / "app"
                    app.rename(self.repo / "app-before-read-race")
                    app.symlink_to(external, target_is_directory=True)
                    swapped = True
                return descriptor

            with mock.patch.object(repository, "_open_directory", side_effect=open_then_swap):
                actual = repository.read_regular(destination, label="generated file")

        self.assertEqual(b"descriptor-rooted read\n", actual)
        self.assertEqual(b"external bytes\n", external_destination.read_bytes())

    def test_descriptor_relative_delete_fails_closed_after_ancestor_swap(self) -> None:
        extra = self.repo / GENERATED_SOURCE_ROOT / "EXTRA"
        extra.parent.mkdir(parents=True)
        extra.write_bytes(b"in-repo extra\n")
        external = Path(self.temporary_directory.name) / "external-delete-race"
        external_extra = external / GENERATED_SOURCE_ROOT.relative_to(PurePosixPath("app")) / "EXTRA"
        external_extra.parent.mkdir(parents=True)
        external_extra.write_bytes(b"external sentinel\n")

        with _ManagedRepository(self.repo) as repository:
            original_scan = repository.scan_tree
            swapped = False

            def scan_then_swap(
                root: PurePosixPath,
            ) -> tuple[set[PurePosixPath], set[PurePosixPath]]:
                nonlocal swapped
                result = original_scan(root)
                if root == GENERATED_SOURCE_ROOT and not swapped:
                    app = self.repo / "app"
                    app.rename(self.repo / "app-before-delete-race")
                    app.symlink_to(external, target_is_directory=True)
                    swapped = True
                return result

            with mock.patch.object(repository, "scan_tree", side_effect=scan_then_swap):
                with self.assertRaisesRegex(SyncError, "traverses a symlink"):
                    _remove_extraneous_outputs(repository)

        self.assertEqual(b"external sentinel\n", external_extra.read_bytes())
        original_extra = (
            self.repo / "app-before-delete-race" / GENERATED_SOURCE_ROOT.relative_to(PurePosixPath("app")) / "EXTRA"
        )
        self.assertEqual(b"in-repo extra\n", original_extra.read_bytes())

    def test_refresh_rejects_checkout_nested_in_managed_output_without_mutation(
        self,
    ) -> None:
        nested = self.repo / GENERATED_SOURCE_ROOT / "verified-upstream"
        nested.parent.mkdir(parents=True)
        shutil.move(self.checkout, nested)
        self.checkout = nested
        sentinel = self.checkout / VENDORED_FILES[0].source_path
        before = sentinel.read_bytes()

        with self.assertRaisesRegex(SyncError, "intersects managed output"):
            refresh(self.repo, self.checkout, self.pin)

        self.assertEqual(before, sentinel.read_bytes())
        self.assertEqual(self.pin.commit, self._git("rev-parse", "HEAD").stdout.strip())

    def test_refresh_allows_checkout_in_repository_toolchain_cache(self) -> None:
        staged = self.repo / ".android-toolchain/sources/verified-upstream"
        staged.parent.mkdir(parents=True)
        shutil.move(self.checkout, staged)
        self.checkout = staged

        refresh(self.repo, self.checkout, self.pin)

        check(self.repo, self.pin)
        self.assertEqual(self.pin.commit, self._git("rev-parse", "HEAD").stdout.strip())
        self.assertTrue((self.checkout / VENDORED_FILES[0].source_path).is_file())

    def test_check_rejects_symlinked_manifest_parent_without_external_mutation(
        self,
    ) -> None:
        refresh(self.repo, self.checkout, self.pin)
        manifest_parent = self.repo / MANIFEST_PATH.parent
        external = Path(self.temporary_directory.name) / "external-check"
        shutil.copytree(manifest_parent, external)
        sentinel = external / "outside-only.txt"
        sentinel.write_bytes(b"must survive check\n")
        before = self._file_snapshot(external)

        shutil.rmtree(manifest_parent)
        manifest_parent.symlink_to(external, target_is_directory=True)

        with self.assertRaisesRegex(SyncError, "traverses a symlink"):
            check(self.repo, self.pin)

        self.assertEqual(before, self._file_snapshot(external))


if __name__ == "__main__":
    unittest.main()
