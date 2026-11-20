"""Round 2: the three voices the owner flagged, each for a diagnosed reason.

CLAPPER  Round 1 fitted the binzasara as ONE strike. The reference is about 21 slat clicks in
         185 ms, 6 ms apart, swelling for 35 ms and then falling away. Now: one short slat
         click, dealt as a cascade with that timing and that swell.
COWBELL  Round 1's least-squares solve gave the short high partials (3.7, 5.7, 6.2 kHz) up to
         full level, where the spectrum has them 12 to 45 dB down, and let noise bands ring for
         3 s. That is a harsh clank over a hiss. Now: each partial's level is read from its own
         band's decay line, more partials are kept (the bell is its partials), and noise is
         limited to the strike itself.
ODAIKO   Round 1 fitted a hit cut from a dense loop: four notes between 78 and 167 Hz ringing
         3 to 4 s with no stick attack. That is the mud. Now: the nagado's clean stroke (which
         the owner did not flag) scaled down to 78 Hz with a longer ring, keeping its stick
         attack where a stick's attack is.
Python harness, not Kotlin.
"""
import sys, glob, json, pickle, shutil, os, numpy as np
import fit as F
SR = F.SR
U = sys.argv[1]; OUT = sys.argv[2]
ref = lambda k: F.readwav([p for p in glob.glob(U + '/*.wav') if k in p][0])
models = pickle.load(open(f"{OUT}/models.pkl", "rb")); report = json.load(open(f"{OUT}/report.json"))

# ---------------------------------------------------------------- ODAIKO
ratio = 78.0 / models["NAGADO"]["partials"][0][0]
ng = models["NAGADO"]
od = {"width_ms": 1.6, "top": ng["top"], "coef": list(ng["coef"]),
      # the two head notes ring 1.8x longer on a bigger drum; the upper notes 1.3x
      "partials": [[hz * ratio, a, t * (1.8 if hz < 150 else 1.3)] for hz, a, t in ng["partials"]],
      # the thud under 250 Hz moves down with the drum; the stick's attack stays where it was
      "bands": [[lo * ratio, hi * ratio, a, T] if lo < 250 else [lo, hi, a * 0.8, T] for lo, hi, a, T in ng["bands"] if lo > 70]}
for k, (hz, a, t) in enumerate(ng["partials"]):
    if hz >= 150: od["coef"][2*k] *= 2.5; od["coef"][2*k+1] *= 2.5      # +8 dB: 95% of the first try sat under 120 Hz
models["ODAIKO"] = od

# ---------------------------------------------------------------- COWBELL
seg, t0, gap = F.cut_hit(ref("cowbell_ring"), 0.60, max_len=4.0)
def ring_partials(seg, floor_db=-50, n_max=24):
    N = 1 << 18; S = np.abs(np.fft.rfft(seg * np.hanning(len(seg)), N)); f = np.fft.rfftfreq(N, 1 / SR); db = 20 * np.log10(S / S.max() + 1e-12)
    w = int(15 / (f[1] - f[0])); pk = [(db[j], f[j]) for j in range(w, len(S) - w) if f[j] > 100 and db[j] > floor_db and S[j] == S[j-w:j+w+1].max()]
    pk = sorted(pk, reverse=True)[:n_max]
    # drop a weaker peak sitting in a much stronger neighbour's skirt (762 beside 722, 1967 beside 1927)
    pk = [(d, fq) for d, fq in pk if not any(abs(fq - f2_) < 60 and d2 > d + 25 for d2, f2_ in pk)]
    win, hp = 2048, 256; fr = np.array([seg[k:k+win] for k in range(0, len(seg) - win, hp)])
    M = np.abs(np.fft.rfft(fr * np.hanning(win), axis=1)); f2 = np.fft.rfftfreq(win, 1 / SR); out = []; L = len(seg) / SR
    for d, fq in pk:
        b = int(round(fq / (f2[1] - f2[0]))); e = 20 * np.log10(M[:, max(0, b-1):b+2].max(axis=1) + 1e-12)
        k0 = int(np.argmax(e[:20])) + 3; k1 = k0                      # past the strike's own broadband click
        while k1 < len(e) - 1 and e[k1] > e[k0] - 30: k1 += 1
        if k1 - k0 < 4: continue
        slope = np.polyfit(np.arange(k0, k1) * hp / SR, e[k0:k1], 1)[0]
        if slope > -3: continue
        T = float(np.clip(-60 / slope, 0.05, 6.0)); tau = T / 6.9078
        # a spectrum peak grows with how long the partial rings, so divide that out to get its level at the strike
        out.append([float(fq), float(10 ** (d / 20) / (tau * (1 - np.exp(-L / tau)))), T])
    return sorted(out)
