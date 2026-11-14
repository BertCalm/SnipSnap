"""Measure actual R9 Kotlin renders. These guards do not establish perceptual realism."""
import pathlib,sys,json
import numpy as np
from scipy.io import wavfile
from scipy.signal import welch
root=pathlib.Path(sys.argv[1]);report={}
def metrics(file):
 sr,x=wavfile.read(file); x=x.astype(float)/2**31
 assert sr==44100 and x.ndim==1 and np.isfinite(x).all()
 f,p=welch(x[:sr],sr,nperseg=4096)
 ratio=p[(f>=200)&(f<1000)].sum()/p[(f>=2000)&(f<8000)].sum()
 en=np.cumsum(x*x)
 return {'lowMidToDefinitionDb':float(10*np.log10(ratio)),
  'energy90Seconds':float(np.searchsorted(en,.9*en[-1])/sr),
  'energyAfter300ms':float(np.sum(x[int(.3*sr):]**2)),
  'peak':float(np.max(np.abs(x))),'mean':float(np.mean(x))}
for voice in ['dark-ride','thin-crash','choked-crash']:
 row={}
 for variant in ['current','lean','focused']:
  id=f'r9-{voice}-{variant}'
  row[variant]={stage:metrics(file) for stage,file in [
   ('source',root/'diagnostics'/f'{id}-source.wav'),
   ('recorded',root/'diagnostics'/f'{id}-recorded.wav'),
   ('full',root/'audio'/f'{id}-raw.wav')]}
 for stage in ['recorded','full']:
  assert row['lean'][stage]['lowMidToDefinitionDb']<row['current'][stage]['lowMidToDefinitionDb']-6,(voice,stage,row)
  if voice!='choked-crash':
   assert row['focused'][stage]['energy90Seconds']<row['current'][stage]['energy90Seconds']*.75,(voice,stage,row)
 report[voice]=row
if (root/'audio/r10-dark-ride-ring-raw.wav').exists():
 row=report['dark-ride']
 row['ring']={stage:metrics(file) for stage,file in [
  ('source',root/'diagnostics/r10-dark-ride-ring-source.wav'),
  ('recorded',root/'diagnostics/r10-dark-ride-ring-recorded.wav'),
  ('full',root/'audio/r10-dark-ride-ring-raw.wav')]}
 for stage in ['recorded','full']:
  assert row['focused'][stage]['energy90Seconds']*1.2<row['ring'][stage]['energy90Seconds']<row['lean'][stage]['energy90Seconds']*.75
  assert row['ring'][stage]['lowMidToDefinitionDb']<row['current'][stage]['lowMidToDefinitionDb']-8
report['scope']='Measured frequency balance and energy decay of Kotlin renders; no listening claim. Choke envelope intentionally preserved.'
(root/'cymbal-reset-measurements.json').write_text(json.dumps(report,indent=2)+'\n')
for voice,row in report.items():
 if not isinstance(row,dict):continue
 print(voice,[(v,round(x['full']['lowMidToDefinitionDb'],1),round(x['full']['energy90Seconds'],3)) for v,x in row.items()])
