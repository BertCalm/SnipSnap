"""Compare native pre-output floats with the frozen accepted R5 Python model.
Run after :synth:generateHydroformAudition. Requires reference/hydroform/requirements.txt.
Raw parity is a port gate; output differences are measurements, not an identity assertion.
"""
from pathlib import Path
import importlib.util,json,sys
import numpy as np
from scipy import signal
root=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('hydroform_reference',root/'reference/hydroform/prototype.py')
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
folder=Path(sys.argv[1]) if len(sys.argv)>1 else root/'testkit/hydroform-audition'
rows=[]
for voice in ['DOME','BULKHEAD']:
 for case,factor in [('default',4),('hard',4),('hard',16)]:
  macros={k:float(np.float32(v)) for k,v in p.DEFAULT.items()}
  if case=='hard':macros.update(CHARGE=1.,YIELD=0.,ARREST=1.,TUNE=1.)
  name=voice.lower()+'-default' if case=='default' else voice.lower()+f'-hard-{factor}'
  native=np.fromfile(folder/'raw'/f'{name}.f32',dtype='<f4').astype(float)
  reference=p.raw(macros,rate=p.RATE*factor,voice=voice)
  assert native.shape==reference.shape,(name,native.shape,reference.shape)
  err=native-reference;peak=float(max(abs(err)));rms=float(np.sqrt(np.mean(err*err)))
  assert peak<1e-4 and rms<2e-5,(name,peak,rms)
  row={'case':name,'raw_peak_error':peak,'raw_rms_error':rms}
  if case=='default':
   finished=np.fromfile(folder/'conditioned'/f'{name}.f32',dtype='<f4').astype(float)
   python=p.finish(reference) if hasattr(p,'finish') else p.match(p.condition(reference))[0]
   lag=int(np.argmax(signal.correlate(finished[:20000],python[:20000],method='fft'))-19999)
   a=finished[max(lag,0):len(finished)+min(lag,0)];b=python[max(-lag,0):len(python)-max(lag,0)]
   gain=float(np.dot(a,b)/np.dot(b,b))
   row.update(output_alignment_frames=lag,output_fitted_gain=gain,output_residual_db=float(20*np.log10(max(np.linalg.norm(a-gain*b),1e-20)/np.linalg.norm(a))))
  rows.append(row)
report={'raw_parity_gate':'pass','raw_tolerance':{'peak':1e-4,'rms':2e-5},'rows':rows,
 'scope':'Python R5 vs Kotlin model 1. Macros quantized to Float before Python comparison. Output residual includes house filtering, resampling and loudness differences; not an alias bound.'}
(folder/'port-comparison.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
