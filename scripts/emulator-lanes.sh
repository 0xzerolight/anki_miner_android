#!/usr/bin/env bash

# Resolve a canonical emulator lane to its pinned AVD and runtime identity.
# Source android-env.sh before calling this function.
# shellcheck disable=SC2034  # Output variables are consumed by sourcing scripts.
resolve_android_emulator_lane() {
    local lane="$1"

    case "$lane" in
        api26)
            ANDROID_LANE_AVD_NAME="$ANDROID_AVD_API26_NAME"
            ANDROID_LANE_EMULATOR_PORT="$ANDROID_EMULATOR_API26_PORT"
            ANDROID_LANE_EMULATOR_SERIAL="$ANDROID_EMULATOR_API26_SERIAL"
            ANDROID_LANE_EXPECTED_API_LEVEL=26
            ANDROID_LANE_EXPECTED_PAGE_SIZE=4096
            ANDROID_LANE_EXPECTED_FINGERPRINT="$ANDROID_EMULATOR_API26_FINGERPRINT"
            ;;
        4k)
            ANDROID_LANE_AVD_NAME="$ANDROID_AVD_4K_NAME"
            ANDROID_LANE_EMULATOR_PORT="$ANDROID_EMULATOR_4K_PORT"
            ANDROID_LANE_EMULATOR_SERIAL="$ANDROID_EMULATOR_4K_SERIAL"
            ANDROID_LANE_EXPECTED_API_LEVEL=36
            ANDROID_LANE_EXPECTED_PAGE_SIZE=4096
            ANDROID_LANE_EXPECTED_FINGERPRINT=""
            ;;
        16k)
            ANDROID_LANE_AVD_NAME="$ANDROID_AVD_16K_NAME"
            ANDROID_LANE_EMULATOR_PORT="$ANDROID_EMULATOR_16K_PORT"
            ANDROID_LANE_EMULATOR_SERIAL="$ANDROID_EMULATOR_16K_SERIAL"
            ANDROID_LANE_EXPECTED_API_LEVEL=36
            ANDROID_LANE_EXPECTED_PAGE_SIZE=16384
            ANDROID_LANE_EXPECTED_FINGERPRINT=""
            ;;
        *)
            echo "Emulator lane must be api26, 4k, or 16k." >&2
            return 2
            ;;
    esac
}
