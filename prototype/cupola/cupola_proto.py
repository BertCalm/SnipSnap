"""CUPOLA prototype: hand-struck tuned steel shells and slapped air tubes.

Python harness, not Kotlin. Copied from the Luthier proto_template and rebuilt around one idea:
the instrument is a body tuned to a scale, so one strike makes the rest of the scale answer.

Steel voices (HANDPAN, DING, TONGUE, TANK)
  hand contact (Hann pulse, width from TOUCH and velocity)
    -> struck note field: modes (ratio, gain, t60) per voice, weighted by strike position
    -> BLOOM: the field's own fundamental, squared, drives its octave; fundamental x octave
       drives the fifth above it. Quadratic coupling of shallow shells, as a one-way readout:
       harder hits bloom more (amplitude squared), so velocity changes timbre, not only level.
    -> SYMPATHY: every other note field of the scale is driven by the struck field's sound at
       low gain. Only partials that coincide with a field's modes build up, so in-scale notes
       answer and a chromatic note mostly does not. Nothing schedules the answer.
    -> cavity: one Helmholtz-like air mode under the shell, driven by the same sound.
Air voice (TUBES)
  paddle slap -> while the paddle seals the end the tube is closed-open (odd modes, an octave
  down); when it lifts, the open-open tube rings at the note (all harmonics). BLOOM sets the
  seal time. Bundle neighbours answer through the same SYMPATHY readout.

Every constant here is a "shape" value unless the comment says "measured" (from the owner's
reference loops, see ref_compare.py; loop-level measurements with overlapping notes).

Run: python3 -I cupola_proto.py OUT_DIR
"""
from __future__ import annotations

import json
import math
import os
import sys
import time

import numpy as np
from scipy import signal

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import refs  # noqa: E402
sys.path.insert(0, str(refs.LUTHIER_SCRIPTS))
import snipkit as sk  # noqa: E402
import probes as pr   # noqa: E402

RATE, INTERNAL = sk.RATE, sk.INTERNAL

# ----------------------------------------------------------------------------- scales
# Semitone offsets from the key root (the lowest field). Generic scale names only.
SCALES = {
    "KURD":       [0, 7, 8, 10, 12, 14, 15, 17, 19],   # minor with a flat second above the fifth
    "CELTIC":     [0, 7, 10, 12, 14, 15, 17, 19, 22],
    "MAJOR":      [0, 4, 7, 11, 12, 14, 16, 19],       # the tongue references sit in D major
    "MINOR_PENT": [0, 3, 5, 7, 10, 12, 15, 17],        # the tank references sit in G minor
    "PENT":       [0, 2, 4, 7, 9, 12, 14, 16],         # the tube references use C and A
    "HIJAZ":      [0, 7, 8, 11, 12, 14, 15, 17, 19],
}
SCALE_ORDER = list(SCALES)


def scale_for(x: float) -> str:
    """SCALE is a stepped macro: 0..1 snaps to one of the layouts (SILK's SCALE does the same)."""
    return SCALE_ORDER[min(len(SCALE_ORDER) - 1, int(x * len(SCALE_ORDER)))]


def scale_macro(name: str) -> float:
    return (SCALE_ORDER.index(name) + 0.5) / len(SCALE_ORDER)


