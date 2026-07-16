#!/usr/bin/env bash

# Shared host-resource rules for every local Android build and emulator lane.
# Callers use these checks at the boundary immediately before starting Gradle
# or an emulator; neither process is allowed to overlap the other.

ANKI_MINER_GRADLE_ARGS=(
    --no-daemon
    --no-parallel
    --max-workers=1
    "-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8"
    --stacktrace
    --dependency-verification
    strict
)

anki_miner_gradle_is_running() {
    pgrep -f \
        'org\.gradle\.(launcher\.daemon\.bootstrap\.GradleDaemon|wrapper\.GradleWrapperMain)' \
        >/dev/null 2>&1
}

anki_miner_emulator_is_running() {
    local adb_output
    if command -v adb >/dev/null 2>&1; then
        adb_output="$(adb devices 2>/dev/null || true)"
        if awk 'NR > 1 && $1 ~ /^emulator-/ { found = 1 } END { exit !found }' \
            <<<"$adb_output"; then
            return 0
        fi
    fi
    pgrep -f \
        '(^|/)(emulator|qemu-system-[[:alnum:]_-]+)([[:space:]]|$)' \
        >/dev/null 2>&1
}

anki_miner_require_no_gradle() {
    if anki_miner_gradle_is_running; then
        echo "Refusing connected/emulator work while Gradle is running." >&2
        return 1
    fi
}

anki_miner_require_no_emulator() {
    if anki_miner_emulator_is_running; then
        echo "Refusing to run Gradle while an Android emulator is running." >&2
        return 1
    fi
}

anki_miner_require_emulator_capacity() {
    local meminfo_path available_kib swap_total_kib swap_free_kib
    local minimum_available_kib minimum_swap_free_kib
    meminfo_path="${ANKI_MINER_MEMINFO_PATH:-/proc/meminfo}"
    minimum_available_kib=$((6 * 1024 * 1024))
    minimum_swap_free_kib=$((1 * 1024 * 1024))
    available_kib="$(awk '/^MemAvailable:/ { print $2; exit }' "$meminfo_path")"
    swap_total_kib="$(awk '/^SwapTotal:/ { print $2; exit }' "$meminfo_path")"
    swap_free_kib="$(awk '/^SwapFree:/ { print $2; exit }' "$meminfo_path")"

    if [[ ! "$available_kib" =~ ^[0-9]+$ ]] \
        || ((available_kib < minimum_available_kib)); then
        echo "Refusing to start an emulator with less than 6 GiB of available host memory." >&2
        return 1
    fi
    if [[ ! "$swap_total_kib" =~ ^[0-9]+$ || ! "$swap_free_kib" =~ ^[0-9]+$ ]]; then
        echo "Refusing to start an emulator because host swap state is unreadable." >&2
        return 1
    fi
    if ((swap_total_kib > 0 && swap_free_kib < minimum_swap_free_kib)); then
        echo "Refusing to start an emulator with less than 1 GiB of free swap." >&2
        return 1
    fi
}

anki_miner_run_gradle() {
    local gradlew="$1"
    shift
    anki_miner_require_no_gradle || return 1
    anki_miner_require_no_emulator || return 1
    "$gradlew" "${ANKI_MINER_GRADLE_ARGS[@]}" "$@"
}
