#!/usr/bin/env python3
"""Measure Kotlin contacts against the accepted Python auditions, without claiming byte parity.

Requires numpy/scipy. Each signal is gain-fitted after at most 1 ms alignment.
These are descriptive comparisons, not acoustic-recognition or acceptance thresholds.
"""
import argparse
import json
from pathlib import Path
import numpy as np
from scipy.io.wavfile import read
from scipy.signal import correlate, correlation_lags


def pcm(path):
    rate, x = read(path)
    assert rate == 44100 and x.ndim == 1
    return x.astype(float) / (2 ** (x.dtype.itemsize * 8 - 1))


def compare(kotlin, r2, r3):
    rows = []
    for ins in ['guitar', 'acoustic_bass', 'cello', 'upright']:
        for gesture, ref, folder in [('knuckle', 'knock', r2), ('slap', 'slap', r2),
                ('rake', 'rake', r2), ('palm', 'palm', r3), ('dead_string', 'muted', r3),
                ('harmonic', 'bridge', r3), ('scratch', 'scrape', r3)]:
            a = pcm(folder / f'{ins.replace("_", "-")}-{ref}-hard.wav')
            b = pcm(kotlin / 'contacts' / f'{ins}-{gesture}.wav')
            c = correlate(b, a, method='fft')
            lags = correlation_lags(len(b), len(a))
            ok = np.abs(lags) <= 44
            lag = int(lags[ok][np.argmax(c[ok])])
            if lag >= 0:
                aa, bb = a[:min(len(a), len(b)-lag)], b[lag:lag+min(len(a), len(b)-lag)]
            else:
                aa, bb = a[-lag:-lag+min(len(a)+lag, len(b))], b[:min(len(a)+lag, len(b))]
            scale = np.dot(aa, bb) / np.dot(aa, aa)
            residual = 10*np.log10(np.sum((bb-scale*aa)**2)/np.sum(bb**2))
            rows.append(dict(instrument=ins, gesture=gesture, alignment_frames=lag,
                correlation=float(np.dot(aa, bb)/np.sqrt(np.dot(aa, aa)*np.dot(bb, bb))),
                gain_fitted_residual_db=float(residual),
                native_rms_ratio_db=float(10*np.log10(np.mean(b*b)/np.mean(a*a)))))
    return dict(claim='Descriptive Python versus Kotlin comparison, not byte parity or listening approval',
                measurements=rows)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('kotlin', type=Path)
    parser.add_argument('python_r2', type=Path)
    parser.add_argument('python_r3', type=Path)
    args = parser.parse_args()
    report = compare(args.kotlin, args.python_r2, args.python_r3)
    (args.kotlin / 'python-comparison.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps({'contacts': len(report['measurements']),
        'minimum_correlation': min(r['correlation'] for r in report['measurements']),
        'maximum_correlation': max(r['correlation'] for r in report['measurements'])}))
