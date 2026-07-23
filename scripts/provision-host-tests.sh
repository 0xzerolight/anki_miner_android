#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

HOST_TEST_VENV="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/host-tests"
LOCK_FILE="$REPO_ROOT/requirements-host-test.lock"
# ruff/black live in this venv too, so health.sh can run the same lint CI runs.
LINT_LOCK_FILE="$REPO_ROOT/requirements-lint.lock"
LOCK_MARKER_NAME=".anki-miner-lock-sha256"

if [[ "$(uname -s)" != "Linux" || "$(uname -m)" != "x86_64" ]]; then
    echo "The host-test lock currently supports Linux x86_64 only." >&2
    exit 1
fi
command -v python3.13 >/dev/null || {
    echo "Host Python 3.13 is required." >&2
    exit 1
}

# Hash file CONTENT, never `sha256sum FILE1 FILE2` output: that embeds absolute
# paths, and this marker is shared with every linked worktree (the toolchain root
# is derived from git-common-dir). health.sh recomputes this exact expression.
lock_sha256="$(cat "$LOCK_FILE" "$LINT_LOCK_FILE" | sha256sum | awk '{ print $1 }')"
if [[ -x "$HOST_TEST_VENV/bin/python" && \
    -f "$HOST_TEST_VENV/$LOCK_MARKER_NAME" && \
    "$(<"$HOST_TEST_VENV/$LOCK_MARKER_NAME")" == "$lock_sha256" ]]; then
    PIP_NO_CACHE_DIR=1 "$HOST_TEST_VENV/bin/python" -m pip check
    echo "Host test environment is current: $HOST_TEST_VENV"
    exit 0
fi

mkdir -p "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT"
staging="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/.host-tests-staging-$$"
previous="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/.host-tests-previous-$$"
cleanup() {
    if [[ -e "$previous" && ! -e "$HOST_TEST_VENV" ]]; then
        mv "$previous" "$HOST_TEST_VENV"
    fi
    rm -rf "$staging" "$previous"
}
trap cleanup EXIT

python3.13 -m venv "$staging"
PIP_NO_CACHE_DIR=1 "$staging/bin/python" -m pip install \
    --disable-pip-version-check \
    --only-binary=:all: \
    --require-hashes \
    -r "$LOCK_FILE" \
    -r "$LINT_LOCK_FILE"
PIP_NO_CACHE_DIR=1 "$staging/bin/python" -m pip check
printf '%s\n' "$lock_sha256" >"$staging/$LOCK_MARKER_NAME"

if [[ -e "$HOST_TEST_VENV" ]]; then
    mv "$HOST_TEST_VENV" "$previous"
fi
mv "$staging" "$HOST_TEST_VENV"
rm -rf "$previous"
trap - EXIT

echo "Host test environment: $HOST_TEST_VENV"
