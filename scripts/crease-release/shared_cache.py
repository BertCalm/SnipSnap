#!/usr/bin/env python3
"""Offline shared-basis experiment. The synthesis source stays actual Kotlin.

Full tail reconstruction in this analysis is an oracle for the basis reader,
not the runtime implementation or its memory accounting.
"""
from __future__ import annotations
import os
os.environ['OPENBLAS_NUM_THREADS'] = '2'
os.environ['OMP_NUM_THREADS'] = '2'
import argparse
import hashlib
import json
from pathlib import Path
import random
import numpy as np
from scipy.linalg import eigh
import playable as p

RANKS = (4, 8, 12, 16, 24, 32, 48, 64)
PAD = 89
LIMITS = dict(normalizedErrorRms=.031623, peakError=.025, harmonicTv=.02,
              keyUpStepError=.001, residentBytes=8*1024**2, compressedBytes=12*1024**2)


def require(ok, message):
    if not ok:
        raise ValueError(message)


def fresh_positions(m, old):
    used = set(old) | set(m['tailCuts'])
    early = {int((j+.19)*.0005*p.RATE+.5) for j in range(1024)} - used
    used |= early
    rng = random.Random(0x43414348)
    groups = {'early': sorted(early)}
    for group, low, high in [('prefix', 1, m['loopStart']-1),
                             ('steady', m['loopStart'], m['heldFrames']-1)]:
        values = set()
        while len(values) < 300:
            cut = rng.randint(low, high)
            if cut not in used:
                values.add(cut); used.add(cut)
        groups[group] = sorted(values)
    cuts = sorted(x for values in groups.values() for x in values)
    return dict(seed='0x43414348', groups=groups, cuts=cuts,
                cutsSha256=p.sha(np.asarray(cuts, dtype='<i4').tobytes()))


def fit(m, pcm):
    n, length = len(m['tailCuts']), m['tailFrames']
    x = pcm[m['heldFrames']:].reshape(n, length).astype(np.float64)/32767
    period = m['loopFrames']/m['rootCycles']
    offsets = np.rint((np.asarray(m['tailCuts'])+period/2)%period-period/2).astype('<i4')
    index = np.clip(np.arange(length+2*PAD)[None, :]-PAD-offsets[:, None], 0, length-1)
    aligned = np.take_along_axis(x, index, axis=1)
    mean = aligned.mean(axis=0)
    centered = aligned-mean
    eigenvalues, u = eigh(centered@centered.T)
    order = np.argsort(eigenvalues)[::-1][:max(RANKS)-1]
    values, u = eigenvalues[order], u[:, order]
    require(np.all(values > 0), 'basis spectrum is degenerate')
    components = (u.T@centered)/np.sqrt(values[:, None])
    basis = np.vstack([mean, components])
    coefficients = np.column_stack([np.ones(n), u*np.sqrt(values)])
    scale = np.max(np.abs(basis), axis=1)
    quantized = np.rint(basis/scale[:, None]*32767).astype('<i2')
    coefficients = (coefficients*scale).astype('<f4')
    return quantized, coefficients, offsets


def reconstruct(m, basis, coefficients, offsets):
    aligned = coefficients.astype(np.float64)@(basis.astype(np.float64)/32767)
    index = np.arange(m['tailFrames'])[None, :]+PAD+offsets[:, None]
    tails = np.take_along_axis(aligned, index, axis=1)
    return dict(zip(m['tailCuts'], tails))


# Reuse the exact harmonic definition, caching its constant matrices. The old
# harness builds these same matrices again for every window of every case.
_PROFILE_MATRICES = {}
def profile(x, hz):
    from scipy.signal.windows import hann
    x = np.asarray(x, dtype=np.float64)
    key = (len(x), hz)
    if key not in _PROFILE_MATRICES:
        harmonics = np.arange(1, min(96, int(19500/hz))+1)
        _PROFILE_MATRICES[key] = np.exp((-2j*np.pi*hz/p.RATE)*harmonics[:, None]*np.arange(len(x))[None, :])*hann(len(x), sym=False)
    power = np.abs(_PROFILE_MATRICES[key]@(x-x.mean()))**2
    return power/max(float(power.sum()), 1e-30)
p.p.profile = profile


