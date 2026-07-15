#!/bin/bash
set -euo pipefail

./configure \
    --host="$HOST" \
    --prefix="$PREFIX" \
    --disable-static \
    --enable-shared \
    --without-crypto \
    --without-python
make -j "$CPU_COUNT" V=1
make install

if [[ -d "$PREFIX/lib/pkgconfig" ]]; then
    sed -i 's|^prefix=.*|prefix=${pcfiledir}/../..|' "$PREFIX/lib/pkgconfig/"*.pc
fi
rm -rf "$PREFIX/bin" "$PREFIX/share" "$PREFIX/lib/cmake" "$PREFIX/lib/xsltConf.sh"
rm -f "$PREFIX/lib/"*.a "$PREFIX/lib/"*.la
