# Anki Miner for Android

Anki Miner for Android mines Japanese vocabulary from user-selected local video, subtitles, text, EPUB, and Mokuro sources and creates Anki notes through AnkiDroid. The app uses a Kotlin and Jetpack Compose UI around a Chaquopy-embedded Python engine synchronized from [Anki Miner](https://github.com/0xzerolight/anki_miner).

This repository is pre-release and has no approved binary download yet. The first external-testing channel will be a signed ARM64 APK published as a GitHub prerelease. It may be published only after the common and GitHub-specific gates in [release/CHECKLIST.md](release/CHECKLIST.md) pass for the exact artifact. Play Console declarations and track testing remain separate later gates; they are not prerequisites for a GitHub APK, and they are not considered complete.

When a prerelease exists, download it only from this repository's GitHub Releases page and follow [INSTALL.md](INSTALL.md). The app does not update itself. Testers should read [TESTING.md](TESTING.md), and support expectations are in [SUPPORT.md](SUPPORT.md).

## Architecture

The production path is Compose → ViewModels → Kotlin services → the JSON bridge → the vendored Python engine. Post-curation work which transforms media uses a cancellable `mediaProcessing` foreground service; text-only reading work does not claim that type. AnkiDroid integration uses its local ContentProvider, an app-owned note model, exact readback, and durable mutation recovery. AnkiconnectAndroid is retained only as a development compatibility canary; it is not bundled, auto-detected, recommended, or used as a production exporter. Video, subtitle, reading, dictionary, frequency, pitch, known-word, and local-audio input uses Android's Storage Access Framework; the app does not request broad video-library access. UniDic and optional language resources are installed into private storage and are not bundled in the base app. Reading sentence audio can use a device-installed offline Japanese Android TTS voice.

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

Android SDK license review is intentionally interactive. Gradle and emulator work must remain sequential on a memory-constrained host. Every externally distributed ARM64 APK additionally requires the exact physical-device acceptance receipt described by the scripts and [release/MANUAL_GATES.md](release/MANUAL_GATES.md). Building an artifact does not approve it for distribution.

## Privacy, security, and licensing

- [PRIVACY.md](PRIVACY.md) describes behavior in this source revision. A monitored contact address and hosted policy URL remain release-owner gates.
- Security reports should follow [SECURITY.md](SECURITY.md).
- Project-specific code is offered under GPL-3.0-or-later; vendored components retain their own terms. See [LICENSE](LICENSE), [NOTICE.md](NOTICE.md), and [SOURCE_AND_RELINKING.md](SOURCE_AND_RELINKING.md).
- Adding these files does not itself establish distribution compliance. Every release requires the legal and source-delivery review recorded in [release/SOURCE_DISTRIBUTION.md](release/SOURCE_DISTRIBUTION.md).
- GitHub APK and future Play requirements are separated in [release/CHANNELS.md](release/CHANNELS.md). Both channels use the same package identity, monotonically increasing version codes, and permanent app-signing certificate so an eventual Play build can update a GitHub installation.

## Project status

Unreleased changes are recorded in [CHANGELOG.md](CHANGELOG.md). Current code, tests, and dated project records are evidence of implemented behavior; a design document is not proof that a feature or release gate has passed.
