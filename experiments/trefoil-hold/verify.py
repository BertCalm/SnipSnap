"""Numerical gates for the separate Python held proof. Run after prototype.py."""
import hashlib
import json
from pathlib import Path
import sys
import time
from dataclasses import replace

import numpy as np
import scipy
import numba
from scipy import signal

import prototype as t


def digest(x): return hashlib.sha256(np.asarray(x).tobytes()).hexdigest()


def main(folder):
    cases={name:json.loads((folder/f'{name}.json').read_text())
           for name in ('ordered','still','no-exchange','memory','bass','bright')}
    checks={}
    def check(name,condition):
        checks[name]=bool(condition)
        if not condition: raise AssertionError(name)

    for name,r in cases.items():
        check(name+':state closes',r['state_relative_error']<1e-10)
        check(name+':true preceding seam',r['seam']<1e-10)
        check(name+':independent continuation',r['independent_cycle_max_error']<1e-10)
        check(name+':pitch',abs(r['detected_cents'])<5 and r['pitch_confidence']>.8)
        check(name+':source tuning',abs(r['source_cents'])<.2)
        check(name+':unforced core contracts',r['unforced_core_energy_rise']<1e-20)
        check(name+':release settles',r['release_last_second_rms']<1e-5)
        for kind in ('loop','returns','release'):
            x,rate=t.sk.read_wav(str(folder/f'{name}-{kind}.wav'))
            check(name+':'+kind+':PCM finite/headroom',rate==44100 and t.pr.finite(x) and t.pr.peak(x)<.99)

    p=t.plan(t.Recipe()); z,mem,closure=t.solve(p)
    one=t.run(p,z,mem,count=8192)[0]; two=t.run(p,z,mem,count=8192)[0]
    check('same-input internal bytes',digest(one)==digest(two))
    p_again=t.plan(t.Recipe())
    check('seeded source recreates exactly',digest(p['force'])==digest(p_again['force']))
    r=t.replace(t.Recipe(),seed=t.Recipe().seed+1)
    check('different seed changes source',digest(p['force'])!=digest(t.plan(r)['force']))
    for r in (replace(t.Recipe(),nudge=.01),replace(t.Recipe(),weave=.1),replace(t.Recipe(),midi=20)):
        rejected=False
        try: t.plan(r)
        except ValueError: rejected=True
        check('explicit refusal '+str(r),rejected)
    capped=False
    try: t.run(p,count=p['ir']*61)
    except ValueError: capped=True
    check('bounded work refusal',capped)

    # Validate the diagnostic with passive and deliberately growing states.
    silent=dict(p,force=np.zeros_like(p['force']))
    rng=np.random.default_rng(43)
    initial=(rng.standard_normal((8,3))+1j*rng.standard_normal((8,3)))*.01
    passive=t.run(silent,z=initial,count=8192,off=0)
    check('passive norm diagnostic',passive[3]<1e-20)
    broken=dict(silent,A=silent['A']*1.0001)
    growing=t.run(broken,z=initial,count=8192,off=0)
    check('diagnostic detects injected growth',growing[3]>1e-8)

    # A body receives energy only when exchange is enabled. Output isn't a pan law.
    single=dict(p,force=p['force'].copy())
    single['force'][:,1:]=0
    transfer=t.run(single,count=round(.4*p['ir']))[1]
    disconnected=t.plan(replace(t.Recipe(),exchange=0,excited_body=0))
    isolated=t.run(disconnected,count=round(.4*p['ir']))[1]
    receiver=float(np.sum(np.abs(transfer[:,1:])**2)/2)
    check('receiver gains modal state',receiver>1e-12)
    check('exchange-off receiver stays exactly silent',np.count_nonzero(isolated[:,1:])==0)

    off=round(.73*p['ir']); ramp=round(.025*p['ir'])
    released=t.run(p,count=off+8192,off=off,onset=4096,release=ramp)[0]
    held=t.run(p,count=off+8192,onset=4096)[0]
    check('note-off preserves exact causal prefix',np.array_equal(released[:off],held[:off]))
    check('note-off changes subsequent state',np.max(np.abs(released[off:]-held[off:]))>1e-6)
    zero=t.plan(replace(t.Recipe(),velocity=0))
    check('zero velocity has exact silence',np.count_nonzero(t.run(zero,count=8192)[0])==0)

    # A noiseless, high-register 8x reference isolates the deterministic source.
    # Difference also contains step-size and filter differences, so is NOT an alias bound.
    references=[]; raw_alias=[]
    for os in (4,8):
        high=t.plan(replace(t.Recipe(),midi=84,thread=1,memory=0),oversample=os,noise_enabled=False)
        hz,hm,_=t.solve(high)
        raw=t.run(high,hz,hm,count=high['n']*3)[0]
        raw_alias.append(t.pr.alias_proxy(raw,high['ir']))
        references.append(t.condition(raw,os)[-high['frames']:])
    a,b=references
    corr=signal.correlate(b,a,mode='full',method='fft'); lags=signal.correlation_lags(len(b),len(a))
    window=np.abs(lags)<100
    lag=int(lags[window][np.argmax(corr[window])]); b=np.roll(b,-lag)
    # Refine the causal filter-delay alignment below one output frame.
    def error(delay):
        shifted=np.fft.irfft(np.fft.rfft(b)*np.exp(2j*np.pi*np.fft.rfftfreq(len(b))*delay),n=len(b))
        scale=np.dot(a,shifted)/np.dot(shifted,shifted)
        return float(np.sum((a-scale*shifted)**2)/np.sum(a*a))
    from scipy.optimize import minimize_scalar
    fit=minimize_scalar(error,bounds=(-1,1),method='bounded')
    check('high-rate comparison is bounded',fit.fun<.01)

    # Restore the aggregate report even after a targeted render invocation.
    manifest={'status':'experimental Python harness, not Kotlin; listening pending',
              'model':t.MODEL,'cases':cases,'checks':checks,'check_count':len(checks),
              'all_checks_pass':all(checks.values()),
              'numerical':{'receiver_energy':receiver,'passive_energy_rise':passive[3],
                           'growth_mutation_energy_rise':growing[3],
                           'high_rate_noiseless_midi':84,'higher_rate_relative_difference':float(fit.fun),
                           'higher_rate_alignment_frames':lag+float(fit.x),
                           'raw_above_output_nyquist_ratios':raw_alias,
                           'comparison_caveat':'4x versus 8x includes filter/step differences; not an isolated alias bound'},
              'versions':{'numpy':np.__version__,'scipy':scipy.__version__,'numba':numba.__version__},
              'hardware':{'status':'finite hardware audition accepted by owner','date':'2026-11-01',
                          'instrumented_phone_timing':False}}
    manifest['wav_sha256']={f.name:hashlib.sha256(f.read_bytes()).hexdigest() for f in sorted(folder.glob('*.wav'))}
    (folder/'report.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(json.dumps({'checks':len(checks),'all_checks_pass':True,'numerical':manifest['numerical']},indent=2))


if __name__=='__main__': main(Path(sys.argv[1]))
