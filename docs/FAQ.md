# Frequently asked questions / Häufige Fragen

**[English](#english) · [Deutsch](#deutsch)**

## English

### What is Jarvis Horizon?

Jarvis Horizon is a community interface for an [OpenClaw](https://openclaw.ai) Gateway you run yourself. It can provide a room-facing Android display or Raspberry Pi surface, but it does not host an account, operate a Gateway for you, or replace a stock smart-display firmware.

### Is it ready for every Android device or smart display?

No. The first public release is `v0.1.0-alpha.1`. The public Android baseline is API 27+ on a supported ARM ABI, while real-device coverage is still limited. A stock Echo is not an APK target and no firmware conversion is provided. See [Compatibility](COMPATIBILITY.md) before buying or repurposing hardware.

### Which setup should I start with?

Use a device you already own, a Gateway you control, and push-to-talk first. The [installation guide](INSTALL.md) covers Android and Pi 4 separately. Enable wake-word listening only after microphone, speaker, pairing and your realtime provider work in your own environment.

### Do I need OpenClaw and a provider?

Yes. Horizon is a client surface, so it needs a reachable Gateway that you own and approve. Talk also needs a configured realtime provider. Home, camera, media and automation views depend on separate integrations and the capabilities your Gateway advertises.

### Does it include Home Assistant, camera control, Bluetooth, OTA or Hermes?

Those are not automatic outcomes of installing Horizon. They require your own integration and validation. Bluetooth reconnect needs an explicit paired target; Pi OTA needs an operator-provided manifest URL and public key; public Android updates are manual. Hermes has no included adapter or end-to-end validation.

### Where should I get the Android APK?

Use only the matching [GitHub release](https://github.com/The-Geek-Freaks/jarvis-horizon/releases/tag/v0.1.0-alpha.1), then verify `SHA256SUMS.txt` and `SIGNING-CERTIFICATE-SHA256.txt`. Do not install an APK forwarded through a chat, copied from another device, or hosted by a mirror.

### Can I use my existing Gateway tokens or configuration?

Only use credentials and setup material you own. Do not copy tokens, session keys, OTA values, Bluetooth identities, Home Assistant credentials, room recordings, camera footage, or private configuration from another installation.

### How do I report a problem or request a change?

Use the repository's bug or feature issue template. For hardware reports, use the **Supported device report** template and state what actually worked, what did not, and how you tested it. Scrub logs and never publish private URLs, tokens, identities, recordings or camera images.

### Is there a browser demo?

The [browser demo](https://the-geek-freaks.github.io/jarvis-horizon/) runs with local synthetic data. It has no login, does not use a microphone, and does not connect to a live Gateway or backend. The display intentionally retains German labels; the website is available in English and German.

### How can I share my own setup safely?

Start with [Show your setup](SHOWCASE.md). It explains what to redact, how to get consent, and how to describe a real result without implying that it will work on every device.

## Deutsch

### Was ist Jarvis Horizon?

Jarvis Horizon ist eine Community-Oberfläche für ein OpenClaw-Gateway, das du selbst betreibst. Sie kann ein raumorientiertes Android-Display oder eine Raspberry-Pi-Oberfläche bereitstellen, hostet aber weder ein Konto noch ein Gateway und ersetzt keine Stock-Firmware eines Smart Displays.

### Läuft es auf jedem Android-Gerät oder Smart Display?

Nein. Die erste öffentliche Version ist `v0.1.0-alpha.1`. Die öffentliche Android-Basis ist API 27+ auf einer unterstützten ARM-ABI, die Abdeckung realer Geräte ist aber noch begrenzt. Ein Stock-Echo ist kein APK-Ziel und es gibt keine Firmware-Konvertierung. Lies vor einem Kauf oder Umbau die [Kompatibilität](COMPATIBILITY.de.md).

### Mit welchem Setup sollte ich beginnen?

Nimm ein Gerät, das du bereits besitzt, ein Gateway unter deiner Kontrolle und beginne mit Push-to-Talk. Die [Installationsanleitung](INSTALL.de.md) behandelt Android und Pi 4 getrennt. Aktiviere das Wake Word erst, wenn Mikrofon, Lautsprecher, Pairing und dein Realtime-Provider in deiner Umgebung funktionieren.

### Brauche ich OpenClaw und einen Provider?

Ja. Horizon ist eine Client-Oberfläche und braucht deshalb ein erreichbares Gateway, das dir gehört und das du freigibst. Talk benötigt zusätzlich einen konfigurierten Realtime-Provider. Home-, Kamera-, Medien- und Automationsansichten hängen von separaten Integrationen und den Fähigkeiten ab, die dein Gateway ausweist.

### Sind Home Assistant, Kamerasteuerung, Bluetooth, OTA oder Hermes enthalten?

Diese Dinge entstehen nicht automatisch durch die Installation von Horizon. Sie brauchen deine eigene Integration und Validierung. Bluetooth-Reconnect benötigt ein ausdrücklich gepaartes Ziel; Pi-OTA benötigt eine von dir bereitgestellte Manifest-URL und einen öffentlichen Schlüssel; öffentliche Android-Updates erfolgen manuell. Für Hermes gibt es keinen enthaltenen Adapter und keine Ende-zu-Ende-Validierung.

### Woher bekomme ich die Android-APK?

Nutze ausschließlich das passende [GitHub Release](https://github.com/The-Geek-Freaks/jarvis-horizon/releases/tag/v0.1.0-alpha.1) und prüfe danach `SHA256SUMS.txt` sowie `SIGNING-CERTIFICATE-SHA256.txt`. Installiere keine APK, die dir per Chat weitergeleitet wurde, von einem anderen Gerät kopiert wurde oder auf einem Mirror liegt.

### Kann ich vorhandene Gateway-Tokens oder Konfigurationen verwenden?

Verwende nur Zugangsdaten und Setup-Material, die dir gehören. Kopiere keine Tokens, Session-Keys, OTA-Werte, Bluetooth-Identitäten, Home-Assistant-Zugangsdaten, Raumaufnahmen, Kamerabilder oder private Konfiguration von einer anderen Installation.

### Wie melde ich ein Problem oder schlage eine Änderung vor?

Nutze die Bug- oder Feature-Vorlage des Repositories. Für Hardware-Berichte gibt es die Vorlage **Supported device report**: Beschreibe, was tatsächlich funktioniert hat, was nicht funktioniert hat und wie du es getestet hast. Bereinige Logs und veröffentliche keine privaten URLs, Tokens, Identitäten, Aufnahmen oder Kamerabilder.

### Gibt es eine Browser-Demo?

Die [Browser-Demo](https://the-geek-freaks.github.io/jarvis-horizon/) läuft mit lokalen synthetischen Daten. Sie hat keinen Login, nutzt kein Mikrofon und verbindet sich weder mit einem Live-Gateway noch mit einem Backend. Die Anzeige behält absichtlich deutsche Beschriftungen; die Website ist auf Englisch und Deutsch verfügbar.

### Wie kann ich mein eigenes Setup sicher zeigen?

Beginne mit [Zeig dein Setup](SHOWCASE.md). Dort steht, was du schwärzen solltest, wann Zustimmung nötig ist und wie du ein echtes Ergebnis beschreibst, ohne zu behaupten, dass es auf jedem Gerät funktioniert.
