# Release validation

**[English](VALIDATION.md) · [Deutsch](#release-validierung)**

This page separates what has been measured from what is still required before the first public alpha can be called a supported release. It is intentionally narrower than a feature list.

## Candidate target

| Item | Planned public value |
| --- | --- |
| Version | `0.1.0-alpha.1` |
| Android package | `org.thegeekfreaks.jarvishorizon` |
| Minimum / target API | 27 / 36 |
| APK filename | `jarvis-horizon-0.1.0-alpha.1-android.apk` |
| Source baseline | OpenClaw `03619565f017820a090f3fbf9b6571b3c8a3d3da` plus this public overlay |
| Update policy | Manual Android APK updates; no public Android OTA default |

## Evidence recorded before publication

| Check | Result | Boundary |
| --- | --- | --- |
| Signed Android candidate build | Passed locally | The candidate is being rebuilt after a portable-artifact audit finding, so an earlier local APK hash is deliberately not a release checksum. |
| Android signer | Locally verified against the intended release keystore and backup | The public checksum and certificate fingerprint will be published only with the rebuilt artifact. |
| Pi installer | Dry run and shell syntax checks passed | This is not a physical Pi installation, display, microphone or speaker acceptance. |
| Pi runtime tests | 39 focused Python tests and 6 Node tests passed | The isolated native wake/VAD probe used 40 ms of synthetic silence, not a real-room microphone test. |
| Private device evidence | Android deployments exercised on Archos Hello 7 and a modified Echo Show 5 | Private-device evidence is not fresh public-install acceptance and is not distributed with the project. |

## Required before the public alpha is published

- [ ] Rebuild the portable APK after the artifact audit correction.
- [ ] Independently audit the rebuilt APK and release archives for private paths, accounts, recordings, credentials and household data.
- [ ] Run the complete Android unit-test suite on a clean Linux runner and retain the result.
- [ ] Install the rebuilt public package alongside the private development package on a fresh device state; verify onboarding starts without a crash, without a preconfigured Gateway and without a microphone or pairing claim.
- [ ] Publish the exact APK, SHA-256 checksum, signing-certificate SHA-256 fingerprint, source archive, Pi archive and release notes together.
- [ ] Verify the published GitHub Release links and archive contents from the public repository view.

## What a public alpha will and will not prove

Passing the release gate proves the published artifact was built and checked within the scope above. It does not prove wake-word reliability in every room, provider quality, camera latency, Bluetooth microphone availability, home-automation semantics, stock Echo compatibility or a Hermes integration.

Only configure the app with a Gateway, provider and integrations you own. The first launch is expected to require pairing and permissions. A connected node is not, by itself, proof that Talk, camera audio, home control or media routing is configured correctly.

---

# Release-Validierung

**[English](#release-validation) · [Deutsch](VALIDATION.md)**

Diese Seite trennt gemessene Tatsachen von den noch notwendigen Prüfungen, bevor das erste öffentliche Alpha als unterstütztes Release gelten kann. Sie ist bewusst enger als eine Featureliste.

## Ziel des Kandidaten

| Punkt | Geplanter öffentlicher Wert |
| --- | --- |
| Version | `0.1.0-alpha.1` |
| Android-Paket | `org.thegeekfreaks.jarvishorizon` |
| Mindest- / Ziel-API | 27 / 36 |
| APK-Dateiname | `jarvis-horizon-0.1.0-alpha.1-android.apk` |
| Quellbasis | OpenClaw `03619565f017820a090f3fbf9b6571b3c8a3d3da` plus dieses öffentliche Overlay |
| Update-Politik | Manuelle Android-APK-Updates; kein öffentliches Android-OTA als Standard |

## Vor der Veröffentlichung erfasste Evidenz

| Prüfung | Ergebnis | Grenze |
| --- | --- | --- |
| Signierter Android-Kandidatenbuild | Lokal bestanden | Der Kandidat wird nach einem Befund im portablen Artefakt neu gebaut; eine frühere lokale APK-Prüfsumme ist deshalb absichtlich keine Release-Prüfsumme. |
| Android-Signatur | Lokal gegen den vorgesehenen Release-Keystore und dessen Backup geprüft | Öffentliche Prüfsumme und Zertifikat-Fingerprint erscheinen erst mit dem neu gebauten Artefakt. |
| Pi-Installer | Dry-Run und Shell-Syntaxprüfung bestanden | Das ersetzt keine physische Pi-Installation, Display-, Mikrofon- oder Lautsprecherabnahme. |
| Pi-Runtime-Tests | 39 fokussierte Python- und 6 Node-Tests bestanden | Der isolierte native Wake-/VAD-Probe nutzte 40 ms künstliches Schweigen, keinen Raum-Mikrofontest. |
| Private Geräte-Evidenz | Android-Deployments auf Archos Hello 7 und einem modifizierten Echo Show 5 erprobt | Das ist keine frische öffentliche Installationsabnahme und wird nicht mit dem Projekt verteilt. |

## Vor dem öffentlichen Alpha noch erforderlich

- [ ] Die portable APK nach der Korrektur des Artefakt-Audits neu bauen.
- [ ] Neu gebaute APK und Release-Archive unabhängig auf private Pfade, Konten, Aufnahmen, Zugangsdaten und Haushaltsdaten prüfen.
- [ ] Die vollständige Android-Unit-Test-Suite auf einem sauberen Linux-Runner ausführen und das Ergebnis aufbewahren.
- [ ] Das neu gebaute öffentliche Paket neben dem privaten Entwicklungspaket auf einem frischen Gerätezustand installieren; prüfen, dass Onboarding ohne Absturz und ohne vorkonfiguriertes Gateway, Mikrofon- oder Pairing-Versprechen startet.
- [ ] Exakte APK, SHA-256-Prüfsumme, SHA-256-Fingerprint des Signaturzertifikats, Quellarchiv, Pi-Archiv und Release Notes gemeinsam veröffentlichen.
- [ ] Links und Archivinhalt des veröffentlichten GitHub Releases aus der öffentlichen Repository-Ansicht prüfen.

## Was ein öffentliches Alpha beweist – und was nicht

Ein bestandener Release-Gate beweist, dass das veröffentlichte Artefakt im oben beschriebenen Umfang gebaut und geprüft wurde. Er beweist keine Wakeword-Zuverlässigkeit in jedem Raum, Provider-Qualität, Kameralatenz, Bluetooth-Mikrofonverfügbarkeit, Semantik der Hausautomation, Stock-Echo-Kompatibilität oder Hermes-Integration.

Konfiguriere die App ausschließlich mit deinem eigenen Gateway, Provider und deinen eigenen Integrationen. Beim ersten Start sind Pairing und Berechtigungen erwartbar. Ein verbundener Node beweist für sich allein nicht, dass Talk, Kameraton, Home-Control oder Medienrouting korrekt eingerichtet sind.
