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
export ANDROID_NDK_VERSION="28.2.13676358"
export ANDROID_SYSTEM_IMAGE="system-images;android-36;google_apis;x86_64"
export ANDROID_AVD_NAME="anki_miner_api36"

export PATH="$JAVA_HOME/bin:$ANDROID_CMDLINE_TOOLS_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

unset _anki_miner_script_dir
unset _anki_miner_checkout_root
unset _anki_miner_git_common_dir
unset _anki_miner_workspace_root
