#!/usr/bin/env bash
# Download the pinned Waze version with apkeep (in-container) and normalize into
# apk/base.apk + apk/split_config.*.apk. The APK is NEVER committed.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
require_image

# Skip the (slow) download when the workspace already holds the normalized apks. FORCE=1 re-fetches.
if [ -z "${FORCE:-}" ] && [ -f "$APK_DIR/base.apk" ] && ls "$APK_DIR"/split_config.*.apk >/dev/null 2>&1; then
    log "apk/base.apk + splits already present — skipping download (set FORCE=1 to re-fetch)"
    exit 0
fi

mkdir -p "$APK_DIR/_dl"
rm -rf "${APK_DIR:?}/_dl"/*

log "downloading ${WAZE_PACKAGE}@${WAZE_VERSION} from ${APK_SOURCE} (apkeep, in container)"
case "$APK_SOURCE" in
    apk-pure)
        run_tools apkeep -a "${WAZE_PACKAGE}@${WAZE_VERSION}" -d apk-pure "apk/_dl"
        ;;
    google-play)
        : "${GOOGLE_EMAIL:?set GOOGLE_EMAIL in .env for google-play}"
        : "${AAS_TOKEN:?set AAS_TOKEN in .env for google-play}"
        # google-play pins by versionCode; splits come down together.
        run_tools apkeep -a "${WAZE_PACKAGE}@${WAZE_VERSION_CODE}" -d google-play \
            -e "$GOOGLE_EMAIL" -t "$AAS_TOKEN" "apk/_dl"
        ;;
    *)
        echo "unknown APK_SOURCE='$APK_SOURCE' (use apk-pure or google-play)" >&2
        exit 1
        ;;
esac

log "normalizing download into apk/base.apk + apk/split_config.*.apk"
run_tools python3 scripts/normalize_apks.py

log "verifying identity"
run_tools aapt2 dump badging "apk/base.apk" | grep -E "^package: name" || true
ls -1 "$APK_DIR"/base.apk "$APK_DIR"/split_*.apk
