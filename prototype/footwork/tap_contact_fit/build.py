"""Fit coarse spectra and two contact-envelope lobes; synthesize fresh excitation.

The render function reads numeric parameters only. No recording PCM, original
phase, sampled impulse response, or previous FOOTWORK audio enters synthesis.
Toe/heel names describe intended pad roles, not verified reference anatomy.
"""
import argparse, hashlib, json, sys, time
from pathlib import Path
import numpy as np
from scipy import signal, optimize, ndimage
from scipy.io import wavfile
from scipy.spatial.distance import jensenshannon
from analyze import RATE, read, metrics

ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'.claude/skills/luthier/scripts'))
import snipkit as sk
FS=RATE*4
N=round(.24*FS)
VELOCITIES=[.3,.65,1.]
# Groups are selected by measured spectrum and spacing, not anatomical claims.
TRAIN={'toe':[5.1383,7.6576,7.8771], 'heel':[4.9288,5.3878,6.0113]}
HELD={'toe':[2.6789,4.5696,9.3837], 'heel':[5.5673,7.0041,7.3084]}

def crop(x,tm):
    a=round(tm*RATE)
    z=x[a:a+round(.12*RATE)].copy()
    # Align by sustained 0.5ms energy crossing, not a single codec overshoot.
    env=ndimage.uniform_filter1d(z*z,22)
    onset=max(0,int(np.flatnonzero(env>max(env)*.04)[0])-22)
    return np.pad(z[onset:],(0,onset)),(a+onset)/RATE

def lobes(t,p):
    tau1,delay,tau2,mix,tail=p
    first=(1-np.exp(-t/.00035))*np.exp(-t/tau1)
    u=np.maximum(0,t-delay)
    second=(u/tau2)*np.exp(1-u/tau2)
    return first+mix*second+tail*(1-np.exp(-t/.002))*np.exp(-t/.032)

def fit(ref):
    result={'version':1,'sample_rate':RATE,'internal_rate':FS,'voices':{},
            'training_reference':1,'labels':'Intended pad roles; reference toe/heel attribution unverified'}
    freqs=np.geomspace(65,16000,64)
    for voice,times in TRAIN.items():
        clips=[crop(ref,t)[0] for t in times]
        spectra=[];envelopes=[]
        for x in clips:
            x=x/max(np.linalg.norm(x),1e-20)
            f,p=signal.periodogram(x,RATE,nfft=16384)
            p=ndimage.gaussian_filter1d(p,3)
            spectra.append(np.interp(freqs,f,p))
            # 2ms averaging describes the envelope, not the waveform's phase.
            e=np.array([np.mean(a*a) for a in np.array_split(x,60)])
            envelopes.append(e/sum(e))
        spectrum=np.mean(spectra,axis=0)
        env=np.mean(envelopes,axis=0);env/=sum(env)
        t=(np.arange(60)+.5)*.002
        def err(p):
            pred=lobes(t,p);pred/=sum(pred)
            return np.sqrt(pred)-np.sqrt(env)
        sol=optimize.least_squares(err,[.006,.008,.010,.5,.08],
            bounds=([.001,.002,.002,0,0],[.025,.035,.040,2.,1.]))
        result['voices'][voice]={'frequency_hz':freqs.tolist(),
            'amplitude_db':(10*np.log10(spectrum/max(spectrum))).tolist(),
            'envelope':sol.x.tolist(),'training_times_seconds':times,
            'held_out_times_seconds':HELD[voice],
            'envelope_fit_rmse':float(np.sqrt(np.mean(err(sol.x)**2)))}
    return result

