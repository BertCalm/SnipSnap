"""HICCUP v2 script: the four groove fixes, as overrides on the v1 prototype.
  1. attack guard: a buzz never eats the first GUARD ms of the hit
  2. one key: every buzz pitch comes from one shared pentatonic scale, not the voice's own root
  3. grid: slices are binary multiples of the CHOP slice; stumble time is a whole number of grid units
  4. cap: total inserted time per hit is bounded by FAULT
"""
import sys, math, numpy as np
sys.path.insert(0, "/Users/joshuacramblet/SnipSnap-hiccup/docs/prototypes/hiccup")
import hiccup_proto as h
sk, RATE, N = h.sk, h.RATE, h.N_SLOTS
GUARD, KEY_HZ = 0.018, 55.0
CHOP_A, CHOP_B = 0.110, 0.0016

def chop_for(seconds):
    return math.log(seconds / CHOP_A) / math.log(CHOP_B / CHOP_A)

def unit_of(base):
    g = base
    while g < 0.040: g *= 2
    while g >= 0.080: g /= 2
    return g

def key_hz(tune):
    semis = 24.0 * (tune - 0.5)
    octv = math.floor(semis / 12.0); within = semis - 12 * octv
    near = min(np.append(h.PENTA, 12), key=lambda p: abs(p - within))
    hz = KEY_HZ * 2.0 ** ((12 * octv + near) / 12.0)
    while hz > 220.0: hz /= 2.0
    return hz

def script(voice, m, vel, take, dry_seconds):
    d = h.draws(voice, take)
    fe = m["FAULT"] * sk.lin(vel, 0.55, 1.0)
    base = sk.exp_map(m["CHOP"], CHOP_A, CHOP_B)
    g = unit_of(base)
    left = max(1, round(fe * 6))                       # total stumble, in grid units
    events = []
    for rank, i in enumerate(np.argsort(d[:, 0])):
        if fe <= (rank + d[i, 0]) / N or left <= 0:
            continue
        u_pos, u_len, u_shrink, u_rev, u_dive = d[i, 1:]
        length = base * 2.0 ** round((u_len - 0.5) * 2.0)          # x1/2, x1, x2
        length = h.snap_to_pitch(length, KEY_HZ)
        want = sk.lin(fe, 0.05, 0.26) * (0.6 + 0.8 * u_len)
        units = min(left, max(1, round(want / g)))
        budget = max(units * g, math.ceil(length / g) * g)
        left -= round(budget / g)
        if u_pos < 0.34:
            pos = max(0.0, GUARD - length)
        else:
            pos = max(g / 2, round(((u_pos - 0.34) / 0.66 * 0.55 * dry_seconds) / (g / 2)) * (g / 2))
        events.append({"slot": int(i), "pos": pos, "length": length, "budget": budget,
                       "shrink": 1.0 - (0.22 * (u_shrink - 0.5) * 2 * fe if u_shrink > 0.5 else 0.0),
                       "reverse": bool(u_rev < 0.35 * fe), "dive": bool(u_dive < 0.4 * fe), "repeats": 0})
    events.sort(key=lambda e: e["pos"])
    kept, cursor = [], -1.0
    for e in events:
        if e["pos"] >= cursor and e["pos"] + e["length"] < dry_seconds:
            kept.append(e); cursor = e["pos"] + e["length"]
    return kept

def reread(dry, events, m):
    dry = dry / max(1e-9, float(np.max(np.abs(dry))))
    total = len(dry) / RATE
    out, cursor = [], 0.0
    for e in events:
        if e["pos"] > cursor:
            out.append(h.segment(dry, cursor, e["pos"] - cursor, 0, m, edges=(cursor > 0, True)))
        L = e["length"]
        out.append(h.segment(dry, e["pos"], L, 0, m, edges=(e["pos"] > 0, True)))
        span, elapsed, k = e["budget"] * 1.5, 0.0, 0
        while e["budget"] - elapsed > 0.0006:                       # fill the budget exactly; the last repeat is cut to fit
            k += 1
            ell = min(max(0.0006, L * e["shrink"] ** k), e["budget"] - elapsed)
            d0 = 1.0 - 0.55 * (elapsed / e["budget"]) if e["dive"] else 1.0
            d1 = 1.0 - 0.55 * ((elapsed + ell) / e["budget"]) if e["dive"] else 1.0
            out.append(h.segment(dry, e["pos"], ell, k, m, reverse=e["reverse"] and k % 2 == 1,
                                 speed0=max(0.3, d0), speed1=max(0.3, d1), gain=math.exp(-elapsed / span)))
            elapsed += ell
        e["repeats"] = k
        cursor = e["pos"] + L
    if cursor < total:
        out.append(h.segment(dry, cursor, total - cursor, 0, m, edges=(cursor > 0, False)))
    return np.concatenate(out)[: int(h.MAX_SECONDS * RATE)]

def install():
    h.script, h.reread = script, reread
