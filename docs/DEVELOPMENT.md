# Development

How WazeTripper is built, why each step exists, and how to work on it. The end-user side is in the
[README](../README.md); the Tripper's BLE protocol is in [`PROTOCOL.md`](PROTOCOL.md).

## Requirements

- **Docker** — the only host dependency. apktool, APKEditor, d8, aapt2, zipalign, apksigner, apkeep and the JDK all run
  inside the pinned `waze-tools` image (`scripts/build-image.sh`, once). `run_tools` in `scripts/lib.sh` mounts only
  the repo, so anything a tool needs must be inside it.
- A network connection for the first build (the Waze download), and `adb` to install the result.
- Disk: the decompiled Waze and the intermediate APKs take about 3 GB under `build/` and `apk/` (both git-ignored).

## Build pipeline

```bash
scripts/build-image.sh      # once: the toolchain image
scripts/all.sh              # fetch -> decompile -> patch -> build (build runs the framecheck first)
scripts/framecheck.sh       # off-bike packet byte-layout test (also a gate inside build.sh)
scripts/fetch-apk.sh        # step 1 only
scripts/decompile.sh        # step 2 only
scripts/patch.sh            # step 4 only (idempotent)
scripts/build.sh            # steps 6-9 and 11: framecheck, compile, graft, bundle, sign, verify
FORCE=1 scripts/all.sh      # redo the download and the decompile from scratch
```

Result: `./wazetripper.apk` at the repo root. Versions are pinned for reproducibility (Waze 5.23.0.2, apktool,
build-tools, android-34); see `scripts/lib.sh` and `docker/Dockerfile`.

After editing Java in `src/`, keep it **Java 8-compatible and Android-framework-only** (no Kotlin, no AppCompat, no
new resources). Validate packet-builder changes with `scripts/framecheck.sh`.

# The full mechanism

## 0. The mental model: Waze ships as a split bundle, we ship one apk

The Play Store delivers Waze as a `base.apk` plus config splits (ABI, screen density, language). WazeTripper builds one
self-contained APK: the **pristine base with a handful of surgical changes**, merged with the splits, re-signed. The
changes are (a) a few smali hooks in Waze's own classes and (b) one extra dex holding our Java package
`com.waze.wazetripper`. Everything else — every resource, every native library — stays byte-identical to what Waze
shipped. Because the signature is ours, it cannot update the Play Store install in place: uninstall the official
Waze first.

## 1. Fetch the APK (never committed) — `scripts/fetch-apk.sh`

Downloads the pinned Waze version (`WAZE_VERSION`, default `5.23.0.2`) with [apkeep](https://github.com/EFForg/apkeep)
inside the container — from APKPure by default, or Google Play with an account (`APK_SOURCE`, see `.env.example`) —
and `normalize_apks.py` turns whatever came back (XAPK/APKM) into `apk/base.apk` + `apk/split_config.*.apk`. The
step is skipped when those files exist; `FORCE=1` re-downloads. **No APK is ever committed** (`.gitignore` blocks
`*.apk`): it is Waze's app.

## 2. Decompile — `scripts/decompile.sh`

`apktool d` turns the base into `build/base_apktool`: smali for every dex, and the decoded manifest. The decoded
*resources* are only there to let apktool reassemble the dex files and the binary manifest later — they are discarded
(see the next section).

## 3. THE GOLDEN RULE: keep resources pristine (why the graft exists)

apktool/aapt2 **re-encode** resource XML when they rebuild an app, and on Waze that corrupts drawables (a route-card
`InflateException` at runtime). So no resource is ever recompiled. The graft (§7) takes the *original* `base.apk`
and swaps in only: the binary manifest, the dex files a hook touched, and one new dex. `scripts/verify_merge.py` (§9)
proves that no `res/*` file changed. Consequences:

- No layouts, drawables or strings can be added — the UI is built in code (`TripperPanel`, `TripperOverlay`).
- Manifest changes (new permissions, providers, activities) are avoided; that is why exporting logs goes through
  `MediaStore` instead of a `FileProvider`, and why the phone call detection needs no permission (`TripperCall`).

## 4. Smali hooks — reading Waze's live guidance — `patches/apply_patches_wazetripper.py`

The patcher injects small blocks of smali into `build/base_apktool`, each one anchored on a **method signature** and
guarded by a marker so a second run does nothing. There are two kinds:

- **Startup:** `MainActivity.onCreate` calls `TripperOverlay.attach(this)`.
- **Navigation:** callbacks of Waze's `NavigationInfoNativeManager` (current and next maneuver, exit numbers,
  distances, ETA, navigation state, route geometry, radar and alert callbacks) call static methods of `NavHooks`.
  The full list is in the patcher.

