#!/usr/bin/env bash
# Compila o pacote com.waze.wazetripper, remonta via apktool, enxerta no base.apk original e funde
# com os splits de config num único apk assinado: ./wazetripper.apk (raiz do repo).
# build/gen/base.apk é um intermediário interno.
#
# Regra de ouro (herdada do wazeology, ver DEVELOPMENT.md §3 do wazeology): nenhum XML de
# recurso é recompilado. O graft só troca o manifest binário, os dexes tocados pelo hook e adiciona
# um dex novo com o nosso pacote; a fusão dos splits é binária (APKEditor). verify_merge.py prova
# que nenhum arquivo de recurso mudou. LANGS escolhe os splits de idioma ("all" ou ex. "pt en").
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
require_image
require_apk
cd "$REPO_ROOT"

if [ ! -d "$DECOMP_DIR" ]; then
    echo "build/base_apktool missing — run scripts/decompile.sh && scripts/patch.sh first" >&2
    exit 1
fi

# 0. Trava do build: o layout dos pacotes do Tripper tem que passar no teste sem hardware.
"$SCRIPT_DIR/framecheck.sh"

# 1. Compila o pacote WazeTripper contra o android.jar -> um dex.
log "compiling WazeTripper package -> dex"
run_tools bash -lc '
set -e
rm -rf build/gen/cls build/gen/dexout build/gen/pkg.dex
mkdir -p build/gen/cls build/gen/dexout
javac -source 8 -target 8 -cp /opt/android-sdk/platforms/android-34/android.jar -nowarn \
  -d build/gen/cls $(find src/com -name "*.java")
d8 --min-api 32 --output build/gen/dexout $(find build/gen/cls -name "*.class")
mv build/gen/dexout/classes.dex build/gen/pkg.dex
'

# 2. Remonta a árvore decompilada (dá o manifest binário + todo dex tocado por hook).
log "apktool b (for binary manifest + patched dexes)"
run_tools apktool b "build/base_apktool" -o "build/gen/apktool_out.apk"

# 3. Enxerta no base.apk original. Só os dexes cujo smali foi tocado por um hook são trocados;
#    o conjunto é derivado do marcador injetado, então um hook novo em outro dex é pego sozinho.
REBUILT_DEX_ARGS=()
while read -r sd; do
    case "$sd" in
        smali)          REBUILT_DEX_ARGS+=(--rebuilt-dex "classes.dex") ;;
        smali_classes*) REBUILT_DEX_ARGS+=(--rebuilt-dex "classes${sd#smali_classes}.dex") ;;
    esac
done < <(grep -rlE "Lcom/waze/wazetripper/(TripperOverlay;->attach|NavHooks;->)" "$DECOMP_DIR"/smali*/ \
    | sed -E 's#.*/(smali(_classes[0-9]+)?)/.*#\1#' | sort -u)
if [ "${#REBUILT_DEX_ARGS[@]}" -eq 0 ]; then
    echo "FATAL: no injected hook markers found in $DECOMP_DIR: patches not applied?" >&2
    exit 1
fi

log "grafting onto pristine base.apk"
run_tools python3 scripts/graft.py \
    --pristine "apk/base.apk" \
    --apktool "build/gen/apktool_out.apk" \
    --pkgdex "build/gen/pkg.dex" \
    "${REBUILT_DEX_ARGS[@]}" \
    --out "build/gen/base.apk"

# 4. Funde o base enxertado com os splits de config. want_split TOKEN -> 0 se o split entra.
#    Splits de ABI e densidade sempre entram; idioma só se LANGS for "all" ou listar o token.
ensure_keystore
langs_lc=" $(printf '%s' "$LANGS" | tr 'A-Z' 'a-z') "
want_split() {
    case "$1" in
        *dpi|arm64_v8a|armeabi_v7a|x86|x86_64|mips|mips64) return 0 ;;
    esac
    [ "$LANGS" = "all" ] && return 0
    case "$langs_lc" in *" $1 "*) return 0 ;; esac
    return 1
}

rm -rf build/gen/merge_in build/gen/merged.apk
rm -f wazetripper.apk wazetripper.apk.idsig
mkdir -p build/gen/merge_in
cp build/gen/base.apk build/gen/merge_in/base.apk
kept=0
for s in apk/split_*.apk; do
    tok="$(basename "$s" .apk)"; tok="${tok#split_config.}"
    if want_split "$tok"; then cp "$s" build/gen/merge_in/; kept=$((kept + 1)); fi
done
log "merge input: base + $kept splits (LANGS=$LANGS)"

log "apkeditor m (binary split merge -> single apk)"
run_tools apkeditor m -i build/gen/merge_in -o build/gen/merged.apk

# 5. Alinha e assina, escrevendo ./wazetripper.apk na raiz.
log "zipalign + apksigner (bundled apk -> ./wazetripper.apk)"
run_tools bash -lc '
set -e
BT=/opt/android-sdk/build-tools/34.0.0
"$BT/zipalign" -p -f 4 build/gen/merged.apk wazetripper.apk
"$BT/apksigner" sign --ks build/debug.keystore --ks-pass pass:android --key-pass pass:android wazetripper.apk
rm -f wazetripper.apk.idsig
"$BT/apksigner" verify wazetripper.apk && echo "bundled-apk signature OK"
'

# 6. GATE: prova que a fusão não corrompeu nada (res/* idênticos ao original, .so sem compressão).
log "verifying resource integrity (golden rule preserved: no resource XML recompiled)"
run_tools python3 scripts/verify_merge.py apk/base.apk build/gen/base.apk wazetripper.apk

log "built: ./wazetripper.apk"
