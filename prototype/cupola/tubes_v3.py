"""TUBES, version 3: one air column, one strike.

The owner heard v2 as "incoherent". v2 was three layers scaled on their own (a pop, a sealed
sub-octave, an open note) and the open note was struck twice (at the slap and at the lift).
v3 is ONE waveguide loop: the slapped tube. While the paddle seals its end the round trip
reflects with opposite signs at the two ends (closed + open), so the same column rings an octave
lower with odd harmonics. When the paddle lifts, the paddle end becomes open too and the same
energy carries on at the note. One state, one excitation, one decay: nothing to drift apart.

Kotlin route: Strings.Loop (Strings.kt:529) with reflected() / inject() (:585, :643), the
waveguide BORE's pipe is built on (Bore.kt:30). The end sign is applied between reflected() and
inject(), one multiply per sample.

Loop:   y[n] = x[n] + s(t) * g * LP(y)[n - D]
  D     round trip of the open tube (rate / f0, less the loop filter's phase delay)
  s(t)  -1 while sealed (closed-open: f0/2 and its odd harmonics), raised-cosine to +1 over the
        lift (open-open: f0 and all harmonics)
  g     loss per round trip from the open T60; LP a gentle one-pole (upper harmonics die sooner)
Out:    radiation from the open end(s): a first difference (radiation grows with frequency at
        these sizes), plus the slap's own pulse, low-passed, from the same instant.
Constants: "fit" = matched to isolated single hits by fit_v3.py; "shape" = chosen, not measured.
"""
from __future__ import annotations

import math
import numpy as np
from scipy import signal

import cupola_proto as cp
sk = cp.sk
INTERNAL = cp.INTERNAL

P = dict(
    T1=.36,     # fit to the clean one-shot (oneshot8, C3): falls 20 dB in about 120 ms; the loop-cut hits are not clean enough to set decay
    LP_HARM=2.0,     # fit: loop low-pass corner in harmonics of the note (at the search's lower bound)
    W_PERIODS=.40,     # fit (search_v3.py, 90 trials, residual 8.6 dB per partial; at the search's lower bound)
    LIFT_MS=.4,       # shape: how long the paddle takes to leave the end (1.5 ms lost too much energy while the end sign crossed zero)
    RAD=.54,     # fit: radiation first-difference weight
    POP=.10,     # fit: the slap pulse heard directly, relative to the drive
    POP_LP=1200.0,    # shape: the slap's low-pass at TOUCH .5 (Hz)
    SEAL_LO=8.7,     # fit: seal at BLOOM 0 (ms, at C4; longer tubes are sealed longer)
    SEAL_HI=60.0,     # shape: seal at BLOOM just under the held top (ms, at C4)
)


def _lp_coef(fc: float) -> float:
    return math.exp(-2 * math.pi * fc / INTERNAL)


def waveguide(x: np.ndarray, f0: float, t60: float, sign: np.ndarray) -> np.ndarray:
    """The loop, solved a round trip at a time: every sample only needs y from D samples back,
    so a block of D samples is one vector step (exact, not an approximation)."""
    a = _lp_coef(min(P["LP_HARM"] * f0, INTERNAL * .45))
    w0 = 2 * math.pi * f0 / INTERNAL
    H = (1 - a) / (1 - a * complex(math.cos(-w0), math.sin(-w0)))
    phase_delay = -math.atan2(H.imag, H.real) / w0                  # samples the filter adds at f0
    D = max(2, int(round(INTERNAL / f0 - phase_delay)))
    g = 10 ** (-3 / (t60 * f0)) / abs(H)                            # per round trip, at the note
    g = min(g, .99999)
    n = len(x)
    y = np.zeros(n)
    zi = np.zeros(1)
    lp_prev = np.zeros(D)                                           # LP(y) of the previous block
    for s in range(0, n, D):
        e = min(n, s + D)
        blk = x[s:e] + sign[s:e] * g * lp_prev[: e - s]
        y[s:e] = blk
        out, zi = signal.lfilter([1 - a], [1, -a], blk, zi=zi)
        lp_prev = np.zeros(D)
        lp_prev[: e - s] = out
    return y


