"""BOROUGH round 0 audition: render every kit and stack pad (3 velocity zones x 3 takes), grooves,
kit+stack combinations and a listening page. Python harness, not Kotlin. Reads fitted.json
(numbers only); no reference PCM is read here or written to the output.

    python3 build.py fitted.json OUT_DIR
"""
from __future__ import annotations
import html, importlib, json, math, os, sys, time, zipfile
from pathlib import Path
import numpy as np
from scipy import signal
from scipy.io import wavfile
import voices as V
import kits as K
import extract as X

# Round 0 rendered with voices.py (drum-machine recipes); round 1 with acoustic.py (EMBER models).
MODEL = os.environ.get('BOROUGH_MODEL', 'voices')
M = importlib.import_module(MODEL)

SR = V.SR
ZONES = [0.35, 0.65, 1.0]
TAKES = 3


def jitter(p, rng):
    q = dict(p)
    for k, v in p.items():
        if not isinstance(v, (int, float)):
            continue
        if k.endswith('_hz') or k in ('f0', 'f1', 'metal_hz', 'j1'):
            q[k] = v * (1 + rng.normal(0, .004))          # a retensioned head, a different spot on the plate
        elif k.endswith('_ms') or k == 't1':
            q[k] = v * (1 + rng.normal(0, .04))
        elif k in ('click', 'wire', 'skin', 'shell'):
            q[k] = v * float(np.clip(1 + rng.normal(0, .1), .7, 1.3))
    return q


def derive(kit, fitted):
    if MODEL != 'voices':
        return M.derive(kit, fitted, fitted['drum_tops_surf']['hat'])
    """Twelve drum pads from two fitted loops. Fitted: KICK, SNARE, HAT, KICK 2, SNARE 2.
    Derived (proposed, not fitted): the rest, each from its fitted parent."""
    a, b = fitted[kit['primary']], fitted[kit['secondary']]
    hat_src = a.get('hat') or b.get('hat') or fitted['drum_tops_surf']['hat']
    k1, s1, h = a['kick']['params'], a['snare']['params'], hat_src['params']
    k2 = (b.get('kick') or a['kick'])['params']; s2 = (b.get('snare') or a['snare'])['params']
    open_h = dict(h, open_ms=float(np.clip(h['decay_ms'] * 6, 140, 650)))
    pedal = dict(h, decay_ms=h['decay_ms'] * .55, bp_hz=h['bp_hz'] * .7, metal=min(1, h['metal'] + .2))
    tom = lambda m: dict(k1, f0=k1['f0'] * m, sweep=1.12, sweep_ms=25, decay_ms=150 + 40 / m, tail_mix=.15,
                         tail_ms=260, click=k1['click'] * .5, skin=.6, skin_ms=40)
    return [
        ('KICK', 'kick', k1, {}), ('SNARE', 'snare', s1, {}), ('HAT', 'hat', h, {}), ('OPEN HAT', 'hat', open_h, {'open_': True}),
        ('KICK 2', 'kick', k2, {}), ('SNARE 2', 'snare', s2, {}), ('RIM', 'rim', s1, {}), ('PEDAL HAT', 'hat', pedal, {}),
        ('GHOST', 'ghost', s1, {}), ('TOM LO', 'kick', tom(2.3), {}), ('TOM HI', 'kick', tom(3.4), {}),
        ('SNARE ROLL', 'roll', s1, {}),
    ]


