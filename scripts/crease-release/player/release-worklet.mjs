import { ReleaseReader } from './pcm-engine.mjs';

class CreaseReleaseProcessor extends AudioWorkletProcessor {
  constructor() {
    super(); this.reader = null; this.previousCount = 0; this.blocks = 0;
    this.port.onmessage = ({ data }) => {
      try {
        if (data.type === 'load') this.reader = new ReleaseReader(data.manifest, data.pcm);
        else if (data.type === 'on') this.reader?.start(data.id, data.mode, data.velocity ?? 1);
        else if (data.type === 'off') this.reader?.release(data.id);
        else if (data.type === 'stop') this.reader?.stop();
        const v = this.reader?.voices.find((v) => v.id === data.id);
        this.port.postMessage({ type: 'ack', action: data.type, command: data.command,
          frame: currentFrame, sourcePosition: v?.pos ?? 0, voices: this.reader?.voices.length ?? 0 });
      } catch (error) {
        this.reader = null;
        this.port.postMessage({ type: 'error', message: error.message });
      }
    };
  }
  process(inputs, outputs) {
    const channels = outputs[0];
    if (!channels?.length) return true;
    let count = 0;
    if (this.reader) count = this.reader.process(channels[0], sampleRate);
    else channels[0].fill(0);
    for (let i = 1; i < channels.length; i++) channels[i].set(channels[0]);
    if (count !== this.previousCount || (++this.blocks % 32 === 0 && count)) {
      this.port.postMessage({ type: 'state', voices: count, frame: currentFrame,
        peak: this.reader?.lastPeak ?? 0, quantumFrames: channels[0].length });
      this.previousCount = count;
    }
    return true;
  }
}
registerProcessor('crease-release-pcm', CreaseReleaseProcessor);