# ----------------------------------------------------------------------------- voices
# key: the default key-root MIDI note at TUNE 0.5. modes: (ratio, gain, t60 at 200 Hz).
VOICES = {
    "HANDPAN": dict(key=53, scale="KURD", seconds=4.0,
                    # measured: partials near 2x and 3x on handpan notes (1.996, 2.88..3.07)
                    modes=[(1.0, 1.0, 4.0), (2.0, .30, 3.2), (3.0, .24, 2.2), (4.38, .04, .6), (5.6, .025, .4)],
                    cavity=(0.5, .22, .7),        # measured: an ~87 Hz component under an F3 key
                    bloom_gain=1.0, touch_ms=(7.0, .6)),
    "DING":    dict(key=53, scale="KURD", seconds=4.0, struck_offset=-12,   # the low centre note
                    modes=[(1.0, 1.0, 5.0), (2.0, .34, 3.6), (3.0, .18, 2.4), (4.4, .05, .7)],
                    cavity=(0.94, .10, .9), bloom_gain=1.0, touch_ms=(9.0, 1.0)),
    "TONGUE":  dict(key=50, scale="MAJOR", seconds=6.0,
                    # measured: 1 : 2.008 : 4.015 : 6.013, fundamental ringing 10..14 s
                    modes=[(1.0, 1.0, 11.0), (2.008, .28, 8.0), (4.015, .10, 5.0), (6.013, .05, 3.0), (9.4, .015, .5)],
                    cavity=(0.5, .10, .6), bloom_gain=.6, touch_ms=(5.0, .4)),
    "TANK":    dict(key=43, scale="MINOR_PENT", seconds=2.5,
                    # measured: nearly pure fundamental, others 15..35 dB down, rings 1..2 s;
                    # a component at 0.81..0.88 of each note, about 65..75 Hz: a cavity
                    modes=[(1.0, 1.0, 2.0), (2.0, .05, 1.1), (2.72, .03, .5)],
                    cavity=(0.72, .30, 1.0), bloom_gain=.35, touch_ms=(12.0, 3.0)),
    "TUBES":   dict(key=60, scale="PENT", seconds=1.2, tube=True, touch_ms=(3.0, .5)),
}
STEEL = ["HANDPAN", "DING", "TONGUE", "TANK"]

MACROS = {"TUNE": .5, "SCALE": None, "TOUCH": .5, "BLOOM": .5, "SYMPATHY": .5, "RING": .5}


def defaults(voice: str) -> dict:
    m = dict(MACROS)
    m["SCALE"] = scale_macro(VOICES[voice]["scale"])
    return m


def key_midi(voice: str, tune: float) -> int:
    return VOICES[voice]["key"] + int(round((tune - .5) * 24))


def struck_midi(voice: str, tune: float) -> int:
    return key_midi(voice, tune) + VOICES[voice].get("struck_offset", 0)


def hz(midi: float) -> float:
    return 440.0 * 2 ** ((midi - 69) / 12)


def fields_for(voice: str, m: dict) -> list[int]:
    k = key_midi(voice, m["TUNE"])
    offs = SCALES[scale_for(m["SCALE"])]
    f = [k + o for o in offs]
    if voice in ("HANDPAN", "DING"):
        f = [k - 12] + f            # the ding sits an octave under the key's field ring
    return f


# ----------------------------------------------------------------------------- resonators
def ring(x: np.ndarray, f: float, gain: float, t60: float, rate: int = INTERNAL) -> np.ndarray:
    """One two-pole mode (Modes.ring's recurrence) with gain * sin(theta): peak-flat across pitch."""
    if f >= rate / 2 * .9 or t60 <= 0:
        return np.zeros_like(x)
    r = math.exp(-6.9078 / (t60 * rate))
    th = 2 * math.pi * f / rate
    return signal.lfilter([gain * math.sin(th)], [1.0, -2 * r * math.cos(th), r * r], x)


def drive_scale(t60: float, rate: int = INTERNAL) -> float:
    """2(1 - r): a steady drive of amplitude A at resonance settles at about A. This is what
    makes BLOOM and SYMPATHY depths mean the same thing at every pitch and every RING."""
    return 2 * (1 - math.exp(-6.9078 / (t60 * rate)))


def t60_at(t60_200: float, f: float, ring_scale: float) -> float:
    return t60_200 * (200.0 / f) ** .5 * ring_scale     # higher notes die sooner (shape)


def hp(x: np.ndarray, f: float = 20.0) -> np.ndarray:
    b, a = signal.butter(1, f / (INTERNAL / 2), "high")
    return signal.lfilter(b, a, x)


