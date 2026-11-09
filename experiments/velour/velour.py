"""Velour R0, adapted from proto_template.py. Python hypothesis, not Kotlin DSP.

All voicing constants are shape values awaiting an audition. No reference audio is used.
Render source at 4x; run linear atmosphere at output rate; oversample nonlinear amp 4x.
"""
from pathlib import Path
import json
import time
import zipfile
import numpy as np
from scipy import signal
import snipkit as sk
import probes as pr

OUT = Path(__file__).resolve().parents[1]
RATE = sk.RATE
DEFAULTS = dict(TUNE=.5, DAMP=.7, SCATTER=.25, SPACE=.3, DRIVE=.35)

def lp(x, hz, rate=RATE, order=2):
    return signal.sosfilt(signal.butter(order, hz, fs=rate, output='sos'), x)

def hp(x, hz, rate=RATE):
    return signal.sosfilt(signal.butter(2, hz, fs=rate, btype='high', output='sos'), x)

def clean(x):
    return sk.fade_tail(hp(x-np.mean(x), 20), RATE, 12)

def render(voice, macros=None, velocity=1.):
    """Template render replacement: bounded source, no per-event normalization."""
    m = DEFAULTS | (macros or {})
    r = sk.INTERNAL
    t = np.arange(int(.85*r))/r
    pitch = 2**((m['TUNE']-.5)*12/12)
    damp = 1.5-1.15*m['DAMP']
    rng = np.random.default_rng(sk.seed_for('velour', voice, 'source-v1'))
    noise = rng.uniform(-1, 1, len(t))
    attack = 1-np.exp(-t/(.0007+.001*(1-velocity)))
    if voice == 'kick':
        f = 57*pitch
        phase = 2*np.pi*(f*t+15*pitch*.012*(1-np.exp(-t/.012)))
        x = .85*np.sin(phase)*np.exp(-6.9078*t/(.39*damp))
        x += .13*np.sin(2*np.pi*f*1.59*t)*np.exp(-6.9078*t/(.15*damp))
        x += .11*lp(noise, 2100, r)*np.exp(-t/.004)
    elif voice == 'snare':
        x = np.zeros_like(t)
        for ratio, gain, decay in [(1,.48,.23),(1.59,.23,.12),(2.14,.12,.09),(2.3,.07,.07)]:
            x += gain*np.sin(2*np.pi*178*pitch*ratio*t)*np.exp(-6.9078*t/(decay*damp))
        wire = hp(lp(noise, 6500*(.7+.3*velocity), r), 850, r)
        x += 1.05*wire*np.exp(-6.9078*t/(.18*damp))
        x += .17*lp(noise, 2800, r)*np.exp(-t/.003)
    else:
        x = hp(lp(noise, 8500, r), 3700, r)*np.exp(-6.9078*t/(.09*damp))*.43
    raw = x*attack*velocity
    # Same pipeline shape as template; omit RMS leveling so velocity stays meaningful.
    return clean(sk.decimate4(sk.band_limit(raw, r)))

def atmosphere(send, onsets, m):
    """Finite taps and finite convolution: no recirculating feedback or growth limiter."""
    n = len(send)
    samples = np.arange(n)
    t = samples/RATE
    chorus = np.zeros(n)
    for phase in (0, 2*np.pi/3, 4*np.pi/3):
        pos = samples-RATE*(.014+.0014*np.sin(2*np.pi*.32*t+phase))
        chorus += np.interp(pos, samples, send, left=0, right=0)/3
    delay = np.zeros(n)
    # 84 BPM eighth-note echoes, finite four repeats, gains strictly decreasing.
    for k in range(1,5):
        d = round(RATE*(60/84/2)*k)
        delay[d:] += .32**(k-1)*send[:-d]
    grains = np.zeros(n)
    weight = np.zeros(n)
    rng = np.random.default_rng(sk.seed_for('velour', 'grain-v1'))
    count = 0
    for onset in onsets:
        for j in range(8):
            draw, jitter, rate_draw = rng.random(3)
            if draw > (.06+.84*m['SCATTER']):
                continue
            speed = .5 if rate_draw < .24 else 1.
            length = int(RATE*(.075+.05*jitter))
            start = int((onset+.14+j*.125+.025*jitter)*RATE)
            stop = min(n,start+length)
            if stop <= start:
                continue
            window = np.hanning(length)[:stop-start]
            pos = onset*RATE+np.arange(stop-start)*speed
            grain = np.interp(pos, samples, send, left=0, right=0)
            gain = .55*.82**j
            grains[start:stop] += window*grain*gain
            weight[start:stop] += window
            count += 1
    grains /= np.maximum(1,weight)  # convex overlap prevents density multiplying peak
    wet = .14*chorus+.16*delay+.65*grains
    # Dense decaying FIR is a hall surrogate, not the pedal's algorithm or an FDN.
    rt60 = .45+2.4*m['SPACE']
    tr = np.arange(int(4*RATE))/RATE
    ir = np.random.default_rng(sk.seed_for('velour','room-v1')).normal(size=len(tr))
    ir = lp(ir, 3900)*np.exp(-6.9078*tr/rt60)
    ir[:int(.022*RATE)] = 0
    ir /= max(1, np.sum(np.abs(ir)))
    room = signal.fftconvolve(wet, ir)[:n]*12
    wet = lp(wet+(.3+1.7*m['SPACE'])*room, 4800)
    return send*0+wet*(.55+1.3*m['SPACE']), dict(grains=count, rt60=rt60)

