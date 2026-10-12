# Jarvis Horizon: erster Erfolg

**[English](QUICKSTART.md) · [Deutsch](QUICKSTART.de.md)**

Starte mit der Browser-Demo und wechsle auf dein eigenes Display, wenn du bereit bist. Für Android und Raspberry Pi brauchst du zusätzlich ein selbst betriebenes OpenClaw Gateway und einen eingerichteten Realtime-Provider für Talk.

## Wähle deinen ersten Erfolg

| Ziel | Was du brauchst | Erstes Ergebnis |
| --- | --- | --- |
| Horizon sofort sehen | Einen modernen Browser | Acht interaktive Beispielansichten im Hoch- oder Querformat |
| Auf Android starten | API-27+-ARM-Android-Gerät, eigenes OpenClaw Gateway | Einen gekoppelten Horizon-Node und Push-to-talk |
| Raumdisplay bauen | Raspberry Pi 4, DietPi/Debian ARM64, Display, Mikrofon und Lautsprecher | Einen mit deinem Gateway gekoppelten Kiosk |

## 1. Ohne Gerät ausprobieren

Öffne die **[Browser-Demo](https://the-geek-freaks.github.io/jarvis-horizon/?lang=de)**.

Wähle eine Ansicht und wechsle zwischen Quer- und Hochformat. Orb, Layouts und responsiver Renderer sind das echte Horizon-Frontend; Ereignisse, Musikpegel und Home-Daten sind lokale Beispieldaten. Die Demo hat keinen Login, keinen Mikrofonzugriff, keinen Live-Kamerastream und keine Gateway-Verbindung.

Das ist der richtige Start, wenn du entscheiden möchtest, ob Horizon auf ein altes Tablet, ein Wanddisplay oder hinter einen Spiegel soll, bevor du etwas installierst.

<a id="android"></a>

## 2. Android: einen Bildschirm koppeln, dann Talk testen

1. Lade die signierte APK aus dem passenden [Release v0.1.0-alpha.1](https://github.com/The-Geek-Freaks/jarvis-horizon/releases/tag/v0.1.0-alpha.1) herunter. Prüfe ihren Eintrag in `SHA256SUMS.txt`; nutze keine kopierten APKs aus Chats oder Spiegeln.
2. Installiere sie auf einem Android-Gerät mit API 27 oder neuer und unterstützter ARM-ABI, dann öffne Jarvis Horizon.
3. Erzeuge auf dem Rechner mit **deinem** Gateway mit `openclaw qr` die Setup-Information. Teile QR-Code beziehungsweise Code nicht öffentlich.
4. Wähle in Horizon **Setup Gateway**, scanne den QR-Code oder wähle **Use setup code**. Manuelle Eingabe von Endpunkt und Token ist ebenfalls möglich.
5. Bestätige Gerät/Node im Gateway und erteile nur die benötigten Android-Berechtigungen. Für Talk ist Mikrofonzugriff erforderlich.
6. Warte, bis Gateway und Node beide verbunden anzeigen. Führe das erste Gespräch mit **Push-to-talk**. Aktiviere Wakeword erst, wenn Mikrofon und Lautsprecher vor Ort funktionieren.

Wenn dein Backend Wetterdaten liefert und Gerät/Node `jarvis.ui` anbietet, kann dein Agent nach dem Pairing eine Wetteransicht anzeigen. Der optionale [OpenClaw-Skill](../integrations/openclaw/SKILL.md) prüft vor einer Anfrage die aktuell gemeldeten Fähigkeiten des Nodes. Die Installation der APK allein erzeugt keine Wetterkarte.

Voraussetzungen, Quellbuild und Details zur manuellen Wiederherstellung stehen in der vollständigen [Android-Anleitung](INSTALL.de.md#erste-android-verbindung).

<a id="raspberry-pi-4"></a>

## 3. Raspberry Pi 4: erst die Raumhardware vorbereiten

Halte vor der Installation diese Hardware bereit:

- Raspberry Pi 4 mit 64-bit DietPi oder Debian, Internet, lokalem Benutzer und `sudo`
- Display und einen hörbaren Audioausgang
- Ein echtes Mikrofon; ein gekoppelter Bluetooth-Lautsprecher ist kein Mikrofon
- Dein eigenes erreichbares OpenClaw Gateway inklusive Pairing-Weg

Lade das versionierte Pi-Archiv aus dem [Release](https://github.com/The-Geek-Freaks/jarvis-horizon/releases/tag/v0.1.0-alpha.1), prüfe es mit `SHA256SUMS.txt`, entpacke es und prüfe zuerst ohne Änderungen:

```sh
./pi/install-pi.sh --dry-run
```

Wenn der Dry-Run sauber ist, installiere:

```sh
sudo ./pi/install-pi.sh
```

Konfiguriere danach `/etc/jarvis-mirror/config.json` anhand von `pi/config.example.json`, paare die lokale Identität wie vom Gateway gefordert und starte `jarvis-mirror.service` sowie `jarvis-kiosk.service`. Die vollständige Konfiguration und Prüfung stehen in der [Pi-Anleitung](INSTALL.de.md#raspberry-pi-4).

## Wenn der erste Erfolg ausbleibt

| Was du siehst | Zuerst prüfen | Danach nachsehen |
| --- | --- | --- |
| Die Demo läuft, Android/Pi koppelt aber nicht | Richtiger Gateway-QR/Setup-Code, erreichbarer Endpunkt und ausstehende Gerätefreigabe | [Android-Verbindung](INSTALL.de.md#erste-android-verbindung) oder Pi-Konfiguration in [Installation](INSTALL.de.md#pi-konfigurieren-und-prüfen) |
| Das Display ist gekoppelt, Talk antwortet aber nicht | Gateway-/Provider-Konfiguration und Mikrofonberechtigung; zuerst Push-to-talk testen | [Kompatibilität: Realtime Talk](COMPATIBILITY.de.md) |
| Das Wakeword ist unzuverlässig | Mikrofonauswahl, Abstand, Raumlärm und Lautsprecher-Rückkopplung | Push-to-talk als Fallback verwenden und reproduzierbar melden |
| Karte, Kamera, Musik oder Home-Aktion fehlen | Backend-Integration und aktuell gemeldete Node-Fähigkeiten | [OpenClaw-Skill](../integrations/openclaw/SKILL.md); diese Integrationen sind optional |
| Pi zeigt Bild, aber keinen Ton oder keine Wake-Erkennung | Gewählter physischer PipeWire-Ein-/Ausgang, nicht nur Bluetooth-Pairing | [Pi-Konfiguration](INSTALL.de.md#pi-konfigurieren-und-prüfen) |

Bei Problemen nenne Gerät, OS/API, ABI, Release, Gateway-Version, Provider und genaue Schritte. Keine Tokens, Setup-Codes, Raumaufnahmen, Kameraaufnahmen oder Haushaltskonfiguration veröffentlichen.

## Danach weiter

- [Kompatibilität](COMPATIBILITY.de.md) — Evidenzgrenze und unterstützte Oberflächen
- [FAQ](FAQ.md#deutsch) — häufige Fragen vor dem Verbinden eines Geräts
- [Dein Setup vorstellen](SHOWCASE.md#deutsch) — damit andere Hardware wiederverwenden können
- [Material zum Teilen](SHARE-KIT.md#deutsch) — editierbare Texte und echte Projektmedien
