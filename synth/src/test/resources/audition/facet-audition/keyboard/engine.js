// Stateful port of FacetLightStudy.kt. Geometry is exported from the Kotlin renderer.
// No Web Audio dependencies: the same core runs in an AudioWorklet and in Node.
const F = Math.fround;
const TAU = 2 * Math.PI;
const MODES = 16;
const GROUPS = 8;
const OVERSAMPLE = 4;
const NOISE_RATE = 44100;
const CALIBRATION = { BLOOM: { glass: 7.48, air: 5.99 }, SHIMMER: { glass: 4.21, air: 3.27 } };
export const OUTPUT_HEADROOM = 0.75;
export const MAX_VOICES = 4;
const clamp = (x, lo, hi) => Math.max(lo, Math.min(hi, x));
const smooth = x => { x = clamp(x, 0, 1); return x * x * (3 - 2 * x); };
const finite = (x, fallback) => Number.isFinite(Number(x)) ? Number(x) : fallback;
const voiceName = name => name === 'SHIMMER' ? 'SHIMMER' : 'BLOOM';
const textureName = name => name === 'air' || name === 'AIRY_RESONANCE' ? 'air' : 'glass';

export function javaStringHash(value) {
  let h = 0;
  for (let i = 0; i < String(value).length; i++) h = (Math.imul(h, 31) + String(value).charCodeAt(i)) | 0;
  return h;
}
export function seedFor(...parts) {
  let h = 17;
  for (const p of parts) h = (Math.imul(h, 31) + javaStringHash(p)) | 0;
  return h;
}

export class Noise {
  constructor(seed) { this.state = (seed | 0) || 1; }
  next() {
    this.state = (Math.imul(this.state, 1103515245) + 12345) & 0x7fffffff;
    return F(F(F(this.state) / F(0x3fffffff)) - 1);
  }
}

export class FixedNoise {
  constructor(seeds, options = {}) {
    this.internalRate = options.internalRate || 176400;
    this.step = NOISE_RATE / this.internalRate;
    this.phase = 0;
    this.ticks = 0;
    this.sources = seeds.map(seed => new Noise(seed));
    this.points = this.sources.map(source => Float64Array.from([source.next(), source.next(), source.next(), source.next()]));
    this.coefficients = this.points.map(() => new Float64Array(4));
    for(let j=0;j<this.points.length;j++)this._update(j);
  }
  _update(j) {
    const p=this.points[j],c=this.coefficients[j];
    c[0]=p[1];c[1]=.5*(p[2]-p[0]);
    c[2]=.5*(2*p[0]-5*p[1]+4*p[2]-p[3]);
    c[3]=.5*(3*(p[1]-p[2])+p[3]-p[0]);
  }
  next(out) {
    while (this.phase >= 1) {
      this.phase -= 1;
      this.ticks++;
      for (let j = 0; j < this.sources.length; j++) {
        const p = this.points[j];
        p[0] = p[1]; p[1] = p[2]; p[2] = p[3]; p[3] = this.sources[j].next();
        this._update(j);
      }
    }
    const x = this.phase;
    for (let j = 0; j < this.sources.length; j++) {
      const c = this.coefficients[j];
      out[j] = c[0]+x*(c[1]+x*(c[2]+x*c[3]));
    }
    this.phase += this.step;
    return out;
  }
}

export function rotationHz(value, voice = 'BLOOM') {
  value = F(clamp(finite(value, 0), 0, 1));
  if (value === 0) return 0;
  const positions = [0, .1, .25, .5, .7, .85, 1].map(F);
  const rates = [0, .025, .1, .5, 2, 8, voice === 'SHIMMER' ? 40 : 24].map(F);
  let j = 0;
  while (j < 5 && value > positions[j + 1]) j++;
  const x = F(F(value - positions[j]) / F(positions[j + 1] - positions[j]));
  return j === 0 ? F(rates[1] * x) : F(rates[j] * Math.pow(F(rates[j + 1] / rates[j]), x));
}

class Ramp {
  constructor(value) { this.value = value; this.target = value; this.remaining = 0; this.step = 0; }
  set(target, samples) {
    if (target === this.target) return;
    this.target = target;
    this.remaining = Math.max(1, samples | 0);
    this.step = (target - this.value) / this.remaining;
  }
  next() {
    if (this.remaining > 0 && --this.remaining > 0) this.value += this.step;
    else if (this.remaining === 0) this.value = this.target;
    return this.value;
  }
}

