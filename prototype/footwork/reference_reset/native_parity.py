"""Compare broad features of the native Tap Jazz saved grid to the selected Python source.
This is statistical port evidence, not an exact waveform or listening claim.
"""
from pathlib import Path
import json,sys
import numpy as np
from scipy.io import wavfile
from scipy import signal

def read(path):
 rate,x=wavfile.read(path);assert rate==44100 and x.ndim==1
 return x.astype(float)/(32768 if x.dtype==np.int16 else 2147483648) if x.dtype.kind=='i' else x.astype(float)
def feature(x):
 f,p=signal.welch(x,44100,nperseg=2048);e=x*x
 return {'rms':float(np.sqrt(np.mean(e))),'lowEnergyFraction':float(p[(f>=100)&(f<=700)].sum()/p.sum()),'t90ms':float(np.searchsorted(np.cumsum(e),.9*sum(e))/44.1)}
def main(native,python,out):
 native=Path(native);python=Path(python);kit=json.loads((native/'native/kit.json').read_text());rows=[]
 for voice,slot in [('toe',3),('heel',2),('brush-forward',5),('brush-back',6),('shuffle',7),('flap',8)]:
  pad=kit['pads'][slot-1];x=read(native/'native'/pad['sample']);boundaries=pad['chain']['boundaries']+[len(x)]
  for z,v in enumerate([.3,.65,1.]):
   for take in range(4):
    i=z*4+take;got=x[boundaries[i]:boundaries[i+1]]*v*pad['level'];wanted=read(python/f'{voice}-v{z}-r{take}.wav')
    a=feature(wanted);b=feature(got)
    # Broad tolerances permit independent random draws, but catch gain/timing/material regressions.
    assert .65<b['rms']/a['rms']<1.45,(voice,z,take,a,b)
    assert abs(b['lowEnergyFraction']-a['lowEnergyFraction'])<.22,(voice,z,take,a,b)
    assert abs(b['t90ms']-a['t90ms'])<max(12,a['t90ms']*.25),(voice,z,take,a,b)
    rows.append({'voice':voice,'velocity':v,'take':take,'python':a,'native':b})
 report={'status':'72 statistical contact comparisons pass; independent noise, not sample equality or listening acceptance','cells':rows,'maxRmsRatio':max(r['native']['rms']/r['python']['rms'] for r in rows),'minRmsRatio':min(r['native']['rms']/r['python']['rms'] for r in rows),'maxLowFractionError':max(abs(r['native']['lowEnergyFraction']-r['python']['lowEnergyFraction']) for r in rows),'maxT90ErrorMs':max(abs(r['native']['t90ms']-r['python']['t90ms']) for r in rows)}
 Path(out).write_text(json.dumps(report,indent=2)+'\n');print({k:v for k,v in report.items() if k!='cells'})
if __name__=='__main__':main(*sys.argv[1:])
