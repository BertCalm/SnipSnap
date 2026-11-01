// Generate independent device-rate reference output from the accepted JS reader.
import fs from 'node:fs';
import path from 'node:path';
import { webcrypto } from 'node:crypto';
import { decodeCache } from './player/cache.mjs';
import { ReleaseReader } from './player/pcm-engine.mjs';
globalThis.crypto ??= webcrypto;
const out=process.argv[2], m=JSON.parse(fs.readFileSync(path.join(out,'manifest.json')));
const pcm=await decodeCache(m,fs.readFileSync(path.join(out,m.container.file)));
const times=[0,1/48000,2/48000,.013,.03796,.137,.231,.29137,.512,1.111,6.6,22.222];
const fd=fs.openSync(path.join(out,'raw/native-48k-fixtures.bin'),'w');
const integer=(...values)=>{const b=Buffer.alloc(values.length*4);values.forEach((v,i)=>b.writeInt32LE(v,i*4));fs.writeSync(fd,b);};
integer(times.length*2);
for(const mode of ['unfold','fade']) for(const time of times) {
 const rate=48000,hold=Math.round(time*rate),frames=60000;
 integer(rate,hold,frames,mode==='unfold'?1:0);
 const reader=new ReleaseReader(m,pcm);reader.start(1,mode);
 const block=new Float32Array(256);
 for(let at=0;at<hold;at+=256) reader.process(block.subarray(0,Math.min(256,hold-at)),rate);
 reader.release(1);
 for(let at=0;at<frames;at+=256) {
  const part=block.subarray(0,Math.min(256,frames-at));reader.process(part,rate);
  fs.writeSync(fd,Buffer.from(part.buffer,part.byteOffset,part.byteLength));
 }
 if(reader.voices.length) throw new Error('48k reference did not end');
}
fs.closeSync(fd);
console.log('Wrote 24 exact streaming JS references at 48 kHz, both release modes.');
