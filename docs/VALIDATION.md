# Release validation

**[English](VALIDATION.md) · [Deutsch](#release-validierung)**

This record separates measured results from the remaining release gate. It is intentionally narrower than a feature list and does not turn private development evidence into a public-support claim.

## Candidate

| Item | Value |
| --- | --- |
| Version | `0.1.0-alpha.1` |
| Android package | `org.thegeekfreaks.jarvishorizon` |
| Minimum / target API | 27 / 36 |
| Candidate APK | `jarvis-horizon-0.1.0-alpha.1-android.apk` |
| Source baseline | OpenClaw `03619565f017820a090f3fbf9b6571b3c8a3d3da` plus this public overlay |
| Android update path | Manual APK installs from a matching GitHub Release; no public Android OTA default |

## Evidence recorded before publication

| Check | Result | Evidence boundary |
| --- | --- | --- |
| Rebuilt native dependency | Passed | The bundled Sherpa AAR was rebuilt from its source with portable debug prefixes, stripped, then recursively scanned. The audit found no private Windows build path in the rebuilt AAR, the Git source archive, or the candidate APK. |
| Signed Android candidate | Passed locally | A `0.1.0-alpha.1` APK was built with the independent public package and a separate release certificate. The APK was signature-verified and its SHA-256 was recorded for the planned release. |
| Fresh public onboarding smoke test | Passed locally | The public package installed beside the private development package on an Archos Hello 7, launched into unpaired onboarding in about 0.9 seconds, and showed no preconfigured Gateway. This was a launch smoke test, not a Talk or microphone acceptance test. |
| Pi installer and focused runtime checks | Passed | Pi installer dry-run and shell syntax checks passed; 39 focused Python tests and 6 Node checks passed. The native wake/VAD probe used synthetic silence, not a room microphone. |
| Private-device exploration | Informative only | Prior work exercised private deployments on older Android hardware. It is not public-install acceptance and is not distributed with this project. |

## Remaining gate before a public alpha

- [ ] Correct and rerun the complete Android unit-test suite on a clean Linux runner. The latest full run executed **3,759 tests with 12 failures**; publication is blocked until those failures are investigated and a clean result is retained.
- [ ] Rebuild and rescan the signed APK if the correction changes Android production code, then update the published checksum and signing-certificate fingerprint from that exact artifact.
- [ ] Create the release with the exact APK, SHA-256 checksum, signing-certificate SHA-256 fingerprint, source archive, Pi archive and release notes together.
- [ ] Verify the public GitHub Release links and downloaded archive contents from the repository view.

The repository is still private and no public release or promotion has been posted while this gate remains open.

## What a public alpha will and will not prove

Passing the release gate will show that the published artifacts were built and checked within the scope above. It will not prove wake-word reliability in every room, provider quality, camera latency or audio support, Bluetooth microphone availability, home-automation semantics, stock Echo compatibility, or a Hermes integration.

Configure the app only with a Gateway, provider and integrations you own. First launch is expected to require pairing and permissions. A connected node alone does not prove that Talk, camera audio, home control or media routing is configured correctly.

---

# Release-Validierung

**[English](#release-validation) · [Deutsch](VALIDATION.md)**

Dieses Protokoll trennt gemessene Ergebnisse vom noch offenen Release-Gate. Es ist bewusst enger als eine Featureliste und macht private Entwicklungsnachweise nicht zu einer öffentlichen Support-Zusage.

## Kandidat

| Punkt | Wert |
| --- | --- |
| Version | `0.1.0-alpha.1` |
| Android-Paket | `org.thegeekfreaks.jarvishorizon` |
| Mindest- / Ziel-API | 27 / 36 |
| Kandidaten-APK | `jarvis-horizon-0.1.0-alpha.1-android.apk` |
| Quellbasis | OpenClaw `03619565f017820a090f3fbf9b6571b3c8a3d3da` plus dieses öffentliche Overlay |
| Android-Updateweg | Manuelle APK-Installation aus dem passenden GitHub Release; kein öffentliches Android-OTA als Standard |

## Vor der Veröffentlichung erfasste Evidenz

| Prüfung | Ergebnis | Evidenzgrenze |
| --- | --- | --- |
| Neu gebaute native Abhängigkeit | Bestanden | Das eingebundene Sherpa-AAR wurde aus dem Quellcode mit portablen Debug-Präfixen neu gebaut, gestrippt und rekursiv geprüft. Weder im neuen AAR noch im Git-Quellarchiv oder der Kandidaten-APK wurde ein privater Windows-Buildpfad gefunden. |
| Signierter Android-Kandidat | Lokal bestanden | Eine `0.1.0-alpha.1`-APK wurde mit eigenständigem öffentlichem Paket und separatem Release-Zertifikat gebaut. Signatur und SHA-256 wurden für das geplante Release festgehalten. |
| Frischer öffentlicher Onboarding-Smoke-Test | Lokal bestanden | Das öffentliche Paket wurde neben der privaten Entwicklungs-App auf einem Archos Hello 7 installiert, startete nach rund 0,9 Sekunden in das nicht gekoppelte Onboarding und enthielt kein vorkonfiguriertes Gateway. Das ist ein Starttest, keine Abnahme von Talk oder Mikrofon. |
| Pi-Installer und fokussierte Runtime-Prüfungen | Bestanden | Pi-Installer-Dry-Run und Shell-Syntaxprüfungen sowie 39 fokussierte Python- und 6 Node-Prüfungen bestanden. Die native Wake-/VAD-Probe nutzte künstliches Schweigen, keinen Raum-Mikrofontest. |
| Private Geräteerprobung | Nur informativ | Frühere Arbeit erprobte private Deployments auf älterer Android-Hardware. Das ist keine öffentliche Installationsabnahme und wird nicht mit diesem Projekt verteilt. |

## Offenes Gate vor der öffentlichen Alpha

- [ ] Die vollständige Android-Unit-Test-Suite auf einem sauberen Linux-Runner korrigieren und erneut ausführen. Der letzte vollständige Lauf führte **3.759 Tests mit 12 Fehlern** aus; die Veröffentlichung bleibt gesperrt, bis diese Fehler untersucht und ein sauberer Lauf festgehalten ist.
- [ ] Falls die Korrektur Android-Produktivcode verändert, die signierte APK neu bauen und erneut scannen; die veröffentlichte Prüfsumme und der Fingerprint müssen aus genau diesem Artefakt stammen.
- [ ] Release mit exakter APK, SHA-256-Prüfsumme, SHA-256-Fingerprint des Signaturzertifikats, Quellarchiv, Pi-Archiv und Release Notes gemeinsam erstellen.
- [ ] Öffentliche GitHub-Release-Links sowie heruntergeladene Archive aus der Repository-Ansicht prüfen.

Das Repository ist weiterhin privat; solange dieses Gate offen ist, wurden weder ein öffentliches Release noch Werbung veröffentlicht.

## Was eine öffentliche Alpha beweist – und was nicht

Ein bestandener Release-Gate zeigt, dass die veröffentlichten Artefakte im oben beschriebenen Umfang gebaut und geprüft wurden. Er beweist keine Wakeword-Zuverlässigkeit in jedem Raum, Provider-Qualität, Kameralatenz oder -ton, Bluetooth-Mikrofonverfügbarkeit, Semantik der Hausautomation, Stock-Echo-Kompatibilität oder Hermes-Integration.

Konfiguriere die App ausschließlich mit einem eigenen Gateway, Provider und eigenen Integrationen. Beim ersten Start sind Pairing und Berechtigungen erwartbar. Ein verbundener Node beweist für sich allein nicht, dass Talk, Kameraton, Haussteuerung oder Medienrouting korrekt eingerichtet sind.
