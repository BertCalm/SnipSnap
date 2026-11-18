"""Restore tap contact weight and author connected timing over the previous phrase.
No new native source model, recorded audio, automatic 'humanize', or per-hit normalization.
"""
from pathlib import Path
import sys,json,copy
import numpy as np
from scipy import signal
from scipy.io import wavfile
RATE=44100;VEL=[.3,.65,1.]
BASE=Path(sys.argv[1])/'footwork-tap-jazz';PRIOR=Path(sys.argv[2]);OUT=Path(sys.argv[3]);OUT.mkdir(parents=True,exist_ok=True)
def read(p):
 r,x=wavfile.read(p);assert r==RATE and x.ndim==1
 return x.astype(float)/(2**31 if x.dtype==np.int32 else 32768)
grids={}
for pad in [2,3,5,6]:
 n=len(read(BASE/f'pad-{pad:02d}.wav'));strip=read(BASE/f'touch-{pad:02d}.wav')
 grids[pad]=[strip[i*(n+5512):i*(n+5512)+n] for i in range(12)]
 assert all(len(x)==n for x in grids[pad])
def contact(pad,v,take):
 z=0 if v<.475 else 1 if v<.825 else 2
 x=grids[pad][z*4+take%4].copy()*v/VEL[z];t=np.arange(len(x))/RATE
 if pad in [2,3]:
  # Preserve native contact + plate + sole/floor, rather than removing body.
  # Broad low-mid support is audible on small speakers without adding sub bass.
  x=signal.sosfilt(signal.butter(2,70,fs=RATE,btype='highpass',output='sos'),x)
  body=signal.sosfilt(signal.butter(2,[180,1050],fs=RATE,btype='bandpass',output='sos'),x)
  x=x+.40*body
  if pad==2:x*=1.3  # Fixed heel balance across all strengths and takes.
 else:
  x=signal.sosfilt(signal.butter(2,500,fs=RATE,btype='highpass',output='sos'),x)
  end=.082 if pad==5 else .062
  x*=2.25*np.clip((end+.018-t)/.018,0,1)
 x[-176:]*=np.linspace(1,0,176)
 return x
plans=copy.deepcopy(json.loads(PRIOR.read_text())['plans'])
for p in plans.values():
 p.pop('peak',None)
 for a in p['events']:
  # Retain accent hierarchy, but stop asking every contact to behave like a ghost.
  a['originalForce']=a['force'];a['force']=float(np.sqrt(a['force'])*(0.96 if a['pad'] in [5,6] else 1.))
  a['force']=min(1.,a['force'])
  beat=a['beat'];within=beat%4
  # Phrase-level push/release is shared by all contacts, not independent jitter.
  bow=.010*np.sin(np.pi*within/2)
  # A brush/spank is a connected sweep, followed by a slightly settled landing.
  movement={5:-.008,6:-.018,3:.006,2:.010}[a['pad']]
  foot_bias=-.002 if a['foot']=='L' else .002
  a['performedSeconds']=round(max(0.,beat*60/p['bpm']+bow+movement+foot_bias),6)
  a['timingOffsetMs']=round(1000*(a['performedSeconds']-beat*60/p['bpm']),3)
def render(p,v=1.,take=0,performed=True):
 y=np.zeros(round((p['beats']*60/p['bpm']+.3)*RATE));counts={}
 for a in p['events']:
  key=(a['pad'],a['foot']);count=counts.get(key,0);counts[key]=count+1
  # Each foot/contact cycles its own takes, avoiding event-index lock to a pad.
  rr=(count+take+(2 if a['foot']=='R' else 0))%4
  x=contact(a['pad'],a['force']*v,rr)
  at=round((a['performedSeconds'] if performed else a['beat']*60/p['bpm'])*RATE)
  n=min(len(x),len(y)-at);y[at:at+n]+=.95*x[:n]
 y[-176:]*=np.linspace(1,0,176)
 assert np.isfinite(y).all() and max(abs(y))<.98 and abs(y.mean())<.005 and y[-1]==0
 return y
def write(name,x):
 path=OUT/(name+'.wav');wavfile.write(path,RATE,np.rint(x*32767).astype(np.int16))
 assert np.max(abs(read(path)-x))<5e-5
report={'scope':'Tap-only weight and authored timing study; no measured human-performance claim','sourceModel':3,'plans':plans,'gestureCells':36,'contactCells':48,'checks':[]}
for name,p in plans.items():
 times=[a['performedSeconds'] for a in p['events']];assert np.all(np.diff(times)>0)
 for foot in ['L','R']:
  t=[a['performedSeconds'] for a in p['events'] if a['foot']==foot]
  assert min(np.diff(t))>.045,(name,foot)
 cells=[[render(p,v,t) for t in range(4)] for v in VEL]
 assert not np.any(render(p,0))
 assert np.array_equal(cells[2][0],render(p))
 for t in range(4):assert all(np.linalg.norm(cells[z][t])<np.linalg.norm(cells[z+1][t]) for z in range(2))
 assert all(not np.array_equal(cells[2][0],x) for x in cells[2][1:])
 write(name,cells[2][0]);write(name+'-grid',render(p,performed=False))
 write(name+'-touch',np.concatenate([np.r_[x,np.zeros(8820)] for row in cells for x in row]))
 p['peak']=float(max(abs(cells[2][0])));p['rms']=float(np.sqrt(np.mean(cells[2][0]**2)))
for pad in grids:
 cells=[[contact(pad,v,t) for t in range(4)] for v in VEL]
 assert all(np.isfinite(x).all() and max(abs(x))<.98 for row in cells for x in row)
 for t in range(4):assert all(np.linalg.norm(cells[z][t])<np.linalg.norm(cells[z+1][t]) for z in range(2))
 write('contact-'+str(pad),np.concatenate([np.r_[x,np.zeros(6615)] for row in cells for x in row]))
report['checks']=['finite/headroom/DC/tail','determinism','zero-velocity silence','four distinct phrase takes','increasing phrase/contact dynamics','ordered timing and single-foot gaps >45 ms','PCM16 round-trip error <5e-5']
(OUT/'report.json').write_text(json.dumps(report,indent=2)+'\n');print({k:{a:p[a] for a in ['peak','rms']} for k,p in plans.items()})
