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
- `runtime`: Python/platform identity plus hashes of every stable installed
  dependency file (including sibling native libraries), and its canonical hash;
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
- A representative novel corpus must run through production
  `parse_text_units`; record its corpus SHA-256, Japanese character count,
  elapsed phase-1 time, and fugashi characters per second. The one-line SRT
  smoke is not a substitute for this measurement.

Use `--check` in CI to derive into a temporary file, validate every provenance
domain and token offset, and byte-compare canonical JSON with the committed
`engine-v1.json`. Its `section_status` object makes staged coverage explicit:
tokenization, morphology, and compounds are implemented in the M0 fixture;
filtering, deinflection, dictionaries, frequency, pitch, and cards remain
machine-visible pending sections rather than ambiguous empty arrays.
