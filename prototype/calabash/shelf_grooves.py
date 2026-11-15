"""Round 2: the kits on the shelf, for the owner's confirming listen.

Reads the listening pack `./gradlew :shell:generateCalabashKits` writes (testkit/calabash-kits: every pad's twelve
strikes as the app's pad player plays them back from the MPC 3 export), and writes under OUT/shelf/:

- each kit's two-bar groove (`kits.py`, the pattern the owner approved) played with those Kotlin strikes, through the
  audition's own groove renderer (the next take at each hit's velocity zone, the sampler's velocity gain, the chokes,
  the feel and the swing), so the Kotlin kit can be heard against the approved Python groove on the same page;
- each pad's hard first take and its soft-to-hard strip, copied as they are;
- `manifest.json`.

Python harness around Kotlin output: nothing here renders a drum.

Run:  python3 shelf_grooves.py KITS_DIR OUT_DIR      then   python3 build_page.py OUT_DIR
"""
from __future__ import annotations

import json
import os
import shutil
import sys

import numpy as np
from scipy.io import wavfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
KITS_DIR, OUT = sys.argv[1], sys.argv[2]
sys.argv = [sys.argv[0], OUT]          # gen_audition reads its OUT from argv; it renders nothing on import
import gen_audition as ga  # noqa: E402
import kits as K  # noqa: E402

ZONE_ORDER = ["s", "m", "h"]            # the strip is soft, medium, hard, four takes each


def read(path):
    rate, x = wavfile.read(path)
    assert rate == ga.RATE, (path, rate)
    if x.dtype == np.int16:
        return x.astype(np.float64) / 32768.0
    if x.dtype == np.int32:
        return x.astype(np.float64) / 2147483648.0
    return x.astype(np.float64)


def strikes(strip):
    """The twelve strikes of a strip: equal strides, each strike first in its stride and silence after it."""
    stride = len(strip) // 12
    assert stride * 12 == len(strip)
    return [strip[i * stride:(i + 1) * stride] for i in range(12)]


def main():
    shelf = json.load(open(os.path.join(KITS_DIR, "manifest.json")))
    assert [k["bpm"] for k in shelf["kits"]] == [int(k["bpm"]) for k in K.GENRE]
    out_dir = os.path.join(OUT, "shelf")
    os.makedirs(out_dir, exist_ok=True)
    for sk, kit in zip(shelf["kits"], K.GENRE):
        src = os.path.join(KITS_DIR, sk["id"])
        dst = os.path.join(out_dir, sk["id"])
        os.makedirs(dst, exist_ok=True)
        layers = {}
        for p in sk["pads"]:
            n = f"{p['slot']:02d}"
            for name in (f"pad-{n}.wav", f"touch-{n}.wav"):
                shutil.copyfile(os.path.join(src, name), os.path.join(dst, name))
            hits = strikes(read(os.path.join(src, f"touch-{n}.wav")))
            layers[f"A{n}"] = {z: hits[4 * i:4 * i + 4] for i, z in enumerate(ZONE_ORDER)}
        y = ga.groove(kit, layers)
        wavfile.write(os.path.join(dst, "groove.wav"), ga.RATE, (np.clip(y, -1, 1) * 32767).astype(np.int16))
        sk["groove"] = f"shelf/{sk['id']}/groove.wav"
        sk["groove_seconds"] = round(len(y) / ga.RATE, 2)
        print(f"{sk['id']}: groove {len(y) / ga.RATE:.1f} s from {len(sk['pads']) * 12} Kotlin strikes")
    json.dump(shelf, open(os.path.join(out_dir, "manifest.json"), "w"), indent=1)


if __name__ == "__main__":
    main()