The hooks only call static methods of our package; `NavHooks` reads Waze's objects by reflection and catches
`Throwable`, so a failure in our code can never crash Waze. The obfuscated class and method names are **tied to Waze
5.23.0.2**.

**Where the startup hook goes (lessons learned).** It took three attempts, worth knowing before adding another one:

1. *Wrong Activity.* `FreeMapAppActivity` is a bootstrap screen replaced almost immediately by `MainActivity`; views
   attached to it die with it. The hook is in `MainActivity.onCreate`.
2. *Hook position.* Injecting *before* `super.onCreate()` made `findViewById(android.R.id.content)` install the
   DecorView too early, and the superclass chain's `requestWindowFeature()` then crashed
   (`requestFeature() must be called before adding content`). The hook goes right **after** the `invoke-super … onCreate`
   call (`after_super_onCreate`).
3. *`setContentView()` wipes the container afterwards.* Waze's own `onCreate` calls `setContentView()` after our hook,
   removing the button silently. Injecting at the end of the method fails ART verification (`VerifyError`: the `p0`
   register is reused). The fix: keep the safe hook and defer the UI work with a
   `Handler(Looper.getMainLooper()).post(...)` inside `TripperOverlay.attach()`.

Log at each checkpoint (`TAG = "WazeTripper"`) and never swallow exceptions silently — that is what exposed all three.

## 5. The binary manifest — no manifest changes

`patch.sh` changes no manifest. The graft still swaps in the **binary** manifest that `apktool b` reassembles from
`build/base_apktool`, because that is the only manifest apktool can produce; it carries no new permission, activity
or provider. (Waze already declares the Bluetooth permissions the dual-role link needs.)

## 6. Compile the package to a dex — `scripts/build.sh`

First `build.sh` runs `scripts/framecheck.sh` (§9) and stops if it fails. Then, inside the container:
`javac -source 8 -target 8` against `android-34`'s `android.jar` compiles `src/com/**` (Java only, framework-only,
no resources), and `d8 --min-api 32` produces `build/gen/pkg.dex`. The package is small and self-contained, with no
Kotlin standard library to bloat the dex.

## 7. Assemble — graft onto the pristine base — `scripts/graft.py`

`apktool b` rebuilds the decompiled tree only to obtain the binary manifest and the dex files whose smali a hook
touched. `graft.py` takes the **original** `apk/base.apk` and swaps in exactly: that manifest; each hook dex (the set
is derived from the injected markers, so a new hook in another dex is picked up by itself — a hook dex that is not
swapped makes its hook silently vanish); and the package dex as a new, contiguous `classesN.dex`. `resources.arsc`
stays byte-identical to the original.

## 8. Sign and install — `scripts/build.sh`

The grafted base and the config splits are merged into one APK with APKEditor's binary merge (§11), then
`zipalign` and `apksigner` sign it with `build/debug.keystore`, created locally on first build and **never committed**.
Install with `adb install ./wazetripper.apk` (uninstall the official Waze first — the signatures differ).

## 9. Verify

- **Off the bike — `scripts/framecheck.sh`.** A test on the computer's JVM, without Android or hardware, that asserts
  the exact bytes of every packet the app can send (`src/test/com/waze/wazetripper/PacketsCheck.java`). Each check is
  tagged with where its expectation comes from:
  - `REAL` — a packet captured from the log of a real Tripper that drew it;
  - `EXT` — an external reference (the standard CRC-16/CCITT-FALSE check value, fixed packets documented elsewhere);
  - `LOCK` — a regression lock for a layout not yet seen on the Tripper: it only stops the layout from changing by
    accident.

  It also checks the CRC against an independent bit-by-bit implementation, the distance encoding, the day/night and
  distance intensity of byte `[6]`, the bottom-line modes (12h/24h arrival times use a fixed "now" so they are
  deterministic), the compass sectors, and that **every** maneuver type Waze 5.23.0.2 declares has a Tripper icon.
  `build.sh` runs it first, so a broken packet layout never reaches an APK. When you change a builder, update the
  expectation and keep its tag honest: a new layout starts as `LOCK` and becomes `REAL` once a capture proves it.
- **`scripts/verify_merge.py`** (also a gate in `build.sh`): no pristine `res/*` file changed except what the graft
  patched intentionally, the only dropped entry is the obsolete split descriptor, the native libraries are stored
  uncompressed, and every dex survived.
- **On the phone:** Waze cold-starts, the floating button appears, the panel shows the version, pairing and the
  connection work, and the trip log (**Exportar**) shows each packet with its hex.

## 10. Gotchas & troubleshooting

