"""Brush contact/movement hypotheses. No recorded PCM and no human-motion claim."""
from pathlib import Path
import sys,json,importlib.util
import numpy as np
from scipy import signal
ROOT=Path(__file__).resolve().parents[3]
spec=importlib.util.spec_from_file_location('connected',ROOT/'prototype/footwork/tap_connected/build.py')
old=importlib.util.module_from_spec(spec);spec.loader.exec_module(old)
core=old.core;FS=old.FS;RATE=old.RATE

def brush(role,v,take,travel=1.):
    forward=role in ['brush','shuffle_brush']
    rng=np.random.default_rng(core.sk.seed_for('FOOTWORK-SLIDING-CONTACT',1,role,take))
    n=round(.24*FS);t=np.arange(n)/FS
    duration=(.104 if forward else .081)*travel*(1.10-.10*v)*(1+rng.uniform(-.09,.09))
    u=np.clip(t/duration,0,1)
    # Asymmetric travel speed: accelerate into the floor, then unload. A few
    # low-frequency pressure knots replace a constant-speed noise swell.
    speed=np.sin(np.pi*u)**1.5*(.45+1.3*u if forward else 1.55-1.05*u)
    pressure=np.interp(u,[0,.18,.46,.72,1],[0,rng.uniform(.4,.7),1.,rng.uniform(.25,.55),0])
    distance=np.cumsum(speed)/FS
    distance/=max(distance[-1],1e-20)
    excitation=np.zeros(n)
    # Surface asperities are distributed in distance; their arrival times follow
    # foot speed. Finite signed pulses share a response with the rubbing texture.
    location=0.
    while location<.98:
        location+=rng.uniform(.018,.065)
        if location>=.98:break
        at=int(np.searchsorted(distance,location));width=max(8,round(FS*rng.uniform(.00018,.00065)))
        end=min(n,at+width)
        pulse=np.sin(np.linspace(0,2*np.pi,width))[:end-at]
        excitation[at:end]+=pulse*rng.uniform(.5,1.4)*pressure[at]*rng.choice([-1.,1.])
    noise=rng.normal(size=n)
    rubbing=signal.sosfilt(signal.butter(1,[500,6500],fs=FS,btype='bandpass',output='sos'),noise)
    excitation+=.075*rubbing*pressure*np.sqrt(speed)
    h=core.response(old.MODEL['voices']['toe'],(1.03 if forward else 1.14)*(1+rng.uniform(-.025,.025)))
    raw=signal.fftconvolve(excitation,h)[:n]*.19*v**1.35
    return core.sk.fade_tail(core.sk.dc_block(core.sk.decimate4(core.sk.band_limit(raw,FS)),RATE),RATE)

def source(role,v,take,travel=1.):
    return old.contact(role,v,take,travel) if role in ['toe','heel'] else brush(role,v,take,travel)

def render(name,v,take,new=True,movement=True):
    p=old.PLANS[name];y=np.zeros(round(p['duration']*RATE));counts={};ledger=[]
    # Shared movement state: displacement and energy evolve over several contacts.
    # These laws are authored hypotheses; no measured dancer timing is implied.
    phase=take*.83
    for i,e in enumerate(p['events']):
        seconds=e['seconds'];force=e['force'];travel=e['travel']
        if movement:
            seconds+=.008*np.sin(2*np.pi*seconds/1.9+phase)
            force*=.95+.05*np.cos(2*np.pi*seconds/1.3+phase)
            travel*=1+.055*np.sin(2*np.pi*seconds/1.9+phase)
        key=e['role'],e['foot'];count=counts.get(key,0);counts[key]=count+1
        rr=(count+take+(2 if e['foot']=='R' else 0))%4
        x=(source if new else old.contact)(e['role'],float(force*v),rr,travel)
        at=round(seconds*RATE);n=min(len(x),len(y)-at);y[at:at+n]+=.82*x[:n]
        ledger.append(dict(seconds=seconds,force=force,travel=travel,role=e['role'],foot=e['foot'],take=rr))
    return core.sk.fade_tail(y,RATE),ledger

def main(out):
    out.mkdir(parents=True,exist_ok=True);report={'scope':'Python brush/movement hypotheses; native model 4 remains rejected for naturalness','cells':[]}
    for role in ['shuffle_brush','shuffle_spank']:
        cells=[]
        for v in old.VEL:
            row=[brush(role,v,t) for t in range(4)];cells.extend(row)
            assert all(np.array_equal(row[t],brush(role,v,t)) for t in range(4))
            assert len({x.tobytes() for x in row})==4
        for t in range(4):assert np.linalg.norm(cells[t])<np.linalg.norm(cells[t+4])<np.linalg.norm(cells[t+8])
        assert not np.any(brush(role,0,0))
        core.write(out/f'{role}.wav',cells[8]);core.write(out/f'{role}-previous.wav',old.contact(role,1.,0))
        core.write(out/f'{role}-touch.wav',np.concatenate([np.r_[x,np.zeros(round(.16*RATE))] for x in cells]))
        for i,x in enumerate(cells):
            assert max(abs(x))<.98 and abs(x.mean())<.005 and x[-1]==0
            core.write(out/f'{role}-{i}.wav',x)
            report['cells'].append(dict(role=role,cell=i,peak=float(max(abs(x))),rms=float(np.sqrt(np.mean(x*x)))))
    for name in ['shuffle','phrase']:
        cells=[[render(name,v,t)[0] for t in range(4)] for v in old.VEL]
        for take in range(4):assert all(np.linalg.norm(cells[z][take])<np.linalg.norm(cells[z+1][take]) for z in range(2))
        for z,row in enumerate(cells):
            assert len({x.tobytes() for x in row})==4
            for take,x in enumerate(row):
                assert np.array_equal(x,render(name,old.VEL[z],take)[0]) and abs(x.mean())<.005
                core.write(out/f'{name}-v{z}-rr{take}.wav',x)
                report['cells'].append(dict(role=name,velocity=old.VEL[z],take=take,peak=float(max(abs(x)))))
        assert not np.any(render(name,0,0)[0])
        a,ledger=render(name,1.,0);b,_=render(name,1.,0,True,False);c,_=render(name,1.,0,False,False)
        core.write(out/f'{name}.wav',a)
        # Three equal-energy comparisons isolate source, then shared timing/force.
        energy=np.linalg.norm(c)
        ys=[x*energy/np.linalg.norm(x) for x in [a,b,c]];gain=min(1.,.95/max(max(abs(x)) for x in ys))
        for label,x in zip(['movement','fixed-timing','previous'],ys):core.write(out/f'{name}-{label}.wav',x*gain)
        report[name+'_events']=ledger
    report['checks']=['48 deterministic/dynamic/repeat cells','zero velocity','PCM16 round trips','finite/headroom/DC/tail'];report['peak']=max(x['peak'] for x in report['cells'])
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print({'cells':len(report['cells']),'peak':report['peak']})

if __name__=='__main__':main(Path(sys.argv[1]))
