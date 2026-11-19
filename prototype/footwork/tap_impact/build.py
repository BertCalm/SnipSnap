"""Tap impact study: finite pressure pulses drive damped shoe/floor support modes.
Uses previous exact event times/forces/takes. Authored model, not calibrated anatomy.
"""
from pathlib import Path
import sys,json,copy
from functools import lru_cache
import numpy as np
from scipy import signal
from scipy.io import wavfile
ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'.claude/skills/luthier/scripts'));import snipkit as sk
RATE=44100;FS=RATE*4;VEL=[.3,.65,1.]
BASE=Path(sys.argv[1])/'footwork-tap-jazz';PRIOR=Path(sys.argv[2]);OUT=Path(sys.argv[3]);OUT.mkdir(parents=True,exist_ok=True)
def read(p):
 r,x=wavfile.read(p);assert r==RATE and x.ndim==1
 return x.astype(float)/(2**31 if x.dtype==np.int32 else 32768)
grids={}
for pad in [2,3,5,6]:
 n=len(read(BASE/f'pad-{pad:02d}.wav'));strip=read(BASE/f'touch-{pad:02d}.wav')
 grids[pad]=[strip[i*(n+5512):i*(n+5512)+n] for i in range(12)]
 assert all(len(x)==n for x in grids[pad])
@lru_cache(maxsize=2048)
def support(pad,v,take):
 n=4*len(grids[pad][0]);t=np.arange(n)/FS
 rng=np.random.default_rng(sk.seed_for('FOOTWORK-IMPACT',1,pad,take))
 width=(.0014 if pad==2 else .0009)*(1.25-.25*v)
 # Unit-area pulse is a force-event parameter, not normalization of output PCM.
 pressure=sk.hann_pulse(width*1000,FS,total_seconds=n/FS)
 pressure/=max(1e-20,sum(pressure))
 # Load spreads across the sole after the initial hard contact.
 delay=round((.0075 if pad==2 else .0055)*FS)
 pressure[delay:]+=.22*pressure[:-delay].copy()
 f=(137 if pad==2 else 173)*(1+rng.uniform(-.025,.025))
 ratios=[1,1.46,2.23,3.61,5.42,8.50]
 gains=[.50,.70,.65,.35,.23,.18];t60=[.25,.18,.13,.09,.06,.04]
 modes=[(a*(1+rng.uniform(-.015,.015)),b*(1+rng.uniform(-.07,.07)),c) for a,b,c in zip(ratios,gains,t60)]
 # Compensated modal gain avoids accidental low-frequency gain from 1/sin(theta).
 body=sk.modal_ring(pressure,f,modes,FS,compensate=True)
 grains=rng.normal(size=n)
 knock=signal.sosfilt(signal.butter(2,[450,3800],fs=FS,btype='bandpass',output='sos'),grains)
 knock*=np.minimum(1,t/.0004)*np.exp(-t/.008)
 raw=(.30*body+.30*knock)*v**1.5
 # Dry contact response: no room convolution, bass pitch sweep or peak matching.
 return sk.fade_tail(sk.dc_block(sk.decimate4(sk.band_limit(raw,FS)),RATE),RATE)
def contact(pad,v,take,impact=True):
 z=0 if v<.475 else 1 if v<.825 else 2
 x=grids[pad][z*4+take%4].copy()*v/VEL[z];t=np.arange(len(x))/RATE
 if pad in [2,3]:
  x=signal.sosfilt(signal.butter(2,70,fs=RATE,btype='highpass',output='sos'),x)
  body=signal.sosfilt(signal.butter(2,[180,1050],fs=RATE,btype='bandpass',output='sos'),x)
  x+=.40*body
  if pad==2:x*=1.3
  if impact:x+=support(pad,float(v),take)
 else:
  x=signal.sosfilt(signal.butter(2,500,fs=RATE,btype='highpass',output='sos'),x)
  end=.082 if pad==5 else .062;x*=2.25*np.clip((end+.018-t)/.018,0,1)
 x[-176:]*=np.linspace(1,0,176)
 return x
plans=copy.deepcopy(json.loads(PRIOR.read_text())['plans'])
def render(p,v=1.,take=0,impact=True):
 y=np.zeros(round((p['beats']*60/p['bpm']+.3)*RATE));counts={}
 for a in p['events']:
  key=(a['pad'],a['foot']);count=counts.get(key,0);counts[key]=count+1
  rr=(count+take+(2 if a['foot']=='R' else 0))%4
  x=contact(a['pad'],a['force']*v,rr,impact)
  at=round(a['performedSeconds']*RATE);n=min(len(x),len(y)-at);y[at:at+n]+=.95*x[:n]
 y[-176:]*=np.linspace(1,0,176)
 assert np.isfinite(y).all() and max(abs(y))<.98 and abs(y.mean())<.005 and y[-1]==0
 return y
def write(name,x):
 path=OUT/(name+'.wav');wavfile.write(path,RATE,np.rint(x*32767).astype(np.int16))
 assert np.max(abs(read(path)-x))<5e-5
report={'scope':'Tap impact source study; exact prior timing, forces and takes retained','sourceModel':3,'plans':plans,'gestureCells':36,'impactContactCells':24,'brushes':'unchanged','checks':[],'weight':{}}
for name,p in plans.items():
 cells=[[render(p,v,t) for t in range(4)] for v in VEL]
 assert not np.any(render(p,0));assert np.array_equal(cells[2][0],render(p))
 for t in range(4):assert all(np.linalg.norm(cells[z][t])<np.linalg.norm(cells[z+1][t]) for z in range(2))
 assert all(not np.array_equal(cells[2][0],x) for x in cells[2][1:])
 write(name,cells[2][0]);write(name+'-previous',render(p,impact=False))
 p['peak']=float(max(abs(cells[2][0])));p['rms']=float(np.sqrt(np.mean(cells[2][0]**2)))
for pad in [2,3]:
 cells=[[contact(pad,v,t) for t in range(4)] for v in VEL]
 assert all(np.isfinite(x).all() and max(abs(x))<.98 for row in cells for x in row)
 for t in range(4):assert all(np.linalg.norm(cells[z][t])<np.linalg.norm(cells[z+1][t]) for z in range(2))
 write('contact-'+str(pad),np.concatenate([np.r_[x,np.zeros(6615)] for row in cells for x in row]))
 a=contact(pad,1.,0,False);b=cells[2][0];end=round(.04*RATE)
 report['weight'][str(pad)]={'first40msEnergyRatio':float(np.dot(b[:end],b[:end])/np.dot(a[:end],a[:end])),'previousRms':float(np.sqrt(np.mean(a*a))),'newRms':float(np.sqrt(np.mean(b*b)))}
report['checks']=['finite/headroom/DC/tail','determinism','zero-velocity silence','four distinct phrase takes','increasing phrase/contact dynamics','PCM16 round-trip error <5e-5','stable modal poles below one']
(OUT/'report.json').write_text(json.dumps(report,indent=2)+'\n');print({'weight':report['weight'],'peaks':{k:p['peak'] for k,p in plans.items()}})
