const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('horizon/js/jarvis-orb.js', 'utf8');

function createGl() {
  const gl = {
    VERTEX_SHADER: 1, FRAGMENT_SHADER: 2, COMPILE_STATUS: 3, ARRAY_BUFFER: 4,
    STATIC_DRAW: 5, BLEND: 6, DEPTH_TEST: 7, COLOR_BUFFER_BIT: 8,
    TRIANGLE_STRIP: 9, LINES: 10, POINTS: 11, LINE_STRIP: 12,
    ONE: 13, ONE_MINUS_SRC_ALPHA: 14, FLOAT: 15,
  };
  for (const name of [
    'createShader', 'createProgram', 'createBuffer', 'getUniformLocation',
    'getExtension',
  ]) gl[name] = () => ({});
  for (const name of [
    'shaderSource', 'compileShader', 'attachShader', 'bindAttribLocation', 'linkProgram',
    'enable', 'disable', 'enableVertexAttribArray', 'bindBuffer', 'bufferData', 'viewport',
    'vertexAttribPointer', 'clearColor', 'clear', 'useProgram', 'blendFunc', 'uniform1f',
    'uniform3fv', 'uniformMatrix3fv', 'drawArrays',
  ]) gl[name] = () => {};
  gl.getShaderParameter = () => true;
  gl.getShaderInfoLog = () => '';
  return gl;
}

function boot() {
  let now = 0;
  let nextId = 0;
  const rafs = new Map();
  const timers = new Map();
  const frames = [];
  const context = {
    Math, Float32Array, console,
    document: {hidden: false, addEventListener() {}, removeEventListener() {}},
    requestAnimationFrame(fn) { const id = ++nextId; rafs.set(id, {fn, due: now + 1000 / 60}); return id; },
    cancelAnimationFrame(id) { rafs.delete(id); },
  };
  context.window = context;
  context.window.devicePixelRatio = 1;
  context.window.setTimeout = (fn, delay) => { const id = ++nextId; timers.set(id, {fn, due: now + delay}); return id; };
  context.window.clearTimeout = id => timers.delete(id);
  const canvas = {
    clientWidth: 360, clientHeight: 360, width: 0, height: 0, style: {},
    getContext() { return createGl(); }, addEventListener() {}, removeEventListener() {},
  };
  vm.runInNewContext(source, context);
  const orb = context.window.JarvisOrb.create(canvas, {onFrame: time => frames.push(time)});

  function stepTo(target, levelEveryMs) {
    let nextLevel = levelEveryMs ? Math.ceil(now / levelEveryMs) * levelEveryMs : Infinity;
    if (nextLevel === now) nextLevel += levelEveryMs;
    while (now < target) {
      let event = null;
      for (const [id, item] of rafs) if (!event || item.due < event.due) event = {kind: 'raf', id, ...item};
      for (const [id, item] of timers) if (!event || item.due < event.due) event = {kind: 'timer', id, ...item};
      const due = Math.min(event ? event.due : Infinity, nextLevel, target);
      now = due;
      if (now === nextLevel) { orb.setLevel(.06); nextLevel += levelEveryMs; continue; }
      if (!event || now === target) break;
      if (event.kind === 'raf') rafs.delete(event.id); else timers.delete(event.id);
      event.fn(now);
    }
  }
  return {orb, frames, timers, stepTo, get now() { return now; }};
}

const idle = boot();
// The microphone worklet reports residual RMS every 40 ms even while the UI is idle.
idle.stepTo(5000, 40);
const settledFrames = idle.frames.filter(time => time >= 4000);
assert.ok(idle.timers.size > 0, 'idle keeps using its timer-backed 30 fps cadence with residual RMS');
assert.ok(settledFrames.length <= 30, `idle rendered ${settledFrames.length} frames in the final second`);

const beforeSpeech = idle.frames.length;
idle.orb.setState('speaking');
idle.stepTo(idle.now + 250, 40);
assert.ok(idle.frames.length - beforeSpeech >= 12, 'speaking stays on the responsive animation clock');

console.log('jarvis orb preserves idle 30 fps cadence while speaking remains responsive');
