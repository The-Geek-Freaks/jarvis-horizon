#!/bin/bash
set -Eeuo pipefail
python3 - <<'PY'
from urllib.request import urlopen
with urlopen('http://127.0.0.1:8765/healthz', timeout=5) as response:
    assert 200 <= response.status < 300
PY
test -L /opt/jarvis-mirror/current
test -x /opt/jarvis-mirror/current/server.py -o -f /opt/jarvis-mirror/current/server.py
test -n "$(find -H /opt/jarvis-mirror/current -type f -name '*.js' -print -quit)"
