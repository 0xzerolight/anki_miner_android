#!/usr/bin/env bash
set -eo pipefail

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
export BASE_DIR
export SOURCES_DIR="${BASE_DIR}/sources"
export STATS_DIR="${BASE_DIR}/stats"
export SCRIPTS_DIR="${BASE_DIR}/scripts"
export OUTPUT_DIR="${BASE_DIR}/output"

"${SCRIPTS_DIR}/check-host-machine.sh"

BUILD_DIR="${BASE_DIR}/build"
export BUILD_DIR_FFMPEG="$BUILD_DIR/ffmpeg"
export BUILD_DIR_EXTERNAL="$BUILD_DIR/external"

prepareOutput() {
  local output_bin="${OUTPUT_DIR}/bin/${ANDROID_ABI}"
  mkdir -p "$output_bin"
  install -m 0755 \
    "${BUILD_DIR_FFMPEG}/${ANDROID_ABI}/bin/ffmpeg" \
    "$output_bin/libffmpeg.so"
  install -m 0755 \
    "${BUILD_DIR_FFMPEG}/${ANDROID_ABI}/bin/ffprobe" \
    "$output_bin/libffprobe.so"
}

checkNativeDynamics() {
  local stats_file="${STATS_DIR}/native-dynamics.txt"
  local executable
  for executable in \
    "${BUILD_DIR_FFMPEG}/${ANDROID_ABI}/bin/ffmpeg" \
    "${BUILD_DIR_FFMPEG}/${ANDROID_ABI}/bin/ffprobe"; do
    "${SCRIPTS_DIR}/verify-elf-dynamic.sh" "${FAM_READELF}" "$executable" \
      >> "$stats_file"
  done
}

rm -rf "$BUILD_DIR" "$STATS_DIR" "$OUTPUT_DIR"
mkdir -p "$STATS_DIR" "$OUTPUT_DIR"

# shellcheck source=/dev/null
source "${SCRIPTS_DIR}/export-host-variables.sh"
# shellcheck source=/dev/null
source "${SCRIPTS_DIR}/parse-arguments.sh"

COMPONENTS_TO_BUILD=("${EXTERNAL_LIBRARIES[@]}")
COMPONENTS_TO_BUILD+=(ffmpeg)

for COMPONENT in "${COMPONENTS_TO_BUILD[@]}"; do
  echo "Getting source code of the component: ${COMPONENT}"
  SOURCE_DIR_FOR_COMPONENT="${SOURCES_DIR}/${COMPONENT}"
  mkdir -p "$SOURCE_DIR_FOR_COMPONENT"
  cd "$SOURCE_DIR_FOR_COMPONENT"
  # shellcheck source=/dev/null
  source "${SCRIPTS_DIR}/${COMPONENT}/download.sh"
  COMPONENT_SOURCES_DIR_VARIABLE="SOURCES_DIR_${COMPONENT}"
  if [[ -z "${!COMPONENT_SOURCES_DIR_VARIABLE}" ]]; then
     export "SOURCES_DIR_${COMPONENT}=${SOURCE_DIR_FOR_COMPONENT}"
  fi
  cd "$BASE_DIR"
done

for ABI in "${ABIS_TO_BUILD[@]}"; do
  # shellcheck source=/dev/null
  source "${SCRIPTS_DIR}/export-build-variables.sh" "$ABI"
  for COMPONENT in "${COMPONENTS_TO_BUILD[@]}"; do
    echo "Building the component: ${COMPONENT}"
    COMPONENT_SOURCES_DIR_VARIABLE="SOURCES_DIR_${COMPONENT}"
    cd "${!COMPONENT_SOURCES_DIR_VARIABLE}"
    # shellcheck source=/dev/null
    source "${SCRIPTS_DIR}/${COMPONENT}/build.sh"
    cd "$BASE_DIR"
  done
  checkNativeDynamics
  prepareOutput
done
