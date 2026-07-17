#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PRIVATE_REHEARSAL_ARGS=()
if [[ "${1:-}" == "--private-rehearsal" ]]; then
    PRIVATE_REHEARSAL_ARGS=(--private-rehearsal)
    shift
fi

if (($# != 13)); then
    cat <<'EOF' >&2
Usage: scripts/prepare-github-prerelease.sh [--private-rehearsal]
       REPOSITORY TAG UNSIGNED_APK SIGNED_APK
       CERTIFICATE ACCEPTANCE_RECEIPT ACCEPTED_DEBUG_APK RUNTIME_MANIFEST
       S1A_MANIFEST APPROVALS CORRESPONDING_SOURCE NOTICES OUTPUT_DIR
EOF
    exit 2
fi
[[ "${ANKI_MINER_APP_SIGNING_CERT_SHA256:-}" =~ ^[0-9a-f]{64}$ ]] || {
    echo "ANKI_MINER_APP_SIGNING_CERT_SHA256 is missing or invalid" >&2
    exit 1
}

python3.13 "$SCRIPT_DIR/github_release.py" prepare-assets \
    --repo-root "$SCRIPT_DIR/.." \
    --repository "$1" \
    --tag "$2" \
    --unsigned-apk "$3" \
    --signed-apk "$4" \
    --certificate "$5" \
    --expected-certificate "$ANKI_MINER_APP_SIGNING_CERT_SHA256" \
    --acceptance-receipt "$6" \
    --accepted-debug-apk "$7" \
    --runtime-manifest "$8" \
    --s1a-manifest "$9" \
    --golden "$SCRIPT_DIR/../golden/engine-v1.json" \
    --approvals "${10}" \
    --corresponding-source "${11}" \
    --notices "${12}" \
    --output-dir "${13}" \
    "${PRIVATE_REHEARSAL_ARGS[@]}"
