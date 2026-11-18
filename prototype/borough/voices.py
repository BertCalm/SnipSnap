"""BOROUGH round 0 voices: small parametric drum and percussion models. Python harness, not Kotlin.

Every sound here starts from oscillators and freshly seeded noise. No reference PCM is read,
stored or mixed: `fit.py` only compares band-envelope numbers (extract.py's descriptor) and moves
these parameters. Each parameter is a physical or recording quantity with a name, so a fitted
kit reads as a recipe ("62 Hz kick, 31 ms sweep, low-pass at 3.1 kHz"), not as a fingerprint.

Rendering: 88.2 kHz internally, a band-limit and a 2:1 polyphase decimation to 44.1 kHz, a 20 Hz
DC high-pass and a 4 ms tail fade, the same order as the house output stage (Kotlin uses 4x;
this harness uses 2x for fitting speed and says so).
"""
from __future__ import annotations
import hashlib
import numpy as np
from scipy import signal

SR = 44_100
OS = 2
RATE = SR * OS
# Six-oscillator metal cluster ratios (a common square-wave hat recipe; shape constants).
METAL = np.array([1.0, 1.304, 1.466, 1.787, 1.932, 2.536])


def rng_for(*parts) -> np.random.Generator:
    h = hashlib.sha256(':'.join(str(p) for p in ('borough-r0',) + parts).encode()).digest()
    return np.random.default_rng(int.from_bytes(h[:8], 'little'))


def _t(seconds):
    return np.arange(int(seconds * RATE)) / RATE


def _bp(x, lo, hi, order=2):
    lo = max(lo, 20.0); hi = min(max(hi, lo * 1.05), RATE * 0.45)
    return signal.sosfilt(signal.butter(order, [lo, hi], fs=RATE, btype='band', output='sos'), x)


def _lp(x, fc, order=2):
    return signal.sosfilt(signal.butter(order, min(fc, RATE * .45), fs=RATE, output='sos'), x)


def _hp(x, fc, order=2):
    return signal.sosfilt(signal.butter(order, max(fc, 10.0), fs=RATE, btype='high', output='sos'), x)


def _drive(x, amount):
    """tanh saturation normalised so small signals keep unit gain; amount 0 is clean."""
    if amount <= 1e-3:
        return x
    g = 1 + 12 * amount
    pk = np.max(np.abs(x)) + 1e-12
    y = np.tanh(g * x / pk + 0.08 * amount) - np.tanh(0.08 * amount)  # small asymmetry: even harmonics
    return y * pk / g


def chain(x, p, vel=1.0):
    """The recording chain each kit prints into its hits: drive (strike-level dependent), a
    2-pole low-pass, a high shelf tilt, optional bit depth. Shape constants, fitted per voice."""
    x = _drive(x, p.get('drive', 0.0) * (0.35 + 0.65 * vel))
    if p.get('tilt', 0.0):
        hi = _hp(x, 900, 1)
        x = x + (10 ** (p['tilt'] / 20) - 1) * hi
    x = _lp(x, p.get('lp', 16000))
    bits = p.get('bits', 0)
    if bits:
        q = 2.0 ** (bits - 1)
        pk = np.max(np.abs(x)) + 1e-12
        x = np.round(x / pk * q) / q * pk
    return x


