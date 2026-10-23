"""proto_template: copy this file to prototype one engine idea and hear it before any Kotlin.

What it does, in order (the same order a SnipSnap engine test would):
  1. `render` is your engine: macros in, raw audio at the internal 4x rate out.
  2. The house output stage (`snipkit.finish`) turns that into 44.1 kHz mono.
  3. The probes measure what a Kotlin test would assert: finite, peak, DC, pitch and its
     confidence, "every macro changes the sound", energy never grows, aliasing.
  4. A loop is planned in whole periods and its seam measured against a real preceding
     cycle (warm-up + loop + continuation), the only seam measurement that means anything.
  5. WAVs, a spectrogram and a JSON report land in an output folder you can listen to.

Run:  python3 proto_template.py out_dir
Edit: `render`, `MACROS`, `VOICES`. Keep the rest.

The example engine here is deliberately small: a struck modal object (bell-ish ratios) over a
support body, with three macros. STRIKE sets the mallet width (a narrower pulse is brighter:
the pulse's spectrum is the mallet's spectrum). BODY moves the support's modal spacing and
damping together, so it changes the object rather than a mix level. BRIGHT tilts the mode
gains. None of the numbers are measured; they are "shape" values to be replaced by listening.
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import snipkit as sk  # noqa: E402
import probes as pr   # noqa: E402

VOICES = ["BELL", "WOOD"]
MACROS = {"TUNE": 0.5, "STRIKE": 0.4, "BODY": 0.5, "BRIGHT": 0.5}
ROOT_MIDI = 48            # C3 at TUNE 0.5, two octaves of TUNE either side is the house habit
TUNE_SEMITONES = 24
SECONDS = 2.0


def midi_for(tune: float) -> int:
    return ROOT_MIDI + int(round((tune - 0.5) * TUNE_SEMITONES))


def hz_for(midi: int) -> float:
    return 440.0 * 2 ** ((midi - 69) / 12)


def mode_table(voice: str, bright: float):
    """(ratio, gain, t60). BELL uses rough church-bell partials, WOOD a short inharmonic set.
    These ratios are 'shape' values, not sourced measurements; a real engine cites a table."""
    if voice == "BELL":
        base = [(0.5, .12, 2.6), (1.0, 1.0, 2.4), (1.19, .5, 1.6), (1.5, .55, 1.4),
                (2.0, .6, 1.1), (2.51, .35, .8), (2.66, .3, .7), (3.01, .25, .5)]
    else:
        base = [(1.0, 1.0, .35), (2.21, .5, .22), (3.72, .3, .16), (5.9, .15, .1)]
    tilt = sk.lin(bright, -1.0, 1.0)
    out = []
    for ratio, gain, t60 in base:
        # BRIGHT tilts gain with log-ratio so the root stays put
        g = gain * (2.0 ** (tilt * np.log2(max(ratio, 0.5))))
        out.append((ratio, g, t60))
    return out


def support_table(body: float):
    """A hollow support under the object: BODY grows it (closer modes, longer ring, lower)."""
    scale = sk.exp_map(body, 0.8, 1.3)        # size (keeps the support's lowest mode at or above the root)
    t60 = sk.exp_map(body, 0.25, 1.4)         # ring
    # the support shares the root (ratio 1.0) so BODY never steals the pitch: a support with a
    # sub-octave mode reads an octave low to a pitch detector and to an ear
    return [(1.0, .5, t60), (1.58 / scale, .35, t60 * .8), (2.3 / scale, .25, t60 * .6),
            (3.1 / scale, .15, t60 * .5)]


def render(voice: str, macros: dict, midi: int, rate: int = sk.INTERNAL, seconds: float = SECONDS) -> np.ndarray:
    """The engine. Pure: same inputs, same samples (seeds via seed_for)."""
    hz = hz_for(midi)
    m = {**MACROS, **macros}
    width_ms = sk.exp_map(m["STRIKE"], 12.0, 0.15)            # harder strike, narrower pulse (its spectrum is the mallet)
    strike = sk.hann_pulse(width_ms, rate, 1.0, seconds)
    # a touch of seeded contact noise, shaped by the same pulse so it stays part of the contact
    contact = sk.noise_burst(width_ms * 2, rate, sk.seed_for(voice, midi, "contact"), .15, seconds, lowpass_hz=6000)
    excitation = strike + contact
    obj = sk.modal_ring(excitation, hz, mode_table(voice, m["BRIGHT"]), rate, compensate=True)
    obj /= max(1e-9, np.max(np.abs(obj)))                      # Modes.ring peak-normalize rule
    support = sk.modal_ring(obj * 0.3 + excitation * 0.2, hz, support_table(m["BODY"]), rate, compensate=True)
    support /= max(1e-9, np.max(np.abs(support)))
    mix = sk.lin(m["BODY"], 0.15, 0.6)
    return obj * (1 - mix) + support * mix


def main(out_dir: str) -> int:
    os.makedirs(out_dir, exist_ok=True)
    results = {"renders": [], "macro_activity_dB": {}, "loop": {}}

    # 1. voices at three notes
    for voice in VOICES:
        for tune in (0.25, 0.5, 0.75):
            midi = midi_for(tune)
            out = sk.finish(render(voice, {"TUNE": tune}, midi))
            name = f"{voice}_tune{tune:.2f}_midi{midi}"
            sk.write_wav(os.path.join(out_dir, name + ".wav"), out)
            rep = pr.report(out, sk.RATE, name, ref_hz=hz_for(midi))
            results["renders"].append(rep)
    # a spectrogram of the default note of the first voice
    pr.spectrogram_png(sk.finish(render(VOICES[0], {}, midi_for(0.5))), sk.RATE,
                       os.path.join(out_dir, f"{VOICES[0]}_default.png"), f"{VOICES[0]} default")

    # 2. every macro changes the sound: extremes, leveled, log-spectral distance
    midi = midi_for(0.5)
    for macro in MACROS:
        if macro == "TUNE":
            continue
        lo = sk.finish(render(VOICES[0], {macro: 0.0}, midi))
        hi = sk.finish(render(VOICES[0], {macro: 1.0}, midi))
        results["macro_activity_dB"][macro] = round(pr.macro_activity(lo, hi), 2)
        sk.write_wav(os.path.join(out_dir, f"{VOICES[0]}_{macro}_0.wav"), lo)
        sk.write_wav(os.path.join(out_dir, f"{VOICES[0]}_{macro}_1.wav"), hi)

    # 3. aliasing: energy above output Nyquist in the raw oversampled signal, before bandLimit
    raw = render(VOICES[0], {"STRIKE": 1.0, "BRIGHT": 1.0}, midi_for(0.9))
    results["alias_proxy_raw_bright_corner"] = round(pr.alias_proxy(raw, sk.INTERNAL), 6)

    # 4. a HOLD loop in whole periods, seam measured against a real preceding cycle.
    #    Here the "held" sound is the object re-struck softly every period of a slow texture;
    #    a real engine would plan its own sustain. The method is what matters:
    #    render warm-up + loop + continuation, then measure seam at the loop start.
    hz = hz_for(midi)
    loop_frames = sk.whole_period_loop_frames(hz, sk.RATE, 2.0)
    hz_fit = round(loop_frames / sk.RATE * hz) * sk.RATE / loop_frames   # the pitch that fits exactly
    warm = 5.0        # preroll past the longest t60 (2.6 s), or the seam measures the transient, not the loop
    total_s = warm + loop_frames / sk.RATE + 0.25
    rate = sk.INTERNAL
    t = np.arange(int(total_s * rate)) / rate
    # a periodic driver: the whole-period loop means every component is periodic in loop_frames
    drive = 0.2 * np.sin(2 * np.pi * hz_fit * t) * (1 + 0.5 * np.sin(2 * np.pi * (sk.RATE / loop_frames) * 2 * t))
    held = sk.modal_ring(drive, hz_fit, mode_table(VOICES[0], 0.5), rate, compensate=True)
    held_out = sk.band_limit(held, rate)
    held_out = sk.decimate4(held_out)
    held_out = sk.dc_block(held_out)
    loop_start = int(warm * sk.RATE)
    # Keys.seamError treats everything from loop_start to the END of the buffer as the loop,
    # so hand it exactly warm-up + loop: the 256 frames before the loop are compared with the
    # last 256 frames of the loop, one loop later. The warm-up is the "real preceding
    # cycle"; a loop pasted twice would be periodic by construction and read 0.
    seam = sk.seam_error(held_out[: loop_start + loop_frames], loop_start)
    # the exported loop is the stretch [loop_start, loop_start + loop_frames)
    loop = sk.level_to(held_out[loop_start: loop_start + loop_frames])
    sk.write_wav(os.path.join(out_dir, "LOOP_x4.wav"), np.tile(loop, 4))
    results["loop"] = {"frames": loop_frames, "hz_fit": round(hz_fit, 3), "cents_off": round(pr.cents(hz_fit, hz), 2),
                       "seam_error": seam, "passes_MAX_SEAM_ERROR": seam < sk.MAX_SEAM_ERROR}

    with open(os.path.join(out_dir, "report.json"), "w") as f:
        json.dump(results, f, indent=2)
    print(json.dumps(results, indent=2))
    print(f"\nwrote {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "proto_out"))
