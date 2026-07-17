# Changelog

All notable project changes will be recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and releases will use semantic versioning once a public version exists.

## [0.1.0] - 2026-07-17

First alpha release: a signed ARM64 APK distributed on GitHub Releases.

### Added

- Android application shell, first-run setup, persistent settings, local video/subtitle mining, and TXT/Aozora, subtitle, EPUB, and Mokuro reading mining with paged vocabulary curation.
- First-party Anki Miner note model provisioning, ContentProvider note/media export, durable mutation recovery, explicit remediation, and notification routing back to the active run.
- Private dictionary, frequency, pitch-accent, known-word, proper-name wordset, and expression-audio resources with import, validation, recovery, attribution, and configurable provider priority.
- Offline Japanese Android TTS sentence audio for reading cards, with no network-voice fallback.
- Post-curation media-processing foreground execution with progress, cancellation, timeout handling, and a policy evidence checklist.
- Pinned Chaquopy runtime, synchronized Python engine, external UniDic installation, native tokenizer and FFmpeg tooling, golden parity fixtures, and host/emulator acceptance harnesses.
- Durable, bounded SAF staging for seekable and non-seekable providers, reading archives, and media inputs.
- GPL-3.0-or-later licensing with a bundled third-party NOTICE and an in-app privacy summary.
- Desktop-derived reading parity for Aozora, subtitle, EPUB, and Mokuro input,
  including packaged Android replay of a real reading card.
- Resumable resource downloads across process death, setup readiness refresh,
  long-list curation virtualization, and privacy-safe tester diagnostics.

### Security

- Resource downloads require HTTPS and immutable size/hash verification.
- Custom resource parsing, archive extraction, bridge messages, callbacks, and card HTML are bounded and fail closed; remote dictionary media URLs are not permitted in generated notes.
- Media probe cancellation kills and reaps ffprobe/ffmpeg children.

[0.1.0]: https://github.com/0xzerolight/anki_miner_android/releases/tag/v0.1.0
