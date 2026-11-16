"""Reference-calibrated tap anchor. Python study; never loads reference PCM at synthesis time.
Fit broad noise-band decay powers on four strikes, inspect four disjoint strikes.
This is one unlabeled tap-shoe contact, not evidence for separate toe/heel/body voices.
"""
import argparse,hashlib,json,sys,time
from pathlib import Path
import numpy as np
from scipy import signal,optimize
from scipy.io import wavfile
ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'.claude/skills/luthier/scripts'));import snipkit as sk
RATE=44100;FS=176400;SECONDS=.22
EDGES=np.array([100,250,500,800,1200,1800,2600,3600,5000,7000,9500,12500,16000.])
DECAYS=np.array([.004,.012,.035,.090,.22])
PEAKS=[13.809,15.994,17.884,18.159,22.195,23.038,25.891,31.853]
SOURCE={'title':'tap dancing.','creator':'Chinouk211','url':'https://freesound.org/people/Chinouk211/sounds/592705/','license':'https://creativecommons.org/licenses/by/4.0/','description':'Tap shoes on tiles in an echoing room. Public HQ MP3 preview; mono excerpts for comparison. Not labeled toe versus heel.'}

def band(x,lo,hi,rate):return signal.sosfilt(signal.butter(2,[lo,hi],fs=rate,btype='bandpass',output='sos'),x)
def cuts(path):
 sr,x=wavfile.read(path);assert sr==RATE and x.ndim==1;x=x.astype(float)/32768
 out=[];times=[]
 for peak in PEAKS:
  start=round((peak-.035)*RATE);seg=x[start:round((peak+.01)*RATE)]
  smooth=np.sqrt(signal.convolve(seg*seg,np.ones(44)/44,mode='same'))
  onset=start+np.flatnonzero(smooth>.18*smooth.max())[0]
  out.append(x[onset:onset+round(SECONDS*RATE)]);times.append(onset/RATE)
 return out,times

def fit(excerpts):
 n=len(excerpts[0]);hop=88;frames=n//hop;t=(np.arange(frames)+.5)*hop/RATE
 basis=np.exp(-2*t[:,None]/DECAYS[None,:]);powers=[]
 # Fit averages after equal-energy alignment; this is analysis, not runtime peak matching.
 target=np.mean([np.mean(x*x) for x in excerpts]);aligned=[x*np.sqrt(target/np.mean(x*x)) for x in excerpts]
 for lo,hi in zip(EDGES[:-1],EDGES[1:]):
  curves=[]
  for x in aligned:
   y=band(x,lo,hi,RATE)[:frames*hop];curves.append((y*y).reshape(frames,hop).mean(axis=1))
  power=np.mean(curves,axis=0);weights,_=optimize.nnls(basis,power);powers.append(weights.tolist())
 return {'version':1,'rate':RATE,'internalRate':FS,'seconds':SECONDS,'edges':EDGES.tolist(),'decays':DECAYS.tolist(),'bandPowers':powers,'gain':1.0,'source':SOURCE}

def render(m,v=1.,take=0):
 assert 0<=v<=1 and take>=0
 n=round(m['seconds']*FS);t=np.arange(n)/FS;rng=np.random.default_rng(83177+take*7919)
 noise=rng.normal(size=n+round(.06*FS));out=np.zeros(n)
 for i,(lo,hi) in enumerate(zip(m['edges'][:-1],m['edges'][1:])):
  y=band(noise,lo,hi,FS)[-n:]
  # Stationary band-energy correction, independent of the current random take.
  sos=signal.butter(2,[lo,hi],fs=FS,btype='bandpass',output='sos')
  _,response=signal.sosfreqz(sos,worN=8192);variance=np.mean(abs(response)**2)
  p=np.array(m['bandPowers'][i]);env=np.sqrt(np.exp(-2*t[:,None]/np.array(m['decays']))@p)
  tilt=(np.sqrt(lo*hi)/1500)**((v-1)*.28)
  out+=y/np.sqrt(variance)*env*tilt*(1+.05*rng.uniform(-1,1))
 out*=np.minimum(1,t/.00035)*v**1.15*m['gain']
 y=sk.decimate4(sk.band_limit(out,FS))
 if 'contactDampingSeconds' in m:y*=np.exp(-np.arange(len(y))/(RATE*m['contactDampingSeconds']))
 return sk.fade_tail(sk.dc_block(y,RATE),RATE)

def features(x):
 f,p=signal.welch(x,fs=RATE,nperseg=2048);mask=(f>=100)&(f<=12000);p=p[mask]
 e=x*x;return {'flatness100to12000':float(np.exp(np.mean(np.log(p+1e-20)))/np.mean(p)),
 't90ms':float(np.searchsorted(np.cumsum(e),.9*e.sum())/RATE*1000),
 'centroidHz':float(np.sum(f[mask]*p)/np.sum(p)),'peak':float(max(abs(x)))}

