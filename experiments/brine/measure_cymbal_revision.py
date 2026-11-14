"""Measure actual R6 WAVs; requires numpy/scipy. No perceptual cymbal verdict is inferred."""
import json
import pathlib
import sys
import numpy as np
from scipy.io import wavfile
from scipy.signal import welch
root = pathlib.Path(sys.argv[1])
report = {}
for voice in ['dark-ride', 'thin-crash', 'choked-crash']:
    report[voice] = {}
    for revision in ['before', 'after']:
        rate, x = wavfile.read(root / 'audio' / f'r6-{voice}-{revision}-raw.wav')
        x = x.astype(float) / 2**31
        energy = lambda lo, hi: float(np.sum(x[int(lo*rate):int(hi*rate)]**2))
        f, power = welch(x[int(.03*rate):int(.6*rate)], rate, nperseg=8192)
        report[voice][revision] = {
            'peak': float(np.max(np.abs(x))),
            'tailEnergy300to1000msOverAttack0to100ms': energy(.3, 1)/energy(0, .1),
            'bandPowerPercent30to600ms': {
                f'{lo}-{hi}Hz': float(100*np.sum(power[(f>=lo)&(f<hi)])/np.sum(power))
                for lo, hi in [(0,600),(600,1200),(1200,2500),(2500,5000),(5000,12000)]
            }
        }
# Regression guard for the rejected first modal candidate (91.8% in the low-mid band).
ride_bands = report['dark-ride']['after']['bandPowerPercent30to600ms']
assert ride_bands['600-1200Hz'] < 50, 'Ride collapsed into low-mid bell energy'
assert ride_bands['2500-5000Hz'] > 20, 'Ride lost its upper metal band'
assert report['thin-crash']['after']['bandPowerPercent30to600ms']['2500-5000Hz'] > 30
text = json.dumps(report, indent=2)
(root / 'metal-comparison.json').write_text(text+'\n')
print(text)
