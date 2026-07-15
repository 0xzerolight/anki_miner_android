#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=../../../scripts/android-env.sh
source "$REPO_ROOT/scripts/android-env.sh"

downloads="${ANKI_MINER_S1A_DOWNLOADS:-$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/downloads}"
wheelhouse="${ANKI_MINER_S1A_HOST_WHEELHOUSE:-$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/wheels/host}"
build_root="${ANKI_MINER_S1A_BUILD_ROOT:-$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/build}"

"$REPO_ROOT/scripts/android-licenses.sh" check
[[ -d "$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION" ]] || {
    echo "Locked NDK is missing; install it after accepting the SDK licenses yourself." >&2
    exit 1
}
stage="$($SCRIPT_DIR/s1a_wheels.py stage --downloads "$downloads" --wheelhouse "$wheelhouse" --build-root "$build_root")"
chaquopy="$(find "$stage/chaquopy" -mindepth 1 -maxdepth 1 -type d -print -quit)"
patchelf="$(find "$stage/patchelf" -type f -name patchelf -perm -u+x -print -quit)"
builder_env="$stage/builder-env"
python3.13 -m venv --without-pip "$builder_env"
pip_wheel="$(find "$wheelhouse" -maxdepth 1 -name 'pip-25.1.1-*.whl' -print -quit)"
"$builder_env/bin/python" "$pip_wheel/pip" install --no-index --only-binary=:all: --find-links "$wheelhouse" \
    build==1.2.2.post1 Jinja2==3.1.6 jsonschema==4.23.0 pyelftools==0.32 PyYAML==6.0.2 setuptools==78.1.1 wheel==0.45.1
"$builder_env/bin/python" -m pip check

export ANDROID_HOME ANKI_MINER_HOST_WHEELHOUSE="$wheelhouse"
export PATH="$(dirname "$patchelf"):$PATH"
cd "$chaquopy/server/pypi"
for abi in arm64-v8a x86_64; do
    "$builder_env/bin/python" build-wheel.py --abi "$abi" --api-level 26 chaquopy-libcxx
    "$builder_env/bin/python" build-wheel.py --abi "$abi" --api-level 26 chaquopy-libmecab
    "$builder_env/bin/python" build-wheel.py --python 3.13 --abi "$abi" --api-level 26 fugashi
done

echo "S1a wheels built under $chaquopy/server/pypi/dist"
