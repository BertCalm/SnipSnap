"""Fit MIRRORBALL's shakers and hand clap to CC0 recordings: noise in third-octave bands, each band following the
recording's own envelope.

The owner heard round 1b's shakers (SKIN) and hand clap (THUMP) as short of "a pro sound" (2026-11-12) and asked that
"pro" mean "like real recordings". A shaker and a clap are noise instruments: beads on a shell, two palms and the air
trapped between them. What makes them real is how their spectrum moves in time: a shaker swells over 50 to 100 ms as
the beads are thrown, a clap is a hard crack whose cavity resonance dies in a few tens of milliseconds. So the model
is the recording's own time-frequency envelope driving fresh noise (the residual of Serra and Smith's sines-plus-noise,
with no sines):

- bands: third-octaves from 100 Hz to 16 kHz (23), each fresh Gaussian noise through two cascaded RBJ band-passes
  (the same filter as Dsp.Biquad.bandpass and BREAKER's stick);
- envelope: each band's energy in 2.5 ms steps, the takes aligned on their loudest 20 ms and averaged (in power)
  over every take of a layer, in dB re the layer's loudest frame;
- level: one gain per band, matched to the recordings by rendering and measuring the same way (three passes);
- layers: the clap has a soft and a hard recording set, and velocity blends their envelopes in dB; the shakers have
  one dynamic each.

Voices and sources (all CC0 1.0):
  shaker:      Virtuosity Drums, Samples/perc/close/shaker/LShaker_Shake1D_rr1..7_Close.wav (a forward shake)
               github.com/sfzinstruments/virtuosity_drums @ 9f04cf9a734527edfbb0a4eee1f674e45bbf71bc    Philly A13
  shaker_wood: VSCO 2 CE, VSCO 1 Percussion/varWood/Camo's Shaker/shake1..8.wav
               github.com/sgossner/VSCO-2-CE (HEAD at fetch, see docs/MIRRORBALL.md)                      Loft A13
  clap:        body_percussion, Samples/body/handclap_vl1_rr1..4.wav (soft), handclap_vl2_rr1..6.wav (hard)
               github.com/sfzinstruments/body_percussion @ 4ac9d8966679c648b62fa10a188179e186b97f24      Loft A05
  tambourine:  BACKWASH v5's tambourine-1-0/1-1 (soft) and 2-0/2-1 (hard), already in this repository      Philly A05
               (the rattle under the fitted jingles of MirrorballTambourine; see docs/MIRRORBALL.md)
Decode each to mono 44.1 kHz float WAV, spaces in names replaced by underscores, e.g.
  ffmpeg -i shake1.wav -ac 1 -ar 44100 -c:a pcm_f32le shake1.wav

Usage: python3 -I scripts/fit_mirrorball_textures.py <dir of decoded WAVs> <out.json> [<audition dir>]
Only the fitted numbers ship (synth/src/main/resources/mirrorball/textures.json); the recordings never do.
"""
import glob
import json
import math
import os
import sys

import numpy as np
from scipy import signal
from scipy.io import wavfile

FS = 44100
HOP = .0025
BACKWASH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "synth", "src", "main", "resources", "backwash", "v5")
BANDS = [100 * 2 ** (k / 3) for k in range(23)]


def load(path):
    r, x = wavfile.read(path)
    assert r == FS, path
    x = x.astype(np.float64)
    if x.ndim > 1:
        x = x.mean(axis=1)
    x /= np.max(np.abs(x))
    on = max(0, int(np.argmax(np.abs(x) > .05)) - int(.001 * FS))
    return x[on:]


def edges(fc):
    return fc / 2 ** (1 / 6), min(fc * 2 ** (1 / 6), FS * .49)


def rbj_bandpass(fc, q):
    w = 2 * math.pi * fc / FS
    al = math.sin(w) / (2 * q)
    a0 = 1 + al
    return np.array([al, 0, -al]) / a0, np.array([1 + al, -2 * math.cos(w), 1 - al]) / a0


