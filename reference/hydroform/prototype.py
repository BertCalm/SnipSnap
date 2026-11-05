"""HYDROFORM R5: accepted dynamic slope is the main path. Not Kotlin.
All model constants are proposed shape values. No randomness or physical simulation.
Run python3 prototype.py. Requires NumPy and SciPy.
"""
from pathlib import Path
import json, math, time, zipfile
import numpy as np
from scipy import signal

ROOT=Path(__file__).parent
OUT=ROOT/'dist'; AUDIO=OUT/'audio'; AUDIO.mkdir(parents=True,exist_ok=True)
RATE=44100; INTERNAL=RATE*4; SECONDS=1.0
DEFAULT={'TUNE':.5,'CHARGE':.7,'STANDOFF':.35,'YIELD':.5,'ARREST':.65}

def raw(macros=None, variant='slope-v2', rate=INTERNAL, seconds=SECONDS, voice='DOME', velocity=1.0):
    m=DEFAULT.copy()
    for k,v in (macros or {}).items():
        if k in m and np.isfinite(v): m[k]=float(np.clip(v,0,1))
    if voice not in ['DOME','BULKHEAD']: raise ValueError(voice)
    velocity=float(np.clip(velocity,0,1)) if np.isfinite(velocity) else 1.0
    hz=(49 if voice=='DOME' else 147)*2**((m['TUNE']-.5)*2)
    t=np.arange(round(seconds*rate))/rate
    # Integrated analytic pitch sweep avoids an unnecessary ODE solver.
    sweep=.018; depth=2.5
    phase=2*np.pi*hz*(t+depth*sweep*(1-np.exp(-t/sweep)))
    env=(1-np.exp(-t/(.0006*(3-2*velocity))))*np.exp(-t/.16)
    x=np.sin(phase)*env*(.6+2.6*m['CHARGE'])*(.25+.75*velocity)
    if voice=='BULKHEAD':
        # Analytic decaying partial bank: literal synthesis, analogy to a struck plate.
        # Ratios and decay times are proposed shape values, not measured steel modes.
        # One shared root plus persistent inharmonic modes distinguish it before shaping.
        modes=[(1,.85,.18),(1.593,.46,.13),(2.317,.34,.095),(3.711,.23,.07),(5.167,.16,.045)]
        onset=1-np.exp(-t/(.00025+.002*(1-velocity)))
        x=np.zeros_like(t)
        for i,(ratio,gain,tau) in enumerate(modes):
            if hz*ratio>=rate*.4: continue
            tilt=1 if i==0 else .2+.8*velocity
            phase=2*np.pi*hz*ratio*(t+.12*.009*(1-np.exp(-t/.009)))
            x+=gain*tilt*np.sin(phase)*np.exp(-t/tau)
        x*=onset*(.6+2.6*m['CHARGE'])*(.25+.75*velocity)/1.35
    if variant not in ['bypass','slope-bypass']:
        # A delayed pressure return interferes with the direct attack. Unlike phase-only
        # processing, this parallel path changes magnitude and temporal attack structure.
        # y[n] = a*x[n] + x[n-D] - a*y[n-D], |a|=.5: stable delayed all-pass.
        delay=.0005*32**m['STANDOFF']  # 0.5 to 16 ms, perceptually spaced
        wet=x.copy()
        for ratio in [1,1.41,1.89]:
            frames=max(1,round(delay*ratio*rate))
            # Each residue modulo D is one first-order recurrence. Reshape keeps cost
            # linear in sample count instead of using a dense D-tap filter of zeros.
            n=len(wet); padded=np.pad(wet,(0,(-n)%frames)).reshape(-1,frames)
            wet=signal.lfilter([.5,1],[1,.5],padded,axis=0).reshape(-1)[:n]
        # Keep the settled root direct; return lives in the first ~100 ms. A delayed
        # front (0 to 24 ms) makes STANDOFF a body-to-impact distance, not a phase knob.
        front=round(.024*m['STANDOFF']*rate)
        if front: wet=np.pad(wet,(front,0))[:len(wet)]
        x=x + .95*wet*np.exp(-t/.085)
    # Normalize derivative by nominal f0 so moving TUNE alone does not multiply detector gain.
    d=np.abs(np.diff(x,prepend=0))*rate/(2*np.pi*hz)
    b,a=signal.butter(1,1800,fs=rate)
    detector=signal.lfilter(b,a,d)
    threshold=.15+1.65*m['YIELD']
    activation=np.clip((detector-threshold)/(.25+threshold),0,1)
    activation=activation*activation*(3-2*activation)
    if variant=='amplitude': activation=np.full_like(x,.4)
    drive=1+2*m['CHARGE']
    brittle=np.tanh(drive*x*1.5)
    folded=np.sin(drive*x*(1.2+1.5*activation))
    y=(1-.65*activation)*brittle+.65*activation*folded
    if variant in ['slope-v2','slope-static','slope-bypass']:
        # Accepted R4 dynamic response. Smooth compression engages at middle slopes;
        # R3's dead zone and limited blend could leave most of the body almost unchanged.
        threshold=.15+1.65*m['YIELD']
        activation=detector**2/(detector**2+threshold**2)
        if variant=='slope-static':
            # Match mean activation weighted by source energy. The A/B difference is
            # temporal slope response, not an arbitrarily different static drive setting.
            mean=float(np.sum(activation*x*x)/max(np.sum(x*x),1e-20))
            activation=np.full_like(x,mean)
        folded=np.sin(drive*x*(1+4*activation))
        y=(1-.9*activation)*brittle+.9*activation*folded
    # Arrest changes termination time and ramp width independently of the clipping ceiling.
    # Exponential timing puts changes in the active body, not only its quiet tail.
    arrest=.75*m['ARREST']  # R1's .75 is R2's 1.0, including the ramp
    stop=.65*(.045/.65)**arrest  # open 650 ms -> short ~88 ms
    ramp=.0015+.020*(1-arrest)**2
    u=np.clip((t-(stop-ramp))/ramp,0,1)
    y*=.5*(1+np.cos(np.pi*u))
    return np.clip(y,-.94,.94)

