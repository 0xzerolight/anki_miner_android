# Release evidence

No production release exists yet. This directory defines the evidence required for the GitHub APK prerelease channel and the additional gates retained for a future Google Play release. Read [CHANNELS.md](CHANNELS.md) before preparing a candidate.

Prepare one immutable evidence directory per candidate outside the source checkout, named `<version-name>-<version-code>/`. Store UTF-8 files with LF endings and lowercase SHA-256. The final `release.json`, which embeds the reviewed declarations and gates, is a release asset beside the APK and corresponding-source archive. It is not added to the source commit it describes and is not placed inside the archive whose hash it records.

The record identifies the exact channel, Git tag, commit and tree, engine revision, runtime and tokenizer publication build keys, physical acceptance receipt hash, toolchain versions, unsigned and signed artifact identities, signing certificate, corresponding-source archive, declarations, and required manual results. Open alpha objectives belong in release notes, but they cannot waive a required release gate. The record contains no signing secret, personal device serial, private path, user content, or raw private log.

A release record is evidence, not a substitute for legal review, privacy review, physical testing, or a later Play Console submission. Never copy evidence from another commit or artifact. If source, version, dependency publication, signing identity, artifact, or required evidence changes, create a new candidate and rerun every affected gate.

Required reviews:

- [CHECKLIST.md](CHECKLIST.md)
- [CHANNELS.md](CHANNELS.md)
- [DATA_SAFETY.md](DATA_SAFETY.md)
- [FOREGROUND_SERVICE.md](FOREGROUND_SERVICE.md)
- [SOURCE_DISTRIBUTION.md](SOURCE_DISTRIBUTION.md)
- [MANUAL_GATES.md](MANUAL_GATES.md)
- [RECORD_FORMAT.md](RECORD_FORMAT.md)
- [APPROVALS.md](APPROVALS.md) and [approval-template.json](approval-template.json)
