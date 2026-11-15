"""CALABASH round 1: the Kotlin port against the approved round-0 prototype, pad by pad. Python harness.

The owner approved round 0 (the Python renders) on 2026-11-15. Every per-take draw in the port comes from Kotlin's
generator, not numpy's, so a Kotlin hit cannot be a Python hit sample for sample; what it can be is another take of
the same pad. The three draws the prototype makes once (the room's noise, the cymbals' beating pairs, the snapping
crowd) are the prototype's own values (export_fixed.py). This measures, for every pad of the four kits, hard:

  * the take law: from 16 seeded hits a side, the median house distance (EMBER's and CLUTTER's `take_distance_db`,
    the whole render for the rolls and cymbals) between two Python hits, two Kotlin hits, and a Kotlin and a Python
    hit. When the port draws from the same law, across sits with within;
  * tone: the 16 hits' mean third-octave spectrum (level-matched, first 300 ms, the whole render for the long voices),
    Kotlin minus Python, in the bands within 30 dB of the loudest, beside the same difference between two halves of
    the Python hits (what 8 against 8 hits move by chance);
  * the Kotlin pad's own velocity (soft against hard, take 0) and round robin (closest of its four takes in any zone).

Run after `./gradlew :synth:generateCalabashAudition`:
    python3 prototype/calabash/compare_kotlin.py testkit/calabash-audition/kotlin [out.json]
"""
from __future__ import annotations

import itertools
import json
import multiprocessing as mp
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import calabash_proto as c  # noqa: E402
import kits as K  # noqa: E402
import measures as ms  # noqa: E402

sk = c.sk
RATE = c.RATE
LONG = {"RIDE", "CRASH", "SHEKERE_RUSH", "TALKING_CALL", "SABAR_ROLL", "ABANICO"}
KIT_ID = {"afrobeat": "calabash-afrobeat", "afrobeats": "calabash-afrobeats", "mbalax": "calabash-mbalax",
          "afro-cuban-jazz": "calabash-afrocuban"}
DRAWS = 16


def full_distance_db(a, b):
    n = max(len(a), len(b))
    a, b = np.pad(a, (0, n - len(a))), np.pad(b, (0, n - len(b)))
    A, B = ms.spectro_db(a, ms=10, total=n / RATE), ms.spectro_db(b, ms=10, total=n / RATE)
    return float(np.sqrt(np.mean((A - B) ** 2)))


def dist(voice, a, b):
    return full_distance_db(a, b) if voice in LONG else ms.take_distance_db(a, b)


def bands_db(x, seconds):
    x = x[:int(seconds * RATE)]
    X = np.abs(np.fft.rfft(x * np.hanning(len(x)), 1 << 18)) ** 2
    f = np.fft.rfftfreq(1 << 18, 1 / RATE)
    e = np.array([X[(f >= fc / 2 ** (1 / 6)) & (f < fc * 2 ** (1 / 6))].sum() for fc in ms.THIRD_OCT])
    return 10 * np.log10(e / e.sum() + 1e-18)


def _py(args):
    voice, macros, v, t = args
    return c.render(voice, macros, v, t)


