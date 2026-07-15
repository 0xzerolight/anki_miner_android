#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EMULATOR_RUNNER="${ANKI_MINER_S1A_EMULATOR_RUNNER:-$SCRIPT_DIR/run-emulator-tests.sh}"
if (($# < 4)) || [[ "$1" != "--manifest" || "$3" != "--unidic-dir" ]]; then
    echo "Usage: scripts/run-s1a-emulator-tests.sh --manifest FILE --unidic-dir DIR [runner options]" >&2
    exit 2
fi
ORG_GRADLE_PROJECT_ankiMinerS1aManifest="$(realpath "$2")"
ANKI_MINER_TEST_UNIDIC_DIR="$(realpath "$4")"
export ORG_GRADLE_PROJECT_ankiMinerS1aManifest ANKI_MINER_TEST_UNIDIC_DIR
shift 4
if (($#)); then
    "$EMULATOR_RUNNER" "$@"
else
    "$EMULATOR_RUNNER" --page-size 4k
    "$EMULATOR_RUNNER" --page-size 16k
fi
