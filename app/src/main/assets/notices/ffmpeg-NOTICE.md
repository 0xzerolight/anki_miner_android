# FFmpeg, LAME, Opus, dav1d, libwebp, and libaom notice

Anki Miner Android bundles standalone position-independent `ffmpeg` and `ffprobe`
command-line executables (shipped as `libffmpeg.so` and `libffprobe.so` in
`app/src/main/jniLibs/{arm64-v8a,x86_64}` so the package manager extracts them
with execute permission). They are invoked as separate subprocesses; no FFmpeg,
LAME, Opus, dav1d, libwebp, or libaom code is linked into the application
process. Each executable statically includes the FFmpeg libraries, LAME, Opus,
dav1d, libwebp, and libaom, and retains only Android system-library
dependencies.

## Components and licenses

| Component | Version | License (text in `licenses/`) |
| --- | --- | --- |
| FFmpeg | 7.1.5 | LGPL-2.1-or-later — `ffmpeg-COPYING.LGPLv2.1`, `ffmpeg-LICENSE.md` |
| LAME (libmp3lame) | 3.100 | LGPL-2.0-or-later, GNU *Library* GPL v2 — `lame-COPYING` (`lame-LICENSE` is LAME's commercial-use FAQ, not the license text) |
| Opus (libopus) | 1.5.2 | BSD-3-Clause — `opus-COPYING`, `opus-AUTHORS` |
| dav1d (libdav1d) | 1.5.0 | BSD-2-Clause — `dav1d-COPYING` |
| libwebp | 1.4.0 | BSD-3-Clause — `libwebp-COPYING`, plus the additional patent grant in `libwebp-PATENTS` |
| libaom | 3.12.1 | BSD-2-Clause — `libaom-LICENSE`, plus the Alliance for Open Media patent license in `libaom-PATENTS` |

The build is configured LGPL, never GPL or non-free: `--enable-gpl`,
`--enable-nonfree`, and network protocols are disabled, and
`tools/ffmpeg/assert-ffmpeg-config.py` fails the build unless `CONFIG_GPL=0`,
`CONFIG_GPLV3=0`, and `CONFIG_NONFREE=0`. All six licenses are compatible with
the application's GPL-3.0-or-later license (the FFmpeg and LAME "or later"
clauses permit the combination).

## Corresponding source

The exact upstream sources, pinned by SHA-256 in `tools/ffmpeg/sources.lock`:

- FFmpeg 7.1.5 — <https://ffmpeg.org/releases/ffmpeg-7.1.5.tar.bz2>
  (`16d0e2b20c61f6fbe6381f9d4c1c74fa6946f09e931203479f94233bd83aab19`)
- LAME 3.100 — <https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz>
  (`ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e`)
- Opus 1.5.2 — <https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz>
  (`65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1`)
- dav1d 1.5.0 — <https://code.videolan.org/videolan/dav1d/-/archive/1.5.0/dav1d-1.5.0.tar.gz>
  (`78b15d9954b513ea92d27f39362535ded2243e1b0924fde39f37a31ebed5f76b`)
- libwebp 1.4.0 — <https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.4.0.tar.gz>
  (`61f873ec69e3be1b99535634340d5bde750b2e4447caa1db9f61be3fd49ab1e5`)
- libaom 3.12.1 — <https://storage.googleapis.com/aom-releases/libaom-3.12.1.tar.gz>
  (`9e9775180dec7dfd61a79e00bda3809d43891aee6b2e331ff7f26986207ea22e`)

The complete build recipe lives in `tools/ffmpeg/` (`build.sh`, the pinned
`sources.lock`, and the `overrides/` patches). The build harness itself is
ffmpeg-android-maker at commit `69bc3f2968e5335fff43123a2bef6c54428144ce` (not
distributed). Each release attaches these license texts and this notice and
identifies the immutable commit whose `tools/ffmpeg/` recipe reproduces the
binaries.

## Rebuild and relink

Because the executables are standalone programs invoked over a process boundary
(not libraries linked into the app), the LGPL relink obligation is met by the
ability to rebuild them from the pinned source: `tools/ffmpeg/build.sh --install`
verifies every archive against `sources.lock`, rebuilds both ABIs, re-checks the
ELF ABI / text relocations / 16 KiB segment alignment and the LGPL feature
configuration, and atomically replaces the committed `jniLibs` binaries. The
shipped binaries are built so their embedded configuration strings carry no
absolute build paths.
