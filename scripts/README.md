# Local Android harness

The harness keeps its JDK, SDK, Gradle cache and AVD data under
`.android-toolchain/` in the primary checkout. Git worktrees share that
installation.

## Setup

Create the hash-locked host test environment, then run provisioning to download
the dedicated CPython 3.12 Chaquopy build interpreter, JDK and Android
command-line tools. Android provisioning stops before SDK packages until the
Android SDK license has already been accepted:

```bash
scripts/provision-host-tests.sh
scripts/provision-runtime-host-tests.sh
scripts/provision-android.sh
scripts/android-licenses.sh review
scripts/provision-android.sh
source scripts/android-env.sh
```

The build interpreter is a hash-pinned python-build-standalone installation at
`.android-toolchain/chaquopy-build-python`. It is selected explicitly through
`ANKI_MINER_CHAQUOPY_BUILD_PYTHON` and is never added to `PATH`; repository
tools, host tests and Unicode generation remain on Python 3.13. The interpreter
can be installed or repaired independently with:

```bash
scripts/provision-chaquopy-build-python.sh
```

Both shared installations use inter-process locks, so provisioning commands from
concurrent Git worktrees are serialized safely. The license review is
interactive: the script displays Google's text and prompts without supplying
answers.

## Checks

Run the full host gate (toolchain checks, Python suites, and the emulator Gradle
build + unit tests) without an emulator:

```bash
source scripts/android-env.sh
scripts/health.sh
```

## Build the release APK

The Python tokenizer/runtime wheels are vendored under `app/wheels/` and the
ffmpeg/ffprobe executables under `app/src/main/jniLibs/`, so a normal Gradle
build produces a working APK. Configure a local signing key first (see the
repository root `README.md`), then:

```bash
source scripts/android-env.sh
./gradlew :app:assembleDeviceRelease        # signed arm64 release APK
./gradlew :app:assembleEmulatorRelease      # x86_64, same release code + R8 (for local testing)
```

The signed APK is at `app/build/outputs/apk/device/release/`. Publish it on a
GitHub Release together with a `SHA256SUMS` and `NOTICE.md`.

## Regenerating the vendored wheels

The wheels are built once and committed. Rebuild them (e.g. on a version bump)
with `tools/runtime-wheels/build-runtime-wheels.sh` and
`tools/wheels/build-s1a-wheels.sh`, then copy the closure into `app/wheels/` and
update the two build-key literals in `app/build.gradle.kts`.
