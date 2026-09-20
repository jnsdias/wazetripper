# Tripper Pod BLE protocol (as used by WazeTripper)

Notes on the Bluetooth Low Energy protocol of the Royal Enfield **Tripper Pod** (Meteor 350
navigation display, BLE name `RE_DISP`), as far as WazeTripper needs and has verified. It does **not** cover the
Tripper Dash, which is a different device.

This is **not an official specification**. It was assembled from observing the Pod on real hardware and
from interoperability analysis of existing Tripper apps; no third-party source code is included. Each
value carries a confidence level:

- **Confirmed** — seen working on the Pod hardware.
- **Likely** — consistent across sources, not checked on hardware here.
- **Unverified** — a guess or a proposal; needs a physical test.

The implementation is `src/com/waze/wazetripper/TripperProtocol.java`.

## 1. Transport

| | |
|---|---|
| Device name (scan) | `RE_DISP` |
| Service UUID | `01FF0100-BA5E-F4EE-5CA1-EB1E5E4B1CE0` |
| Characteristic UUID | `01FF0101-BA5E-F4EE-5CA1-EB1E5E4B1CE0` |

- **Origin of the UUIDs.** `01FF0100-BA5E-F4EE-5CA1-EB1E5E4B1CE0` / `01FF0101-…` are the *Wireless UART* service and its
  writable characteristic from NXP's Bluetooth LE SDK (MCUXpresso / Kinetis demo applications), and the Pod's Bluetooth
  address prefix `00:60:37` is registered to NXP Semiconductors. So the transport is NXP's generic serial-over-BLE service;
  the screen protocol described below (`10 11 …`, CRC) is the product's own and is not part of that SDK. (Public sources;
  the manufacturer of the Pod itself is not known.)
- Connect with an explicit `TRANSPORT_LE`. (Confirmed)
- Write with `WRITE_TYPE_NO_RESPONSE` when the characteristic supports it, else `WRITE_TYPE_DEFAULT`.
- **Two links.** Besides connecting as a GATT *client* to the Pod's service, the phone also hosts a local
  GATT *server* with the **same** service/characteristic UUIDs (properties `WRITE`/`WRITE_NO_RESPONSE`).
  The Pod writes replies back through it (its own characteristic has no NOTIFY). No advertising is
  needed: the Pod writes over the connection already established. (Confirmed)
- Wait ~200 ms between service discovery and the first write (writing too fast makes the Pod drop the
  link). Serialize writes: never send the next packet before `onCharacteristicWrite` of the previous one.
- **Firmware watchdog.** The Pod drops the link ~5 s after the last packet it received. Send `PING_FW`
  when 2 s have passed without any write (check once per second). Screens that keep changing (compass,
  navigation) should also be re-sent every ~2 s even when nothing changed. (Observed)
- The Pod advertises only for a short time (under ~30 s, observed) after the ignition is turned on; if it
  is not connected within that window it stops, and only an ignition off/on brings it back. (Observed)

## 2. Packet format

Every packet is **20 bytes**: 18 bytes of payload followed by a 2-byte CRC.

CRC-16/CCITT-FALSE over bytes `[0..17]`: polynomial `0x1021`, init `0xFFFF`, MSB first, no final XOR,
stored big-endian in bytes `[18..19]`.

## 3. Fixed packets (payload bytes 3..17 are `00`; CRC included)

| Name | Hex |
|---|---|
| `HANDSHAKE_SHOW_PIN` (new device: the Pod shows a PIN) | `21 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 50 A7` |
| `HANDSHAKE_RESUME` (already paired) | `21 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 40 45` |
| `PING_FW` (keepalive) | `03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 45 D9` |

### Handshake

- **Known device:** send `HANDSHAKE_RESUME`, start the keepalive, wait 200 ms → `SET_TIME`, wait 150 ms →
  `PING_FW` twice, wait 300 ms → connected.
- **New device:** send `HANDSHAKE_SHOW_PIN`, start the keepalive, wait for the user to type the PIN
  shown on the Pod.
- **PIN** (`0x20`): `[0]=0x20`, `[1..6]` = PIN as ASCII (up to 6 chars), rest `00`.
- **Time** (`0x50`): `[0]=0x50`, `[1]` = hour, `[2]` = minute. 24h: raw hour (0–23). 12h: hour (1–12)
  `| 0x40` for AM, plain for PM. (Not the same scheme as the ETA — see §4.)

## 4. Screens: `CMD_NAVIGATE` (`10 11`)

Compass and navigation share the packet `[0]=0x10 [1]=0x11`.

If **no** screen packet is sent after the handshake (only keepalives), the Pod shows its **native clock**.

### Compass (no route)

`[2]=0x41`, `[6]` = night flag (`01` night / `00` day), `[7]=[11]=[12]=[13]=0xFF`, `[14]` = direction:

| N | NE | E | SE | S | SW | W | NW |
|---|---|---|---|---|---|---|---|
| `10` | `50` | `20` | `70` | `40` | `80` | `30` | `60` |

