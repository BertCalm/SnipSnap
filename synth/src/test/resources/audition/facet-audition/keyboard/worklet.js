import { FacetKeyboardEngine } from './engine.js';

class FacetLiveProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this.lastStatusFrame=0;this.changed=true;
    this.engine=new FacetKeyboardEngine({geometry:options.processorOptions.geometry,sampleRate,maxVoices:4,
      onEvent:event=>{this.changed=true;this.port.postMessage(event);}});
    this.port.onmessage=event=>{
      try { this.engine.handleMessage(event.data);this.changed=true; }
      catch(error){this.port.postMessage({type:'error',message:String(error?.message||error)});this.engine.panic();}
    };
    this.port.postMessage({type:'ready',sampleRate,maxVoices:4});
  }
  process(_inputs,outputs) {
    const channels=outputs[0];if(!channels?.[0])return true;
    this.engine.process(channels[0],channels[1]||null);
    if(this.changed||this.engine.frame-this.lastStatusFrame>=sampleRate/10) {
      this.port.postMessage({type:'status',...this.engine.getState()});
      this.lastStatusFrame=this.engine.frame;this.changed=false;
    }
    return true;
  }
}

registerProcessor('facet-live',FacetLiveProcessor);