# ----------------------------------------------------------------------------- steel
# The shell is ONE linear network of mass-normalised modes with reciprocal springs between
# modes of different note fields that sit within WINDOW_CENTS of each other. That is the
# system Modes.Bank steps in Kotlin (K = diag(w^2) - A, k_ij = kappa * min(w_i, w_j)^2). Here it
# is solved exactly by diagonalising it once per render and running each eigenmode as a complex
# one-pole filter, which is the same linear system, only faster in numpy.
WINDOW_CENTS = 60.0
RR_REBOUND = True
BLOOM_MAX = 3.0         # measured: the largest depth tried where BLOOM alone never grows; BLOOM x SYMPATHY at the top still does (DING 1.27)
TUBE_STRETCH = 0.0015  # measured on a C4 tube loop: 2.023, 3.03, 4.07, 5.1 (about 0.0045); kept at a third so the detected root stays inside about 8 cents
KAPPA_MAX = 0.012        # shape: splits two coincident 300 Hz modes by about 3.6 Hz at SYMPATHY 1


class Shell:
    def __init__(self, freqs, t60s, coupling_pairs, kappa, rate=INTERNAL):
        n = len(freqs)
        w = 2 * np.pi * np.asarray(freqs)
        K = np.diag(w ** 2)
        for i, j in coupling_pairs:
            k = kappa * min(w[i], w[j]) ** 2
            K[i, j] -= k
            K[j, i] -= k
        sums = np.zeros(n)
        for i, j in coupling_pairs:
            sums[i] += kappa
            sums[j] += kappa
        assert np.all(sums < 1.0), "kappa sum over MAX_NODE_KAPPA"   # Modes.Bank's bound
        assert np.all(np.linalg.eigvalsh(K) > 0), "stiffness lost positive definiteness"
        damp = 2 * 6.9078 / np.asarray(t60s)                  # x'' + (2/tau) x' + K x = f
        A = np.zeros((2 * n, 2 * n))
        A[:n, n:] = np.eye(n)
        A[n:, :n] = -K
        A[n:, n:] = -np.diag(damp)
        lam, V = np.linalg.eig(A)
        keep = lam.imag > 0
        self.lam, self.V, self.Vi = lam[keep], V[:, keep], np.linalg.inv(V)[keep, :]
        self.n, self.T = n, 1.0 / rate
        self.a = np.exp(self.lam * self.T)
        self.b = (self.a - 1) / self.lam
        self.coupled_hz = self.lam.imag / (2 * np.pi)

    def run(self, inputs, outputs):
        """inputs: list of (force vector over modes, signal); outputs: dict name -> weight vector
        over mode velocities. Returns dict name -> real signal."""
        P = np.stack([self.Vi[:, self.n:] @ fv for fv, _ in inputs], axis=1)     # (poles, nin)
        U = np.stack([s for _, s in inputs], axis=0)                              # (nin, frames)
        res = {k: np.zeros(U.shape[1]) for k in outputs}
        Q = {k: (wv @ self.V[self.n:, :]) for k, wv in outputs.items()}           # (poles,)
        for p in range(len(self.lam)):
            drive = P[p] @ U
            if not np.any(drive):
                continue
            z = signal.lfilter([self.b[p]], [1.0, -self.a[p]], drive)
            for k in outputs:
                if Q[k][p] != 0:
                    res[k] += 2 * np.real(Q[k][p] * z)
        return res


