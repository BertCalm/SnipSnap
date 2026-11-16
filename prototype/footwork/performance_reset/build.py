"""Arrangement/gesture audition only. Uses saved native touch grids and prior study cells.
Source identity is still open; this is not a new accepted kit or native engine version.
"""
from pathlib import Path
import json,sys
import numpy as np
from scipy.io import wavfile
from scipy import signal
RATE=44100;ANCHORS=[.3,.65,1.]
NATIVE=Path(sys.argv[1]);STUDY=Path(sys.argv[2]);OUT=Path(sys.argv[3]);OUT.mkdir(parents=True,exist_ok=True)
KITS={'tap':'footwork-tap-jazz','street':'footwork-street-breaks','industrial':'footwork-industrial-stepping'}
cache={}
def wav(p):
 r,x=wavfile.read(p);assert r==RATE
 return x.astype(float)/(2**31 if x.dtype==np.int32 else 32768 if x.dtype==np.int16 else 1)
def source(kit,pad,v,take,light=False):
 key=(kit,pad)
 if key not in cache:
  if kit=='street' and pad in [1,9,11,12]:
   strip=wav(STUDY/'audio'/f'{pad:02d}-touch.wav');n=13230;gap=8820
  else:
   base=NATIVE/KITS[kit];n=len(wav(base/f'pad-{pad:02d}.wav'));gap=5512;strip=wav(base/f'touch-{pad:02d}.wav')
  cache[key]=[strip[i*(n+gap):i*(n+gap)+n] for i in range(12)]
  assert all(len(x)==n for x in cache[key])
 z=0 if v<.475 else 1 if v<.825 else 2
 x=cache[key][z*4+take%4].copy()*(v/ANCHORS[z])
 if light:
  # Proposed light-load contact: remove low support and damp the sole response.
  # This is disclosed processing, not a new physical tap model.
  x=signal.sosfilt(signal.butter(2,260,fs=RATE,btype='highpass',output='sos'),x)
  x*=np.exp(-np.arange(len(x))/RATE/.075)
 return x
plans={}
def event(events,at,pad,v=0.65,light=False,limb='ensemble'):
 events.append({'at':round(at,6),'pad':pad,'velocity':v,'light':light,'limb':limb})
def render(kit,events,duration,v=1.,take=0):
 y=np.zeros(round(duration*RATE))
 for i,e in enumerate(events):
  x=source(kit,e['pad'],e['velocity']*v,(i+take)%4,e['light'])
  at=round(e['at']*RATE);n=min(len(x),len(y)-at);y[at:at+n]+=1.1*x[:n]
 y[-176:]*=np.linspace(1,0,176)
 assert np.isfinite(y).all() and max(abs(y))<.98 and y[-1]==0
 return y
