# Architecture

Anki Miner for Android pairs a Kotlin / Jetpack Compose UI with a
Chaquopy-embedded Python engine synchronized from the desktop
[Anki Miner](https://github.com/0xzerolight/anki_miner).

```
Compose UI → ViewModels → Kotlin services → JSON bridge → vendored Python engine
```

## Layers

- **UI** — Jetpack Compose screens (`Video`, `Reading`, `Settings`) with
  ViewModels holding screen state.
- **Kotlin services** — mining orchestration, resource management, and a
  foreground service for post-curation media processing.
- **JSON bridge** — a string-in/string-out boundary that hands work to Python
  and adapts engine callbacks (progress, curation, Anki I/O) back to Kotlin.
- **Python engine** — the vendored desktop engine under
  `app/src/main/python/anki_miner/`, generated from
  `tools/engine-sync/engine.lock`. **Do not edit it directly** — fix upstream in
  the desktop repo or add an override; the sync tool owns that boundary.

## AnkiDroid integration

Cards are written to AnkiDroid on the same device through its local
ContentProvider, using an app-owned note model with exact readback and durable
mutation recovery. There is no network round-trip to Anki.

## Native components

- Tokenizer and CPython runtime wheels are vendored under `app/wheels/`.
- `ffmpeg`/`ffprobe` ship as PIE executables under `app/src/main/jniLibs/`.
- The UniDic tokenizer dictionary is downloaded once after install into private
  storage (never bundled in the APK).

See [CONTRIBUTING.md](CONTRIBUTING.md) for building from source and
[NOTICE.md](NOTICE.md) for the third-party component inventory.
