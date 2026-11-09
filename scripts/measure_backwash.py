"""Compare dry attack spectra with the preserved rejected candidate; no listening claims."""
import argparse,json,csv,statistics
from pathlib import Path
import numpy as np
from scipy.io import wavfile
p=argparse.ArgumentParser();p.add_argument('current',type=Path);p.add_argument('previous',type=Path);a=p.parse_args()
def measure(path):
 rate,x=wavfile.read(path);x=x.astype(np.float64)/(2**31)
 n=min(len(x),round(.08*rate));power=abs(np.fft.rfft(x[:n]*np.hanning(n)))**2;hz=np.fft.rfftfreq(n,1/rate)
 total=max(power.sum(),1e-20)
 bands=[float(power[(hz>=lo)&(hz<hi)].sum()/total) for lo,hi in [(20,250),(250,2000),(2000,10000)]]
 energy=np.cumsum(x*x);t90=float(np.searchsorted(energy,.9*energy[-1])/rate)
 return {'attackBands':bands,'energy90Seconds':t90,'peak':float(abs(x).max()),'mean':float(x.mean())}
report={'revision':3,'method':'First 80 ms Hann-windowed spectral energy, bands 20–250 / 250–2000 / 2000–10000 Hz. Not a perceptual match score.','voices':{}}
for v in ['kick','snare','tom','hat_closed','hat_open']:
 report['voices'][v]={'previous':measure(a.previous/(v+'-stage-dry.wav')),'current':measure(a.current/(v+'-stage-dry.wav'))}
rows=list(csv.DictReader((a.current/'metrics.csv').open()))
report['render']={'medianMs':statistics.median(float(r['milliseconds']) for r in rows),'maxMs':max(float(r['milliseconds']) for r in rows),'maxSeconds':max(float(r['seconds']) for r in rows),'maxPeak':max(float(r['peak']) for r in rows),'maxAbsMean':max(abs(float(r['mean'])) for r in rows)}
(a.current/'summary.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))

# Broad rejection guards for the specific defects found in the discarded audition.
# They do not certify acoustic realism or enforce a match to the mixed recording.
for name in ['kick','tom']:
 assert report['voices'][name]['current']['attackBands'][1] > .08, name + ': missing midrange strike'
for name in ['hat_closed','hat_open']:
 assert report['voices'][name]['current']['attackBands'][1] < .2, name + ': low metallic body dominates'
assert report['voices']['snare']['current']['energy90Seconds'] > .03, 'snare collapses into a short burst'
assert report['voices']['tom']['current']['energy90Seconds'] > .03, 'tom collapses into a short burst'
print('Attack-band and energy-decay guards passed.')
