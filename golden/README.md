# Engine golden contract

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
- `runtime`: Python/platform/dependency identity and its canonical hash;
- `data`: corpus plus tokenizer/dictionary asset hashes and their canonical hash.

The exporter must run against an explicit, clean `--engine-root`. Its isolated
worker inserts only that root for `anki_miner` imports, starts in a temporary
working directory, and rejects an imported engine outside the requested root.
This prevents a developer's editable install or current checkout from silently
supplying the fixtures.

Run the desktop exporter through the Android-side verifier:

```bash
python tools/engine-sync/run_goldens.py \
  --python /path/to/desktop/.venv/bin/python \
  --exporter /path/to/desktop/scripts/dump_engine_goldens.py \
  --engine-root /path/to/clean/pinned-desktop-checkout
```

Use `--check` in CI to derive into a temporary file, validate every provenance
domain and token offset, and byte-compare canonical JSON with the committed
fixture. The committed fixture is generated only after the exporter revision is
final; schema and seed corpus changes remain separate from generated output.
