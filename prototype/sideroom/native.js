const $=id=>document.getElementById(id), tempos={'deep-pocket':110,'high-voltage':128,angular:116,'tight-pocket':122,'open-road':100}, counts={};
const player=$('player');let serial=0;
function stop(){serial++;player.pause();player.removeAttribute('src');player.load();}
function refresh(){stop();$('tempo').textContent=`New phrases: ${tempos[$('kit').value]} BPM`;$('download').href=`${$('kit').value}-native.zip`;$('status').textContent='Choose a phrase to listen.';}
async function play(role){stop();const ticket=serial, kit=$('kit').value, level=$('level').value, phrase=['roll','riser'].includes(role),key=`${kit}/${role}/${level}`;let take=$('take').value;
if(take==='cycle'){take=counts[key]||0;counts[key]=(take+1)%3;}
player.src=phrase?`${kit}/${role}-${level}-${take}.wav`:`${kit}/${role}.wav`;
$('status').textContent=phrase?`${role==='roll'?'Snare roll':'Tom riser'} · ${['soft','medium','hard'][level]} · take ${Number(take)+1}`:`Original ${role} · 100 BPM`;
try{await player.play();}catch(e){if(ticket===serial)$('status').textContent='Playback could not start. Tap play on the audio player to retry.';}}
document.querySelectorAll('[data-role]').forEach(b=>b.addEventListener('click',()=>play(b.dataset.role)));
$('kit').addEventListener('change',refresh);$('stop').addEventListener('click',()=>{stop();$('status').textContent='Stopped.';});player.addEventListener('error',()=>{$('status').textContent='Audio could not load. Please try again.';});refresh();
