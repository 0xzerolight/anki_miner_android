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
reviewed `40f2f5c` pin. Both SHA-256 and Git-blob identities bind the desktop
sources, and the fixture records the hashes of the actual materialized files.
Any upstream exporter change requires an explicit rebase in
`golden_exporter_overlay.py`.

`.github/workflows/parity-nightly.yml` separately derives desktop
HEAD and reports semantic case drift as an early warning artifact.
`run_head_goldens_v2.py` materializes desktop HEAD's exporter outside its clean
checkout and changes only the unique pinned-revision assignment to that exact
HEAD. This advisory path never replaces the attested overlay or pinned fixture
used by release gates.

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

```bash
python tools/engine-sync/run_goldens.py \
  --python /path/to/desktop/.venv/bin/python \
  --exporter /path/to/desktop/scripts/dump_engine_goldens.py \
  --engine-root /path/to/clean/pinned/desktop/checkout
```

The runner uses a scrubbed home and environment, disables user-site and unsafe
path injection, prevents bytecode writes, and verifies that `PYTHONHASHSEED=0`
was applied at interpreter startup. `--check` re-derives the fixture and fails
on any byte drift without rewriting the committed output.
