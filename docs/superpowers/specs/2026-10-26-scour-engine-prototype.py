"""SCOUR phase 0: the adapted design in the Python harness (not Kotlin).

What it settles, in order:
  M1  stress never leaves [0, 1) without a clamp (the coverage law)
  M2  mean shot power during the blast is flat across SHOT (the house energy doctrine)
  M3  the spec's law vs the coverage law: does SHOT move the stress at all?
  R1  per-sample retune vs 1 kHz control-rate retune of a smoothed stress: how different?
  A*  pitch, root confidence, centroid motion, macro activity, aliasing, DC, determinism
  H1  the spec's HOLD (cut a stochastic 3 s render at a zero crossing) against Keys.seamError
  H2  the adapted HOLD (periodic by construction, stress orbit converged at control rate)
  C   render lengths over the macro cube (what drumClassFor must say), WAVs for the classifier

Every constant here is a shape value. The numbers in the spec's "Phase 0" section came from
this file at the commit that added it. Needs numpy and scipy. From the repository root:

    python3 docs/superpowers/specs/2026-10-26-scour-engine-prototype.py /tmp/scour_out

It writes WAVs and report.json into the folder you name. Nothing here has been heard.
"""
from __future__ import annotations

import json
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..",
                                ".claude", "skills", "luthier", "scripts"))
import snipkit as sk  # noqa: E402
import probes as pr   # noqa: E402

RATE = sk.INTERNAL          # 176.4 kHz, the house internal rate
OUT = sk.RATE
CTRL = 176                  # control tick in internal samples (~1 kHz), THAW's CTRL
DT = 1.0 / RATE
MODEL_VERSION = 1
ROOT_MIDI = 48              # TUNE .5 -> C4, the THAW habit (48 + round(TUNE * 24))
TUNE_SEMIS = 24
SMOOTH_S = 0.015            # one-pole smoothing of stress before it retunes the plate
REVS_PER_S = 5.0            # roller revolutions per second at v = 1
TABLE = 8192                # roughness points per revolution
NYQ_GUARD_HZ = 18_000.0     # a mode that HARDEN could push past this is dropped for the note

# Recalled from Fletcher & Rossing's free-edge circular plate table, NOT read from the book
# in this session. Modes.kt's rule applies: source it before Kotlin.
PLATE_RATIOS = [1.0, 1.73, 2.33, 3.91, 4.11, 6.30, 6.71, 7.34]

VOICES = {
    # dense metal: slow recovery (the ratchet holds), a long ring that tightens as it hardens
    "PLATE": dict(ratios=PLATE_RATIOS, t60=2.2, decay=0.80, gain=0.72, kappa=0.012, rho=0.15,
                  shift=0.35, damp=1.2, lead=0.20, window=0.90, decel=0.45, roll=0.06),
    # glass: sparse, little coverage per shot, fast recovery (it springs back in the tail)
    "PANE": dict(ratios=[1.0, 1.73, 2.33, 3.91, 6.30, 7.34], t60=1.4, decay=0.74, gain=0.65,
                 kappa=0.03, rho=1.5, shift=0.25, damp=0.6, lead=0.12, window=0.60, decel=0.60,
                 roll=0.05),
}
MACROS = dict(TUNE=.5, RPM=.3, SHOT=.5, HARDEN=.6, TRAVERSE=.2)
A_SHOT = 60.0               # force scale of one shot at the reference rate


def midi_for(tune): return ROOT_MIDI + int(round(tune * TUNE_SEMIS))
def hz_for(midi): return 440.0 * 2 ** ((midi - 69) / 12)
def lam_for(shot): return sk.exp_map(shot, 3.0, 300.0)   # shots per second
LAM_REF = lam_for(0.5)


def weights(x, k_count):
    """Strike participation at radial position x. The root keeps full weight so TRAVERSE
    can never put the note on a node (the pitch would go with it)."""
    return np.array([1.0 if k == 0 else 0.3 + 0.7 * abs(math.cos(math.pi * (k + 0.5) * x))
                     for k in range(k_count)])


def plate_modes(voice, hz, harden):
    v = VOICES[voice]
    keep = []
    n = len(v["ratios"])
    for k, r in enumerate(v["ratios"]):
        grade = k / (n - 1)
        fmax = hz * r * (1 + v["shift"] * harden * grade)
        if fmax < NYQ_GUARD_HZ:
            keep.append((k, r, grade))
    return keep


