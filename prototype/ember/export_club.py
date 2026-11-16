"""Export the club's tail as the round-0e prototype drew it, for the Kotlin port.

The club (`ember_proto.room_ir`) is fixed early reflections plus a two-band tail of SEEDED noise. Close up, the
tail carries most of a drum's low end (it is concentrated under 900 Hz while the direct path spreads over the
whole band), so a different noise draw colours each drum's fundamental differently: Kotlin's own draw put the
rack tom's 250 Hz octave 2.8 dB under the prototype's. So the Kotlin port reads the prototype's own draw instead
of drawing again.

What is written: the tail h (early reflections and noise tail, before the direct path), normalised to unit energy
at 176.4 kHz exactly as `room_ir` normalises it, then brought to 44.1 kHz with scipy's resample_poly. Only the
band under 20 kHz survives the engine's own output stage, so nothing audible is lost. Big-endian float32, 39,690
samples. Synthetic, from a seed; no recording.

Run:  python3 prototype/ember/export_club.py synth/src/main/resources/ember/club_tail.f32
"""
import math
import sys

import numpy as np
from scipy import signal

import ember_proto as e


def tail():
    r = np.random.default_rng(e.sk.seed_for("ember-club"))
    n = e.n_of(0.9)
    h = np.zeros(n)
    for ms, g, cut in ((7.6, .55, 5000), (11.2, .42, 7000), (16.8, .38, 4000), (23.5, .30, 6000),
                       (29.0, .22, 3500), (37.0, .20, 4500), (44.0, .15, 3000), (53.0, .12, 3500)):
        i = int(ms * 1e-3 * e.FS)
        tap = np.zeros(e.n_of(0.004)); tap[0] = g
        tap = e.lp(tap, cut, 1)
        h[i:i + len(tap)] += tap
    t = np.arange(n) / e.FS
    lo = e.lp(r.standard_normal(n), 900) * np.exp(-6.9078 * t / 0.62)
    hi = e.hp(r.standard_normal(n), 900) * np.exp(-6.9078 * t / 0.32)
    onset = np.clip((t - 0.012) / 0.03, 0, 1)
    h += (lo * 0.09 + hi * 0.05) * onset * 1.6
    h = e.lp(h, 9000, 1)
    return h / math.sqrt(np.sum(h ** 2))


if __name__ == "__main__":
    h = tail()
    # The same tail room_ir builds (a check that this file has not drifted from the model).
    d0 = e.room_ir(0.0)
    direct = np.zeros(e.n_of(0.01)); direct[0] = 1.0
    direct = e.lp(direct, e.sk.exp_map(0.0, 19000, 5200), 1)
    rebuilt = h * 10 ** (-16.0 / 20)
    rebuilt[:len(direct)] += direct / math.sqrt(np.sum(direct ** 2))
    assert np.max(np.abs(rebuilt - d0)) < 1e-12, np.max(np.abs(rebuilt - d0))
    h44 = signal.resample_poly(h, 1, 4)
    h44.astype(">f4").tofile(sys.argv[1])
    print("wrote", sys.argv[1], len(h44), "samples")