export function bandpassCoefficients(texture, internalRate) {
  const airy = textureName(texture) === 'air';
  const w = TAU * (airy ? 4100 : 4800) / internalRate;
  const cw = F(Math.cos(w)), sw = F(Math.sin(w));
  const alpha = F(sw / F(2 * F(airy ? .75 : 1.15)));
  const a0 = F(1 + alpha);
  return [F(alpha / a0), 0, F(-alpha / a0), F(F(-2 * cw) / a0), F(F(1 - alpha) / a0)];
}

export class Biquad {
  constructor(coefficients) { [this.b0,this.b1,this.b2,this.a1,this.a2] = coefficients; this.x1=0;this.x2=0;this.y1=0;this.y2=0; }
  process(x) {
    x = F(x);
    const y = this.b0*x+this.b1*this.x1+this.b2*this.x2-this.a1*this.y1-this.a2*this.y2;
    this.x2=this.x1;this.x1=x;this.y2=this.y1;this.y1=y;
    return y;
  }
}

export class TptLowpass {
  constructor(rate, damping) {
    const g=F(Math.tan(Math.PI*19500/rate)), k=F(damping);
    this.a1=F(1/F(1+F(g*F(g+k))));this.a2=F(g*this.a1);this.a3=F(g*this.a2);this.s1=0;this.s2=0;
  }
  process(input) {
    const v3=input-this.s2;
    const v1=this.a1*this.s1+this.a2*v3;
    const v2=this.s2+this.a2*this.s1+this.a3*v3;
    this.s1=2*v1-this.s1;this.s2=2*v2-this.s2;
    return v2;
  }
}

export function resamplerKernel(x, cutoff = .5) {
  if (Math.abs(x) >= 16) return 0;
  const sinc = Math.abs(x)<1e-9 ? cutoff : Math.sin(Math.PI*cutoff*x)/(Math.PI*x);
  const t=(x+16)/32;
  return sinc*(.42-.5*Math.cos(TAU*t)+.08*Math.cos(2*TAU*t));
}

// The Kotlin 32-tap kernel with a causal 16-source-sample delay. Boundary
// truncation/renormalization at centre>=0 matches the finite reference.
export class Decimator2 {
  constructor() {
    this.weights=Float64Array.from({length:32},(_,k)=>resamplerKernel(15-k,.5));
    // At this exact half-band ratio every non-central even sinc tap is zero.
    // Floating trig leaves ~1e-18 residuals; omitting them preserves response
    // while halving the convolution work. Keep a fixed interior normalization.
    this.taps=[];this.norm=0;
    for(let k=0;k<32;k++)if(Math.abs(this.weights[k])>1e-15){this.taps.push(k);this.norm+=this.weights[k];}
    this.ring=new Float32Array(32);this.index=0;this.hasOutput=false;this.output=0;
  }
  process(input) {
    const n=this.index++;
    this.ring[n&31]=input;
    this.hasOutput=(n&1)===0;
    if (!this.hasOutput) return 0;
    const centre=n-16;
    if (centre<0) { this.output=0; return 0; }
    let acc=0,norm=this.norm;
    if(centre>=15) {
      for(let j=0;j<this.taps.length;j++) {
        const k=this.taps[j];acc+=this.ring[(centre-15+k)&31]*this.weights[k];
      }
    } else {
      norm=0;
      for(let j=0;j<this.taps.length;j++) {
        const k=this.taps[j],pos=centre-15+k;
        if(pos<0)continue;
        const w=this.weights[k];acc+=this.ring[pos&31]*w;norm+=w;
      }
    }
    this.output=norm===0?0:F(acc/norm);
    return this.output;
  }
}

export class OutputFilter {
  constructor(sampleRate, dcEnabled=true) {
    this.sections=[1.9616,1.6629,1.1111,.3902].map(k=>new TptLowpass(sampleRate*4,k));
    this.first=new Decimator2();this.second=new Decimator2();
    this.dc=0;this.dcA=F(1-Math.exp(-TAU*8/sampleRate));this.dcEnabled=dcEnabled;this.output=0;
  }
  process(input) {
    for(let j=0;j<4;j++)input=this.sections[j].process(input);
    this.first.process(input);
    if(this.first.hasOutput) {
      this.second.process(this.first.output);
      if(this.second.hasOutput) {
        const x=this.second.output;
        if(this.dcEnabled) { this.dc=F(this.dc+F(this.dcA*F(x-this.dc)));this.output=F(x-this.dc); }
        else this.output=x;
      }
    }
    return this.output;
  }
}

