"""Experimental Trefoil held-state proof. Python harness, not Kotlin.

Adapted from the repository's luthier proto_template.py. Reuses its probes and
conditioning primitives, but carries filters through genuine preceding cycles.
No crossfade, end fade, limiter, state reset, or duplicated audio proves closure.
The source is a bounded periodic contact adaptation; finite model 2 is untouched.
"""
from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass, replace
import hashlib
import json
from pathlib import Path
import sys
import time

import numpy as np
from numba import njit
from scipy import integrate, interpolate, signal

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / '.claude/skills/luthier/scripts'))
import snipkit as sk
import probes as pr

RATE = 44100
IR = RATE * 4
PERIOD = 6.3259140097821724
RATIOS = np.array([1., 2., 3., 4., 5., 6., 8., 10.])
COLORS = np.array([[.85,.90,.25,.12,.08,.04,.02,.01],
                   [.75,.20,.95,.25,.65,.20,.10,.04],
                   [.60,.15,.25,.95,.25,.85,.70,.50]])
WEIGHTS = np.array([.10,1.,1.35,1.7,2.,2.3,2.6,2.9])
REFERENCE = np.array([.97000436,-.24308753,-.97000436,.24308753,0.,0.,
                     .46620368406955315,.4323657309174746,
                     .46620368406955315,.4323657309174746,
                     -.9324073681391063,-.8647314618349492])
MODEL = 'trefoil-held-python-1'


@dataclass(frozen=True)
class Recipe:
    midi: int = 60
    velocity: float = .78
    seed: int = 43012026
    weave: float = .42
    nudge: float = 0.
    exchange: float = .55
    thread: float = .28
    memory: float = .25
    excited_body: int = -1
    model: str = MODEL

    def validate(self):
        if self.model != MODEL or self.nudge != 0:
            raise ValueError('This proof supports its own model and NUDGE=0 only')
        if not 36 <= self.midi <= 84 or self.excited_body not in (-1,0,1,2):
            raise ValueError('Unsupported note/body')
        for key in ('velocity','weave','exchange','thread','memory'):
            if not np.isfinite(getattr(self,key)) or not 0 <= getattr(self,key) <= 1:
                raise ValueError(key)


def weave_hz(w):
    if w == 0: return 0.
    smooth = lambda x: x*x*(3-2*x)
    if w < .1: return smooth(w/.1)/16
    knots = [.1,.25,.5,.75,1.]
    hz = [1/16,1/8,.5,2.,8.]
    i = min(3, np.searchsorted(knots,w,side='right')-1)
    return hz[i]*(hz[i+1]/hz[i])**smooth((w-knots[i])/(knots[i+1]-knots[i]))


def orbit():
    def ode(t,y):
        p=y[:6].reshape(3,2); acc=np.zeros_like(p)
        for i,j in ((0,1),(1,2),(2,0)):
            d=p[j]-p[i]; r=np.linalg.norm(d)
            if r < 1e-3: raise ValueError('Orbit exclusion reached')
            a=d/r**3; acc[i]+=a; acc[j]-=a
        return np.r_[y[6:],acc.ravel()]
    sol=integrate.solve_ivp(ode,(0,PERIOD),REFERENCE,method='DOP853',
                            rtol=1e-10,atol=1e-12,max_step=.04,dense_output=True)
    if not sol.success: raise RuntimeError(sol.message)
    error=float(np.max(np.abs(sol.y[:,-1]-REFERENCE)))
    t=np.linspace(0,PERIOD,8193); y=sol.sol(t)
    c=[]; approach=[]; distance=np.zeros((3,len(t)))
    for i,j in ((0,1),(1,2),(2,0)):
        d=y[2*j:2*j+2]-y[2*i:2*i+2]; r=np.sqrt((d*d).sum(axis=0))
        dv=y[6+2*j:8+2*j]-y[6+2*i:8+2*i]
        c.append(1/(1+(r/1.15)**4)); approach.append(np.tanh(-(d*dv).sum(axis=0)/r/1.8))
        distance[i]+=r*.5; distance[j]+=r*.5
    desc=np.r_[c,approach,1/(1+(distance/3.5)**2)].T
    projection=float(np.max(np.abs(desc[-1]-desc[0])))
    # An explicitly measured periodic descriptor approximation, not an exact ODE orbit.
    desc[-1]=desc[0]
    return interpolate.CubicSpline(t/PERIOD,desc,bc_type='periodic'),error,projection


