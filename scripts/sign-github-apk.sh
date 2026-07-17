#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

UNSIGNED_APK=""
KEYSTORE=""
KEY_ALIAS=""
CERTIFICATE=""
OUTPUT_APK=""
EXPECTED_SOURCE=""

usage() {
    cat <<'EOF' >&2
Usage: scripts/sign-github-apk.sh --unsigned APK --keystore FILE --alias NAME
       --certificate PEM --output APK --expected-source COMMIT

Passwords are read from ANKI_MINER_SIGNING_STORE_PASSWORD and
ANKI_MINER_SIGNING_KEY_PASSWORD. If unset, they are requested without echo.
The public permanent fingerprint must be in
ANKI_MINER_APP_SIGNING_CERT_SHA256.
Never store the permanent app-signing key or its passwords in the repository
or on a self-hosted runner.
EOF
}

while (($#)); do
    case "$1" in
        --unsigned) UNSIGNED_APK="${2:-}"; shift ;;
        --keystore) KEYSTORE="${2:-}"; shift ;;
        --alias) KEY_ALIAS="${2:-}"; shift ;;
        --certificate) CERTIFICATE="${2:-}"; shift ;;
        --output) OUTPUT_APK="${2:-}"; shift ;;
        --expected-source) EXPECTED_SOURCE="${2:-}"; shift ;;
        -h|--help) usage; exit 0 ;;
        *) usage; exit 2 ;;
    esac
    shift
done

for value in "$UNSIGNED_APK" "$KEYSTORE" "$KEY_ALIAS" "$CERTIFICATE" "$OUTPUT_APK" "$EXPECTED_SOURCE"; do
    [[ -n "$value" ]] || { usage; exit 2; }
done
[[ -f "$UNSIGNED_APK" ]] || { echo "Unsigned APK not found: $UNSIGNED_APK" >&2; exit 1; }
[[ -f "$KEYSTORE" ]] || { echo "Keystore not found: $KEYSTORE" >&2; exit 1; }
[[ -f "$CERTIFICATE" ]] || { echo "Certificate not found: $CERTIFICATE" >&2; exit 1; }
[[ ! -e "$OUTPUT_APK" ]] || { echo "Refusing to overwrite: $OUTPUT_APK" >&2; exit 1; }
[[ "${ANKI_MINER_APP_SIGNING_CERT_SHA256:-}" =~ ^[0-9a-f]{64}$ ]] || {
    echo "ANKI_MINER_APP_SIGNING_CERT_SHA256 is missing or invalid" >&2
    exit 1
}

python3.13 "$SCRIPT_DIR/github_release.py" verify-unsigned \
    --apk "$UNSIGNED_APK" \
    --expected-source "$EXPECTED_SOURCE" >/dev/null
python3.13 "$SCRIPT_DIR/github_release.py" verify-certificate \
    --certificate "$CERTIFICATE" \
    --expected-certificate "$ANKI_MINER_APP_SIGNING_CERT_SHA256" >/dev/null

if [[ -z "${ANKI_MINER_SIGNING_STORE_PASSWORD:-}" ]]; then
    read -r -s -p "App-signing keystore password: " ANKI_MINER_SIGNING_STORE_PASSWORD
    echo >&2
fi
if [[ -z "${ANKI_MINER_SIGNING_KEY_PASSWORD:-}" ]]; then
    read -r -s -p "App-signing key password: " ANKI_MINER_SIGNING_KEY_PASSWORD
    echo >&2
fi
export ANKI_MINER_SIGNING_STORE_PASSWORD ANKI_MINER_SIGNING_KEY_PASSWORD

staging="$(mktemp -d /tmp/anki-miner-apk-signing.XXXXXX)"
cleanup() {
    rm -rf -- "$staging"
    unset ANKI_MINER_SIGNING_STORE_PASSWORD ANKI_MINER_SIGNING_KEY_PASSWORD
}
trap cleanup EXIT
aligned="$staging/aligned.apk"
signed="$staging/signed.apk"

zipalign -P 16 -f 4 "$UNSIGNED_APK" "$aligned"
apksigner sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass env:ANKI_MINER_SIGNING_STORE_PASSWORD \
    --key-pass env:ANKI_MINER_SIGNING_KEY_PASSWORD \
    --min-sdk-version 26 \
    --out "$signed" \
    "$aligned"
zipalign -P 16 -c 4 "$signed"

python3.13 "$SCRIPT_DIR/github_release.py" verify-signed \
    --apk "$signed" \
    --expected-source "$EXPECTED_SOURCE" \
    --expected-certificate "$ANKI_MINER_APP_SIGNING_CERT_SHA256" >/dev/null

unsigned_payload="$(python3.13 "$SCRIPT_DIR/github_release.py" payload-digest --apk "$UNSIGNED_APK")"
signed_payload="$(python3.13 "$SCRIPT_DIR/github_release.py" payload-digest --apk "$signed")"
[[ "$unsigned_payload" == "$signed_payload" ]] || {
    echo "Signing changed the logical APK payload" >&2
    exit 1
}
mkdir -p "$(dirname "$OUTPUT_APK")"
mv "$signed" "$OUTPUT_APK"
echo "$OUTPUT_APK"
