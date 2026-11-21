"""TUBES, version 5: v4 (the open tube the owner approved at its low note), corrected for pitch.

The owner, after choosing that a key names the tube's LOUDEST partial: "It doesn't sound very
tubular in higher notes." Measured (register_probe.py, 101 isolated hits against v4): the owner's
tubes keep, at every pitch, a weak fundamental an octave under the loudest partial and its odd
partials (0.5L at -15..-28 dB, 1.5L at -14..-18 dB), plus a lot of breath between the harmonics
(-3.5..-7 dB); v4 lost both above its fitted low note and grew a hard attack (-7 dB above 2 kHz
in the first 10 ms against -29) because its contact time shrank with the period. Three changes,
each a physical cause:
  ENDS   both open ends radiate; on odd harmonics they push in opposite directions, so a listener
         at the side hears those partly cancel. Odd harmonics x (1 - END_C)/(1 + END_C).
  PADDLE one paddle strikes every tube: its contact time is set in ms, not in periods.
  BREATH the push carries a short puff of air into the same column: noise driving the same
         modes (so the tube colours it), plus a little heard directly.
Constants END_C X CONTACT_MS RISE_MS BREATH RAD RADC_HZ DK: a 100-trial search (search_v5.py) across
six keys G3..G5, mapping B, against the owner's isolated hits banded by register; residual 8.0 dB.
v4 header, unchanged below:

TUBES, version 4: an open-open tube, struck once. No seal.

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
    DK=0.7655,          # fit: harmonic k dies at T1 / k^DK (radiation loss grows with frequency)
    RAD=1.103,         # fit: radiated gain k^RAD (open-end radiation grows with frequency)
    W_HARM2=.478,     # fit: contact length in periods of the 2nd harmonic (null near the 3rd)
    POP=.08,         # shape: the paddle on the rim, heard directly
    POP_LP=1500.0,   # shape: its low-pass at TOUCH .5 (Hz)
    KMAX=24,
    X=0.0488,         # fit: where along the column the push lands (fraction of length); 1/6 nulls the 3rd harmonic
    END_C=0.864,       # fit: two-end balance (0 = one end heard, 1 = odd harmonics cancel)
    CONTACT_MS=2.575,  # fit: paddle contact at TOUCH .5 and the default velocity (v4's value at 66 Hz)
    BREATH=0.2288,      # fit: air puff level into the column, relative to the push
    BREATH_MS=12.0,  # shape: how long the puff lasts
    RISE_MS=0.6588,      # fit: how fast the paddle's push arrives
    RADC_HZ=553.3,  # fit: where open-end radiation stops rising, in Hz (one tube diameter for the set)
    RADC=3.37,        # fit: harmonic where open-end radiation stops rising (ka near 1)
)


def render_tube(voice, m, midi, vel, take, seconds):
    n = int(seconds * INTERNAL)
    F = cp.hz(midi)
    rng = np.random.default_rng(sk.seed_for("CUPOLA", voice, midi, take, "v5"))
    ring_scale = sk.exp_map(m["RING"], .4, 2.5)
    # round robin, dealt: each take owns a quarter of the contact range and of the strike angle
    band = (take % 4 + rng.uniform(.15, .85)) / 4
    # a hard hit shortens the contact, but less than before: the first version made hard hits
    # sharper than any attack in the owner's loops (sample steps over 0.08 where the loops have none)
    touch = sk.exp_map(m["TOUCH"], 1.5, .65) * (1.2 - .4 * vel) / .88 * (.85 + .3 * band)
    w_ms = P["CONTACT_MS"] * touch                       # the paddle's contact, the same on every tube
    air = 1.0
    amp = vel * 10 ** (.5 * rng.uniform(-1, 1) / 20)
    # the paddle meets the air abruptly and lets go slowly: a fast raised-cosine rise and an
    # exponential release (a symmetric 3 ms bump filtered away the high tubes' own partials)
    n_all = int(seconds * INTERNAL)
    rise = max(2, int(P["RISE_MS"] * (1.5 - vel) / .7 * 1e-3 * INTERNAL)); tau = w_ms * 1e-3 * INTERNAL / 3   # harder = faster
    tail = int(6 * tau)
    shape = np.concatenate([.5 * (1 - np.cos(np.pi * np.arange(rise) / rise)), np.exp(-np.arange(tail) / tau)])
    drive = np.zeros(n_all); drive[: min(n_all, len(shape))] = shape[: n_all]
    drive /= drive.sum() / INTERNAL * 1e3                              # unit area per ms, as before
    # the air puff: seeded noise, decaying over BREATH_MS, low-passed by the paddle's softness
    nb = int(4 * P["BREATH_MS"] * 1e-3 * INTERNAL)
    puff = sk.Noise(sk.seed_for(voice, midi, take, "puff")).array(nb) * np.exp(-np.arange(nb) / (P["BREATH_MS"] * 1e-3 * INTERNAL))
    puff = signal.lfilter(*signal.butter(2, min(.45, 2500 / (w_ms / 3.6) / (INTERNAL / 2))), puff)
    breath = np.zeros(int(seconds * INTERNAL)); breath[:nb] = puff[: len(breath)]
    breath *= P["BREATH"] / max(1e-12, np.sqrt(np.mean(puff ** 2))) * np.max(drive) * .05

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
            rad = k ** P["RAD"] / (1 + (fk / P["RADC_HZ"]) ** P["RAD"])   # rises with frequency, then levels off (fixed in Hz)
            rad *= (1 - P["END_C"]) / (1 + P["END_C"]) if k % 2 else 1.0          # the two ends on odd harmonics
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
    p = shell.run([(hit, drive + breath)], {"out": radiate, "struck": np.where(hit > 0, radiate, 0.0)})
    ref = max(1e-12, np.max(np.abs(p["struck"])))
    struck, answer = p["struck"] / ref, (p["out"] - p["struck"]) / ref

    pop = signal.lfilter(*signal.butter(2, P["POP_LP"] * sk.exp_map(m["TOUCH"], .6, 2.0) * (.8 + .4 * vel) / (INTERNAL / 2)), drive)
    pop = cp.hp(pop, 80.0)
    pop /= max(1e-12, np.max(np.abs(pop)))
    direct = cp.hp(breath, 300.0); direct /= max(1e-12, np.max(np.abs(direct)))
    out = amp * (air * (struck + answer) + P["POP"] * (1.6 - air) * pop + .5 * P["BREATH"] * direct * .3)
    diag = {"w_ms": round(w_ms, 3), "air": round(air, 3), "kappa": round(kappa, 5),
            "springs": len(pairs) if kappa > 0 else 0, "peak_struck": float(np.max(np.abs(struck))) * amp,
            "peak_answer": float(np.max(np.abs(answer))) * amp,
            "answer_energy_over_struck_dB": round(10 * np.log10((np.sum(answer ** 2) + 1e-30) / (np.sum(struck ** 2) + 1e-30)), 1)}
    return out, diag


def install():
    cp.render_tube = render_tube
