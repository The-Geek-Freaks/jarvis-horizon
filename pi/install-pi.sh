#!/usr/bin/env bash
# Install Jarvis Horizon from a checked-out public release on DietPi/Debian.
# It never supplies a gateway, pairing token, Home Assistant credential, OTA
# feed, Bluetooth target, or persistent device identity.
set -Eeuo pipefail

readonly ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly APP_ROOT=/opt/jarvis-mirror
readonly ETC_ROOT=/etc/jarvis-mirror
readonly DATA_ROOT=/var/lib/jarvis-mirror
USER_NAME="${JARVIS_USER:-${SUDO_USER:-dietpi}}"
readonly RELEASE="$(tr -d '[:space:]' < "$ROOT/app/VERSION")"
PYTHON_BIN="${PYTHON_BIN:-python3}"

usage() { printf '%s\n' 'usage: sudo ./install-pi.sh [--dry-run] [--user USER]'; }
dry_run=false
while (($#)); do
  case "$1" in
    --dry-run) dry_run=true ;;
    --user) shift; [[ ${1:-} ]] || { usage >&2; exit 2; }; USER_NAME="$1" ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
  shift
done

[[ -n "$RELEASE" && "$RELEASE" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$ ]] || { echo 'invalid app VERSION' >&2; exit 1; }
for required in "$ROOT/app/server.py" "$ROOT/app/gateway.py" "$ROOT/app/voice.py" "$ROOT/app/horizon/index.html" "$ROOT/config.example.json"; do
  [[ -f "$required" ]] || { echo "missing required file: $required" >&2; exit 1; }
done
"$PYTHON_BIN" - "$ROOT" <<'PY'
import hashlib, json, re, sys
from pathlib import Path

root = Path(sys.argv[1]).resolve()
lock = json.loads((root / "assets.lock.json").read_text(encoding="utf-8"))
sha256 = re.compile(r"^[0-9a-f]{64}$")

def verify(relative, expected):
    if not isinstance(relative, str) or not isinstance(expected, str) or not sha256.fullmatch(expected):
        raise SystemExit("invalid assets.lock.json entry")
    path = (root / relative).resolve()
    if root not in path.parents or not path.is_file():
        raise SystemExit(f"locked asset is absent or outside payload: {relative}")
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise SystemExit(f"locked asset checksum mismatch: {relative}")

for relative, metadata in lock.get("bundled", {}).items():
    verify(relative, metadata.get("sha256") if isinstance(metadata, dict) else None)
wake = lock.get("runtime", {}).get("wake_models", {})
for name, expected in wake.get("files", {}).items() if isinstance(wake, dict) else ():
    verify(f"models/wake/{name}", expected)
PY
if $dry_run; then
  "$PYTHON_BIN" - "$ROOT/config.example.json" <<'PY'
import json, pathlib, sys
for item in sys.argv[1:]: json.loads(pathlib.Path(item).read_text(encoding='utf-8'))
PY
  printf 'Validated public Pi payload and locked assets for version %s. No files or packages changed.\n' "$RELEASE"
  exit 0
fi

[[ $(id -u) -eq 0 ]] || { echo 'run with sudo/root' >&2; exit 1; }
id "$USER_NAME" >/dev/null 2>&1 || { echo "user does not exist: $USER_NAME" >&2; exit 1; }
uid="$(id -u "$USER_NAME")"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends \
  python3 python3-aiohttp python3-websockets python3-numpy python3-cryptography python3-venv \
  pipewire pipewire-pulse wireplumber libspa-0.2-bluetooth bluez pi-bluetooth dbus-user-session alsa-utils pulseaudio-utils \
  xserver-xorg xserver-xorg-legacy x11-xserver-utils xinit x11-xkb-utils unclutter openbox fonts-noto-color-emoji curl ca-certificates
if ! command -v chromium >/dev/null 2>&1 && ! command -v chromium-browser >/dev/null 2>&1; then
  if command -v dietpi-software >/dev/null 2>&1; then dietpi-software install 113; else apt-get install -y --no-install-recommends chromium; fi
