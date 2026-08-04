#!/usr/bin/env bash

# Full replacement for the upstream libaom download script.
# Upstream fetches https://aomedia.googlesource.com/aom/+archive/<tag>.tar.gz.
# Gitiles regenerates `+archive` tarballs per request, so that URL has no stable
# SHA-256 and could fail verify-sources.sh on a clean cache through no change of
# ours. The aom-releases bucket serves the byte-stable release artifact instead,
# which is what distributions pin.
#
# The release tarball's top-level directory matches the archive stem
# (libaom-3.12.1), so unlike the flat gitiles archive it needs no extra
# extraction directory argument.

source ${SCRIPTS_DIR}/common-functions.sh

AOM_VERSION=3.12.1

downloadTarArchive \
  "libaom" \
  "https://storage.googleapis.com/aom-releases/libaom-${AOM_VERSION}.tar.gz"
