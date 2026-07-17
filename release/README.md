# Release evidence

No production release exists yet. This directory defines the minimum evidence which must be completed for each candidate without weakening fail-closed build or device gates.

Create one immutable directory per candidate under `release/records/<version-name>-<version-code>/`. Store UTF-8 files with LF endings and use lowercase SHA-256. The record must identify the exact Git commit and tree, engine revision, runtime and tokenizer publication build keys, physical acceptance receipt, toolchain versions, signed artifacts, signing certificate, corresponding-source archive, declarations, manual results, and every waiver.

A release record is evidence, not a substitute for Play Console submission, legal review, or testing. Never copy evidence from another commit or artifact. If any input changes, create a new candidate record and rerun all affected gates.

Required reviews:

- [CHECKLIST.md](CHECKLIST.md)
- [DATA_SAFETY.md](DATA_SAFETY.md)
- [FOREGROUND_SERVICE.md](FOREGROUND_SERVICE.md)
- [SOURCE_DISTRIBUTION.md](SOURCE_DISTRIBUTION.md)
- [MANUAL_GATES.md](MANUAL_GATES.md)
- [RECORD_FORMAT.md](RECORD_FORMAT.md)
