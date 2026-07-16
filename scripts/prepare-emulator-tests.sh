#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HEALTH_SCRIPT="${ANKI_MINER_HEALTH_SCRIPT:-$SCRIPT_DIR/health.sh}"
RECEIPT=""
ANKIDROID_APK=""
S2_RESET_OPT_IN=false

usage() {
    cat <<'EOF' >&2
Usage: scripts/prepare-emulator-tests.sh --receipt FILE
       [--ankidroid-apk FILE --s2-reset-opt-in]

Runs the authoritative host health gate and writes a receipt for the exact
committed source, manifests, tasks, and APKs. Every emulator must be stopped.
EOF
}

while (($#)); do
    case "$1" in
        --receipt)
            (($# >= 2)) || { usage; exit 2; }
            RECEIPT="$2"
            shift
            ;;
        --ankidroid-apk)
            (($# >= 2)) || { usage; exit 2; }
            ANKIDROID_APK="$2"
            shift
            ;;
        --s2-reset-opt-in)
            S2_RESET_OPT_IN=true
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

[[ -n "$RECEIPT" ]] || { usage; exit 2; }
health_args=(--write-receipt "$RECEIPT")
if [[ -n "$ANKIDROID_APK" ]]; then
    health_args+=(--receipt-ankidroid-apk "$ANKIDROID_APK")
fi
if [[ "$S2_RESET_OPT_IN" == true ]]; then
    health_args+=(--receipt-s2-reset-opt-in)
fi
"$HEALTH_SCRIPT" "${health_args[@]}"
