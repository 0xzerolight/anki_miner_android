# Contributing

Contributions of any kind are welcome. Bug reports and feature requests go to
[Issues](https://github.com/0xzerolight/anki_miner_android/issues).

## Build from source

Requires a Linux x86_64 host with the provisioned Android toolchain. See
[scripts/README.md](scripts/README.md) for one-time provisioning, then:

```sh
source scripts/android-env.sh
scripts/health.sh                        # host checks + emulator build + unit tests
./gradlew :app:assembleDeviceRelease     # signed arm64 release APK (needs keystore.properties)
```

The signed APK lands in `app/build/outputs/apk/device/release/`. The Python
tokenizer/runtime wheels (`app/wheels/`) and ffmpeg/ffprobe
(`app/src/main/jniLibs/`) are vendored, so a normal Gradle build produces a
working APK.

## Signing

Generate a keystore and create a `keystore.properties` at the repo root
(gitignored):

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

## Vendored engine

`app/src/main/python/anki_miner/` is generated from
`tools/engine-sync/engine.lock` — do not edit it directly. See
[ARCHITECTURE.md](ARCHITECTURE.md).
