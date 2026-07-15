#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

ARTIFACT=""
PYTHON_ARGS=(--require-app-imy --reject-base-unidic)

usage() {
    cat <<'EOF'
Usage: scripts/check-native-artifact.sh --artifact FILE --allow-abi ABI [options]

Options:
  --allow-abi ABI       Allowed ABI; repeat only for a multi-ABI artifact.
  --forbid-entry TEXT   Reject this text in any recursively nested entry name.
  --require-entry PATH  Require this exact direct archive entry.
  --require-app-imy     Require recursive inspection of Chaquopy app.imy.
  --reject-base-unidic  Reject UniDic payloads in the APK/AAB base module.
  --require-s1a         Require and validate the complete S1a wheel payload.

The gate inspects every ELF in APKs, AABs, nested ZIPs and Chaquopy IMYs.
It rejects UniDic payloads from the APK/AAB base module; a future separate
Play Asset Delivery module remains outside that base-only policy.
APKs additionally require 16 KiB zip alignment and extractNativeLibs=true.
EOF
}

while (($#)); do
    case "$1" in
        --artifact)
            (($# >= 2)) || { usage >&2; exit 2; }
            ARTIFACT="$2"
            shift
            ;;
        --allow-abi|--forbid-entry|--require-entry)
            (($# >= 2)) || { usage >&2; exit 2; }
            PYTHON_ARGS+=("$1" "$2")
            shift
            ;;
        --require-app-imy|--reject-base-unidic|--require-s1a)
            PYTHON_ARGS+=("$1")
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
    shift
done

[[ -n "$ARTIFACT" ]] || { usage >&2; exit 2; }
python3.13 "$SCRIPT_DIR/check_native_artifacts.py" \
    --artifact "$ARTIFACT" \
    "${PYTHON_ARGS[@]}"

case "$ARTIFACT" in
    *.apk)
        command -v zipalign >/dev/null || {
            echo "zipalign is missing; provision Build Tools $ANDROID_BUILD_TOOLS_VERSION" >&2
            exit 1
        }
        zipalign -c -P 16 -v 4 "$ARTIFACT" >/dev/null
        manifest="$(apkanalyzer manifest print "$ARTIFACT")"
        if ! grep -E 'android:extractNativeLibs="true"|extractNativeLibs="true"' <<<"$manifest" >/dev/null; then
            echo "$ARTIFACT: extractNativeLibs=true is required for executable native tools" >&2
            exit 1
        fi
        ;;
    *.aab)
        ;;
    *)
        echo "Unsupported Android artifact: $ARTIFACT" >&2
        exit 2
        ;;
esac

echo "Android native packaging OK: $ARTIFACT"
