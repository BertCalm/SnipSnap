"""Genre candidates derived from accepted synthetic PCM; no acoustic samples.
Run: python genre_audition.py /absolute/site/dist
Linear resampling, filtering and envelope shaping preserve each independent take.
"""
from pathlib import Path
from fractions import Fraction
import gzip, io, json, hashlib, sys, zipfile
import numpy as np
from scipy import signal
from scipy.io import wavfile
ROOT=Path(__file__).resolve().parents[2]
BANK=ROOT/'synth/src/main/resources/knee-break/v1'
OUT=Path(sys.argv[1])/'genres';(OUT/'audio').mkdir(parents=True,exist_ok=True)
SR=44100
NAMES=['Kick','Snare','Closed Hat','Open Hat','Short Kick','Ghost Snare','Rimshot','Cross-stick','Low Tom','Rack Tom','Pedal Hat','Ride','Shaker','Woodblock','Snare Roll','Snare Riser']
CONFIG={
 'boom-bap':dict(bpm=90,body='body',fx='knee',pitch=[-2,1,0,-1,-3,0,2,0,-2,-1,0,-2,0,-1,0,0],cut=8500,decay=.55),
 'trip-hop':dict(bpm=76,body='body',fx='cassette',pitch=[-4,-3,-2,-3,-5,-3,-2,-2,-4,-3,-2,-4,-2,-3,-3,-3],cut=5200,decay=2.2),
 'funk':dict(bpm=108,body='tight',fx='close',pitch=[1,2,1,1,2,1,2,1,0,2,1,0,1,2,1,1],cut=13500,decay=.8)}
manifest=[];sounds={};stats=[]
def write(path,x):
 assert np.isfinite(x).all() and np.max(abs(x))<.98
 pcm=np.round(x*32767).astype('<i2');wavfile.write(path,SR,pcm)
 return pcm.astype(float)/32767
for genre,c in CONFIG.items():
 for slot in range(1,17):
  # Genre-specific mic choices retain kick definition and softer ghost detail.
  fx=c['fx']
  if genre=='boom-bap' and slot in [3,4,8,11,13,14]:fx='close'
  if genre=='trip-hop' and slot in [1,5,6]:fx='knee'
  body=c['body'];semitones=c['pitch'][slot-1]
  ratio=Fraction(2**(-semitones/12)).limit_denominator(1000)
  energies=[]
  for z in range(3):
   energy=[];takes=[]
   for t in range(3):
    source=f'{body}/{fx}/{slot}-{z}-{t}.wav.gz';raw=gzip.decompress((BANK/source).read_bytes());sr,x=wavfile.read(io.BytesIO(raw));assert sr==SR
    y=signal.resample_poly(x.astype(float)/32767,ratio.numerator,ratio.denominator)
    y=signal.sosfilt(signal.butter(2,c['cut'],fs=SR,output='sos'),y)
    # Preserve the original attacks, then sculpt decay; gestures keep their score.
    if slot<15:y*=np.exp(-np.maximum(0,np.arange(len(y))/SR-.045)/c['decay'])
    y=signal.sosfilt(signal.butter(2,22,fs=SR,btype='highpass',output='sos'),y)
    active=np.flatnonzero(abs(y)>10**(-65/20));end=min(len(y),int(active[-1])+441) if len(active) else 1
    y=y[:end].copy();n=min(len(y),353);y[-n:]*=np.linspace(1,0,n);y=np.r_[y,np.zeros(220)]
    name=f'{genre}-{slot}-{z}-{t}.wav';y=write(OUT/'audio'/name,y);sounds[genre,slot,z,t]=y
    energy.append(float(y@y));takes.append(y)
    manifest.append(dict(genre=genre,slot=slot,zone=z,take=t,name=NAMES[slot-1],file='audio/'+name,frames=len(y),source=source,sourceSha256=hashlib.sha256(raw).hexdigest(),sha256=hashlib.sha256((OUT/'audio'/name).read_bytes()).hexdigest()))
   assert len({hashlib.sha256(x.tobytes()).hexdigest() for x in takes})==3
   energies.append(float(np.mean(energy)))
  assert energies[0]<energies[1]<energies[2],(genre,slot,energies)
  stats.append(dict(genre=genre,slot=slot,velocityEnergy=energies))
 print(genre,'144 pad renders verified',flush=True)

