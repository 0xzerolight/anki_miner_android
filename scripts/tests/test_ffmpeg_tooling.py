from __future__ import annotations

import hashlib
import importlib.util
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
FFMPEG_ROOT = REPO_ROOT / "tools/ffmpeg"


def _run_verify(lock: Path, cache: Path) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env["ANKI_MINER_FFMPEG_LOCK_FILE"] = str(lock)
    return subprocess.run(
        [str(FFMPEG_ROOT / "verify-sources.sh"), str(cache), "offline"],
        cwd=REPO_ROOT,
        env=env,
        capture_output=True,
        text=True,
        check=False,
    )


def _load_python_tool(filename: str):
    path = FFMPEG_ROOT / filename
    spec = importlib.util.spec_from_file_location(filename.replace("-", "_"), path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _write_executable(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    path.chmod(0o755)


class FfmpegToolingTests(unittest.TestCase):
    def test_source_lock_is_complete_and_immutable(self) -> None:
        rows: dict[str, tuple[str, str, str]] = {}
        for raw_line in (FFMPEG_ROOT / "sources.lock").read_text(encoding="utf-8").splitlines():
            if not raw_line or raw_line.startswith("#"):
                continue
            key, checksum, filename, url = raw_line.split()
            self.assertNotIn(key, rows)
            self.assertEqual(len(checksum), 64)
            int(checksum, 16)
            self.assertEqual(Path(filename).name, filename)
            self.assertTrue(url.startswith("https://"))
            rows[key] = (checksum, filename, url)

        self.assertEqual(
            rows.keys(),
            {"builder", "ffmpeg", "libaom", "libdav1d", "libmp3lame", "libopus", "libwebp"},
        )
        self.assertIn("7.1.5", rows["ffmpeg"][1])
        self.assertIn("1.5.0", rows["libdav1d"][1])
        self.assertIn("1.4.0", rows["libwebp"][1])
        self.assertIn("3.12.1", rows["libaom"][1])
        # Gitiles regenerates `+archive` tarballs per request, so that endpoint
        # cannot back a pinned hash.  See overrides/libaom-download.sh.
        self.assertNotIn("+archive", rows["libaom"][2])
        self.assertIn("69bc3f2968e5335fff43123a2bef6c54428144ce", rows["builder"][1])

    def test_offline_verifier_accepts_exact_files_and_rejects_corruption(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cache = root / "cache"
            cache.mkdir()
            # Must carry every key in verify-sources.sh's `required` array: the
            # verifier enforces that array against whatever lock it is pointed
            # at, so a new required source makes this synthetic lock exit 2.
            entries = {
                "builder": b"builder",
                "ffmpeg": b"ffmpeg",
                "libdav1d": b"dav1d",
                "libmp3lame": b"lame",
                "libopus": b"opus",
                "libwebp": b"webp",
                "libaom": b"aom",
            }
            lines = []
            for key, content in entries.items():
                filename = f"{key}.tar.gz"
                (cache / filename).write_bytes(content)
                checksum = hashlib.sha256(content).hexdigest()
                lines.append(f"{key} {checksum} {filename} https://example.invalid/{filename}")
            lock = root / "sources.lock"
            lock.write_text("\n".join(lines) + "\n", encoding="utf-8")

            accepted = _run_verify(lock, cache)
            self.assertEqual(accepted.returncode, 0, accepted.stderr)

            (cache / "ffmpeg.tar.gz").write_bytes(b"corrupt")
            rejected = _run_verify(lock, cache)
            self.assertNotEqual(rejected.returncode, 0)
            self.assertIn("hash mismatch", rejected.stderr)

    def test_builder_is_standalone_local_only_and_16k_aligned(self) -> None:
        build = (FFMPEG_ROOT / "build.sh").read_text(encoding="utf-8")
        configure = (FFMPEG_ROOT / "overrides/ffmpeg-build.sh").read_text(encoding="utf-8")
        maker = (FFMPEG_ROOT / "overrides/ffmpeg-android-maker.sh").read_text(encoding="utf-8")
        common = (FFMPEG_ROOT / "overrides/common-functions.sh").read_text(encoding="utf-8")

        self.assertIn("ANDROID_NDK_VERSION", build)
        self.assertIn("arm64-v8a,x86_64", build)
        self.assertIn("--enable-libmp3lame", build)
        self.assertIn("--enable-libopus", build)
        self.assertIn("--enable-libdav1d", build)
        self.assertIn("libdav1d-build.sh", build)
        self.assertIn("--enable-libwebp", build)
        self.assertIn("--enable-libaom", build)
        self.assertIn("libaom-download.sh", build)
        self.assertIn("libaom-build.sh", build)
        self.assertIn("cmake", build)
        self.assertNotIn("sdkmanager", "\n".join((build, configure, maker, common)))
        self.assertNotIn("--enable-gpl", build)
        self.assertIn("--enable-static", configure)
        self.assertIn("--disable-shared", configure)
        self.assertIn("--disable-devices", configure)
        self.assertIn("--enable-indev=lavfi", configure)
        self.assertIn("--disable-network", configure)
        self.assertIn("assert-ffmpeg-config.py", configure)
        self.assertGreaterEqual(configure.count("max-page-size=16384"), 2)
        self.assertIn("libffmpeg.so", maker)
        self.assertIn("libffprobe.so", maker)
        self.assertIn("verify-elf-dynamic.sh", maker)
        self.assertIn("prepare-build-root.py", build)
        self.assertIn("install-outputs.sh", build)
        self.assertLess(
            build.index("for abi in arm64-v8a x86_64; do"),
            build.index('if [[ "$INSTALL_OUTPUT" == true ]]'),
        )
        self.assertNotIn("curl", common)

    def test_build_root_guard_rejects_escape_and_symlink_without_deleting(self) -> None:
        guard = FFMPEG_ROOT / "prepare-build-root.py"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            allowed = root / "toolchain" / "build"
            outside = root / "outside"
            outside.mkdir()
            sentinel = outside / "keep"
            sentinel.write_text("owned", encoding="utf-8")
            allowed.mkdir(parents=True)

            escaped = subprocess.run(
                [
                    str(guard),
                    "--allowed-parent",
                    str(allowed),
                    "--build-root",
                    str(allowed / ".." / ".." / "outside"),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(2, escaped.returncode)
            self.assertTrue(sentinel.is_file())

            parent_itself = subprocess.run(
                [
                    str(guard),
                    "--allowed-parent",
                    str(allowed),
                    "--build-root",
                    str(allowed),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(2, parent_itself.returncode)
            self.assertTrue(allowed.is_dir())

            link = allowed / "linked"
            link.symlink_to(outside, target_is_directory=True)
            symlinked = subprocess.run(
                [
                    str(guard),
                    "--allowed-parent",
                    str(allowed),
                    "--build-root",
                    str(link / "ffmpeg"),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(2, symlinked.returncode)
            self.assertIn("symlink", symlinked.stderr)
            self.assertTrue(sentinel.is_file())

    def test_build_root_guard_only_recreates_safe_child(self) -> None:
        guard = FFMPEG_ROOT / "prepare-build-root.py"
        with tempfile.TemporaryDirectory() as directory:
            allowed = Path(directory) / "build"
            target = allowed / "ffmpeg"
            target.mkdir(parents=True)
            (target / "stale").write_text("old", encoding="utf-8")

            completed = subprocess.run(
                [
                    str(guard),
                    "--allowed-parent",
                    str(allowed),
                    "--build-root",
                    str(target),
                ],
                capture_output=True,
                text=True,
                check=False,
            )

            self.assertEqual(0, completed.returncode, completed.stderr)
            self.assertTrue(target.is_dir())
            self.assertEqual([], list(target.iterdir()))

    def test_generated_configuration_contract_covers_s3_and_policy(self) -> None:
        config_tool = _load_python_tool("assert-ffmpeg-config.py")
        enabled = config_tool.REQUIRED_ENABLED
        disabled = config_tool.REQUIRED_DISABLED
        self.assertTrue(
            {
                "CONFIG_MATROSKA_DEMUXER",
                "CONFIG_MJPEG_ENCODER",
                "CONFIG_IMAGE2_MUXER",
                "CONFIG_LIBMP3LAME_ENCODER",
                "CONFIG_LIBOPUS_ENCODER",
                "CONFIG_FILE_PROTOCOL",
                "CONFIG_PIPE_PROTOCOL",
                "CONFIG_LIBWEBP",
                "CONFIG_LIBWEBP_ENCODER",
                "CONFIG_LIBWEBP_ANIM_ENCODER",
                "CONFIG_WEBP_MUXER",
                "CONFIG_LIBAOM",
                "CONFIG_LIBAOM_AV1_ENCODER",
                "CONFIG_AVIF_MUXER",
            }.issubset(enabled)
        )
        self.assertTrue(
            {
                "CONFIG_NETWORK",
                "CONFIG_GPL",
                "CONFIG_GPLV3",
                "CONFIG_NONFREE",
                "CONFIG_HTTP_PROTOCOL",
                "CONFIG_TCP_PROTOCOL",
                "CONFIG_ANDROID_CAMERA_INDEV",
                "CONFIG_FBDEV_INDEV",
                "CONFIG_FBDEV_OUTDEV",
                "CONFIG_V4L2_INDEV",
                "CONFIG_V4L2_OUTDEV",
            }.issubset(disabled)
        )

        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "config.h"
            components = Path(directory) / "config_components.h"
            lines = [*(f"#define {key} 1" for key in enabled)]
            lines.extend(f"#define {key} 0" for key in disabled)
            config.write_text("\n".join(lines) + "\n", encoding="utf-8")
            components.write_text("", encoding="utf-8")
            config_tool.assert_configuration(config, components)

            components.unlink()
            with self.assertRaisesRegex(
                config_tool.ConfigurationError,
                "generated config is missing",
            ):
                config_tool.assert_configuration(config, components)
            components.write_text("", encoding="utf-8")

            config.write_text(
                "\n".join(line for line in lines if line != "#define CONFIG_PIPE_PROTOCOL 1") + "\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                config_tool.ConfigurationError,
                "CONFIG_PIPE_PROTOCOL=1 required, found None",
            ):
                config_tool.assert_configuration(config, components)
            config.write_text("\n".join(lines) + "\n", encoding="utf-8")

            for device in ("CONFIG_ALSA_INDEV", "CONFIG_PULSE_OUTDEV"):
                with self.subTest(unexpected_device=device):
                    components.write_text(
                        f"#define {device} 1\n",
                        encoding="utf-8",
                    )
                    with self.assertRaisesRegex(
                        config_tool.ConfigurationError,
                        f"unexpected enabled devices: {device}",
                    ):
                        config_tool.assert_configuration(config, components)
            components.write_text("", encoding="utf-8")

            # FFmpeg 7.1.5 emits some identical component/library defines more
            # than once (for example CONFIG_LIBSMBCLIENT). Identical C macro
            # redefinitions are valid; only a disagreement is ambiguous.
            config.write_text(
                config.read_text(encoding="utf-8")
                + "#define CONFIG_LIBSMBCLIENT 0\n"
                + "#define CONFIG_LIBSMBCLIENT 0\n",
                encoding="utf-8",
            )
            config_tool.assert_configuration(config, components)

            components.write_text(
                "#define CONFIG_LIBSMBCLIENT 1\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                config_tool.ConfigurationError,
                "conflicting duplicate CONFIG_LIBSMBCLIENT",
            ):
                config_tool.assert_configuration(config, components)

            components.write_text("", encoding="utf-8")
            config.write_text(
                "\n".join(lines).replace(
                    "#define CONFIG_MATROSKA_DEMUXER 1",
                    "#define CONFIG_MATROSKA_DEMUXER 0",
                )
                + "\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(
                config_tool.ConfigurationError,
                "CONFIG_MATROSKA_DEMUXER=1 required",
            ):
                config_tool.assert_configuration(config, components)

    def test_dynamic_checker_fails_closed_on_readelf_error_textrel_and_dependency(self) -> None:
        checker = FFMPEG_ROOT / "verify-elf-dynamic.sh"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            elf = root / "libffmpeg.so"
            elf.write_bytes(b"ELF fixture")
            fake = root / "llvm-readelf"

            cases = (
                ("#!/bin/sh\necho readelf-broke >&2\nexit 9\n", "llvm-readelf failed"),
                (
                    "#!/bin/sh\necho ' 0x0 (TEXTREL) 0x0'\n" "echo ' 0x1 (NEEDED) Shared library: [libc.so]'\n",
                    "text relocations",
                ),
                (
                    "#!/bin/sh\necho ' 0x1 (NEEDED) Shared library: [libevil.so]'\n",
                    "non-system dependency",
                ),
            )
            for body, message in cases:
                with self.subTest(message=message):
                    _write_executable(fake, body)
                    completed = subprocess.run(
                        [str(checker), str(fake), str(elf)],
                        capture_output=True,
                        text=True,
                        check=False,
                    )
                    self.assertNotEqual(0, completed.returncode)
                    self.assertIn(message, completed.stderr)

            _write_executable(
                fake,
                "#!/bin/sh\n"
                "echo ' 0x1 (NEEDED) Shared library: [libc.so]'\n"
                "echo ' 0x1 (NEEDED) Shared library: [libm.so]'\n",
            )
            accepted = subprocess.run(
                [str(checker), str(fake), str(elf)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, accepted.returncode, accepted.stderr)

    def test_install_validates_all_four_before_transactional_directory_swap(self) -> None:
        installer = FFMPEG_ROOT / "install-outputs.sh"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "output"
            destination = root / "jniLibs"
            (destination / "arm64-v8a").mkdir(parents=True)
            preserved = destination / "arm64-v8a" / "libmecab.so"
            preserved.write_bytes(b"mecab")
            for abi in ("arm64-v8a", "x86_64"):
                (source / abi).mkdir(parents=True)
                for name in ("libffmpeg.so", "libffprobe.so"):
                    artifact = source / abi / name
                    artifact.write_bytes(f"{abi}:{name}".encode())
                    artifact.chmod(0o755)

            missing = source / "x86_64" / "libffprobe.so"
            missing.unlink()
            before = {
                path.relative_to(destination): path.read_bytes() for path in destination.rglob("*") if path.is_file()
            }
            rejected = subprocess.run(
                [str(installer), str(source), str(destination)],
                capture_output=True,
                text=True,
                check=False,
            )
            after = {
                path.relative_to(destination): path.read_bytes() for path in destination.rglob("*") if path.is_file()
            }
            self.assertNotEqual(0, rejected.returncode)
            self.assertEqual(before, after)

            missing.write_bytes(b"x86_64:libffprobe.so")
            missing.chmod(0o755)
            abi_directory = destination / "x86_64"
            abi_directory.symlink_to(root / "outside-abi", target_is_directory=True)
            symlinked = subprocess.run(
                [str(installer), str(source), str(destination)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertNotEqual(0, symlinked.returncode)
            self.assertIn("must not be a symlink", symlinked.stderr)
            abi_directory.unlink()

            accepted = subprocess.run(
                [str(installer), str(source), str(destination)],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, accepted.returncode, accepted.stderr)
            self.assertEqual(b"mecab", preserved.read_bytes())
            for abi in ("arm64-v8a", "x86_64"):
                for name in ("libffmpeg.so", "libffprobe.so"):
                    self.assertEqual(
                        f"{abi}:{name}".encode(),
                        (destination / abi / name).read_bytes(),
                    )

    def test_probe_profile_requires_a_decode_only_ffprobe(self) -> None:
        # The second configure pass exists to keep libaom and libwebp — over 6 MB —
        # out of a binary that only reports stream metadata. The guard has to fail
        # on any surviving encode component, not just the ones named.
        config_tool = _load_python_tool("assert-ffmpeg-config.py")
        enabled = config_tool.PROBE_REQUIRED_ENABLED
        disabled = config_tool.PROBE_REQUIRED_DISABLED

        self.assertTrue({"CONFIG_FFPROBE", "CONFIG_MATROSKA_DEMUXER"}.issubset(enabled))
        self.assertTrue({"CONFIG_FFMPEG", "CONFIG_LIBAOM", "CONFIG_LIBWEBP"}.issubset(disabled))
        # ffprobe must keep the read surface, or a container the app was handed
        # stops probing.
        self.assertFalse(any(key.endswith("_DEMUXER") for key in disabled))

        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "config.h"
            components = Path(directory) / "config_components.h"

            def write(extra: dict[str, int] | None = None) -> None:
                values = {key: 1 for key in enabled}
                values.update({key: 0 for key in disabled})
                values.update(extra or {})
                config.write_text(
                    "\n".join(f"#define {key} {value}" for key, value in values.items()) + "\n",
                    encoding="utf-8",
                )
                components.write_text("", encoding="utf-8")

            write()
            config_tool.assert_configuration(config, components, "probe")

            # A single leaked encoder fails, even one no required-disabled entry names.
            write({"CONFIG_PNG_ENCODER": 1})
            with self.assertRaises(config_tool.ConfigurationError):
                config_tool.assert_configuration(config, components, "probe")

            # The same config is rejected by the full profile, so the two cannot be confused.
            write()
            with self.assertRaises(config_tool.ConfigurationError):
                config_tool.assert_configuration(config, components)


if __name__ == "__main__":
    unittest.main()
