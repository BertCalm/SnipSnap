"""Reference-directed recipe prototype. Python, not Kotlin. No reference PCM in renders.
Uses existing EMBER acoustic synthesis; kit settings are proposals, not measured instruments.
"""
from pathlib import Path
import sys,json,time,hashlib,html,zipfile
import numpy as np
from scipy import signal
from scipy.io import wavfile
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'ember'))
import ember_proto as e
FS=44100
KITS=[('soul-pocket',108,dict(TUNE=.53,DAMP=.7,BUZZ=.4,DISTANCE=.12,WARMTH=.35)),('roadhouse-rock',134,dict(TUNE=.36,DAMP=.36,BUZZ=.64,DISTANCE=.26,WARMTH=.2)),('open-room-rock',89,dict(TUNE=.46,DAMP=.18,BUZZ=.55,DISTANCE=.4,WARMTH=.12))]
VOICES=['KICK','SNARE','RIMSHOT','CROSS_STICK','HAT_CLOSED','HAT_OPEN','HAT_FOOT','TOM_RACK','TOM_FLOOR','RIDE','RIDE_BELL','SIZZLE','TAMBOURINE','STICKS','PRESS_ROLL','CYMBAL_SWELL']
VEL=[.34,.64,1.]; manifest={'status':'Python recipe prototype; not factory presets or an accepted reference match','source_commit':'4ead7cd350ab6fabb116a2ee01896ad9fff49f8f','kits':[]}
# Reference-derived pulse estimates set tempos. Timbre settings remain authored hypotheses.
def write(p,x):
 p.parent.mkdir(parents=True,exist_ok=True);wavfile.write(p,FS,np.round(np.clip(x,-1,1)*32767).astype(np.int16))
def norm_res(a,b):
 n=min(len(a),len(b));a=a[:n];b=b[:n];gain=np.dot(a,b)/max(np.dot(b,b),1e-20)
 return float(20*np.log10(max(np.linalg.norm(a-gain*b),1e-20)/max(np.linalg.norm(a),1e-20)))
