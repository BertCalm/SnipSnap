"""BOROUGH round 2: designed boom-bap drums. Python harness, not Kotlin.

Owner, after round 1.7: "Without the chain the kick sounds like a weak hit, with it- it becomes muddy.
I don't feel like we have effectively replicated the vibe of the references ... These are just
flopping." They chose designed drums over an imprint of the loops.

Why round 1 flopped, measured (hits.py, onset-aligned medians over a loop's hits): STOOP's kick sat
near 97 Hz with almost no sub and grew louder for 200 ms (the compressor lifting its tail), where
driggs' kick is struck at ~124 Hz, falls to ~55 Hz within 30 ms, and is gone by 200-300 ms. The
snare's body sat at 150 Hz and peaked late with its crack 12-15 dB weak, where driggs' cracks at
209 Hz with its wires strong for 50 ms. The round 1 descriptor (each sound's band shape over its own
peak) could not see pitch or the order of events.

So each drum is now built the way boom-bap drums are layered, on an acoustic core so it never turns
back into a drum machine (round 0):

  kick  : EMBER's membrane at the loop's own pitch (the boom) + a punch burst that falls onto it
          (the "knock" a break kick has around 100-150 Hz) + a small beater click; a low-mid cut
          (where mud lives); grit on the punch and click only, never the sub; a low-pass.
  snare : EMBER's snare (stick, head, threshold wires) + a body tone at the loop's pitch + a crack
          (a short band of noise, the snap) ; a low cut and a low-mid cut; grit; a low-pass.

Each kit's drums are fitted (fit_designed.py) to the fine measurement of its own loop: band energy
in six windows from 0-5 ms to 200-300 ms, the kick's pitch track and the snare's body pitch.
"""
from __future__ import annotations
import math
import numpy as np
from scipy import signal

import acoustic as A

E = A.E
SR = A.SR


def _peak_eq(x, f0, gain_db, q=1.0):
    a_ = 10 ** (gain_db / 40); w = 2 * math.pi * f0 / SR; al = math.sin(w) / (2 * q); cw = math.cos(w)
    b = np.array([1 + al * a_, -2 * cw, 1 - al * a_]); a = np.array([1 + al / a_, -2 * cw, 1 - al / a_])
    return signal.lfilter(b / a[0], a / a[0], x)


def _lp(x, hz, order=2):
    return signal.sosfilt(signal.butter(order, min(hz, SR * .45), fs=SR, output='sos'), x)


def _hp(x, hz, order=2):
    return signal.sosfilt(signal.butter(order, hz, btype='high', fs=SR, output='sos'), x)


def _finish(y, seconds):
    y = y[:int(seconds * SR)]
    return A.end(y, seconds)


def kick(p, vel=1.0, rng=None, seconds=0.6):
    """Boom (membrane at f1, ringing t1) + punch (a sine struck at punch_hz falling onto f1 over punch_ms,
    punch_db against the boom) + click (beater, click_db) -> mud cut at 350 Hz (mud_db) -> grit on what
    is above 200 Hz (drive) -> low-pass. Velocity: the punch and click grow faster than the boom."""
    rng = rng or A.rng_for('designed-kick')
    n = E.n_of(seconds + .05)
    beater = E.mallet(vel, rng, n, width_hard=p['beater_ms'] * 1e-3)
    ir = E.peak_norm(E.membrane_ir(p['f1'], n, p['t1'], p['alpha'], float(np.clip(.15 + rng.normal(0, .03), 0, .5)),
                                   rng.uniform(0, math.pi), sag=.03 * vel * vel, sag_tau=.04, reso=(1.02, .35)))
    boom = A.to44(E.conv(beater, ir, n), .05)
    boom = boom / (np.max(np.abs(boom)) + 1e-12)
    t = np.arange(len(boom)) / SR
    tau = p['punch_ms'] * 1e-3
    f = p['f1'] + (p['punch_hz'] - p['f1']) * np.exp(-t / tau)
    ph = 2 * math.pi * np.cumsum(f) / SR
    punch = np.sin(ph) * np.exp(-t / (2.2 * tau)) * (1 - np.exp(-t / .0008))
    punch *= 10 ** (p['punch_db'] / 20) * vel ** .6
    clk = _hp(rng.standard_normal(len(t)) * np.exp(-t / .0007), 1500) * 10 ** (p['click_db'] / 20) * vel ** 1.5
    lo = boom + punch
    hi = _hp(lo, 200) + clk
    lo = lo - _hp(lo, 200)
    d = p['drive'] * (.4 + .6 * vel)
    if d > 1e-3:
        g = 1 + 8 * d
        hi = np.tanh(g * hi / (np.max(np.abs(hi)) + 1e-12)) * (np.max(np.abs(hi)) + 1e-12) / math.tanh(g)
    y = _peak_eq(lo + hi, 350, -p['mud_db'], .9)
    y = _lp(y, p['lp'])
    return _finish(y, seconds)