fi

install -d -m 0755 "$APP_ROOT/bin" "$APP_ROOT/releases" "$ETC_ROOT" "$DATA_ROOT" "$APP_ROOT/models"
chown "$USER_NAME:$USER_NAME" "$DATA_ROOT" "$APP_ROOT/models"
install -d -m 0755 "$APP_ROOT/releases/$RELEASE"
cp -a "$ROOT/app/." "$APP_ROOT/releases/$RELEASE/"
cp -a "$ROOT/models/wake" "$APP_ROOT/models/"
chown -R "$USER_NAME:$USER_NAME" "$APP_ROOT/releases/$RELEASE"
chown -R "$USER_NAME:$USER_NAME" "$APP_ROOT/models/wake"
ln -sfn "$APP_ROOT/releases/$RELEASE" "$APP_ROOT/.current.new"
mv -Tf "$APP_ROOT/.current.new" "$APP_ROOT/current"
for tool in basic-health.sh bt-reconnect.sh kiosk-session.sh staged-app-updater.py; do install -m 0755 "$ROOT/provision/bin/$tool" "$APP_ROOT/bin/$tool"; done
for unit in "$ROOT"/provision/systemd/*.service "$ROOT"/provision/systemd/*.timer; do install -m 0644 "$unit" "/etc/systemd/system/$(basename "$unit")"; done
# The source units use DietPi's default account. Public installation supports
# another existing local kiosk user without shipping a machine-specific unit.
sed -i "s/dietpi/$USER_NAME/g; s/1000/$uid/g" "$APP_ROOT/bin/bt-reconnect.sh" "$APP_ROOT/bin/kiosk-session.sh" /etc/systemd/system/jarvis-*.service
install -d -m 0755 /etc/wireplumber/wireplumber.conf.d /etc/chromium/policies/managed /etc/X11
install -m 0644 "$ROOT/provision/51-jarvis-a2dp.conf" /etc/wireplumber/wireplumber.conf.d/51-jarvis-a2dp.conf
install -m 0644 "$ROOT/provision/chromium-policies/jarvis.json" /etc/chromium/policies/managed/jarvis.json
install -m 0644 "$ROOT/provision/Xwrapper.config" /etc/X11/Xwrapper.config
if [[ ! -e "$ETC_ROOT/config.json" ]]; then install -m 0600 -o "$USER_NAME" -g "$USER_NAME" "$ROOT/config.example.json" "$ETC_ROOT/config.json"; fi
if [[ ! -e "$ETC_ROOT/ota.pub" ]]; then install -m 0644 "$ROOT/provision/ota.pub.example" "$ETC_ROOT/ota.pub"; fi
"$PYTHON_BIN" -m venv --system-site-packages "$APP_ROOT/venv"
"$APP_ROOT/venv/bin/pip" install --no-cache-dir -r "$ROOT/requirements.txt"
usermod -aG audio,video,render,bluetooth "$USER_NAME"
loginctl enable-linger "$USER_NAME" || true
systemctl daemon-reload
systemctl enable jarvis-mirror.service jarvis-kiosk.service jarvis-basic-health.service jarvis-ota.timer
if "$PYTHON_BIN" - "$ETC_ROOT/config.json" <<'PY'
import json, sys
c=json.load(open(sys.argv[1], encoding='utf-8'))
raise SystemExit(not bool(str(c.get('bt_mac') or '').strip()))
PY
then systemctl enable jarvis-bt-reconnect.timer; else systemctl disable --now jarvis-bt-reconnect.timer jarvis-bt-reconnect.service || true; fi
printf '%s\n' "Installed $RELEASE. Configure $ETC_ROOT/config.json, pair the generated device identity at $DATA_ROOT with your gateway, then start jarvis-mirror.service and jarvis-kiosk.service. The verified local wake/VAD model is installed at $APP_ROOT/models/wake."
