#!/usr/bin/env bash

case $ANDROID_ABI in
  x86)
    EXTRA_BUILD_CONFIGURATION_FLAGS="$EXTRA_BUILD_CONFIGURATION_FLAGS --disable-asm"
    ;;
  x86_64)
    # Emulator correctness matters more than SIMD. Avoid an undeclared host
    # nasm/yasm dependency in the controlled builder.
    EXTRA_BUILD_CONFIGURATION_FLAGS="$EXTRA_BUILD_CONFIGURATION_FLAGS --disable-x86asm"
    ;;
esac

if [[ "$FFMPEG_GPL_ENABLED" == true ]]; then
    EXTRA_BUILD_CONFIGURATION_FLAGS="$EXTRA_BUILD_CONFIGURATION_FLAGS --enable-gpl"
fi

read -r -a EXTERNAL_LIBRARY_NAMES <<< "$FFMPEG_EXTERNAL_LIBRARIES"
ADDITIONAL_COMPONENTS=()
for LIBRARY_NAME in "${EXTERNAL_LIBRARY_NAMES[@]}"; do
  ADDITIONAL_COMPONENTS+=("--enable-$LIBRARY_NAME")
done
read -r -a EXTRA_CONFIGURATION <<< "$EXTRA_BUILD_CONFIGURATION_FLAGS"

DEP_CFLAGS="-I${BUILD_DIR_EXTERNAL}/${ANDROID_ABI}/include"
DEP_LD_FLAGS="-L${BUILD_DIR_EXTERNAL}/${ANDROID_ABI}/lib $FFMPEG_EXTRA_LD_FLAGS"
EXTRA_LDFLAGS="-Wl,-z,max-page-size=16384 $DEP_LD_FLAGS"

# Common to both passes. The two binaries have nothing in common but the
# container plumbing: ffmpeg encodes, ffprobe only reports. One configure gave
# both of them every encoder, which put libaom and libwebp — over 6 MB — inside a
# binary that never encodes anything.
configure_common=(
  --prefix="${BUILD_DIR_FFMPEG}/${ANDROID_ABI}"
  --enable-cross-compile
  --target-os=android
  --arch="${TARGET_TRIPLE_MACHINE_ARCH}"
  --sysroot="${SYSROOT_PATH}"
  --cc="${FAM_CC}"
  --cxx="${FAM_CXX}"
  --ld="${FAM_LD}"
  --ar="${FAM_AR}"
  --as="${FAM_CC}"
  --nm="${FAM_NM}"
  --ranlib="${FAM_RANLIB}"
  --strip="${FAM_STRIP}"
  --extra-cflags="-O3 -fPIC $DEP_CFLAGS"
  --extra-ldflags="$EXTRA_LDFLAGS"
  --extra-ldexeflags="-pie -Wl,-z,max-page-size=16384"
  --enable-static
  --disable-shared
  --enable-pic
  --disable-autodetect
  --disable-debug
  --disable-doc
  --disable-ffplay
  --disable-devices
  --disable-network
  --disable-vulkan
  --enable-zlib
  --pkg-config="${PKG_CONFIG_EXECUTABLE}"
  --pkg-config-flags=--static
)

# Pass 1 — the full media surface, for ffmpeg.
./configure \
  "${configure_common[@]}" \
  --enable-indev=lavfi \
  "${EXTRA_CONFIGURATION[@]}" \
  "${ADDITIONAL_COMPONENTS[@]}" || exit 1

python3.13 "${SCRIPTS_DIR}/assert-ffmpeg-config.py" \
  config.h config_components.h || exit 1

"${MAKE_EXECUTABLE}" clean
"${MAKE_EXECUTABLE}" -j"${HOST_NPROC}"
"${MAKE_EXECUTABLE}" install

# Pass 2 — probe-only, for ffprobe. Every demuxer, decoder, parser and protocol
# stays: ffprobe reads whatever container a user hands the app, and trimming that
# set to the ones we can think of would fail on the first source we did not.
# Encoders, muxers and filters go, and with them every external encode-only
# library. --disable-ffmpeg keeps this pass from overwriting the binary above.
"${MAKE_EXECUTABLE}" distclean

./configure \
  "${configure_common[@]}" \
  --disable-ffmpeg \
  --disable-encoders \
  --disable-muxers \
  --disable-filters \
  --enable-libdav1d \
  "${EXTRA_CONFIGURATION[@]}" || exit 1

python3.13 "${SCRIPTS_DIR}/assert-ffmpeg-config.py" \
  --profile probe config.h config_components.h || exit 1

"${MAKE_EXECUTABLE}" -j"${HOST_NPROC}"
"${MAKE_EXECUTABLE}" install
