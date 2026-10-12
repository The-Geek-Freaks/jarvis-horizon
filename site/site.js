'use strict';
(() => {
  const params = new URLSearchParams(location.search);
  const scenes = ['home', 'conversation', 'weather', 'activity', 'music', 'agenda', 'house', 'ambient'];
  let scene = scenes.includes(params.get('scene')) ? params.get('scene') : 'home';
  let lang = params.get('lang') === 'de' ? 'de' : 'en';
  let format = ['portrait', 'landscape'].includes(params.get('format')) ? params.get('format') : (innerWidth < 640 ? 'portrait' : 'landscape');
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  let motion = !reduced.matches, ready = false, loadTimer;
  const display = document.getElementById('display'), still = document.getElementById('still');
  const loading = document.getElementById('loading');
  const english = new Map(Array.from(document.querySelectorAll('[data-i18n]'), el => [el.dataset.i18n, el.innerHTML]));
  const german = {
    skip:'Direkt zur Demo',install:'Installieren',headline:'Gib deinem Assistenten<br>einen Platz im Raum.',intro:'Mach aus einem Android-Display oder Raspberry Pi eine sprachgesteuerte Oberfläche für OpenClaw. Sieh, wie dein Assistent zuhört, arbeitet und dein Zuhause anzeigt.',explore:'Oberfläche entdecken',make:'Selbst einrichten ↗',alpha:'Open Source · Öffentliche Alpha · Dein eigenes Gateway',demoTitle:'Ein Display für jeden Moment.',sample:'Interaktive Demo · Beispieldaten',home:'Übersicht',conversation:'Gespräch',weather:'Wetter',activity:'Agentenaktivität',music:'Musik',agenda:'Termine',house:'Zuhause',ambient:'Nachtansicht',loading:'Horizon-Oberfläche wird geladen…',landscape:'Querformat',portrait:'Hochformat',pause:'Animation pausieren',share:'Link zu dieser Ansicht kopieren',boundary:'Echte Oberfläche, simulierte Ereignisse. Kein Mikrofon, Konto, Kamerastream oder Zugriff auf dein Zuhause. Die Alpha verwendet deutsche Beschriftungen; Musik- und Sprachpegel sind ohne Ton simuliert.',storyTitle:'Mehr Präsenz.<br>Weniger vergessene Hardware.',storyText:'Das Tablet in der Schublade. Ein Display hinter dem Spiegel. Ein Bildschirm in der Küche. Horizon gibt deinem vorhandenen Assistenten einen sichtbaren Platz – mit lokaler Wakeword-Erkennung und Kontext aus deinem eigenen Backend.',compat:'Gerätekompatibilität prüfen ↗',workTitle:'Sieh, was gerade passiert',workText:'Tool- und Unteragentenereignisse werden als lesbare Aktivität sichtbar. Dein Gateway steuert die Ausführung; das Display zeigt die empfangenen Ereignisse.',roomTitle:'Für den Raum gestaltet',roomText:'Hoch- und Querformat, eine orbitale Sprachvisualisierung, Kontextansichten und eine bewegte Ambient-Uhr.',ownTitle:'Dein Assistent. Dein Setup.',ownText:'Gateway, Identität, Provider und Integrationen bleiben bei dir. Die lokale Wakeword-Erkennung öffnet den von dir konfigurierten Sprachpfad.',installTitle:'Wähle das Display.<br>Bring dein Gateway mit.',installIntro:'Für die Installation brauchst du ein eigenes OpenClaw Gateway, die Gerätekopplung und einen Realtime-Provider für Talk. Die Demo kannst du ohne all das ausprobieren.',androidText:'Ein ARM-Handy, Tablet oder modifiziertes Display ab Android 8.1. Starte mit der signierten Alpha-APK und der Anleitung zur Kopplung.',androidInstall:'Auf Android installieren ↗',androidNote:'Auf der originalen Echo-Firmware lässt sich diese APK nicht installieren. Öffentliche Updates erfolgen manuell.',piText:'Ein Display- oder Spiegel-Kiosk auf 64-Bit-DietPi/Debian. Ergänze ein Mikrofon und richte die Tonausgabe für deinen Raum ein.',piInstall:'Raspberry Pi einrichten ↗',piNote:'Display, Mikrofon und Lautsprecher müssen auf deiner Hardware geprüft werden.',release:'v0.1.0-alpha.1 · Downloads, Prüfsummen & Quellcode',hermes:'OpenClaw-Backend. Noch kein Hermes-Adapter enthalten.',communityTitle:'Zeig uns, wo<br>Horizon hingehört.',communityText:'Ein guter Gerätebericht hilft anderen, ihre Hardware weiterzuverwenden. Teile dein Setup, gemessene Leistung und das, was noch verbessert werden muss.',setup:'Dein Setup vorstellen ↗',device:'Getestetes Gerät melden ↗',faq:'Häufige Fragen ↗',kit:'Material zum Teilen ↗',footer:'Unabhängiges Community-Projekt. Keine Analyse-Tracker, Cookies oder Anmeldung in dieser Demo.',credits:'Mitwirkende & Lizenzen'
  };
  const descriptions = {
    en:{home:'Time, your next appointment and the orb. A calm overview for a display that stays in the room.',conversation:'A simulated conversation moves from listening to working to speaking. The orb follows generated voice levels; this page never listens or plays audio.',weather:'A detailed weather view with a forecast chart. These are fixed sample values, not a live forecast.',activity:'Watch sample tool events arrive one by one. No shell command is executed; the real activity renderer visualizes the sequence.',music:'Sample track information and animated levels. No Spotify account, playback or copyrighted recording is used.',agenda:'A sample day, legible at a glance. In your installation, your configured backend supplies the appointments.',house:'Room states in context. These values are illustrative; the demo cannot control devices.',ambient:'The actual galactic screensaver: a moving clock, quiet particles and the next appointment.'},
    de:{home:'Uhrzeit, nächster Termin und Orb. Eine ruhige Übersicht für ein Display, das im Raum bleibt.',conversation:'Ein simuliertes Gespräch wechselt von Zuhören zu Arbeiten zu Sprechen. Der Orb folgt erzeugten Sprachpegeln; diese Seite hört niemals zu und spielt keinen Ton ab.',weather:'Eine detaillierte Wetteransicht mit Verlaufskurve. Feste Beispieldaten, keine Live-Vorhersage.',activity:'Beispiel-Toolereignisse erscheinen nacheinander. Es wird kein Shell-Befehl ausgeführt; die echte Aktivitätsansicht zeigt den Ablauf.',music:'Beispiel-Titel und animierte Pegel. Kein Spotify-Konto, keine Wiedergabe und keine geschützte Tonaufnahme.',agenda:'Ein Beispieltag auf einen Blick. In deiner Installation liefert dein konfiguriertes Backend die Termine.',house:'Raumzustände im Kontext. Die Werte sind Beispiele; diese Demo kann keine Geräte steuern.',ambient:'Der echte galaktische Bildschirmschoner: eine bewegte Uhr, ruhige Partikel und der nächste Termin.'}
  };
  const images = {home:'home',conversation:'home',weather:'weather',activity:'activity',music:'music',agenda:'agenda',house:'home-control',ambient:'ambient'};
  function urlSync() {
    const url = new URL(location.href);
    url.search = new URLSearchParams({scene,format,...(lang==='de'?{lang}: {})}).toString();
    history.replaceState(null,'',url);
  }
  function labels() {
    document.documentElement.lang=lang;
    document.querySelectorAll('[data-i18n]').forEach(el => {
      // Only authored, static translations are inserted, never URL or message data.
      el.innerHTML=lang==='de'?german[el.dataset.i18n]:english.get(el.dataset.i18n);
    });
    document.getElementById('language').textContent=lang==='de'?'EN':'DE';
    document.getElementById('language').setAttribute('aria-label',lang==='de'?'Switch to English':'Auf Deutsch wechseln');
    document.getElementById('scene-caption').textContent=descriptions[lang][scene];
    document.querySelectorAll('[data-scene]').forEach(el=>el.setAttribute('aria-pressed',String(el.dataset.scene===scene)));
    document.querySelectorAll('[data-format]').forEach(el=>el.setAttribute('aria-pressed',String(el.dataset.format===format)));
    document.getElementById('motion').textContent=motion?(lang==='de'?'Animation pausieren':'Pause motion'):(lang==='de'?'Animation starten':'Play motion');
    document.getElementById('motion').setAttribute('aria-pressed',String(motion));
    const base='https://github.com/The-Geek-Freaks/jarvis-horizon/blob/main/docs/';
    document.getElementById('android-guide').href=base+(lang==='de'?'INSTALL.de.md#erste-android-verbindung':'INSTALL.md#first-android-connection');
    document.getElementById('pi-guide').href=base+(lang==='de'?'INSTALL.de.md':'INSTALL.md')+'#raspberry-pi-4';
    document.getElementById('display-frame').classList.toggle('portrait',format==='portrait');
  }
  function sendScene() { if(ready)display.contentWindow.postMessage({type:'horizon-demo-scene',scene},location.origin); }
  function render() {
    labels();urlSync();clearTimeout(loadTimer);
    document.getElementById('copy-status').textContent='';
    if(!motion) {
      ready=false;display.removeAttribute('src');display.hidden=true;still.hidden=false;loading.hidden=true;
      const imageName=format==='portrait' && scene==='home'?'portrait':images[scene];
      still.src='assets/showcase-'+imageName+'.png';
      still.alt=lang==='de'?'Statische Horizon-Referenzansicht mit Beispieldaten':'Static Horizon reference view with sample data';
      document.getElementById('scene-caption').textContent+=(lang==='de'?' Animation pausiert; statische Referenzansicht.':' Motion paused; static reference view.');
    } else {
      display.hidden=false;still.hidden=true;
      if(!display.hasAttribute('src')) {
        ready=false;loading.hidden=false;loading.textContent=lang==='de'?german.loading:english.get('loading');
        display.src='horizon/index.html?layout=adaptive&scene='+scene;
        loadTimer=setTimeout(()=>{if(!ready){loading.textContent=lang==='de'?'Demo konnte nicht geladen werden. Statische Ansicht ist verfügbar.':'The demo could not load. A static view is available.';motion=false;render();document.getElementById('copy-status').textContent=lang==='de'?'Demo konnte nicht geladen werden. Mit „Animation starten“ erneut versuchen.':'The demo could not load. Select “Play motion” to retry.';}},12000);
      } else sendScene();
    }
  }
  window.addEventListener('message',event=>{
    if(event.source!==display.contentWindow||event.origin!==location.origin||event.data?.type!=='horizon-demo-ready')return;
    ready=true;clearTimeout(loadTimer);loading.hidden=true;sendScene();
  });
  document.querySelectorAll('[data-scene]').forEach(el=>el.addEventListener('click',()=>{scene=el.dataset.scene;render();}));
  document.querySelectorAll('[data-format]').forEach(el=>el.addEventListener('click',()=>{format=el.dataset.format;render();}));
  document.getElementById('language').addEventListener('click',()=>{lang=lang==='en'?'de':'en';render();});
  document.getElementById('motion').addEventListener('click',()=>{motion=!motion;render();});
  reduced.addEventListener('change',event=>{motion=!event.matches;render();});
  document.getElementById('share').addEventListener('click',async()=>{
    const status=document.getElementById('copy-status');
    try { await navigator.clipboard.writeText(location.href);status.textContent=lang==='de'?'Link zu dieser Ansicht kopiert.':'Link to this view copied.'; }
    catch { status.textContent=(lang==='de'?'Link zum Kopieren: ':'Copy this link: ')+location.href; }
  });
  render();
})();
