from __future__ import annotations

import ast
import hashlib
import json
import subprocess
import tempfile
import tomllib
import unittest
from pathlib import Path

from engine_sync.core import (
    EngineSyncError,
    build_snapshot,
    check_destination,
    discover_source_repo,
    sync_destination,
)


PINNED_MEDIA_EXTRACTOR_BLOB = "357dea44ae92b47ad06e19d8692a863438fa3d62"
PINNED_AUDIO_TRACK_DETECTOR_BLOB = "f785f5b8706e1073f076149dbfb873472446d414"
REMOVED_WAV_TO_FLOAT32 = '''def wav_to_float32(path: Path) -> "tuple[Any, int, float]":
    """Read a mono 16-bit PCM WAV and return (samples, sample_rate, duration_s).

    The WAV produced by :meth:`MediaExtractorService.extract_full_audio` is
    always 16 kHz mono ``pcm_s16le`` (WAVE format tag 1), the only family
    Python's stdlib ``wave`` module can read. The int16 samples are scaled to
    float32 in ``[-1.0, 1.0]`` — Whisper's expected input — by dividing by
    32768.

    Memory note: the whole track is loaded at once (~115 MB/hour at 16 kHz
    float32). Fine for episodes; a multi-hour film loads ~0.5 GB. If that
    becomes a problem, pass the WAV path straight to ``WhisperModel.transcribe``
    (it decodes internally) instead of materializing the float32 array here.

    Args:
        path: Path to the WAV file written by ``extract_full_audio``.

    Returns:
        A 3-tuple of:
        - ``samples``: 1-D float32 numpy array in ``[-1.0, 1.0]``.
        - ``sample_rate``: Frame rate in Hz (typically 16 000).
        - ``duration_s``: Duration in seconds (``nframes / framerate``).
    """
    import numpy as np  # noqa: PLC0415  (intentional function-local import — numpy is an [asr] extra)

    with wave.open(str(path), "rb") as wf:
        # Fail loudly on an unexpected layout rather than silently reinterpreting
        # float/stereo bytes as garbage int16. extract_full_audio always writes
        # mono pcm_s16le; a mismatch means a stale or foreign WAV reached us.
        if wf.getnchannels() != 1 or wf.getsampwidth() != 2 or wf.getcomptype() != "NONE":
            raise ValueError(
                "wav_to_float32 expects mono pcm_s16le; got "
                f"channels={wf.getnchannels()} sampwidth={wf.getsampwidth()} comptype={wf.getcomptype()}"
            )
        sample_rate = wf.getframerate()
        n_frames = wf.getnframes()
        raw = wf.readframes(n_frames)

    # int16 → float32 in [-1.0, 1.0]. astype already yields a writable, owned
    # array (np.frombuffer over immutable bytes is read-only), which
    # faster-whisper/ctranslate2 may require — no separate .copy() needed.
    samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    duration = n_frames / sample_rate
    return samples, sample_rate, duration


'''
PINNED_WAVE_DOC = """Python's stdlib ``wave`` module (which both the zero-frame guard below
        and :func:`wav_to_float32` rely on) cannot read tag-3 float WAVs and"""
ANDROID_WAVE_DOC = """Python's stdlib ``wave`` module (which the zero-frame guard below relies
        on) cannot read tag-3 float WAVs and"""
ANDROID_FD_IMPORT = "from anki_miner.utils.android_fd import inherited_fd_command\n"
ANDROID_FFMPEG_SPAWN = '''            with inherited_fd_command(cmd) as (child_cmd, pass_fds):
                proc = subprocess.Popen(
                    child_cmd,
                    stdin=subprocess.DEVNULL,  # detach from the TTY: a backgrounded ffmpeg reading
                    # the controlling terminal gets SIGTTIN-stopped and the extraction times out.
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    pass_fds=pass_fds,
                    **no_window_kwargs(),  # hide the Windows cmd.exe flash (Issue #79)
                )
'''
DESKTOP_FFMPEG_SPAWN = '''            proc = subprocess.Popen(
                cmd,
                stdin=subprocess.DEVNULL,  # detach from the TTY: a backgrounded ffmpeg reading
                # the controlling terminal gets SIGTTIN-stopped and the extraction times out.
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
                **no_window_kwargs(),  # hide the Windows cmd.exe flash (Issue #79)
            )
'''
ANDROID_FFPROBE_DOC = """    Android SAF procfs inputs are duplicated per child and explicitly inherited.
"""
ANDROID_FFPROBE_SPAWN = '''        with inherited_fd_command(cmd) as (child_cmd, pass_fds):
            proc = subprocess.run(
                child_cmd,
                capture_output=True,
                timeout=30,
                text=True,
                encoding="utf-8",
                errors="replace",
                pass_fds=pass_fds,
                **no_window_kwargs(),  # hide the Windows cmd.exe flash (Issue #79)
            )
'''
DESKTOP_FFPROBE_SPAWN = '''        proc = subprocess.run(
            cmd,
            capture_output=True,
            timeout=30,
            text=True,
            encoding="utf-8",
            errors="replace",
            **no_window_kwargs(),  # hide the Windows cmd.exe flash (Issue #79)
        )
'''


