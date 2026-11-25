> [!WARNING]
> ⚠️ AUTHORIZED LAB USE ONLY. Use only on networks and devices you own or are
> explicitly authorized to test. No third-party networks or devices.

# BetterCap Commander

Android controller app plus local engine for `bettercap` v2.41.5.

- `app/` — Android controller source (`com.bettercap.commander`)
- `engine/` — Python engine wrapping the bettercap REST API

The APK is controller-only. bettercap itself runs in the Kali chroot.

## Layout

- `app/src/main/kotlin/com/bettercap/commander/MainActivity.kt` — UI + socket protocol
- `app/build.gradle.kts` — target SDK 35, V1+V2+V3 debug signing
- `engine/bettercap_engine.py` — REST wrapper, socket API, cache/replay
- `engine/config.json` — interface, ports, credentials, lab subnet
- `engine/start.sh`, `engine/stop.sh` — process control

## Socket API

Engine listens on `127.0.0.1:8766` (JSON lines). bettercap REST API is on
`127.0.0.1:8081`.

Commands include `status`, `modules`, `recon`, `set`, `deauth`, `force`,
`capture`, `console`. Events include `hello`, `status`, `log`, `ap`, `host`,
`ble`, `modules`, `result`, `handshake`.

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
