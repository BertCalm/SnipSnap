"""Round 0c: the photo sets its own amounts, chooses its sound-maker, and picks its register.

Python harness, not Kotlin. Builds on distinct.py's L4 (rank-mapped knobs, PORTRAIT, spectral
contrast) and adds the owner's three asks:

  1. AMOUNTS   SCAN amount from how much the strips change top to bottom; contrast amount and
               slope over wider ranges.
  2. FAMILIES  four sound-makers, chosen by where the photo sits on two readings (detail and
               contrast), blended across the borders so a near-twin photo cannot jump family:
                 BREATH  soft, plain photos       noise through resonators tuned to the photo's harmonics
                 PLUCK   busy, soft-edged photos  a string whose starting shape is the photo's line
                 STRIKE  plain, hard-edged photos a struck bar whose partials are the photo's harmonics, stretched
                 TONE    busy, hard-edged photos  the scanned wavetable (L4)
  3. REGISTER  hue picks the note name (12 steps, not 24); brightness picks the octave
               (4 registers, A1 to G#5), so dark photos land low and bright photos high.

Run:  python3 families.py /path/to/photos /path/to/out
"""
import sys, os, glob, json, hashlib
from multiprocessing import Pool
import numpy as np
from scipy.signal import lfilter
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(1, os.path.join(HERE, '..', '..', '.claude', 'skills', 'luthier', 'scripts'))
import distinct as d, scan_proto as sp, real_photos as rp, snipkit as sk

RATE, IRATE, N, BANDS = d.RATE, d.IRATE, d.N, d.BANDS
FAMILIES = ["BREATH", "PLUCK", "STRIKE", "TONE"]
# centres on (detail rank, contrast rank)
CENTRES = {"BREATH": (0.2, 0.2), "PLUCK": (0.8, 0.25), "STRIKE": (0.25, 0.8), "TONE": (0.8, 0.8)}
TAU = float(os.environ.get('SNAP_TAU', 0.03))          # border softness: the width of the blend between neighbouring families
EXTRA = {}          # percentile tables for readings calibration v1 lacks (contrast, vchange)


def rank(name, v):
    if name in EXTRA: return float(np.interp(v, EXTRA[name], np.linspace(0, 1, 9)))
    return d.rank(name, v)


def vchange(strips):
    """How much the picture changes top to bottom: mean neighbour distance of the strip spectra."""
    mags = [20 * np.log10(np.abs(np.fft.rfft(s))[1:41] + 1e-4) for s in strips]
    return float(np.mean([np.sqrt(np.mean((a - b) ** 2)) for a, b in zip(mags, mags[1:])]))


def family_weights(f):
    x, y = rank("detail", f["detail"]), rank("contrast", f["contrast"])
    w = {k: np.exp(-((x - cx) ** 2 + (y - cy) ** 2) / TAU) for k, (cx, cy) in CENTRES.items()}
    top = sorted(w, key=w.get, reverse=True)[:2]           # at most two families blend
    s = w[top[0]] + w[top[1]]
    out = {k: (w[k] / s if k in top else 0.0) for k in FAMILIES}
    if out[top[0]] > 0.9: out = {k: (1.0 if k == top[0] else 0.0) for k in FAMILIES}
    return out


def note_today(f):
    return 45 + round(rp.macros_today(f)["TUNE"] * 24)            # A2 + 0..24


def note_new(f):
    grey = f["sat"] < 0.08 or f["hue_strength"] < 0.2
    pc = 0 if grey else int(round(((f["hue"] - 330 + 720) % 360) / 30)) % 12
    reg = min(int(rank("lum", f["lum"]) * 4), 3)                   # 0..3: A1, A2, A3, A4 bases
    return 33 + 12 * reg + pc


def harmonic_gains(table, n=16):
    m = np.abs(np.fft.rfft(table))[1:n + 1]; return m / (m.max() + 1e-12)


