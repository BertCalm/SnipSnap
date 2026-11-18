"""Checks the glide fitter on synthetic hits whose answer is known.

Each case is a decaying sine whose pitch falls by a known law, with a second harmonic 12 dB
down and a little noise, as a real kick has. The fitter must recover the start pitch, the end
pitch and the speed within 6%, and the level 50 ms in within 10%.

    python3 -I test_glidefit.py
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))  # run with -I: import our own module
import glidefit  # noqa: E402

R = 44_100
CASES = [  # (law, f0, f1, tau seconds, root guess) : a kick body, an 808 drop, a shallow sub,
    ("exp", 180, 90, 0.030, 100),     # a fast click sweep, a short sub kick, a zap
    ("exp", 150, 33, 0.020, 40),
    ("exp", 400, 52, 0.006, 50),
    ("exp", 3000, 60, 0.012, 70),
    ("logexp", 3000, 90, 0.030, 100),
]


def main() -> int:
    t = np.arange(int(0.4 * R)) / R
    failed = 0
    for i, (law, f0, f1, tau, guess) in enumerate(CASES):
        f = glidefit.freq(law, f0, f1, tau, t)
        ph = 2 * np.pi * np.cumsum(f) / R
        x = np.sin(ph) * np.exp(-t / 0.15) + 0.25 * np.sin(2 * ph) * np.exp(-t / 0.1)
        x += 0.03 * np.random.default_rng(i).standard_normal(len(t))
        g = glidefit.fit(x, R, guess, 0.35, 6000)
        amp = g["amp"][1][int(0.05 * g["rate"])]
        errs = dict(f0=abs(g["f0"] / f0 - 1), f1=abs(g["f1"] / f1 - 1),
                    tau=abs(g["tau"] / tau - 1), level=abs(amp / np.exp(-0.05 / 0.15) - 1))
        ok = errs["f0"] < .06 and errs["f1"] < .06 and errs["tau"] < .06 and errs["level"] < .10
        ok = ok and g["law"] == law
        failed += not ok
        print(f"{'pass' if ok else 'FAIL'}  {law:6s} {f0:5.0f}->{f1:3.0f} Hz tau {tau * 1000:4.1f} ms"
              f" | fit {g['law']:6s} {g['f0']:7.1f}->{g['f1']:5.1f} tau {g['tau'] * 1000:5.1f} ms"
              f" level err {100 * errs['level']:.1f}%")
    return failed


if __name__ == "__main__":
    sys.exit(main())
