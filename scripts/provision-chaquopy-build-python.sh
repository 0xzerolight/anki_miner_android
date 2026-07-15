#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

LOCK="$SCRIPT_DIR/chaquopy-build-python.lock.json"
VERIFIER="$SCRIPT_DIR/verify_chaquopy_build_python.py"

usage() {
    cat <<'EOF'
Usage: scripts/provision-chaquopy-build-python.sh

Installs the hash-pinned CPython interpreter used only to build the embedded
Chaquopy 3.12 runtime. Repository tools continue to run with Python 3.13.
EOF
}

if (($#)); then
    if [[ "$#" -eq 1 && ( "$1" == "-h" || "$1" == "--help" ) ]]; then
        usage
        exit 0
    fi
    echo "Unknown argument: $1" >&2
    usage >&2
    exit 2
fi

if [[ "$(uname -s)" != "Linux" || "$(uname -m)" != "x86_64" ]]; then
    echo "The pinned Chaquopy build Python currently supports Linux x86_64 only." >&2
    exit 1
fi
for command_name in curl flock python3.13 sha256sum tar; do
    if ! command -v "$command_name" >/dev/null; then
        echo "Required command not found: $command_name" >&2
        exit 1
    fi
done

mkdir -p "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/.locks"
build_python_lock="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/.locks/chaquopy-build-python.lock"
exec {build_python_lock_fd}>"$build_python_lock"
flock --exclusive "$build_python_lock_fd"

mapfile -t lock_fields < <(python3.13 "$VERIFIER" --lock "$LOCK" describe)
if [[ "${#lock_fields[@]}" -ne 8 ]]; then
    echo "Build Python lock did not produce the expected provisioning fields." >&2
    exit 1
fi
archive_filename="${lock_fields[0]}"
archive_url="${lock_fields[1]}"
archive_sha256="${lock_fields[2]}"
install_directory="${lock_fields[3]}"
relative_executable="${lock_fields[4]}"
executable_sha256="${lock_fields[5]}"
marker_name="${lock_fields[6]}"
expected_version="${lock_fields[7]}"

install_root="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/$install_directory"
expected_python="$install_root/$relative_executable"
if [[ "$expected_python" != "$ANKI_MINER_CHAQUOPY_BUILD_PYTHON" ]]; then
    echo "android-env build Python path differs from the committed lock." >&2
    exit 1
fi
if python3.13 "$VERIFIER" --lock "$LOCK" verify \
    --toolchain-root "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT" \
    --python "$ANKI_MINER_CHAQUOPY_BUILD_PYTHON" >/dev/null 2>&1; then
    echo "Pinned Chaquopy build Python is already verified: $expected_python"
    exit 0
fi

downloads="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/downloads"
archive="$downloads/$archive_filename"
mkdir -p "$downloads"
if [[ ! -f "$archive" ]] || ! echo "$archive_sha256  $archive" | sha256sum --check --status; then
    echo "Downloading $archive_filename"
    rm -f "$archive.partial"
    curl \
        --fail \
        --location \
        --retry 4 \
        --retry-all-errors \
        --output "$archive.partial" \
        "$archive_url"
    echo "$archive_sha256  $archive.partial" | sha256sum --check --status
    mv "$archive.partial" "$archive"
fi
echo "$archive_sha256  $archive" | sha256sum --check --status

staging="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/.chaquopy-build-python-staging-$$"
backup=""
new_install=false
cleanup() {
    if [[ -n "${staging:-}" ]]; then
        rm -rf -- "$staging"
    fi
    if [[ "$new_install" == true && -e "$install_root" ]]; then
        rm -rf -- "$install_root"
    fi
    if [[ -n "${backup:-}" && -e "$backup" && ! -e "$install_root" ]]; then
        mv "$backup" "$install_root"
    fi
}
trap cleanup EXIT INT TERM

[[ ! -e "$staging" ]] || {
    echo "Unexpected staging path already exists: $staging" >&2
    exit 1
}
mkdir -p "$staging"
tar -xzf "$archive" --strip-components=1 --no-same-owner -C "$staging"
cp "$LOCK" "$staging/$marker_name"
echo "$executable_sha256  $staging/$relative_executable" | sha256sum --check --status
actual_version="$(
    "$staging/$relative_executable" -I -S -B -c \
        'import platform; print(platform.python_version())'
)"
[[ "$actual_version" == "$expected_version" ]] || {
    echo "Staged build Python version is $actual_version, expected $expected_version." >&2
    exit 1
}
python3.13 "$VERIFIER" --lock "$LOCK" verify \
    --install-root "$staging" \
    --python "$staging/$relative_executable" >/dev/null

if [[ -e "$install_root" ]]; then
    backup="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/.chaquopy-build-python-backup-$$"
    [[ ! -e "$backup" ]] || {
        echo "Unexpected backup path already exists: $backup" >&2
        exit 1
    }
    mv "$install_root" "$backup"
fi
mv "$staging" "$install_root"
staging=""
new_install=true
python3.13 "$VERIFIER" --lock "$LOCK" verify \
    --toolchain-root "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT" \
    --python "$ANKI_MINER_CHAQUOPY_BUILD_PYTHON" >/dev/null
new_install=false
if [[ -n "$backup" ]]; then
    rm -rf -- "$backup"
    backup=""
fi
trap - EXIT INT TERM

echo "Provisioned pinned Chaquopy build Python: $ANKI_MINER_CHAQUOPY_BUILD_PYTHON"
