#!/usr/bin/env bash

# Source this file to use the repository-local JDK, Android SDK and AVD.
_anki_miner_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_anki_miner_checkout_root="$(cd "$_anki_miner_script_dir/.." && pwd)"
_anki_miner_git_common_dir="$({
    git -C "$_anki_miner_checkout_root" rev-parse --path-format=absolute --git-common-dir
} 2>/dev/null || true)"

if [[ "$(basename "$_anki_miner_git_common_dir")" == ".git" ]]; then
    _anki_miner_workspace_root="$(dirname "$_anki_miner_git_common_dir")"
else
    _anki_miner_workspace_root="$_anki_miner_checkout_root"
fi

export ANKI_MINER_ANDROID_TOOLCHAIN_ROOT="${ANKI_MINER_ANDROID_TOOLCHAIN_ROOT:-$_anki_miner_workspace_root/.android-toolchain}"
export ANDROID_HOME="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/android-user-home"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
export GRADLE_USER_HOME="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/gradle-user-home"
export JAVA_HOME="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/jdk-17"

export ANDROID_CMDLINE_TOOLS_VERSION="14742923"
export ANDROID_CMDLINE_TOOLS_HOME="$ANDROID_HOME/cmdline-tools/$ANDROID_CMDLINE_TOOLS_VERSION"
export ANDROID_API_LEVEL="36"
export ANDROID_BUILD_TOOLS_VERSION="36.0.0"
export ANDROID_CMAKE_VERSION="3.22.1"
export ANDROID_CMAKE_HOME="$ANDROID_HOME/cmake/$ANDROID_CMAKE_VERSION"
export ANDROID_NDK_VERSION="28.2.13676358"
export ANDROID_SYSTEM_IMAGE_API26="system-images;android-26;google_apis;x86_64"
export ANDROID_SYSTEM_IMAGE_4K="system-images;android-36;google_apis;x86_64"
export ANDROID_SYSTEM_IMAGE_16K="system-images;android-36;google_apis_ps16k;x86_64"
export ANDROID_AVD_API26_NAME="anki_miner_api26"
export ANDROID_AVD_4K_NAME="anki_miner_api36"
export ANDROID_AVD_16K_NAME="anki_miner_api36_ps16k"
export ANDROID_EMULATOR_API26_PORT="5558"
export ANDROID_EMULATOR_4K_PORT="5554"
export ANDROID_EMULATOR_16K_PORT="5556"
export ANDROID_EMULATOR_API26_SERIAL="emulator-$ANDROID_EMULATOR_API26_PORT"
export ANDROID_EMULATOR_4K_SERIAL="emulator-$ANDROID_EMULATOR_4K_PORT"
export ANDROID_EMULATOR_16K_SERIAL="emulator-$ANDROID_EMULATOR_16K_PORT"
export ANDROID_EMULATOR_API26_FINGERPRINT="Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys"
export ANDROID_S1B_TEST_UNIDIC_ARCHIVE="/data/local/tmp/anki-miner-s1b-unidic.zip"

export PATH="$JAVA_HOME/bin:$ANDROID_CMDLINE_TOOLS_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS_VERSION:$ANDROID_CMAKE_HOME/bin:$PATH"

unset _anki_miner_script_dir
unset _anki_miner_checkout_root
unset _anki_miner_git_common_dir
unset _anki_miner_workspace_root
