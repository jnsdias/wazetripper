#!/usr/bin/env bash
# Shared config + Docker helpers for every script in this repo. (Derivado do wazeology, Apache-2.0 — ver NOTICE.md)
# Host rule: NO tool ever runs on the host. Everything goes through run_tools() (the toolchain image).
set -euo pipefail

# --- repo paths -----------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
APK_DIR="$REPO_ROOT/apk"
BUILD_DIR="$REPO_ROOT/build"
DECOMP_DIR="$BUILD_DIR/base_apktool"
KEYSTORE="$BUILD_DIR/debug.keystore"

# --- pinned versions (reproducibility) ------------------------------------------------------
WAZE_PACKAGE="com.waze"
WAZE_VERSION="${WAZE_VERSION:-5.23.0.2}"      # pinned default; overridable via .env
WAZE_VERSION_CODE="1030725"
APK_SOURCE="${APK_SOURCE:-apk-pure}"          # apk-pure (default) | google-play
LANGS="${LANGS:-all}"                         # languages to bundle: "all" (default) or e.g. "pt en"
IMAGE="waze-tools:latest"

# Toolchain versions are pinned in docker/Dockerfile (apktool 2.10.0, build-tools 34.0.0,
# platform android-34, apkeep 1.0.0). Keep them in sync with these constants for docs.

# Optional .env (WAZE_VERSION, APK_SOURCE, LANGS, GOOGLE_EMAIL, AAS_TOKEN, ...)
if [ -f "$REPO_ROOT/.env" ]; then
    # shellcheck disable=SC1091
    set -a; . "$REPO_ROOT/.env"; set +a
fi

# --- docker helpers -------------------------------------------------------------------------
# Run a tool inside the toolchain image, repo mounted at /work, as the host user so outputs
# are not root-owned. HOME=/tmp gives shell tools a writable home. JAVA_TOOL_OPTIONS pins
# user.home too: the anonymous uid has no /etc/passwd entry, so the JVM (apktool, d8, apksigner)
# would otherwise resolve user.home to the literal "?" and drop its caches in a "?" dir at the
# repo root instead of honouring $HOME.
run_tools() {
    docker run --rm \
        -u "$(id -u):$(id -g)" \
        -e HOME=/tmp \
        -e JAVA_TOOL_OPTIONS=-Duser.home=/tmp \
        -v "$REPO_ROOT:/work" -w /work \
        "$IMAGE" "$@"
}

require_image() {
    if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
        echo "toolchain image '$IMAGE' missing — run scripts/build-image.sh first" >&2
        exit 1
    fi
}

require_apk() {
    if [ ! -f "$APK_DIR/base.apk" ]; then
        echo "apk/base.apk missing — run scripts/fetch-apk.sh first" >&2
        exit 1
    fi
}

# Create a throwaway debug keystore once; reuse it thereafter (stable signing identity so a
# reinstall never needs an uninstall). Gitignored — never committed.
ensure_keystore() {
    if [ ! -f "$KEYSTORE" ]; then
        mkdir -p "$BUILD_DIR"
        echo ">> generating debug keystore (throwaway)"
        run_tools keytool -genkeypair -v -keystore "build/debug.keystore" \
            -storepass android -keypass android -alias androiddebugkey \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=WazeTripper Debug,O=wazetripper,C=BR"
    fi
}

log() { echo ">> $*"; }
