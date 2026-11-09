// Freeze the approved audition's pad exports without substituting a different synth.
import {render,defaults,names,RATE} from './dsp.mjs';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
const here=path.dirname(fileURLToPath(import.meta.url));
const root=path.resolve(here,'../../synth/src/main/resources/cosmic-breaks/v1');
fs.mkdirSync(root,{recursive:true});
const hash=b=>createHash('sha256').update(b).digest('hex');
const manifest={version:1,prototypeVersion:2,siteCommit:'10857a750238566cfc3284a0bd788288203624ca',sourceSha256:hash(fs.readFileSync(path.join(here,'dsp.mjs'))),approval:'Owner: Sounds cool, continue. Overall direction approved; not hardware acceptance.',macros:defaults,sampleRate:RATE,channels:1,bits:16,frames:RATE*4,pads:[]};
for(let solo=0;solo<names.length;solo++){
 const x=render({solo}).wet,b=Buffer.alloc(44+x.length*2);
 b.write('RIFF');b.writeUInt32LE(b.length-8,4);b.write('WAVEfmt ',8);b.writeUInt32LE(16,16);b.writeUInt16LE(1,20);b.writeUInt16LE(1,22);b.writeUInt32LE(RATE,24);b.writeUInt32LE(RATE*2,28);b.writeUInt16LE(2,32);b.writeUInt16LE(16,34);b.write('data',36);b.writeUInt32LE(x.length*2,40);
 x.forEach((v,i)=>b.writeInt16LE(Math.max(-32768,Math.min(32767,Math.round(v*32767))),44+i*2));
 const file=`${String(solo+1).padStart(2,'0')}-${names[solo].toLowerCase().replaceAll(' ','-')}.wav`;
 fs.writeFileSync(path.join(root,file),b);manifest.pads.push({slot:solo+1,name:names[solo],file,sha256:hash(b)});
}
fs.writeFileSync(path.join(root,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
console.log('Frozen 16 pads using the audition WAV export quantization.');
