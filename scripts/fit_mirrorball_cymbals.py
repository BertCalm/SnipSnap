"""Fit MIRRORBALL's open hi-hats to CC0 recordings: BREAKER's dense modal model, applied to two hat articulations.

The owner heard round 1b's open hats (SKIN's noise hats) as short of "a pro sound" (2026-11-12) and asked that "pro"
mean "like real recordings". BREAKER's cymbals, fitted the same way, were accepted ("these are fine", 2026-11-11), so
this reuses that fit (scripts/fit_breaker_cymbals.py: fit_voice) unchanged, on two hat articulations:

  hat_half: the half-open hat, the disco "tss" (about 30 dB down by 0.5 s)   Philly A04
  hat_34:   the three-quarter-open hat, a longer sizzle                        Loft A04

Sources: Virtuosity Drums (CC0 1.0), github.com/sfzinstruments/virtuosity_drums at
9f04cf9a734527edfbb0a4eee1f674e45bbf71bc, overhead mic:
  Samples/oh/hh/oh_hh_half_vl4_rr1.flac (hard), oh_hh_half_vl1_rr1.flac (soft)
  Samples/oh/hh/oh_hh_34_vl4_rr1.flac (hard), oh_hh_34_vl1_rr1.flac (soft)
Decode each to mono 44.1 kHz float WAV first, e.g.
  ffmpeg -i oh_hh_half_vl4_rr1.flac -ac 1 -ar 44100 -c:a pcm_f32le oh_hh_half_vl4_rr1.wav

Usage: python3 -I scripts/fit_mirrorball_cymbals.py <dir of decoded WAVs> <out.json>
The recordings never ship; only the fitted numbers do (synth/src/main/resources/mirrorball/cymbals.json).
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fit_breaker_cymbals as fit  # noqa: E402

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    d, outp = sys.argv[1], sys.argv[2]
    data = dict(
        source="Virtuosity Drums (CC0 1.0), sfzinstruments/virtuosity_drums@9f04cf9a734527edfbb0a4eee1f674e45bbf71bc, "
               "overhead mic, half-open and three-quarter-open hats; fitted by scripts/fit_mirrorball_cymbals.py with "
               "scripts/fit_breaker_cymbals.py's fit_voice. The recordings do not ship.",
        bands=[round(b, 3) for b in fit.BANDS],
        voices=dict(hat_half=fit.fit_voice(f"{d}/oh_hh_half_vl4_rr1.wav", f"{d}/oh_hh_half_vl1_rr1.wav", 400),
                    hat_34=fit.fit_voice(f"{d}/oh_hh_34_vl4_rr1.wav", f"{d}/oh_hh_34_vl1_rr1.wav", 400)))
    os.makedirs(os.path.dirname(outp), exist_ok=True)
    json.dump(data, open(outp, "w"), separators=(",", ":"))
    for k, v in data["voices"].items():
        print(f"{k}: {len(v['modes'])} modes")