cp = ring_partials(seg)
n = len(seg); t = np.arange(n) / SR; width = 0.2; RISE = 5.0
# the reference bell peaks 17 ms after the strike: its notes bloom, they do not start at full level.
# Starting them at full level put 17 dB too much below 500 Hz into the attack (a click).
bloom = 1.0 - np.exp(-t / (RISE * 1e-3))
def bell(parts): return sum(np.convolve(a * np.exp(-6.9078 * t / T) * bloom * np.sin(2 * np.pi * hz * t), F.contact(width))[:n] for hz, a, T in parts)
body = bell(cp)
g = np.sqrt(np.sum(seg[int(.03*SR):int(.5*SR)] ** 2) / np.sum(body[int(.03*SR):int(.5*SR)] ** 2))     # one level for the whole bell
cp = [[hz, a * g, T] for hz, a, T in cp]
# then each third-octave band's partials are levelled to the reference's body window (30-300 ms)
tb = F.third_octaves(); n0 = int(F.ATT * SR); n1 = int(F.BODY * SR)
rbody = F.band_energy(seg[n0:n1], tb); sbody = F.band_energy(bell(cp)[n0:n1], tb)
for (lo, hi), rr, ss in zip(tb, rbody, sbody):
    if ss > 0 and rr > rbody.max() * 1e-5:
        k = float(np.clip(np.sqrt(rr / ss), 0.4, 8.0))
        for p in cp:
            if lo <= p[0] < hi: p[1] *= k
body = bell(cp); g = 1.0
coef = []
for hz, a, T in cp: coef += [a, 0.0]
bands = F.third_octaves(); na = int(F.ATT * SR); nb = int(F.BODY * SR) - na; tab = []
ra = F.band_energy(seg[:na], bands); rb = F.band_energy(seg[na:na+nb], bands)
pa = F.band_energy(body[:na] * g, bands); pb = F.band_energy(body[na:na+nb] * g, bands)
for (lo, hi), ea, eb in zip(bands, np.maximum(ra - pa, 0), np.maximum(rb - pb, 0)):
    a, T = F.solve_band(ea, max(eb, ea * 1e-6), na, nb)
    if a > 1e-5: tab.append([lo, hi, a, min(T, 0.30)])               # noise may fill the clank, never the long ring
models["COWBELL"] = {"rise_ms": RISE, "width_ms": width, "top": max(a for _, a, _ in cp) * g, "coef": coef, "partials": cp, "bands": tab}

# ---------------------------------------------------------------- CLAPPER
cref = ref("binzasara_p"); cseg, _, _ = F.cut_hit(cref, None)
old = models["CLAPPER"]
slat = {"width_ms": 0.12, "top": old["top"], "coef": list(old["coef"]),
        "partials": [[hz, a, 0.035] for hz, a, _ in old["partials"]],          # one slat: a 35 ms wooden click
        "bands": [[lo, hi, a, 0.012] for lo, hi, a, _ in old["bands"]]}
def swell(ms):          # measured: rises to its peak at 35 ms, down to a tenth by 185 ms
    return (0.35 + 0.65 * ms / 35.0) if ms < 35 else float(np.exp(-(ms - 35) / 62.0))
TIMES = [1, 5, 9, 25, 27, 33, 38, 42, 47, 53, 65, 80, 96, 104, 133, 143, 158, 162, 174, 180, 184]      # ms, measured
LEVELS = [.26, .46, .59, .55, .5, .72, .52, .55, .69, .49, .61, .48, .33, .29, .38, .2, .29, .29, .08, .06, .08]
def cascade(seconds, velocity=1.0, take=0):
    """The slats fall in the measured order. A softer throw is slower and drops the quietest clicks;
    another take keeps the shape and moves every click a little."""
    rng = np.random.default_rng(500 + take); n = int(seconds * SR); y = np.zeros(n)
    stretch = 1.0 + 0.35 * (1 - velocity)
    for i, (ms, lv) in enumerate(zip(TIMES, LEVELS)):
        if lv < 0.35 * (1 - velocity): continue
        if take: ms = ms * float(np.exp(rng.normal(0, 0.06))) + rng.normal(0, 1.2); lv = lv * float(np.exp(rng.normal(0, 0.2)))
        h = F.render(slat, 0.09, min(1.0, velocity ** 0.6 * lv / 0.72), take * 31 + i + 1)
        p = max(0, int(ms * stretch * 1e-3 * SR)); y[p:p + len(h)] += h[: n - p]
    return y
models["CLAPPER_SLAT"] = slat

# ---------------------------------------------------------------- render, measure, write
os.makedirs(f"{OUT}/../out_r1", exist_ok=True)
layers = {"soft": 0.3, "mid": 0.6, "hard": 1.0}
def renderer(voice):
    secs = F.VOICES[voice][3]
    return (lambda v, take: cascade(secs, v, take)) if voice == "CLAPPER" else (lambda v, take: F.render(models[voice], secs, v, take))
