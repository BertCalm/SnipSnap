"""Round 5, cowbell. The owner sent the reference again after round 3, so round 3 is still not it.
What round 3 got wrong, measured: it gave every clapper strike the same spectrum. In the
reference the first two strikes mostly ring 725 Hz and 3.7 kHz, and only the third brings in
1.9 and 2.1 kHz; a strike landing on a bell that is already ringing also adds to or cancels
each note depending on where that note is in its cycle. That per-strike, per-note behaviour is
what a shaken clapper bell is.

Now: every note gets its own level and phase at every strike, solved by least squares against
the whole reference gesture. Then, per strike, band noise for what the notes leave out in that
strike's first 30 ms. Measured only; no audio copied. Python harness, not Kotlin."""
import sys, glob, json, pickle, numpy as np
from scipy import signal
import fit as F
SR = F.SR; U, OUT = sys.argv[1], sys.argv[2]
models = pickle.load(open(f"{OUT}/models.pkl", "rb")); report = json.load(open(f"{OUT}/report.json"))
x = F.readwav(glob.glob(U + '/cb423f68*cowbell_ring.wav')[0]); T0 = 0.2535
ref = x[int(T0 * SR):]; ref = ref / np.max(np.abs(ref)); STRIKES = [0.0, 0.1395, 0.3455, 0.7085]

# notes: frequencies refined on the ring after the last big strike, ring times from the round-2 fit
tail = ref[int(0.36 * SR):int(0.36 * SR) + int(2.5 * SR)]; N = 1 << 19
S = np.abs(np.fft.rfft(tail * np.hanning(len(tail)), N)); f = np.fft.rfftfreq(N, 1 / SR)
# A bell's notes come in close pairs (the shell is never perfectly round), and the pair beating is
# the slow wobble measured earlier (8.6 Hz at 722 Hz). One frequency per note explained only 45%
# of the reference; so each note keeps every peak within 18 Hz of it that is within 26 dB of its top.
notes = []; w = int(1.2 / (f[1] - f[0]))
for hz, a, T in models["COWBELL"]["partials"]:
    m = np.where((f > hz - 18) & (f < hz + 18))[0]
    cand = sorted(((S[j], j) for j in m if S[j] == S[j-w:j+w+1].max()), reverse=True)
    kept = []
    for sv, j in cand:                       # the note and at most one partner: within 20 dB, at least 2.5 Hz away
        if len(kept) == (4 if hz < 4500 else 2) or sv < cand[0][0] * 0.05: break      # up to three partners on the strong low notes, within 26 dB
        y0, y1, y2 = np.log(S[j-1:j+2] + 1e-20); fq = float(f[j] + 0.5 * (y0 - y2) / (y0 - 2 * y1 + y2) * (f[1] - f[0]))
        if all(abs(fq - q) > 2.5 for q in kept) and all(abs(fq - q[0]) > 2.5 for q in notes): kept.append(fq)
    notes += [[fq, float(T)] for fq in kept]
notes.sort()
print("notes", [round(q[0], 1) for q in notes])

def columns(n, strikes, notes, decay=1.0, tune=1.0):
    t = np.arange(n) / SR; cols = []
    for ts in strikes:
        tt = np.maximum(t - ts, 0.0); on = (t >= ts)
        for hz, T in notes:
            e = np.exp(-6.9078 * tt / (T * decay)) * on * (1 - np.exp(-tt / 0.0003))
            cols.append(e * np.sin(2 * np.pi * hz * tune * tt)); cols.append(e * np.cos(2 * np.pi * hz * tune * tt))
    return np.array(cols).T
L = int(2.2 * SR); B = columns(L, STRIKES, notes); lam = 1e-3 * np.trace(B.T @ B) / B.shape[1]; coef = np.linalg.solve(B.T @ B + lam * np.eye(B.shape[1]), B.T @ ref[:L])
ring = B @ coef
# per strike: what the notes leave out in its first 30 ms, as band noise
bands = [b for b in F.third_octaves() if b[0] >= 400]; na = int(0.030 * SR); bursts = []
for ts in STRIKES:
    a = int(ts * SR); rr = F.band_energy(ref[a:a+na], bands); ss = F.band_energy(ring[a:a+na], bands); row = []
    for (lo, hi), e in zip(bands, np.maximum(rr - ss, 0)):
        Tn = 0.025; amp = float(np.sqrt(e / np.sum(np.exp(-13.8155 * np.arange(na) / SR / Tn))))
        row.append([lo, hi, amp, Tn])
    bursts.append(row)
model = {"strikes": STRIKES, "notes": notes, "coef": coef.tolist(), "bursts": bursts}; models["COWBELL_R5"] = model

