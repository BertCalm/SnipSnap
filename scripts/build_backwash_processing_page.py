"""Package the processing study, verify its preserved reference, and build listening comparisons."""
from pathlib import Path
from html.parser import HTMLParser
import csv,shutil,json,zipfile,html,wave
import sys
repo=Path(__file__).resolve().parents[1];src=repo/'testkit/backwash-processing';dist=Path(sys.argv[1]);assets=dist/'processing-study-1'
rows=list(csv.DictReader((dist/'mpc-kit-1/pads.csv').open()))
for row in rows:assert (src/'reference'/(row['pad']+'-raw.wav')).read_bytes()==(dist/'mpc-kit-1'/row['hard']).read_bytes(),row['pad']
assert (src/'reference/groove-processed.wav').read_bytes()==(dist/'revision-3/groove-processed.wav').read_bytes()
for t in ['seasick','clamped','overloaded','combined']:
 assert (src/t/'groove-dry.wav').read_bytes()==(src/'reference/groove-dry.wav').read_bytes()
 assert (src/t/'groove-events.csv').read_bytes()==(src/'reference/groove-events.csv').read_bytes()
parameters={'format':'snipsnap-backwash-processing-study','version':1,'sourceModel':3,'pipeline':'dry source -> chorus -> parallel compressor -> amp -> DC/level/fade','strongChorus':{'centerMs':28,'depthMs':14,'rateHz':1.15,'feedback':.74,'wetGain':.95,'feedbackLowpassHz':5500,'addedTailMs':900},'strongCompression':{'amount':.88,'attack':.58,'recover':.84,'wetBlend':.9},'strongAmp':{'drive':.84,'sag':.35,'tone':.48,'cab':.55,'ampBlend':.8,'preAmpLowBandBlend':.2,'lowBandHz':160},'profiles':{'reference':'model 3 unchanged','seasick':['strongChorus'],'clamped':['strongCompression'],'overloaded':['strongAmp'],'combined':['strongChorus','strongCompression','strongAmp']},'unchangedStages':'Use model-3 mappings with saved baseRecipe macros.','matching':'One linear gain per complete clip using SnipSnap Loudness.of (loudest weighted 200 ms window, not LUFS); common peak-safe target per comparison group. No extra limiting.','replay':'Study JSONs require the included study renderer and model-3 source; they are not ordinary PadRecipe files and are not installed phone presets.'}
(src/'study.json').write_text(json.dumps(parameters,indent=2)+'\n')
code=src/'source';code.mkdir(exist_ok=True)
for base,name in [('test','BackwashProcessingStudy.kt'),('test','BackwashProcessingGenerator.kt'),('test','BackwashGroove.kt'),('main','Backwash.kt'),('main','BackwashV3.kt'),('main','BackwashPatch.kt'),('main','BackwashDrums.kt'),('main','BackwashPresets.kt')]:shutil.copy2(repo/f'synth/src/{base}/kotlin/com/snipsnap/synth/{name}',code/name)
(src/'README.txt').write_text('BACKWASH processing study 1\nSame model-3 dry kit and same four-bar 94 BPM groove. Effects run per hit, not on a shared drum bus. Reference, Seasick, Clamped, Overloaded, Combined.\nDefault page comparisons use *-matched.wav. Native *-raw.wav files retain renderer levels; groove-processed.wav is the original groove render. matching.csv records gain and measured level for all 85 comparisons. metrics.csv records one-shot render cost and late energy.\nAll 16 reference raw hits match the shipped kit and the reference groove matches the earlier audition exactly. All five dry grooves and event lists are identical.\nAxx.study.json files carry the base recipe and study treatment; reproduce with the included study Kotlin source inside SnipSnap, not as ordinary factory PadRecipe imports. study.json records all processing constants. Source code depends on the repository DSP toolkit at baseline cc47036c43e36d3eca78bd6e70cb15994b08d469 plus model-3 files included here.\nHistorical study renders are preserved here. The approved treatments are now editable production presets; the archive uses the study renderer.\n')
for p in src.rglob('*.wav'):
 with wave.open(str(p)) as f:assert(f.getnchannels(),f.getframerate(),f.getsampwidth())==(1,44100,3),p
levels=list(csv.DictReader((src/'matching.csv').open()))
assert len(levels)==85
for r in levels:
 assert abs(float(r['targetLoudness'])-float(r['measuredLoudness']))<1e-5
 assert float(r['matchedPeak'])<=.90001
