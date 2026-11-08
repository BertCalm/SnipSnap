"""Build the processor/amp listening page and attributed MPC package."""
from pathlib import Path
import re,shutil,zipfile,html,subprocess,sys
subprocess.run([sys.executable,'scripts/fired_bronze_color_validate.py'],check=True)
r=Path('testkit/fired-bronze-color')
shutil.copyfile('docs/FIRED_BRONZE_COLOR.md',r/'BUILD_NOTES.md')
(r/'NOTICE.txt').write_text(Path('testkit/fired-bronze-dry-kit/NOTICE.txt').read_text()+'\nColor round: compression, saturation, delay, diffuse space and amp/speaker processing added. Shared-bus grooves and per-pad samples are separate adaptations. No hardware or performer endorsement.\n')
shutil.copyfile(r/'NOTICE.txt',r/'SnipSnap Fired Bronze Color/NOTICE.txt')
style=re.search(r'<style>(.*?)</style>',Path('testkit/fired-bronze-dry-kit/index.html').read_text(),re.S)[1]
ids=['kick','snare','closed_hat','open_hat','tin','tun','na','te','ga','pressure','clay_open','clay_closed','kettle_low','kettle_high','hat_pedal','gong']
labels=['Bass Skin Kick','Rim Rattle Snare','Closed Bronze','Open Bronze','Edge Ring','Center Ring','Rim Ring','Pressed Tap','Bass Stroke','Moving Bass','Open Clay','Covered Clay','Low Kettle','High Kettle','Bronze Foot','Long Gong']
def player(id,label):
 assert (r/f'native/{id}.wav').is_file(),id
 return f'<div class="clip"><p class="label">{html.escape(label)}</p><audio controls preload="none" aria-label="{html.escape(label)}" src="listen/{id}.wav" data-native="native/{id}.wav" data-matched="listen/{id}.wav"></audio><div class="links"><a href="native/{id}.wav" download>Native WAV</a></div></div>'
def stages(id):return ''.join(player(id+'_'+s,l) for s,l in [('dry','Dry reference'),('processor','Processor only'),('amp','Processor → amp')])
cards=[]
for n,(id,label) in enumerate(zip(ids,labels),1):
 card=f'<article><p class="eyebrow">A{n:02d}</p><h3>{label}</h3>'+stages(id)+f'<p class="small"><a href="recipes/{id}_amp.json">Replay recipe</a></p><details><summary>Velocity and stronger-drive comparisons</summary>'+player(id+'_soft_30','Amp · 30% strike')+player(id+'_soft_65','Amp · 65% strike')
 if (r/f'native/{id}_hot.wav').exists():card+=player(id+'_hot','More drive and space · alternative')
 cards.append(card+'</details></article>')
page=f'''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Fired Bronze / Processor and amp</title><style>{style}</style></head><body><main>
<header><p class="eyebrow">SnipSnap / Fired Bronze / Processing round</p><h1>Same kit.<br>Now through the chain.</h1><p class="intro">The dry kit you accepted, with compression, saturation, dark repeats and short space, followed by an amp. Listen for whether the hand-struck character survives each stage.</p><div class="actions"><a class="button" href="fired-bronze-color.zip" download>Download processed MPC kit ↓</a><a href="https://fired-bronze-audition.bertcalm.chatgpt.site/dry-kit/">Accepted dry kit</a><a href="#pads">Compare each pad</a></div><p class="small">A behavioral adaptation of the percussion-processing brief, not a measured hardware emulation. Long gong and kettle tails remain intact.</p></header>
<section><div class="section-top"><h2>One chain across the whole groove</h2><label>Playback <select id="level"><option value="matched">Loudness matched</option><option value="native">Native levels</option></select></label></div><p class="small">Same notes, velocities and balance at 96 BPM. The processor and amp react to all overlapping hits together. Matched mode helps compare color; native mode retains dynamics and level differences.</p><div class="grid"><article><h3>Foundation</h3><p class="small">Kick, snare and hats</p>{stages('foundation_bus')}</article><article><h3>Full kit</h3><p class="small">Hand strokes, clay and kettle, with the full gong ending</p>{stages('full_bus')}</article></div><details><summary>Try more drive and space</summary>{player('full_bus_hot','Full kit · stronger-drive alternative')}</details></section>
<section><h2>How the exported pads behave</h2><p class="intro">The MPC download bakes the chain into each pad. These versions process hits independently, so they do not share the whole-kit compressor response above. All three hats choke one another, including their baked effects tails.</p><div class="grid"><article><h3>Foundation · treated pads</h3>{player('foundation_pads_amp','Individually processed foundation')}</article><article><h3>Full kit · treated pads</h3>{player('full_pads_amp','Individually processed full kit')}</article></div></section>
<section id="pads"><h2>Dry → processor → amp</h2><p class="small">Dry sources are unchanged. Softer strikes are available below each pad; switch to native levels to assess velocity. Playing a clip pauses the previous one.</p><div class="grid">{''.join(cards)}</div></section>
<section><h2>Inside the download</h2><p>Sixteen processed MPC pads, three timbre layers each, the three native full-bus comparisons, replay recipes and credits. All 93 comparisons remain playable on this page. Clay and pressure sources retain their earlier calibration limits.</p><p><a href="BUILD_NOTES.md">Build notes and research</a> · <a href="metrics.csv">Treatment timings</a> · <a href="validation.json">Validation</a> · <a href="BUS_SETTINGS.txt">Bus settings</a></p></section>
<footer><p>Tabla profiles derive from <a href="https://freesound.org/people/ajaysm/packs/10737/">ajaysm's Tabla Strokes</a>, <a href="https://creativecommons.org/licenses/by/4.0/">CC BY 4.0</a>. Spectral synthesis, transposition, drum layers, sequencing and effects are adaptations. No endorsement is implied. <a href="NOTICE.txt">Full attribution</a>.</p><p><a href="https://fired-bronze-audition.bertcalm.chatgpt.site/r2/">Reference recordings and dry fits</a> · <a href="https://fired-bronze-audition.bertcalm.chatgpt.site/r1.html">First build archive</a>. MPC hardware loading and browser interaction remain unverified.</p></footer>
</main><script>const players=[...document.querySelectorAll('audio')];players.forEach(p=>p.addEventListener('play',()=>players.forEach(o=>{{if(o!==p)o.pause()}})));document.getElementById('level').addEventListener('change',e=>players.forEach(p=>{{p.pause();p.src=p.dataset[e.target.value];p.load()}}));</script></body></html>'''
(r/'index.html').write_text(page)
archive=r/'fired-bronze-color.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(r.rglob('*')):
  if not p.is_file() or p==archive:continue
  rel=p.relative_to(r)
  if rel.parts[0] in ('SnipSnap Fired Bronze Color','recipes') or rel.name in ('BUILD_NOTES.md','NOTICE.txt','metrics.csv','validation.json','BUS_SETTINGS.txt') or (rel.parts[0]=='native' and p.stem in ('full_bus_dry','full_bus_processor','full_bus_amp')):z.write(p,rel)
print(archive,archive.stat().st_size)
