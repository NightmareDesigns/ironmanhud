'use strict';
class PCMProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.samples = [];
    this.position = 0;
  }
  process(inputs) {
    const input = inputs[0]?.[0];
    if (!input) return true;
    const step = sampleRate / 16000;
    while (this.position < input.length) {
      this.samples.push(Math.max(-1, Math.min(1, input[Math.floor(this.position)])));
      this.position += step;
      if (this.samples.length === 1600) {
        const buffer = new ArrayBuffer(3200);
        const view = new DataView(buffer);
        this.samples.forEach((sample, i) => view.setInt16(i * 2, Math.round(sample * 32767), true));
        this.port.postMessage(buffer, [buffer]);
        this.samples = [];
      }
    }
    this.position -= input.length;
    return true;
  }
}
registerProcessor('pcm-capture', PCMProcessor);
