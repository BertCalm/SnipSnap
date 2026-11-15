"""Fit EMBER's cymbals and hats to CC0 recordings of a jazz club kit. Measured numbers only; no recording ships.

The owner heard round 0's cymbals as "hitting sheet metal" (2026-11-14). They were guessed: 40 to 226 modes
placed by a formula. Measured against the recordings below they sat 11 to 34 dB from the real cymbals, where two
takes of one real flat ride sit 6 to 9 dB apart, with their brightness half where it should be. So EMBER does what
BREAKER did for its approved cymbals (docs/BREAKER.md, "The cymbals"): resynthesize the recorded cymbal's own
resonances. This script reuses BREAKER's fitter (`scripts/fit_breaker_cymbals.py`: `fit_voice`, `points`, `stick`)
rather than copying it, on cymbals BREAKER does not use.

Source: Virtuosity Drums (CC0 1.0), github.com/sfzinstruments/virtuosity_drums at
9f04cf9a734527edfbb0a4eee1f674e45bbf71bc: "Contemporary Jazz drum kit library ... the shop's house kit, indicative of
a typical setup in a live club", overhead mic. The files and their sha256 are listed in NOTICE.txt beside the JSON.
Decode each to mono 44.1 kHz float WAV first, e.g.
  ffmpeg -i oh_flatride_ride_vl3_rr1.flac -ac 1 -ar 44100 -c:a pcm_f32le oh_flatride_ride_vl3_rr1.wav

Voices:
- flatride: EMBER's RIDE. A flat ride (no bell): the dry, spread jazz ride. Hard vl3, soft vl1. 400 modes.
- bell: RIDE_BELL. The ride's bell. Hard vl3, soft vl1. 240 modes.
- sizzle: SIZZLE. A crash played with its sizzle (rivets) on. Hard vl3, soft vl1. 600 modes.
- hat_open: HAT_OPEN. Hard vl4, soft vl1. 300 modes.
- Every modal voice also carries its residual (Serra and Smith): per band and time, what its modes and stick do
  not explain of the recording, hard and soft. The renderer plays it as band noise. Without it the modes alone
  look like steady lines where a real cymbal is diffuse.
- hat_closed, hat_pedal: HAT_CLOSED and HAT_FOOT. Too short for an 8192-point modal fit (the window is 186 ms), so
  they are fitted as band envelopes: each third-octave band's level at BREAKER's TIMES, hard and soft, plus the stick
  spectrum. The renderer plays band noise along those envelopes.

Usage: python3 -I prototype/ember/fit_ember_cymbals.py <dir of decoded WAVs> <out.json>
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "scripts"))
import fit_breaker_cymbals as fb  # noqa: E402

SOURCE = ("Virtuosity Drums (CC0 1.0), sfzinstruments/virtuosity_drums@9f04cf9a734527edfbb0a4eee1f674e45bbf71bc, "
          "overhead mic; fitted by prototype/ember/fit_ember_cymbals.py with BREAKER's fitter. The recordings do not ship.")


def envelope_voice(hard_path, soft_path):
    hard = fb.load(hard_path); hard = hard[fb.onset(hard):]
    soft = fb.load(soft_path); soft = soft[fb.onset(soft):]
    r = lambda a: [[round(float(v), 2) for v in row] for row in a]
    return dict(times=fb.TIMES, hard=r(fb.points(hard)), soft=r(fb.points(soft)),
                stick=[round(v, 5) for v in fb.stick(hard)], stickSoft=[round(v, 5) for v in fb.stick(soft)])


def residual(voice, hard_path, soft_path):
    """What the fitted modes and stick do not explain, per third-octave band over time: the recording's band
    level minus the model's, in power (Serra and Smith's residual). Both sides are dB re their own rms over
    the first 0.5 s, as `points` measures. Where the model already carries a band, the residual sits 30 dB
    under the recording (nothing left). Hard from the hard recording and model; soft from the soft
    recording and the model with its soft tilt and soft stick."""
    out = {}
    for key, path, soft in (("residual", hard_path, False), ("residualSoft", soft_path, True)):
        x = fb.load(path); x = x[fb.onset(x):]
        seconds = min(4.0, len(x) / fb.FS)
        modes = [[hz, a * (10 ** (tilt / 20) if soft else 1.0), slope, slope] for hz, a, slope, tilt in voice["modes"]]
        model = dict(modes=modes, stick=voice["stickSoft"] if soft else voice["stick"])
        rec, mod = fb.points(x), fb.points(fb.render(model, [1.0] * len(fb.BANDS), seconds))
        p = 10 ** (rec / 10) - 10 ** (mod / 10)
        res = 10 * fb.np.log10(fb.np.maximum(p, 10 ** ((rec - 30) / 10)))
        out[key] = [[round(float(v), 2) for v in row] for row in res]
    out["times"] = fb.TIMES
    return out


def modal_voice(hard, soft, max_modes):
    v = fb.fit_voice(hard, soft, max_modes)
    v.update(residual(v, hard, soft))
    return v


if __name__ == "__main__":
    d, outp = sys.argv[1], sys.argv[2]
    w = lambda name: os.path.join(d, name + ".wav")
    data = dict(
        source=SOURCE,
        bands=[round(b, 3) for b in fb.BANDS],
        voices=dict(
            flatride=modal_voice(w("oh_flatride_ride_vl3_rr1"), w("oh_flatride_ride_vl1_rr1"), 400),
            bell=modal_voice(w("oh_ride_bell_vl3_rr1"), w("oh_ride_bell_vl1_rr1"), 240),
            sizzle=modal_voice(w("oh_crash_sizzle_vl3_rr1"), w("oh_crash_sizzle_vl1_rr1"), 600),
            hat_open=modal_voice(w("oh_hh_open_vl4_rr1"), w("oh_hh_open_vl1_rr1"), 300),
            hat_closed=envelope_voice(w("oh_hh_closed_vl4_rr1"), w("oh_hh_closed_vl1_rr1")),
            hat_pedal=envelope_voice(w("oh_hh_pedal_vl3_rr1"), w("oh_hh_pedal_vl1_rr1")),
        ))
    json.dump(data, open(outp, "w"), separators=(",", ":"))
    for k, v in data["voices"].items():
        print(k, f"{len(v['modes'])} modes" if "modes" in v else "band envelopes")
