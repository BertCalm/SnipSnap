"""R4: sample-derived spectral-envelope synthesis; no waveform/phase in model.
Analysis averages three recorded strikes per velocity into magnitude descriptors.
Runtime uses continuous sinusoidal low modes and independently seeded noise.
This is spectral resynthesis, not a physical simulation of a complete drum kit.
"""
from pathlib import Path
import hashlib,json,sys,time,shutil,zipfile
import numpy as np
from scipy import signal,ndimage
from scipy.io import wavfile
RATE=44100; NFFT=2048; HOP=256
VOICES=['kick','snare','hat','openhat']
def read(p):
 sr,x=wavfile.read(p);assert sr==RATE;return x.astype(float)/32768

def analyze(root):
 models={'version':2,'method':'averaged magnitude envelopes; no audio or original phase','voices':{}}
 for voice in VOICES:
  layers=[]
  for level in range(3):
   hits=[read(root/'open-road'/f'{voice}-{level}-{rr}.wav') for rr in range(3)]
   duration=max(map(len,hits))/RATE
   # Dense attack landmarks, coarser tail; less than 100 envelope knots per layer.
   knots=np.unique(np.r_[np.arange(0,.08,.004),np.arange(.08,.5,.015),np.arange(.5,min(duration,2),.05),np.arange(2,duration,.15),duration])
   frequencies=np.unique(np.r_[np.arange(0,1000,44100/NFFT),np.geomspace(1000,20000,100),22050])
   power=[]
   for x in hits:
    f,t,z=signal.stft(np.pad(x,(0,round(duration*RATE)-len(x))),RATE,nperseg=NFFT,noverlap=NFFT-HOP)
    power.append(abs(z)**2)
   mag=np.sqrt(np.mean(power,axis=0))
   rows=np.array([np.interp(frequencies,f,col) for col in mag.T]).T
   envelope=np.array([np.interp(knots,t,row) for row in rows])
   # A second, short analysis window resolves contact that the 46 ms body window smears.
   attack_times=np.arange(0,.065,.001)
   attack_power=[]
   for x in hits:
    af,at,az=signal.stft(x[:round(.09*RATE)],RATE,nperseg=256,noverlap=212)
    attack_power.append(abs(az)**2)
   attack_mag=np.sqrt(np.mean(attack_power,axis=0))
   attack=np.array([np.interp(attack_times,at,row) for row in attack_mag])
   # Stable narrow spectral peaks from a longer window describe metallic modes.
   modes=[]
   if voice in ('hat','openhat'):
    spectra=[]
    for x in hits:
     mf,mt,mz=signal.stft(x,RATE,nperseg=8192,noverlap=6144)
     spectra.append(abs(mz)**2)
    # Source durations vary; use only the common analysis span.
    common=min(z.shape[1] for z in spectra)
    mp=np.mean([z[:,:common] for z in spectra],axis=0);mt=mt[:common]
    power=np.mean(mp[:,(mt>=.035)&(mt<=.35)],axis=1)
    floor=ndimage.gaussian_filter1d(power,10)
    peaks,_=signal.find_peaks(power,distance=7)
    peaks=[i for i in peaks if 700<mf[i]<15500 and power[i]>1.35*floor[i]]
    peaks=sorted(peaks,key=lambda i:power[i],reverse=True)[:40]
    for i in peaks:
     a,b,c=np.log(np.maximum(power[i-1:i+2],1e-20));offset=float(np.clip(.5*(a-c)/(a-2*b+c),-.5,.5))
     amplitude=np.sqrt(np.sum(mp[i-2:i+3],axis=0))
     curve=np.interp(knots,mt,amplitude,right=0)
     modes.append({'hz':round(float(mf[i]+offset*RATE/8192),3),'envelope':np.round(curve,8).tolist()})
   layers.append({'seconds':round(duration,5),'frequencies':frequencies.round(3).tolist(),'times':knots.round(5).tolist(),'magnitude':np.round(envelope,8).tolist(),'energy':float(np.mean([np.sum(x*x) for x in hits])), 'attack_times':attack_times.round(5).tolist(),'attack_magnitude':np.round(attack,8).tolist(),'metal_modes':modes})
  models['voices'][voice]=layers
 return models

