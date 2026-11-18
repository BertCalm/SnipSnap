"""BOROUGH round 0 audition: render every kit and stack pad (3 velocity zones x 3 takes), grooves,
kit+stack combinations and a listening page. Python harness, not Kotlin. Reads fitted.json
(numbers only); no reference PCM is read here or written to the output.

    python3 build.py fitted.json OUT_DIR
"""
from __future__ import annotations
import html, importlib, json, os, sys, time, zipfile
from pathlib import Path
import numpy as np
from scipy.io import wavfile
import voices as V
import kits as K

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


def groove(kit, bank, stack=None, sbank=None, bars=4):
    """Round 1 plays it like a person: every hit drifts a few ms (sd 3 ms kick, 4 ms snare, 5 ms
    hats; shape), the backbeat sits 6 ms late, quarter-note hats are accented, and each hit's
    strength wanders (sd 0.06) and picks the nearest velocity zone and take in turn, as a pad would."""
    bpm = kit['bpm']; st = 60 / bpm / 4
    hr = V.rng_for('groove', kit['id'], stack['id'] if stack else '')   # seeded: same groove every run
    y = np.zeros(int((bars * 16 * st + 2) * SR)); count = {}
    def place(key, step_abs, v, gain, sd_ms, late_ms=0.0):
        v = float(np.clip(v + hr.normal(0, .06), .15, 1.0))
        zone = int(np.argmin([abs(v - z) for z in ZONES]))
        cyc = count.get(key, 0); count[key] = cyc + 1
        hit = (bank if key[0] == 'k' else sbank)[key[1]][zone][cyc % TAKES] * (v / ZONES[zone]) ** .8
        sw = kit['swing_ms'] / 1000 if step_abs % 2 else 0
        at = max(0, int((step_abs * st + sw + (late_ms + hr.normal(0, sd_ms)) / 1000) * SR))
        y[at:at + len(hit)] += hit[:len(y) - at] * gain
    for bar in range(bars):
        pat = PATTERNS[kit['id']][bar % 2 if bar < 3 else 1]
        for s, c in enumerate(pat):
            if c == '.': continue
            v = NOMINAL[c] + (.25 if c == 'h' and s % 4 == 0 else 0)
            place(('k', LANE[c]), bar * 16 + s, v, .55 if c in 'hop' else 1,
                  3 if c == 'K' else 4 if c in 'Sgr' else 5, 6 if c == 'S' else 0)
        for pad, steps, v, in_bars in OVER.get(kit['id'], []):
            if bar in in_bars:
                for s in steps:   # the ride rings 1.4 s, so eighths pile up: it sits lower than a hat
                    place(('k', pad), bar * 16 + s, v, .32 if pad in (2, 3) else .5, 5)
        if bar == 3:
            place(('k', 9), bar * 16 + 13, .9, .8, 4); place(('k', 10), bar * 16 + 14, .7, .7, 4)
        if stack is not None:
            for pad, steps, zone in STACK_PATTERNS[stack['id']]:
                for s in steps:
                    place(('s', pad), bar * 16 + s, ZONES[zone], .5, 6)
    return y / np.max(np.abs(y)) * .89


def stacks():
    if MODEL == 'voices':
        return K.STACKS
    return [dict(st, pads=[(label, 'stack', dict(voice=v, macros=m, target=t)) for label, v, m, t in st['pads']]) for st in M.STACKS]


STACKS = stacks()


def main(fitted_path, out):
    out = Path(out); fitted = json.load(open(fitted_path))
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
    for kit in K.KITS:
        pads = derive(kit, fitted); bank = []
        for i, (label, kind, p, extra) in enumerate(pads):
            g = grid(f"{kit['id']}-{label}", kind, p, extra)
            for zi in range(3):
                for t in range(TAKES):
                    write(out / 'kits' / kit['id'] / f'A{i + 1:02d}-{label.lower().replace(" ", "-")}-v{zi + 1}-t{t + 1}.wav', g[zi][t])
            bank.append(g)
        write(out / 'demo' / f"{kit['id']}-groove.wav", groove(kit, bank))
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
        man['kits'].append(dict(kit, pads=[dict(pad=i + 1, label=l, model=k, params=p, extra=e) for i, (l, k, p, e) in enumerate(pads)],
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
