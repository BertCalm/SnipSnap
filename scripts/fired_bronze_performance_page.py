"""Validate four-layer export and package the performance audition."""
from pathlib import Path
import json,re,shutil,zipfile,wave,xml.etree.ElementTree as E
r=Path('testkit/fired-bronze-performance');kit=r/'SnipSnap Fired Bronze Performance'
used=[]
for inst in E.parse(next(kit.glob('*.xpm'))).getroot().findall('.//Instrument'):
 layers=[l for l in inst.findall('.//Layer') if l.findtext('SampleName')]
 if not layers:continue
 used.append(inst)
 assert [(int(l.findtext('VelStart')),int(l.findtext('VelEnd'))) for l in layers]==[(1,31),(32,63),(64,95),(96,127)]
 for l in layers:
  file=kit/(l.findtext('SampleName')+'.wav')
  assert file.is_file()
  with wave.open(str(file)) as w:assert int(l.findtext('SliceEnd'))==w.getnframes(),str(file)
assert len(used)==16
assert [int(i.get('number')) for i in used if i.findtext('MuteGroup')=='1']==[2,3,14]
for p in r.rglob('*.wav'):
 with wave.open(str(p)) as w:assert w.getnchannels()==1 and w.getframerate()==44100 and w.getsampwidth()==3
for p in (r/'native').glob('*_100.wav'):
 old=Path('testkit/fired-bronze-push/native')/(p.stem.removesuffix('_100')+'_both.wav')
 assert p.read_bytes()==old.read_bytes(),str(p)
assert (r/'native/groove_fixed.wav').read_bytes()==Path('testkit/fired-bronze-push/native/full_pads.wav').read_bytes()
(r/'validation.json').write_text(json.dumps({'pads':16,'layers':64,'sliceEndFrames':'all 64 equal their WAV frame counts','velocityZones':[[1,31],[32,63],[64,95],[96,127]],'fullStrengthPads':'16 byte-identical accepted WAVs','fixedGroove':'byte-identical previous per-pad groove','hatChoke':['A03','A04','A15']},indent=2))
shutil.copyfile('docs/FIRED_BRONZE_PERFORMANCE.md',r/'BUILD_NOTES.md')
(r/'NOTICE.txt').write_text(Path('testkit/fired-bronze-push/NOTICE.txt').read_text()+'\nPerformance round: added velocity-linked drive and four timbre layers. Full-strength synthesis unchanged.\n')
shutil.copyfile(r/'NOTICE.txt',kit/'NOTICE.txt')
style=re.search(r'<style>(.*?)</style>',Path('testkit/fired-bronze-push/index.html').read_text(),re.S)[1]
def player(id,label):
 assert (r/f'native/{id}.wav').is_file(),id
 return f'<div class="clip"><p class="label">{label}</p><audio controls preload="none" aria-label="{label}" src="native/{id}.wav" data-native="native/{id}.wav" data-matched="listen/{id}.wav"></audio><div class="links"><a href="native/{id}.wav" download>Native WAV</a></div></div>'
ids=['kick','snare','closed_hat','open_hat','tin','tun','na','te','ga','pressure','clay_open','clay_closed','kettle_low','kettle_high','hat_pedal','gong']
labels=['Bass Skin Kick','Rim Rattle Snare','Closed Bronze','Open Bronze','Edge Ring','Center Ring','Rim Ring','Pressed Tap','Bass Stroke','Moving Bass','Open Clay','Covered Clay','Low Kettle','High Kettle','Bronze Foot','Long Gong']
sequences=''.join(f'<article><h3>{labels[ids.index(id)]}</h3>'+player(id+'_fixed','Previous fixed drive · light to full')+player(id+'_response','Responsive drive · light to full')+'</article>' for id in ['kick','snare','tin','open_hat'])
cards=''.join(f'<article><p class="eyebrow">A{i:02d}</p><h3>{label}</h3>'+player(id+'_100','Accepted full-strength sound')+f'<p class="small"><a href="recipes/{id}_100.json">Recipe</a></p></article>' for i,(id,label) in enumerate(zip(ids,labels),1))
page=f'''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Fired Bronze / Playing response</title><style>{style}</style></head><body><main><header><p class="eyebrow">SnipSnap / Fired Bronze / Playing response</p><h1>Keep the impact.<br>Let lighter hits breathe.</h1><p class="intro">The pushed sound you liked stays exact at full strength. Softer hits now ease back the processor and amp drive, with four timbre layers for each MPC pad.</p><div class="actions"><a class="button" href="fired-bronze-performance.zip" download>Download four-layer MPC kit ↓</a><a href="https://fired-bronze-audition.bertcalm.chatgpt.site/push/">Accepted pushed direction</a></div></header>
<section><div class="section-top"><h2>Same groove, different touch response</h2><label>Playback <select id="level"><option value="native">Native levels</option><option value="matched">Loudness matched</option></select></label></div><p class="small">Both versions process pads individually. Same notes and velocities at 96 BPM; only the drive response changes. Full-strength hits remain unchanged.</p><div class="grid"><article><h3>Previous response</h3>{player('groove_fixed','Fixed drive at every strike')}</article><article><h3>New response</h3>{player('groove_response','Drive follows strike strength')}</article></div></section>
<section><h2>Light → medium → firm → full</h2><p class="small">Each sequence plays four representative velocity levels, with one shared level scale. Native mode preserves their dynamics. Matched mode adjusts the whole sequence together.</p><div class="grid">{sequences}</div></section>
<section><h2>The sixteen full-strength pads</h2><p class="small">These are byte-identical to the accepted Both pushed renders. The download adds four timbre layers per pad; all three hats share a choke group.</p><div class="grid">{cards}</div></section>
<section><h2>Inside the download</h2><p>Sixteen pads, 64 WAV layers, 64 replay recipes, and two groove comparisons. Long tails remain intact. The MPC plays four discrete timbre zones; the grooves here render continuous velocity recipes rather than emulating the hardware player.</p><p><a href="BUILD_NOTES.md">Build notes</a> · <a href="metrics.csv">Velocity measurements</a> · <a href="validation.json">Export validation</a></p></section>
<footer><p>Tabla profiles adapt <a href="https://freesound.org/people/ajaysm/packs/10737/">ajaysm's recordings</a> under <a href="https://creativecommons.org/licenses/by/4.0/">CC BY 4.0</a>. <a href="NOTICE.txt">Attribution and changes</a>. No endorsement or hardware-model accuracy is claimed. Clay and pressure models retain their calibration limits.</p><p>New touch response awaits listening. MPC hardware loading and browser interaction remain unverified.</p></footer></main><script>const players=[...document.querySelectorAll('audio')];players.forEach(p=>p.addEventListener('play',()=>players.forEach(o=>{{if(o!==p)o.pause()}})));document.getElementById('level').addEventListener('change',e=>players.forEach(p=>{{p.pause();p.src=p.dataset[e.target.value];p.load()}}));</script></body></html>'''
(r/'index.html').write_text(page)
archive=r/'fired-bronze-performance.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(r.rglob('*')):
  if not p.is_file() or p==archive:continue
  rel=p.relative_to(r)
  if rel.parts[0] in ('SnipSnap Fired Bronze Performance','recipes') or rel.name in ('BUILD_NOTES.md','NOTICE.txt','metrics.csv','validation.json') or (rel.parts[0]=='native' and p.stem.startswith('groove_')):z.write(p,rel)
print('Validated four-layer kit and accepted sound identity:',archive,archive.stat().st_size)