def finish(x):
    """House order: band-limit, decimate, DC high-pass, 4 ms tail fade. Peak-normalised to 1;
    the kit applies the velocity level law afterwards."""
    y = signal.resample_poly(x, 1, OS)          # polyphase windowed-sinc low-pass then 2:1
    y = y - np.mean(y)
    y = signal.sosfilt(signal.butter(1, 20, fs=SR, btype='high', output='sos'), y)
    # Trim where the 10 ms envelope has fallen 60 dB below its peak, then fade the last 10 ms,
    # so no one-shot ends on a step (the probe checks the final 10 ms RMS).
    w = int(.01 * SR)
    e = np.sqrt(np.convolve(y * y, np.ones(w) / w, 'same'))
    live = np.where(e > e.max() * 1e-3)[0]
    end = min(len(y), (live[-1] if len(live) else len(y)) + w)
    y = y[:end]
    # A pad still ringing at its render length gets a sampler-style release (raised cosine over
    # the last quarter, at most 300 ms) rather than a step. Disclosed: long tails are shortened.
    still = e[min(end, len(e)) - 1] > e.max() * 3e-3
    n = min(int(.3 * SR), len(y) // 4) if still else min(w, len(y) // 4)
    y[-n:] *= (0.5 + 0.5 * np.cos(np.linspace(0, np.pi, n)))
    a = int(.0004 * SR)
    y[:a] *= np.linspace(0, 1, a)
    return y / (np.max(np.abs(y)) + 1e-12)


# ---------------------------------------------------------------- drums

def kick(p, vel=1.0, rng=None, seconds=1.2):
    """Swept sine body with a two-stage decay, a second (shell) mode, a beater click and a skin
    noise layer. Velocity narrows the sweep and darkens the click (a softer beater contacts longer)."""
    rng = rng or rng_for('kick')
    t = _t(seconds)
    ratio = 1 + (p['sweep'] - 1) * (0.55 + 0.45 * vel)
    f = p['f0'] * (1 + (ratio - 1) * np.exp(-t / (p['sweep_ms'] / 1000)))
    ph = 2 * np.pi * np.cumsum(f) / RATE + rng.uniform(-.2, .2)
    env = (1 - p['tail_mix']) * np.exp(-t / (p['decay_ms'] / 1000)) + p['tail_mix'] * np.exp(-t / (p['tail_ms'] / 1000))
    body = np.sin(ph) * env
    shell = p['shell'] * np.sin(ph * p['shell_ratio']) * np.exp(-t / (p['decay_ms'] / 2500))
    click_fc = p['click_hz'] * (0.45 + 0.55 * vel)
    click = _bp(rng.standard_normal(len(t)), click_fc / 2, click_fc * 2) * np.exp(-t / .0015)
    click *= p['click'] * vel ** 0.7 / (np.std(click[:int(.003 * RATE)]) + 1e-9)
    skin = _bp(rng.standard_normal(len(t)), 120, 900) * np.exp(-t / (p['skin_ms'] / 1000))
    skin *= p['skin'] / (np.std(skin[:int(.01 * RATE)]) + 1e-9)
    x = body + shell + click * 0.5 + skin * 0.3
    return finish(chain(x, p, vel))


def snare(p, vel=1.0, rng=None, seconds=0.7):
    """Two head modes (a small pitch drop on the strike) plus snare-wire noise that starts a beat
    after the head, with its own decay and band. Soft strokes follow the BOOM_BAP wire law: wire
    level sqrt(v), wire band top 14 kHz * v^0.8, wire decay (0.55 + 0.45 sqrt v)."""
    rng = rng or rng_for('snare')
    t = _t(seconds)
    bend = 1 + 0.06 * vel * np.exp(-t / .006)
    ph = 2 * np.pi * np.cumsum(p['f1'] * bend) / RATE
    md = np.exp(-t / (p['mode_ms'] / 1000))
    head = (np.sin(ph) + p['m2'] * np.sin(ph * p['r2'] + 1.1)) * md
    sv = np.sqrt(vel)
    w_hi = min(p['wire_hi'], 14000 * vel ** 0.8 + 1500)
    wire = _bp(rng.standard_normal(len(t)), p['wire_lo'], w_hi)
    wd = p['wire_ms'] / 1000 * (0.55 + 0.45 * sv)
    wenv = (1 - np.exp(-t / .0012)) * (np.exp(-t / wd) + p['rattle'] * np.exp(-t / (wd * 3.5)))
    wire = wire / (np.std(wire) + 1e-9) * wenv * p['wire'] * sv
    click = _hp(rng.standard_normal(len(t)), 3000) * np.exp(-t / .0008) * p['click'] * vel
    x = head + wire + click
    return finish(chain(x, p, vel))


def rim(p, vel=1.0, rng=None, seconds=0.35):
    """Cross-stick from the snare's own numbers: the head modes lifted by the rim contact, a
    hard woody click, little wire."""
    q = dict(p); q.update(f1=p['f1'] * 1.9, r2=2.3, m2=0.8, mode_ms=min(p['mode_ms'], 60) * 0.7,
                          wire=p['wire'] * 0.12, click=1.5, rattle=0)
    return snare(q, vel, rng, seconds)


def hat(p, vel=1.0, rng=None, seconds=0.5, open_=False):
    """Six detuned square partials plus noise, band-passed and high-passed, one decay. Soft hits
    are darker and shorter (stick tip barely closes the plates)."""
    rng = rng or rng_for('hat')
    t = _t(seconds)
    base = p['metal_hz'] * (1 + rng.uniform(-.004, .004))
    metal = sum(np.sign(np.sin(2 * np.pi * base * r * t + rng.uniform(0, 6.28))) for r in METAL)
    noise = rng.standard_normal(len(t)) * 2.0
    x = p['metal'] * metal / 3 + (1 - p['metal']) * noise
    x = _bp(x, p['bp_hz'] / 1.8 * (0.7 + 0.3 * vel), p['bp_hz'] * 1.8)
    x = _hp(x, p['hp_hz'])
    d = (p['open_ms'] if open_ else p['decay_ms']) / 1000 * (0.7 + 0.3 * vel)
    x = x * (1 - np.exp(-t / .0004)) * np.exp(-t / d)
    return finish(chain(x, p, vel))


def ride(p, vel=1.0, rng=None, seconds=2.4, bell=False):
    """A 24-mode inharmonic cymbal bank with a stick ping and a slowly blooming wash."""
    rng = rng or rng_for('ride')
    t = _t(seconds)
    r2 = rng_for('ride-modes')  # mode table is fixed per kit, the take only moves phases
    ratios = np.sort(r2.uniform(1, 9, 24)) ** 1.15
    x = np.zeros(len(t))
    for i, r in enumerate(ratios):
        f = p['ride_hz'] * r
        if f > RATE * .45:
            continue
        dec = p['ride_ms'] / 1000 * (1.4 - 0.9 * i / 24) * (0.6 if not bell else 1.3)
        x += np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28)) * np.exp(-t / dec) * r2.uniform(.3, 1) / (1 + .1 * i)
    wash = _bp(rng.standard_normal(len(t)), 3000, 12000) * (1 - np.exp(-t / .03)) * np.exp(-t / (p['ride_ms'] / 1000))
    ping = _hp(rng.standard_normal(len(t)), 4000) * np.exp(-t / .002)
    x = x / (np.max(np.abs(x)) + 1e-9) * (1.6 if bell else 1) + p['wash'] * wash / (np.std(wash) + 1e-9) * .3 + ping * .4 * vel
    return finish(chain(x, p, vel))


