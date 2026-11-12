"""Fit MIRRORBALL's tambourine to BACKWASH's CC0 tambourine recordings: jingle modes struck by a collision train.

The owner asked on 2026-11-12: "Can we recreate the tambourine with synthesis?" BACKWASH's designed tambourines
(model 4 and its revisions) were heard as lacking "a living rattle" and were replaced by these recordings, so the
synthesis here is fitted to them, as BREAKER's cymbals were fitted to theirs (scripts/fit_breaker_cymbals.py).

Sources (already in the repository, CC0, see synth/src/main/resources/backwash/v5/NOTICE.txt):
  synth/src/main/resources/backwash/v5/tambourine-1-0.wav, -1-1.wav  (the soft layer, two takes)
  synth/src/main/resources/backwash/v5/tambourine-2-0.wav, -2-1.wav  (the hard layer, two takes)

Usage: python3 -I scripts/fit_mirrorball_tambourine.py <repo root> <out.json> [<audition dir>]

The model (docs/MIRRORBALL.md, "The tambourine"):
- jingles: the six most prominent spectral peaks in every third-octave from 1 to 16 kHz of the four recordings'
  first 300 ms (a 16384-point average spectrum), each a frequency and an amplitude. Each mode rings with a T60 of RING_S;
- collisions: a train of impulses about COLLISION_MS apart (measured: 57 to 69 collisions per hit, 3 to 5 ms
  apart), each striking a random third of the jingles with a random sign, so no two collisions sound alike;
- the envelope: each collision's level follows the recording's own 4 to 11 kHz envelope, per layer (hard: loudest
  at once; soft: building to about 50 ms), averaged over the layer's two takes, 5 ms steps;
- the hand: a short broadband click at the front, its level measured from the first 5 ms;
- per third-octave band (1 kHz to 16 kHz): one level correction per layer, matched to the recording over 0 to 300
  ms (two passes).
Only these numbers ship. The synthesis never reads the recordings.
"""
import json
import math
import os
import sys

import numpy as np
from scipy import signal
from scipy.io import wavfile

FS = 44100
HOP_S = .005
ENV_S = .8
PER_BAND = 6
RING_S = .06
COLLISION_MS = 4.0
HIGHPASS_HZ = 1500.0
BANDS = [1000 * 2 ** (k / 3) for k in range(13)]   # 1 to 16 kHz


def load(path):
    r, x = wavfile.read(path)
    assert r == FS, f"{path}: {r}"
    x = x.astype(np.float64)
    if x.ndim > 1:
        x = x.mean(axis=1)
    x /= np.max(np.abs(x))
    on = max(0, int(np.argmax(np.abs(x) > .05)) - int(.001 * FS))
    return x[on:]


def band_sos(fc):
    lo, hi = fc / 2 ** (1 / 6), min(fc * 2 ** (1 / 6), FS * .49)
    return signal.butter(4, [lo / (FS / 2), hi / (FS / 2)], "band", output="sos")


def third_octaves(x):
    seg = x[: int(.3 * FS)]
    return np.array([10 * math.log10(np.mean(signal.sosfilt(band_sos(fc), seg) ** 2) + 1e-20) for fc in BANDS])


def envelope(x):
    """The 4 to 11 kHz energy envelope, dB re its own peak, at HOP_S steps over ENV_S."""
    y = signal.sosfilt(signal.butter(4, [4000 / (FS / 2), 11000 / (FS / 2)], "band", output="sos"), x)
    h = int(HOP_S * FS)
    n = int(ENV_S / HOP_S)
    e = np.array([np.mean(y[i * h:(i + 1) * h] ** 2) if (i + 1) * h <= len(y) else 1e-20 for i in range(n)])
    e = 10 * np.log10(e + 1e-20)
    return e - e.max()