def render(seconds, velocity=1.0, take=0, fade=True):
    rng = np.random.default_rng(4000 + take); n = int(seconds * SR); v = velocity
    strikes = list(STRIKES) if take == 0 else [0.0] + [float(s * np.exp(rng.normal(0, 0.05))) for s in STRIKES[1:]]
    c = np.array(model["coef"]).reshape(len(STRIKES), len(notes), 2).copy(); hz0 = notes[0][0]
    for k in range(len(STRIKES)):
        if k == 3 and v < 0.45: c[k] = 0                                   # a gentle shake has no rebound
        for j, (hz, T) in enumerate(notes):
            g = v ** min(3.0, 1.0 + 0.45 * np.log2(hz / hz0))              # softer: upper notes fall away first
            if take: g *= float(np.exp(rng.normal(0, 0.10)))
            c[k, j] *= g
    y = columns(n, strikes, notes) @ c.reshape(-1); t = np.arange(n) / SR
    for k, ts in enumerate(strikes):
        if k == 3 and v < 0.45: continue
        tt = np.maximum(t - ts, 0.0); on = t >= ts
        for i, (lo, hi, amp, Tn) in enumerate(model["bursts"][k]):
            if amp < 1e-5: continue
            y += amp * v ** F.band_exponent(lo) * F.band_noise(lo, hi, n, 31 * (i + 1) + 977 * k + 104729 * take) * np.exp(-6.9078 * tt / Tn) * on
    if fade: fd = int(0.3 * SR); y[-fd:] *= 0.5 + 0.5 * np.cos(np.pi * np.arange(fd) / fd)
    return y

full = render(len(ref) / SR, fade=False)[:len(ref)]; r = {"round": 5}
tb = F.third_octaves(); err = {}
for nm, a, b in (("0-140ms", 0, .14), ("140-345ms", .14, .345), ("345-700ms", .345, .7), ("700-1200ms", .7, 1.2), ("1.2-2.2s", 1.2, 2.2), ("2.2-4s", 2.2, 4.0)):
    rr = F.band_energy(ref[int(a*SR):int(b*SR)], tb); ss = F.band_energy(full[int(a*SR):int(b*SR)], tb); k = rr > rr.max() * 1e-3
    err[nm] = round(float(np.sqrt(np.mean((10 * np.log10(rr[k] / np.maximum(ss[k], 1e-20))) ** 2))), 2)
r["gesture_error_dB"] = err
pk = lambda s: [round(float(np.max(np.abs(s[int(a*SR):int(b*SR)]))), 2) for a, b in ((0, .05), (.139, .19), (.345, .4), (.708, .76), (1.0, 1.1), (2.0, 2.1))]
r["strike_peaks_ref"] = pk(ref); r["strike_peaks_fit"] = pk(full)
r["waveform_match_first_2.2s"] = round(float(1 - np.sum((ref[:L] - full[:L]) ** 2) / np.sum(ref[:L] ** 2)), 3)      # 1.0 = identical
scale = 0.95 / np.max(np.abs(full)); cents = {}; takes = {}
for name, v in {"soft": 0.3, "mid": 0.6, "hard": 1.0}.items():
    for take in range(4):
        y = render(1.45, v, take) * scale; F.writewav(f"{OUT}/COWBELL_{name}_rr{take+1}.wav", y)
        if take == 0: cents[name] = round(F.centroid(y[:int(0.03 * SR)]))
        if name == "hard": takes[take] = y
r["velocity_brightness_rise_pct"] = round(100 * (cents["hard"] / cents["soft"] - 1))
d = [F.third_octave_error(takes[a], takes[b])["attack"] for a in range(4) for b in range(a + 1, 4)]
r["round_robin_attack_dB"] = [round(min(d), 2), round(max(d), 2)]
r["deterministic"] = bool(np.array_equal(render(1.45, 1.0, 2), render(1.45, 1.0, 2))); r["finite"] = bool(np.all(np.isfinite(full)))
F.writewav(f"{OUT}/_COWBELL_full_ring.wav", full * scale)
report["COWBELL"] = r; print(json.dumps(r))
print("level of each note at each strike (rows = strikes):")
c = np.array(model["coef"]).reshape(len(STRIKES), len(notes), 2); mag = np.hypot(c[..., 0], c[..., 1]); mag /= mag.max()
for k in range(len(STRIKES)): print(f"  {int(STRIKES[k]*1000):4d}ms", [(round(notes[j][0]), round(float(mag[k][j]), 2)) for j in np.argsort(-mag[k])[:6]])
json.dump(report, open(f"{OUT}/report.json", "w"), indent=1); pickle.dump(models, open(f"{OUT}/models.pkl", "wb"))
