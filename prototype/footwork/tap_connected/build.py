"""Connected tap study. Preserve the reference-guided toe/heel renderer exactly.

Brushes and event plans are authored hypotheses, not extracted dancer motion.
Usage: build.py output_directory
"""
from pathlib import Path
import sys,json,hashlib
from functools import lru_cache
import numpy as np
from scipy import signal
ROOT=Path(__file__).resolve().parents[3]
SOURCE=ROOT/'prototype/footwork/tap_contact_fit'
sys.path.insert(0,str(SOURCE))
import build as core
RATE=core.RATE;FS=core.FS;VEL=[.3,.65,1.]
MODEL=json.loads((SOURCE/'model.json').read_text())

@lru_cache(maxsize=4096)
def contact(role,v,take):
    if role in ['toe','heel']:return core.render(MODEL,role,v,take)
    # Sliding contact has a finite travel envelope through the same shoe response.
    # It is not a quiet planted toe repeated rapidly. The return is shorter and
    # firmer, making the two sounds in a shuffle separate gestures.
    rng=np.random.default_rng(core.sk.seed_for('FOOTWORK-TRAVEL',1,role,take))
    n=round(.16*FS);t=np.arange(n)/FS
    duration=(.062 if role=='brush' else .043)*(1+rng.uniform(-.07,.07))
    phase=np.clip(t/duration,0,1)
    env=np.sin(np.pi*phase)**(1.3 if role=='brush' else .8)
    h=core.response(MODEL['voices']['toe'],(1.55 if role=='brush' else 1.85)*(1+rng.uniform(-.025,.025)))
    noise=rng.normal(size=n+len(h))
    texture=signal.fftconvolve(noise,h)[len(h):len(h)+n]
    texture=signal.sosfilt(signal.butter(2,[330,7200],fs=FS,btype='bandpass',output='sos'),texture)
    # Pressure moves through the scrape; irregular grains modulate that travel.
    grains=np.interp(t,np.linspace(0,n/FS,16),rng.uniform(.55,1.3,16))
    raw=texture*env*grains*v**1.35*(.30 if role=='brush' else .36)
    return core.sk.fade_tail(core.sk.dc_block(core.sk.decimate4(core.sk.band_limit(raw,FS)),RATE),RATE)

def event(events,t,role,force,foot):
    events.append(dict(seconds=round(t,6),role=role,force=force,foot=foot))

def gesture(events,kind,t,foot,force=1.,spacing=1.):
    other='R' if foot=='L' else 'L'
    shapes={
        'shuffle':[(0,'brush',.70,foot),(.092,'spank',.82,foot)],
        'flap':[(0,'brush',.65,foot),(.075,'toe',1.,foot)],
        'ball_change':[(0,'toe',.65,foot),(.112,'toe',1.,other),(.169,'heel',.55,other)],
        'pickup':[(0,'brush',.62,foot),(.078,'spank',.75,foot),(.164,'toe',1.,foot)],
    }
    for offset,role,level,f in shapes[kind]:event(events,t+offset*spacing,role,force*level,f)

def plans():
    p={}
    for kind in ['shuffle','flap','ball_change']:
        e=[];gesture(e,kind,.04,'L');p[kind]=dict(duration=.68,events=e)
    # A short call and answer: air between phrases keeps contacts intelligible.
    e=[];beat=60/112
    gesture(e,'pickup',.04,'L',.94)
    event(e,.04+.75*beat,'heel',.63,'L')
    gesture(e,'flap',.04+1.25*beat,'R',.86)
    gesture(e,'ball_change',.04+2.25*beat,'L',.92)
    gesture(e,'shuffle',.04+3.25*beat,'R',.64)
    gesture(e,'flap',.04+4*beat,'R',.78)
    gesture(e,'pickup',.04+5*beat,'L',.88)
    gesture(e,'ball_change',.04+6*beat,'R',1.)
    event(e,.04+7*beat,'toe',.76,'L');event(e,.04+7.25*beat,'heel',.57,'L')
    p['phrase']=dict(duration=8*beat+.30,bpm=112,events=e)
    # Triplet-like pickup phrases alternate feet; heels punctuate, rather than
    # constituting an alternating-stomp roll. Pauses reset the motion.
    e=[]
    for i in range(6):
        t=.04+i*.36;foot='L' if i%2==0 else 'R'
        gesture(e,'pickup',t,foot,.72+.04*(i%3),spacing=.70)
        if i in [2,5]:event(e,t+.245,'heel',.57,foot)
    p['roll']=dict(duration=2.55,events=e)
    e=[];t=.04
    for i in range(7):
        force=.44+.075*i;space=1.05-.065*i
        gesture(e,'pickup',t,'L' if i%2==0 else 'R',force,space)
        t+=.46-.035*i
    gesture(e,'ball_change',t+.08,'L',1.,.85)
    p['build']=dict(duration=t+.65,events=e)
    for x in p.values():x['events'].sort(key=lambda a:a['seconds'])
    return p

