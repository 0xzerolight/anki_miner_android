#!/usr/bin/env bash

# Validate the complete terminal contract emitted by `am instrument -w -r`.
android_instrumentation_output_passed() {
    local output="$1"
    local expected_count="$2"
    local normalized expected_summary summary_count
    local -a terminal_codes

    [[ "$expected_count" =~ ^[1-9][0-9]*$ ]] || return 2
    normalized="${output//$'\r'/}"
    if [[ "$expected_count" == 1 ]]; then
        expected_summary="OK (1 test)"
    else
        expected_summary="OK ($expected_count tests)"
    fi

    summary_count="$(grep -Fxc -- "$expected_summary" <<<"$normalized" || true)"
    [[ "$summary_count" == 1 ]] || return 1
    if grep -Eq \
        'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|shortMsg=|Process crashed' \
        <<<"$normalized"; then
        return 1
    fi
    if grep -Eiq \
        'AssumptionViolatedException|assumption (failed|violation)|test (ignored|skipped)' \
        <<<"$normalized"; then
        return 1
    fi
    mapfile -t terminal_codes < <(
        sed -n 's/^INSTRUMENTATION_CODE: //p' <<<"$normalized"
    )
    [[ "${#terminal_codes[@]}" == 1 && "${terminal_codes[0]}" == -1 ]]
}

android_instrumentation_output_passed_any() {
    local output="$1"
    local normalized summary_count
    local -a terminal_codes

    normalized="${output//$'\r'/}"
    summary_count="$(
        grep -Ec '^OK \([1-9][0-9]* tests?\)$' <<<"$normalized" || true
    )"
    [[ "$summary_count" == 1 ]] || return 1
    if grep -Eq \
        'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|shortMsg=|Process crashed' \
        <<<"$normalized"; then
        return 1
    fi
    if grep -Eiq \
        'AssumptionViolatedException|assumption (failed|violation)|test (ignored|skipped)' \
        <<<"$normalized"; then
        return 1
    fi
    mapfile -t terminal_codes < <(
        sed -n 's/^INSTRUMENTATION_CODE: //p' <<<"$normalized"
    )
    [[ "${#terminal_codes[@]}" == 1 && "${terminal_codes[0]}" == -1 ]]
}