# ------------------------------------------------------------------ mechanics (control rate)

def unit_gaps(voice, count=4000):
    """Unit-rate exponential gaps, seeded by voice only: SHOT scales them by 1/lambda, so turning
    SHOT stretches one fixed schedule instead of reshuffling it."""
    rng = np.random.default_rng(sk.seed_for("SCOUR", MODEL_VERSION, voice, "GAPS"))
    return rng.exponential(1.0, count), rng.uniform(-1, 1, count), rng.uniform(-1, 1, count)


def blast_schedule(voice, m, t0, t1, velocity=1.0):
    gaps, jit_a, jit_x = unit_gaps(voice)
    lam = lam_for(m["SHOT"])
    t = t0 + np.cumsum(gaps) / lam
    keep = t < t1
    t = t[keep]
    amp = A_SHOT * math.sqrt(LAM_REF / lam) * (1 + 0.2 * jit_a[: len(t)]) * velocity
    x = np.clip(sk.lin(m["TRAVERSE"], .05, .95) + 0.05 * jit_x[: len(t)], 0, 1)
    energy = (1 + 0.2 * jit_a[: len(t)]) * velocity ** 0.8   # coverage per shot, not the audio level
    return t, amp, x, energy, lam


def coverage_stress(voice, shot_t, energy, n_ctrl, smooth=True):
    """The adapted law. Per shot: S += kappa * e * (1 - S), the coverage recursion (a dent lands
    on the uncovered fraction). Between shots: S *= exp(-rho * dt). Bounded in [0, 1) by
    construction: no clamp."""
    v = VOICES[voice]
    ticks = (shot_t * RATE).astype(int) // CTRL
    s = 0.0
    out = np.zeros(n_ctrl)
    relax = math.exp(-v["rho"] * CTRL / RATE)
    j = 0
    for k in range(n_ctrl):
        s *= relax
        while j < len(ticks) and ticks[j] == k:
            s += v["kappa"] * energy[j] * (1 - s)
            j += 1
        out[k] = s
    if smooth:
        a = math.exp(-CTRL / RATE / SMOOTH_S)
        y = 0.0
        sm = np.zeros_like(out)
        for k in range(n_ctrl):
            y = a * y + (1 - a) * out[k]
            sm[k] = y
        return out, sm
    return out, out


def spec_stress(shot_t, amp, n_ctrl, acc, rel):
    """The spec's law as written: S += I * accumulationRate - S * relaxationRate, I = impact
    energy (amp^2, which the energy budget scales by 1/lambda), clamped to 1."""
    ticks = (shot_t * RATE).astype(int) // CTRL
    inj = np.zeros(n_ctrl)
    np.add.at(inj, ticks[ticks < n_ctrl], amp[ticks < n_ctrl] ** 2)
    s = 0.0
    out = np.zeros(n_ctrl)
    for k in range(n_ctrl):
        s = min(1.0, s + inj[k] * acc - s * rel)
        out[k] = s
    return out


def expected_stress_at_close(voice, m, velocity=1.0):
    """Closed form for render length (drumClassFor must not render to know the class):
    dS/dt = lambda*kappa*e*(1-S) - rho*S over the window."""
    v = VOICES[voice]
    g = lam_for(m["SHOT"]) * v["kappa"] * velocity ** 0.8
    total = g + v["rho"]
    return g / total * (1 - math.exp(-total * v["window"]))


def render_seconds(voice, m, velocity=1.0):
    v = VOICES[voice]
    close = v["lead"] + v["window"]
    v0 = sk.lin(m["RPM"], 0.15, 1.0)
    stop = v0 / v["decel"]
    s_end = expected_stress_at_close(voice, m, velocity)
    tail = v["t60"] / (1 + v["damp"] * m["HARDEN"] * s_end)
    return max(close, stop) + tail


# ------------------------------------------------------------------ excitation and plate (4x)

def roughness_table(voice):
    rng = np.random.default_rng(sk.seed_for("SCOUR", MODEL_VERSION, voice, "ROUGH"))
    spec = np.fft.rfft(rng.standard_normal(TABLE))
    k = np.arange(len(spec))
    spec *= 1.0 / np.sqrt(np.maximum(k, 1))          # pink-ish roughness profile
    spec[0] = 0
    t = np.fft.irfft(spec, TABLE)
    return t / np.max(np.abs(t))