PLANS=plans();GAIN=.82
def render(name,v,take):
    p=PLANS[name];y=np.zeros(round(p['duration']*RATE));counts={}
    for e in p['events']:
        key=(e['role'],e['foot']);rr=(counts.get(key,0)+take+(2 if e['foot']=='R' else 0))%4
        counts[key]=counts.get(key,0)+1
        x=contact(e['role'],float(e['force']*v),rr)
        at=round(e['seconds']*RATE);n=min(len(x),len(y)-at)
        y[at:at+n]+=GAIN*x[:n]
    return core.sk.fade_tail(y,RATE)

def main(out):
    out.mkdir(parents=True,exist_ok=True)
    report={'scope':'Python connected gestures, not native kit/export','plans':PLANS,
            'contact_model_sha256':hashlib.sha256((SOURCE/'model.json').read_bytes()).hexdigest(),
            'gain':GAIN,'cells':[],'core_invariance':[]}
    # Prove direct source preservation against the previously published PCM strips.
    for role in ['toe','heel']:
        prior=core.read(ROOT.parent/'sites/footwork-audition/dist/tap-contact-fit'/f'{role}-dynamics.wav')
        for z,v in enumerate(VEL):
            for take in range(4):
                x=contact(role,v,take);i=z*4+take;old=prior[i*17640:i*17640+len(x)]
                saved=np.rint(x*32767).astype(np.int16)
                assert np.array_equal(saved,np.rint(old*32768).astype(np.int16))
                report['core_invariance'].append({'role':role,'velocity':v,'take':take,'pcm_identical':True})
    for name in PLANS:
        cells=[[render(name,v,t) for t in range(4)] for v in VEL]
        assert not np.any(render(name,0.,0))
        for z,row in enumerate(cells):
            assert len({hashlib.sha256(x.tobytes()).hexdigest() for x in row})==4
            for take,x in enumerate(row):
                assert np.array_equal(x,render(name,VEL[z],take))
                assert np.isfinite(x).all() and max(abs(x))<.98 and abs(x.mean())<.005 and x[-1]==0
                report['cells'].append({'gesture':name,'velocity':VEL[z],'take':take,'peak':float(max(abs(x))),'rms':float(np.sqrt(np.mean(x*x)))})
                # Save and read every cell, including those omitted from the page.
                core.write(out/f'{name}-v{z}-rr{take}.wav',x)
        for take in range(4):assert all(np.linalg.norm(cells[z][take])<np.linalg.norm(cells[z+1][take]) for z in range(2))
        core.write(out/f'{name}.wav',cells[2][0])
        if name in ['shuffle','flap','ball_change']:
            core.write(out/f'{name}-dynamics.wav',np.concatenate([np.r_[x,np.zeros(round(.16*RATE))] for row in cells for x in row]))
    report['checks']=['72 finite/headroom/DC/tail cells','72 deterministic rerenders','72 PCM16 round trips',
                      'velocity-zero silence','increasing three-level energy','four distinct takes per level',
                      '24 toe/heel PCM cells identical to prior audition']
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print({'cells':len(report['cells']),'peak':max(c['peak'] for c in report['cells']),'core_preserved':len(report['core_invariance'])})

if __name__=='__main__':main(Path(sys.argv[1]))
