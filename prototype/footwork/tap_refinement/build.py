"""Tap-only study: different contact shaping and one-dancer phrases.
Reads the saved native model-3 3x4 grids; no new sampled reference, native version,
or change to the retained Street/Industrial studies.
"""
from pathlib import Path
import sys,json,hashlib
import numpy as np
from scipy import signal
from scipy.io import wavfile
RATE=44100;VEL=[.3,.65,1.];BPM=124
BASE=Path(sys.argv[1])/'footwork-tap-jazz';OUT=Path(sys.argv[2]);OUT.mkdir(parents=True,exist_ok=True)
def read(p):
 r,x=wavfile.read(p);assert r==RATE and x.ndim==1
 return x.astype(float)/(2**31 if x.dtype==np.int32 else 32768)
grids={}
for pad in [2,3,5,6]:
 n=len(read(BASE/f'pad-{pad:02d}.wav'));strip=read(BASE/f'touch-{pad:02d}.wav')
 grids[pad]=[strip[i*(n+5512):i*(n+5512)+n] for i in range(12)]
 assert all(len(x)==n for x in grids[pad])
def contact(pad,v,take,revised=True):
 z=0 if v<.475 else 1 if v<.825 else 2
 x=grids[pad][z*4+take%4].copy()*v/VEL[z];t=np.arange(len(x))/RATE
 if not revised:
  x=signal.sosfilt(signal.butter(2,260,fs=RATE,btype='highpass',output='sos'),x)
  return x*np.exp(-t/.075)
 if pad==3:
  # Preserve the delayed ta-ee plate, reduce the shared low floor response.
  x=signal.sosfilt(signal.butter(2,480,fs=RATE,btype='highpass',output='sos'),x)
  x*=1.12*np.exp(-t/.16)
 elif pad==2:
  # Keep a heel knock's low-mid body rather than high-passing it away with toe.
  x=signal.sosfilt(signal.butter(2,[110,2600],fs=RATE,btype='bandpass',output='sos'),x)
  x*=1.8*np.exp(-t/.040)
 else:
  # Earlier blanket damping buried brush travel before the pressure peak.
  # Preserve the directional envelope, then gate the delayed planted contact.
  x=signal.sosfilt(signal.butter(2,850,fs=RATE,btype='highpass',output='sos'),x)
  end=.075 if pad==5 else .055
  gate=np.clip((end+.015-t)/.015,0,1)
  x*=2.6*gate
 x[-176:]*=np.linspace(1,0,176)
 return x
plans={}
def ev(e,beat,pad,v,foot,gesture):
 e.append({'beat':round(beat,6),'pad':pad,'force':v,'foot':foot,'gesture':gesture})
def shuffle(e,b,foot,force=.65):
 ev(e,b,5,force*.82,foot,'brush');ev(e,b+1/6,6,force,foot,'spank')
def step(e,b,foot,force=.8):ev(e,b,3,force,foot,'ball-tap proxy')
def heel(e,b,foot,force=.6):ev(e,b,2,force,foot,'heel')
def flap(e,b,foot,force=.8):ev(e,b,5,force*.65,foot,'brush');step(e,b+1/6,foot,force)
def cramp(e,b,gap=.25):
 step(e,b,'L',.7);step(e,b+gap,'R',.85);heel(e,b+2*gap,'L',.5);heel(e,b+3*gap,'R',.7)
