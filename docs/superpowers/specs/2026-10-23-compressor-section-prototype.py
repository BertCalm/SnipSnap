"""The COMPRESSOR section's prototype (Python harness, not Kotlin). numpy only.

Spec: docs/superpowers/specs/2026-10-23-compressor-section-design.md. Run it on the pedal audition's
clips (./gradlew :synth:generatePedalAudition writes testkit/pedal-audition/):

    python3 -I 2026-10-23-compressor-section-prototype.py testkit/pedal-audition out-dir > report.json

Law A (the design): a level detector (5 ms mean square, linked over channels, in dB under the pad's
loudest level), a static curve (threshold T under that level, ratio R), the gain smoothed in dB with
ATTACK (falling) and RELEASE (rising), applied 2 ms early (SQUASH's lookahead), a lookahead limiter
whose ceiling is the compressor's own static output at the loudest level plus ATTACK's headroom, then
the house peak-match, which is where the make-up comes from. A FLOOR 50 dB under the loudest level
takes the make-up back out over 12 dB, so silence, noise and the pad's own end fade are not raised.
Law B (rejected, kept for the record): upward compression below T, measured to do nothing on pads
that never fall under T in their body (the choir, both baked guitars) and 2 dB on the snare.
"""
import json, sys, wave
from pathlib import Path
import numpy as np

RATE = 44100

def read(p):
    with wave.open(str(p)) as w:
        n, ch = w.getnframes(), w.getnchannels()
        x = np.frombuffer(w.readframes(n), dtype="<i2").astype(np.float64) / 32768.0
    return x.reshape(-1, ch) if ch > 1 else x.reshape(-1, 1)

def write(p, x):
    p.parent.mkdir(parents=True, exist_ok=True)
    y = np.clip(x, -1, 1)
    with wave.open(str(p), "wb") as w:
        w.setnchannels(y.shape[1]); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes((np.round(y * 32767)).astype("<i2").tobytes())

def exp_map(v, lo, hi):
    return lo * (hi / lo) ** v

def params(amount, attack, release):
    depth = 10.0 + 30.0 * amount           # threshold under the pad's loudest level: 10 dB at 0+, 40 dB at 1 (chosen by the sweep)
    ratio = 2.0 + 8.0 * amount             # 2:1 to 10:1 (chosen by the sweep)
    atk = exp_map(attack, 0.0005, 0.040)   # 0.5 ms to 40 ms, 5 ms at 0.5
    rel = exp_map(release, 0.020, 1.0)     # 20 ms to 1 s; 64 ms at the default 0.3 (the sweep: short release is sustain)
    return depth, ratio, atk, rel

FLOOR_DB, TAPER_DB = 50.0, 12.0
LEVEL_S = 0.005        # the level detector: 5 ms mean square, linked over channels (tracks, does not shape)
LOOK_S = 0.002         # SQUASH's lookahead
HEADROOM_MAX_DB = 12.0 # law A: how far the escaped hit may stand over the compressed body at ATTACK 1 (shape; 6 dB at 0.5)

def one_pole(v, tau):
    k = 1 - np.exp(-1 / (tau * RATE)); out = np.empty_like(v); e = v[0]
    for i, s in enumerate(v):
        e += k * (s - e); out[i] = e
    return out

def level_db(x):
    ms = one_pole((x ** 2).max(axis=1), LEVEL_S)
    lvl = 10 * np.log10(np.maximum(ms, 1e-18))
    return lvl - lvl.max()                     # dB under the pad's loudest level

def smooth_gain(target, atk, rel):
    """Attack when the gain falls (more reduction), release when it rises; in dB."""
    ka = 1 - np.exp(-1 / (atk * RATE)); kr = 1 - np.exp(-1 / (rel * RATE))
    g = np.empty_like(target); e = 0.0
    for i, t in enumerate(target):
        e += (ka if t < e else kr) * (t - e); g[i] = e
    return g

def taper(lvl, lift):
    t = np.clip((lvl + FLOOR_DB + TAPER_DB) / TAPER_DB, 0, 1)
    return lift * t

def limit(y, ceiling):
    """Lookahead peak limiter: never above ceiling, 30 ms recovery."""
    look = int(0.001 * RATE)
    need = np.minimum(1.0, ceiling / np.maximum(np.abs(y).max(axis=1), 1e-12))
    # the minimum over the next `look` samples, so the gain is down before the peak arrives
    pad = np.concatenate([need, np.ones(look)])
    ahead = np.array([pad[i:i + look + 1].min() for i in range(len(need))])
    kr = 1 - np.exp(-1 / (0.03 * RATE)); g = np.empty_like(ahead); e = 1.0
    for i, t in enumerate(ahead):
        e = t if t < e else e + kr * (t - e); g[i] = e
    return y * g[:, None]

