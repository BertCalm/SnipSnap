'use strict';
// Sample-scheduled loops; transport envelopes never alter the rendered WAVs.
window.HeldTransport=(()=>{
 const RAMP=.012,LEAD=.004;
 function pcmBuffer(context,input){
  const v=new DataView(input.buffer,input.byteOffset,input.byteLength),tag=p=>String.fromCharCode(...input.subarray(p,p+4));
  if(tag(0)!=='RIFF'||tag(8)!=='WAVE')throw Error('Expected WAV');
  let rate=0,data=-1,length=0,format=false;
  for(let p=12;p+8<=input.length;){const size=v.getUint32(p+4,true);if(p+8+size>input.length)throw Error('Truncated WAV');if(tag(p)==='fmt '){rate=v.getUint32(p+12,true);format=v.getUint16(p+8,true)===1&&v.getUint16(p+10,true)===1&&v.getUint16(p+22,true)===24}if(tag(p)==='data'){data=p+8;length=size}p+=8+size+(size&1)}
  if(!format||rate!==44100||data<0||length%3)throw Error('Expected mono PCM24 / 44.1 kHz');
  // Preserve original frames even on a 48 kHz output device. decodeAudioData
  // resampling an isolated cycle can pad its edges before it is looped.
  const buffer=context.createBuffer(1,length/3,rate),out=buffer.getChannelData(0);
  for(let i=0,p=data;i<out.length;i++,p+=3){let q=input[p]|input[p+1]<<8|input[p+2]<<16;if(q&0x800000)q-=0x1000000;out[i]=q/8388608}
  return buffer;
 }
 function gainAt(voice,t){
  if(voice.fade&&t>=voice.fade.from)return voice.fade.value*Math.max(0,1-(t-voice.fade.from)/(voice.fade.to-voice.fade.from));
  return Math.min(1,Math.max(0,(t-voice.start)/voice.ramp))*Math.min(1,Math.max(0,(voice.end-t)/voice.ramp));
 }
 function schedule(context,buffer,{when=context.currentTime,offset=0,loop=false,destination=context.destination,ramp=RAMP}={}){
  const source=context.createBufferSource(),gain=context.createGain();source.buffer=buffer;source.loop=loop;source.loopStart=0;source.loopEnd=buffer.duration;source.connect(gain);gain.connect(destination);
  const end=loop?Infinity:when+buffer.duration-offset;ramp=Math.min(ramp,(end-when)/2);
  gain.gain.setValueAtTime(0,when);gain.gain.linearRampToValueAtTime(1,when+ramp);
  if(Number.isFinite(end)){gain.gain.setValueAtTime(1,Math.max(when+ramp,end-ramp));gain.gain.linearRampToValueAtTime(0,end)}
  source.start(when,offset);
  return{source,gain,start:when,end,ramp,fade:null};
 }
 function retire(voice,when){
  if(voice.end<=when)return;
  const value=gainAt(voice,when),end=Math.min(when+voice.ramp,voice.end);
  const param=voice.gain.gain;
  if(param.cancelAndHoldAtTime){param.cancelAndHoldAtTime(when);param.setValueAtTime(value,when)}
  else{param.cancelScheduledValues(when);param.linearRampToValueAtTime(value,when)}
  param.linearRampToValueAtTime(0,end);voice.fade={from:when,to:end,value};voice.source.stop(end);
 }
 class Player extends EventTarget{
  constructor(){super();this.src='';this.loop=false;this.readyState=0;this._buffer=null;this._context=null;this._offset=0;this._playing=false;this._playId=0;this._voices=new Set();this._active=null;this._started=0;setInterval(()=>{if(this._playing)this.dispatchEvent(new Event('timeupdate'))},50)}
  context(){if(!this._context){const C=window.AudioContext||window.webkitAudioContext;try{this._context=new C({sampleRate:44100})}catch{this._context=new C()}}return this._context}
  unlock(){return this.context().resume()}
  get duration(){return this._buffer?.duration??NaN}
  get paused(){return !this._playing}
  get currentTime(){if(!this._playing)return this._offset;const time=this._offset+Math.max(0,this.context().currentTime-this._started);return this.loop?time%this.duration:Math.min(time,this.duration)}
  set currentTime(value){const offset=Math.max(0,Math.min(Number.isFinite(this.duration)?this.duration:0,value));this._offset=offset;if(this._playing)this._start(offset);this.dispatchEvent(new Event('timeupdate'))}
  async prepare(src){return pcmBuffer(this.context(),await HeldDownloads.bytes(src))}
  async use(buffer,{src,loop,offset,autoplay}){const id=++this._playId;if(autoplay)await this.unlock();if(id!==this._playId)return false;this.src=src;this.loop=loop;this._buffer=buffer;this.readyState=1;this._offset=offset;if(autoplay){this._playing=true;this._start(offset)}else{this._playing=false;if(this._context)for(const voice of this._voices)retire(voice,this._context.currentTime+LEAD)}this.dispatchEvent(new Event('loadedmetadata'));this.dispatchEvent(new Event(autoplay?'play':'pause'));this.dispatchEvent(new Event('timeupdate'));return true}
  async play(){const id=++this._playId;await this.unlock();if(id!==this._playId||!this._buffer||this._playing)return;if(this._offset>=this.duration)this._offset=0;this._playing=true;this._start(this._offset);this.dispatchEvent(new Event('play'))}
  _start(offset){const context=this.context(),when=context.currentTime+LEAD;for(const voice of this._voices)retire(voice,when);const voice=schedule(context,this._buffer,{when,offset,loop:this.loop});this._voices.add(voice);this._active=voice;this._offset=offset;this._started=when;voice.source.onended=()=>{this._voices.delete(voice);voice.source.disconnect();voice.gain.disconnect();if(this._active===voice&&this._playing){this._playing=false;this._offset=this.duration;this.dispatchEvent(new Event('ended'));this.dispatchEvent(new Event('timeupdate'))}}}
  pause(){++this._playId;const offset=this.currentTime;this._playing=false;this._offset=offset;if(this._context)for(const voice of this._voices)retire(voice,this._context.currentTime+LEAD);this._active=null;this.dispatchEvent(new Event('pause'));this.dispatchEvent(new Event('timeupdate'))}
 }
 return{Player,pcmBuffer,schedule,retire,gainAt,RAMP};
})();
