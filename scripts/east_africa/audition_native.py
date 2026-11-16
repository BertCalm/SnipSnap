"""Recreate the approved scores from Kotlin-exported PCM and quantify port differences.
Usage: python3 scripts/east_africa/audition_native.py [native-output] [approved-python-audio]
No synthesis here: all audition hits come from the exported MPC3 chain windows.
"""
from pathlib import Path
import json,sys,zipfile
import numpy as np
from scipy.io import wavfile
from scipy import signal
R=Path(__file__).resolve().parent
OUT=Path(sys.argv[1] if len(sys.argv)>1 else R.parents[1]/'testkit/east-africa-kits')
REFERENCE=Path(sys.argv[2]) if len(sys.argv)>2 else None
A=OUT/'audition';A.mkdir(exist_ok=True)
SR=44100
VOICES=['deep-kick','tight-kick','snare','rimshot','rim','closed-hat','open-hat','shaker','low-bongo','high-bongo','conga','bell','low-log','high-log','hand-roll','shaker-rise','muted-tap','open-conga','clave','wood-knock','metal-low','metal-high']
KITS={'bongo-flava':100,'singeli':200,'bongopiano':112,'genge':96,'kapuka':108}
def read(p):
 sr,x=wavfile.read(p);assert sr==SR
 return x.astype(float)/(2147483648 if x.dtype==np.int32 else 32768)
def write(name,x):
 assert np.isfinite(x).all() and max(abs(x))<1,(name,max(abs(x)))
 wavfile.write(A/name,SR,np.round(x*32767).astype(np.int16))
def add(x,a,at,gain):
 i=round(at*SR);n=min(len(a),len(x)-i)
 if n>0:x[i:i+n]+=a[:n]*gain
def filename(v,z,t):return f'{VOICES.index(v)+1:02d}-{v}-v{z+1}-r{t+1}.wav'
BANK={(k,v,z,t):read(OUT/k/'audio'/filename(v,z,t)) for k in KITS for v in VOICES for z in range(3) for t in range(3)}
colors=json.loads((R/'scores/colors.json').read_text())
for k,bpm in KITS.items():
 if k=='singeli':
  score=json.loads((R/'scores/singeli-pocket.json').read_text())
  rolemap=dict(zip(['kick','snare','rim','hat','tap','wood','low','high'],['tight-kick','snare','rim','closed-hat','muted-tap','wood-knock','metal-low','metal-high']))
  x=np.zeros(423360)
  for e in score['events']:
   c=score['roles'][e['role']];a=BANK[k,rolemap[e['role']],e['zone'],e['take']][:round(c['duration']*SR)].copy()
   a[-176:]*=np.linspace(1,0,176);idx=(round(e['beat']*SR*3/10)+np.arange(len(a)))%len(x)
   np.add.at(x,idx,a*e['gain']*c['gain'])
  write('singeli-loop.wav',x*.8);x=np.tile(x,2);x[-441:]*=np.linspace(1,0,441)
 else:
  events=json.loads((R/f'scores/{k}.json').read_text())['events'];x=np.zeros(round((960/bpm+1.5)*SR))
  for e in events:
   v=VOICES[e['pad']];a=BANK[k,v,e['zone'],e['take']]
   if v=='open-hat':
    nxt=[q['beat'] for q in events if q['pad'] in (5,6) and q['beat']>e['beat']]
    if nxt:
     a=a[:round((min(nxt)-e['beat'])*60/bpm*SR)].copy();f=min(176,len(a));a[-f:]*=np.linspace(1,0,f)
   add(x,a,e['beat']*60/bpm,e['gain'])
  for e in colors[k]:add(x,BANK[k,e['voice'],e['zone'],e['take']],e['beat']*60/bpm,e['gain'])
 write(k+'.wav',x*.8)
write('all-pads.wav',np.concatenate([np.r_[BANK['bongo-flava',v,2,0],np.zeros(8820)] for v in VOICES]))
write('touch.wav',np.concatenate([np.r_[BANK['bongo-flava',v,z,t],np.zeros(4410)] for v in ['tight-kick','snare','low-bongo','closed-hat','shaker','wood-knock'] for z in range(3) for t in range(3)]))
def features(x):
 f,p=signal.periodogram(x,SR,nfft=65536);bands=[30,90,180,350,700,1400,2800,5600,10000,18000];windows=[0,.005,.015,.04,.10,.25,.6,1.2]
 spec=np.array([p[(f>=a)&(f<b)].sum() for a,b in zip(bands,bands[1:])]);spec/=max(sum(spec),1e-20)
 env=np.array([np.sum(x[round(a*SR):round(b*SR)]**2) for a,b in zip(windows,windows[1:])]);env/=max(sum(env),1e-20)
 return 10*np.log10(np.maximum(spec,1e-5)),10*np.log10(np.maximum(env,1e-5))
report={'scope':'Kotlin synthesis with house noise; Python approval does not imply PCM parity or hardware approval.',
 'comparison_scope':'900 single hits; noise texture is not PCM identical. Spectral bands and normalized time energy, not perceived similarity.',
 'variants':len(BANK),'max_peak':max(float(max(abs(x))) for x in BANK.values()),
 'max_dc':max(float(abs(np.mean(x))) for x in BANK.values()),'rows':[]}
if REFERENCE:
 for (k,v,z,t),x in BANK.items():
  if v in ('hand-roll','shaker-rise'):continue
  old=read(REFERENCE/k/filename(v,z,t));a,b=features(old);c,d=features(x)
  report['rows'].append(dict(kit=k,voice=v,zone=z,take=t,spectral_db=float(np.mean(abs(a-c))),envelope_db=float(np.mean(abs(b-d))),rms_delta_db=float(20*np.log10(np.sqrt(np.sum(x*x)/np.sum(old*old))))))
 for metric in ['spectral_db','envelope_db']:
  values=[r[metric] for r in report['rows']];report[metric]={'mean':float(np.mean(values)),'p95':float(np.percentile(values,95)),'max':max(values)}
 original=read(REFERENCE/'singeli-synthesis.wav');native=read(A/'singeli.wav');write('python-then-kotlin.wav',np.r_[original,np.zeros(SR),native])
(OUT/'port-comparison.json').write_text(json.dumps(report,indent=2))
for k in KITS:
 with zipfile.ZipFile(OUT/(k+'-native-kits.zip'),'w',zipfile.ZIP_DEFLATED) as z:
  for p in sorted((OUT/k).rglob('*')):
   if p.is_file() and 'audio' not in p.relative_to(OUT/k).parts:z.write(p,p.relative_to(OUT))
 with zipfile.ZipFile(OUT/(k+'-native-kits.zip')) as z:assert z.testzip() is None
print(json.dumps({k:v for k,v in report.items() if k!='rows'},indent=2))
