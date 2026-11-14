"""Compare actual Kotlin audition WAVs with independent recorded references. numpy/scipy required."""
import sys,json
from pathlib import Path
import numpy as np
from scipy.io import wavfile
from scipy.signal import welch,stft
root=Path(sys.argv[1]);report={}
def read(id):
 sr,x=wavfile.read(root/'audio'/(id+'-raw.wav'))
 return sr,x.astype(float)/2**31

def metrics(sr,x):
 energy=np.cumsum(x*x);energy/=energy[-1]
 f,p=welch(x[:sr],sr,nperseg=4096)
 F,t,z=stft(x,sr,nperseg=2048,noverlap=1536)
 spec=abs(z[:,(t>.1)&(t<.5)])**2;mask=(F>1000)&(F<10000)
 flat=float(np.mean(np.exp(np.mean(np.log(spec[mask]+1e-18),axis=0))/np.mean(spec[mask]+1e-18,axis=0)))
 return dict(centroidHz=float(sum(f*p)/sum(p)),energy50Seconds=float(np.searchsorted(energy,.5)/sr),
             energy90Seconds=float(np.searchsorted(energy,.9)/sr),spectralFlatness100to500ms=flat)
for voice in ['dark-ride','choked-crash','thin-crash']:
 result={}
 for label,suffix in [('reference','reference'),('previousSynth','old-source'),('fittedSynth','source')]:
  result[label]=metrics(*read('r7-'+voice+'-'+suffix))
 if voice!='thin-crash':result['heldOutTake']=metrics(*read('r7-'+voice+'-check'))
 report[voice]=result
# This checks matching to selected training examples, not perceptual realism or generalization.
for voice,row in report.items():
 target=row['reference'];new=row['fittedSynth']
 assert abs(new['centroidHz']/target['centroidHz']-1)<.20,(voice,'spectral center')
 assert abs(new['energy90Seconds']/target['energy90Seconds']-1)<.40,(voice,'decay')
 assert .5<new['spectralFlatness100to500ms']/target['spectralFlatness100to500ms']<2,(voice,'density')
report['scope']='Training-reference agreement only. Held-out takes reported without fitting. No listening or hardware verdict.'
(root/'reference-comparison-r7.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
