"""HYDROFORM prototype: Python harness, not Kotlin. Nothing here has been heard.

Settles four design risks with numbers before any spec is written:
  R1  Does a dense all-pass chain concentrate a transient (the brief's claim) or spread it?
  R2  Does disperse -> fold -> phase-conjugate recompress (offline only) beat a direct fold?
  R3  How much does a slew-gated folder alias at the house 4x rate vs a 16x reference?
  R4  Does each macro (CHARGE, STANDOFF, YIELD, ARREST) move its own measurable quantity?

Run: python3 hydroform_proto.py out_dir
"""
from __future__ import annotations

import json
import math
import os
import sys

import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
LUTHIER = next((os.path.join(d, ".claude", "skills", "luthier", "scripts") for d in
                (HERE, *[os.path.abspath(os.path.join(HERE, *([".."] * k))) for k in range(1, 6)])
                if os.path.isdir(os.path.join(d, ".claude", "skills", "luthier", "scripts"))),
               "/home/user/SnipSnap/.claude/skills/luthier/scripts")
sys.path.insert(0, LUTHIER)
import snipkit as sk  # noqa: E402
import probes as pr   # noqa: E402

RATE = sk.RATE
INTERNAL = sk.INTERNAL
MACROS = {"CHARGE": 0.5, "STANDOFF": 0.5, "YIELD": 0.5, "ARREST": 0.5}
SECONDS = 0.6

# ---------------------------------------------------------------- sources (stand-ins for a pad)

def kick_source(rate: int, seconds: float = SECONDS) -> np.ndarray:
    """A THUMP-like kick: a 160 -> 48 Hz sine sweep with t60 0.45 s plus a 0.6 ms click."""
    n = int(seconds * rate)
    t = np.arange(n) / rate
    f = 48.0 + 112.0 * np.exp(-t / 0.02)
    phase = 2 * np.pi * np.cumsum(f) / rate
    body = np.sin(phase) * np.exp(-6.9078 * t / 0.45)
    click = sk.hann_pulse(0.6, rate, 0.8, seconds)[:n]
    x = body + click
    return x / np.max(np.abs(x))


def metal_source(rate: int, seconds: float = SECONDS) -> np.ndarray:
    """A short inharmonic modal ring (a keygroup 'metallic attack' stand-in). Ratios are shape values."""
    modes = [(1.0, 1.0, .30), (2.76, .6, .22), (5.40, .4, .16), (8.93, .25, .12), (13.3, .15, .08)]
    strike = sk.hann_pulse(0.25, rate, 1.0, seconds)
    x = sk.modal_ring(strike, 220.0, modes, rate)
    return x / np.max(np.abs(x))


# ---------------------------------------------------------------- stage 1: the medium (all-pass chain)

def allpass_chain(x: np.ndarray, rate: int, sections: int, f_lo: float = 300.0, f_hi: float = 6000.0,
                  q: float = 0.7) -> np.ndarray:
    """RBJ second-order all-passes (the form of Dsp.Biquad.allpass), centres spread log-wise."""
    y = x
    for k in range(sections):
        f0 = f_lo * (f_hi / f_lo) ** (k / max(sections - 1, 1))
        w0 = 2 * np.pi * f0 / rate
        cw, sw = np.cos(w0), np.sin(w0)
        alpha = sw / (2 * q)
        b = [1 - alpha, -2 * cw, 1 + alpha]
        a = [1 + alpha, -2 * cw, 1 - alpha]
        y = signal.lfilter(b, a, y)
    return y


def allpass_conjugate(x: np.ndarray, rate: int, sections: int, **kw) -> np.ndarray:
    """The exact inverse of allpass_chain, possible only offline: reverse, filter, reverse."""
    return allpass_chain(x[::-1], rate, sections, **kw)[::-1]


def energy_width_ms(x: np.ndarray, rate: int, frac: float = 0.9) -> float:
    """Time for the cumulative energy to reach `frac` of the total: a transient's spread."""
    e = np.cumsum(x * x)
    if e[-1] <= 0:
        return 0.0
    i = int(np.searchsorted(e, frac * e[-1]))
    return 1000.0 * i / rate


# ---------------------------------------------------------------- stage 2: charge and standoff

