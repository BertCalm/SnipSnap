import assert from 'node:assert/strict';
import fs from 'node:fs';
import {render,names} from './dsp-v2.mjs';
const root=new URL('../../synth/src/main/resources/cosmic-breaks/',import.meta.url);
const old=JSON.parse(fs.readFileSync(new URL('v1/manifest.json',root))),bank=JSON.parse(fs.readFileSync(new URL('v2/manifest.json',root)));
for(let k=0;k<16;k++){
 assert.deepEqual(fs.readFileSync(new URL('v1/'+old.pads[k].file,root)),fs.readFileSync(new URL('v2/'+bank.pads[k].file,root)),names[k]+' original hard take');
 const variants=bank.pads[k].variants.map(v=>{const b=fs.readFileSync(new URL('v2/'+v.file,root));return Float32Array.from({length:176400},(_,i)=>b.readInt16LE(44+i*2)/32768)});
 for(const x of variants){let sum=0,peak=0;for(const v of x){assert(Number.isFinite(v));sum+=v;peak=Math.max(peak,Math.abs(v))}assert(peak>.01&&peak<.881);assert(Math.abs(sum/x.length)<.005);assert.equal(x.at(-1),0)}
 // Correlation detects copies differing only in gain; every layer and take must change waveform shape.
 for(let a=0;a<12;a++)for(let b=a+1;b<12;b++){let xy=0,xx=0,yy=0;for(let i=0;i<22050;i++){const x=variants[a][i],y=variants[b][i];xy+=x*y;xx+=x*x;yy+=y*y}assert(xy/Math.sqrt(xx*yy)<.99999,`${names[k]} ${a}/${b} timbre duplicate`)}
}
const soft=render({solo:1,velocity:30}),hard=render({solo:1,velocity:110});
assert(soft.metrics.wetRms<hard.metrics.wetRms);
assert.deepEqual(render({solo:1,velocity:70,take:3}).wet,render({solo:1,velocity:70,take:3}).wet);
for(const roundRobin of [true,false]){const r=render({solo:13,velocity:127,repeat:8,roundRobin});assert(r.wet.every(v=>Number.isFinite(v)&&Math.abs(v)<1));}
console.log('192 finite, distinct strikes; 16 original hard takes byte-identical; velocity dynamics, deterministic takes and repeated-hit headroom passed.');
