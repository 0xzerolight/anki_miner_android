#!/bin/bash
set -euo pipefail

./configure \
    --host="$HOST" \
    --prefix="$PREFIX" \
    --sysconfdir=/etc \
    --disable-static \
    --enable-shared \
    --with-zlib \
    --without-history \
    --without-iconv \
    --without-icu \
    --without-lzma \
    --without-modules \
    --without-python \
    --without-readline

grep -qx '#define XML_SYSCONFDIR "/etc"' config.h

# Avoid a stage-specific install prefix in the ELF dynamic string table. The
# final Android wheels must be reproducible across independent clean roots.
sed -i 's/^hardcode_into_libs=yes$/hardcode_into_libs=no/' libtool
sed -i 's|^hardcode_libdir_flag_spec=.*|hardcode_libdir_flag_spec=""|' libtool
grep -qx 'hardcode_into_libs=no' libtool
grep -qx 'hardcode_libdir_flag_spec=""' libtool

make -j "$CPU_COUNT"
make install

if [[ -d "$PREFIX/lib/pkgconfig" ]]; then
    sed -i 's|^prefix=.*|prefix=${pcfiledir}/../..|' "$PREFIX/lib/pkgconfig/"*.pc
fi
rm -rf "$PREFIX/bin" "$PREFIX/share" "$PREFIX/lib/cmake" "$PREFIX/lib/xml2Conf.sh"
rm -f "$PREFIX/lib/"*.a "$PREFIX/lib/"*.la
