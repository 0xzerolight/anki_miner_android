#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCK_FILE="${ANKI_MINER_FFMPEG_LOCK_FILE:-$SCRIPT_DIR/sources.lock}"
CACHE_DIR="${1:-}"
MODE="${2:-download}"

if [[ -z "$CACHE_DIR" || ("$MODE" != "download" && "$MODE" != "offline") ]]; then
    echo "Usage: tools/ffmpeg/verify-sources.sh CACHE_DIR [download|offline]" >&2
    exit 2
fi
mkdir -p "$CACHE_DIR"
CACHE_DIR="$(cd "$CACHE_DIR" && pwd)"

declare -A seen_keys=()
declare -A seen_files=()
while read -r key checksum filename url extra; do
    [[ -z "$key" || "$key" == \#* ]] && continue
    [[ -z "${extra:-}" ]] || {
        echo "sources.lock: unexpected fields for $key" >&2
        exit 2
    }
    [[ "$key" =~ ^[a-z][a-z0-9_]*$ ]] || {
        echo "sources.lock: invalid key $key" >&2
        exit 2
    }
    [[ "$checksum" =~ ^[0-9a-f]{64}$ ]] || {
        echo "sources.lock: invalid SHA-256 for $key" >&2
        exit 2
    }
    [[ "$filename" != */* && "$filename" != "." && "$filename" != ".." ]] || {
        echo "sources.lock: invalid filename for $key" >&2
        exit 2
    }
    [[ "$url" == https://* ]] || {
        echo "sources.lock: source URL must use HTTPS for $key" >&2
        exit 2
    }
    [[ -z "${seen_keys[$key]:-}" && -z "${seen_files[$filename]:-}" ]] || {
        echo "sources.lock: duplicate key or filename for $key" >&2
        exit 2
    }
    seen_keys[$key]=1
    seen_files[$filename]=1

    destination="$CACHE_DIR/$filename"
    if [[ ! -f "$destination" ]]; then
        [[ "$MODE" == "download" ]] || {
            echo "Missing locked source: $destination" >&2
            exit 1
        }
        temporary="$destination.part"
        rm -f "$temporary"
        curl --fail --location --proto '=https' --retry 3 --output "$temporary" "$url"
        echo "$checksum  $temporary" | sha256sum --check --status || {
            rm -f "$temporary"
            echo "Downloaded source hash mismatch: $filename" >&2
            exit 1
        }
        mv "$temporary" "$destination"
    fi
    echo "$checksum  $destination" | sha256sum --check --status || {
        echo "Locked source hash mismatch: $filename" >&2
        exit 1
    }
done < "$LOCK_FILE"

required=(builder ffmpeg libdav1d libmp3lame libopus libwebp)
for key in "${required[@]}"; do
    [[ -n "${seen_keys[$key]:-}" ]] || {
        echo "sources.lock: missing required key $key" >&2
        exit 2
    }
done

echo "Verified ${#seen_keys[@]} locked ffmpeg source archives"