def render_pad(kind, p, vel, rng, extra):
    if kind in ('dkick', 'dsnare', 'dghost'):   # round 2: designed boom-bap drums (designed.py)
        import designed as DS
        if kind == 'dkick': return DS.kick(p, vel, rng)
        if kind == 'dsnare': return DS.snare(p, vel, rng)
        return DS.snare(p, vel * .4, rng, seconds=.35)   # the ghost: the stick barely leaves the head
    if MODEL != 'voices' and kind != 'stack':
        return M.render_pad(kind, p, vel, rng, extra)
    if kind == 'stack':
        return M.stack_pad(p['voice'], p['macros'], p['target'], vel, rng.integers(1 << 20))
    if kind == 'kick': return V.kick(p, vel, rng)
    if kind == 'snare': return V.snare(p, vel, rng)
    if kind == 'rim': return V.rim(p, vel, rng)
    if kind == 'hat': return V.hat(p, vel, rng, seconds=1.0 if extra.get('open_') else .5, **extra)
    if kind == 'ghost':  # a ghost stroke: the stick barely leaves the head; more wire than head
        q = dict(p, wire=p['wire'] * 1.3, rattle=min(.8, p['rattle'] + .2))
        return V.snare(q, vel * .38, rng, seconds=.45)
    if kind == 'roll':   # a finite two-beat press roll: 32nds at 90 BPM, crescendo into an accent
        step = 60 / 90 / 8
        n = 16
        out = np.zeros(int((n * step + .6) * SR))
        for i in range(n + 1):
            a = (.25 + .75 * (i / n) ** 1.6) * vel
            hit = V.snare(p, a, np.random.default_rng(rng.integers(1 << 31)), seconds=.4) * a ** .8
            at = int(i * step * SR * (1 + rng.normal(0, .02)))
            out[at:at + len(hit)] += hit[:len(out) - at]
        return out / np.max(np.abs(out))
    if kind == 'shaker': return V.shaker(p, vel, rng)
    if kind == 'hand': return V.hand(p, vel, rng)
    if kind == 'hand_slap': return V.hand(p, vel, rng, slap=True)
    if kind == 'block': return V.block(p, vel, rng)
    if kind == 'tamb': return V.tamb(p, vel, rng)
    if kind == 'tamb_hit': return V.tamb(p, vel, rng, hit=True)
    if kind == 'tamb_roll':
        step = 60 / 90 / 4
        out = np.zeros(int((8 * step + .5) * SR))
        for i in range(8):
            a = (.3 + .7 * i / 7) * vel
            hit = V.tamb(p, a, np.random.default_rng(rng.integers(1 << 31))) * a ** .8
            at = int(i * step * SR); out[at:at + len(hit)] += hit[:len(out) - at]
        return out / np.max(np.abs(out))
    if kind == 'bell': return V.bell(p, vel, rng)
    if kind == 'bell_lo': return V.bell(p, vel, rng, which=1)
    if kind == 'clap': return V.clap(p, vel, rng)
    if kind == 'ride': return V.ride(p, vel, rng)
    if kind == 'ride_bell': return V.ride(p, vel, rng, bell=True)
    raise ValueError(kind)


def grid(label, kind, p, extra):
    """3 zones x 3 takes. Takes re-seed the noise and move the physics a little; zones change the
    strike itself and then take the 0.89 * v^0.8 level law (the SOUL ROCK law)."""
    out = []
    for zi, v in enumerate(ZONES):
        row = []
        for take in range(TAKES):
            rng = V.rng_for(label, kind, zi, take)
            y = render_pad(kind, jitter(p, rng) if take else p, v, rng, extra)
            assert np.all(np.isfinite(y))
            row.append(y * .89 * v ** .8)
        out.append(row)
    return out


def write(path: Path, x):
    path.parent.mkdir(parents=True, exist_ok=True)
    wavfile.write(path, SR, np.round(np.clip(x, -1, 1) * 32767).astype(np.int16))


# Authored audition patterns (not transcriptions): 16 steps, one char per step.
# K kick, S snare, g ghost, h hat, o open hat, r rim, p pedal hat. Ride kits add an OVER lane.
PATTERNS = {
    'borough-stoop':   ['K.h.S.hKh.K.S.h.', 'K.h.S.h.hKK.S.o.'],
    'borough-cellar':  ['K.h.S.h...K.S.h.', 'K.h.S.hK..K.S.oK'],
    'borough-cranked': ['K.h.S.g.KKh.S.g.', 'K.h.S.g.K.K.S.gK'],
    'borough-wax':     ['KK.KS.h.K.K.S.h.', 'KK.KS.h.K.K.S.o.'],
    'borough-dry':     ['K.h.S...K.K.S...', 'K.h.S.h.K.K.S..K'],
    'borough-rims':    ['K.K.r...K.K.r...', 'K.K.r...K.K.r..K'],   # the ride loop's kick and rim places
}
# Cymbal lanes played over the pattern, for kits whose timekeeper is a ride: (pad index, steps, strength,
# bars) -- every 8th on the ride with the quarters leaning in, the bell to close every other bar, the
# pedal hat under the rims on 2 and 4. Separate from PATTERNS because a ride stroke shares its step
# with the kick and the rim (one character per step cannot hold both).
OVER = {
    'borough-rims': [(2, [0, 4, 8, 12], .8, (0, 1, 2, 3)), (2, [2, 6, 10], .55, (0, 1, 2, 3)),
                     (2, [14], .55, (0, 2)), (3, [14], .75, (1, 3)), (7, [4, 12], .45, (0, 1, 2, 3))],
}
LANE = dict(K=0, S=1, h=2, o=3, r=6, p=7, g=8)
STACK_PATTERNS = {   # (pad index 0-3, steps, zone)
    'hands': [(0, range(0, 16, 2), 1), (0, range(1, 16, 2), 0), (1, [3, 10], 2), (2, [6, 14], 1), (3, [11], 1)],
    'tamb':  [(0, range(0, 16), 0), (1, [4, 12], 2), (2, [5], 1)],
    'bells': [(0, [0, 8], 2), (1, [3, 11], 1), (2, [4, 12], 1), (3, [2, 10], 1)],
    'tops':  [(0, [4, 12], 2), (1, range(0, 16, 2), 1), (2, [14], 1), (3, [7], 1)],
}


