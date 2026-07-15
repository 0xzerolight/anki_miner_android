# Android ffmpeg toolchain

`build.sh` produces standalone PIE `ffmpeg` and `ffprobe` executables for
`arm64-v8a` and `x86_64`, renamed to `libffmpeg.so` and `libffprobe.so` so the
Android package manager extracts them into `nativeLibraryDir` with execute
permission. The executables statically include FFmpeg's libraries, LAME, and
Opus; they retain only Android system-library dependencies.

All inputs are immutable in `sources.lock`. The wrapper verifies every archive
before extracting it, patches the pinned ffmpeg-android-maker build in a fresh
toolchain directory, disables network protocols and GPL libraries, and checks
both ELF ABI and 16 KiB segment alignment. It never installs SDK components or
accepts Android licenses.

```bash
# Verify an already-populated cache without network access.
tools/ffmpeg/verify-sources.sh .android-toolchain/downloads offline

# Build in .android-toolchain/build/ffmpeg.
tools/ffmpeg/build.sh

# Build, validate, then copy both ABIs into app/src/main/jniLibs.
tools/ffmpeg/build.sh --install
```

The pinned NDK must exist first. Until the SDK licenses have been accepted,
the build exits with that single actionable prerequisite. `--install` mutates
the application tree only after all four executables pass the native checks.

The source set is LGPL-compatible: FFmpeg 7.1.5, LAME 3.100, Opus 1.5.2, and
ffmpeg-android-maker v2.12 at commit
`69bc3f2968e5335fff43123a2bef6c54428144ce`. A public distribution will still
need the corresponding notices, source offer, and static-relink materials;
those release artifacts are intentionally outside the current technical port.
