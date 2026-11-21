"""Steel voices, version 2: the higher shell modes and the fingertip, measured against the loops.

The resynthesis A/B (resynth.py: CUPOLA plays what each reference loop plays, both measured the
same way) found the round-3 steel voices missing what happens above the notes' main partials:
  HANDPAN  14 to 54 dB short from 0.8 to 5 kHz; attack above 2 kHz -57..-62 dB against -25..-33
  TONGUE   14 to 38 dB short above 1.3 kHz; attack -67 dB against -35 (the dry loop)
  TANK     15 to 40 dB short from 300 Hz to 1.5 kHz, but too bright on top (+8..+10 dB above
           5 kHz, attack -43..-46 against -62..-71) and ringing 3 to 7 dB too long
A steel note field has many more modes than its tuned three: inharmonic shell modes above them
that ring briefly. They are driven here by the SAME contact pulse as the tuned modes (one body,
one strike), so a shorter, harder contact brightens them by itself. Ratios are clusters seen on
the owner's isolated notes (handpan: 2.2, 2.9, 3.8..4.4, 5.6..5.8; tank: a body from 2x to 8x);
gains and decays are "fit" by fit_steel.py against the resynthesis measures, not by ear.

  SLAP  the fingertip on steel: a 1..3 ms high-passed noise tick, from the same instant, scaled
        by velocity squared and TOUCH. The tank is played softer (thumb, felt): its slap is low
        and its contact noise is darker.
"""
from __future__ import annotations

import numpy as np
from scipy import signal

import cupola_proto as cp
sk = cp.sk
INTERNAL = cp.INTERNAL

# per voice: upper mode ratios, their level at the first one and the slope per ratio octave (dB),
# their T60 at the first one (s) and how fast it shortens with ratio, the slap level, the
# skin-noise low-pass at TOUCH .5 (Hz), and a RING trim for the tuned modes.
UPPER = {
    "HANDPAN": dict(ratios=[2.2, 2.9, 3.85, 4.4, 5.6, 5.8, 7.8, 9.6, 12.4, 15.1], top_db=-5.0, slope_db=-2.0,
                    t60=.5, t60_slope=1.0, slap=.40, skin_lp=2500.0, ring_trim=1.0),
    "DING":    dict(ratios=[2.2, 2.9, 3.85, 4.4, 5.6, 7.8, 9.6, 12.4], top_db=-18.0, slope_db=-6.0,
                    t60=.4, t60_slope=1.0, slap=.06, skin_lp=4000.0, ring_trim=1.0),
    "TONGUE":  dict(ratios=[5.4, 8.7, 11.3, 14.7, 18.9], top_db=-14.0, slope_db=-5.0,
                    t60=.5, t60_slope=1.0, slap=.30, skin_lp=6000.0, ring_trim=.3),
    "TANK":    dict(ratios=[2.3, 2.9, 3.6, 4.7, 5.9, 7.2], top_db=0.0, slope_db=-2.0,
                    t60=.25, t60_slope=.8, slap=.01, skin_lp=1500.0, ring_trim=1.0),
}


def _excitation(voice, m, midi, vel, take, seconds):
    """The contact pulse exactly as render_steel builds it (same seed, same draw order)."""
    v = cp.VOICES[voice]
    rng = np.random.default_rng(sk.seed_for("CUPOLA", voice, midi, take))
    soft, hard = v["touch_ms"]
    width = sk.exp_map(m["TOUCH"], soft, hard) * (1.3 - .5 * vel) * (1 + .1 * rng.uniform(-1, 1))
    impulse = vel * 10 ** (.5 * rng.uniform(-1, 1) / 20)
    exc = sk.hann_pulse(width, INTERNAL, 2 * impulse / (width * 1e-3), seconds)
    if cp.RR_REBOUND:
        rb_ms = sk.exp_map(rng.uniform(), 1.2, 7.0)
        rb = sk.exp_map(rng.uniform(), .06, .35) * impulse
        exc += sk.hann_pulse(width * .8, INTERNAL, 2 * rb / (width * .8e-3), seconds, at_seconds=rb_ms * 1e-3)
    pos = float(np.clip(.4 + .3 * rng.uniform(-1, 1) + .25 * (m["TOUCH"] - .5), 0, 1))
    return exc, width, impulse, pos


_base_render_steel = cp.render_steel


