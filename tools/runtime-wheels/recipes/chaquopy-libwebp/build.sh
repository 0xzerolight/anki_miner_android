#!/bin/bash
set -euo pipefail

./configure \
    --host="$HOST" \
    --prefix="$PREFIX" \
    --disable-static \
    --enable-shared \
    --disable-gl \
    --disable-gif \
    --disable-jpeg \
    --disable-libwebpextras \
    --disable-png \
    --disable-sdl \
    --disable-tiff \
    --disable-wic

# Libtool otherwise embeds the clean-stage prefix in DT_RUNPATH. Chaquopy removes
# the tag while packaging, but patchelf deliberately leaves the old string-table
# bytes behind, making otherwise identical clean builds differ.
sed -i 's/^hardcode_into_libs=yes$/hardcode_into_libs=no/' libtool
sed -i 's|^hardcode_libdir_flag_spec=.*|hardcode_libdir_flag_spec=""|' libtool
grep -qx 'hardcode_into_libs=no' libtool
grep -qx 'hardcode_libdir_flag_spec=""' libtool

make -j "$CPU_COUNT"
make install

rm -rf "$PREFIX/bin" "$PREFIX/share" "$PREFIX/lib/pkgconfig"
rm -f "$PREFIX/lib/"*.a "$PREFIX/lib/"*.la