def main():
    kdir = sys.argv[1]
    outp = sys.argv[2] if len(sys.argv) > 2 else None
    jobs = [(k["id"], i, voice, macros) for k in K.ALL for i, (_, _, voice, macros) in enumerate(k["pads"])]
    pool = mp.Pool(4)
    py = pool.map(_py, [(voice, macros, 1.0, t) for _, _, voice, macros in jobs for t in range(DRAWS)]
                  + [(voice, macros, 0.35, 0) for _, _, voice, macros in jobs], chunksize=4)
    pool.close()
    soft_py = py[len(jobs) * DRAWS:]
    rows = []
    for j, (kid, i, voice, macros) in enumerate(jobs):
        P = py[DRAWS * j:DRAWS * (j + 1)]
        slot = f"a{i + 1:02d}"
        kd = os.path.join(kdir, KIT_ID[kid])
        Kl = [sk.read_wav(os.path.join(kdir, "law", KIT_ID[kid], f"{slot}-s{s}.wav"))[0] for s in range(DRAWS)]
        Kz = {z: [sk.read_wav(os.path.join(kd, f"{slot}-{z}-t{t}.wav"))[0] for t in range(4)] for z in "smh"}
        pp = [dist(voice, a, b) for a, b in itertools.combinations(P, 2)]
        kk = [dist(voice, a, b) for a, b in itertools.combinations(Kl, 2)]
        kp = [dist(voice, a, b) for a in Kl[:8] for b in P[:8]]
        secs = min(len(Kl[0]), len(P[0])) / RATE if voice in LONG else 0.3
        pb = np.mean([bands_db(x, secs) for x in P], 0)
        kb = np.mean([bands_db(x, secs) for x in Kl], 0)
        half = np.mean([bands_db(x, secs) for x in P[:8]], 0) - np.mean([bands_db(x, secs) for x in P[8:]], 0)
        sel = pb > pb.max() - 30
        tone = (kb - pb)[sel]
        k_takes = min(dist(voice, a, b) for z in "smh" for a, b in itertools.combinations(Kz[z], 2))
        label = K.ALL[[k["id"] for k in K.ALL].index(kid)]["pads"][i][1]
        rows.append(dict(
            kit=kid, pad=slot.upper(), label=label, voice=voice,
            within_python_db=round(float(np.median(pp)), 2), within_kotlin_db=round(float(np.median(kk)), 2),
            across_db=round(float(np.median(kp)), 2),
            tone_max_db=round(float(np.max(np.abs(tone))), 2),
            tone_band_hz=int(round(float(ms.THIRD_OCT[sel][int(np.argmax(np.abs(tone)))]))),
            python_halves_max_db=round(float(np.max(np.abs(half[sel]))), 2),
            kotlin_soft_vs_hard_db=round(dist(voice, Kz["s"][0], Kz["h"][0]), 2),
            python_soft_vs_hard_db=round(dist(voice, soft_py[j], P[0]), 2),
            kotlin_closest_takes_db=round(k_takes, 2),
        ))
        r = rows[-1]
        print(f"{kid:16s} {slot} {label:18s} within P {r['within_python_db']:5.2f} K {r['within_kotlin_db']:5.2f} across {r['across_db']:5.2f}"
              f" | tone {r['tone_max_db']:4.2f} dB @{r['tone_band_hz']:5d} (P halves {r['python_halves_max_db']:4.2f})"
              f" | soft/hard K {r['kotlin_soft_vs_hard_db']:5.2f} P {r['python_soft_vs_hard_db']:5.2f} | K takes {r['kotlin_closest_takes_db']:4.2f}",
              flush=True)
    excess = [r["across_db"] - (r["within_python_db"] + r["within_kotlin_db"]) / 2 for r in rows]
    summary = dict(
        pads=len(rows),
        across_minus_within_median_db=round(float(np.median(excess)), 2),
        across_minus_within_max_db=round(float(np.max(excess)), 2),
        across_minus_within_max_pad=rows[int(np.argmax(excess))]["kit"] + " " + rows[int(np.argmax(excess))]["pad"],
        tone_max_db=max(r["tone_max_db"] for r in rows),
        tone_over_python_halves=[f"{r['kit']} {r['pad']}" for r in rows if r["tone_max_db"] > r["python_halves_max_db"] + 1.0],
        kotlin_soft_vs_hard_min_db=min(r["kotlin_soft_vs_hard_db"] for r in rows),
        kotlin_closest_takes_min_db=min(r["kotlin_closest_takes_db"] for r in rows),
    )
    print(json.dumps(summary, indent=1))
    if outp:
        json.dump(dict(rows=rows, summary=summary), open(outp, "w"), indent=1)


if __name__ == "__main__":
    main()
