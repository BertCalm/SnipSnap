"""Compare Kotlin pre-output floats with the approved Python voice laws.

Pass --reference to scripts/apsis_voice_study.py in the retained voice-study
checkout (fe897be84044ad9182db2f9f6c820374045e7f61). Requires numpy/scipy/numba.
The reference's own Luthier helpers must remain beside its repository checkout.
Kotlin uses Float macros/root/noise and Cayley junctions; this is a numerical
fidelity gate, not a byte-identity claim for the Python house output stage.
"""
import argparse
import importlib.util
import json
import sys
from pathlib import Path

import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--renders', type=Path, default=Path('testkit/apsis-populations'))
    args = parser.parse_args()
    sys.path.insert(0, str(args.reference.resolve().parent))
    spec = importlib.util.spec_from_file_location(args.reference.stem, args.reference.resolve())
    ref = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = ref
    spec.loader.exec_module(ref)
    manifest = json.loads((args.renders / 'manifest.json').read_text())
    comparisons = []
    for clip in manifest['clips']:
        if clip['kind'] == 'phrase':
            continue
        patch = json.loads((args.renders / clip['recipe']).read_text())
        m = patch['macros']
        held = clip['kind'] == 'held'
        actual = np.fromfile(args.renders / 'internal' / (clip['id'] + '.f32'), dtype='<f4')
        rate = manifest['internalRate']
        seconds = len(actual) / rate
        fields, residual = ref.motion(m['STRETCH'], m['DISTANCE'], m['ALIGN'], seconds, rate, held)
        idx, co, si, rho, radiation = ref.profile(clip['voice'], clip['effectiveHz'], rate)
        state = np.zeros(72)
        state[54:60] = 100.
        seed = ref.seed_for('APSIS_VOICE_STUDY', clip['midi'], patch['config']['seedContext'])
        for _ in range(12 if held else 1):
            before = state.copy()
            expected, _ = ref.run_cycle(fields, len(actual), rate, idx, clip['effectiveHz'], co, si, rho,
                                       radiation, m['BODIES'], m['COUPLE'], clip['velocity'],
                                       -1. if held else 3.6 + 1.8 * m['HOLD'], seed, state)
            if held and np.max(np.abs(state - before)) < 1e-10:
                break
        delta = actual.astype(np.float64) - expected
        relative = float(np.linalg.norm(delta) / max(1e-30, np.linalg.norm(expected)))
        correlation = float(np.corrcoef(actual, expected)[0, 1])
        comparisons.append(dict(id=clip['id'], relativeRmsError=relative,
                                correlation=correlation, maxAbsoluteError=float(np.max(np.abs(delta))),
                                solverResidual=residual))
        print(clip['id'], 'relative RMS error', relative, 'correlation', correlation)
        assert relative < 1e-4, (clip['id'], relative)
        assert correlation > .999999, (clip['id'], correlation)
    report = dict(referenceRevision='fe897be84044ad9182db2f9f6c820374045e7f61',
                  comparison='4x internal radiation, Float recipe values and native effective root',
                  outputStage='Kotlin house output differs from SciPy; neither is claimed byte identical',
                  comparisons=comparisons)
    (args.renders / 'prototype-comparison.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
