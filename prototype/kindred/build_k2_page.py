"""Round K2 listening pack: the four KINDRED kits as they land, cut from their MPC 3 export.

Run after `./gradlew :shell:generateKindredKits` (writes testkit/kindred-kits/):
    python3 build_k2_page.py ../../testkit/kindred-kits OUT
Writes OUT/audio/*.wav and OUT/clips.json:
- each kit's round-2 groove played from the exported slices: the zone picked by velocity (X = 1, x = .6, o = .3),
  each pad's takes walked in order per zone, the closed hat choking the open one, each pad at its mixer level and
  the sampler's velocity-to-level (.35 + .65 v, the prototype's touch law);
- every pad's twelve strikes, soft to hard, four takes each, at the same touch law;
- the phrase (A15) and riser (A16) at full velocity.
Every clip comes from the exported files; nothing here re-renders the engine.
"""
import json
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "..", ".claude", "skills", "luthier", "scripts"))
import snipkit as sk  # noqa: E402
import kindred_kits as kk  # noqa: E402

RATE = 44100
ZONE = {"X": 2, "x": 1, "o": 0}
VEL = [.3, .6, 1.0]


def touch(v):
    return .35 + .65 * v


def main(src, out):
    os.makedirs(f"{out}/audio", exist_ok=True)
    man = json.load(open(f"{src}/manifest.json"))
    clips = {}
    for k in man["kits"]:
        kid, name = k["id"], k["id"].split("-")[1].upper()
        pads = {p["slot"]: p for p in k["pads"]}

        def sl(slot, z, t):
            x, _ = sk.read_wav(f"{src}/{kid}/slices/pad-{slot:02d}-z{z}-t{t}.wav")
            return x

        # the groove, as the round-2 audition played it, from the exported slices
        K = kk.KITS[name]
        step = 60.0 / K["bpm"] / 4
        n = int((32 * step + 3.0) * RATE)
        mix = np.zeros(n); openhat = np.zeros(n)
        counters = {}
        events = sorted((si, pi, ch) for pi, row in K["groove"].items() for si, ch in enumerate(row) if ch in ZONE)
        for si, pi, ch in events:
            slot, z = pi + 1, ZONE[ch]
            t = counters.get((slot, z), 0) % 4; counters[(slot, z)] = t + 1
            x = sl(slot, z, t) * pads[slot]["level"] * touch(VEL[z])
            a = int(si * step * RATE); x = x[: n - a]
            cls = pads[slot]["class"]
            if cls == "HAT_CLOSED":
                f = min(int(.005 * RATE), n - a)
                openhat[a:a + f] *= np.linspace(1, 0, f); openhat[a + f:] = 0
            (openhat if cls == "HAT_OPEN" else mix)[a:a + len(x)] += x
        g = (mix + openhat)[: int(32 * step * RATE + .8 * RATE)]
        period = int(32 * step * RATE)
        gg = np.zeros(period + len(g)); gg[:len(g)] += g; gg[period:period + len(g)] += g
        sk.write_wav(f"{out}/audio/{name.lower()}_groove_k2.wav", kk.kp.level(gg, .13))

        # every pad: twelve strikes, soft to hard
        for slot in range(1, 15):
            parts = [sl(slot, z, t) * touch(VEL[z]) for z in range(3) for t in range(4)]
            sk.write_wav(f"{out}/audio/{name.lower()}_a{slot:02d}_k2.wav", kk.kp.strip(parts, .15) * .9)
        for slot in (15, 16):
            sk.write_wav(f"{out}/audio/{name.lower()}_a{slot:02d}_k2.wav", sl(slot, 2, 0) * .9)
        clips[name] = {"bpm": K["bpm"], "pads": [{"slot": p["slot"], "name": p["name"], "class": p["class"],
                                                  "level": p["level"]} for p in k["pads"]]}
    json.dump(clips, open(f"{out}/clips.json", "w"), indent=1)
    print("K2 clips for", ", ".join(clips))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
