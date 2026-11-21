"""BOROUGH round 3.1: fuller and more vibrant, less sluggish and muddled. Python harness, not Kotlin.

Owner, on the round 3 A/B (the app's export, every groove with its default perc stack, through the preview glue):
"I feel like the sound needs to be a bit fuller and vibrant. It feels sluggish and muddled" (2026-11-21).

Measured on the app's own pads (listen_app.py), 10 s of each groove, against the kit's reference loop:
  - The space between hits: the quietest 2% of the groove's onset flux sits 26-33 dB under its loudest, where the
    loops sit 43-69 dB under. The drums alone sit 51-138 dB under (RIDE & RIMS 32, its ride rings 1.4 s): the
    default perc stack fills the gaps, and the glue lifts them a further 4-7 dB.
  - The bottom: with the stack, energy under 150 Hz against 150 Hz-5 kHz falls 2-7 dB under the drums alone, and
    4-8 dB under the loop in CELLAR, DRY ROOM, WAX and RIDE & RIMS.
  - Why: the stack pads carry no level trim (each peaks at 0.89, against the kit's trimmed snare and hats at
    0.28-0.54), and THUD, BONGO LO, LOG, STICKS, BELL LO and the TOPS ride ring 0.5-1.4 s in the low and low mid.
  - The top is not missing: 99% of each groove's energy lies under 10-15 kHz, the loops' under 1.2-4.5 kHz.

Variants, each on the app's pads, the same seeded groove and the kit's default stack:
  now      : as on the round 3 page.
  perc     : the stack sits at the kit's own level (its rest trim, as the drums' snare and hats) and every stack
             tail is shortened (-30 dB by 250 ms; bells and the ride 600 ms); RIDE & RIMS' ride and bell
             600 ms. A kit change: it would move into the recipes.
  dense    : perc, then tape-style saturation on the whole kit, driven until the glued groove's crest meets the
             loop's (at most 6 dB), then the same glue. A change to the bus the app is to switch on at load.
Tried and dropped: a faster, lighter glue (60 ms release, 4 dB limiting) raised every kit's crest 2-4 dB:
peakier, the opposite of fuller.
Each variant is level-matched to `now` by RMS, so none wins by being louder.

    python3 fuller.py <app export dir> <out dir>
"""
from __future__ import annotations
import json, math, sys
from pathlib import Path
import numpy as np

import listen_app as L
import build as B
import extract as X

LONG = {'BELL HI', 'BELL LO', 'RIDE', 'RIDE BELL'}   # rung by design: a shorter tail, not a choke
TAIL_MS, LONG_TAIL_MS = 250.0, 600.0


def shorten(x, tail_ms, hold_ms=30.0):
    """Leave the strike alone for hold_ms, then add an exponential decay that reaches -30 dB at tail_ms."""
    n = np.arange(len(x)) / B.SR * 1000.0
    k = 30.0 / max(tail_ms - hold_ms, 1.0)            # dB per ms
    return x * 10 ** (-np.maximum(n - hold_ms, 0.0) * k / 20)


def tighten_stack(sbank, labels, trim):
    return [[[shorten(c, LONG_TAIL_MS if lab in LONG else TAIL_MS) * trim for c in zone] for zone in pad]
            for pad, lab in zip(sbank, labels)]


def tighten_ride(bank, labels):
    return [[[shorten(c, LONG_TAIL_MS) for c in zone] for zone in pad] if lab in LONG else pad
            for pad, lab in zip(bank, labels)]


SAT_MAX_DB = 6.0


def saturate(x, crest_target, drive_db):
    """Tape-style saturation on the whole kit, then the round-2 glue at the kit's limiter drive: tanh driven by g dB
    over the groove's own peak. g is bisected until the glued groove's crest meets its loop's, at most SAT_MAX_DB.
    Saturation thickens each hit (the peak rounds, the body stays), where more limiting would pump and lift the
    gaps the perc fix opened."""
    m = np.max(np.abs(x)) + 1e-12
    def chain(g_db):
        g = 10 ** (g_db / 20)
        return B.bus_preview(np.tanh(g * x / m) / math.tanh(g), drive_db)
    lo, hi = 0.0, SAT_MAX_DB
    if X.crest_db(chain(hi)[:int(10 * B.SR)]) > crest_target:
        return chain(hi), hi
    for _ in range(12):
        mid = (lo + hi) / 2
        lo, hi = (mid, hi) if X.crest_db(chain(mid)[:int(10 * B.SR)]) > crest_target else (lo, mid)
    return chain(hi), hi


def floor_db(x):
    fl = X.flux(x, 30, 16000)
    return float(20 * np.log10(np.percentile(fl, 2) / fl.max()))


def _rms(x):
    return float(np.sqrt(np.mean(x ** 2)))


def main(app_dir, out):
    app_dir, out = Path(app_dir), Path(out)
    out.mkdir(parents=True, exist_ok=True)
    recipes = json.load(open(L.RECIPES))
    kits = {k['id']: k for k in recipes['kits']}
    stacks = {s['id']: s for s in B.STACKS}
    slabels = {s['id']: [p['label'] for p in s['pads']] for s in recipes['stacks']}
    targets = json.load(open(Path(__file__).with_name('targets.json')))['loops']
    report = []
    for kit in B.K.KITS:
        r = kits[kit['id']]; st = stacks[r['default_stack']]
        bank = [L.app_pad(app_dir / kit['id'], s) for s in range(1, 13)]
        sbank = [L.app_pad(app_dir / kit['id'], s) for s in range(13, 17)]
        dlabels = [p['label'] for p in r['pads']]
        trim = 10 ** (r['rest_trim_db'] / 20)
        tbank, tsbank = tighten_ride(bank, dlabels), tighten_stack(sbank, slabels[st['id']], trim)
        gv_now, gv_perc = B.groove(kit, bank, st, sbank), B.groove(kit, tbank, st, tsbank)
        drive = r['glue_limiter_drive_db']
        t = targets[kit['primary']]
        dense, sat_db = saturate(gv_perc, t['crest_db'], drive)
        v = dict(now=B.bus_preview(gv_now, drive), perc=B.bus_preview(gv_perc, drive), dense=dense)
        ref = _rms(v['now'][:int(10 * B.SR)])
        row = dict(kit=kit['id'], name=kit['name'], saturation_db=round(sat_db, 2), loop=dict(floor_db=round(t['floor_db'], 1),
                   low_mid_db=t['low_mid_db'], crest_db=t['crest_db']))
        for name, y in v.items():
            y = y * min(ref / _rms(y[:int(10 * B.SR)]), 1.0 / np.max(np.abs(y)) * .99)
            seg = y[:int(10 * B.SR)]
            row[name] = dict(floor_db=round(floor_db(seg), 1), low_mid_db=round(X.low_mid_db(seg), 1),
                             crest_db=round(X.crest_db(seg), 1), peak=round(float(np.max(np.abs(y))), 3))
            B.write(out / f"{kit['id']}-{name}.wav", y)
        report.append(row)
        print(row, flush=True)
    (out / 'fuller.json').write_text(json.dumps(report, indent=1))


if __name__ == '__main__':
    main(*sys.argv[1:3])
