"""Add a focused R2 comparison while preserving every R1 audio/download byte."""
from pathlib import Path
import shutil,re,json,hashlib,zipfile,csv
import numpy as np
from scipy.io import wavfile
repo=Path(__file__).resolve().parents[1]
site=Path('/workspace/sites/triad-audition/dist')
src=repo/'testkit/triad-hands-r2'
dest=site/'hands-r2';dest.mkdir(exist_ok=True)
original={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in site.iterdir() if p.is_file() and p.name not in ('index.html','r1.html')}
archive=site/'r1.html'
if not archive.exists(): shutil.copy2(site/'index.html',archive)
for p in src.iterdir():
    if p.is_file(): shutil.copy2(p,dest/p.name)
for name in ['Triad.kt','TriadHands.kt']: shutil.copy2(repo/'synth/src/main/kotlin/com/snipsnap/synth'/name,dest/name)
for name in ['TriadHandsAuditionGenerator.kt','TriadHandsTest.kt']: shutil.copy2(repo/'synth/src/test/kotlin/com/snipsnap/synth'/name,dest/name)
shutil.copy2(repo/'docs/TRIAD_HANDS_R2.md',dest/'design.md')
checks=[]
for f in sorted(dest.glob('*.wav')):
    sr,p=wavfile.read(f);assert sr==44100 and p.ndim==1 and p.dtype==np.int32
    x=p.astype(float)/2**31;peak=float(abs(x).max());dc=float(x.mean())
    assert 0.01<peak<1 and abs(dc)<.005 and np.isfinite(x).all()
    checks.append(dict(file=f.name,frames=len(x),peak=peak,dc=dc,sha256=hashlib.sha256(f.read_bytes()).hexdigest()))
assert len(checks)==12
# Compare body against contact within the same hit, independent of audition gain.
shape={}
for lane,step in [('conga',3),('bongo',1)]:
    offset=round((step+.30*.38)*44100*60/124/4)
    for rev in ['before','revised']:
        _,p=wavfile.read(dest/f'{lane}-{rev}.wav');x=p.astype(float)/2**31
        def energy(a,b):return float(np.mean(x[offset+int(a*44100):offset+int(b*44100)]**2))
        shape[f'{lane}-{rev}']={'bodyToAttackDb':float(10*np.log10(energy(.03,.10)/energy(.001,.02)))}
manifest={'status':'Native Kotlin; owner listening pending','files':checks,'bodyShape':shape,'preservedR1Files':original}
(dest/'verification.json').write_text(json.dumps(manifest,indent=2))
with zipfile.ZipFile(dest/'triad-hands-r2.zip','w',zipfile.ZIP_DEFLATED) as z:
    for p in sorted(dest.iterdir()):
        if p.suffix!='.zip':z.write(p,p.name)
css=re.search(r'<style>(.*?)</style>',archive.read_text(),re.S).group(1)
html='''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>TRIAD · Hand drums, revision 2</title><style>'''+css+'''.pairs{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px}.pair{background:#1d2420;padding:22px;border-radius:8px}.pair p{color:#b2bcb4}.pair label{margin-top:16px}@media(max-width:760px){.pairs{grid-template-columns:1fr}}</style></head><body><main><header><p class="eyebrow">SnipSnap / TRIAD / revision 02</p><h1>More skin.<br><span>More body.</span></h1><p class="lead">You liked the rigs. The conga and bongo needed more life. This pass reshapes their strike, resonance and accents.</p><span class="badge">124 BPM</span><span class="badge">SAME GROOVE</span><span class="badge">MATCHED A/B</span><div class="actions"><a class="download" href="hands-r2/triad-hands-r2.zip" download>Download comparison + source ↓</a><a href="r1.html">Original full audition</a></div><p class="hint">The other seven drum lanes, timing and effect settings are identical. Start with the solo pairs, then check the complete grooves.</p></header>'''
def pair(prefix,title,desc):
    return f'<article class="pair"><h3>{title}</h3><p>{desc}</p>'+''.join(f'<label for="{prefix}-{rev}">{label}</label><audio id="{prefix}-{rev}" controls preload="none" src="hands-r2/{prefix}-{rev}.wav"></audio>' for rev,label in [('before','Before · first audition'),('revised','Revised · fuller body and varied strikes')])+'</article>'
html+='<section><h2>Hear the hand drums.</h2><p>Each solo pair is matched to the same listening level. Listen for the body after the strike and the difference between softer and accented hits.</p><div class="pairs">'
html+=pair('conga','Conga','Lower, longer resonance with palm contact and a brief skin slap.')
html+=pair('bongo','Bongo','Higher, shorter resonance with a quicker fingertip attack.')
html+='</div></section><section><h2>Back inside the rigs.</h2><p>These use the original Main effect settings. All eight complete grooves share one listening level. Shared processors naturally respond to the revised hand drums.</p><div class="pairs">'
for n,title in [('dry','Dry groove'),('warehouse','Warehouse Slam'),('submerged','Submerged'),('fracture','Glitch & Morph')]: html+=pair(n,title,'Four bars plus the natural release and effect tail.')
html+='''</div></section><section><h2>What changed</h2><p>Nine membrane resonances give the body more texture. Upper resonances fade faster than the low body, and stronger hits use a shorter, brighter contact. Small repeatable differences in the contact texture vary the hits.</p><p>This is an authored synthesis revision, not a claim of acoustic or hardware accuracy. Tell me whether the revised hand drums now hold their place in the groove.</p><p><a href="hands-r2/design.md">Design notes</a> · <a href="hands-r2/metrics.csv">Level measurements</a> · <a href="hands-r2/verification.json">Audio verification</a> · <a href="r1.html">First audition archive</a></p></section><footer>TRIAD · Native Kotlin · Revision 02<br>Original audio and downloads preserved. R2 awaits your listening verdict.</footer></main><script>document.addEventListener('play',e=>{if(e.target.tagName==='AUDIO')document.querySelectorAll('audio').forEach(a=>{if(a!==e.target)a.pause()})},true)</script></body></html>'''
(site/'index.html').write_text(html)
for link in re.findall(r'(?:src|href)="([^"]+)"',html):assert (site/link).is_file(),link
assert all(hashlib.sha256((site/name).read_bytes()).hexdigest()==sha for name,sha in original.items())
with zipfile.ZipFile(dest/'triad-hands-r2.zip') as z:assert z.testzip() is None
print(json.dumps({'validatedWavs':len(checks),'originalFilesUnchanged':len(original),'bodyShape':shape},indent=2))
