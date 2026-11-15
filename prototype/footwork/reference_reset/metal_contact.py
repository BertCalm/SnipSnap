"""Owner-directed ta-ee study: dry tap -> bright plate chatter -> short ring.
Proposed perceptual gesture, not a recovered shoe geometry or reference fit.
"""
import json,sys
from pathlib import Path
import numpy as np
from scipy import signal
import build

def render(model,velocity=1.,take=0,openness=0.):
    first=build.render(model,velocity,take)
    fs=build.FS;n=round(model['seconds']*fs);t=np.arange(n)/fs
    rng=np.random.default_rng(61903+take*7919)
    delay=(.012+.009*openness)*(1+rng.uniform(-.1,.1))
    u=t-delay
    # The ee is a delayed bright plate response, not another dark foot landing.
    envelope=np.where(u>=0,(1-np.exp(-np.maximum(u,0)/.0007))*np.exp(-np.maximum(u,0)/(.018+.012*openness)),0.)
    noise=rng.normal(size=n)
    plate=np.zeros(n)
    for freq,weight in [(2350,1.),(3470,.8),(5130,.6),(7310,.35)]:
        f=freq*(1+rng.uniform(-.025,.025))
        # Broad resonant bands avoid a pure pitched bell while retaining metal colour.
        b,a=signal.iirpeak(f,Q=5.5,fs=fs)
        plate+=weight*signal.lfilter(b,a,noise)
    ring=np.zeros(n)
    for freq,weight in [(2910,1.),(4670,.55),(6830,.3)]:
        f=freq*(1+rng.uniform(-.018,.018))
        ring+=weight*np.sin(2*np.pi*f*np.maximum(u,0))*np.exp(-np.maximum(u,0)/(.012+.01*openness))
    # Tiny additional contact excites a final tick; not a copy of the primary strike.
    w=np.maximum(u-.004,0)
    chatter=np.where(u>=.004,np.exp(-w/.0015),0)*noise
    high=(.28*plate+.012*ring+.014*chatter)*envelope*velocity**1.25
    high=build.sk.decimate4(build.sk.band_limit(high,fs))
    # Fixed calibration, no sample-by-sample or per-take normalization.
    y=.72*first+high
    return build.sk.fade_tail(build.sk.dc_block(y,build.RATE),build.RATE)

def main(out):
    out=Path(out);out.mkdir(parents=True,exist_ok=True)
    model=json.loads(Path(__file__).with_name('model-v1.json').read_text())
    gain=json.loads(Path(__file__).with_name('measured-report.json').read_text())['comparisonGain']
    report={'status':'owner-directed ta-ee hypothesis awaiting listening','variants':{}}
    for label,openness in [('small-metal',0.),('open-metal',1.)]:
        rows=[render(model,v,t,openness) for v in [.3,.65,1.] for t in range(4)]
        for i,x in enumerate(rows):
            assert np.isfinite(x).all() and max(abs(x))*gain<1 and abs(x.mean())<.005 and x[-1]==0
            assert np.array_equal(x,render(model,[.3,.65,1.][i//4],i%4,openness))
        assert not np.any(render(model,0,0,openness))
        for t in range(4):
            assert max(abs(rows[t]))<max(abs(rows[t+4]))<max(abs(rows[t+8]))
            assert np.linalg.norm(rows[t]/np.linalg.norm(rows[t])-rows[t+8]/np.linalg.norm(rows[t+8]))>.005
        for z in range(3):
            assert all(np.linalg.norm(rows[z*4]/np.linalg.norm(rows[z*4])-rows[z*4+t]/np.linalg.norm(rows[z*4+t]))>.01 for t in range(1,4))
        def write(name,clips,gap):
            build.sk.write_wav(str(out/(name+'.wav')),np.concatenate([np.r_[x*gain,np.zeros(round(gap*build.RATE))] for x in clips]))
        write(label,rows[8:],.25);write(label+'-touch',rows,.12)
        write(label+'-phrase',[render(model,v,i%4,openness) for i,v in enumerate([1.,.7,1.,.7,.9,.55,1.,.7])],.08)
        report['variants'][label]={'cells':12,'delayMs':12+9*openness,'plateDecayMs':18+12*openness,'checksPassed':True,'peak':float(max(max(abs(x)) for x in rows)*gain)}
    (out/'metal-report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':main(sys.argv[1])
