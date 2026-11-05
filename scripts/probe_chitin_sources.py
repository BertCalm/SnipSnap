"""Measure actual Kotlin source exports against independent 16x renders.
Run after :synth:generateChitinAudition; requires numpy/scipy.
The source error includes resampling error; it is not a full-chain alias certificate.
"""
import json,sys
from pathlib import Path
import numpy as np
from scipy import signal
from scipy.io import wavfile
root=Path(sys.argv[1] if len(sys.argv)>1 else 'testkit/chitin-audition')
rows=[]
for midi in (24,48,72):
 for ratio in (1.0,2.3,5.7):
  signals={}
  for os in (4,16):
   for corrected in (True,False):
    rate,x=wavfile.read(root/f'source-{midi}-{ratio}-{os}-{str(corrected).lower()}.wav')
    x=x.astype(float)/2147483648
    x=signal.resample_poly(x,1,os,window=('kaiser',10.))
    x=signal.sosfiltfilt(signal.butter(6,[40,18000],btype='bandpass',fs=44100,output='sos'),x)
    signals[os,corrected]=x[2000:-2000]
  ref=signals[16,True]
  def error(x):
   # DC is removed by the house finish, and raw sinc guards can introduce a tiny offset.
   d=(x-ref);d-=np.mean(d)
   return float(20*np.log10(np.sqrt(np.mean(d*d))/np.sqrt(np.mean((ref-np.mean(ref))**2))))
  rows.append(dict(midi=midi,ratio=ratio,corrected_error_db=error(signals[4,True]),naive_error_db=error(signals[4,False])))
report={'scope':'Kotlin source-only 4x vs 16x; 40 Hz to 18 kHz approximate comparison band',
        'measurements':rows,'passes_proposed_minus55db':all(r['corrected_error_db']< -55 for r in rows)}
(root/'source-report.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
raise SystemExit(0 if report['passes_proposed_minus55db'] else 1)
