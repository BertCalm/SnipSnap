import sys, numpy as np, fit as F
D, R = sys.argv[1], sys.argv[2]; SR = F.SR
raw = lambda n: np.frombuffer(open(f"{D}/{n}.raw", "rb").read(), "<i2").astype(float) / 32767
wav = lambda n: np.frombuffer(open(f"{R}/{n}.wav", "rb").read()[44:], "<i2").astype(float) / 32767
print(f"{'stroke':10s} kotlin-vs-python 3rd-oct err (att/body dB) | soft-vs-hard attack centroid | takes differ (att dB) | t0==t1?")
for n in ("ODAIKO_A","ODAIKO_B","NAGADO","SHIME","SHIME_B","KA","KANE","KANE_EDGE","CHAPPA","WATER","CLAPPER","COWBELL"):
    k = raw(n + "_v100_t0"); p = wav(n); m = min(len(k), len(p)); k1 = k[:m] / np.max(np.abs(k[:m])); p1 = p[:m] / np.max(np.abs(p[:m]))
    e = F.third_octave_error(p1, k1)
    soft = raw(n + "_v030_t0"); c = lambda x: F.centroid(x[:int(.03 * SR)])
    t1 = raw(n + "_v100_t1"); t2 = raw(n + "_v100_t2")
    d = [F.third_octave_error(a, b)["attack"] for a, b in ((k, t1), (k, t2), (t1, t2))]
    print(f"{n:10s} {e['attack']:5.2f} / {e['body']:5.2f} | {c(soft):6.0f} -> {c(k):6.0f} Hz (+{100*(c(k)/c(soft)-1):.0f}%) | {min(d):.2f} to {max(d):.2f} | {np.array_equal(k, t1)}")
