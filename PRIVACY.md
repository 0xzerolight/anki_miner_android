# Privacy policy draft

Last updated: 2026-07-17

This document describes the behavior of the current Anki Miner for Android source revision. It is release groundwork, not yet the hosted Play Store privacy policy. Before publication, the release owner must add a monitored privacy contact, publish this policy at a stable public URL, review it against the exact signed artifact, and record that URL in the release record.

## Data processed on the device

Anki Miner processes video, subtitle, reading, dictionary, and audio-pack files which the user selects through Android's Storage Access Framework. It may retain Android URI permissions for selected files. Processing outputs, downloaded language resources, settings, recovery journals, caches, and staged Anki media are stored in app-private storage unless the user sends a generated note or media file to AnkiDroid.

The app accesses AnkiDroid's local ContentProvider, with user-granted permission, to inspect relevant collection structure and known vocabulary and to create decks, models, notes, and media. It does not upload the Anki collection to an Anki Miner service. Notes and media successfully added to AnkiDroid remain under AnkiDroid's control after Anki Miner is uninstalled.

This source revision contains no Anki Miner account system, advertising SDK, analytics SDK, or remote crash-reporting SDK.

## Network activity

Required and recommended language resources are downloaded over HTTPS from immutable catalog URLs. The current catalog uses `files.pythonhosted.org` for UniDic Lite and GitHub release hosting for the recommended Jitendex dictionary. Those hosts receive ordinary network information such as the user's IP address, TLS connection metadata, and the app's resource-installer User-Agent. Downloaded bytes are checked against catalog size and SHA-256 values before installation.

Jisho definition fallback is disabled by default. If the user explicitly enables it and local dictionaries do not satisfy a lookup, the app sends the Japanese lookup term, which may be derived from selected subtitle or reading content, to `https://jisho.org`. Jisho also receives ordinary network information such as the user's IP address and HTTP request metadata. The fallback is designed not to send the selected video, audio, complete subtitle file, complete sentence, Anki collection, note identifiers, or account identifiers. Android enforces at least one second before each request and memoizes a distinct term for the rest of that mining run so definition and glossary generation do not transmit it twice.

Imported dictionary definitions can contain ordinary hyperlinks. Anki Miner blocks automatic remote dictionary image loads, but it preserves unrelated links which require an explicit user action. Following such a link from a rendered card is handled by AnkiDroid's web view or the user's browser and contacts the linked host under that software's controls and policies.

Optional reading sentence audio uses Android's local `TextToSpeech` service. The app accepts only an installed Japanese voice which declares that it does not require a network connection; it does not fall back to gTTS, Google web endpoints, JPod101, or another network voice provider. Android system voice packages remain governed by the user's device and voice-provider settings.

The bundled FFmpeg tools are built with network protocols disabled. The separate AnkiconnectAndroid artifact recorded under `third_party/` is a development capability probe and is not embedded as a production exporter.

Third-party services process data under their own policies. Relevant services include [Jisho](https://jisho.org/), [Python Package Index](https://policies.python.org/pypi.org/Privacy-Notice/), and [GitHub](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).

## Retention and deletion

The app retains settings and installed resources until the user replaces them, clears app storage, or uninstalls the app. Temporary archives and media staging are removed through normal completion and recovery paths, but failures may leave private recovery records until reconciliation succeeds. Clearing app storage or uninstalling removes app-private data. It does not remove notes or media already committed to AnkiDroid; users must manage those in AnkiDroid.

Android or the selected document provider controls persisted file-access grants. Users can revoke app permissions through Android settings.

## Security

The app disables cleartext network traffic in its production manifest, uses HTTPS for resource downloads and Jisho, verifies catalog resources cryptographically, and keeps working data in app-private storage. No security measure eliminates all risk. Please report vulnerabilities through the private process in [SECURITY.md](SECURITY.md).

## Contact and release review

Pre-release questions may be opened in the [project issue tracker](https://github.com/0xzerolight/anki_miner_android/issues). Do not report security vulnerabilities in a public issue.

Release blocker: replace this paragraph with a monitored privacy email and the final hosted-policy URL before any closed, open, or production Play track. The exact Data Safety assessment must be recorded using [release/DATA_SAFETY.md](release/DATA_SAFETY.md).
