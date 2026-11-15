"""Synthesis from compact scalar parameters. Loads model JSON, never recorded audio.
Fitted frequencies/decays/envelopes calibrate oscillators and fresh seeded noise.
"""
from pathlib import Path
from functools import lru_cache
import json,hashlib,numpy as np
from scipy import signal
SR=44100;VEL=[.34,.64,1.]
def seed(*parts):return int.from_bytes(hashlib.sha256('/'.join(map(str,parts)).encode()).digest()[:8],'little')
@lru_cache(None)
def models():return json.loads((Path(__file__).parent/'models.json').read_text())['voices']
@lru_cache(None)
def noise_filter(lo,hi,rate):
 sos=signal.butter(3,[lo,hi],btype='bandpass',fs=rate,output='sos');f,h=signal.sosfreqz(sos,worN=16384,fs=rate)
 return sos,np.sqrt(np.trapezoid(abs(h)**2,f)/(rate/2))
@lru_cache(maxsize=6)
def noise_bank(voice,take,tune,rate,n):
 rng=np.random.default_rng(seed(voice,take,'noise'));out=[]
 for lo0,hi0 in zip([35,90,180,350,700,1400,2800,5600,10000,16000],[90,180,350,700,1400,2800,5600,10000,16000,20000]):
  lo=float(np.clip(lo0*tune,20,17000));hi=float(np.clip(hi0*tune,lo+10,20000));sos,norm=noise_filter(lo,hi,rate)
  noise=rng.standard_normal(n+round(.2*rate));a=signal.sosfilt(sos,noise)[round(.2*rate):];out.append(a/max(norm,1e-6))
 return out
def decay(u):return np.exp(-np.minimum(u,80.))
def env(t,attack,fast,slow,mix):return (1-decay(t/attack))*(mix*decay(t/fast)+(1-mix)*decay(t/slow))
def synthesize(voice,zone=2,take=0,tune=1.,damp=1.,strike=.5,oversample=4):
 rate=SR*oversample;duration=.95 if voice in ('low-log','high-log') else min(1.2,max(p['tail_end'] for p in models()[voice])/damp+.04);n=round(duration*rate);t=np.arange(n)/rate;x=np.zeros(n);rng=np.random.default_rng(seed(voice,take));delta=(strike-.5)
 if voice in ('low-log','high-log'):
  f=(49 if voice=='low-log' else 73.41619198)*tune;v=VEL[zone];p=2*np.pi*f*t
  x=.27*v*np.sin(p+(.55+2.8*v+.7*delta)*np.exp(-t/(.020+.0015*take))*np.sin(2*p))*(1-np.exp(-t/.0007))*np.exp(-t/(.22/damp))
  x+=.025*v*np.sin(3*p)*(1-np.exp(-t/.0007))*np.exp(-t/.075)
 else:
  params=models()[voice][zone]
  for m in params['modes']:
   hz=m['hz']*tune*(1+rng.uniform(-.0008,.0008));phase=m['phase']+rng.uniform(-.07,.07)
   if hz>17500:continue
   x+=m['amp']*(1+rng.uniform(-.035,.035))*(hz/1000)**(.15*delta)*np.cos(2*np.pi*hz*t+phase)*(1-np.exp(-t/(.00045*2**(-delta))))*decay(t*damp/m['tau'])
  for i,b in enumerate(params['noise']):
   # Fresh filtered noise, cached across velocity zones; no recorded residual waveform.
   colored=noise_bank(voice,take,tune,rate,n)[i]
   e=env(t,b['attack']*2**(-delta),b['fast']/damp,b['slow']/damp,b['mix'])
   x+=b['amp']*e*colored*(1+rng.uniform(-.025,.025))
 if voice not in ('low-log','high-log'):
  end=params['tail_end']/damp;gate=np.clip((end-t)/.015,0,1);x*=gate
 x=signal.sosfilt(signal.butter(8,18000,fs=rate,output='sos'),x);x=signal.resample_poly(x,1,oversample)
 x=signal.sosfilt(signal.butter(2,22,btype='highpass',fs=SR,output='sos'),x);x[-441:]*=np.linspace(1,0,441)
 return np.pad(x,(0,max(0,52920-len(x))))[:52920]
