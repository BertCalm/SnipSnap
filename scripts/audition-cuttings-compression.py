"""Offline whole-groove compressor comparison; does not alter accepted pad banks."""
import json, math, sys, wave
from pathlib import Path
import numpy as np
from scipy.ndimage import maximum_filter1d
root=Path(sys.argv[1])
settings={'glue':(-24.,4.5,6.,.0004,.090),'squeeze':(-34.,12.,6.,.0001,.055)}
report={}
for kit in ('cuttings-pocket','cuttings-night','cuttings-circuit'):
 p=root/kit; manifest=json.loads((p/'manifest.json').read_text())
 with wave.open(str(p/'audio/groove.wav'),'rb') as w:
  assert w.getsampwidth()==2 and w.getnchannels()==1 and w.getframerate()==44100
  x=np.frombuffer(w.readframes(w.getnframes()),'<i2').astype(float)/32768
 versions={'finished':x}; reductions={}
 for name,(threshold,ratio,knee,attack,release) in settings.items():
  # Follow the signal peak BEFORE the dB/ratio law. The old per-sample
  # dB reduction averaging missed short transients and mostly changed gain.
  a=math.exp(-1/(44100*attack));r=math.exp(-1/(44100*release));env=0.;envelope=np.empty(len(x))
  # Two-ms lookahead catches splice clicks before gain smoothing.
  detector=maximum_filter1d(np.abs(x),size=177,mode='constant')
  for i,v in enumerate(detector):
   c=a if v>env else r;env=c*env+(1-c)*v;envelope[i]=env
  over=20*np.log10(np.maximum(envelope,1e-9))-threshold
  gr=np.where(over < -knee/2,0,np.where(over>knee/2,over*(1-1/ratio),(over+knee/2)**2/(2*knee)*(1-1/ratio)))
  versions[name]=x*10**(-gr/20);reductions[name]=float(np.max(gr))
 # Equal full-file RMS, including identical silence, with common headroom.
 rms={k:float(np.sqrt(np.mean(v*v))) for k,v in versions.items()}
 target=min(rms['finished'],*(.88*rms[k]/np.max(np.abs(v)) for k,v in versions.items()))
 report[kit]={}
 for name,v in versions.items():
  gain=target/rms[name];y=v*gain
  assert np.isfinite(y).all() and np.max(np.abs(y))<=.880001
  if name=='finished':manifest['grooves'][name]={'file':'audio/groove.wav','gain':gain}
  else:
   file=f'audio/groove_{name}_v2.wav'
   with wave.open(str(p/file),'wb') as w:
    w.setnchannels(1);w.setsampwidth(2);w.setframerate(44100);w.writeframes(np.rint(y*32768).astype('<i2').tobytes())
   manifest['grooves'][name]={'file':file,'gain':1}
  report[kit][name]={'rms':float(np.sqrt(np.mean(y*y))),'peak':float(np.max(np.abs(y))),'maxReductionDb':reductions.get(name,0),'crestDb':float(20*np.log10(np.max(np.abs(y))/np.sqrt(np.mean(y*y))))}
 (p/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
# Install the same small player patch after both initial packaging and revisions.
import subprocess
subprocess.run([sys.executable,str(Path(__file__).with_name('patch-cuttings-compression-player.py')),str(root)],check=True)
(root/'compression-report.json').write_text(json.dumps({'method':'Full-file RMS matched, envelope-first peak compression; original groove untouched','settings':settings,'measurements':report},indent=2)+'\n')
print(json.dumps(report,indent=2))