| Symptom | Cause / fix |
|---|---|
| Waze crashes at launch with `requestFeature() must be called before adding content` | The startup hook is before `super.onCreate()`; it must be right after (§4) |
| The button never appears | Waze's `setContentView()` cleared it; the UI work must be posted to the main looper (§4) |
| `VerifyError` when Waze starts | A hook injected at a position where a register is reused; use the safe position (§4) |
| Hooks stop working after a Waze update | Obfuscated names changed; the hooks are tied to 5.23.0.2 |
| Install fails with a signature error | The official Waze is still installed; uninstall it first |
| The Tripper never appears in the scan | It only advertises for a short time after the ignition is turned on (and again at once after it closes the link itself); cycle the ignition with Waze open |
| The Tripper drops the link ~5 s after a screen stops | Firmware watchdog: every screen must be re-sent (see `PROTOCOL.md`) |
| Need the app's log | `adb logcat -s WazeTripper:V` (on WSL, use the Windows `adb.exe`), or export the trip log from the panel |
| A tool cannot see a file | `run_tools` mounts only the repo; copy the file inside it |

## 11. Bundle the base and splits into one apk — `scripts/build.sh`

The config splits (ABI, density, and language when `LANGS=all`, or a list such as `LANGS="pt en"`) are merged with the
grafted base by APKEditor's **binary** merge, which rebuilds the resource *table* but copies every `res/*` file
verbatim — so the golden rule holds. `verify_merge.py` asserts exactly that on the final APK.

# Working on the code

## Layout

```
src/com/waze/wazetripper/   Java code compiled and grafted into Waze (package com.waze.wazetripper)
src/test/                   the packet checks run by scripts/framecheck.sh (not part of the app)
patches/                    the smali patcher (apply_patches_wazetripper.py)
scripts/                    the build pipeline (Docker-based)
docker/                     the toolchain image
docs/                       PROTOCOL.md and these notes
```

## Architecture

| Class | Role |
|---|---|
| `TripperOverlay` | Floating button; entry point `attach(Activity)` called by the injected hook. |
| `TripperPanel` | The bottom-sheet panel: connection, settings, trip logs. UI built in code. |
| `TripperBridge` | Process-wide BLE bridge: GATT client + local GATT server, handshake, write queue, keepalive, automatic reconnection, screen arbitration (navigation > compass > native clock), call icon. |
| `TripperProtocol` | Packet builders, CRC, Waze → Tripper icon translation. See `PROTOCOL.md`; checked by `PacketsCheck`. |
| `NavHooks` | Static entry points called from the injected smali; reads Waze's objects by reflection and forwards to `TripperNav`. |
| `TripperNav` | Navigation state; builds and re-sends the navigation packet every 2 s; route-started and recalculating screens; radar. |
| `CompassManager` | GPS-bearing compass shown when there is no route. |
| `TripperNight` | Reads Waze's day/night theme by reflection. |
| `TripperCall` | Detects a ringing or ongoing phone call through the audio mode (no permission). |
| `TripperLog` | Logcat + per-trip files, export. |
| `TripperPrefs`, `Version` | Preferences; the version constant. |

Everything that touches the UI or BLE state runs on the main thread; hooks arrive on Waze threads and are posted to it.

## Trip logs

`TripperLog` mirrors `android.util.Log` (`TripperLog.i/w/e(tag, msg)`): use it instead of `Log` so lines reach the trip
file. A trip starts and ends with Waze's navigation state (`TripperNav.onNavState`). The last 200 lines before the
start are kept in memory and written at the top of the file. Lines starting with `GEO` (route geometry, i.e.
coordinates) are **never** written to the file. Limits: 30 trips, ~10 MB in total, 2 MB per trip.

`scripts/simulate_route.py` replays a route through adb's mock-location provider using the `GEO` lines from
`adb logcat`; the phone's shell must be allowed to mock locations (see its header).

## Releasing

1. Bump `Version.NAME` in `src/com/waze/wazetripper/Version.java`.
2. Update `CHANGELOG.md`.
3. Run `scripts/framecheck.sh` (the build does too), commit, and tag `vX.Y.Z`.
4. **Never** attach or commit an APK: it is Waze's app, modified.

## Reference

The Tripper link is a BLE serial-style service (NXP's Wireless UART UUIDs) carrying 20-byte packets with a CRC-16, a
PIN-based pairing handled by the app, and screens that must be re-sent every ~2 s or the Tripper closes the link.
Everything known, with a confidence level per value, is in [`PROTOCOL.md`](PROTOCOL.md). It has been validated on a
Royal Enfield Meteor 350 Tripper Pod with Waze 5.23.0.2 and a Samsung Galaxy S23; other bikes, phones and Waze versions are
expected to differ and remain untested.
