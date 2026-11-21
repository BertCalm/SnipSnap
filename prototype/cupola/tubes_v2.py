"""TUBES, version 2: rebuilt against ISOLATED hits in the owner's tube loops.

Round 3 fitted the tubes to whole-loop spectra, whose strong 2x and 3x peaks were mostly the
OTHER tube of each note pair (the files are named notesCA, notesCB, ...). Single hits measured
(hits.py, Python harness): 2nd harmonic -12..-30 dB, 3rd -30..-42 dB, a component an octave
below the note at -16..-33 dB, a short pop about 10 dB over the ringing note at C4, body
brightness (centroid) about 1.7..2x the note, the note falling 20 dB in about 20..120 ms.

The tube, in three parts, all driven by one paddle slap:
  pop     the slap's own pressure pulse, heard directly: low-passed, a few ms
  sealed  while the paddle covers the end the tube is closed-open: odd modes of HALF the note.
          After the paddle lifts that mode does not vanish at once; it dies fast (shape: 80 ms).
          BLOOM is the seal time, so it sets how much of the low "thoom" is under the note.
  open    the open-open note: harmonics falling steeply (k^-TILT), upper ones dying sooner,
          in the same coupled network as the bundle neighbours (SYMPATHY).
Constants marked "fit" came from a 110-trial random search (search.py) against the median single-hit
harmonics of the C4 and B4 tubes: residual about 4.7 dB per partial. Not set by listening.
"""
from __future__ import annotations

import math
import numpy as np
from scipy import signal

import cupola_proto as cp
sk = cp.sk
INTERNAL = cp.INTERNAL

P = dict(
    TILT=1.01,          # fit: open harmonic gain k^-TILT (2nd about -20 dB at C4 once the pulse shapes it)
    T1=.20,            # fit: open fundamental T60 at C4 (s), x (261.6/f)^0.3
    DECAY_K=0.2,        # fit: harmonic k dies at T1 / k^DECAY_K
    PULSE_PERIODS=.45,     # the sharp-edged drive's relaxation, in periods
    SUB_LEVEL=.30,     # fit: sealed sub-octave at C4 and BLOOM .5, falling as (261.6/f)^2.5 (C4 -18 dB, B4 -30 dB measured)
    SUB_T60=.20,     # fit: the sealed mode dies over this once the end opens (s); C4 hits fall 20 dB in about 34 ms, refs 18..126
    POP_LEVEL=1.6,     # fit: the slap pulse relative to the open note's peak
    POP_LP=900.0,      # fit: the pop's low-pass (Hz) at TOUCH .5
    STRETCH=.0015,     # measured on single hits: 2x at 2.014..2.05; kept low for the root test
    W_PERIODS=0.673,   # fit (0.612 from the search, x1.1 to keep the default contact after velocity was given more reach)
    EDGE=0.911,          # fit: the sharp-edged share of the drive
    SLAP0=0.525,          # fit: share of the drive that lands at the slap rather than at the lift
    KMAX=16,           # harmonics kept (under 9 kHz)
)


def edge_pulse(tau_ms, seconds, at_s, edge_ms=.15):
    n = int(seconds * INTERNAL)
    out = np.zeros(n)
    e = max(2, int(edge_ms * 1e-3 * INTERNAL))
    tail = int(8 * tau_ms * 1e-3 * INTERNAL)
    shape = np.concatenate([.5 * (1 - np.cos(np.pi * np.arange(e) / e)),
                            np.exp(-np.arange(tail) / (tau_ms * 1e-3 * INTERNAL))])
    s = int(at_s * INTERNAL)
    end = min(n, s + len(shape))
    out[s:end] = shape[: end - s]
    return out / max(1e-12, shape.sum() / INTERNAL * 1e3)        # unit area per ms: level independent of tau


