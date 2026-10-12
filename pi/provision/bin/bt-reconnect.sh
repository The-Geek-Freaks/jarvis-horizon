#!/bin/bash
set -Eeuo pipefail
readonly CONFIG=/etc/jarvis-mirror/config.json

read_config() {
  python3 -c 'import json; c=json.load(open("/etc/jarvis-mirror/config.json")); print(c.get("bt_mac", "")); print(c.get("bt_name", "")); print(c.get("bt_name_prefix", ""))'
}
mapfile -t configured < <(read_config)
mac="${configured[0]:-}"
name="${configured[1]:-}"
name_prefix="${configured[2]:-}"

valid_mac() { [[ "$1" =~ ^([[:xdigit:]]{2}:){5}[[:xdigit:]]{2}$ ]]; }

# A successful bluetoothctl command only means that the CLI accepted it.  Read
# BlueZ's Device1 properties before treating a device as paired or connected.
bluez_device_state() {
  local target="$1" token path property value info
  token="${target^^}"
  token="${token//:/_}"
  if command -v busctl >/dev/null 2>&1; then
    path="$(busctl --system --no-pager tree org.bluez 2>/dev/null | awk -v suffix="/dev_${token}" '$NF ~ (suffix "$") { print $NF; exit }')"
    if [ -n "$path" ]; then
      local -a values=()
      for property in Paired Trusted Connected; do
        value="$(busctl --system get-property org.bluez "$path" org.bluez.Device1 "$property" 2>/dev/null | awk '$1 == "b" { print $2 }')"
        [[ "$value" == true || "$value" == false ]] || return 1
        values+=("$value")
      done
      printf '%s %s %s\n' "${values[@]}"
      return 0
    fi
  fi

  # Some minimal images do not ship busctl.  bluetoothctl info reads the same
  # Device1 state, but its prose must be kept in the C locale for stable parsing.
  info="$(LC_ALL=C bluetoothctl info "$target" 2>/dev/null || true)"
  for property in Paired Trusted Connected; do
    value="$(awk -F ': ' -v property="$property" '$1 ~ "^[[:space:]]*" property "$" { print $2; exit }' <<<"$info")"
    case "$value" in
      yes) printf '%s ' true ;;
      no) printf '%s ' false ;;
      *) return 1 ;;
    esac
  done
  printf '\n'
}

device_is_paired_and_trusted() {
  local paired trusted connected
  read -r paired trusted connected < <(bluez_device_state "$1" || true)
  [[ "$paired" == true && "$trusted" == true ]]
}

device_is_ready() {
  local paired trusted connected
  read -r paired trusted connected < <(bluez_device_state "$1" || true)
  [[ "$paired" == true && "$trusted" == true && "$connected" == true ]]
}

rescan_exact_mac() {
  local target="$1"
  bluetoothctl --timeout 15 scan on >/dev/null || true
  bluetoothctl devices | awk -v target="$target" '$1 == "Device" && toupper($2) == toupper(target) { found=1 } END { exit !found }'
}

pair_and_trust() {
  local target="$1" agent_pid agent_input
  # Keep a registered agent running while the separate pair operation is in
  # flight.  A one-shot `bluetoothctl pair` unregisters its own agent too soon
  # for devices that need an authorization callback.
  coproc BT_AGENT { bluetoothctl >/dev/null 2>&1; }
  agent_pid="$BT_AGENT_PID"
  agent_input="${BT_AGENT[1]}"
  printf 'agent NoInputNoOutput\ndefault-agent\npairable on\n' >&"$agent_input"
  sleep 1
  if ! bluetoothctl --timeout 60 pair "$target" >/dev/null; then
    exec {agent_input}>&-
    kill "$agent_pid" 2>/dev/null || true
    wait "$agent_pid" 2>/dev/null || true
    return 1
  fi
  if ! bluetoothctl --timeout 20 trust "$target" >/dev/null; then
    exec {agent_input}>&-
    kill "$agent_pid" 2>/dev/null || true
    wait "$agent_pid" 2>/dev/null || true
    return 1
  fi
  exec {agent_input}>&-
  kill "$agent_pid" 2>/dev/null || true
  wait "$agent_pid" 2>/dev/null || true
}

systemctl is-active --quiet bluetooth || exit 1
if [ "$(LC_ALL=C bluetoothctl show | awk '$1 == "Powered:" { print $2; exit }')" != yes ]; then
  bluetoothctl --timeout 20 power on >/dev/null || true
fi

