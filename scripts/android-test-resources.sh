#!/usr/bin/env bash

# Shared host-resource rules for every local Android build and emulator lane.
# One workload lock covers the checked-to-running transition and stays held for
# the full Gradle or emulator child lifetime.

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

anki_miner_acquire_workload_lock() {
    local lock_directory lock_path

    if [[ -n "${ANKI_MINER_WORKLOAD_LOCK_FD:-}" ]]; then
        return 0
    fi
    if ! command -v flock >/dev/null 2>&1; then
        echo "Refusing to start a Gradle or emulator workload because flock is unavailable." >&2
        return 1
    fi
    lock_directory="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/.locks"
    lock_path="$lock_directory/android-workload.lock"
    mkdir -p "$lock_directory"
    exec {ANKI_MINER_WORKLOAD_LOCK_FD}>"$lock_path"
    if ! flock --exclusive --nonblock "$ANKI_MINER_WORKLOAD_LOCK_FD"; then
        exec {ANKI_MINER_WORKLOAD_LOCK_FD}>&-
        unset ANKI_MINER_WORKLOAD_LOCK_FD
        echo "Refusing to start another Gradle or emulator workload while one owns the lock." >&2
        return 1
    fi
}

anki_miner_acquire_emulator_lock() {
    local lock_directory lock_path

    if [[ -n "${ANKI_MINER_EMULATOR_LOCK_FD:-}" ]]; then
        return 0
    fi
    if ! command -v flock >/dev/null 2>&1; then
        echo "Refusing to start an emulator because flock is unavailable." >&2
        return 1
    fi
    lock_directory="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/.locks"
    lock_path="$lock_directory/emulator.lock"
    mkdir -p "$lock_directory"
    exec {ANKI_MINER_EMULATOR_LOCK_FD}>"$lock_path"
    if ! flock --exclusive --nonblock "$ANKI_MINER_EMULATOR_LOCK_FD"; then
        exec {ANKI_MINER_EMULATOR_LOCK_FD}>&-
        unset ANKI_MINER_EMULATOR_LOCK_FD
        echo "Refusing to start another emulator launcher while one already owns the lock." >&2
        return 1
    fi
}

anki_miner_run_gradle() {
    local gradlew="$1"
    shift
    anki_miner_acquire_workload_lock || return 1
    anki_miner_require_no_gradle || return 1
    anki_miner_require_no_emulator || return 1
    "$gradlew" "${ANKI_MINER_GRADLE_ARGS[@]}" "$@"
}
