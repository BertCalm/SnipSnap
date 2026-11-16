"""R2: recorded acoustic candidates. No reference-video audio or added effects.
Usage: python prototype/knee-break/revise_dry.py SITE_DIST
"""
from pathlib import Path
import sys,json,hashlib,shutil
import numpy as np
from scipy.io import wavfile
from scipy import signal
ROOT=Path(__file__).resolve().parents[2];BANK=ROOT/'synth/src/main/resources/spore-station/v7'
OUT=Path(sys.argv[1]);OUT.mkdir(parents=True,exist_ok=True);(OUT/'audio').mkdir(exist_ok=True)
SR=44100;voices={'kick':'kick','snare':'snare','closed-hat':'closed','open-hat':'open'}
provenance=json.loads((BANK/'provenance.json').read_text());used=[];cache={};manifest=[]
def read(p):
 sr,x=wavfile.read(p);assert sr==SR and x.ndim==1
 return x.astype(float)/(2147483648 if x.dtype==np.int32 else 32768)
def write(name,x):
 assert np.isfinite(x).all() and abs(x).max()<.95
 pcm=np.round(x*32767).astype('<i2');wavfile.write(OUT/'audio'/name,SR,pcm)
 manifest.append({'file':'audio/'+name,'frames':len(pcm),'sha256':hashlib.sha256((OUT/'audio'/name).read_bytes()).hexdigest()})
def finish(x):
 x=signal.sosfilt(signal.butter(2,22,fs=SR,btype='highpass',output='sos'),x)
 active=np.flatnonzero(abs(x)>10**(-65/20));end=min(len(x),active[-1]+1103) if len(active) else 1;x=x[:end].copy();n=min(len(x),353);x[-n:]*=np.linspace(1,0,n)
 return np.r_[x,np.zeros(882)]
for voice,bankvoice in voices.items():
 for z in range(3):
  for take in range(3):
   e=next(e for e in provenance if e['voice']==bankvoice and e['zone']==z and e['take']==take);p=BANK/e['resource'];assert hashlib.sha256(p.read_bytes()).hexdigest()==e['resourceSha256'];used.append(e)
   source=read(p);t=np.arange(len(source))/SR
   # Envelope leaves the initial contact and body intact. Recorded room remains.
   for candidate in ['body','tight']:
    onset={'kick':.065,'snare':.055,'closed-hat':.025,'open-hat':.09}[voice]
    tau={'kick':.17,'snare':.15,'closed-hat':.09,'open-hat':.28}[voice]*(1 if candidate=='body' else .55)
    x=source*np.exp(-np.maximum(0,t-onset)/tau)
    # One fixed gain per instrument for all recorded velocity layers and takes.
    x*= {'kick':.8,'snare':.8,'closed-hat':.34,'open-hat':.32}[voice]
    x=finish(x);cache[candidate,voice,z,take]=x;write(f'{candidate}-{voice}-{z}-{take}.wav',x)
# Original pattern and timing from R1, including hat closures.
step=60/94/4;n=round((32*step+2)*SR);events=[]
for s in range(0,32,2):events.append((s,'closed-hat',1 if s%4==0 else 0))
for s in [0,7,10,16,22,26]:events.append((s,'kick',2 if s%8==0 else 1))
for s in [4,12,20,28]:events.append((s,'snare',2))
for s in [3,11,19,23,27,30]:events.append((s,'snare',0))
events=[e for e in events if not(e[0] in [14,30] and e[1]=='closed-hat')]+[(14,'open-hat',1),(30,'open-hat',1)]
hats=sorted(e[0] for e in events if 'hat' in e[1]);grooves={}
for c in ['body','tight']:
 y=np.zeros(n)
 for i,(s,v,z) in enumerate(sorted(events)):
  x=cache[c,v,z,i%3].copy();at=round(s*step*SR)
  if v=='open-hat':
   later=[h for h in hats if h>s]
   if later:
    x=x[:round((later[0]-s)*step*SR)];f=min(220,len(x));x[-f:]*=np.linspace(1,0,f)
  y[at:at+len(x)]+=x
 grooves[c]=y
# Exactly the archived rejected groove, then level-matched by a single gain.
grooves['old']=read(OUT.parent/'round1/audio/groove-close.wav')
rms={c:float(np.sqrt(np.mean(y[:round(32*step*SR)]**2))) for c,y in grooves.items()}
target=min(.12,min(.89*rms[c]/max(abs(y)) for c,y in grooves.items()));gains={}
for c,y in grooves.items():gains[c]=target/rms[c];write(f'groove-{c}.wav',y*gains[c])
# Gain-matched hard first strikes for direct timbre comparison; full grids retain dynamics.
for v in voices:
 xs={c:cache[c,v,2,0] for c in ['body','tight']};xs['old']=read(OUT.parent/f'round1/audio/close-{v}-2-0.wav')
 r={c:np.sqrt(np.mean(x[:min(len(x),8820)]**2)) for c,x in xs.items()};target=min(.12,min(.89*r[c]/max(abs(x)) for c,x in xs.items()))
 for c,x in xs.items():write(f'compare-{c}-{v}.wav',x*target/r[c])
minimum=1.;maxdc=0.;maxpeak=0.;ordering={}
for c in ['body','tight']:
 for v in voices:
  energies=[]
  for z in range(3):
   row=[read(OUT/f'audio/{c}-{v}-{z}-{t}.wav') for t in range(3)];energies.append(float(np.mean([x@x for x in row])))
   for i,a in enumerate(row):
    maxpeak=max(maxpeak,float(abs(a).max()));maxdc=max(maxdc,float(abs(a.mean())));assert a[-1]==0
    for b in row[i+1:]:
     n=max(len(a),len(b));aa=np.pad(a,(0,n-len(a)));bb=np.pad(b,(0,n-len(b)));res=float(np.sum((bb-aa*(aa@bb)/(aa@aa))**2)/(bb@bb));minimum=min(minimum,res);assert res>1e-6
  assert np.all(np.diff(energies)>0),(c,v,energies);ordering[c+'-'+v]=energies
assert maxdc<.005 and maxpeak<.95
report={'status':'72 acoustic candidate WAVs checked; listening approval pending','minimum_take_residual':minimum,'maximum_absolute_dc':maxdc,'maximum_peak':maxpeak,'layer_mean_energies':ordering,'groove_comparison_gains':gains,'comparison':'grooves RMS-matched over two bars; hard hits RMS-matched over first 200 ms, peak cap .89','snare_takes':'distinct neighbouring recorded dynamics, not same-dynamic recorded round robins','source':'Virtuosity Drums CC0, repository bank at f1842eb48; no samples from user videos'}
(OUT/'manifest.json').write_text(json.dumps(manifest,indent=2));(OUT/'verification.json').write_text(json.dumps(report,indent=2));(OUT/'provenance.json').write_text(json.dumps(used,indent=2));shutil.copyfile(BANK/'LICENSE.txt',OUT/'LICENSE.txt');print(json.dumps(report,indent=2))
