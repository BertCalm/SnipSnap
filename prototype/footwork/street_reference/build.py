"""Street source study: measured time-varying band energies, newly generated noise.
No reference PCM or phase is used at synthesis time. Python study, not native delivery.
"""
import json,sys,hashlib
from pathlib import Path
import numpy as np
from scipy import signal,ndimage
from scipy.io import wavfile
ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'.claude/skills/luthier/scripts'));import snipkit as sk
RATE=44100;FS=4*RATE;DURATION=.3;N=round(DURATION*RATE)
EDGES=np.geomspace(45,16000,29)
TIMES=np.r_[0,np.arange(.001,.041,.002),np.arange(.045,.301,.005)]
ROLES={1:('Stamp','stomp_l'),2:('Heel','heel_l'),9:('Hand clap','handclap'),11:('Chest thump','chestthump_l'),12:('Finger snap','snap_l')}
SHA='4ac9d8966679c648b62fa10a188179e186b97f24'
def read(path):
 r,x=wavfile.read(path);assert r==RATE
 x=x.astype(float)/(2**31 if x.dtype==np.int32 else 2**15 if x.dtype==np.int16 else 1.)
 if x.ndim==2:x=x.mean(axis=1)
 # Recorded floor/handling rumble dominated the 20-40 Hz range. The same disclosed high-pass
 # conditioning is applied to reference auditions and analysis, and disclosed.
 x=signal.sosfilt(signal.butter(4,120 if path.name.startswith(('heel','stomp')) else 80,fs=RATE,btype='highpass',output='sos'),x)
 e=ndimage.uniform_filter1d(x*x,22)
 onset=max(0,np.flatnonzero(e>.02*max(e))[0]-22)
 y=np.zeros(N);a=x[onset:onset+N];y[:len(a)]=a
 return sk.fade_tail(sk.dc_block(y,RATE),RATE)
def fit(group):
 # Equal-energy alignment only for analysis; gain is fixed at source-family level.
 energy=np.median([np.dot(x,x) for x in group]);group=[x*np.sqrt(energy/np.dot(x,x)) for x in group]
 curves=[]
 for lo,hi in zip(EDGES[:-1],EDGES[1:]):
  sos=signal.butter(2,[lo,hi],fs=RATE,btype='bandpass',output='sos')
  rows=[]
  for x in group:
   b=signal.sosfilt(sos,x);smooth=ndimage.uniform_filter1d(b*b,max(44,round(RATE/min(hi-lo,800))))
   rows.append(np.interp(TIMES,np.arange(N)/RATE,np.sqrt(np.maximum(0,smooth))))
  curves.append(np.mean(rows,axis=0).tolist())
 return curves
FILTERS=[]
for lo,hi in zip(EDGES[:-1],EDGES[1:]):
 sos=signal.butter(2,[lo,hi],fs=FS,btype='bandpass',output='sos');_,h=signal.sosfreqz(sos,worN=32768)
 FILTERS.append((sos,np.sqrt(np.mean(abs(h)**2))))
