"""Validate and package the stronger treatment comparison page."""
from pathlib import Path
import re,shutil,zipfile,html,json,wave,xml.etree.ElementTree as E
import numpy as np
from scipy.io import wavfile
r=Path('testkit/fired-bronze-push');kit=r/'SnipSnap Fired Bronze Push'
used=[]
for inst in E.parse(next(kit.glob('*.xpm'))).getroot().findall('.//Instrument'):
 layers=[l for l in inst.findall('.//Layer') if l.findtext('SampleName')]
 if not layers:continue
 used.append(inst)
 assert [(int(l.findtext('VelStart')),int(l.findtext('VelEnd'))) for l in layers]==[(1,41),(42,84),(85,127)]
 for l in layers:assert (kit/(l.findtext('SampleName')+'.wav')).is_file()
assert len(used)==16
assert [int(i.get('number')) for i in used if i.findtext('MuteGroup')=='1']==[2,3,14]
for p in r.rglob('*.wav'):
 with wave.open(str(p)) as w:assert w.getnchannels()==1 and w.getframerate()==44100 and w.getsampwidth()==3
for name in ['full','foundation']:
 assert (r/f'native/{name}_dry.wav').read_bytes()==Path(f'testkit/fired-bronze-dry-kit/native/groove_{name}.wav').read_bytes()
 assert (r/f'native/{name}_reference.wav').read_bytes()==Path(f'testkit/fired-bronze-color/native/{name}_bus_amp.wav').read_bytes()
crest={}
for name in ['reference','processor_push','amp_push','both','edge']:
 rate,x=wavfile.read(r/f'native/full_{name}.wav');x=x[:rate*10].astype(float)/2147483648
 crest[name]=float(20*np.log10(max(abs(x))/np.sqrt(np.mean(x*x))))
(r/'validation.json').write_text(json.dumps({'crestDbFirstTenSeconds':crest,'pads':16,'layers':48,'hatChoke':['A03','A04','A15'],'dryAndPreviousGrooves':'byte-identical','format':'mono 44100 Hz PCM24'},indent=2))
shutil.copyfile('docs/FIRED_BRONZE_PUSH.md',r/'BUILD_NOTES.md')
(r/'NOTICE.txt').write_text(Path('testkit/fired-bronze-color/NOTICE.txt').read_text()+'\nPush round: increased compression blend, processor drive, time-effect sends and independent amp drive. No new source recordings.\n')
shutil.copyfile(r/'NOTICE.txt',kit/'NOTICE.txt')
style=re.search(r'<style>(.*?)</style>',Path('testkit/fired-bronze-color/index.html').read_text(),re.S)[1]
variants=[('dry','Dry source'),('reference','Previous treatment'),('processor_push','Processor pushed · previous amp'),('amp_push','Amp pushed · previous processor'),('both','Both pushed'),('edge','Edge · further still')]
def player(id,label):
 assert (r/f'native/{id}.wav').exists(),id
 return f'<div class="clip"><p class="label">{html.escape(label)}</p><audio controls preload="none" aria-label="{html.escape(label)}" src="listen/{id}.wav" data-native="native/{id}.wav" data-matched="listen/{id}.wav"></audio><div class="links"><a href="native/{id}.wav" download>Native WAV</a></div></div>'
def routes(id):return ''.join(player(id+'_'+s,label) for s,label in variants)
ids=['kick','snare','closed_hat','open_hat','tin','tun','na','te','ga','pressure','clay_open','clay_closed','kettle_low','kettle_high','hat_pedal','gong']
labels=['Bass Skin Kick','Rim Rattle Snare','Closed Bronze','Open Bronze','Edge Ring','Center Ring','Rim Ring','Pressed Tap','Bass Stroke','Moving Bass','Open Clay','Covered Clay','Low Kettle','High Kettle','Bronze Foot','Long Gong']
cards=[]
for n,(id,label) in enumerate(zip(ids,labels),1):
 c=f'<article><p class="eyebrow">A{n:02d}</p><h3>{label}</h3>'+player(id+'_both','Both pushed')+f'<p class="small"><a href="recipes/{id}_both.json">Replay recipe</a></p>'
 if id in ['snare','tin','open_hat','gong']:c+='<details><summary>Separate the stages</summary>'+''.join(player(id+'_'+s,l) for s,l in variants if s not in ['dry','both'])+'</details>'
 cards.append(c+'</article>')
