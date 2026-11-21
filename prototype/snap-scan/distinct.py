"""Round 0b: how distinct can different photos get while staying musical?

Python harness, not Kotlin. Builds the SNAP SCAN render with every layer of the design on, then
stacks two new levers, and measures each step on the owner's photos:

  L0 today        today's read and today's knob mapping
  L1 +scan        16 aligned strips, SCAN 0.6, HORIZON mix (the spec's law)
  L2 +rank        knobs rank-mapped through calibration v1 instead of linear stretches
  L3 +portrait    warmth tilt, symmetry drift, grain breath, light-direction envelope family
  L4 +contrast    spectral contrast: strip the 1/f slope every photo shares, amplify what is left
  L5 +voicing     colour channels as three detuned layers whose balance is the photo's colour

TUNE is held at the centre for every measurement so that pitch, which hue already moves, cannot
inflate the distances: these numbers are about timbre and shape only.

Run:  python3 distinct.py /path/to/photos /path/to/out
"""
import sys, os, glob, json
from multiprocessing import Pool
import numpy as np
from scipy.signal import lfilter
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(1, os.path.join(HERE, '..', '..', '.claude', 'skills', 'luthier', 'scripts'))
import scan_proto as sp, real_photos as rp, snipkit as sk

RATE = 44100; IRATE = RATE * 4; N = 256; BANDS = 16
CAL = json.load(open(os.path.join(HERE, "calib_report.json")))["percentile_table_0_to_100_by_12.5"]
LEVELS = ["L0_today", "L1_scan", "L2_rank", "L3_portrait", "L4_contrast", "L5_voicing"]


def rank(name, v):
    """Calibration v1: where this reading sits among the owner's photos, 0..1."""
    return float(np.interp(v, CAL[name], np.linspace(0, 1, 9)))


def contrast(table, amount, slope):
    """Spectral contrast. Every natural photo's line has roughly the same 1/f harmonic slope
    (measured: median -1.12), so that slope is what makes photos sound alike. Fit it, remove it,
    scale what is left (the photo's own peaks and holes) by `amount`, then lay a chosen slope back
    under it so the result is never white noise. Phases are the photo's own and are kept."""
    spec = np.fft.rfft(table); h = np.arange(1, len(spec))
    mag = np.abs(spec[1:]) + 1e-9; lh = np.log(h); lm = np.log(mag)
    k = h <= 64
    fit = np.polyfit(lh[k], lm[k], 1)
    resid = lm - np.polyval(fit, lh)
    new = slope * lh + amount * np.clip(resid, -3, 3)
    new[h > 96] -= (h[h > 96] - 96) * 0.15          # roll the top octave off
    spec[1:] = np.exp(new) * np.exp(1j * np.angle(spec[1:]))
    spec[0] = 0
    out = np.fft.irfft(spec, len(table))
    return out / np.abs(out).max()


def read(path):
    rgb = rp.load(path); L = rp.lum(rgb)
    f = rp.look(rgb); f.update(rp.portrait(rgb))
    horizon = sp.cycle(L.mean(0))
    strips = sp.align(sp.bands(L))
    # colour layers: each channel's own HORIZON line
    chans = [sp.cycle(rgb[..., c].mean(0)) for c in range(3)]
    means = rgb.reshape(-1, 3).mean(0)
    return dict(f=f, horizon=horizon, strips=strips, chans=chans, means=means, light_dir=sp.light_dir(L))


def svf_lowpass(x, cut, k=1.2):
    g = np.tan(np.pi * np.minimum(cut, IRATE * 0.45) / IRATE)
    a1 = 1 / (1 + g * (g + k)); ic1 = ic2 = 0.0; y = np.empty_like(x)
    for i in range(len(x)):
        v1 = a1[i] * (ic1 + g[i] * (x[i] - ic2)); v2 = ic2 + g[i] * v1
        ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2; y[i] = v2
    return y


def osc(tables, pos, hz, n, phase0=0.0):
    """Read a stack of tables at fractional strip position `pos` (array) and pitch `hz`."""
    T = np.asarray(tables)
    phase = (phase0 + np.arange(n) * hz * N / IRATE) % N
    i0 = phase.astype(int); fr = phase - i0; i1 = (i0 + 1) % N
    b0 = np.clip(np.floor(pos).astype(int), 0, len(T) - 1); b1 = np.minimum(b0 + 1, len(T) - 1)
    bw = np.clip(pos - b0, 0, 1); bw = bw * bw * (3 - 2 * bw)
    s0 = T[b0, i0] * (1 - fr) + T[b0, i1] * fr; s1 = T[b1, i0] * (1 - fr) + T[b1, i1] * fr
    return s0 * (1 - bw) + s1 * bw


