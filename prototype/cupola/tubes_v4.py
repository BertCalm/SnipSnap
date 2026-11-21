"""TUBES, version 4: an open-open tube, struck once. No seal.

The owner: "The tube is open ended, but those sound like it's not." v2 and v3 both began as a
closed tube (a paddle sealing the end), to explain a component an octave under the loudest
partial. Measured again (Python harness), that component is the tube's own fundamental:
  - in the clean one-shot it rings LONGEST (T60 1.11 s, against 0.19 to 0.33 s above it); a
    closed phase would die first, at the lift
  - the partials between (1.5x, 2.5x of the loudest) are there too (1.5x a median 7 dB above the
    empty spots between harmonics, over 61 hits): a full series on the low fundamental
An open tube does all of this: every harmonic of c/2L; the open ends radiate the fundamental
weakly (radiation rises with frequency), so the 2nd harmonic is loudest; and they lose the
upper harmonics fastest (radiation loss rises with frequency), so the fundamental rings longest.

Model: one paddle strike at an open end (a velocity antinode for every mode, so every harmonic
is driven), a contact about 2/3 of a 2nd-harmonic period long (its spectral null makes the dip
the one-shot shows at the 3rd harmonic), harmonics k*F with radiation gain k^RAD and T60
T1/k^DK, all from ONE strike, in the coupled network with the bundle neighbours.

REGISTER: F, the tube's fundamental, is the MIDI note. The loudest partial is an octave above.
Constants: "fit" = a 110-trial search (fit_v4.py) against the clean one-shot: partial levels,
per-partial T60 and the envelope; residual 4.1 dB. The strike position came out at 0.155 of the
length, near the 1/6 that nulls the 3rd harmonic. "shape" = chosen, not measured.
"""
from __future__ import annotations

import math
import numpy as np
from scipy import signal

import cupola_proto as cp
sk = cp.sk
INTERNAL = cp.INTERNAL

P = dict(
    T1=.52,          # fit: fundamental T60 at 66 Hz (s), x (66/F)^0.5 for shorter tubes
    DK=.675,          # fit: harmonic k dies at T1 / k^DK (radiation loss grows with frequency)
    RAD=1.27,         # fit: radiated gain k^RAD (open-end radiation grows with frequency)
    W_HARM2=.478,     # fit: contact length in periods of the 2nd harmonic (null near the 3rd)
    POP=.08,         # shape: the paddle on the rim, heard directly
    POP_LP=1500.0,   # shape: its low-pass at TOUCH .5 (Hz)
    KMAX=24,
    X=.155,         # fit: where along the column the push lands (fraction of length); 1/6 nulls the 3rd harmonic
    RADC=3.37,        # fit: harmonic where open-end radiation stops rising (ka near 1)
)


def render_tube(voice, m, midi, vel, take, seconds):
    n = int(seconds * INTERNAL)
    F = cp.hz(midi)
    rng = np.random.default_rng(sk.seed_for("CUPOLA", voice, midi, take, "v4"))
    ring_scale = sk.exp_map(m["RING"], .4, 2.5)
    # round robin, dealt: each take owns a quarter of the contact range and of the strike angle
    band = (take % 4 + rng.uniform(.15, .85)) / 4
    # a hard hit shortens the contact, but less than before: the first version made hard hits
    # sharper than any attack in the owner's loops (sample steps over 0.08 where the loops have none)
    touch = sk.exp_map(m["TOUCH"], 1.5, .65) * (1.2 - .4 * vel) / .88 * (.85 + .3 * band)
    w_ms = 1e3 * P["W_HARM2"] / (2 * F) * touch
    air = 1.0
    amp = vel * 10 ** (.5 * rng.uniform(-1, 1) / 20)
    drive = sk.hann_pulse(w_ms, INTERNAL, 1.0, seconds) / w_ms

    flds = cp.fields_for(voice, m)
    if midi not in flds:
        flds = flds + [midi]
    freqs, t60s, owner, gains = [], [], [], []
    for fm in flds:
        ff = cp.hz(fm)
        t1 = P["T1"] * (66.0 / ff) ** .5 * ring_scale
        for k in range(1, P["KMAX"] + 1):
            fk = ff * k * (1 + .0005 * (k - 1))
            if fk > 9000:
                break
            rad = k ** P["RAD"] / (1 + (k / P["RADC"]) ** P["RAD"])        # rises, then levels off
            freqs.append(fk); t60s.append(max(.02, t1 / k ** P["DK"])); owner.append((fm, k)); gains.append(rad)
    kappa = 0.0 if m["SYMPATHY"] <= 0 else sk.exp_map(m["SYMPATHY"], cp.KAPPA_MAX / 25, cp.KAPPA_MAX)
    pairs = [(i, j) for i in range(len(freqs)) for j in range(i + 1, len(freqs))
             if owner[i][0] != owner[j][0] and abs(1200 * math.log2(freqs[i] / freqs[j])) < cp.WINDOW_CENTS]
    shell = cp.Shell(freqs, t60s, pairs if kappa > 0 else [], kappa)
    # the push lands at X along the column (the take moves it a little): mode k is driven by its
    # velocity shape there, cos(k*pi*x). One drive vector, one strike: every partial starts together.
    # BLOOM is that position: low = near the end (full, every harmonic), the default lands on the
    # fit (0.155, the 3rd harmonic hollowed), high = further in (the 2nd and 4th hollowed instead)
    x = sk.around(m["BLOOM"], P["X"], .04, .28) + .025 * (((take * 3) % 4 + rng.uniform(.15, .85)) / 4 - .5)
    hit = np.array([math.cos(owner[i][1] * math.pi * x) if owner[i][0] == midi else 0.0 for i in range(len(freqs))])
    radiate = np.asarray(gains)
    p = shell.run([(hit, drive)], {"out": radiate, "struck": np.where(hit > 0, radiate, 0.0)})
    ref = max(1e-12, np.max(np.abs(p["struck"])))
    struck, answer = p["struck"] / ref, (p["out"] - p["struck"]) / ref

    pop = signal.lfilter(*signal.butter(2, P["POP_LP"] * sk.exp_map(m["TOUCH"], .6, 2.0) * (.8 + .4 * vel) / (INTERNAL / 2)), drive)
    pop = cp.hp(pop, 80.0)
    pop /= max(1e-12, np.max(np.abs(pop)))
    out = amp * (air * (struck + answer) + P["POP"] * (1.6 - air) * pop)
    diag = {"w_ms": round(w_ms, 3), "air": round(air, 3), "kappa": round(kappa, 5),
            "springs": len(pairs) if kappa > 0 else 0, "peak_struck": float(np.max(np.abs(struck))) * amp,
            "peak_answer": float(np.max(np.abs(answer))) * amp,
            "answer_energy_over_struck_dB": round(10 * np.log10((np.sum(answer ** 2) + 1e-30) / (np.sum(struck ** 2) + 1e-30)), 1)}
    return out, diag


def install():
    cp.render_tube = render_tube