def render_steel(voice: str, m: dict, midi: int, vel: float, take: int, seconds: float) -> tuple[np.ndarray, dict]:
    v = VOICES[voice]
    n = int(seconds * INTERNAL)
    f0 = hz(midi)
    rng = np.random.default_rng(sk.seed_for("CUPOLA", voice, midi, take))
    ring_scale = sk.exp_map(m["RING"], .2, 2.2)

    # hand contact: TOUCH runs palm -> fingertip. The pulse carries a fixed impulse (vel), so a
    # shorter contact keeps the low end and adds highs: harder and brighter by the contact alone.
    soft, hard = v["touch_ms"]
    width = sk.exp_map(m["TOUCH"], soft, hard) * (1.3 - .5 * vel) * (1 + .1 * rng.uniform(-1, 1))
    jitter = 10 ** (.5 * rng.uniform(-1, 1) / 20)           # +-0.5 dB take to take
    impulse = vel * jitter
    exc = sk.hann_pulse(width, INTERNAL, 2 * impulse / (width * 1e-3), seconds)
    # the finger rebounds: a second, lighter contact a few ms later. Its delay and weight are the
    # take (round robin), and the comb it makes with the first contact moves the early spectrum.
    if RR_REBOUND:
        rb_ms = sk.exp_map(rng.uniform(), 1.2, 7.0)
        rb = sk.exp_map(rng.uniform(), .06, .35) * impulse
        exc += sk.hann_pulse(width * .8, INTERNAL, 2 * rb / (width * .8e-3), seconds, at_seconds=rb_ms * 1e-3)
    skin =sk.noise_burst(width * 1.5, INTERNAL, sk.seed_for(voice, midi, take, "skin"), 1.0, seconds,
                          lowpass_hz=sk.exp_map(m["TOUCH"], 2500, 9000))
    # strike position across the field (round robin moves it): the edge feeds octave and fifth
    pos = float(np.clip(.4 + .3 * rng.uniform(-1, 1) + .25 * (m["TOUCH"] - .5), 0, 1))

    flds = fields_for(voice, m)
    if midi not in flds:
        flds = flds + [midi]                     # a chromatic note is its own, unrelated field
    freqs, t60s, owner, gains = [], [], [], []
    for fm in flds:
        ff = hz(fm)
        for i, (ratio, g, t) in enumerate(v["modes"]):
            if ff * ratio > 9000:
                continue
            freqs.append(ff * ratio)
            t60s.append(t60_at(t, ff * ratio, ring_scale))
            owner.append((fm, i))
            gains.append(g)
    kappa = 0.0 if m["SYMPATHY"] <= 0 else sk.exp_map(m["SYMPATHY"], KAPPA_MAX / 25, KAPPA_MAX)
    pairs = [(i, j) for i in range(len(freqs)) for j in range(i + 1, len(freqs))
             if owner[i][0] != owner[j][0] and abs(1200 * math.log2(freqs[i] / freqs[j])) < WINDOW_CENTS]
    shell = Shell(freqs, t60s, pairs if kappa > 0 else [], kappa)

    nm = len(freqs)
    struck_idx = {owner[i][1]: i for i in range(nm) if owner[i][0] == midi}
    hit = np.zeros(nm)
    for i, idx in struck_idx.items():
        hit[idx] = 1.0 if i == 0 else (.45 + 1.4 * pos) * (1 + .15 * rng.uniform(-1, 1))
    radiate = np.asarray(gains, dtype=float)                 # every field radiates by its mode gains
    e1, e2, e3 = (np.eye(nm)[struck_idx[i]] for i in (0, 1, 2))
    struck_only = np.where([owner[i][0] == midi for i in range(nm)], radiate, 0.0)

    # pass 1: the hit alone; read the struck fundamental and octave
    p1 = shell.run([(hit, exc)], {"v1": e1, "v2": e2})
    beta = sk.lin(m["BLOOM"], 0, BLOOM_MAX) * v["bloom_gain"]
    tau2, tau3 = t60s[struck_idx[1]] / 6.9078, t60s[struck_idx[2]] / 6.9078
    # quadratic transfer: v1^2 has a component at twice the fundamental, which drives the octave
    oct_f = beta * (2 / tau2) * hp(p1["v1"] ** 2)
    p2 = shell.run([(e2, oct_f)], {"v2b": e2})
    v2 = p1["v2"] + p2["v2b"]
    pair = v2 * v2 if voice == "TONGUE" else p1["v1"] * v2      # the sum frequency must land on mode 3
    fif_f = beta * (2 / tau3) * hp(pair)
    p3 = shell.run([(hit, exc), (e2, oct_f), (e3, fif_f)], {"out": radiate, "struck": struck_only})
    struck = p3["struck"]
    answer = p3["out"] - struck

    cr, cg, ct = v["cavity"]
    base = hz(midi) if voice == "DING" else hz(key_midi(voice, m["TUNE"]))
    cts = ct * ring_scale ** .5
    cav = ring(cg * drive_scale(cts) * 6 * p3["out"], base * cr, 1.0, cts)
    out = p3["out"] + cav + .02 * impulse * skin
    diag = {"width_ms": round(width, 3), "pos": round(pos, 3), "beta": round(beta, 3), "kappa": round(kappa, 5),
            "modes": nm, "springs": len(pairs) if kappa > 0 else 0,
            "peak_struck": float(np.max(np.abs(struck))), "peak_answer": float(np.max(np.abs(answer))),
            "answer_energy_over_struck_dB": round(10 * np.log10((np.sum(answer ** 2) + 1e-30) / (np.sum(struck ** 2) + 1e-30)), 1),
            "peak_cavity": float(np.max(np.abs(cav))),
            "struck_root_coupled_hz": round(float(shell.coupled_hz[np.argmin(np.abs(shell.coupled_hz - f0))]), 3)}
    return out, diag


