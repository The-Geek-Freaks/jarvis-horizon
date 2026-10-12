# Install Jarvis Horizon

**[English](INSTALL.md) · [Deutsch](INSTALL.de.md)**

Jarvis Horizon is a client surface for an OpenClaw Gateway you operate. It does not create a hosted account, Gateway, voice-provider account, or home-automation configuration.

## Release state

Jarvis Horizon is preparing `v0.1.0-alpha.1`. A signed Android release candidate has been produced locally, but public publication is still gated on a portable-artifact audit, a clean Linux validation run and a fresh-device launch check. Until those checks are recorded, there is no supported binary download. When the alpha is published, use the exact matching release at https://github.com/The-Geek-Freaks/jarvis-horizon/releases/tag/v0.1.0-alpha.1 — not a copied APK from a chat, mirror or another installation.

Android source preparation is available for review, but it is not a substitute for a verified public release artifact. The Pi installer and its locked runtime payload are in this repository.

## Prerequisites

You need a reachable Gateway you own, accepted pairing or authorization details, and a configured realtime provider for Talk. Android source builds require the matching Android SDK and Node/pnpm toolchain. Pi requires a Pi 4 on DietPi/Debian ARM64, internet access, an existing local user, and sudo. Never reuse another installation's tokens, session keys, OTA values, Bluetooth identities, Home Assistant credentials, recordings, or configuration.

## Checkout and build prerequisites

    git clone https://github.com/The-Geek-Freaks/jarvis-horizon.git
    cd jarvis-horizon
    pwsh -File ./Test-ReleaseStaging.ps1

For source builds use Git, PowerShell, JDK 21, the Android SDK platform 37 and NDK 28.2.13676358. The pinned upstream package requires Node >=24.16.0 <25 or >=26.1.0 and pnpm 12.5.1. Set ANDROID_HOME to your SDK, accept its licenses, and let the Gradle wrapper use the pinned build tools. Use at most half your logical CPU cores for builds on a shared workstation.

The debug package is `org.thegeekfreaks.jarvishorizon.debug`; the release package is `org.thegeekfreaks.jarvishorizon`. The Java/Kotlin class namespace remains `ai.openclaw.app`.

## Android from source

Prepare a fresh destination; the script refuses to overwrite an existing one.

    ./Prepare-AndroidSource.ps1 -Destination C:\src\openclaw-jarvis
    cd C:\src\openclaw-jarvis
    pnpm install --frozen-lockfile
    cd apps/android
    ./gradlew :app:assembleThirdPartyDebug --max-workers=2

The script pins OpenClaw commit 03619565f017820a090f3fbf9b6571b3c8a3d3da, copies the public overlay, verifies the bundled Sherpa AAR, and excludes credentials, device identity, signing keys, OTA state and sessions. These are build-ready instructions, not a verified clean public build result. The public application ID is `org.thegeekfreaks.jarvishorizon`; its Java/Kotlin namespace remains `ai.openclaw.app`. First launch has no Gateway, identity, OTA or home defaults. Connect only to your own Gateway and invoke only capabilities it currently advertises.

## First Android connection

1. After publication, install only `jarvis-horizon-0.1.0-alpha.1-android.apk` from the matching GitHub release, or install a debug APK you produced from the source build. Android may ask to allow installation from your chosen browser or file manager.
2. On your own Gateway host, generate a setup QR/code with `openclaw qr`. Keep that setup information private.
3. In the app, select **Setup Gateway** and scan the QR or choose **Use setup code**. Manual endpoint/token entry is also available.
4. Approve the new device/node in your Gateway and complete the app's permission steps. Voice needs microphone permission; camera and notification features need their respective permissions.
5. Wait for the Gateway and node to connect. Start with push-to-talk, then enable wake-word listening and test with your real microphone and speaker. A connected node alone does not prove that the voice provider is configured.

The default home tab is Jarvis. For agent-controlled views, install the optional [OpenClaw skill](../integrations/openclaw/SKILL.md) into your agent workspace; its helper checks the device's current effective capabilities.

## Raspberry Pi 4

From a public checkout on the Pi, first validate without changing files or packages:

    ./pi/install-pi.sh --dry-run

Install after a clean dry run:

    sudo ./pi/install-pi.sh

The installer provisions Chromium/X11, PipeWire/WirePlumber, Bluetooth, Python and systemd; stages /opt/jarvis-mirror/releases/<VERSION>; updates /opt/jarvis-mirror/current; retains existing /etc/jarvis-mirror/config.json; and creates local identity storage at /var/lib/jarvis-mirror.

## Configure and verify Pi

Start with pi/config.example.json, then edit /etc/jarvis-mirror/config.json. Gateway URL/token, voice session key, physical microphone, Home Assistant values, Bluetooth target and OTA values are operator-supplied. Bluetooth reconnect is disabled unless bt_mac is set. Public Android OTA is intentionally disabled; Android updates are manual release APK updates. Pi OTA has no private default and requires an operator manifest URL and public key. Pair the generated local identity as your Gateway requires, then start jarvis-mirror.service and jarvis-kiosk.service.

The bundled local wake/VAD payload is installed at `/opt/jarvis-mirror/models/wake`; checksums are in `pi/assets.lock.json`. Installer dry-run and shell syntax validation, 39 focused Python tests, six Node tests, and isolated native sherpa_onnx 1.13.8 construction of bundled WakeSpotter/SpeechGate passed. The native probe used a 40-ms synthetic silence frame and is not target-device microphone acceptance.

## Optional integrations and Hermes

Home cards, Home Assistant facts, room/media routing, announcements, camera orchestration, routines, custom jarvis.* commands, and managed OTA are separate integrations, not automatic after install. See [Architecture](ARCHITECTURE.md) and [Compatibility](COMPATIBILITY.md). Hermes is unsupported: no adapter or end-to-end Hermes validation is included.

### Start the configured Pi services

    sudoedit /etc/jarvis-mirror/config.json
    sudo systemctl start jarvis-mirror.service jarvis-kiosk.service
    systemctl status jarvis-mirror.service jarvis-kiosk.service --no-pager
    journalctl -u jarvis-mirror.service -n 50 --no-pager

Use your actual Gateway endpoint and credentials. Approve the new identity at the Gateway when it requests pairing. If the microphone or sound output is wrong, inspect `wpctl status` and select the intended physical input/output; pairing a Bluetooth speaker alone does not select a microphone.