def render(r, level, take=0, fp=0.5, scan=0.6, tune=0.5):
    f = r["f"]; L = LEVELS.index(level)
    rng = np.random.default_rng(1000 + take); jit = lambda lo, hi: rng.uniform(lo, hi) * fp
    # ---- knobs
    if L >= 2:
        bright = 0.15 + 0.8 * rank("lum", f["lum"])
        decay = 0.2 + 0.7 * rank("sat", f["sat"])
        grit = 0.8 * rank("detail", f["detail"])
    else:
        bright = 0.15 + 0.8 * f["lum"]; decay = 0.2 + 0.7 * min(f["sat"] * 2, 1); grit = min(f["detail"] * 8, 1) * 0.8
    hz = 110 * 2 ** (round(tune * 24) / 12) * 2 ** (jit(-6, 6) / 1200)
    t60 = sk.exp_map(decay, .15, 2.0) * (1 + jit(-.12, .12))
    secs = min(max(t60 * 1.1, .25), 1.45); n = int(secs * IRATE); t = np.arange(n) / IRATE
    # ---- envelope: L3 picks a family from where the light is
    if L >= 3:
        lr = rank("light", f["light"])                    # 1 bright sky .. 0 bright ground
        attack = sk.exp_map(1 - lr, 0.003, 0.35 * secs)   # strike .. swell
        hold = rank("symmetry", f["symmetry"]) * 0.25 * secs
    else:
        attack, hold = 0.003, 0.0
    env = np.minimum(t / attack, 1) ** 1.5 * np.where(t < attack + hold, 1.0, 10 ** (-3 * np.maximum(t - attack - hold, 0) / t60))
    # ---- source
    if L == 0:
        x = osc([r["horizon"]], np.zeros(n), hz, n)
    else:
        strips = r["strips"]
        if L >= 4:
            amount = 1.0 + 1.5 * rank("detail", f["detail"])        # busier photos get more contrast
            slope = -1.6 + 0.9 * rank("lum", f["lum"])               # darker photos get a darker slope
            strips = [contrast(s, amount, slope) for s in strips]
        start = abs(jit(0, 1.0)); travel = scan * (BANDS - 1)
        pos = start + travel * (1 - np.exp(-3 * t / secs)) / (1 - np.exp(-3))
        pos = np.clip(pos, 0, BANDS - 1)
        if r["light_dir"] < 0: pos = (BANDS - 1) - pos
        ph = jit(0, N)
        x = (1 - scan) * osc([r["horizon"]], np.zeros(n), hz, n, ph) + scan * osc(strips, pos, hz, n, ph)
        if L >= 5:
            # colour layers: R a little flat, B a little sharp, each weighted by how much of that
            # colour the photo holds; a grey photo gets three equal quiet layers, a red one a red layer
            w = r["means"] / max(r["means"].sum(), 1e-6)
            spread = 4 + 10 * rank("sat", f["sat"])                # cents; vivid photos spread wider
            for c, cents in zip(range(3), (-spread, 0.0, spread)):
                x += 0.6 * w[c] * osc([r["chans"][c]], np.zeros(n), hz * 2 ** (cents / 1200), n, ph + 37 * c)
    if L >= 3:
        # symmetry: lopsided photos drift (slow pitch wobble applied as a tiny phase-mod via resample)
        depth = (1 - rank("symmetry", f["symmetry"])) * 8.0      # cents
        drift = 2 ** (depth / 1200 * np.sin(2 * np.pi * 0.7 * t + jit(0, 6.28)))
        idx = np.clip(np.cumsum(drift), 0, n - 1)
        x = np.interp(idx, np.arange(n), x)
        # warmth tilt: warm rounds the top (one-pole low-pass mix), cool adds air (high-pass mix)
        wr = rank("warmth", f["warmth"]) - 0.5
        a = np.exp(-2 * np.pi * 2500 / IRATE); lp = lfilter([1 - a], [1, -a], x)
        x = x + wr * 1.2 * (lp - x) if wr > 0 else x + (-wr) * 0.8 * (x - lp)
        # grain: breath noise, band-limited, follows the envelope
        g = rank("grain", f["grain"]) * 0.12
        noise = lfilter([1 - a], [1, -a], np.random.default_rng(7 + take).normal(0, 1, n))
        x = x + g * noise / (np.abs(noise).max() + 1e-9)
    cut = sk.exp_map(bright, 250, 12000) * (0.45 + 0.55 * env)
    y = svf_lowpass(x, cut)
    gain = 1 + 2 * grit
    if L >= 3 and f.get("clipped", 0) > 0 and r.get("camera", True):
        gain *= 1 + 2 * rank("clipped", f["clipped"])
    y = np.tanh(y * gain) / np.tanh(gain) * env
    return sk.finish(y)


