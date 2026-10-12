# Release validation

**[English](VALIDATION.md) · [Deutsch](#release-validierung)**

This record separates measured release evidence from feature claims. It does not turn private development evidence into a public-support claim.

## Release artifact

| Item | Value |
| --- | --- |
| Version | `0.1.0-alpha.1` |
| Android package | `org.thegeekfreaks.jarvishorizon` |
| Minimum / target API | 27 / 36 |
| APK | `jarvis-horizon-0.1.0-alpha.1-android.apk` |
| APK SHA-256 | `4759715dc0c526dbc40ead61b76105ef33101608e1f380834bab60a83c0a9cb9` |
| APK size | 296,621,297 bytes |
| Signing-certificate SHA-256 | `5d76b450c90bc1a3e43820003093a2f45b38a8dc1fad27ca2d22c8971c6632c2` |
| Built from | Public source commit `ff6bb7a05adae62a41df8126ed115275f1c56b2a` |
| Source baseline | OpenClaw `03619565f017820a090f3fbf9b6571b3c8a3d3da` plus this public overlay |
| Android update path | Manual APK installs from a matching GitHub Release; no public Android OTA default |

## Evidence recorded before publication

| Check | Result | Evidence boundary |
| --- | --- | --- |
| Rebuilt native dependency | Passed | The bundled Sherpa AAR was rebuilt from its source with portable debug prefixes, stripped, then recursively scanned. The audit found no private Windows build path in the rebuilt AAR, the Git source archive, or the candidate APK. |
| Signed Android artifact | Passed locally | The `0.1.0-alpha.1` APK was built with the independent public package and a separate release certificate. R8 finished in 3m 56s; signature verification and a nested-path scan passed. |
| Fresh public onboarding smoke test | Passed locally | The public package installed beside the private development package on an Archos Hello 7, launched into unpaired onboarding in 1.359 seconds, and showed no preconfigured Gateway. This was a launch smoke test, not a Talk or microphone acceptance test. |
| Pi installer and focused runtime checks | Passed | Pi installer dry-run and shell syntax checks passed; 39 focused Python tests and 6 Node checks passed. The native wake/VAD probe used synthetic silence, not a room microphone. |
| Private-device exploration | Informative only | Prior work exercised private deployments on older Android hardware. It is not public-install acceptance and is not distributed with this project. |

## Release set and test boundary

The matching GitHub Release contains the APK, `SHA256SUMS.txt`, `SIGNING-CERTIFICATE-SHA256.txt`, `BUILD-PROVENANCE.json`, `jarvis-horizon-0.1.0-alpha.1-source.zip` and `jarvis-horizon-0.1.0-alpha.1-pi.zip`. Verify the downloaded APK against the checksums before installation.

**Windows local full-suite result:** 3,760 tests, 5 failures, 0 errors and 0 skips in 7m 39s. All five are the known Windows-only FileProvider slash-containment/URI timing cases (`FileProviderPathsTest`, `WorkspaceFilesShareLayoutTest` and `ChatComposer`); their corresponding tests passed in the earlier Linux run. This is not presented as a cross-platform green result. The authoritative Linux follow-up is tracked separately and will be linked here once complete.

## What a public alpha will and will not prove

Passing the release gate will show that the published artifacts were built and checked within the scope above. It will not prove wake-word reliability in every room, provider quality, camera latency or audio support, Bluetooth microphone availability, home-automation semantics, stock Echo compatibility, or a Hermes integration.

Configure the app only with a Gateway, provider and integrations you own. First launch is expected to require pairing and permissions. A connected node alone does not prove that Talk, camera audio, home control or media routing is configured correctly.

---

# Release-Validierung

**[English](#release-validation) · [Deutsch](VALIDATION.md)**

Dieses Protokoll trennt gemessene Release-Evidenz von Feature-Aussagen. Es macht private Entwicklungsnachweise nicht zu einer öffentlichen Support-Zusage.

## Release-Artefakt

| Punkt | Wert |
| --- | --- |
| Version | `0.1.0-alpha.1` |
| Android-Paket | `org.thegeekfreaks.jarvishorizon` |
| Mindest- / Ziel-API | 27 / 36 |
| APK | `jarvis-horizon-0.1.0-alpha.1-android.apk` |
| APK-SHA-256 | `4759715dc0c526dbc40ead61b76105ef33101608e1f380834bab60a83c0a9cb9` |
| APK-Größe | 296.621.297 Bytes |
| SHA-256 des Signaturzertifikats | `5d76b450c90bc1a3e43820003093a2f45b38a8dc1fad27ca2d22c8971c6632c2` |
| Gebaut aus | Öffentlichem Quellcommit `ff6bb7a05adae62a41df8126ed115275f1c56b2a` |
| Quellbasis | OpenClaw `03619565f017820a090f3fbf9b6571b3c8a3d3da` plus dieses öffentliche Overlay |
| Android-Updateweg | Manuelle APK-Installation aus dem passenden GitHub Release; kein öffentliches Android-OTA als Standard |

## Vor der Veröffentlichung erfasste Evidenz

| Prüfung | Ergebnis | Evidenzgrenze |
| --- | --- | --- |
| Neu gebaute native Abhängigkeit | Bestanden | Das eingebundene Sherpa-AAR wurde aus dem Quellcode mit portablen Debug-Präfixen neu gebaut, gestrippt und rekursiv geprüft. Weder im neuen AAR noch im Git-Quellarchiv oder der Kandidaten-APK wurde ein privater Windows-Buildpfad gefunden. |
| Signiertes Android-Artefakt | Lokal bestanden | Die `0.1.0-alpha.1`-APK wurde mit eigenständigem öffentlichem Paket und separatem Release-Zertifikat gebaut. R8 lief in 3m 56s durch; Signaturprüfung und verschachtelter Pfadscan bestanden. |
| Frischer öffentlicher Onboarding-Smoke-Test | Lokal bestanden | Das öffentliche Paket wurde neben der privaten Entwicklungs-App auf einem Archos Hello 7 installiert, startete nach 1,359 Sekunden in das nicht gekoppelte Onboarding und enthielt kein vorkonfiguriertes Gateway. Das ist ein Starttest, keine Abnahme von Talk oder Mikrofon. |
| Pi-Installer und fokussierte Runtime-Prüfungen | Bestanden | Pi-Installer-Dry-Run und Shell-Syntaxprüfungen sowie 39 fokussierte Python- und 6 Node-Prüfungen bestanden. Die native Wake-/VAD-Probe nutzte künstliches Schweigen, keinen Raum-Mikrofontest. |
| Private Geräteerprobung | Nur informativ | Frühere Arbeit erprobte private Deployments auf älterer Android-Hardware. Das ist keine öffentliche Installationsabnahme und wird nicht mit diesem Projekt verteilt. |

## Release-Set und Testgrenze

Der passende GitHub Release enthält APK, `SHA256SUMS.txt`, `SIGNING-CERTIFICATE-SHA256.txt`, `BUILD-PROVENANCE.json`, `jarvis-horizon-0.1.0-alpha.1-source.zip` und `jarvis-horizon-0.1.0-alpha.1-pi.zip`. Prüfe die heruntergeladene APK vor der Installation gegen die Prüfsummen.

**Ergebnis der vollständigen lokalen Windows-Suite:** 3.760 Tests, 5 Fehler, 0 Errors und 0 Skips in 7m 39s. Alle fünf betreffen die bekannten reinen Windows-Fälle zu FileProvider-Slash-Containment/URI-Timing (`FileProviderPathsTest`, `WorkspaceFilesShareLayoutTest` und `ChatComposer`); die jeweiligen Tests bestanden im früheren Linux-Lauf. Das wird nicht als plattformübergreifend grünes Ergebnis dargestellt. Der maßgebliche Linux-Follow-up wird getrennt verfolgt und nach Abschluss hier verlinkt.

## Was eine öffentliche Alpha beweist – und was nicht

Ein bestandener Release-Gate zeigt, dass die veröffentlichten Artefakte im oben beschriebenen Umfang gebaut und geprüft wurden. Er beweist keine Wakeword-Zuverlässigkeit in jedem Raum, Provider-Qualität, Kameralatenz oder -ton, Bluetooth-Mikrofonverfügbarkeit, Semantik der Hausautomation, Stock-Echo-Kompatibilität oder Hermes-Integration.

Konfiguriere die App ausschließlich mit einem eigenen Gateway, Provider und eigenen Integrationen. Beim ersten Start sind Pairing und Berechtigungen erwartbar. Ein verbundener Node beweist für sich allein nicht, dass Talk, Kameraton, Haussteuerung oder Medienrouting korrekt eingerichtet sind.
