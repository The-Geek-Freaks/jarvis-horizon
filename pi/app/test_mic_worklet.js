const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('horizon/mic-worklet.js', 'utf8');

function captureOneSecond(inputRate) {
  let Processor;
  const packets = [];
  class WorkletBase {
    constructor() {
      this.port = { postMessage(message) { packets.push(message); } };
    }
  }
  vm.runInNewContext(source, {
    AudioWorkletProcessor: WorkletBase,
    Float32Array,
    Int16Array,
    Math,
    sampleRate: inputRate,
    registerProcessor(_name, processor) { Processor = processor; },
  });
  const processor = new Processor();
  for (let offset = 0; offset < inputRate; offset += 128) {
    const block = new Float32Array(Math.min(128, inputRate - offset));
    block.fill(0.25);
    processor.process([[block]]);
  }
  return packets;
}

for (const rate of [48000, 44100]) {
  const packets = captureOneSecond(rate);
  assert.ok(Math.abs(packets.length - 25) <= 1, `${rate} Hz emitted ${packets.length} packets`);
  assert.equal(packets.length, 25, `${rate} Hz must not drift across one second`);
  for (const packet of packets) {
    assert.equal(packet.pcm.byteLength, 640 * Int16Array.BYTES_PER_ELEMENT);
    assert.ok(packet.level > 0);
  }
}

console.log('mic-worklet 40-ms packet and input-rate drift checks passed');
