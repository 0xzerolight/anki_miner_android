# Android runtime wheels

This directory builds the Python dependency set for the Android app: the
tokenizer-neutral runtime (requests, pysubs2, Pillow, lxml and their native
libraries) and the language-engine families. It publishes locked pure-Python wheels
once and source-built native wheels for `arm64-v8a` and `x86_64`.

The build is fixed to Chaquopy CPython 3.12, Android API 26, NDK 28.2, and 16 KiB
ELF load alignment. UniDic, tokenizer packages, PyQt6, gTTS, and yt-dlp are outside
this artifact boundary. Fugashi is separately published under `tools/wheels`; it is
optional to this artifact, not to the app, and its wheel is vendored into both ABI
groups of `app/wheels/`.

Rust recipes need the pinned toolchain, provisioned once with rustup (the build
itself never calls rustup; `ANKI_MINER_RUSTUP_HOME` overrides `~/.rustup`):

```sh
rustup toolchain install 1.98.0 --profile minimal \
  --target aarch64-linux-android --target x86_64-linux-android
```

Run:

```sh
tools/runtime-wheels/build-runtime-wheels.sh
```

Each Rust source's `cargo vendor` tree is a hash-locked derived input: when it is
missing from the downloads directory, `fetch` regenerates it over the network with
`cargo vendor --locked` and requires the packed tarball to match the lock. The
builds themselves run offline.

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

`manifest.json` groups wheels into `common`, `arm64-v8a`, and `x86_64`, and lists under
`families` the exact wheels each family's wave vendors into `app/wheels`. Consumers must
select `common` plus exactly one ABI group and verify the recorded hashes. The adjacent
`attributions.json` is generated from license text found and hash-checked in the wheels.
`verify-publication` returns the validated recipe/build/platform identity and the exact
filename list for all three groups and every family.

Publication also holds every native to the rules in `scripts/check_native_artifacts.py`:
16 KiB `PT_LOAD` alignment, a SONAME only on `chaquopy/lib` libraries, `DT_NEEDED` within
Android, libpython3.12 and the publication, and on arm64 an ISA audit that rejects any
instruction beyond ARMv8.0 that no runtime check guards. `isa_audit` records the result.

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
