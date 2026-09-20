#!/usr/bin/env bash
# Teste na JVM do computador (sem Android, sem aparelho) do layout exato dos pacotes do Tripper.
# Trava o build: build.sh roda este script antes de compilar. Ver src/test/.../PacketsCheck.java.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"
require_image

log "compiling + running packet byte-layout checks"
run_tools bash -lc '
set -e
rm -rf build/framecheck && mkdir -p build/framecheck
javac -nowarn -d build/framecheck \
  src/com/waze/wazetripper/TripperProtocol.java \
  src/test/com/waze/wazetripper/PacketsCheck.java
java -cp build/framecheck com.waze.wazetripper.PacketsCheck
'
