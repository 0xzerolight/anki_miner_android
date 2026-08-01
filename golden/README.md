# Engine golden contract

`engine-v2.json` plus `schema/engine-goldens-v2.schema.json` is the current,
complete Android engine contract. All nine sections are implemented and
non-empty: tokenization, morphology, filtering, deinflection, compounds,
dictionaries, frequency, pitch, and cards. `corpus/engine-v2-input.json` holds
the reviewed synthetic dictionary, frequency, pitch, card, and media inputs;
`corpus/tokenizer-v1.json` remains the reviewed tokenizer input shared by both
contract generations.

The v2 verifier rejects anything other than the exact committed fixture and
then independently checks its schema, section coverage, engine lock/tree,
desktop exporter source hashes, runtime/data aggregate provenance, and UniDic
tree identity. Re-derive it by executing the desktop exporter against a clean
checkout at the revision in `tools/engine-sync/engine.lock`:

```bash
PYTHONPATH=tools/engine-sync python tools/engine-sync/run_goldens_v2.py \
  --python /path/to/desktop/.venv/bin/python \
  --exporter /path/to/desktop/scripts/dump_engine_goldens.py \
  --engine-root /path/to/clean/pinned-desktop-checkout \
  --dicdir /path/to/unidic_lite/dicdir
```

The debug instrumentation APK packages the exact v2 fixture and replays every
section through the vendored Android engine in one fresh process.
`EngineGoldenV2InstrumentedTest` is assumption-gated on
`-e ankiMinerRunGoldenV2 true`, and no lane passes it — the API 26 runner
(`.github/scripts/run-api26-instrumentation.sh`) passes only `-e notClass`. So
that replay is opt-in, not a per-push gate. Neither CI nor `scripts/health.sh`
re-derives either; both run only `validate_committed_fixture`, a hash-pin check
that any self-consistent fixture passes — it does cross-check the fixture's
recorded exporter hashes against the reviewed overlay constants, so a re-pin
that never updated `golden_exporter_overlay.py` fails there, but it cannot tell
whether the recorded cases are what the engine actually produces. After an
`engine.lock` bump the re-derivation above is therefore a manual step, and its
output is the evidence.

No hosted derivation exists in either repository, by decision. The desktop repo
carried `.github/workflows/android-engine-goldens.yml` until 2026-07-31; it was
removed because it byte-compared a desktop-side *duplicate* of this fixture at a
pin older than `engine.lock`, because the artifact embeds the hash of the exporter
that produced it (so every exporter edit turned the workflow red until the
duplicate was regenerated inside its frozen runtime), and because deriving an old
pin cannot detect drift against desktop HEAD — it stayed green through two weeks
of exporter breakage against the current engine. Do not rebuild it here.

## Reading-source parity

`reading-v1.json` is the desktop-derived M4 contract for all four supported
file-backed reading sources. It freezes detector/loader output for CP932 Aozora
TXT, reading subtitles, EPUB with a cover, and Mokuro with a CBZ companion. The
same fixture runs the loaded Mokuro volume through the real
`EpisodeProcessor.process_reading`, `WordFilterService`, page-image
materialization, definition lookup orchestration, and card-payload construction.
Only the tokenizer, definition, and Anki I/O boundaries are deterministic
in-memory fixtures; the committed card evidence includes its source field and
decoded JPEG dimensions/pixel hash.

Re-derive or byte-check it against a clean desktop checkout at `engine.lock`:

```bash
PYTHONPATH=tools/engine-sync python tools/engine-sync/run_reading_goldens.py \
  --python /path/to/desktop/.venv/bin/python \
  --engine-root /path/to/clean/pinned-desktop-checkout

PYTHONPATH=tools/engine-sync python tools/engine-sync/run_reading_goldens.py \
  --python /path/to/desktop/.venv/bin/python \
  --engine-root /path/to/clean/pinned-desktop-checkout \
  --check
```

