// Reproducible 3 × 4 candidate bank; v1 remains intact for comparison.
import {render,defaults,names,RATE} from './dsp-v2.mjs';
import fs from 'node:fs';
import {createHash} from 'node:crypto';
const root=new URL('../../synth/src/main/resources/cosmic-breaks/v2/',import.meta.url);
fs.mkdirSync(root,{recursive:true});
const hash=b=>createHash('sha256').update(b).digest('hex');
const manifest={version:2,sourceSha256:hash(fs.readFileSync(new URL('dsp-v2.mjs',import.meta.url))),approval:'Velocity and round-robin expansion requested; listening candidates.',macros:defaults,sampleRate:RATE,channels:1,bits:16,frames:RATE*4,zones:[[0,41],[42,84],[85,127]],takes:4,pads:[]};
for(let solo=0;solo<names.length;solo++){
 const variants=[];
 for(let layer=0;layer<3;layer++)for(let take=0;take<4;take++){
 const x=render({solo,layer,take}).wet,b=Buffer.alloc(44+x.length*2);
 b.write('RIFF');b.writeUInt32LE(b.length-8,4);b.write('WAVEfmt ',8);b.writeUInt32LE(16,16);b.writeUInt16LE(1,20);b.writeUInt16LE(1,22);b.writeUInt32LE(RATE,24);b.writeUInt32LE(RATE*2,28);b.writeUInt16LE(2,32);b.writeUInt16LE(16,34);b.write('data',36);b.writeUInt32LE(x.length*2,40);
 x.forEach((v,i)=>b.writeInt16LE(Math.max(-32768,Math.min(32767,Math.round(v*32767))),44+i*2));
 const file=`${String(solo+1).padStart(2,'0')}-${names[solo].toLowerCase().replaceAll(' ','-')}-v${layer+1}-r${take+1}.wav`;
 fs.writeFileSync(new URL(file,root),b);variants.push({layer,take,file,sha256:hash(b)});
 }
 const hard=variants[8];manifest.pads.push({slot:solo+1,name:names[solo],file:hard.file,sha256:hard.sha256,variants});
 console.log(names[solo]+': 12 strikes');
}
fs.writeFileSync(new URL('manifest.json',root),JSON.stringify(manifest,null,2)+'\n');
