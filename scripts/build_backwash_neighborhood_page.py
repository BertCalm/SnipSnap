"""Publish nearby candidates without overwriting the owner-preferred source or its page."""
import sys,shutil,zipfile,hashlib
from pathlib import Path
from html.parser import HTMLParser
src,dist=map(Path,sys.argv[1:]);assets=dist/'neighborhood-1'
# Check the unchanged reference against the exact published WAVs, not regenerated fixtures.
for voice in ['kick','snare','tom','hat_closed','hat_open']:
 for fresh,old in [(f'{voice}-dry.wav',f'{voice}-stage-dry.wav'),(f'{voice}-processed.wav',f'{voice}-default.wav')]:
  assert (src/'reference'/fresh).read_bytes()==(dist/'revision-3'/old).read_bytes(),fresh
for name in ['groove-dry.wav','groove-processed.wav']:
 assert (src/'reference'/name).read_bytes()==(dist/'revision-3'/name).read_bytes(),name
shutil.copytree(src,assets,dirs_exist_ok=True)
archive=dist/'accepted-direction.html'
if not archive.exists():shutil.copy2(dist/'index.html',archive)
with zipfile.ZipFile(assets/'backwash-neighborhood.zip','w',zipfile.ZIP_DEFLATED) as z:
 for file in assets.rglob('*'):
  if file.is_file() and file.suffix!='.zip':z.write(file,str(file.relative_to(assets)))
base=archive.read_text();css=base.split('<style>')[1].split('</style>')[0]
def player(family,stem,label,recipe=None):
 path=f'neighborhood-1/{family}/{stem}.wav'
 s=f'<article><h3>{label}</h3><audio controls preload="none" src="{path}" aria-label="{label}"></audio><p class="links"><a download href="{path}">WAV</a>'
 if recipe:s+=f' · <a download href="neighborhood-1/{family}/{recipe}.json">Editable recipe</a>'
 return s+'</p></article>'
families=[('reference','Accepted direction','The exact revision-3 sound you preferred.'),('tighter','Tighter','DAMP .75 → .90. A shorter ring; the same effects.'),('more-ring','More ring','DAMP .75 → .60. More body decay; the same effects.'),('moving-double','Moving double','The accepted dry kit, with SWAY .30 → .45 and TRAIL .25 → .40.')]
p='<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BACKWASH · Develop the accepted direction</title><style>'+css+'</style></head><body><main><header><div class="eyebrow">BACKWASH / Model 3 / Nearby variations</div><h1>Keep the character.<br>Find the range.</h1><p class="lead">The direction you preferred is preserved exactly. Three small variations explore damping and the moving double.</p><p>Start with the accepted groove, then compare the new candidates. They use identical notes, velocity, balance and timing.</p></header><section><h2>Compare the grooves</h2>'
for family,title,desc in families:
 p+=f'<h3 style="margin-top:28px">{title}</h3><p>{desc}</p><div class="grid">'+player(family,'groove-dry',title+' · Dry')+player(family,'groove-processed',title+' · Chorus → compression → amp')+'</div>'
p+='</section><section><h2>Individual voices</h2><p>Compare the dry source first, then its treatment. Each recipe recreates the processed one-shot.</p>'
for voice,label in [('kick','Kick'),('snare','Snare'),('tom','Tom'),('hat_closed','Closed hat'),('hat_open','Open hat')]:
 p+=f'<details><summary>{label}</summary>'
 for family,title,desc in families:
  p+='<div class="grid" style="margin-bottom:16px">'+player(family,voice+'-dry',title+' · Dry '+label)+player(family,voice+'-processed',title+' · Processed '+label,voice)+'</div>'
 p+='</details>'
p+='</section><footer><p><a class="download" download href="neighborhood-1/backwash-neighborhood.zip">Download WAVs + 20 editable recipes</a></p><p><a href="accepted-direction.html">Previous audition and touch comparisons</a> · <a href="neighborhood-1/metrics.csv">Measurements</a></p><p>Model 3 source unchanged. New variations are listening candidates. Four bars at 94 BPM; per-hit effects and hat choking, not a shared effects bus. Grooves share a loudness target; peak limits can leave level differences.</p><p>Reference direction: your supplied Miami Beach performance. No original recording samples are embedded. Mono 44.1 kHz / PCM24.</p></footer></main><script>document.addEventListener("play",e=>{if(e.target.tagName==="AUDIO")document.querySelectorAll("audio").forEach(a=>{if(a!==e.target)a.pause()})},true)</script></body></html>'
class Check(HTMLParser):
 def handle_starttag(self,t,attrs):
  for k,v in attrs:
   if k in ['src','href']:assert (dist/v).is_file(),v
Check().feed(p);(dist/'index.html').write_text(p)
print('Accepted reference matches all 12 published WAVs byte for byte; all page links resolve.')
