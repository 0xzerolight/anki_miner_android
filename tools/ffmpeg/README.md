# Android ffmpeg toolchain

`build.sh` produces standalone PIE `ffmpeg` and `ffprobe` executables for
`arm64-v8a` and `x86_64`, renamed to `libffmpeg.so` and `libffprobe.so` so the
Android package manager extracts them into `nativeLibraryDir` with execute
permission. The executables statically include FFmpeg's libraries, LAME, and
Opus; they retain only Android system-library dependencies.

All inputs are immutable in `sources.lock`. The wrapper verifies every archive
before extracting it, patches the pinned ffmpeg-android-maker build in a guarded
fresh toolchain directory, disables network protocols and GPL libraries, and
checks the generated feature configuration, dynamic dependencies, text
relocations, ELF ABI, and 16 KiB segment alignment. It never installs SDK
components or accepts Android licenses.

The locked inputs make recipe changes auditable, but outputs are not claimed to
be byte-reproducible across workspace locations: FFmpeg records absolute build,
sysroot, and compiler paths in its configuration string.

```bash
# Verify an already-populated cache without network access.
tools/ffmpeg/verify-sources.sh .android-toolchain/downloads offline

# Build in .android-toolchain/build/ffmpeg.
tools/ffmpeg/build.sh

# Build, validate, then copy both ABIs into app/src/main/jniLibs.
tools/ffmpeg/build.sh --install
```

The pinned NDK must exist first. Until the SDK licenses have been accepted,
the build exits with that single actionable prerequisite. `--install` validates
all four executables before replacing the staged `jniLibs` tree, preserving any
unrelated native libraries and rolling back a failed swap.

Screenshots are static JPEG by default and animated on request. The generated
configuration gate proves the Matroska and MP4 inputs, AV1 decode (dav1d),
JPEG, animated WebP (libwebp), MP3, Opus, WAV, and local file/pipe protocol
surfaces used by the engine and the S3 instrumented test.

The animated WebP encoder needs watching. FFmpeg detects it with
`check_pkg_config libwebpmux`, which is non-fatal: a libwebp that installs no
`libwebpmux.pc` leaves `CONFIG_LIBWEBP_ANIM_ENCODER` at 0 and configure still
succeeds, producing a green build whose animated path is silently missing.
libwebp 1.4.0 builds mux by default (the flag is `--disable-libwebpmux`), so the
upstream recipe needs no override — but `assert-ffmpeg-config.py` requires the
symbol so a future version that changes that default fails the build instead of
shipping.

dav1d builds with meson/ninja, so both must be on the host PATH
(`python3.13 -m pip install --user meson ninja` suffices). Its assembly is
per-ABI: arm64 keeps NEON (assembled by clang), x86/x86_64 build C-only —
consistent with the ffmpeg `--disable-x86asm` emulator stance — so the host
needs no nasm (`overrides/libdav1d-build.sh`).

The source set is LGPL/BSD-compatible: FFmpeg 7.1.5, LAME 3.100, Opus 1.5.2,
dav1d 1.5.0 (BSD-2-Clause), and ffmpeg-android-maker v2.12 at commit
`69bc3f2968e5335fff43123a2bef6c54428144ce`. The corresponding notices, source offer, and static-relink materials for a
public distribution are in `third_party/ffmpeg/` (the LGPL/BSD license texts and
`NOTICE.md`).

## Path-clean release binaries

FFmpeg bakes the absolute build, sysroot, and compiler paths into its
configuration string and its datadir into a separate string, so the committed
`jniLibs` executables are built under a **username-free** toolchain root to keep
them free of any maintainer path. Stage a neutral root holding the pinned NDK and
the `downloads/` cache — copy, not symlink, since a symlink can be
realpath-resolved back to the real location — then build against it:

```bash
ANKI_MINER_ANDROID_TOOLCHAIN_ROOT=/var/tmp/anki-miner-build tools/ffmpeg/build.sh --install
```

That root needs both the pinned NDK and the pinned CMake under its own `sdk/`,
because libaom is a CMake build and `ANDROID_CMAKE_HOME` is derived from the
toolchain root. Symlinks are enough — only the paths ffmpeg bakes into its
configure string have to be maintainer-free:

```bash
mkdir -p /var/tmp/anki-miner-build/sdk/cmake
ln -sfnT "$PWD/.android-toolchain/sdk/cmake/3.22.1" /var/tmp/anki-miner-build/sdk/cmake/3.22.1
```

Acceptance gate — the shipped ELFs must embed no maintainer path (prints nothing):

```bash
strings app/src/main/jniLibs/*/lib{ffmpeg,ffprobe}.so | grep -E '/home/|/Users/'
```
