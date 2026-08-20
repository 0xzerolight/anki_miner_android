# Architecture

Anki Miner for Android pairs a Kotlin / Jetpack Compose UI with a
Chaquopy-embedded Python engine synchronized from the desktop
[Anki Miner](https://github.com/0xzerolight/anki_miner).

```
Compose UI → ViewModels → Kotlin services → JSON bridge → vendored Python engine
```

## Layers

- **UI** — Jetpack Compose screens (`Video`, `Audio`, `Reading`, `Settings`) with
  ViewModels holding screen state. The `Audio` lane runs the same pipeline as
  `Video` against an audio file and a transcript, without frame capture.
- **Kotlin services** — mining orchestration, resource management, and a
  foreground service for post-curation media processing.
- **JSON bridge** — a string-in/string-out boundary that hands work to Python
  and adapts engine callbacks (progress, curation, Anki I/O) back to Kotlin.
  Curation has two distinct outcomes: a `None` result cancels the whole run,
  while an empty list means the user selected zero words and the run continues
  to a zero-card result.
- **Python engine** — the vendored desktop engine under
  `app/src/main/python/anki_miner/`, generated from
  `tools/engine-sync/engine.lock`. **Do not edit it directly** — fix upstream in
  the desktop repo or add an override; the sync tool owns that boundary.

## AnkiDroid integration

Cards are written to AnkiDroid on the same device through its local
ContentProvider. The user selects an existing note type; Android verifies that
target and may create only the selected target deck. Writes use exact readback
and durable mutation recovery.

Card and collection operations do not use a network backend. Expression audio
comes only from imported local packs — folder packs extracted from the
local-audio-yomichan collection, or a registered `android.db` whose entries and
audio blobs are read in place. `android_bridge/config_map.py` pins the chain to
the snapshot's pack entries (the desktop default names cut network kinds), and
`android_bridge/mining.py` builds the fetchers in config order. The former
loopback `localaudio` source (AnkiConnect-Android, localhost:8765) was removed
on 2026-08-20; nothing in the app performs loopback HTTP.

Historical correction (2026-07-21): commit `99058d7` superseded the 2026-07-17
completion checkpoint's app-owned note-model statement. The checkpoint remains
historical evidence; the user-owned note-type behavior above is current.

## Timing-preview decision

The pre-run subtitle timing workbench compares its working offset against
unshifted timing (`0.0`) in A/B mode. This deliberately differs from the
desktop workbench, whose A side starts from its initial offset: Android follows
the S4 requirement that A/B expose the source cue timing directly.

## Project documentation

Use current code, tests, this architecture overview, and `CHANGELOG.md` as
evidence of current behavior.

## Native components

- Tokenizer and CPython runtime wheels are vendored under `app/wheels/`.
- `ffmpeg`/`ffprobe` ship as PIE executables under `app/src/main/jniLibs/`.
- The UniDic tokenizer dictionary is downloaded once after install into private
  storage (never bundled in the APK).

See [CONTRIBUTING.md](CONTRIBUTING.md) for building from source and
[NOTICE.md](NOTICE.md) for the third-party component inventory.
