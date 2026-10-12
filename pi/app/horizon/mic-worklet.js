/* Fixed 40-ms, 16-kHz PCM16LE packets; keeps capture at 25 messages per second. */
class JarvisMicProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.targetRate = 16000;
    this.packetSamples = 640;
    // A retained input buffer avoids allocation and copying for every 128-sample render quantum.
    this.input = new Float32Array(4096);
    this.inputLength = 0;
  }

  process(inputs) {
    const source = inputs[0] && inputs[0][0];
    if (!source || !source.length) return true;
    if (this.inputLength + source.length > this.input.length) return true;

    this.input.set(source, this.inputLength);
    this.inputLength += source.length;
    const ratio = sampleRate / this.targetRate;
    // Each packet consumes this many source samples; wait for all of them so
    // copyWithin never advances beyond the retained input.
    const required = Math.floor(this.packetSamples * ratio);

    while (this.inputLength >= required) {
      const pcm = new Int16Array(this.packetSamples);
      let energy = 0;
      for (let index = 0; index < this.packetSamples; index++) {
        const at = index * ratio;
        const left = at | 0;
        const right = Math.min(left + 1, this.inputLength - 1);
        const sample = this.input[left] + (this.input[right] - this.input[left]) * (at - left);
        energy += sample * sample;
        pcm[index] = Math.max(-1, Math.min(1, sample)) * 32767;
      }

      const consumed = Math.floor(this.packetSamples * ratio);
      this.input.copyWithin(0, consumed, this.inputLength);
      this.inputLength -= consumed;
      this.port.postMessage({
        pcm: pcm.buffer,
        level: Math.min(1, Math.sqrt(energy / this.packetSamples) * 3.2),
      }, [pcm.buffer]);
    }
    return true;
  }
}

registerProcessor('jarvis-mic', JarvisMicProcessor);
