"""R3: measured resonances and coarse envelopes driving oscillators and fresh noise.
Fit reads licensed reference PCM. render reads only numeric JSON, never PCM.
"""
from pathlib import Path
import json,hashlib
import numpy as np
from scipy import signal
from scipy.io import wavfile
SR=44100;OS=4;TIMES=[0,.002,.005,.01,.02,.035,.055,.08,.12,.18,.26,.38,.55,.8,1.2,1.8,2.4];EDGES=np.geomspace(32,17000,25)
ROOT=Path(__file__).resolve().parents[2];BANK=ROOT/'synth/src/main/resources/spore-station/v7';MODEL=Path(__file__).with_name('extra-model.json')
def sos(lo,hi,rate):return signal.butter(2,[lo,hi],fs=rate,btype='bandpass',output='sos')
def fit():
 provenance=json.loads((BANK/'provenance.json').read_text());out={'version':1,'rate':SR,'times':TIMES,'voices':{},'fit_source_commit':'f1842eb48f175fb5fc77e02efeeda97c42c15d6a'}
 for name,source in [('short-kick','heel'),('ghost-snare','ghost'),('rimshot','rim'),('cross-stick','stick'),('rack-tom','tom'),('pedal-hat','pedal'),('ride','ride')]:
  layers=[]
  for zone in range(3):
   e=next(e for e in provenance if e['voice']==source and e['zone']==zone and e['take']==0);p=BANK/e['resource'];assert hashlib.sha256(p.read_bytes()).hexdigest()==e['resourceSha256'];sr,x=wavfile.read(p);assert sr==SR;x=x.astype(float)/2147483648;bands=[]
   for lo,hi in zip(EDGES[:-1],EDGES[1:]):
    y=signal.sosfilt(sos(lo,hi,SR),x);env=np.sqrt(np.maximum(signal.convolve(y*y,np.ones(221)/221,mode='same'),0));levels=np.interp(TIMES,np.arange(len(y))/SR,env,left=0,right=0);levels[0]=0
    f,power=signal.periodogram(y[:min(len(y),int(.22*SR))],SR,nfft=32768);pk,_=signal.find_peaks(power);pk=sorted([i for i in pk if lo<f[i]<hi],key=lambda i:-power[i])[:3]
    if not pk:pk=[int(np.argmin(abs(f-(lo+hi)/2)))]
    freqs=f[pk];weights=np.sqrt(power[pk]);weights/=np.sqrt(sum(weights*weights))+1e-12
    tone=(.98 if hi<250 else .78 if hi<1400 else .30) if name in ['short-kick','ghost-snare','rimshot','cross-stick','rack-tom'] else .62
    t=np.arange(min(len(y),int(.06*SR)))/SR;phases=[float(np.angle(np.sum(y[:len(t)]*np.exp(-2j*np.pi*hz*t)))) for hz in freqs]
    bands.append({'lo':float(lo),'hi':float(hi),'frequencies':freqs.tolist(),'weights':weights.tolist(),'phases':phases,'envelope':levels.tolist(),'tonal_energy_fraction':tone})
   layers.append({'bands':bands,'source':e['resource'],'sha256':e['resourceSha256'],'seconds':min(2.4,len(x)/SR)})
  out['voices'][name]=layers
 MODEL.write_text(json.dumps(out,separators=(',',':')));print('12 parameter sets, no PCM:',MODEL.stat().st_size,'bytes')
def render(model,voice,zone,take):
 spec=model['voices'][voice][zone];rate=SR*OS;n=round(spec['seconds']*rate);t=np.arange(n)/rate;seed=int.from_bytes(hashlib.sha256(f'knee-r3:{voice}:{zone}:{take}'.encode()).digest()[:8],'little');rng=np.random.default_rng(seed);out=np.zeros(n)
 for b in spec['bands']:
  env=np.interp(t,model['times'],b['envelope'],right=0);tone=np.zeros(n)
  for hz,w,phase in zip(b['frequencies'],b['weights'],b['phases']):tone+=w*np.cos(2*np.pi*hz*(1+rng.uniform(-.0008,.0008))*t+phase+rng.normal(0,.06))
  noise=signal.sosfilt(sos(b['lo'],b['hi'],rate),rng.normal(size=n));noise/=np.sqrt(np.mean(noise**2))+1e-12
  out+=(np.sqrt(b['tonal_energy_fraction']*2)*tone+np.sqrt(1-b['tonal_energy_fraction'])*noise)*env*rng.uniform(.97,1.03)
 out=signal.resample_poly(out,1,OS);out*=np.minimum(1,np.arange(len(out))/(.0007*SR));return out
if __name__=='__main__':fit()
