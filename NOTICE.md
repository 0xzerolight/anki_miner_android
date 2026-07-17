# Third-party notices and provenance

This inventory is an engineering aid, not legal advice and not a declaration that an APK or AAB is cleared for distribution. The release owner must review the exact artifact and complete [release/SOURCE_DISTRIBUTION.md](release/SOURCE_DISTRIBUTION.md). License texts and source offers must accompany the release where their terms require them.

## Embedded source and native components

| Component | Evidence in this repository | Recorded terms or status |
|---|---|---|
| Anki Miner Python engine | `app/src/main/python/anki_miner/`, `tools/engine-sync/engine.lock`, `.engine-sync-manifest.json` | GPL-3.0-or-later; full text is packaged at `app/src/main/python/anki_miner/LICENSE` |
| AnkiDroid API source | `third_party/ankidroid-api/manifest.json`, `NOTICE.md`, `upstream/` | Per-file LGPL-3.0-only, LGPL-3.0-or-later, and the recorded permissive `FlashCardsContract` notice |
| MeCab and mecab_for_dart-derived source | `third_party/mecab/source-manifest.json` and both `LICENSE.*` files | BSD-3-Clause |
| FFmpeg, LAME, and Opus CLI build | `tools/ffmpeg/sources.lock`, build recipes, and committed `jniLibs` executables | The build is configured as an LGPL-compatible source set; exact notices, corresponding source, and static-relink material remain release gates |
| Chaquopy and CPython runtime | Gradle verification metadata, Chaquopy target artifacts, and runtime build manifests | Release inventory must include Chaquopy, Python, OpenSSL, SQLite, and other target-runtime notices actually present in the artifact |
| Python runtime wheels | `tools/runtime-wheels/sources.lock` and publication `attributions.json` | Hash-locked per-package terms including Apache-2.0, BSD, MIT-family, MPL-2.0, FTL, IJG, and Zlib terms |
| Fugashi/libmecab tokenizer wheels | `tools/wheels/sources.lock` and verified publication manifest | Publication verifier records and checks the packaged license files |
| Kotlin and Android runtime dependencies | `third_party/s2-runtime-dependencies/manifest.json` and `NOTICE.md` | Complete locked runtime inventory, predominantly Apache-2.0, with Jackson's bundled notices recorded separately |
| Unicode data | `tools/anki-contract/unicode/15.1.0/` | Unicode data license in that directory |

`third_party/ankiconnect-fallback/` records a separately downloaded GPL-3.0-only development probe. The fallback APK is not embedded or distributed with Anki Miner.

## Downloaded language resources

UniDic Lite and the recommended Jitendex/Yomitan dictionary are downloaded as data after installation. Their immutable identities and attributions are stored in `app/src/main/python/android_bridge/resource_catalog_v1.json` and presented by the app. Jitendex includes data under CC BY-SA and source-specific terms, including EDRDG/JMdict attribution. Download-on-demand does not remove the need to preserve required notices in the product and store listing.

## Release requirement

For every distributed artifact, reconcile this human-readable inventory against the packaged dependency and native inventories, preserve every required notice, publish the exact corresponding source bundle and checksums, and record any component added, removed, or changed. Follow [SOURCE_AND_RELINKING.md](SOURCE_AND_RELINKING.md) for the minimum engineering bundle.
