"""Fit EMBER's Latin percussion to CC0 recordings. Measured numbers only; no recording ships.

The owner asked for a Latin jazz kit with new voices (2026-11-15: "New sounds first"): timbales, cowbell, clave,
bongos, guiro and shaker. Where a CC0 recording exists, the voice is that instrument's own resonances, fitted with
the fitter EMBER's cymbals use (`fit_ember_cymbals.py`, which wraps BREAKER's `scripts/fit_breaker_cymbals.py`).
There is no CC0 recording of timbales in the libraries the repository uses, so the timbales and their shell (the
paila) are physical models in `ember_proto.py`, by ear.

Source: the Versilian Community Sample Library (VCSL, CC0 1.0), github.com/sgossner/VCSL at
c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e, the "Mid" microphone. The files and their sha256 are in NOTICE.txt. Decode
each to mono 44.1 kHz float WAV under the names below (the stereo files averaged to mono), then run:
  python3 -I prototype/ember/fit_ember_latin.py <dir of decoded WAVs> <out.json>

Voices:
- cowbell: COWBELL. Cowbell1 Hit, hard v4, soft v2. 200 modes, residual.
- clave: CLAVE. Claves1 Hit, hard v3, soft v1. 120 modes, residual.
- bongo_high, bongo_low: BONGO_HIGH and BONGO_LOW (the macho and the hembra), an open tone with the fingers. Hard
  v3 rr1, soft v1. 160 modes each, residual.
- shaker: SHAKER. The large shaker's single hit, fitted as band envelopes (as the closed hats are): hard from rr1.
  The library recorded one dynamic, so the soft table is the hard one with its top tilted down, 1.5 dB per
  third-octave over 2 kHz to at most 9 dB (by ear, "shape": a softer shake throws the seeds less hard). The second
  take (rr2) is held out to measure the fit against.
- guiro: GUIRO. A scrape is clicks, one per ridge. Measured: the click's spectrum (band levels over a steady 300 ms
  of the medium stroke, as `stick` measures a stick), the ridge rate (the stroke's envelope modulation: 41 to 77 Hz
  across the slow, medium and fast strokes, median 55), and the stroke lengths (a short scrape 0.09 s, fast 0.24 s,
  medium 0.6 s, slow 1.15 s). The renderer makes the clicks; this only measures them.
"""
import json
import os
import sys

import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "..", "scripts"))
import fit_breaker_cymbals as fb  # noqa: E402
import fit_ember_cymbals as fe  # noqa: E402

SOURCE = ("Versilian Community Sample Library (VCSL, CC0 1.0), sgossner/VCSL@c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e, "
          "Mid microphone; fitted by prototype/ember/fit_ember_latin.py with EMBER's cymbal fitter. The recordings do not ship.")

SOFT_TILT_DB = 1.5        # per third-octave over 2 kHz: the shaker's soft table (shape, by ear)
SOFT_TILT_MAX = 9.0


def shaker(hard_path, held_path):
    v = fe.envelope_voice(hard_path, held_path)
    tilt = [-min(SOFT_TILT_MAX, SOFT_TILT_DB * max(0, k - fb.band_of(2000.0))) for k in range(len(fb.BANDS))]
    v["soft"] = [[round(val + tilt[b], 2) for val in row] for b, row in enumerate(v["hard"])]
    v["stickSoft"] = [round(s * 10 ** (tilt[b] / 20), 5) for b, s in enumerate(v["stick"])]
    return v


def ridge_rate(path):
    """The stroke's envelope modulation, 80 ms windows over the loud part: the ridges the stick crosses a second."""
    x = fb.load(path)
    hp = signal.sosfilt(signal.butter(4, 1000 / (fb.FS / 2), 'high', output='sos'), x)
    env = signal.sosfilt(signal.butter(2, 1500 / (fb.FS / 2), output='sos'), np.abs(hp))
    lvl = 20 * np.log10(np.convolve(np.abs(x), np.ones(441) / 441, 'same') + 1e-9)
    lvl -= lvl.max()
    w = int(.08 * fb.FS)
    rates = []
    for a in range(0, len(x) - w, w):
        if lvl[a:a + w].max() < -20:
            continue
        s = env[a:a + w] - env[a:a + w].mean()
        spec = np.abs(np.fft.rfft(s * np.hanning(w), 8 * w))
        f = np.fft.rfftfreq(8 * w, 1 / fb.FS)
        m = (f > 30) & (f < 120)
        rates.append(float(f[m][np.argmax(spec[m])]))
    return rates


def stroke_seconds(path):
    """How long the stroke stays within 20 dB of its loudest 20 ms."""
    x = fb.load(path)
    e = 20 * np.log10(np.convolve(np.abs(x), np.ones(882) / 882, 'same') + 1e-9)
    on = np.flatnonzero(e > e.max() - 20)
    return round(float((on[-1] - on[0]) / fb.FS), 3)


def guiro(d):
    med = fb.load(d("guiro_med"))
    a = fb.onset(med) + int(.15 * fb.FS)
    seg = med[a:a + int(.3 * fb.FS)]
    rates = []
    for k in ("guiro_slow", "guiro_med", "guiro_fast"):
        rates += ridge_rate(d(k))
    return dict(click=[round(v, 5) for v in fb.stick(seg)], rate=round(float(np.median(rates)), 1),
                rateRange=[round(min(rates), 1), round(max(rates), 1)],
                strokes={k.removeprefix("guiro_"): stroke_seconds(d(k)) for k in ("guiro_hit", "guiro_fast", "guiro_med", "guiro_slow")})


if __name__ == "__main__":
    src, outp = sys.argv[1], sys.argv[2]
    d = lambda name: os.path.join(src, name + ".wav")
    data = dict(
        source=SOURCE,
        bands=[round(b, 3) for b in fb.BANDS],
        voices=dict(
            cowbell=fe.modal_voice(d("cowbell_hard"), d("cowbell_soft"), 200),
            clave=fe.modal_voice(d("clave_hard"), d("clave_soft"), 120),
            bongo_high=fe.modal_voice(d("bongo_high_hard"), d("bongo_high_soft"), 160),
            bongo_low=fe.modal_voice(d("bongo_low_hard"), d("bongo_low_soft"), 160),
            shaker=shaker(d("shaker_hard"), d("shaker_hard2")),
        ),
        guiro=guiro(d),
    )
    json.dump(data, open(outp, "w"), separators=(",", ":"))
    for k, v in data["voices"].items():
        print(f"{k}: {len(v.get('modes', []))} modes" if "modes" in v else f"{k}: band envelopes")
    print("guiro:", {k: v for k, v in data["guiro"].items() if k != "click"})
