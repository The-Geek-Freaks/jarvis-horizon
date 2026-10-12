<h1 align="center">Jarvis Horizon</h1>

<p align="center"><strong>Gib deinem Assistenten einen Platz im Raum.</strong><br>Eine sprachgesteuerte OpenClaw-Oberfläche für Android und Raspberry Pi.</p>

<p align="center"><a href="https://the-geek-freaks.github.io/jarvis-horizon/?lang=de#experience"><img src="docs/assets/try-demo.de.svg" alt="Demo im Browser starten" width="480"></a><br><sub>Ohne Installation. Ohne Anmeldung. Ohne Mikrofonzugriff.</sub></p>

<p align="center"><a href="https://the-geek-freaks.github.io/jarvis-horizon/?lang=de#experience"><img src="docs/assets/horizon-motion.gif" alt="Interaktive Horizon-Demo öffnen — animierte blaue und goldene Orbitaloberfläche" width="100%"></a></p>

<p align="center"><strong><a href="https://the-geek-freaks.github.io/jarvis-horizon/?lang=de#watch">23-Sekunden-Überblick ansehen</a> · <a href="docs/QUICKSTART.de.md">Auf deinem Display einrichten</a></strong><br><sub>Echter Renderer · Beispieldaten · acht Ansichten · geführte 30-Sekunden-Tour</sub></p>

<p align="center"><a href="README.md">English</a> · <strong>Deutsch</strong> · <a href="https://github.com/The-Geek-Freaks/jarvis-horizon/releases/tag/v0.1.0-alpha.1">Öffentliche Alpha herunterladen</a></p>

## Sieh, was dein Assistent gerade macht

- **Einfach sprechen.** Lokale Wakeword-Erkennung öffnet deine konfigurierte Sprachverbindung; der Orb folgt den Gesprächszuständen.
- **Die Arbeit verfolgen.** Empfangene Tool- und Unteragentenereignisse erscheinen als lesbare Aktivität, ohne rohe Befehle oder private Ausgaben offenzulegen.
- **Vorhandene Hardware weiterverwenden.** Hoch- und Querformat, Kontextansichten und eine bewegte Ambient-Uhr für Tablet, Raumdisplay oder Pi-Kiosk.

Deine Installation verwendet **dein eigenes OpenClaw Gateway und deinen Realtime-Provider**. Kamera, Wetter, Musik und Haussteuerung hängen von deinen eingerichteten Integrationen ab.

## Die Horizon-Sprache auf allen Oberflächen

**Klicke auf einen Bildschirm und öffne genau diese Ansicht in der Demo.** Wechsle die Ansicht oder Ausrichtung, oder starte die geführte Tour.

