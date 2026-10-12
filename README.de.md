<p align="center"><a href="docs/assets/horizon-motion.mp4"><img src="docs/assets/horizon-motion.gif" alt="Jarvis-Horizon-Orbitalintelligenz in ruhigen, zuhörenden und arbeitenden Zuständen" width="640"></a></p>

<p align="center"><a href="README.md">English</a> · <strong>Deutsch</strong></p>

# Jarvis Horizon

**Eine sprachorientierte Home-Oberfläche für dein eigenes OpenClaw Gateway.**

Jarvis Horizon macht aus einem Android-Display oder Raspberry Pi eine ruhige, freihändige Präsenz für deinen Assistenten: lokale Wakeword-Erkennung, Gesprächszustände, sichtbare Agentenaktivität, Kameraansichten, Medienkontext und eine Ambient-Ansicht für die Zeit dazwischen.

*Die lebendige Orbital-Animation verwendet synthetische Beispieldaten. Antippen öffnet die MP4-Version.*

[Installation](docs/INSTALL.de.md) · [Kompatibilität](docs/COMPATIBILITY.de.md) · [Architektur](docs/ARCHITECTURE.de.md) · [Validierung](docs/VALIDATION.md) · [Mitwirken](CONTRIBUTING.md)

## Ein Display für den Moment, nicht nur für einen Chat

Jarvis Horizon ist für Displays gedacht, die im Raum bleiben. Es hält den aktuellen Kontext aus der Entfernung lesbar und wechselt seinen Zustand, wenn dein Assistent zuhört, arbeitet, antwortet oder eine Kameraansicht zeigt.

| Was die Oberfläche kann | Warum das nützlich ist |
| --- | --- |
| **Lokal aufwachen** | Ein lokaler Wakeword-Pfad kann eine Talk-Session öffnen, ohne dass dauerhaft ein Vollbild-Transkript im Raum steht. Push-to-talk bleibt für schwierige Raumakustik verfügbar. |
| **Arbeit sichtbar machen** | Empfangene Agenten-, Tool- und delegierte Aufgabenereignisse erscheinen als kurze, begrenzte Aktivitätszeilen statt als rohe Befehle, Argumente oder private Ausgaben. |
| **Den Bildschirm bewusst nutzen** | Horizon passt sich Hoch- und Querformat an. Kontextansichten können Backend-Daten darstellen; die Ambient-Ansicht verringert statische Darstellung im Leerlauf. |
| **Live-Kontext einblenden** | Android kann Kamera-Overlays und Vollbildwiedergabe zeigen. Ob ein bestimmter Stream Ton enthält, hängt von Quelle, Codec und Berechtigungen ab. |
| **Die Kontrolle zu Hause behalten** | Gateway, Provider, Identität und Integrationen stellst du selbst bereit. Dieses Repository enthält kein Konto, Token, Geräteidentität, Haushaltskonfiguration oder OTA-Endpunkt. |

## Die Horizon-Sprache auf allen Oberflächen

<table>
  <tr>
    <td width="50%" valign="top"><a href="docs/assets/showcase-home.png"><img src="docs/assets/showcase-home.png" alt="Horizon-Übersicht mit orbitalem Zentrum und aktuellem Kontext" width="100%"></a><br><strong>Das Zuhause im Blick.</strong> Die raumorientierte Übersicht hält Uhrzeit, Status und Kontext an einem Ort zusammen.</td>
    <td width="50%" valign="top"><a href="docs/assets/showcase-weather.png"><img src="docs/assets/showcase-weather.png" alt="Horizon-Wetteransicht" width="100%"></a><br><strong>Wetter im Kontext.</strong> Eine gelieferte Wetteransicht kann bei Relevanz den visuellen Fokus übernehmen.</td>
  </tr>
  <tr>
    <td valign="top"><a href="docs/assets/showcase-activity.png"><img src="docs/assets/showcase-activity.png" alt="Horizon-Agentenaktivität mit gut lesbarem Toolstatus" width="100%"></a><br><strong>Sichtbare Arbeit.</strong> Empfangene Aktivität wird zu einem begrenzten Status statt zu einem offengelegten Befehlsstream.</td>
    <td valign="top"><a href="docs/assets/showcase-music.png"><img src="docs/assets/showcase-music.png" alt="Horizon-Musikansicht" width="100%"></a><br><strong>Medienkontext.</strong> Wiedergabe kann sichtbar bleiben, ohne den Fokus des Raums zu übernehmen.</td>
  </tr>
  <tr>
    <td valign="top"><a href="docs/assets/showcase-agenda.png"><img src="docs/assets/showcase-agenda.png" alt="Horizon-Terminansicht" width="100%"></a><br><strong>Was als Nächstes kommt.</strong> Kalenderdaten erscheinen nur, wenn dein Backend sie liefert.</td>
    <td valign="top"><a href="docs/assets/showcase-home-control.png"><img src="docs/assets/showcase-home-control.png" alt="Horizon-Home-Control-Ansicht" width="100%"></a><br><strong>Home nach deinen Regeln.</strong> Die Oberfläche folgt den Integrationen und Fähigkeiten, die dein Gateway ausweist.</td>
  </tr>
  <tr>
    <td valign="top"><a href="docs/assets/showcase-ambient.png"><img src="docs/assets/showcase-ambient.png" alt="Horizon-Ambient-Ansicht" width="100%"></a><br><strong>Ambient, nicht abwesend.</strong> Die Leerlaufansicht bewegt Inhalt und vermeidet eine dauerhaft starre Komposition.</td>
    <td valign="top"><a href="docs/assets/showcase-portrait.png"><img src="docs/assets/showcase-portrait.png" alt="Horizon-Layout im Hochformat" width="160"></a><br><strong>Bereit für Hochformat.</strong> Das visuelle System schafft Raum für schmale Wanddisplays und Tablets.</td>
  </tr>
