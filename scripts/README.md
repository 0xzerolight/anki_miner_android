# Local Android harness

The harness keeps its JDK, SDK, Gradle cache and AVD data under
`.android-toolchain/` in the primary checkout. Git worktrees share that
installation.

## Setup

Create the hash-locked host test environment, then run provisioning to download
the JDK and Android command-line tools. Android provisioning stops before SDK
packages until the Android SDK license has already been accepted:

```bash
scripts/provision-host-tests.sh
scripts/provision-android.sh
scripts/android-licenses.sh review
scripts/provision-android.sh
source scripts/android-env.sh
```

The license review is interactive. The script displays Google's text and
prompts without supplying answers. Provisioning then verifies the recorded
license hash, checks stable-channel revisions against the package lock before
allowing sdkmanager to install or upgrade anything, and then verifies each
installed package and all three AVD configurations.

## Checks

Run all build-time checks without an emulator:

```bash
scripts/health.sh
```

Run the connected suite on the compatibility and page-size lanes:

```bash
UNIDIC_DIR=/absolute/path/to/the/golden-pinned/unidic/dicdir
scripts/run-emulator-tests.sh --lane api26 --unidic-dir "$UNIDIC_DIR"
scripts/run-emulator-tests.sh --lane 4k --unidic-dir "$UNIDIC_DIR"
scripts/run-emulator-tests.sh --lane 16k --unidic-dir "$UNIDIC_DIR"
```

`--page-size 4k|16k` remains an exact backward-compatible alias for the two
API 36 lanes. The AVDs have fixed identities and serials. Each connected run
also checks the runtime API level and page size; the API 26 lane additionally
checks its exact build fingerprint.

| Lane | AVD | Serial | API | Page size |
| --- | --- | --- | --- | --- |
| `api26` | `anki_miner_api26` | `emulator-5558` | 26 | 4096 |
| `4k` | `anki_miner_api36` | `emulator-5554` | 36 | 4096 |
| `16k` | `anki_miner_api36_ps16k` | `emulator-5556` | 36 | 16384 |

The separate arm64 S1b gate requires an already-running, explicitly named
target and a previously recorded image fingerprint:

```bash
scripts/run-s1b-arm64-tests.sh \
    --serial ARM64_SERIAL \
    --unidic-dir /absolute/path/to/golden-pinned/unidic/dicdir \
    --page-size 4k \
    --image-fingerprint EXPECTED_BUILD_FINGERPRINT
```

It temporarily enables the `deviceDebug` instrumentation variant and runs only
the production-JNI S1b class. It does not manage or select a target.

When KVM is unavailable, the launcher selects software CPU emulation and the
Swangle renderer. A first boot can take several minutes. Use `--keep` with the
test runner to leave an emulator running, or `scripts/emulator.sh --window` for
an interactive window. Test runs always wipe userdata and disable snapshot
load/save; an interactive launch remains persistent unless `--wipe-data` is
given explicitly.

Chaquopy reads ABI filters from product flavors. The normal Gradle variants
are therefore `emulatorDebug` (x86_64) and `deviceRelease` (arm64-v8a):

```bash
./gradlew assembleEmulatorDebug
./gradlew assembleDeviceRelease bundleDeviceRelease
./gradlew connectedEmulatorDebugAndroidTest
```

`scripts/check-native-artifact.sh` recursively opens APKs, AABs, ZIPs and
Chaquopy `.imy` files. It rejects unexpected ABIs, 4 KiB-aligned ELF load
segments, ffmpeg/ffprobe files which are not dynamically linked PIE command
executables, UniDic payloads or layouts in an APK/AAB base module, debug probe
leakage, invalid APK zip-alignment, and manifests which do not extract native
executables. A separate AAB asset-pack module is deliberately outside the
UniDic base-module check. Gradle resolves with committed locks
for every project configuration and strict SHA-256 dependency verification;
plugin artifacts and their transitives are covered by the same metadata.

S1a wheel publications are builder-identity scoped. `verify-publication`
recomputes the current source recipe, CPython binary, host runtime and tool
versions, then reopens every wheel and compares its full license and ELF
inventory with the manifest. Gradle runs that verifier itself whenever
`ankiMinerS1aManifest` is set, so no caller-supplied recipe or build key can
bypass the gate. A publication built on a different host identity must be
rebuilt locally instead of being treated as a portable cache entry.
