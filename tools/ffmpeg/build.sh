#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# The computed repository root is deliberate: worktrees do not share cwd.
# shellcheck disable=SC1091
source "$REPO_ROOT/scripts/android-env.sh"

CACHE_DIR="${ANKI_MINER_FFMPEG_SOURCE_CACHE:-$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/downloads}"
BUILD_ROOT="${ANKI_MINER_FFMPEG_BUILD_ROOT:-$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/build/ffmpeg}"
INSTALL_OUTPUT=false
if [[ "${1:-}" == "--install" ]]; then
    INSTALL_OUTPUT=true
    shift
fi
[[ $# -eq 0 ]] || {
    echo "Usage: tools/ffmpeg/build.sh [--install]" >&2
    exit 2
}

"$SCRIPT_DIR/verify-sources.sh" "$CACHE_DIR" download

NDK_ROOT="$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION"
[[ -x "$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang" ]] || {
    echo "Pinned NDK $ANDROID_NDK_VERSION is not installed." >&2
    echo "Accept the Android SDK licenses, then run scripts/install-android-sdk-packages.sh." >&2
    exit 1
}
for command in make patch pkg-config tar; do
    command -v "$command" >/dev/null || {
        echo "Required host command is missing: $command" >&2
        exit 1
    }
done

builder_archive="$(awk '$1 == "builder" { print $3 }' "$SCRIPT_DIR/sources.lock")"
builder_dir_name="ffmpeg-android-maker-69bc3f2968e5335fff43123a2bef6c54428144ce"
case "$BUILD_ROOT" in
    "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT"/build/*) ;;
    *)
        echo "Refusing unsafe ffmpeg build root: $BUILD_ROOT" >&2
        exit 2
        ;;
esac
rm -rf "$BUILD_ROOT"
mkdir -p "$BUILD_ROOT"
tar --extract --file "$CACHE_DIR/$builder_archive" --directory "$BUILD_ROOT" --no-same-owner
BUILDER_ROOT="$BUILD_ROOT/$builder_dir_name"

cp "$SCRIPT_DIR/overrides/ffmpeg-android-maker.sh" "$BUILDER_ROOT/ffmpeg-android-maker.sh"
cp "$SCRIPT_DIR/overrides/common-functions.sh" "$BUILDER_ROOT/scripts/common-functions.sh"
cp "$SCRIPT_DIR/overrides/ffmpeg-build.sh" "$BUILDER_ROOT/scripts/ffmpeg/build.sh"
chmod +x "$BUILDER_ROOT/ffmpeg-android-maker.sh"

export ANDROID_SDK_HOME="$ANDROID_HOME"
export ANDROID_NDK_HOME="$NDK_ROOT"
export ANKI_MINER_SOURCE_CACHE="$CACHE_DIR"
export LC_ALL=C
export SOURCE_DATE_EPOCH=1732530957
export TZ=UTC

"$BUILDER_ROOT/ffmpeg-android-maker.sh" \
    --source-tar=7.1.5 \
    --android-api-level=26 \
    --target-abis=arm64-v8a,x86_64 \
    --enable-libmp3lame \
    --enable-libopus

for abi in arm64-v8a x86_64; do
    output_dir="$BUILDER_ROOT/output/bin/$abi"
    readelf="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    for executable in "$output_dir/libffmpeg.so" "$output_dir/libffprobe.so"; do
        python3.13 "$REPO_ROOT/scripts/check_native_elf.py" \
            --elf "$executable" --allow-abi "$abi" --require-pie-cli
        while read -r dependency; do
            case "$dependency" in
                libc.so|libdl.so|liblog.so|libm.so|libz.so) ;;
                *)
                    echo "$executable has non-system dependency $dependency" >&2
                    exit 1
                    ;;
            esac
        done < <("$readelf" --dynamic "$executable" \
            | sed -n 's/.*Shared library: \[\([^]]*\)\].*/\1/p')
    done
    if [[ "$INSTALL_OUTPUT" == true ]]; then
        install_dir="$REPO_ROOT/app/src/main/jniLibs/$abi"
        mkdir -p "$install_dir"
        install -m 0755 "$output_dir/libffmpeg.so" "$install_dir/libffmpeg.so"
        install -m 0755 "$output_dir/libffprobe.so" "$install_dir/libffprobe.so"
    fi
done

echo "Standalone ffmpeg/ffprobe artifacts passed ELF and 16 KiB alignment checks"
