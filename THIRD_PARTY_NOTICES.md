# Third-party notices

Jarvis Horizon is an Android overlay for OpenClaw. LICENSE retains the upstream MIT terms and copyright and adds attribution for this project's original changes. Retained dependency notices are in THIRD_PARTY_NOTICES/.

Jarvis Horizon modifications, original concept artwork and original neutral acknowledgement tones are provided under the MIT license in LICENSE, copyright The-Geek-Freaks contributors. The tones are generated sine-wave chimes and contain no recorded or cloned voice. Third-party components retain their own licenses below.

The custom Sherpa-ONNX clock1 AAR is a derivative of Sherpa-ONNX v1.13.8 under Apache-2.0. Its source commit, patch, license, and SHA-256 are in third_party/sherpa-clock1/.
## Bundled speech and visual assets

- Zipformer KWS: the publisher declares Apache-2.0. See [primary-source provenance](THIRD_PARTY_NOTICES/kws-model-provenance.json) and the retained Android notices.
- Silero VAD: MIT; the model is separate from the Sherpa runtime.
- Vosk small English model: Apache-2.0 according to the upstream model catalogue.
- Space Grotesk and JetBrains Mono: SIL Open Font License 1.1; license files are retained beside the Pi fonts and in Android notices.
- The repository hero is original generated concept artwork. It contains no private device capture.

Exact binary hashes are recorded in [ASSET_LOCK.json](ASSET_LOCK.json) and [pi/assets.lock.json](pi/assets.lock.json). Component licenses govern their respective files; the project license does not replace them.
