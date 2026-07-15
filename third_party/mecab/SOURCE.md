# MeCab runtime source provenance

This directory contains only the MeCab 0.996 runtime files needed by the
Android tokenizer. They are copied byte-for-byte from `ios/Classes` in
`dariyooo/mecab_for_dart` tag `d2.0.0`, commit
`453d4deb7e3857f32c1ab6c1ced574d9f73a2233`.

Source: https://github.com/dariyooo/mecab_for_dart

The upstream Android CMake runtime list is reused, except that its Flutter/Dart
entry point (`dart_ffi.cpp`) is deliberately excluded. `source-manifest.json`
pins every retained source byte and the BSD-3-Clause license. Run
`python3 tools/tokenizer/vendor_s1b_mecab.py --check` to verify the committed
copy, or pass `--source <checkout>` to reproduce it from the pinned checkout.

The upstream `config.h` is not modified. Android CMake defines `HAVE_MMAP=1`,
`HAVE_SYS_MMAN_H=1`, and `HAVE_SYS_TYPES_H=1`; omitting those definitions makes
MeCab heap-copy the roughly 250 MiB UniDic files and is a build failure.
