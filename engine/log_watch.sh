#!/system/bin/sh
# Keep BetterCap Commander logs under 200 KB each.
while true; do
  sleep 60
  for f in /opt/BetterCap-Commander/engine.log /opt/BetterCap-Commander/bettercap.log; do
    [ -f "$f" ] || continue
    sz=$(wc -c < "$f" 2>/dev/null || echo 0)
    if [ "$sz" -gt 200000 ]; then
      : > "$f"
    fi
  done
done
