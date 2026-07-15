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

./configure \
  --prefix="${BUILD_DIR_FFMPEG}/${ANDROID_ABI}" \
  --enable-cross-compile \
  --target-os=android \
  --arch="${TARGET_TRIPLE_MACHINE_ARCH}" \
  --sysroot="${SYSROOT_PATH}" \
  --cc="${FAM_CC}" \
  --cxx="${FAM_CXX}" \
  --ld="${FAM_LD}" \
  --ar="${FAM_AR}" \
  --as="${FAM_CC}" \
  --nm="${FAM_NM}" \
  --ranlib="${FAM_RANLIB}" \
  --strip="${FAM_STRIP}" \
  --extra-cflags="-O3 -fPIC $DEP_CFLAGS" \
  --extra-ldflags="$EXTRA_LDFLAGS" \
  --extra-ldexeflags="-pie -Wl,-z,max-page-size=16384" \
  --enable-static \
  --disable-shared \
  --enable-pic \
  --disable-autodetect \
  --disable-debug \
  --disable-doc \
  --disable-ffplay \
  --disable-network \
  --disable-vulkan \
  --enable-zlib \
  --pkg-config="${PKG_CONFIG_EXECUTABLE}" \
  --pkg-config-flags=--static \
  "${EXTRA_CONFIGURATION[@]}" \
  "${ADDITIONAL_COMPONENTS[@]}" || exit 1

python3.13 "${SCRIPTS_DIR}/assert-ffmpeg-config.py" config.h || exit 1

"${MAKE_EXECUTABLE}" clean
"${MAKE_EXECUTABLE}" -j"${HOST_NPROC}"
"${MAKE_EXECUTABLE}" install
