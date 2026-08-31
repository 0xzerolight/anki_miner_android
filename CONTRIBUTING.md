# Contributing

Contributions of any kind are welcome. Bug reports and feature requests go to
[Issues](https://github.com/0xzerolight/anki_miner_android/issues).

Participation is governed by the [Code of Conduct](CODE_OF_CONDUCT.md). Report
security vulnerabilities through a
[private advisory](https://github.com/0xzerolight/anki_miner_android/security/advisories/new)
rather than a public issue — see [SECURITY.md](SECURITY.md).

## Build from source

Requires a Linux x86_64 host with the provisioned Android toolchain. See
[scripts/README.md](scripts/README.md) for one-time provisioning, then:

```sh
source scripts/android-env.sh
scripts/health.sh                        # host checks + emulator build + unit tests
source_commit="$(git rev-parse HEAD)"
# Signed arm64 release APK; requires keystore.properties.
./gradlew -PankiMinerSourceCommit="$source_commit" :app:assembleDeviceRelease
```

The signed APK lands in
`app/build/outputs/apk/device/release/anki-miner-android-<version>-arm64-v8a.apk`,
which is the name it is published under. The Python
tokenizer/runtime wheels (`app/wheels/`) and ffmpeg/ffprobe
(`app/src/main/jniLibs/`) are vendored, so a normal Gradle build produces a
working APK.

Release builds reject a missing or non-full source SHA. Debug builds retain the
`development` identity when no SHA is supplied.

## Code style

Python is formatted with `black` and linted with `ruff` (config in
`pyproject.toml`). To auto-fix on commit, install the hook once:

```sh
pip install pre-commit
pre-commit install
```

CI enforces the same tools in check-only mode (`ruff check .`, `black --check .`),
so the hook is a convenience, not a requirement.

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
