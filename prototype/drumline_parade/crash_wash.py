"""Crash-only diffuse/tonal synthesis from measured band envelopes and spectral floor.
The median across frequency estimates diffuse energy; it is not a physical separation.
Source PCM is only read by --fit, never by render(). See NOTICE-measured.txt.
"""
import argparse,functools,json
from pathlib import Path
import numpy as np
from scipy import signal,ndimage
from scipy.io import wavfile
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/".claude/skills/luthier/scripts"))
import snipkit as sk
FS=44100
BANDS=125*2.**(np.arange(22)/3)
TABLE=Path(__file__).parent/'crash-wash.json'

def sos(fc):return signal.butter(3,[fc/2**(1/6),min(fc*2**(1/6),21000)],btype='bandpass',fs=FS,output='sos')
def rms_envelope(x):return np.sqrt(np.maximum(ndimage.uniform_filter1d(x*x,round(.012*FS)),1e-20))

def analyze(path):
 rate,x=wavfile.read(path);assert rate==FS
 x=x.astype(float)
 if x.ndim>1:x=x.mean(axis=1)
 onset=max(0,int(np.argmax(np.abs(x)>.05*np.max(np.abs(x))))-round(.0005*FS));x=x[onset:]
 x=x[:round(4.5*FS)];x/=np.sqrt(np.mean(x[:round(.5*FS)]**2))
 freq,st,z=signal.stft(x,FS,nperseg=2048,noverlap=1920)
 power=np.abs(z)**2;floor=np.minimum(power,ndimage.median_filter(power,size=(9,1)))
 times=np.arange(0,len(x)/FS,.005);envelopes=[];diffuse=[]
 for fc in BANDS:
  y=signal.sosfilt(sos(fc),x);env=rms_envelope(y)
  sel=(freq>=fc/2**(1/6))&(freq<fc*2**(1/6))
  fraction=np.sum(floor[sel],axis=0)/np.maximum(np.sum(power[sel],axis=0),1e-20)
  envelopes.append(np.interp(times,np.arange(len(x))/FS,env).tolist())
  diffuse.append(np.interp(times,st,fraction).clip(0,1).tolist())
 return {'times':times.tolist(),'envelope':envelopes,'diffuse_fraction':diffuse}

@functools.lru_cache(maxsize=1)
def data():return json.loads(TABLE.read_text())

def render(v,take):
 # Imports happen only while rendering, so the fitting CLI needs no synthesis state.
 import measured
 d=data();rng=np.random.default_rng(sk.seed_for('PARADE_CRASH_WASH',take))
 measured.C._breaker_cymbals()
 modes=measured.C.e.fitted_modes('breaker_crash',.5,.3,v,rng,4.5)
 n=len(modes);t=np.arange(n)/FS;y=np.zeros(n);w=np.clip((1-v)/.65,0,1)
 for k,fc in enumerate(BANDS):
  # Match spectral envelopes, then divide energy using the measured broad-floor estimate.
  a=d['hard'];b=d['soft']
  env=(1-w)*np.interp(t,a['times'],a['envelope'][k])+w*np.interp(t,b['times'],b['envelope'][k])
  fraction=(1-w)*np.interp(t,a['times'],a['diffuse_fraction'][k])+w*np.interp(t,b['times'],b['diffuse_fraction'][k])
  tonal=signal.sosfilt(sos(fc),modes);normal=rms_envelope(tonal)
  tonal=tonal*np.minimum(env/np.maximum(normal,1e-8),100)
  noise=signal.sosfilt(sos(fc),rng.standard_normal(n+4096))[4096:]
  noise/=max(np.sqrt(np.mean(noise*noise)),1e-12)
  y+=np.sqrt(1-fraction)*tonal+np.sqrt(fraction)*noise*env
 y*=np.minimum(t/.0004,1)*v
 return signal.resample_poly(y,4,1)

if __name__=='__main__':
 ap=argparse.ArgumentParser();ap.add_argument('--fit',type=Path,required=True);a=ap.parse_args()
 TABLE.write_text(json.dumps({'source':'Virtuosity Drums CC0, pinned source and hashes in NOTICE-measured.txt','bands':BANDS.tolist(),'hard':analyze(a.fit/'crash_hard.wav'),'soft':analyze(a.fit/'crash_soft.wav')},separators=(',',':'))+'\n')
