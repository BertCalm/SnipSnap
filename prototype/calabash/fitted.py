"""CALABASH's fitted voices: a recording's own resonances, struck again.

Reads hands.json (fit_calabash.py). Renders at 44.1 kHz, as EMBER's fitted cymbals do: a sum of sines is band-limited
by construction, so the 4x chain gets it upsampled. Python harness, not Kotlin; nothing here has been heard.

What one strike is:
  * every fitted mode from rest (a sine at phase 0, so the strike lands as one coherent onset), at its measured
    frequency, amplitude and two decay slopes;
  * the head starts sharp and settles (the measured glide), more on a harder hit (glide x v^2);
  * the residual: per band and time, what the modes leave out of the recording (the hand's slap, the stick's click,
    the room), played as band noise along its measured envelope;
  * velocity: each mode moves by its band's measured soft-minus-hard tilt, and the residual moves toward the soft
    recording's, so a soft hit is the soft recording's shape, not the hard one turned down;
  * a take (BREAKER's law, adapted to a hand): up to 0.15% detune per mode, 1.5 dB level scatter on the upper modes
    (0.5 on the lowest three), a quarter of the upper modes landing in the other phase (the hand lands a little
    elsewhere on the head, so their shapes change sign), 15% scatter on the glide, fresh residual noise.
"""
from __future__ import annotations

import functools
import json
import math
import os

import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
RATE = 44100
TAU = 2 * math.pi


@functools.cache
def data():
    with open(os.path.join(HERE, "hands.json")) as fh:
        return json.load(fh)


@functools.cache
def band_sos(i):
    fc = data()["bands"][i]
    lo, hi = fc / 2 ** (1 / 6), min(fc * 2 ** (1 / 6), RATE * .49)
    return signal.butter(4, [lo / (RATE / 2), hi / (RATE / 2)], "band", output="sos")


def soft_weight(v):
    """0 at a full hit, 1 at the soft recordings (velocity .3, VCSL's lowest layer), a little past it below."""
    return float(np.clip((1.0 - v) / 0.7, 0.0, 1.2))


def damp_factor(damp):
    """DAMP .3 is the drum as recorded; 1 is every mode dying three times faster; 0 is 0.64x. Shape."""
    return 2.0 ** ((damp - 0.3) / 0.7 * 1.6)


def strike(name, v, tr, seconds, tune_ratio=1.0, damp=.3, mode_gain=None, slope_mul=None, glide_mul=1.0,
           residual_db=0.0, attack_db=0.0, resid_until=None, contact_ms=None):
    """One strike of fitted voice `name` at velocity v, take rng `tr`, `seconds` long, at 44.1 kHz, absolute units
    (the recording at unit rms over its first 0.5 s). `mode_gain(hz)` and `slope_mul(hz)` reshape the modes (the
    strokes built on a fitted one use them); `attack_db` lifts the residual's first 15 ms, `residual_db` all of it.
    Returns (modes, residual) so a stroke can weigh them."""
    d = data()["voices"][name]
    n = int(round(seconds * RATE))
    t = np.arange(n) / RATE
    w = soft_weight(v)
    modes = np.zeros(n)
    if d["modes"]:
        m = np.array(d["modes"], float)
        hz, amp, early, late, tilt = m.T
        k = len(hz)
        order = np.argsort(-amp)
        low_rank = np.zeros(k, bool)
        low_rank[order[:3]] = True
        f_ref = hz[order[0]]
        hz = hz * tune_ratio * (1 + tr.normal(0, .0015, k))
        gain = 10 ** ((tilt * w + tr.normal(0, 1, k) * np.where(low_rank, .5, 1.5)) / 20)
        sign = np.where((hz > 1.8 * f_ref * tune_ratio) & (tr.random(k) < .25), -1.0, 1.0)
        if mode_gain is not None:
            gain = gain * np.array([mode_gain(f) for f in hz])
        sm = np.full(k, damp_factor(damp))
        if slope_mul is not None:
            sm = sm * np.array([slope_mul(f) for f in hz])
        early, late = early * sm, late * sm
        g, tau = d["glide"]
        g = g * glide_mul * v * v * (1 + tr.normal(0, .15))
        warp = t + g * tau * (1 - np.exp(-t / tau))          # phase time of a pitch that starts sharp and settles
        ok = hz < .47 * RATE
        E = data()["early"]
        for s0 in range(0, k, 24):
            j = np.arange(s0, min(k, s0 + 24))
            j = j[ok[j]]
            if not len(j):
                continue
            # Each block runs only until its slowest mode is 90 dB down.
            at_e = early[j] * E
            t90 = np.where(at_e <= -90, 90 / -early[j], E + (90 + at_e) / -late[j])
            lim = min(n, int(t90.max() * RATE) + 1)
            tt = t[None, :lim]
            dbt = np.where(tt < E, early[j, None] * tt, early[j, None] * E + late[j, None] * (tt - E))
            modes[:lim] += (sign[j, None] * amp[j, None] * gain[j, None] * 10 ** (dbt / 20)
                            * np.sin(TAU * hz[j, None] * warp[None, :lim])).sum(0)
        # The contact: a hand or a stick stays on the head for a moment, so the modes grow in over it rather than
        # starting on one sample (which would also leak a thump into bands a bell never had). A softer hit stays on
        # longer. Shape: 0.25 ms for a stick on metal or wood at full force, up to about 3x that at velocity .3.
        c = (contact_ms if contact_ms is not None else .25) * 1e-3 * v ** -.6
        modes *= 1 - np.exp(-t / c)
    res = residual_noise(d, w, tr, n, tune_ratio, damp, residual_db, attack_db, resid_until)
    return modes, res


def residual_noise(d, w, tr, n, tune_ratio, damp, residual_db, attack_db, resid_until):
    bands = data()["bands"]
    T = np.array(data()["times"])
    lev = (1 - min(w, 1.0)) * np.array(d["residual"]) + min(w, 1.0) * np.array(d["residualSoft"])
    shift = 3 * math.log2(tune_ratio)                         # third-octave bands to slide by
    idx = np.arange(len(bands))
    lev = np.array([np.interp(idx - shift, idx, lev[:, j]) for j in range(lev.shape[1])]).T
    t = np.arange(n) / RATE
    tw = t * damp_factor(damp)
    out = np.zeros(n)
    for i, fc in enumerate(bands):
        if fc * 2 ** (1 / 6) >= .49 * RATE or lev[i].max() < -150:
            continue
        # The stroke's attack lift is a hand or a stick on skin: it lives above 400 Hz, never in the low thump.
        lift = np.where(t < .015, attack_db if fc >= 400 else 0.0, 0.0) + residual_db
        x = signal.sosfilt(band_sos(i), tr.standard_normal(n + 2000))[2000:]
        x /= np.sqrt(np.mean(x ** 2)) + 1e-20
        e = np.interp(tw, T, lev[i], right=lev[i][-1] - 40)
        out += x * 10 ** ((e + lift) / 20)
    if resid_until is not None:
        fade = np.clip((resid_until - t) / .02, 0, 1)
        out *= fade
    return out


def fitted(name, v, tr, seconds, **kw):
    a, b = strike(name, v, tr, seconds, **kw)
    return a + b