def shock(charge: float, standoff: float, rate: int, n: int) -> np.ndarray:
    """A pressure front: a 0.1 ms rise (the broadband edge) into a 48 Hz decaying sine (the weight),
    placed `standoff` sub-milliseconds after t=0. Closed-form exponential; no RK4 needed."""
    amp = sk.lin(charge, 0.0, 1.2)
    delay = int(sk.lin(standoff, 0.0, 1.0e-3) * rate)
    t = np.arange(n) / rate
    rise = np.minimum(t / 1.0e-4, 1.0)
    rise = 0.5 - 0.5 * np.cos(np.pi * rise)
    body = np.sin(2 * np.pi * 48.0 * t) * np.exp(-6.9078 * t / 0.25)
    # the front itself: a sharp unipolar edge that decays in 0.5 ms, so the edge has energy up to ~10 kHz
    front = np.exp(-t / 5.0e-4)
    s = amp * rise * (0.45 * body + 0.55 * front)
    out = np.zeros(n)
    out[delay:] = s[: n - delay]
    return out


# ---------------------------------------------------------------- stage 3: hyperplastic (slew-gated) shaper

def fold_tri(x: np.ndarray) -> np.ndarray:
    """A triangle wavefolder: reflect anything past +-1 back inside. Period 4 in x."""
    return np.abs(((x + 1.0) % 4.0) - 2.0) - 1.0


def fold_tri_F(x: np.ndarray) -> np.ndarray:
    """Antiderivative of fold_tri (its integral over one period is 0, so this is periodic too)."""
    u = ((x + 1.0) % 4.0) - 2.0
    return np.where(u < 0, -u * u / 2 - u, u * u / 2 - u)


def clip_F(x: np.ndarray) -> np.ndarray:
    a = np.abs(x)
    return np.where(a <= 1.0, x * x / 2, a - 0.5)


def adaa1(x: np.ndarray, f, F, eps: float = 1e-5) -> np.ndarray:
    """First-order antiderivative anti-aliasing (Parker, Zavalishin, Le Bivic, DAFx-16):
    the curve is applied to the continuous-time path between samples, not to the samples."""
    x0 = np.concatenate(([x[0]], x[:-1]))
    d = x - x0
    safe = np.abs(d) > eps
    mid = f((x + x0) / 2)
    out = np.empty_like(x)
    out[~safe] = mid[~safe]
    out[safe] = (F(x[safe]) - F(x0[safe])) / d[safe]
    return out


def slew_state(x: np.ndarray, rate: int, yield_: float) -> np.ndarray:
    """p in 0..1: how 'hyperplastic' the shaper is, from the smoothed |dx/dt| against the YIELD threshold.
    A full-scale sine at f has peak slew 2*pi*f; 150..6000 /s spans a 24 Hz sine to a 1 kHz one, which is where a drum's body and its click sit."""
    d = np.abs(np.diff(x, prepend=x[0])) * rate
    k = 1.0 - math.exp(-2 * math.pi * 2000.0 / rate)   # 2 kHz one-pole on the detector
    s = signal.lfilter([k], [1, -(1 - k)], d)
    thr = float(s.max()) * sk.exp_map(yield_, 0.02, 1.0)   # a fraction of this pad's own peak slew
    u = (np.log(np.maximum(s, 1e-9)) - np.log(thr)) / np.log(3.0)   # one 'width' = a factor of 3 in slew
    u = np.clip(u, 0.0, 1.0)
    return u * u * (3 - 2 * u)


FOLD_DRIVE = 3.0   # shape: how much harder the hyperplastic (fast) material is driven into the folder


def hyperplastic(x: np.ndarray, rate: int, yield_: float, pre_gain: float = 1.3, adaa: bool = True) -> np.ndarray:
    """Slow material (low slew) meets a rigid clip at a modest drive. Fast material (high slew) flows:
    it is driven FOLD_DRIVE times harder into a wavefolder. A curve switch alone does nothing where the
    fast part is small in amplitude (a kick's click sits under its body), so the state must add drive."""
    p = slew_state(x, rate, yield_)
    g_clip = x * pre_gain
    g_fold = x * pre_gain * (1.0 + FOLD_DRIVE * p)
    if adaa:
        return (1 - p) * adaa1(g_clip, lambda v: np.clip(v, -1, 1), clip_F) + p * adaa1(g_fold, fold_tri, fold_tri_F)
    return (1 - p) * np.clip(g_clip, -1, 1) + p * fold_tri(g_fold)


# ---------------------------------------------------------------- stage 4: die arrest (coining)

