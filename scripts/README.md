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

That default gate builds only `emulatorDebug` plus its test APK. ARM64 release
APK/AAB tasks are deliberately unavailable until a source-, publication-,
artifact-, and device-bound physical acceptance receipt is supplied:

```bash
export ORG_GRADLE_PROJECT_ankiMinerS1aManifest=/absolute/path/to/s1a/manifest.json
scripts/health.sh --release-acceptance-receipt /outside/the/repo/s1a-acceptance.json
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

The M3 S5 lane is a separate destructive acceptance test. It requires the
selected x86_64 S1a wheel publication and the exact golden-pinned UniDic tree.
It creates a disposable model/deck and a tiny offline dictionary, then drives
the real process-owned mining repository from SAF content URIs through parked
curation, the media-processing foreground service, and the production durable
AnkiDroid callbacks. The lane verifies the resulting note, card, deck, fields,
and non-empty screenshot/audio files. Its second run observes a real ffmpeg
child before cancelling and bounds complete child/service teardown.

```bash
export ANKI_MINER_S5_ALLOW_COLLECTION_RESET=true
export ORG_GRADLE_PROJECT_ankiMinerS1aManifest=/absolute/path/to/s1a/manifest.json
S5_RECEIPT="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/test-receipts/s5.json"
UNIDIC_DIR=/absolute/path/to/the/golden-pinned/unidic/dicdir
scripts/prepare-s5-video-probe.sh --receipt "$S5_RECEIPT"
scripts/run-s5-video-acceptance.sh \
  --receipt "$S5_RECEIPT" \
  --unidic-dir "$UNIDIC_DIR"
```

The owner runner wipes only `/storage/emulated/0/AnkiDroid` on the dedicated
API 36 4 KiB emulator and always stops that emulator. Passing instrumentation
argument `ankiMinerRunS5=true` directly is not a substitute: the wrapper also
pins the AnkiDroid APK/certificate, S1a publication, collection reset, runtime
permissions, UniDic provenance, and emulator identity.

Screen-off and Activity/process lifecycle checks remain bounded manual
acceptance because emulator power/notification timing is not stable enough for
an authoritative unattended assertion:

1. In a windowed owned emulator, complete setup, select one local MKV and SRT,
   wait for curation, choose at least twelve words, and confirm. As soon as the
   notification says media extraction is running, run
   `adb -s emulator-5554 shell input keyevent 26`. Leave it off for 60 seconds,
   wake it with the same command, and verify a terminal result and playable
   card media. The mining service must be absent afterward.
2. Repeat to curation, rotate the Activity twice, and verify the same parked
   request and selections remain available. Confirm, rotate again during media
   extraction, and verify progress/cancel still control the same run.
3. Repeat to curation, run
   `adb -s emulator-5554 shell am force-stop com.ankiminer.android`, relaunch,
   and verify a clean idle screen with no mining notification or service. Pick
   the files again; no old persisted URI grants should remain after startup
   reconciliation.
4. Start a many-word run, cancel from the notification during extraction, and
   require a terminal cancelled state within 10 seconds. Then verify
   `adb -s emulator-5554 shell dumpsys activity services com.ankiminer.android`
   has no `MiningForegroundService` entry and `adb -s emulator-5554 shell ps -A`
   has no `libffmpeg.so` or `libffprobe.so` child.

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

The selected S1a path has a stricter physical acceptance collector. Run it from
a clean committed checkout with a verified wheel publication, the exact UniDic
tree, and a UTF-8 novel containing at least 50,000 Japanese characters:

```bash
scripts/collect-s1a-arm64-acceptance.sh \
  --serial PHYSICAL_ARM64_SERIAL \
  --manifest /absolute/path/to/s1a/manifest.json \
  --unidic-dir /absolute/path/to/golden-pinned/unidic/dicdir \
  --page-size 4k \
  --image-fingerprint EXACT_DEVICE_BUILD_FINGERPRINT \
  --novel /absolute/path/to/representative-novel.txt \
  --output /tmp/s1a-arm64-acceptance.json
```

The collector rejects emulators and devices outside the frozen 3–5 GiB RAM
class. It runs the v1 adversarial tokenizer corpus, three distinct fresh app
processes for the complete cold initialization boundary, and one production
`parse_text_units` novel workload. Peak RSS is the kernel's process-lifetime
`VmHWM`, not a point-in-time heap estimate. The generated receipt is accepted
only when every cold run is below 4.0 seconds and peak RSS is at most 384 MiB;
it must live outside the checkout so creating it cannot dirty the source it
attests.

## Continuous integration

`.github/workflows/ci.yml` runs on a dedicated `anki-miner-android` self-hosted
runner because Android SDK license acceptance and builder-identity-scoped
native wheel publications are provisioned inputs, not unattended downloads.
The runner repository variables name the external toolchain, desktop checkout,
UniDic tree, runtime/S1a manifests, pinned AnkiDroid APK, and pinned
AnkiconnectAndroid APK. One serialized job re-derives v2, runs host/native
health, then owns and stops the API 26, API 36 4 KiB, API 36 16 KiB, provider,
and HTTP fallback emulators one at a time.

`.github/workflows/parity-nightly.yml` is hosted and network-only. It checks out
desktop HEAD, derives a complete v2 artifact with the desktop frozen runtime,
and emits a visible warning when semantic cases differ from the Android pin.

`.github/workflows/ankidroid-prerelease-canary.yml` supplies the moving half of
the stable-plus-prerelease AnkiDroid matrix without weakening the immutable
stable S2 receipt. Each scheduled run resolves the newest non-draft official
`alpha`, `beta`, or `rc` release from the GitHub API, requires GitHub's SHA-256
digest for the exact x86_64 ABI asset, downloads only its canonical release
URL, and verifies package name, version, size, API compatibility, and the
official AnkiDroid signing certificate before starting the emulator. The
resolved manifest and identity are retained as run evidence. Missing digest,
ambiguous asset, unexpected signer, or absent prerelease fails closed.

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