NOMINAL = dict(K=.95, S=.92, g=.3, h=.5, o=.7, r=.7, p=.45)


_TARGETS = None


def loop_feel(kit):
    """What a kit's demo groove takes from its own loop (numbers in targets.json, never audio): the
    16th-grid pattern of each bar, the median push of each grid position relative to the loop's own
    on-beats, and the kick's hit-to-hit levels. Owner, round 1.4: "They all kind of sound pretty
    similar to each other": five of six demo grooves were one authored pattern at 90 BPM."""
    global _TARGETS
    if _TARGETS is None:
        _TARGETS = json.load(open(Path(__file__).with_name('targets.json')))['loops']
    t = _TARGETS.get(kit['primary'], {})
    tm = t.get('timing_ms') or {}
    on = tm.get('on_beat') or 0.0
    rel = lambda k: (tm.get(k) - on) if tm.get(k) is not None else 0.0
    kl = (t.get('levels_db') or {}).get('kick') or [0.0]
    top = max(kl)
    return dict(pattern=t.get('pattern'), eighth=rel('eighth_off'), sixteenth=rel('sixteenth_off'),
                backbeat=rel('backbeat'), kick_db=[d - top for d in kl])


def groove(kit, bank, stack=None, sbank=None, bars=4):
    """Each kit plays its own loop's groove (loop_feel): its pattern bar by bar (K kick, S snare, X both,
    g ghost, h hat; hats are only seen where nothing louder hit, so a hat found on a position in any bar
    plays there in every bar), its push on the off-8ths, off-16ths and backbeat, and its kick dynamics
    (a measured -13 dB kick is played soft). On top, a person's drift: sd 3 ms kick, 4 ms snare, 5 ms
    hats, strength sd 0.06 picking the nearest velocity zone and the next take, as a pad would.
    Ride kits keep their OVER lane for the ride and pedal hat. No authored fill."""
    bpm = kit['bpm']; st = 60 / bpm / 4
    feel = loop_feel(kit)
    pats = feel['pattern'] or PATTERNS[kit['id']]
    hat_slots = sorted({k for row in pats for k, c in enumerate(row) if c == 'h'}) if not kit.get('ride') else []
    kick_db = feel['kick_db']
    hr = V.rng_for('groove', kit['id'], stack['id'] if stack else '')   # seeded: same groove every run
    y = np.zeros(int((bars * 16 * st + 2) * SR)); count = {}
    def push(s, snare=False):
        if snare and s % 8 == 4: return feel['backbeat']
        return 0.0 if s % 4 == 0 else feel['eighth'] if s % 4 == 2 else feel['sixteenth']
    def place(key, step_abs, v, gain, sd_ms, late_ms=0.0):
        v = float(np.clip(v + hr.normal(0, .06), .15, 1.0))
        zone = int(np.argmin([abs(v - z) for z in ZONES]))
        cyc = count.get(key, 0); count[key] = cyc + 1
        hit = (bank if key[0] == 'k' else sbank)[key[1]][zone][cyc % TAKES] * (v / ZONES[zone]) ** .8
        at = max(0, int((step_abs * st + (late_ms + hr.normal(0, sd_ms)) / 1000) * SR))
        y[at:at + len(hit)] += hit[:len(y) - at] * gain
    kicks = 0
    for bar in range(bars):
        row = pats[bar % len(pats)]
        for s, c in enumerate(row):
            if c in 'KX':
                v = float(np.clip(NOMINAL['K'] * 10 ** (kick_db[kicks % len(kick_db)] / 20 / .8), .15, 1.0)); kicks += 1
                place(('k', LANE['K']), bar * 16 + s, v, 1, 3, push(s))
            if c in 'SX':   # a ride kit's backbeat is the rim click its loop plays
                place(('k', LANE['r'] if kit.get('ride') else LANE['S']), bar * 16 + s,
                      NOMINAL['r'] if kit.get('ride') else NOMINAL['S'], 1, 4, push(s, snare=True))
            if c == 'g':
                place(('k', LANE['g']), bar * 16 + s, NOMINAL['g'], 1, 4, push(s, snare=True))
        for s in hat_slots:
            place(('k', LANE['h']), bar * 16 + s, NOMINAL['h'] + (.25 if s % 4 == 0 else 0), .55, 5, push(s))
        for pad, steps, v, in_bars in OVER.get(kit['id'], []):
            if bar in in_bars:
                for s in steps:   # the ride rings 1.4 s, so eighths pile up: it sits lower than a hat
                    place(('k', pad), bar * 16 + s, v, .32 if pad in (2, 3) else .5, 5, push(s))
        if stack is not None:
            for pad, steps, zone in STACK_PATTERNS[stack['id']]:
                for s in steps:
                    place(('s', pad), bar * 16 + s, ZONES[zone], .5, 6, push(s))
    return y / np.max(np.abs(y)) * .89


