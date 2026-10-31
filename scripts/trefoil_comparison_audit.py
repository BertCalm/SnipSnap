"""Measure the published Trefoil comparisons without changing their WAVs.

Requires NumPy. These are signal measurements, not a listening acceptance test.
Usage: python3 scripts/trefoil_comparison_audit.py [audition-directory]
"""
import hashlib
import json
import math
from pathlib import Path
import sys
import wave

import numpy as np

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'testkit/trefoil-model-two-audition')
manifest = json.loads((root / 'manifest.json').read_text())
clips = {clip['id']: clip for clip in manifest['clips']}
fingerprints = {}


def samples(clip_id):
    path = root / clips[clip_id]['matched']
    fingerprints[clip_id] = hashlib.sha256(path.read_bytes()).hexdigest()
    with wave.open(str(path)) as wav:
        assert wav.getnchannels() == 1 and wav.getsampwidth() == 2
        assert wav.getframerate() == 44100
        return np.frombuffer(wav.readframes(wav.getnframes()), dtype='<i2').astype(float) / 32768


def db(ratio):
    return 20 * math.log10(max(float(ratio), 1e-15))


comparisons = []
for group in ('ordered', 'unsettled'):
    reference = samples(group + '-live')
    for branch in ('frozen', 'independent', 'no-memory'):
        candidate = samples(group + '-' + branch)
        # A single least-squares scalar removes level differences. It is used
        # only for this measurement; playback retains the saved matching gains.
        gain = np.dot(reference, candidate) / np.dot(candidate, candidate)
        comparisons.append({
            'reference': group + '-live', 'candidate': group + '-' + branch,
            'sameGainResidualDb': db(np.linalg.norm(reference - candidate) / np.linalg.norm(reference)),
            'levelAlignedResidualDb': db(np.linalg.norm(reference - gain * candidate) / np.linalg.norm(reference)),
            'levelDifferenceDb': db(np.linalg.norm(candidate) / np.linalg.norm(reference)),
        })

fundamental = []
for clip_id in ('ordered', 'hollow', 'wire', 'unsettled', 'trace'):
    data = samples(clip_id)
    spectrum = abs(np.fft.rfft(data * np.hanning(len(data)))) ** 2
    frequencies = np.fft.rfftfreq(len(data), 1 / 44100)
    hz = 440 * 2 ** ((clips[clip_id]['midi'] - 69) / 12)
    band = (frequencies > hz * .95) & (frequencies < hz * 1.05)
    fundamental.append({'id': clip_id, 'rootHz': hz, 'fundamentalBandPowerPercent': float(100 * spectrum[band].sum() / spectrum.sum())})

report = {
    'audioSourceRevision': manifest['provenance']['revision'],
    'method': 'Published matched PCM16 mono WAVs; full-length Hann-window FFT; fundamental band is +/-5% of saved MIDI root. Residuals are RMS ratios relative to the reference. No time or phase alignment.',
    'interpretation': 'A small residual after scalar level alignment can explain a weak comparison. These measurements do not establish perceived difference or musical value.',
    'starterFundamentalPower': fundamental,
    'comparisons': comparisons,
    'matchedWavSha256': fingerprints,
    'heard': 'Josh reports difficulty distinguishing the comparisons; no later listening acceptance recorded.',
}
(root / 'comparison-audit.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({'fundamentalBandPowerPercent': [round(item['fundamentalBandPowerPercent'], 2) for item in fundamental], 'comparisons': comparisons}, indent=2))