def render(model,voice,level,take):
 start=time.perf_counter();m=model['voices'][voice][level]
 r=np.random.default_rng(int.from_bytes(hashlib.sha256(f'sideroom-r3/{voice}/{level}/{take}'.encode()).digest()[:8],'little'))
 n=round(m['seconds']*RATE);noise=r.standard_normal(n)
 f,t,z=signal.stft(noise,RATE,nperseg=NFFT,noverlap=NFFT-HOP)
 # Variation is generated, not a recorded RR selection: small strike/timing/spectral changes.
 pitch=r.uniform(.993,1.007);stretch=r.uniform(.97,1.03)
 coarse=np.array(m['magnitude']);env=np.array([np.interp(t/stretch,m['times'],row,right=0) for row in coarse])
 mag=np.array([np.interp(f/pitch,m['frequencies'],col,right=0) for col in env.T]).T
 tilt=(np.maximum(f,100)/2000)**r.uniform(-.035,.035);mag*=tilt[:,None]
 phase=z/np.maximum(abs(z),1e-15)
 if voice in ('kick','snare'):
  # Low-frequency resonances get coherent oscillator phase, not fresh random phase each frame.
  # Fractional peak refinement reduces bin-pitched ringing; high bands remain stochastic.
  average=np.mean(mag[:,(t>.015)&(t<.18)]**2,axis=1)
  peaks,_=signal.find_peaks(average)
  peaks=[p for p in peaks if 35<f[p]<(700 if voice=='kick' else 1000)]
  peaks=sorted(peaks,key=lambda p:average[p],reverse=True)[:(8 if voice=='kick' else 12)]
  for p in peaks:
   a,b,c=np.log(np.maximum(average[p-1:p+2],1e-20));offset=np.clip(.5*(a-c)/(a-2*b+c),-.5,.5)
   hz=(p+offset)*RATE/NFFT;initial=r.uniform(-.3,.3)
   for k in range(max(1,p-1),min(len(f)-1,p+2)):
    phase[k]=np.exp(1j*(2*np.pi*hz*t+initial+(k-p)*np.pi))
 _,x=signal.istft(mag*phase,RATE,nperseg=NFFT,noverlap=NFFT-HOP)
 x=x[:n]
 if voice in ('hat','openhat') and m.get('metal_modes'):
  # Explicit inharmonic oscillators supply persistent metal; retain the stochastic wash.
  tm=np.arange(n)/RATE;metal=np.zeros(n)
  for mode in m['metal_modes']:
   hz=mode['hz']*pitch*r.uniform(.9996,1.0004)
   amplitude=np.interp(tm/stretch,m['times'],mode['envelope'],right=0)
   metal+=amplitude*np.sin(2*np.pi*hz*tm+r.uniform(0,2*np.pi))
  metal*=np.sqrt(np.sum(x*x)/max(np.sum(metal*metal),1e-15))
  # Energy split, not an extra louder layer. Onset is replaced below.
  x=np.sqrt(.76)*x+np.sqrt(.24)*metal
 if voice=='snare':
  # Irregular wire-contact bursts, with stronger modulation on the early wire response.
  # This is an authored collision analogy, not recovered original waveform modulation.
  tm=np.arange(n)/RATE
  low=signal.sosfilt(signal.butter(2,1400,fs=RATE,output='sos'),x);wire=x-low
  impulses=np.zeros(n);positions=r.choice(min(n,round(.32*RATE)),size=85,replace=False)
  impulses[positions]=r.uniform(.4,1.0,len(positions))
  flutter=signal.lfilter([1],[1,-np.exp(-1/(.0018*RATE))],impulses)
  flutter=ndimage.gaussian_filter1d(flutter,10)
  flutter/=max(np.sqrt(np.mean(flutter[:min(n,round(.32*RATE))]**2)),1e-12)
  amount=.30*np.exp(-tm/.16)
  x=low+wire*((1-amount)+amount*np.clip(flutter,0,2.5))
 # Short-window stochastic contact followed by the continuous body/metal model.
 contact_n=round(.065*RATE)
 af,at,az=signal.stft(r.standard_normal(contact_n),RATE,nperseg=256,noverlap=212)
 ae=np.array([np.interp(at,m['attack_times'],row,right=0) for row in m['attack_magnitude']])
 _,contact=signal.istft(ae*az/np.maximum(abs(az),1e-15),RATE,nperseg=256,noverlap=212)
 count=min(len(x),contact_n);contact=contact[:count]
 # Keep kick/snare body phase continuous; contact replaces only their upper spectrum.
 cutoff=900 if voice=='kick' else 1400 if voice=='snare' else 1800
 filt=signal.butter(2,cutoff,fs=RATE,btype='highpass',output='sos')
 original_high=signal.sosfilt(filt,x)
 contact=signal.sosfilt(filt,contact)
 blend=np.clip((.032-np.arange(count)/RATE)/.018,0,1)
 x[:count]+=blend*(contact-original_high[:count])
 x=signal.sosfilt(signal.butter(2,22,fs=RATE,btype='highpass',output='sos'),x)
 x=signal.sosfilt(signal.butter(4,18000,fs=RATE,output='sos'),x)
 x[:44]*=np.linspace(0,1,44);x[-882:]*=np.linspace(1,0,882)
 # Preserve measured velocity energies; scale is determined by model, not peak normalization.
 x*=np.sqrt(m['energy']/max(np.sum(x*x),1e-15))*r.uniform(.97,1.03)
 return x,(time.perf_counter()-start)*1000

