#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
EMULATOR_RUNNER="${ANKI_MINER_S1A_EMULATOR_RUNNER:-$SCRIPT_DIR/run-emulator-tests.sh}"
WHEEL_TOOL="${ANKI_MINER_S1A_WHEEL_TOOL:-$REPO_ROOT/tools/wheels/s1a_wheels.py}"
if (($# < 4)) || [[ "$1" != "--manifest" || "$3" != "--unidic-dir" ]]; then
    echo "Usage: scripts/run-s1a-emulator-tests.sh --manifest FILE --unidic-dir DIR [runner options]" >&2
    exit 2
fi
ORG_GRADLE_PROJECT_ankiMinerS1aManifest="$(realpath "$2")"
ANKI_MINER_TEST_UNIDIC_DIR="$(realpath "$4")"
"$WHEEL_TOOL" verify-publication \
    --manifest "$ORG_GRADLE_PROJECT_ankiMinerS1aManifest" >/dev/null
export ORG_GRADLE_PROJECT_ankiMinerS1aManifest
export ANKI_MINER_TEST_UNIDIC_DIR
shift 4
if (($#)); then
    "$EMULATOR_RUNNER" "$@"
else
    "$EMULATOR_RUNNER" --page-size 4k
    "$EMULATOR_RUNNER" --page-size 16k
fi
