> [!WARNING]
> ⚠️ AUTHORIZED LAB USE ONLY. Use only on networks and devices you own or are
> explicitly authorized to test. No third-party networks or devices.

# BetterCap Commander (Android controller)

Android controller for the local BetterCap Commander engine. The APK contains
only the controller UI — bettercap itself runs in the Kali chroot. The app
connects to `127.0.0.1:8766` and displays engine events.

## Build

Requirements: JDK 17+, Android SDK platform 35.

```sh
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

Fresh debug build, V1 + V2 + V3 signing, `minSdk 24`, `targetSdk 35`.

## Tabs

- **Overview** — connection state, engine info, last result, engine log
- **WiFi** — AP list, `wifi.recon` control, DEAUTH on selected AP,
  **CAPTURE HANDSHAKE** (locks the AP channel, reconnects recon, deauths that
  AP), and **FORCE ALL HANDSHAKES** (`wifi.deauth all`) with no target allow-list
- **LAN** — discovered hosts, `net.recon` control, BLE devices
- **Handshake alert** — a green card on Overview and WiFi shows
  `HANDSHAKE CAPTURED` as soon as bettercap reports a capture; the latest
  capture and count are replayed on reconnect
- **Modules** — bettercap module list with start/stop controls and filter
- **Console** — run any bettercap command and see its output

DEAUTH requires a valid unicast MAC. Broadcast, multicast and zero MACs are
rejected in the app. There is no allow-list step.

Commands are queued while reconnecting, so taps during a reconnect gap are
flushed instead of producing a send error.

## Installed APK

```text
/sdcard/Download/BetterCap-Commander.apk
MD5: dd18340b267bc9a68c165cb3126e89cd
```