The fixture separately pins the engine tree, corpus, exporter, contract, and
support-tool hashes. `ReadingGoldenInstrumentedTest` reconstructs the sources
inside app-private storage, loads them through the packaged Android bridge, and
replays the Mokuro card. Like the v2 replay it is assumption-gated, here on
`-e ankiMinerRunReadingGolden true`, and no lane passes it — the API 26 runner
passes only `-e notClass`, and that argument name appears nowhere else in the
repository. So it too is an opt-in manual step, not a per-push gate.

## Bridge fixtures and reviewed inputs

`bridge/` holds the Anki bridge-boundary corpora. They are not engine goldens:
no exporter derives them, and no provenance block pins them.

- `bridge/anki-protocol-v1.jsonl` — 117 envelope cases (44 request, 73
  response; 40 accept, 77 reject) across the five Anki callbacks. Read by
  `tests/python/android_bridge/test_anki_protocol_corpus.py` and
  `app/src/test/kotlin/com/ankiminer/android/anki/AnkiJsonCodecBoundaryTest.kt`,
  so one corpus pins both decoders. `AnkiRequestDigestTest.kt` also probes for
  it, but only to locate the project root.
- `bridge/anki-request-digest-v1.jsonl` — 18 raw-request/canonical-form/digest
  vectors; `bridge/anki-request-digest-mutations-v1.jsonl` — 55 single-leaf
  mutations of those vectors with the digest each must produce. Both are read by
  `tests/python/android_bridge/test_request_digest.py` and
  `app/src/test/kotlin/com/ankiminer/android/anki/AnkiRequestDigestTest.kt`.

Two further files under `corpus/` are reviewed inputs rather than generated
output: `corpus/reading-v1-input.json` is the default `--corpus` of
`run_reading_goldens.py`, and `corpus/s4-engine-smoke-v1.json` is the default
`--corpus` of `run_s4_engine_smoke.py` — distinct from the derived
`s4-engine-smoke-v1.json` at this directory's root, which shares its name.

`app/build.gradle.kts:247` adds this entire directory as `androidTest` assets,
so every file here — inputs and schemas included — ships in the instrumentation
APK.

## Historical v1 and bounded S4 fixtures

`schema/engine-goldens-v1.schema.json` is the compatibility boundary between
the pinned desktop exporter and Android parity tests. A schema change requires a
new numbered schema; existing committed fixtures remain readable as v1.

`corpus/tokenizer-v1.json` is a reviewed input corpus, not generated output. It
contains the known UniDic fidelity traps and machine-readable spot assertions.
Generated fixtures normalize UniDic's `*` sentinel to JSON `null` and record
both Python code-point and JVM UTF-16 offsets.

Fixture provenance separates four independently meaningful identities:

- `engine`: pinned Git revision and the hash of the exact engine files used;
- `tool`: exporter name/version and exporter-source hash;
- `runtime`: Python/platform identity plus hashes of every stable installed
  dependency file (including sibling native libraries), and its canonical hash;
- `data`: corpus plus tokenizer/dictionary asset hashes and their canonical hash.

The exporter must run against an explicit, clean `--engine-root`. Its isolated
worker inserts only that root for `anki_miner` imports, starts in a temporary
working directory, and rejects an imported engine outside the requested root.
This prevents a developer's editable install or current checkout from silently
supplying the fixtures.

Run the desktop exporter through the Android-side verifier. `engine-v1.json` is
frozen at desktop revision `ba3b3cf`, which predates the `2d227dd` recorded in
`tools/engine-sync/engine.lock`. `run_goldens.py` takes its expected revision
from `--lock` (default: that file), so reproducing v1 means pointing both
`--engine-root` and `--lock` at `ba3b3cf` — against the current lock the run
fails the revision check. Always pass `--check` as well: `--output` defaults to
the committed `golden/engine-v1.json`, so a run without it rewrites the frozen
fixture instead of verifying it.