def rotations(v):
    angle=np.linalg.norm(v,axis=-1)
    u=v/np.maximum(angle[:,None],1e-30)
    x,y,z=u.T; c=np.cos(angle); s=np.sin(angle); d=1-c
    return np.stack([c+x*x*d,x*y*d-z*s,x*z*d+y*s,
                     y*x*d+z*s,c+y*y*d,y*z*d-x*s,
                     z*x*d-y*s,z*y*d+x*s,c+z*z*d],axis=-1).reshape(-1,3,3)


def plan(r, oversample=4, noise_enabled=True):
    r.validate()
    if oversample not in (4,8): raise ValueError('Supported proof rates: 4x and 8x')
    ir=RATE*oversample
    root=440*2**((r.midi-69)/12)
    requested=1/weave_hz(r.weave) if r.weave else 1/weave_hz(.42)
    # Long low-WEAVE loops remain an explicit unsupported case in this proof.
    if requested > 6: raise ValueError('Proof motion period exceeds six-second budget')
    cycles=max(1,round(root*requested))
    frames=round(cycles/root*RATE/8)*8
    n=frames*oversample; seconds=frames/RATE
    source_hz=cycles/seconds
    spline,orbit_error,projection=orbit()
    phase=np.arange(n)/n if r.weave else np.zeros(n)
    desc=spline(phase); c=desc[:,:3]; approach=desc[:,3:6]; attach=desc[:,6:]
    rates=18*min(1,root/261.625565)*r.exchange**1.1*WEIGHTS/ir
    v=c[::32][:,[1,2,0]]*(.85+.15*approach[::32][:,[1,2,0]])
    rot=np.stack([rotations(v*rate) for rate in rates],axis=1)
    freq=root*RATIOS[:,None]*(1+np.where(np.arange(8)[:,None]>=4,
                        .003*r.thread**3*(np.arange(3)[None,:]-1),0))
    t60=np.where(RATIOS==1,1.4,(.65+1.1*r.thread)/RATIOS**.18)
    a=np.exp(-np.log(1000)/t60[:,None]/ir-2j*np.pi*freq/ir)
    A=np.ascontiguousarray(a[None,:,:,None]*rot)
    excite=COLORS*np.r_[.8,np.repeat(.8+1.5*r.thread,7)]
    excite/=np.linalg.norm(excite,axis=1)[:,None]
    pickup=COLORS*np.r_[.45+.20*(1-min(1,root/261.625565)),np.repeat(.8+1.2*r.thread,7)]*.8/np.sqrt(3)
    harmonic=np.r_[1.,(.8+1.4*r.thread)/np.arange(2.,11.)**(1.2-.9*r.thread)]
    harmonic/=np.linalg.norm(harmonic)
    rng=np.random.Generator(np.random.PCG64(r.seed))
    carrier=np.zeros((n,3)); phi=2*np.pi*cycles*np.arange(n)/n
    for b,p in enumerate([0.,.61,1.73]):
        for h,weight in enumerate(harmonic,1): carrier[:,b]+=weight*np.sin(h*(phi+p))
    # PCG64 periodic noise: explicitly a Python prototype source, not Kotlin Random.
    noise=rng.uniform(-1,1,(n,3)); alpha=1-np.exp(-2*np.pi*(900+6500*r.thread)/ir)
    for b in range(3):
        _,zi=signal.lfilter([alpha],[1,-(1-alpha)],noise[:,b],zi=[0.])
        noise[:,b]=signal.lfilter([alpha],[1,-(1-alpha)],noise[:,b],zi=zi)[0]
    pressure=np.stack([.10+1.8*(c[:,a]**3+c[:,b]**3) for a,b in [(0,2),(1,0),(2,1)]],axis=1)
    # Bounded external drive replaces the finite work budget and nonlinear body feedback.
    source=np.tanh((.6+3*r.velocity)*(.65*carrier+(.06+.18*r.thread)*noise*noise_enabled))
    force=np.ascontiguousarray(.8*np.sqrt(max(1,130/root))*r.velocity*attach*pressure*source/ir)
    if r.excited_body >= 0:
        force[:,np.arange(3)!=r.excited_body]=0
    ma=np.exp(-np.log(1000)/((2+4.5*r.memory)/(1+.10*np.arange(7)))/ir
              -2j*np.pi*root*RATIOS[1:]/ir)
    return dict(A=A,force=force,excite=np.ascontiguousarray(excite.T),pickup=np.ascontiguousarray(pickup.T),
                ma=ma,send=120*np.sqrt(r.memory)/ir,ret=.45*np.sqrt(r.memory),
                frames=frames,n=n,ir=ir,oversample=oversample,seconds=seconds,source_hz=source_hz,
                root=root,orbit_error=orbit_error,descriptor_projection=projection,
                contraction=float(np.max(np.abs(a))),requested_seconds=requested)


