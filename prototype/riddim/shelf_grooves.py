"""Round 2's listen: the approved grooves played with the Kotlin kits as the shelf holds them (Python harness).

    ./gradlew :shell:generateRiddimKits -PriddimKitsOutput=PACK
    python3 shelf_grooves.py PACK OUT

`generateRiddimKits` proves every grid through saving, the pad resolver and MPC 3 export, and writes for each pad a
`touch-NN.wav` strip: soft, mid and hard, four takes each, in the order the pad deals them, each strike in an equal
slot. This script cuts the strips back into strikes and plays `kits.GROOVES` with them, dealing takes per zone and
choking exactly as `kits.play_groove` does with the prototype's renders, so the two grooves differ only by the port.
Writes OUT/<kit>/groove.wav, OUT/<kit>/pads/<pad>_z<zone>_t<take>.wav (the Kotlin strikes) and OUT/checks.json in
the layout `build_page.py` reads.
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import kits as K  # noqa: E402
import wave  # noqa: E402


def rd(path):
    """A mono 16- or 24-bit WAV as floats (the pack writes 16-bit). Local, not build_page's: CALABASH's prototype
    folder is on the path (riddim_proto imports it) and has a build_page of its own."""
    with wave.open(path) as w:
        n, width = w.getnframes(), w.getsampwidth()
        raw = w.readframes(n)
    if width == 2:
        return np.frombuffer(raw, "<i2") / 32768
    b = np.frombuffer(raw, np.uint8).reshape(-1, 3)
    return (np.pad(b, ((0, 0), (1, 0))).view("<i4").ravel() >> 8) / 2 ** 23

RATE = K.RATE
IDS = {"clave_dancehall": "riddim-clave-dancehall", "dembow": "riddim-dembow", "boom_riddim": "riddim-boom-riddim"}


def strikes(pack, kit, slot):
    """The twelve strikes of one pad, trimmed of the strip's silent padding: [zone][take]."""
    x = rd(os.path.join(pack, IDS[kit], "touch-%02d.wav" % slot))
    stride = len(x) // 12
    out = []
    for i in range(12):
        s = x[i * stride:(i + 1) * stride]
        nz = np.nonzero(np.abs(s) > 1e-6)[0]
        out.append(s[: nz[-1] + 1] if len(nz) else s[:1])
    return [out[z * 4:(z + 1) * 4] for z in range(3)]


def main(pack, out):
    checks = {}
    for kit, k in K.KITS.items():
        grid = {pid: strikes(pack, kit, i + 1) for i, pid in enumerate(K.PADS)}
        os.makedirs(os.path.join(out, kit, "pads"), exist_ok=True)
        for pid, zones in grid.items():
            for z, row in enumerate(zones):
                for t, y in enumerate(row):
                    K.write_wav(os.path.join(out, kit, "pads", f"{pid}_z{z}_t{t}.wav"), y)
        # The prototype's dealing and choking, with the Kotlin strikes in place of the prototype's renders.
        real = K.render_pad
        K.render_pad = lambda p, bpm, v, take, _g=grid, _k=k: _g[K.PADS[_k["pads"].index(p)]][K.ZONES.index(v)][take]
        try:
            K.write_wav(os.path.join(out, kit, "groove.wav"), K.play_groove(kit))
        finally:
            K.render_pad = real
        checks[kit] = {}
        for pid, p in zip(K.PADS, k["pads"]):
            zones = grid[pid]
            closest = max(K.take_distance_db(row[i], row[j]) for row in zones for i in range(4) for j in range(i + 1, 4))
            bright = [float(np.mean([K.upper_share_db(y, p["voice"]) for y in row])) for row in zones]
            checks[kit][pid] = {"label": p["label"], "voice": p["voice"], "closest_takes_db": round(closest, 1),
                                "brightness_soft_mid_hard_db": [round(b, 1) for b in bright],
                                "darker_when_softer": bool(bright[0] < bright[1] < bright[2])}
    json.dump(checks, open(os.path.join(out, "checks.json"), "w"), indent=1)
    print("wrote", out)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
