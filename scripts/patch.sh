#!/usr/bin/env bash
# Injeta o hook de startup do WazeTripper em build/base_apktool. Idempotente.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

if [ ! -d "$DECOMP_DIR" ]; then
    echo "build/base_apktool missing — run scripts/decompile.sh first" >&2
    exit 1
fi

log "applying WazeTripper patch (smali hook)"
run_tools python3 patches/apply_patches_wazetripper.py "build/base_apktool"
