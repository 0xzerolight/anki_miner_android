# Desktop engine vendor

The generated Python engine comes from
[`0xzerolight/anki_miner`](https://github.com/0xzerolight/anki_miner) at the full
commit in `engine.lock`. `composition.toml` records reviewed composition roots,
third-party boundaries, assets, and the complete overlay allowlist. Run:

```bash
python tools/engine-sync/sync_engine.py
python tools/engine-sync/sync_engine.py --check
```

Source discovery works in both the main Android checkout and Git worktrees. An
explicit `--source-repo PATH` wins; otherwise set `ANKI_MINER_DESKTOP_REPO` when
the desktop checkout is not the Android repository's sibling.

The upstream root `LICENSE` is copied byte-for-byte to
`app/src/main/python/anki_miner/LICENSE`. The generated manifest maps every
destination to its pinned desktop source or reviewed overlay, including that
root-license mapping. The vendored engine is GPL-3.0-or-later; corresponding
source is the repository and exact commit named above.

Never edit generated files directly. Change a reviewed file under `overrides/`
or update the pinned composition and rerun the sync. Unallowlisted, missing, or
unused overlays fail the sync so a misspelled divergence cannot silently ship.

The current `media_extractor.py` overlay is the pinned desktop file with the
deferred ASR `wav_to_float32`/NumPy helper removed, Android SAF child-process
inheritance, and a per-run cancellation registry covering encoder probes,
ffprobe, and ffmpeg. `audio_track_detector.py` accepts that registry and uses a
cancellable, always-reaped `Popen` ffprobe path. Focused process tests exercise
spawn/cancel/timeout races and single-flight stream probing; whole-file hashes
freeze the reviewed overlays. `overlay_base_blobs` still binds each shadow to
its upstream Git blob, so an `engine.lock` bump fails until the overlay is
rebased and reviewed.

## Golden derivation

The current complete contract is v2. `run_goldens_v2.py` first verifies the
exact committed fixture and every provenance input, executes the desktop v2
exporter in a scrubbed process, and byte-compares the result. It requires an
explicit clean engine checkout at `engine.lock` and the exact external UniDic
tree recorded by the fixture:

```bash
PYTHONPATH=tools/engine-sync python tools/engine-sync/run_goldens_v2.py \
  --python /path/to/desktop/.venv/bin/python \
  --exporter /path/to/desktop/scripts/dump_engine_goldens.py \
  --engine-root /path/to/clean/pinned/desktop/checkout \
  --dicdir /path/to/unidic_lite/dicdir
```

The exporter source is independently pinned by file hash, so pointing this
command at a newer desktop exporter fails rather than silently changing the
contract. The frozen desktop v2 contract still names the earlier Android
engine revision. Before derivation, the runner therefore materializes an
attested exporter trio and changes only that revision constant to `engine.lock`'s
reviewed `6f57f83` pin. Both SHA-256 and Git-blob identities bind the desktop
sources, and the fixture records the hashes of the actual materialized files.
Any upstream exporter change requires an explicit rebase in
`golden_exporter_overlay.py` — all three constants for the changed file:
`SOURCE_ATTESTATIONS` (its SHA-256 **and** its Git blob), `MATERIALIZED_SHA256`,
and `ANDROID_REVISION_LINE`. The attestation is verified first, so updating only
the revision line leaves the run failing at "changed since review".

`run_head_goldens_v2.py` derives desktop HEAD and reports semantic case drift.
It materializes desktop HEAD's exporter outside its clean checkout and changes
only the unique pinned-revision assignment to that exact HEAD. Unlike every
sibling runner it takes `--desktop-root` and a required `--output`, not
`--engine-root`/`--exporter`:

```bash
PYTHONPATH=tools/engine-sync python tools/engine-sync/run_head_goldens_v2.py \
  --python /path/to/desktop/.venv/bin/python \
  --desktop-root /path/to/desktop/checkout \
  --dicdir /path/to/unidic_lite/dicdir \
  --output /tmp/head-goldens-v2.json
```

No hosted nightly HEAD-parity workflow exists yet, so this remains an advisory
local tool rather than a current CI or release gate. It never replaces the
attested overlay or pinned fixture used by release gates.

Hosted derivation is not absent altogether: the desktop repository carries
`.github/workflows/android-engine-goldens.yml`, which derives the v2 contract in
a hash-frozen runtime and byte-compares it against the desktop-side committed
fixture. It is paths-scoped push/PR plus `workflow_dispatch`, deliberately not
nightly and deliberately never a required status check. It derives a pinned
revision (dispatch can override it), not desktop HEAD, and it verifies the
desktop copy of the fixture — which currently pins an earlier revision than this
repository's `engine.lock`. It is therefore not a check on the Android fixture.

`run_reading_goldens.py` owns the separate M4 reading contract. It derives
Aozora, subtitle, EPUB, and Mokuro loader snapshots plus a real Mokuro
`process_reading` card from the clean pinned desktop engine; the packaged
instrumentation replay uses Android staging and loader limits before comparing
the same semantic output.

### Historical v1 derivation

`run_goldens.py` launches the desktop exporter under the explicitly selected
Python interpreter, independently hashes that interpreter's complete stable
distribution contents (including native wheel files), and compares the probe
with the fixture provenance. It resolves
UniDic from `unidic-lite` unless `--dicdir` is supplied, always passes the
resolved directory to the exporter, and records it under the reserved
`unidic_dicdir` asset name.

`engine-v1.json` is frozen at desktop revision `ba3b3cf`, which predates the
`6f57f83` in `engine.lock`. `run_goldens.py` takes its expected revision from
`--lock`, so reproducing v1 means pointing both `--engine-root` and `--lock` at
`ba3b3cf`; against the current lock the run fails the revision check. Pass
`--check` too — `--output` defaults to the committed fixture, so a run without
it rewrites the frozen file instead of verifying it.

```bash
python tools/engine-sync/run_goldens.py \
  --python /path/to/desktop/.venv/bin/python \
  --exporter /path/to/desktop/scripts/dump_engine_goldens.py \
  --engine-root /path/to/ba3b3cf/desktop/checkout \
  --lock /path/to/ba3b3cf.lock \
  --check
```

The runner uses a scrubbed home and environment, disables user-site and unsafe
path injection, prevents bytecode writes, and verifies that `PYTHONHASHSEED=0`
was applied at interpreter startup. `--check` re-derives the fixture and fails
on any byte drift without rewriting the committed output.
