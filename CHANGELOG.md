# Changelog

All notable project changes will be recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and releases will use semantic versioning once a public version exists.

## [0.1.4] - 2026-07-20

### Added

- AV1 video support. The bundled FFmpeg now includes the dav1d decoder, so screenshots extract from AV1 releases (mkv, webm, and mp4) instead of failing every word.
- The engine writes a capped log file, and Settings gains a separate "Share engine log" action so extraction failures can finally be reported with their actual FFmpeg errors. The log can include selected file names; review it before sending — the existing tester diagnostics report stays codes-and-counts only.

### Fixed

- **Media extraction no longer fails for every word.** Selected videos are now copied into app cache before FFmpeg runs. Android's scoped storage blocked FFmpeg child processes from reopening the picked file's descriptor (`Permission denied` on `/proc/self/fd/N`), which made every audio and screenshot extraction fail on every device since 0.1.0.
- Cancelling a run while the video is still being prepared now ends it as "Cancelled" instead of "Mining stopped".

## [0.1.3] - 2026-07-20

### Added

- Expression audio now pulls from the on-device AnkiConnect-Android `localaudio` server by default, with your imported local audio packs kept as an ordered fallback. A run with `localaudio` and no packs still records expression audio.

### Changed

- Settings save automatically as you edit, matching the desktop app. The hard-to-reach Save button is gone; Restore defaults and the incomplete-number warning stay.

### Fixed

- Media (audio and screenshots) is no longer dropped with "N media file(s) could not be stored in Anki." App-data paths are now canonicalized on both sides so every media source matches the approved staging root.
- A dictionary, frequency list, or audio resource installed while Settings has unsaved edits now appears in its priority list immediately instead of only after a restart.
- Only Word/Expression is a required Anki field again. Sentence, definition, picture, audio, and the furigana fields are no longer wrongly mandatory and no longer block mining setup.
- Large Yomitan dictionaries that previously failed as "oversized" now import, and imports that fail on an unsupported compression method report an actionable, method-named error instead of a generic "corrupt" message.
- Import, resource, Anki, and save failures now appear as a snackbar instead of a card that could land off-screen higher up the Settings scroll and be missed.

## [0.1.2] - 2026-07-19

### Changed

- Anki setup now uses your own note type. Connect AnkiDroid, pick any note type you already have, and map Anki Miner's fields onto it — the same detect-and-map approach as the desktop app. Anki Miner no longer creates or manages a note type of its own.

### Fixed

- The Anki note-type setup no longer strands you on a dead "Resume verified setup" button when AnkiDroid cannot be read. Setup now guides you to connect AnkiDroid first, then select and verify a note type, and mining reports a clear, actionable reason when a note type is not yet ready instead of doing nothing.

### Removed

- First-party "Anki Miner" note-type provisioning, verification, and recovery. If an earlier build already created that note type, just select it during setup — no cards are lost.

## [0.1.1] - 2026-07-19

First published release; supersedes the internal 0.1.0 test build.

### Added

- Light/Dark theme setting (dark by default) in Settings → UI.
- JMdict (English) as a second pinned dictionary download alongside Jitendex; either, both, or neither can be installed.
- Skippable onboarding wizard (tokenizer, dictionary choice, AnkiDroid) replacing the mandatory first-run Setup tab; re-runnable from Settings → UI.
- Launcher icon ported from the desktop Anki Miner logo, with a monochrome themed-icon variant.
- Third-party license notices are bundled in the APK and viewable from Settings → Licenses → third-party notices.

### Changed

- Bottom navigation mirrors the desktop app: exactly Video, Reading, and Settings tabs, text-only, always enabled; the app starts on Video.
- All setup and resource management relocated into Settings, organised in the desktop section order (Anki, Media, Dictionaries, Audio, Frequency, Filtering, UI). Mining tabs show an inline readiness notice pointing to Settings instead of being disabled.
- Option pickers use Material segmented buttons instead of check-prefixed buttons.

### Removed

- The Setup tab and forced first-run flow.
- The legacy "Lapis" note-type migration path.

### Initial alpha implementation

#### Capabilities

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

#### Security

- Resource downloads require HTTPS and immutable size/hash verification.
- Custom resource parsing, archive extraction, bridge messages, callbacks, and card HTML are bounded and fail closed; remote dictionary media URLs are not permitted in generated notes.
- Media probe cancellation kills and reaps ffprobe/ffmpeg children.