e=[]
# Four-bar call, answer, quieter question, turnaround. No independent kick/snare
# pulse underneath: all events belong to a single pair of feet.
shuffle(e,0,'L');step(e,1/3,'L',.9);heel(e,2/3,'L',.55)
step(e,1,'R',.65);heel(e,4/3,'R',.45)
shuffle(e,5/3,'L',.7);step(e,2,'L',.85)
step(e,8/3,'R',.8);heel(e,3,'L',.5);heel(e,10/3,'R',.7)
flap(e,4,'R',.9);heel(e,4+1/3,'R',.55)
flap(e,4+2/3,'L',.75);heel(e,5,'L',.5)
shuffle(e,4+5/3,'R',.7);step(e,6,'R',.9)
step(e,4+8/3,'L',.7);heel(e,7,'R',.55);heel(e,4+10/3,'L',.75)
step(e,8,'R',.55);heel(e,8+1/3,'R',.4)
shuffle(e,8+2/3,'L',.48);step(e,9,'L',.65)
# A longer breath makes the last bar's response meaningful.
shuffle(e,10+1/3,'R',.6);step(e,10+2/3,'R',.8);heel(e,11,'R',.5)
cramp(e,12,.25)
shuffle(e,13+1/3,'L',.7);step(e,13+2/3,'L',.85);heel(e,14,'L',.55)
shuffle(e,14+2/3,'R',.75);step(e,15,'R',1.);heel(e,15+1/3,'R',.65)
plans['phrase']={'events':e,'beats':16,'bpm':BPM}
e=[]
cramp(e,0,.24);cramp(e,1.25,.20);shuffle(e,2.5,'L',.6);step(e,2+5/6,'L',.85);heel(e,3+1/6,'L',.55)
plans['roll']={'events':e,'beats':4,'bpm':BPM}
e=[]
for b,foot,force in [(0,'L',.45),(1.5,'R',.6),(2.75,'L',.75),(3.75,'R',.85)]:
 shuffle(e,b,foot,force);step(e,b+1/3,foot,min(1,force+.1));heel(e,b+2/3,foot,force*.7)
shuffle(e,5,'L',.75);step(e,5+1/3,'L',1.);heel(e,5+2/3,'L',.65)
plans['build']={'events':e,'beats':7,'bpm':BPM}
def render(p,v=1.,take=0,revised=True):
 beat=60/p['bpm'];y=np.zeros(round((p['beats']*beat+.3)*RATE))
 for i,a in enumerate(sorted(p['events'],key=lambda a:a['beat'])):
  x=contact(a['pad'],a['force']*v,(i+take)%4,revised)
  at=round(a['beat']*beat*RATE);n=min(len(x),len(y)-at);y[at:at+n]+=.9*x[:n]
 y[-176:]*=np.linspace(1,0,176)
 assert np.isfinite(y).all() and max(abs(y))<.98 and abs(y.mean())<.005 and y[-1]==0
 return y
def write(name,x):
 path=OUT/(name+'.wav');wavfile.write(path,RATE,np.rint(x*32767).astype(np.int16))
 back=read(path);assert np.max(abs(back-x))<5e-5
report={'scope':'Tap-only Python arrangement/contact study; native source model 3 unchanged','plans':plans,'gestureCells':36,'singleContactCells':48,'checks':[]}
for name,p in plans.items():
 p['events'].sort(key=lambda a:a['beat'])
 # Explicit one-dancer event ledger: no accidental double booking of one foot.
 for foot in ['L','R']:
  times=[a['beat']*60/BPM for a in p['events'] if a['foot']==foot]
  assert min(np.diff(times))>.07,(name,foot)
 cells=[[render(p,v,t) for t in range(4)] for v in VEL]
 assert not np.any(render(p,0))
 assert np.array_equal(cells[2][0],render(p))
 for t in range(4):assert all(np.linalg.norm(cells[z][t])<np.linalg.norm(cells[z+1][t]) for z in range(2))
 assert all(not np.array_equal(cells[2][0],x) for x in cells[2][1:])
 write(name,cells[2][0]);write(name+'-touch',np.concatenate([np.r_[x,np.zeros(8820)] for row in cells for x in row]))
 p['peak']=float(max(abs(cells[2][0])));p['eventCount']=len(p['events'])
for pad in grids:
 cells=[[contact(pad,v,t) for t in range(4)] for v in VEL]
 assert all(np.isfinite(x).all() and max(abs(x))<.98 for row in cells for x in row)
 for t in range(4):assert all(np.linalg.norm(cells[z][t])<np.linalg.norm(cells[z+1][t]) for z in range(2))
 write('contact-'+str(pad),np.concatenate([np.r_[x,np.zeros(6615)] for row in cells for x in row]))
write('phrase-old-contacts',render(plans['phrase'],revised=False))
report['checks']=['finite/headroom/DC/tail','determinism','silent velocity zero','four distinct phrase takes','monotonic phrase/contact dynamics','one-foot event separation >70 ms','PCM16 WAV round-trip error <5e-5']
(OUT/'report.json').write_text(json.dumps(report,indent=2)+'\n');print({k:(p['eventCount'],p['peak']) for k,p in plans.items()})
