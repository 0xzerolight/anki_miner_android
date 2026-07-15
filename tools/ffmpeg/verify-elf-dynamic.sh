#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "Usage: tools/ffmpeg/verify-elf-dynamic.sh LLVM_READELF ELF" >&2
    exit 2
fi
readelf=$1
elf=$2
[[ -x "$readelf" ]] || {
    echo "llvm-readelf is not executable: $readelf" >&2
    exit 1
}
[[ -f "$elf" ]] || {
    echo "ELF does not exist: $elf" >&2
    exit 1
}

if ! dynamic_output=$("$readelf" --dynamic "$elf" 2>&1); then
    echo "llvm-readelf failed for $elf:" >&2
    echo "$dynamic_output" >&2
    exit 1
fi
if grep -q 'TEXTREL' <<<"$dynamic_output"; then
    echo "$elf contains text relocations:" >&2
    echo "$dynamic_output" >&2
    exit 1
fi

mapfile -t dependencies < <(
    sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p' <<<"$dynamic_output"
)
((${#dependencies[@]} > 0)) || {
    echo "$elf has no readable dynamic dependencies" >&2
    exit 1
}
for dependency in "${dependencies[@]}"; do
    case "$dependency" in
        libc.so|libdl.so|liblog.so|libm.so|libz.so) ;;
        *)
            echo "$elf has non-system dependency $dependency" >&2
            exit 1
            ;;
    esac
done

echo "ELF dynamic section OK: $elf"