KICK_PADS = (0, 4)   # A01 KICK, A05 KICK 2


def designed_pads(kit, pads, fits):
    """Round 2: where designed_fit.json (fit_designed.py) holds a kit's loops, its KICK, SNARE, KICK 2,
    SNARE 2 and GHOST become designed boom-bap drums fitted to those loops' fine hit measurements."""
    pads = list(pads)
    for i, loop, role, kind in ((0, kit['primary'], 'kick', 'dkick'), (1, kit['primary'], 'snare', 'dsnare'),
                                (4, kit['secondary'], 'kick', 'dkick'), (5, kit['secondary'], 'snare', 'dsnare'),
                                (8, kit['primary'], 'snare', 'dghost')):
        f = fits.get(loop, {}).get(role)
        if f:
            pads[i] = (pads[i][0], kind, f['params'], {})
    return pads


# The record chain taken off (BOROUGH_CLEAN=1): EMBER's drums as modelled, a small room, no squash,
# drive, tilt or low-pass. Owner, round 1.6: "They all just sound muddy, muffled, and flat", after every
# wow variant (louder, brighter, wider) built on the same chain. Measured against the loops' own hits,
# the pads' attacks and crests were mostly sharper, so the chain is tested by ear: an A/B, not a guess.
CLEAN_CHAIN = dict(distance=0.08, comp=0.0, drive=0.0, lp=16000.0, tilt=0.0)


def clean(fitted):
    out = json.loads(json.dumps(fitted))
    for loop in out.values():
        for role in loop.values():
            if isinstance(role, dict) and 'params' in role:
                role['params'].update({k: v for k, v in CLEAN_CHAIN.items() if k in role['params']})
    return out


def spread(fitted, kits, factor):
    """Push the kits apart: every fitted parameter of every role the kits use is moved `factor`
    times further from the kits' own average, in the fitter's normalised space (log for frequencies
    and times), and clipped to the model's bounds. Owner, round 1.4: the kits "all kind of sound pretty
    similar", and asked for them further apart than the loops are. The pads were already as far apart
    as the loops' drums (88-95% of the loops' spread); factor 1 leaves the fits untouched."""
    if factor == 1.0 or MODEL == 'voices':
        return fitted
    out = json.loads(json.dumps(fitted))
    loops = sorted({k['primary'] for k in kits} | {k['secondary'] for k in kits})
    logk = lambda k: k in getattr(M, 'LOGK', set()) or k.endswith(('_hz', '_ms')) or k in ('f0', 'f1', 'lp')
    for role, space in M.SPACES.items():
        have = [l for l in loops if role in fitted.get(l, {})]
        if len(have) < 2:
            continue
        for k, (lo, hi) in space.items():
            f = (lambda v: (math.log(v) - math.log(lo)) / (math.log(hi) - math.log(lo))) if logk(k) else (lambda v: (v - lo) / (hi - lo))
            g = (lambda u: math.exp(math.log(lo) + u * (math.log(hi) - math.log(lo)))) if logk(k) else (lambda u: lo + u * (hi - lo))
            u = {l: f(fitted[l][role]['params'][k]) for l in have}
            mean = sum(u.values()) / len(u)
            for l in have:
                v = g(min(1.0, max(0.0, mean + factor * (u[l] - mean))))
                cap = SPREAD_CAPS.get((role, k))
                if cap is not None:   # never past what the owner has heard, unless the fit already was
                    v = min(v, max(cap, fitted[l][role]['params'][k]))
                out[l][role]['params'][k] = round(v, 4)
    return out


