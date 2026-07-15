# M0 shared tokenizer contract

This is the backend-neutral contract for tokenizer spikes S1a and S1b. It does
not select a winner. Both candidates must copy a parse into
`android_bridge.tokenizer_contract.TokenRecord` values before the vendored
engine sees it. No native node pointer may escape the backend call.

## UniDic features

`UNIDIC_FEATURE_FIELDS` freezes the 26-field UniDic 2.1.2 binary schema in the
same order as fugashi 1.5.2 and the desktop golden fixture. CSV decoding follows
fugashi's observable behavior: quoted commas are decoded, explicit empty fields
and literal `*` values survive, and only absent trailing fields become `None`.
Rows longer than 26 fields fail instead of being silently truncated.

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

Both backends consume `RegisteredUniDic.mecab_arguments`, yielding explicit
`-r <dicdir>/mecabrc -d <dicdir>` argv elements. S1a may format those elements
for fugashi only inside its backend module. S1b passes them directly to
`mecab_new`. Neither backend may import Java, fugashi, or the engine from the
shared contract modules.
