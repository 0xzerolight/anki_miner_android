#!/usr/bin/env bash
set -euo pipefail

case "$HOST" in
    aarch64-linux-android)
        configure_host=aarch64-unknown-linux-android
        compiler_rt_name=libclang_rt.builtins-aarch64-android.a
        ;;
    x86_64-linux-android)
        configure_host=x86_64-pc-linux-android
        compiler_rt_name=libclang_rt.builtins-x86_64-android.a
        ;;
    *) echo "unsupported S1a host: $HOST" >&2; exit 1 ;;
esac

# The upstream COPYING file only points at the actual license choices. Give the
# selected BSD terms a license-prefixed name so build-wheel includes them too.
cp BSD LICENSE.BSD

compiler_rt="$("$CXX" -print-libgcc-file-name)"
if [[ ! -f "$compiler_rt" || "$(basename "$compiler_rt")" != "$compiler_rt_name" ]]; then
    echo "cannot resolve the pinned NDK compiler runtime for $HOST" >&2
    exit 1
fi
export LIBS="${LIBS:-} $compiler_rt"
export CXXFLAGS="${CXXFLAGS:-} -std=gnu++14"
./configure \
    --build=x86_64-pc-linux-gnu \
    --host="$configure_host" \
    --enable-utf8-only \
    --disable-static \
    --enable-shared \
    ac_cv_lib_stdcpp_main=no \
    ac_cv_func_mmap_fixed_mapped=yes
if grep -E '(^|[[:space:]])-lstdc\+\+([[:space:]]|$)' src/Makefile; then
    echo "MeCab configuration retained forbidden -lstdc++" >&2
    exit 1
fi
make -C src -j"$CPU_COUNT" libmecab.la
mkdir -p "$PREFIX/include" "$PREFIX/lib"
cp src/mecab.h "$PREFIX/include/"
cp -L src/.libs/libmecab.so.2 "$PREFIX/lib/libmecab.so.2"
grep -Eq '^#define HAVE_MMAP 1$' config.h
