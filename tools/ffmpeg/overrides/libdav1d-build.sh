#!/usr/bin/env bash

# Full replacement for the upstream libdav1d build script.
# - Per-ABI assembly: arm64 keeps NEON (.S files are assembled by clang, no
#   nasm involved); x86/x86_64 build C-only, matching the ffmpeg
#   --disable-x86asm emulator stance and avoiding an undeclared host nasm
#   dependency in the controlled builder.
# - The upstream script's bare `rm` of the cross file aborts fresh builds
#   under `set -eo pipefail`; the cleanup here is guarded.
# - The nasm cross-file entry is omitted entirely: no ABI in this build
#   needs it, and an empty `nasm = ''` entry would fail meson validation.

CROSS_FILE_NAME=crossfile-${ANDROID_ABI}.meson

rm -f "${CROSS_FILE_NAME}"

case "${ANDROID_ABI}" in
  x86 | x86_64)
    DAV1D_ENABLE_ASM=false
    ;;
  *)
    DAV1D_ENABLE_ASM=true
    ;;
esac

cat > "${CROSS_FILE_NAME}" << EOF
[binaries]
c = '${FAM_CC}'
ar = '${FAM_AR}'
strip = '${FAM_STRIP}'
pkgconfig = '${PKG_CONFIG_EXECUTABLE}'

[properties]
needs_exe_wrapper = true
sys_root = '${SYSROOT_PATH}'

[host_machine]
system = 'linux'
cpu_family = '${CPU_FAMILY}'
cpu = '${TARGET_TRIPLE_MACHINE_ARCH}'
endian = 'little'

[built-in options]
prefix = '${INSTALL_DIR}'
EOF

BUILD_DIRECTORY=build/${ANDROID_ABI}

rm -rf "${BUILD_DIRECTORY}"

${MESON_EXECUTABLE} setup . "${BUILD_DIRECTORY}" \
  --cross-file "${CROSS_FILE_NAME}" \
  --default-library=static \
  -Denable_asm=${DAV1D_ENABLE_ASM} \
  -Denable_tools=false \
  -Denable_tests=false \
  -Denable_examples=false \
  -Dtestdata_tests=false

cd "${BUILD_DIRECTORY}"

${NINJA_EXECUTABLE} -j "${HOST_NPROC}"
${NINJA_EXECUTABLE} install
