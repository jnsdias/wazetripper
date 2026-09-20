# Attribution / Créditos

## wazeology

WazeTripper would not exist without **[wazeology](https://github.com/agstrc/wazeology)** by
[agstrc](https://github.com/agstrc), licensed under Apache-2.0. wazeology showed that the Waze Android
app can be patched at the smali level to read its navigation state, and it provides the whole
build pipeline this project reuses. Thank you.

What comes from wazeology:

- `docker/Dockerfile`, `scripts/lib.sh`, `scripts/build-image.sh`, `scripts/fetch-apk.sh`,
  `scripts/decompile.sh`, `scripts/normalize_apks.py`, `scripts/graft.py`, `scripts/verify_merge.py`
  — copied, with minimal changes (`lib.sh` adjusted).
- `scripts/build.sh`, `scripts/patch.sh`, `scripts/all.sh` — rewritten from the originals, without the
  wazeology package.
- `patches/apply_patches_wazetripper.py` follows the mechanism of wazeology's `apply_patches.py`
  (anchoring smali injections on method signatures), and the choice of Waze navigation callbacks to
  hook (`onCurrentInstructionChanged`, `onCurrentInstructionDistanceChanged`, `onExitNumberChanged`,
  `onNavigationStateChanged`) was first made there.
- The design ideas of a process-wide BLE bridge with no foreground service, and the "golden rule" of
  never recompiling the app's resources, come from wazeology's `DEVELOPMENT.md`.

The code in `src/com/waze/wazetripper/` is original to WazeTripper (a Tripper Pod protocol
implementation written for this project). Files derived from wazeology keep their Apache-2.0 terms;
see `LICENSE`.

## Tools used by the build

apktool, APKEditor, Android build-tools (zipalign, apksigner, aapt2, d8) and
[apkeep](https://github.com/EFForg/apkeep) run inside the Docker image; none of them is bundled here.

## Protocol knowledge

The Tripper Pod BLE protocol described in `docs/PROTOCOL.md` was assembled from observing the Pod's
behavior on real hardware and from interoperability analysis of existing Tripper apps. No third-party
source code is included in this repository.

## Trademarks and no affiliation

WazeTripper is an independent hobby project, **not affiliated with, endorsed by or sponsored by**
Waze, Google, Royal Enfield or Eicher Motors. "Waze" is a trademark of Google LLC; "Royal Enfield",
"Tripper Pod" and "Tripper Dash" are trademarks of their respective owners. This repository does not contain, and
must never contain, any Waze APK or other third-party binary.