persist_mac=false
if ! valid_mac "$mac"; then
  [ -n "$name$name_prefix" ] || { echo 'Bluetooth output needs bt_mac, bt_name, or bt_name_prefix.' >&2; exit 1; }
  # Only the exact name and configured literal prefix are eligible. The union
  # must identify exactly one MAC; there is no fuzzy or nearest-device fallback.
  bluetoothctl --timeout 15 scan on >/dev/null || true
  mapfile -t matches < <(bluetoothctl devices | python3 -c '
import sys
wanted, prefix = sys.argv[1:]
matches = set()
for line in sys.stdin:
    parts = line.rstrip("\n").split(" ", 2)
    if len(parts) == 3 and parts[0] == "Device":
        advertised = parts[2]
        if (wanted and advertised == wanted) or (prefix and advertised.startswith(prefix)):
            matches.add(parts[1])
for mac in sorted(matches):
    print(mac)
' "$name" "$name_prefix")
  if [ "${#matches[@]}" -ne 1 ]; then
    echo "Configured Echo Studio is not uniquely discoverable (${#matches[@]} MAC matches); will retry." >&2
    exit 1
  fi
  mac="${matches[0]}"
  valid_mac "$mac" || { echo 'Bluetooth discovery returned an invalid MAC.' >&2; exit 1; }
  persist_mac=true
fi

# A configured MAC can outlive BlueZ's pairing database.  Rescan and pair it
# again instead of assuming that the config is evidence of a usable device.
if ! device_is_paired_and_trusted "$mac"; then
  rescan_exact_mac "$mac" || { echo "Configured Bluetooth device $mac is not discoverable; will retry." >&2; exit 1; }
  pair_and_trust "$mac" || { echo "Pairing $mac failed; make it discoverable, then retry." >&2; exit 1; }
  device_is_paired_and_trusted "$mac" || { echo "BlueZ did not retain Paired and Trusted state for $mac; will retry." >&2; exit 1; }
fi

if ! device_is_ready "$mac"; then
  bluetoothctl --timeout 20 connect "$mac" >/dev/null || true
fi
device_is_ready "$mac" || { echo "BlueZ Device1 is not Paired, Trusted, and Connected for $mac; will retry." >&2; exit 1; }

if [ "$persist_mac" = true ]; then
  python3 - "$mac" "$CONFIG" <<'PY'
import json, os, sys, tempfile
mac, path = sys.argv[1:]
with open(path, encoding="utf-8") as source:
    config = json.load(source)
ownership = os.stat(path)
config["bt_mac"] = mac
fd, temporary = tempfile.mkstemp(prefix=".config.", dir=os.path.dirname(path))
try:
    with os.fdopen(fd, "w", encoding="utf-8") as target:
        json.dump(config, target, indent=2, sort_keys=True)
        target.write("\n")
        target.flush()
        os.fsync(target.fileno())
    os.chmod(temporary, 0o600)
    os.chown(temporary, ownership.st_uid, ownership.st_gid)
    os.replace(temporary, path)
finally:
    try: os.unlink(temporary)
    except FileNotFoundError: pass
PY
fi

# Select exactly the A2DP sink encoding this paired MAC, never the first bluez
# sink. USB microphone sources are not changed.
uid="$(id -u dietpi)"
runtime="/run/user/$uid"
find_a2dp_sink() {
  runuser -u dietpi -- env XDG_RUNTIME_DIR="$runtime" pactl -f json list sinks 2>/dev/null | MAC="$mac" python3 -c '
import json, os, sys
token = os.environ["MAC"].replace(":", "_").lower()
try:
    for sink in json.load(sys.stdin):
        candidate = sink.get("name", "").lower()
        if candidate.startswith("bluez_output." + token + "."):
            print(sink["name"])
            break
except Exception:
    pass
'
}
wait_for_a2dp_sink() {
  local attempt
  sink=""
  for attempt in $(seq 1 20); do
    sink="$(find_a2dp_sink)"
    [ -n "$sink" ] && return 0
    sleep 1
  done
  return 1
}

if ! wait_for_a2dp_sink; then
  # BlueZ can report Connected while WirePlumber has retained a stale endpoint
  # and therefore never creates its A2DP card. Reconnect only the configured
  # MAC once; pairing, trust, other speakers, and global audio stay untouched.
  echo "Configured Bluetooth device $mac is connected without an A2DP sink; reconnecting it once." >&2
  bluetoothctl --timeout 20 disconnect "$mac" >/dev/null || true
  bluetoothctl --timeout 20 connect "$mac" >/dev/null || true
  wait_for_a2dp_sink || { echo "No PipeWire A2DP sink for configured MAC $mac after exact reconnect." >&2; exit 1; }
fi
current_sink="$(runuser -u dietpi -- env XDG_RUNTIME_DIR="$runtime" pactl get-default-sink 2>/dev/null || true)"
if [ "$current_sink" != "$sink" ]; then
  runuser -u dietpi -- env XDG_RUNTIME_DIR="$runtime" pactl set-default-sink "$sink"
  # Existing Chromium streams do not follow a default-sink change.  Move only
  # those playback streams; sources (including the USB microphone) are untouched.
  while IFS= read -r input; do
    [ -n "$input" ] && runuser -u dietpi -- env XDG_RUNTIME_DIR="$runtime" pactl move-sink-input "$input" "$sink" || true
  done < <(runuser -u dietpi -- env XDG_RUNTIME_DIR="$runtime" pactl -f json list sink-inputs 2>/dev/null | python3 -c '
import json, sys
try:
    for item in json.load(sys.stdin):
        props = item.get("properties", {})
        values = (props.get("application.process.binary", ""), props.get("application.name", ""))
        if any("chromium" in str(value).lower() for value in values):
            print(item["index"])
except Exception:
    pass
')
fi
