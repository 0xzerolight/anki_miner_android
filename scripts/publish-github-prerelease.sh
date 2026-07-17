#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=android-env.sh
source "$SCRIPT_DIR/android-env.sh"

PRIVATE_REHEARSAL_ARGS=()
private_rehearsal=false
if [[ "${1:-}" == "--private-rehearsal" ]]; then
    PRIVATE_REHEARSAL_ARGS=(--private-rehearsal)
    private_rehearsal=true
    shift
fi
if (($# != 1)); then
    echo "Usage: scripts/publish-github-prerelease.sh [--private-rehearsal] TAG" >&2
    exit 2
fi

tag="$1"
[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+-alpha\.[0-9]+$ ]] || {
    echo "Tag must look like v0.1.0-alpha.1" >&2
    exit 1
}
command -v gh >/dev/null || { echo "GitHub CLI is required" >&2; exit 1; }
[[ "${ANKI_MINER_APP_SIGNING_CERT_SHA256:-}" =~ ^[0-9a-f]{64}$ ]] || {
    echo "ANKI_MINER_APP_SIGNING_CERT_SHA256 is missing or invalid" >&2
    exit 1
}

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"
repository="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
visibility="$(gh repo view --json visibility --jq .visibility)"
default_branch="$(gh repo view --json defaultBranchRef --jq .defaultBranchRef.name)"
[[ "$repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ && -n "$default_branch" ]] || {
    echo "Could not resolve the current GitHub repository" >&2
    exit 1
}
if [[ "$private_rehearsal" == true && "$visibility" != "PRIVATE" ]]; then
    echo "Private rehearsal publication is allowed only in a private repository" >&2
    exit 1
fi
if [[ "$private_rehearsal" == false && "$visibility" == "PRIVATE" ]]; then
    echo "A private repository must publish through the explicit rehearsal channel" >&2
    exit 1
fi

head_commit="$(git rev-parse HEAD)"
remote_tag_commit="$(gh api "repos/$repository/commits/$tag" --jq .sha)"
[[ "$remote_tag_commit" == "$head_commit" ]] || {
    echo "The remote tag does not point at the checked-out commit" >&2
    exit 1
}
compare_status="$(
    gh api "repos/$repository/compare/$remote_tag_commit...$default_branch" --jq .status
)"
[[ "$compare_status" == "ahead" || "$compare_status" == "identical" ]] || {
    echo "The release tag is not in the remote default-branch history" >&2
    exit 1
}

release_state="$(
    gh release view "$tag" --json isDraft,isPrerelease,tagName \
        --jq '[.isDraft, .isPrerelease, .tagName] | @tsv'
)"
[[ "$release_state" == $'true\ttrue\t'"$tag" ]] || {
    echo "The release is not the expected draft prerelease" >&2
    exit 1
}
assets_before="$(
    gh api "repos/$repository/releases/tags/$tag" \
        --jq '[.assets[] | {id: .id, name: .name, size: .size}] | sort_by(.name) | @json'
)"

asset_directory="$(mktemp -d)"
cleanup() {
    rm -rf -- "$asset_directory"
}
trap cleanup EXIT
gh release download "$tag" --dir "$asset_directory"
python3.13 "$SCRIPT_DIR/github_release.py" verify-assets \
    --directory "$asset_directory" \
    --tag "$tag" \
    --repository "$repository" \
    --repo-root "$repo_root" \
    --expected-certificate "$ANKI_MINER_APP_SIGNING_CERT_SHA256" \
    "${PRIVATE_REHEARSAL_ARGS[@]}" >/dev/null

gh release edit "$tag" --draft=false --prerelease

published_state="$(
    gh release view "$tag" --json isDraft,isPrerelease,tagName \
        --jq '[.isDraft, .isPrerelease, .tagName] | @tsv'
)"
assets_after="$(
    gh api "repos/$repository/releases/tags/$tag" \
        --jq '[.assets[] | {id: .id, name: .name, size: .size}] | sort_by(.name) | @json'
)"
[[ "$published_state" == $'false\ttrue\t'"$tag" ]] || {
    echo "GitHub did not publish the release as a prerelease" >&2
    exit 1
}
[[ "$assets_after" == "$assets_before" ]] || {
    echo "Release assets changed between verification and publication" >&2
    exit 1
}

echo "Published verified APK prerelease: $repository $tag"