def measure(m, held, banks, cuts, raw):
    fade = p.fade_curve()
    rows = []
    for cut in cuts:
        reference = np.fromfile(raw/f'exact-{cut:07d}.f32', dtype='<f4')[:p.FADE_FRAMES].astype(np.float64)*m['commonGain']*fade
        actual = p.approximation(cut, m['tailCuts'], banks, held, m)*fade
        require(len(reference) == len(actual), f'missing exact reference at {cut}')
        error = actual-reference
        rms = p.p.rms(error)/max(p.p.rms(reference), .005)
        peak = float(np.max(np.abs(error)))
        step = float(abs(error[0]))
        finite = bool(np.isfinite(actual).all() and np.max(np.abs(actual)) < 1)
        # A failed mandatory gate already rejects this candidate. Avoid costly
        # spectral measurements on those rows and mark them explicitly absent.
        tv = None
        if rms <= LIMITS['normalizedErrorRms'] and peak <= LIMITS['peakError'] and step <= LIMITS['keyUpStepError'] and finite:
            tv = [p.p.variation(actual[round(a*p.RATE):round(b*p.RATE)], reference[round(a*p.RATE):round(b*p.RATE)], m['hz']) for a,b in [(.04,.25),(.25,.5)]]
        passed = tv is not None and max(tv) <= LIMITS['harmonicTv']
        rows.append(dict(cutFrame=cut, normalizedErrorRms=rms, peakError=peak,
                         keyUpStepError=step, harmonicTv=tv, finiteUnclipped=finite, passed=passed))
    return dict(total=len(rows), passed=sum(r['passed'] for r in rows),
                failed=sum(not r['passed'] for r in rows),
                maxNormalizedErrorRms=max(r['normalizedErrorRms'] for r in rows),
                maxPeakError=max(r['peakError'] for r in rows),
                maxHarmonicTv=max((max(r['harmonicTv']) for r in rows if r['harmonicTv'] is not None), default=None), cases=rows)


def development(source, out):
    require(not out.exists(), 'use a new output directory')
    out.mkdir(parents=True)
    m = json.loads((source/'manifest.json').read_text())
    meta = json.loads((source/'raw/capture.json').read_text())
    data = (source/'raw/native-pcm.i16').read_bytes()
    require(p.sha(data) == m['container']['pcmSha256'], 'baseline PCM changed')
    pcm = np.frombuffer(data, dtype='<i2')
    held = pcm[:m['heldFrames']].astype(np.float64)/32767
    fresh = fresh_positions(m, meta['testCuts'])
    p.write(out/'fresh-positions.json', fresh)
    basis, coefficients, offsets = fit(m, pcm)
    entries = []
    chosen = None
    for rank in RANKS:
        banks = reconstruct(m, basis[:rank], coefficients[:, :rank], offsets)
        result = measure(m, held, banks, meta['testCuts'], source/'raw')
        resident = held.size*2+basis[:rank].nbytes+coefficients[:, :rank].nbytes+offsets.nbytes+4*len(m['tailCuts'])+8*2*rank*8
        result.update(components=rank, residentBytes=resident, memoryPassed=resident<=LIMITS['residentBytes'])
        p.write(out/f'development-{rank}.json', result)
        entries.append({k:v for k,v in result.items() if k != 'cases'})
        print(json.dumps(entries[-1]), flush=True)
        if result['failed'] == 0 and result['memoryPassed']:
            chosen = rank
            break
    receipt = dict(baselinePcmSha256=p.sha(data), baselineManifestSha256=p.sha((source/'manifest.json').read_bytes()),
                   thresholds=LIMITS, candidates=entries, selectedComponents=chosen,
                   freshPositionsSha256=fresh['cutsSha256'], freshCount=len(fresh['cuts']),
                   protocolSha256=p.sha(Path(__file__).with_name('shared-protocol.md').read_bytes()))
    if chosen is not None:
        selected_basis = basis[:chosen].copy()
        selected_coefficients = coefficients[:, :chosen].copy()
        np.savez(out/'locked-basis.npz', basis=selected_basis, coefficients=selected_coefficients, offsets=offsets)
        receipt.update(basisSha256=p.sha(selected_basis.tobytes()), coefficientsSha256=p.sha(selected_coefficients.tobytes()), offsetsSha256=p.sha(offsets.tobytes()))
    p.write(out/'development.json', receipt)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, default=p.ROOT/'testkit/crease-phone-release-r2')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    development(args.source, args.output)

if __name__ == '__main__':
    main()
