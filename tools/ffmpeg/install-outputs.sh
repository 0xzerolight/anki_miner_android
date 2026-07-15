#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "Usage: tools/ffmpeg/install-outputs.sh BUILDER_OUTPUT_BIN JNI_LIBS_DIR" >&2
    exit 2
fi
source_root=$1
destination=$2

artifacts=()
for abi in arm64-v8a x86_64; do
    for name in libffmpeg.so libffprobe.so; do
        artifact="$source_root/$abi/$name"
        [[ -f "$artifact" && ! -L "$artifact" && -x "$artifact" ]] || {
            echo "Validated FFmpeg output is missing or unsafe: $artifact" >&2
            exit 1
        }
        artifacts+=("$artifact")
    done
done

destination_parent=$(dirname "$destination")
mkdir -p "$destination_parent"
[[ ! -L "$destination" && (! -e "$destination" || -d "$destination") ]] || {
    echo "JNI destination must be a directory, not a file or symlink: $destination" >&2
    exit 1
}
for abi in arm64-v8a x86_64; do
    [[ ! -L "$destination/$abi" ]] || {
        echo "JNI ABI destination must not be a symlink: $destination/$abi" >&2
        exit 1
    }
done

stage=$(mktemp -d "$destination_parent/.jniLibs-stage.XXXXXX")
backup=""
committed=false
cleanup() {
    status=$?
    if [[ "$committed" == false && -n "$backup" && -d "$backup" && ! -e "$destination" ]]; then
        mv "$backup" "$destination" || true
    fi
    [[ ! -d "$stage" ]] || rm -rf "$stage"
    if [[ "$committed" == true && -n "$backup" && -d "$backup" ]]; then
        rm -rf "$backup"
    fi
    exit "$status"
}
trap cleanup EXIT

if [[ -d "$destination" ]]; then
    cp -a "$destination/." "$stage/"
fi
artifact_index=0
for abi in arm64-v8a x86_64; do
    mkdir -p "$stage/$abi"
    for name in libffmpeg.so libffprobe.so; do
        install -m 0755 "${artifacts[$artifact_index]}" "$stage/$abi/$name"
        ((artifact_index += 1))
    done
done

if [[ -d "$destination" ]]; then
    backup=$(mktemp -d "$destination_parent/.jniLibs-backup.XXXXXX")
    rmdir "$backup"
    mv "$destination" "$backup"
fi
mv "$stage" "$destination"
committed=true

echo "Installed four validated FFmpeg tools into $destination"
