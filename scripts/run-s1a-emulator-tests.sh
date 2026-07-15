#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
EMULATOR_RUNNER="${ANKI_MINER_S1A_EMULATOR_RUNNER:-$SCRIPT_DIR/run-emulator-tests.sh}"
WHEEL_TOOL="${ANKI_MINER_S1A_WHEEL_TOOL:-$REPO_ROOT/tools/wheels/s1a_wheels.py}"
PYTHON_COMMAND="${ANKI_MINER_PYTHON_COMMAND:-python3.13}"
if (($# < 4)) || [[ "$1" != "--manifest" || "$3" != "--unidic-dir" ]]; then
    echo "Usage: scripts/run-s1a-emulator-tests.sh --manifest FILE --unidic-dir DIR [runner options]" >&2
    exit 2
fi
ORG_GRADLE_PROJECT_ankiMinerS1aManifest="$(realpath "$2")"
ANKI_MINER_TEST_UNIDIC_DIR="$(realpath "$4")"
publication_json="$(
    "$WHEEL_TOOL" verify-publication \
        --manifest "$ORG_GRADLE_PROJECT_ankiMinerS1aManifest"
)"
publication_keys="$("$PYTHON_COMMAND" -c '
import json
import sys

value = json.loads(sys.argv[1])
if set(value) != {"schema", "recipe_key", "build_key"} or value["schema"] != 2:
    raise SystemExit("unexpected S1a publication identity")
print(value["recipe_key"])
print(value["build_key"])
' "$publication_json")"
mapfile -t parsed_keys <<<"$publication_keys"
[[ "${#parsed_keys[@]}" -eq 2 \
    && "${parsed_keys[0]}" =~ ^[0-9a-f]{64}$ \
    && "${parsed_keys[1]}" =~ ^[0-9a-f]{64}$ ]] || {
    echo "S1a publication identity is invalid" >&2
    exit 1
}
ORG_GRADLE_PROJECT_ankiMinerS1aRecipeKey="${parsed_keys[0]}"
ORG_GRADLE_PROJECT_ankiMinerS1aBuildKey="${parsed_keys[1]}"
export ORG_GRADLE_PROJECT_ankiMinerS1aManifest
export ORG_GRADLE_PROJECT_ankiMinerS1aRecipeKey
export ORG_GRADLE_PROJECT_ankiMinerS1aBuildKey
export ANKI_MINER_TEST_UNIDIC_DIR
shift 4
if (($#)); then
    "$EMULATOR_RUNNER" "$@"
else
    "$EMULATOR_RUNNER" --page-size 4k
    "$EMULATOR_RUNNER" --page-size 16k
fi
