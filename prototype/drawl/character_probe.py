"""Put numbers on the owner's words for round 0b: CONE's "twang" and BOTTLE's "whine".

Python harness, not Kotlin. Measured on a still bar (SLIDE 0, WAVER 0) so the bar's motion
does not stand in for the string's character.

    python3 prototype/drawl/character_probe.py [OUT_JSON]

twang (attack):
  boing_cents      pitch 8-40 ms after the pick minus pitch 200-300 ms after: a hard metal pick
                   stretches the string, so it starts sharp and drops
  attack_band_db   share of 1.5-4 kHz energy in the first 150 ms, dB of the total
whine (sustain):
  sustain_band_db  share of 1-4 kHz energy from 0.5 to 1.5 s, dB of the total
  highs_hold_db    how much less harmonics 4 to 10 have fallen than the fundamental between
                   0.1 s and 1.0 s: positive means the upper harmonics keep singing
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import drawl_proto as dp  # noqa: E402
import measure  # noqa: E402

RATE = dp.RATE


def band_db(y, lo, hi):
    spec = np.abs(np.fft.rfft(y * np.hanning(len(y)))) ** 2
    f = np.fft.rfftfreq(len(y), 1 / RATE)
    return float(10 * np.log10(spec[(f >= lo) & (f < hi)].sum() / max(spec[(f >= 40)].sum(), 1e-30)))


def harmonic_db(y, t, f0, ks, win=.1):
    seg = y[int(t * RATE):int((t + win) * RATE)]
    seg = seg * np.hanning(len(seg))
    spec = np.abs(np.fft.rfft(seg, 1 << 16))
    f = np.fft.rfftfreq(1 << 16, 1 / RATE)
    e = sum(spec[np.argmin(np.abs(f - k * f0))] ** 2 for k in ks if k * f0 < RATE / 2 - 500)
    return 10 * np.log10(e + 1e-30)


def probe(voice, velocity=.75, module=dp):
    y, p = module.render(voice, {"SLIDE": 0.0, "WAVER": 0.0}, velocity=velocity)
    f0 = module.hz(p.midi)
    tr, dt = measure.pitch_track(y, RATE)
    c = 1200 * np.log2(tr / f0)
    early = c[int(.008 / dt):int(.04 / dt) + 1]
    late = c[int(.2 / dt):int(.3 / dt)]
    early, late = early[np.isfinite(early)], late[np.isfinite(late)]
    boing = float(np.median(early) - np.median(late)) if len(early) and len(late) else None
    hold = ((harmonic_db(y, 1.0, f0, range(4, 11)) - harmonic_db(y, .1, f0, range(4, 11)))
            - (harmonic_db(y, 1.0, f0, [1]) - harmonic_db(y, .1, f0, [1])))
    return {
        "boing_cents": None if boing is None else round(boing, 1),
        "attack_band_db": round(band_db(y[: int(.15 * RATE)], 1500, 4000), 1),
        "sustain_band_db": round(band_db(y[int(.5 * RATE):int(1.5 * RATE)], 1000, 4000), 1),
        "highs_hold_db": round(float(hold), 1),
    }


def main(module=dp):
    out = {}
    for v in ("CONE", "BOTTLE", "LAP"):
        out[v] = {"medium": probe(v, .75, module), "hard": probe(v, 1.0, module)}
        print(v, json.dumps(out[v]))
    if len(sys.argv) > 1:
        json.dump(out, open(sys.argv[1], "w"), indent=1)
    return out


if __name__ == "__main__":
    main()
