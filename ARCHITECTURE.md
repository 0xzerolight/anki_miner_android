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
  Three `composition.toml` keys cover the language registry: `languages` (the
  codes to vendor), `[[dynamic_imports]]` (each non-literal `import_module`
  site, with the per-language modules it reaches) and `deferred_unavailable`
  (function-local imports of modules never vendored: ASR, subtitle retiming,
  the desktop GUI). Each fails closed.

## AnkiDroid integration

Cards are written to AnkiDroid on the same device through its local
ContentProvider. The user selects an existing note type; Android verifies that
target and may create only the selected target deck. Writes use exact readback
and durable mutation recovery.

Card and collection operations do not use a network backend. Japanese
expression audio comes only from imported local packs — folder packs extracted from the
local-audio-yomichan collection, or a registered `android.db` whose entries and
audio blobs are read in place. `android_bridge/config_map.py` pins the chain to
the snapshot's pack entries (the desktop default names cut network kinds), and
`android_bridge/mining.py` builds the fetchers in config order. The former
loopback `localaudio` source (AnkiConnect-Android, localhost:8765) was removed
on 2026-08-20; nothing in the app performs loopback HTTP. Other languages may
also use the device voice (see [Word audio](#word-audio)).

Historical correction (2026-07-21): commit `99058d7` superseded the 2026-07-17
completion checkpoint's app-owned note-model statement. The checkpoint remains
historical evidence; the user-owned note-type behavior above is current.

## Mining languages

The desktop language registry (`anki_miner/languages/`) is vendored for the 32
codes in `composition.toml`. Each code has a profile: display name,
capabilities, script (zh) or regional (pt) variants, text direction, its own
tokenizer, and the first-visit value of every language-scoped setting. Japanese
keeps the S1a MeCab tagger over the downloaded UniDic and is the default
wherever a request names no language.

- `android_bridge/languages.py` is the bridge's one language seam. It refuses a
  code the registry did not build (`unsupported_language`) rather than let the
  engine mine it as Japanese. `language.profiles` returns a JSON view of every
  profile, with an Android reason code when a language cannot mine yet
  (`language_data_required`, `language_unsupported`); engine text never crosses.
- Switching mirrors desktop `languages/switching.py`. Kotlin's `LanguageScope`
  parks the outgoing language's scoped settings in a stash, restores the
  incoming language's, and starts a first visit from the profile's defaults.
  The stash stays in Kotlin; only `language` crosses the bridge.
- Known words are per language: Japanese keeps `known_words.db`, every other
  language gets `known_words.<lang>.db`.
- Dictionary, frequency, pitch and audio-pack slots are stamped with the
  language they were imported for. A rebuild keeps the slot's stamp; unstamped
  legacy slots are Japanese.

### Resource catalogs and language data

Pinned downloads live in one catalog per language,
`android_bridge/resource_catalog/<code>.json` (schema 3). `ja.json` holds the
former single catalog unchanged, and resource ids are unique across catalogs.
Besides dictionaries and frequency and pitch lists, a catalog can pin
`language-data`: the models and tables a language needs before it can mine
(CAMeL for ar, hazm for fa, the spaCy pipelines, pymorphy3 dictionaries, the
Kiwi model, and the vi and yue models split out of their APK wheels).
`tools/language-data/generate_language_data.py` copies these pins from the
vendored `pack.py` manifests, so an engine re-pin that moves a pack fails its
`--check`.

Engine code ships in the APK; only data is downloaded. Kotlin fetches the
pinned archive, and `resource.languagedata.install` re-verifies its hash,
refuses the whole archive if the vendored extractor would write any `.py`,
`.pyc` or `.so` member or an escaping path, and only then runs that extractor
into `language_packs/<code>/`. Nothing is added to `sys.path`: the engine
reads installed data by path.

### Word audio

Every non-Japanese profile defaults its word audio to Google or Edge
read-aloud, both cut. `config_map.py` puts `android_tts` in their place:
`android_bridge/word_audio.py` speaks the word with the device's offline
TextToSpeech voice through the callback sentence audio already uses, and copies
the result under a per-word media name. Portuguese picks a pt-BR or pt-PT
voice from its regional variant. Imported packs still work for every language.

## Timing-preview decision

The pre-run subtitle timing workbench compares its working offset against
unshifted timing (`0.0`) in A/B mode. This deliberately differs from the
desktop workbench, whose A side starts from its initial offset: Android follows
the S4 requirement that A/B expose the source cue timing directly.

## Project documentation

Use current code, tests, this architecture overview, and `CHANGELOG.md` as
evidence of current behavior.

## Native components

- Tokenizer and CPython runtime wheels are vendored under `app/wheels/`:
  `common` plus one group per ABI.
- `tools/runtime-wheels/` builds the native wheels from source with the locked
  Chaquopy builder, extended for Rust (maturin), CMake and meson. Two clean
  builds must match byte for byte before a publication is accepted. Wheels are
  grouped into families: the base runtime, f1 tr (zeyrek), f2 the spaCy
  languages with pymorphy3 for ru and uk, f3 ko (kiwipiepy), f4 vi and yue, and
  f5 zh. Pure-Python pack wheels are repacked with the desktop `pack.py`
  excludes (`repack_wheels.py`).
- Every native `.so` must have 16 KiB `PT_LOAD` alignment, and on arm64 an ISA
  audit rejects any instruction beyond ARMv8.0 that no runtime check guards.
- `ffmpeg`/`ffprobe` ship as PIE executables under `app/src/main/jniLibs/`.
- The UniDic tokenizer dictionary (Japanese only) is downloaded once after
  install into private storage (never bundled in the APK).

See [CONTRIBUTING.md](CONTRIBUTING.md) for building from source and
[NOTICE.md](NOTICE.md) for the third-party component inventory.
