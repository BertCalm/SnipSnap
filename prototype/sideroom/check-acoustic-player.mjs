import fs from 'node:fs';import vm from 'node:vm';import assert from 'node:assert/strict';
const manifest=JSON.parse(fs.readFileSync(process.argv[2]+'/manifest.json'));const sounds=[];
class Audio{constructor(src){this.src=src;this.paused=true;this.currentTime=0;sounds.push(this)}play(){this.paused=false;return Promise.resolve()}pause(){this.paused=true}}
const nodes={'#mode':{value:'open-road'},'#level':{value:'1',selectedIndex:1,options:[{text:'Soft'},{text:'Medium'},{text:'Hard'}]},'#take':{value:'cycle'},'#player':new Audio(), '#status':{},'#groove':{},'#stop':{}};
const pads=['kick','snare','hat','openhat'].map(v=>({dataset:{voice:v},firstChild:{textContent:v}}));
vm.runInNewContext(fs.readFileSync(new URL('./acoustic.js',import.meta.url),'utf8'),{Audio,document:{querySelector:s=>nodes[s],querySelectorAll:()=>pads},fetch:async()=>({ok:true,json:async()=>manifest})});
for(const pad of pads){for(let i=0;i<4;i++){await pad.onclick();assert.equal(sounds.at(-1).src,`open-road/${pad.dataset.voice}-1-${i%3}.wav`)}}
await pads[3].onclick();let open=sounds.at(-1);await pads[2].onclick();assert(open.paused);
nodes['#level'].value='2';nodes['#take'].value='2';await pads[0].onclick();assert.equal(sounds.at(-1).src,'open-road/kick-2-2.wav');
nodes['#groove'].onclick();await Promise.resolve();nodes['#player'].currentTime=4;nodes['#mode'].value='source';nodes['#mode'].onchange();assert.equal(nodes['#player'].currentTime,4);assert.equal(nodes['#player'].src,'source/groove.wav');nodes['#stop'].onclick();assert(sounds.every(s=>s.paused));console.log('PASS: pad routing, RR cycling, explicit take, level, choke, treatment position, stop (simulated audio)');
