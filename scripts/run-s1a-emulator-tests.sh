#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"
EMULATOR_RUNNER="${ANKI_MINER_S1A_EMULATOR_RUNNER:-$SCRIPT_DIR/run-emulator-tests.sh}"
EMULATOR_PREPARER="${ANKI_MINER_EMULATOR_PREPARER:-$SCRIPT_DIR/prepare-emulator-tests.sh}"
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
receipt="${ANKI_MINER_ANDROID_TEST_RECEIPT:-$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/test-receipts/s1a-$(git -C "$REPO_ROOT" rev-parse HEAD).json}"
"$EMULATOR_PREPARER" --receipt "$receipt"
shift 4
if (($#)); then
    "$EMULATOR_RUNNER" \
        --receipt "$receipt" \
        --unidic-dir "$ANKI_MINER_TEST_UNIDIC_DIR" \
        "$@"
else
    "$EMULATOR_RUNNER" \
        --receipt "$receipt" \
        --unidic-dir "$ANKI_MINER_TEST_UNIDIC_DIR" \
        --page-size 4k
    "$EMULATOR_RUNNER" \
        --receipt "$receipt" \
        --unidic-dir "$ANKI_MINER_TEST_UNIDIC_DIR" \
        --page-size 16k
fi