The direction is the heading split in 45° sectors (offset 22.5°). WazeTripper uses the GPS bearing (steadier
than the magnetometer on a motorbike) with hysteresis so the arrow does not jitter between sectors.
The compass screen has a **watchdog in the Pod's firmware**: if it is the last screen shown and no new compass packet
arrives within ~5 s, the Pod closes the link itself. It is specific to the compass: if the last screen command is a
different one (observed with the call icon, §4.1), the link survives with generic keepalives only. Keep re-sending the
compass every ~2 s.

### Navigation

```
[0]=0x10  [1]=0x11
[2]     current maneuver icon (large)            — §6
[3-4]   distance to the maneuver, 16-bit big-endian
[5]     distance unit: 1 = meters, 2 = "km × 10" (used above 999 m)
[6]     night flag: 01 night / 00 day
[7]     NEXT maneuver icon (small arrow); FF = none
[8-9]   FF FF normally; with a radar ahead: distance to the radar, uint16 meters (see §5; unverified)
[10]    41
[11-13] bottom line (see below); FF FF FF = no data
[14-17] 00
[18-19] CRC
```

Distance encoding: up to 999 m the raw meters (unit 1); above that `meters / 100` with unit 2
(so "22.9 km" is sent as `229`).

Bottom line `[11-13]`:

- **Total distance:** same encoding as the distance above.
- **Time remaining:** `[hours, minutes, 0]`.
- **Arrival time (24h):** `[hour 0–23, minute, 0]`.
- **Arrival time (12h):** `[hour 0–11 | 0x40 if AM or 0x80 if PM, minute, 0]` — a different scheme from
  `SET_TIME`.

Byte `[6]` is an *intensity* that depends on the distance to the maneuver (`[3-5]`), plus `1` at night:

| Distance to maneuver | ≤ 10 m | ≤ 20 m | ≤ 45 m | ≤ 70 m | ≤ 95 m | farther |
|---|---|---|---|---|---|---|
| `[6]` (day) | `50` | `40` | `30` | `20` | `10` | `00` |

Sending only `00`/`01` (day/night) is also accepted, and is what the compass and the special screens use. (The table
is what another Tripper app sends; what the Pod does with it visually is not documented.)

### 4.1 Other screens and commands

| What | Packet (before CRC) | Notes |
|---|---|---|
| **Route started** | `10 11 45 00 00 01 00 FF FF FF 41 FF FF FF ...` | `[2]=0x45` as the *large* icon, distance 0, bottom line `FF FF FF`; shown ~2 s when a navigation begins |
| **Loading / recalculating** | `10 11 1C 00 00 00 00 00 ...` | `[2]=0x1C` (the "re-routing" icon of §6), rest zero, `[6]` = night flag. Seen on the Pod: it draws the *recalculating* icon (it is not a way back to the clock) |
| **Call icon** | `40 05 00 00 00 ...` | Not a `CMD_NAVIGATE` icon: `CMD_KEEPALIVE` (`0x40`) with sub-byte `0x05`. The Pod draws it itself as an overlay (size and position are not documented). Re-send it as the keepalive while the call lasts; to remove it, re-send the current screen |
| **Idle screen** | `10 11 3C 00 00 04 40 15 00 00 41 00 04 03 00 00 00 00` | `[6]` = `0x40` (+1 at night). Draws a generic maneuver (the depart icon) and, like any screen, is dropped by the watchdog after ~5 s if not re-sent. Not used |

The Pod's own clock is shown when, after the handshake, **no** screen packet is sent.

**Watchdog and reconnecting.** Any screen packet must keep being re-sent: about 4–5 s after the last one stops, the
Pod closes the link itself (`status=19`), for the compass, the idle screen and navigation alike; pings and a repeated
handshake do not renew it. After its *own* drop the Pod advertises again at once (a reconnect a few seconds later worked); but after a
disconnection started by the phone it did not reappear in scans, so dropping the link from the phone to get back to
the clock does not work.

**Going back to the clock.** Everything tried to leave a screen without the Pod dropping the link failed, each time with a
drop after 4–5 s: keepalive only, a repeated handshake `21 00`, the idle screen `0x3C`, and the `0x1C` screen sent once
(which just draws the *recalculating* icon). Dropping the link from the phone and reconnecting does not work either.
What does work is letting the Pod drop the link by itself and reconnecting: WazeTripper stops sending screens, the Pod
closes the link after ~5 s, and the automatic reconnection (1 s later) brings it back on its clock.

## 5. Radar

There is no dedicated radar icon known, so a radar is shown by borrowing fields of the navigation screen.

**When to show it** (rule taken from how other Tripper apps behave): only when a radar on your path is within
**300 m** (they search up to 700 m but display up to 300 m); otherwise the screen is normal. It should be
switchable, and it goes away as soon as the radar is passed.

