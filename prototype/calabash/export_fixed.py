"""Export the approved prototype's fixed draws for the Kotlin port (CALABASH round 1).

Everything random in a CALABASH hit is drawn per take, and the port draws those from its own generator: another take
of the same law, which the round-1 A/B confirms (docs/CALABASH.md, "Round 1"). Three draws are not per take. They
are made once and colour every hit of a voice or a kit the same way, so a port that drew them afresh would not be
another take of the approved sound but a slightly different instrument. This writes the prototype's exact values so
the Kotlin uses them:

  * the room's noise (calabash_proto.room_ir): numpy's PCG64 seeded by seed_for("calabash-room"), standard normals.
    A ROOM setting takes the first n for the low tail and the next n for the high one, n growing with ROOM, so the
    stream is written out to the longest room's 2 n (502 740 values, little-endian float32: room_noise.f32);
  * the cymbals' beating pairs (ember_proto.fitted_modes): per fitted cymbal, each mode's split, energy share and
    decay ratio, from seed_for("ember-doublets", kind);
  * the snapping crowd (ember_proto._crowd): per person, the cavity, the burst length, and (after the first) how
    late and how loud, from seed_for("ember-crowd", False, p).

Run:  python3 prototype/calabash/export_fixed.py synth/src/main/resources/calabash
"""
from __future__ import annotations

import json
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import calabash_proto as c  # noqa: E402

sk = c.sk
e = c.e


def room_noise():
    n_max = c.n_of(min(1.6, 0.05 + sk.lin(1.0, 0.2, 1.1) * 1.25))
    r = np.random.default_rng(sk.seed_for("calabash-room"))
    lo = r.standard_normal(n_max)          # the generator's first n_max draws
    hi = r.standard_normal(n_max)          # and the next n_max: for any room, its lows are the first n and its highs
    return np.concatenate([lo, hi])        # the next n, which this stream holds as long as n <= n_max


def check_room_stream(stream):
    """The stream reproduces room_ir's draws for a short and a long room."""
    for room in (0.0, 0.35, 1.0):
        n = c.n_of(min(1.6, 0.05 + sk.lin(room, 0.2, 1.1) * 1.25))
        r = np.random.default_rng(sk.seed_for("calabash-room"))
        assert np.array_equal(r.standard_normal(n), stream[:n])
        assert np.array_equal(r.standard_normal(n), stream[n:2 * n])


def doublets(kind, k):
    cr = np.random.default_rng(sk.seed_for("ember-doublets", kind))
    split = cr.uniform(0.4, 3.0, k)
    share = cr.uniform(0.2 * math.pi, 0.3 * math.pi, k)
    slope = cr.uniform(0.9, 1.1, k)
    return dict(split=split.tolist(), share=share.tolist(), slope=slope.tolist())


def crowd(people=6):
    out = []
    for p in range(people):
        pr = np.random.default_rng(sk.seed_for("ember-crowd", False, p))
        row = {}
        if p:
            row["late"] = float(pr.uniform(0, 0.01))
            row["gain"] = float(pr.uniform(0.35, 0.8))
        row["cavity"] = float(pr.uniform(1700, 2700))
        row["width"] = float(pr.uniform(0.006, 0.011))
        out.append(row)
    return out


if __name__ == "__main__":
    out = sys.argv[1]
    stream = room_noise()
    check_room_stream(stream)
    stream.astype("<f4").tofile(os.path.join(out, "room_noise.f32"))
    c._breaker_cymbals()
    fit = e.fit()
    kinds = {"hat_open": len(fit["voices"]["hat_open"]["modes"]),
             "breaker_ride": len(fit["voices"]["breaker_ride"]["modes"]),
             "breaker_crash": len(fit["voices"]["breaker_crash"]["modes"])}
    data = dict(
        source="CALABASH round 0 at 967f53f7f (approved 2026-11-15): the prototype's fixed draws, written by "
               "prototype/calabash/export_fixed.py. The room's noise is room_noise.f32 beside this file.",
        roomNoiseValues=int(len(stream)),
        doublets={k: doublets(k, n) for k, n in kinds.items()},
        crowd=crowd(),
    )
    json.dump(data, open(os.path.join(out, "fixed.json"), "w"), separators=(",", ":"))
    print("room noise", len(stream), "values;", {k: n for k, n in kinds.items()}, "doublets; crowd of", len(data["crowd"]))