def die_arrest(x: np.ndarray, rate: int, arrest: float, seed: int, burst_gain: float = 0.35, adaa: bool = True,
               burst_band: tuple[float, float] | None = (3000.0, 14000.0)) -> np.ndarray:
    """Morph a tanh boundary (elastic, harmonic bleed) into a hard clamp; the clipped-off excess
    drives a short high-passed noise burst (the 'thermal' micro-burst). No gain envelope anywhere."""
    ceiling = sk.lin(arrest, 1.0, 0.4)          # the die closes: the stroke goes deeper as ARREST rises
    xs = x / ceiling
    K = 1.6
    if adaa:
        soft = adaa1(xs, lambda v: np.tanh(K * v) / math.tanh(K), lambda v: np.log(np.cosh(K * v)) / (K * math.tanh(K)))
        hard = adaa1(xs, lambda v: np.clip(v, -1, 1), clip_F)
    else:
        soft = np.tanh(K * xs) / math.tanh(K)
        hard = np.clip(xs, -1, 1)
    y = ((1 - arrest) * soft + arrest * hard) * ceiling
    excess = np.maximum(np.abs(xs) - 1.0, 0.0)
    k = 1.0 - math.exp(-1.0 / (0.5e-3 * rate))     # the burst follows the excess with a 0.5 ms decay
    env = signal.lfilter([k], [1, -(1 - k)], excess)
    noise = sk.Noise(seed).next_array(len(x)) if hasattr(sk.Noise, "next_array") else np.random.default_rng(seed).uniform(-1, 1, len(x))
    if burst_band is None:
        hp = signal.butter(2, 4000.0 / (rate / 2), btype="high", output="sos")
    else:
        hp = signal.butter(4, [burst_band[0] / (rate / 2), burst_band[1] / (rate / 2)], btype="band", output="sos")
    burst = signal.sosfilt(hp, noise) * env
    return y + arrest * burst_gain * burst * ceiling


# ---------------------------------------------------------------- the whole chain

def render(source: np.ndarray, macros: dict, rate: int = INTERNAL, recompress: bool = False, adaa: bool = True,
           burst_gain: float = 0.35, burst_band=(3000.0, 14000.0), standoff_mode: str = "shock") -> np.ndarray:
    m = {**MACROS, **{k: v for k, v in macros.items() if k in MACROS}}
    n = len(source)
    sections = int(round(sk.lin(m["CHARGE"], 2, 24)))
    if standoff_mode == "copy":
        front = np.zeros(n)
        w = int(0.002 * rate)
        head = source[:w] * np.hanning(2 * w)[w:]
        delay = int(sk.lin(m["STANDOFF"], 0.0, 1.0e-3) * rate)
        front[delay:delay + w] = head
        lf = shock(m["CHARGE"], 0.0, rate, n)   # the weight stays at t=0; only the front moves
        x = source + 0.6 * front + lf
    else:
        x = source + shock(m["CHARGE"], m["STANDOFF"], rate, n)
    x = x / max(np.max(np.abs(x)), 1e-9)
    x = allpass_chain(x, rate, sections)
    x = x / max(np.max(np.abs(x)), 1e-9)       # the medium spreads the pulse but delivers all of its energy
    x = hyperplastic(x, rate, m["YIELD"], adaa=adaa)
    if recompress:
        x = allpass_conjugate(x, rate, sections)
    x = die_arrest(x, rate, m["ARREST"], sk.seed_for("HYDROFORM", sorted(m.items())), adaa=adaa,
                   burst_gain=burst_gain, burst_band=burst_band)
    return x * (np.max(np.abs(source)) / max(np.max(np.abs(x)), 1e-9))


def finish(raw: np.ndarray) -> np.ndarray:
    return sk.finish(raw)


# ---------------------------------------------------------------- measurements

def crest(x: np.ndarray) -> float:
    r = pr.rms(x)
    return float(np.max(np.abs(x)) / r) if r > 0 else 0.0


def band_share(x: np.ndarray, rate: int, lo: float, hi: float, ms: float | None = None) -> float:
    seg = x[: int(ms * 1e-3 * rate)] if ms else x
    f, p = signal.periodogram(seg, rate)
    tot = p.sum()
    return float(p[(f >= lo) & (f < hi)].sum() / tot) if tot > 0 else 0.0


def attack_db(x: np.ndarray, rate: int, ms: float = 6.0) -> tuple[np.ndarray, np.ndarray]:
    n = int(ms * 1e-3 * rate)
    f, p = signal.periodogram(x[:n] * np.hanning(n), rate, nfft=1 << 15)
    return f, 10 * np.log10(np.maximum(p, 1e-20))


