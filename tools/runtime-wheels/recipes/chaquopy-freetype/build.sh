#!/bin/bash
set -euo pipefail

./configure \
    --host="$HOST" \
    --prefix="$PREFIX" \
    --disable-static \
    --enable-shared \
    --without-brotli \
    --without-bzip2 \
    --without-harfbuzz \
    --without-png
make -j "$CPU_COUNT"
make install

mv "$PREFIX/include/freetype2/"* "$PREFIX/include/"
rmdir "$PREFIX/include/freetype2"
rm -rf "$PREFIX/share" "$PREFIX/lib/pkgconfig"
rm -f "$PREFIX/lib/"*.a "$PREFIX/lib/"*.la
