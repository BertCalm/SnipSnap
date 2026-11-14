"""Build the audition page from Kotlin renders. Usage: current previous site-dist."""
from pathlib import Path
import sys,shutil,json,zipfile,html
from html.parser import HTMLParser
src,old,dist=map(Path,sys.argv[1:]);assets=dist/'revision-3';assets.mkdir(parents=True,exist_ok=True)
for p in src.iterdir():
 if p.suffix in ('.wav','.json','.csv'):shutil.copy2(p,assets/p.name)
voices=['kick','snare','tom','hat_closed','hat_open'];labels=['Kick','Snare','Tom','Closed hat','Open hat']
for v in voices:shutil.copy2(old/f'{v}-stage-dry.wav',assets/f'{v}-previous-dry.wav')
with zipfile.ZipFile(assets/'backwash-revision-3.zip','w',zipfile.ZIP_DEFLATED) as z:
 for p in assets.iterdir():
  if p.suffix in ('.wav','.json','.csv'):z.write(p,p.name)
 z.writestr('README.txt','BACKWASH revision 3 listening candidate. Dry stage files bypass effects. Default recipes render the processed single hits. Touch files use velocities .3/.6/1 and velocity^1.4 playback gain. Groove: 94 BPM, four bars, groove-events.csv; independent per-hit processing with hat choking, not a shared effects bus. No audio sampled from the reference recording. Previous dry clips are the rejected revision 2.\n')
def player(file,label,recipe=None):
 s=f'<article><h3>{html.escape(label)}</h3><audio controls preload="none" src="revision-3/{file}.wav" aria-label="{html.escape(label)}"></audio><p class="links"><a download href="revision-3/{file}.wav">WAV</a>'
 if recipe:s+=f' · <a download href="revision-3/{recipe}.json">Recipe</a>'
 return s+'</p></article>'
css='''*{box-sizing:border-box}body{background:#f4f0e6;color:#233b3b;margin:0;font:16px/1.55 system-ui,sans-serif}main{max-width:1050px;margin:auto;padding:36px 20px 65px}header{border-top:5px solid #21665f;padding-top:22px}h1{font-size:clamp(48px,10vw,92px);letter-spacing:-.07em;line-height:1;margin:24px 0}h2{font-size:27px;letter-spacing:-.03em;margin-top:40px}h3{font-size:17px;margin:0 0 12px}.eyebrow{font-size:12px;letter-spacing:.15em;text-transform:uppercase}.lead{font-size:21px;max-width:730px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(100%,280px),1fr));gap:15px}article{padding:18px;border:1px solid #c9d2c8;border-radius:6px;background:#fffdf7;min-width:0}audio{width:100%;height:42px}a{color:#21665f;text-underline-offset:4px}.links,.hint{font-size:13px}.download{display:inline-block;background:#21665f;color:white;padding:12px 18px;border-radius:4px;text-decoration:none}details{border-top:1px solid #c9d2c8;margin-top:20px;padding-top:16px}summary{font-size:19px;cursor:pointer;margin-bottom:15px}footer{margin-top:42px;border-top:1px solid #c9d2c8;padding-top:18px;font-size:13px}a:focus-visible,summary:focus-visible{outline:3px solid #ad733c;outline-offset:4px}'''
p='<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BACKWASH · Reference-led kit audition</title><style>'+css+'</style></head><body><main><header><div class="eyebrow">SnipSnap / BACKWASH / Revision 3</div><h1>The strike<br>and the body.</h1><p class="lead">A new dry-kit candidate, shaped around your Miami Beach reference. Start with the groove, then compare the individual drums.</p><p>Beater and stick attack restored. Short head resonance. Head-driven snare rattle. Brighter hats. Acoustic character is still yours to judge.</p></header>'
p+='<section><h2>1. Hear the kit together</h2><p>Four bars at 94 BPM with accents, a ghost note, an open hat and a tom fill. Both clips play the same events. This is an original test pattern, not a transcription of the video.</p><div class="grid">'+player('groove-dry','Dry kit · no effects')+player('groove-processed','Kit through chorus → compressor → amp')+'</div><p class="hint">Whole grooves use the same loudness target. Processing happens per hit, as in the engine; pads do not share a compressor or chorus bus. Hats choke. Peak limits can leave some loudness differences.</p></section>'
p+='<section><h2>2. Judge the dry drums</h2><p>The previous candidate is kept beside each new hit. Compare the strike, the body beneath it, and how the ringing ends.</p>'
for v,l in zip(voices,labels):
 p+=f'<h3 style="margin-top:26px">{l}</h3><div class="grid">'+player(v+'-stage-dry',l+' · New dry')+player(v+'-previous-dry',l+' · Previous dry, rejected')+player(v+'-default',l+' · New through effects',v+'-default')+'</div>'
p+='</section><section><h2>3. Soft, medium, hard</h2><p>Three dry hits per voice. Velocity changes strike colour as well as playback level.</p><div class="grid">'
for v,l in zip(voices,labels):p+=player(v+'-touch',l+' · Touch comparison')
p+='</div></section><details><summary>Effect stages and control sweeps</summary><p>Stage clips use the same loudness target. Controls are normalized 0–1. The other controls remain at their defaults.</p>'
for v,l in zip(voices,labels):
 p+=f'<details><summary>{l}</summary><div class="grid">'
 for s in ['dry','chorus','compressed','amp']:p+=player(v+'-stage-'+s,l+' · '+s)
 for key in ['tune','damp','sway','trail','press','drive']:
  for value in ['0.1','0.5','0.9']:p+=player(v+'-'+key+'-'+value,key.upper()+' '+value,v+'-'+key+'-'+value)
 p+='</div></details>'
p+='</details><footer><p><a class="download" download href="revision-3/backwash-revision-3.zip">Download the listening pack</a></p><p><a href="revision-3/summary.json">Measurements</a> · <a href="revision-3/groove-events.csv">Groove events</a></p><p>Reference: Rolling Stone, “Ringo Starr Plays Drums on Miami Beach,” supplied screen recording around 0:17–0:32. Used to guide character; no reference audio is embedded in these sounds. The mixed recording does not provide isolated drum measurements.</p><p>Kotlin renders · mono 44.1 kHz / PCM24. Listening candidate, not an approved factory bank.</p></footer></main><script>document.addEventListener("play",e=>{if(e.target.tagName==="AUDIO")document.querySelectorAll("audio").forEach(a=>{if(a!==e.target)a.pause()})},true)</script></body></html>'
class Check(HTMLParser):
 def handle_starttag(self,tag,attrs):
  for key,value in attrs:
   if key in ['src','href']:assert (dist/value).is_file(),value
Check().feed(p);(dist/'index.html').write_text(p)
print('Page built; all linked assets exist.')
