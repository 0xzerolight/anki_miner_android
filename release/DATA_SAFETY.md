# Privacy inventory and Play Data Safety worksheet

The repository behavior inventory applies to every channel. The Google Play Data Safety form is a later Play-only gate and is not submitted for a GitHub APK prerelease. A GitHub release still requires a final hosted privacy policy with a monitored contact and an exact-artifact privacy review.

Review current service and policy terms at release time. This worksheet is not a completed Play declaration and does not establish legal compliance.

## Repository evidence

| Surface | Current behavior | Every-channel review |
|---|---|---|
| Selected video, subtitles, reading files, dictionaries, and audio packs | Processed locally through SAF/app-private storage; no Anki Miner server | Confirm no added SDK or bridge transmits content |
| AnkiDroid collection and generated notes/media | Accessed locally through AnkiDroid's ContentProvider; committed output remains in AnkiDroid | Confirm permissions, retention, deletion wording, exact readback, and recovery |
| Required/recommended resource downloads | HTTPS request to pinned PyPI/GitHub URLs; hosts receive IP address, TLS/HTTP metadata, and resource-installer User-Agent | Confirm immutable URL/size/hash behavior and disclose network metadata |
| Jisho fallback | Off by default; when enabled, a Japanese lookup term derived from user-selected content is sent to `jisho.org`, with ordinary IP/request metadata | Confirm disclosure precedes opt-in, rate limiting/memoization remain active, and offline-only behavior works |
| Android sentence TTS | Uses only a device voice which declares itself offline; sentence text is not sent to an Anki Miner or web TTS endpoint | Confirm exact accepted voice/provider behavior and no network fallback |
| Accounts, ads, analytics, remote crash reporting | None in the current source revision | Reconfirm from the exact dependency and artifact inventory |
| GitHub distribution and feedback | GitHub processes release downloads and issue submissions under GitHub's terms; users choose what they submit | Link GitHub's policy and warn testers not to submit private media, subtitles, Anki data, logs, identifiers, or credentials |

Do not describe the app as having no network activity. Required resources contact PyPI/GitHub, and enabled Jisho lookup transmits a Japanese term. The exact privacy classification and language require release-owner and legal review.

## Required for every distributed binary

- [ ] Exact signed artifact SHA-256 and dependency inventory reviewed.
- [ ] Final privacy policy URL is public without login, linked in-app, and names a monitored privacy contact.
- [ ] Jisho egress, resource-host metadata, GitHub distribution/feedback, and local/offline processing are accurately described.
- [ ] User deletion explanation distinguishes app-private data from notes/media already written to AnkiDroid.
- [ ] Tester/support instructions warn against submitting private user content or identifiers.
- [ ] Reviewer, UTC review date, exact artifact, and policy hash are recorded.

## Google Play only

- [ ] Each applicable Play data type, purpose, optional/required status, collection/sharing status, retention/ephemeral status, and transport-security answer is recorded under current definitions.
- [ ] Jisho egress is explicitly present in both policy and Play declaration.
- [ ] Resource-host network metadata and GitHub-hosted policy/support surfaces were considered.
- [ ] Submitted Play form export or screenshots are stored as private release evidence without user data or credentials.
- [ ] Final Play Console status and reviewer are recorded.