<table>
  <tr>
    <td width="50%" valign="top"><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=home&amp;format=landscape&amp;lang=de#experience"><img src="docs/assets/showcase-home.png" alt="Horizon-Übersicht mit orbitalem Zentrum und aktuellem Kontext" width="100%"></a><br><strong><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=home&amp;format=landscape&amp;lang=de#experience">Übersicht in der Demo öffnen ↗</a></strong> Uhrzeit, Status und Kontext an einem Ort.</td>
    <td width="50%" valign="top"><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=weather&amp;format=landscape&amp;lang=de#experience"><img src="docs/assets/showcase-weather.png" alt="Horizon-Wetteransicht" width="100%"></a><br><strong><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=weather&amp;format=landscape&amp;lang=de#experience">Wetteransicht öffnen ↗</a></strong> Gelieferte Wetterdaten erhalten bei Relevanz den Fokus.</td>
  </tr>
  <tr>
    <td valign="top"><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=activity&amp;format=landscape&amp;lang=de#experience"><img src="docs/assets/showcase-activity.png" alt="Horizon-Agentenaktivität mit gut lesbarem Toolstatus" width="100%"></a><br><strong><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=activity&amp;format=landscape&amp;lang=de#experience">Agentenaktivität öffnen ↗</a></strong> Ereignisse bleiben lesbar, ohne Befehle offenzulegen.</td>
    <td valign="top"><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=music&amp;format=landscape&amp;lang=de#experience"><img src="docs/assets/showcase-music.png" alt="Horizon-Musikansicht" width="100%"></a><br><strong><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=music&amp;format=landscape&amp;lang=de#experience">Musikansicht öffnen ↗</a></strong> Wiedergabe bleibt sichtbar, ohne den Raum zu dominieren.</td>
  </tr>
  <tr>
    <td valign="top"><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=agenda&amp;format=landscape&amp;lang=de#experience"><img src="docs/assets/showcase-agenda.png" alt="Horizon-Terminansicht" width="100%"></a><br><strong><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=agenda&amp;format=landscape&amp;lang=de#experience">Terminansicht öffnen ↗</a></strong> Kalenderdaten erscheinen, wenn dein Backend sie liefert.</td>
    <td valign="top"><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=house&amp;format=landscape&amp;lang=de#experience"><img src="docs/assets/showcase-home-control.png" alt="Horizon-Home-Control-Ansicht" width="100%"></a><br><strong><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=house&amp;format=landscape&amp;lang=de#experience">Zuhause-Ansicht öffnen ↗</a></strong> Sie folgt den Integrationen und Fähigkeiten deines Gateways.</td>
  </tr>
  <tr>
    <td valign="top"><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=ambient&amp;format=landscape&amp;lang=de#experience"><img src="docs/assets/showcase-ambient.png" alt="Horizon-Ambient-Ansicht" width="100%"></a><br><strong><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=ambient&amp;format=landscape&amp;lang=de#experience">Ambient-Ansicht öffnen ↗</a></strong> Bewegter Leerlauf statt dauerhaft starrer Komposition.</td>
    <td valign="top"><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=home&amp;format=portrait&amp;lang=de#experience"><img src="docs/assets/showcase-portrait.png" alt="Horizon-Layout im Hochformat" width="160"></a><br><strong><a href="https://the-geek-freaks.github.io/jarvis-horizon/?scene=home&amp;format=portrait&amp;lang=de#experience">Hochformat öffnen ↗</a></strong> Für schmale Wanddisplays und Tablets.</td>
  </tr>
</table>

*Alle Screenshots sind tatsächliche Frontend-Renderings mit synthetischen Beispieldaten. Sie zeigen Layouts und Zustände, aber weder ein verbundenes Gateway noch ein echtes Zuhause oder einen Hardware-Leistungstest.*

## Dein Assistent bleibt die Autorität

<p align="center"><a href="docs/assets/how-it-works.svg"><img src="docs/assets/how-it-works.png" alt="Architekturdiagramm: Android und Raspberry Pi nutzen lokale Wakeword-Erkennung; ein selbst betriebenes OpenClaw Gateway verbindet konfigurierten Voice-Provider, Agenten sowie optionale Home- und Medienfähigkeiten" width="100%"></a></p>

Die Wakeword-Erkennung läuft lokal auf der Oberfläche. Nach der Aktivierung folgt Gesprächsaudio dem Gateway- und Voice-Provider-Pfad, den **du** einrichtest. Eine lokale Wakeword-Erkennung macht die Cloud-Gesprächsverarbeitung nicht automatisch lokal.

Kamera-Aliase, Kalender, Medienrouten, Ansagen und Home-Aktionen sind optionale Integrationen. Ein gekoppelter Bluetooth-Lautsprecher ist nicht automatisch ein Mikrofon, und eine angeforderte Kamera ist noch kein bestätigter Livestream.

## Release-Status