def write(name,x):wavfile.write(OUT/(name+'.wav'),RATE,np.rint(x*32767).astype(np.int16))
# Four-bar phrases. Timing is in beats until conversion; part-specific placements
# express feel without adding random jitter to conceal an unmusical sequence.
for kit,bpm in [('tap',124),('street',106),('industrial',132)]:
 e=[];beat=60/bpm
 def hit(at,pad,v=.65,light=False,limb='ensemble'):event(e,at*beat,pad,v,light,limb)
 if kit=='tap':
  for bar in range(4):
   b=bar*4
   for at in [0,2]:hit(b+at,2,.40,True,'L')
   for at in [1,3]:hit(b+at,3,.65,True,'R')
   # Swung shuffle pickups answered by step/heel accents.
   for at in ([.67,2.67] if bar%2==0 else [.67,1.67]):
    hit(b+at,5,.45,True,'R');hit(b+at+.16,6,.55,True,'R')
   if bar in [1,3]:
    for at,pad,limb,v in [(2.50,3,'L',.5),(2.75,3,'R',.65),(3.0,2,'L',.45),(3.25,2,'R',.75)]:hit(b+at,pad,v,True,limb)
   if bar==2:hit(b+3.67,12,.4)
 elif kit=='street':
  for bar in range(4):
   b=bar*4
   for at,v in [(0,.95),(1.75,.65),(2.5,.85)]:hit(b+at,1,v)
   for at in [1.03,3.03]:hit(b+at,9,.90)
   for at in [.5,1.5,2.5,3.5]:hit(b+at,12,.4)
   for at in ([.75,2.75] if bar%2==0 else [1.5,3.75]):hit(b+at,9,.30)
   if bar==3:hit(b+3.5,11,.5)
 else:
  for bar in range(4):
   b=bar*4
   for at,v in [(0,1),(1.5,.75),(3,.9)]:hit(b+at,1,v)
   # A repeating 3+3+2 metal ostinato crosses the boot pulse.
   for at,v in [(0,.65),(.75,.45),(1.5,.8),(2,.5),(2.75,.65),(3.5,.85)]:hit(b+at,15,v)
   for at in [1,3]:hit(b+at,14,.65)
   if bar==3:
    for at in [2.5,2.75,3.25,3.5,3.75]:hit(b+at,15,.45+(at-2.5)*.2)
 e.sort(key=lambda x:x['at']);duration=16*beat+.5
 plans[kit]={'bpm':bpm,'events':e,'duration':duration};write(kit+'-groove',render(kit,e,duration))
# A cramp-roll-inspired phrase is step L, step R, heel L, heel R.
# TOE is the existing ball-tap proxy, not an anatomically simulated new step.
roll=[]
for start,step in [(0,.115),(.58,.105),(1.15,.09),(1.67,.08)]:
 for j,(pad,limb,v) in enumerate([(3,'L',.55),(3,'R',.7),(2,'L',.35),(2,'R',.60)]):event(roll,start+j*step,pad,v,True,limb)
event(roll,2.12,5,.45,True,'L');event(roll,2.21,6,.55,True,'L');event(roll,2.32,3,.8,True,'L')
riser=[]
# Increase phrase density through complete brush-spank-step-heel cells; leave a
# breath before the final shuffle/landing. No pitch sweep and no stomp pile-up.
for group,(at,gap) in enumerate([(0,.16),(.76,.13),(1.38,.105),(1.89,.08)]):
 for j,pad in enumerate([5,6,3,2]):event(riser,at+j*gap,pad,.35+.10*group+(0.10 if j==2 else 0),True,'L' if group%2==0 else 'R')
for at,pad,v in [(2.42,5,.65),(2.50,6,.7),(2.63,3,.9),(2.74,2,.55)]:event(riser,at,pad,v,True,'R')
for name,e,duration in [('tap-roll',roll,2.8),('tap-riser',riser,3.2)]:
 plans[name]={'events':e,'duration':duration}
 cells=[[render('tap',e,duration,v,t) for t in range(4)] for v in ANCHORS]
 for t in range(4):assert all(np.linalg.norm(cells[z][t])<np.linalg.norm(cells[z+1][t]) for z in range(2))
 assert np.array_equal(cells[2][0],render('tap',e,duration,1,0))
 assert all(not np.array_equal(cells[2][0],x) for x in cells[2][1:])
 write(name,cells[2][0]);write(name+'-touch',np.concatenate([np.r_[x,np.zeros(8820)] for row in cells for x in row]))
report={'scope':'Arrangement and processed-articulation study, not native kit replacement','sourceModel':3,'newGestureCells':24,'sourceIdentity':'open','MPC3_RR':'open_missing_slice_map','plans':plans}
for name,p in plans.items():
 p['eventCount']=len(p['events']);p['peak']=float(max(abs(wav(OUT/(name+('-groove' if name in KITS else '')+'.wav')))))
(OUT/'report.json').write_text(json.dumps(report,indent=2)+'\n');print({k:(v['eventCount'],v['peak']) for k,v in plans.items()})