def process(x, law, amount, attack=0.5, release=0.5):
    if amount <= 0.001:
        return x.copy()
    peak = np.abs(x).max()
    depth, ratio, atk, rel = params(amount, attack, release)
    lvl = level_db(x)
    T = -depth
    if law == "A":
        target = np.where(lvl > T, (T - lvl) * (1 - 1 / ratio), 0.0)
        # under the floor the make-up the peak-match will add is taken back out
        makeup = depth * (1 - 1 / ratio)
        target = target - (makeup - taper(lvl, np.full_like(lvl, makeup)))
    else:
        target = taper(lvl, np.where(lvl < T, (T - lvl) * (1 - 1 / ratio), 0.0))
    g = smooth_gain(target, atk, rel)
    look = int(LOOK_S * RATE)
    g = np.concatenate([g[look:], np.full(look, g[-1])])
    y = x * (10 ** (g / 20))[:, None]
    if law == "A":
        # The ceiling is the compressor's own static output at the pad's loudest level, plus HEADROOM_DB and
        # a sine's crest. Measured against the escaped hit itself it never engaged (the hit set the body).
        ref = np.sqrt(one_pole((x ** 2).max(axis=1), LEVEL_S).max())
        static = T * (1 - 1 / ratio)
        y = limit(y, ref * 10 ** ((static + HEADROOM_MAX_DB * attack + 3.0) / 20))
    yp = np.abs(y).max()
    return y * (peak / yp) if yp > 1e-9 else y   # the house peak-match

def db(v): return 20 * np.log10(max(v, 1e-12))
def rms(s): return np.sqrt((s ** 2).mean()) if len(s) else 0.0

def metrics(x, ref):
    def contour(s):
        lvl = level_db(s); lp = int(np.argmax(lvl)); return lvl, lp
    def t20(s):
        lvl, lp = contour(s); under = np.nonzero(lvl[lp:] < -20)[0]
        return round(under[0] / RATE * 1000) if len(under) else None
    def late(s):
        lvl, lp = contour(s); i = lp + int(1.5 * RATE)
        return round(float(lvl[i] - lvl[lp + int(.05 * RATE)]), 1) if i < len(lvl) else None
    def crest(s):
        m = np.abs(s).max(axis=1); lvl, lp = contour(s)
        body = np.sqrt(one_pole((s ** 2).max(axis=1), LEVEL_S)[lp + int(.03 * RATE): lp + int(.08 * RATE)].mean())
        return db(m.max()) - db(body)
    out = {"t20_ms": t20(x), "t20_ref_ms": t20(ref), "spike_over_body_db": round(crest(x), 1),
           "spike_ref_db": round(crest(ref), 1),
           "end_under_peak_db": round(db(np.abs(x).max()) - db(rms(x[-int(.02 * RATE):].mean(axis=1))), 1)}
    if late(ref) is not None:
        out["late_1500_db"] = late(x); out["late_ref_db"] = late(ref)
    return out

if __name__ == "__main__":
    src_root, out_root = Path(sys.argv[1]), Path(sys.argv[2])
    sources = {"SNARE": "dry", "CHOIR": "dry", "JANGLE": "baked", "CHUG": "baked"}
    plan = [  # (clip id, law, AMOUNT, ATTACK, RELEASE); AMOUNT 0.7 is the chip at the sheet's default AMT
        ("new_70", "A", .7, .5, .3), ("new_100", "A", 1., .5, .3),
        ("attack_0", "A", .7, 0., .3), ("attack_100", "A", .7, 1., .3),
        ("release_0", "A", .7, .5, 0.), ("release_100", "A", .7, .5, 1.),
    ]
    report = {"harness": "Python, not Kotlin", "params": {}, "clips": {}}
    for cid, law, a, at, rl in plan:
        d, r, ak, rk = params(a, at, rl)
        report["params"][cid] = {"law": law, "threshold_db_under_peak": d, "ratio": r, "attack_ms": round(ak * 1e3, 2), "release_ms": round(rk * 1e3)}
    for src, ref_name in sources.items():
        x = read(src_root / src / f"{ref_name}.wav")
        write(out_root / src / f"{ref_name}.wav", x)
        old = read(src_root / src / ("comp_70.wav" if ref_name == "dry" else "sheet_comp_70.wav"))
        write(out_root / src / "old_comp_70.wav", old)
        report["clips"][f"{src}/old_comp_70"] = metrics(old, x)
        for cid, law, a, at, rl in plan:
            y = process(x, law, a, at, rl)
            write(out_root / src / f"{cid}.wav", y)
            report["clips"][f"{src}/{cid}"] = metrics(y, x)
    print(json.dumps(report, indent=1))
