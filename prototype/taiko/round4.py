"""Round 4: the big drum fitted to the owner's two single-hit references (round 1's method:
measured partials plus a fitted residual). Until now ODAIKO had no clean hit to fit.
  ODAIKO_A  from the chu-daiko duo hit (lowest note 66 Hz)
  ODAIKO_B  from the big taiko hit (lowest note 61 Hz)
References measured only; no audio copied. Python harness, not Kotlin."""
import sys, glob, json, pickle, numpy as np
import fit as F
SR = F.SR; U, OUT = sys.argv[1], sys.argv[2]
models = pickle.load(open(f"{OUT}/models.pkl", "rb")); report = json.load(open(f"{OUT}/report.json"))
layers = {"soft": 0.3, "mid": 0.6, "hard": 1.0}; SECS = 1.45
for voice, key in (("ODAIKO_A", "chu_daiko_duo_hit_punch"), ("ODAIKO_B", "fm_big_taiko")):
    x = F.readwav(glob.glob(U + f"/*{key}.wav")[0]); first = int(np.argmax(np.abs(x) > 0.08)); seg = x[max(0, first - 44): max(0, first - 44) + int(1.6 * SR)]      # a single-hit file: the whole hit, not a cut from a loop
    m = F.fit(seg, 1.6); models[voice] = m
    full = F.render(m, len(seg) / SR); scale = 0.95 / np.max(np.abs(full)); cents = {}; takes = {}
    for name, v in layers.items():
        for take in range(4):
            y = F.render(m, SECS, v, take) * scale; F.writewav(f"{OUT}/{voice}_{name}_rr{take+1}.wav", y)
            if take == 0: cents[name] = round(F.centroid(y[:int(0.03 * SR)]))
            if name == "hard": takes[take] = y
    d = [F.third_octave_error(takes[a], takes[b])["attack"] for a in range(4) for b in range(a + 1, 4)]
    S = np.abs(np.fft.rfft(full)) ** 2; f = np.fft.rfftfreq(len(full), 1 / SR); R = np.abs(np.fft.rfft(seg)) ** 2; fr = np.fft.rfftfreq(len(seg), 1 / SR)
    sh = lambda P, ff, lo, hi: round(float(P[(ff >= lo) & (ff < hi)].sum() / P.sum()), 2)
    r = {"round": 4, "reference": key, "clear_s": round(len(seg) / SR, 2), "third_octave_error_dB": F.third_octave_error(seg, full[:len(seg)]),
         "partials": [(round(h, 1), round(a / m["top"], 2), round(t, 2)) for h, a, t in m["partials"]],
         "shares_ref": {"<120": sh(R, fr, 0, 120), "120-500": sh(R, fr, 120, 500), "500+": sh(R, fr, 500, 22050)},
         "shares_fit": {"<120": sh(S, f, 0, 120), "120-500": sh(S, f, 120, 500), "500+": sh(S, f, 500, 22050)},
         "velocity_brightness_rise_pct": round(100 * (cents["hard"] / cents["soft"] - 1)), "round_robin_attack_dB": [round(min(d), 2), round(max(d), 2)],
         "deterministic": bool(np.array_equal(F.render(m, SECS, 1.0, 2), F.render(m, SECS, 1.0, 2))), "finite": bool(np.all(np.isfinite(full)))}
    report[voice] = r; print(voice, json.dumps(r))
    y = np.zeros(int((4.0 + SECS) * SR)); tt = 0.0; i = 0
    while tt < 4.0:
        q = tt / 4.0; h = F.render(m, SECS, round(0.25 + 0.75 * q ** 1.4, 2), i % 4); p = int(tt * SR); y[p:p+len(h)] += h[: len(y) - p]; tt += 1 / (4.0 * 3.0 ** q); i += 1
    F.writewav(f"{OUT}/_{voice}_roll_build.wav", y / np.max(np.abs(y)) * 0.95)
json.dump(report, open(f"{OUT}/report.json", "w"), indent=1); pickle.dump(models, open(f"{OUT}/models.pkl", "wb"))
