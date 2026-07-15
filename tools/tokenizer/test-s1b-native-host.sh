#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if (($# != 1)); then
    echo "Usage: tools/tokenizer/test-s1b-native-host.sh <unidic-dicdir>" >&2
    exit 2
fi
dicdir="$(realpath "$1")"
for file in mecabrc sys.dic matrix.bin; do
    [[ -f "$dicdir/$file" ]] || {
        echo "S1b host test: missing $dicdir/$file" >&2
        exit 1
    }
done

python3 "$SCRIPT_DIR/vendor_s1b_mecab.py" --check
command -v g++ >/dev/null || {
    echo "S1b host test: g++ is required" >&2
    exit 1
}

build_dir="$(mktemp -d "${TMPDIR:-/tmp}/anki-miner-s1b-host.XXXXXX")"
trap 'rm -rf "$build_dir"' EXIT

mecab="$REPO_ROOT/third_party/mecab/src"
sources=(
    char_property.cpp
    connector.cpp
    context_id.cpp
    dictionary.cpp
    eval.cpp
    iconv_utils.cpp
    libmecab.cpp
    nbest_generator.cpp
    param.cpp
    string_buffer.cpp
    tagger.cpp
    tokenizer.cpp
    utils.cpp
    viterbi.cpp
    writer.cpp
)
common_arguments=(
    -O2
    -pthread
    -Wno-deprecated-declarations
    -Wno-register
    -DHAVE_MMAP=1
    -DHAVE_SYS_MMAN_H=1
    -DHAVE_SYS_TYPES_H=1
    -I "$mecab"
    -I "$REPO_ROOT/app/src/main/cpp"
)
objects=()
for source in "${sources[@]}"; do
    object="$build_dir/${source%.cpp}.o"
    g++ -std=c++11 "${common_arguments[@]}" \
        -c "$mecab/$source" -o "$object"
    objects+=("$object")
done
g++ -std=c++17 "${common_arguments[@]}" \
    -Wall -Wextra -Werror \
    -c "$REPO_ROOT/app/src/main/cpp/tokenizer_wire.cpp" \
    -o "$build_dir/tokenizer_wire.o"
g++ -std=c++17 "${common_arguments[@]}" \
    -Wall -Wextra -Werror \
    "$SCRIPT_DIR/s1b_native_host_test.cpp" \
    "$build_dir/tokenizer_wire.o" "${objects[@]}" \
    -o "$build_dir/s1b_native_host_test"
"$build_dir/s1b_native_host_test" "$dicdir"

g++ -std=c++17 "${common_arguments[@]}" \
    -Wall -Wextra -Werror \
    "$SCRIPT_DIR/s1b_native_parity_driver.cpp" \
    "$build_dir/tokenizer_wire.o" "${objects[@]}" \
    -o "$build_dir/s1b_native_parity_driver"
PYTHONDONTWRITEBYTECODE=1 python3.13 "$SCRIPT_DIR/verify_s1b_host_parity.py" \
    --driver "$build_dir/s1b_native_parity_driver" \
    --dicdir "$dicdir"