page=f'''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Fired Bronze / Pushed</title><style>{style}</style></head><body><main><header><p class="eyebrow">SnipSnap / Fired Bronze / Pushed treatment</p><h1>Push the body.<br>Drive the amp.</h1><p class="intro">More compression, stronger processor saturation and an independently driven amp. Compare the stages separately, then together, against the previous treatment.</p><div class="actions"><a class="button" href="fired-bronze-push.zip" download>Download pushed MPC kit ↓</a><a href="https://fired-bronze-audition.bertcalm.chatgpt.site/color/">Previous treatment</a><a href="https://fired-bronze-audition.bertcalm.chatgpt.site/dry-kit/">Accepted dry kit</a></div></header>
<section><div class="section-top"><h2>Find the useful amount of push</h2><label>Playback <select id="level"><option value="matched">Loudness matched</option><option value="native">Native levels</option></select></label></div><p class="small">Start with Previous treatment, then Both pushed. The two middle options change one stage while keeping the other at its previous setting. Edge takes both further. The notes and velocities stay the same at 96 BPM.</p><div class="grid"><article><h3>Foundation</h3>{routes('foundation')}</article><article><h3>Full kit</h3>{routes('full')}</article></div></section>
<section><h2>The exported-pad route</h2><p class="intro">The comparisons above run the whole groove through one chain. The MPC kit processes each pad separately; this version lets you hear that difference.</p>{player('full_pads','Full kit · individually processed pads')}</section>
<section><h2>Sixteen pushed pads</h2><p class="small">Original sources and long tails are retained. Snare, Tin, open hat and gong include separate-stage comparisons below.</p><div class="grid">{''.join(cards)}</div></section>
<section><h2>Inside the download</h2><p>Sixteen MPC pads using Both pushed, with three velocity timbre layers each. Includes replay recipes, the previous and pushed full-groove WAVs, and attribution. All 45 auditions stream here.</p><p><a href="BUILD_NOTES.md">Build notes</a> · <a href="metrics.csv">Treatment timings</a> · <a href="validation.json">Export checks</a> · <a href="BUS_SETTINGS.txt">Route settings</a></p></section>
<footer><p>Tabla profiles adapt <a href="https://freesound.org/people/ajaysm/packs/10737/">ajaysm's recordings</a> under <a href="https://creativecommons.org/licenses/by/4.0/">CC BY 4.0</a>. Spectral synthesis, drum layers, sequencing and processing are adaptations. <a href="NOTICE.txt">Credits and changes</a>. No performer endorsement or hardware-emulation accuracy is claimed.</p><p>Clay and pressure sources remain experimental. MPC hardware loading and browser interaction remain unverified.</p></footer></main><script>const players=[...document.querySelectorAll('audio')];players.forEach(p=>p.addEventListener('play',()=>players.forEach(o=>{{if(o!==p)o.pause()}})));document.getElementById('level').addEventListener('change',e=>players.forEach(p=>{{p.pause();p.src=p.dataset[e.target.value];p.load()}}));</script></body></html>'''
(r/'index.html').write_text(page)
archive=r/'fired-bronze-push.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(r.rglob('*')):
  if not p.is_file() or p==archive:continue
  rel=p.relative_to(r)
  if rel.parts[0] in ('SnipSnap Fired Bronze Push','recipes') or rel.name in ('BUILD_NOTES.md','NOTICE.txt','metrics.csv','validation.json','BUS_SETTINGS.txt') or (rel.parts[0]=='native' and p.stem in ('full_reference','full_both')):z.write(p,rel)
print('Validated 16 pads, 48 layers, unchanged reference grooves;',archive,archive.stat().st_size)