allmetrics=[]; pages=[]
for ki,(name,bpm,base) in enumerate(KITS):
 start=time.perf_counter(); bank={}; kit={'name':name,'bpm':bpm,'pads':[]}; demos=[]
 for pi,voice in enumerate(VOICES):
  m={**base}
  if voice in ('HAT_CLOSED','HAT_OPEN','HAT_FOOT','RIDE','RIDE_BELL'):m['BUZZ']=0
  if voice in ('TOM_RACK','TOM_FLOOR'):m['DAMP']=max(0,base['DAMP']-.12)
  if voice in ('PRESS_ROLL','CYMBAL_SWELL'):m['SPEED']=[.58,.65,.4][ki]
  # Different complementary roles per kit, as well as the base tuning and damping.
  actual=voice
  if pi==13:actual=['CLAPS','COWBELL','TOM_RACK'][ki]
  if ki==2 and pi==13:m['TUNE']=.74
  pad={'pad':pi+1,'voice':actual,'macros':m,'variants':[]}; rendered=[]
  for zi,v in enumerate(VEL):
   takes=[]
   for take in range(3):takes.append(e.render(actual,m,velocity=v,take=take))
   # Equal RMS takes within each zone, preserving a genuine velocity level difference.
   rms=[np.sqrt(np.mean(y*y)) for y in takes];target=np.exp(np.mean(np.log(np.maximum(rms,1e-12))))
   takes=[y*target/max(r,1e-12) for y,r in zip(takes,rms)]
   ceiling=max(max(abs(y)) for y in takes); takes=[y*.88/max(ceiling,1e-12)*v**.8 for y in takes]
   for take,y in enumerate(takes):
    assert np.isfinite(y).all()
    rel=f'{name}/A{pi+1:02d}-{actual.lower()}-v{zi+1}-t{take+1}.wav';write(ROOT/rel,y)
    met={'file':rel,'peak':float(max(abs(y))),'dc':float(np.mean(y)),'tail_rms':float(np.sqrt(np.mean(y[-441:]**2)))}
    allmetrics.append(met);pad['variants'].append(rel);bank[(pi,zi,take)]=y
   rendered.append(takes)
  pad['soft_hard_residual_db']=norm_res(rendered[0][0],rendered[2][0]);pad['take_residual_db']=norm_res(rendered[2][0],rendered[2][1])
  # Check replay independently of gain matching.
  assert np.array_equal(e.render(actual,m,velocity=1,take=0),e.render(actual,m,velocity=1,take=0))
  kit['pads'].append(pad)
  demos.extend([rendered[2][0],np.zeros(int(.15*FS))])
 # Four authored bars; timings are original arrangements, not song transcriptions.
 step=60/bpm/4; events=[]
 for bar in range(4):
  off=bar*16
  for s in ([0,6,8,11] if ki==0 else [0,3,8,10] if ki==1 else [0,7,8,14]):events.append((off+s,0,2,.88))
  for s in [4,12]:events.append((off+s,1,2,.78))
  for s in ([3,10,15] if ki==0 else [11,15]):events.append((off+s,1,0,.45))
  for s in range(0,16,2):events.append((off+s,9 if ki==2 else 4,1 if s%4 else 2,.32))
  if ki==0:
   for s in [2,6,10,14]:events.append((off+s,12,1,.3))
  if ki==1:events.append((off+14,5,1,.25))
  if bar==3:
   for s,p in [(12,7),(13,7),(14,8),(15,8)]:events.append((off+s,p,2,.65))
 events.sort(); y=np.zeros(round((64*step+5)*FS));counts={}
 for s,p,z,level in events:
  take=counts.get(p,0)%3;counts[p]=take+1;hit=bank[p,z,take];at=round(s*step*FS);y[at:at+len(hit)]+=hit*level
 gain=min(1,.89/max(abs(y)));y*=gain
 write(ROOT/f'{name}-groove.wav',y);write(ROOT/f'{name}-pads.wav',np.concatenate(demos)*.9)
 touch=[]
 for p in [0,1,4,7]:
  for z in range(3):
   for t in range(3):touch.extend([bank[p,z,t],np.zeros(round(.1*FS))])
 write(ROOT/f'{name}-touch.wav',np.concatenate(touch))
 kit['events']=events;kit['groove_gain']=float(gain);kit['render_seconds']=time.perf_counter()-start;manifest['kits'].append(kit)
 pages.append(f'<section><h2>{html.escape(name.replace("-"," ").title())} · {bpm} BPM</h2><p>Original four-bar test arrangement</p><audio controls src="{name}-groove.wav"></audio><p>All 16 pads in order</p><audio controls src="{name}-pads.wav"></audio><p>Touch: kick, snare, hat, rack tom; three soft, three medium, three hard strikes each</p><audio controls src="{name}-touch.wav"></audio></section>')
 print(name,'done',round(kit['render_seconds'],1),'seconds',flush=True)
manifest['verification']={'variants':len(allmetrics),'peak':max(x['peak'] for x in allmetrics),'max_abs_dc':max(abs(x['dc']) for x in allmetrics),'max_tail_rms':max(x['tail_rms'] for x in allmetrics),'max_soft_hard_residual_db':max(p['soft_hard_residual_db'] for k in manifest['kits'] for p in k['pads']),'min_soft_hard_residual_db':min(p['soft_hard_residual_db'] for k in manifest['kits'] for p in k['pads']),'min_take_residual_db':min(p['take_residual_db'] for k in manifest['kits'] for p in k['pads'])}
(ROOT/'manifest.json').write_text(json.dumps(manifest,indent=2));(ROOT/'checks.json').write_text(json.dumps(allmetrics,indent=2))
(ROOT/'index.html').write_text('<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Acoustic drum collection · First study</title><style>body{font:18px system-ui;background:#171c20;color:#eee;max-width:850px;margin:40px auto;padding:20px}section{border-top:1px solid #657078;padding:20px 0}audio{width:100%}p{line-height:1.6}</style><h1>Acoustic drum collection</h1><p>First Python study: three provisional kits using SnipSnap’s EMBER synthesis. No audio from your recordings is played by these instruments. This is a recipe direction, not a completed fit or approved factory collection.</p>'+''.join(pages)+'<p>Pad order: kick, snare, rimshot, cross stick, closed hat, open hat, pedal hat, rack tom, floor tom, ride, ride bell, sizzle, tambourine, kit color (claps / cowbell / high tom), press roll, cymbal swell. The sizzle is a placeholder for a future crash fit.</p>')
with zipfile.ZipFile(ROOT/'audition.zip','w',zipfile.ZIP_DEFLATED) as z:
 for p in ROOT.glob('*.wav'):z.write(p,p.name)
 z.write(ROOT/'index.html','index.html');z.write(ROOT/'manifest.json','manifest.json')
print(json.dumps(manifest['verification']),flush=True)
