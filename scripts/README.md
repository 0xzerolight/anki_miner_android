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

Run the full host gate without an emulator: toolchain and lock verification,
shell syntax + ShellCheck, the host Python suites, `ruff`/`black`, the JVM unit
tests, Android Lint, the emulator-flavor app and AndroidTest APK builds, and
native inspection of the resulting APK.

```bash
source scripts/android-env.sh
scripts/health.sh
```

`health.sh` builds the instrumentation APK but never executes it, and it refuses
to start while any emulator is running. It is a superset of the CI "Secretless
host checks" job.

## Emulator lanes

Three pinned AVDs, one per lane. Each lane owns a fixed AVD, console port and
ADB serial; `emulator.sh` rejects `-avd`/`@name`/`-port` passthrough so a lane
cannot be aimed somewhere else.

| Lane | AVD | API | Page size | Serial |
|------|-----|-----|-----------|--------|
| `api26` | `anki_miner_api26` | 26 | 4 KiB | `emulator-5558` |
| `4k` (default) | `anki_miner_api36` | 36 | 4 KiB | `emulator-5554` |
| `16k` | `anki_miner_api36_ps16k` | 36 | 16 KiB | `emulator-5556` |

Boot a lane, then confirm the emulator that answered is the one you meant:

```bash
scripts/emulator.sh --lane 16k --test-session
scripts/verify-emulator-runtime.sh --lane 16k
```

`emulator.sh` selects headless mode without a display and software CPU/GPU
without `/dev/kvm`; `--window`, `--headless`, `--hardware`, `--software`,
`--wipe-data` and `--print-command` override that. `--test-session` wipes data
and disables snapshot load and save. `--page-size 4k|16k` is a backward
compatible alias for `--lane`. `verify-emulator-runtime.sh` fails unless exactly
one emulator is attached and its AVD name, API level, page size and (for
`api26`) build fingerprint all match the lane.

`emulator-lanes.sh` is the sourced lane table behind both and is never run
directly.

Build and put the app on a device in one command:

```bash
scripts/run-app.sh          # emulatorDebug (default)
scripts/run-app.sh release  # emulatorRelease, needs release signing
```

`run-app.sh` assembles first, then boots the `api26` AVD, installs with `-r` so
app data survives, and launches the app. Stop it with
`adb -s emulator-5558 emu kill`.

Gradle and emulators are mutually exclusive. `android-test-resources.sh` is
sourced by `health.sh`, `emulator.sh` and `run-app.sh`; it refuses to start
either process while the other runs, pins the shared Gradle arguments, and
refuses to boot an emulator with less than 6 GiB available memory or less than
1 GiB free swap.

## Instrumentation

Nothing local executes instrumentation. CI runs it on the `api26` lane through
`.github/scripts/run-api26-instrumentation.sh`, which sources
`scripts/instrumentation-result.sh` to validate the complete terminal contract
emitted by `am instrument -w -r`. That script pins `expected_test_count=177`
(179 discovered, minus the two external-UniDic fixture tests it excludes), so
adding or removing an instrumentation test requires editing that number by hand.

Those two need a full UniDic pushed to `/data/local/tmp` first — S1a and S1b
respectively, from a local UniDic `dicdir`:

```bash
ANDROID_SERIAL=emulator-5558 scripts/provision-tokenizer-test-unidic.sh --dicdir DIR
ANDROID_SERIAL=emulator-5558 scripts/provision-s1b-test-unidic.sh --dicdir DIR
```

## Verification helpers

Called by provisioning, `health.sh`, or the wheel/ffmpeg build tools; each can
also be run directly.

| Script | Purpose |
|--------|---------|
| `verify-android-toolchain.sh` | Wraps `verify_android_toolchain.py` with this repo's SDK root, AVD list and lock. |
| `verify_android_toolchain.py` | Compares installed SDK package revisions and AVD definitions against `android-sdk-packages.lock`. |
| `preflight_android_packages.py` | Fails on remote revision drift before `sdkmanager` mutates anything. |
| `install-android-sdk-packages.sh` | Preflights, then installs the locked SDK packages. Called by `provision-android.sh`. |
| `verify_chaquopy_build_python.py` | `describe` or `verify` the pinned build interpreter against `chaquopy-build-python.lock.json`. |
| `check-python-runtime.py` | Imports the whole Android Python runtime and asserts the expected version and distribution set. |
| `check-native-artifact.sh` | Wrapper for `check_native_artifacts.py`; `--help` lists the gate options. |
| `check_native_artifacts.py` | Recursive ELF/ABI/alignment gate over APKs, AABs, nested ZIPs and Chaquopy IMYs. |
| `check_native_elf.py` | Same ELF parser on a raw executable before packaging; used by `tools/ffmpeg/build.sh`. |
| `check_runtime_artifact.py` | Audits packaged Chaquopy requirements against the verified wheel manifests. |
| `android-sdk-packages.lock` | Pinned SDK package paths, revisions and `package.xml` locations. |
| `chaquopy-build-python.lock.json` | Pinned build-interpreter archive, hashes and install layout. |

`scripts/tests/` holds the host unittest suite for these scripts;
`health.sh` runs it with `python3.13 -m unittest discover`.

## Build the release APK

The Python tokenizer/runtime wheels are vendored under `app/wheels/` and the
ffmpeg/ffprobe executables under `app/src/main/jniLibs/`, so a normal Gradle
build produces a working APK. Configure a local signing key first (see the
repository root `README.md`), then:

```bash
source scripts/android-env.sh
source_commit="$(git rev-parse HEAD)"
# Signed arm64 release APK.
./gradlew -PankiMinerSourceCommit="$source_commit" :app:assembleDeviceRelease
# x86_64 with the same release code and R8, for local testing.
./gradlew -PankiMinerSourceCommit="$source_commit" :app:assembleEmulatorRelease
```

The signed APK is at `app/build/outputs/apk/device/release/`. Publish it on a
GitHub Release together with a `SHA256SUMS` and `NOTICE.md`. Release variants
fail before compilation unless `ankiMinerSourceCommit` is a full lowercase Git
SHA. Debug variants may use `development`.

## Regenerating the vendored wheels

The wheels are built once and committed. Rebuild them (e.g. on a version bump)
with `tools/runtime-wheels/build-runtime-wheels.sh` and
`tools/wheels/build-s1a-wheels.sh`, then copy the closure into `app/wheels/` and
update the two build-key literals in `app/build.gradle.kts`. Regenerate and
verify the committed provenance manifest:

```bash
python3.13 tools/wheels/vendored_wheel_manifest.py generate
python3.13 tools/wheels/vendored_wheel_manifest.py check
```

Every Gradle build runs the manifest check before `preBuild` and fails if a
wheel is added, removed, renamed, or changed without regenerating the manifest.
