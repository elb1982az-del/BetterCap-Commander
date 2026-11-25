#!/bin/sh
if [ -f /opt/BetterCap-Commander/engine.pid ]; then
  PID=$(cat /opt/BetterCap-Commander/engine.pid 2>/dev/null || true)
  if [ -n "$PID" ]; then
    kill "$PID" 2>/dev/null || true
    sleep 2
    kill -9 "$PID" 2>/dev/null || true
  fi
  rm -f /opt/BetterCap-Commander/engine.pid
fi
pkill -f 'bettercap.*engine.cap' 2>/dev/null || true
echo stopped