In both encodings `[7]` (the small next-maneuver arrow) becomes `0x3C` (the "depart" icon, the same one other Tripper
apps use for radar). They differ in where the distance goes (the panel's "Radar no Tripper" setting):

| Mode | Distance goes to | Bottom line `[11-13]` |
|---|---|---|
| **Compatible** | `[11-13]`, same encoding as §4 | replaced by the radar distance (hides the route total) |
| **Experimental** | `[8-9]`, uint16 big-endian meters (`FF FF` = unknown) | unchanged |

- Compatible mode is what other Tripper apps do.
- Experimental mode is a hypothesis: `[8-9]` are `FF FF` on every other screen we know (an old layout used them as a
  repeated distance). The idea is that they feed a smaller distance field next to the small arrow. **Unverified**: if
  the Pod ignores them, only the icon shows.
- `0x45` ("route started", seen as a *large* icon) was tried on the small arrow and the Pod drew **no icon**.

## 6. Icon codes (byte `[2]` / `[7]`)

Codes observed on the Pod (Confirmed unless noted):

| Code | Icon | Code | Icon |
|---|---|---|---|
| `00` | destination | `1E` | on-ramp left |
| `01` | destination left | `1F` | on-ramp slight right |
| `02` | destination right | `20` | on-ramp slight left |
| `03` | merge right | `21` | on-ramp sharp right |
| `04` | merge left | `22` | on-ramp sharp left |
| `05` | fork right | `27` | exit/keep right (soft) |
| `06` | fork left | `28` | exit/keep left (soft) |
| `07` | off-ramp right | `2B` | keep lane, highlight left |
| `08` | off-ramp left | `2C` | keep lane, highlight right |
| `09` | straight | `2D` | off-ramp slight right (sharp exit) |
| `0A` | roundabout (clockwise), base | `2E` | off-ramp slight left (sharp exit) |
| `14` | turn left | `2F` | off-ramp keep/sharp right |
| `15` | turn right | `30` | off-ramp keep/sharp left |
| `16` | sharp left | `31` | roundabout (counter-clockwise), base |
| `17` | sharp right | `3C` | depart |
| `18` | slight left / keep left | `3D` | U-turn (counter-clockwise) |
| `19` | slight right / keep right | `3E` | ferry (boat) |
| `1A` | U-turn (clockwise) | `3F` | ferry (train) |
| `1B` | merge | `42` | no mobile data |
| `1C` | re-routing | `44` | low battery |
| `1D` | on-ramp right | `FF` | blank |

Roundabouts (right-hand traffic): `0x32 + (exit − 1)` for exits 1–9 (`0x0B + (exit − 1)` for
left-hand traffic; WazeTripper only implements right-hand).

Untested ranges: `0B–13`, `23–2A`, `32–3B` (partly used by roundabouts), `40–41`, `43`, `45–FE`.
Some sources disagree on `1C`, `1E`, `1F` (re-routing / lane closed / keep vs on-ramp); WazeTripper does not
use them. The soft/sharp exit pairs `27/28` and `2D/2E` were checked on the Pod.

## 7. Waze → Pod translation (what WazeTripper sends)

From `TripperProtocol.maneuverByte()`; Waze names are `Instruction$Type`.

| Waze | Byte `[2]` | Byte `[7]` (next) |
|---|---|---|
| `TURN_LEFT`, `PREPARE_TURN_LEFT` | `14` | `14` |
| `TURN_RIGHT`, `PREPARE_TURN_RIGHT` | `15` | `15` |
| `SHARP_LEFT` / `SHARP_RIGHT` | `16` / `17` | same |
| `SLIGHT_LEFT` / `SLIGHT_RIGHT` | `18` / `19` | same |
| `KEEP_LEFT` / `KEEP_RIGHT` | `28` / `27` | `2B` / `2C` |
| `EXIT_LEFT`, `PREPARE_EXIT_LEFT` | `2E` | `2B` |
| `EXIT_RIGHT`, `PREPARE_EXIT_RIGHT` | `2D` | `2C` |
| `CONTINUE_STRAIGHT`, `ENTER_HOV_LANE` | `09` | `09` |
| `U_TURN` | `3D` | `3D` |
| `ROUNDABOUT_*` | `32 + (exit−1)`, exit clamped to 1–9 | same |
| `APPROACHING_DESTINATION`, `LAST_DIRECTION`, `APPROACHING_STOP_POINT`, `WAYPOINT_DELAY` | `00` | `FF` for `LAST_DIRECTION` |
| anything else | no icon (the previous one is kept; a warning is logged) | `FF` |

Confidence: straight (`09`) and the exit/keep icons (`27/28`, `2D/2E`) were checked on the Pod. Turns,
sharp/slight, U-turn and roundabouts follow the icon table above (**Likely**). `ENTER_HOV_LANE`,
`APPROACHING_STOP_POINT` and `WAYPOINT_DELAY` are guesses (**Unverified**).

## 8. Open questions

- Do bytes `[8-9]` really hold a small distance field, and in which unit/encoding?
- What does Waze's `onEnforcementZoneUpdate(int)` value mean? It is logged (`RADAR zona de fiscalizacao: N`).
- What do the untested icon ranges show? Is there a dedicated radar icon among them?
- Is there any way back to the Pod's native clock **without** the Pod dropping the link? (See §4.1: keepalive only,
  `21 00`, `0x3C` and `0x1C` were tried; all dropped.)
