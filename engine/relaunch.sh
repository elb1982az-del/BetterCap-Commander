#!/bin/sh
# Fresh start: stop, clear logs, repair adapter, start engine, open app.
cd /opt/BetterCap-Commander || exit 1
./stop.sh >/dev/null 2>&1 || true
sleep 2
: > engine.log
: > bettercap.log
if ! iw dev fluxwl0 info 2>/dev/null | grep -q 'type monitor'; then
  sh /sdcard/Download/sc_fix_adapter.sh >/dev/null 2>&1 || true
fi
./start.sh
P=$(ps -A | awk '/ercap.commander/{print $1; exit}')
[ -n "$P" ] && kill -9 "$P" 2>/dev/null || true
sleep 1
LD_LIBRARY_PATH=/system/lib64/bootstrap /system/bin/bootstrap/linker64 \
  /system/bin/cmd activity start -n com.bettercap.commander/.MainActivity >/dev/null 2>&1
sleep 3
echo "engine=$(cat engine.pid 2>/dev/null)"
echo "app=$(ps -A | awk '/ercap.commander/{print $1; exit}')"
echo "engine.log=$(wc -c < engine.log) bytes"
echo "bettercap.log=$(wc -c < bettercap.log) bytes"
