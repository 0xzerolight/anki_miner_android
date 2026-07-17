# Test a GitHub prerelease

Anki Miner prereleases are unfinished test builds. Use disposable or backed-up source files and an AnkiDroid collection whose changes you can inspect. Do not use copyrighted or private content which you would be unable to describe safely in a public report.

No APK is approved yet. When one is published, install and verify it through [INSTALL.md](INSTALL.md). Test the exact signed APK; a debug build, emulator APK, workflow artifact, or locally rebuilt APK is different evidence.

## Record the environment

Before testing, record without publishing personal identifiers:

- Anki Miner version name/code, APK SHA-256, and signer-certificate SHA-256
- Android version/API level, device manufacturer/model, RAM class, and 4 KiB or 16 KiB page size when known
- AnkiDroid version and where it was installed from
- available storage before setup and after resource installation
- offline Japanese TTS engine and voice, if tested
- selected source type and a privacy-safe description of its format and approximate size

Do not record an ADB serial, account identifier, private filesystem path, full build fingerprint tied to a personal device, or the contents of source material in a public issue.

## First-run and resource setup

1. Start with AnkiDroid absent. Confirm Anki Miner explains that it is required and does not proceed as though the collection were available.
2. Install and open AnkiDroid, finish onboarding, then return and grant database access.
3. Create the Anki Miner note type. Confirm a same-name conflicting model is reported rather than overwritten.
4. Start the required UniDic download. After progress is visible, force-stop Anki Miner from Android system settings. Relaunch and retry/resume.
5. Confirm the valid partial download is reused, the completed archive and installed tree are verified, and there is no duplicate resource or stuck operation.
6. Repeat interruption around the recommended dictionary download/import boundary. A verified partial archive may resume; an incomplete transactional import must either finish safely or retain the previous slot.
7. Test insufficient storage, offline start, corrupt resource, cancel, verify/repair, and a custom Yomitan import.

For an operator-run acceptance record, repeat the force-stop test with an explicit ADB force-stop and capture only redacted timing, byte-count, state, and hash evidence. Do not publish raw device or path data.

## Video mining

Use a small local video and matching subtitles for the first run.

- select both files through the Android document picker;
- inspect preflight and progress without blocking the main UI;
- rotate at curation and confirm candidates/selections remain stable;
- test one selected word, several selected words, an empty selection, and cancellation;
- during media extraction, background and lock the device, then return;
- verify notification and in-app progress/cancel control the same run;
- inspect the final AnkiDroid note, card, tags, sentence, definition, screenshot, and playable audio;
- confirm completion/cancel leaves no foreground service or FFmpeg child;
- repeat once with notification permission denied;
- repeat with a non-seekable or cloud document provider when available.

## Reading mining

Create and inspect at least one card from every supported source:

- plain TXT or Aozora-formatted text;
- subtitle-as-reading input;
- EPUB, including a cover or embedded image when present;
- Mokuro `.mokuro` with one same-stem `.zip` or `.cbz` image archive.

For each source, test curation, empty selection, cancellation, duplicate/known-word filtering, final field content, images, and cleanup. Mokuro folder selection and multi-volume image graphs are not supported; test the documented one-sidecar/one-archive workflow.

## Optional language and audio resources

- import at least two dictionaries and verify enabled order changes which definition is selected;
- confirm remote dictionary images are not fetched into notes;
- import rank and categorical frequency sources and verify priority/filter behavior;
- import pitch-accent data and inspect the configured label format;
- test imported known words, the Anki known-word cache, and bundled proper-name wordsets independently;
- import two local expression-audio packs and verify priority without network audio;
- enable offline Japanese TTS for reading, verify playable sentence audio, then remove/disable the voice and confirm the card remains usable with a warning;
- confirm Jisho is off by default, disclosure appears before enabling it, repeated terms are bounded, and disabling it restores offline-only behavior.

## Failure, lifecycle, and compatibility

- cancel during probe, extraction, media insertion, and note insertion;
- force-stop during curation, media work, resource download, and resource import, then inspect relaunch/recovery;
- exercise screen-off work, process recreation, foreground-service timeout, low storage, and revoked SAF access;
- test AnkiDroid force-stop, permission revocation, upgrade, model conflict, and interrupted mutation remediation;
- verify API 26 and current API 36, 4 KiB and 16 KiB page-size environments, and at least one representative 3–5 GiB ARM64 device;
- review portrait/landscape rotation, dark theme, largest practical font size, TalkBack order/labels, keyboard or switch navigation where available, empty states, confirmations, and errors.

## Report a result

Search existing issues before filing a new bug. Report one problem per issue and include:

- exact Anki Miner and AnkiDroid versions;
- Android API and device model, without serial or account information;
- source kind and a synthetic/privacy-safe description;
- reproducible steps, expected result, and actual result;
- whether the result reached AnkiDroid and what was committed;
- whether retry, repair, or relaunch changed the outcome;
- sanitized screenshots or the smallest relevant log excerpt only when necessary.

Do not attach source media, full subtitles/books, dictionary archives, Anki exports/collections, recovery databases, raw logcat, URI grants, signing material, access tokens, email addresses, device serials, or private paths. If sanitization is uncertain, describe the symptom without the attachment. Security vulnerabilities use the private process in [SECURITY.md](SECURITY.md), not a public issue.
