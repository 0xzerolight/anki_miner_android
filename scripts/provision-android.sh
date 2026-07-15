#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHECKOUT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

JDK_VERSION="17.0.19_10"
JDK_URL="https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.19%2B10/OpenJDK17U-jdk_x64_linux_hotspot_${JDK_VERSION}.tar.gz"
JDK_SHA256="d8afc263758141a66e0e3aafc321e783f7016696f4eaea067d340a269037d331"

CMDLINE_TOOLS_ARCHIVE="commandlinetools-linux-${ANDROID_CMDLINE_TOOLS_VERSION}_latest.zip"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/$CMDLINE_TOOLS_ARCHIVE"
CMDLINE_TOOLS_SHA256="04453066b540409d975c676d781da1477479dde3761310f1a7eb92a1dfb15af7"
# Google's download table labels this 40-character value SHA-256. It is the
# archive's SHA-1; verify both digests so the pinned download stays auditable.
CMDLINE_TOOLS_SHA1="48833c34b761c10cb20bcd16582129395d121b27"

usage() {
    cat <<'EOF'
Usage: scripts/provision-android.sh

Installs a pinned JDK and a workspace-local Android SDK. This command never
accepts license terms. Use scripts/android-licenses.sh review first. No system
directories or shell profiles are changed.
EOF
}

if (($#)); then
    if [[ "$#" -eq 1 && ( "$1" == "-h" || "$1" == "--help" ) ]]; then
        usage
        exit 0
    fi
    echo "Unknown argument: $1" >&2
    usage >&2
    exit 2
fi

if [[ "$(uname -s)" != "Linux" || "$(uname -m)" != "x86_64" ]]; then
    echo "This pinned bootstrap currently supports Linux x86_64 only." >&2
    exit 1
fi

for command_name in curl python3.13 sha1sum sha256sum tar unzip; do
    if ! command -v "$command_name" >/dev/null; then
        echo "Required command not found: $command_name" >&2
        exit 1
    fi
done

mkdir -p \
    "$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/downloads" \
    "$ANDROID_HOME" \
    "$ANDROID_USER_HOME" \
    "$ANDROID_AVD_HOME" \
    "$GRADLE_USER_HOME"

download_verified() {
    local url="$1"
    local destination="$2"
    local expected_sha256="$3"
    local expected_sha1="${4:-}"

    if [[ ! -f "$destination" ]] || ! echo "$expected_sha256  $destination" | sha256sum --check --status; then
        echo "Downloading $(basename "$destination")"
        curl \
            --fail \
            --location \
            --retry 4 \
            --retry-all-errors \
            --output "$destination.partial" \
            "$url"
        echo "$expected_sha256  $destination.partial" | sha256sum --check --status
        mv "$destination.partial" "$destination"
    fi

    echo "$expected_sha256  $destination" | sha256sum --check --status
    if [[ -n "$expected_sha1" ]]; then
        echo "$expected_sha1  $destination" | sha1sum --check --status
    fi
}

JDK_ARCHIVE="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/downloads/temurin-${JDK_VERSION}.tar.gz"
download_verified "$JDK_URL" "$JDK_ARCHIVE" "$JDK_SHA256"

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    if [[ -e "$JAVA_HOME" ]]; then
        echo "Incomplete JDK directory exists at $JAVA_HOME; remove it and retry." >&2
        exit 1
    fi
    jdk_staging="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/.jdk-staging-$$"
    trap 'rm -rf "${jdk_staging:-}" "${tools_staging:-}"' EXIT
    mkdir -p "$jdk_staging"
    tar -xzf "$JDK_ARCHIVE" --strip-components=1 -C "$jdk_staging"
    mv "$jdk_staging" "$JAVA_HOME"
fi

TOOLS_ARCHIVE="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/downloads/$CMDLINE_TOOLS_ARCHIVE"
download_verified \
    "$CMDLINE_TOOLS_URL" \
    "$TOOLS_ARCHIVE" \
    "$CMDLINE_TOOLS_SHA256" \
    "$CMDLINE_TOOLS_SHA1"

if [[ ! -x "$ANDROID_CMDLINE_TOOLS_HOME/bin/sdkmanager" ]]; then
    if [[ -e "$ANDROID_CMDLINE_TOOLS_HOME" ]]; then
        echo "Incomplete command-line tools directory exists at $ANDROID_CMDLINE_TOOLS_HOME; remove it and retry." >&2
        exit 1
    fi
    tools_staging="$ANKI_MINER_ANDROID_TOOLCHAIN_ROOT/.tools-staging-$$"
    trap 'rm -rf "${jdk_staging:-}" "${tools_staging:-}"' EXIT
    mkdir -p "$tools_staging" "$(dirname "$ANDROID_CMDLINE_TOOLS_HOME")"
    unzip -q "$TOOLS_ARCHIVE" -d "$tools_staging"
    mv "$tools_staging/cmdline-tools" "$ANDROID_CMDLINE_TOOLS_HOME"
    rmdir "$tools_staging"
fi

"$SCRIPT_DIR/install-android-sdk-packages.sh"

create_avd() {
    local name="$1"
    local image="$2"
    if emulator -list-avds | grep -Fx "$name" >/dev/null; then
        return
    fi
    echo "Creating AVD $name"
    printf 'no\n' | avdmanager create avd \
        --force \
        --name "$name" \
        --package "$image" \
        --device "pixel_6"
}

create_avd "$ANDROID_AVD_4K_NAME" "$ANDROID_SYSTEM_IMAGE_4K"
create_avd "$ANDROID_AVD_16K_NAME" "$ANDROID_SYSTEM_IMAGE_16K"

printf 'sdk.dir=%s\n' "$ANDROID_HOME" >"$CHECKOUT_ROOT/local.properties"

"$SCRIPT_DIR/verify-android-toolchain.sh"

echo
echo "Provisioned toolchain:"
java -version
adb version | head -n 1
emulator -version | head -n 1
echo "AVDs: $ANDROID_AVD_4K_NAME, $ANDROID_AVD_16K_NAME"
echo "Environment: source scripts/android-env.sh"
