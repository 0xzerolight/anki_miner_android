# Anki Miner for Android

Anki Miner for Android mines Japanese vocabulary from user-selected local media and creates Anki notes through AnkiDroid. The app uses a Kotlin and Jetpack Compose UI around a Chaquopy-embedded Python engine synchronized from [Anki Miner](https://github.com/0xzerolight/anki_miner).

This repository is pre-release. Do not distribute an APK or AAB until the open legal, privacy, physical-device, and Play Console gates in [release/CHECKLIST.md](release/CHECKLIST.md) have been completed for the exact artifact.

## Architecture

The production path is Compose → ViewModels → Kotlin services → a media-processing foreground service → the JSON bridge → the vendored Python engine. AnkiDroid integration is ContentProvider-first. Video and subtitle access uses Android's Storage Access Framework; the app does not request broad video-library access. UniDic and optional dictionaries are verified external data resources and are not bundled in the base app.

The vendored engine under `app/src/main/python/anki_miner/` is generated from the revision in `tools/engine-sync/engine.lock`. Do not edit it directly. Android adaptations live in `app/src/main/python/android_bridge/` and `tools/engine-sync/overrides/`.

## Build and verification

The supported build host is Linux x86_64. Toolchain versions, dependency hashes, runtime wheels, native inputs, and emulator lanes are pinned. Start with [scripts/README.md](scripts/README.md); the normal host gate is:

```sh
scripts/provision-host-tests.sh
scripts/provision-android.sh
scripts/android-licenses.sh review
scripts/provision-android.sh
source scripts/android-env.sh
scripts/health.sh
```

Android SDK license review is intentionally interactive. Gradle and emulator work must remain sequential on a memory-constrained host. A production ARM64 release additionally requires the exact physical-device acceptance receipt described by the scripts and [release/MANUAL_GATES.md](release/MANUAL_GATES.md).

## Privacy, security, and licensing

- [PRIVACY.md](PRIVACY.md) describes behavior in this source revision. A monitored contact address and hosted policy URL remain release-owner gates.
- Security reports should follow [SECURITY.md](SECURITY.md).
- Project-specific code is offered under GPL-3.0-or-later; vendored components retain their own terms. See [LICENSE](LICENSE), [NOTICE.md](NOTICE.md), and [SOURCE_AND_RELINKING.md](SOURCE_AND_RELINKING.md).
- Adding these files does not itself establish distribution compliance. Every release requires the legal and source-delivery review recorded in [release/SOURCE_DISTRIBUTION.md](release/SOURCE_DISTRIBUTION.md).

## Project status

Unreleased changes are recorded in [CHANGELOG.md](CHANGELOG.md). Current code, tests, and dated project records are evidence of implemented behavior; a design document is not proof that a feature or release gate has passed.
