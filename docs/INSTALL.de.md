# Jarvis Horizon installieren

**[English](INSTALL.md) · [Deutsch](INSTALL.de.md)**

Jarvis Horizon ist eine Client-Oberfläche für ein OpenClaw Gateway, das du selbst betreibst. Es erstellt kein gehostetes Konto, Gateway, Provider-Konto oder Hausautomations-Setup.

## Release-Status

Jarvis Horizon bereitet `v0.1.0-alpha.1` vor. Ein signierter Android-Releasekandidat wurde lokal erzeugt, aber die öffentliche Veröffentlichung wartet noch auf ein Audit des portablen Artefakts, einen sauberen Linux-Validierungslauf und einen Erststart auf einem frischen Gerät. Bis diese Prüfungen dokumentiert sind, gibt es keinen unterstützten Binärdownload. Nach der Veröffentlichung nutze ausschließlich den exakt passenden Release unter https://github.com/The-Geek-Freaks/jarvis-horizon/releases/tag/v0.1.0-alpha.1 — keine APK aus Chats, Spiegeln oder einer fremden Installation.

Die Android-Quellvorbereitung steht zur Prüfung bereit, ersetzt aber kein verifiziertes öffentliches Release-Artefakt. Pi-Installer und gelockter Laufzeit-Payload liegen im Repository.

## Voraussetzungen

Du brauchst ein eigenes erreichbares Gateway, dessen akzeptierte Pairing- oder Autorisierungsdaten und für Talk einen eingerichteten Echtzeit-Provider. Android-Quellbuilds brauchen passendes Android SDK sowie Node/pnpm. Pi braucht Pi 4 mit DietPi/Debian ARM64, Internet, einen lokalen Benutzer und sudo. Übernimm niemals Tokens, Session Keys, OTA-Werte, Bluetooth-Identitäten, Home-Assistant-Zugangsdaten, Aufnahmen oder Konfiguration anderer Installationen.

## Checkout und Build-Voraussetzungen

    git clone https://github.com/The-Geek-Freaks/jarvis-horizon.git
    cd jarvis-horizon
    pwsh -File ./Test-ReleaseStaging.ps1

Für einen Quellcode-Build brauchst du Git, PowerShell, JDK 21, Android-SDK-Plattform 37 und NDK 28.2.13676358. Der festgelegte Upstream-Stand verlangt Node >=24.16.0 <25 oder >=26.1.0 sowie pnpm 12.5.1. ANDROID_HOME muss auf dein SDK zeigen; dessen Lizenzen müssen angenommen sein. Die Gradle-Version bringt der Wrapper mit. Auf einem gemeinsam genutzten Rechner höchstens die Hälfte der logischen CPU-Kerne für Builds verwenden.

Das Debug-Paket heißt `org.thegeekfreaks.jarvishorizon.debug`, das Release-Paket `org.thegeekfreaks.jarvishorizon`. Der Java-/Kotlin-Namensraum bleibt `ai.openclaw.app`.

## Android aus dem Quellcode

Nutze ein neues Ziel; das Skript überschreibt kein vorhandenes Verzeichnis.

    ./Prepare-AndroidSource.ps1 -Destination C:\src\openclaw-jarvis
    cd C:\src\openclaw-jarvis
    pnpm install --frozen-lockfile
    cd apps/android
    ./gradlew :app:assembleThirdPartyDebug --max-workers=2

Das Skript pinnt OpenClaw auf 03619565f017820a090f3fbf9b6571b3c8a3d3da, kopiert das öffentliche Overlay, prüft das mitgelieferte Sherpa-AAR und schließt Zugangsdaten, Geräteidentität, Signierschlüssel, OTA-Status und Sitzungen aus. Dies sind build-bereite Anweisungen, kein bestätigter sauberer öffentlicher Build. Die öffentliche Application-ID lautet `org.thegeekfreaks.jarvishorizon`; der Namespace bleibt `ai.openclaw.app`. Beim ersten Start gibt es keine Gateway-, Identitäts-, OTA- oder Home-Standardwerte. Verbinde nur dein eigenes Gateway und rufe ausschließlich aktuell beworbene Fähigkeiten auf.

## Erste Android-Verbindung

