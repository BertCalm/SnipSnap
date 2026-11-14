"""Compare broad drum-recording balance, not perceptual fidelity.
Usage: python measure_reference.py reference.wav previous.wav revised.wav
Requires NumPy/SciPy. Only statistics are exported, never reference PCM.
Reference uses the entire clip; synthetic comparisons use their first 4.8 seconds
(the original two-bar 100 BPM phrase, excluding appended tails).
"""
import json
import sys
import numpy as np
from scipy import signal
from scipy.io import wavfile

BANDS = [(30, 100), (100, 250), (250, 1000), (1000, 4000), (4000, 10000), (10000, 20000)]

def measure(path, seconds=None):
    rate, pcm = wavfile.read(path)
    if pcm.dtype.kind == 'i':
        x = pcm.astype(np.float64) / (2 ** (pcm.dtype.itemsize * 8 - 1))
    else:
        x = pcm.astype(np.float64)
    if x.ndim != 1:
        raise ValueError('Use mono comparison files')
    if seconds is not None:
        x = x[:round(seconds * rate)]
    f, power = signal.welch(x, rate, nperseg=4096)
    energy = np.array([power[(f >= lo) & (f < hi)].sum() for lo, hi in BANDS])
    return {
        'bands_percent': {f'{lo}-{hi}': round(float(value), 2)
                          for (lo, hi), value in zip(BANDS, 100 * energy / energy.sum())},
        'crest_db': round(float(20 * np.log10(np.abs(x).max() / np.sqrt(np.mean(x*x)))), 2),
    }

if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    print(json.dumps({name: measure(path, None if index == 0 else 4.8)
                     for index, (name, path) in enumerate(zip(('reference', 'previous', 'revised'), sys.argv[1:]))}, indent=2))
