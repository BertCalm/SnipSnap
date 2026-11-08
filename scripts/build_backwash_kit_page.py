"""Build the kit download and audition from verified export files."""
from pathlib import Path
from html.parser import HTMLParser
import csv,html,shutil,sys,zipfile,wave,hashlib
src,dist=map(Path,sys.argv[1:3]);pushed='--pushed' in sys.argv[3:];assets=dist/('mpc-kit-pushed' if pushed else 'mpc-kit-1')
rows=list(csv.DictReader((src/'pads.csv').open()))
assert len(rows)==16
for line in (src/'SHA256SUMS.txt').read_text().splitlines():
 digest,file=line.split('  ',1);assert hashlib.sha256((src/file).read_bytes()).hexdigest()==digest,file
with zipfile.ZipFile(src/'backwash-mpc-kit.zip') as z:assert z.testzip() is None
for row in rows:
 for key in ['soft','medium','hard']:
  with wave.open(str(src/row[key])) as w:assert (w.getnchannels(),w.getframerate(),w.getsampwidth())==(1,44100,3)
shutil.copytree(src,assets,dirs_exist_ok=True)
prior=dist/'variations.html'
if not prior.exists():shutil.copy2(dist/'index.html',prior)
css=prior.read_text().split('<style>')[1].split('</style>')[0]
css+=' .pads{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.pads article{padding:14px}.pads h3{margin-bottom:5px}select{width:100%;padding:8px;margin:8px 0;background:#fffdf7;color:#233b3b;border:1px solid #c9d2c8;border-radius:4px}@media(max-width:800px){.pads{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:420px){.pads{grid-template-columns:1fr}}'
p='<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BACKWASH · MPC Kit</title><style>'+css+'</style></head><body><main><header><div class="eyebrow">SnipSnap / BACKWASH / MPC playing trial</div><h1>Put it<br>under the pads.</h1><p class="lead">16 pads. Three strike layers each. The preferred model-3 sound, arranged as a playable kit.</p><p><a class="download" download href="mpc-kit-1/backwash-mpc-kit.zip">Download MPC kit · MPC 3 + MPC 2</a></p><p>Unzip and keep the folders together. In MPC 3, load <strong>SnipSnap Backwash Kit.xtd</strong>. In MPC 2, open its <strong>_[TrackData]</strong> folder and load <strong>SnipSnap Backwash Kit.xpm</strong>.</p><p class="hint">Set your session to 94 BPM for the included MPC 3 pattern. The MPC 2 download supplies the program and WAV audition, not an embedded sequence.</p></header><section><h2>Hear the delivered kit</h2><p>These previews play the actual exported samples, including strike-layer selection, pad balance and hat choking. The preview uses a simple velocity curve; your MPC may respond differently.</p><div class="grid">'
for file,title in [('groove.wav','Four-bar groove · 94 BPM'),('pad-walk.wav','Pad walk · soft, medium, hard')]:
 p+=f'<article><h3>{title}</h3><audio controls preload="none" src="mpc-kit-1/{file}"></audio></article>'
p+='</div></section><section><h2>Bank A · pad map</h2><p>A01–A04 core kit · A05–A08 tighter variations · A09–A12 toms · A13–A16 moving double. Every hat is in choke group 1.</p><p class="hint">Choose a strike layer to audition its timbre. Individual WAVs play at file level; the groove includes the saved pad balance.</p><div class="pads">'
for r in rows:
 label=html.escape(r['name']);pad=r['pad'];p+=f'<article><div class="eyebrow">{pad}</div><h3>{label}</h3><label class="hint" for="select-{pad}">Strike layer</label><select id="select-{pad}" data-player="audio-{pad}">'
 for key,title in [('soft','Soft · MIDI 1–41'),('medium','Medium · MIDI 42–84'),('hard','Hard · MIDI 85–127')]:
  selected=' selected' if key=='hard' else '';p+=f'<option value="mpc-kit-1/{html.escape(r[key],quote=True)}"{selected}>{title}</option>'
 p+=f'</select><audio id="audio-{pad}" controls preload="none" aria-label="{pad} {label}" src="mpc-kit-1/{html.escape(r["hard"],quote=True)}"></audio><p class="hint">'+('Hat choke group 1' if r['muteGroup']=='1' else 'One-shot')+'</p></article>'
p+='</div></section><section><h2>First hardware check</h2><p>Play the core row softly, then firmly. Try a quiet snare between accents, close an open hat, and roll across the toms. Check whether the hat balance, layer transitions and effect tails feel right under your fingers.</p><p>The 48 WAVs and both program files have passed export read-back checks. Loading and feel on your MPC are still the listening test.</p></section><footer><p><a href="mpc-kit-1/pads.csv" download>Pad map CSV</a> · <a href="mpc-kit-1/README.txt">Loading and recipe notes</a> · <a href="mpc-kit-1/read-back.txt">Export checks</a> · <a href="variations.html">Earlier variations</a></p><p>The kit includes editable hard-layer recipes and source-velocity records for all 48 strikes. The source model remains 3; no new synthesis revision. Mono 44.1 kHz / PCM24. Effects are printed into each one-shot.</p></footer></main><script>document.addEventListener("play",e=>{if(e.target.tagName==="AUDIO")document.querySelectorAll("audio").forEach(a=>{if(a!==e.target)a.pause()})},true);document.querySelectorAll("select[data-player]").forEach(s=>s.addEventListener("change",()=>{const a=document.getElementById(s.dataset.player);a.pause();a.src=s.value;a.load()}));</script></body></html>'
if pushed:
 p=p.replace('mpc-kit-1/', 'mpc-kit-pushed/').replace('SnipSnap Backwash Kit','SnipSnap Backwash Pushed')
 p=p.replace('The preferred model-3 sound, arranged as a playable kit.', 'The approved stronger processing, arranged as a playable kit. Choose BACKWASH in the phone picker for 20 new editable presets alongside the 16 originals.')
 p=p.replace('A01–A04 core kit · A05–A08 tighter variations · A09–A12 toms · A13–A16 moving double.', 'A01–A04 all three stages pushed · A05–A08 clamped · A09–A12 four tom treatments · A13–A16 seasick.')
 p=p.replace('The source model remains 3; no new synthesis revision.', 'The drum source remains model 3; processing is model 4. Older recipes keep their original renderer.')
 p=p.replace('<a href="variations.html">Earlier variations</a>', '<a href="index.html">Processing comparisons</a> · <a href="kit-and-phone.html#phone-preview">Updated Android preview</a>')
class Check(HTMLParser):
 def handle_starttag(self,t,attrs):
  for key,val in attrs:
   if key in ['src','href'] or (t=='option' and key=='value'):assert (dist/val.split('#')[0]).is_file(),val
Check().feed(p);(dist/('pushed-kit.html' if pushed else 'index.html')).write_text(p)
print('All checksums, WAV headers, ZIP and page links verified.')