def first_notch_hz(x: np.ndarray, ref: np.ndarray, rate: int, lo: float = 400.0, hi: float = 12000.0) -> float:
    """The first dip deeper than 6 dB in the attack's difference spectrum against a reference render
    (STANDOFF 0): the source's own minima cancel, a comb's notch does not."""
    f, a = attack_db(x, rate)
    _, b = attack_db(ref, rate)
    d = np.convolve(a - b, np.ones(15) / 15, mode="same")
    sel = (f >= lo) & (f <= hi)
    fs, ds = f[sel], d[sel]
    for i in range(30, len(ds) - 30):
        if ds[i] < -6 and ds[i] <= ds[i - 1] and ds[i] <= ds[i + 1] and ds[i] < ds[i - 30] - 4 and ds[i] < ds[i + 30] - 4:
            return float(fs[i])
    return 0.0


def spectral_flatness(x: np.ndarray, rate: int, ms: float = 5.0, lo: float = 200.0, hi: float = 18000.0) -> float:
    seg = x[: int(ms * 1e-3 * rate)] * np.hanning(int(ms * 1e-3 * rate))
    f, p = signal.periodogram(seg, rate, nfft=1 << 14)
    sel = (f >= lo) & (f <= hi)
    p = np.maximum(p[sel], 1e-20)
    return float(np.exp(np.mean(np.log(p))) / np.mean(p))


