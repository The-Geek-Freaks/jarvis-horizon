<h1 align="center">Jarvis Horizon</h1>

<p align="center"><strong>Give your assistant a place in the room.</strong><br>A voice-first OpenClaw interface for Android and Raspberry Pi.</p>

<p align="center"><a href="https://the-geek-freaks.github.io/jarvis-horizon/#experience"><img src="docs/assets/try-demo.svg" alt="Try the interactive demo" width="480"></a><br><sub>No installation. No login. No microphone access.</sub></p>

<p align="center"><a href="https://the-geek-freaks.github.io/jarvis-horizon/#experience"><img src="docs/assets/horizon-motion.gif" alt="Open the interactive Horizon demo — animated blue and gold orbital interface" width="100%"></a></p>

<p align="center"><strong><a href="https://the-geek-freaks.github.io/jarvis-horizon/#watch">Watch the 23-second overview</a> · <a href="docs/QUICKSTART.md">Get it running on your screen</a></strong><br><sub>Actual renderer · sample data · eight scenes · 30-second guided tour</sub></p>

<p align="center"><strong>English</strong> · <a href="README.de.md">Deutsch</a> · <a href="https://github.com/The-Geek-Freaks/jarvis-horizon/releases/tag/v0.1.0-alpha.1">Download the public alpha</a></p>

## See what your assistant is doing

- **Speak naturally.** Local wake-word detection opens your configured voice session; the orb follows conversation states.
- **Follow the work.** Received tool and delegated-task events appear as readable activity, without exposing raw commands or private output.
- **Give spare hardware a purpose.** Portrait and landscape layouts, context views and a moving ambient clock for a tablet, room display or Pi kiosk.

Your installation uses **your own OpenClaw Gateway and realtime provider**. Camera, weather, music and home actions depend on the integrations you configure.

## The Horizon language, across surfaces

**Click any screen to open that scene in the demo.** Switch views, change orientation or start the guided tour.

<table>
  <tr>
    <td width="50%" valign="top"><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=home&amp;format=landscape#experience"><img src="docs/assets/showcase-home.png" alt="Explore the Horizon home overview" width="100%"></a><br><strong><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=home&amp;format=landscape#experience">Explore the home view</a></strong><br>Time, appointments and the orbital center.</td>
    <td width="50%" valign="top"><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=weather&amp;format=landscape#experience"><img src="docs/assets/showcase-weather.png" alt="Explore the Horizon weather view" width="100%"></a><br><strong><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=weather&amp;format=landscape#experience">Open the weather view</a></strong><br>A forecast and chart that use the screen.</td>
  </tr>
  <tr>
    <td valign="top"><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=activity&amp;format=landscape#experience"><img src="docs/assets/showcase-activity.png" alt="Watch sample agent activity arrive in Horizon" width="100%"></a><br><strong><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=activity&amp;format=landscape#experience">Watch the work happen</a></strong><br>Sample tool events appear as the task progresses.</td>
    <td valign="top"><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=music&amp;format=landscape#experience"><img src="docs/assets/showcase-music.png" alt="Explore Horizon music context" width="100%"></a><br><strong><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=music&amp;format=landscape#experience">Explore music context</a></strong><br>Track information with animated sample levels.</td>
  </tr>
  <tr>
    <td valign="top"><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=agenda&amp;format=landscape#experience"><img src="docs/assets/showcase-agenda.png" alt="Explore the Horizon agenda view" width="100%"></a><br><strong><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=agenda&amp;format=landscape#experience">See what comes next</a></strong><br>A sample day, readable at a glance.</td>
    <td valign="top"><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=house&amp;format=landscape#experience"><img src="docs/assets/showcase-home-control.png" alt="Explore Horizon room states" width="100%"></a><br><strong><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=house&amp;format=landscape#experience">Look around the home</a></strong><br>Room and device states in one view.</td>
  </tr>
  <tr>
    <td valign="top"><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=ambient&amp;format=landscape#experience"><img src="docs/assets/showcase-ambient.png" alt="Explore the Horizon galactic ambient screen" width="100%"></a><br><strong><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=ambient&amp;format=landscape#experience">Stay for the quiet moments</a></strong><br>A moving clock and a galactic ambient view.</td>
    <td valign="top"><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=home&amp;format=portrait#experience"><img src="docs/assets/showcase-portrait.png" alt="Try Horizon in portrait orientation" width="160"></a><br><strong><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=home&amp;format=portrait#experience">Try a portrait display</a></strong><br>Made for narrow wall displays and tablets.</td>
  </tr>