def condition(x, rate=INTERNAL):
    x=signal.sosfilt(signal.butter(8,20000,fs=rate,output='sos'),x)
    # Same reference resampler at every factor, zero-phase FIR; not Kotlin Dsp.decimate.
    x=signal.resample_poly(x,1,rate//RATE,window=('kaiser',10))
    x=x-np.mean(x)
    alpha=1/(1+2*np.pi*20/RATE)
    return signal.lfilter([alpha,-alpha],[1,-alpha],x)

def weight(x):
    # Explicit proxy: high-pass weighted RMS, not LUFS or SnipSnap Loudness.of.
    return float(np.sqrt(np.mean(signal.sosfilt(signal.butter(2,100,fs=RATE,btype='high',output='sos'),x)**2)))

def match(x):
    gain=min(.11/max(weight(x),1e-12),.92/max(np.max(np.abs(x)),1e-12))
    y=x*gain
    n=round(.004*RATE); y[-n:]*=np.linspace(1,0,n)
    return y, gain

def metrics(x):
    p=float(np.max(np.abs(x))); r=float(np.sqrt(np.mean(x*x)))
    f,ps=signal.welch(x,RATE,nperseg=4096)
    energy=np.cumsum(x*x); end=int(np.searchsorted(energy,energy[-1]*.999))/RATE
    # Pitch from the low-passed settled body, not the swept onset.
    s=signal.sosfilt(signal.butter(4,160,fs=RATE,output='sos'),x)[round(.09*RATE):round(.25*RATE)]
    ac=signal.correlate(s,s,mode='full',method='fft')[len(s)-1:]
    lo,hi=round(RATE/110),round(RATE/23)
    peaks,_=signal.find_peaks(ac[lo:hi]); lag=int(peaks[np.argmax(ac[lo:hi][peaks])]+lo) if len(peaks) else 0
    pitch=RATE/lag if lag else 0
    return {'finite':bool(np.isfinite(x).all()),'peak':p,'dc':float(np.mean(x)),
      'rms':r,'weighted_rms':weight(x),'crest_db':20*math.log10(p/max(r,1e-12)),
      'centroid_hz':float(np.sum(f*ps)/max(np.sum(ps),1e-20)),
      'energy_99_9_s':end,'energy_90_s':int(np.searchsorted(energy,energy[-1]*.9))/RATE,'body_pitch_hz':pitch}

def wav(path,x):
    from scipy.io import wavfile
    wavfile.write(path,RATE,(np.clip(x,-1,1)*32767).astype(np.int16))

def main():
    manifest=[]; reports=[]; times=[]
    def add(id,title,group,m=None,variant='slope-v2',description='',voice='DOME',velocity=1.0,rate=INTERNAL):
        start=time.perf_counter(); r=raw(m,variant,voice=voice,velocity=velocity,rate=rate); y=condition(r,rate)
        times.append((time.perf_counter()-start)*1000)
        prefix='r4-' if group=='slope-study' else 'r5-'
        y,gain=match(y); wav(AUDIO/(prefix+id+'.wav'),y)
        probe=metrics(y); reports.append({'id':id,**probe})
        peaks=np.max(np.abs(y[:len(y)//180*180].reshape(180,-1)),axis=1).tolist()
        manifest.append({'id':id,'title':title,'group':group,'file':'audio/'+prefix+id+'.wav','voice':voice,'velocity':velocity,'internal_factor':rate//RATE,'macros':{**DEFAULT,**(m or {})},'variant':variant,'description':description,'waveform':peaks,'metrics':probe,'gain':gain})
        return y
    for id,variant,title,desc in [('amplitude','slope-static','A · Frozen response','Source-energy-weighted average activation, held fixed.'),('derivative','slope-v2','B · Dynamic slope','Accepted dynamic response; now the main path.'),('bypass','slope-bypass','C · No all-pass','Accepted dynamic response with the dispersion cascade bypassed.')]:
        add('compare-'+id,title,'compare',variant=variant,description=desc)
    for macro in DEFAULT:
        for v in [0,.25,.5,.75,1]: add(f'{macro.lower()}-{v:g}',f'{macro} {v:.2f}','sweep',{macro:v})
    for id,title,m in [('pressure','Pressure vessel',{'CHARGE':1,'YIELD':.2,'ARREST':.4}),('cut','Die cut',{'ARREST':1,'YIELD':.3}),('sub','Low dome',{'TUNE':0,'CHARGE':.5}),('brittle','Brittle face',{'TUNE':.8,'YIELD':1,'CHARGE':.9}),('soft','Soft contact',{'CHARGE':.15,'YIELD':.8}),('dispersion','Dispersed strike',{'STANDOFF':1,'ARREST':.25})]:
        add(id,title,'study',m)
    for voice in ['DOME','BULKHEAD']:
        key=voice.lower()
        add(key+'-default',voice+' · Default','voice',voice=voice,description='Same CHARGE, STANDOFF, YIELD and approved ARREST law. '+('49 Hz swept pressure burst.' if voice=='DOME' else '147 Hz root with four inharmonic partials.'))
        for v in [.25,.5,.75]: add(f'{key}-pitch-{v:g}',f'{voice} · TUNE {v:g}','pitch',{'TUNE':v},voice=voice,description=f'Root {(49 if voice=="DOME" else 147)*2**((v-.5)*2):.1f} Hz; inharmonic voice pitch remains a listening judgment.')
        for v in [.25,.6,1]: add(f'{key}-velocity-{v:g}',f'{voice} · Velocity {v:g}','velocity',voice=voice,velocity=v,description='Matched output level: judge contact and distortion changes, not playback volume.')
        for pair in [('CHARGE','YIELD'),('STANDOFF','ARREST')]:
            for a,b in [(0,0),(0,1),(1,0),(1,1)]:
                add(f'{key}-pair-{pair[0].lower()}-{a}-{b}',f'{voice} · {pair[0]} {a} / {pair[1]} {b}','pair',{pair[0]:a,pair[1]:b},voice=voice,description='Two-control corner. Other macros remain at default.')
    for macro in DEFAULT:
        for v in [0,.25,.5,.75,1]: add(f'bulk-{macro.lower()}-{v:g}',f'BULKHEAD · {macro} {v:.2f}','bulk-sweep',{macro:v},voice='BULKHEAD')
    for voice in ['DOME','BULKHEAD']:
        key=voice.lower()
        for variant,title,desc in [('derivative','R3 reference','Historical weak-slope voice; retained for comparison.'),('slope-static','A · Frozen response','Audition reference with source-energy-weighted mean activation held fixed.'),('slope-v2','B · Dynamic slope','Accepted response; the main voice, sweeps and HOLD now use this path.')]:
            add(f'{key}-{variant}',voice+' · '+title,'slope-study',variant=variant,voice=voice,description=desc)
        for v in [0,.5,1]:
            add(f'{key}-slope-yield-{v:g}',f'{voice} · Dynamic slope / YIELD {v:g}','slope-study',{'YIELD':v},variant='slope-v2',voice=voice,description='Accepted response. Source, dispersion and ARREST laws stay at R3.')
        for tune in [0,.5,1]:
            for factor in [4,16]:
                add(f'{key}-hot-{tune:g}-{factor}',f'{voice} · TUNE {tune:g} / {factor}×','hot-corner',{'CHARGE':1,'YIELD':0,'TUNE':tune},voice=voice,rate=RATE*factor,description='CHARGE 1, YIELD 0. Compare 4× against a 16× reference; matched output-level proxy, not an alias-isolated test.')
    # Genuine periodic excitation before processing; warm-up, kept period and continuation.
    period=2*INTERNAL; seq=np.zeros(period*5); hit=raw()
    for i in range(0,len(seq),INTERNAL//4):
        n=min(len(hit),len(seq)-i); seq[i:i+n]+=hit[:n]
    seq=condition(seq); start=6*RATE; count=2*RATE
    loop=seq[start:start+count].copy(); gain=min(.11/max(weight(loop),1e-12),.92/max(abs(loop)))
    loop*=gain; wav(AUDIO/'r5-hold.wav',loop)
    before=seq[start-1024:start]; end=seq[start+count-1024:start+count]
    continuation=seq[start+count:start+2*count]
    seam=float(np.sum((before-end)**2)/max(np.sum(before**2),1e-20))
    continuation_error=float(np.sum((seq[start:start+count]-continuation)**2)/max(np.sum(continuation**2),1e-20))
    manifest.append({'id':'hold','title':'HOLD · Eight impacts','group':'hold','file':'audio/r5-hold.wav','description':'120 BPM assumption · eighth-note strikes · 2 s loop. Accepted dynamic response.','metrics':metrics(loop),'waveform':np.max(abs(loop).reshape(180,490),axis=1).tolist()})
    # Sensitivity check against 16x. Delay alignment includes the causal Butterworth path.
    reference=[]
    for voice,label,m in [(v,l,m) for v in ['DOME','BULKHEAD'] for l,m in [('default',{}),('hard corner',{'CHARGE':1,'YIELD':0,'ARREST':1,'TUNE':1})]]:
        a=condition(raw(m,voice=voice)); b=condition(raw(m,rate=RATE*16,voice=voice),RATE*16)
        corr=signal.correlate(a[:20000],b[:20000],method='fft'); lag=int(np.argmax(corr)-(20000-1))
        aa=a[max(lag,0):len(a)+min(lag,0)]; bb=b[max(-lag,0):len(b)-max(lag,0)]
        gain=float(np.dot(aa,bb)/np.dot(bb,bb))
        error=20*math.log10(max(np.linalg.norm(aa-gain*bb),1e-20)/np.linalg.norm(aa))
        reference.append({'case':voice+' '+label,'aligned_residual_db':error,'lag_frames':lag,'note':'4x versus 16x residual includes filter and derivative approximation differences; not an aliasing bound.'})
    assert all(r['finite'] and r['peak']<=.920001 and abs(r['dc'])<.005 for r in reports)
    for voice in ['DOME','BULKHEAD']:
        assert np.array_equal(raw(voice=voice),raw(voice=voice)),'determinism failed'
    assert seam<1e-3 and continuation_error<1e-3
    activity={}
    for macro in DEFAULT:
        a=match(condition(raw({macro:0})))[0]; b=match(condition(raw({macro:1})))[0]
        activity[macro]=float(np.sqrt(np.mean((a-b)**2)))
        assert activity[macro]>.001,macro
    # Meaningful controls need features beyond raw sample differences: phase rotation alone
    # can pass a waveform-distance test while scarcely changing the perceived sound.
    controls={}
    for macro in ['STANDOFF','ARREST']:
        entries=[s for s in manifest if s['group']=='sweep' and s['id'].startswith(macro.lower()+'-')]
        features=[]; envelopes=[]
        for s in entries:
            y=match(condition(raw(s['macros'])))[0]
            f,p=signal.welch(y[:round(.12*RATE)],RATE,nperseg=2048)
            bands=[]
            for lo,hi in [(25,80),(80,200),(200,600),(600,2000),(2000,10000)]:
                bands.append(float(np.sum(p[(f>=lo)&(f<hi)])))
            v=np.array(bands);v/=max(v.sum(),1e-20)
            envelope=np.sqrt(np.mean(y[:44100].reshape(100,441)**2,axis=1))
            envelope/=max(np.linalg.norm(envelope),1e-20)
            features.append(v);envelopes.append(envelope)
        controls[macro]={'steps':[{'value':s['macros'][macro],'energy_90_s':s['metrics']['energy_90_s'],'centroid_hz':s['metrics']['centroid_hz']} for s in entries],
          'adjacent_attack_band_l1':[float(np.sum(abs(a-b))) for a,b in zip(features,features[1:])],
          'adjacent_envelope_distance':[float(np.linalg.norm(a-b)) for a,b in zip(envelopes,envelopes[1:])],
          'note':'Feature differences are audibility proxies, not a listening verdict.'}
    arrest90=[s['energy_90_s'] for s in controls['ARREST']['steps']]
    assert arrest90[0]>3*arrest90[-1],arrest90
    assert all(a>1.1*b for a,b in zip(arrest90,arrest90[1:])),arrest90
    assert min(controls['STANDOFF']['adjacent_attack_band_l1'])>.05
    # Preserve R0 samples without mislabelling their metrics as R2 renders.
    from scipy.io import wavfile
    for macro in ['standoff','arrest']:
        for value in ['0','0.5','1']:
            path=AUDIO/'r0'/f'{macro}-{value}.wav'
            if path.exists():
                _,pcm=wavfile.read(path);y=pcm.astype(float)/32768
                manifest.append({'id':f'r0-{macro}-{value}','title':f'R0 · {macro.upper()} {value}','group':'legacy','file':f'audio/r0/{macro}-{value}.wav','metrics':metrics(y)})
    bulk_activity={}
    for macro in DEFAULT:
        a=match(condition(raw({macro:0},voice='BULKHEAD')))[0];b=match(condition(raw({macro:1},voice='BULKHEAD')))[0]
        bulk_activity[macro]=float(np.sqrt(np.mean((a-b)**2)))
        assert bulk_activity[macro]>.001,macro
    slope_references=[]
    for voice in ['DOME','BULKHEAD']:
        for label,m in [('default',{}),('hard corner',{'CHARGE':1,'YIELD':0,'ARREST':1,'TUNE':1})]:
            a=condition(raw(m,voice=voice,variant='slope-v2'));b=condition(raw(m,rate=RATE*16,voice=voice,variant='slope-v2'),RATE*16)
            lag=int(np.argmax(signal.correlate(a[:20000],b[:20000],method='fft'))-19999)
            aa=a[max(lag,0):len(a)+min(lag,0)];bb=b[max(-lag,0):len(b)-max(lag,0)]
            gain=float(np.dot(aa,bb)/np.dot(bb,bb))
            residual=20*math.log10(max(np.linalg.norm(aa-gain*bb),1e-20)/np.linalg.norm(aa))
            slope_references.append({'case':voice+' '+label,'aligned_residual_db':residual,'note':'Not an alias bound; includes filter and discrete derivative differences.'})
    report={'revision':'R5','accepted_slope_reference_comparison':slope_references,'heard_ledger':'Josh heard added value from the R4 dynamic response and authorized promotion. New hot-corner 4x/16x studies await his listen.','implementation':'Python / NumPy / SciPy, not Kotlin; not heard by the author','renders':reports,'control_features':controls,'bulk_macro_endpoint_difference_rms':bulk_activity,
      'deterministic':True,'macro_endpoint_difference_rms':activity,'hold_seam_error':seam,'hold_continuation_error':continuation_error,
      'reference_comparison':reference,'median_render_ms':float(np.median(times)),'max_render_ms':max(times),
      'leveling':'100 Hz second-order high-pass weighted RMS target 0.11, subject to 0.92 peak cap; not LUFS or SnipSnap loudness.',
      'limitations':['Shape constants await listening.','Python timings are not Android/Kotlin timings.','No alias-free claim.','HOLD fixed 120 BPM assumption; no host tempo integration.','Body pitch estimator is diagnostic, not a tuning gate.']}
    (OUT/'manifest.json').write_text(json.dumps(manifest)); (OUT/'report.json').write_text(json.dumps(report,indent=2))
    with zipfile.ZipFile(OUT/'hydroform-prototype.zip','w',zipfile.ZIP_DEFLATED) as z:
        z.write(__file__,'prototype.py')
        for f in [OUT/'report.json',OUT/'manifest.json',*[OUT/s['file'] for s in manifest]]: z.write(f,str(f.relative_to(OUT)))
        for name in ['README.md','requirements.txt']: z.write(ROOT/name,name)
    print(json.dumps({k:v for k,v in report.items() if k!='renders'},indent=2))

if __name__=='__main__': main()
