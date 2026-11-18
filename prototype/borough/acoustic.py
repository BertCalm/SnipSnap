"""BOROUGH round 1 voices: acoustic drums from EMBER's physical models, plus a sampled-break chain.
Python harness, not Kotlin.

Round 0 (voices.py) used drum-machine recipes: a swept sine kick, two sines and white noise for the
snare, six square waves for the hats. The owner heard it as "quite drum machine like", which is what
those recipes are. Round 1 replaces every voice with EMBER's acoustic models (prototype/ember,
owner-approved "Much better", 2026-11-15):

  kick  : felt beater + the air it pushes -> calf-loss membrane (18 Bessel modes, high modes die
          first, pitch sag on hard hits, second head beating) -> the kit hums along (snare wires,
          a floor tom answering)
  snare : hickory stick -> membrane -> threshold snare wires driven by the head's own motion, so a
          soft stroke is clean and a hard stroke sizzles; the stick on the head heard directly
  hats, ride, toms, rim, roll, clap, shaker, tambourine, bongos, conga, cowbell: EMBER's voices
          (the cymbals, hats and Latin voices fitted to CC0 recordings), retuned and re-chained here

Then the part round 0 did not have, the record: a room around the kit (EMBER's room by DISTANCE),
and a sampled-break chain per hit at 44.1 kHz: a compressor that lifts the room and the tail, drive,
a high-shelf tilt and a 2-pole low-pass. Every parameter is a named physical or recording quantity
that fit.py moves against the reference descriptors. No reference PCM is read here.
"""
from __future__ import annotations
import functools, hashlib, math, os, sys
import numpy as np
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, '..', 'ember'))
import ember_proto as E  # noqa: E402

SR = 44_100
FS = E.FS            # 176.4 kHz, EMBER's internal rate


def rng_for(*parts):
    h = hashlib.sha256(':'.join(str(p) for p in ('borough-r1',) + parts).encode()).digest()
    return np.random.default_rng(int.from_bytes(h[:8], 'little'))


# ------------------------------------------------------------------ the record (44.1 kHz)

def compress(x, amount, attack_ms=2.0, release_ms=90.0, ratio=4.0):
    """Feed-forward peak compressor. amount 0 is a bypass; 1 puts the threshold 24 dB under the
    hit's peak. Brings the room and the drum's tail up against the attack, as a squashed break does."""
    if amount <= 1e-3:
        return x
    pk = np.max(np.abs(x)) + 1e-12
    thr = pk * 10 ** (-24 * amount / 20)
    a_at = math.exp(-1 / (attack_ms * 1e-3 * SR)); a_re = math.exp(-1 / (release_ms * 1e-3 * SR))
    lvl = np.abs(x)
    env = np.empty_like(x); e = 0.0
    for i, l in enumerate(lvl):           # loop is fine at 44.1 kHz and < 1 s
        e = a_at * e + (1 - a_at) * l if l > e else a_re * e + (1 - a_re) * l
        env[i] = e
    over = np.maximum(env / thr, 1.0)
    gain = over ** (1 / ratio - 1)
    return x * gain


try:                                       # the loop above is the slow part of a fit; use numba when present
    import numba
    @numba.njit(cache=True)
    def _env(lvl, a_at, a_re):
        env = np.empty_like(lvl); e = 0.0
        for i in range(len(lvl)):
            l = lvl[i]
            e = a_at * e + (1 - a_at) * l if l > e else a_re * e + (1 - a_re) * l
            env[i] = e
        return env

    def compress(x, amount, attack_ms=2.0, release_ms=90.0, ratio=4.0):  # noqa: F811
        if amount <= 1e-3:
            return x
        pk = np.max(np.abs(x)) + 1e-12
        thr = pk * 10 ** (-24 * amount / 20)
        env = _env(np.abs(x), math.exp(-1 / (attack_ms * 1e-3 * SR)), math.exp(-1 / (release_ms * 1e-3 * SR)))
        return x * np.maximum(env / thr, 1.0) ** (1 / ratio - 1)
except ImportError:
    pass


def record(y44, p, vel):
    """The sampled-break chain on one hit at 44.1 kHz: compress -> drive (harder hits drive harder)
    -> tilt -> low-pass. Shape constants; the amounts are fitted per voice."""
    y = compress(y44, p.get('comp', 0.0))
    d = p.get('drive', 0.0) * (0.35 + 0.65 * vel)
    if d > 1e-3:
        g = 1 + 10 * d
        pk = np.max(np.abs(y)) + 1e-12
        y = (np.tanh(g * y / pk + 0.1 * d) - math.tanh(0.1 * d)) * pk / g
    if p.get('tilt', 0.0):
        hi = signal.sosfilt(signal.butter(1, 1000, fs=SR, btype='high', output='sos'), y)
        y = y + (10 ** (p['tilt'] / 20) - 1) * hi
    y = signal.sosfilt(signal.butter(2, min(p.get('lp', 16000), SR * .45), fs=SR, output='sos'), y)
    return y


