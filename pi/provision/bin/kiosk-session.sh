#!/bin/bash
set -Eeuo pipefail
export DISPLAY=:0
export XDG_RUNTIME_DIR="/run/user/$(id -u)"

# xrandr only succeeds after the X server has registered the connector.
for _ in $(seq 1 30); do
  output="$(xrandr --query 2>/dev/null | awk '/ connected/{print $1; exit}')" || true
  [ -n "${output:-}" ] && break
  sleep 1
done
if [ -n "${output:-}" ]; then
  xrandr --output "$output" --rotate "$(python3 -c 'import json; print(json.load(open("/etc/jarvis-mirror/config.json")).get("display",{}).get("rotation","right"))')" || true
fi
scale="$(xrandr --current 2>/dev/null | python3 -c '
import json, re, sys
try:
    configured = json.load(open("/etc/jarvis-mirror/config.json")).get("display", {}).get("scale")
    if isinstance(configured, (int, float)) and 1 <= configured <= 3:
        print(f"{configured:g}")
    else:
        match = re.search(r"current\s+(\d+)\s+x\s+\d+", sys.stdin.read())
        width = int(match.group(1)) if match else 540
        print(f"{max(1, min(3, width / 540)):g}")
except Exception:
    print("1")
')"
xset s off -dpms s noblank || true
xsetroot -solid black || true
unclutter -idle 0.1 -root &
# Chromium's fullscreen request needs a window manager under bare startx.
openbox --sm-disable &

# Do not let Chromium render a connection-error page during the normal backend
# start race. It will still start after the bounded wait for diagnosability.
python3 - <<'PY'
from time import sleep
from urllib.request import urlopen
for _ in range(30):
    try:
        with urlopen('http://127.0.0.1:8765/healthz', timeout=1) as response:
            if 200 <= response.status < 300:
                break
    except Exception:
        sleep(1)
PY

# A USB webcam/microphone is usable by the web UI only if exactly one physical
# USB capture source exists. Do not choose arbitrarily when several are present.
usb_sources="$(pactl -f json list sources 2>/dev/null | python3 -c '
import json, sys
try:
    for source in json.load(sys.stdin):
        name = source.get("name", "")
        props = source.get("properties", {})
        if name.startswith("alsa_input.usb-") or props.get("device.bus") == "usb":
            print(name)
except Exception:
    pass
')"
if [ "$(printf '%s\n' "$usb_sources" | sed '/^$/d' | wc -l)" -eq 1 ]; then
  pactl set-default-source "$usb_sources" || true
fi

chromium_bin="$(command -v chromium || command -v chromium-browser)"
mkdir -p "$HOME/.local/share/jarvis-mirror/chromium"
exec "$chromium_bin" \
  --user-data-dir="$HOME/.local/share/jarvis-mirror/chromium" \
  --kiosk --app='http://127.0.0.1:8765/?layout=adaptive' --no-first-run --no-default-browser-check \
  --disable-session-crashed-bubble --disable-infobars --noerrdialogs --password-store=basic \
  --start-fullscreen --disable-pinch --overscroll-history-navigation=0 --autoplay-policy=no-user-gesture-required \
  --force-device-scale-factor="$scale"
