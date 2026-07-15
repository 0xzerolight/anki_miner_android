#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

RUNTIME_VENV="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/runtime-host-tests"
LOCK_FILE="$REPO_ROOT/requirements-runtime-host-test.lock"
LOCK_MARKER_NAME=".anki-miner-runtime-lock-sha256"

if [[ "$(uname -s)" != "Linux" || "$(uname -m)" != "x86_64" ]]; then
    echo "The runtime host-test lock currently supports Linux x86_64 only." >&2
    exit 1
fi
for command_name in awk flock python3.13 sha256sum; do
    if ! command -v "$command_name" >/dev/null; then
        echo "Required command not found: $command_name" >&2
        exit 1
    fi
done

"$SCRIPT_DIR/provision-chaquopy-build-python.sh"

# Lock ordering is always build Python, then runtime venv. The build provisioner
# has returned before this process acquires either lock, so nested calls cannot
# deadlock. Holding the shared build lock keeps the venv base stable until swap.
mkdir -p "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/.locks"
build_python_lock="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/.locks/chaquopy-build-python.lock"
runtime_lock="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/.locks/runtime-host-tests.lock"
exec {build_python_lock_fd}>"$build_python_lock"
flock --shared "$build_python_lock_fd"
python3.13 "$SCRIPT_DIR/verify_chaquopy_build_python.py" verify \
    --toolchain-root "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT" \
    --python "$ANKI_MINER_CHAQUOPY_BUILD_PYTHON" >/dev/null
exec {runtime_lock_fd}>"$runtime_lock"
flock --exclusive "$runtime_lock_fd"

verify_runtime_environment() {
    local python_command="$1"
    PIP_NO_CACHE_DIR=1 "$python_command" -m pip check || return 1
    "$python_command" -I -c '
import importlib.metadata
import importlib.util
import platform
from PIL import features

assert platform.python_implementation() == "CPython"
assert platform.python_version() == "3.12.13"
expected = {
    "certifi": "2026.6.17",
    "charset-normalizer": "3.4.7",
    "idna": "3.18",
    "lxml": "6.1.1",
    "pillow": "12.2.0",
    "pysubs2": "1.8.1",
    "requests": "2.34.2",
    "urllib3": "2.7.0",
}
for name, version in expected.items():
    assert importlib.metadata.version(name) == version, (name, version)
assert features.check("jpg")
assert features.check("webp")
assert features.check("zlib")
assert importlib.util.find_spec("fugashi") is None
assert importlib.util.find_spec("unidic") is None
' || return 1
}

lock_sha256="$(sha256sum "$LOCK_FILE" | awk '{ print $1 }')"
if [[ -x "$RUNTIME_VENV/bin/python" && \
    -f "$RUNTIME_VENV/$LOCK_MARKER_NAME" && \
    "$(<"$RUNTIME_VENV/$LOCK_MARKER_NAME")" == "$lock_sha256" ]]; then
    if verify_runtime_environment "$RUNTIME_VENV/bin/python"; then
        echo "Runtime host test environment is current: $RUNTIME_VENV"
        exit 0
    fi
    echo "Runtime host test environment failed verification; rebuilding it." >&2
fi

mkdir -p "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT"
staging="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/.runtime-host-tests-staging-$$"
previous="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/.runtime-host-tests-previous-$$"
cleanup() {
    if [[ -e "$previous" && ! -e "$RUNTIME_VENV" ]]; then
        mv "$previous" "$RUNTIME_VENV"
    fi
    rm -rf "$staging" "$previous"
}
trap cleanup EXIT

"$ANKI_MINER_CHAQUOPY_BUILD_PYTHON" -m venv "$staging"
PIP_NO_CACHE_DIR=1 "$staging/bin/python" -m pip install \
    --disable-pip-version-check \
    --only-binary=:all: \
    --require-hashes \
    -r "$LOCK_FILE"
verify_runtime_environment "$staging/bin/python"
printf '%s\n' "$lock_sha256" >"$staging/$LOCK_MARKER_NAME"

if [[ -e "$RUNTIME_VENV" ]]; then
    mv "$RUNTIME_VENV" "$previous"
fi
mv "$staging" "$RUNTIME_VENV"
rm -rf "$previous"
trap - EXIT

echo "Runtime host test environment: $RUNTIME_VENV"