def roller_force(voice, m, n, hold_v=None, revs_per_s=None):
    v = VOICES[voice]
    t = np.arange(n) * DT
    if hold_v is None:
        speed = np.maximum(0.0, sk.lin(m["RPM"], 0.15, 1.0) - v["decel"] * t)   # Coulomb spin-down
        rate = REVS_PER_S * speed
    else:
        speed = np.full(n, hold_v)
        rate = np.full(n, revs_per_s)
    phase = np.cumsum(rate) * DT                      # revolutions
    idx = (phase % 1.0) * TABLE
    i0 = idx.astype(int) % TABLE
    fr = idx - np.floor(idx)
    tab = roughness_table(voice)
    rough = tab[i0] * (1 - fr) + tab[(i0 + 1) % TABLE] * fr
    return v["roll"] * A_SHOT * speed ** 1.5 * rough


def shot_force(n, shot_t, amp, x, modes_k, k_count):
    """Per-mode force arrays: each shot a 4-sample half-sine (band-limited, 22.7 us), weighted
    by the participation at its landing position."""
    pulse = np.sin(np.pi * (np.arange(4) + 0.5) / 4)
    forces = np.zeros((k_count, n))
    starts = (shot_t * RATE).astype(int)
    for s0, a, xx in zip(starts, amp, x):
        if s0 + 4 > n:
            continue
        w = weights(xx, k_count)
        for row, k in enumerate(modes_k):
            forces[row, s0: s0 + 4] += w[k] * a * pulse
    return forces


def tv_onepole(coef, x, block=2048):
    """z[n] = coef[n] * z[n-1] + x[n] for complex coef, vectorised in blocks."""
    z0 = 0j
    out = np.empty(len(x), dtype=complex)
    for b0 in range(0, len(x), block):
        a = coef[b0: b0 + block]
        q = np.cumprod(a)
        acc = np.cumsum(x[b0: b0 + block] / q)
        seg = q * (z0 + acc)
        out[b0: b0 + block] = seg
        z0 = seg[-1]
    return out


def plate(voice, hz, harden, stress_per_sample, forces, modes):
    """A Modes.Bank stand-in: each mode is z = omega*q - i*v rotated by r*e^{i omega T}; a force
    lands as a velocity kick (v += b*F*T); the pickup reads velocity. Retuning keeps |z|."""
    v = VOICES[voice]
    out = np.zeros(forces.shape[1])
    n_modes = len(v["ratios"])
    for row, (k, ratio, grade) in enumerate(modes):
        f = hz * ratio * (1 + v["shift"] * harden * stress_per_sample * grade)
        t60 = v["t60"] * v["decay"] ** k / (1 + v["damp"] * harden * stress_per_sample)
        coef = np.exp(-6.9078 * DT / t60) * np.exp(1j * 2 * np.pi * f * DT)
        z = tv_onepole(coef, -1j * forces[row] * DT)
        out += v["gain"] ** k * (-z.imag)
    return out


def hold_steps(ctrl_values, n):
    return np.repeat(ctrl_values, CTRL)[:n]


