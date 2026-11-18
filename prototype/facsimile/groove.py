"""FACSIMILE grooves: re-perform each reference loop with the replica drums, and measure it.

Every drum's events come from the reference: when it was hit, to the millisecond (so the swing
is the measured swing, not a swing knob), and how hard (so ghost notes stay ghost notes, and
are rendered as softer strikes, not as quieter copies). Each repeat of a drum deals the next of
four round-robin takes. Drums in a loop's choke group are monophonic: a new note cuts the last
with a 6 ms fade, as an 808 on a sampler pad does.

Reads only models/*.json and kits.json; never the reference audio. The comparison against the
reference lives in measure.py, which is the only part that reads reference PCM.

    python3 -I groove.py MODELS_DIR OUT_DIR
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parent))  # run with -I: import our own synth
import synth  # noqa: E402

RATE = synth.RATE
TAKES = 4
CHOKE_FADE_S = 0.006


def velocity_of(gain: float) -> float:
    """The render's level follows velocity squared, so a hit measured at `gain` (amplitude,
    relative to the drum's loudest) is played at sqrt(gain)."""
    return float(np.clip(np.sqrt(gain), 0.05, 1.0))


def render_loop(loop: dict, choke: list[str], oversample: int = synth.OVERSAMPLE,
                drums: list[str] | None = None) -> np.ndarray:
    n = int(round(loop["seconds"] * RATE))
    out = np.zeros(n)
    cache: dict = {}
    for did, model in loop["drums"].items():
        if drums is not None and did not in drums:
            continue
        events = sorted(model["events"], key=lambda e: e["t"])
        lane = np.zeros(n)
        for i, e in enumerate(events):
            v = round(velocity_of(e["gain"]), 2)
            take = i % TAKES
            key = (did, v, take)
            if key not in cache:
                cache[key] = synth.render(model, v, take, oversample)
            y = cache[key]
            start = int(round(e["t"] * RATE))
            if did in choke:
                # the next note of any drum in the choke group cuts this one
                later = [int(round(x["t"] * RATE)) for d2 in choke if d2 in loop["drums"]
                         for x in loop["drums"][d2]["events"]]
                later = sorted(((t - start) % n) for t in later if (t - start) % n > 0)
                if later and later[0] < len(y):
                    stop = later[0]
                    y = y.copy()
                    fade = int(CHOKE_FADE_S * RATE)
                    y[max(0, stop - fade):stop] *= np.linspace(1, 0, min(fade, stop))
                    y[stop:] = 0
            idx = (np.arange(len(y)) + start) % n   # the loop wraps, tails ring into bar one
            np.add.at(lane, idx, y)
        out += lane
    return out


def main(models: Path, out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    kits = json.loads((Path(__file__).resolve().parent / "kits.json").read_text())
    for f in sorted(models.glob("[0-9]*.json")):
        key = f.stem
        loop = json.loads(f.read_text())
        choke = kits["choke"].get(key, [])
        y = render_loop(loop, choke)
        sf.write(out / f"loop_{key}.wav", y.astype(np.float32), RATE, subtype="FLOAT")
        print(key, f"{len(y) / RATE:.2f}s peak {np.abs(y).max():.3f}")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