def render_tube(voice, m, midi, vel, take, seconds):
    n = int(seconds * INTERNAL)
    f0 = cp.hz(midi)
    rng = np.random.default_rng(sk.seed_for("CUPOLA", voice, midi, take, "v2"))
    ring_scale = sk.exp_map(m["RING"], .4, 2.5)
    t1 = P["T1"] * (261.6 / f0) ** .3 * ring_scale
    amp = vel * 10 ** (.5 * rng.uniform(-1, 1) / 20)

    # the paddle: TOUCH runs soft foam (long, dark) to hard (short, brighter); velocity shortens it
    # round robin, dealt rather than drawn: take k gets its own band of seal time and contact length,
    # so four takes are apart by construction (round 3's random draws put two tube takes 0.88 dB apart)
    band = (take % 4 + rng.uniform(.15, .85)) / 4                    # 0..1, a different quarter per take
    cband = ((take * 3) % 4 + rng.uniform(.15, .85)) / 4             # a different ordering for the contact
    touch = sk.exp_map(m["TOUCH"], 1.6, .6) * (1.45 - .8 * vel) * (1 + .24 * (cband - .5))
    w_open = 1e3 * P["PULSE_PERIODS"] / f0 * touch                    # ms
    # seal time: BLOOM, from a flick (1 ms) to a pressed paddle (25 ms); the take moves it
    seal_ms = sk.exp_map(m["BLOOM"], 1.0, 25.0) * (.7 + .6 * band)
    seal_s = seal_ms * 1e-3

    # sealed phase: odd modes of f0/2, driven by the slap, at full Q while sealed, then dying fast
    push = sk.hann_pulse(w_open * 2, INTERNAL, 1.0, seconds)
    sealed = np.zeros(n)
    for k, g in ((1, 1.0), (3, .12), (5, .03)):
        sealed += cp.ring(push, f0 * k / 2, g, max(t1 * 2, .15) / k ** .5)
    t = np.arange(n) / INTERNAL
    after = np.clip((t - seal_s) / P["SUB_T60"], 0, None)
    sealed *= 10 ** (-3 * after)                                       # -60 dB per SUB_T60 after the lift
    sealed /= max(1e-12, np.max(np.abs(sealed)))

    # open phase: driven at the lift, plus a little straight from the slap
    # the drive is sharp-edged (the end opens, or the paddle lands, abruptly) and then relaxes:
    # a fast raised-cosine edge and an exponential tail, so its spectrum falls only 6 dB per
    # octave and the upper harmonics keep the floor the references show (-35..-50 dB at 4x..8x)
    # Measured on single hits: the 3rd harmonic dips below the 4th and 5th (C4: -37 against -35,
    # -37). A soft contact lasting about 2/3 of one period has a spectral null at 3x the note,
    # which is that dip; TOUCH and velocity move the null (shorter contact, brighter). A small
    # sharp-edged part keeps the faint upper floor.
    w_soft = 1e3 * P["W_PERIODS"] / f0 * touch
    open_exc = (sk.hann_pulse(w_soft, INTERNAL, 1.0, seconds, at_seconds=min(seal_s, .02)) / w_soft
                + P["SLAP0"] * sk.hann_pulse(w_soft, INTERNAL, 1.0, seconds) / w_soft
                + P["EDGE"] * edge_pulse(w_open, seconds, min(seal_s, .02)))
    flds = cp.fields_for(voice, m)
    if midi not in flds:
        flds = flds + [midi]
    freqs, t60s, owner, gains = [], [], [], []
    for fm in flds:
        ff = cp.hz(fm)
        tt = P["T1"] * (261.6 / ff) ** .3 * ring_scale
        for k in range(1, P["KMAX"] + 1):
            fk = ff * k * (1 + P["STRETCH"] * (k - 1))
            if fk > 9000:
                break
            freqs.append(fk); t60s.append(tt / k ** P["DECAY_K"]); owner.append((fm, k)); gains.append(k ** -P["TILT"])
    kappa = 0.0 if m["SYMPATHY"] <= 0 else sk.exp_map(m["SYMPATHY"], cp.KAPPA_MAX / 25, cp.KAPPA_MAX)
    pairs = [(i, j) for i in range(len(freqs)) for j in range(i + 1, len(freqs))
             if owner[i][0] != owner[j][0] and abs(1200 * math.log2(freqs[i] / freqs[j])) < cp.WINDOW_CENTS]
    shell = cp.Shell(freqs, t60s, pairs if kappa > 0 else [], kappa)
    hit = np.array([1.0 if owner[i][0] == midi else 0.0 for i in range(len(freqs))])
    radiate = np.asarray(gains)
    p = shell.run([(hit, open_exc)], {"out": radiate, "struck": np.where(hit > 0, radiate, 0.0)})
    ref = max(1e-12, np.max(np.abs(p["struck"])))
    opened, answer = p["struck"] / ref, (p["out"] - p["struck"]) / ref

    # the pop: the slap pulse itself, low-passed (TOUCH brightens it), a seeded grain of noise
    pop = sk.hann_pulse(max(.4, w_open * .8), INTERNAL, 1.0, seconds)
    pop += .25 * sk.noise_burst(max(.6, w_open), INTERNAL, sk.seed_for(voice, midi, take, "pop"), 1.0, seconds)
    lp = P["POP_LP"] * sk.exp_map(m["TOUCH"], .6, 2.0) * (.8 + .4 * vel)
    pop = signal.lfilter(*signal.butter(2, lp / (INTERNAL / 2)), pop)
    pop = cp.hp(pop, 60.0)
    pop /= max(1e-12, np.max(np.abs(pop)))

    sub = P["SUB_LEVEL"] * (261.6 / f0) ** 2.5 * sk.lin(m["BLOOM"], .3, 1.7)
    out = amp * (opened + answer + sub * sealed + P["POP_LEVEL"] * (.6 + .5 * vel) * pop * .5)
    diag = {"w_open_ms": round(w_open, 3), "seal_ms": round(seal_ms, 2), "kappa": round(kappa, 5),
            "springs": len(pairs) if kappa > 0 else 0,
            "peak_struck": float(np.max(np.abs(opened))) * amp, "peak_answer": float(np.max(np.abs(answer))) * amp,
            "answer_energy_over_struck_dB": round(10 * np.log10((np.sum(answer ** 2) + 1e-30) / (np.sum(opened ** 2) + 1e-30)), 1)}
    return out, diag


def install():
    cp.render_tube = render_tube
