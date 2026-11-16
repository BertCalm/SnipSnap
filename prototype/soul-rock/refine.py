"""Tune prototype kicks toward low-dominant reference-event peaks, then rebuild auditions.
This is an explicitly provisional target: events are not separated kick stems.
"""
import sys,json,zipfile,time
from pathlib import Path
import numpy as np
from scipy import signal
from scipy.io import wavfile
ROOT=Path(__file__).parent;sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'ember'))
import ember_proto as e
FS=44100;VEL=[.34,.64,1.]
def read(p):
 fs,x=wavfile.read(p);assert fs==FS;return x.astype(float)/32768

def write(p,x):wavfile.write(p,FS,np.round(np.clip(x,-1,1)*32767).astype(np.int16))
def peak_low(x):
 f,p=signal.welch(x[:round(.12*FS)],FS,nperseg=4096,nfft=16384);i=(f>=35)&(f<=160);return float(f[i][np.argmax(p[i])])
def residual(a,b):
 n=min(len(a),len(b));a=a[:n];b=b[:n];g=np.dot(a,b)/max(np.dot(b,b),1e-20);return float(20*np.log10(max(np.linalg.norm(a-g*b),1e-20)/max(np.linalg.norm(a),1e-20)))
m=json.loads((ROOT/'manifest.json').read_text());refs=json.loads((ROOT/'kick-diagnostic.json').read_text())['kits']
for kit,ref in zip(m['kits'],refs):
 p=kit['pads'][0];old=p['macros']['TUNE'];before=peak_low(read(ROOT/p['variants'][6]));target=ref['candidate_median_strongest_35_160_hz']
 # A dominant spectral peak can jump between modes. Evaluate proposals and retain
 # the prior setting when the simple frequency-ratio update makes the match worse.
 candidates={old,p.get('tuning_evidence',{}).get('old_tune',old),float(np.clip(old+np.log2(target/before),0,1))}
 def error(tune):
  hz=peak_low(e.render('KICK',{**p['macros'],'TUNE':tune},1,0))
  return abs(np.log2(hz/target))
 p['macros']['TUNE']=min(sorted(candidates),key=error)
 for zi,v in enumerate(VEL):
  ys=[e.render('KICK',p['macros'],v,t) for t in range(3)];rms=np.array([np.sqrt(np.mean(y*y)) for y in ys]);r=np.exp(np.mean(np.log(rms)));ys=[y*r/a for y,a in zip(ys,rms)];peak=max(max(abs(y)) for y in ys)
  for t,y in enumerate(ys):write(ROOT/p['variants'][zi*3+t],y*.88/peak*v**.8)
 p['tuning_evidence']={'method':'Match strongest 35–160 Hz peak of first 120ms to median of low-band-dominant reference events; provisional, overlapping sources possible','target_hz':target,'before_hz':before,'after_hz':peak_low(read(ROOT/p['variants'][6])),'old_tune':old}
 # One kit-wide gain preserves pad balance and velocity/take relationships.
 kit_peak=max(float(max(abs(signal.resample_poly(read(ROOT/rel),4,1)))) for pad in kit['pads'] for rel in pad['variants'])
 safety_gain=min(1,.92/kit_peak);kit['true_peak_gain']=safety_gain
 if safety_gain<1:
  for pad in kit['pads']:
   for rel in pad['variants']:write(ROOT/rel,read(ROOT/rel)*safety_gain)
 name=kit['name'];step=60/kit['bpm']/4;y=np.zeros(round((64*step+5)*FS));counts={};events=kit['events'];hat_indices=(4,5,6)
 for j,(s,pi,zi,level) in enumerate(events):
  ti=counts.get(pi,0)%3;counts[pi]=ti+1;hit=read(ROOT/kit['pads'][pi]['variants'][zi*3+ti]);at=round(s*step*FS)
  if pi in hat_indices:
   future=[ev[0] for ev in events[j+1:] if ev[1] in hat_indices and ev[0]>s]
   if future:
    length=round((min(future)-s)*step*FS);hit=hit[:length].copy();fade=min(len(hit),round(.004*FS));hit[-fade:]*=np.linspace(1,0,fade)
  y[at:at+len(hit)]+=hit*level
 gain=min(1,.89/max(abs(y)));write(ROOT/f'{name}-groove.wav',y*gain);kit['groove_gain']=float(gain);kit['audition_hat_choke']=True
 demos=[]
 for p in kit['pads']:demos.extend([read(ROOT/p['variants'][6]),np.zeros(round(.15*FS))])
 write(ROOT/f'{name}-pads.wav',np.concatenate(demos)*.9)
 touch=[]
 for pi in [0,1,4,7]:
  for rel in kit['pads'][pi]['variants']:touch.extend([read(ROOT/rel),np.zeros(round(.1*FS))])
 write(ROOT/f'{name}-touch.wav',np.concatenate(touch))
checks=[]
for kit in m['kits']:
 for p in kit['pads']:
  ys=[read(ROOT/rel) for rel in p['variants']]
  p['soft_hard_residual_db']=residual(ys[0],ys[6]);p['take_residual_db']=residual(ys[6],ys[7])
  for rel,y in zip(p['variants'],ys):
   assert np.isfinite(y).all();assert max(abs(y))<.9
   checks.append({'file':rel,'peak':float(max(abs(y))),'dc':float(np.mean(y)),'tail_rms':float(np.sqrt(np.mean(y[-441:]**2)))})
m['verification']={'variants':len(checks),'sample_peak':max(c['peak'] for c in checks),'max_abs_dc':max(abs(c['dc']) for c in checks),'max_tail_rms':max(c['tail_rms'] for c in checks),'min_soft_hard_residual_db':min(p['soft_hard_residual_db'] for k in m['kits'] for p in k['pads']),'min_take_residual_db':min(p['take_residual_db'] for k in m['kits'] for p in k['pads']),'scope':'Python and PCM16 readback; not a native, hardware or perceptual pass. True peak and full alias-reference tests remain open.'}
(ROOT/'manifest.json').write_text(json.dumps(m,indent=2));(ROOT/'checks.json').write_text(json.dumps(checks,indent=2))
with zipfile.ZipFile(ROOT/'audition.zip','w',zipfile.ZIP_DEFLATED) as z:
 for p in ROOT.glob('*.wav'):z.write(p,p.name)
 for name in ['index.html','manifest.json','EMBER-NOTICE.txt']:z.write(ROOT/name,name)
for kit in m['kits']:
 name=kit['name']
 with zipfile.ZipFile(ROOT/f'{name}-wav-kit.zip','w',zipfile.ZIP_DEFLATED) as z:
  for p in (ROOT/name).glob('*.wav'):z.write(p,str(p.relative_to(ROOT)))
  z.write(ROOT/'manifest.json','manifest.json');z.write(ROOT/'EMBER-NOTICE.txt','EMBER-NOTICE.txt')
print(json.dumps(m['verification']),flush=True)
for k in m['kits']:print(k['name'],k['pads'][0]['tuning_evidence'],flush=True)
