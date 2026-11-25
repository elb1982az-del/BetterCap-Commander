> [!WARNING]
> ⚠️ AUTHORIZED LAB USE ONLY. Use only on networks and devices you own or are
> explicitly authorized to test. No third-party networks or devices.

# BetterCap Commander engine

Thin Python wrapper around the installed `bettercap` binary. It starts bettercap
with its REST API bound to `127.0.0.1:8081`, then exposes a JSON-lines socket API
on `127.0.0.1:8766` for the Android controller app.

## Files

- `bettercap_engine.py` — engine
- `config.json` — interface, ports, credentials, lab subnet
- `engine.cap` — generated caplet that enables `api.rest`
- `start.sh` / `stop.sh` — process control
- `engine.log` / `bettercap.log` — logs

## Commands (app → engine)

| cmd | extras | purpose |
| --- | --- | --- |
| `ping` | — | liveness check |
| `status` | — | engine and bettercap state |
| `session` | — | force session refresh |
| `modules` | — | list bettercap modules |
| `module` | `name`, `action` (`on`/`off`/`options`) | control a module |
| `set` | `key`, `value` | set a bettercap variable |
| `recon` | `name`, `action` (`on`/`off`) | start/stop recon module |
| `deauth` | `bssid`, optional `client` | send WiFi deauth |
| `force` | `mode` (`all`/`list`), optional `bssids` | force handshakes, no allow-list |
| `capture` | `bssid` | one-tap handshake: lock AP channel, recon, deauth that AP |
| `console` | `line` | run any bettercap command |
| `target` | `action`, `value` | add/clear targets |
| `events.clear` | — | clear bettercap event buffer |

Events (engine → app): `hello`, `status`, `log`, `ap`, `host`, `ble`, `modules`,
`result`, `handshake`. Cached state is replayed to every fresh connection.

Handshake detection watches bettercap's `wifi.client.handshake` event and the
size of `~/bettercap-wifi-handshakes.pcap`, then broadcasts a `handshake` event
with AP/client/file details. Duplicate notifications are suppressed.

## Interfaces

- `wlan0` — phone chip, managed, used for LAN recon (`net.recon`)
- `fluxwl0` — USB adapter in monitor mode, used for WiFi recon/deauth

The USB adapter may need `/sdcard/Download/sc_fix_adapter.sh` to recreate
`fluxwl0` after unplug/reboot. Without it, WiFi recon returns an error.
