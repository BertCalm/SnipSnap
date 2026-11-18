"""Checks that only tones that really ring become sine partials.

The owner heard ring modulation on the long shaker (2026-11-18): a random peak of one noise
sample, repeated by the loop, had been read as a ringing tone and rendered as a steady sine.
Three cases with known answers:

  repeated noise   one noise burst copied into every hit: its chance peaks are identical in
                   every copy, so the median across hits keeps them. No tone may come out.
  ringing tone     a 5 kHz sine decaying under fresh noise in every hit: it must come out.
  tom ring         a 180 Hz sine decaying under noise: it must come out.

    python3 -I test_steady.py
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))  # run with -I: import our own modules
import fit  # noqa: E402

R = fit.RATE
N = int(0.3 * R)
t = np.arange(N) / R


def hit(x):
    return np.concatenate([np.zeros(fit.PRE), x])


def main() -> int:
    rng = np.random.default_rng(3)
    burst = rng.standard_normal(N) * np.exp(-t / 0.06)
    cases = {
        "repeated noise": ([hit(burst) for _ in range(8)], None),
        "ringing tone": ([hit(rng.standard_normal(N) * np.exp(-t / 0.05) * 0.3 +
                              np.sin(2 * np.pi * 5000 * t) * np.exp(-t / 0.2)) for _ in range(8)],
                         5000.0),
        "tom ring": ([hit(rng.standard_normal(N) * np.exp(-t / 0.03) * 0.3 +
                          np.sin(2 * np.pi * 180 * t) * np.exp(-t / 0.15)) for _ in range(8)],
                     180.0),
    }
    failed = 0
    for name, (takes, want) in cases.items():
        peaks = fit.steady_peaks(np.array(takes), 0.25, [])
        hz = [round(p["hz"]) for p in peaks]
        if want is None:
            ok = not peaks
        else:
            ok = any(abs(np.log2(h / want)) < 0.03 for h in hz)
        failed += not ok
        print(f"{'pass' if ok else 'FAIL'}  {name:15s} tones found: {hz}")
    return failed


if __name__ == "__main__":
    sys.exit(main())