# ---------- metrics ----------
EDGES = 125 * 2 ** (np.arange(0, 22) / 3)

def frames(x, frame=2048, secs=0.6):
    f = np.fft.rfftfreq(frame, 1 / RATE); out, lev = [], []
    for s in range(0, min(len(x), int(secs * RATE)) - frame, frame // 2):
        p = np.abs(np.fft.rfft(x[s:s + frame] * np.hanning(frame))) ** 2
        e = np.array([p[(f >= lo) & (f < hi)].sum() for lo, hi in zip(EDGES[:-1], EDGES[1:])])
        out.append(np.maximum(10 * np.log10(e / max(e.sum(), 1e-12) + 1e-9), -60))
        lev.append(10 * np.log10(np.mean(x[s:s + frame] ** 2) + 1e-12))
    return np.array(out), np.array(lev)

def timbre_dist(a, b):
    A, _ = a; B, _ = b; n = min(len(A), len(B)); return float(np.sqrt(np.mean((A[:n] - B[:n]) ** 2)))

def shape_dist(a, b):
    """Loudness-contour distance over the first 600 ms, each contour relative to its own peak."""
    _, A = a; _, B = b; n = min(len(A), len(B))
    A = np.maximum(A[:n] - A.max(), -40); B = np.maximum(B[:n] - B.max(), -40)
    return float(np.sqrt(np.mean((A - B) ** 2)))

def centroid(x):
    p = np.abs(np.fft.rfft(x[: int(0.3 * RATE)])) ** 2; f = np.fft.rfftfreq(len(x[: int(0.3 * RATE)]), 1 / RATE)
    return float((p * f).sum() / p.sum())

def pitch_ok(x, target=220.0):
    """Autocorrelation pitch of 100 ms after the attack, within 50 cents of the note (or an octave of it)."""
    s = x[int(0.05 * RATE): int(0.15 * RATE)]; s = s - s.mean()
    ac = np.correlate(s, s, "full")[len(s) - 1:]
    lo, hi = int(RATE / 900), int(RATE / 60)
    lag = lo + int(np.argmax(ac[lo:hi])); f0 = RATE / lag
    cents = 1200 * np.log2(f0 / target); return bool(min(abs(cents - 1200 * k) for k in (-1, 0, 1)) < 50)


def job(args):
    path, level = args
    r = read(path)
    a = render(r, level, take=0); b = render(r, level, take=1)
    return os.path.basename(path)[:8], level, frames(a), frames(b), centroid(a), pitch_ok(a), float(np.sqrt(np.mean(a ** 2)))


def main():
    up, out = sys.argv[1], sys.argv[2]
    files = sorted(glob.glob(up + "/*"))
    # drop exact duplicates the same way calib2 does
    import hashlib
    seen, uniq = set(), []
    for f in files:
        h = hashlib.sha1((rp.load(f) * 255).astype(np.uint8).tobytes()).hexdigest()
        if h not in seen: seen.add(h); uniq.append(f)
    with Pool(4) as pool:
        res = pool.map(job, [(f, lv) for lv in LEVELS for f in uniq])
    by = {}
    for name, lv, fa, fb, c, ok, rms in res: by.setdefault(lv, {})[name] = (fa, fb, c, ok)
    names = sorted(by[LEVELS[0]]); rng = np.random.default_rng(5)
    pairs = [tuple(rng.choice(names, 2, replace=False)) for _ in range(300)]
    rep = {"images": len(names), "note": "Python harness; TUNE held at centre; timbre = third-octave dB rms, shape = loudness contour dB rms, first 600 ms"}
    for lv in LEVELS:
        d = by[lv]
        cents = np.array([d[n][2] for n in names])
        rep[lv] = {
            "different_photos_timbre_db": round(float(np.median([timbre_dist(d[a][0], d[b][0]) for a, b in pairs])), 2),
            "different_photos_shape_db": round(float(np.median([shape_dist(d[a][0], d[b][0]) for a, b in pairs])), 2),
            "round_robin_timbre_db": round(float(np.median([timbre_dist(d[n][0], d[n][1]) for n in names])), 2),
            "round_robin_shape_db": round(float(np.median([shape_dist(d[n][0], d[n][1]) for n in names])), 2),
            "centroid_hz_10_50_90": [round(float(np.percentile(cents, p))) for p in (10, 50, 90)],
            "pitch_held_share": round(float(np.mean([d[n][3] for n in names])), 2),
        }
    json.dump(rep, open(os.path.join(out, "distinct_report.json"), "w"), indent=1)
    print(json.dumps(rep, indent=1))


if __name__ == "__main__":
    main()