const DEFAULT_GLASS_RATIOS=[2,3,4.02,5.03,5.98,7.97,10.03,12.02,15.97,18.03,20.04,24.03,27.97,31.98,40.03,47.95];
const DEFAULT_AIR_RATIOS=[1,2,3.01,4.01,5.98,8.02,10.01,12.03,16.02,20.01,24.02,28.04,32.02,40.01,48.03,56.01];

export function modeCoefficients({texture='glass',midi=48,density=.25,movement=.15,voice='BLOOM',sampleRate=44100,geometry=null}={}) {
  const airy=textureName(texture)==='air', rate=sampleRate*4,frequency=440*Math.pow(2,(midi-69)/12);
  const ratios=airy?(geometry?.model?.airRatios||DEFAULT_AIR_RATIOS):(geometry?.model?.glassRatios||DEFAULT_GLASS_RATIOS);
  const poleReal=new Float64Array(MODES),poleImag=new Float64Array(MODES),injection=new Float64Array(MODES),modeLevel=new Float64Array(MODES);
  for(let j=0;j<MODES;j++) {
    const band=j/15;
    const t60=airy?(1.9-.85*band)*(1.15-.35*density):(.60-.27*band)*(1.25-.65*density);
    const decayRate=Math.log(1000)/t60,radius=Math.exp(-decayRate/rate),hz=frequency*ratios[j];
    poleReal[j]=radius*Math.cos(TAU*hz/rate);poleImag[j]=radius*Math.sin(TAU*hz/rate);
    injection[j]=Math.sqrt(4*decayRate*NOISE_RATE)/rate;
    modeLevel[j]=hz>=13000?0:(.45+.55*band)/4*Math.min(1,(13000-hz)/2000);
  }
  const attackSeconds=airy?.035-.014*density:.018-.010*density;
  const burstSeconds=airy?.12-.045*density:.050-.025*density;
  const rotorHz=rotationHz(movement,voice);
  return {poleReal,poleImag,injection,modeLevel,ratios,frequency,rate,rotorHz,attackSeconds,burstSeconds,
    fastPole:Math.exp(-1/(attackSeconds*rate)),slowPole:Math.exp(-1/(burstSeconds*rate)),
    packetBudget:(airy?.40:.85)*Math.sqrt(.25/(.18+rotorHz))*Math.sqrt(.05/burstSeconds),
    bandpass:bandpassCoefficients(texture,rate)};
}

function prepareGeometry(raw) {
  if(!raw?.voices?.BLOOM||!raw?.voices?.SHIMMER)throw new Error('FACET geometry atlas is missing its voice layouts');
  const voices={};
  for(const name of ['BLOOM','SHIMMER']) {
    const source=raw.voices[name];
    if(source.levels?.length!==5)throw new Error('FACET geometry requires five hierarchy levels');
    voices[name]={...source,levels:source.levels.map((level,depth)=>({
      depth,facets:level.facets,routes:level.routes.map(route=>Float32Array.from(route)),
      events:level.events.map(event=>({...event})).sort((a,b)=>a.phase-b.phase)
    }))};
    for(const level of voices[name].levels)if(level.routes.length!==8||level.routes.some(r=>r.length!==2048))throw new Error('FACET route shape is invalid');
  }
  return {...raw,voices,tableSize:2048};
}