def band(x, fc):
    """The measuring filter: a fourth-order Butterworth band-pass, zero phase (as BREAKER's fit measures)."""
    lo, hi = edges(fc)
    return signal.sosfiltfilt(signal.butter(4, [lo / (FS / 2), hi / (FS / 2)], "band", output="sos"), x)


def envelopes(x, frames):
    """Each band's power per HOP over `frames` hops (zero past the clip's end)."""
    h = int(HOP * FS)
    out = np.zeros((len(BANDS), frames))
    for b, fc in enumerate(BANDS):
        y = band(x, fc) ** 2
        for i in range(frames):
            s = y[i * h:(i + 1) * h]
            out[b, i] = s.mean() if len(s) else 0.0
    return out


def render(p, layer, seed=1, velocity_db=None):
    """The synthesis, in Python (MirrorballTexture.kt is its twin): fresh noise per band, shaped by the envelope."""
    rng = np.random.default_rng(seed)
    env = np.array(p["env"][layer]) if velocity_db is None else velocity_db
    frames = env.shape[1]
    h = int(HOP * FS)
    n = frames * h
    t = (np.arange(n) + .5) / h - .5
    out = np.zeros(n)
    for b, fc in enumerate(BANDS):
        lo, hi = edges(fc)
        if lo >= FS * .49:
            continue
        centre = math.sqrt(lo * hi)
        bb, aa = rbj_bandpass(centre, centre / (hi - lo))
        noise = signal.lfilter(bb, aa, signal.lfilter(bb, aa, rng.standard_normal(n)))
        amp = 10 ** ((np.interp(t, np.arange(frames), env[b]) + p["gain"][b]) / 20)
        out += noise * amp
    return out


def fit_voice(paths_by_layer, seconds):
    frames = int(seconds / HOP)
    p = {"env": {}, "gain": [0.0] * len(BANDS)}
    targets = {}
    for layer, paths in paths_by_layer.items():
        clips = [load(q) for q in paths]
        # Align the takes on their loudest 20 ms before averaging, at the median of those frames. Aligned only at the
        # onset, takes whose main burst lands at different times (the wooden shaker's peak at 160 to 260 ms) averaged
        # into a smear that the grab-click at the very start outweighed: the fit peaked at 7.5 ms.
        long = [envelopes(x, frames * 2) for x in clips]
        # The peak of a 20 ms smoothing: at 2.5 ms a take's grab-click is the loudest single frame, but the burst
        # carries the energy.
        peaks = [int(np.argmax(np.convolve(e.sum(axis=0), np.ones(8) / 8, "same"))) for e in long]
        target = int(np.median(peaks))
        aligned = []
        for e, pk in zip(long, peaks):
            shift = pk - target
            if shift >= 0:
                a = e[:, shift:shift + frames]
            else:
                a = np.concatenate([np.zeros((e.shape[0], -shift)), e[:, :frames + shift]], axis=1)
            if a.shape[1] < frames:
                a = np.concatenate([a, np.zeros((e.shape[0], frames - a.shape[1]))], axis=1)
            aligned.append(a)
        power = np.mean(aligned, axis=0)
        db = 10 * np.log10(power + 1e-14)
        db -= db.max()
        p["env"][layer] = np.maximum(db, -90.0)
        targets[layer] = db
    # One gain per band: render the hardest layer, measure the same way, correct, three passes.
    ref = "hard" if "hard" in targets else next(iter(targets))
    for _ in range(3):
        y = render(p, ref)
        got = 10 * np.log10(envelopes(y, frames) + 1e-14)
        got -= got.max()
        # Compare where the recording is within 40 dB of its loudest frame: the body, not the noise floor.
        mask = targets[ref] > -40
        for b in range(len(BANDS)):
            m = mask[b]
            if m.any():
                p["gain"][b] -= float(np.mean(got[b][m] - targets[ref][b][m])) * .9
    # The check: four renders averaged in power (the target is an average over takes too), both smoothed to 20 ms,
    # because one render's narrow bands flicker frame to frame (a 100 Hz third-octave's noise has a 40 ms time
    # constant), which is not an error of the model.
    k = np.ones(8) / 8
    got = np.mean([envelopes(render(p, ref, seed=s), frames) for s in range(1, 5)], axis=0)
    got = 10 * np.log10(np.array([np.convolve(g, k, "same") for g in got]) + 1e-14)
    want = 10 * np.log10(np.array([np.convolve(10 ** (t / 10), k, "same") for t in targets[ref]]) + 1e-14)
    got -= got.max()
    want -= want.max()
    mask = want > -40
    err = [float(np.mean(np.abs(got[b][mask[b]] - want[b][mask[b]]))) for b in range(len(BANDS)) if mask[b].any()]
    report = {"layers": list(targets), "frames": frames, "band_envelope_error_db_median": round(float(np.median(err)), 2),
              "band_envelope_error_db_max": round(float(np.max(err)), 2)}
    p["env"] = {k: [[round(float(v), 1) for v in row] for row in e] for k, e in p["env"].items()}
    p["gain"] = [round(g, 2) for g in p["gain"]]
    return p, report


