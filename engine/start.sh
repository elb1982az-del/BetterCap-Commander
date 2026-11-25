#!/bin/sh
set -e
cd /opt/BetterCap-Commander
if [ -f engine.pid ]; then
  PID=$(cat engine.pid 2>/dev/null || true)
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    echo "engine already running: $PID"
    exit 0
  fi
fi
: > engine.log
: > bettercap.log
nohup python3 bettercap_engine.py >> engine.log 2>&1 &
echo $! > engine.pid
sleep 4
echo "engine started: $(cat engine.pid)"
