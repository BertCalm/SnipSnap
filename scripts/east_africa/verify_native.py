"""Independent output audit of Kotlin-exported PCM (not a replacement for JVM workflow tests)."""
from pathlib import Path
import json,sys,hashlib
import numpy as np
from scipy.io import wavfile
from scipy import signal
R=Path(__file__).resolve().parents[2]
OUT=Path(sys.argv[1] if len(sys.argv)>1 else R/'testkit/east-africa-kits')
paths=sorted(OUT.glob('*/audio/*.wav'));assert len(paths)==990,len(paths)
peak=dc=tail=0.
for p in paths:
 sr,a=wavfile.read(p);assert sr==44100 and a.ndim==1
 x=a.astype(float)/2147483648
 assert np.isfinite(x).all()
 peak=max(peak,float(np.max(abs(signal.resample_poly(x,4,1)))))
 dc=max(dc,float(abs(np.mean(x))))
 tail=max(tail,float(np.sqrt(np.mean(x[-441:]**2))))
 assert x[-1]==0,p
assert peak<1 and dc<.001 and tail<.003,(peak,dc,tail)
# Bound the output-filter alias energy for each stationary unit-noise band.
# This tests transfer functions, not all transient modulation or arbitrary new controls.
native=json.loads((R/'synth/src/main/resources/east-africa/native.json').read_text())
f,lp=signal.sosfreqz(native['lowpass'],worN=65536,fs=176400)
_,fir=signal.freqz(native['fir'],worN=65536,fs=176400)
fractions=[]
for filters in native['filters'].values():
 for b in filters:
  _,h=signal.sosfreqz(b['sos'],worN=65536,fs=176400)
  power=abs(h*lp*fir)**2
  fractions.append(float(power[f>22050].sum()/power.sum()))
report=dict(scope='Kotlin exported PCM, 990 variants; estimated true peak at 4x. Stationary noise-filter alias bound is not a full transient alias certification.',
 variants=len(paths),estimated_true_peak_4x=peak,max_abs_dc=dc,max_last_10ms_rms=tail,
 worst_stationary_noise_fraction_above_output_nyquist=max(fractions),
 sha256={str(p.relative_to(R)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [*sorted((R/'synth/src/main/resources/east-africa').glob('*.json')),*sorted((R/'synth/src/main/kotlin/com/snipsnap/synth').glob('EastAfrica*.kt'))]})
(OUT/'audio-checks.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
