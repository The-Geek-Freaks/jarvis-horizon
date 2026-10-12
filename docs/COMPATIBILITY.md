# Compatibility

**[English](COMPATIBILITY.md) · [Deutsch](COMPATIBILITY.de.md)**

“Implemented” means source contains a path. “Validated” means the named check ran. Neither predicts behavior on another device, Gateway, provider, or home stack.

| Area | Public baseline | Requires separate validation or integration | Evidence |
| --- | --- | --- | --- |
| Android | API 27+ and supported ARM ABI | Fresh public package/install and broad device coverage | Private Archos Hello 7 Android 8.1 ARM64; private modified Echo Show 5 Android 11 armeabi-v7a |
| Android source | Pinned OpenClaw plus public overlay | Clean public build, portable artifact audit and published signed release artifact | Preparation script pins source and checks custom AAR |
| Android alpha package | Local signed release candidate for `0.1.0-alpha.1` | Fresh-device launch check and public publication | No supported public APK until all release checks are recorded |
| Pi 4 | DietPi/Debian ARM64 installer | Physical mic, display, audio route and pairing | Dry run, shell syntax, 39 Python and 6 Node tests |
| Local wake/VAD | Bundled locked payload | Actual microphone reliability | Isolated native construction plus 40-ms synthetic silence frame |
| Realtime Talk | Client session-flow support | Paired reachable Gateway, accepted methods, configured provider | Client/protocol source only |
| Gateway events | Render received activity events | Provider/tool event detail | Client handles agent/session.tool events |
| Node commands | Current advertised capability | Any particular jarvis.* command | Invoke only current capability list |
| Home/cameras/media | Optional client paths | HA/source/token/entity/codec/permissions | No automatic integration |
| Bluetooth | Explicit paired target configuration | Valid bt_mac and pairing | Reconnect disabled by default |
| OTA | Operator-configurable Pi path | Manifest, public key, publisher pipeline | No private defaults; Android manual updates |
| Hermes | None | Adapter and E2E validation | Not implemented |

## Release rules

- The planned release package is `org.thegeekfreaks.jarvishorizon`, with a minimum API level of 27 and target API level of 36. The implementation namespace remains `ai.openclaw.app`.
- `v0.1.0-alpha.1` is not a supported download until its GitHub release contains the matching APK, checksums and validation record. Android updates are manual in the public distribution.
- Internal deployment evidence is deliberately separate from public-release acceptance.

## Operating rules

- Stock Echo firmware is not an APK target; no firmware conversion is supplied.
- A requested or connecting camera state is not a visible stream; only reported live/snapshot state supports that claim.
- Push-to-talk is the fallback for wake issues.
- A Bluetooth speaker and usable microphone are separate facts.
- Reports should include device, OS/API, ABI, Gateway version, provider, and integration configuration.

The documentation is available in English and German. Horizon currently retains predominantly German interface labels; conversation language also depends on your backend configuration.
