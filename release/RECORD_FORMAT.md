# Deterministic release record format

Each candidate evidence directory contains a final `release.json` with embedded reviewed declarations and gates. Serialize JSON as UTF-8 with sorted keys, two-space indentation, LF endings, and one trailing newline. Paths are release-asset filenames or repository-relative source paths, never machine-local absolute paths.

The channel-aware schema is version 2. Publication tooling must validate every required field and gate before any binary release. Required top-level keys are:

```json
{
  "artifacts": [],
  "assetAllowlist": [],
  "channel": "github-apk-prerelease",
  "declarations": {},
  "engineRevision": "40 lowercase hex characters",
  "gates": {},
  "releaseSchemaVersion": 2,
  "releaseUrl": "https://github.com/0xzerolight/anki_miner_android/releases/tag/v0.1.0-alpha.1",
  "runtimeWheelBuildKey": "64 lowercase hex characters",
  "s1aAcceptance": {},
  "s1aPublicationBuildKey": "64 lowercase hex characters",
  "sourceArchive": {},
  "sourceCommit": "40 lowercase hex characters",
  "sourceTree": "40 lowercase hex characters",
  "tag": "v0.1.0-alpha.1",
  "toolchain": {},
  "versionCode": 100001,
  "versionName": "0.1.0-alpha.1"
}
```

`channel` is exactly `github-apk-prerelease` for a final GitHub record. The tooling also emits `github-apk-private-rehearsal` only when explicitly requested for a disposable private mirror; the normal verifier rejects that record. A future schema may add `google-play`. The tag, manifest version, artifact version, and record version must agree. Version code is globally monotonic.

For GitHub, `artifacts` contains exactly one installable APK entry. It records the signed filename, pre-signing audited APK SHA-256, signed APK SHA-256 and byte size, deterministic payload-inventory digest before and after signing, ABI set, package/application ID, minimum and target API, version, signing-certificate SHA-256, alignment result, and signature-verification result. The payload digests must match. A Play record instead identifies the reviewed AAB and Play-generated delivery identities in addition to the permanent app-signing certificate.

`assetAllowlist` is the complete custom asset list from [CHANNELS.md](CHANNELS.md). The record rejects an unlisted asset. `SHA256SUMS` is generated after `release.json` and may hash the record; `release.json` does not hash `SHA256SUMS`, avoiding a cycle.

## Physical acceptance privacy

`s1aAcceptance` records the raw receipt SHA-256, schema, source commit/tree, tokenizer publication build key, accepted artifact hash, device manufacturer/model, API level, ABI, page size, RAM class, threshold outcomes, and a structured redacted summary. It contains no device serial, absolute path, user corpus text, or credential. A separately reviewed redacted-evidence archive is optional and, when present, is hash-bound through `SHA256SUMS` and `assetAllowlist`.

Acceptance receipt schema v2 omits operational ADB serials and absolute publication/artifact paths. It identifies publications and artifacts by stable filename, hash, size, build key, and source identity; the verifier receives local paths separately and resolves them by content. The collector and release verifier require v2. Keep the raw receipt private, publish only its SHA-256 plus a manually reviewed redacted summary, and do not claim that the public summary can replace the raw receipt for build admission.

## Source and declaration records

`sourceArchive` records the corresponding-source filename, SHA-256, byte size, public sibling URL, and the exact internal `anki-miner-source-manifest.json`. Preparation and publication resolve the release tag in a clean checkout, require every tracked path, mode, and Git blob in the archive to match that tagged tree, and require `anki-miner-external-source-inventory.json` to identify every additional file or symlink. The manifest binds both inventories to the source commit/tree, engine revision, runtime-wheel build key, and tokenizer publication build key in the release record. The final record is not placed inside that archive because it records the archive's hash. GitHub-generated source ZIP/TAR links never substitute for the reviewed archive.

`declarations` records SHA-256, review date, reviewer identity or stable handle, channel applicability, and final status for privacy, foreground-service behavior, Play foreground-service submission, Play Data Safety, third-party notices, corresponding source, relinking, installation information, support, and Android developer verification. Play-only declarations use `not_applicable_to_channel` in a GitHub record, not `passed`.

Each `gates` entry records whether it is required for this channel, command or manual procedure, UTC timestamp, operator stable handle, outcome, and evidence SHA-256. The approval document itself is bound to the exact tag, source commit, and signed APK SHA-256. Every final GitHub gate must be `passed`; the only final `not_applicable_to_channel` outcomes are the two Play-only declarations. The checked-in template uses `not_run` only to remain deliberately non-passing.

For the GitHub APK channel, start from [approval-template.json](approval-template.json) and follow [APPROVALS.md](APPROVALS.md). Release preparation fails closed unless every common/GitHub entry is passed and both Play-only declarations are explicitly `not_applicable_to_channel`.

Do not place passwords, tokens, private keys, personal device serials, private filesystem paths, raw private logs, or user content in the record or public evidence. Only a record with every channel-required gate passed may be associated with a published artifact.