def groove(bank):
 beat=.6;events=[];counts={v:0 for v in VOICES}
 for step in range(32):
  b=step/2;v='openhat' if step in (15,31) else 'hat'
  events.append((b*beat,v,2 if step%2==0 or v=='openhat' else 1))
  if step%8 in (0,4,7):events.append((b*beat,'kick',2 if step%8==0 else 1))
  if step%8 in (2,6):events.append((b*beat+.008,'snare',2))
  if step in (13,29):events.append((b*beat+.014,'snare',0))
 mix=np.zeros(round(13.6*RATE));ordered=sorted(events)
 for tm,v,l in ordered:
  rr=counts[v]%3;counts[v]+=1;x=bank[v,l,rr].copy()
  if v=='openhat':
   stop=next((t for t,w,l in ordered if w=='hat' and t>tm),9.6)
   x=x[:round((stop-tm)*RATE)];x[-220:]*=np.linspace(1,0,220)
  at=round(tm*RATE);n=min(len(x),len(mix)-at);mix[at:at+n]+=x[:n]
 mix[-882:]*=np.linspace(1,0,882);return mix

def main(source,dest,previous):
 dest.mkdir(parents=True,exist_ok=True);model=analyze(source)
 (dest/'synthesis-model.json').write_text(json.dumps(model,separators=(',',':')))
 # Reopen serialized parameters; synthesis has no source-waveform argument or reads.
 model=json.loads((dest/'synthesis-model.json').read_text())
 banks={m:{} for m in ('synth','previous','acoustic')};times=[]
 for voice in VOICES:
  for level in range(3):
   for take in range(3):
    x,ms=render(model,voice,level,take);times.append(ms)
    assert np.array_equal(x,render(model,voice,level,take)[0])
    banks['synth'][voice,level,take]=x
    banks['previous'][voice,level,take]=read(previous/'synth'/f'{voice}-{level}-{take}.wav')
    banks['acoustic'][voice,level,take]=read(source/'open-road'/f'{voice}-{level}-{take}.wav')
 # Shared safety gain for both treatments; no clipping or per-hit peak matching.
 gain=min(1,.90/max(np.max(abs(x)) for bank in banks.values() for x in bank.values()))
 manifest={'revision':'R4 contact and metal synthesis comparison','levels':['Soft','Medium','Hard'],'takes':3,'modes':['synth','previous','acoustic'],'voices':VOICES,'files':{}}
 def write(p,x):
  assert np.isfinite(x).all() and np.max(abs(x))<.99
  p.parent.mkdir(parents=True,exist_ok=True);wavfile.write(p,RATE,np.rint(x*32767).astype(np.int16))
 mixes={m:groove(bank) for m,bank in banks.items()};gg=.85/max(np.max(abs(x)) for x in mixes.values())
 for mode,bank in banks.items():
  regions=[]
  for (v,l,rr),x in bank.items():
   rel=f'{mode}/{v}-{l}-{rr}.wav';write(dest/rel,x*gain);manifest['files'][f'{mode}/{v}/{l}/{rr}']=rel
   choke=' group=1 off_by=1 off_mode=fast' if v in ('hat','openhat') else ''
   regions.append(f'<region> sample={v}-{l}-{rr}.wav key={dict(zip(VOICES,[36,38,42,46]))[v]} lovel={[1,51,96][l]} hivel={[50,95,127][l]} seq_length=3 seq_position={rr+1} amp_veltrack=0 loop_mode=one_shot{choke}')
  (dest/mode/'kit.sfz').write_text('\n'.join(regions)+'\n');write(dest/mode/'groove.wav',mixes[mode]*gg)
 (dest/'manifest.json').write_text(json.dumps(manifest,indent=2))
 for name in ('CC0.txt','NOTICE.txt','provenance.json'):shutil.copy(source/name,dest/name)
 (dest/'NOTICE.txt').write_text((dest/'NOTICE.txt').read_text()+'R4 synth: averaged magnitude-envelope parameters derived from these CC0 sources; generated oscillator phase and noise, no original waveform playback. Acoustic comparator retains R2 Open Road recordings.\n')
 report={'model_bytes':(dest/'synthesis-model.json').stat().st_size,'render_ms_median':float(np.median(times)),'render_ms_max':max(times),'shared_pad_gain':gain,'determinism':'36 repeated renders identical','claim':'spectral resynthesis, not a validated physical model or native engine'}
 (dest/'synthesis-report.json').write_text(json.dumps(report,indent=2))
 with zipfile.ZipFile(dest/'open-road-acoustic.zip','w',zipfile.ZIP_DEFLATED) as z:
  for p in sorted(dest.rglob('*')):
   if p.is_file() and p.suffix in ('.wav','.sfz','.json','.txt'):z.write(p,p.relative_to(dest))
 print(json.dumps(report,indent=2))
if __name__=='__main__':main(Path(sys.argv[1]),Path(sys.argv[2]),Path(sys.argv[3]))
