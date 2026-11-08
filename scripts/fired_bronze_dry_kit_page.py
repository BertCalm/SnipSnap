"""Package the Kotlin dry kit with a portable audition page and credited profiles."""
from pathlib import Path
import re,shutil,zipfile,html,subprocess,sys
subprocess.run([sys.executable,"scripts/fired_bronze_dry_kit_validate.py"],check=True)
root=Path('testkit/fired-bronze-dry-kit')
shutil.copyfile('docs/FIRED_BRONZE_DRY_KIT.md',root/'BUILD_NOTES.md')
(root/'NOTICE.txt').write_text(Path('synth/src/main/resources/fired-bronze-r2/NOTICE.txt').read_text()+'\nDry kit adaptation: Ga is transposed and combined with an authored low membrane; Na/Te drive an authored rattle. Other source profiles retain R2 synthesis. Kit gains, velocities and sequencing are authored changes.\n')
shutil.copyfile(root/'NOTICE.txt',root/'SnipSnap Fired Bronze Dry'/'NOTICE.txt')
style=re.search(r'<style>(.*?)</style>',Path('testkit/fired-bronze-r2/index.html').read_text(),re.S)[1]
labels=['Bass Skin Kick','Rim Rattle Snare','Closed Bronze','Open Bronze','Edge Ring','Center Ring','Rim Ring','Pressed Tap','Bass Stroke','Moving Bass','Open Clay','Covered Clay','Low Kettle','High Kettle','Bronze Foot','Long Gong']
ids=['kick','snare','closed_hat','open_hat','tin','tun','na','te','ga','pressure','clay_open','clay_closed','kettle_low','kettle_high','hat_pedal','gong']
def player(id,label):
 return f'<div class="clip"><p class="label">{html.escape(label)}</p><audio controls preload="none" aria-label="{html.escape(label)}" src="native/{id}.wav" data-native="native/{id}.wav" data-matched="listen/{id}.wav"></audio><div class="links"><a href="native/{id}.wav" download>Native WAV</a></div></div>'
cards=[]
for i,(id,label) in enumerate(zip(ids,labels),1):
 status='New kit adaptation' if id in ids[:4]+['hat_pedal'] else ('Fitted R2 source, unchanged' if id in ['tin','tun','na','te','ga'] else 'R2 mechanism study; calibration limits remain')
 card=f'<article><p class="eyebrow">A{i:02d}</p><h3>{label}</h3><p class="status">{status}</p>'+player(id,'Full strike')+f'<p class="small"><a href="recipes/{id}.json">Replay recipe</a></p>'
 if (root/f'native/{id}_30.wav').exists():card+='<details><summary>Velocity comparisons</summary>'+player(id+'_30','30% strike')+player(id+'_65','65% strike')+'</details>'
 cards.append(card+'</article>')
page=f'''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Fired Bronze / The dry kit</title><style>{style}</style></head><body><main>
<header><p class="eyebrow">SnipSnap / Fired Bronze / Dry kit round</p><h1>A kit around<br>the hand-struck voices.</h1><p class="intro">The improved dry sources now have a kick, snare and related bronze hats around them. Start with the foundation, then hear the full kit on the same four-bar pattern. No processor or amp yet.</p><div class="actions"><a class="button" href="fired-bronze-dry-kit.zip" download>Download MPC kit + WAVs ↓</a><a href="https://fired-bronze-audition.bertcalm.chatgpt.site/r2/">R2 reference comparisons</a><a href="#pads">Explore 16 pads</a></div><p class="small">New kit balance awaiting your ears. The accepted R2 source renderer is unchanged. Clay and pressure voices remain experimental; the new drums are authored adaptations.</p></header>
<section><div class="section-top"><h2>Hear the kit together</h2><label>Playback <select id="level"><option value="native">Native levels</option><option value="matched">Loudness matched</option></select></label></div><p class="small">96 BPM · four bars · same foundation and gains. Native preserves velocity and arrangement balance. Matched compares timbre but makes soft hits louder. Playing one clip pauses the previous clip.</p><div class="grid"><article><h3>Foundation</h3>{player('groove_foundation','Kick, snare and hats')}</article><article><h3>Full dry kit</h3>{player('groove_full','Hand strokes, clay and kettle added; full gong ending')}</article></div></section>
<section id="pads"><h2>The sixteen pads</h2><p class="intro">Bass-stroke contact anchors the kick. Rim vibration drives the snare rattle. Open, closed and foot hats use the same bronze spectrum with different damping, and share a choke group in the MPC program.</p><div class="grid">{''.join(cards)}</div></section>
<section><h2>What is in the download</h2><p>Three timbre layers per pad, an MPC program folder, native and matched WAVs, replay recipes, timings and credits. Long gong and kettle tails are retained. The groove is an authored hybrid pattern, not a traditional performance.</p><p><a href="BUILD_NOTES.md">Build notes and limitations</a> · <a href="metrics.csv">Render measurements</a> · <a href="validation.json">Export checks</a> · <a href="NOTICE.txt">Attribution</a></p></section>
<footer><p>The five fitted tabla profiles derive from <a href="https://freesound.org/people/ajaysm/packs/10737/">ajaysm's Tabla Strokes</a>, <a href="https://creativecommons.org/licenses/by/4.0/">CC BY 4.0</a>. Their spectral envelopes were adapted into oscillator and seeded-noise synthesis. New drum layers and transpositions are authored changes. No performer endorsement is implied.</p><p>MPC hardware loading and browser interaction have not been verified. <a href="https://fired-bronze-audition.bertcalm.chatgpt.site/r1.html">First build archive</a></p></footer></main><script>
const players=[...document.querySelectorAll('audio')];players.forEach(p=>p.addEventListener('play',()=>players.forEach(o=>{{if(o!==p)o.pause()}})));document.getElementById('level').addEventListener('change',e=>players.forEach(p=>{{p.pause();p.src=p.dataset[e.target.value];p.load()}}));
</script></body></html>'''
(root/'index.html').write_text(page)
archive=root/'fired-bronze-dry-kit.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(root.rglob('*')):
  if p.is_file() and p!=archive:z.write(p,p.relative_to(root))
print(archive,archive.stat().st_size)
