# Source, rebuild and installation assessment

This is an engineering assessment, not legal advice.

## Current linking shape

The LGPL-covered Kotlin files are compiled directly into the single `:app`
module and its DEX output. Treat the APK as a statically combined work. The
planned compliance path is LGPLv3 section 4(d)(0): convey the exact Minimal
Corresponding Source and the Corresponding Application Code in a form and
under terms which let a recipient replace the library source and rebuild the
combined work. Reverse engineering for debugging modifications to the library
must not be restricted.

The exact unmodified linked source, its provenance manifest, both complete
license texts, Gradle wrapper, dependency locks and build scripts are present
in this repository. The normal manifest check is an integrity check only; the
opt-in verified-upstream mode proves byte equality against regular,
non-executable blobs in the exact pinned Git tree.

The app repository is licensed under GPL-3.0-or-later (top-level `LICENSE`),
which grants recipients the terms to modify and rebuild the combined work and
supplies the recombination/relinking permission LGPLv3 requires. Convey the
complete source/build material for the exact released revision with the APK.

## Rebuild and relink

On the supported Linux x86_64 host, start from the exact app source revision:

```bash
scripts/provision-host-tests.sh
scripts/provision-android.sh
scripts/android-licenses.sh review
scripts/provision-android.sh
source scripts/android-env.sh
python3.13 tools/ankidroid-api/sync_ankidroid_api.py --check
scripts/health.sh
```

The license review is deliberately interactive. The pinned toolchain and
dependency verification files make the normal build reproducible once those
third-party packages are available.

To relink a modified AnkiDroid API, replace only the generated API Kotlin files
under `app/src/main/ankidroidApi/kotlin` with interface-compatible modified
sources and run the Gradle build directly. The maintainer-only provenance check
will intentionally fail because those bytes are no longer the pinned upstream
component; it is not a restriction on compiling them. Build the variants with:

```bash
source_commit="$(git rev-parse HEAD)"
./gradlew --dependency-verification strict \
  -PankiMinerSourceCommit="$source_commit" \
  :app:assembleEmulatorDebug :app:assembleDeviceRelease
```

Preserve the LGPL/GPL texts and notices, mark modified library files, and make
the modified source available with the rebuilt APK.

## Installation information

Android requires every APK to be signed. A recipient can sign a rebuilt APK
with a key they control and install it with `adb install`. It cannot replace an
installed APK signed by another key; uninstall that package first or use a
distinct application ID for the modified build. This app does not add a
signature check, boot lock or other technical restriction on modified builds.
After installation, the user must grant AnkiDroid's dangerous
`com.ichi2.anki.permission.READ_WRITE_DATABASE` permission through the app's
user-driven permission flow (or `pm grant` in the emulator harness).

If a future distributor adds a device lock, signature gate or other restriction
which makes those steps insufficient, the Installation Information needed to
install and run the modified combined work must accompany the release.
