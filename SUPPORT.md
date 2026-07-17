# Support

Anki Miner for Android has no production release and no support service-level agreement. GitHub prereleases, when available, are best-effort alpha builds intended to collect reproducible testing feedback.

## Supported builds

No binary is currently supported. Before the first prerelease, this section must name its exact version and the oldest still-supported version. Once prereleases exist, only the latest non-withdrawn GitHub prerelease receives routine fixes unless a release notice explicitly says otherwise.

An APK from a fork, mirror, workflow artifact, local build, debug variant, or unexpected signing certificate is unsupported. Supported device/API/ABI and AnkiDroid versions are recorded in each release.

## General problems

Use the public issue tracker for reproducible non-security bugs after reading [INSTALL.md](INSTALL.md) and [TESTING.md](TESTING.md). Search existing issues first and use the bug report form.

Public reports must not include private video, audio, subtitles, reading material, dictionary entries not safe to redistribute, Anki collection/export data, note contents, recovery databases, complete logs, document URIs, device serials, account information, credentials, signing material, or access tokens. Provide synthetic inputs or a minimal privacy-safe description. Maintainers may close or redact reports which expose user or third-party data.

The release owner must add and monitor a public support/privacy contact before any external APK is published. Until that contact and the final hosted privacy policy exist, contact readiness remains a release blocker.

## Security reports

Follow [SECURITY.md](SECURITY.md) and use GitHub private vulnerability reporting. Do not open a public issue for a vulnerability, signing-key concern, exploitable archive/parser behavior, privacy leak, or collection-corruption path.

## Response and withdrawal

Responses, fixes, and release timing are best effort during alpha. A release may be withdrawn for a signing, legal, privacy, security, data-integrity, or severe compatibility problem. GitHub releases do not update installed apps automatically; check the release page before continuing to test an old build.

If a release is withdrawn, stop distributing it, preserve its immutable evidence privately as required, publish a clear advisory, and state whether users should update, stop using a feature, export information, or uninstall. Do not advise a downgrade unless Android signature/version behavior and data consequences have been tested.
