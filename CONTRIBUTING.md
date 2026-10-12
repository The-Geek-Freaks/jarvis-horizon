# Contributing

Help make the interface easier to run and more reliable on real hardware.

New here? Start with the [quickstart](docs/QUICKSTART.md), then choose a [good first issue](https://github.com/The-Geek-Freaks/jarvis-horizon/labels/good%20first%20issue) or a [help wanted task](https://github.com/The-Geek-Freaks/jarvis-horizon/labels/help%20wanted). Each task includes a bounded scope and acceptance checks. Comment before starting so contributors do not duplicate work.

Use synthetic or scrubbed inputs. Never commit credentials, paired-device identities, signing keys, room recordings, camera footage, personal configuration or private OTA metadata.

1. Read the installation and compatibility guides.
2. Open an issue before a substantial protocol or architecture change.
3. Keep Android, Pi and backend changes scoped. Preserve the existing design system.
4. Run the affected checks. Test hardware-dependent behavior on the actual target when possible.
5. Describe what changed, why, how it was checked, and what remains unverified.

A bug report should include the release or commit, device model, Android API/ABI or Pi OS, Gateway version, and reproducible steps. Scrub logs before attaching them.

Every redistributed dependency, model, font and asset needs provenance and its applicable license. Do not replace the pinned native speech library without compatibility validation.

Performance work must preserve voice, tool-event and camera semantics. Lower memory use or a faster microbenchmark alone does not prove smoother end-to-end operation.
