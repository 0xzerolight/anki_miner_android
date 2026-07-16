# M0 shared tokenizer contract

This is the backend-neutral contract for tokenizer spikes S1a and S1b. It does
not select a winner. Both candidates must copy a parse into
`android_bridge.tokenizer_contract.TokenRecord` values before the vendored
engine sees it. No native node pointer may escape the backend call.

## UniDic features

`UNIDIC_FEATURE_FIELDS` freezes the 26-field UniDic 2.1.2 binary schema in the
same order as fugashi 1.5.2 and the desktop golden fixture. CSV decoding follows
fugashi's observable behavior: quoted commas are decoded, explicit empty fields
and literal `*` values survive through the raw `TokenRecord` and the
engine-facing `SimpleNamespace`, and only absent trailing fields become `None`.
Rows longer than 26 fields fail instead of being silently truncated. The
canonical JSON golden format alone collapses `*` to `null`; that serialization
rule must never change the object seen by the engine.

The adapter builds a mutable `SimpleNamespace` for features and a fugashi-shaped
token namespace. It also records UTF-8 byte, Python code-point, and JVM UTF-16
offsets. Token surfaces are sliced from the original input rather than trusted
from a backend. Gaps between tokens and the trailing remainder must contain
only whitespace; `rlength` must equal the token byte length plus its leading
whitespace bytes.

## Native wire v1

S1b returns a standard UTF-8 `byte[]`, not a JNI modified-UTF-8 string. All
integers are unsigned little-endian. The header is 16 bytes:

| Field | Type | Requirement |
| --- | --- | --- |
| magic | 4 bytes | `AMTK` |
| version | `u16` | `1` |
| flags | `u16` | `0` |
| input byte length | `u32` | exact UTF-8 input length |
| token count | `u32` | non-BOS/EOS records |

Each token has a 28-byte fixed prefix followed immediately by its feature CSV:

| Field | Type | Requirement |
| --- | --- | --- |
| byte start | `u32` | UTF-8 boundary, inclusive |
| byte end | `u32` | UTF-8 boundary, exclusive |
| raw length | `u32` | token plus leading whitespace bytes |
| POS id | `u32` | MeCab value, restricted to `u16` |
| character type | `u32` | MeCab value, restricted to `u8` |
| status | `u8` | `0` normal or `1` unknown |
| reserved | 3 bytes | all zero |
| feature byte length | `u32` | at most 1 MiB |
| feature CSV | bytes | strict UTF-8, one NUL-free row |

The decoder rejects unsupported versions, unknown flags, overflow-shaped
counts, truncated fields, invalid UTF-8, overlap, non-boundary offsets,
non-whitespace omissions, and trailing bytes. For `猫𠮟𠮟𠮟犬`, the OOV span
must map byte `3..15` to code-point `1..4` and UTF-16 `1..7`.

## External dictionary registration

UniDic remains external to the application package. On the parked Python
worker, call `register_unidic` with an absolute extracted directory, a resource
catalog id, and the trusted canonical tree SHA-256. Registration verifies every
path/length/content frame, rejects symlinks and special files, requires the six
runtime dictionary files, and freezes the identity for the process. The
canonical framing is:

```text
u64be(relative-path byte length) || relative-path UTF-8
u64be(file byte length)          || file bytes
```

Files are ordered by POSIX relative path. Hashing the roughly 250 MiB installed
tree is intentionally an off-main-thread operation. An identical registration
is idempotent; changing the path, id, or hash requires a fresh process. Resource
installers must use versioned directories and must never mutate a registered
tree. After backend creation, pass its dictionary-info filenames to
`validate_loaded_dictionary_filenames`; v1 permits exactly the registered
`sys.dic` and no user dictionary.

`RegisteredUniDic.mecab_arguments` yields explicit
`-r <dicdir>/mecabrc -d <dicdir>` option elements for S1a, which may format
them for fugashi only inside its backend module. S1b must pass the complete
`RegisteredUniDic.mecab_new_argv` to `mecab_new`: it prepends the required
non-option `argv[0]` program name and `-C`, matching fugashi's copied-node
allocation mode. Passing `mecab_arguments` directly is invalid because MeCab
does not parse `argv[0]` as an option. Neither backend may import Java, fugashi,
or the engine from the shared contract modules.

