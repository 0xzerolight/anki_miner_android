# GitHub APK release approvals

`approval-template.json` is deliberately non-passing. Copy it outside the
checkout for a specific tagged candidate, replace its source identity, and
record its exact signed APK SHA-256 and each completed declaration and gate. Every GitHub-required entry must
use `"required": true` and `"outcome": "passed"`. The two Play-only entries
remain `not_applicable_to_channel`; that status does not claim they passed.

Each entry records a repository-relative procedure, a UTC completion time, a
stable operator handle, and the SHA-256 of its reviewed evidence. Do not put an
absolute path, device serial, user content, private log, email address, token,
or signing material in this file. Keep private evidence separately; publish
only material which has been explicitly redacted and reviewed.

`scripts/prepare-github-prerelease.sh` rejects missing, stale, malformed, or
non-passing approvals. `scripts/github_release.py verify-assets` validates the
same declarations and gates again before a draft can be published.

The `private_repository_rehearsal` gate is mandatory. Its reviewed evidence
must identify the same source commit and signed APK SHA-256 as the approval
document; evidence from another build or an unsigned/debug APK is not reusable.
For the two-pass process, `--private-rehearsal` permits only that one required
gate to remain `not_run` and marks the generated record as a private rehearsal.
The normal asset verifier will not accept that channel. Regenerate the final
assets after the rehearsal report is hashed and the gate is changed to `passed`.
