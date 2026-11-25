> *[❤️ Sponsor this project on GitHub](https://github.com)**

> ⚠️ AUTHORIZED LAB USE ONLY. Use only on networks and devices you own or are
> explicitly authorized to test. No third-party networks or devices.

# BetterCap Commander

Android controller app plus local engine for `bettercap` v2.41.5.

- `app/` — Android controller source (`com.bettercap.commander`)
- `engine/` — Python engine wrapping the bettercap REST API

The APK is controller-only. bettercap itself runs in the Kali chroot.

## Features

- One-tap **LAUNCH BETTERCAP** from the app Overview tab
- Fresh launch: stop engine/app, clear logs, repair `fluxwl0`, restart engine,
  open app
- WiFi recon, DEAUTH, **CAPTURE HANDSHAKE**, and **FORCE ALL HANDSHAKES** with
  no target allow-list (valid unicast MAC/SSID only)
- Green `HANDSHAKE CAPTURED` card with AP/client/file and persisted count
- Modules browser, bettercap console, LAN hosts, BLE devices
- Logs capped at 200 KB by `log_watch.sh`
- Stale socket writes close and reconnect in 300 ms; queued commands flush
  automatically

## Layout

- `app/src/main/kotlin/com/bettercap/commander/MainActivity.kt` — UI + socket protocol
- `app/build.gradle.kts` — target SDK 35, V1+V2+V3 debug signing
- `engine/bettercap_engine.py` — REST wrapper, socket API, cache/replay, launch watcher
- `engine/config.json` — interface, ports, credentials, lab subnet
- `engine/start.sh`, `engine/stop.sh`, `engine/relaunch.sh`, `engine/log_watch.sh` — process and log control

## Socket API

Engine listens on `127.0.0.1:8766` (JSON lines). bettercap REST API is on
`127.0.0.1:8081`.

Commands include `status`, `modules`, `module`, `set`, `launch`, `recon`,
`deauth`, `force`, `capture`, `console`, `target`, and `events.clear`. Events
include `hello`, `status`, `log`, `ap`, `host`, `ble`, `modules`, `result`, and
`handshake`.

The app queues commands during reconnect gaps. A failed write closes the stale
socket, reconnects in 300 ms, and re-sends queued commands.

## Fresh launch trigger

The **LAUNCH BETTERCAP** button writes `/sdcard/Download/BC_LAUNCH.trigger`.
The engine watcher removes it and runs `/sdcard/Download/BC_LAUNCH.sh`. No root
prompt is needed and the button does not rely on the socket.

## Interfaces

- `wlan0` — managed, LAN recon
- `fluxwl0` — USB adapter in monitor mode, WiFi recon/deauth/handshakes

The USB adapter may re-enumerate and need `/sdcard/Download/sc_fix_adapter.sh`
to recreate `fluxwl0`.

## Build

```sh
cd app
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Installed APK

```text
/sdcard/Download/BetterCap-Commander.apk
MD5: 2240030ce814844520e01e8953c82750
```
