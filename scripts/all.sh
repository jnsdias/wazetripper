#!/usr/bin/env bash
# One-shot: fetch -> decompile -> patch -> build.
# Requer a imagem de toolchain (rode scripts/build-image.sh uma vez antes).
# fetch-apk e decompile pulam o trabalho quando as saídas já existem; FORCE=1 refaz ambos.
set -euo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"$D/fetch-apk.sh"
"$D/decompile.sh"
"$D/patch.sh"
"$D/build.sh"         # roda o framecheck, compila src/, enxerta no base.apk, funde os splits e assina ./wazetripper.apk
