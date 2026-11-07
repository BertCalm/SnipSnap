"""Independent, read-only pitch-clock measurements on the scratch audition stems."""
from pathlib import Path
import hashlib
import json
import math
import wave
import numpy as np
from scipy.signal import correlate, find_peaks

ROOT = Path(__file__).resolve().parents[1]
RATE = 44100


def read_pcm24(path):
    with wave.open(str(path)) as wav:
        assert (wav.getnchannels(), wav.getsampwidth(), wav.getframerate()) == (1, 3, RATE)
        data = np.frombuffer(wav.readframes(wav.getnframes()), dtype=np.uint8).reshape(-1, 3)
    pcm = data[:, 0].astype(np.int32) | (data[:, 1].astype(np.int32) << 8) | (data[:, 2].astype(np.int32) << 16)
    pcm = np.where(pcm >= 0x800000, pcm - 0x1000000, pcm)
    return pcm.astype(np.float64) / 8388608


def track(samples):
    size, hop = 4410, 441
    lo, hi = int(RATE / 700), int(RATE / 120)
    observations = []
    for start in range(0, len(samples) - size + 1, hop):
        z = np.asarray(samples[start:start + size], dtype=np.float64)
        z = z - np.mean(z)
        if np.sqrt(np.mean(z * z)) < 1e-4:
            continue
        ac = correlate(z, z, mode='full', method='fft')[size - 1:]
        power = np.r_[0.0, np.cumsum(z * z)]
        lag = np.arange(hi + 2)
        denominator = np.sqrt(power[size - lag] * (power[size] - power[lag]))
        ncc = np.divide(ac[:hi + 2], denominator, out=np.zeros(hi + 2), where=denominator > 1e-15)
        peaks = find_peaks(ncc[lo:hi + 1])[0] + lo
        if len(peaks) == 0:
            continue
        best = float(np.max(ncc[peaks]))
        eligible = peaks[(ncc[peaks] >= .85) & (ncc[peaks] >= best - .02)]
        if len(eligible) == 0:
            continue
        chosen = int(eligible[0])
        a, b, c = ncc[chosen - 1:chosen + 2]
        delta = .5 * (a - c) / (a - 2 * b + c) if a - 2 * b + c else 0.0
        hz = RATE / (chosen + np.clip(delta, -.5, .5))
        observations.append({'seconds': (start + size / 2) / RATE, 'hz': float(hz), 'ncc': float(b)})
    return observations


def target(time, base):
    grown = 1 - np.exp(-np.maximum(0, time - .12) * 6)
    cents = (8.5 * grown * np.sin(2 * math.pi * (5.23 * time + .31))
             + 3.5 * np.sin(2 * math.pi * (.61 * time + .07))
             + 2.0 * np.sin(2 * math.pi * (1.37 * time + .62)))
    return base * np.power(2.0, cents / 1200)


def main():
    manifest = json.loads((ROOT / 'render.json').read_text())
    a_path = ROOT / 'audio/a-alone.wav'
    b_path = ROOT / 'audio/synthetic-b-alone.wav'
    a = read_pcm24(a_path)
    b = read_pcm24(b_path)
    # Different declared onsets are removed before comparison. Boundary envelopes excluded.
    source = [o for o in track(a) if .50 <= o['seconds'] <= 1.85]
    synthetic = [o for o in track(b) if .545 <= o['seconds'] <= 1.895]
    source_time = np.array([o['seconds'] - .20 for o in source])
    source_hz = np.array([o['hz'] for o in source])
    out_time = np.array([o['seconds'] - .245 for o in synthetic])
    out_hz = np.array([o['hz'] for o in synthetic])
    # The independent tracker averages a 100 ms window. Average the declared clock similarly.
    sub = np.linspace(-.05, .05, 101)
    expected = np.array([np.mean(target(t + sub, manifest['sourceReferenceHzB'])) for t in out_time])
    inherited = np.interp(out_time / .992, source_time, source_hz)
    cents = 1200 * np.log2(out_hz / expected)
    source_cents = 1200 * np.log2(inherited / np.median(inherited))
    output_cents = 1200 * np.log2(out_hz / np.median(out_hz))
    expected_cents = 1200 * np.log2(expected / np.median(expected))
    result = {
        'renderJsonSha256': hashlib.sha256((ROOT / 'render.json').read_bytes()).hexdigest(),
        'deliveredSoloHashes': {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in [a_path, b_path]},
        'method': 'Independent normalized broad autocorrelation, 100 ms windows / 10 ms hop; first high-confidence local peak within .02 of strongest, 120–700 Hz. No production pitch-analysis values consumed.',
        'pairedWindows': len(out_hz),
        'outputMedianHz': float(np.median(out_hz)),
        'outputMinMaxHz': [float(np.min(out_hz)), float(np.max(out_hz))],
        'nccMinimum': float(min(o['ncc'] for o in synthetic)),
        'expectedClockCorrelation': float(np.corrcoef(output_cents, expected_cents)[0, 1]),
        'sourceContourCorrelation': float(np.corrcoef(output_cents, source_cents)[0, 1]),
        'declaredClockErrorMedianCents': float(np.median(cents)),
        'declaredClockErrorP95AbsCents': float(np.percentile(np.abs(cents), 95)),
        'sourceContourP05P95Cents': float(np.percentile(source_cents, 95) - np.percentile(source_cents, 5)),
        'outputContourP05P95Cents': float(np.percentile(output_cents, 95) - np.percentile(output_cents, 5)),
        'observations': [{'secondsAfterSyntheticOnset': float(t), 'measuredHz': float(h), 'expectedHz': float(e), 'errorCents': float(c)} for t, h, e, c in zip(out_time, out_hz, expected, cents)],
        'limits': ['Numerical pitch evidence does not establish a separate perceived singer.', 'FormantScale1.08 is a declared grain-processing parameter; no claim of measured human formant identity is made.'],
    }
    (ROOT / 'reviewer/pitch-clock-audit.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({k: v for k, v in result.items() if k != 'observations'}, indent=2))


if __name__ == '__main__':
    main()
