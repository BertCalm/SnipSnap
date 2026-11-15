"""Sample-free gesture family over the owner-recognized ta-ee contact.
Python audition, not a delivered Kotlin instrument or sampler kit.
"""
import json,sys,time
from pathlib import Path
import numpy as np
from scipy import signal
import build,metal_contact
RATE=build.RATE
VOICES=['toe','heel','brush-forward','brush-back','shuffle','flap','roll','riser']

def brush(model,v,take,back=False):
    fs=build.FS;n=round(.22*fs);t=np.arange(n)/fs
    rng=np.random.default_rng(91003+take*7919+int(back)*104729)
    duration=.058 if back else .078
    u=np.clip(t/duration,0,1)
    # Direction changes the pressure trajectory and abrasive band, not just gain.
    pressure=np.sin(np.pi*u)**(.8 if back else 1.4)
    pressure*=1.15-.65*u if back else .45+.7*u
    pressure[t>=duration]=0
    noise=rng.normal(size=n)
    friction=build.band(noise,1000 if back else 650,10500 if back else 7500,fs)
    chatter=.6+.4*np.sin(2*np.pi*(130*t+1100*t*t)+rng.uniform(0,6.28))**2
    raw=.15*friction*pressure*chatter*v**1.2
    y=build.sk.decimate4(build.sk.band_limit(raw,fs))
    # A light plate exit ties the sweep to the existing metallic tap character.
    tick=metal_contact.render(model,v,take+200,0.)
    delay=round(duration*.72*RATE);y[delay:]+=.23*tick[:-delay]
    return build.sk.fade_tail(build.sk.dc_block(y,RATE),RATE)

def render(model,voice,v=1.,take=0):
    assert voice in VOICES and 0<=v<=1 and take>=0
    if voice=='toe':return metal_contact.render(model,v,take,0.)
    if voice=='heel':
        x=metal_contact.render(model,v,take+400,.25)
        x=signal.sosfilt(signal.butter(2,3700,fs=RATE,output='sos'),x)*.85
        fs=build.FS;t=np.arange(round(.22*fs))/fs
        rng=np.random.default_rng(77111+take*7919)
        noise=build.band(rng.normal(size=len(t)),100,1700,fs)
        # Broader floor support, shorter upper plate response than the toe.
        body=(.18*noise+.07*np.sin(2*np.pi*(210+rng.uniform(-12,12))*t))*np.exp(-t/.012)*np.minimum(1,t/.0007)*v**1.1
        x+=build.sk.decimate4(build.sk.band_limit(body,fs))
        return build.sk.fade_tail(build.sk.dc_block(x,RATE),RATE)
    if voice.startswith('brush'):return brush(model,v,take,voice=='brush-back')
    if voice in ['shuffle','flap']:
        events=[(0,'brush-forward',.8),( .095,'brush-back' if voice=='shuffle' else 'toe',1.)]
        length=.42
    else:
        length=2.4 if voice=='roll' else 3.1
        if voice=='roll':events=[(i*.115,'toe' if i%2==0 else 'heel',.72 if i%4 else 1.) for i in range(16)]+[(2.0,'heel',1.)]
        else:
            times=[0.];interval=.2
            while times[-1]+interval<2.55:
                times.append(times[-1]+interval);interval=max(.045,interval*.92)
            events=[(t,'toe' if i%2==0 else 'heel',.3+.65*t/2.55) for i,t in enumerate(times)]+[(2.65,'heel',1.)]
    out=np.zeros(round(length*RATE))
    for i,(start,part,force) in enumerate(events):
        x=render(model,part,v*force,take*67+i);at=round(start*RATE)
        out[at:at+len(x)]+=x
    return build.sk.fade_tail(build.sk.dc_block(out,RATE),RATE)

def main(out):
    out=Path(out);a=out/'audio';a.mkdir(parents=True,exist_ok=True)
    model=json.loads(Path(__file__).with_name('model-v1.json').read_text())
    gain=2.0 # One fixed family gain: preserve dynamics and differences between gestures.
    report={'status':'gesture family awaiting owner listening; native delivery gates open','gain':gain,'voices':{},'cells':96}
    cache={}
    for voice in VOICES:
        start=time.perf_counter();rows=[]
        for z,v in enumerate([.3,.65,1.]):
            for take in range(4):
                x=render(model,voice,v,take)*gain
                assert np.isfinite(x).all() and max(abs(x))<.95 and abs(x.mean())<.005 and x[-1]==0
                assert np.array_equal(x,render(model,voice,v,take)*gain)
                rows.append(x);build.sk.write_wav(str(a/f'{voice}-v{z}-r{take}.wav'),x)
        assert not np.any(render(model,voice,0))
        for t in range(4):
            assert max(abs(rows[t]))<max(abs(rows[t+4]))<max(abs(rows[t+8]))
            assert np.linalg.norm(rows[t]/np.linalg.norm(rows[t])-rows[t+8]/np.linalg.norm(rows[t+8]))>.005
        for z in range(3):
            assert all(np.linalg.norm(rows[z*4]/np.linalg.norm(rows[z*4])-rows[z*4+i]/np.linalg.norm(rows[z*4+i]))>.01 for i in range(1,4))
        build.sk.write_wav(str(a/f'{voice}-touch.wav'),np.concatenate([np.r_[x,np.zeros(round(.12*RATE))] for x in rows]))
        cache[voice]=rows[8]
        report['voices'][voice]={'cells':12,'checksPassed':True,'peak':float(max(max(abs(x)) for x in rows)),'meanRenderMsIncludingVerification':(time.perf_counter()-start)*1000/24}
    correlations={}
    for left,right in [('toe','heel'),('brush-forward','brush-back'),('shuffle','flap')]:
        correlation=float(np.dot(cache[left],cache[right])/np.linalg.norm(cache[left])/np.linalg.norm(cache[right]));assert abs(correlation)<.9
        correlations[left+'/'+right]=correlation
    phrase=np.zeros(round(9*RATE));beat=60/116
    events=[(0,'toe',1),(.5,'heel',.8),(1,'shuffle',.9),(2,'flap',1),(3,'heel',.85),(3.5,'toe',.6),(4,'shuffle',1),(5,'flap',.8),(6,'toe',1),(6.5,'heel',.7),(7,'flap',1),(8,'shuffle',.8),(9,'toe',1),(9.5,'heel',.7),(10,'flap',1),(11,'heel',1),(12,'shuffle',1),(13,'flap',.8),(14,'toe',.9),(15,'heel',1)]
    for i,(b,voice,v) in enumerate(events):
        x=render(model,voice,v,i%4)*gain;at=round(b*beat*RATE);phrase[at:at+len(x)]+=x
    assert max(abs(phrase))<.95
    build.sk.write_wav(str(a/'dance-116.wav'),phrase)
    report['normalizedPairCorrelations']=correlations;report['phraseBpm']=116
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
if __name__=='__main__':main(sys.argv[1])
