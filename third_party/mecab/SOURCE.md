# MeCab runtime source provenance

This directory contains only the MeCab 0.996-derived runtime files needed by
the Android tokenizer. The exact source snapshot is copied byte-for-byte from
`ios/Classes` in the `dariyooo/mecab_for_dart` wrapper repository at tag
`d2.0.0`, commit `453d4deb7e3857f32c1ab6c1ced574d9f73a2233`.

Source: https://github.com/dariyooo/mecab_for_dart

The upstream Android CMake runtime list is reused, except that its Flutter/Dart
entry point (`dart_ffi.cpp`) is deliberately excluded. The copied runtime is
copyright Taku Kudo and Nippon Telegraph and Telephone Corporation and is
redistributed under its original BSD-3-Clause text in `LICENSE.mecab`. The
wrapper repository's separate CaptainDario BSD-3-Clause text is preserved in
`LICENSE.mecab_for_dart`; it describes the repository through which the source
snapshot was obtained, not ownership of the original MeCab runtime. The exact
source snapshot and both license texts are pinned in `source-manifest.json`.

Original MeCab source and license: https://github.com/taku910/mecab
(`mecab/BSD` at commit
`61b90ba6e669dc2d7d533d4a80d206f3b31d52b1`).

Run
`python3 tools/tokenizer/vendor_s1b_mecab.py --check` to verify the committed
copy. To reproduce it, pass both `--source <mecab_for_dart-checkout>` and
`--mecab-source <original-mecab-checkout>` at their manifest-pinned commits.

The upstream `config.h` is not modified. Android CMake defines `HAVE_MMAP=1`,
`HAVE_SYS_MMAN_H=1`, and `HAVE_SYS_TYPES_H=1`; omitting those definitions makes
MeCab heap-copy the roughly 250 MiB UniDic files and is a build failure.
