#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

HEADLESS=auto
SOFTWARE=auto
WIPE_DATA=false
TEST_SESSION=false
PRINT_COMMAND=false
PAGE_SIZE_LANE=4k
EXTRA_ARGS=()

usage() {
    cat <<'EOF'
Usage: scripts/emulator.sh [options] [-- emulator-options]

Options:
  --headless       Disable the emulator window.
  --window         Require a graphical emulator window.
  --software       Disable KVM and use software CPU/GPU emulation.
  --hardware       Require hardware acceleration.
  --page-size SIZE Select the 4k or 16k API 36 image (default: 4k).
  --wipe-data      Reset the AVD before booting.
  --test-session   Reset data and disable snapshot load/save for a test run.
  --print-command  Print the resolved emulator command without running it.

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
        --test-session)
            TEST_SESSION=true
            ;;
        --print-command)
            PRINT_COMMAND=true
            ;;
        --page-size)
            (($# >= 2)) || { usage >&2; exit 2; }
            PAGE_SIZE_LANE="$2"
            shift
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

case "$PAGE_SIZE_LANE" in
    4k)
        AVD_NAME="$ANDROID_AVD_4K_NAME"
        EMULATOR_PORT="$ANDROID_EMULATOR_4K_PORT"
        EMULATOR_SERIAL="$ANDROID_EMULATOR_4K_SERIAL"
        ;;
    16k)
        AVD_NAME="$ANDROID_AVD_16K_NAME"
        EMULATOR_PORT="$ANDROID_EMULATOR_16K_PORT"
        EMULATOR_SERIAL="$ANDROID_EMULATOR_16K_SERIAL"
        ;;
    *)
        echo "Page size must be 4k or 16k." >&2
        exit 2
        ;;
esac

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
if [[ "$SOFTWARE" == false ]] && { [[ ! -r /dev/kvm ]] || [[ ! -w /dev/kvm ]]; }; then
    echo "Hardware acceleration was requested, but /dev/kvm is unavailable." >&2
    exit 1
fi

args=(
    -avd "$AVD_NAME"
    -port "$EMULATOR_PORT"
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
if [[ "$WIPE_DATA" == true && "$TEST_SESSION" != true ]]; then
    args+=(-wipe-data)
fi

final_args=("${args[@]}" "${EXTRA_ARGS[@]}")
if [[ "$TEST_SESSION" == true ]]; then
    # Keep these last so passthrough arguments cannot re-enable snapshots.
    final_args+=(-wipe-data -no-snapshot-load -no-snapshot-save)
fi

if [[ "$PRINT_COMMAND" == true ]]; then
    printf '%q ' emulator "${final_args[@]}"
    printf '\n'
    exit 0
fi

if [[ ! -x "$ANDROID_HOME/emulator/emulator" ]]; then
    echo "Android Emulator is not installed; run scripts/provision-android.sh first." >&2
    exit 1
fi
"$SCRIPT_DIR/verify-android-toolchain.sh"
if ! emulator -list-avds | grep -Fx "$AVD_NAME" >/dev/null; then
    echo "AVD $AVD_NAME is missing; rerun provisioning." >&2
    exit 1
fi
if adb devices | awk -v serial="$EMULATOR_SERIAL" \
    '$1 == serial { found = 1 } END { exit !found }'; then
    echo "$EMULATOR_SERIAL is already reserved by a running or offline emulator." >&2
    exit 1
fi
if command -v ss >/dev/null; then
    adb_port=$((EMULATOR_PORT + 1))
    if ss -H -ltn | awk -v console=":$EMULATOR_PORT" -v adb=":$adb_port" \
        '$4 ~ console "$" || $4 ~ adb "$" { found = 1 } END { exit !found }'; then
        echo "Emulator ports $EMULATOR_PORT/$adb_port are already in use." >&2
        exit 1
    fi
fi

exec emulator "${final_args[@]}"