def main(out_dir: str) -> int:
    os.makedirs(out_dir, exist_ok=True)
    report: dict = {"harness": "Python, not Kotlin; nothing heard", "internal_rate": INTERNAL}

    # ---- R1: does the all-pass chain concentrate or spread a transient?
    click = sk.hann_pulse(0.3, INTERNAL, 1.0, 0.05)
    r1 = {}
    for n in (0, 2, 8, 24):
        y = allpass_chain(click, INTERNAL, n) if n else click
        r1[f"sections_{n}"] = {"energy_90pct_ms": round(energy_width_ms(y, INTERNAL), 3),
                               "peak": round(float(np.max(np.abs(y))), 4)}
    rt = allpass_conjugate(allpass_chain(click, INTERNAL, 24), INTERNAL, 24)
    r1["forward_then_conjugate_identity_error"] = float(np.max(np.abs(rt - click)))
    report["R1_allpass_medium"] = r1

    # ---- R2: disperse -> fold -> recompress vs direct fold, on both sources
    r2 = {}
    for name, src in (("kick", kick_source(INTERNAL)), ("metal", metal_source(INTERNAL))):
        row = {}
        for label, mac, rc in (("direct_fold", {"CHARGE": 0.0}, False),
                               ("dispersed_fold", {"CHARGE": 1.0}, False),
                               ("recompressed_fold", {"CHARGE": 1.0}, True)):
            mac = {**mac, "ARREST": 0.0}   # keep the coiner elastic so the comparison is the folder's
            raw = render(src, mac, recompress=rc)
            out = finish(raw)
            row[label] = {"crest_out": round(crest(out), 3),
                          "energy_90pct_ms_out": round(energy_width_ms(out, RATE), 2),
                          "attack_flatness_5ms": round(spectral_flatness(out, RATE), 4),
                          "hf_share_above_3k_first_5ms": round(band_share(out, RATE, 3000, 20000, 5.0), 4),
                          "never_grows": pr.never_grows(out, RATE)[0]}
            sk.write_wav(os.path.join(out_dir, f"{name}_{label}.wav"), out)
        r2[name] = row
    report["R2_recompression"] = r2

    # ---- R3: aliasing of the slew-gated folder at 4x against a 16x reference
    r3 = {}
    variants = (("kick_curves_plain_burst_hp_only", kick_source, dict(adaa=False, burst_band=None)),
                ("kick_curves_adaa_burst_hp_only", kick_source, dict(adaa=True, burst_band=None)),
                ("kick_curves_adaa_no_burst", kick_source, dict(adaa=True, burst_gain=0.0)),
                ("kick_curves_plain_burst_band_3k_14k", kick_source, dict(adaa=False)),
                ("kick_curves_adaa_burst_band_3k_14k", kick_source, dict(adaa=True)),
                ("metal_curves_adaa_burst_band_3k_14k", metal_source, dict(adaa=True)))
    for name, fn, kw in variants:
        mac = {"YIELD": 0.3, "ARREST": 1.0}
        raw4 = render(fn(INTERNAL), mac, **kw)
        out4 = finish(raw4)
        r16 = RATE * 16
        raw16 = render(fn(r16), mac, rate=r16, **{**kw, "adaa": False})
        clean4 = signal.resample_poly(raw16, 1, 4)     # a sharp 16x -> 4x step: what a 4x render would be with no fold-back
        out16 = finish(clean4)                          # then the identical house output stage
        n = min(len(out4), len(out16)) - 200
        r3[name] = {"alias_proxy_raw_4x_share_above_output_nyquist": round(pr.alias_proxy(raw4, INTERNAL), 5),
                    "spectral_distance_dB_4x_vs_16x": round(pr.macro_activity(out4[:n], out16[:n], RATE), 3),
                    "spectral_distance_dB_4x_vs_16x_above_8k_only": round(_hf_distance(out4[:n], out16[:n], RATE), 3),
                    "partials_above_nyquist_4x": pr.partials_above_nyquist(out4, RATE)}
    report["R3_aliasing"] = r3

    # ---- R4: every macro moves its own quantity, on the kick
    src = kick_source(INTERNAL)
    metal = metal_source(INTERNAL)
    base = finish(render(src, {}))
    sk.write_wav(os.path.join(out_dir, "kick_default.wav"), base)
    standoff_ref = finish(render(src, {"STANDOFF": 0.0}))
    # detector calibration: what the slew detector actually reads on the kick (body vs click)
    import time
    t0 = time.perf_counter(); render(src, {}); report["one_render_ms_4x_python"] = round(1000 * (time.perf_counter() - t0), 1)
    d = np.abs(np.diff(src, prepend=src[0])) * INTERNAL
    k = 1.0 - math.exp(-2 * math.pi * 2000.0 / INTERNAL)
    sm = signal.lfilter([k], [1, -(1 - k)], d)
    report["detector_calibration_kick"] = {"max_smoothed_slew_per_s": round(float(sm.max()), 1),
                                            "body_slew_after_50ms_per_s": round(float(sm[int(0.05 * INTERNAL):].max()), 1)}
    r4 = {}
    standoff_ref_copy = finish(render(src, {"STANDOFF": 0.0}, standoff_mode="copy"))
    for macro, metric in (("CHARGE", lambda o: band_share(o, RATE, 20, 120)),
                          ("STANDOFF", lambda o: first_notch_hz(o, standoff_ref, RATE)),
                          ("STANDOFF_COPY", lambda o: first_notch_hz(o, standoff_ref_copy, RATE)),
                          ("YIELD", lambda o: band_share(o, RATE, 3000, 20000, 5.0)),
                          ("ARREST", lambda o: crest(o))):
        row = {}
        for v in (0.0, 0.25, 0.5, 0.75, 1.0):
            if macro == "STANDOFF_COPY":
                out = finish(render(src, {"STANDOFF": v}, standoff_mode="copy"))
            else:
                out = finish(render(metal if macro == "CHARGE" else src, {macro: v}))
            body = out[int(0.05 * RATE):]
            row[str(v)] = {"metric": round(float(metric(out)), 4),
                           "body_share_above_200Hz_after_50ms": round(band_share(body, RATE, 200, 20000), 4),
                           "hf_share_above_6k_whole": round(band_share(out, RATE, 6000, 20000), 5),
                           "distance_from_default_dB": round(pr.macro_activity(out, base, RATE), 3),
                           "peak": round(pr.peak(out), 3), "dc": round(pr.dc(out), 5),
                           "finite": pr.finite(out)}
            if v in (0.0, 1.0):
                sk.write_wav(os.path.join(out_dir, f"kick_{macro}_{v}.wav"), out)
        r4[macro] = {"quantity": {"CHARGE": "share of energy 20..120 Hz (on the METAL source, which has none)", "STANDOFF": "first comb notch Hz in the 6 ms attack's difference spectrum vs STANDOFF 0 (expect ~1/(2*delay)); shock-front mode",
                                  "STANDOFF_COPY": "same notch metric; the front is a delayed copy of the pad's own first 2 ms",
                                  "YIELD": "share above 3 kHz in the first 5 ms", "ARREST": "crest factor of the output"}[macro],
                     "sweep": row}
    report["R4_macros"] = r4

    # unknown key must not change the render
    alt = finish(render(src, {"BOGUS": 1.0}))
    report["unknown_key_changes_nothing"] = bool(np.array_equal(alt, base))

    with open(os.path.join(out_dir, "report.json"), "w") as f:
        json.dump(report, f, indent=2)
    print(json.dumps(report, indent=2))
    return 0


def _hf_distance(a: np.ndarray, b: np.ndarray, rate: int, lo: float = 8000.0) -> float:
    _, pa = signal.welch(a, rate, nperseg=4096)
    f, pb = signal.welch(b, rate, nperseg=4096)
    sel = f >= lo
    la = 10 * np.log10(np.maximum(pa[sel], 1e-14))
    lb = 10 * np.log10(np.maximum(pb[sel], 1e-14))
    return float(np.sqrt(np.mean((la - lb) ** 2)))


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "out"))
