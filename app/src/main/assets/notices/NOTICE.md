# Third-party notices

Anki Miner for Android is licensed under GPL-3.0-or-later (see [LICENSE](LICENSE)) and bundles the third-party components listed below, each retaining its own terms. Every distributed APK identifies an immutable commit and is accompanied by the corresponding source from that commit. The native components' upstream sources and hashes are pinned in `tools/ffmpeg/sources.lock`, `tools/runtime-wheels/sources.lock`, and `tools/wheels/sources.lock`.

## Embedded source and native components

| Component | Evidence in this repository | Recorded terms or status |
|---|---|---|
| Anki Miner Python engine | `app/src/main/python/anki_miner/`, `tools/engine-sync/engine.lock`, `app/src/main/python/.engine-sync-manifest.json` | GPL-3.0-or-later; full text is packaged at `app/src/main/python/anki_miner/LICENSE` |
| Theme colour palettes | `tools/themes/palettes/`, `tools/themes/themes.lock`, `themes-LICENSE.md` | Per-family MIT palette attributions; published hex values only, no upstream source |
| AnkiDroid API source | `third_party/ankidroid-api/manifest.json`, `NOTICE.md`, `upstream/` | Per-file LGPL-3.0-only, LGPL-3.0-or-later, and the recorded permissive `FlashCardsContract` notice |
| MeCab and mecab_for_dart-derived source | `third_party/mecab/source-manifest.json` and both `LICENSE.*` files | BSD-3-Clause |
| FFmpeg, LAME, Opus, and dav1d CLI build | `third_party/ffmpeg/` (license texts and `NOTICE.md`), `tools/ffmpeg/sources.lock`, build recipes, and committed `jniLibs` executables | FFmpeg 7.1.5 LGPL-2.1-or-later; LAME 3.100 LGPL-2.0-or-later (GNU Library GPL v2); Opus 1.5.2 BSD-3-Clause; dav1d 1.5.0 BSD-2-Clause |
| Chaquopy and CPython runtime | Chaquopy target artifacts and vendored runtime wheels under `app/wheels/` | Chaquopy, Python (PSF), OpenSSL, and SQLite terms as present in the runtime |
| Python runtime wheels | `tools/runtime-wheels/sources.lock` and publication `attributions.json` | Hash-locked per-package terms including Apache-2.0, BSD, MIT-family, MPL-2.0, FTL, IJG, and Zlib terms |
| Fugashi/libmecab tokenizer wheels | `tools/wheels/sources.lock` and verified publication manifest | Publication verifier records and checks the packaged license files |
| Kotlin and Android runtime dependencies | `third_party/s2-runtime-dependencies/manifest.json` and `NOTICE.md` | Complete locked runtime inventory, predominantly Apache-2.0, with Jackson's bundled notices recorded separately |
| Unicode data | `tools/anki-contract/unicode/15.1.0/` | Unicode data license in that directory |

`third_party/ankiconnect-fallback/` records a separately downloaded GPL-3.0-only development probe. The fallback APK is not embedded or distributed with Anki Miner.

## Downloaded language resources

UniDic Lite and the recommended Jitendex/Yomitan dictionary are downloaded as data after installation. Their immutable identities and attributions are stored in `app/src/main/python/android_bridge/resource_catalog_v1.json` and presented by the app. Jitendex includes data under CC BY-SA and source-specific terms, including EDRDG/JMdict attribution. Download-on-demand does not remove the need to preserve required notices in the product and store listing.

## Source availability

For a distributed APK, the corresponding source for the GPL/LGPL components
must be made available at no charge at the immutable application commit embedded
in that APK. Pinned upstream sources for FFmpeg, LAME, Opus, dav1d, MeCab, fugashi, and
the runtime wheels are recorded with hashes in the `sources.lock` files above.
