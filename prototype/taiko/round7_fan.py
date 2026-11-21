"""Round 7: the fan. Measured, the reference's events are not a struck drum (no ringing note) and not
continuous noise: each is a burst of short flaps with a flat spectrum from 400 Hz to 2 kHz.
  FAN        the slap: flaps at the measured times (3 to 106 ms) and levels, dying away
  FAN_SWEEP  the swish: sixteen flaps over 123 ms that swell to their peak at 66 ms
Each flap is band noise (third octaves, a short decay) plus the three spectral peaks of the event as
short notes; the band levels are then solved so the whole event's third-octave spectrum is the
reference's. Measured only; no audio copied. Python harness, not Kotlin."""
import sys, glob, json, pickle, numpy as np
import fit as F
SR = F.SR; OUT = sys.argv[1]
x = F.readwav(glob.glob('/root/.claude/uploads/b406b698-4caa-5435-bf72-6fa71346ad9f/*fan_yabu.wav')[0])
models = pickle.load(open(f"{OUT}/models.pkl", "rb")); report = json.load(open(f"{OUT}/report.json"))
FLAP_T = 0.065   # chosen from 4 to 130 ms: the closest third-octave fit of both gestures
EVENTS = {
    "FAN": (0.95, [3, 11, 16, 26, 32, 39, 43, 53, 65, 83, 101, 106], [0.81, 1.0, 0.5, 0.31, 0.43, 0.33, 0.34, 0.27, 0.21, 0.22, 0.25, 0.21]),
    "FAN_SWEEP": (8.94, [13, 22, 29, 37, 45, 53, 58, 66, 70, 76, 88, 95, 99, 110, 118, 123], [0.55, 0.32, 0.18, 0.57, 0.26, 0.49, 0.89, 1.0, 0.87, 0.71, 0.74, 0.92, 0.54, 0.37, 0.35, 0.31]),
}
bands = [b for b in F.third_octaves(160, 16000)]
def cut(at, secs):
    s = int((at - 0.01) * SR); seg = x[s:s + int(secs * SR)]; a = np.abs(seg)
    first = int(np.argmax(a > 0.08 * a.max())); seg = seg[max(0, first - 44):]
    return seg / np.max(np.abs(seg))
def build(name, gains, notes):
    at, times, levels = EVENTS[name]; t0 = times[0]
    hits = []
    for ms, lv in zip(times, levels):
        hits.append(dict(time=(ms - t0) * 1e-3, sin=[a * lv for _, a, _ in notes], cos=[0.0] * len(notes),
                         bands=[[lo, hi, g * lv, FLAP_T] for (lo, hi), g in zip(bands, gains)]))
    return dict(width=0.0, rise=0.0, hz=[h for h, _, _ in notes], t60=[T for _, _, T in notes], hits=hits)
def render(s, secs, seed=0):
    n = int(secs * SR); t = np.arange(n) / SR; y = np.zeros(n)
    for k, h in enumerate(s["hits"]):
        tt = np.maximum(t - h["time"], 0); on = t >= h["time"]
        for hz, T, a in zip(s["hz"], s["t60"], h["sin"]): y += a * np.exp(-6.9078 * tt / T) * on * np.sin(2 * np.pi * hz * tt)
        for i, (lo, hi, a, T) in enumerate(h["bands"]):
            y += a * F.band_noise(lo, hi, n, 31 * i + 977 * k + seed) * np.exp(-6.9078 * tt / T) * on
    return y
for name, (at, times, levels) in EVENTS.items():
    ref = cut(at, 0.2)[:int(0.2 * SR)]
    N = 1 << 15; S = np.abs(np.fft.rfft(ref * np.hanning(len(ref)), N)); f = np.fft.rfftfreq(N, 1 / SR)
    w = int(20 / (f[1] - f[0])); pk = sorted([(S[j], f[j]) for j in range(w, len(S) - w) if 300 < f[j] < 3000 and S[j] == S[j-w:j+w+1].max()], reverse=True)[:3]
    notes = [[float(fq), 0.0, 0.04] for _, fq in pk]
    gains = np.full(len(bands), 0.05)
    target = F.band_energy(ref, bands)
    for it in range(8):
        s = build(name, gains, notes); y = render(s, 0.2)[:len(ref)]; got = F.band_energy(y, bands)
        gains = gains * np.sqrt(np.maximum(target, 1e-12) / np.maximum(got, 1e-12)) ** 0.8
        if it == 2:   # once the bands are close, give each note a level near its own band's (shape: -6 dB)
            for nt in notes:
                b = next(i for i, (lo, hi) in enumerate(bands) if lo <= nt[0] < hi)
                nt[1] = float(gains[b] * 0.5 * np.sqrt(0.002 / 0.04) * 4)
    s = build(name, gains, notes); y = render(s, 0.2)[:len(ref)]
    e = lambda z: np.array([np.max(np.abs(z[k:k + 88])) for k in range(0, int(.15 * SR), 88)])
    er, ey = e(ref), e(y); corr = float(np.corrcoef(er, ey)[0, 1])
    err = F.third_octave_error(ref, y / np.max(np.abs(y)))
    soft = render(dict(s, hits=[dict(h, bands=[[lo, hi, a * (1/3) ** F.band_exponent(lo), T] for lo, hi, a, T in h["bands"]], sin=[a / 3 for a in h["sin"]]) for h in s["hits"]]), 0.2)
    r = {"round": 7, "flaps": len(times), "third_octave_error_dB": err, "envelope_correlation_2ms": round(corr, 3),
         "centroid_ref": round(F.centroid(ref)), "centroid_fit": round(F.centroid(y)),
         "velocity_rise_pct": round(100 * (F.centroid(y[:1323]) / F.centroid(soft[:1323]) - 1))}
    report[name] = r; models[name + "_STROKE"] = s; print(name, json.dumps(r))
    F.writewav(f"{OUT}/{name}_fit.wav", y / np.max(np.abs(y)) * 0.9)
json.dump(report, open(f"{OUT}/report.json", "w"), indent=1); pickle.dump(models, open(f"{OUT}/models.pkl", "wb"))