@njit(cache=True)
def monodromy(powers):
    m=np.zeros((8,3,3),np.complex128)
    for k in range(8):
        for b in range(3): m[k,b,b]=1
    for block in range(len(powers)):
        for k in range(8): m[k]=powers[block,k] @ m[k]
    return m


@njit(cache=True)
def evolve(A,force,excite,pickup,ma,send,ret,z0,m0,count,off=-1,onset=0,release=0):
    z=z0.copy(); mem=m0.copy(); raw=np.empty(count); energies=np.empty(count//1024+1)
    maxrise=0.; previous=0.; maxenergy=0.; n=len(force)
    for frame in range(count):
        f=frame%n; gate=1.
        if onset>0 and frame<onset: gate=.5-.5*np.cos(np.pi*frame/onset)
        if off>=0 and frame>=off:
            gate=0. if release==0 or frame>=off+release else .5+.5*np.cos(np.pi*(frame-off)/release)
        for k in range(8):
            for b in range(3): z[k,b]+=1j*excite[k,b]*force[f,b]*gate
            v=z[k].copy()
            for b in range(3):
                z[k,b]=A[f//32,k,b,0]*v[0]+A[f//32,k,b,1]*v[1]+A[f//32,k,b,2]*v[2]
        mix=0.; energy=0.
        for k in range(8):
            source=0.
            for b in range(3):
                source+=z[k,b].imag*pickup[k,b]
                energy+=.5*abs(z[k,b])**2
            mix+=source
            if k>0:
                mem[k-1]=ma[k-1]*(mem[k-1]+1j*source*send)
                mix+=mem[k-1].imag*ret/np.sqrt(k)
        raw[frame]=mix
        if off>=0 and frame>off+release: maxrise=max(maxrise,energy-previous)
        previous=energy; maxenergy=max(maxenergy,energy)
        if frame%1024==0: energies[frame//1024]=energy
    return raw,z,mem,maxrise,maxenergy,energies[:(count-1)//1024+1]


def run(p,z=None,mem=None,count=None,off=-1,onset=0,release=0):
    count=p['n'] if count is None else count
    if not 0 < count <= p['ir']*60: raise ValueError('Proof capture exceeds 60-second work cap')
    return evolve(p['A'],p['force'],p['excite'],p['pickup'],p['ma'],p['send'],p['ret'],
                  np.zeros((8,3),complex) if z is None else z,
                  np.zeros(7,complex) if mem is None else mem,
                  count,off,onset,release)


def solve(p):
    # The full block-triangular system is solved core first, then feedforward memory.
    powers=np.linalg.matrix_power(p['A'],32)
    m=monodromy(powers)
    b=run(p)[1]
    z=np.stack([np.linalg.solve(np.eye(3)-m[k],b[k]) for k in range(8)])
    driven=run(p,z=z)
    mem=driven[2]/(1-p['ma']**p['n'])
    actual=run(p,z,mem)
    state=max(float(np.max(np.abs(actual[1]-z))),float(np.max(np.abs(actual[2]-mem))))
    relative=state/max(1e-20,float(np.max(np.abs(z))),float(np.max(np.abs(mem))))
    return z,mem,dict(state_error=state,state_relative_error=relative,
                    monodromy_norm=float(np.max(np.linalg.norm(m,ord=2,axis=(1,2)))))


def condition(x,oversample=4):
    # Continuous causal filters with real guard cycles. No per-loop resets or end fades.
    y=signal.sosfilt(signal.butter(8,20000,fs=RATE*oversample,output='sos'),x)
    for _ in range(int(np.log2(oversample))):
        y=signal.lfilter(signal.firwin(63,.45),[1.],y)[::2]
    alpha=np.exp(-2*np.pi*16/RATE)
    return signal.lfilter([alpha,-alpha],[1.,-alpha],y)


def write(path,x):
    # 24-bit mono PCM so playback/export has headroom without loop-end fades.
    import wave
    if not np.all(np.isfinite(x)) or np.max(np.abs(x))>=1: raise ValueError('Unsafe WAV')
    pcm=np.round(x*8388607).astype('<i4').view(np.uint8).reshape(-1,4)[:,:3].copy()
    with wave.open(str(path),'wb') as f:
        f.setparams((1,3,RATE,0,'NONE','not compressed')); f.writeframes(pcm.tobytes())


def render_case(folder,name,r,returns=3):
    start=time.perf_counter(); p=plan(r); planned=time.perf_counter()
    z,mem,closure=solve(p); solved=time.perf_counter()
    raw=run(p,z,mem,p['n']*(returns+1))[0]
    conditioned=condition(raw)
    # First cycle warms output filters; all retained cycles independently evolved.
    context=conditioned[p['frames']:]; loop=context[:p['frames']]
    gain=min(.10/pr.rms(loop),.85/max(1e-20,pr.peak(context)))
    loop=loop*gain; context=context*gain
    off_seconds=p['seconds']*2+.371
    off=round(off_seconds*p['ir']); ramp=round(.025*p['ir'])
    note=run(p,count=off+round(8*p['ir']),off=off,onset=round(.035*p['ir']),release=ramp)
    release=condition(note[0])*gain
    if pr.peak(release)>=.99: raise ValueError('Release exceeds shared headroom')
    write(folder/f'{name}-loop.wav',loop)
    write(folder/f'{name}-returns.wav',context)
    write(folder/f'{name}-release.wav',release)
    hz,confidence=pr.pitch(loop,lo_hz=p['root']*.75,hi_hz=p['root']*1.25,start_seconds=.2)
    report=dict(recipe=asdict(r),**closure,period_frames=p['frames'],period_seconds=p['seconds'],
                requested_period_seconds=p['requested_seconds'],source_hz=p['source_hz'],
                source_cents=pr.cents(p['source_hz'],p['root']),detected_hz=hz,pitch_confidence=confidence,
                detected_cents=pr.cents(hz,p['root']),orbit_closure=p['orbit_error'],
                descriptor_endpoint_projection=p['descriptor_projection'],contraction=p['contraction'],
                seam=sk.seam_error(context[:2*p['frames']],p['frames']),
                independent_cycle_max_error=float(np.max(np.abs(context[:p['frames']]-context[p['frames']:2*p['frames']]))),
                raw_peak=pr.peak(raw),gain=gain,output_peak=pr.peak(context),dc=pr.dc(loop),
                noteoff_seconds=off/p['ir'],power_stops_seconds=(off+ramp)/p['ir'],
                release_peak=pr.peak(release),unforced_core_energy_rise=note[3],
                release_last_second_rms=pr.rms(release[-RATE:]),
                plan_ms=(planned-start)*1000,solve_ms=(solved-planned)*1000,
                total_ms=(time.perf_counter()-start)*1000,returns=returns)
    (folder/f'{name}.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(dict(name=name,seam=report['seam'],state=closure['state_relative_error'],
                         cents=report['detected_cents'],total_ms=report['total_ms'])),flush=True)
    return report


def main():
    parser=argparse.ArgumentParser(); parser.add_argument('output',type=Path)
    parser.add_argument('--case',default='all'); args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    base=Recipe()
    cases={'ordered':(base,8),'still':(replace(base,weave=0),3),
           'no-exchange':(replace(base,exchange=0),3),
           'memory':(replace(base,memory=.8),3),
           'bass':(replace(base,midi=48,thread=.45),3),
           'bright':(replace(base,midi=72,thread=.7),3)}
    reports={}
    for name,(recipe,returns) in cases.items():
        if args.case in ('all',name): reports[name]=render_case(args.output,name,recipe,returns)
    report={'status':'experimental Python harness, not Kotlin; listening pending',
            'model':MODEL,'cases':reports,'versions':{'numpy':np.__version__}}
    (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')


if __name__=='__main__': main()
