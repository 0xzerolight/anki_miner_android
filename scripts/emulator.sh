#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

HEADLESS=auto
SOFTWARE=auto
WIPE_DATA=false
EXTRA_ARGS=()

usage() {
    cat <<'EOF'
Usage: scripts/emulator.sh [options] [-- emulator-options]

Options:
  --headless       Disable the emulator window.
  --window         Require a graphical emulator window.
  --software       Disable KVM and use software CPU/GPU emulation.
  --hardware       Require hardware acceleration.
  --wipe-data      Reset the AVD before booting.

Headless mode is selected automatically without a display. Software mode is
selected automatically when /dev/kvm is unavailable. Software boot is slow but
keeps emulator testing possible on hosts without virtualization access.
EOF
}

while (($#)); do
    case "$1" in
        --headless)
            HEADLESS=true
            ;;
        --window)
            HEADLESS=false
            ;;
        --software)
            SOFTWARE=true
            ;;
        --hardware)
            SOFTWARE=false
            ;;
        --wipe-data)
            WIPE_DATA=true
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        --)
            shift
            EXTRA_ARGS+=("$@")
            break
            ;;
        *)
            EXTRA_ARGS+=("$1")
            ;;
    esac
    shift
done

if [[ ! -x "$ANDROID_HOME/emulator/emulator" ]]; then
    echo "Android Emulator is not installed; run scripts/provision-android.sh first." >&2
    exit 1
fi
if ! emulator -list-avds | grep -Fxq "$ANDROID_AVD_NAME"; then
    echo "AVD $ANDROID_AVD_NAME is missing; rerun provisioning without --no-avd." >&2
    exit 1
fi

if [[ "$HEADLESS" == auto ]]; then
    if [[ -z "${DISPLAY:-}" && -z "${WAYLAND_DISPLAY:-}" ]]; then
        HEADLESS=true
    else
        HEADLESS=false
    fi
fi

if [[ "$SOFTWARE" == auto ]]; then
    if [[ -r /dev/kvm && -w /dev/kvm ]]; then
        SOFTWARE=false
    else
        SOFTWARE=true
    fi
fi

args=(
    -avd "$ANDROID_AVD_NAME"
    -netdelay none
    -netspeed full
    -no-boot-anim
)

if [[ "$HEADLESS" == true ]]; then
    args+=(-no-window -no-audio)
fi
if [[ "$SOFTWARE" == true ]]; then
    echo "KVM is unavailable or software mode was requested; boot may take several minutes." >&2
    args+=(-accel off -gpu swangle)
else
    args+=(-accel auto -gpu auto)
fi
if [[ "$WIPE_DATA" == true ]]; then
    args+=(-wipe-data)
fi

exec emulator "${args[@]}" "${EXTRA_ARGS[@]}"