def tube(f0: float, m: dict, vel: float, take: int, seconds: float, rng) -> tuple[np.ndarray, dict]:
    n = int(seconds * INTERNAL)
    ring_scale = sk.exp_map(m["RING"], .4, 2.5)
    t60 = P["T1"] * (261.6 / f0) ** .3 * ring_scale
    # round robin, dealt: take k owns a quarter of the seal range and another of the contact range
    band = (take % 4 + rng.uniform(.15, .85)) / 4
    cband = ((take * 3) % 4 + rng.uniform(.15, .85)) / 4
    touch = sk.exp_map(m["TOUCH"], 1.6, .6) * (1.45 - .8 * vel) * (1 + .24 * (cband - .5)) / .81
    w_ms = 1e3 * P["W_PERIODS"] / f0 * touch
    # BLOOM: a flick (1 ms) to a paddle held on the end; at the top the tube stays sealed
    seal_ms = sk.exp_map(m["BLOOM"], P["SEAL_LO"], P["SEAL_HI"]) * (261.6 / f0) ** .5 * (.7 + .6 * band)
    held = m["BLOOM"] >= .97
    t = np.arange(n) / INTERNAL
    lift = P["LIFT_MS"] * 1e-3
    u = np.clip((t - seal_ms * 1e-3) / lift, 0, 1)
    sign = -np.cos(np.pi * u) if not held else -np.ones(n)          # -1 sealed ... +1 open
    amp = vel * 10 ** (.5 * rng.uniform(-1, 1) / 20)
    drive = sk.hann_pulse(w_ms, INTERNAL, 1.0, seconds) / w_ms
    y = waveguide(drive, f0, t60, sign)
    rad = y - P["RAD"] * np.concatenate([[0.0], y[:-1]])
    rad /= max(1e-12, np.max(np.abs(rad)))
    pop = signal.lfilter(*signal.butter(2, P["POP_LP"] * sk.exp_map(m["TOUCH"], .6, 2.0) * (.8 + .4 * vel) / (INTERNAL / 2)), drive)
    pop = cp.hp(pop, 60.0)
    pop /= max(1e-12, np.max(np.abs(pop)))
    out = amp * (rad + P["POP"] * pop)
    return out, {"w_ms": round(w_ms, 3), "seal_ms": round(seal_ms, 2), "held": held, "t60": round(t60, 3)}


def render_tube(voice, m, midi, vel, take, seconds):
    f0 = cp.hz(midi)
    rng = np.random.default_rng(sk.seed_for("CUPOLA", voice, midi, take, "v3"))
    struck, diag = tube(f0, m, vel, take, seconds, rng)
    # bundle neighbours: faint, one-way (a tube radiates almost everything it has); they hear the
    # struck tube through the frame and ring as open tubes, low-passed
    kappa = sk.lin(m["SYMPATHY"], 0, .05)
    answer = np.zeros_like(struck)
    if kappa > 0:
        for fm in cp.fields_for(voice, m):
            if fm == midi:
                continue
            ff = cp.hz(fm)
            for k in (1, 2):
                tt = P["T1"] * (261.6 / ff) ** .3 / k ** .3
                answer += cp.ring(kappa * cp.drive_scale(tt) * struck, ff * k, 1.0 / k, tt)
    diag.update({"kappa": round(kappa, 4), "peak_struck": float(np.max(np.abs(struck))),
                 "peak_answer": float(np.max(np.abs(answer))),
                 "answer_energy_over_struck_dB": round(10 * np.log10((np.sum(answer ** 2) + 1e-30) / (np.sum(struck ** 2) + 1e-30)), 1)})
    return struck + answer, diag


def install():
    cp.render_tube = render_tube