`v0.1.0-alpha.1` ist die erste öffentliche Alpha. Installiere die exakte Android-APK, Prüfsummendatei und den Signaturzertifikat-Fingerprint aus dem passenden [GitHub Release](https://github.com/The-Geek-Freaks/jarvis-horizon/releases/tag/v0.1.0-alpha.1); nutze keine kopierte APK aus Chats, Spiegeln oder einer fremden Installation. Öffentliches Android-OTA ist bewusst nicht konfiguriert. Die [Validierung](docs/VALIDATION.md) beschreibt die genaue Evidenzgrenze und den abgeschlossenen Linux-Release-Gate.

Die implementierte Backend-Integration richtet sich an **OpenClaw**. Ein **Hermes-Adapter ist nicht enthalten**; bevor Hermes als unterstützt gelten kann, braucht es einen eigenen Protokolladapter samt Ende-zu-Ende-Abnahme.

## Wähle deine Oberfläche

| Oberfläche | Gedachte Rolle | Bekannte Grenze |
| --- | --- | --- |
| Android-Handy oder -Tablet | Wanddisplay oder tragbares Terminal | API 27+ mit unterstützter ARM-ABI; andere Geräte brauchen einen eigenen Abnahmelauf |
| Archos Hello 7 | Hoch- oder Querformat-Display | Nur private Deployment-Evidenz unter Android 8.1 / ARM64 |
| Echo Show 5, zweite Generation | Kompaktes Home-Display | Private Evidenz stammt von einem bereits modifizierten Android-11-Gerät; Stock-Echo-Firmware ist kein APK-Ziel |
| Raspberry Pi 4 | Display- oder Mirror-Kiosk | DietPi/Debian-ARM64-Installer und gelockter Runtime-Payload; Audio, Display und Pairing sind weiterhin installationsspezifisch |

## Starte mit einer sauberen, eigenen Installation

Lies vor dem Verbinden eines Displays die [Installationsanleitung](docs/INSTALL.de.md). Du brauchst ein selbst betriebenes OpenClaw Gateway, einen akzeptierten Pairing-Weg und einen eingerichteten Echtzeit-Provider für Talk. Übernimm niemals Tokens, Geräteidentitäten, Aufnahmen, Bluetooth-Identitäten, Kamera-URLs oder OTA-Konfiguration aus einer fremden Installation.

**[Android schnell einrichten](docs/QUICKSTART.de.md#android) · [Raspberry Pi schnell einrichten](docs/QUICKSTART.de.md#raspberry-pi-4)**

Der Android-Standardreiter ist Jarvis. Für Ansichten, die dein Agent aufruft, installierst du den optionalen [OpenClaw-Skill](integrations/openclaw/SKILL.md) in seinem eigenen Workspace; sein Helfer prüft die aktuell wirksamen Gerätefähigkeiten, bevor er eine Aktion anfordert.

## Hilf, das erste Alpha besser zu machen

Zeig, was du mit deinem Display gebaut hast, oder übernimm eine [Aufgabe für Einsteiger](https://github.com/The-Geek-Freaks/jarvis-horizon/labels/good%20first%20issue). Wenn Horizon zu deinem nächsten Projekt passt, speichere das Repository mit einem Stern.

Meldungen zu Wake-Recovery, Audio-Routing, Kamera-Decoding und älterer Hardware sind besonders wertvoll, wenn sie reproduzierbar sind. Nenne Gerät, OS/API, ABI, Release, Gateway-Version und genaue Schritte. Entferne persönliche Daten aus Logs; lade keine Raumaufnahmen, Kamerabilder, Tokens oder Haushaltskonfiguration hoch.

Lies [CONTRIBUTING.md](CONTRIBUTING.md). Sichtbare Zustände müssen auf echten Laufzeitereignissen beruhen, und Performance-Verbesserungen brauchen Messungen.

**[FAQ](docs/FAQ.md#deutsch) · [Dein Setup vorstellen](docs/SHOWCASE.md#deutsch) · [Getestetes Gerät melden](https://github.com/The-Geek-Freaks/jarvis-horizon/issues/new?template=supported-device.yml) · [Material zum Teilen](docs/SHARE-KIT.md#deutsch)**

## Danksagung

Jarvis Horizon baut auf [OpenClaw](https://github.com/openclaw/openclaw), [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) und [Silero VAD](https://github.com/snakers4/silero-vad) auf. Die Pi-Laufzeit verwendet Debian/DietPi, Chromium und den Linux-Audio-Stack.

Ein unabhängiges Community-Projekt von **The Geek Freaks**. Upstream-Urheberrechte und Lizenzen bleiben erhalten. Siehe [LICENSE](LICENSE) und [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
