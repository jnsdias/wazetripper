# Changelog

Versioning follows [SemVer](https://semver.org) while in `0.x` (anything may change). The current
version lives in `src/com/waze/wazetripper/Version.java` and is shown in the app's panel and in the
header of exported trip logs.

## 0.5.0 — 2026-09-20

Changed
- The floating button is now the Tripper's bezel with a Wi-Fi symbol that is red (disconnected), orange (connecting) or
  green (connected); the panel header uses the same icon.
- The panel follows the language set inside Waze (Portuguese, Spanish, or English for any other language), falling back
  to the system language. Diagnostic logs stay in Portuguese.

## 0.4.2 — 2026-09-20

First public release.

Added
- `scripts/framecheck.sh` + `src/test/…/PacketsCheck.java`: an off-bike test of the exact bytes of every packet the app
  can send, including packets captured from a real Tripper. `build.sh` runs it first and stops if it fails.
- `TripperProtocol.buildSetTimePacket` and an overload of `buildBottomInfoBytes` that take the time explicitly, so the
  time-dependent packets can be checked deterministically (behavior is unchanged).

Changed
- Leaving the compass or a route no longer sends the `0x1C` screen (the Tripper draws the recalculating icon for ~5 s
  and drops the link anyway); only keepalives are sent. The Tripper closes the link by itself after ~5 s and comes back
  on its clock through the automatic reconnection, which now waits 1 s (instead of 5 s) after a drop started by the Tripper.
- README and `docs/DEVELOPMENT.md` reorganized (table of contents, Security, Background, Install, Usage, Disclaimer,
  License; the build mechanism step by step).

## 0.4.1 — 2026-09-20

Changed
- Leaving the compass or a route for the Pod's clock now sends the `0x1C` screen once and then only keepalives
  (as another Tripper app does), instead of disconnecting from the phone and reconnecting. That never worked: the Tripper did not advertise again after a
  phone-initiated disconnection (after a drop it started itself, it does, at once).

Removed
- The *Voltar ao relógio* setting added in 0.4.0. Tests showed that keepalive only, a repeated handshake and the
  idle screen all get the link dropped by the Pod after 4–5 s.

## 0.4.0 — 2026-09-20

Added
- **Route started** screen (large icon `0x45`, ~2 s) when a navigation begins.
- **Recalculating** screen (`0x1C`) while Waze recomputes the route (detected by the "empty" current-instruction
  distance it sends), capped at 8 s.
- **Call icon** on the Pod while the phone rings or is in a call (detected through the audio mode, no permission
  needed; VoIP calls are ignored). Switchable.
- **Intensity by distance** (byte `[6]`) on the navigation screen. Switchable.
- Setting **Voltar ao relógio** (experimental): how to go back to the Pod's native clock when the compass is turned
  off or the route ends — reconnect (default), keepalive only, soft handshake, or idle screen.
- The trip log header now lists these settings.

## 0.3.3 — 2026-09-20

Fixed
- The radar icon now uses `0x3C` on the small arrow. `0x45` ("route started") drew no icon there.

## 0.3.2 — 2026-09-20

Added
- Setting **Radar no Tripper**: Off / Compatible (`0x3C` + distance on the bottom line, as other Tripper apps do) /
  Experimental (distance in bytes `[8-9]`, bottom line untouched; default).

Changed
- The radar is shown only when it is within **300 m** (previously any active Waze alert, from >1 km away).

## 0.3.1 — 2026-09-20

Fixed
- A radar alert no longer overwrites the bottom line of the navigation screen (which showed the route total,
  e.g. 2.5 km, and turned into the radar distance, e.g. 250 m). The radar now only sets the small arrow
  and puts its distance in bytes `[8-9]` (**unverified** on the Pod).

## 0.3.0 — 2026-09-20

Added
- Trip logs: while Waze is navigating, everything WazeTripper does is recorded to one file per trip
  (icon translations, packets sent, radar events, link state). The panel has **Export** (saves to
  `Downloads/WazeTripper/` and opens the share sheet) and **Clear**. Route geometry (`GEO` lines) is
  never written to these files.
- Warning `NAV manobra ... sem traducao pro Tripper: X` in the log when a Waze maneuver has no Pod icon.
- Automatic reconnection to the known Pod: on Waze start and after a dropped link, with one continuous
  BLE scan for up to 30 minutes. Toggle in the panel; **Disconnect**/**Cancel** stops it.
- Version shown in the panel header and in exported logs.

Changed
- Radar alerts: every radar source (camera alerts, average-speed zones, enforcement zones) lights the
  same indicator.

Removed
- The in-app simulated route ("Simulação de navegação"). `scripts/simulate_route.py` (adb-based) stays.

## 0.2.0 — 2026-09-20

- Real navigation from Waze: current and next maneuver, distance to maneuver, roundabout exit,
  total distance / time remaining / arrival time (selectable), Waze day/night theme, camera-radar
  alerts.

## 0.1.0 — 2026-09-19

- First working version: dual-role BLE (GATT client + local GATT server) running inside the patched
  Waze process, floating button and connection panel, pairing with PIN, 12h/24h clock, clock sync and
  a GPS compass shown when there is no route.