</table>

*Alle Screenshots sind tatsächliche Frontend-Renderings mit synthetischen Beispieldaten. Sie zeigen Layouts und Zustände, aber weder ein verbundenes Gateway noch ein echtes Zuhause oder einen Hardware-Leistungstest.*

## Dein Assistent bleibt die Autorität

<p align="center"><a href="docs/assets/how-it-works.svg"><img src="docs/assets/how-it-works.png" alt="Architekturdiagramm: Android und Raspberry Pi nutzen lokale Wakeword-Erkennung; ein selbst betriebenes OpenClaw Gateway verbindet konfigurierten Voice-Provider, Agenten sowie optionale Home- und Medienfähigkeiten" width="100%"></a></p>

Die Wakeword-Erkennung läuft lokal auf der Oberfläche. Nach der Aktivierung folgt Gesprächsaudio dem Gateway- und Voice-Provider-Pfad, den **du** einrichtest. Eine lokale Wakeword-Erkennung macht die Cloud-Gesprächsverarbeitung nicht automatisch lokal.

Kamera-Aliase, Kalender, Medienrouten, Ansagen und Home-Aktionen sind optionale Integrationen. Ein gekoppelter Bluetooth-Lautsprecher ist nicht automatisch ein Mikrofon, und eine angeforderte Kamera ist noch kein bestätigter Livestream.

## Release-Status

Jarvis Horizon bereitet seinen ersten öffentlichen Alpha-Release vor. Ein signierter Android-Releasekandidat wurde lokal gebaut, aber die öffentliche Veröffentlichung wartet noch auf ein frisches Audit des portablen Artefakts, einen sauberen Linux-Validierungslauf und einen Erststart auf einem frischen Gerät. Bis diese Prüfungen abgeschlossen sind, gibt es keinen unterstützten Download und keinen Anspruch auf einen bestätigten öffentlichen Installationsweg.

Nach der Veröffentlichung ist der unterstützte manuelle Updateweg der passende [GitHub Release](https://github.com/The-Geek-Freaks/jarvis-horizon/releases/tag/v0.1.0-alpha.1). Öffentliches Android-OTA ist bewusst nicht konfiguriert. Die genaue Beleggrenze steht unter [Validierung](docs/VALIDATION.md).

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

Der Android-Standardreiter ist Jarvis. Für Ansichten, die dein Agent aufruft, installierst du den optionalen [OpenClaw-Skill](integrations/openclaw/SKILL.md) in seinem eigenen Workspace; sein Helfer prüft die aktuell wirksamen Gerätefähigkeiten, bevor er eine Aktion anfordert.

## Hilf, das erste Alpha besser zu machen

Meldungen zu Wake-Recovery, Audio-Routing, Kamera-Decoding und älterer Hardware sind besonders wertvoll, wenn sie reproduzierbar sind. Nenne Gerät, OS/API, ABI, Release, Gateway-Version und genaue Schritte. Entferne persönliche Daten aus Logs; lade keine Raumaufnahmen, Kamerabilder, Tokens oder Haushaltskonfiguration hoch.

Lies [CONTRIBUTING.md](CONTRIBUTING.md). Sichtbare Zustände müssen auf echten Laufzeitereignissen beruhen, und Performance-Verbesserungen brauchen Messungen.

## Danksagung

Jarvis Horizon baut auf [OpenClaw](https://github.com/openclaw/openclaw), [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) und [Silero VAD](https://github.com/snakers4/silero-vad) auf. Die Pi-Laufzeit verwendet Debian/DietPi, Chromium und den Linux-Audio-Stack.

Ein unabhängiges Community-Projekt von **The Geek Freaks**. Upstream-Urheberrechte und Lizenzen bleiben erhalten. Siehe [LICENSE](LICENSE) und [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