# Limits on how far spread() may push a setting, from what the owner has heard: STOOP's kick ring was
# "overblown" at 1.18 s and "better" at 0.77 s (round 1.3); pushed, it went to 0.95 s with full drive
# and air. A fit that already rings longer keeps its own value.
SPREAD_CAPS = {('kick', 't1'): 0.8}


def glue_bus(x, threshold_db=-10.0, ratio=4.0, attack_ms=10.0, release_ms=120.0):
    """A gentle glue compressor across the whole kit, causal like a live one: 10 ms attack (the
    crack of each hit gets through), 120 ms release, ratio 4, threshold 10 dB under the peak."""
    a_at = math.exp(-1 / (attack_ms * 1e-3 * SR)); a_re = math.exp(-1 / (release_ms * 1e-3 * SR))
    thr = np.max(np.abs(x)) * 10 ** (threshold_db / 20)
    lvl = np.abs(x); env = np.empty_like(x); e = 0.0
    for i, l in enumerate(lvl):
        e = a_at * e + (1 - a_at) * l if l > e else a_re * e + (1 - a_re) * l
        env[i] = e
    y = x * np.maximum(env / thr, 1.0) ** (1 / ratio - 1)
    return y / (np.max(np.abs(y)) + 1e-12)


def limit(x, drive_db, look_ms=3.0, release_ms=50.0):
    """A lookahead peak limiter at a 1.0 ceiling: the needed gain, its minimum over the lookahead
    window, a 50 ms release (the app's own output guard), then smoothed over the lookahead (which
    cannot overshoot: every value averaged is a minimum over a window holding that sample).
    `drive_db` pushes into it. The release matters on kicks: a 3 ms recovery moves the gain inside
    one cycle of a 50-100 Hz head, which is distortion."""
    from scipy.ndimage import minimum_filter1d, uniform_filter1d
    y = x * 10 ** (drive_db / 20)
    L = max(1, int(look_ms * 1e-3 * SR))
    g = minimum_filter1d(np.minimum(1.0, 1.0 / (np.abs(y) + 1e-12)), 2 * L + 1)
    k = 1 - math.exp(-1 / (release_ms * 1e-3 * SR)); prev = 1.0
    for n in range(len(g)):
        prev = min(g[n], prev + (1.0 - prev) * k)
        g[n] = prev
    g = uniform_filter1d(g, L)
    return y * np.minimum(g, 1.0 / (np.abs(y) + 1e-12))


def bus_preview(x, drive_db):
    """PREVIEW ONLY, on demo grooves: the record's own chain across the whole kit, glue compressor
    then limiter, the way a break was squashed and then mastered. Back to a 0.89 peak. The app has
    no such bus yet (round 1.4: the owner chose to hear it before it is built), so it never touches
    a pad. Compression alone measured peakier, not denser: a compressor's attack lets each crack
    through (STOOP's groove 14.1 -> 22.2 dB crest), so the loops' density is a limiter's too."""
    y = limit(glue_bus(x), drive_db)
    return y / (np.max(np.abs(y)) + 1e-12) * .89


BUS_DRIVE_MAX_DB = 8.0
# The owner heard 5.0-7.5 dB of limiting and said it "generally helps" (round 1.4). With each kit on its
# own loop's groove the grooves came out peakier and the loop match asked for 10-16 dB, which is unheard,
# and would flatten every kit toward one squashed sound, against "they all sound similar"; DRY ROOM's
# silence between hits would fill in. Capped just above what was heard.


# ---------------------------------------------------------------- "wow" A/B (preview only, round 1.6)
# Owner, round 1.5: "Can you increase the vibrancy of the sound? It just doesn't have the wow", and chose
# an A/B page with every option. Measured against the loops at the same peak: the grooves 8-10 dB quieter
# (2.5-4 with the glue preview); CRANKED, DRY ROOM and WAX duller in presence and air; every kit mono.
# Each variant starts from the glue preview the owner liked. BRIGHT and WIDE are loudness-matched to it,
# so the owner hears character, not level; PUNCH is the one meant to be louder.