def render(r, take=0, fp=0.5, midi=57, families=None):
    f = r["f"]
    rng = np.random.default_rng(1000 + take); jit = lambda lo, hi: rng.uniform(lo, hi) * fp
    hz = 440 * 2 ** ((midi - 69) / 12) * 2 ** (jit(-6, 6) / 1200)
    bright = 0.15 + 0.8 * rank("lum", f["lum"])
    decay = 0.2 + 0.7 * rank("sat", f["sat"])
    grit = 0.8 * rank("detail", f["detail"])
    t60 = sk.exp_map(decay, .15, 2.0) * (1 + jit(-.12, .12))
    secs = min(max(t60 * 1.1, .25), 1.45); n = int(secs * IRATE); t = np.arange(n) / IRATE
    lr = rank("light", f["light"]); attack = sk.exp_map(1 - lr, 0.003, 0.35 * secs)
    hold = rank("symmetry", f["symmetry"]) * 0.25 * secs
    env = np.minimum(t / attack, 1) ** 1.5 * np.where(t < attack + hold, 1.0, 10 ** (-3 * np.maximum(t - attack - hold, 0) / t60))
    # ---- 1. the photo's own amounts
    scan = 0.3 + 0.7 * rank("vchange", r["vchange"])
    amount = 1.0 + 2.0 * rank("detail", f["detail"]); slope = -1.7 + 1.1 * rank("lum", f["lum"])
    strips = [d.contrast(s, amount, slope) for s in r["strips"]]
    mid = strips[BANDS // 2]
    ph = jit(0, N)
    weights = families or family_weights(f)
    x = np.zeros(n)
    if weights["TONE"]:
        start = abs(jit(0, 1.0)); travel = scan * (BANDS - 1)
        pos = np.clip(start + travel * (1 - np.exp(-3 * t / secs)) / (1 - np.exp(-3)), 0, BANDS - 1)
        if r["light_dir"] < 0: pos = (BANDS - 1) - pos
        y = (1 - scan) * d.osc([r["horizon"]], np.zeros(n), hz, n, ph) + scan * d.osc(strips, pos, hz, n, ph)
        x += weights["TONE"] * y / (np.abs(y).max() + 1e-9)
    if weights["PLUCK"]:
        # the string starts in the shape of the photo's mid line; BRIGHT sets the loop damping
        period = IRATE / hz; exc = np.zeros(n)
        k = int(period); exc[:k] = np.interp(np.arange(k) / k * N, np.arange(N), mid)
        loop = sk.KSLoop(hz=hz, rate=IRATE, loop_gain=10 ** (-3 / (t60 * hz)),
                         lowpass=0.55 + 0.43 * bright)
        y = loop.render(exc)
        x += weights["PLUCK"] * y / (np.abs(y).max() + 1e-9)
    if weights["STRIKE"]:
        # a bar: the photo's harmonic magnitudes are the partial gains; contrast stretches them
        B = 0.0002 + 0.003 * rank("contrast", f["contrast"])
        g = harmonic_gains(mid, 12); g[0] = max(g[0], 0.8)
        modes = [(h * np.sqrt(1 + B * h * h), g[h - 1], t60 / (1 + 0.35 * (h - 1))) for h in range(1, 13)]
        width = sk.exp_map(1 - rank("detail", f["detail"]), 0.3, 4.0)       # ms; plain photos hit softer
        exc = sk.hann_pulse(width, IRATE, total_seconds=secs)[:n]
        y = sk.modal_ring(exc, hz, modes, IRATE, compensate=True)
        x += weights["STRIKE"] * y / (np.abs(y).max() + 1e-9)
    if weights["BREATH"]:
        # noise through resonators at the photo's harmonics, a slow swell, and a quiet tone to hold the root
        noise = np.random.default_rng(11 + take).normal(0, 1, n); y = np.zeros(n)
        g = harmonic_gains(mid, 16); g[0] = max(g[0], 0.8)
        for h in range(1, 17):
            fc = hz * h
            if fc > IRATE * 0.2: break
            rr = np.exp(-np.pi * fc / 40 / IRATE); th = 2 * np.pi * fc / IRATE
            y += g[h - 1] * lfilter([1 - rr], [1, -2 * rr * np.cos(th), rr * rr], noise)
        y = y / (np.abs(y).max() + 1e-9) + 0.35 * d.osc([mid], np.zeros(n), hz, n, ph)
        swell = np.minimum(t / (0.4 * secs), 1)
        x += weights["BREATH"] * swell * y / (np.abs(y).max() + 1e-9)
    # ---- shared PORTRAIT finish (as distinct.py L3/L4)
    depth = (1 - rank("symmetry", f["symmetry"])) * 8.0
    drift = 2 ** (depth / 1200 * np.sin(2 * np.pi * 0.7 * t + jit(0, 6.28)))
    x = np.interp(np.clip(np.cumsum(drift), 0, n - 1), np.arange(n), x)
    wr = rank("warmth", f["warmth"]) - 0.5
    a = np.exp(-2 * np.pi * 2500 / IRATE); lp = lfilter([1 - a], [1, -a], x)
    x = x + wr * 1.2 * (lp - x) if wr > 0 else x + (-wr) * 0.8 * (x - lp)
    cut = sk.exp_map(bright, 250, 12000) * (0.45 + 0.55 * env)
    y = d.svf_lowpass(x, cut)
    gain = 1 + 2 * grit
    y = np.tanh(y * gain) / np.tanh(gain) * env
    return sk.finish(y)


def read(path):
    r = d.read(path); r["vchange"] = vchange(r["strips"]); return r


def pitch_ok(x, target):
    s = x[int(0.05 * RATE): int(0.25 * RATE)]; s = s - s.mean()
    ac = np.correlate(s, s, "full")[len(s) - 1:]
    # search only past the first dip below zero: below 70 Hz the zero-lag lobe is wide enough that
    # the plain arg-max lands on its shoulder (the round-0c detector reported 1002 Hz for 55 Hz notes)
    hi = int(RATE / 45); neg = np.where(ac[: hi] < 0)[0]
    lo = int(neg[0]) if len(neg) else int(RATE / 1000)
    lag = lo + int(np.argmax(ac[lo:hi])); f0 = RATE / lag
    cents = 1200 * np.log2(f0 / target); return bool(min(abs(cents - 1200 * k) for k in (-2, -1, 0, 1, 2)) < 50)


def job(args):
    path, extra = args
    EXTRA.update(extra)
    r = read(path); f = r["f"]; w = family_weights(f)
    a = render(r, 0); b = render(r, 1)
    m = note_new(f); real = render(r, 0, midi=m)
    return dict(name=os.path.basename(path)[:8], weights=w, fa=d.frames(a), fb=d.frames(b),
                note_today=note_today(f), note_new=m, pitch_ok=pitch_ok(real, 440 * 2 ** ((m - 69) / 12)),
                centroid=d.centroid(a), time=None)


def main():
    up, out = sys.argv[1], sys.argv[2]
    seen, files = set(), []
    for p in sorted(glob.glob(up + "/*")):
        h = hashlib.sha1((rp.load(p) * 255).astype(np.uint8).tobytes()).hexdigest()
        if h not in seen: seen.add(h); files.append(p)
    # calibrate the two readings calibration v1 lacks, on the same set
    with Pool(4) as pool:
        pre = pool.map(read, files)
    for name, vals in (("contrast", [r["f"]["contrast"] for r in pre]), ("vchange", [r["vchange"] for r in pre])):
        EXTRA[name] = [float(np.percentile(vals, p)) for p in np.linspace(0, 100, 9)]
    with Pool(4) as pool:
        res = pool.map(job, [(p, EXTRA) for p in files])
    by = {r["name"]: r for r in res}; names = sorted(by)
    rng = np.random.default_rng(5); pairs = [tuple(rng.choice(names, 2, replace=False)) for _ in range(300)]
    dom = {n: max(by[n]["weights"], key=by[n]["weights"].get) for n in names}
    # re-shot pairs: capture times within 2 minutes (EXIF), same as calib2
    from PIL import Image
    import datetime as dt
    times = []
    for p in files:
        ex = Image.open(p).getexif()
        if 306 in ex: times.append((dt.datetime.strptime(ex[306], "%Y:%m:%d %H:%M:%S"), os.path.basename(p)[:8]))
    times.sort(); near = [(a[1], b[1]) for a, b in zip(times, times[1:]) if (b[0] - a[0]).total_seconds() <= 120]
    def span(notes): return int(max(notes) - min(notes))
    nt = [by[n]["note_today"] for n in names]; nn = [by[n]["note_new"] for n in names]
    def thumb(p):
        L = rp.lum(rp.load(p)); h, w = L.shape
        L = L[: h - h % 16, : w - w % 16].reshape(16, h // 16, 16, w // 16).mean((1, 3)); L = L - L.mean()
        return L / (np.linalg.norm(L) + 1e-9)
    path = {os.path.basename(p)[:8]: p for p in files}
    sim = {(a_, b_): float((thumb(path[a_]) * thumb(path[b_])).sum()) for a_, b_ in near}
    same = [pr for pr in near if sim[pr] > 0.6]
    wdist = lambda a_, b_: 0.5 * sum(abs(by[a_]["weights"][k] - by[b_]["weights"][k]) for k in FAMILIES)
    rep = {
        "same_scene_pairs (thumbnail correlation > 0.6)": len(same),
        "same_scene_family_weight_change_median_max": [round(float(np.median([wdist(*pr) for pr in same])), 2) if same else None,
                                                       round(float(max([wdist(*pr) for pr in same])), 2) if same else None],
        "same_scene_timbre_db_median": round(float(np.median([d.timbre_dist(by[a_]["fa"], by[b_]["fa"]) for a_, b_ in same])), 2) if same else None,
        "tau": TAU,
        "images": len(names),
        "calibration_v1b_extra": EXTRA,
        "family_share_dominant": {k: round(sum(v == k for v in dom.values()) / len(names), 2) for k in FAMILIES},
        "blended_images_share": round(float(np.mean([sum(w > 0 for w in by[n]["weights"].values()) > 1 for n in names])), 2),
        "different_photos_timbre_db": round(float(np.median([d.timbre_dist(by[a]["fa"], by[b]["fa"]) for a, b in pairs])), 2),
        "different_photos_shape_db": round(float(np.median([d.shape_dist(by[a]["fa"], by[b]["fa"]) for a, b in pairs])), 2),
        "round_robin_timbre_db": round(float(np.median([d.timbre_dist(by[n]["fa"], by[n]["fb"]) for n in names])), 2),
        "round_robin_shape_db": round(float(np.median([d.shape_dist(by[n]["fa"], by[n]["fb"]) for n in names])), 2),
        "within_2min_pairs": len(near),
        "within_2min_pairs_changing_dominant_family": sum(dom[a] != dom[b] for a, b in near),
        "centroid_hz_10_50_90": [round(float(np.percentile([by[n]["centroid"] for n in names], p))) for p in (10, 50, 90)],
        "pitch": {
            "today": {"span_semitones": span(nt), "notes_used": len(set(nt)), "top_note_share": round(max(nt.count(v) for v in set(nt)) / len(nt), 2)},
            "new": {"span_semitones": span(nn), "notes_used": len(set(nn)), "top_note_share": round(max(nn.count(v) for v in set(nn)) / len(nn), 2)},
            "new_pitch_held_share": round(float(np.mean([by[n]["pitch_ok"] for n in names])), 2),
            "new_pitch_failures_by_family": {k: sum((not by[n]["pitch_ok"]) and dom[n] == k for n in names) for k in FAMILIES},
        },
        "note": "Python harness. Timbre and shape distances use the centre note (A3) so pitch cannot inflate them; pitch rows use each photo's own note.",
    }
    json.dump(rep, open(os.path.join(out, "families_report.json"), "w"), indent=1)
    json.dump({n: {"family": dom[n], "weights": by[n]["weights"], "note": by[n]["note_new"]} for n in names},
              open(os.path.join(out, "families_per_image.json"), "w"), indent=1)
    print(json.dumps(rep, indent=1))


if __name__ == "__main__":
    main()