class LiveVoice {
  constructor(engine,note) {
    this.engine=engine;this.sampleRate=engine.sampleRate;this.rate=this.sampleRate*4;
    this.id=note.id;this.voice=voiceName(note.voice);this.texture=textureName(note.texture);
    this.midi=clamp(finite(note.midi,48),0,127);this.velocity=clamp(finite(note.velocity,.8),0,1);
    this.atlas=engine.geometry.voices[this.voice];this.ageFrames=0;this.internalFrames=0;
    this.initialAlignment=clamp(finite(note.initialAlignment,0),0,1)%1;
    this.absolutePhase=this.initialAlignment;this.phase=this.initialAlignment;
    this.density=new Ramp(F(clamp(finite(note.density,this.atlas.macros.FACETS),0,1)));
    this.movement=new Ramp(F(clamp(finite(note.movement,this.atlas.macros.REVOLVE),0,1)));
    this.brightness=new Ramp(clamp(finite(note.brightness,.5),0,1));
    this.attack=clamp(finite(note.attack,this.texture==='air'?.45:.20),.005,3);
    this.release=clamp(finite(note.release,.35),.01,3);
    this.attackProgress=0;this.releaseProgress=0;this.released=false;this.done=false;this.endReason='release';
    this.pending=null;this.outputGate=1;this.releaseDuration=this.release;this.releaseStart=1;
    this.birth=engine.frame;this.modeReal=new Float64Array(MODES);this.modeImag=new Float64Array(MODES);
    this.slow=new Float64Array(MODES);this.fast=new Float64Array(MODES);this.route=new Float32Array(GROUPS);
    this.noiseValues=new Float64Array(MODES+1);
    const seeds=this.atlas.noiseSeeds||Array.from({length:17},(_,j)=>seedFor(this.atlas.noiseSeed,j));
    this.noise=new FixedNoise(seeds,{internalRate:this.rate});
    this.coeff=modeCoefficients({texture:this.texture,midi:this.midi,density:this.density.value,movement:this.movement.value,voice:this.voice,sampleRate:this.sampleRate,geometry:engine.geometry});
    this.coeffDensity=this.density.value;this.coeffMovement=this.movement.value;this.lastBrightness=NaN;
    this.tilt=new Float64Array(16);this.veilTilt=1;
    this.rootSin=Math.sin(.4);this.rootCos=Math.cos(.4);
    this.rootStepSin=Math.sin(TAU*this.coeff.frequency/this.rate);this.rootStepCos=Math.cos(TAU*this.coeff.frequency/this.rate);
    this.stationary=new Float64Array(8);this.cachedRoutes=Array.from({length:8},()=>new Float32Array(2048));this.cachedDensity=NaN;
    this.airFilter=new Biquad(this.coeff.bandpass);this.veilFilter=new Biquad(this.coeff.bandpass);
    this.outputFilter=new OutputFilter(this.sampleRate,engine.dcEnabled);
    this.gain=engine.gain===null?CALIBRATION[this.voice][this.texture]*OUTPUT_HEADROOM:engine.gain;
    this.illumination=F(this.atlas.macros.ILLUMINATE);
    this.finiteSeconds=engine.finiteReferenceSeconds;
    this.lower=-1;this.low=null;this.high=null;this.blend=0;this.events=[];this.eventIndex=0;this.eventTurn=0;
    this._setLevels();
  }
  _setLevels() {
    const position=this.density.value*4;
    const lower=Math.min(3,Math.floor(position));
    this.blend=clamp(position-lower,0,1);
    if(lower===this.lower)return;
    this.lower=lower;
    this.low=this.atlas.levels[lower];this.high=this.atlas.levels[lower+1];
    this.events=this.low.events.map(event=>({...event,side:0})).concat(this.high.events.map(event=>({...event,side:1}))).sort((a,b)=>a.phase-b.phase);
    this._resetCursor();
  }
  _resetCursor() {
    const turn=Math.floor(this.absolutePhase),phase=this.absolutePhase-turn;
    this.eventIndex=this.events.findIndex(e=>e.phase>phase);
    this.eventTurn=turn;
    if(this.eventIndex<0){this.eventIndex=0;this.eventTurn++;}
  }
  setControls(params) {
    const samples=Math.round(this.sampleRate*.020);
    for(const key of ['density','movement','brightness'])if(Number.isFinite(Number(params[key])))this[key].set(F(clamp(Number(params[key]),0,1)),samples);
    if(Number.isFinite(Number(params.attack)))this.attack=clamp(Number(params.attack),.005,3);
    if(Number.isFinite(Number(params.release)))this.release=clamp(Number(params.release),.01,3);
  }
  noteOff(reason='release',duration=null) {
    if(this.released&&duration===null)return;
    this.released=true;this.endReason=reason;this.releaseProgress=0;
    this.releaseDuration=duration===null?this.release:duration;
    this.releaseStart=this.outputGate===undefined?1:this.outputGate;
  }
  _lookup() {
    const position=this.phase*2048,i=Math.floor(position),x=position-i,blend=this.blend;
    if(this.density.remaining===0&&this.cachedDensity===this.density.value) {
      for(let g=0;g<8;g++) {
        const p=this.cachedRoutes[g],a=p[(i-1)&2047],b=p[i&2047],c=p[(i+1)&2047],d=p[(i+2)&2047];
        this.route[g]=b+.5*x*(c-a+x*(2*a-5*b+4*c-d+x*(3*(b-c)+d-a)));
      }
      return;
    }
    for(let g=0;g<8;g++) {
      const a=this.low.routes[g],b=this.high.routes[g];
      // Interpolate the actual Float route coefficients before cubic lookup.
      const p0=F(a[(i-1)&2047]*(1-blend)+b[(i-1)&2047]*blend);
      const p1=F(a[i&2047]*(1-blend)+b[i&2047]*blend);
      const p2=F(a[(i+1)&2047]*(1-blend)+b[(i+1)&2047]*blend);
      const p3=F(a[(i+2)&2047]*(1-blend)+b[(i+2)&2047]*blend);
      this.route[g]=p1+.5*x*(p2-p0+x*(2*p0-5*p1+4*p2-p3+x*(3*(p1-p2)+p3-p0)));
    }
  }
  _internal() {
    const c=this.coeff,t=this.internalFrames/this.rate;
    this.phase=this.absolutePhase-Math.floor(this.absolutePhase);
    this._lookup();
    if(c.rotorHz!==0&&this.events.length) {
      while(this.eventTurn+this.events[this.eventIndex].phase<=this.absolutePhase) {
        const event=this.events[this.eventIndex],weight=event.side===0?1-this.blend:this.blend;
        const budget=1/Math.sqrt((1-this.blend)**2+this.blend**2);
        if(weight>0)for(let offset=0;offset<3;offset++) {
          const j=(event.group*2+offset)%16;
          const amount=event.amplitude*weight*budget*event.polarity*c.packetBudget*(offset===2?.38:1);
          this.slow[j]+=amount;this.fast[j]+=amount;
        }
        if(++this.eventIndex===this.events.length){this.eventIndex=0;this.eventTurn++;}
      }
    }
    this.noise.next(this.noiseValues);
    let envelope;
    if(this.finiteSeconds!==null) {
      const seconds=this.finiteSeconds,tail=Math.min(seconds*.36,1.1),attack=Math.min(seconds*.16,this.texture==='air'?.45:.20);
      envelope=smooth(t/attack)*(1-smooth((t-(seconds-tail))/Math.min(.4,tail*.65)))*this.velocity;
    } else {
      if(this.attackProgress<1) {
        this.attackProgress=Math.min(1,this.attackProgress+1/(this.attack*this.rate));
        envelope=smooth(this.attackProgress)*this.velocity;
      } else envelope=this.velocity;
    }
    if(this.released) {
      this.releaseProgress=Math.min(1,this.releaseProgress+1/(this.releaseDuration*this.rate));
      this.outputGate=this.releaseStart*(1-smooth(this.releaseProgress));
      envelope*=this.outputGate;
    } else this.outputGate=1;
    let modal=0;
    const stationaryLevel=this.texture==='air'?.030:.017;
    for(let g=0;g<8;g++)this.stationary[g]=stationaryLevel*Math.sqrt(Math.max(0,this.route[g])*8);
    for(let j=0;j<16;j++) {
      this.slow[j]*=c.slowPole;this.fast[j]*=c.fastPole;
      const packet=this.slow[j]-this.fast[j];
      const excitation=(packet+this.stationary[j>>1])*this.noiseValues[j]*envelope;
      const re=this.modeReal[j],im=this.modeImag[j];
      this.modeReal[j]=c.poleReal[j]*re-c.poleImag[j]*im+c.injection[j]*excitation;
      this.modeImag[j]=c.poleImag[j]*re+c.poleReal[j]*im;
      modal+=this.modeReal[j]*c.modeLevel[j]*this.tilt[j];
    }
    let illumination=0;for(let g=0;g<8;g++)illumination=F(illumination+this.route[g]);
    const filtered=this.veilFilter.process(this.airFilter.process(F(this.noiseValues[16])));
    const veilGain=this.texture==='air'?.016:.012;
    const veil=filtered*veilGain*Math.sqrt(Math.max(0,illumination))*envelope*this.veilTilt;
    const root=this.rootSin*.004*(.25+.75*this.illumination)*envelope;
    const rootSin=this.rootSin;
    this.rootSin=rootSin*this.rootStepCos+this.rootCos*this.rootStepSin;
    this.rootCos=this.rootCos*this.rootStepCos-rootSin*this.rootStepSin;
    let cap=this.outputGate;
    if(this.finiteSeconds!==null) {
      const tail=Math.min(this.finiteSeconds*.36,1.1),length=Math.min(.32,tail*.6);
      cap*=1-smooth((t-(this.finiteSeconds-length))/length);
    }
    this.internalFrames++;
    this.absolutePhase+=c.rotorHz/this.rate;
    return F((modal+veil+root)*cap);
  }
  next() {
    this.density.next();this.movement.next();this.brightness.next();this._setLevels();
    if(this.density.remaining===0&&this.cachedDensity!==this.density.value) {
      const blend=this.blend;
      for(let g=0;g<8;g++) {
        const a=this.low.routes[g],b=this.high.routes[g],out=this.cachedRoutes[g];
        for(let i=0;i<2048;i++)out[i]=a[i]*(1-blend)+b[i]*blend;
      }
      this.cachedDensity=this.density.value;
    }
    // Recompute rate/damping/packet coefficients at a short control interval.
    if((this.density.value!==this.coeffDensity||this.movement.value!==this.coeffMovement)&&
      ((this.ageFrames&15)===0||(this.density.remaining===0&&this.density.value!==this.coeffDensity)||
        (this.movement.remaining===0&&this.movement.value!==this.coeffMovement))) {
      this.coeff=modeCoefficients({texture:this.texture,midi:this.midi,density:this.density.value,movement:this.movement.value,voice:this.voice,sampleRate:this.sampleRate,geometry:this.engine.geometry});
      this.coeffDensity=this.density.value;this.coeffMovement=this.movement.value;
    }
    if(this.lastBrightness!==this.brightness.value) {
      for(let j=0;j<16;j++)this.tilt[j]=Math.pow(2,(this.brightness.value-.5)*4*(j/15-.5));
      // Brighten the ringing modes without lifting the diffuse noise floor.
      // Preserve the dark half and neutral sound; ease the veil back above .5.
      this.veilTilt=Math.pow(2,-Math.abs(this.brightness.value-.5)*2);this.lastBrightness=this.brightness.value;
    }
    let output=0;
    for(let sub=0;sub<4;sub++) {
      this.outputFilter.process(this._internal());
      // Each fourth internal sample produces one final-rate sample (phase 0).
      if(sub===0)output=this.outputFilter.output;
    }
    this.ageFrames++;
    if(this.released&&this.releaseProgress>=1)this.done=true;
    if(this.finiteSeconds!==null&&this.ageFrames>=this.finiteSeconds*this.sampleRate)this.done=true;
    return output*this.gain;
  }
  state() {
    let modeEnergy=0;for(let j=0;j<16;j++)modeEnergy+=this.modeReal[j]**2+this.modeImag[j]**2;
    return {id:this.id,midi:this.midi,voice:this.voice,texture:this.texture,phase:this.absolutePhase-Math.floor(this.absolutePhase),
      ageFrames:this.ageFrames,internalFrames:this.internalFrames,noiseTicks:this.noise.ticks,modeEnergy,
      density:this.density.value,movement:this.movement.value,brightness:this.brightness.value,
      rotorHz:this.coeff.rotorHz,released:this.released,pendingId:this.pending?.id??null};
  }
}