def distance(x,y):
 def spectrum(a):
  _,p=signal.welch(a,fs=RATE,nperseg=1024);p/=sum(p);return 10*np.log10(p+1e-10)
 return float(np.mean(abs(spectrum(x)-spectrum(y))))

def main(reference,out):
 out=Path(out);out.mkdir(parents=True,exist_ok=True);a=out/'audio';a.mkdir(exist_ok=True)
 hits,times=cuts(reference);train=hits[::2];check=hits[1::2];m=fit(train)
 nominal=render(m)
 # Band analysis includes filter ringing and the recorded room. Fit one additional
 # contact-loss time to the median TRAINING energy duration, never the holdout.
 target=float(np.median([features(x)['t90ms'] for x in train]));lo,hi=.0005,2.
 for _ in range(40):
  tau=(lo+hi)/2;y=nominal*np.exp(-np.arange(len(nominal))/(RATE*tau))
  if features(y)['t90ms']>target:hi=tau
  else:lo=tau
 m['contactDampingSeconds']=(lo+hi)/2;m['targetTrainingT90ms']=target
 nominal=render(m);m['gain']=float(np.sqrt(np.mean([np.mean(x*x) for x in train])/np.mean(nominal*nominal)))
 (out/'model.json').write_text(json.dumps(m,indent=2)+'\n')
 # Export model and measurements, but never embed wave samples in the model.
 generated=[render(m,1,t) for t in range(4)]
 sys.path.insert(0,str(Path(__file__).resolve().parents[1]));import footwork_proto
 old=footwork_proto.render('toe',1.,0)
 old=old[:round(SECONDS*RATE)];old*=np.sqrt(np.mean([np.mean(x*x) for x in train])/np.mean(old*old))
 gain=.8/max(max(abs(x)) for x in [*hits,*generated,old])
 for label,group in [('reference',check),('candidate',generated),('previous',[old]*4)]:
  clip=np.concatenate([np.r_[x*gain,np.zeros(round(.25*RATE))] for x in group]);sk.write_wav(str(a/f'{label}.wav'),clip)
 rows=[]
 for v in [.3,.65,1]:
  takes=[render(m,v,t) for t in range(4)]
  assert all(np.isfinite(x).all() and max(abs(x))<1 and abs(x.mean())<.005 and x[-1]==0 for x in takes)
  assert all(np.linalg.norm(takes[0]/np.linalg.norm(takes[0])-x/np.linalg.norm(x))>.01 for x in takes[1:])
  for t,x in enumerate(takes):assert np.array_equal(x,render(m,v,t));rows.append(x)
 assert not np.any(render(m,0))
 for t in range(4):
  levels=[max(abs(rows[z*4+t])) for z in range(3)];assert levels[0]<levels[1]<levels[2]
  assert np.linalg.norm(rows[t]/np.linalg.norm(rows[t])-rows[8+t]/np.linalg.norm(rows[8+t]))>.005
 sk.write_wav(str(a/'touch.wav'),np.concatenate([np.r_[x*gain,np.zeros(round(.12*RATE))] for x in rows]))
 report={'status':'new single tap anchor awaiting listen; full kit not revised','source':SOURCE,'referenceWavSha256':hashlib.sha256(Path(reference).read_bytes()).hexdigest(),
 'onsetsSeconds':times,'trainingIndices':[0,2,4,6],'holdoutIndices':[1,3,5,7],
 'old':features(old),'candidate':features(generated[0]),'holdout':[features(x) for x in check],
 'holdoutMeanLogSpectrumDistanceDb':{'old':float(np.mean([distance(old,x) for x in check])),'candidate':float(np.mean([distance(y,x) for x in check for y in generated]))},
 'checks':{'cells':12,'deterministic':True,'velocityShapeAndLevel':True,'differentTakes':True,'finiteAndBounded':True,'zeroVelocity':True},'comparisonGain':gain}
 (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
 (out/'CREDITS.txt').write_text('Reference audio: '+SOURCE['title']+' by '+SOURCE['creator']+'\n'+SOURCE['url']+'\nCreative Commons Attribution 4.0: '+SOURCE['license']+'\nModified: decoded public MP3 preview to mono 44.1 kHz; extracted four 220 ms strikes; applied a shared comparison gain and silence between strikes.\nReference is recorded tap shoes on tiles in an echoing room. Candidate is newly generated filtered noise with fitted decay coefficients, not a recording. The reference is not loaded by render().\n')
 print(json.dumps(report,indent=2))
if __name__=='__main__':main(sys.argv[1],sys.argv[2])
