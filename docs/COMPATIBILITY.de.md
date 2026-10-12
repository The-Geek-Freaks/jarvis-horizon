# Kompatibilität

**[English](COMPATIBILITY.md) · [Deutsch](COMPATIBILITY.de.md)**

„Implementiert“ bezeichnet einen vorhandenen Codepfad. „Geprüft“ bezeichnet einen tatsächlich ausgeführten Test. Beides garantiert noch keinen fehlerfreien Betrieb auf anderer Hardware oder mit einem anderen Gateway.

| Bereich | Öffentliche Grundlage | Zusätzlich nötig |
| --- | --- | --- |
| Android | API 27 oder neuer, ARMv7/ARM64 | Berechtigungen, Pairing und ein eigener Voice-Provider |
| Archos Hello 7 | Frischer nicht gekoppelter Onboarding-Smoke-Test unter Android 8.1 / ARM64 | Breite Geräteabnahme bleibt offen |
| Echo Show 5 Gen 2 | Private Geräteprüfung unter modifiziertem Android 11 / ARMv7 | Bereits geeignetes Betriebssystem; keine Firmware-Umrüstung im Installer |
| Raspberry Pi 4 | DietPi/Debian ARM64, Kiosk-Installer | Physischer Mikrofon-/Lautsprechertest und Gateway-Pairing |
| Wakeword und VAD | Mitgelieferte, per Hash festgelegte Modelle | Aussprache, Entfernung, Raumgeräusche und Mikrofon müssen vor Ort geprüft werden |
| Echtzeitgespräch | OpenClaw-Clientprotokoll | Erreichbarer und autorisierter Gateway mit passendem Provider |
| Tool-Anzeige | Darstellung eingehender Ereignisse | Der Backend-Pfad muss diese Ereignisse liefern |
| Kameras | Vorhandene Ansichten und Player | Eingerichtete Quelle, erreichbarer Stream und geeignete Codecs |
| Musik, Haus, Kalender | Optionale Integrationspfade | Eigene Dienste, Entitäten, Tokens und Ausgaberouten |
| Updates | Android manuell, Pi-OTA optional | Für Pi-OTA ein eigener signierter Feed und Vertrauensschlüssel |
| Hermes | Kein Adapter enthalten | Eigene Implementierung und Ende-zu-Ende-Prüfung |

## Release-Regeln

- Das Release-Paket heißt `org.thegeekfreaks.jarvishorizon`, setzt mindestens API 27 voraus und zielt auf API 36. Der Implementierungs-Namespace bleibt `ai.openclaw.app`.
- `v0.1.0-alpha.1` wird über seinen passenden GitHub Release mit APK, Prüfsummen, Signaturzertifikat-Fingerprint und Validierungsnachweis verteilt. Öffentliche Android-Updates erfolgen manuell.
- Private Deployment-Evidenz bleibt bewusst von der öffentlichen Release-Abnahme getrennt.

Die Dokumentation gibt es auf Englisch und Deutsch. Die aktuelle Horizon-Oberfläche enthält überwiegend deutsche Beschriftungen; die Sprache des Gesprächs wird zusätzlich durch die Backend-Konfiguration bestimmt.

Die Pi-Tests umfassen 39 Python- und sechs Node-Prüfungen sowie das Laden der echten Wake-/VAD-Modelle mit Sherpa 1.13.8 und synthetischer Stille. Das ersetzt keinen Hör- oder Mikrofontest auf dem Pi.

Ein Bluetooth-Lautsprecher liefert nicht automatisch einen Mikrofoneingang. Eine Kamera im Zustand „angefordert“ oder „verbindet“ ist noch kein bestätigtes Livebild. Fehlende Ereignisse sind kein Erfolgsnachweis.

Für Fehlerberichte bitte Gerät, Betriebssystem/API, Architektur, Release, Gateway-/Provider-Version und reproduzierbare Schritte nennen. Private Räume, Aufnahmen und Zugangsdaten gehören nicht in einen öffentlichen Bericht.
