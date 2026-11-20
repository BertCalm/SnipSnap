"""Round 3, cowbell only. The owner heard round 2 as "a regular bell", and the reference says why:
it is not one strike. The bell has a clapper inside and is shaken: strikes land at 0, 139 and
345 ms (levels 0.47, 0.61, 1.0), a small rebound at 708 ms, and only then does it ring. Rounds
1 and 2 cut the loudest strike out and fitted that alone, which is a struck bell.

COWBELL       the shake: the measured strike train on one bell, each strike adding to the ring,
              with the slow wobble of a swinging bell (8.6 Hz, about 1.4 dB, measured).
COWBELL_MUTE  one clapper strike with a hand on the bell: the same partials ringing a quarter
              as long. (The 'short' reference is a muted shake ending in a grab; this is its
              first strike only.)
Python harness, not Kotlin.
"""
import sys, glob, json, pickle, numpy as np
from scipy import signal
import fit as F
SR = F.SR; U, OUT = sys.argv[1], sys.argv[2]
models = pickle.load(open(f"{OUT}/models.pkl", "rb")); report = json.load(open(f"{OUT}/report.json"))
base = models["COWBELL"]
open_bell = dict(base, rise_ms=0.3)                                   # metal on metal: no bloom
mute_bell = dict(base, rise_ms=0.3, partials=[[hz, a, max(0.04, T * 0.25)] for hz, a, T in base["partials"]],
                 bands=[[lo, hi, a, min(T, 0.06)] for lo, hi, a, T in base["bands"]])
HP = signal.butter(2, 450 / (SR / 2), 'high', output='sos')           # the bell has nothing under 700 Hz; this removes the strike's thump
TIMES = [0.0, 139.0, 345.0, 708.0]; LEVELS = [0.47, 0.61, 1.0, 0.17]  # measured

def shake(seconds, velocity=1.0, take=0):
    rng = np.random.default_rng(900 + take); n = int(seconds * SR); y = np.zeros(n)
    for i, (ms, lv) in enumerate(zip(TIMES, LEVELS)):
        if lv < 0.3 * (1 - velocity) + 0.05: continue                 # a gentle shake has no rebound
        if take: ms *= float(np.exp(rng.normal(0, 0.07))); lv *= float(np.exp(rng.normal(0, 0.15)))
        # each clapper strike is as bright as the throw; only its level follows the measured train
        # (rendering a 0.47 strike as a soft one made the first two strikes dull and half as loud as measured)
        h = F.render(open_bell, seconds, velocity, take * 17 + i) * lv
        p = int(ms * 1e-3 * SR); y[p:] += h[: n - p]
    t = np.arange(n) / SR
    y *= 1.0 + 0.08 * np.sin(2 * np.pi * 8.6 * t + rng.uniform(0, 6.28) * (take > 0))     # the swing
    y = signal.sosfilt(HP, y); fade = int(0.25 * SR); y[-fade:] *= 0.5 + 0.5 * np.cos(np.pi * np.arange(fade) / fade)
    return y
def mute(seconds, velocity=1.0, take=0):
    return signal.sosfilt(HP, F.render(mute_bell, seconds, velocity, take))

x = F.readwav(glob.glob(U + '/*cowbell_ring.wav')[0]); ref = x[int(0.2535 * SR):int(0.2535 * SR) + int(1.45 * SR)]
layers = {"soft": 0.3, "mid": 0.6, "hard": 1.0}
for voice, rd, secs in (("COWBELL", shake, 1.45), ("COWBELL_MUTE", mute, 0.5)):
    full = rd(secs, 1.0, 0); scale = 0.95 / np.max(np.abs(full)); r = {"round": 3}; cents = {}; takes = {}
    for name, v in layers.items():
        for take in range(4):
            y = rd(secs, v, take) * scale; F.writewav(f"{OUT}/{voice}_{name}_rr{take+1}.wav", y)
            if take == 0: cents[name] = round(F.centroid(y[:int(0.03 * SR)]))
            if name == "hard": takes[take] = y
    r["velocity_brightness_rise_pct"] = round(100 * (cents["hard"] / cents["soft"] - 1))
    d = [F.third_octave_error(takes[a], takes[b])["attack"] for a in range(4) for b in range(a + 1, 4)]
    r["round_robin_attack_dB"] = [round(min(d), 2), round(max(d), 2)]
    r["deterministic"] = bool(np.array_equal(rd(secs, 1.0, 2), rd(secs, 1.0, 2))); r["finite"] = bool(np.all(np.isfinite(full)))
    if voice == "COWBELL":
        # the whole gesture against the reference from its first strike: third-octave energy in four windows
        bands = F.third_octaves(); syn = full / np.max(np.abs(full)) * np.max(np.abs(ref)); err = {}
        for nm, a, b in (("0-140ms", 0, .14), ("140-345ms", .14, .345), ("345-700ms", .345, .7), ("700-1200ms", .7, 1.2)):
            rr = F.band_energy(ref[int(a*SR):int(b*SR)], bands); ss = F.band_energy(syn[int(a*SR):int(b*SR)], bands); k = rr > rr.max() * 1e-3
            err[nm] = round(float(np.sqrt(np.mean((10 * np.log10(rr[k] / np.maximum(ss[k], 1e-20))) ** 2))), 2)
        r["gesture_error_dB"] = err
        e = lambda s: [round(float(np.max(np.abs(s[int(a*SR):int(b*SR)]))), 2) for a, b in ((0, .05), (.139, .19), (.345, .4), (.708, .76), (1.0, 1.1))]
        r["strike_peaks_ref"] = e(ref / np.max(np.abs(ref))); r["strike_peaks_syn"] = e(syn / np.max(np.abs(syn)))
    report[voice] = dict(report.get(voice, {}), **r); print(voice, json.dumps(r))
models["COWBELL_OPEN"] = open_bell; models["COWBELL_MUTE"] = mute_bell
json.dump(report, open(f"{OUT}/report.json", "w"), indent=1); pickle.dump(models, open(f"{OUT}/models.pkl", "wb"))
# a short phrase so the cowbell is heard in context: shake on 1, mutes on the offbeats
step = 60 / 95 / 4; mix = np.zeros(int((step * 32 + 2) * SR))
def put(h, s, g):
    p = int(s * step * SR); mix[p:p+len(h)] += h[: len(mix) - p] * g
nz = lambda h: h / np.max(np.abs(h))
for bar in range(2):
    put(nz(shake(1.45, 1.0, bar)), bar * 16, 0.8)
    for k, s in enumerate((6, 10, 12, 14)): put(nz(mute(0.5, 1.0, 0)) * (0.5 + 0.5 * (k % 2)), bar * 16 + s, 0.5)
    for s, v in ((0, 1.0), (8, .8)): put(nz(F.render(models["ODAIKO"], 1.45, 1.0, 0)) * v, bar * 16 + s, 1.0)
F.writewav(f"{OUT}/_COWBELL_phrase.wav", mix / np.max(np.abs(mix)) * 0.95)