export class FacetKeyboardEngine {
  constructor({geometry,sampleRate=44100,maxVoices=4,gain=null,onEvent=null,finiteReferenceSeconds=null,dcEnabled=true}={}) {
    if(![44100,48000].includes(sampleRate))throw new Error('FACET keyboard supports 44.1 or 48 kHz');
    this.geometry=prepareGeometry(geometry);this.sampleRate=sampleRate;this.maxVoices=clamp(maxVoices|0,1,4);
    this.gain=Number.isFinite(gain)?gain:null;this.onEvent=typeof onEvent==='function'?onEvent:()=>{};
    this.finiteReferenceSeconds=Number.isFinite(finiteReferenceSeconds)?finiteReferenceSeconds:null;
    this.dcEnabled=dcEnabled;this.voices=[];this.frame=0;this.defaults={};this.peak=0;this.rms=0;this.clippedSamples=0;this.nonFiniteSamples=0;
  }
  noteOn(note) {
    if(!note||note.id===undefined||!Number.isFinite(Number(note.midi)))return false;
    note={...this.defaults,...note};
    if(finite(note.velocity,.8)<=0)return this.noteOff(note.id);
    const same=this.voices.find(v=>v.id===note.id||v.pending?.id===note.id);
    if(same){same.pending=note;same.noteOff('stolen',.012);return true;}
    if(this.voices.length<this.maxVoices)this.voices.push(new LiveVoice(this,note));
    else {
      const chosen=this.voices.filter(v=>!v.pending).sort((a,b)=>(a.released?0:1)-(b.released?0:1)||a.birth-b.birth)[0]||this.voices[0];
      if(chosen.pending)this.onEvent({type:'voiceStolen',id:chosen.pending.id});
      this.onEvent({type:'voiceStolen',id:chosen.id});chosen.pending=note;chosen.noteOff('stolen',.012);
    }
    return true;
  }
  noteOff(id) {
    for(const voice of this.voices) {
      if(voice.pending?.id===id){voice.pending=null;return true;}
      if(voice.id===id){voice.noteOff();return true;}
    }
    return false;
  }
  setControls(params={}) {
    Object.assign(this.defaults,params);
    for(const voice of this.voices)voice.setControls(params);
  }
  panic() { for(const voice of this.voices){voice.pending=null;voice.noteOff('panic',.012);} }
  handleMessage(message={}) {
    if(message.type==='noteOn')return this.noteOn(message);
    if(message.type==='noteOff')return this.noteOff(message.id);
    if(message.type==='params'){this.setControls(message.params||{});return true;}
    if(message.type==='panic'){this.panic();return true;}
    return false;
  }
  process(left,right=null) {
    let blockPeak=0,squares=0;
    for(let i=0;i<left.length;i++) {
      let sample=0;
      for(let j=this.voices.length-1;j>=0;j--) {
        const voice=this.voices[j];sample+=voice.next();
        if(voice.done) {
          this.onEvent({type:'voiceEnded',id:voice.id,reason:voice.endReason});
          if(voice.pending)this.voices[j]=new LiveVoice(this,voice.pending);else this.voices.splice(j,1);
        }
      }
      if(!Number.isFinite(sample)){sample=0;this.nonFiniteSamples++;}
      if(Math.abs(sample)>.98){this.clippedSamples++;sample=clamp(sample,-.98,.98);}
      left[i]=sample;if(right)right[i]=sample;blockPeak=Math.max(blockPeak,Math.abs(sample));squares+=sample*sample;this.frame++;
    }
    this.peak=blockPeak;this.rms=Math.sqrt(squares/Math.max(1,left.length));
    return left;
  }
  render(frames) { const out=new Float32Array(frames);this.process(out);return out; }
  getState() { return {activeVoices:this.voices.length,voices:this.voices.map(v=>v.state()),frame:this.frame,
    sampleRate:this.sampleRate,peak:this.peak,rms:this.rms||0,clippedSamples:this.clippedSamples,nonFiniteSamples:this.nonFiniteSamples}; }
  debugState() { return this.getState(); }
}

export function createEngine(geometry,options={}) { return new FacetKeyboardEngine({geometry,...options}); }

export function renderOffline({geometry,sampleRate=44100,seconds=6,finiteReferenceSeconds=null,gain=1,id='offline',...note}={}) {
  const engine=new FacetKeyboardEngine({geometry,sampleRate,gain,finiteReferenceSeconds});
  engine.noteOn({id,midi:48,velocity:.8,voice:'BLOOM',texture:'glass',...note});
  const samples=engine.render(Math.round(seconds*sampleRate));
  if(finiteReferenceSeconds!==null) {
    // A live DC blocker cannot know a future whole-file mean. Removing the
    // tiny residual here and reproducing the finite fade is only a test hook.
    let mean=0;for(const sample of samples)mean+=sample;mean/=samples.length;
    for(let i=0;i<samples.length;i++)samples[i]-=mean;
    const fade=Math.min(samples.length,Math.round(sampleRate*.012));
    for(let j=0;j<fade;j++)samples[samples.length-1-j]*=j/fade;
  }
  return {samples,diagnostics:engine.getState()};
}
