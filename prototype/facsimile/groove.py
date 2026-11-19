"""FACSIMILE grooves: re-perform each reference loop with the replica drums, and measure it.

Every drum's events come from the reference: when it was hit, to the millisecond (so the swing
is the measured swing, not a swing knob), and how hard (so ghost notes stay ghost notes, and
are rendered as softer strikes, not as quieter copies). Each repeat of a drum deals the next of
four round-robin takes. Drums in a loop's choke group are monophonic: a new note cuts the last
with a 6 ms fade, as an 808 on a sampler pad does.

Each groove can be printed through a glue compressor (`glue`, settings per loop in kits.json,
fitted by fit_glue.py to the reference's peak-to-average ratio): a produced loop is mixed
through one, and the raw sum of replica drums measured 2 to 7 dB peakier than its reference.

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
            # an 808 the reference cut with its next note keeps its natural tail as a pad,
            # but in the groove it is cut at its own next hit, as the reference played it
            group = choke if did in choke else ([did] if model.get("self_choke") else None)
            if group:
                # the next note of any drum in the choke group cuts this one
                later = [int(round(x["t"] * RATE)) for d2 in group if d2 in loop["drums"]
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


GLUE_BLOCK_S = 0.001   # the compressor's control rate: one gain decision per millisecond


def glue(x: np.ndarray, threshold_db: float, ratio: float = 4.0, knee_db: float = 6.0,
         attack_ms: float = 10.0, release_ms: float = 120.0, makeup_db: float = 0.0
         ) -> np.ndarray:
    """A bus compressor, the kind a produced loop is printed through to glue its drums.

    Feed-forward, peak-sensing on 1 ms blocks, soft knee. The gain reduction rises with the
    attack time and falls with the release time (one-pole in dB), so a 10 ms attack lets each
    hit's front edge through before it clamps, and a 120 ms release lets it relax between
    hits. The loop wraps, so the compressor runs over it twice and keeps the second pass:
    bar one is compressed with the state the loop's end leaves, as it is when the loop repeats.
    """
    n = len(x)
    blk = int(GLUE_BLOCK_S * RATE)
    m = int(np.ceil(n / blk))
    padded = np.resize(x, m * blk)
    peak = np.abs(padded).reshape(m, blk).max(axis=1)
    level = 20 * np.log10(peak + 1e-9)
    over = level - threshold_db
    # soft knee gain computer: the gain reduction wanted at each block, in dB (positive)
    want = np.where(over <= -knee_db / 2, 0.0,
                    np.where(over >= knee_db / 2, over * (1 - 1 / ratio),
                             (1 - 1 / ratio) * (over + knee_db / 2) ** 2 / (2 * knee_db)))
    a = np.exp(-GLUE_BLOCK_S * 1000 / attack_ms)
    r = np.exp(-GLUE_BLOCK_S * 1000 / release_ms)
    gr = np.zeros(m)
    g = 0.0
    for _ in range(2):              # twice round the loop: the second pass has settled state
        for i in range(m):
            c = a if want[i] > g else r
            g = c * g + (1 - c) * want[i]
            gr[i] = g
    gain_db = np.interp(np.arange(n), np.arange(m) * blk + blk / 2, -gr, period=m * blk)
    return x * 10 ** ((gain_db + makeup_db) / 20)


def main(models: Path, out: Path) -> None:
    out.mkdir(parents=True, exist_ok=True)
    kits = json.loads((Path(__file__).resolve().parent / "kits.json").read_text())
    for f in sorted(models.glob("[0-9]*.json")):
        key = f.stem
        loop = json.loads(f.read_text())
        choke = kits["choke"].get(key, [])
        y = render_loop(loop, choke)
        sf.write(out / f"loop_{key}.wav", y.astype(np.float32), RATE, subtype="FLOAT")
        line = f"{len(y) / RATE:.2f}s peak {np.abs(y).max():.3f}"
        if key in kits.get("glue", {}):
            gl = glue(y, **{k: v for k, v in kits["glue"][key].items() if k != "note"})
            sf.write(out / f"loop_{key}_glued.wav", gl.astype(np.float32), RATE,
                     subtype="FLOAT")
            line += f", glued peak {np.abs(gl).max():.3f}"
        print(key, line)


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
