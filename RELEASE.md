# Release candidate policy

No public APK has been tagged. Version 0.1.0 remains unreleased.

There are three build channels:

- `ci` is ephemeral-signed and non-distributable.
- `github-alpha` is the permanently signed GitHub prerelease channel. It
  requires `S1A_ARM64_ACCEPTED=true` before even closed tester distribution.
- `production` requires `S1A_ARM64_ACCEPTED=true` backed by recorded physical
  evidence.

Every release task requires complete signing configuration, a positive explicit
version code, a semantic version name, a lowercase 40-hex source commit, an
allowlisted channel, and an explicit ARM64 acceptance value. This validates the
inputs but does not prove that a version code is newer than prior releases; check
the published history before choosing it.

Both distributable channels require source-bound physical ARM64 acceptance.
The `ci` channel may record `false` only because its ephemeral APKs are never
distributed.

Before distributing a `github-alpha` or `production` APK:

1. Use the permanent project signing key. Never distribute a CI/health APK.
2. Build from a clean worktree with the embedded source commit equal to
   `HEAD`, using the serialized Gradle helper documented in `scripts/README.md`.
3. Resolve the complete immutable runtime and S1a publication directories
   selected by the APK. A clean build-stage manifest is not a publication.
4. Run `scripts/verify_release_candidate.py --mode distribution` against the
   exact APK and the independently recorded permanent certificate fingerprint.
5. Publish the APK with its SHA-256, `NOTICE.md`, privacy policy, and source for
   the embedded commit. Re-run verification after any rebuild or re-signing.
6. State all remaining physical gates. Do not claim ARM64, SAF-provider, OEM
   TTS/background behavior, or real AnkiDroid acceptance without evidence.

If any item cannot be completed, keep the artifact local and non-distributable.