1. Installiere nach Veröffentlichung nur `jarvis-horizon-0.1.0-alpha.1-android.apk` aus dem passenden GitHub Release oder deine selbst gebaute Debug-APK. Android fragt gegebenenfalls nach der Installationsberechtigung für Browser oder Dateimanager.
2. Erzeuge auf deinem eigenen Gateway-Rechner mit `openclaw qr` einen Setup-QR-Code beziehungsweise Einrichtungscode. Teile diese Zugangsdaten nicht öffentlich.
3. Wähle in der App **Setup Gateway** und scanne den QR-Code oder öffne **Use setup code**. Alternativ kannst du Endpunkt und Token manuell eingeben.
4. Bestätige das neue Gerät beziehungsweise den Node in deinem Gateway und schließe die Berechtigungsschritte der App ab. Sprache benötigt Mikrofonzugriff; Kamera- und Benachrichtigungsfunktionen benötigen ihre jeweiligen Berechtigungen.
5. Warte auf die Verbindung von Gateway und Node. Teste zuerst Push-to-talk, danach Wakeword und Lautsprecher vor Ort. Eine Node-Verbindung allein beweist noch keinen eingerichteten Voice-Provider.

Der Standard-Startreiter ist Jarvis. Für Ansichten, die dein Agent selbst aufruft, kannst du den optionalen [OpenClaw-Skill](../integrations/openclaw/SKILL.md) in seinen Workspace installieren. Sein Werkzeug prüft die aktuell freigegebenen Gerätefähigkeiten.

## Raspberry Pi 4

Prüfe aus dem öffentlichen Checkout zuerst ohne Änderungen:

    ./pi/install-pi.sh --dry-run

Installiere erst danach:

    sudo ./pi/install-pi.sh

Der Installer richtet Chromium/X11, PipeWire/WirePlumber, Bluetooth, Python und systemd ein, verwaltet /opt/jarvis-mirror/releases/<VERSION> und /opt/jarvis-mirror/current, behält vorhandenes /etc/jarvis-mirror/config.json und legt Identitätsdaten unter /var/lib/jarvis-mirror ab.

## Pi konfigurieren und prüfen

Beginne mit pi/config.example.json, bearbeite dann /etc/jarvis-mirror/config.json. Gateway-URL/-Token, Voice-Session-Key, Mikrofon, Home-Assistant-Werte, Bluetooth-Ziel und OTA-Werte kommen vom Betreiber. Bluetooth-Reconnect ist ohne bt_mac deaktiviert. Öffentliches Android-OTA ist bewusst deaktiviert; Android-Updates erfolgen manuell über Release-APKs. Pi-OTA hat keinen privaten Standardwert und benötigt deine Manifest-URL plus öffentlichen Schlüssel. Paare die lokale Identität gemäß deinem Gateway und starte jarvis-mirror.service sowie jarvis-kiosk.service.

Das lokale Wake-/VAD-Payload wird unter `/opt/jarvis-mirror/models/wake` installiert; Prüfsummen stehen in `pi/assets.lock.json`. Installer-Dry-Run und Shell-Syntaxprüfung, 39 fokussierte Python-Tests, sechs Node-Tests und die isolierte native Konstruktion von WakeSpotter/SpeechGate mit sherpa_onnx 1.13.8 bestanden. Der native Probe nutzte 40 ms künstliches PCM16-Schweigen; er ist keine Mikrofon-Abnahme auf dem Ziel-Pi.

## Optionale Integrationen und Hermes

Home-Karten, Home-Assistant-Daten, Raum-/Medienrouting, Ansagen, Kamera-Orchestrierung, Routinen, eigene jarvis.*-Befehle und verwaltetes OTA sind separate Integrationen. Sie funktionieren nicht automatisch nach der Installation. Details: [Architektur](ARCHITECTURE.md), [Kompatibilität](COMPATIBILITY.md). Hermes wird nicht unterstützt: Adapter und Ende-zu-Ende-Validierung fehlen.

### Konfigurierte Pi-Dienste starten

    sudoedit /etc/jarvis-mirror/config.json
    sudo systemctl start jarvis-mirror.service jarvis-kiosk.service
    systemctl status jarvis-mirror.service jarvis-kiosk.service --no-pager
    journalctl -u jarvis-mirror.service -n 50 --no-pager

Trage deinen tatsächlichen Gateway-Endpunkt und die zugehörigen Zugangsdaten ein. Bestätige die neue Identität im Gateway, sobald eine Pairing-Anfrage erscheint. Bei falschem Mikrofon oder Tonausgang zeigt `wpctl status` die verfügbaren Geräte; wähle den tatsächlich angeschlossenen Ein-/Ausgang. Ein gekoppelter Bluetooth-Lautsprecher wählt nicht automatisch ein Mikrofon.

