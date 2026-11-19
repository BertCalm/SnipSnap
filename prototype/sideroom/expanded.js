'use strict';
const mode=document.querySelector('#mode'),level=document.querySelector('#level'),take=document.querySelector('#take'),player=document.querySelector('#player'),status=document.querySelector('#status');
const counters={},active=new Set(); let hat=null,manifest=null;
const ready=fetch('manifest.json').then(r=>{if(!r.ok)throw Error('Could not load the sample map');return r.json()}).then(m=>manifest=m).catch(e=>{status.textContent=e.message;throw e});
function stop(){player.pause();player.currentTime=0;for(const a of active){a.pause();a.currentTime=0}active.clear();hat=null;status.textContent='Stopped.'}
let phrase='groove';
function grooveUrl(){return `${mode.value}/${phrase}.wav`}
player.src=grooveUrl();
async function playGroove(){try{await player.play()}catch(e){status.textContent='Playback could not start. Please try Play again.'}}
document.querySelector('#groove').onclick=()=>{phrase='groove';player.src=grooveUrl();player.currentTime=0;playGroove()};
document.querySelector('#fill').onclick=()=>{phrase='fill';player.src=grooveUrl();player.currentTime=0;playGroove()};
document.querySelector('#stop').onclick=stop;
mode.onchange=()=>{const time=player.currentTime,playing=!player.paused;for(const a of active)a.pause();active.clear();hat=null;player.src=grooveUrl();player.currentTime=time;if(playing)playGroove();status.textContent='Treatment changed.'};
for(const button of document.querySelectorAll('[data-voice]'))button.onclick=async()=>{
 try{await ready;const voice=button.dataset.voice,key=`${voice}/${level.value}`,rr=take.value==='cycle'?(counters[key]||0)%3:Number(take.value);counters[key]=rr+1;
 if(voice==='hat'||voice==='openhat'){if(hat){hat.pause();active.delete(hat)}}
 const path=manifest.files[`${mode.value}/${voice}/${level.value}/${rr}`];if(!path)throw Error('Missing sample mapping');
 const a=new Audio(path);active.add(a);if(voice==='hat'||voice==='openhat')hat=a;a.onended=()=>active.delete(a);a.onerror=()=>{active.delete(a);status.textContent='Sample could not load. Please retry.'};await a.play();status.textContent=`${button.firstChild.textContent} · ${level.options[level.selectedIndex].text} · take ${rr+1}`;
 }catch(e){status.textContent=e.message||'Playback could not start.'}
};

const banks={new:[['tom','Rack tom','Stick / head / shell'],['floor','Floor tom','Low body / sustain'],['ride','Ride','Tip / ringing metal'],['crash','Crash','Impact / spreading wash']],core:[['kick','Kick','Beater / head / body'],['snare','Snare','Stick / shell / wires'],['hat','Closed hat','Contact / metal'],['openhat','Open hat','Wash / natural tail']]};
document.querySelector('#bank').onchange=()=>{const bank=banks[document.querySelector('#bank').value];document.querySelectorAll('[data-voice]').forEach((button,i)=>{button.dataset.voice=bank[i][0];button.firstChild.textContent=bank[i][1];button.querySelector('span').textContent=bank[i][2]});status.textContent='Pad bank changed.'};