def render(m,v=1.,take=0):
 assert 0<=v<=1 and take in range(4)
 rng=np.random.default_rng(sk.seed_for('FOOTWORK-STREET-STUDY',1,m['role'],take))
 n=round(DURATION*FS);t=np.arange(n)/FS;out=np.zeros(n)
 soft=np.array(m['soft']);hard=np.array(m['hard']);blend=np.clip((v-.3)/.7,0,1)
 envs=(1-blend)*soft+blend*hard
 # Repeat variation changes excitation, band balance and contact timing, not only gain.
 warp=1+rng.uniform(-.025,.025)
 for b,(sos,std) in enumerate(FILTERS):
  white=rng.normal(size=n+FS//10);band=signal.sosfilt(sos,white)[-n:]/std
  env=np.interp(t*warp,TIMES,envs[b],right=0)
  tilt=(np.sqrt(EDGES[b]*EDGES[b+1])/1300)**((v-1)*.10)
  out+=band*env*(1+rng.uniform(-.05,.05))*tilt
 out*=np.minimum(1,t/.00035)*v**1.25*m['gain']
 return sk.fade_tail(sk.dc_block(sk.decimate4(sk.band_limit(out,FS)),RATE),RATE)
def feature(x):
 e=x*x;f,p=signal.welch(x,RATE,nperseg=2048)
 return {'peak':float(max(abs(x))),'rms':float(np.sqrt(e.mean())), 'centroidHz':float(sum(f*p)/sum(p)), 't90ms':float(np.searchsorted(np.cumsum(e),.9*sum(e))/RATE*1000)}
def main(source,out):
 source=Path(source);out=Path(out);out.mkdir(parents=True,exist_ok=True);audio=out/'audio';audio.mkdir(exist_ok=True)
 models={};report={'scope':'Python study, 5 roles x 3 velocity levels x 4 takes; no native/save/export claim','sourceCommit':SHA,'roles':{},'unresolved':[10,13,16]}
 for slot,(name,prefix) in ROLES.items():
  paths=sorted(source.glob(prefix+'*.wav'));assert paths
  train=[p for p in paths if any(p.stem.endswith('rr'+str(i)) for i in [1,2,3])]
  hold=[p for p in paths if p.stem.endswith('rr4')]
  hard=[read(p) for p in train if 'vl2' in p.name or prefix=='snap_l']
  soft=[read(p) for p in train if 'vl1' in p.name] or hard
  softenv=np.array(fit(soft));hardenv=np.array(fit(hard))
  # Interpolate timbre, with explicit full-dynamics force law. Avoid confusing the
  # source library's unspecified recorded touch strengths with exact MIDI anchors.
  softenv*=np.linalg.norm(hardenv)/np.linalg.norm(softenv)
  m={'role':name,'slot':slot,'gain':1.,'soft':softenv.tolist(),'hard':hardenv.tolist()}
  # Fixed calibration from training references and four nominal synthesis takes.
  nominal=[render(m,1,t) for t in range(4)]
  m['gain']=float(.65/max(max(abs(x)) for x in nominal))
  cells=[[render(m,v,t) for t in range(4)] for v in [.3,.65,1]]
  for row in cells:
   for x in row:assert np.isfinite(x).all() and max(abs(x))<.98 and abs(x.mean())<.005 and x[-1]==0
   assert all(not np.array_equal(row[0],x) for x in row[1:])
  assert np.array_equal(cells[-1][0],render(m,1,0))
  for t in range(4):assert all(np.linalg.norm(cells[z][t])<np.linalg.norm(cells[z+1][t]) for z in range(2))
  ref=read(next((p for p in hold if 'vl2' in p.name),hold[0]));refgain=np.linalg.norm(cells[-1][0])/np.linalg.norm(ref)
  # Reference level matching is audition-only and explicitly labeled, not source dynamics.
  ref*=refgain
  assert max(abs(ref))<1
  sk.write_wav(str(audio/f'{slot:02d}-reference.wav'),ref)
  sk.write_wav(str(audio/f'{slot:02d}-candidate.wav'),cells[-1][0])
  strip=np.concatenate([np.r_[x,np.zeros(round(.2*RATE))] for row in cells for x in row]);sk.write_wav(str(audio/f'{slot:02d}-touch.wav'),strip)
  models[str(slot)]=m
  report['roles'][str(slot)]={'name':name,'training':[p.name for p in train],'holdout':[p.name for p in hold], 'reference':feature(ref),'candidate':feature(cells[-1][0]),'cellsPassed':12}
 (out/'models.json').write_text(json.dumps({'edges':EDGES.tolist(),'times':TIMES.tolist(),'models':models},indent=2)+'\n')
 (out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
if __name__=='__main__':main(*sys.argv[1:])
