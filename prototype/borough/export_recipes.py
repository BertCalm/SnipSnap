"""Export BOROUGH's accepted kits as recipes for the Kotlin engine (round 3: generated in the app).
Python harness. Numbers only, no audio.

    BOROUGH_MODEL=acoustic python3 export_recipes.py BUILD_OUT/manifest.json OUT/recipes.json

The owner approved round 2 ("I think they are all ready", 2026-11-20) and chose to generate the kits in
the app rather than ship samples. This writes what the Kotlin side needs to render the same recipes:
per kit, the twelve drum pads (designed drums or EMBER voices with their record chain), the balance trims
and the glue/limiter drive the owner heard; per stack, the four EMBER voices with their resolved TUNE and
resample ratio (acoustic.tuned's search, done once here so the app does not repeat it); and the shared
rules (zones, takes, level law, take jitter, trims, end).
"""
from __future__ import annotations
import json, sys
import acoustic as A

RULES = dict(
    zones=[0.35, 0.65, 1.0], takes=3, level_law='0.89 * v^0.8',
    take_jitter=dict(hz_sd=0.004, ms_and_t1_sd=0.04, click_wire_sd=0.10, click_wire_clip=[0.7, 1.3],
                     note='take 0 is the recipe itself; takes 1-2 jitter it (build.jitter)'),
    end='trim at -60 dB of the 10 ms envelope peak; a 250 ms cosine release when the last 10 ms is above 1e-3 of the peak, else 10 ms; peak 1 (acoustic.end)',
    record_chain='compress(comp: threshold 24*comp dB under peak, ratio 4, attack 2 ms, release 90 ms) -> tanh drive (d = drive*(0.35+0.65 v), g = 1+10d) -> tilt (1 kHz high-pass blend, dB) -> 2-pole low-pass (acoustic.record)',
    room='EMBER room by distance (to44 / ember_voice DISTANCE)',
    ghost='designed snare at 0.4 x velocity, 0.35 s',
    stack_chain=A.STACK_CHAIN,
    # Round 3.1 (owner: "C for all except rides and rims", 2026-11-21; fuller.py): a stack sits at its kit's own
    # level and stops sooner, so it no longer fills the space between hits or masks the kick.
    stack_trim='the kit\'s rest_trim_db, as on its snare and hats',
    stack_tail=dict(hold_ms=30.0, tail_ms=250.0, long_tail_ms=600.0, long_labels=['BELL HI', 'BELL LO', 'RIDE', 'RIDE BELL'],
                    note='after hold_ms an exponential decay reaching -30 dB at the tail (fuller.shorten); cut where it '
                         'reaches -60 dB with a 10 ms cosine fade'),
    glue=dict(threshold_dbfs=-10.0, ratio=4.0, attack_ms=10.0, release_ms=120.0,
              limiter=dict(lookahead_ms=3.0, release_ms=50.0, ceiling_dbfs=-1.0)),
)


def main(manifest_path, out_path):
    man = json.load(open(manifest_path))
    kits = []
    for k in man['kits']:
        kits.append(dict(id=k['id'], name=k['name'], bpm=k['bpm'], role=k['role'], ride=bool(k.get('ride')),
                         default_stack={'borough-stoop': 'tamb', 'borough-cellar': 'hands', 'borough-cranked': 'tops',
                                        'borough-wax': 'bells', 'borough-dry': 'hands', 'borough-rims': 'bells'}[k['id']],
                         kick_trim_db=k['kick_trim_db'], rest_trim_db=k['rest_trim_db'],
                         glue_limiter_drive_db=k['bus_preview']['limiter_drive_db'],
                         pads=[dict(pad=p['pad'], label=p['label'], kind=p['model'], params=p['params'], extra=p['extra'])
                               for p in k['pads']]))
    stacks = []
    for s in man['stacks']:
        pads = []
        for p in s['pads']:
            prm = p['params']
            tune, ratio = A.tuned(prm['voice'], tuple(sorted(prm['macros'].items())), prm['target'])
            pads.append(dict(pad=p['pad'], label=p['label'], voice=prm['voice'], macros=prm['macros'], target_hz=prm['target'],
                             tune=round(tune, 4), resample_ratio=round(ratio, 5),
                             resample=bool(abs(__import__('math').log2(ratio)) > 1 / 24)))
        stacks.append(dict(id=s['id'], name=s['name'], pads=pads))
    out = dict(version=1, note='BOROUGH recipes exported from the Python harness (prototype/borough). Numbers only.',
               rules=RULES, kits=kits, stacks=stacks)
    json.dump(out, open(out_path, 'w'), indent=1)
    print('kits', len(kits), 'stacks', len(stacks), '->', out_path)


if __name__ == '__main__':
    main(*sys.argv[1:3])
