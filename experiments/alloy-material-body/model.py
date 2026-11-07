"""Proposed pre-render MATERIAL mapping over complete, existing Alloy configurations.

This is an audition authoring function, not a change to the installed engine's
MATERIAL macro. Every emitted snapshot is independently replayable by model 1.
"""
import copy
import json
from pathlib import Path

PACKAGE = Path(__file__).resolve().parent

DESIGNS = {
    'rounded_plug': {
        'name': 'Rounded Plug', 'voice': 'BASS',
        'description': 'A woody harmonic bass body below the original nearly pure rounded tone.',
        'operators': [
            dict(gain=.20, decay=.35, sustain=.85),
            dict(gain=.10, decay=.20, sustain=.80),
            dict(gain=.95, decay=.18, sustain=1.),
            dict(gain=.70, decay=.10, sustain=.95),
        ],
        'routing': {(1, 0): .18, (3, 2): .28},
        'wood': dict(losses=[.04, .012, .008], gains=[.10, 1., .60]),
        'cutoffRatio': 16.,
    },
    'long_prism': {
        'name': 'Long Prism', 'voice': 'KEY',
        'description': 'A more fundamental-led wooden key with a sustained hollow third, below the original glassy tine.',
        'operators': [
            dict(gain=.65, decay=.45, sustain=.85),
            dict(gain=.75, decay=.30, sustain=.90),
            dict(gain=.25, decay=.15, sustain=.55),
            dict(gain=.10, decay=.08, sustain=.10),
        ],
        'routing': {(1, 0): .04, (2, 1): .03},
        'wood': dict(losses=[.18, .045, .012, .008], gains=[.70, .45, .20, .10]),
        'cutoffRatio': 10.,
    },
    'velvet_rise': {
        'name': 'Velvet Rise', 'voice': 'SOFT',
        'description': 'A hollow, reedier sustained body below the original warm soft tone, with its slow swell retained.',
        'operators': [
            dict(gain=.20, decay=1., sustain=1.),
            dict(gain=.05, decay=.30, sustain=.15),
            dict(gain=.95, decay=1.20, sustain=1.),
            dict(gain=.55, decay=.80, sustain=.95),
        ],
        'routing': {(1, 0): .90, (2, 1): .20, (3, 0): .25},
        'wood': dict(losses=[.085, .025, .008], gains=[.15, .60, .85]),
        'cutoffRatio': 18.,
    },
}


def original(slug):
    return json.loads((PACKAGE / 'baselines' / (slug + '.json')).read_text())


def endpoint(slug):
    out = original(slug)['config']
    d = DESIGNS[slug]
    for op, changes in zip(out['operators'], d['operators']):
        op.update(changes)
    out['routing'] = [dict(from_=a, to=b, radians=v) for (a, b), v in d['routing'].items()]
    for route in out['routing']:
        route['from'] = route.pop('from_')
    out['wood'].update(copy.deepcopy(d['wood']))
    out['cutoffRatio'] = d['cutoffRatio']
    return out


def patch(slug, material, held=False, midi=48, velocity=1., reference=False):
    out = original(slug)
    out['macros']['HOLD'] = 1 if held else 0
    out['midi'] = midi
    out['velocity'] = velocity
    if reference:
        return out
    out['macros']['MATERIAL'] = material
    if material >= .5:
        # This exact branch preserves every original field, including route list
        # order and absent routes. No rounded interpolation result reaches it.
        return out
    old = out['config']
    new = endpoint(slug)
    # The bass's gain times sustain would otherwise crowd most colour change
    # near zero. A square law distributes its measured spectral movement more
    # usefully over the two lower-half audition steps. Other roles use a line.
    t = (material * 2.) ** (2. if slug == 'rounded_plug' else 1.)

    def blend(a, b):
        return a + (b - a) * t

    cfg = copy.deepcopy(old)
    for op, left, right in zip(cfg['operators'], new['operators'], old['operators']):
        # Ratios never move, so intermediate settings cannot introduce detuned
        # carriers or a new subharmonic period.
        assert left['ratio'] == right['ratio']
        for key in ['gain', 'decay', 'sustain']:
            op[key] = blend(left[key], right[key])
    before = {(x['from'], x['to']): x['radians'] for x in new['routing']}
    after = {(x['from'], x['to']): x['radians'] for x in old['routing']}
    cfg['routing'] = [dict(from_=a, to=b, radians=blend(before.get((a, b), 0.), after.get((a, b), 0.)))
                      for a, b in sorted(set(before) | set(after))]
    for route in cfg['routing']:
        route['from'] = route.pop('from_')
    for key in ['losses', 'gains']:
        cfg['wood'][key] = [blend(a, b) for a, b in zip(new['wood'][key], old['wood'][key])]
    assert new['wood']['ratios'] == old['wood']['ratios']
    cfg['cutoffRatio'] = blend(new['cutoffRatio'], old['cutoffRatio'])
    out['config'] = cfg
    return out


def serial_designs():
    return {slug: {**{k: v for k, v in d.items() if k != 'routing'},
                   'originalWeightPower': 2 if slug == 'rounded_plug' else 1,
                   'routing': [dict(from_=a, to=b, radians=v) for (a, b), v in d['routing'].items()]}
            for slug, d in DESIGNS.items()}
