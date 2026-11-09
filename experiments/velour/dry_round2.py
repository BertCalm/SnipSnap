"""Dry-source audition candidates; synthetic Python models, no reference samples.
All constants are voicing hypotheses. No processing from the atmosphere/amp chain.
"""
from pathlib import Path
import json, sys, time, shutil, zipfile
import numpy as np
from scipy import signal
import velour as v
import snipkit as sk
import probes as pr

R=sk.INTERNAL
ROOT=Path(__file__).resolve().parents[1]
DEST=Path(sys.argv[1]) if len(sys.argv)>1 else ROOT/'dry-round2'
SETTINGS={
 'A':dict(kick=49,kt=.33,contact=.18,snare=145,st=.19,wire=.12,bright=4200),
 'B':dict(kick=56,kt=.22,contact=.23,snare=170,st=.13,wire=.085,bright=4700),
 'C':dict(kick=62,kt=.29,contact=.33,snare=195,st=.17,wire=.11,bright=5900),
}

def render(voice, choice, velocity=1., take=0):
    if choice=='original': return v.render(voice,velocity=velocity)
    p=SETTINGS[choice];t=np.arange(int(.85*R))/R
    rng=np.random.default_rng(sk.seed_for('velour-dry-r2',voice,take))
    noise=rng.uniform(-1,1,len(t))
    # Finite soft contact excites damped modes. No pitch sweep or arbitrary modal peak normalization.
    width=(.003 if voice=='kick' else .0013)*(1.35-.35*velocity)
    contact=np.zeros(len(t)); n=max(4,round(width*R))
    contact[:n]=np.hanning(n); contact/=contact.sum()
    if voice=='kick':
        modes=[(1,1,p['kt']),(1.54,.42,p['kt']*.62),(2.06,.22,p['kt']*.34),
               (2.8,.1,p['kt']*.22),(4.1,.04,.045)]
        body=sk.modal_ring(contact,p['kick'],modes,R,compensate=True)
        # Beater/felt noise is spread over a few milliseconds, low-mid rather than a click.
        touch=v.lp(noise,1500,R)*(1-np.exp(-t/.0007))*np.exp(-t/.0035)
        x=body+p['contact']*touch
    else:
        modes=[(1,.9,p['st']),(1.47,.36,p['st']*.8),(1.91,.24,p['st']*.54),
               (2.37,.16,p['st']*.37),(3.12,.09,p['st']*.24),(4.03,.045,.035)]
        body=sk.modal_ring(contact,p['snare'],modes,R,compensate=True)
        # Two wire bands avoid a flat white-noise layer; micro-envelope is driven by head motion.
        wires=v.hp(v.lp(noise,p['bright']*(.75+.25*velocity),R),650,R)
        papery=v.hp(v.lp(noise,1900,R),450,R)
        env=(1-np.exp(-t/.0016))*np.exp(-6.9078*t/(p['wire']*(.7+.3*velocity)))
        modulation=.7+.3*np.abs(np.sin(2*np.pi*p['snare']*t))
        wire=(1.9*wires+.7*papery)*env*modulation
        stick=.15*v.lp(noise,2600,R)*(1-np.exp(-t/.0003))*np.exp(-t/.0018)
        x=.95*body+wire+stick
    x*=velocity
    return v.clean(sk.decimate4(sk.band_limit(x,R)))

def strokes(voice,choice):
    out=np.zeros(4*sk.RATE)
    for sec,vel,take in [(.1,1,0),(1.1,1,1),(2.1,.42,2),(2.85,.85,3)]:
        y=render(voice,choice,vel,take); start=round(sec*sk.RATE)
        out[start:start+len(y)]+=y
    return out

def groove(choice):
    original=v.render
    # Reuse the existing event list, hat scaffold and timing exactly; only source pair changes.
    def selected(voice,macros=None,velocity=1.):
        if voice=='hat' or choice=='original': return original(voice,macros,velocity)
        return render(voice,choice,velocity)
    v.render=selected
    try: dry,_,_=v.groove(v.DEFAULTS)
    finally: v.render=original
    return v.clean(dry[:round((8*60/84+1)*sk.RATE)])

def main():
    start=time.perf_counter(); dest=DEST/'audio'; dest.mkdir(exist_ok=True)
    choices=['original','A','B','C']; rows=[]
    for category in ['kick','snare','groove']:
        arrays=[groove(c) if category=='groove' else strokes(category,c) for c in choices]
        arrays,target=v.match_group(arrays)
        for c,x in zip(choices,arrays):
            name=f'r2_{category}_{c}'
            assert np.isfinite(x).all() and pr.peak(x)<=.921 and abs(pr.dc(x))<.005
            sk.write_wav(str(dest/(name+'.wav')),x)
            rows.append(dict(file=name+'.wav',peak=pr.peak(x),dc=pr.dc(x),rms=sk.rms(x)))
    checks={}
    for voice in ['kick','snare']:
        for c in ['A','B','C']:
            x=render(voice,c)
            assert np.array_equal(x,render(voice,c))
            assert not np.array_equal(x,render(voice,c,take=1))
            assert pr.rms(render(voice,c,.42))<pr.rms(x)
            checks[voice+c]=dict(deterministic=True,take_changes=True,soft_hit_lower=True,
                centroid_hz=pr.spectral_centroid(x),decay_s=pr.decay_seconds(x))
    report=dict(status='Python prototype; owner listening pending',settings=SETTINGS,
        checks=checks,renders=rows,runtime_seconds=time.perf_counter()-start,
        matching='Equal whole-file RMS within kick, snare and groove groups; common peak-safe target.',
        reference='Mixed recording peaks 48.4,75.4,86.1 Hz; not isolated drum roots. No reference audio published.')
    (DEST/'dry-round2-report.json').write_text(json.dumps(report,indent=2))
    shutil.copy2(__file__,DEST/'dry_round2.py')
    print(json.dumps({'checks':checks,'renders':len(rows),'seconds':report['runtime_seconds']},indent=2))

if __name__=='__main__':main()
