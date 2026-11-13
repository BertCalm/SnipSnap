"""Fit a coarse oscillator/noise dictionary to a supplied mixed drum recording.

The reference is used here only. The saved model renders without it. Fit and
holdout strike windows are disjoint; they are not isolated microphone stems.
"""
import argparse
import hashlib
import json
import time
from pathlib import Path
import numpy as np
from scipy import signal, optimize
from scipy.io import wavfile
from model import RATE, SECONDS, ROOT, components, definitions, render, sk

FIT_PEAKS = [1.4541, 2.3380, 2.8648, 3.7500, 4.2710, 5.1606, 5.6976]
HOLDOUT_PEAKS = [.9230, 7.9906, 8.5232, 9.3997]


def load(path):
    rate, x = wavfile.read(path)
    if np.issubdtype(x.dtype, np.integer):
        x = x.astype(float) / (np.iinfo(x.dtype).max + 1.)
    if x.ndim > 1:
        x = x.mean(axis=1)
    if rate != RATE:
        x = signal.resample_poly(x, RATE, rate)
    return x


def align(x, peak):
    start = round((peak - .025) * RATE)
    chunk = x[start:round((peak + .025) * RATE)]
    hp = signal.sosfilt(signal.butter(2, 900, fs=RATE, btype='highpass', output='sos'), chunk)
    env = np.convolve(hp ** 2, np.ones(44) / 44, 'same')
    idx = np.flatnonzero(env > env.max() * .10)[0]
    onset = max(0, start + idx - 22)
    return x[onset:onset + round(SECONDS * RATE)].copy(), onset / RATE


