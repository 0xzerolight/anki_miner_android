#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

# SHA-1 of the current android-sdk-license text in Google's stable repository
# metadata. sdkmanager writes this value only after the user accepts the terms.
ANDROID_SDK_LICENSE_HASH="efa68a6b3c661d18699d5c026771d5911cdc2f83"
ANDROID_SDK_LICENSE_FILE="$ANDROID_HOME/licenses/android-sdk-license"

usage() {
    cat <<'EOF'
Usage: scripts/android-licenses.sh <check|review>

  check   Verify that the pinned Android SDK license was already accepted.
  review  Interactively show sdkmanager's license text and prompts.

The review command requires a terminal. It never answers a prompt for you.
EOF
}

check_license() {
    if [[ -f "$ANDROID_SDK_LICENSE_FILE" ]] && \
        grep -Fx "$ANDROID_SDK_LICENSE_HASH" "$ANDROID_SDK_LICENSE_FILE" >/dev/null; then
        echo "Android SDK license state: accepted"
        return 0
    fi

    echo "The pinned Android SDK license has not been accepted." >&2
    echo "Review it interactively with: scripts/android-licenses.sh review" >&2
    return 2
}

case "${1:-}" in
    check)
        (($# == 1)) || { usage >&2; exit 2; }
        check_license
        ;;
    review)
        (($# == 1)) || { usage >&2; exit 2; }
        [[ -t 0 && -t 1 ]] || {
            echo "License review requires an interactive terminal." >&2
            exit 2
        }
        [[ -x "$ANDROID_CMDLINE_TOOLS_HOME/bin/sdkmanager" ]] || {
            echo "Android command-line tools are missing; run scripts/provision-android.sh once first." >&2
            exit 1
        }
        sdkmanager --sdk_root="$ANDROID_HOME" --licenses
        echo
        check_license
        ;;
    -h|--help)
        usage
        ;;
    *)
        usage >&2
        exit 2
        ;;
esac
