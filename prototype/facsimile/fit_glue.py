"""FACSIMILE glue: fit each groove's bus compressor to its reference.

A produced loop is mixed through compression that pulls its drums together. The raw sum of
replica drums measured 2 to 7 dB peakier than its reference (crest factor: peak over RMS).

What the measurement showed about that compression: a classic slow "glue" setting (10 ms
attack) makes a drum loop PEAKIER, not tighter, because it lets each hit's front edge through
and turns down the body behind it. Only attacks of 0.3 to 1 ms brought the replicas' crest down
to their references': the references were tightened by fast peak control, closer to a limiter.
So the attack is fitted too. The fit searches attack (0.3, 0.5, 1, 2 ms) and threshold (every
1.5 dB down to 30 dB under the groove's peak) at 4:1, 120 ms release and a 6 dB soft knee, and
keeps, among settings within 0.5 dB of the reference's crest, the slowest attack and then the
least compression; failing that, the closest setting, which is reported. A makeup gain then
matches the reference's RMS level.

Reads the reference loops (licensed PCM) and the raw replica grooves; writes only numbers into
kits.json's "glue" section, which groove.py reads.

    python3 -I fit_glue.py REF_DIR GROOVE_DIR
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))  # run with -I: import our own modules
import groove  # noqa: E402

ATTACKS_MS = (2.0, 1.0, 0.5, 0.3)   # slowest first: the slowest that gets there keeps most punch
DEPTHS_DB = np.arange(1.5, 30.01, 1.5)  # threshold this far under the groove's peak
RATIO = 4.0
CLOSE_DB = 0.5


def crest_db(x: np.ndarray) -> float:
    x = x - np.mean(x)
    return float(20 * np.log10(np.abs(x).max() / (np.sqrt(np.mean(x * x)) + 1e-12)))


def rms_db(x: np.ndarray) -> float:
    return float(10 * np.log10(np.mean(x * x) + 1e-12))


def fit_one(rep: np.ndarray, target_crest: float):
    if crest_db(rep) <= target_crest:              # already as tight as the reference
        return None, crest_db(rep)
    peak_db = 20 * np.log10(np.abs(rep).max() + 1e-12)
    tried = []
    for attack in ATTACKS_MS:
        for depth in DEPTHS_DB:
            s = dict(threshold_db=round(float(peak_db - depth), 2), ratio=RATIO,
                     attack_ms=attack)
            c = crest_db(groove.glue(rep, **s))
            tried.append((abs(c - target_crest), s, c))
            if abs(c - target_crest) <= CLOSE_DB:
                return s, c                         # slowest attack, least compression
    _, s, c = min(tried, key=lambda t: t[0])
    return s, c


def main(ref_dir: Path, grooves: Path) -> None:
    kits_path = HERE / "kits.json"
    kits = json.loads(kits_path.read_text())
    glue = {}
    for p in sorted(ref_dir.glob("*.wav"), key=lambda p: int(re.search(r"TRNDY_(\d+)_", p.stem)
                                                               .group(1))):
        key = str(int(re.search(r"TRNDY_(\d+)_", p.stem).group(1)))
        ref = sf.read(p, always_2d=True)[0].mean(1)
        rep = sf.read(grooves / f"loop_{key}.wav")[0]
        target = crest_db(ref)
        settings, got = fit_one(rep, target)
        if settings is None:
            print(f"{key:4s} crest ref {target:5.1f}  raw {crest_db(rep):5.1f}: already as tight, "
                  "no compression")
            continue
        glued = groove.glue(rep, **settings)
        makeup = rms_db(ref) - rms_db(glued)
        settings["makeup_db"] = round(makeup, 2)
        glue[key] = settings
        final = groove.glue(rep, **settings)
        print(f"{key:4s} crest ref {target:5.1f}  raw {crest_db(rep):5.1f}  glued {got:5.1f} dB  "
              f"(threshold {settings['threshold_db']:6.1f} dB, attack {settings['attack_ms']} ms, "
              f"makeup {makeup:+5.1f} dB, level vs ref {rms_db(final) - rms_db(ref):+.2f} dB, "
              f"peak {np.abs(final).max():.2f})")
    kits["glue"] = glue
    kits["glue_note"] = ("Bus compression per groove (groove.glue), fitted by fit_glue.py to "
                         "the reference's crest factor: 4:1, release 120 ms, 6 dB soft knee, "
                         "attack and threshold fitted (a slow attack made the grooves peakier); "
                         "the makeup gain matches the reference's RMS level.")
    kits_path.write_text(json.dumps(kits, indent=1) + "\n")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
