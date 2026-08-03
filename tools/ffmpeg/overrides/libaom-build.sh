#!/usr/bin/env bash

# Full replacement for the upstream libaom build script.
# - Per-ABI assembly, matching the stance overrides/libdav1d-build.sh and the
#   ffmpeg --disable-x86asm flag already take: arm64 keeps NEON (clang
#   assembles it, no external assembler), x86/x86_64 build C-only via the
#   documented AOM_TARGET_CPU=generic escape. Upstream's recipe would abort on
#   x86_64 with "Unable to find assembler" because the NDK's yasm is not on
#   PATH, and putting it there would add an undeclared host dependency.
# - Encoder only. dav1d already provides AV1 decode, so the decoder would be
#   dead weight in a binary that is committed to the repository.
# - Uses the upstream android.cmake toolchain shim, which is the thing that
#   feeds the NDK toolchain file the ANDROID_* variables.

CMAKE_BUILD_DIR=aom_build_${ANDROID_ABI}
rm -rf "${CMAKE_BUILD_DIR}"
mkdir -p "${CMAKE_BUILD_DIR}"
cd "${CMAKE_BUILD_DIR}" || exit 1

case "${ANDROID_ABI}" in
  x86 | x86_64)
    AOM_CPU_FLAG=(-DAOM_TARGET_CPU=generic)
    ;;
  *)
    AOM_CPU_FLAG=()
    ;;
esac

${CMAKE_EXECUTABLE} .. \
  -DANDROID_PLATFORM=${ANDROID_PLATFORM} \
  -DANDROID_ABI=${ANDROID_ABI} \
  -DCMAKE_TOOLCHAIN_FILE=${SCRIPTS_DIR}/libaom/android.cmake \
  -DCMAKE_INSTALL_PREFIX=${INSTALL_DIR} \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=0 \
  -DCONFIG_PIC=1 \
  -DCONFIG_RUNTIME_CPU_DETECT=0 \
  -DCONFIG_AV1_DECODER=0 \
  -DENABLE_TESTS=0 \
  -DENABLE_DOCS=0 \
  -DENABLE_TESTDATA=0 \
  -DENABLE_EXAMPLES=0 \
  -DENABLE_TOOLS=0 \
  "${AOM_CPU_FLAG[@]}" || exit 1

${MAKE_EXECUTABLE} -j${HOST_NPROC}
${MAKE_EXECUTABLE} install

# --enable-libaom turns on ffmpeg's libaom *decoder* as well, and
# libavcodec/libaomdec.c includes <aom/aom_decoder.h>, which an encoder-only
# libaom does not install. dav1d is the AV1 decoder here, so drop ffmpeg's
# libaom decoder rather than build a second one.
export EXTRA_BUILD_CONFIGURATION_FLAGS="${EXTRA_BUILD_CONFIGURATION_FLAGS} --disable-decoder=libaom_av1"
