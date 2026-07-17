# Data Safety review

This is a release-owner worksheet, not a completed Play Console declaration. Google requires the developer to classify the exact artifact and all included SDK behavior. Review the current [Data Safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469) and [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311) at release time.

## Repository evidence

| Surface | Current behavior | Release review |
|---|---|---|
| Selected video, subtitles, reading files, dictionaries, and audio packs | Processed locally through SAF/app-private storage; no Anki Miner server | Confirm no added SDK or bridge transmits content |
| AnkiDroid collection and generated notes/media | Accessed locally through AnkiDroid's ContentProvider; committed output remains in AnkiDroid | Confirm permissions, retention, and deletion wording |
| Required/recommended resource downloads | HTTPS request to pinned PyPI/GitHub URLs; hosts receive IP address, TLS/HTTP metadata, and resource-installer User-Agent | Decide applicable Data Safety classification and disclosure |
| Jisho fallback | Off by default; when enabled, a Japanese lookup term derived from user-selected content is sent to `jisho.org`, with ordinary IP/request metadata | Explicitly classify collection/sharing, purpose, optionality, ephemeral handling, and user-generated/search-like content under current form definitions |
| Android sentence TTS | Uses only a device voice which declares itself offline; sentence text is not sent to an Anki Miner or web TTS endpoint | Confirm the exact device/provider behavior used in release acceptance and that no network voice fallback was introduced |
| Accounts, ads, analytics, remote crash reporting | None in the current source revision | Reconfirm from the exact dependency/artifact inventory |

Do not mark “no data collected or shared” merely because the app has no first-party server. The release owner must decide how Play's current definitions apply to Jisho lookup terms, IP addresses, and resource-host requests and must document the rationale. This assessment requires privacy/legal review.

## Required record

- [ ] Exact signed artifact SHA-256 and dependency inventory reviewed.
- [ ] Privacy policy URL and monitored privacy contact recorded.
- [ ] Each applicable Play data type, purpose, optional/required status, collection/sharing status, retention/ephemeral status, and transport security answer recorded.
- [ ] Jisho egress is explicitly present in both the policy and Play declaration.
- [ ] Resource-host network metadata was considered.
- [ ] User deletion explanation distinguishes app-private data from notes/media already written to AnkiDroid.
- [ ] Submitted Play form export or screenshots are stored as release evidence without user data or credentials.
- [ ] Reviewer, UTC review date, and final Play Console status are recorded.