# ----------------------------------------------------------------------------- tubes
def render_tube(voice: str, m: dict, midi: int, vel: float, take: int, seconds: float) -> tuple[np.ndarray, dict]:
    v = VOICES[voice]
    n = int(seconds * INTERNAL)
    f0 = hz(midi)
    rng = np.random.default_rng(sk.seed_for("CUPOLA", voice, midi, take))
    ring_scale = sk.exp_map(m["RING"], .4, 2.5)
    t1 = .28 * (261.6 / f0) ** .3 * ring_scale         # measured: hits fall 20 dB in about 60 ms

    soft, hard = v["touch_ms"]
    width = sk.exp_map(m["TOUCH"], soft, hard) * (1.3 - .5 * vel) * (1 + .08 * rng.uniform(-1, 1))
    amp = vel * 10 ** (.05 * rng.uniform(-1, 1) / 20)
    # seal time: how long the paddle covers the end (BLOOM), a softer paddle stays longer
    seal_ms = sk.exp_map(m["BLOOM"], .6, 9.0) * (1 + .5 * (1 - m["TOUCH"])) * (1 + (.25 if RR_REBOUND else .1) * rng.uniform(-1, 1))
    seal = int(seal_ms * 1e-3 * INTERNAL)
    lift = max(8, int(.0012 * INTERNAL))

    push = sk.hann_pulse(width, INTERNAL, amp, seconds)
    if RR_REBOUND:
        push += sk.hann_pulse(width, INTERNAL, amp * sk.exp_map(rng.uniform(), .08, .4), seconds,
                              at_seconds=sk.exp_map(rng.uniform(), 1.5, 9.0) * 1e-3)
    # closed-open phase: odd modes of half the note (a quarter-wave tube), only while sealed
    closed = np.zeros(n)
    for k in (1, 3, 5, 7):
        closed += ring(push, f0 * k / 2, 1.0 / k, t1 / k ** .6)
    gate = np.zeros(n)
    gate[:seal] = 1.0
    gate[seal:seal + lift] = .5 * (1 + np.cos(np.pi * np.arange(min(lift, n - seal)) / lift))
    closed *= gate
    # open-open phase: the pressure left in the tube when the paddle lifts rings all harmonics
    resid = float(np.max(np.abs(closed[:seal + 1]))) if seal > 0 else amp
    open_exc = sk.hann_pulse(max(.25, width * .5), INTERNAL, max(resid, .3 * amp), seconds,
                             at_seconds=seal / INTERNAL) + .35 * push
    # open-open phase, in the same coupled network as the steel shells: the struck tube and its
    # bundle neighbours. Measured on the references: upper harmonics only ~2 dB down at 2x and 3x
    # (open-end radiation grows with frequency) and stretched sharp, 2.023, 3.03, 4.07, 5.1.
    flds = fields_for(voice, m)
    if midi not in flds:
        flds = flds + [midi]
    freqs, t60s, owner, gains = [], [], [], []
    for fm in flds:
        ff = hz(fm)
        tt = .28 * (261.6 / ff) ** .3 * ring_scale
        for k in range(1, 11):
            fk = ff * k * (1 + TUBE_STRETCH * (k - 1))
            if fk > 9000:
                break
            freqs.append(fk); t60s.append(tt / k ** .25); owner.append((fm, k)); gains.append(k ** .1)
    kappa = 0.0 if m["SYMPATHY"] <= 0 else sk.exp_map(m["SYMPATHY"], KAPPA_MAX / 25, KAPPA_MAX)
    pairs = [(i, j) for i in range(len(freqs)) for j in range(i + 1, len(freqs))
             if owner[i][0] != owner[j][0] and abs(1200 * math.log2(freqs[i] / freqs[j])) < WINDOW_CENTS]
    shell = Shell(freqs, t60s, pairs if kappa > 0 else [], kappa)
    hit = np.array([1.0 if owner[i][0] == midi else 0.0 for i in range(len(freqs))])
    radiate = np.asarray(gains)
    struck_only = np.where(hit > 0, radiate, 0.0)
    p = shell.run([(hit, open_exc)], {"out": radiate, "struck": struck_only})
    ref = max(1e-12, np.max(np.abs(p["struck"])))
    opened, answer_n = p["struck"] * amp / ref, (p["out"] - p["struck"]) * amp / ref
    closed *= amp / max(1e-12, np.max(np.abs(closed))) * sk.lin(m["BLOOM"], .15, .9)
    slap = sk.noise_burst(1.6, INTERNAL, sk.seed_for(voice, midi, take, "slap"), .12 * amp * (.4 + m["TOUCH"]),
                          seconds)
    slap = signal.lfilter(*signal.butter(2, sk.exp_map(rng.uniform(), 1000, 2500) / (INTERNAL / 2), "high"), slap)
    struck = opened + closed + slap
    answer = answer_n
    diag = {"width_ms": round(width, 3), "seal_ms": round(seal_ms, 3), "kappa": round(kappa, 5),
            "springs": len(pairs) if kappa > 0 else 0,
            "peak_struck": float(np.max(np.abs(struck))), "peak_answer": float(np.max(np.abs(answer))),
            "answer_energy_over_struck_dB": round(10 * np.log10((np.sum(answer ** 2) + 1e-30) / (np.sum(struck ** 2) + 1e-30)), 1)}
    return struck + answer, diag


