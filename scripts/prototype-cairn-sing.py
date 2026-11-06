#!/usr/bin/env python3
"""SING proof, adapted from Luthier's proto_template.py; Python, not Kotlin.

All constants are normalized design shapes. The logistic law approximates
smooth rubbing's regenerative feedback; it does not resolve stick/slip.
"""
import itertools, json, math, sys
from pathlib import Path
import numpy as np
from scipy.signal import lfilter

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'.claude/skills/luthier/scripts'))
import snipkit as sk
import probes as pr

DEFAULT = dict(WEIGHT=.55, SLOPE=.45, GROOVE=.25, FRICTION=.60, BODY=.65, HOLD=0.)
CTRL = 2000

def advance(e, a, beta, t):
    if abs(a)<1e-10:
        return e/(1+4*beta*e*t)
    v=math.expm1(2*a*t)
    return e*(1+v)/(1+(2*beta/a)*e*v)

def plan(m, held=False, velocity=1.):
    w,s,g,f,b=(m[k] for k in ['WEIGHT','SLOPE','GROOVE','FRICTION','BODY'])
    mass=.55+1.3*w
    gamma=3.5+5*f+2.5*(1-b)
    traction=gamma+9+18*s/(.8+.4*mass)
    cap=(.0025+.004*w)*(.65+.6*f)*velocity**2/(1+.4*g)
    beta=(traction-gamma)/(2*max(cap,1e-30))
    dwell=1.25+.85*m['HOLD']+.2*g
    lift=.12+.16*w
    seconds=2. if held else dwell+lift+.5+1.1*b
    n=round(seconds*CTRL)
    def smooth(x):
        x=max(0.,min(1.,x)); return x*x*(3-2*x)
    def pressure(t):
        if held:
            return 1-.08*g+.08*g*math.cos(math.pi*t)
        return smooth((t-.08)/(.07+.16*w))*(1-smooth((t-dwell)/lift))
    e=.0001*velocity**2; previous=None
    for cycle in range(8 if held else 1):
        initial=e; supplied=loss=0.; energies=[e]; pressures=[]
        for i in range(n):
            p=pressure((i+.5)/CTRL); a=traction*p-gamma
            for x,q in zip([.1127016653792583,.5,.8872983346207417],[5/18,8/18,5/18]):
                ex=advance(e,a,beta,x/CTRL)
                supplied+=q/CTRL*2*traction*p*ex
                loss+=q/CTRL*(2*gamma*ex+4*beta*ex*ex)
            e=advance(e,a,beta,1/CTRL)
            energies.append(e);pressures.append(p)
        assert abs(initial+supplied-e-loss)<1e-9
        if not held or cycle>=2 and abs(e-initial)<1e-12:
            return dict(energy=np.array(energies),pressure=np.array(pressures+[pressures[0] if held else 0.]),
                        previous=previous,seconds=n/CTRL,initial=initial,final=e,supplied=supplied,loss=loss,
                        residual=initial+supplied-e-loss,cap=cap,cycles=cycle+1,contactEnd=dwell+lift)
        previous=np.array(energies)
    raise RuntimeError('SING pressure/energy cycle did not settle')

def render(m, midi, held=False):
    p=plan(m,held);rate=sk.INTERNAL
    hz=440*2**((midi-69)/12)
    actual=round(hz*2)/2 if held else hz
    # A separately warmed linear bank supplies real preceding audio. No tiled output.
    seconds=8. if held else p['seconds']
    t=np.arange(round(seconds*rate))/rate
    local=t%2 if held else t
    energy=np.interp(local,np.arange(len(p['energy']))/CTRL,p['energy'])
    pressure=np.interp(local,np.arange(len(p['pressure']))/CTRL,p['pressure'])
    phase=2*np.pi*actual*t
    force=.6*np.sqrt(energy)*pressure*(np.sin(phase)+(.08+.3*m['GROOVE'])*np.sin(2*phase)+(.03+.14*m['FRICTION'])*np.sin(3*phase))
    if not held:
        width=(.0007+.0012*m['WEIGHT'])*.7
        start=round(.018*rate);count=max(8,round(width*rate))
        kernel=.5-.5*np.cos(2*np.pi*(np.arange(count)+.5)/count)
        force[start:start+count]+=.12*math.sqrt(.0024)*kernel/(kernel.sum()/rate)
    raw=np.zeros(len(force));ring=.45+1.1*m['BODY']
    for ratio,contact,pickup,decay in zip([1,2,3,2.71,5.8,1.47],[1,.65,.45,.22,.08,.15+.2*m['BODY']],
                                       [1,.75,.5,.18,.06,.25],[1,.8,.65,.45,.25,.6]):
        pole=np.exp((-math.log(1000)/(ring*decay)+2j*np.pi*hz*ratio)/rate)
        state=lfilter([-1j*contact/rate*pole],[1,-pole],force)
        raw+=-state.imag*pickup
    if held:
        out=sk.level_to(sk.dc_block(sk.decimate4(sk.band_limit(raw,rate))))
        # Remove the finite right-edge margin before comparing genuine cycles.
        full=out[round(4*sk.RATE):round(8*sk.RATE)-1024]
        marker=round(2*sk.RATE)-1024
        seam=sk.seam_error(full,marker)
        return full[marker:],dict(seam=seam,fitCents=pr.cents(actual,hz),rawPeak=float(max(abs(raw))))
    return sk.finish(raw),dict(rawPeak=float(max(abs(raw))))

def main():
    target=Path(sys.argv[1] if len(sys.argv)>1 else ROOT/'testkit/cairn-sing-prototype')
    target.mkdir(parents=True,exist_ok=True);report=dict(scope='Python harness, not Kotlin; no listening claim',cases=[])
    residual=0.;peak_ratio=0.
    for values in itertools.product([0.,.5,1.],repeat=5):
        m=DEFAULT|dict(zip(['WEIGHT','SLOPE','GROOVE','FRICTION','BODY'],values))
        for held in [False,True]:
            p=plan(m,held);residual=max(residual,abs(p['residual']))
            peak_ratio=max(peak_ratio,float(max(p['energy']))/max(p['cap'],.0001))
    report.update(controlPoints=243,maxBudgetResidual=residual,maxEnergyToCap=peak_ratio)
    for held in [False,True]:
        for note in [48,60,72]:
            out,metrics=render(DEFAULT,note,held)
            hz,confidence=pr.pitch(out,start_seconds=.6)
            row=dict(note=note,held=held,pitchHz=hz,confidence=confidence,cents=pr.cents(hz,440*2**((note-69)/12)),**metrics)
            assert abs(row['cents'])<6 and confidence>.8,row
            if held: assert row['seam']<1e-3,row
            sk.write_wav(str(target/f"sing-{note}-{'hold' if held else 'shot'}.wav"),out)
            report['cases'].append(row)
    (target/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))

if __name__=='__main__': main()
