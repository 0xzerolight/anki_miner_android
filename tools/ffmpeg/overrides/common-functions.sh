#!/usr/bin/env bash

# Download-free source extraction for the hash-verified project cache.
function downloadTarArchive() {
  LIBRARY_NAME=$1
  DOWNLOAD_URL=$2
  NEED_EXTRA_DIRECTORY=${3:-false}

  ARCHIVE_NAME=${DOWNLOAD_URL##*/}
  LIBRARY_SOURCES="${ARCHIVE_NAME%.tar.*}"
  CACHE_FILE="${ANKI_MINER_SOURCE_CACHE:?}/$ARCHIVE_NAME"

  echo "Ensuring locked sources of ${LIBRARY_NAME} in ${LIBRARY_SOURCES}"
  if [[ ! -d "$LIBRARY_SOURCES" ]]; then
    [[ -f "$CACHE_FILE" ]] || {
      echo "Missing verified source cache entry: $CACHE_FILE" >&2
      exit 1
    }
    cp "$CACHE_FILE" "$ARCHIVE_NAME"

    EXTRACTION_DIR="."
    if [[ "$NEED_EXTRA_DIRECTORY" == true ]]; then
      EXTRACTION_DIR=$LIBRARY_SOURCES
      mkdir "$EXTRACTION_DIR"
    fi

    tar xf "$ARCHIVE_NAME" -C "$EXTRACTION_DIR" --no-same-owner
    rm "$ARCHIVE_NAME"
  fi

  export "SOURCES_DIR_${LIBRARY_NAME}=$(pwd)/${LIBRARY_SOURCES}"
}
