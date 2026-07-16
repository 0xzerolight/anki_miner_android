#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OWNER_RUNNER="${ANKI_MINER_S5_OWNER_RUNNER:-$SCRIPT_DIR/run-emulator-tests.sh}"
RECEIPT="${ANKI_MINER_ANDROID_TEST_RECEIPT:-}"
UNIDIC_DIR="${ANKI_MINER_TEST_UNIDIC_DIR:-}"

usage() {
    echo "Usage: scripts/run-s5-video-acceptance.sh --receipt FILE --unidic-dir DIR" >&2
}

while (($#)); do
    case "$1" in
        --receipt)
            (($# >= 2)) || { usage; exit 2; }
            RECEIPT="$2"
            shift
            ;;
        --unidic-dir)
            (($# >= 2)) || { usage; exit 2; }
            UNIDIC_DIR="$2"
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

[[ -n "$RECEIPT" && -f "$RECEIPT" ]] || { usage; exit 2; }
[[ -n "$UNIDIC_DIR" && -d "$UNIDIC_DIR" ]] || { usage; exit 2; }
[[ "${ANKI_MINER_S5_ALLOW_COLLECTION_RESET:-}" == true ]] || {
    echo "S5 requires ANKI_MINER_S5_ALLOW_COLLECTION_RESET=true for the disposable emulator." >&2
    exit 2
}

export ANKI_MINER_TEST_UNIDIC_DIR
ANKI_MINER_TEST_UNIDIC_DIR="$(realpath "$UNIDIC_DIR")"
export ANKI_MINER_S2_ALLOW_COLLECTION_RESET=true
export ANKI_MINER_S2_CONNECTED_RUNNER="${ANKI_MINER_S5_CONNECTED_RUNNER:-$SCRIPT_DIR/run-s5-video-probe.sh}"
"$OWNER_RUNNER" --s2 --receipt "$(realpath "$RECEIPT")"
