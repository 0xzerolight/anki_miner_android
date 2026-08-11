# Android runtime wheels

This directory builds the tokenizer-neutral Python dependency set for the Android app.
It publishes the six locked pure-Python wheels once, plus source-built Pillow, lxml,
and their native libraries for `arm64-v8a` and `x86_64`.

The build is fixed to Chaquopy CPython 3.12, Android API 26, NDK 28.2, and 16 KiB
ELF load alignment. UniDic, tokenizer packages, PyQt6, gTTS, and yt-dlp are outside
this artifact boundary. Fugashi is separately published under `tools/wheels`; it is
optional to this artifact, not to the app, and its wheel is vendored into both ABI
groups of `app/wheels/`.

Run:

```sh
tools/runtime-wheels/build-runtime-wheels.sh
```

The script fetches only hash-locked inputs, stages the Chaquopy builder with network
source discovery disabled, and performs two clean builds in different directories.
Publication succeeds only when every source-built wheel is byte-for-byte identical
between those builds.

Publications are immutable and shared by all worktrees. By default they live at
`.android-toolchain/runtime-wheels/runtime-wheels-<build-key>/`; set
`ANKI_MINER_RUNTIME_OUTPUT_ROOT` only when an alternate publication root is needed.
Each checkout has an ignored `tools/runtime-wheels/out/current` symlink which is
atomically moved to the fully verified shared publication. Removing that symlink does
not remove the publication.

The driver provisions the pinned build interpreter first, then holds a shared lock on
that interpreter and an exclusive runtime-publication lock through input fetching,
both clean builds, publication verification, and pointer activation. A second run with
the same key fully verifies and reuses the existing target without fetching or
building. If an exact target already exists but fails validation, the command stops;
it never replaces that directory or changes `out/current`. Failed private build roots
are retained for diagnosis, while a successful private build root is removed.

`manifest.json` groups wheels into `common`, `arm64-v8a`, and `x86_64`. Consumers must
select `common` plus exactly one ABI group and verify the recorded hashes. The adjacent
`attributions.json` is generated from license text found and hash-checked in the wheels.
`verify-publication` returns the validated recipe/build/platform identity and the exact
filename list for all three groups.

Useful focused commands:

```sh
python3.13 tools/runtime-wheels/runtime_wheels.py verify-inputs \
  --downloads "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/runtime-wheels/downloads" \
  --wheelhouse "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/runtime-wheels/host-wheels"
python3.13 tools/runtime-wheels/runtime_wheels.py verify-publication \
  --manifest "$(realpath tools/runtime-wheels/out/current/manifest.json)"
python3.13 -m unittest discover -s tools/runtime-wheels/tests -v
```

Both `scripts/health.sh` and the secretless CI job run this test suite.
