> [!WARNING]
> ⚠️ AUTHORIZED LAB USE ONLY. Use only on networks and devices you own or are
> explicitly authorized to test. No third-party networks or devices.

# BetterCap Commander engine

Thin Python wrapper around the installed `bettercap` v2.41.5. It starts
bettercap with its REST API bound to `127.0.0.1:8081`, then exposes a JSON-lines
socket API on `127.0.0.1:8766` for the Android controller app.

## Files

- `bettercap_engine.py` — engine, REST wrapper, socket API, cache/replay
- `config.json` — interface, ports, credentials, lab subnet
- `start.sh` / `stop.sh` — process control; `start.sh` truncates both logs
- `relaunch.sh` — full fresh launch: stop, clear logs, repair adapter, start
  engine, open app
- `log_watch.sh` — caps `engine.log` and `bettercap.log` at 200 KB every 60 s
- `engine.cap` — generated at runtime to enable `api.rest` (not tracked)
- `engine.log` / `bettercap.log` — runtime logs (not tracked)

## Commands (app → engine)

| cmd | extras | purpose |
| --- | --- | --- |
| `ping` | — | liveness check |
| `status` | — | engine and bettercap state |
| `session` | — | force session refresh |
| `modules` | — | list bettercap modules |
| `module` | `name`, `action` (`on`/`off`/`options`) | control a module |
| `set` | `key`, `value` | set a bettercap variable |
| `launch` | — | restart BetterCap fresh, repair adapter, clear history |
| `recon` | `name`, `action` (`on`/`off`) | start/stop recon module |
| `deauth` | `bssid`, optional `client` | send WiFi deauth |
| `force` | `mode` (`all`/`list`), optional `bssids` | force handshakes, no allow-list |
| `capture` | `bssid` | one-tap handshake: lock AP channel, recon, deauth that AP |
| `console` | `line` | run any bettercap command |
| `target` | `action`, `value` | add/clear targets |
| `events.clear` | — | clear bettercap event buffer |

Events (engine → app): `hello`, `status`, `log`, `ap`, `host`, `ble`, `modules`,
`result`, `handshake`. Cached state is replayed to every fresh connection;
`clear_history()` truncates logs and clears in-memory AP/log/result caches.

## Fresh launch trigger

The Android **LAUNCH BETTERCAP** button writes
`/sdcard/Download/BC_LAUNCH.trigger`. The engine watcher removes the trigger and
runs `/sdcard/Download/BC_LAUNCH.sh`. That script stops the engine and app,
clears logs, recreates `fluxwl0` if needed, restarts the engine, and opens the
app. No root prompt and no socket command are required. The socket `launch`
command is still available.

## Handshakes

Handshake detection watches bettercap's `wifi.client.handshake` event and the
size of `~/bettercap-wifi-handshakes.pcap`, then broadcasts a `handshake` event
with AP/client/file details. Duplicate notifications are suppressed and the
latest count persists in `handshake_cache.json`.

Bettercap's `half`/`full` flags describe its own event state. For cracking,
verify the pcap contains the client's EAPOL reply (M2) as well as AP messages
(M1/M3) with `tshark` or `aircrack-ng`.

## Interfaces

- `wlan0` — phone chip, managed, used for LAN recon (`net.recon`)
- `fluxwl0` — USB adapter in monitor mode, used for WiFi recon/deauth/handshakes

The USB adapter may need `/sdcard/Download/sc_fix_adapter.sh` to recreate
`fluxwl0` after unplug/reboot. Without it, WiFi recon returns an error.
