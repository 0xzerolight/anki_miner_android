#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"
# shellcheck source=emulator-lanes.sh
source "$SCRIPT_DIR/emulator-lanes.sh"

usage() {
    echo "Usage: scripts/verify-emulator-runtime.sh --lane api26|4k|16k" >&2
}

LANE=""
while (($#)); do
    case "$1" in
        --lane)
            [[ -z "$LANE" ]] || { echo "--lane may be specified only once." >&2; exit 2; }
            (($# >= 2)) || { usage; exit 2; }
            LANE="$2"
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage
            exit 2
            ;;
    esac
    shift
done

[[ -n "$LANE" ]] || { usage; exit 2; }
resolve_android_emulator_lane "$LANE"

fail() {
    echo "emulator identity: $*" >&2
    exit 1
}

adb_output="$(adb devices)" || fail "could not list ADB devices"
intended_state="$(awk -v serial="$ANDROID_LANE_EMULATOR_SERIAL" \
    '$1 == serial { print $2; exit }' <<<"$adb_output")"
[[ -n "$intended_state" ]] \
    || fail "$ANDROID_LANE_EMULATOR_SERIAL is absent from ADB"
[[ "$intended_state" == device ]] \
    || fail "$ANDROID_LANE_EMULATOR_SERIAL is in ADB state '$intended_state', expected 'device'"

mapfile -t adb_targets < <(awk 'NR > 1 && NF >= 2 { print $1 " (" $2 ")" }' <<<"$adb_output")
if [[ "${#adb_targets[@]}" -ne 1 || "${adb_targets[0]}" != "$ANDROID_LANE_EMULATOR_SERIAL (device)" ]]; then
    target_summary="${adb_targets[*]:-none}"
    fail "only $ANDROID_LANE_EMULATOR_SERIAL may be present in ADB; found: $target_summary"
fi

actual_avd="$(adb -s "$ANDROID_LANE_EMULATOR_SERIAL" emu avd name 2>/dev/null \
    | tr -d '\r' | sed '/^OK$/d' | sed -n '1p')"
[[ "$actual_avd" == "$ANDROID_LANE_AVD_NAME" ]] \
    || fail "$ANDROID_LANE_EMULATOR_SERIAL is running AVD ${actual_avd:-unknown}, expected $ANDROID_LANE_AVD_NAME"

actual_api="$(adb -s "$ANDROID_LANE_EMULATOR_SERIAL" shell getprop ro.build.version.sdk 2>/dev/null \
    | tr -d '\r')"
[[ "$actual_api" == "$ANDROID_LANE_EXPECTED_API_LEVEL" ]] \
    || fail "$ANDROID_LANE_AVD_NAME API level is ${actual_api:-unknown}, expected $ANDROID_LANE_EXPECTED_API_LEVEL"

if [[ "$LANE" == api26 ]]; then
    # Android 8's pinned toybox has no getconf applet. Its only supported page
    # size is 4 KiB, so the first process mapping is an exact fallback there.
    smaps="$(adb -s "$ANDROID_LANE_EMULATOR_SERIAL" shell cat /proc/self/smaps 2>/dev/null)" \
        || fail "$ANDROID_LANE_AVD_NAME process page metadata could not be read"
    page_size_kib="$(
        awk '$1 == "KernelPageSize:" && $2 ~ /^[0-9]+$/ && $3 == "kB" { print $2; exit }' \
            <<<"${smaps//$'\r'/}"
    )"
    [[ "$page_size_kib" =~ ^[1-9][0-9]*$ ]] \
        || fail "$ANDROID_LANE_AVD_NAME process page metadata is missing or malformed"
    actual_page_size=$((page_size_kib * 1024))
else
    actual_page_size="$(
        adb -s "$ANDROID_LANE_EMULATOR_SERIAL" shell getconf PAGE_SIZE 2>/dev/null \
            | tr -d '\r'
    )" || fail "$ANDROID_LANE_AVD_NAME process page size could not be read"
    [[ "$actual_page_size" =~ ^[1-9][0-9]*$ ]] \
        || fail "$ANDROID_LANE_AVD_NAME process page size is missing or malformed"
fi
[[ "$actual_page_size" == "$ANDROID_LANE_EXPECTED_PAGE_SIZE" ]] \
    || fail "$ANDROID_LANE_AVD_NAME page size is ${actual_page_size:-unknown}, expected $ANDROID_LANE_EXPECTED_PAGE_SIZE"

if [[ -n "$ANDROID_LANE_EXPECTED_FINGERPRINT" ]]; then
    actual_fingerprint="$(adb -s "$ANDROID_LANE_EMULATOR_SERIAL" shell getprop ro.build.fingerprint 2>/dev/null \
        | tr -d '\r')"
    [[ "$actual_fingerprint" == "$ANDROID_LANE_EXPECTED_FINGERPRINT" ]] \
        || fail "$ANDROID_LANE_AVD_NAME build fingerprint is ${actual_fingerprint:-unknown}, expected $ANDROID_LANE_EXPECTED_FINGERPRINT"
fi
