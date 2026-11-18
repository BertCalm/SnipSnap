"""Rhythm-only audition using the earlier preferred contact model."""
from pathlib import Path
import sys,json,numpy as np
ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'prototype/footwork/tap_contact_fit'))
import build as core
model=json.loads((ROOT/'prototype/footwork/tap_contact_fit/model.json').read_text())
RATE=44100;BPM=120;BEAT=60/BPM
# Two bars: a repeated rhythmic cell, then an answer with a short pickup.
# Toe/heel are sound roles; these are authored musical events, not verified choreography.
cell=[(0,'toe',.9),(.75,'toe',.35),(1,'heel',.55),(1.5,'toe',.65),
      (2,'toe',.9),(2.75,'toe',.35),(3,'heel',.55)]
events=cell+[(4+b,r,v) for b,r,v in cell]+[(7.5,'toe',.45),(7.75,'toe',.65)]
def render(guide=False,skeleton=False,v=1.,take=0):
    y=np.zeros(round(20*BEAT*RATE));counts={}
    # Four clicks establish the tempo; the same two-bar pattern then plays twice.
    for beat in range(20):
        if beat<4 or guide:
            n=round(.024*RATE);t=np.arange(n)/RATE
            click=.07*np.sin(2*np.pi*(1600 if beat%4==0 else 1100)*t)*np.exp(-t/.003)
            at=round(beat*BEAT*RATE);y[at:at+n]+=click
    for repeat in range(2):
        for b,role,force in events:
            if skeleton and b%1:continue
            rr=(counts.get(role,0)+take)%4;counts[role]=counts.get(role,0)+1
            x=core.render(model,role,force*v,rr)
            at=round((4+repeat*8+b)*BEAT*RATE);n=min(len(x),len(y)-at)
            y[at:at+n]+=.85*x[:n]
    return core.sk.fade_tail(y,RATE)
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
for name,guide,skeleton in [('pulse',True,True),('guided',True,False),('pattern',False,False)]:
    x=render(guide,skeleton);assert np.array_equal(x,render(guide,skeleton))
    core.write(out/f'{name}.wav',x)
(out/'plan.json').write_text(json.dumps({'bpm':BPM,'meter':'4/4','count_in_beats':4,'pattern_bars':2,'repetitions':2,'events':events,'scope':'Authored rhythm study; preferred contact coefficients unchanged; no brush or dancer-motion realism claim'},indent=2)+'\n')
print('Three checked rhythm auditions; identical count-in and event plan')
