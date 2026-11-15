"""Proposed compound contact; independent impacts, not a delayed copy or sample.
The reference shows multiple energy peaks, but their physical origin is unproven.
"""
import copy,json,sys
from pathlib import Path
import numpy as np
import build

def render(model,velocity=1.,take=0,spacing=.007):
    first=build.render(model,velocity,take)
    secondary=copy.deepcopy(model)
    # A softer landing excites less high-frequency energy than the initial plate hit.
    centers=np.sqrt(np.array(model['edges'][:-1])*model['edges'][1:])
    secondary['bandPowers']=(np.array(model['bandPowers'])*(centers[:,None]/1500)**(-.65)).tolist()
    second=build.render(secondary,velocity,take+100)
    rng=np.random.default_rng(4019+take*7919)
    delay=round(build.RATE*spacing*(1+rng.uniform(-.12,.12)))
    strength=.65*(.85+.15*velocity)*(1+rng.uniform(-.08,.08))
    # Fixed energy budget, never normalize individual hits; preserve velocity dynamics.
    out=first.copy();out[delay:]+=strength*second[:-delay]
    out/=np.sqrt(1+.65**2)
    return build.sk.fade_tail(build.sk.dc_block(out,build.RATE),build.RATE)

def main(out):
    out=Path(out);out.mkdir(parents=True,exist_ok=True)
    model=json.loads(Path(__file__).with_name('model-v1.json').read_text())
    comparison=json.loads(Path(__file__).with_name('measured-report.json').read_text())['comparisonGain']
    report={'status':'secondary-contact hypothesis awaiting listening','sourceCommitBase':'eb72e27bf102b625f35523b054ee6e62a57c8084','variants':{}}
    for label,spacing in [('tight',.0065),('separated',.011)]:
        rows=[]
        for v in [.3,.65,1.]:
            takes=[render(model,v,t,spacing) for t in range(4)]
            for t,x in enumerate(takes):
                assert np.isfinite(x).all() and max(abs(x))*comparison<1 and abs(x.mean())<.005 and x[-1]==0
                assert np.array_equal(x,render(model,v,t,spacing))
            assert all(np.linalg.norm(takes[0]/np.linalg.norm(takes[0])-x/np.linalg.norm(x))>.01 for x in takes[1:])
            rows.extend(takes)
        assert not np.any(render(model,0,0,spacing))
        for t in range(4):
            assert max(abs(rows[t]))<max(abs(rows[t+4]))<max(abs(rows[t+8]))
            assert np.linalg.norm(rows[t]/np.linalg.norm(rows[t])-rows[t+8]/np.linalg.norm(rows[t+8]))>.005
        def write(name,clips):
            build.sk.write_wav(str(out/(name+'.wav')),np.concatenate([np.r_[x*comparison,np.zeros(round(.25*build.RATE))] for x in clips]))
        write(label,rows[8:]);write(label+'-touch',rows)
        report['variants'][label]={'nominalSpacingMs':spacing*1000,'spacingVariationPercent':12,'secondaryAmplitude':.65,'cells':12,'checksPassed':True,'peakAfterComparisonGain':float(max(max(abs(x)) for x in rows)*comparison)}
    (out/'pair-report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
if __name__=='__main__':main(sys.argv[1])
