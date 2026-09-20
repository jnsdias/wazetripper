#!/usr/bin/env bash
# Build the pinned toolchain image (apktool, Android build-tools, apkeep, JDK, ...).
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

log "building $IMAGE (first build downloads the Android SDK, ~1.5 GB)"
docker build -t "$IMAGE" "$REPO_ROOT/docker"

log "sanity-checking tools inside the image"
run_tools bash -lc 'apktool --version && apkeep --version && apkeditor -version 2>/dev/null | head -1 && d8 --version 2>/dev/null | head -1 && javac -version'
log "image ready: $IMAGE"
