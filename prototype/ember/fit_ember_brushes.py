"""Fit EMBER's brush sweep to CC0 recordings of brush stirs on a snare. Measured numbers only; no recording ships.

The owner heard round 0b's sweep as "hail hitting a roof" (2026-11-14). It was a cloud of separate micro-impulses,
each striking the head and the wires: measured, its top half was impulsive (median kurtosis of the >2 kHz signal in
20 ms frames 5.7 to 7.8, where a smooth hiss is 3) and its spectrum peaked at the head's own pitch (253 to 320 Hz).
Real stirs measure 2.9 to 3.0, with a broad hump from about 500 Hz to 5 kHz. A brush sweep is many wires sliding at
once, so it sums to a smooth noise; it is not a train of hits.

So the sweep becomes a fitted noise, like EMBER's closed hats: what the recordings measure, per third-octave band,
at each of four dynamic levels.
- spectrum: each band's mean level re the frame's overall level (50 ms frames, 25 ms hop, active frames only);
- follow: how many dB each band moves per dB of overall level (least squares across frames), so a faster or
  harder moment of the stroke is brighter, as measured;
- wobble: the standard deviation of the overall level over 50 ms frames, and of its 250 ms smoothing.
Dynamic levels dl1 (softest, -41 dBFS rms) to dl4 (loudest, -28 dBFS). The main microphone, not the close "skin"
one: EMBER's own DISTANCE places the mic. dl4's rr1 main-mic file is byte-identical to its skin-mic file in the
library, so dl4 is fitted from rr2 to rr4.

Source: Karoryfer Samples, Swirly Drums (CC0 1.0), github.com/sfzinstruments/karoryfer.swirly-drums at
c40dafe0011cb2e54c0c220ff0fa308a11fc60f5, Samples/snare_stir/. The files and sha256 are in NOTICE.txt.
The library's WAVs are already mono 44.1 kHz.

The brush roll is fitted the same way from the library's four snare flutters (Samples/snare_flutter, main mic, one
dynamic level): round 0b's roll was brush taps whose wires each struck the head (median kurtosis 4.1 against the
flutters' 3.1, and a spectrum peaked at the head's pitch).

Usage: python3 -I prototype/ember/fit_ember_brushes.py <Samples/snare_stir dir> <out.json>
"""
import json
import os
import sys

import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "scripts"))
import fit_breaker_cymbals as fb  # noqa: E402  (BANDS and band_sos: the same bands as the cymbals)

FRAME, HOP = int(0.05 * fb.FS), int(0.025 * fb.FS)
FILES = {1: ["rr1", "rr2", "rr3", "rr4"], 2: ["rr1", "rr2", "rr3", "rr4"], 3: ["rr1", "rr2", "rr3", "rr4"], 4: ["rr2", "rr3", "rr4"]}
SOURCE = ("Karoryfer Samples, Swirly Drums (CC0 1.0), sfzinstruments/karoryfer.swirly-drums@"
          "c40dafe0011cb2e54c0c220ff0fa308a11fc60f5, Samples/snare_stir and Samples/snare_flutter, main mic; fitted by "
          "prototype/ember/fit_ember_brushes.py. The recordings do not ship.")


def load(path):
    from scipy.io import wavfile
    r, x = wavfile.read(path)
    assert r == fb.FS, path
    x = x.astype(np.float64)
    if x.ndim > 1:
        x = x.mean(axis=1)
    return x / np.max(np.abs(x))


def frames_db(x):
    """(overall dB, band dB [bands x frames]) over 50 ms frames, active frames only (within 25 dB of the loudest)."""
    bands = np.array([signal.sosfiltfilt(fb.band_sos(fc), x) for fc in fb.BANDS])
    k = (len(x) - FRAME) // HOP
    tot = np.array([10 * np.log10(np.mean(x[i * HOP:i * HOP + FRAME] ** 2) + 1e-20) for i in range(k)])
    bd = np.array([[10 * np.log10(np.mean(b[i * HOP:i * HOP + FRAME] ** 2) + 1e-20) for i in range(k)] for b in bands])
    keep = tot > tot.max() - 25
    return tot[keep], bd[:, keep]


def fit_level(paths):
    tots, bds = [], []
    for p in paths:
        t, b = frames_db(load(p))
        tots.append(t); bds.append(b)
    tot = np.concatenate(tots)
    bd = np.concatenate(bds, axis=1)
    rel = bd - tot[None, :]
    spectrum = rel.mean(axis=1)
    c = tot - tot.mean()
    follow = (rel * c[None, :]).sum(axis=1) / (c ** 2).sum()          # extra dB per band per dB of overall level
    smooth = np.convolve(c, np.ones(10) / 10, "same")
    return dict(spectrum=[round(float(v), 2) for v in spectrum], follow=[round(float(v), 3) for v in follow],
                wobble=round(float(np.std(c - smooth)), 2), sway=round(float(np.std(smooth)), 2))


if __name__ == "__main__":
    d, outp = sys.argv[1], sys.argv[2]
    levels = {f"dl{k}": fit_level([os.path.join(d, f"stir_dl{k}_{rr}.wav") for rr in v]) for k, v in FILES.items()}
    flutter_dir = os.path.join(os.path.dirname(os.path.normpath(d)), "snare_flutter")
    flutter = fit_level([os.path.join(flutter_dir, f"flutter_rr{k}.wav") for k in (1, 2, 3, 4)])
    data = dict(source=SOURCE, bands=[round(b, 3) for b in fb.BANDS], frame_s=0.05, levels=levels, flutter=flutter,
                velocity={"dl1": 0.3, "dl2": 0.55, "dl3": 0.8, "dl4": 1.0})
    json.dump(data, open(outp, "w"), separators=(",", ":"))
    print("flutter", "wobble", flutter["wobble"], "sway", flutter["sway"])
    for k, v in levels.items():
        print(k, "wobble", v["wobble"], "sway", v["sway"], "spectrum", np.round(v["spectrum"]).astype(int).tolist())
