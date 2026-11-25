#!/usr/bin/env python3
"""BetterCap Commander engine.

Runs bettercap locally with its REST API enabled, then exposes a simple
JSON-lines socket API for the Android controller app. Bound to 127.0.0.1 only.

Authorized lab use only.
"""

from __future__ import annotations

import base64
import datetime as dt
import json
import os
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional

VERSION = "1.0.0"
HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")
CAPLET_PATH = os.path.join(HERE, "engine.cap")
HANDSHAKE_CACHE_PATH = os.path.join(HERE, "handshake_cache.json")
HANDSHAKE_ENABLED = True
LAUNCH_TRIGGER = "/sdcard/Download/BC_LAUNCH.trigger"

DEFAULT_CONFIG = {
    "bettercap_path": "/usr/bin/bettercap",
    "iface": "wlan0",
    "api_host": "127.0.0.1",
    "api_port": 8081,
    "api_user": "sc-bc-lab",
    "api_password": "change-me-sc-bc",
    "listen_host": "127.0.0.1",
    "listen_port": 8766,
    "lab_subnet": "192.168.0.0/24",
    "poll_events_sec": 0.8,
    "poll_session_sec": 2.5,
}


def now_iso() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def safe_str(value: Any, max_len: int = 4000) -> str:
    try:
        text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    except Exception:
        text = str(value)
    if len(text) > max_len:
        text = text[:max_len] + "...(truncated)"
    return text


def is_unicast_mac(value: str) -> bool:
    mac = str(value).strip().lower()
    parts = mac.replace("-", ":").split(":")
    if len(parts) != 6 or any(len(p) != 2 for p in parts):
        return False
    try:
        first = int(parts[0], 16)
        if all(int(p, 16) == 0 for p in parts):
            return False
        return first & 1 == 0
    except ValueError:
        return False


