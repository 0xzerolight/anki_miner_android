#!/bin/bash
# NumPy 2.x builds only with meson-python, which Chaquopy's PEP 517 path cannot
# pass a cross file to. Build through the hook directly, BLAS-free (thinc and
# spaCy use blis for their matrix products), and unpack into ../prefix.
set -euo pipefail

case "$CHAQUOPY_ABI" in
    arm64-v8a) cpu_family=aarch64 ;;
    x86_64) cpu_family=x86_64 ;;
    *) echo "unsupported ABI: $CHAQUOPY_ABI" >&2; exit 1 ;;
esac

meson_array() {
    python -c 'import shlex, sys; print("[" + ", ".join(repr(a) for a in shlex.split(sys.argv[1])) + "]")' "$1"
}

cross="$SRC_DIR/../android.meson.cross"
cat > "$cross" <<CROSS
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
strip = '$STRIP'

[properties]
# Bionic uses IEEE binary128 long double on both 64-bit ABIs.
longdouble_format = 'IEEE_QUAD_LE'

[built-in options]
c_args = $(meson_array "$CFLAGS")
cpp_args = $(meson_array "$CXXFLAGS")
c_link_args = $(meson_array "$LDFLAGS")
cpp_link_args = $(meson_array "$LDFLAGS")

[host_machine]
system = 'android'
cpu_family = '$cpu_family'
cpu = '$cpu_family'
endian = 'little'
CROSS

python - "$cross" <<'PY'
import os
import sys

import mesonpy

os.makedirs("dist", exist_ok=True)
print(mesonpy.build_wheel("dist", {
    "setup-args": [
        f"--cross-file={sys.argv[1]}",
        "-Dblas=none",
        "-Dlapack=none",
        "-Dallow-noblas=true",
    ],
    "builddir": "build-android",
}))
PY
unzip -q -d ../prefix dist/numpy-*.whl
# numpy/__config__.py records the compiler command lines, which carry the stage
# root; `wheel pack` regenerates RECORD afterwards.
sed -i "s#$ANKI_MINER_RUNTIME_STAGE_ROOT#/anki-miner-runtime#g" ../prefix/numpy/__config__.py
if grep -q "$ANKI_MINER_RUNTIME_STAGE_ROOT" -r ../prefix; then
    echo "stage root still present in the numpy payload" >&2
    exit 1
fi