def response(p,warp):
    # Real cepstrum makes a minimum-phase filter from 64 coarse magnitude bins.
    # This is regenerated filter phase, not phase recovered from a recording.
    size=16384;f=np.fft.rfftfreq(size,1/FS)
    hz=np.r_[0,30,p['frequency_hz'],22000,FS/2]*warp
    db=np.r_[-100,-45,p['amplitude_db'],-80,-100]
    logmag=np.interp(f,hz,db)*np.log(10)/20
    cep=np.fft.irfft(logmag,size)
    causal=np.zeros(size);causal[0]=cep[0];causal[1:size//2]=2*cep[1:size//2];causal[size//2]=cep[size//2]
    h=np.fft.irfft(np.exp(np.fft.rfft(causal)),size)[:8192]
    h[-512:]*=np.linspace(1,0,512)
    return h/max(np.linalg.norm(h),1e-20)

def render(model,voice,velocity,take):
    p=model['voices'][voice]
    rng=np.random.default_rng(sk.seed_for('FOOTWORK-CONTACT-FIT',1,voice,take))
    warp=(.965+.035*velocity)*(1+rng.uniform(-.012,.012))
    h=response(p,warp)
    # A shared response carries fresh broadband contact texture. A short pressure
    # component starts that same response coherently; no separate bass layer.
    noise=rng.normal(size=N+len(h))
    stationary=signal.fftconvolve(noise,h)[:len(noise)][len(h):]
    direct=np.pad(h,(0,max(0,N-len(h))))[:N]
    carrier=.92*stationary+.08*np.sqrt(FS*.008)*direct
    t=np.arange(N)/FS
    pars=np.array(p['envelope']);pars[:3]*=(1.04-.04*velocity)*(1+rng.uniform(-.04,.04))
    pars[3]*=1+rng.uniform(-.08,.08)
    env=lobes(t,pars)
    # Coherent contact and delayed settling share the fitted broad spectral body.
    raw=carrier*np.sqrt(np.maximum(env,0))*velocity**1.35
    out=sk.fade_tail(sk.dc_block(sk.decimate4(sk.band_limit(raw,FS)),RATE),RATE)
    return out*model.get('gain',1.)

def write(path,x):
    assert np.isfinite(x).all() and max(abs(x))<.98
    wavfile.write(path,RATE,np.rint(x*32767).astype(np.int16))
    assert np.max(abs(read(path)-x))<5e-5

def main():
    ap=argparse.ArgumentParser();ap.add_argument('refs',type=Path);ap.add_argument('old',type=Path);ap.add_argument('out',type=Path)
    args=ap.parse_args();args.out.mkdir(parents=True,exist_ok=True)
    ref=read(args.refs/'ref-1.wav');model=fit(ref)
    # One fixed gain for the entire two-voice model. No per-hit/velocity leveling.
    peak=max(max(abs(render(model,voice,v,t))) for voice in TRAIN for v in VELOCITIES for t in range(4))
    model['gain']=.82/peak
    report={'scope':'Python isolated-contact prototype only','cells':[], 'comparisons':{},
        'reference_limitations':['Screen recordings include room, codec and capture coloration.',
          'Reference 1 has decoded peaks above full scale; these are not a force calibration.',
          'References 2 and 3 show multiple dancers. References 4 and 5 have no video.',
          'Toe/heel role attribution is provisional. Holdout distances are descriptive, not realism tests.'],
        'checks':['determinism','zero velocity','finite/headroom/DC/tail','increasing velocity energy',
                  'distinct RR PCM','PCM16 save/read error below 5e-5'], 'native_gates':'unchanged/open'}
    timings=[]
    for voice,pad in [('toe',3),('heel',2)]:
        start=time.perf_counter()
        cells=[[render(model,voice,v,t) for t in range(4)] for v in VELOCITIES]
        timings.append((time.perf_counter()-start)/12)
        assert np.array_equal(cells[2][0],render(model,voice,1.,0))
        assert not np.any(render(model,voice,0.,0))
        for row in cells:
            assert len({hashlib.sha256(x.tobytes()).hexdigest() for x in row})==4
        for take in range(4):
            assert all(np.linalg.norm(cells[z][take])<np.linalg.norm(cells[z+1][take]) for z in range(2))
        for z,row in enumerate(cells):
            for take,x in enumerate(row):
                assert np.isfinite(x).all() and max(abs(x))<.98 and abs(x.mean())<.005 and x[-1]==0
                report['cells'].append({'voice':voice,'velocity':VELOCITIES[z],'take':take,**metrics(x[:5292])})
        new=cells[2][0]
        old=read(args.old/f'contact-{pad}.wav')[8*(9702+6615):8*(9702+6615)+9702]
        target,tm=crop(ref,TRAIN[voice][0])
        # Equal 120ms energy for reference/new/previous timbre comparisons. A
        # common headroom reduction preserves those equal energies if needed.
        compare=[target,new,old];length=round(.12*RATE)
        matched=[x/np.linalg.norm(x[:length]) for x in compare]
        gain=.82/max(max(abs(x)) for x in matched)
        for label,x in zip(['reference','new','previous'],matched):
            write(args.out/f'{voice}-{label}.wav',np.r_[x*gain,np.zeros(round(.22*RATE))])
        strip=np.concatenate([np.r_[x,np.zeros(round(.16*RATE))] for row in cells for x in row])
        write(args.out/f'{voice}-dynamics.wav',strip)
        saved=read(args.out/f'{voice}-dynamics.wav')
        stride=round(.4*RATE);n=len(new)
        decoded=[saved[i*stride:i*stride+n] for i in range(12)]
        assert all(np.max(abs(x-y))<5e-5 for x,y in zip(decoded,[x for row in cells for x in row]))
        assert len({hashlib.sha256(x.tobytes()).hexdigest() for x in decoded})==12
        assert all(np.linalg.norm(decoded[z*4+t])<np.linalg.norm(decoded[(z+1)*4+t]) for z in range(2) for t in range(4))
        write(args.out/f'{voice}-raw.wav',new)
        oldm=metrics(old[:length]);newm=metrics(new[:length]);refm=metrics(target)
        report['comparisons'][voice]={'reference_start_seconds':tm,'reference':refm,'new':newm,'previous':oldm,
            'held_out':[{'time_seconds':a,**metrics(crop(ref,a)[0])} for a in HELD[voice]]}
        c=report['comparisons'][voice]
        c['held_out_mean_band_js_distance']={k:float(np.mean([jensenshannon(c[k]['band_fraction'],h['band_fraction']) for h in c['held_out']])) for k in ['new','previous']}
    report['mean_render_ms']=float(np.mean(timings)*1000)
    (args.out/'model.json').write_text(json.dumps(model,indent=2)+'\n')
    (args.out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'gain':model['gain'],'mean_render_ms':report['mean_render_ms'],
        'comparisons':{v:{k:{m:round(d[m],3) if m!='band_fraction' else [round(a,3) for a in d[m]] for m in ['t50_ms','t90_ms','crest_db','band_fraction']} for k,d in c.items() if k in ['reference','new','previous']} for v,c in report['comparisons'].items()}},indent=2))

if __name__=='__main__':main()