def complex_features(x):
    out = []
    # Short frames constrain the crack; longer frames resolve the body modes.
    for n in [256, 1024]:
        f, t, z = signal.stft(x, RATE, nperseg=n, noverlap=n * 3 // 4)
        select = (f >= 170) & (f <= 15000)
        times = t <= .12
        out.extend(z[select][:, times].ravel())
    return np.array(out)


def features(x):
    return np.abs(complex_features(x)) ** 2


def metrics(x):
    # Identical onset-aligned 65 ms measurement, including on the rejected model.
    a = x[:round(.065 * RATE)]
    f = np.fft.rfftfreq(len(a), 1 / RATE)
    power = np.abs(np.fft.rfft(a)) ** 2
    bands = {(str(lo) + '-' + str(hi)): float(power[(f >= lo) & (f < hi)].sum() / max(1e-20, power.sum()))
             for lo, hi in [(20, 150), (150, 900), (900, 3000), (3000, 12000)]}
    energy = np.cumsum(x[:round(.12 * RATE)] ** 2)
    return dict(bands=bands, energy90ms=float(np.searchsorted(energy, .90 * energy[-1]) / RATE * 1000),
                peak=float(np.max(abs(x))), dc=float(np.mean(x)))


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--reference', type=Path, required=True)
    p.add_argument('--previous', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    x = load(args.reference)
    fit = [align(x, p) for p in FIT_PEAKS]
    holdout = [align(x, p) for p in HOLDOUT_PEAKS]
    target = np.median([features(y) for y, _ in fit], axis=0)
    # Average powers across fresh noise realizations; fit no reference phase or waveform.
    basis = []
    complex_basis = []
    for take in range(4):
        c = components(take)
        z = np.array([complex_features(y) for y in c]).T
        complex_basis.append(z)
        basis.append(abs(z) ** 2)
    matrix = np.mean(basis, axis=0)
    weight = 1 / np.maximum(target, target.max() * .002) ** .65
    weighted = matrix * weight[:, None]
    norm = np.linalg.norm(weighted, axis=0)
    # Tiny ridge discourages an unstable, very large coefficient on a weak atom.
    a = weighted / norm
    powers, _ = optimize.nnls(np.vstack([a, np.eye(a.shape[1]) * .02]),
                              np.r_[target * weight, np.zeros(a.shape[1])], maxiter=10000)
    gains = np.sqrt(powers / norm)
    # Account for coherent components (same band with different decay) rather than
    # assuming their cross terms vanish. Optimize analytic amplitudes, never phases.
    zs = np.array(complex_basis)
    floor = target.max() * .002
    def residual(g):
        mixed = np.einsum('sfc,c->sf', zs, g)
        power = np.mean(abs(mixed) ** 2, axis=0)
        return np.log(power + floor) - np.log(target + floor)
    def jacobian(g):
        mixed = np.einsum('sfc,c->sf', zs, g)
        power = np.mean(abs(mixed) ** 2, axis=0)
        derivative = 2 * np.mean((mixed.conj()[:, :, None] * zs).real, axis=0)
        return derivative / (power + floor)[:, None]
    gains = optimize.least_squares(residual, gains, jac=jacobian, bounds=(0, np.inf),
                                   max_nfev=80).x
    model = dict(version=1, method='analytic oscillators and filtered seeded noise',
                 sampleRate=RATE, internalRate=RATE * 4, outputGain=1.,
                 definitions=definitions(), gains=gains.tolist(),
                 source='Parameter fit to supplied live-drum screen recording; no source audio in renderer')
    # One fixed gain for all takes/velocities. The page's separate matching is clip-wide.
    trial = [render(model, take=i) for i in range(6)]
    for name in ['tune', 'strike', 'body', 'wires']:
        trial.extend(render(model, **{name: value}) for value in [0., 1.])
    trial.extend(render(model, tune=v, strike=v, body=v, wires=v) for v in [0., 1.])
    model['outputGain'] = min(1., .78 / max(np.max(abs(y)) for y in trial))
    (args.output / 'model.json').write_text(json.dumps(model, indent=2) + '\n')
    generated = []
    times = []
    for i in range(6):
        start = time.perf_counter(); y = render(model, take=i); times.append((time.perf_counter() - start) * 1000)
        generated.append(y)
        sk.write_wav(str(args.output / f'take-{i}.wav'), y)
    previous = load(args.previous)
    if len(previous) < round(SECONDS * RATE):
        previous = np.pad(previous, (0, round(SECONDS * RATE) - len(previous)))
    # Compare average synthesized power to held-out strikes, using one global level alignment.
    predicted = np.mean([features(y) for y in generated], axis=0)
    old = features(previous[:round(SECONDS * RATE)])
    actual = np.median([features(y) for y, _ in holdout], axis=0)
    mask = actual > actual.max() * .002
    def error(p):
        scale = np.sum(actual) / max(1e-20, np.sum(p))
        return float(np.mean(abs(10 * np.log10(np.maximum(p[mask] * scale, 1e-12) / actual[mask]))))
    report = dict(harness='Python, not Kotlin', referenceFile=args.reference.name,
                  referenceSha256=hashlib.sha256(args.reference.read_bytes()).hexdigest(),
                  fitOnsetsSeconds=[t for _, t in fit], holdoutOnsetsSeconds=[t for _, t in holdout],
                  comparisonWindowSeconds=.12, limitations='Mixed recording: cymbal bleed, microphone and processing remain. No isolated snare stem.',
                  fit='nonnegative powers of 46 analytic components; first 120 ms, seven strikes',
                  metrics=dict(reference=[metrics(y) for y, _ in holdout], previous=metrics(previous),
                               candidate=[metrics(y) for y in generated]),
                  heldoutMeanAbsoluteLogPowerErrorDb=dict(previous=error(old), candidate=error(predicted)),
                  renderMs=dict(median=float(np.median(times)), maximum=float(max(times))),
                  listening='pending', integration='Python study only; native/MPC/SFZ gates remain open')
    (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    # Reference crop is for private comparison only, and never in the synthetic download.
    y = holdout[0][0][:round(.14 * RATE)].copy()
    y[-round(.003 * RATE):] *= np.linspace(1, 0, round(.003 * RATE))
    sk.write_wav(str(args.output / 'reference-crop.wav'), y)
    sk.write_wav(str(args.output / 'previous.wav'), previous[:round(SECONDS * RATE)])
    for stem in ['attack', 'body', 'tail']:
        sk.write_wav(str(args.output / f'stem-{stem}.wav'), render(model, stem=stem))
    print(json.dumps({k: report[k] for k in ['heldoutMeanAbsoluteLogPowerErrorDb', 'renderMs']}))

if __name__ == '__main__':
    main()