def render_steel(voice, m, midi, vel, take, seconds):
    u = UPPER[voice]
    trimmed = {**m, "RING": float(np.clip(m["RING"] + np.log(u["ring_trim"]) / np.log(2.2 / .2), 0, 1))}
    out, diag = _base_render_steel(voice, trimmed, midi, vel, take, seconds)
    exc, width, impulse, pos = _excitation(voice, trimmed, midi, vel, take, seconds)
    f0 = cp.hz(midi)
    ring_scale = sk.exp_map(trimmed["RING"], .2, 2.2)
    # the struck fundamental's own response to this contact: the reference the upper modes sit under
    fund = cp.ring(exc, f0, 1.0, cp.t60_at(cp.VOICES[voice]["modes"][0][2], f0, ring_scale))
    a1 = max(1e-12, float(np.max(np.abs(fund))))
    # round robin, dealt: take k owns a quarter of the range of how much the shell modes ring
    # (random draws put two DING takes 0.98 dB apart, under the 1.0 dB bar)
    band = (take % 4 + np.random.default_rng(sk.seed_for(voice, midi, take, "deal")).uniform(.15, .85)) / 4
    # this field's own small mistuning of its shell modes (fixed per note, not per take)
    frng = np.random.default_rng(sk.seed_for("CUPOLA", voice, "shell", round(midi * 100)))
    upper = np.zeros_like(out)
    for i, r in enumerate(u["ratios"]):
        rr = r * (1 + .02 * frng.uniform(-1, 1))
        fk = f0 * rr
        if fk > 18000:
            break
        g_db = u["top_db"] + u["slope_db"] * np.log2(rr / u["ratios"][0])
        edge = (.6 + .8 * pos) * (.55 + .9 * band)                # an edge strike feeds them more; the take deals the spot
        t60 = u["t60"] / (rr / u["ratios"][0]) ** u["t60_slope"] * ring_scale ** .5
        y = cp.ring(exc, fk, 1.0, t60)
        upper += y * (10 ** (g_db / 20) * edge)
    # scale the bank as one: its first mode at top_db under the fundamental, for a unit contact
    upper *= np.max(np.abs(out)) / a1 if a1 > 0 else 1.0
    # the fingertip: a short high-passed tick, velocity squared, TOUCH brightens and lengthens it
    tick_ms = sk.exp_map(trimmed["TOUCH"], 3.0, 1.0)
    tick = sk.noise_burst(tick_ms, INTERNAL, sk.seed_for(voice, midi, take, "tick"), 1.0, seconds)
    tick = signal.lfilter(*signal.butter(2, sk.exp_map(trimmed["TOUCH"], 900.0, 2500.0) / (INTERNAL / 2), "high"), tick)
    tick = signal.lfilter(*signal.butter(2, min(.45, u["skin_lp"] * 2 / (INTERNAL / 2))), tick)
    tick *= u["slap"] * vel ** 2 * np.max(np.abs(out)) / max(1e-12, np.max(np.abs(tick)))
    diag.update({"upper_peak": float(np.max(np.abs(upper))), "tick_peak": float(np.max(np.abs(tick)))})
    return out + upper + tick, diag


# shorter hand contacts: round 3's (7..0.6 ms handpan, 12..3 ms tank) put a spectral null at
# 1 kHz and 330 Hz at the default touch, silencing the shell modes the loops show above them
TOUCH_MS = {"HANDPAN": (3.0, .3), "DING": (4.0, .5), "TONGUE": (2.5, .3), "TANK": (4.0, 1.0)}
SKIN = {"TANK": .004}          # the tank is struck soft (thumb, felt): far less contact noise
# the tongue's tuned partials, quieter: both dry tongue loops measured 7..22 dB too much at 2x and 4x
TONGUE_MODES = [(1.0, 1.0, 11.0), (2.008, .12, 8.0), (4.015, .05, 5.0), (6.013, .03, 3.0), (9.4, .015, .5)]


def install():
    cp.render_steel = render_steel
    for k, sk_level in SKIN.items():
        cp.VOICES[k]["skin"] = sk_level
    cp.VOICES["TONGUE"]["modes"] = TONGUE_MODES
    cp.VOICES["TANK"]["cavity"] = (0.72, .15, 1.0)
    for k, t in TOUCH_MS.items():
        cp.VOICES[k]["touch_ms"] = t          # half the air: the loops measured 8..11 dB too much at 80..125 Hz