def snare(p, vel=1.0, rng=None, seconds=0.45):
    """EMBER's snare core (head f1, t1, wires) with the record chain off + a body tone at f1 (body_db,
    body_ms) + a crack (noise in crack_hz +- an octave, crack_db, crack_ms) -> low cut 90 Hz -> mud cut
    at 400 Hz -> grit (drive) -> low-pass. Velocity: the crack grows fastest."""
    rng = rng or A.rng_for('designed-snare')
    core_p = dict(f1=p['f1'], t1=p['t1'], alpha=p['alpha'], r=p['r'], buzz=p['buzz'], wire=p['wire'],
                  stick_ms=p['stick_ms'], crack=0.0, distance=.05, comp=0.0, drive=0.0, lp=16000.0, tilt=0.0)
    core = A.snare(core_p, vel, rng, seconds=seconds)
    t = np.arange(len(core)) / SR
    body = np.sin(2 * math.pi * p['f1'] * t * (1 - .03 * np.exp(-t / .02))) * np.exp(-t / (p['body_ms'] * 1e-3))
    body *= (1 - np.exp(-t / .0006)) * 10 ** (p['body_db'] / 20) * vel ** .8
    nz = rng.standard_normal(len(t))
    band = signal.butter(2, [p['crack_hz'] / 2, min(p['crack_hz'] * 2, SR * .45)], btype='band', fs=SR, output='sos')
    crack = signal.sosfilt(band, nz) * np.exp(-t / (p['crack_ms'] * 1e-3))
    crack *= 10 ** (p['crack_db'] / 20) * vel ** 1.4 / (np.max(np.abs(crack)) + 1e-12)
    y = core + body + crack
    y = _hp(y, 90)
    y = _peak_eq(y, 400, -p['mud_db'], .9)
    d = p['drive'] * (.4 + .6 * vel)
    if d > 1e-3:
        g = 1 + 8 * d; m = np.max(np.abs(y)) + 1e-12
        y = np.tanh(g * y / m) * m / math.tanh(g)
    y = _lp(y, p['lp'])
    return _finish(y, seconds)


SPACES = {
    'kick': dict(f1=(38, 90), t1=(.15, .95), alpha=(.6, 1.6), beater_ms=(.5, 3.0), punch_hz=(70, 200),
                 punch_ms=(3, 30), punch_db=(-18, 6), click_db=(-50, -10), mud_db=(0, 9), drive=(0, 1), lp=(1500, 14000)),
    'snare': dict(f1=(150, 300), t1=(.04, .4), alpha=(.6, 1.6), r=(.05, .7), buzz=(.05, 1), wire=(.2, 3),
                  stick_ms=(.15, .8), body_db=(-24, 6), body_ms=(10, 120), crack_hz=(1500, 7000),
                  crack_db=(-24, 6), crack_ms=(3, 40), mud_db=(0, 9), drive=(0, 1), lp=(3000, 16000)),
}
LOGK = {'f1', 'lp', 't1', 'punch_hz', 'crack_hz', 'punch_ms', 'crack_ms', 'body_ms', 'beater_ms', 'stick_ms'}
RENDER = {'kick': kick, 'snare': snare}
