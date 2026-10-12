/* Jarvis Horizon night saver: capped Canvas2D motion with real local state only. */
(function () {
  'use strict';
  function clean(value) { return String(value == null ? '' : value).replace(/[\u0000-\u001f<>]/g, ' ').trim(); }
  function create(options) {
    options = options || {};
    var doc = options.document || document, win = options.window || window, canvas = options.canvas || doc.querySelector('#galactic-saver-canvas');
    var saver = options.saver || doc.querySelector('#galactic-saver'), copy = options.copy || doc.querySelector('.galactic-saver-copy'), time = options.time || doc.querySelector('#galactic-saver-time'), date = options.date || doc.querySelector('#galactic-saver-date'), event = options.event || doc.querySelector('#galactic-saver-event'), empty = options.empty || doc.querySelector('#galactic-saver-empty');
    var eventTime = event && event.querySelector('time'), eventTitle = event && event.querySelector('strong'), eventMeta = event && event.querySelector('small');
    var context = canvas && canvas.getContext ? canvas.getContext('2d') : null, timer = 0, running = false, destroyed = false, externalPaused = false, state = {}, lastMinute = '', lastDrift = -1, stars = [];
    var reduced = !!(win.matchMedia && win.matchMedia('(prefers-reduced-motion: reduce)').matches), fps = reduced ? 2 : 8, frameMs = 1000 / fps;
    function seed() { var n = reduced ? 28 : 70, i, x = 17; for (i = 0; i < n; i++) { x = (x * 48271) % 2147483647; var a = x / 2147483647; x = (x * 48271) % 2147483647; var b = x / 2147483647; x = (x * 48271) % 2147483647; stars.push({ radius:.035 + b * .43, arm:i % 3, jitter:(a - .5) * .075, r:.5 + (x / 2147483647) * 1.5, phase:a * 6.28, gold:i % 9 === 0 }); } }
    function size() { if (!canvas || !context) return { w:0,h:0 }; var rect = canvas.getBoundingClientRect ? canvas.getBoundingClientRect() : { width:960,height:480 }, w = Math.max(1, Math.round(rect.width || 960)), h = Math.max(1, Math.round(rect.height || 480)); if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; } return { w:w,h:h }; }
    function paint(now) { if (!context) return; var box = size(), w = box.w, h = box.h, i, j, s, pulse, x, y, orbit = now / (reduced ? 700000 : 350000), driftX = Math.sin(orbit) * w * .075, driftY = Math.cos(orbit * .81) * h * .065, cx = w * .56 + driftX, cy = h * .49 + driftY, scale = Math.min(w, h), theta, radius, arm, color;
      context.clearRect(0, 0, w, h); context.fillStyle = '#02050c'; context.fillRect(0, 0, w, h); context.lineWidth = 1;
      for (i = 0; i < 3; i++) { color = i === 1 ? 'rgba(246,200,120,.32)' : 'rgba(91,174,255,.31)'; context.strokeStyle = color; context.beginPath(); for (j = 0; j <= 34; j++) { radius = 8 + j / 34 * scale * .42; theta = i * 2.094 + j / 34 * 4.35 + orbit * .14; x = cx + Math.cos(theta) * radius; y = cy + Math.sin(theta) * radius * .38; if (j) context.lineTo(x, y); else context.moveTo(x, y); } context.stroke(); }
      context.strokeStyle = 'rgba(151,205,255,.18)'; context.setLineDash([2, 11]); context.beginPath(); context.ellipse(cx, cy, scale * .31, scale * .115, -.22 + orbit * .04, 0, Math.PI * 2); context.stroke(); context.setLineDash([]);
      for (i = 0; i < stars.length; i++) { s = stars[i]; arm = s.arm * 2.094 + s.radius * 10.4 + orbit * .14; radius = s.radius * scale; x = cx + Math.cos(arm) * radius + Math.sin(s.phase + orbit) * scale * s.jitter; y = cy + Math.sin(arm) * radius * .38 + Math.cos(s.phase + orbit) * scale * s.jitter * .32; pulse = .42 + .58 * (Math.sin(now / 2400 + s.phase) + 1) / 2; context.fillStyle = s.gold ? 'rgba(255,211,139,' + (.32 + pulse * .52) + ')' : 'rgba(174,218,255,' + (.2 + pulse * .58) + ')'; context.fillRect(Math.round(x), Math.round(y), s.r, s.r); }
      context.fillStyle = 'rgba(246,200,120,.16)'; context.beginPath(); context.arc(cx, cy, 18, 0, Math.PI * 2); context.fill(); context.fillStyle = 'rgba(255,226,174,.72)'; context.beginPath(); context.arc(cx, cy, 4, 0, Math.PI * 2); context.fill();
    }
    function format() { var now = new Date(), minute = now.getHours() + ':' + now.getMinutes(); if (minute !== lastMinute) { lastMinute = minute; if (time) time.textContent = now.toLocaleTimeString('de-DE', { hour:'2-digit', minute:'2-digit' }); if (date) date.textContent = now.toLocaleDateString('de-DE', { weekday:'long', day:'numeric', month:'long' }); } }
    function renderEvent() { var next = state && state.nextEvent || {}, title = clean(next.title), has = !!title; if (event) event.hidden = !has; if (empty) empty.hidden = has; if (!has) return; if (eventTime) eventTime.textContent = clean(next.time); if (eventTitle) eventTitle.textContent = title; if (eventMeta) eventMeta.textContent = clean(next.meta); }
    function drift(now) { var phase = Math.floor(now / (reduced ? 90000 : 45000)) % 3; if (phase === lastDrift) return; lastDrift = phase; if (copy) copy.dataset.drift = String(phase); if (event) event.dataset.drift = String((phase + 1) % 3); if (empty) empty.dataset.drift = String((phase + 1) % 3); }
    function eligible() { return !destroyed && !!state.night && !externalPaused && !doc.hidden; }
    function tick() { timer = 0; if (!eligible()) { stop(); return; } var now = Date.now(); format(); drift(now); paint(now); timer = win.setTimeout(tick, frameMs); }
    function start() { if (!eligible() || running) return; running = true; if (saver) saver.hidden = false; tick(); }
    function stop() { if (timer) { win.clearTimeout(timer); timer = 0; } running = false; if (saver) saver.hidden = true; }
    function sync(next) { state = next || {}; format(); renderEvent(); if (eligible()) start(); else stop(); }
    function setPaused(value) { externalPaused = !!value; if (eligible()) start(); else stop(); }
    function visibility() { if (eligible()) start(); else stop(); }
    seed(); doc.addEventListener('visibilitychange', visibility);
    return { update:sync, setPaused:setPaused, destroy:function(){ destroyed = true; stop(); doc.removeEventListener('visibilitychange', visibility); }, isRunning:function(){ return running; }, frameMs:frameMs };
  }
  window.JarvisGalacticSaver = { create:create };
  if (!window.JarvisHorizon) return;
  var saver = create({}), horizon = window.JarvisHorizon, update = horizon.update, setPaused = horizon.setPaused || function () {};
  function sync() { saver.update(horizon.debugState ? horizon.debugState() : {}); }
  horizon.update = function (next) { update(next); sync(); };
  horizon.setPaused = function (value) { setPaused(value); saver.setPaused(value); sync(); };
  var destroy = horizon.destroy;
  horizon.destroy = function () { saver.destroy(); destroy(); };
  sync();
}());