def fit_modes(clips):
    n = 16384
    spec = np.zeros(n // 2 + 1)
    for x in clips:
        seg = np.zeros(n)
        m = min(n, int(.3 * FS), len(x))
        seg[:m] = x[:m] * np.hanning(m)
        spec += np.abs(np.fft.rfft(seg)) ** 2
    f = np.fft.rfftfreq(n, 1 / FS)
    mag = np.sqrt(spec)
    pk, prop = signal.find_peaks(mag, prominence=0, distance=4)
    # Up to PER_BAND of the most prominent peaks in every third-octave, so no band is left without a jingle
    # to tune (a global top-N left the 1.6 to 3 kHz and 10 kHz bands empty and 5 to 9 dB short).
    chosen = []
    for fc in BANDS:
        inside = [i for i in range(len(pk)) if fc / 2 ** (1 / 6) <= f[pk[i]] < fc * 2 ** (1 / 6)]
        inside.sort(key=lambda i: -prop["prominences"][i])
        chosen += [pk[i] for i in inside[:PER_BAND]]
    pk = np.sort(np.array(chosen))
    amps = mag[pk] / mag[pk].max()
    return [float(v) for v in f[pk]], [float(v) for v in amps]


def render(p, layer, velocity=1.0, seed=1, n_s=.8):
    """The synthesis, in Python (the Kotlin port in MirrorballTambourine.kt is its twin)."""
    rng = np.random.default_rng(seed)
    n = int(n_s * FS)
    env = np.array(p["envelope"][layer])
    step = COLLISION_MS * 1e-3
    exc = np.zeros((len(p["hz"]), n))
    t = 0.0
    while t < ENV_S:
        i = int(t * FS)
        if i >= n:
            break
        lvl = 10 ** (np.interp(t / HOP_S, np.arange(len(env)), env) / 20)
        hit = rng.random(len(p["hz"])) < .33
        signs = np.where(rng.random(len(p["hz"])) < .5, -1.0, 1.0)
        jitter = 10 ** (rng.normal(0, 3) / 20)
        exc[hit, i] += signs[hit] * lvl * jitter
        t += step * (.6 + .8 * rng.random())
    out = np.zeros(n)
    for k, hz in enumerate(p["hz"]):
        r = math.exp(-6.907755 / (RING_S * FS))
        w = 2 * math.pi * hz / FS
        b = [math.sin(w) * p["amp"][layer][k]]
        a = [1, -2 * r * math.cos(w), r * r]
        out += signal.lfilter(b, a, exc[k])
    # The hand on the frame: a 3 ms click.
    m = int(.003 * FS)
    click = rng.standard_normal(m) * np.exp(-np.arange(m) / (.0008 * FS))
    click = signal.sosfilt(signal.butter(2, 2500 / (FS / 2), "high", output="sos"), click)
    out[:m] += p["click"][layer] * click
    # The recordings hold almost nothing under 2 kHz (about 36 dB down); the higher jingles' skirts leak there, so
    # a gentle high-pass takes it out (second order, HIGHPASS_HZ).
    out = signal.sosfilt(signal.butter(2, HIGHPASS_HZ / (FS / 2), "high", output="sos"), out)
    return out / max(np.max(np.abs(out)), 1e-12)


def main(root, out_path, audition=None):
    base = os.path.join(root, "synth/src/main/resources/backwash/v5")
    layers = {"soft": [load(os.path.join(base, f"tambourine-1-{t}.wav")) for t in (0, 1)],
              "hard": [load(os.path.join(base, f"tambourine-2-{t}.wav")) for t in (0, 1)]}
    hz, amp = fit_modes(layers["soft"] + layers["hard"])
    p = {"hz": hz, "amp": {"soft": amp[:], "hard": amp[:]}, "envelope": {}, "click": {}, "ringS": RING_S,
         "collisionMs": COLLISION_MS, "hopS": HOP_S, "highpassHz": HIGHPASS_HZ}
    for layer, clips in layers.items():
        p["envelope"][layer] = [round(float(v), 2) for v in np.mean([envelope(x) for x in clips], axis=0)]
        p["click"][layer] = .5 if layer == "hard" else .15
    report = {}
    for layer, clips in layers.items():
        target = np.mean([third_octaves(x / np.sqrt(np.mean(x[:int(.3 * FS)] ** 2))) for x in clips], axis=0)
        for _ in range(3):
            y = render(p, layer)
            got = third_octaves(y / np.sqrt(np.mean(y[:int(.3 * FS)] ** 2)))
            corr = np.clip(target - got, -18, 18)
            for k, f in enumerate(hz):
                b = int(np.argmin([abs(math.log2(f / fc)) for fc in BANDS]))
                p["amp"][layer][k] *= 10 ** (corr[b] / 20)
        y = render(p, layer)
        got = third_octaves(y / np.sqrt(np.mean(y[:int(.3 * FS)] ** 2)))
        env_err = np.abs(envelope(y)[:80] - p["envelope"][layer][:80])
        report[layer] = {"third_octave_error_db": [round(float(v), 1) for v in got - target],
                         "envelope_error_db_0_400ms_median": round(float(np.median(env_err)), 1),
                         "envelope_error_db_0_400ms_max": round(float(np.max(env_err)), 1)}
        if audition:
            os.makedirs(audition, exist_ok=True)
            for s in (1, 2):
                wavfile.write(os.path.join(audition, f"tamb-synth-{layer}-{s}.wav"), FS, (render(p, layer, seed=s) * .9).astype(np.float32))
            wavfile.write(os.path.join(audition, f"tamb-recorded-{layer}.wav"), FS, (clips[0] * .9).astype(np.float32))
    p["amp"] = {k: [round(float(v), 5) for v in a] for k, a in p["amp"].items()}
    p["hz"] = [round(v, 1) for v in hz]
    p["source"] = "BACKWASH v5 tambourine-1-0/1-1 (soft), 2-0/2-1 (hard), CC0; fitted by scripts/fit_mirrorball_tambourine.py"
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w") as fh:
        json.dump(p, fh, indent=1)
    print(json.dumps(report, indent=1))


if __name__ == "__main__":
    if len(sys.argv) not in (3, 4):
        print(__doc__)
        sys.exit(2)
    main(*sys.argv[1:])