def _biquad(kind, f0, gain_db, q=0.707):
    """RBJ cookbook peaking / high-shelf biquad as an SOS row."""
    A = 10 ** (gain_db / 40); w = 2 * math.pi * f0 / SR; cw, sw = math.cos(w), math.sin(w); al = sw / (2 * q)
    if kind == 'peak':
        b = [1 + al * A, -2 * cw, 1 - al * A]; a = [1 + al / A, -2 * cw, 1 - al / A]
    else:   # high shelf
        sq = 2 * math.sqrt(A) * al
        b = [A * ((A + 1) + (A - 1) * cw + sq), -2 * A * ((A - 1) + (A + 1) * cw), A * ((A + 1) + (A - 1) * cw - sq)]
        a = [(A + 1) - (A - 1) * cw + sq, 2 * ((A - 1) - (A + 1) * cw), (A + 1) - (A - 1) * cw - sq]
    return np.array([b[0] / a[0], b[1] / a[0], b[2] / a[0], 1.0, a[1] / a[0], a[2] / a[0]])


def _rms(x):
    return float(np.sqrt(np.mean(np.asarray(x, float) ** 2)) + 1e-12)


def _peak89(y):
    return y / (np.max(np.abs(y)) + 1e-12) * .89


def wow_bright(x):
    """Brighter and crisper: +3 dB presence at 3 kHz (Q 0.8), +4 dB shelf above 6 kHz, and a little
    sparkle (the band above 3 kHz saturated, 12% in), so the top gains harmonics, not only level."""
    y = signal.sosfilt(np.vstack([_biquad('peak', 3000, 3.0, .8), _biquad('shelf', 6000, 4.0)]), x)
    hi = signal.sosfilt(signal.butter(2, 3000, btype='high', fs=SR, output='sos'), x)
    hp = signal.sosfilt(signal.butter(2, 3000, btype='high', fs=SR, output='sos'), np.tanh(4 * hi / (np.max(np.abs(hi)) + 1e-12)))
    y = y + .12 * hp * np.max(np.abs(hi))
    return y


def wow_punch(x, crest_target):
    """Louder and punchier: parallel compression (a copy squashed hard, 2 ms attack, 80 ms release,
    ratio 8, 30 dB under the peak, blended in at the dry hit's peak level) lifts the body while the
    dry hit keeps its crack; then the glue chain with its limiter at the 8 dB the owner has heard."""
    a_at = math.exp(-1 / (2e-3 * SR)); a_re = math.exp(-1 / (80e-3 * SR))
    thr = np.max(np.abs(x)) * 10 ** (-30 / 20); e = 0.0; env = np.empty_like(x)
    for i, l in enumerate(np.abs(x)):
        e = a_at * e + (1 - a_at) * l if l > e else a_re * e + (1 - a_re) * l
        env[i] = e
    sq = x * np.maximum(env / thr, 1.0) ** (1 / 8 - 1)
    y = x + sq * (np.max(np.abs(x)) / (np.max(np.abs(sq)) + 1e-12))
    y = glue_bus(y)
    # Loudness past the 8 dB of limiting the owner has heard comes from a soft clipper before it,
    # shaving only the first milliseconds of each hit (part of how loud breaks crunch). Its drive is
    # searched until the groove's crest is its loop's, at most 9 dB. Without it the 'louder' version
    # came out only +0.3 to +0.9 dB: the glue preview already used the full 8 dB.
    clip = lambda d: np.tanh(y * 10 ** (d / 20)) / np.tanh(10 ** (d / 20))
    n = int(10 * SR); crest = lambda d: X.crest_db(limit(clip(d)[:n], BUS_DRIVE_MAX_DB))
    lo, hi = 0.0, 9.0
    if crest_target is not None and crest(lo) > crest_target:
        if crest(hi) > crest_target:
            lo = hi
        else:
            for _ in range(10):
                mid = (lo + hi) / 2
                lo, hi = (mid, hi) if crest(mid) > crest_target else (lo, mid)
    y = limit(clip(lo), BUS_DRIVE_MAX_DB)
    return y / (np.max(np.abs(y)) + 1e-12) * .89


def wow_wide(x, seed=7):
    """Wider and bigger: a short stereo room (8 ms pre-delay, 0.5 s decay, 300 Hz-8 kHz) whose left and
    right are different, added as side only, about 14 dB under the kit. The mid is the kit untouched,
    so it sums back to mono exactly; nothing below 300 Hz is spread."""
    r = np.random.default_rng(seed); n = int(.6 * SR); t = np.arange(n) / SR
    env = np.exp(-6.9 * t / .5); env[:int(.008 * SR)] = 0
    band = signal.butter(2, [300, 8000], btype='band', fs=SR, output='sos')
    irl = signal.sosfilt(band, r.standard_normal(n)) * env; irr = signal.sosfilt(band, r.standard_normal(n)) * env
    side = (signal.fftconvolve(x, irl)[:len(x)] - signal.fftconvolve(x, irr)[:len(x)]) / 2
    side *= _rms(x) * 10 ** (-14 / 20) / _rms(side)
    return np.stack([x + side, x - side], 1)


