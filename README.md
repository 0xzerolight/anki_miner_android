# Anki Miner for Android

> Alpha. This README is a minimal placeholder — expect it to be rewritten.

Anki Miner for Android mines Japanese vocabulary from local video, subtitles,
text, EPUB, and Mokuro sources and creates Anki notes through AnkiDroid. It pairs
a Kotlin / Jetpack Compose UI with a Chaquopy-embedded Python engine synchronized
from [Anki Miner](https://github.com/0xzerolight/anki_miner).

## Install

1. Install **AnkiDroid** from the Play Store or F-Droid first.
2. Download the latest `anki-miner-android-<version>-arm64-v8a.apk` from the
   [Releases](https://github.com/0xzerolight/anki_miner_android/releases) page.
   Requires a 64-bit ARM device on Android 8.0 (API 26) or newer.
3. Allow installing from unknown sources, then open the APK to install.
4. Optionally verify the download against the published `SHA256SUMS`.

The app does not update itself; install a newer APK over the old one to update.

## Privacy

Offline-first. No accounts, analytics, or tracking. It only reaches the network
for optional Jisho.org dictionary lookups and one-time resource downloads (e.g.
the UniDic tokenizer dictionary), always over HTTPS. See [PRIVACY.md](PRIVACY.md).

## Build from source

Linux x86_64 host with the provisioned Android toolchain. See
[scripts/README.md](scripts/README.md) for setup, then:

```sh
source scripts/android-env.sh
scripts/health.sh                        # host checks + emulator build + unit tests
./gradlew :app:assembleDeviceRelease     # signed arm64 release APK (needs keystore.properties)
```

To sign locally, generate a keystore and create a `keystore.properties` at the
repo root (gitignored):

```sh
keytool -genkeypair -v -keystore anki-miner-release.jks -alias anki-miner \
    -keyalg RSA -keysize 4096 -validity 10000
```

```properties
# keystore.properties (never commit)
storeFile=/absolute/path/to/anki-miner-release.jks
storePassword=...
keyAlias=anki-miner
keyPassword=...
```

Keep the keystore file and its passwords safe and reuse them for every future
release, or updates will not install over an existing version.

## Architecture

Compose → ViewModels → Kotlin services → a JSON bridge → the vendored Python
engine under `app/src/main/python/anki_miner/` (generated from
`tools/engine-sync/engine.lock`; do not edit directly). AnkiDroid integration
uses its local ContentProvider with an app-owned note model, exact readback, and
durable mutation recovery. The Python tokenizer/runtime wheels are vendored under
`app/wheels/` and ffmpeg/ffprobe under `app/src/main/jniLibs/`.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE) and third-party terms in
[NOTICE.md](NOTICE.md).