```bash
python tools/engine-sync/run_goldens.py \
  --python /path/to/desktop/.venv/bin/python \
  --exporter /path/to/desktop/scripts/dump_engine_goldens.py \
  --engine-root /path/to/clean/ba3b3cf-desktop-checkout \
  --lock /path/to/lockfile-containing-ba3b3cf \
  --check
```

The bounded S4 fixture is derived separately because the v1 engine golden still
marks filtering and dictionaries as staged sections. It exercises one
continuous SRT → known-word filter → rendered indexed-dictionary lookup without
changing that established schema:

```bash
python tools/engine-sync/run_s4_engine_smoke.py \
  --python /path/to/desktop/.venv/bin/python \
  --engine-root /path/to/clean/pinned-desktop-checkout \
  --dicdir /path/to/unidic_lite/dicdir

# Re-derive into a temporary file and byte-compare the committed fixture.
python tools/engine-sync/run_s4_engine_smoke.py \
  --python /path/to/desktop/.venv/bin/python \
  --engine-root /path/to/clean/pinned-desktop-checkout \
  --dicdir /path/to/unidic_lite/dicdir \
  --check
```

Its provenance independently pins the engine tree, input corpus and UniDic,
exporter/contract tools, and canonical output. Emulator timing and PSS emitted
by the corresponding instrumentation test are diagnostic only; the real ARM64
performance gate remains a physical-device measurement.

### S4 measurement record

The external dictionary tree used by the S4 emulator run contains 260,467,176
file bytes and has tree SHA-256
`bd942f1b395aa7c56fe20321dc7f021930e29107f6b2949a49f5c56caab55ea7`.
The exact archive staged on the emulator was the deterministic test artifact
produced by:

```bash
python3.13 tools/tokenizer/package_test_unidic.py \
  --dicdir "$DICDIR" \
  --golden golden/engine-v1.json \
  --output "$TOOLCHAIN_ROOT/assets/tokenizer-test-unidic.zip"
```

That ZIP is 47,653,455 bytes with SHA-256
`b1068079f99b8c87ba16d5dde6ad35111e3ec2db2eb83bd2ac5b2a2a249cd597`.
It uses sorted entries, fixed 1980 timestamps, fixed permissions, ZIP deflate
level 9, and forced ZIP64 streams. This is a test transport, not the selected
install-pack format.

Two local packaging comparisons were also measured. A reproducible GNU tar
1.35 plus Zstandard 1.5.7 level-15 stream was generated with:

```bash
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
  -cf - -C "$DICDIR" . | zstd -15 -o unidic-lite-1.0.8-dicdir.tar.zst
```

It is 40,166,147 bytes with SHA-256
`7346e40c47160298195d308be1577f4d92f0e6630745c88303ff78fbc455b116`.
An Info-ZIP 3.0 `zip -9 -X -r` comparison made from the then-current file
metadata is 47,423,880 bytes with SHA-256
`d5c78801b4222d9d8bf2ff79c52ac4f94cbf4a5aaacaeafa8607f75aab7542fc`.
It was not staged on the emulator and is not canonical because it retains DOS
entry timestamps.

S4 is not complete. The remaining measurements are frozen before the first
physical-device run:

- On a real mid-range ARM64 device with 4 GiB RAM, each of three clean-process
  runs must complete Python startup, bootstrap, dictionary registration,
  selected-tokenizer initialization, and the episode-processor import in less
  than 4.0 seconds. Peak app-process RSS during the representative mining run
  must not exceed 384 MiB.
- A representative novel corpus must run through production `process_reading`.
  The measured parser records corpus SHA-256, Japanese character count, elapsed
  phase-1 time, and fugashi characters per second from that same run; the
  processor then filters, curates a frozen 100 candidates, constructs reading
  media/card payloads, and writes them to deterministic offline sinks before
  `VmHWM` is sampled. The one-line SRT smoke or a parser-only run is not a
  substitute for this measurement.

The v1 `section_status` remains intentionally staged for historical M0
compatibility. It is no longer the complete parity claim; v2 is. Use the v1
runner only for the tokenizer/S4 contracts which still cite its corpus and
provenance.
