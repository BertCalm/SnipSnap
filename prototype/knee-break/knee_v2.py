"""Knee treatment revision. Approved dry/cassette assets are never rewritten.
No acoustic-microphone simulation claim: filtered/coloured parallel bus recipe.
"""
from pathlib import Path
import sys,json,hashlib,zipfile
import numpy as np
from scipy import signal
from scipy.io import wavfile
SR=44100

def process(x):
 # Body-forward mic perspective with less sub and overhead sparkle.
 wet=signal.sosfilt(signal.butter(2,[135,3400],fs=SR,btype='bandpass',output='sos'),x)
 mid=signal.sosfilt(signal.butter(2,[260,1100],fs=SR,btype='bandpass',output='sos'),wet);wet+=.65*mid
 # Short local reflections, not a long reverb tail.
 for sec,g in [(.007,.28),(.017,-.14)]:
  d=round(sec*SR);wet[d:]+=g*wet[:-d].copy()
 up=signal.resample_poly(wet,4,1);wet=signal.resample_poly(np.tanh(up*2.8)/2.8,1,4)[:len(x)]
 power=signal.lfilter([1/(.0015*SR)],[1,-(1-1/(.0015*SR))],wet*wet);env=np.sqrt(np.maximum(power,0))
 target=np.minimum(1,(.022/np.maximum(env,1e-9))**.8);release=np.exp(-1/(.09*SR));last=1.;gain=np.empty(len(x))
 for i,g in enumerate(target):last=min(g,release*last+(1-release)*g);gain[i]=last
 out=.20*x+.80*wet*gain*3.4
 out=signal.sosfilt(signal.butter(2,22,fs=SR,btype='highpass',output='sos'),out)
 out[-min(353,len(out)):]*=np.linspace(1,0,min(353,len(out)))
 return out,{'maximum_gain_reduction_db':float(-20*np.log10(min(gain))),'active_mean_gain_reduction_db':float(np.mean(-20*np.log10(gain[env>.01]))) if np.any(env>.01) else 0.}

def main(root):
 r4=root/'round4';out=root/'round5';(out/'audio').mkdir(parents=True,exist_ok=True);m=json.loads((r4/'manifest.json').read_text());manifest=[];reports={};preserved={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (r4/'audio').glob('*.wav') if '-close-' in p.name or '-cassette-' in p.name or p.name.endswith(('-close.wav','-cassette.wav'))}
 def read(p):return wavfile.read(p)[1].astype(float)/32767
 def write(name,y):
  assert np.isfinite(y).all() and max(abs(y))<.95 and abs(y.mean())<.005
  p=out/'audio'/name;wavfile.write(p,SR,np.round(y*32767).astype('<i2'));return p
 for e in m['samples']:
  if e['fx']!='close':continue
  x=read(r4/e['file']);y,stats=process(np.pad(x,(0,round(.12*SR))));p=write(f"{e['kit']}-knee-{e['slot']}-{e['zone']}-{e['take']}.wav",y)
  manifest.append({**e,'fx':'knee','file':'audio/'+p.name,'frames':len(y),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()})
 for kit in ['body','tight']:
  x=read(r4/f'audio/groove-{kit}-close.wav');y,stats=process(x);old=read(r4/f'audio/groove-{kit}-knee.wav')
  # Full-phrase RMS matching: fixed gain for this comparison, no per-pad normalization.
  gain=np.linalg.norm(x)/np.linalg.norm(y);y*=gain;assert max(abs(y))<.95
  write(f'groove-{kit}-knee.wav',y)
  def measure(y):
   g=x@y/(x@x);return {'correlation':float(np.corrcoef(x,y)[0,1]),'gain_removed_rms_residual':float(np.linalg.norm(y-g*x)/np.linalg.norm(y)),'rms_difference_db':float(20*np.log10(np.linalg.norm(y)/np.linalg.norm(x)))}
  reports[kit]={**stats,'comparison_gain':float(gain),'old':measure(old),'new':measure(y)}
 # New versions of the two knee download programs; dry/cassette ZIPs remain exact.
 downloads={}
 for kit in ['body','tight']:
  sfz=(r4/f'{kit}-knee.sfz').read_bytes();(out/f'{kit}-knee.sfz').write_bytes(sfz)
  path=out/f'{kit}-knee.zip'
  with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as z:
   z.writestr(f'{kit}-knee.sfz',sfz)
   for e in manifest:
    if e['kit']==kit:z.write(out/e['file'],e['file'])
   z.write(r4/'LICENSE.txt','REFERENCE-LICENSE.txt');z.write(r4/'NOTICE.txt','NOTICE.txt');z.writestr('README.txt','Knee Break knee-mic treatment v2. Synthesized sources unchanged; more processed-mic blend and stronger compression. Three velocities x three takes for 16 pads. Printed per-pad effects; groove demo uses shared processing and RMS matching. Native/hardware playback unverified.\n')
  with zipfile.ZipFile(path) as z:
   assert z.testzip() is None
   for e in manifest:
    if e['kit']==kit:assert hashlib.sha256(z.read(e['file'])).hexdigest()==e['sha256']
  downloads[kit]={'file':path.name,'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
 minimum=1.;peak=0.;dc=0.
 for kit in ['body','tight']:
  for slot in range(1,17):
   energy=[]
   for zone in range(3):
    row=[read(out/f'audio/{kit}-knee-{slot}-{zone}-{t}.wav') for t in range(3)];energy.append(np.mean([a@a for a in row]))
    for i,a in enumerate(row):
     peak=max(peak,float(max(abs(a))));dc=max(dc,float(abs(a.mean())));assert a[-1]==0
     for b in row[i+1:]:
      n=max(len(a),len(b));aa=np.pad(a,(0,n-len(a)));bb=np.pad(b,(0,n-len(b)));res=float(np.sum((bb-aa*(aa@bb)/(aa@aa))**2)/(bb@bb));minimum=min(minimum,res);assert res>1e-6
   assert np.all(np.diff(energy)>0),(kit,slot,energy)
 for name,sha in preserved.items():assert hashlib.sha256((r4/'audio'/name).read_bytes()).hexdigest()==sha
 report={'samples':len(manifest),'dry_and_cassette_files_unchanged':len(preserved),'maximum_peak':peak,'maximum_dc':dc,'minimum_take_residual':minimum,'velocity_order':'all 32 pad/kit combinations increase mean energy across layers','grooves':reports,'scope':'DSP and package checks; listening pending'}
 (out/'manifest.json').write_text(json.dumps(manifest,indent=2));(out/'verification.json').write_text(json.dumps(report,indent=2));(out/'downloads.json').write_text(json.dumps(downloads,indent=2));print(json.dumps(report,indent=2))
if __name__=='__main__':main(Path(sys.argv[1]))
