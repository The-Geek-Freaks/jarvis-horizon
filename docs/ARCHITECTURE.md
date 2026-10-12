# Architecture

**[English](ARCHITECTURE.md) · [Deutsch](ARCHITECTURE.de.md)**

Jarvis Horizon is a set of client surfaces around an operator-owned OpenClaw Gateway. The public baseline is intentionally smaller than a private home stack.

    Android Horizon ─┐
                     ├─ Operator-owned OpenClaw Gateway ─ configured realtime provider
    Pi backend/kiosk ─┘
    Android/Pi wake paths run locally; Pi Home Assistant views are optional.

## Public boundary

The clients do not include a hosted Gateway, provider account, Home Assistant credentials, OTA feed, paired Bluetooth identity, camera feed, private session, or household configuration.

## Android

Prepare-AndroidSource.ps1 clones and checks out a pinned OpenClaw revision, copies the public overlay and verifies the custom Sherpa AAR. It excludes operational state. The client knows generic Talk methods and can consume agent/tool events, but the connected Gateway must advertise, authorize and accept them. The overlay contains jarvis.display, jarvis.ui, jarvis.schedule, and jarvis.media; they are not stock-Gateway guarantees and depend on the built node, foreground state, permissions and integration.

## Raspberry Pi

The installer stages versioned releases, switches current, retains configuration under /etc/jarvis-mirror, and stores the device identity under /var/lib/jarvis-mirror. It provisions the kiosk, browser, audio, Bluetooth and Python backend. Locked local wake/VAD assets install under /opt/jarvis-mirror/models/wake; Gateway URL/token/session key and microphone remain operator-controlled.

## Optional layers

| Layer | Required input | Not proven automatically |
| --- | --- | --- |
| Talk | Gateway, accepted session flow, provider | Every Gateway/provider combination |
| Home Assistant | URL, token, entities | Generic Gateway dashboard support |
| Camera | Advertised capability, stream and codec | Requested state equals visible video |
| Media | Capability and exact output config | Room inference/Spotify Connect selection |
| Routines | Separate local automation configuration | Gateway-native routine API |
| OTA | Operator manifest, public key, publisher | Managed public updates by default |

## Truthful state

Gateway acceptance, a queued action, and final physical effect differ. A requested/connecting camera is not live; only reported live or snapshot state is evidence. Missing activity events are unknown, not inferred completion. Never commit secrets, OTA/signing material, identities, Bluetooth addresses, camera images, recordings, receipts, entity IDs, or private configuration.
