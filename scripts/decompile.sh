#!/usr/bin/env bash
# Decompile the pristine base.apk into build/base_apktool for smali + manifest editing.
# NOTE: the decoded resources here are only used to let apktool reassemble classes*.dex and the
# binary manifest at build time — the rebuilt resources are DISCARDED (see the golden rule).
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
require_image
require_apk

mkdir -p "$BUILD_DIR"
# Skip when the workspace already holds a decompile. FORCE=1 discards it and decompiles afresh.
if [ -d "$DECOMP_DIR" ]; then
    if [ -z "${FORCE:-}" ]; then
        log "build/base_apktool already present — skipping decompile (set FORCE=1 to re-decompile)"
        exit 0
    fi
    log "removing previous decompile ($DECOMP_DIR)"
    rm -rf "$DECOMP_DIR"
fi

log "apktool d apk/base.apk -> build/base_apktool"
run_tools apktool d --force "apk/base.apk" -o "build/base_apktool"
log "decompiled. Next: scripts/patch.sh"
