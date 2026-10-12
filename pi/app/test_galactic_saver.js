const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('horizon/js/galactic-saver.js', 'utf8');
const listeners = new Map();
const timers = new Map();
let timerId = 0;
const child = () => ({ textContent: '', hidden: false, dataset: {} });
const event = Object.assign(child(), { querySelector(selector) { return this.children[selector]; }, children: { time: child(), strong: child(), small: child() } });
const ctx = { clearRect() {}, fillRect() {}, beginPath() {}, ellipse() {}, stroke() {}, setLineDash() {}, moveTo() {}, lineTo() {}, arc() {}, fill() {}, fillStyle: '', strokeStyle: '', lineWidth: 0 };
const canvas = { width: 0, height: 0, getContext() { return ctx; }, getBoundingClientRect() { return { width: 960, height: 480 }; } };
const elements = new Map([
  ['#galactic-saver', child()], ['.galactic-saver-copy', child()], ['#galactic-saver-canvas', canvas], ['#galactic-saver-time', child()], ['#galactic-saver-date', child()], ['#galactic-saver-event', event], ['#galactic-saver-empty', child()],
]);
const document = { hidden: false, querySelector(selector) { return elements.get(selector) || null; }, addEventListener(type, listener) { listeners.set(type, listener); }, removeEventListener(type) { listeners.delete(type); } };
const window = { setTimeout(callback) { const id = ++timerId; timers.set(id, callback); return id; }, clearTimeout(id) { timers.delete(id); }, matchMedia() { return { matches: false }; } };
vm.runInNewContext(source, { window, document, Date, Math, String, Number, Object, console });

const saver = window.JarvisGalacticSaver.create();
saver.update({ night: true, nextEvent: { time: '09:30', title: 'Stand-up', meta: 'Küche' } });
assert.equal(saver.isRunning(), true, 'night starts one stable saver loop');
assert.equal(timers.size, 1, 'the saver schedules one capped frame');
assert.equal(canvas.width, 960, 'canvas stays at CSS pixels instead of device DPR');
assert.equal(event.hidden, false, 'real next appointment is displayed');
assert.equal(event.children.strong.textContent, 'Stand-up');
assert.match(event.dataset.drift, /^[0-2]$/, 'event drift stays in bounded positions');
assert.match(elements.get('.galactic-saver-copy').dataset.drift, /^[0-2]$/, 'clock copy receives a bounded drift position');
assert.match(source, /for \(i = 0; i < 3; i\+\+\).*radius = 8/, 'the saver paints three coherent spiral arms');
assert.match(source, /rgba\(255,226,174,.72\)/, 'the galaxy retains its warm core');
saver.update({ night: true, nextEvent: null });
assert.equal(timers.size, 1, 'state updates do not create another loop');
assert.equal(event.hidden, true, 'missing appointment clears the appointment block');
assert.equal(elements.get('#galactic-saver-empty').hidden, false, 'the no-event state is explicit');
document.hidden = true;
listeners.get('visibilitychange')();
assert.equal(saver.isRunning(), false, 'hidden documents stop the scheduler');
assert.equal(timers.size, 0, 'hide clears the pending frame');
document.hidden = false;
saver.setPaused(true);
saver.update({ night: true });
assert.equal(saver.isRunning(), false, 'external pause keeps the saver stopped');
saver.setPaused(false);
assert.equal(saver.isRunning(), true, 'unpausing resumes one saver loop');
assert.equal(timers.size, 1);

console.log('galactic saver is capped, calendar-backed, and idle-safe');
