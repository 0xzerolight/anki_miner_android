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

## Golden derivation

`run_goldens.py` launches the desktop exporter under the explicitly selected
Python interpreter, independently probes that interpreter's dependency
versions, and compares the probe with the fixture provenance. It resolves
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