def render(voice, macros=None, velocity=1.0, per_sample=False, interp_smooth=False, return_state=False):
    m = {**MACROS, **(macros or {})}
    v = VOICES[voice]
    hz = hz_for(midi_for(m["TUNE"]))
    seconds = render_seconds(voice, m, velocity)
    n = int(round(seconds * OUT)) * 4
    n_ctrl = -(-n // CTRL)
    shot_t, amp, x, energy, lam = blast_schedule(voice, m, v["lead"], v["lead"] + v["window"], velocity)
    modes = plate_modes(voice, hz, m["HARDEN"])
    k_count = len(v["ratios"])
    forces = shot_force(n, shot_t, amp, x, [k for k, _, _ in modes], k_count)
    roll = roller_force(voice, m, n)
    w_roll = weights(0.97, k_count)                   # the roller runs on the rim
    for row, (k, _, _) in enumerate(modes):
        forces[row] += w_roll[k] * roll
    raw_s, smooth_s = coverage_stress(voice, shot_t, energy, n_ctrl)
    if per_sample:
        # the spec's "audio-rate": unsmoothed stress stepping at the shot's own sample
        s = np.zeros(n)
        idx = (shot_t * RATE).astype(int)
        cur, last = 0.0, 0
        for i, e in zip(idx, energy):
            if i >= n:
                break
            seg = np.arange(last, i)
            s[last:i] = cur * np.exp(-v["rho"] * (seg - last) * DT)
            cur = s[i - 1] * math.exp(-v["rho"] * DT) if i > 0 else 0.0
            cur += v["kappa"] * e * (1 - cur)
            last = i
        seg = np.arange(last, n)
        s[last:] = cur * np.exp(-v["rho"] * (seg - last) * DT)
        stress = s
    elif interp_smooth:
        # the same smoothed stress, retuned every sample (linear between control ticks)
        stress = np.interp(np.arange(n) / CTRL, np.arange(n_ctrl), smooth_s)
    else:
        stress = hold_steps(smooth_s, n)
    raw = plate(voice, hz, m["HARDEN"], stress, forces, modes)
    if return_state:
        return raw, dict(hz=hz, stress_ctrl=raw_s, smooth=smooth_s, shot_t=shot_t, amp=amp,
                         lam=lam, seconds=seconds, modes=len(modes), forces=forces)
    return raw


# ------------------------------------------------------------------ HOLD

def hold_spec_method(voice, macros=None):
    """The spec's §11: render 3 s with the blast and roller running, cut a loop at the zero
    crossing with the smallest slope mismatch, measure against the real preceding material."""
    m = {**MACROS, **(macros or {})}
    v = VOICES[voice]
    hz = hz_for(midi_for(m["TUNE"]))
    n = int(3.2 * RATE)
    n_ctrl = -(-n // CTRL)
    shot_t, amp, x, energy, _ = blast_schedule(voice, m, v["lead"], 3.2)
    modes = plate_modes(voice, hz, m["HARDEN"])
    k_count = len(v["ratios"])
    forces = shot_force(n, shot_t, amp, x, [k for k, _, _ in modes], k_count)
    v0 = sk.lin(m["RPM"], 0.15, 1.0)
    roll = roller_force(voice, m, n, hold_v=v0, revs_per_s=REVS_PER_S * v0)
    w_roll = weights(0.97, k_count)
    for row, (k, _, _) in enumerate(modes):
        forces[row] += w_roll[k] * roll
    _, sm = coverage_stress(voice, shot_t, energy, n_ctrl)
    y = sk.dc_block(sk.decimate4(sk.band_limit(plate(voice, hz, m["HARDEN"], hold_steps(sm, n), forces, modes), RATE)))
    start = next(i for i in range(int(1.2 * OUT), len(y) - 1) if y[i - 1] < 0 <= y[i])
    slope0 = y[start] - y[start - 1]
    cands = [i for i in range(int(2.6 * OUT), int(3.1 * OUT)) if y[i - 1] < 0 <= y[i]]
    end = min(cands, key=lambda i: abs((y[i] - y[i - 1]) - slope0))
    return sk.seam_error(y[:end], start), (end - start) / OUT


def hold_adapted(voice, macros=None, cycles_warm=1):
    """Periodic by construction: loop = whole roller revolutions on a multiple of the control
    tick; one seeded shot schedule per loop, repeated; the stress orbit is converged at control
    rate (cheap), then the audio renders warm-up cycles + the loop + nothing else."""
    m = {**MACROS, **(macros or {})}
    v = VOICES[voice]
    hz = hz_for(midi_for(m["TUNE"]))
    v0 = sk.lin(m["RPM"], 0.15, 1.0)
    rev0 = REVS_PER_S * v0
    n_rev = max(1, round(2.0 * rev0))
    loop = int(round(n_rev / rev0 * RATE / CTRL)) * CTRL          # multiple of CTRL and of 4
    rev = n_rev * RATE / loop
    lam = lam_for(m["SHOT"])
    gaps, jit_a, jit_x = unit_gaps(voice)
    t = np.cumsum(gaps) / lam
    one = t < loop / RATE
    t1, ja, jx = t[one], jit_a[: one.sum()], jit_x[: one.sum()]
    amp1 = A_SHOT * math.sqrt(LAM_REF / lam) * (1 + 0.2 * ja)
    x1 = np.clip(sk.lin(m["TRAVERSE"], .05, .95) + 0.05 * jx, 0, 1)
    e1 = 1 + 0.2 * ja
    # stress orbit: iterate the control-rate map until one cycle repeats to 1e-12
    per = loop // CTRL
    ticks = (t1 * RATE).astype(int) // CTRL
    relax = math.exp(-v["rho"] * CTRL / RATE)
    a_s = math.exp(-CTRL / RATE / SMOOTH_S)
    s = y_s = 0.0
    prev = None
    for it in range(400):
        cyc = np.zeros(per)
        j = 0
        for k in range(per):
            s *= relax
            while j < len(ticks) and ticks[j] == k:
                s += v["kappa"] * e1[j] * (1 - s)
                j += 1
            y_s = a_s * y_s + (1 - a_s) * s
            cyc[k] = y_s
        if prev is not None and np.max(np.abs(cyc - prev)) < 1e-12:
            break
        prev = cyc
    cycles = cycles_warm + 1
    n = loop * cycles
    shot_t = np.concatenate([t1 + c * loop / RATE for c in range(cycles)])
    amp = np.tile(amp1, cycles)
    x = np.tile(x1, cycles)
    modes = plate_modes(voice, hz, m["HARDEN"])
    k_count = len(v["ratios"])
    forces = shot_force(n, shot_t, amp, x, [k for k, _, _ in modes], k_count)
    roll = roller_force(voice, m, n, hold_v=v0, revs_per_s=rev)
    w_roll = weights(0.97, k_count)
    for row, (k, _, _) in enumerate(modes):
        forces[row] += w_roll[k] * roll
    stress = np.tile(np.repeat(cyc, CTRL), cycles)
    y = sk.dc_block(sk.decimate4(sk.band_limit(plate(voice, hz, m["HARDEN"], stress, forces, modes), RATE)))
    start = loop // 4 * cycles_warm
    seam = sk.seam_error(y, start)
    return seam, loop / RATE, it + 1, float(np.mean(cyc)), y[start:]


# ------------------------------------------------------------------ the report

def upper_centroid(x, f0):
    spec = np.abs(np.fft.rfft(x * np.hanning(len(x)))) ** 2
    f = np.fft.rfftfreq(len(x), 1 / OUT)
    band = f > 1.5 * f0
    return float(np.sum(f[band] * spec[band]) / np.sum(spec[band]))


def peak_in(x, lo, hi):
    n = 1 << 16
    spec = np.abs(np.fft.rfft(x * np.hanning(len(x)), n))
    f = np.fft.rfftfreq(n, 1 / OUT)
    band = (f > lo) & (f < hi)
    return float(f[band][np.argmax(spec[band])])


def window(x, t0, t1):
    return x[int(t0 * OUT): int(t1 * OUT)]


def main(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    R = {}

    # M1, M2, M3
    m1 = {}
    for voice in VOICES:
        worst = 0.0
        for shot in (0, .5, 1):
            for vel in (.3, 1):
                mm = {**MACROS, "SHOT": shot}
                v = VOICES[voice]
                st, amp, x, e, _ = blast_schedule(voice, mm, v["lead"], v["lead"] + v["window"], vel)
                raw_s, _ = coverage_stress(voice, st, e, int(3 * RATE / CTRL))
                worst = max(worst, float(raw_s.max()))
        # the harshest case the law could see: shots every tick at the largest energy
        v = VOICES[voice]
        s = 0.0
        for _ in range(200_000):
            s = s * math.exp(-v["rho"] * CTRL / RATE) + v["kappa"] * 1.2 * (1 - s)
        m1[voice] = dict(max_over_cube=round(worst, 4), max_saturating=s, under_one=s < 1.0)
    R["M1_stress_bound"] = m1

    m2, m3 = {}, {}
    for voice in VOICES:
        v = VOICES[voice]
        pw, cov, spec_mean, cov_mean = {}, {}, {}, {}
        n_ctrl = int((v["lead"] + v["window"]) * RATE / CTRL) + 1
        # spec constants chosen so its stress sits near .5 at the default SHOT
        rel = 1.0 * CTRL / RATE
        acc = 0.5 * rel / (A_SHOT ** 2 * LAM_REF * CTRL / RATE)
        for shot in (.1, .5, .9):
            mm = {**MACROS, "SHOT": shot}
            st, amp, x, e, lam = blast_schedule(voice, mm, v["lead"], v["lead"] + v["window"])
            pw[shot] = float(np.sum(amp ** 2) / v["window"])      # mean shot power (pulse energy is fixed)
            raw_s, _ = coverage_stress(voice, st, e, n_ctrl)
            sp = spec_stress(st, amp, n_ctrl, acc, rel)
            k0 = int(v["lead"] * RATE / CTRL)
            cov[shot] = round(float(raw_s[-1]), 4)
            cov_mean[shot] = round(float(raw_s[k0:].mean()), 4)
            spec_mean[shot] = round(float(sp[k0:].mean()), 4)
        ref = pw[.5]
        m2[voice] = {f"SHOT {k}": round(10 * math.log10(p / ref), 2) for k, p in pw.items()}
        m3[voice] = dict(coverage_at_close=cov, coverage_mean_over_blast=cov_mean,
                         spec_law_mean_over_blast=spec_mean)
    R["M2_mean_shot_power_dB_vs_default"] = m2
    R["M3_stress_vs_SHOT"] = m3

    # R1: per-sample unsmoothed vs control-rate smoothed retune
    corner = {"HARDEN": 1.0, "SHOT": .8}
    ya = sk.decimate4(sk.band_limit(render("PLATE", corner), RATE))
    yb = sk.decimate4(sk.band_limit(render("PLATE", corner, interp_smooth=True), RATE))
    yc = sk.decimate4(sk.band_limit(render("PLATE", corner, per_sample=True), RATE))
    R["R1_retune"] = dict(
        control_step_vs_per_sample_same_stress_dB=round(10 * math.log10(np.sum((ya - yb) ** 2) / np.sum(yb ** 2)), 1),
        smoothed_control_vs_unsmoothed_per_sample_waveform_dB=round(10 * math.log10(np.sum((ya - yc) ** 2) / np.sum(yc ** 2)), 1),
        smoothed_control_vs_unsmoothed_per_sample_LSD_dB=round(pr.macro_activity(ya, yc), 2))

    # A: renders, pitch, centroid motion
    renders = []
    for voice in VOICES:
        for tune in (.25, .5, .75):
            raw, st = render(voice, {"TUNE": tune}, return_state=True)
            out = sk.finish(raw)
            midi = midi_for(tune)
            name = f"{voice}_tune{tune:.2f}_midi{midi}"
            sk.write_wav(os.path.join(out_dir, name + ".wav"), out)
            close = VOICES[voice]["lead"] + VOICES[voice]["window"]
            early = pr.pitch(out, OUT, start_seconds=0.03, window_seconds=0.15)
            tail = pr.pitch(out, OUT, start_seconds=close + 0.05, window_seconds=0.25)
            renders.append(dict(name=name, seconds=round(len(out) / OUT, 3), peak=round(pr.peak(out), 3),
                                dc=round(pr.dc(out), 6), finite=pr.finite(out), raw_peak=round(float(np.max(np.abs(raw))), 3),
                                early_pitch_cents=round(pr.cents(early[0], hz_for(midi)), 1) if early[0] else None,
                                early_conf=round(early[1], 3),
                                tail_pitch_cents=round(pr.cents(tail[0], hz_for(midi)), 1) if tail[0] else None,
                                tail_conf=round(tail[1], 3), modes=st["modes"], shots=len(st["shot_t"])))
    R["A_renders"] = renders

    cent = {}
    for voice in VOICES:
        v = VOICES[voice]
        close = v["lead"] + v["window"]
        for h in (0.0, .6, 1.0):
            out = sk.finish(render(voice, {"HARDEN": h, "SHOT": .7}))
            c0 = pr.spectral_centroid(window(out, v["lead"], v["lead"] + .15), OUT)
            c1 = pr.spectral_centroid(window(out, close + .05, close + .45), OUT)
            f0 = hz_for(midi_for(.5))
            u0 = upper_centroid(window(out, v["lead"], v["lead"] + .15), f0)
            u1 = upper_centroid(window(out, close + .05, close + .45), f0)
            r4 = v["ratios"][4] if voice == "PLATE" else v["ratios"][3]
            p0 = peak_in(window(out, v["lead"], v["lead"] + .3), f0 * r4 * .97, f0 * r4 * 1.5)
            p1 = peak_in(window(out, close + .05, close + .45), f0 * r4 * .97, f0 * r4 * 1.5)
            cent[f"{voice} HARDEN {h}"] = dict(centroid_blast_start=round(c0), centroid_after_close=round(c1),
                                               upper_centroid_start=round(u0), upper_centroid_after=round(u1),
                                               tracked_partial_ratio_start=round(p0 / f0, 3),
                                               tracked_partial_ratio_after=round(p1 / f0, 3))
            if voice == "PLATE" and h in (0.0, 1.0):
                sk.write_wav(os.path.join(out_dir, f"PLATE_SHOT.7_HARDEN{h}.wav"), out)
    R["A_hardening"] = cent

    act = {}
    base = {**MACROS}
    for macro in ("RPM", "SHOT", "HARDEN", "TRAVERSE"):
        lo = sk.finish(render("PLATE", {macro: 0.0}))
        mid = sk.finish(render("PLATE", {macro: 0.5}))
        hi = sk.finish(render("PLATE", {macro: 1.0}))
        act[macro] = dict(lo_vs_hi=round(pr.macro_activity(lo, hi), 2), lo_vs_mid=round(pr.macro_activity(lo, mid), 2))
        sk.write_wav(os.path.join(out_dir, f"PLATE_{macro}_0.wav"), lo)
        sk.write_wav(os.path.join(out_dir, f"PLATE_{macro}_1.wav"), hi)
    R["A_macro_activity_dB"] = act

    raw = render("PLATE", {"TUNE": 1.0, "HARDEN": 1.0, "SHOT": 1.0, "RPM": 1.0})
    R["A_alias_proxy_raw_corner"] = round(pr.alias_proxy(raw, RATE), 6)
    R["A_modes_kept_at_TUNE1_HARDEN1"] = len(plate_modes("PLATE", hz_for(midi_for(1.0)), 1.0))
    r1 = render("PLATE")
    r2 = render("PLATE")
    R["A_determinism"] = bool(np.array_equal(r1, r2))

    # H1 / H2
    h = {}
    for voice in VOICES:
        for tag, mm in (("default", {}), ("SHOT1_HARDEN1", {"SHOT": 1.0, "HARDEN": 1.0})):
            s1, l1 = hold_spec_method(voice, mm)
            s2, l2, iters, s_mean, loop = hold_adapted(voice, mm)
            h[f"{voice} {tag}"] = dict(spec_cut_seam=float(f"{s1:.3g}"), spec_cut_seconds=round(l1, 3),
                                       adapted_seam=float(f"{s2:.3g}"), adapted_seconds=round(l2, 3),
                                       stress_orbit_iterations=iters, held_stress=round(s_mean, 3),
                                       adapted_passes=s2 < sk.MAX_SEAM_ERROR)
            if tag == "default":
                lv = sk.level_to(loop)
                sk.write_wav(os.path.join(out_dir, f"{voice}_LOOP_x8.wav"), np.tile(lv, 8))
    R["H_hold"] = h

    # C: lengths over the macro cube
    lengths = {}
    for voice in VOICES:
        vals = []
        for rpm in (0, .5, 1):
            for shot in (0, .5, 1):
                for hard in (0, .5, 1):
                    for vel in (.2, 1):
                        vals.append(render_seconds(voice, {**MACROS, "RPM": rpm, "SHOT": shot, "HARDEN": hard}, vel))
        lengths[voice] = dict(min=round(min(vals), 3), max=round(max(vals), 3),
                              under_1_5=sum(1 for x in vals if x <= 1.5), of=len(vals))
    R["C_render_seconds"] = lengths

    # classifier bait: what the first 1.2 s of a dense blast looks like if the engine were cut short
    for voice in VOICES:
        out = sk.finish(render(voice, {"SHOT": 1.0, "RPM": .8}))
        sk.write_wav(os.path.join(out_dir, f"CUT_{voice}_SHOT1_first1.2s.wav"), out[: int(1.2 * OUT)])
        sk.write_wav(os.path.join(out_dir, f"FULL_{voice}_SHOT1.wav"), out)

    with open(os.path.join(out_dir, "report.json"), "w") as f:
        json.dump(R, f, indent=1)
    print(json.dumps(R, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "scour_out"))