def wow_variants(gv, crest_target):
    """The five A/B grooves, each at a 0.89 peak; BRIGHT and WIDE loudness-matched (RMS) to NOW."""
    now = bus_preview(gv, bus_drive(gv, crest_target))
    match = lambda y: y * (_rms(now) / _rms(y if y.ndim == 1 else y.mean(1)))
    bright = match(bus_preview(wow_bright(gv), bus_drive(wow_bright(gv), crest_target)))
    punch = wow_punch(gv, crest_target)
    wide = match(wow_wide(now))
    allx = wow_wide(wow_punch(wow_bright(gv), crest_target))
    fit = lambda y: y * min(1.0, .89 / (np.max(np.abs(y)) + 1e-12))   # matched ones may only come down
    return dict(now=now, punch=punch, bright=fit(bright), wide=fit(wide), all=_peak89(allx))


def bus_drive(x, crest_target, max_db=BUS_DRIVE_MAX_DB):
    """The limiter drive (dB) that brings this groove's crest (first 10 s) to its loop's, at most max_db."""
    n = int(10 * SR); g = glue_bus(x[:n])
    crest = lambda d: X.crest_db(limit(g, d))
    if crest_target is None or crest(0.0) <= crest_target:
        return 0.0
    lo, hi = 0.0, max_db       # crest falls as the drive rises
    if crest(hi) > crest_target:
        return hi
    for _ in range(12):
        mid = (lo + hi) / 2
        lo, hi = (mid, hi) if crest(mid) > crest_target else (lo, mid)
    return hi


def balance(kit, bank, target_db):
    """(kick gain, gain for every other drum pad) putting the groove's low-to-mid balance at the loop's.
    The kick is cut when it is too heavy; when it is too light, the rest of the kit is cut instead
    (the kick cannot go past its 0.89 peak), which is the same balance. Each side at most 12 dB.
    Round 1.4: with the loops' own grooves DRY ROOM and RIDE & RIMS came out light, which a cut-only
    trim could not fix."""
    g = kick_trim_one_sided(kit, bank, target_db)
    if g < 1.0 or target_db is None:
        return g, 1.0
    def bal(r):
        b = [pad if i in KICK_PADS else [[y * r for y in row] for row in pad] for i, pad in enumerate(bank)]
        return X.low_mid_db(groove(kit, b))
    if bal(1.0) >= target_db:
        return 1.0, 1.0
    lo, hi = 10 ** (-12 / 20), 1.0   # bal(lo) is the heaviest the kit can get, bal(hi) the lightest
    if bal(lo) <= target_db:
        return 1.0, lo
    for _ in range(14):   # the balance rises as the rest of the kit comes down
        mid = (lo * hi) ** .5
        lo, hi = (mid, hi) if bal(mid) > target_db else (lo, mid)
    return 1.0, (lo * hi) ** .5


def kick_trim_one_sided(kit, bank, target_db):
    """Gain for both kick pads that puts the groove's low-to-mid balance (extract.low_mid_db) at
    the primary loop's. Cuts only, never boosts (a boost would break the 0.89 peak), and at most
    12 dB. Fitting scores each pad's shape normalised to its own peak, so nothing else sets how
    loud a kick is against its snare; round 1's STOOP kick came out heavy ("a bit overpowering")."""
    if target_db is None:
        return 1.0
    def balance(g):
        b = [[[y * g for y in row] for row in pad] if i in KICK_PADS else pad for i, pad in enumerate(bank)]
        return X.low_mid_db(groove(kit, b))
    if balance(1.0) <= target_db:
        return 1.0
    lo, hi = 10 ** (-12 / 20), 1.0
    for _ in range(14):   # balance rises with the kick gain: bisect on it
        mid = (lo * hi) ** .5
        lo, hi = (mid, hi) if balance(mid) < target_db else (lo, mid)
    return lo


def stacks():
    if MODEL == 'voices':
        return K.STACKS
    return [dict(st, pads=[(label, 'stack', dict(voice=v, macros=m, target=t)) for label, v, m, t in st['pads']]) for st in M.STACKS]


STACKS = stacks()