def render(voice: str, macros: dict | None = None, midi: int | None = None, vel: float = .8, take: int = 0,
           seconds: float | None = None) -> tuple[np.ndarray, dict]:
    m = {**defaults(voice), **(macros or {})}
    if midi is None:
        midi = struck_midi(voice, m["TUNE"])
    sec = seconds or VOICES[voice]["seconds"]
    fn = render_tube if VOICES[voice].get("tube") else render_steel
    return fn(voice, m, midi, vel, take, sec)


def finish(raw: np.ndarray) -> np.ndarray:
    return sk.finish(raw)


# ----------------------------------------------------------------------------- measurements
def centroid(x: np.ndarray, a: float, b: float) -> float:
    seg = x[int(a * RATE):int(b * RATE)]
    S = np.abs(np.fft.rfft(seg * np.hanning(len(seg))))
    f = np.fft.rfftfreq(len(seg), 1 / RATE)
    return float((S * f).sum() / (S.sum() + 1e-12))


def band_env(x: np.ndarray, f: float, block: float = .01) -> np.ndarray:
    b, a = signal.butter(2, [f * .97, f * 1.03], "band", fs=RATE)
    y = signal.filtfilt(b, a, x)
    nb = int(block * RATE)
    k = len(y) // nb
    return np.sqrt(np.mean(y[:k * nb].reshape(k, nb) ** 2, axis=1))


def fall20(x: np.ndarray) -> float:
    e = sk.rms
    hop = 128
    env = np.sqrt(np.convolve(x * x, np.ones(hop) / hop, "same"))[::hop]
    db = 20 * np.log10(env + 1e-9)
    p = int(np.argmax(db))
    below = np.where(db[p:] < db[p] - 20)[0]
    return float(below[0] * hop / RATE) if len(below) else float("nan")


