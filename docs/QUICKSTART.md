# Jarvis Horizon: first success

**[English](QUICKSTART.md) · [Deutsch](QUICKSTART.de.md)**

Start with the browser demo, then move to your own screen when you are ready. Android and Raspberry Pi additionally need an OpenClaw Gateway you operate and a configured realtime provider for Talk.

## Choose your first success

| Goal | What you need | First result |
| --- | --- | --- |
| See Horizon now | A modern browser | Eight interactive sample scenes, in portrait or landscape |
| Put it on Android | API 27+ ARM Android device, your OpenClaw Gateway | A paired Horizon node and push-to-talk |
| Build a room display | Raspberry Pi 4, DietPi/Debian ARM64, display, microphone and speaker | A kiosk paired with your Gateway |

## 1. Explore without a device

Open the **[browser demo](https://the-geek-freaks.github.io/jarvis-horizon/)**.

Pick a scene, then switch between landscape and portrait. The orb, layouts and responsive renderer are the real Horizon frontend; events, music levels and home data are local sample data. The demo has no account, microphone access, live camera stream or Gateway connection.

This is the right starting point if you want to decide whether Horizon belongs on a spare tablet, a wall display or a mirror before installing anything.

<a id="android"></a>

## 2. Android: pair one screen, then test Talk

1. Download the signed APK from the matching [v0.1.0-alpha.1 release](https://github.com/The-Geek-Freaks/jarvis-horizon/releases/tag/v0.1.0-alpha.1). Check its entry in `SHA256SUMS.txt`; do not use copied APKs from chats or mirrors.
2. Install it on an API 27+ Android device with a supported ARM ABI, then open Jarvis Horizon.
3. On the machine that runs **your** Gateway, create setup information with `openclaw qr`. Keep that QR/code private.
4. In Horizon, choose **Setup Gateway**, scan the QR, or choose **Use setup code**. Manual endpoint/token entry is also available.
5. Approve the device/node in your Gateway and grant only the Android permissions you need. Microphone permission is required for Talk.
6. Wait until Gateway and node both show connected. Use **push-to-talk** for the first conversation. Enable wake-word listening only after the microphone and speaker work locally.

If your backend supplies weather data and the device/node advertises `jarvis.ui`, ask your agent to present it after pairing. The optional [OpenClaw skill](../integrations/openclaw/SKILL.md) checks the node's current capability list before requesting a view. Installing the APK alone does not create a weather card.

For full requirements, source builds and manual recovery details, continue with the [Android installation guide](INSTALL.md#first-android-connection).

<a id="raspberry-pi-4"></a>

## 3. Raspberry Pi 4: prepare the room hardware first

Before installation, have these physical pieces ready:

- Raspberry Pi 4 running 64-bit DietPi or Debian, with internet, a local user and `sudo`
- Display and an audio output that you can hear
- A real microphone; a paired Bluetooth speaker alone is not a microphone
- Your own reachable OpenClaw Gateway and its pairing path

Download the version-matched Pi archive from the [release](https://github.com/The-Geek-Freaks/jarvis-horizon/releases/tag/v0.1.0-alpha.1), verify it using `SHA256SUMS.txt`, extract it, then run the installer dry-run before changing anything:

```sh
./pi/install-pi.sh --dry-run
```

If the dry run is clean, install:

```sh
sudo ./pi/install-pi.sh
```

Then configure `/etc/jarvis-mirror/config.json` from `pi/config.example.json`, pair the local identity as your Gateway requests, and start `jarvis-mirror.service` plus `jarvis-kiosk.service`. Use the [Pi installation guide](INSTALL.md#raspberry-pi-4) for the complete configuration and verification sequence.

## If the first success does not happen

| What you see | Check first | Next place to look |
| --- | --- | --- |
| The demo works, but an Android/Pi screen cannot pair | The correct Gateway QR/setup code, reachable endpoint and the pending device approval | [Android connection](INSTALL.md#first-android-connection) or Pi configuration in [Install](INSTALL.md#configure-and-verify-pi) |
| The screen is paired, but Talk does not answer | Gateway/provider configuration and microphone permission; test push-to-talk before wake word | [Compatibility: Realtime Talk](COMPATIBILITY.md) |
| Wake word is unreliable | Microphone choice, distance, room noise and speaker feedback | Keep push-to-talk as the fallback and report a reproducible result |
| A card, camera, music or home action is missing | The backend integration and current advertised node capabilities | [OpenClaw skill](../integrations/openclaw/SKILL.md); these integrations are optional |
| Pi has picture but no sound or no wake detection | The selected physical PipeWire input/output, not just Bluetooth pairing | [Pi configuration](INSTALL.md#configure-and-verify-pi) |

When reporting a problem, include device, OS/API, ABI, release, Gateway version, provider and exact steps. Do not include tokens, setup codes, room recordings, camera footage or household configuration.

## Where to go next

- [Compatibility](COMPATIBILITY.md) — evidence boundary and supported surfaces
- [FAQ](FAQ.md) — common questions before connecting a device
- [Show your setup](SHOWCASE.md) — help the next person reuse their hardware
- [Share kit](SHARE-KIT.md) — ready-to-edit copy and real project media
