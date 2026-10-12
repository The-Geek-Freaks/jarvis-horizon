---
name: jarvis-horizon
description: Invoke capability-advertised Jarvis Horizon node commands and run typed, idempotent display/media/schedule routines without Home Assistant or private Gateway patches.
metadata:
  openclaw:
    requires:
      bins: [python3, openclaw]
---

# Jarvis Horizon OpenClaw integration

This portable workspace skill does not install a Gateway plugin or make a stock node advertise Jarvis commands. It uses only a command a connected node already advertises.

## Setup

Copy this directory to an OpenClaw workspace skill directory. Ensure `python3` and the supported `openclaw` CLI are available. No secret, Home Assistant, vendor camera, Alexa, Pi, or Hermes configuration is required by this package.

Before any action, select a paired/connected node from `nodes status`, then obtain its exact command inventory with `nodes describe`:

```sh
python3 jarvis_horizon.py capabilities --node node-demo.invalid
```

An unavailable command is normal non-success. Do not use a source-code string as proof that a node supports it.

## Supported node commands

- `jarvis.display`: `list`, `open`, `fullscreen`, `minimize`, `close`, `status`
- `jarvis.ui`: foreground presentation card or `show: null` to clear it
- `jarvis.media`: `status`, `play`, `pause`, `next`, `previous`, `volume`
- `jarvis.schedule`: `set_timer`, `set_alarm`, `list`, `status`, `cancel`, `stop`, `snooze`, `pause`, `resume`, `extend`

`jarvis.display` and `jarvis.ui` may require foreground. `requested` or `connecting` is not visible camera evidence: query `status`, and require `live` or `snapshot` before claiming an image is shown.

```sh
python3 jarvis_horizon.py invoke --node node-demo.invalid --command jarvis.ui --params '{"t":"ui","id":"weather-demo","show":"weather","data":{},"ttl":9000}'
python3 jarvis_horizon.py invoke --node node-demo.invalid --command jarvis.schedule --params '{"action":"set_timer","durationMs":300000,"name":"Tea"}'
```

The helper prints the authoritative CLI result. It never converts error, queued, accepted, requested, or connecting into success.

## Optional typed routine cache

The routine cache is local SQLite state, not chat memory or a device source of truth. It has no Home Assistant/Alexa dependency.

```sh
python3 jarvis_horizon.py routine remember --db ./routines.sqlite3 --plan-file example-plan.json
python3 jarvis_horizon.py routine invoke --db ./routines.sqlite3 --text 'show weather' --request-id 0f974e1b-24c8-4c04-bda1-c36660f42a1d --node node-demo.invalid
```

Plans use exact normalized aliases only. Steps are typed as `jarvis_display`, `jarvis_ui`, `jarvis_media`, or `jarvis_schedule`. A request ID is reserved before the first action; repeating it returns the receipt and never repeats a side effect. First unavailable/uncertain/non-success stops the chain.

`jarvis.media` controls only the local media session implemented by the node. It does not discover a room, select Spotify Connect, infer Bluetooth routing, or prove audible sound. This package has no camera vendor, announcement, OTA, realtime provider, wake-word, or Hermes adapter.
