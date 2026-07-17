# Corresponding-source and relinking review

Complete this review with qualified legal input for every distributed APK or AAB. The presence of `LICENSE`, a public repository, GitHub-generated source archives, or download URLs does not itself prove that every distribution obligation is satisfied.

## Required package for every channel

- [ ] Project-specific license scope and GPL-3.0-or-later compatibility were reviewed.
- [ ] Exact GPL engine revision, vendored source, modifications/overrides, build scripts, and notices are included.
- [ ] AnkiDroid API per-file terms, complete LGPL/GPL texts, modifications, application source, and practical replacement/rebuild instructions are included.
- [ ] FFmpeg/LAME/Opus exact terms were audited from locked sources; notices, source, configuration, patches, and required static-relink material are included and tested.
- [ ] MeCab/mecab_for_dart, Fugashi, Chaquopy, CPython, OpenSSL, SQLite, runtime wheels, Android/Kotlin dependencies, Unicode data, and downloaded resource attributions were reconciled against the exact binary.
- [ ] Complete third-party notice output is accessible to recipients and archived as the release's reviewed notices asset.
- [ ] Recipient-controlled rebuild, relink, signing, and installation procedure was tested from a clean host without publisher credentials, private caches, or the publisher's signing key.
- [ ] Source availability/retention owner and duration are recorded.
- [ ] Legal reviewer, scope, date, open issues, and decision are recorded without claiming broader clearance.

## Sibling assets and hash binding

Publish the reviewed corresponding-source archive, final `release.json`, notices archive, signed binary, public signing certificate, and `SHA256SUMS` as sibling release assets. The final record must not be placed inside the source archive whose SHA-256 it records; that would create a self-reference. A later archival copy of the record may be committed after publication, but it is not part of the source tag or the archive it describes.

GitHub-generated source ZIP and TAR files are convenience snapshots, not the reviewed corresponding-source package. Release notes must point recipients to `anki-miner-android-<version>-corresponding-source.tar.zst`.

Do not publish a raw physical-device receipt containing an ADB serial or machine-local path. The release record stores its SHA-256 and a reviewed redacted summary. Receipt schema v2 is expected to identify source, publications, artifacts, device class, and outcomes by stable hashes and public metadata while omitting operational serials and absolute paths. A private raw receipt remains available to the reviewer when required by the build verifier.

## GitHub APK prerelease

- [ ] The corresponding-source and notices archives are present in the exact GitHub asset allowlist in [CHANNELS.md](CHANNELS.md).
- [ ] The source archive matches the signed APK's source commit/tree, engine revision, runtime and tokenizer publications, native sources, and build inputs.
- [ ] `anki-miner-source-manifest.json` and `anki-miner-external-source-inventory.json` are directly under the archive's single root; every tracked path, mode, and blob matches the tagged tree; every additional source item matches the external inventory; and the final `release.json` is absent from the archive.
- [ ] Installation instructions explain signing a modified APK with a recipient-controlled key and the resulting package/signature update rules.
- [ ] The APK, source, notices, certificate, and release record hashes are listed in `SHA256SUMS`.
- [ ] No AAB, signing secret, raw receipt, private runner input, or private evidence is included.

## Google Play only

- [ ] Play terms, Play App Signing terms, store listing text, and any EULA were reviewed for GPL/LGPL conflicts and prohibited reverse-engineering or redistribution restrictions.
- [ ] The Play AAB and generated delivery artifacts are reconciled against the same corresponding-source obligations.
- [ ] Play's custody of the app-signing key and the recipient-controlled rebuild/re-sign path are described without implying that Play credentials are conveyed.

Detailed engineering expectations are in [SOURCE_AND_RELINKING.md](../SOURCE_AND_RELINKING.md).
