import sys, os, numpy as np
sys.path.insert(0, os.path.dirname(__file__))
import v2
h, sk = v2.h, v2.sk
OUT = os.path.join(os.path.dirname(__file__), "out"); os.makedirs(OUT, exist_ok=True)
BPM = 160; S16 = 60 / BPM / 4
def dur(x, db=-30):
    e = np.abs(x); return np.nonzero(e > e.max() * 10 ** (db / 20))[0][-1] / sk.RATE
def onset_grid(mix, bpm, div=8, tol=0.004):
    """Share of onset energy within tol of the 32nd-note grid."""
    hop = 64; n = len(mix) // hop
    env = np.sqrt(np.mean(mix[: n * hop].reshape(n, hop) ** 2, axis=1))
    flux = np.maximum(0, np.diff(env)); t = (np.arange(len(flux)) + 1) * hop / sk.RATE
    step = 60 / bpm / div; off = np.abs((t + step / 2) % step - step / 2)
    return float(flux[off < tol].sum() / flux.sum())
def attack_kept(voice, m):
    dry = h.finish(h.render(voice, {**m, "FAULT": 0})); wet = h.finish(h.render(voice, m))
    n = int(0.015 * sk.RATE); a, b = dry[:n], wet[:n]
    return float(np.dot(a, b) / (np.linalg.norm(a) * np.linalg.norm(b) + 1e-12))
old_kit = {
  "kick": ("KICK", {"TUNE": .6, "DECAY": .7, "FAULT": .7, "CHOP": .85, "CRUSH": .7, "TOY": .9}, 1.0),
  "snare": ("SNARE", {"TUNE": .7, "DECAY": .35, "FAULT": .85, "CHOP": .8, "CRUSH": .6, "TOY": .8}, .8),
  "clap": ("CLAP", {"TUNE": .6, "DECAY": .4, "FAULT": .5, "CHOP": .9, "CRUSH": .5, "TOY": .7}, .7),
  "hat": ("HAT", {"TUNE": .7, "DECAY": .1, "FAULT": .5, "CHOP": .95, "CRUSH": .6, "TOY": .9}, .3),
  "blip": ("BLIP", {"TUNE": .75, "DECAY": .3, "FAULT": .9, "CHOP": .9, "CRUSH": .4, "TOY": 1.0}, .4)}
old_pat = {"kick": [(0, 1), (3, .8), (6, 1), (10, 1), (13, .8)], "snare": [(4, 1), (12, 1)], "clap": [(4, .8), (12, .9), (14, .6)],
           "hat": [(s, .9 if s % 2 == 0 else .5) for s in range(16)], "blip": [(7, .8), (15, .9)]}
c = lambda div: v2.chop_for(60 / BPM / div)     # CHOP that makes the slice one 1/div note at 160
# backbone light on FAULT; the glitch lives on accent pads
new_kit = {
  "kick":   ("KICK",  {"TUNE": .65, "DECAY": .40, "FAULT": .15, "CHOP": c(8),  "CRUSH": .40, "TOY": .85}, 1.0),
  "snare":  ("SNARE", {"TUNE": .70, "DECAY": .30, "FAULT": .30, "CHOP": c(16), "CRUSH": .40, "TOY": .80}, .8),
  "clap":   ("CLAP",  {"TUNE": .65, "DECAY": .30, "FAULT": .0,  "CHOP": c(16), "CRUSH": .30, "TOY": .70}, .6),
  "hat":    ("HAT",   {"TUNE": .75, "DECAY": .10, "FAULT": .0,  "CHOP": c(32), "CRUSH": .35, "TOY": .90}, .28),
  "zhat":   ("HAT",   {"TUNE": .75, "DECAY": .12, "FAULT": .60, "CHOP": c(32), "CRUSH": .50, "TOY": .90}, .30),
  "bkick":  ("KICK",  {"TUNE": .65, "DECAY": .40, "FAULT": .70, "CHOP": c(64), "CRUSH": .55, "TOY": .90}, .9),
  "blip":   ("BLIP",  {"TUNE": .65, "DECAY": .30, "FAULT": .80, "CHOP": c(128), "CRUSH": .40, "TOY": 1.0}, .38),
  "fill":   ("SNARE", {"TUNE": .75, "DECAY": .25, "FAULT": .90, "CHOP": c(32), "CRUSH": .50, "TOY": .90}, .7)}
new_pat = {"kick": [(0, 1), (6, .9), (8, 1)], "bkick": [(14, .9)], "snare": [(4, 1), (12, 1)], "clap": [(4, .8), (12, .9)],
           "hat": [(s, .9 if s % 4 == 0 else .55) for s in range(0, 16, 2) if s != 10], "zhat": [(10, .8)], "blip": [(3, .7), (11, .8)]}
def report(tag, kit, pat):
    print(f"-- {tag}")
    for name, (v, m, _) in kit.items():
        info = {}; y = h.finish(h.render(v, m, 1.0, 0, info)); ev = info["events"]
        ins = len(y) / sk.RATE - len(h.finish(h.render(v, {**m, "FAULT": 0}))) / sk.RATE
        hz = sorted({round(1 / e["length"]) for e in ev if e["length"] < .012})
        print(f"  {name:6s} len {dur(y)/S16:4.1f} steps  inserted {ins/S16:4.2f} steps  attack kept {attack_kept(v, m):.2f}  buzz Hz {hz}")
    mix = h.beat(BPM, 4, pat, kit)
    print(f"  onset energy on the 32nd grid: {onset_grid(mix, BPM):.2f}")
    return mix
a = report("v1 engine, v1 kit", old_kit, old_pat)
v2.install()
b = report("v2 engine, v1 kit (engine fixes only)", old_kit, old_pat)
cmix = report("v2 engine, v2 kit (backbone clean, accents glitch)", new_kit, new_pat)
print("chance level for this probe: %.2f" % (2 * 0.004 / (60 / BPM / 8)))
import inspect; print(inspect.signature(sk.write_wav))
for n, x in (("a_before", a), ("b_engine_fix_same_kit", b), ("c_engine_fix_new_kit", cmix)):
    sk.write_wav(os.path.join(OUT, n + ".wav"), x)
