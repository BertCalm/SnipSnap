"""Package Kotlin generator output as a compact audition under the existing Site."""
from pathlib import Path
import json,shutil,sys,wave,subprocess
import numpy as np
root=Path(__file__).resolve().parents[1]
src=Path(sys.argv[1]);dst=Path(sys.argv[2]);dst.mkdir(parents=True,exist_ok=True)
for g in ('cuttings-pocket','cuttings-night','cuttings-circuit'):
 (dst/g).mkdir(exist_ok=True)
 (dst/g/'audio').mkdir(exist_ok=True)
 for wav in (src/g/'audio').glob('*.wav'):
  with wave.open(str(wav),'rb') as f:
   assert f.getsampwidth()==3 and f.getnchannels()==1 and f.getframerate()==44100
   raw=np.frombuffer(f.readframes(f.getnframes()),dtype=np.uint8).reshape(-1,3)
  ints=raw[:,0].astype(np.int32)|(raw[:,1].astype(np.int32)<<8)|(raw[:,2].astype(np.int32)<<16)
  ints=(ints+8388608)%16777216-8388608
  pcm=np.clip(np.rint(ints/256),-32768,32767).astype('<i2')
  assert np.max(np.abs(ints/8388608-pcm.astype(float)/32768))<=1/65536
  with wave.open(str(dst/g/'audio'/wav.name),'wb') as f:
   f.setnchannels(1);f.setsampwidth(2);f.setframerate(44100);f.writeframes(pcm.tobytes())
 shutil.copyfile(src/g/'manifest.json',dst/g/'manifest.json')
 m=json.loads((dst/g/'manifest.json').read_text())
 assert len(m['entries'])==240 and len(m['voices'])==20
 assert all((dst/g/e['file']).is_file() for e in m['entries'])
shutil.copyfile(src/'verification.csv',dst/'verification.csv')
html=(root/'prototype/cuttings/index.html').read_text()
a=html.index('<div class="control"><label for="stage">');b=html.index('<div class="control"><label for="dynamic">',a)
html=html[:a]+'''<div class="control"><label for="kit">Kit</label><select id="kit"><option value="cuttings-pocket">Pocket Finds · 89 BPM</option><option value="cuttings-night">Night Fragments · 76 BPM</option><option value="cuttings-circuit">Cut Circuit · 124 BPM</option></select><select id="stage" hidden><option value="finished">Printed kit</option></select></div>\n'''+html[b:]
a=html.index('<nav class="banks"');b=html.index('<div class="transport">',a)
new='<nav class="banks" aria-label="Pad banks">'+''.join(f'<button data-bank="{i}" aria-pressed="{str(i==0).lower()}">{label}</button>' for i,label in enumerate(['A 01–04','A 05–08','A 09–12','A 13–16','B · Long fills']))+'</nav><div class="pads">'
for i in range(20):
 new+=f'<button class="pad" data-bank-id="{i//4}" data-voice="p{i+1:02d}" {"hidden" if i>=4 else ""} disabled><span class="num">{i+1:02d}</span><b>Loading</b><small></small></button>'
html=html[:a]+new+'</div>'+html[b:]
html=html.replace('STUDY 04<br>89 BPM','FACTORY<br>THREE KITS').replace('Sixteen finds. Four small crates.','Three arrangements. Twenty pads each.')
a=html.index('<p class="intro">');b=html.index('<div class="controls">',a)
html=html[:a]+'<p class="intro">Choose a genre kit, then play its groove or individual cuts. Each pad has three strike levels and four alternate performances. The final bank has two- and four-bar rolls and rising fills.</p>'+html[b:]
html=html.replace('<label style="font-size:11px"><input id="context" type="checkbox" checked style="width:auto"> Groove traces</label>','<input id="context" type="checkbox" checked hidden>')
a=html.index('<p class="note">');b=html.index('</main>',a)
html=html[:a]+'''<p class="note">16-bit listening copies come from the saved SnipSnap kit windows. Strike and performance apply to pads; each groove has authored dynamics and cycling takes. Keyboard 1–4 plays the visible pads.</p><div class="bottom"><p>Pocket Finds preserves the accepted Study 04 hits and one-bar phrases. Night Fragments and Cut Circuit are new arrangements for listening. Two- and four-bar phrases are separate playable pads.</p><a href="../">Study 04 comparisons</a></div><details class="details"><summary>Integration evidence</summary><p>Native kits and export proofs use 24-bit files; this page uses 16-bit listening copies. Saved PCM, velocity/RR selection and MPC metadata are checked. SFZ import/export is tested. Physical phone and MPC playback remain unverified. MPC 2 retains velocity layers but cannot cycle takes within them; MPC 3 hardware verification remains open.</p><a href="verification.csv">Saved-kit proof</a></details>'''+html[b:]
(dst/'index.html').write_text(html)
js=(root/'prototype/cuttings/app.js').read_text().replace("lastVoice='kick'","lastVoice='p01'")
js=js.replace('Four-bar groove · 89 BPM','Four-bar groove · ${manifest.bpm} BPM')
js=js.replace("$('context').onchange=()=>{stop();status('Groove traces changed. Press Play groove.');};",'')
a=js.index("fetch('manifest.json')")
js=js[:a]+'''let loadGeneration=0;
async function loadKit(){
 const revision=++loadGeneration;stop();manifest=null;cache.clear();counts.clear();
 document.querySelectorAll('[data-voice],#groove,#sequence').forEach(b=>b.disabled=true);
 status('Loading saved kit…');const id=$('kit').value;
 try{
 const response=await fetch(id+'/manifest.json');if(!response.ok)throw Error('Kit could not load');const m=await response.json();if(revision!==loadGeneration)return;
 m.entries.forEach(e=>e.file=id+'/'+e.file);Object.values(m.grooves).forEach(g=>g.file=id+'/'+g.file);manifest=m;
 document.querySelectorAll('[data-voice]').forEach(p=>{p.querySelector('b').textContent=m.names[p.dataset.voice];p.querySelector('small').textContent=+p.dataset.voice.slice(1)>=15?'Authored finite phrase':'Printed source collage';});
 document.querySelectorAll('[data-voice],#groove,#sequence').forEach(b=>b.disabled=false);
 status(m.title+' · ready.');
 }catch(e){if(revision===loadGeneration)status(e.message+' · Select a kit to retry.');}
}
$('kit').onchange=loadKit;loadKit();
'''
(dst/'app.js').write_text(js)
print('Packaged three actual saved-kit auditions, 720 variant WAVs.')

subprocess.run([sys.executable,str(root/'scripts/audition-cuttings-compression.py'),str(dst)],check=True)
