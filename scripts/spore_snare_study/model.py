"""Sample-free Python listening study, adapted from Luthier's proto_template.py.

Only oscillators, seeded noise, filters and analytic envelopes run in render().
The small fitted coefficient table contains no waveform or spectral frames.
This is statistical sound synthesis, not a literal membrane/contact simulation.
"""
from pathlib import Path
import sys
import numpy as np
from scipy import signal

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / '.claude/skills/luthier/scripts'))
import snipkit as sk

RATE = 44100
INTERNAL = RATE * 4
SECONDS = .45
# Centers chosen from broad regions and persistent peaks in the supplied live clip.
MODES = [300., 520., 780., 1285., 1650.]
EDGES = [140., 260., 400., 700., 1100., 1800., 3000., 5000., 8500., 15000.]
DECAYS = [.035, .10, .26, .55]  # seconds to -60 dB; a coarse analytic dictionary


def definitions():
    return ([dict(kind='body', hz=f, t60=d) for f in MODES for d in [.10, .30]] +
            [dict(kind='noise', low=a, high=b, t60=d)
             for a, b in zip(EDGES[:-1], EDGES[1:]) for d in DECAYS])


def condition(raw, rate=INTERNAL):
    """Python approximation of house conditioning; fixed gain, no hit normalization."""
    raw = sk.band_limit(raw, rate)
    if rate == INTERNAL:
        out = sk.decimate4(raw)
    else:
        out = signal.resample_poly(raw, RATE, rate)
    out = sk.dc_block(out)
    return sk.fade_tail(out)


def components(take=0, velocity=1., tune=.5, strike=.5, body=.5, wires=.5, rate=INTERNAL, conditioned=True):
    for value in (velocity, tune, strike, body, wires):
        if not np.isfinite(value) or not 0 <= value <= 1:
            raise ValueError('Controls must be finite and within 0..1')
    if not isinstance(take, int) or take < 0:
        raise ValueError('take must be a nonnegative integer')
    t = np.arange(round(SECONDS * rate)) / rate
    rng = sk.Noise(sk.seed_for('spore-snare-study', 1, take))
    pitch = 2 ** ((tune - .5) * 8 / 12)
    # Variation changes contact width, phases and spectral balance, not drum tuning.
    contact = (.00065 * (1.35 - .35 * velocity) * 2 ** (1 - 2 * strike)
               * (1 + .15 * rng.next()))
    noise = rng.array(len(t))
    # Prewarm filters to avoid a low-frequency startup ramp masquerading as contact.
    warm = rng.array(round(.04 * rate))
    band_noise = {}
    for a, b in zip(EDGES[:-1], EDGES[1:]):
        sos = signal.butter(2, [a * pitch, b * pitch], fs=rate, btype='bandpass', output='sos')
        z = signal.sosfilt(sos, np.concatenate([warm, noise]))[len(warm):]
        # Fixed theoretical broadband scaling, never normalize individual realizations.
        band_noise[a] = z * np.sqrt(3 * rate / (2 * (b - a) * pitch))
    result = []
    for d in definitions():
        decay = d['t60'] * (2 ** ((body - .5) * 1.5) if d['kind'] == 'body'
                             else 2 ** ((wires - .5) * 1.2))
        envelope = (1 - np.exp(-t / contact)) * np.exp(-np.log(1000) * t / decay)
        if d['kind'] == 'body':
            # A short upward recovery follows the struck, initially slackened head.
            # Its magnitude is a voicing hypothesis, not a mechanical derivation.
            hz = d['hz'] * pitch
            phase = 2 * np.pi * hz * (t - .055 * velocity * .005 * (1 - np.exp(-t / .005)))
            source = np.sin(phase + .20 * rng.next())
            gain = 1 + .12 * rng.next()
        else:
            source = band_noise[d['low']]
            brightness = np.sqrt(d['low'] * d['high']) / 1500
            gain = brightness ** ((velocity - 1) * .28 + (strike - .5) * .3)
            gain *= (1 + .10 * rng.next())
        raw = source * envelope * gain * velocity ** 1.15
        result.append(condition(raw, rate) if conditioned else raw)
    return np.array(result)


def render(model, take=0, velocity=1., tune=.5, strike=.5, body=.5, wires=.5, stem=None):
    if model.get('version') != 1 or model.get('definitions') != definitions():
        raise ValueError('Unsupported model version or component dictionary')
    coefficients = np.asarray(model['gains'])
    if (coefficients.shape != (len(definitions()),) or not np.isfinite(coefficients).all()
            or np.any(coefficients < 0) or not np.isfinite(model['outputGain'])
            or not 0 < model['outputGain'] <= 1):
        raise ValueError('Invalid synthesis gains')
    c = components(take, velocity, tune, strike, body, wires, conditioned=False)
    gains = np.asarray(model['gains']) * model['outputGain']
    if stem is not None:
        if stem not in ('attack', 'body', 'tail'):
            raise ValueError(stem)
        mask = [d['kind'] == 'body' if stem == 'body' else
                d['kind'] == 'noise' and (d['t60'] <= .10 if stem == 'attack' else d['t60'] > .10)
                for d in definitions()]
        gains = gains * np.asarray(mask)
    return condition(gains @ c)