def amp(x, drive, factor=4):
    """Generic combo surrogate. Fixed gain law; shared envelope, never peak normalize input."""
    r = RATE*factor
    up = signal.resample_poly(x, factor, 1)
    pre = hp(up, 48, r)
    gain = 1+7*drive
    z = gain*pre
    tube = np.where(z>=0, np.tanh(z), .85*np.tanh(z/.85))
    a = np.exp(-1/(.035*r))
    env = signal.lfilter([1-a], [1,-a], np.abs(tube))
    y = tube/(1+1.25*drive*env)
    y = lp(hp(y, 55, r), 5300, r, 4)
    return signal.resample_poly(y, 1, factor)[:len(x)]

def treatment(dry, send, onsets, m):
    wet, diag = atmosphere(send,onsets,m)
    air = clean(dry+wet)
    return air, clean(amp(air,m['DRIVE'])), diag

def groove(m):
    beat = 60/84
    n = int((8*beat+4)*RATE)
    dry, send = np.zeros(n), np.zeros(n)
    events = []
    for b in [0,1.75,2.5,4,5.5,6.75]: events.append(('kick', b, .9, .12))
    for b in [1,3,5,7]: events.append(('snare', b, .88, 1.))
    for b in [2.75,6.5]: events.append(('snare', b, .3, .7))
    for step in range(16): events.append(('hat', step*.5+(0.035 if step%2 else 0), .65 if step%2==0 else .42, .25))
    events.sort(key=lambda e:e[1])
    onsets=[]
    for voice,b,v,s in events:
        hit=render(voice,m,v)
        start=round(b*beat*RATE)
        dry[start:start+len(hit)] += hit
        send[start:start+len(hit)] += hit*s
        if voice=='snare': onsets.append(start/RATE)
    return dry,send,onsets

def match_group(arrays):
    """Equal whole-file RMS, common peak-safe target; same duration within a group."""
    target=min(.105,*(.92*sk.rms(a)/max(pr.peak(a),1e-12) for a in arrays))
    return [a*(target/max(sk.rms(a),1e-12)) for a in arrays],target

def main():
    audio=OUT/'audio'; audio.mkdir(exist_ok=True)
    report={'status':'Python harness, not Kotlin; unauditioned', 'defaults':DEFAULTS,
            'bpm':84,'pattern':'original two-bar demonstration, not a transcription',
            'renders':[], 'checks':{}, 'timing_ms':{}}
    start=time.perf_counter()
    dry,send,onsets=groove(DEFAULTS)
    air,driven,diag=treatment(dry,send,onsets,DEFAULTS)
    lush=DEFAULTS|dict(SCATTER=.65,SPACE=.6,DRIVE=.5)
    _,wild,_=treatment(dry,send,onsets,lush)
    report['timing_ms']['two_bar_default_and_lush']=round((time.perf_counter()-start)*1000,1)
    mixes,target=match_group([clean(dry),air,driven,wild])
    report['groove_target_rms']=target; report['default_atmosphere']=diag
    named=list(zip(['01_groove_dry','02_groove_atmosphere','03_groove_amp','04_groove_more_bloom'],mixes))
    for voice in ['kick','snare']:
        start=time.perf_counter()
        hit=np.pad(render(voice), (0,int(3.15*RATE)))
        a,b,_=treatment(hit,hit*(.12 if voice=='kick' else 1),[0],DEFAULTS)
        report['timing_ms'][voice+'_all_stages']=round((time.perf_counter()-start)*1000,1)
        group,_=match_group([hit,a,b])
        named+=list(zip([voice+'_dry',voice+'_atmosphere',voice+'_amp'],group))
    for name,x in named:
        assert np.isfinite(x).all() and pr.peak(x)<.93 and abs(pr.dc(x))<.005
        sk.write_wav(str(audio/(name+'.wav')),x)
        row=pr.report(x,RATE,name)
        row.pop('never_grows'); row.pop('later_over_attack')
        # A groove has new hits and the design deliberately has late events: monotone envelope is inappropriate.
        report['renders'].append(row)
    report['checks']['deterministic_source']=np.array_equal(render('snare'),render('snare'))
    repeat_air,repeat_amp,_=treatment(dry,send,onsets,DEFAULTS)
    report['checks']['deterministic_chain']=np.array_equal(driven,repeat_amp)
    report['checks']['silence_preserved']=pr.peak(amp(np.zeros(RATE),1))==0
    activity={}
    for key in DEFAULTS:
        out=[]
        for value in [0.,1.]:
            m=DEFAULTS|{key:value}
            hit=np.pad(render('snare',m),(0,int(3.15*RATE)))
            _,y,_=treatment(hit,hit,[0],m)
            out.append(sk.level_to(y,.05))
        activity[key]=round(pr.macro_activity(*out),3)
        assert activity[key]>.1, (key,activity[key])
    report['macro_extremes_spectral_distance_db']=activity
    # Higher-rate reference tests amp resampling/nonlinearity error, not a standalone alias-energy measurement.
    a4=amp(air,.8,4); a8=amp(air,.8,8)
    report['amp_4x_vs_8x_relative_error_db']=round(20*np.log10(sk.rms(a4-a8)/sk.rms(a8)),2)
    report['checks']['all_checks_pass']=all(report['checks'].values())
    assert report['checks']['all_checks_pass']
    pr.spectrogram_png(mixes[2],RATE,str(OUT/'spectrum.png'),'Velour: ambience into amp')
    (OUT/'report.json').write_text(json.dumps(report,indent=2))
    print(json.dumps({k:v for k,v in report.items() if k!='renders'},indent=2))

if __name__=='__main__': main()