def partials(x: np.ndarray, f0: float) -> list:
    seg = x[int(.03 * RATE):int(.33 * RATE)]
    S = np.abs(np.fft.rfft(seg * np.hanning(len(seg)), 1 << 18))
    f = np.fft.rfftfreq(1 << 18, 1 / RATE)
    Sd = 20 * np.log10(S / S.max() + 1e-12)
    band = (f > 40) & (f < 8000)
    pk, _ = signal.find_peaks(np.where(band, Sd, -300), height=-40, prominence=10, distance=30)
    pk = pk[np.argsort(-Sd[pk])][:6]
    return sorted((round(float(f[i] / f0), 3), round(float(Sd[i]), 1)) for i in pk)


def main(out: str) -> int:
    os.makedirs(out, exist_ok=True)
    for d in ("voices", "macros", "velocity", "round_robin", "sympathy", "phrases"):
        os.makedirs(os.path.join(out, d), exist_ok=True)
    rep = {"voices": {}, "macro_activity_dB": {}, "velocity": {}, "round_robin": {}, "sympathy": {}, "timing_s": {}}

    # 1. each voice at three notes of its scale, with the house probes
    for voice in VOICES:
        rep["voices"][voice] = []
        m = defaults(voice)
        flds = fields_for(voice, m)
        base = struck_midi(voice, .5)
        for midi in (base, base + 7, base + 12):
            t0 = time.perf_counter()
            raw, diag = render(voice, midi=midi)
            dt = time.perf_counter() - t0
            y = finish(raw)
            name = f"{voice}_midi{midi}"
            sk.write_wav(os.path.join(out, "voices", name + ".wav"), y)
            r = pr.report(y, RATE, name, ref_hz=hz(midi))
            r.update({"partials": partials(y, hz(midi)), "fall20dB_s": round(fall20(y), 3),
                      "centroid_attack_body_Hz": [round(centroid(y, 0, .02)), round(centroid(y, .1, .25))],
                      "alias_proxy_raw": round(pr.alias_proxy(raw, INTERNAL), 8),
                      "render_s_python": round(dt, 3), "diag": diag, "in_scale": midi in flds})
            rep["voices"][voice].append(r)
        pr.spectrogram_png(finish(render(voice)[0]), RATE, os.path.join(out, "voices", f"{voice}_default.png"),
                           f"CUPOLA {voice} default (Python harness)")

    # 2. every macro changes the sound (extremes, levelled, log-spectral distance)
    for voice in VOICES:
        rep["macro_activity_dB"][voice] = {}
        def walk(sc):
            mm = {**defaults(voice), "SCALE": sc}
            f = fields_for(voice, mm)
            s = 1 if voice in ("HANDPAN", "DING") else 0
            ys = [finish(render(voice, {"SCALE": sc}, midi=f[s + i], seconds=.6)[0]) for i in range(1, 5)]
            return np.concatenate(ys)
        lo_w, hi_w = walk(0.0), walk(0.99)
        rep["macro_activity_dB"][voice]["SCALE_walk"] = round(pr.macro_activity(lo_w, hi_w), 2)
        sk.write_wav(os.path.join(out, "macros", f"{voice}_SCALE_walk_0.wav"), lo_w)
        sk.write_wav(os.path.join(out, "macros", f"{voice}_SCALE_walk_1.wav"), hi_w)
        for macro in ("TOUCH", "BLOOM", "SYMPATHY", "RING", "SCALE"):
            lo = finish(render(voice, {macro: 0.0})[0])
            hi = finish(render(voice, {macro: 0.99})[0])
            rep["macro_activity_dB"][voice][macro] = round(pr.macro_activity(lo, hi), 2)
            sk.write_wav(os.path.join(out, "macros", f"{voice}_{macro}_0.wav"), lo)
            sk.write_wav(os.path.join(out, "macros", f"{voice}_{macro}_1.wav"), hi)
        # an unknown key does nothing
        a = finish(render(voice)[0])
        b = finish(render(voice, {"NOPE": 1.0})[0])
        rep["macro_activity_dB"][voice]["unknown_key_identical"] = bool(np.array_equal(a, b))

    # 3. velocity: timbre, not level. Centroid and octave share at three velocities (levelled output)
    for voice in VOICES:
        rows = []
        midi = struck_midi(voice, .5)
        for vel in (.25, .55, .95):
            raw, _ = render(voice, vel=vel)
            y = finish(raw)
            sk.write_wav(os.path.join(out, "velocity", f"{voice}_vel{vel:.2f}.wav"), y)
            e1 = band_env(y, hz(midi))
            e2 = band_env(y, hz(midi) * (2.0 if voice != "TONGUE" else 2.008))
            rows.append({"vel": vel, "centroid_0_50ms_Hz": round(centroid(y, 0, .05)),
                         "octave_minus_root_dB_at_300ms": round(20 * np.log10((e2[30] + 1e-12) / (e1[30] + 1e-12)), 1),
                         "raw_peak": round(float(np.max(np.abs(raw))), 4)})
        rep["velocity"][voice] = rows

    # 4. round robin: four takes, distance between takes vs the same take twice
    for voice in VOICES:
        takes = [finish(render(voice, take=k)[0]) for k in range(4)]
        for k, y in enumerate(takes):
            sk.write_wav(os.path.join(out, "round_robin", f"{voice}_take{k}.wav"), y)
        d = [pr.macro_activity(takes[i], takes[j]) for i in range(4) for j in range(i + 1, 4)]
        again = finish(render(voice, take=0)[0])
        rep["round_robin"][voice] = {"pairwise_dB_min_max": [round(min(d), 2), round(max(d), 2)],
                                     "same_take_identical": bool(np.array_equal(takes[0], again))}

    # 5. sympathy: does the scale answer, and does a chromatic note get less of an answer?
    for voice in VOICES:
        m = defaults(voice)
        base = struck_midi(voice, .5)
        flds = fields_for(voice, m)
        out_scale = next(x for x in range(base + 1, base + 13) if x not in flds)
        in_scale = next(x for x in flds if x > base)
        res = {}
        for label, midi in (("in_scale", in_scale), ("chromatic", out_scale)):
            raw, diag = render(voice, midi=midi)
            res[label] = {"midi": midi, "answer_peak_over_struck_dB": round(20 * np.log10(diag["peak_answer"] / max(1e-12, diag.get("peak_struck", np.max(np.abs(raw))))), 1),
                          "answer_energy_over_struck_dB": diag.get("answer_energy_over_struck_dB"),
                          "springs": diag.get("springs")}
            sk.write_wav(os.path.join(out, "sympathy", f"{voice}_{label}_midi{midi}.wav"), finish(raw))
        for s in (0.0, 0.99):
            sk.write_wav(os.path.join(out, "sympathy", f"{voice}_SYMPATHY_{s:.0f}.wav"), finish(render(voice, {"SYMPATHY": s})[0]))
        rep["sympathy"][voice] = res

    # 6. phrases: the one-shots summed as a sampler would play them (eighths at 100 BPM), so the
    #    owner can hold them next to the reference loops. Velocity and take vary per hit.
    eighth = 60 / 100 / 2
    for voice in VOICES:
        m = defaults(voice)
        flds = fields_for(voice, m)
        start = 1 if voice in ("HANDPAN",) else 0
        pattern = [0, 2, 3, 4, 2, 5, 4, 3, 0, 6, 5, 4, 3, 2, 3, 1] if voice != "DING" else [0, 0, 0, 0]
        idx = [flds[min(len(flds) - 1, start + p)] if voice != "DING" else struck_midi(voice, .5) for p in pattern]
        step = eighth if voice != "DING" else eighth * 4
        total = int((len(idx) * step + VOICES[voice]["seconds"]) * RATE)
        mix = np.zeros(total)
        for i, midi in enumerate(idx):
            vel = .55 + .4 * ((i * 7) % 5) / 4
            y = sk.decimate4(sk.band_limit(render(voice, midi=midi, vel=vel, take=i % 4)[0], INTERNAL))
            o = int(i * step * RATE)
            mix[o:o + len(y)] += y[: total - o]
        mix = sk.fade_tail(sk.level_to(sk.dc_block(mix), .12))
        sk.write_wav(os.path.join(out, "phrases", f"{voice}_phrase_100bpm.wav"), mix)

    with open(os.path.join(out, "report.json"), "w") as f:
        json.dump(rep, f, indent=1)
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else str(refs.OUT / "proto")))
