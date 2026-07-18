# FFmpeg, LAME, and Opus notice

Anki Miner Android bundles standalone position-independent `ffmpeg` and `ffprobe`
command-line executables (shipped as `libffmpeg.so` and `libffprobe.so` in
`app/src/main/jniLibs/{arm64-v8a,x86_64}` so the package manager extracts them
with execute permission). They are invoked as separate subprocesses; no FFmpeg,
LAME, or Opus code is linked into the application process. Each executable
statically includes the FFmpeg libraries, LAME, and Opus, and retains only
Android system-library dependencies.

## Components and licenses

| Component | Version | License (text in `licenses/`) |
| --- | --- | --- |
| FFmpeg | 7.1.5 | LGPL-2.1-or-later — `ffmpeg-COPYING.LGPLv2.1`, `ffmpeg-LICENSE.md` |
| LAME (libmp3lame) | 3.100 | LGPL-2.0-or-later, GNU *Library* GPL v2 — `lame-COPYING` (`lame-LICENSE` is LAME's commercial-use FAQ, not the license text) |
| Opus (libopus) | 1.5.2 | BSD-3-Clause — `opus-COPYING`, `opus-AUTHORS` |

The build is configured LGPL, never GPL or non-free: `--enable-gpl`,
`--enable-nonfree`, and network protocols are disabled, and
`tools/ffmpeg/assert-ffmpeg-config.py` fails the build unless `CONFIG_GPL=0`,
`CONFIG_GPLV3=0`, and `CONFIG_NONFREE=0`. All three licenses are compatible with
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