def main(d, outp, audition=None):
    voices = {
        "shaker": ({"hard": sorted(glob.glob(f"{d}/LShaker_Shake1D_rr*_Close.wav"))}, .7),
        "shaker_wood": ({"hard": sorted(glob.glob(f"{d}/shake[1-8].wav"))}, .6),
        "clap": ({"soft": sorted(glob.glob(f"{d}/handclap_vl1_rr*.wav")),
                  "hard": sorted(glob.glob(f"{d}/handclap_vl2_rr*.wav"))}, .5),
        # The tambourine's rattle: BACKWASH's CC0 recordings, already in the repository (layer 1 soft, 2 hard).
        "tambourine": ({"soft": [f"{BACKWASH}/tambourine-1-{t}.wav" for t in (0, 1)],
                        "hard": [f"{BACKWASH}/tambourine-2-{t}.wav" for t in (0, 1)]}, .8),
    }
    data = {"source": "Shaker: Virtuosity Drums LShaker_Shake1D close (CC0). Wooden shaker: VSCO 2 CE Camo's Shaker (CC0). "
                      "Clap: body_percussion handclap vl1/vl2 (CC0). Tambourine: BACKWASH v5 tambourine-1/2 (CC0). Fitted by scripts/fit_mirrorball_textures.py; "
                      "the recordings do not ship.",
            "bands": [round(b, 3) for b in BANDS], "hopS": HOP, "voices": {}}
    for name, (layers, seconds) in voices.items():
        assert all(layers.values()), f"{name}: recordings missing in {d}"
        p, rep = fit_voice(layers, seconds)
        data["voices"][name] = p
        print(name, json.dumps(rep), "takes", {k: len(v) for k, v in layers.items()})
        if audition:
            os.makedirs(audition, exist_ok=True)
            for layer in p["env"]:
                for s in (1, 2):
                    y = render({"env": {layer: np.array(p["env"][layer])}, "gain": p["gain"]}, layer, seed=s)
                    wavfile.write(os.path.join(audition, f"{name}-synth-{layer}-{s}.wav"), FS, (y / np.abs(y).max() * .9).astype(np.float32))
                x = load(layers[layer][0])
                wavfile.write(os.path.join(audition, f"{name}-recorded-{layer}.wav"), FS, (x * .9).astype(np.float32))
    os.makedirs(os.path.dirname(outp), exist_ok=True)
    json.dump(data, open(outp, "w"), separators=(",", ":"))


if __name__ == "__main__":
    if len(sys.argv) not in (3, 4):
        print(__doc__)
        sys.exit(2)
    main(*sys.argv[1:])
