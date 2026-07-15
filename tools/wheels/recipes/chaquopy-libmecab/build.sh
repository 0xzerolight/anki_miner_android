#!/usr/bin/env bash
set -euo pipefail

case "$HOST" in
    aarch64-linux-android) configure_host=aarch64-unknown-linux-android ;;
    x86_64-linux-android) configure_host=x86_64-pc-linux-android ;;
    *) echo "unsupported S1a host: $HOST" >&2; exit 1 ;;
esac

./configure \
    --build=x86_64-pc-linux-gnu \
    --host="$configure_host" \
    --enable-utf8-only \
    --disable-static \
    --enable-shared \
    ac_cv_func_mmap_fixed_mapped=yes
if grep -R -- '-lstdc++' config.log src/Makefile; then
    echo "MeCab configuration retained forbidden -lstdc++" >&2
    exit 1
fi
make -C src -j"$CPU_COUNT" libmecab.la
mkdir -p "$PREFIX/include" "$PREFIX/lib"
cp src/mecab.h "$PREFIX/include/"
cp -L src/.libs/libmecab.so.2 "$PREFIX/lib/libmecab.so.2"
grep -Eq '^#define HAVE_MMAP 1$' config.h