def _run(*args: str, cwd: Path) -> str:
    result = subprocess.run(args, cwd=cwd, check=True, capture_output=True, text=True)
    return result.stdout.strip()


class EngineSyncTests(unittest.TestCase):
    def setUp(self) -> None:
        self._temporary = tempfile.TemporaryDirectory()
        self.root = Path(self._temporary.name)

    def tearDown(self) -> None:
        self._temporary.cleanup()

    def _fixture(self, root_source: str | None = None) -> dict[str, Path]:
        repo = self.root / "desktop"
        (repo / "anki_miner").mkdir(parents=True)
        (repo / "anki_miner/__init__.py").write_text("", encoding="utf-8")
        (repo / "anki_miner/dep.py").write_text("VALUE = 1\n", encoding="utf-8")
        (repo / "anki_miner/root.py").write_text(
            root_source
            or (
                "from typing import TYPE_CHECKING\n"
                "from PyQt6.QtCore import QCoreApplication\n"
                "from anki_miner import dep\n"
                "if TYPE_CHECKING:\n"
                "    from anki_miner.services.youtube_fetcher import YouTubeFetcherService\n"
            ),
            encoding="utf-8",
        )
        (repo / "anki_miner/data.txt").write_text("asset\n", encoding="utf-8")
        (repo / "LICENSE").write_text("license\n", encoding="utf-8")
        _run("git", "init", "-q", cwd=repo)
        _run("git", "config", "user.email", "tests@example.invalid", cwd=repo)
        _run("git", "config", "user.name", "Engine Sync Tests", cwd=repo)
        _run("git", "add", ".", cwd=repo)
        _run("git", "commit", "-qm", "fixture", cwd=repo)
        revision = _run("git", "rev-parse", "HEAD", cwd=repo)

        tooling = self.root / "tooling"
        overlay = tooling / "overrides/PyQt6"
        overlay.mkdir(parents=True)
        (overlay / "__init__.py").write_text("", encoding="utf-8")
        (overlay / "QtCore.py").write_text(
            "class QCoreApplication: pass\n", encoding="utf-8"
        )
        lock = tooling / "engine.lock"
        lock.write_text(revision + "\n", encoding="ascii")
        composition = tooling / "composition.toml"
        composition.write_text(
            """format_version = 1
roots = ["anki_miner.root"]
assets = ["anki_miner/data.txt"]
protected_verbatim = ["anki_miner.root"]
allowed_external = []
allowed_stdlib = ["typing"]
local_only_imports = ["PyQt6"]
forbidden_imports = ["gtts", "mpv", "yt_dlp", "anki_miner.services.youtube_fetcher"]
overlay_allowlist = ["PyQt6/QtCore.py", "PyQt6/__init__.py"]
[[mapped_assets]]
source = "LICENSE"
destination = "anki_miner/LICENSE"
[[forbidden_type_checking_exceptions]]
importer = "anki_miner.root"
target = "anki_miner.services.youtube_fetcher"
""",
            encoding="utf-8",
        )
        return {
            "repo": repo,
            "lock": lock,
            "composition": composition,
            "overlays": tooling / "overrides",
        }

    def _snapshot(self, fixture: dict[str, Path]):
        return build_snapshot(
            source_repo=fixture["repo"],
            lock_path=fixture["lock"],
            composition_path=fixture["composition"],
            overlays_path=fixture["overlays"],
        )

    def _drop_type_checking_exception(self, fixture: dict[str, Path]) -> None:
        composition = fixture["composition"]
        composition.write_text(
            composition.read_text(encoding="utf-8").replace(
                '[[forbidden_type_checking_exceptions]]\nimporter = "anki_miner.root"\n'
                'target = "anki_miner.services.youtube_fetcher"\n',
                "",
            ),
            encoding="utf-8",
        )

    def test_closure_uses_overlay_and_records_exact_origins(self) -> None:
        snapshot = self._snapshot(self._fixture())

        self.assertEqual(
            snapshot.modules,
            (
                "PyQt6",
                "PyQt6.QtCore",
                "anki_miner",
                "anki_miner.dep",
                "anki_miner.root",
            ),
        )
        self.assertEqual(snapshot.files["PyQt6/QtCore.py"].origin, "overlay")
        self.assertEqual(snapshot.files["anki_miner/root.py"].origin, "desktop")
        self.assertEqual(snapshot.files["anki_miner/LICENSE"].source_path, "LICENSE")
        self.assertEqual(snapshot.files["anki_miner/LICENSE"].content, b"license\n")
        self.assertNotIn("anki_miner/services/youtube_fetcher.py", snapshot.files)

        manifest = json.loads(snapshot.manifest_bytes())
        self.assertEqual(manifest["engine_revision"], snapshot.revision)
        self.assertEqual(
            manifest["files"]["anki_miner/root.py"]["sha256"],
            hashlib.sha256(snapshot.files["anki_miner/root.py"].content).hexdigest(),
        )
        self.assertEqual(
            manifest["files"]["anki_miner/root.py"]["source"],
            "anki_miner/root.py",
        )

    def test_sync_check_detects_and_repairs_direct_edits(self) -> None:
        snapshot = self._snapshot(self._fixture())
        destination = self.root / "vendor"
        sync_destination(destination, snapshot)
        self.assertEqual(check_destination(destination, snapshot), ())

        (destination / "anki_miner/root.py").write_text("edited\n", encoding="utf-8")
        (destination / "anki_miner/unexpected.py").write_text("", encoding="utf-8")
        (destination / "anki_miner/__pycache__").mkdir()
        (destination / "anki_miner/__pycache__/root.cpython-313.pyc").write_bytes(
            b"cache"
        )
        (destination / "PyQt6/QtCore.py").unlink()
        self.assertEqual(
            check_destination(destination, snapshot),
            (
                "missing PyQt6/QtCore.py",
                "unexpected anki_miner/unexpected.py",
                "modified anki_miner/root.py",
            ),
        )

        sync_destination(destination, snapshot)
        self.assertEqual(check_destination(destination, snapshot), ())

    def test_runtime_forbidden_import_is_rejected_semantically(self) -> None:
        fixture = self._fixture("import gtts\n")
        with self.assertRaisesRegex(EngineSyncError, r"forbidden import gtts"):
            self._snapshot(fixture)

    def test_literal_dynamic_forbidden_import_is_rejected(self) -> None:
        fixture = self._fixture(
            "import importlib\n\n"
            "def load():\n"
            "    return importlib.import_module('gtts')\n"
        )
        fixture["composition"].write_text(
            fixture["composition"]
            .read_text(encoding="utf-8")
            .replace(
                'allowed_stdlib = ["typing"]',
                'allowed_stdlib = ["importlib", "typing"]',
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(EngineSyncError, r"forbidden import gtts"):
            self._snapshot(fixture)

    def test_missing_internal_import_does_not_fall_back_to_package(self) -> None:
        fixture = self._fixture("import anki_miner.does_not_exist\n")
        with self.assertRaisesRegex(
            EngineSyncError, r"unresolved import anki_miner.does_not_exist"
        ):
            self._snapshot(fixture)

    def test_missing_from_import_member_is_rejected(self) -> None:
        fixture = self._fixture("from anki_miner import missing\n")
        with self.assertRaisesRegex(
            EngineSyncError, r"unresolved internal import member anki_miner.missing"
        ):
            self._snapshot(fixture)

    def test_defined_from_import_member_is_accepted(self) -> None:
        fixture = self._fixture(
            "from PyQt6.QtCore import QCoreApplication\n"
            "from anki_miner.dep import VALUE\n"
        )
        self._drop_type_checking_exception(fixture)
        snapshot = self._snapshot(fixture)
        self.assertIn("anki_miner.dep", snapshot.modules)

    def test_non_literal_dynamic_import_is_rejected(self) -> None:
        fixture = self._fixture(
            "from importlib import import_module as load\n\n"
            "def dynamic(name):\n"
            "    return load(name)\n"
        )
        fixture["composition"].write_text(
            fixture["composition"]
            .read_text(encoding="utf-8")
            .replace(
                'allowed_stdlib = ["typing"]',
                'allowed_stdlib = ["importlib", "typing"]',
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(EngineSyncError, "dynamic import target must be"):
            self._snapshot(fixture)

    def test_unlisted_deferred_external_import_is_rejected(self) -> None:
        fixture = self._fixture("def load():\n    import numpy\n")
        with self.assertRaisesRegex(
            EngineSyncError, "unresolved deferred import numpy"
        ):
            self._snapshot(fixture)

    def test_protected_module_cannot_be_overlaid(self) -> None:
        fixture = self._fixture()
        protected = fixture["overlays"] / "anki_miner/root.py"
        protected.parent.mkdir(parents=True)
        protected.write_text("VALUE = 'forked'\n", encoding="utf-8")
        fixture["composition"].write_text(
            fixture["composition"]
            .read_text(encoding="utf-8")
            .replace(
                'overlay_allowlist = ["PyQt6/QtCore.py", "PyQt6/__init__.py"]',
                'overlay_allowlist = ["PyQt6/QtCore.py", "PyQt6/__init__.py", "anki_miner/root.py"]',
            ),
            encoding="utf-8",
        )
        with self.assertRaisesRegex(
            EngineSyncError, r"protected module cannot be overlaid"
        ):
            self._snapshot(fixture)

    def test_shadow_overlay_is_bound_to_the_locked_upstream_blob(self) -> None:
        fixture = self._fixture()
        upstream_path = "anki_miner/dep.py"
        overlay = fixture["overlays"] / upstream_path
        overlay.parent.mkdir(parents=True, exist_ok=True)
        overlay.write_text("VALUE = 2\n", encoding="utf-8")
        revision = fixture["lock"].read_text(encoding="ascii").strip()
        base_blob = _run(
            "git",
            "rev-parse",
            f"{revision}:{upstream_path}",
            cwd=fixture["repo"],
        )
        fixture["composition"].write_text(
            fixture["composition"]
            .read_text(encoding="utf-8")
            .replace(
                'overlay_allowlist = ["PyQt6/QtCore.py", "PyQt6/__init__.py"]',
                'overlay_allowlist = ["PyQt6/QtCore.py", "PyQt6/__init__.py", '
                f'"{upstream_path}"]\noverlay_base_blobs = {{ "{upstream_path}" = '
                f'"{base_blob}" }}',
            ),
            encoding="utf-8",
        )

        snapshot = self._snapshot(fixture)
        self.assertEqual(snapshot.files[upstream_path].content, b"VALUE = 2\n")

        (fixture["repo"] / upstream_path).write_text("VALUE = 3\n", encoding="utf-8")
        _run("git", "add", upstream_path, cwd=fixture["repo"])
        _run(
            "git", "commit", "-qm", "change upstream overlay base", cwd=fixture["repo"]
        )
        fixture["lock"].write_text(
            _run("git", "rev-parse", "HEAD", cwd=fixture["repo"]) + "\n",
            encoding="ascii",
        )

        with self.assertRaisesRegex(EngineSyncError, "overlay base changed"):
            self._snapshot(fixture)

    def test_shadow_overlay_requires_a_base_declaration(self) -> None:
        fixture = self._fixture()
        overlay = fixture["overlays"] / "anki_miner/dep.py"
        overlay.parent.mkdir(parents=True, exist_ok=True)
        overlay.write_text("VALUE = 2\n", encoding="utf-8")
        fixture["composition"].write_text(
            fixture["composition"]
            .read_text(encoding="utf-8")
            .replace(
                'overlay_allowlist = ["PyQt6/QtCore.py", "PyQt6/__init__.py"]',
                'overlay_allowlist = ["PyQt6/QtCore.py", "PyQt6/__init__.py", '
                '"anki_miner/dep.py"]',
            ),
            encoding="utf-8",
        )

        with self.assertRaisesRegex(
            EngineSyncError, "must declare their upstream base blob"
        ):
            self._snapshot(fixture)

    def test_pyqt_must_be_satisfied_by_the_overlay(self) -> None:
        fixture = self._fixture()
        (fixture["overlays"] / "PyQt6/QtCore.py").unlink()
        (fixture["overlays"] / "PyQt6/__init__.py").unlink()
        with self.assertRaisesRegex(
            EngineSyncError, r"allowlisted overlay files are missing"
        ):
            self._snapshot(fixture)

    def test_unallowlisted_and_unused_overlays_are_rejected(self) -> None:
        fixture = self._fixture()
        extra = fixture["overlays"] / "anki_miner/typo.py"
        extra.parent.mkdir(parents=True)
        extra.write_text("", encoding="utf-8")
        with self.assertRaisesRegex(
            EngineSyncError, "overlay files are not allowlisted"
        ):
            self._snapshot(fixture)

        extra.unlink()
        (fixture["repo"] / "anki_miner/root.py").write_text(
            "VALUE = 1\n", encoding="utf-8"
        )
        _run("git", "add", ".", cwd=fixture["repo"])
        _run("git", "commit", "-qm", "drop overlay import", cwd=fixture["repo"])
        fixture["lock"].write_text(
            _run("git", "rev-parse", "HEAD", cwd=fixture["repo"]) + "\n",
            encoding="ascii",
        )
        self._drop_type_checking_exception(fixture)
        with self.assertRaisesRegex(EngineSyncError, "allowlisted overlays are unused"):
            self._snapshot(fixture)

    def test_destination_and_manifest_symlinks_are_rejected(self) -> None:
        snapshot = self._snapshot(self._fixture())
        real_destination = self.root / "real-vendor"
        real_destination.mkdir()
        linked_destination = self.root / "linked-vendor"
        linked_destination.symlink_to(real_destination, target_is_directory=True)
        with self.assertRaisesRegex(
            EngineSyncError, "destination may not be a symlink"
        ):
            sync_destination(linked_destination, snapshot)

        manifest_target = self.root / "manifest-target"
        manifest_target.write_text("{}", encoding="utf-8")
        (real_destination / ".engine-sync-manifest.json").symlink_to(manifest_target)
        with self.assertRaisesRegex(EngineSyncError, "manifest may not be a symlink"):
            check_destination(real_destination, snapshot)

    def test_source_discovery_honors_environment_override(self) -> None:
        fixture = self._fixture()
        self.assertEqual(
            discover_source_repo(
                self.root, {"ANKI_MINER_DESKTOP_REPO": str(fixture["repo"])}
            ),
            fixture["repo"].resolve(),
        )

    def test_manifest_is_byte_deterministic(self) -> None:
        fixture = self._fixture()
        first = self._snapshot(fixture)
        second = self._snapshot(fixture)
        self.assertEqual(first.manifest_bytes(), second.manifest_bytes())
        self.assertEqual(first.files, second.files)

    def test_production_composition_keeps_known_words_import_as_a_root(self) -> None:
        project_root = Path(__file__).resolve().parents[3]
        composition = tomllib.loads(
            (project_root / "tools/engine-sync/composition.toml").read_text(
                encoding="utf-8"
            )
        )
        self.assertIn(
            "anki_miner.services.known_words_import",
            composition["roots"],
        )

        manifest = json.loads(
            (project_root / "app/src/main/python/.engine-sync-manifest.json").read_text(
                encoding="utf-8"
            )
        )
        path = "anki_miner/services/known_words_import.py"
        self.assertIn("anki_miner.services.known_words_import", manifest["modules"])
        self.assertEqual("desktop", manifest["files"][path]["origin"])

    def test_reading_image_limits_are_applied_before_every_decode(self) -> None:
        project_root = Path(__file__).resolve().parents[3]
        reading_root = (
            project_root / "app/src/main/python/anki_miner/services/reading"
        )
        decodes: list[tuple[Path, int]] = []
        application_lines: list[int] = []
        images_path = reading_root / "images.py"
        for path in sorted(reading_root.rglob("*.py")):
            parsed = ast.parse(path.read_text(encoding="utf-8"))
            for node in ast.walk(parsed):
                if not isinstance(node, ast.Call):
                    continue
                if (
                    isinstance(node.func, ast.Attribute)
                    and isinstance(node.func.value, ast.Name)
                    and node.func.value.id == "Image"
                    and node.func.attr == "open"
                ):
                    decodes.append((path, node.lineno))
            if path == images_path:
                application_lines = [
                    node.lineno
                    for node in parsed.body
                    if isinstance(node, ast.Expr)
                    and isinstance(node.value, ast.Call)
                    and isinstance(node.value.func, ast.Name)
                    and node.value.func.id == "apply_pil_image_limits"
                ]

        self.assertTrue(decodes)
        self.assertEqual({images_path}, {path for path, _ in decodes})
        self.assertEqual(1, len(application_lines))
        self.assertLess(application_lines[0], min(line for _, line in decodes))

    def test_media_extractor_override_has_only_reviewed_android_changes(self) -> None:
        project_root = Path(__file__).resolve().parents[3]
        override = (
            project_root
            / "tools/engine-sync/overrides/anki_miner/services/media_extractor.py"
        ).read_text(encoding="utf-8")
        parsed = ast.parse(override)
        imported_roots = {
            alias.name.partition(".")[0]
            for node in ast.walk(parsed)
            if isinstance(node, ast.Import)
            for alias in node.names
        } | {
            (node.module or "").partition(".")[0]
            for node in ast.walk(parsed)
            if isinstance(node, ast.ImportFrom)
        }
        functions = {
            node.name
            for node in ast.walk(parsed)
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        }
        self.assertNotIn("numpy", imported_roots)
        self.assertNotIn("wav_to_float32", functions)
        self.assertIn("wave", imported_roots)
        self.assertIn("extract_full_audio", functions)
        self.assertEqual(override.count(ANDROID_WAVE_DOC), 1)

        self.assertEqual(override.count(ANDROID_FD_IMPORT), 1)
        self.assertEqual(override.count(ANDROID_FFMPEG_SPAWN), 1)
        reconstructed = override.replace(ANDROID_FD_IMPORT, "")
        reconstructed = reconstructed.replace(
            ANDROID_FFMPEG_SPAWN, DESKTOP_FFMPEG_SPAWN
        )
        reconstructed = reconstructed.replace(ANDROID_WAVE_DOC, PINNED_WAVE_DOC)
        marker = 'def _kill_quietly(proc: "subprocess.Popen[str]") -> None:'
        self.assertEqual(reconstructed.count(marker), 1)
        reconstructed = reconstructed.replace(
            marker, REMOVED_WAV_TO_FLOAT32 + marker
        ).encode("utf-8")
        git_object = f"blob {len(reconstructed)}\0".encode() + reconstructed
        self.assertEqual(
            hashlib.sha1(git_object, usedforsecurity=False).hexdigest(),
            PINNED_MEDIA_EXTRACTOR_BLOB,
        )

    def test_audio_detector_override_has_only_fd_inheritance_change(self) -> None:
        project_root = Path(__file__).resolve().parents[3]
        override = (
            project_root
            / "tools/engine-sync/overrides/anki_miner/utils/audio_track_detector.py"
        ).read_text(encoding="utf-8")
        self.assertEqual(override.count(ANDROID_FD_IMPORT), 1)
        self.assertEqual(override.count(ANDROID_FFPROBE_DOC), 1)
        self.assertEqual(override.count(ANDROID_FFPROBE_SPAWN), 1)

        reconstructed = override.replace(ANDROID_FD_IMPORT, "")
        reconstructed = reconstructed.replace(ANDROID_FFPROBE_DOC, "")
        reconstructed = reconstructed.replace(
            ANDROID_FFPROBE_SPAWN, DESKTOP_FFPROBE_SPAWN
        ).encode("utf-8")
        git_object = f"blob {len(reconstructed)}\0".encode() + reconstructed
        self.assertEqual(
            hashlib.sha1(git_object, usedforsecurity=False).hexdigest(),
            PINNED_AUDIO_TRACK_DETECTOR_BLOB,
        )


if __name__ == "__main__":
    unittest.main()