def score(genre):
 events=[]
 def add(sl,steps,z=2,g=.85):
  events.extend((float(s),sl,z,g) for s in steps)
 if genre=='boom-bap':
  add(1,[0,7,10,16,22,27,32,39,42,48,54,58]);add(7,[4,12,20,28,36,44,52,60],2,.7)
  add(3,[s+(.22 if s%4==2 else 0) for s in range(0,64,2)],1,.65);add(6,[11.25,26.25,43.25,59.25],0,.6);add(4,[14,30,46],1,.6);add(15,[62],1,.45)
 elif genre=='trip-hop':
  add(1,[0,11,16,27,32,42,48,59]);add(2,[8,24,40,56],2,.85)
  add(3,range(0,64,4),0,.65);add(8,[6,22,38,54],1,.6);add(13,range(2,64,4),1,.5);add(12,[0,32],1,.4);add(9,[47,63],1,.7);add(16,[57],0,.3)
 else:
  add(1,[0,6,10,16,19,26,32,38,42,48,51,58]);add(2,[4,12,20,28,36,44,52,60],2,.75)
  add(3,range(64),0,.55);add(3,range(0,64,4),1,.3);add(6,[3,7,11,15,19,23,27,31,35,39,43,47,51,55],0,.65)
  add(4,[14,30,46],1,.5);add(11,[15,31,47],1,.6);add(13,range(2,64,4),1,.45);add(10,[61],1,.6);add(9,[62],2,.65);add(7,[63],2,.65)
 return sorted(events)
common=sorted([(s,1,2,.8) for s in [0,10,16,26,32,42,48,58]]+[(s,2,2,.8) for s in range(4,64,8)]+[(s,3,1,.55) for s in range(0,64,2)]+[(15,4,1,.5),(31,8,1,.6),(47,12,1,.4),(60,15,1,.4)])
def groove(genre,events,bpm):
 step=60/bpm/4;out=np.zeros(round((64*step+4)*SR));hats=[s for s,p,z,g in events if p in [3,4,11]];counts={}
 for s,p,z,g in events:
  key=(p,z);take=counts.get(key,0)%3;counts[key]=take+1;x=sounds[genre,p,z,take].copy()*g*.65
  if p in [3,4,11]:
   later=[h for h in hats if h>s]
   if later:
    x=x[:max(1,round((min(later)-s)*step*SR))];n=min(220,len(x));x[-n:]*=np.linspace(1,0,n)
  at=round(s*step*SR);x=x[:len(out)-at];out[at:at+len(x)]+=x
 return out
for kind in ['genre','common']:
 mixes={g:groove(g,score(g) if kind=='genre' else common,CONFIG[g]['bpm'] if kind=='genre' else 94) for g in CONFIG}
 # A common RMS target removes first-order loudness bias, with one safe ceiling.
 scaled={g:x/(np.sqrt(np.mean(x*x))+1e-12)*.075 for g,x in mixes.items()};gain=min(1,.9/max(np.max(abs(x)) for x in scaled.values()))
 for g,x in scaled.items():write(OUT/'audio'/f'{g}-{kind}.wav',x*gain)
for g,c in CONFIG.items():
 entries=[e for e in manifest if e['genre']==g];sfz=['<control> default_path=audio/']
 for e in entries:
  lo,hi=[(1,41),(42,84),(85,127)][e['zone']];hat=' group=1 off_by=1 off_mode=fast' if e['slot'] in [3,4,11] else ''
  sfz.append(f"<region> sample={Path(e['file']).name} key={35+e['slot']} lovel={lo} hivel={hi} seq_length=3 seq_position={e['take']+1} loop_mode=one_shot volume=-3.742{hat}")
 (OUT/f'{g}.sfz').write_text('\n'.join(sfz)+'\n')
 with zipfile.ZipFile(OUT/f'{g}.zip','w',zipfile.ZIP_DEFLATED) as z:
  for e in entries:z.write(OUT/e['file'],e['file'])
  z.write(OUT/f'{g}.sfz',f'{g}.sfz');z.writestr('README.txt',f'Knee Break {g} candidate; {c["bpm"]} BPM. 16 pads, MIDI 36-51, 3 velocities x 3 strikes. Effects printed. Extract before loading SFZ. Not yet a native shelf kit or hardware-verified genre expansion.\n');z.writestr('manifest.json',json.dumps(entries,indent=2));z.write(BANK/'REFERENCE-LICENSE.txt','REFERENCE-LICENSE.txt')
 with zipfile.ZipFile(OUT/f'{g}.zip') as z:
  assert z.testzip() is None
  for e in entries:assert z.read(e['file'])==(OUT/e['file']).read_bytes()
(OUT/'manifest.json').write_text(json.dumps(dict(config=CONFIG,entries=manifest),indent=2))
(OUT/'scores.json').write_text(json.dumps({g:score(g) for g in CONFIG},indent=2))
report=dict(pads=len(manifest),velocityOrdering='48/48 passed',uniqueTakes='144/144 groups passed',maxPeak=max(float(np.max(abs(x))) for x in sounds.values()),maxDC=max(float(abs(np.mean(x))) for x in sounds.values()),lastSample='all zero',packageBytesVerified=True,hardware='new genre candidates unverified',measurements=stats)
(OUT/'verification.json').write_text(json.dumps(report,indent=2));print({k:v for k,v in report.items() if k!='measurements'})
