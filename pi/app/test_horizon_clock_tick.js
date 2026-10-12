const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('horizon/js/app.js', 'utf8');
const RealDate = Date;
let now = new RealDate(2026, 9, 5, 23, 59, 59).getTime();
const intervals = new Map();
let nextTimer = 0;
let frameRequests = 0;

function FakeDate(...args) {
  return args.length ? new RealDate(...args) : new RealDate(now);
}
FakeDate.now = () => now;
FakeDate.parse = RealDate.parse;
FakeDate.UTC = RealDate.UTC;
FakeDate.prototype = RealDate.prototype;

function element() {
  return {
    textContent: '', hidden: false, style: {}, dataset: {}, firstChild: null,
    classList: {add() {}, remove() {}, toggle() {}, contains() { return false; }},
    querySelector() { return element(); }, querySelectorAll() { return []; },
  };
}

const elements = {'#horizon': element(), '#stage': element(), '#stage-body': element(), '#caption': element(), '#clock': element(), '#date': element(), '#seconds': element()};
const context = {
  Date: FakeDate, Math, JSON, Array, Number, String, RegExp, Object, console,
  document: {querySelector(selector) { return elements[selector] || element(); }, querySelectorAll() { return []; }},
  requestAnimationFrame() { frameRequests += 1; return frameRequests; }, cancelAnimationFrame() {},
  setTimeout() { return 1; }, clearTimeout() {},
  setInterval(fn, delay) { const id = ++nextTimer; intervals.set(id, {fn, delay}); return id; },
  clearInterval(id) { intervals.delete(id); },
};
context.window = context;
vm.runInNewContext(source, context);

const clockTick = [...intervals.values()].find(timer => timer.delay === 1000);
assert.ok(clockTick, 'clock text owns a dedicated one-second timer');
assert.equal(intervals.has(1), false, 'the superseded render-queue timer is cleared');
assert.equal(elements['#clock'].textContent, new RealDate(now).toLocaleTimeString('de-DE', {hour: '2-digit', minute: '2-digit'}));
const framesBeforeTick = frameRequests;

now += 1000;
clockTick.fn();
assert.equal(elements['#clock'].textContent, new RealDate(now).toLocaleTimeString('de-DE', {hour: '2-digit', minute: '2-digit'}), 'minute rolls forward without a Horizon update event');

now = new RealDate(2026, 9, 6, 0, 0, 1).getTime();
clockTick.fn();
assert.equal(elements['#clock'].textContent, new RealDate(now).toLocaleTimeString('de-DE', {hour: '2-digit', minute: '2-digit'}), 'midnight updates the clock text');
assert.equal(elements['#date'].textContent, new RealDate(now).toLocaleDateString('de-DE', {weekday: 'long', day: '2-digit', month: 'long'}), 'midnight updates the date text');
assert.equal(frameRequests, framesBeforeTick, 'the clock tick does not schedule a full Horizon render');

console.log('horizon clock text advances across minute and midnight without incoming updates');
