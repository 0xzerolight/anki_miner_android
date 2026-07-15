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
installed package and both AVD configurations.

## Checks

Run all build-time checks without an emulator:

```bash
scripts/health.sh
```

Run the connected suite on each page-size lane:

```bash
scripts/run-emulator-tests.sh --page-size 4k
scripts/run-emulator-tests.sh --page-size 16k
```

The AVDs have fixed identities and serials:

| Lane | AVD | Serial | Expected page size |
| --- | --- | --- | --- |
| Normal | `anki_miner_api36` | `emulator-5554` | 4096 |
| 16 KiB | `anki_miner_api36_ps16k` | `emulator-5556` | 16384 |

When KVM is unavailable, the launcher selects software CPU emulation and the
Swangle renderer. A first boot can take several minutes. Use `--keep` with the
test runner to leave an emulator running, or `scripts/emulator.sh --window` for
an interactive window. Test runs always wipe userdata and disable snapshot
load/save; an interactive launch remains persistent unless `--wipe-data` is
given explicitly.

Chaquopy reads ABI filters from product flavors. The supported Gradle variants
are therefore `emulatorDebug` (x86_64) and `deviceRelease` (arm64-v8a):

```bash
./gradlew assembleEmulatorDebug
./gradlew assembleDeviceRelease bundleDeviceRelease
./gradlew connectedEmulatorDebugAndroidTest
```

`scripts/check-native-artifact.sh` recursively opens APKs, AABs, ZIPs and
Chaquopy `.imy` files. It rejects unexpected ABIs, 4 KiB-aligned ELF load
segments, ffmpeg/ffprobe files which are not dynamically linked PIE command
executables, debug probe leakage, invalid APK zip-alignment, and manifests
which do not extract native executables. Gradle resolves with committed locks
for every project configuration and strict SHA-256 dependency verification;
plugin artifacts and their transitives are covered by the same metadata.
