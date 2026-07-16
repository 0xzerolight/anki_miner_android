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
tools, host tests, Unicode generation and desktop goldens remain on Python
3.13. Its lock attests the complete extracted payload as well as the executable.
Generated `__pycache__` files are excluded from the tree digest but must compile
exactly from their separately attested source files. The interpreter can be
installed or repaired independently with:

```bash
scripts/provision-chaquopy-build-python.sh
```

`provision-runtime-host-tests.sh` creates a second hash-locked environment on
that interpreter, provisioning or repairing the interpreter first when needed.
Health uses it to run the bridge suite and complete engine import smoke under
CPython 3.12 with the tokenizer-neutral Android dependency set; fugashi and
UniDic remain absent from this common lane. Both shared installations use
inter-process locks, so provisioning commands from concurrent Git worktrees are
serialized safely.

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
RECEIPT="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/test-receipts/connected.json"
scripts/prepare-emulator-tests.sh --receipt "$RECEIPT"
scripts/run-emulator-tests.sh --receipt "$RECEIPT" --lane api26 --unidic-dir "$UNIDIC_DIR"
scripts/run-emulator-tests.sh --receipt "$RECEIPT" --lane 4k --unidic-dir "$UNIDIC_DIR"
scripts/run-emulator-tests.sh --receipt "$RECEIPT" --lane 16k --unidic-dir "$UNIDIC_DIR"
```

`--page-size 4k|16k` remains an exact backward-compatible alias for the two
API 36 lanes. The AVDs have fixed identities and serials. Each connected run
also checks the runtime API level and page size; the API 26 lane additionally
checks its exact build fingerprint. Preparation runs Gradle once with every
emulator stopped. Connected runs validate the source, manifest and APK hashes
from that receipt, perform only adb work, and always stop their emulator.

| Lane | AVD | Serial | API | Page size |
| --- | --- | --- | --- | --- |
| `api26` | `anki_miner_api26` | `emulator-5558` | 26 | 4096 |
| `4k` | `anki_miner_api36` | `emulator-5554` | 36 | 4096 |
| `16k` | `anki_miner_api36_ps16k` | `emulator-5556` | 36 | 16384 |

The destructive S2 capability probe uses the same owned lifecycle. After
confirming that the dedicated emulator collection is disposable, prepare its
receipt with the pinned external AnkiDroid APK, then run the wiped 4 KiB lane:

```bash
export ANKI_MINER_S2_ALLOW_COLLECTION_RESET=true
S2_RECEIPT="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/test-receipts/s2.json"
scripts/prepare-s2-ankidroid-probe.sh --receipt "$S2_RECEIPT"
scripts/run-emulator-tests.sh --s2 --receipt "$S2_RECEIPT"
```

The owner runner stops the emulator on success, failure, or timeout. The
adb-only `run-s2-ankidroid-probe.sh` is its internal connected phase.

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
Swangle renderer. A first boot can take several minutes. Test runs always wipe
userdata, disable snapshot load/save, and stop the emulator afterward. Use
`scripts/emulator.sh --window` only for a separate interactive session.

Chaquopy reads ABI filters from product flavors. The normal Gradle variants
are therefore `emulatorDebug` (x86_64) and `deviceRelease` (arm64-v8a):

The repository scripts enforce one Gradle worker, no parallel execution, no
daemon, a 2 GiB heap, and no overlap between Gradle and an emulator. Use
`scripts/health.sh` instead of invoking connected Gradle tasks directly.

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
