"""Japanese percussion, round 1: each voice fitted to one clean reference hit, the way TERRA's
fitted hand drums were (docs/TERRA_HANDS.md, "measured partials plus a fitted residual").

For each voice:
  1. Find the reference hit with the longest clear gap after it and cut it out.
  2. Partials: the strongest spectral peaks, each with a ring time (T60) read from how fast its
     band falls, and a starting amplitude solved by least squares against the waveform.
  3. Residual: what the partials leave out. In each third-octave band, noise with its own level
     and decay, solved so the band's energy matches the reference in an attack window
     (0-30 ms) and a body window (30-300 ms). Never louder than the reference.
  4. Render: partials + residual. Velocity drives the partials as v and each noise band by an
     exponent that climbs with frequency (TerraFitted's law), so a soft hit is darker, not just
     quieter. Round-robin takes re-draw the noise and nudge the strike.

The output tables are in TerraFitted's shape (Partial(hz, amp, t60), Band(lo, hi, a, t)) so the
picked voices port to Kotlin as data. References are measured only; no audio is copied.
Python harness, not Kotlin.
"""
import sys, os, glob, json, struct, numpy as np
from scipy import signal
SR = 44100
ATT, BODY = 0.030, 0.300

def readwav(f):
    b = open(f, 'rb').read(); i = 12
    while i + 8 <= len(b):
        cid = b[i:i+4]; sz = struct.unpack('<I', b[i+4:i+8])[0]
        if cid == b'fmt ': fmt = struct.unpack('<HHIIHH', b[i+8:i+24])
        if cid == b'data': data = b[i+8:i+8+sz]
        i += 8 + sz + (sz & 1)
    tag, ch, sr, _, _, bits = fmt
    if bits == 16: x = np.frombuffer(data[:len(data)//2*2], '<i2').astype(float)
    elif bits == 24:
        a = np.frombuffer(data[:len(data)//3*3], np.uint8).reshape(-1, 3).astype(np.int32)
        x = (a[:, 0] | (a[:, 1] << 8) | (a[:, 2] << 16)); x = np.where(x >= 1 << 23, x - (1 << 24), x).astype(float)
    else: x = np.frombuffer(data[:len(data)//4*4], '<f4').astype(float)
    assert sr == SR
    x = x[:len(x)//ch*ch].reshape(-1, ch).mean(axis=1)
    return x / np.max(np.abs(x))

def writewav(path, x):
    y = np.clip(x, -1, 1); d = (y * 32767).astype('<i2').tobytes()
    open(path, 'wb').write(b'RIFF' + struct.pack('<I', 36 + len(d)) + b'WAVEfmt ' + struct.pack('<IHHIIHH', 16, 1, 1, SR, SR * 2, 2, 16) + b'data' + struct.pack('<I', len(d)) + d)

def cut_hit(x, at=None, max_len=1.6):
    """The hit with the longest clear gap after it (or the one nearest `at` seconds)."""
    hop = 128; n = len(x) // hop
    env = np.array([np.sqrt(np.mean(x[i*hop:(i+1)*hop]**2)) for i in range(n)])
    de = np.diff(np.log10(env + 1e-4))
    on = [i for i in range(1, len(de) - 1) if de[i] > 0.3 and de[i] >= de[i-1] and de[i] > de[i+1]]
    m = []
    for i in on:
        if not m or (i - m[-1]) * hop / SR > 0.03: m.append(i)
    if not m: m = [0]
    ends = m[1:] + [n]
    gaps = [((e - s) * hop / SR, s) for s, e in zip(m, ends)]
    if at is None:
        # the loudest quarter of hits only, then the longest gap among them
        pk = [np.max(np.abs(x[s*hop:s*hop + 2048])) for _, s in gaps]
        thr = np.percentile(pk, 60)
        g, s = max((gp for gp, p in zip(gaps, pk) if p >= thr), key=lambda t: t[0])
    else:
        g, s = min(gaps, key=lambda t: abs(t[1] * hop / SR - at))
    a = max(0, s * hop - 512); seg = x[a:a + int(min(g, max_len) * SR) + 512]
    pk = np.max(np.abs(seg)); first = int(np.argmax(np.abs(seg) > 0.08 * pk))
    seg = seg[max(0, first - 44):]                         # 1 ms before the strike
    return seg / np.max(np.abs(seg)), s * hop / SR, g

def find_partials(seg, n_max=14):
    N = 1 << int(np.ceil(np.log2(len(seg))) + 1)
    S = np.abs(np.fft.rfft(seg * np.hanning(len(seg)), N)); f = np.fft.rfftfreq(N, 1 / SR)
    db = 20 * np.log10(S / S.max() + 1e-12)
    w = max(2, int(10 / (f[1] - f[0]))); pk = []
    for j in range(w, len(S) - w):
        if 35 < f[j] < 16000 and db[j] > -38 and S[j] == S[j-w:j+w+1].max(): pk.append((db[j], f[j]))
    pk = sorted(sorted(pk, reverse=True)[:n_max], key=lambda p: p[1])
    # ring time: slope of each partial's band level over time
    win = 2048 if len(seg) > 6000 else 512; hp = win // 8
    fr = np.array([seg[k:k+win] for k in range(0, len(seg) - win, hp)])
    M = np.abs(np.fft.rfft(fr * np.hanning(win), axis=1)); f2 = np.fft.rfftfreq(win, 1 / SR)
    out = []
    for d, fq in pk:
        b = int(round(fq / (f2[1] - f2[0]))); e = 20 * np.log10(M[:, max(0, b-1):b+2].sum(axis=1) + 1e-12)
        k0 = int(np.argmax(e)); k1 = k0
        while k1 < len(e) - 1 and e[k1] > e[k0] - 30: k1 += 1
        t60 = 0.25
        if k1 - k0 >= 3:
            slope = np.polyfit(np.arange(k0, k1) * hp / SR, e[k0:k1], 1)[0]     # dB per second
            if slope < -1: t60 = float(np.clip(-60 / slope, 0.03, 4.0))
        out.append([float(fq), 1.0, t60])
    return out

def contact(width_ms):
    w = max(3, int(width_ms * 1e-3 * SR)); p = np.sin(np.pi * np.arange(w) / w); return p / p.sum()

def partial_basis(partials, n, width_ms, tune=1.0, decay=1.0, rise_ms=0.0):
    t = np.arange(n) / SR; p = contact(width_ms); cols = []
    bloom = 1.0 - np.exp(-t / (rise_ms * 1e-3)) if rise_ms > 0 else 1.0      # a bell blooms; a drum head does not
    for hz, _, t60 in partials:
        e = np.exp(-6.9078 * t / (t60 * decay)) * bloom
        cols.append(np.convolve(e * np.sin(2 * np.pi * hz * tune * t), p)[:n])
        cols.append(np.convolve(e * np.cos(2 * np.pi * hz * tune * t), p)[:n])
    return np.array(cols).T

def third_octaves(lo=50.0, hi=16000.0):
    edges = [lo]
    while edges[-1] < hi: edges.append(edges[-1] * 2 ** (1 / 3))
    return list(zip(edges[:-1], edges[1:]))

def band_energy(x, bands):
    X = np.abs(np.fft.rfft(x)) ** 2 * 2 / len(x); f = np.fft.rfftfreq(len(x), 1 / SR)
    return np.array([X[(f >= a) & (f < b)].sum() for a, b in bands])

def solve_band(e_att, e_body, n_att, n_body):
    """Level a and decay T of unit-RMS noise times a*exp(-6.9 t/T) with these two window energies."""
    if e_att <= 0: return 0.0, 0.05
    ta = np.arange(n_att) / SR; tb = (n_att + np.arange(n_body)) / SR
    def ratio(T): return np.sum(np.exp(-13.8155 * tb / T)) / np.sum(np.exp(-13.8155 * ta / T))
    lo, hi = 0.004, 3.0; target = e_body / e_att
    for _ in range(50):
        mid = (lo * hi) ** 0.5
        if ratio(mid) < target: lo = mid
        else: hi = mid
    T = (lo * hi) ** 0.5
    return float(np.sqrt(e_att / np.sum(np.exp(-13.8155 * ta / T)))), float(T)

_sos = {}
def band_noise(lo, hi, n, seed):
    key = (round(lo, 2), round(hi, 2))
    if key not in _sos: _sos[key] = signal.butter(4, [lo / (SR / 2), min(hi, SR / 2 * 0.95) / (SR / 2)], 'band', output='sos')
    x = signal.sosfilt(_sos[key], np.random.default_rng(seed).standard_normal(n + 2048))[2048:]
    return x / (np.sqrt(np.mean(x ** 2)) + 1e-12)

def band_exponent(lo):
    """TerraFitted's velocity law: low thud falls as v^0.5, then climbs from v^0.75 to v^3 at 8 kHz."""
    if lo < 250: return 0.5
    return float(np.clip(0.75 + 2.25 * np.log2(lo / 250) / np.log2(8000 / 250), 0.75, 3.0))

def fit(seg, width_ms):
    n = len(seg); partials = find_partials(seg)
    B = partial_basis(partials, n, width_ms)
    coef = np.linalg.lstsq(B, seg, rcond=None)[0]
    body = B @ coef
    for k, p in enumerate(partials): p[1] = float(np.hypot(coef[2*k], coef[2*k+1]))
    top = max(p[1] for p in partials)
    bands = third_octaves(); na = int(ATT * SR); nb = min(n, int(BODY * SR)) - na
    ra = band_energy(seg[:na], bands); rb = band_energy(seg[na:na+nb], bands)      # band_energy is already a sum of squares
    pa = band_energy(body[:na], bands); pb = band_energy(body[na:na+nb], bands)
    table = []
    for (lo, hi), ea, eb in zip(bands, np.maximum(ra - pa, 0), np.maximum(rb - pb, 0)):
        a, T = solve_band(ea, max(eb, ea * 1e-6), na, nb)
        if a > 1e-5: table.append([lo, hi, a, T])
    return {"partials": partials, "coef": coef.tolist(), "bands": table, "width_ms": width_ms, "top": top}

def render(model, seconds, velocity=1.0, take=0, tune=0.5, decay=0.5, force=0.5):
    n = int(seconds * SR); v = velocity
    rng = np.random.default_rng(1000 + take)
    j = (lambda s: 1.0) if take == 0 else (lambda s: float(np.exp(rng.normal(0, s))))
    tr = 2 ** ((tune - 0.5) * 10 / 12); dc = 2 ** ((decay - 0.5) * 2)
    width = model["width_ms"] * 2 ** ((0.5 - force) * 2) * j(0.10) + (1 - v) * 0.6
    parts = [[hz, a, t] for hz, a, t in model["partials"]]
    B = partial_basis(parts, n, width, tr, dc, model.get("rise_ms", 0.0))
    coef = np.array(model["coef"]).copy()
    hz0 = parts[0][0]
    for k in range(len(parts)):
        # a softer strike drives the upper notes less than the lowest one (the tilt TERRA's
        # StrikeVelocity gives a modal drum), so velocity changes a drum with little noise too
        coef[2*k:2*k+2] *= j(0.09) * v ** min(3.0, 1.0 + 0.45 * np.log2(parts[k][0] / hz0))
    out = B @ coef
    t = np.arange(n) / SR
    for i, (lo, hi, a, T) in enumerate(model["bands"]):
        if lo * tr > SR / 2 * 0.9: continue
        out += a * j(0.06) * v ** band_exponent(lo) * band_noise(lo * tr, hi * tr, n, 7919 * (i + 1) + 104729 * take) * np.exp(-6.9078 * t / (T * dc))
    fade = int(0.02 * SR); out[-fade:] *= np.linspace(1, 0, fade)
    return out

def third_octave_error(ref, syn):
    bands = third_octaves(); na = int(ATT * SR); nb = min(len(ref), len(syn), int(BODY * SR)) - na; res = {}
    for name, a, b in (("attack", 0, na), ("body", na, na + nb)):
        r = band_energy(ref[a:b], bands); s = band_energy(syn[a:b], bands)
        keep = r > r.max() * 1e-4                                   # bands within 40 dB of the loudest
        res[name] = round(float(np.sqrt(np.mean((10 * np.log10(r[keep] / np.maximum(s[keep], 1e-20))) ** 2))), 2)
    return res

def centroid(x):
    S = np.abs(np.fft.rfft(x * np.hanning(len(x)))); f = np.fft.rfftfreq(len(x), 1 / SR); return float((S * f).sum() / S.sum())

# voice: (reference file key, hit time or None for auto, contact ms, rendered seconds)
VOICES = {
    "ODAIKO":    ("taiko_clicks_kamo_pitched", None, 1.6, 1.45),
    "NAGADO":    ("taiko_ensemble_arida", 7.57, 1.2, 1.2),
    "SHIME":     ("taiko_small_arida", 6.12, 0.5, 0.8),
    "SHIME_B":   ("taiko_small_agano", 7.98, 0.5, 0.8),
    "KA":        ("taiko_clicks_kaizu", 4.27, 0.25, 0.5),
    "KANE":      ("atarigane_tahara", 4.69, 0.15, 1.0),
    "KANE_EDGE": ("atarigane_tainai", 2.66, 0.15, 0.6),
    "CHAPPA":    ("chappa_ukiha", 9.35, 0.15, 1.45),
    "CLAPPER":   ("binzasara_p", None, 0.15, 0.5),
    "COWBELL":   ("cowbell_ring", 0.60, 0.2, 1.45),
    "WATER":     ("water_drum_ten", None, 0.6, 0.8),
}

if __name__ == "__main__":
    refdir, out = sys.argv[1], sys.argv[2]; os.makedirs(out, exist_ok=True); report = {}; models = {}
    for voice, (key, at, width, secs) in VOICES.items():
        x = readwav([p for p in glob.glob(os.path.join(refdir, '*.wav')) if key in p][0])
        seg, t0, gap = cut_hit(x, at)
        model = fit(seg, width); models[voice] = model
        full = render(model, max(secs, len(seg) / SR))
        scale = 0.95 / np.max(np.abs(full))
        r = {"reference": key, "hit_at_s": round(t0, 2), "clear_gap_s": round(gap, 2), "partials": len(model["partials"]), "bands": len(model["bands"]),
             "lowest_partial_hz": round(model["partials"][0][0], 1), "third_octave_error_dB": third_octave_error(seg, full[:len(seg)]),
             "centroid_ref_hz": round(centroid(seg)), "centroid_fit_hz": round(centroid(full[:len(seg)]))}
        layers = {"soft": 0.3, "mid": 0.6, "hard": 1.0}; cents = {}; takes = {}
        for name, v in layers.items():
            for take in range(4):
                y = render(model, secs, v, take) * scale; writewav(f"{out}/{voice}_{name}_rr{take+1}.wav", y)
                if take == 0: cents[name] = round(centroid(y[:int(0.03 * SR)]))        # first 30 ms, as TERRA's velocity test reads it
                if name == "hard": takes[take] = y
        r["velocity_attack_centroid_hz"] = cents
        r["velocity_brightness_rise_pct"] = round(100 * (cents["hard"] / cents["soft"] - 1))
        d = [third_octave_error(takes[a], takes[b])["attack"] for a in range(4) for b in range(a + 1, 4)]
        r["round_robin_attack_dB"] = [round(min(d), 2), round(max(d), 2)]
        r["deterministic"] = bool(np.array_equal(render(model, secs, 1.0, 2), render(model, secs, 1.0, 2)))
        r["finite"] = bool(np.all(np.isfinite(full)))
        report[voice] = r
        print(voice, json.dumps(r))
    # authored builds and a phrase, made from the fitted hits (takes alternate like two hands, tails overlap)
    def build(voice, secs, a, b):
        m = models[voice]; L = VOICES[voice][3]; y = np.zeros(int((secs + L) * SR)); t = 0.0; i = 0
        while t < secs:
            x = t / secs; h = render(m, L, round(0.25 + 0.75 * x ** 1.4, 2), i % 4); p = int(t * SR); y[p:p+len(h)] += h
            t += 1 / (a * (b / a) ** x); i += 1
        return y / np.max(np.abs(y)) * 0.95
    for voice, secs, a, b in (("ODAIKO", 4.0, 4.0, 12.0), ("NAGADO", 4.0, 5.0, 14.0), ("SHIME", 3.0, 6.0, 18.0), ("CHAPPA", 3.0, 3.0, 10.0), ("CLAPPER", 2.5, 8.0, 20.0)):
        writewav(f"{out}/_{voice}_roll_build.wav", build(voice, secs, a, b))
    step = 60 / 95 / 4; mix = np.zeros(int((step * 64 + 2) * SR))
    pat = [("ODAIKO", 1.0, 0), ("KA", .6, 2), ("KA", .3, 3), ("NAGADO", .7, 4), ("SHIME", 1.0, 6), ("KANE", .6, 6), ("ODAIKO", .8, 8), ("NAGADO", .9, 10),
           ("KA", .6, 11), ("SHIME", .6, 12), ("SHIME", 1.0, 13), ("KANE_EDGE", .5, 14), ("SHIME", .6, 14), ("KA", 1.0, 15)]
    lvl = {"KANE": .35, "KANE_EDGE": .25, "CHAPPA": .3, "CLAPPER": .3, "KA": .6, "SHIME": .7}
    for bar in range(4):
        hits = pat + ([("CHAPPA", .7, 0)] if bar == 2 else []) + ([("CLAPPER", .8, 12), ("COWBELL", .5, 8)] if bar == 3 else []) + ([("WATER", .7, 7), ("WATER", .5, 9)] if bar == 1 else [])
        for k, (voice, v, s) in enumerate(hits):
            m = models[voice]; h = render(m, VOICES[voice][3], v, (k + bar) % 4); h = h / max(1e-9, np.max(np.abs(render(m, VOICES[voice][3])))) * lvl.get(voice, 1.0)
            p = int((bar * 16 + s) * step * SR); mix[p:p+len(h)] += h
    writewav(f"{out}/_demo_phrase_95bpm.wav", mix / np.max(np.abs(mix)) * 0.95)
    json.dump(report, open(f"{out}/report.json", "w"), indent=1)
    json.dump({k: {"width_ms": m["width_ms"], "partials": [[round(h, 2), round(a / m["top"], 5), round(t, 3)] for h, a, t in m["partials"]],
                   "bands": [[round(l, 2), round(h, 2), float(f"{a:.5g}"), round(T, 3)] for l, h, a, T in m["bands"]]} for k, m in models.items()},
              open(f"{out}/tables.json", "w"), indent=1)
    import pickle; pickle.dump(models, open(f"{out}/models.pkl", "wb"))
