#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

PRIVATE_REHEARSAL_ARGS=()
if [[ "${1:-}" == "--private-rehearsal" ]]; then
    PRIVATE_REHEARSAL_ARGS=(--private-rehearsal)
    shift
fi
if (($# != 3)); then
    echo "Usage: scripts/create-github-draft.sh [--private-rehearsal] TAG ASSET_DIRECTORY RELEASE_NOTES" >&2
    exit 2
fi

tag="$1"
asset_directory="$2"
release_notes="$3"
[[ -d "$asset_directory" ]] || { echo "Asset directory not found" >&2; exit 1; }
[[ -f "$release_notes" ]] || { echo "Release notes not found" >&2; exit 1; }
command -v gh >/dev/null || { echo "GitHub CLI is required" >&2; exit 1; }
[[ "${ANKI_MINER_APP_SIGNING_CERT_SHA256:-}" =~ ^[0-9a-f]{64}$ ]] || {
    echo "ANKI_MINER_APP_SIGNING_CERT_SHA256 is missing or invalid" >&2
    exit 1
}
repository="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
[[ "$repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || {
    echo "Could not resolve the current GitHub repository" >&2
    exit 1
}
repo_root="$(git rev-parse --show-toplevel)"
python3.13 "$SCRIPT_DIR/github_release.py" verify-assets \
    --directory "$asset_directory" --tag "$tag" \
    --repository "$repository" \
    --repo-root "$repo_root" \
    --expected-certificate "$ANKI_MINER_APP_SIGNING_CERT_SHA256" \
    "${PRIVATE_REHEARSAL_ARGS[@]}" >/dev/null
if gh release view "$tag" >/dev/null 2>&1; then
    echo "A GitHub release already exists for $tag; refusing to replace it" >&2
    exit 1
fi

assets=(
    "$asset_directory"/anki-miner-android-*-arm64-v8a.apk
    "$asset_directory"/anki-miner-android-*-corresponding-source.tar.zst
    "$asset_directory"/anki-miner-android-*-notices.tar.zst
    "$asset_directory"/app-signing-certificate.pem
    "$asset_directory"/app-signing-certificate.sha256
    "$asset_directory"/release.json
    "$asset_directory"/SHA256SUMS
)
evidence=("$asset_directory"/anki-miner-android-*-redacted-evidence.tar.zst)
if [[ -f "${evidence[0]}" ]]; then
    assets+=("${evidence[0]}")
fi
[[ ${#assets[@]} -eq 7 || ${#assets[@]} -eq 8 ]] || {
    echo "Release asset allowlist is incomplete" >&2
    exit 1
}
gh release create "$tag" \
    --draft \
    --prerelease \
    --verify-tag \
    --notes-file "$release_notes" \
    "${assets[@]}"
