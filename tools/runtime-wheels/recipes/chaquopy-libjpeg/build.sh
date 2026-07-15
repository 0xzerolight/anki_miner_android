#!/bin/bash
set -euo pipefail

# SIMD is only available for x86, so disable it for ABI-consistent output.
./configure \
    --host="$HOST" \
    --disable-static \
    --enable-shared \
    --without-simd \
    --without-turbojpeg
make -j "$CPU_COUNT"
make install prefix="$PREFIX"

rm -rf "$PREFIX/bin" "$PREFIX/doc" "$PREFIX/man"
if [[ -d "$PREFIX/lib64" ]]; then
    mv "$PREFIX/lib64" "$PREFIX/lib"
elif [[ -d "$PREFIX/lib32" ]]; then
    mv "$PREFIX/lib32" "$PREFIX/lib"
fi
mv "$PREFIX/lib/libjpeg.so" "$PREFIX/lib/libjpeg_chaquopy.so"
rm -rf "$PREFIX/lib/pkgconfig"
rm -f "$PREFIX/lib/"*.a "$PREFIX/lib/"*.la
