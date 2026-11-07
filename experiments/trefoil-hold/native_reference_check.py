"""Compare Kotlin's modal kernel with the independent Python held model.

Kotlin's versioned source uses Dsp.Noise. Supplying that exact contact force to
Python isolates the port's state arithmetic and geometry from the RNG change.
The original approved PCG64 audio is compared separately, after scalar/delay fit.
"""
import argparse
from dataclasses import replace
import json
from pathlib import Path

import numpy as np
from scipy import signal, optimize
import prototype as t


def align(reference,candidate):
    a=reference-t.pr.dc(reference);b=candidate-t.pr.dc(candidate)
    corr=signal.correlate(b,a,method='fft');lags=signal.correlation_lags(len(b),len(a))
    window=np.abs(lags)<512
    lag=int(lags[window][np.argmax(corr[window])]);b=np.roll(b,-lag)
    spectrum=np.fft.rfft(b);freq=np.fft.rfftfreq(len(b))
    def fit(delay):
        shifted=np.fft.irfft(spectrum*np.exp(2j*np.pi*freq*delay),n=len(b))
        scale=np.dot(a,shifted)/np.dot(shifted,shifted)
        return float(np.sum((a-scale*shifted)**2)/np.sum(a*a))
    match=optimize.minimize_scalar(fit,bounds=(-1,1),method='bounded')
    return {'relative_error_energy':float(match.fun),'correlation':float(np.sqrt(max(0,1-match.fun))),
            'alignment_frames':lag+float(match.x)}


def main(directory):
    native=json.loads((directory/'native.json').read_text());results={}
    for name,case in native['cases'].items():
        patch=case['patch'];m=patch['macros']
        recipe=t.Recipe(midi=patch['midi'],velocity=patch['velocity'],seed=int(patch['seed']),
                        weave=m['WEAVE'],exchange=m['EXCHANGE'],thread=m['THREAD'],memory=m['MEMORY'])
        plan=t.plan(recipe)
        force=np.fromfile(directory/f'{name}-force.f64be',dtype='>f8').astype(float).reshape(-1,3)
        assert force.shape==plan['force'].shape
        original_force=plan['force'];plan['force']=np.ascontiguousarray(force)
        z,mem,closure=t.solve(plan)
        reference=t.run(plan,z,mem)[0]
        actual=np.fromfile(directory/f'{name}-raw.f32be',dtype='>f4').astype(float)
        assert len(actual)==len(reference)
        error=float(np.sum((reference-actual)**2)/np.sum(reference**2))
        maximum=float(np.max(np.abs(reference-actual)))
        assert error<1e-10,(name,error)
        plan['force']=original_force;oz,om,_=t.solve(plan)
        original=t.condition(t.run(plan,oz,om,count=plan['n']*2)[0])[-plan['frames']:]
        output,_=t.sk.read_wav(str(directory/f'{name}-loop.wav'))
        comparison=align(original,output)
        # This bound admits the documented RNG and house conditioning changes;
        # the kernel bound above is much tighter and checks actual port arithmetic.
        assert comparison['correlation']>.98,(name,comparison)
        results[name]={'shared_force_error_energy':error,'shared_force_max_error':maximum,
                       'python_state_error':closure['state_relative_error'],
                       'original_PCG64_comparison':comparison}
        print(json.dumps({'case':name,**results[name]}),flush=True)
    report={'all_pass':True,'cases':results,'claim':'independent Python model versus Kotlin raw modal output; source variation reported separately'}
    (directory/'reference-check.json').write_text(json.dumps(report,indent=2)+'\n')


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('directory',type=Path);a=p.parse_args();main(a.directory)
