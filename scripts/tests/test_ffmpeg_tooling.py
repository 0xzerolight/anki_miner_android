from __future__ import annotations

import hashlib
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


class FfmpegToolingTests(unittest.TestCase):
    def test_source_lock_is_complete_and_immutable(self) -> None:
        rows: dict[str, tuple[str, str, str]] = {}
        for raw_line in (FFMPEG_ROOT / "sources.lock").read_text(
            encoding="utf-8"
        ).splitlines():
            if not raw_line or raw_line.startswith("#"):
                continue
            key, checksum, filename, url = raw_line.split()
            self.assertNotIn(key, rows)
            self.assertEqual(len(checksum), 64)
            int(checksum, 16)
            self.assertEqual(Path(filename).name, filename)
            self.assertTrue(url.startswith("https://"))
            rows[key] = (checksum, filename, url)

        self.assertEqual(rows.keys(), {"builder", "ffmpeg", "libmp3lame", "libopus"})
        self.assertIn("7.1.5", rows["ffmpeg"][1])
        self.assertIn("69bc3f2968e5335fff43123a2bef6c54428144ce", rows["builder"][1])

    def test_offline_verifier_accepts_exact_files_and_rejects_corruption(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cache = root / "cache"
            cache.mkdir()
            entries = {
                "builder": b"builder",
                "ffmpeg": b"ffmpeg",
                "libmp3lame": b"lame",
                "libopus": b"opus",
            }
            lines = []
            for key, content in entries.items():
                filename = f"{key}.tar.gz"
                (cache / filename).write_bytes(content)
                checksum = hashlib.sha256(content).hexdigest()
                lines.append(
                    f"{key} {checksum} {filename} https://example.invalid/{filename}"
                )
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
        configure = (FFMPEG_ROOT / "overrides/ffmpeg-build.sh").read_text(
            encoding="utf-8"
        )
        maker = (FFMPEG_ROOT / "overrides/ffmpeg-android-maker.sh").read_text(
            encoding="utf-8"
        )
        common = (FFMPEG_ROOT / "overrides/common-functions.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn("ANDROID_NDK_VERSION", build)
        self.assertIn("arm64-v8a,x86_64", build)
        self.assertIn("--enable-libmp3lame", build)
        self.assertIn("--enable-libopus", build)
        self.assertNotIn("sdkmanager", "\n".join((build, configure, maker, common)))
        self.assertNotIn("--enable-gpl", build)
        self.assertIn("--enable-static", configure)
        self.assertIn("--disable-shared", configure)
        self.assertIn("--disable-network", configure)
        self.assertGreaterEqual(configure.count("max-page-size=16384"), 2)
        self.assertIn("libffmpeg.so", maker)
        self.assertIn("libffprobe.so", maker)
        self.assertNotIn("curl", common)


if __name__ == "__main__":
    unittest.main()
