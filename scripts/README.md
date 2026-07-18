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

The build interpreter is an exact CPython 3.12.13 python-build-standalone
installation at
`.android-toolchain/chaquopy-build-python`. It is selected explicitly through
`ANKI_MINER_CHAQUOPY_BUILD_PYTHON` and is never added to `PATH`; repository
tools and host tests remain on Python 3.13. HTML entity generation uses the
3.12.13 interpreter because its output is pinned to that CPython version. The
build interpreter can be installed or repaired independently with:

```bash
scripts/provision-chaquopy-build-python.sh
```

Both shared installations use inter-process locks, so provisioning commands from
concurrent Git worktrees are serialized safely. The license review is
interactive: the script displays Google's text and prompts without supplying
answers.

## Checks

Run the full host gate without an emulator. It performs the Python suites and
one serialized, one-worker Gradle invocation covering debug unit tests/lint and
an ephemeral-signed, non-distributable emulator R8 APK. The APK is audited for
its signature, manifest identity, ABI, required native entries, ffmpeg/ffprobe,
UniDic exclusion, and 16 KiB ZIP alignment. Exact S1a publication matching is
reserved for the candidate verifier because it requires the final immutable
publication:

```bash
source scripts/android-env.sh
scripts/health.sh
```

## Build an exact release candidate

The Python tokenizer/runtime wheels are vendored under `app/wheels/` and the
ffmpeg/ffprobe executables under `app/src/main/jniLibs/`, so a normal Gradle
build produces an APK. A release task intentionally fails unless signing and
identity inputs are complete. Configure the permanent local signing key first
(see the repository root `README.md`), choose a version which has not previously
been published, then:

```bash
source scripts/android-env.sh
source scripts/android-test-resources.sh
export ANKI_MINER_VERSION_CODE=1
export ANKI_MINER_VERSION_NAME=0.1.0-alpha.1
export ANKI_MINER_SOURCE_COMMIT="<40-character output of git rev-parse HEAD>"
export ANKI_MINER_RELEASE_CHANNEL=github-alpha
export ANKI_MINER_S1A_ARM64_ACCEPTED=true
anki_miner_run_gradle ./gradlew :app:assembleDeviceRelease
```

`false` is allowed only for the non-distributable `ci` channel. Every GitHub or
production candidate requires `true`, backed by recorded source-bound physical
ARM64 evidence. The signed APK is under
`app/build/outputs/apk/device/release/`.

A successful build is not sufficient for distribution. Resolve the immutable
runtime and S1a publication manifests inside their complete publication
directories, plus the independently recorded SHA-256 fingerprint of the
permanent certificate, then run:

```bash
python3.13 scripts/verify_release_candidate.py \
  --artifact app/build/outputs/apk/device/release/app-device-release.apk \
  --mode distribution \
  --abi arm64-v8a \
  --runtime-manifest /absolute/runtime-wheels-<build-key>/manifest.json \
  --s1a-manifest /absolute/s1a-wheels-<build-key>/manifest.json \
  --expected-cert-sha256 <permanent-certificate-sha256> \
  --expected-version-code "$ANKI_MINER_VERSION_CODE" \
  --expected-version-name "$ANKI_MINER_VERSION_NAME" \
  --expected-source-commit "$ANKI_MINER_SOURCE_COMMIT" \
  --expected-channel "$ANKI_MINER_RELEASE_CHANNEL" \
  --expected-s1a-arm64-accepted "$ANKI_MINER_S1A_ARM64_ACCEPTED"
```

The verifier rejects dirty or untracked source, a source SHA different from HEAD,
manifest or version drift, the wrong certificate, non-immutable publication
manifests, runtime/native inventory drift, missing ffmpeg/ffprobe/S1a, bundled
UniDic, and alignment failures. `--mode test` accepts only the `ci` channel and
ephemeral certificates; it verifies but never publishes. Follow
[`RELEASE.md`](../RELEASE.md) before distributing anything.

For local UI work, `scripts/run-app.sh` builds first, lets Gradle exit, then
starts one emulator and installs the prebuilt APK. It never uninstalls the app;
on a signing mismatch it stops and leaves existing app data intact.

## Regenerating the vendored wheels

The wheels are built once and committed. Rebuild them (e.g. on a version bump)
with `tools/runtime-wheels/build-runtime-wheels.sh` and
`tools/wheels/build-s1a-wheels.sh`, then copy the closure into `app/wheels/` and
update the two build-key literals in `app/build.gradle.kts`.