def main(fitted_path, out):
    out = Path(out); fitted = spread(json.load(open(fitted_path)), K.KITS, getattr(K, 'SPREAD', 1.0))
    if os.environ.get('BOROUGH_CLEAN'):
        fitted = clean(fitted)
    t0 = time.time(); man = {'status': f"Python round {0 if MODEL == 'voices' else 1} audition ({MODEL}); not factory audio, not heard", 'kits': [], 'stacks': []}
    sbanks = {}
    for st in STACKS:
        bank = []
        for i, (label, kind, p) in enumerate(st['pads']):
            g = grid(f"{st['id']}-{label}", kind, p, {})
            for zi in range(3):
                for t in range(TAKES):
                    write(out / 'stacks' / st['id'] / f'A{13 + i:02d}-{label.lower().replace(" ", "-")}-v{zi + 1}-t{t + 1}.wav', g[zi][t])
            bank.append(g)
        sbanks[st['id']] = bank
        man['stacks'].append(dict(id=st['id'], name=st['name'], source=st['source'], pads=[dict(pad=13 + i, label=l, model=k, params=p) for i, (l, k, p) in enumerate(st['pads'])]))
    targets = json.load(open(Path(__file__).with_name('targets.json')))['loops']
    designed_fits = json.load(open(os.environ['BOROUGH_DESIGNED'])) if os.environ.get('BOROUGH_DESIGNED') else {}
    for kit in K.KITS:
        pads = designed_pads(kit, derive(kit, fitted), designed_fits)
        bank = [grid(f"{kit['id']}-{label}", kind, p, extra) for label, kind, p, extra in pads]
        trim, rest = balance(kit, bank, targets[kit['primary']].get('low_mid_db'))
        bank = [[[y * (trim if i in KICK_PADS else rest) for y in row] for row in pad] for i, pad in enumerate(bank)]
        for i, (label, kind, p, extra) in enumerate(pads):
            for zi in range(3):
                for t in range(TAKES):
                    write(out / 'kits' / kit['id'] / f'A{i + 1:02d}-{label.lower().replace(" ", "-")}-v{zi + 1}-t{t + 1}.wav', bank[i][zi][t])
        gv = groove(kit, bank)
        write(out / 'demo' / f"{kit['id']}-groove.wav", gv)
        bus_db = bus_drive(gv, targets[kit['primary']].get('crest_db'))
        write(out / 'demo' / f"{kit['id']}-groove-glued.wav", bus_preview(gv, bus_db))
        for name, y in wow_variants(gv, targets[kit['primary']].get('crest_db')).items():
            write(out / 'demo' / f"{kit['id']}-wow-{name}.wav", y)
        for st in STACKS:
            write(out / 'demo' / f"{kit['id']}+{st['id']}.wav", groove(kit, bank, st, sbanks[st['id']]))
        tour = []
        for g in bank:
            tour += [g[2][0], np.zeros(int(.12 * SR))]
        write(out / 'demo' / f"{kit['id']}-pads.wav", np.concatenate(tour))
        touch = []
        for pi in (0, 1, 2):
            for zi in range(3):
                for t in range(TAKES):
                    clip = bank[pi][zi][t][:int(.5 * SR)].copy()      # a ride rings past 0.5 s: fade, no click
                    clip[-441:] *= np.linspace(1, 0, min(441, len(clip)))[-len(clip[-441:]):]
                    touch += [clip, np.zeros(int(.08 * SR))]
        write(out / 'demo' / f"{kit['id']}-touch.wav", np.concatenate(touch))
        man['kits'].append(dict(kit, kick_trim_db=round(20 * np.log10(trim), 2), rest_trim_db=round(20 * np.log10(rest), 2),
                                bus_preview=dict(limiter_drive_db=round(bus_db, 2), crest_db=round(X.crest_db(bus_preview(gv, bus_db)[:int(10 * SR)]), 2),
                                                 crest_dry_db=round(X.crest_db(gv[:int(10 * SR)]), 2), loop_crest_db=targets[kit['primary']].get('crest_db')),
                                pads=[dict(pad=i + 1, label=l, model=k, params=p, extra=e) for i, (l, k, p, e) in enumerate(pads)],
                                fitted_residual_db={r: fitted[kit['primary']][r]['residual_db'] for r in fitted[kit['primary']]}))
        print(kit['id'], round(time.time() - t0, 1), 's', flush=True)
    for st in STACKS:
        tour = []
        for g in sbanks[st['id']]:
            tour += [g[2][0][:int(1.4 * SR)], np.zeros(int(.12 * SR))]
        write(out / 'demo' / f"stack-{st['id']}-pads.wav", np.concatenate(tour))
    man['render_seconds'] = round(time.time() - t0, 1)
    (out / 'manifest.json').write_text(json.dumps(man, indent=1, default=float))
    print('done', man['render_seconds'], 's')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