def clap(p, vel=1.0, rng=None, seconds=0.5):
    """Several hands: noise bursts spread over a few ms, a band-pass for the palm cup, a room tail."""
    rng = rng or rng_for('clap')
    t = _t(seconds)
    env = np.zeros(len(t))
    k = int(p['hands'])
    for i in range(k):
        at = i * p['spread_ms'] / 1000 * rng.uniform(.6, 1.4)
        env += np.where(t >= at, np.exp(-(t - at) / .0035), 0) * rng.uniform(.6, 1)
    env += p['tail'] * np.where(t >= (k - 1) * p['spread_ms'] / 1000, np.exp(-t / (p['tail_ms'] / 1000)), 0)
    x = _bp(rng.standard_normal(len(t)), p['cup_hz'] / 1.6, p['cup_hz'] * 1.6) * env
    x += _hp(rng.standard_normal(len(t)), 5000) * env * 0.3 * vel
    return finish(chain(x, p, vel))


# ---------------------------------------------------------------- percussion

def shaker(p, vel=1.0, rng=None, seconds=0.25):
    """Grains of seed noise: an attack (the throw), a band-limited burst, a quick decay."""
    rng = rng or rng_for('shaker')
    t = _t(seconds)
    grains = (rng.random(len(t)) < p['density'] / RATE).astype(float)
    g = _bp(grains * rng.standard_normal(len(t)) + 0.15 * rng.standard_normal(len(t)), p['shk_hz'] / 1.7, p['shk_hz'] * 1.9)
    env = (1 - np.exp(-t / (p['attack_ms'] / 1000))) * np.exp(-t / (p['shk_ms'] / 1000 * (0.7 + 0.3 * vel)))
    return finish(chain(g * env, p, vel))


def tamb(p, vel=1.0, rng=None, seconds=0.45, hit=False):
    """Jingles: pairs of thin brass discs, each a few inharmonic modes at three cluster centres,
    rattled by a burst of collisions. A hit adds the frame's head knock."""
    rng = rng or rng_for('tamb')
    t = _t(seconds)
    col = (rng.random(len(t)) < (p['jingle_rate'] * (3 if hit else 1)) / RATE) * rng.standard_normal(len(t))
    col *= np.exp(-t / (p['tmb_ms'] / 1000 * (0.6 + 0.4 * vel)))
    x = np.zeros(len(t))
    for fc in (p['j1'], p['j1'] * p['j2r'], p['j1'] * p['j3r']):
        for d in (-0.03, 0.0, 0.025):
            f = fc * (1 + d + rng.uniform(-.004, .004))
            r = np.exp(-2 * np.pi * f / (2 * 40) / RATE)   # Q 40 resonator
            b, a = [1 - r], [1, -2 * r * np.cos(2 * np.pi * f / RATE), r * r]
            x += signal.lfilter(b, a, col)
    x += 0.4 * _hp(col, 6000)
    if hit:
        x = x / (np.max(np.abs(x)) + 1e-9)
        x += 0.8 * np.sin(2 * np.pi * p['frame_hz'] * t) * np.exp(-t / .025)
    return finish(chain(x, p, vel))