class BetterCapREST:
    def __init__(self, cfg: Dict[str, Any]):
        self.base = "http://%s:%d" % (cfg["api_host"], int(cfg["api_port"]))
        token = base64.b64encode(
            ("%s:%s" % (cfg["api_user"], cfg["api_password"])).encode()
        ).decode()
        self.auth = "Basic " + token

    def request(self, method: str, path: str, body: Optional[Any] = None,
                timeout: float = 8.0) -> Any:
        data = None
        headers = {"Authorization": self.auth, "Accept": "application/json"}
        if body is not None:
            data = json.dumps(body, separators=(",", ":")).encode()
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(self.base + path, data=data,
                                     headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            if not raw:
                raise RuntimeError("HTTP %d" % e.code)
        if not raw.strip():
            return {}
        try:
            return json.loads(raw)
        except Exception:
            return {"raw": raw}

    def command(self, line: str) -> Dict[str, Any]:
        result = self.request("POST", "/api/session", {"cmd": line}, timeout=15.0)
        if isinstance(result, dict):
            if "raw" in result and "msg" not in result:
                text = str(result.get("raw", "")).strip()
                lower = text.lower()
                failed = ("error" in lower or "could not" in lower or
                          "no interface" in lower or "failed" in lower)
                result["msg"] = text
                result.setdefault("success", not failed)
            return result
        return {"success": True, "msg": safe_str(result)}


class Engine:
    def __init__(self) -> None:
        self.cfg = dict(DEFAULT_CONFIG)
        if os.path.exists(CONFIG_PATH):
            try:
                with open(CONFIG_PATH, "r", encoding="utf-8") as fh:
                    self.cfg.update(json.load(fh))
            except Exception as exc:
                print("config load failed: %s" % exc, file=sys.stderr)

        self.rest = BetterCapREST(self.cfg)
        self.lock = threading.RLock()
        self.clients: List[socket.socket] = []
        self.proc: Optional[subprocess.Popen] = None
        self.running = True

        self.cache_logs: List[Dict[str, str]] = []
        self.cache_aps: Dict[str, Dict[str, Any]] = {}
        self.cache_hosts: Dict[str, Dict[str, Any]] = {}
        self.cache_ble: Dict[str, Dict[str, Any]] = {}
        self.cache_modules: List[Dict[str, Any]] = []
        self.cache_targets: List[Dict[str, Any]] = []
        self.last_results: Dict[str, Dict[str, Any]] = {}
        self.last_handshake: Dict[str, Any] = {}
        self.handshake_count = 0
        self.handshake_file_size = -1
        # Handshake captures persist; logs and history are fresh per session.
        if os.path.exists(HANDSHAKE_CACHE_PATH):
            try:
                with open(HANDSHAKE_CACHE_PATH, "r", encoding="utf-8") as fh:
                    saved = json.load(fh)
                self.last_handshake = dict(saved.get("last", {}))
                self.handshake_count = int(saved.get("count", 0))
            except Exception:
                pass
        self.seen_events: set = set()
        self.session_meta: Dict[str, Any] = {}

    # ---------- client handling ----------

    def send_client(self, client, event: str, data: Any) -> None:
        line = json.dumps({"event": event, "data": data},
                          separators=(",", ":"), ensure_ascii=False) + "\n"
        try:
            client.sendall(line.encode("utf-8"))
        except Exception:
            pass

    def broadcast(self, event: str, data: Any, cache: bool = False) -> None:
        with self.lock:
            if cache:
                self._cache(event, data)
            targets = list(self.clients)
        for client in targets:
            self.send_client(client, event, data)

    def _cache(self, event: str, data: Any) -> None:
        if event == "log":
            self.cache_logs.append(dict(data))
            self.cache_logs = self.cache_logs[-120:]
        elif event == "result":
            cmd = data.get("cmd", "")
            self.last_results[cmd] = dict(data)

    def emit_log(self, message: str, level: str = "info") -> None:
        self.broadcast("log", {"message": safe_str(message), "level": level}, cache=True)

    def emit_handshake(self, detail: Dict[str, Any]) -> None:
        if not HANDSHAKE_ENABLED:
            return
        with self.lock:
            self.handshake_count += 1
            payload = dict(detail)
            payload["time"] = now_iso()
            payload["count"] = self.handshake_count
            self.last_handshake = payload
            self._save_handshake_cache()
        self.broadcast("handshake", payload)
        bssid = payload.get("bssid") or payload.get("ap") or "unknown AP"
        self.emit_log("HANDSHAKE CAPTURED: %s" % bssid, "success")

    def _delete_handshake_cache(self) -> None:
        try:
            if os.path.exists(HANDSHAKE_CACHE_PATH):
                os.remove(HANDSHAKE_CACHE_PATH)
        except Exception:
            pass

    def clear_history(self) -> None:
        with self.lock:
            self.cache_logs = []
            self.cache_aps = {}
            self.cache_hosts = {}
            self.cache_ble = {}
            self.cache_modules = []
            self.cache_targets = []
            self.last_results = {}
            self.session_meta = {}
        # Fresh log files on every app connection.
        for path in (os.path.join(HERE, "engine.log"),
                     os.path.join(HERE, "bettercap.log")):
            try:
                with open(path, "w", encoding="utf-8"):
                    pass
            except Exception:
                pass

    def _save_handshake_cache(self) -> None:
        try:
            data = {"count": self.handshake_count, "last": self.last_handshake}
            tmp = HANDSHAKE_CACHE_PATH + ".tmp"
            with open(tmp, "w", encoding="utf-8") as fh:
                json.dump(data, fh, ensure_ascii=False)
            os.replace(tmp, HANDSHAKE_CACHE_PATH)
        except Exception:
            pass

    def handle_client(self, client, addr) -> None:
        with self.lock:
            self.clients.append(client)
        self.clear_history()
        try:
            self.send_client(client, "hello", {
                "version": VERSION,
                "message": "BetterCap Commander engine ready",
            })
            self.replay(client)
            client.settimeout(1.0)
            buffered = b""
            while self.running:
                try:
                    chunk = client.recv(65536)
                except socket.timeout:
                    continue
                if not chunk:
                    break
                buffered += chunk
                while b"\n" in buffered:
                    raw, buffered = buffered.split(b"\n", 1)
                    if not raw.strip():
                        continue
                    try:
                        msg = json.loads(raw.decode("utf-8"))
                    except Exception:
                        continue
                    self.dispatch(client, msg)
        except Exception as exc:
            print("client %s error: %s" % (addr, exc), file=sys.stderr)
        finally:
            with self.lock:
                if client in self.clients:
                    self.clients.remove(client)
            try:
                client.close()
            except Exception:
                pass

    def replay(self, client) -> None:
        with self.lock:
            logs = list(self.cache_logs)
            aps = list(self.cache_aps.values())
            hosts = list(self.cache_hosts.values())
            ble = list(self.cache_ble.values())
            modules = list(self.cache_modules)
            results = dict(self.last_results)
            meta = dict(self.session_meta)
            handshake = dict(self.last_handshake)
        for entry in logs:
            self.send_client(client, "log", entry)
        for ap in aps:
            self.send_client(client, "ap", ap)
        for host in hosts:
            self.send_client(client, "host", host)
        for device in ble:
            self.send_client(client, "ble", device)
        if modules:
            self.send_client(client, "modules", modules)
        if meta:
            self.send_client(client, "status", meta)
        for cmd, result in results.items():
            self.send_client(client, "result", result)
        if handshake:
            self.send_client(client, "handshake", handshake)

    # ---------- command dispatch ----------

    def dispatch(self, client, msg: Dict[str, Any]) -> None:
        cmd = msg.get("cmd", "")
        try:
            if cmd == "ping":
                self.send_client(client, "result", {
                    "cmd": "ping", "ok": True, "message": "pong", "code": "ok",
                })
            elif cmd == "status":
                self.send_client(client, "status", self.get_status())
            elif cmd == "session":
                self.refresh_session(force=True)
                self.push_state()
            elif cmd == "modules":
                self.refresh_modules(force=True)
                with self.lock:
                    mods = list(self.cache_modules)
                self.send_client(client, "modules", mods)
            elif cmd == "console":
                line = str(msg.get("line", "")).strip()
                if not line:
                    return self.reply(client, cmd, False, "empty command", "invalid")
                result = self.bc(line)
                text = safe_str(result.get("msg", ""))
                self.emit_log("$ %s\n%s" % (line, text),
                              "info" if result.get("success") else "error")
                self.reply(client, cmd, bool(result.get("success")),
                           text or ("ok" if result.get("success") else "failed"),
                           "ok" if result.get("success") else "error")
            elif cmd == "module":
                action = str(msg.get("action", "")).strip()
                name = str(msg.get("name", "")).strip()
                if not name:
                    return self.reply(client, cmd, False, "module name required", "invalid")
                if action == "on":
                    line = "%s on" % name
                elif action == "off":
                    line = "%s off" % name
                elif action == "options":
                    line = "help %s" % name
                else:
                    return self.reply(client, cmd, False, "unknown action", "invalid")
                result = self.bc(line)
                text = safe_str(result.get("msg", ""))
                self.reply(client, cmd, bool(result.get("success")),
                           text or ("%s %s" % (name, action)),
                           "ok" if result.get("success") else "error")
                self.refresh_modules(force=True)
            elif cmd == "set":
                key = str(msg.get("key", "")).strip()
                value = str(msg.get("value", "")).strip()
                if not key:
                    return self.reply(client, cmd, False, "variable name required", "invalid")
                result = self.bc("set %s %s" % (key, value))
                text = safe_str(result.get("msg", ""))
                self.reply(client, cmd, bool(result.get("success")),
                           text or ("%s=%s" % (key, value)),
                           "ok" if result.get("success") else "error")
            elif cmd == "launch":
                fix = "/sdcard/Download/sc_fix_adapter.sh"
                try:
                    subprocess.run(["sh", fix], stdout=subprocess.DEVNULL,
                                   stderr=subprocess.STDOUT, timeout=20)
                except Exception:
                    pass
                try:
                    self.stop_bettercap()
                    self.start_bettercap()
                except Exception as exc:
                    return self.reply(client, cmd, False,
                                      "restart failed: %s" % exc, "error")
                self.clear_history()
                for line in ("set wifi.interface fluxwl0",
                             "set wifi.recon.channel 0",
                             "wifi.recon on"):
                    self.bc(line)
                self.refresh_modules(force=True)
                self.reply(client, cmd, True, "BetterCap launched fresh", "ok")
            elif cmd == "recon":
                name = str(msg.get("name", "wifi.recon")).strip()
                action = str(msg.get("action", "on")).strip()
                if action not in ("on", "off"):
                    return self.reply(client, cmd, False, "action must be on/off", "invalid")
                result = self.bc("%s %s" % (name, action))
                text = safe_str(result.get("msg", ""))
                self.reply(client, cmd, bool(result.get("success")),
                           text or ("%s %s" % (name, action)),
                           "ok" if result.get("success") else "error")
                self.refresh_modules(force=True)
            elif cmd == "deauth":
                bssid = str(msg.get("bssid", "")).strip()
                client_mac = str(msg.get("client", "")).strip()
                if not bssid:
                    return self.reply(client, cmd, False, "bssid required", "invalid")
                if client_mac:
                    self.bc("wifi.deauth %s -c %s" % (bssid, client_mac))
                else:
                    self.bc("wifi.deauth %s" % bssid)
                message = "deauth sent to %s" % bssid
                if client_mac:
                    message += " client %s" % client_mac
                self.emit_log(message, "warn")
                self.reply(client, cmd, True, message, "ok")
            elif cmd == "force":
                mode = str(msg.get("mode", "all")).strip().lower()
                if mode not in ("all", "list"):
                    return self.reply(client, cmd, False, "mode must be all/list", "invalid")
                raw_bssids = msg.get("bssids", [])
                if not isinstance(raw_bssids, list):
                    raw_bssids = []
                bssids = [str(x).strip().lower() for x in raw_bssids
                          if is_unicast_mac(str(x))]
                if mode == "all" or not bssids:
                    line = "wifi.deauth all"
                    message = "handshake force: deauth all visible APs"
                else:
                    lines = ["wifi.deauth %s" % mac for mac in bssids]
                    result = self.bc(";".join(lines))
                    ok = bool(result.get("success", True))
                    message = "handshake force: %d AP(s)" % len(bssids)
                    self.emit_log(message, "warn")
                    return self.reply(client, cmd, ok,
                                      message, "ok" if ok else "error")
                result = self.bc(line)
                ok = bool(result.get("success", True))
                text = safe_str(result.get("msg", ""))
                if not ok and text:
                    message += " - " + text
                self.emit_log(message, "warn")
                self.reply(client, cmd, ok, message, "ok" if ok else "error")
            elif cmd == "capture":
                bssid = str(msg.get("bssid", "")).strip().lower()
                if not is_unicast_mac(bssid):
                    return self.reply(client, cmd, False, "valid unicast bssid required",
                                      "invalid")
                with self.lock:
                    ap = dict(self.cache_aps.get(bssid, {}))
                ssid = str(ap.get("ssid", "") or bssid)
                try:
                    channel = int(ap.get("channel", 0) or 0)
                except (TypeError, ValueError):
                    channel = 0
                self.bc("set wifi.interface fluxwl0")
                if channel > 0:
                    self.bc("set wifi.recon.channel %d" % channel)
                self.bc("wifi.recon off")
                self.bc("wifi.recon on")
                time.sleep(2.5)
                result = self.bc("wifi.deauth %s" % bssid)
                ok = bool(result.get("success", True))
                message = "handshake capture started: %s ch %s" % (
                    ssid, channel if channel > 0 else "?")
                self.emit_log(message, "warn")
                self.reply(client, cmd, ok, message, "ok" if ok else "error")
            elif cmd == "target":
                action = str(msg.get("action", "add")).strip()
                value = str(msg.get("value", "")).strip()
                if action == "add":
                    if not value:
                        return self.reply(client, cmd, False, "target required", "invalid")
                    self.bc("targets add %s" % value)
                    with self.lock:
                        self.cache_targets.append({"value": value})
                elif action == "clear":
                    self.bc("targets clear")
                    with self.lock:
                        self.cache_targets = []
                else:
                    return self.reply(client, cmd, False, "unknown action", "invalid")
                self.reply(client, cmd, True, "%s %s" % (action, value), "ok")
            elif cmd == "events.clear":
                self.rest.request("DELETE", "/api/events", timeout=8.0)
                self.reply(client, cmd, True, "events cleared", "ok")
            else:
                self.reply(client, cmd, False, "unknown command: %s" % cmd, "unknown")
        except Exception as exc:
            self.reply(client, cmd, False, safe_str(exc), "error")

    def reply(self, client, cmd: str, ok: bool, message: str, code: str) -> None:
        data = {"cmd": cmd, "ok": ok, "message": safe_str(message), "code": code}
        self.broadcast("result", data, cache=True)

    def bc(self, line: str) -> Dict[str, Any]:
        return self.rest.command(line)

    # ---------- bettercap process ----------

    def write_caplet(self) -> None:
        content = """set api.rest.address {host}
set api.rest.port {port}
set api.rest.username {user}
set api.rest.password {password}
set api.rest.alloworigin *
api.rest on
""".format(
            host=self.cfg["api_host"],
            port=int(self.cfg["api_port"]),
            user=self.cfg["api_user"],
            password=self.cfg["api_password"],
        )
        with open(CAPLET_PATH, "w", encoding="utf-8") as fh:
            fh.write(content)

    def start_bettercap(self) -> None:
        binary = self.cfg.get("bettercap_path", "/usr/bin/bettercap")
        if not os.path.exists(binary):
            raise RuntimeError("bettercap binary not found: %s" % binary)
        self.write_caplet()
        cmd = [binary, "-iface", str(self.cfg["iface"]),
               "-caplet", CAPLET_PATH, "-no-colors", "-silent", "-no-history"]
        log_path = os.path.join(HERE, "bettercap.log")
        with open(log_path, "ab", buffering=0) as log:
            self.proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT)
        for _ in range(40):
            try:
                self.rest.request("GET", "/api/session", timeout=2.0)
                return
            except Exception:
                if self.proc.poll() is not None:
                    raise RuntimeError("bettercap exited during startup")
                time.sleep(0.25)
        raise RuntimeError("bettercap API did not come up")

    def stop_bettercap(self) -> None:
        if self.proc and self.proc.poll() is None:
            try:
                self.rest.command("quit")
            except Exception:
                pass
            try:
                self.proc.terminate()
                self.proc.wait(timeout=5)
            except Exception:
                try:
                    self.proc.kill()
                except Exception:
                    pass
        self.proc = None

    # ---------- session polling ----------

    def get_status(self) -> Dict[str, Any]:
        with self.lock:
            return {
                "version": VERSION,
                "active": self.proc is not None and self.proc.poll() is None,
                "iface": str(self.cfg.get("iface", "")),
                "lab_subnet": str(self.cfg.get("lab_subnet", "")),
                "api_port": int(self.cfg["api_port"]),
                "started_at": self.session_meta.get("started_at", ""),
                "clients": len(self.clients),
                "handshakes": 0 if not HANDSHAKE_ENABLED else self.handshake_count,
            }

    def bc_info(self, data: Any, key: str, default: Any = "") -> Any:
        if isinstance(data, dict):
            return data.get(key, default)
        return default

    def refresh_session(self, force: bool = False) -> None:
        try:
            session = self.rest.request("GET", "/api/session", timeout=8.0)
        except Exception as exc:
            self.emit_log("session poll failed: %s" % exc, "error")
            return
        if not isinstance(session, dict):
            return

        with self.lock:
            self.session_meta["started_at"] = str(session.get("started_at", ""))
            self.session_meta["interface"] = safe_str(session.get("interface", {}), 300)
            self.session_meta["gateway"] = safe_str(session.get("gateway", {}), 300)

        wifi = session.get("wifi") or {}
        if isinstance(wifi, dict):
            aps = wifi.get("aps", [])
            if isinstance(aps, list):
                for ap in aps:
                    if not isinstance(ap, dict):
                        continue
                    mac = str(ap.get("mac", "")).strip()
                    if not mac:
                        continue
                    entry = {
                        "bssid": mac,
                        "ssid": str(ap.get("ssid", "") or ap.get("hostname", "")),
                        "channel": int(ap.get("channel", 0) or 0),
                        "signal": int(ap.get("rssi", ap.get("signal", 0)) or 0),
                        "security": str(ap.get("security", "") or ""),
                    }
                    with self.lock:
                        previous = self.cache_aps.get(mac)
                        changed = previous != entry
                        self.cache_aps[mac] = entry
                    if changed or force:
                        self.broadcast("ap", entry)

        lan = session.get("lan") or {}
        if isinstance(lan, dict):
            hosts = lan.get("hosts", [])
            if isinstance(hosts, list):
                for host in hosts:
                    if not isinstance(host, dict):
                        continue
                    ip = str(host.get("ipv4", "")).strip()
                    if not ip:
                        continue
                    entry = {
                        "ip": ip,
                        "mac": str(host.get("mac", "")),
                        "hostname": str(host.get("hostname", "")),
                        "vendor": str(host.get("vendor", "")),
                    }
                    with self.lock:
                        previous = self.cache_hosts.get(ip)
                        changed = previous != entry
                        self.cache_hosts[ip] = entry
                    if changed or force:
                        self.broadcast("host", entry)

        ble = session.get("ble") or {}
        if isinstance(ble, dict):
            devices = ble.get("devices", [])
            if isinstance(devices, list):
                for dev in devices:
                    if not isinstance(dev, dict):
                        continue
                    mac = str(dev.get("mac", "")).strip()
                    if not mac:
                        continue
                    entry = {
                        "mac": mac,
                        "name": str(dev.get("name", "") or dev.get("hostname", "")),
                        "rssi": int(dev.get("rssi", 0) or 0),
                    }
                    with self.lock:
                        previous = self.cache_ble.get(mac)
                        changed = previous != entry
                        self.cache_ble[mac] = entry
                    if changed or force:
                        self.broadcast("ble", entry)

    def push_state(self) -> None:
        with self.lock:
            aps = list(self.cache_aps.values())
            hosts = list(self.cache_hosts.values())
            ble = list(self.cache_ble.values())
        for ap in aps:
            self.broadcast("ap", ap)
        for host in hosts:
            self.broadcast("host", host)
        for dev in ble:
            self.broadcast("ble", dev)

    def refresh_modules(self, force: bool = False) -> None:
        try:
            data = self.rest.request("GET", "/api/session/modules", timeout=8.0)
        except Exception:
            return
        modules = data if isinstance(data, list) else []
        items: List[Dict[str, Any]] = []
        for mod in modules:
            if not isinstance(mod, dict):
                continue
            items.append({
                "name": str(mod.get("name", "")),
                "description": str(mod.get("description", "")),
                "running": bool(mod.get("running", False)),
            })
        with self.lock:
            changed = items != self.cache_modules
            self.cache_modules = items
        if changed or force:
            self.broadcast("modules", items)

    def poll_events(self) -> None:
        try:
            data = self.rest.request("GET", "/api/events?n=80", timeout=8.0)
        except Exception:
            return
        if not isinstance(data, list):
            return
        for item in data:
            if not isinstance(item, dict):
                continue
            tag = str(item.get("tag", ""))
            if not HANDSHAKE_ENABLED and "handshake" in tag.lower():
                continue
            stamp = str(item.get("time", ""))
            key = (tag, stamp)
            with self.lock:
                if key in self.seen_events:
                    continue
                self.seen_events.add(key)
                if len(self.seen_events) > 1000:
                    self.seen_events = set(list(self.seen_events)[-500:])
            payload = item.get("data", "")
            if isinstance(payload, (dict, list)):
                text = safe_str(payload)
            else:
                text = safe_str(str(payload))
            level = "error" if tag in ("sys.log",) and "err" in text.lower() else "info"
            self.emit_log("[%s] %s" % (tag, text), level)
            if "handshake" in tag.lower():
                detail: Dict[str, Any] = {"tag": tag, "raw": text}
                if isinstance(payload, dict):
                    if "file" in payload:
                        detail["file"] = safe_str(payload["file"], 300)
                    ap = (payload.get("bssid") or payload.get("ap") or
                          payload.get("ap_mac"))
                    sta = (payload.get("client") or payload.get("station") or
                           payload.get("sta") or payload.get("sta_mac") or
                           payload.get("mac"))
                    if ap:
                        detail["bssid"] = safe_str(ap, 300)
                    if sta:
                        detail["client"] = safe_str(sta, 300)
                    detail["full"] = bool(payload.get("full", False))
                    detail["half"] = bool(payload.get("half", False))
                self.emit_handshake(detail)
            if tag.startswith("mod."):
                self.refresh_modules()

    def handshake_file_path(self) -> str:
        for path in (
            os.path.expanduser("~/bettercap-wifi-handshakes.pcap"),
            os.path.join(HERE, "bettercap-wifi-handshakes.pcap"),
        ):
            if os.path.exists(path):
                return path
        return os.path.expanduser("~/bettercap-wifi-handshakes.pcap")

    def watch_handshake_file(self, interval: float = 2.0) -> None:
        while self.running:
            try:
                path = self.handshake_file_path()
                size = os.path.getsize(path) if os.path.exists(path) else -1
                if self.handshake_file_size < 0 and size > 0:
                    self.handshake_file_size = size
                elif size > self.handshake_file_size >= 0:
                    self.handshake_file_size = size
                    with self.lock:
                        last = dict(self.last_handshake)
                    # An event already reported this capture; don't double notify.
                    if "tag" not in last:
                        detail = dict(last)
                        detail["file"] = path
                        detail["bytes"] = size
                        detail.setdefault("bssid", "capture file")
                        self.emit_handshake(detail)
            except Exception:
                pass
            time.sleep(max(1.0, interval))

    def launch_trigger_loop(self, interval: float = 1.0) -> None:
        while self.running:
            try:
                if os.path.exists(LAUNCH_TRIGGER):
                    try:
                        os.remove(LAUNCH_TRIGGER)
                    except Exception:
                        pass
                    subprocess.run(
                        ["sh", "/sdcard/Download/BC_LAUNCH.sh"],
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.STDOUT,
                        timeout=90,
                    )
            except Exception:
                pass
            time.sleep(max(0.5, interval))

    # ---------- loops ----------

    def event_loop(self, interval: float) -> None:
        while self.running:
            self.poll_events()
            time.sleep(max(0.2, interval))

    def session_loop(self, interval: float) -> None:
        first = True
        while self.running:
            self.refresh_session(force=first)
            self.refresh_modules(force=first)
            first = False
            self.send_client_status()
            time.sleep(max(0.5, interval))

    def send_client_status(self) -> None:
        self.broadcast("status", self.get_status())

    def serve(self) -> None:
        self.start_bettercap()
        self.emit_log("bettercap %s started on iface %s" %
                      (self.cfg.get("bettercap_path", ""), self.cfg.get("iface", "")), "info")
        self.emit_log("engine listening on %s:%d" %
                      (self.cfg["listen_host"], int(self.cfg["listen_port"])), "info")

        threading.Thread(target=self.event_loop,
                         args=(float(self.cfg["poll_events_sec"]),), daemon=True).start()
        threading.Thread(target=self.session_loop,
                         args=(float(self.cfg["poll_session_sec"]),), daemon=True).start()
        threading.Thread(target=self.watch_handshake_file, daemon=True).start()
        threading.Thread(target=self.launch_trigger_loop, daemon=True).start()

        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((self.cfg["listen_host"], int(self.cfg["listen_port"])))
        server.listen(8)
        server.settimeout(1.0)
        try:
            while self.running:
                try:
                    client, addr = server.accept()
                except socket.timeout:
                    continue
                threading.Thread(target=self.handle_client,
                                 args=(client, addr), daemon=True).start()
        finally:
            server.close()
            self.stop_bettercap()


def main() -> None:
    engine = Engine()
    try:
        engine.serve()
    except KeyboardInterrupt:
        engine.running = False
        engine.stop_bettercap()


if __name__ == "__main__":
    main()