def to44(raw, distance):
    """Room at the internal rate (EMBER's room, cached by DISTANCE), then 4:1 polyphase decimation."""
    n = len(raw)
    y = E.conv(raw, E.room_ir(round(float(distance), 2)), n)
    y = signal.resample_poly(y, 1, 4)
    return y - np.mean(y)


def end(y, seconds):
    """Cut to length, trim at -60 dB, DC high-pass, a release where still ringing, peak 1."""
    y = y[:int(seconds * SR)]
    y = signal.sosfilt(signal.butter(1, 20, fs=SR, btype='high', output='sos'), y)
    w = int(.01 * SR)
    e = np.sqrt(np.convolve(y * y, np.ones(w) / w, 'same'))
    live = np.where(e > e.max() * 1e-3)[0]
    stop = min(len(y), (live[-1] if len(live) else len(y)) + w)
    y = y[:stop]
    still = e[stop - 1] > e.max() * 3e-3
    k = min(int(.25 * SR), len(y) // 4) if still else min(w, len(y) // 4)
    y[-k:] *= 0.5 + 0.5 * np.cos(np.linspace(0, math.pi, k))
    return y / (np.max(np.abs(y)) + 1e-12)


# ------------------------------------------------------------------ fitted voices

def kick(p, vel=1.0, rng=None, seconds=0.75):
    """EMBER's bass drum with its numbers exposed: head pitch f1, decay t1, loss law alpha, beater
    contact width, the air push, strike radius, second-head ratio, the kit's hum, a beater click."""
    rng = rng or rng_for('kick')
    n = E.n_of(seconds + 0.05)
    beater = E.mallet(vel, rng, n, width_hard=p['beater_ms'] * 1e-3)
    air = E.hann_force(9e-3, p['air'] * vel ** 1.6, n)
    r = float(np.clip(p['r'] + rng.normal(0, 0.04), 0, .9))
    ir = E.peak_norm(E.membrane_ir(p['f1'], n, p['t1'], p['alpha'], r, rng.uniform(0, math.pi),
                                   sag=0.07 * vel * vel, sag_tau=0.04 + 0.02 * rng.random(), reso=(p['reso'], 0.35)))
    out = E.conv(beater + air, ir, n)
    if p['hum'] > 0:
        head = E.lp(out, 300)
        out += 0.03 * p['hum'] * E.WIRE_GAIN * np.max(np.abs(out)) * E.wires(head / (np.max(np.abs(head)) + 1e-12) * vel, vel, 0.8, rng, n)
        tom = E.peak_norm(E.membrane_ir(p['f1'] * 1.6, n, 0.9, 1.1, 0.3, 0.7))
        out += 0.10 * p['hum'] * E.peak_norm(E.conv(E.lp(out, 200), tom, n)) * vel
    out += E.hp(E.hann_force(0.5e-3, p['click'] * vel ** 2, n), 1500)
    return end(record(to44(out, p['distance']), p, vel), seconds)


def snare(p, vel=1.0, rng=None, seconds=0.7, force_scale=1.0, wire_scale=1.0):
    """EMBER's snare core with its numbers exposed: head pitch, decay, loss law, strike radius, how
    loose the wires are (buzz) and how loud, the stick's contact width, the stick heard directly."""
    rng = rng or rng_for('snare')
    n = E.n_of(seconds + 0.05)
    force = E.stick(vel, rng, n, width_hard=p['stick_ms'] * 1e-3) * force_scale
    r = float(np.clip(p['r'] + rng.normal(0, 0.07), 0, .95))
    ir = E.peak_norm(E.membrane_ir(p['f1'], n, p['t1'], p['alpha'], r, rng.uniform(0, math.pi), sag=0.03 * vel * vel,
                                   sag_tau=0.04 + 0.02 * rng.random(), scatter=rng))
    head = E.conv(force, ir, n)
    k = E.n_of(0.08)
    ref = E.conv(E.hann_force(0.28e-3, 1.0, k), ir[:k], k)
    x = E.lp(head, 900) / (np.max(np.abs(E.lp(ref, 900))) + 1e-12)
    hd = np.sign(x) * np.abs(x) ** 0.6
    out = head + p['wire'] * wire_scale * E.WIRE_GAIN * np.max(np.abs(ref)) * E.wires(hd, vel, p['buzz'], rng, n)
    out += E.hp(force, 2500) * p['crack']
    return end(record(to44(out, p['distance']), p, vel), seconds)


def ember_voice(voice, macros, p, vel, rng_take, seconds=None):
    """Any EMBER voice, dry of EMBER's own warmth, through this collection's room and record chain.
    EMBER seeds its takes from (voice, take); `rng_take` picks the take number."""
    m = dict(macros); m.setdefault('DISTANCE', p.get('distance', .2)); m['WARMTH'] = 0.0
    y = E.render(voice, m, vel, int(rng_take))
    y = record(y, p, vel)
    return end(y, seconds or len(y) / SR)


def hat(p, vel=1.0, rng=None, seconds=0.45, kind='HAT_CLOSED'):
    take = 0 if rng is None else int(rng.integers(1 << 20))
    return ember_voice(kind, dict(TUNE=p['tune'], DAMP=p['damp'], BUZZ=p['buzz'], DISTANCE=p['distance']), p, vel, take,
                       seconds if kind != 'HAT_OPEN' else None)


CHAIN = dict(distance=(0, .7), comp=(0, 1), drive=(0, 1), lp=(1200, 16000), tilt=(-12, 6))
SPACES = {
    'kick': dict(f1=(38, 110), t1=(.08, 1.2), alpha=(.6, 1.6), beater_ms=(.5, 3.5), air=(0, 1), r=(0, .5),
                 reso=(1.0, 1.15), hum=(0, 1), click=(0, .3), **CHAIN),
    'snare': dict(f1=(150, 340), t1=(.04, .5), alpha=(.6, 1.6), r=(.05, .7), buzz=(.05, 1), wire=(.2, 3),
                  stick_ms=(.15, .8), crack=(0, .5), **CHAIN),
    'hat': dict(tune=(0, 1), damp=(0, 1), buzz=(0, 1), **CHAIN),
}
RENDER = {'kick': kick, 'snare': snare, 'hat': hat}
LOGK = {'f1', 'lp', 't1', 'beater_ms', 'stick_ms'}


# ------------------------------------------------------------------ kits and stacks

def tune_for(f1, base, span):
    """EMBER TUNE that puts a voice's head at f1 (inverse of E.semis), clamped to 0..1."""
    return float(np.clip(0.5 + math.log2(f1 / base) * 12 / (2 * span), 0, 1))


def derive(kit, fitted, fallback_hat):
    """Twelve pads. Fitted: KICK, SNARE, HAT (A01-A03), KICK 2, SNARE 2 (A05, A06). The rest are EMBER
    voices tuned and chained from their fitted parent (proposed, not fitted)."""
    a, b = fitted[kit['primary']], fitted[kit['secondary']]
    k1, s1 = a['kick']['params'], a['snare']['params']
    h = (a.get('hat') or b.get('hat') or fallback_hat)['params']
    k2 = (b.get('kick') or a['kick'])['params']; s2 = (b.get('snare') or a['snare'])['params']
    snare_tune = tune_for(s1['f1'], 285.0, 5)
    sch = {k: s1[k] for k in CHAIN}
    floor_tune = tune_for(k1['f1'] * 2.0, 104.0, 6)    # EMBER floor tom base 104 Hz, rack 172 Hz
    rack_tune = tune_for(k1['f1'] * 3.2, 172.0, 6)
    return [
        ('KICK', 'kick', k1, {}), ('SNARE', 'snare', s1, {}), ('HAT', 'hat', h, {}),
        ('OPEN HAT', 'hat', h, {'kind': 'HAT_OPEN'}),
        ('KICK 2', 'kick', k2, {}), ('SNARE 2', 'snare', s2, {}),
        ('RIM', 'ember', dict(sch, voice='CROSS_STICK', macros=dict(TUNE=snare_tune, DAMP=.4, BUZZ=s1['buzz'])), {}),
        ('PEDAL HAT', 'hat', h, {'kind': 'HAT_FOOT'}),
        ('GHOST', 'ghost', s1, {}),
        ('TOM LO', 'ember', dict(sch, voice='TOM_FLOOR', macros=dict(TUNE=floor_tune, DAMP=.45, BUZZ=.4)), {}),
        ('TOM HI', 'ember', dict(sch, voice='TOM_RACK', macros=dict(TUNE=rack_tune, DAMP=.45, BUZZ=.4)), {}),
        ('SNARE ROLL', 'ember', dict(sch, voice='PRESS_ROLL', macros=dict(TUNE=snare_tune, SPEED=.55, BUZZ=s1['buzz'])), {}),
    ]


def render_pad(kind, p, vel, rng, extra):
    if kind == 'kick': return kick(p, vel, rng)
    if kind == 'snare': return snare(p, vel, rng)
    if kind == 'ghost':   # the stick barely leaves the head: a soft stroke, wires relatively louder
        return snare(p, vel * .4, rng, seconds=.5, wire_scale=1.3)
    if kind == 'hat': return hat(p, vel, rng, kind=extra.get('kind', 'HAT_CLOSED'))
    if kind == 'ember':
        return ember_voice(p['voice'], p['macros'], p, vel, int(rng.integers(1 << 20)))
    raise ValueError(kind)


# Perc stacks: EMBER's acoustic percussion (cowbell, clave, bongos, shaker fitted to CC0 recordings;
# tambourine, conga, claps physical), tuned so the strongest peak lands on the measured peak of the
# reference loop (the last number, Hz, searched by `tuned`), then a light shared chain. Where TUNE
# cannot reach it (the block, the thud, the log, the bells) a resample moves every mode together, as
# scaling the object would (decays shorten with the size, a disclosed analogy). The bell pair is the
# ride's bell, the strongest of EMBER's bells near 2 to 3.5 kHz; the cowbell's strongest partial
# stays at its lowest mode however it is shifted.
STACK_CHAIN = dict(distance=.25, comp=.35, drive=.15, lp=14000, tilt=0)
STACKS = [
    dict(id='hands', name='HANDS', source='perc_stack_hudson',
         pads=[('SHAKER', 'SHAKER', dict(TUNE=.6, DAMP=.4), 5600),
               ('BONGO LO', 'BONGO_LOW', dict(DAMP=.35), 400),
               ('BONGO HI', 'BONGO_HIGH', dict(DAMP=.35), 750),
               ('BLOCK', 'CLAVE', dict(DAMP=.5), 950)]),
    dict(id='tamb', name='TAMBOURINE', source='perc_stack_tamb_simpson',
         pads=[('TAMB SHAKE', 'TAMBOURINE', dict(TUNE=.5, DAMP=.55, BUZZ=.6), 5300),
               ('TAMB HIT', 'TAMBOURINE', dict(TUNE=.5, DAMP=.2, BUZZ=.9), 5300),
               ('THUD', 'CONGA', dict(DAMP=.6, SLAP=.1), 88),
               ('CLAPS', 'CLAPS', dict(CROWD=.5), None)]),
    dict(id='bells', name='BELLS', source='perc_stack_bells_rockaway',
         pads=[('BELL HI', 'RIDE_BELL', dict(DAMP=.35), 3450),     # the pair measured at 3.45 and 2.52 kHz
               ('BELL LO', 'RIDE_BELL', dict(DAMP=.35), 2520),
               ('JINGLE', 'TAMBOURINE', dict(TUNE=1.0, DAMP=.4, BUZZ=1.0), 7100),
               ('LOG', 'CONGA', dict(DAMP=.7, SLAP=.2), 395)]),
    dict(id='tops', name='TOPS', source='drum_tops_claps_rogers + drum_tops_surf',
         pads=[('CLAP', 'CLAPS', dict(CROWD=.35), None),
               ('RIDE', 'RIDE', dict(DAMP=.25), None),
               ('RIDE BELL', 'RIDE_BELL', dict(DAMP=.25), None),
               ('STICKS', 'STICKS', dict(DAMP=.5), None)]),
]


def peak_hz(y, lo=60, hi=16000):
    seg = y[:int(.12 * SR)]
    X = np.abs(np.fft.rfft(seg * np.hanning(len(seg)), n=1 << 16)); f = np.fft.rfftfreq(1 << 16, 1 / SR)
    sel = (f > lo) & (f < hi)
    return float(f[sel][np.argmax(X[sel])])


@functools.cache
def tuned(voice, macros_items, target):
    """(TUNE, resample ratio) putting `voice`'s strongest peak at `target` Hz: best TUNE on a grid,
    then whatever is left as a resample ratio (1.0 when TUNE alone reaches it)."""
    macros = dict(macros_items)
    if target is None:
        return macros.get('TUNE', .5), 1.0
    best = None
    for t in np.linspace(0, 1, 9):
        y = E.render(voice, dict(macros, TUNE=float(t), DISTANCE=0, WARMTH=0), 1.0, 0)
        hz = peak_hz(y, target / 4, min(16000, target * 4))
        err = abs(math.log2(hz / target))
        if best is None or err < best[0]:
            best = (err, float(t), hz)
    return best[1], target / best[2]


def stack_pad(voice, macros, target, vel, take, rate_shift_ok=True):
    tune, ratio = tuned(voice, tuple(sorted(macros.items())), target)
    m = dict(macros, TUNE=tune, DISTANCE=STACK_CHAIN['distance'], WARMTH=0)
    y = E.render(voice, m, vel, int(take))
    if rate_shift_ok and abs(math.log2(ratio)) > 1 / 24:           # more than a quarter tone off
        y = signal.resample(y, max(64, int(len(y) / ratio)))
    return end(record(y, STACK_CHAIN, vel), len(y) / SR)