refs = {"COWBELL": seg, "CLAPPER": cseg}
for voice in ("ODAIKO", "COWBELL", "CLAPPER"):
    rd = renderer(voice); full = rd(1.0, 0); scale = 0.95 / np.max(np.abs(full)); r = report[voice]; cents = {}; takes = {}
    for name, v in layers.items():
        for take in range(4):
            fn = f"{OUT}/{voice}_{name}_rr{take+1}.wav"
            if not os.path.exists(f"{OUT}/../out_r1/{os.path.basename(fn)}"): shutil.copy(fn, f"{OUT}/../out_r1/")
            y = rd(v, take) * scale; F.writewav(fn, y)
            if take == 0: cents[name] = round(F.centroid(y[:int(0.03 * SR)]))
            if name == "hard": takes[take] = y
    if voice in refs:
        rs = refs[voice]; m = min(len(rs), len(full)); r["third_octave_error_dB"] = F.third_octave_error(rs[:m], full[:m] / np.max(np.abs(full[:m])) * np.max(np.abs(rs[:m])))
    else: r["third_octave_error_dB"] = None
    r["velocity_attack_centroid_hz"] = cents; r["velocity_brightness_rise_pct"] = round(100 * (cents["hard"] / cents["soft"] - 1))
    d = [F.third_octave_error(takes[a], takes[b])["attack"] for a in range(4) for b in range(a + 1, 4)]
    r["round_robin_attack_dB"] = [round(min(d), 2), round(max(d), 2)]
    S = np.abs(np.fft.rfft(full)) ** 2; f = np.fft.rfftfreq(len(full), 1 / SR)
    r["share_under_120Hz"] = round(float(S[f < 120].sum() / S.sum()), 2); r["share_120_500Hz"] = round(float(S[(f >= 120) & (f < 500)].sum() / S.sum()), 2)
    r["share_above_2kHz"] = round(float(S[f >= 2000].sum() / S.sum()), 2)
    env = np.abs(full); pk = env.max(); last = np.where(env > pk * 10 ** (-40 / 20))[0][-1]; r["seconds_to_minus_40dB"] = round(last / SR, 2)
    r["deterministic"] = bool(np.array_equal(rd(1.0, 2), rd(1.0, 2))); r["round"] = 2
    print(voice, json.dumps({k: r[k] for k in ("third_octave_error_dB", "velocity_brightness_rise_pct", "round_robin_attack_dB", "share_under_120Hz", "share_120_500Hz", "share_above_2kHz", "seconds_to_minus_40dB", "deterministic")}))
print("cowbell partials", [(round(h), round(float(a * g / models['COWBELL']['top']), 3), round(T, 2)) for h, a, T in cp])

# rolls and the phrase again, with the new voices
def build(rd, L, secs, a, b):
    y = np.zeros(int((secs + L) * SR)); tt = 0.0; i = 0
    while tt < secs:
        x = tt / secs; h = rd(round(0.25 + 0.75 * x ** 1.4, 2), i % 4); p = int(tt * SR); y[p:p+len(h)] += h[: len(y) - p]
        tt += 1 / (a * (b / a) ** x); i += 1
    return y / np.max(np.abs(y)) * 0.95
F.writewav(f"{OUT}/_ODAIKO_roll_build.wav", build(renderer("ODAIKO"), 1.45, 4.0, 4.0, 12.0))
F.writewav(f"{OUT}/_CLAPPER_roll_build.wav", build(renderer("CLAPPER"), 0.5, 2.5, 2.0, 7.0))
step = 60 / 95 / 4; mix = np.zeros(int((step * 64 + 2) * SR))
pat = [("ODAIKO", 1.0, 0), ("KA", .6, 2), ("KA", .3, 3), ("NAGADO", .7, 4), ("SHIME", 1.0, 6), ("KANE", .6, 6), ("ODAIKO", .8, 8), ("NAGADO", .9, 10),
       ("KA", .6, 11), ("SHIME", .6, 12), ("SHIME", 1.0, 13), ("KANE_EDGE", .5, 14), ("SHIME", .6, 14), ("KA", 1.0, 15)]
lvl = {"KANE": .35, "KANE_EDGE": .25, "CHAPPA": .3, "CLAPPER": .35, "KA": .6, "SHIME": .7, "COWBELL": .4}
norm = {v: np.max(np.abs(renderer(v)(1.0, 0))) for v in F.VOICES}
for bar in range(4):
    hits = pat + ([("CHAPPA", .7, 0)] if bar == 2 else []) + ([("CLAPPER", .8, 12), ("COWBELL", .6, 8)] if bar == 3 else []) + ([("WATER", .7, 7), ("WATER", .5, 9), ("COWBELL", .5, 12)] if bar == 1 else [])
    for k, (voice, v, s) in enumerate(hits):
        h = renderer(voice)(v, (k + bar) % 4) / norm[voice] * lvl.get(voice, 1.0); p = int((bar * 16 + s) * step * SR); mix[p:p+len(h)] += h
F.writewav(f"{OUT}/_demo_phrase_95bpm.wav", mix / np.max(np.abs(mix)) * 0.95)
json.dump(report, open(f"{OUT}/report.json", "w"), indent=1); pickle.dump(models, open(f"{OUT}/models.pkl", "wb"))