def hand(p, vel=1.0, rng=None, seconds=0.6, slap=False):
    """Hand drum (bongo/conga family): membrane fundamental with a small downward bend, the
    1.59 and 2.14 circular-membrane modes, a palm contact noise. A slap is brighter, shorter."""
    rng = rng or rng_for('hand')
    t = _t(seconds)
    f0 = p['hd_hz'] * (1 + 0.04 * vel * np.exp(-t / .01))
    ph = 2 * np.pi * np.cumsum(f0) / RATE
    dec = p['hd_ms'] / 1000 * (0.5 if slap else 1)
    x = np.sin(ph) * np.exp(-t / dec) + p['hd_modes'] * (np.sin(ph * 1.594) * .6 + np.sin(ph * 2.136) * .4) * np.exp(-t / (dec * .5))
    palm = _bp(rng.standard_normal(len(t)), 600, (1500 + (3500 if slap else 1000) * vel)) * np.exp(-t / (.012 if slap else .005))
    x += palm * (1.6 if slap else 0.5) * vel ** 1.5
    return finish(chain(x, p, vel))


def bell(p, vel=1.0, rng=None, seconds=1.4, which=0):
    """A small bell or agogo: a few inharmonic modes with long, frequency-dependent decays and
    a metal ping. `which` picks the high or low bell of a pair."""
    rng = rng or rng_for('bell')
    t = _t(seconds)
    f0 = p['bell_hz'] * (1 if which == 0 else p['pair'])
    x = np.zeros(len(t))
    for r, g in zip((1, p['br2'], p['br3'], 4.2), (1, .6 * vel ** .5, .4 * vel, .15 * vel ** 1.5)):  # harder strike, more upper modes
        f = f0 * r * (1 + rng.uniform(-.001, .001))
        if f < RATE * .45:
            x += g * np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28)) * np.exp(-t / (p['bell_ms'] / 1000 / r ** 0.6))
    x += _hp(rng.standard_normal(len(t)), 5000) * np.exp(-t / .0015) * .8 * vel
    return finish(chain(x, p, vel))


def block(p, vel=1.0, rng=None, seconds=0.3):
    """Wood block or clave: two short modes, a dry knock."""
    rng = rng or rng_for('block')
    t = _t(seconds)
    # A harder stick drives the upper mode and the knock (contact time shortens with force).
    dec = p['blk_ms'] / 1000 * (0.75 + 0.25 * vel)
    x = (np.sin(2 * np.pi * p['blk_hz'] * t) + (.15 + .55 * vel) * np.sin(2 * np.pi * p['blk_hz'] * 2.76 * t)) * np.exp(-t / dec)
    x += _bp(rng.standard_normal(len(t)), 1500, 2500 + 5000 * vel) * np.exp(-t / .001) * 1.2 * vel ** 1.5
    return finish(chain(x, p, vel))


# Parameter spaces the fitter searches (low, high). Chain parameters belong to every voice.
CHAIN = dict(drive=(0, 1), lp=(1200, 16000), tilt=(-12, 6))
SPACES = {
    'kick': dict(f0=(38, 120), sweep=(1.0, 4.0), sweep_ms=(5, 80), decay_ms=(40, 400), tail_ms=(80, 900),
                 tail_mix=(0, .6), shell=(0, .6), shell_ratio=(1.4, 2.6), click=(0, 1.2), click_hz=(800, 6000),
                 skin=(0, 1.2), skin_ms=(5, 120), **CHAIN),
    'snare': dict(f1=(140, 330), r2=(1.4, 2.4), m2=(0, 1), mode_ms=(15, 200), wire=(.2, 3), wire_lo=(200, 3000),
                  wire_hi=(3000, 15000), wire_ms=(20, 250), rattle=(0, .6), click=(0, 1.5), **CHAIN),
    'hat': dict(metal_hz=(250, 600), metal=(0, 1), bp_hz=(3000, 12000), hp_hz=(1500, 9000), decay_ms=(10, 250),
                **CHAIN),
}
RENDER = {'kick': kick, 'snare': snare, 'hat': hat}