</table>

*All screenshots are actual frontend renders with synthetic sample data. They illustrate layouts and states, not a connected Gateway, a live home, or a device-performance result.*

**[Explore all eight scenes](https://the-geek-freaks.github.io/jarvis-horizon/#experience)** · [Share a scene or the video](docs/SHARE-KIT.md)

## Your assistant remains the authority

<p align="center"><a href="docs/assets/how-it-works.svg"><img src="docs/assets/how-it-works.png" alt="Architecture diagram: Android and Raspberry Pi use local wake detection, then an operator-owned OpenClaw Gateway connects configured voice, agents and optional home and media capabilities" width="100%"></a></p>

Wake-word detection is local to the surface. After activation, conversation audio follows the Gateway and voice-provider path that **you** configure. Local wake-word detection does not make cloud conversation processing local.

Camera aliases, calendars, media routes, announcements and home actions are optional integrations. A paired Bluetooth speaker is not automatically a microphone, and a requested camera is not automatically a confirmed live stream.

## Release status

`v0.1.0-alpha.1` is the first public alpha. Install the exact Android APK, checksum file and signing-certificate fingerprint from the matching [GitHub release](https://github.com/The-Geek-Freaks/jarvis-horizon/releases/tag/v0.1.0-alpha.1); do not use a copied APK from a chat, mirror or another installation. Android OTA is intentionally not configured in the public distribution. See [Validation](docs/VALIDATION.md) for the exact evidence boundary and the completed Linux release gate.

The implemented backend is **OpenClaw**. A **Hermes adapter is not included**; it needs a dedicated protocol adapter and end-to-end acceptance coverage before it can be described as supported.

## Choose a surface

| Surface | Intended role | Known boundary |
| --- | --- | --- |
| Android phone or tablet | Wall display or portable terminal | API 27+ with a supported ARM ABI; other devices need their own acceptance run |
| Archos Hello 7 | Portrait or landscape display | Private Android 8.1 / ARM64 deployment evidence only |
| Echo Show 5, second generation | Compact home display | Private evidence is from an already-modified Android 11 device; stock Echo firmware is not an APK target |
| Raspberry Pi 4 | Display or mirror kiosk | DietPi/Debian ARM64 installer and locked runtime payload; physical audio, display and pairing remain installation-specific |

## Start from a clean, owned setup

**[Android quickstart](docs/QUICKSTART.md#android)** · **[Raspberry Pi quickstart](docs/QUICKSTART.md#raspberry-pi-4)**

Start with one paired screen and a push-to-talk conversation. You need an OpenClaw Gateway you operate and a configured realtime provider for Talk. The [full installation guide](docs/INSTALL.md) covers requirements, configuration and source builds.

[Compatibility](docs/COMPATIBILITY.md) · [Architecture](docs/ARCHITECTURE.md) · [Validation](docs/VALIDATION.md) · [FAQ](docs/FAQ.md)

The default Android tab is Jarvis. For agent-driven views, install the optional [OpenClaw skill](integrations/openclaw/SKILL.md) in your own agent workspace; it checks the device's current effective capabilities before asking the screen to act.

## Help make the first alpha useful

Put Horizon on a screen, then show what you built. [Share your setup](docs/SHOWCASE.md), [report a tested device](https://github.com/The-Geek-Freaks/jarvis-horizon/issues/new?template=supported-device.yml), or pick a [good first issue](https://github.com/The-Geek-Freaks/jarvis-horizon/labels/good%20first%20issue). If it belongs in your next project, star the repository to find it again.

Reports on wake recovery, audio routing, camera decoding and older hardware are valuable when they are reproducible. Include the device, OS/API level, ABI, release, Gateway version and exact steps. Remove personal details from logs; do not upload room recordings, camera footage, tokens or household configuration.

Read [CONTRIBUTING.md](CONTRIBUTING.md). Visible states must come from real runtime events, and performance improvements need measurements.

## Credits

Built on [OpenClaw](https://github.com/openclaw/openclaw), with speech components from [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) and [Silero VAD](https://github.com/snakers4/silero-vad). The Pi runtime uses the Debian/DietPi ecosystem, Chromium and the Linux audio stack.

An independent community project by **The Geek Freaks**. Upstream copyrights and licenses are retained. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
