"""Round 6: the owner kept both big drums ("A + B"). In case that meant one drum made of both,
ODAIKO_AB is the two struck together. The demo phrase now uses the current picks:
ODAIKO_A and ODAIKO_B for the big drum and the round-5 cowbell. Python harness, not Kotlin."""
import sys, json, pickle, numpy as np
import fit as F, importlib
SR = F.SR; OUT = sys.argv[2]
models = pickle.load(open(f"{OUT}/models.pkl", "rb")); report = json.load(open(f"{OUT}/report.json"))
pa = np.max(np.abs(F.render(models["ODAIKO_A"], 1.45))); pb = np.max(np.abs(F.render(models["ODAIKO_B"], 1.45)))
def both(secs, v, take):      # each at its own full-hit level, B's take offset so the pair is never the same two strikes
    return F.render(models["ODAIKO_A"], secs, v, take) / pa + F.render(models["ODAIKO_B"], secs, v, (take + 2) % 4) / pb
full = both(1.45, 1.0, 0); scale = 0.95 / np.max(np.abs(full)); cents = {}; takes = {}
for name, v in {"soft": 0.3, "mid": 0.6, "hard": 1.0}.items():
    for take in range(4):
        y = both(1.45, v, take) * scale; F.writewav(f"{OUT}/ODAIKO_AB_{name}_rr{take+1}.wav", y)
        if take == 0: cents[name] = round(F.centroid(y[:int(0.03 * SR)]))
        if name == "hard": takes[take] = y
d = [F.third_octave_error(takes[a], takes[b])["attack"] for a in range(4) for b in range(a + 1, 4)]
r = {"round": 6, "velocity_brightness_rise_pct": round(100 * (cents["hard"] / cents["soft"] - 1)), "round_robin_attack_dB": [round(min(d), 2), round(max(d), 2)],
     "deterministic": bool(np.array_equal(both(1.45, 1.0, 2), both(1.45, 1.0, 2))), "finite": bool(np.all(np.isfinite(full)))}
report["ODAIKO_AB"] = r; print("ODAIKO_AB", json.dumps(r)); json.dump(report, open(f"{OUT}/report.json", "w"), indent=1)
def wav(name):
    b = open(f"{OUT}/{name}.wav", "rb").read(); return np.frombuffer(b[44:], "<i2").astype(float) / 32767
step = 60 / 95 / 4; mix = np.zeros(int((step * 64 + 2.5) * SR))
lay = lambda v: "soft" if v < 0.45 else "mid" if v < 0.8 else "hard"
pat = [("ODAIKO_A", 1.0, 0), ("KA", .6, 2), ("KA", .3, 3), ("NAGADO", .7, 4), ("SHIME", 1.0, 6), ("KANE", .6, 6), ("ODAIKO_B", 1.0, 8), ("NAGADO", .9, 10),
       ("KA", .6, 11), ("SHIME", .6, 12), ("SHIME", 1.0, 13), ("KANE_EDGE", .5, 14), ("SHIME", .6, 14), ("KA", 1.0, 15)]
lvl = {"KANE": .35, "KANE_EDGE": .25, "CHAPPA": .3, "CLAPPER": .35, "KA": .6, "SHIME": .7, "COWBELL": .45, "COWBELL_MUTE": .3, "WATER": .8}
for bar in range(4):
    hits = pat + ([("CHAPPA", .7, 0)] if bar == 2 else []) + ([("CLAPPER", 1.0, 12), ("COWBELL", 1.0, 0)] if bar == 3 else []) + ([("WATER", .7, 7), ("WATER", .5, 9), ("COWBELL_MUTE", .7, 14)] if bar == 1 else [])
    for k, (voice, v, s_) in enumerate(hits):
        h = wav(f"{voice}_{lay(v)}_rr{(k + bar) % 4 + 1}") * lvl.get(voice, 1.0) * (v if lay(v) == "hard" else 1.0); p = int((bar * 16 + s_) * step * SR); mix[p:p+len(h)] += h[: len(mix) - p]
F.writewav(f"{OUT}/_demo_phrase_95bpm.wav", mix / np.max(np.abs(mix)) * 0.95)
print("phrase written", round(len(mix) / SR, 1), "s")