shutil.copytree(src,assets,dirs_exist_ok=True)
# Match-file directory is fixed; archive is never allowed to include an older archive.
with zipfile.ZipFile(assets/'backwash-processing-study.zip','w',zipfile.ZIP_DEFLATED) as z:
 for p in assets.rglob('*'):
  relative=p.relative_to(assets)
  # Dry/touch clips are identical across profiles; store the reference copy once.
  shared=p.name=='groove-dry.wav' or p.name.endswith('-touch.wav')
  if shared and relative.parts[0]!='reference':
   assert p.read_bytes()==(assets/'reference'/p.name).read_bytes(),p
   continue
  if p.is_file() and p.suffix!='.zip':z.write(p,str(relative))
prior=dist/'kit-and-phone.html'
if not prior.exists():shutil.copy2(dist/'index.html',prior)
css=prior.read_text().split('<style>')[1].split('</style>')[0]
labels={'reference':'Current reference','seasick':'Seasick','clamped':'Clamped','overloaded':'Overloaded','combined':'All three pushed'}
desc={'reference':'The current sound, preserved exactly before comparison gain.','seasick':'A louder delayed double, deeper pitch movement and longer regeneration.','clamped':'Firmer compression and faster recovery bring up the moving tail; a little dry attack stays in the blend.','overloaded':'Much harder amp drive, with a small pre-amp low-frequency blend to support the drum body.','combined':'Deep chorus, firm compression and heavy amp drive together.'}
def player(t,case,label):
 raw='groove-processed' if case=='groove' else case+'-raw'
 return f'<article><h3>{html.escape(label)}</h3><audio controls preload="none" src="processing-study-1/{t}/{case}-matched.wav" aria-label="{html.escape(label)}"></audio><p class="hint">{desc[t]}</p><p class="links"><a download href="processing-study-1/{t}/{case}-matched.wav">Matched WAV</a> · <a download href="processing-study-1/{t}/{raw}.wav">Native-level WAV</a></p></article>'
p='<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>BACKWASH · Push the processing</title><style>'+css+'</style></head><body><main><header><div class="eyebrow">BACKWASH / Processing study 1</div><h1>Push the<br>processing.</h1><p class="lead">The same dry kit. Four stronger treatments. Start with the current sound, then hear each stage pushed and all three together.</p><p>These are listening candidates. The factory bank, MPC kit and phone preview still contain the current reference.</p></header><section><h2>Same groove, stronger treatment</h2><p>Four bars at 94 BPM, identical timing, strike strengths, pad balance and hat choking. Each clip has one comparison gain applied to match SnipSnap’s loudness meter without extra limiting.</p><div class="grid">'
for t,label in labels.items():p+=player(t,'groove',label)
p+='</div><p class="hint">Effects are rendered per hit, as in the existing kit. This does not add shared-bus compression. Matching uses the loudest weighted 200 ms window, not integrated LUFS; it reduces loudness bias without guaranteeing identical perceived loudness.</p></section><section><h2>Listen closer</h2><p>Compare the snare’s attack and tail, kick weight, tom body, and the hat wash. The download includes all 16 pads in every treatment.</p>'
for case,label in [('A02','Snare'),('A01','Kick'),('A10','Mid tom'),('A03','Closed hat'),('A04','Open hat')]:
 p+=f'<details><summary>{label}</summary><div class="grid">'
 for t,title in labels.items():p+=player(t,case,label+' · '+title)
 p+='</div></details>'
p+='</section><footer><p><a class="download" download href="processing-study-1/backwash-processing-study.zip">Download the processing study</a></p><p><a href="kit-and-phone.html">Current MPC kit and Android preview</a> · <a href="processing-study-1/README.txt">Study notes</a> · <a href="processing-study-1/matching.csv">Level matching</a> · <a href="processing-study-1/study.json">Processing settings</a></p><p>Study settings need the included renderer; they are not yet presets in the phone app. Mono 44.1 kHz / PCM24.</p></footer></main><script>document.addEventListener("play",e=>{if(e.target.tagName==="AUDIO")document.querySelectorAll("audio").forEach(a=>{if(a!==e.target)a.pause()})},true)</script></body></html>'
class Check(HTMLParser):
 def handle_starttag(self,t,attrs):
  for k,v in attrs:
   if k in ['src','href']:assert(dist/v).is_file(),v
Check().feed(p);(dist/'index.html').write_text(p)
# Keep the full bank in the ZIP; serve only individually linked study assets.
import re
linked={dist / v for v in re.findall(r'(?:src|href)="([^"]+)"',p)}
for asset in assets.rglob('*'):
 if asset.is_file() and asset not in linked:asset.unlink()
print('16 reference hits and groove unchanged; all dry grooves/events identical; 85 level matches and all page links verified.')