## M0 decision status

S1a is the leading implementation candidate based on host and x86 emulator
correctness evidence. No tokenizer is selected for M0 or release until physical
ARM64 parity, cold-init, throughput, and RSS gates pass. Failure reopens S1b or
mitigation evaluation. The process-immutable shared-tagger selector remains in
place so both production seams can be tested against the same contract.

| Gate | S1a result | Evidence |
| --- | --- | --- |
| Host contract and engine regression | pass | `scripts/health.sh` with S1a publication `95f6024a…`: health OK |
| x86_64, 4 KiB | pass | API 36 `anki_miner_api36`: 6 connected tests passed |
| x86_64, 16 KiB | pass | API 36 `anki_miner_api36_ps16k`: 6 connected tests passed |
| arm64 build/package/static ELF | pass | reproducible wheel, APK and AAB artifact gates |
| arm64 runtime | pending supported target | no arm64 runtime is available on this x86_64 host |
| M0 selection | not selected | requires physical ARM64 parity and S4 performance/RSS gates |

After the licensed wheel build emits its manifest, run both owned x86_64 lanes
with `scripts/run-s1a-emulator-tests.sh --manifest FILE --unidic-dir DIR`.
Run the provisional arm64 gate only against an explicitly chosen target with
`scripts/run-s1a-arm64-tests.sh --serial SERIAL --manifest FILE --unidic-dir DIR`.

## S1b verification

The host-native gate compiles the pinned MeCab runtime, proves `sys.dic` and
`matrix.bin` are present in `/proc/self/maps`, and compares every seeded case
and all 26 fields against the committed desktop golden. It separately proves
that explicit `*` and actually absent fields remain distinct at the engine
boundary, then drives the seeded `走り出した` case through native MeCab, the
S1b tagger adapter, and the vendored compound pipeline:

```bash
tools/tokenizer/test-s1b-native-host.sh /absolute/path/to/unidic/dicdir
```

The supplied dictionary is accepted only when its canonical tree hash equals
`golden/engine-v1.json`'s `provenance.data.assets_sha256.unidic_dicdir`.

The Android x86_64 success-path test uses the same external dictionary without
adding it to either APK. After the user has accepted the Android SDK license
and the locked emulator is installed, run:

```bash
scripts/run-emulator-tests.sh \
    --receipt /absolute/path/to/a/host-prepared-receipt.json \
    --unidic-dir /absolute/path/to/the/golden-pinned/unidic/dicdir \
    --page-size 4k
```

Create that receipt first, with every emulator stopped, using
`scripts/prepare-emulator-tests.sh --receipt FILE`.

The connected gate verifies the dictionary on the host, writes a deterministic
temporary ZIP to `/data/local/tmp`, and streams it into a versioned app-private
test directory. Python verifies the extracted tree again before opening it.
The instrumented test then traverses Chaquopy, the real vendored-engine shared
tagger seam, the Python S1b adapter, Kotlin, JNI, and native MeCab for all
seeded cases, including the astral OOV UTF-16 span, and checks both dictionary
mappings in the Android process. The temporary device ZIP is removed when the
gate exits.

S1b remains provisional until that same production-JNI class passes on an
explicitly named arm64 target. Record the expected image fingerprint outside
the target first, then run either page-size lane with:

```bash
scripts/run-s1b-arm64-tests.sh \
    --serial ARM64_SERIAL \
    --unidic-dir /absolute/path/to/the/golden-pinned/unidic/dicdir \
    --page-size 16k \
    --image-fingerprint EXPECTED_BUILD_FINGERPRINT
```

This opt-in gate enables only `deviceDebug`, checks the serial is an online
API 36 `arm64-v8a` target with the requested actual page size and exact image
fingerprint, inspects both APK identities and the production JNI artifact,
provisions the golden-pinned external dictionary, and invokes only
`MecabNativeTokenizerInstrumentedTest`. It never discovers, starts, stops, or
chooses a target. Normal builds remain limited to `emulatorDebug` and
`deviceRelease`.
