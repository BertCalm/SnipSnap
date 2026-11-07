"""Afterimage R2 diagnostic candidate. Adapted from luthier proto_template.py.
Python/NumPy/SciPy audition harness, not Kotlin or a calibrated hardware emulation.
All timbre constants are proposed shape values. No original-instrument samples.
Run: python prototype.py [output_directory]
"""
from __future__ import annotations
import base64, functools, html, json, platform, sys, time
from pathlib import Path
import numpy as np
from scipy import signal
from scipy.ndimage import gaussian_filter1d
import scipy
import snipkit as sk
import probes as pr

RATE, INTERNAL, SECONDS = sk.RATE, sk.INTERNAL, 4.0
PROFILES = {
 'bass': dict(attack=.006, decay=.28, sustain=.42, gate=1.15, release=.28, cutoff=650, sweep=1900, sub=.24, capture=(.50,.82)),
 'pluck': dict(attack=.004, decay=.16, sustain=0., gate=.75, release=.18, cutoff=1600, sweep=5800, sub=.10, capture=(.22,.46)),
 'pad': dict(attack=.32, decay=.7, sustain=.75, gate=2., release=.7, cutoff=2200, sweep=1800, sub=.14, capture=(.48,.98)),
}

def hz_for(midi): return 440. * 2 ** ((midi-69)/12.)
def blep(p, dt):
    y=np.zeros_like(p); a=p<dt; z=p[a]/dt; y[a]=z+z-z*z-1
    a=p>1-dt; z=(p[a]-1)/dt; y[a]=z*z+z+z+1
    return y

def oscillator(hz,t,rate,sub=.14):
    # Saw and pulse share one clock; sub is an exact octave division.
    p=np.mod(t*hz+.13,1.); dt=hz/rate
    saw=2*p-1-blep(p,dt)
    width=.43+.065*np.sin(2*np.pi*.31*t)
    pulse=np.where(p<width,1.,-1.)+blep(p,dt)-blep(np.mod(p-width,1),dt)
    ps=np.mod(t*hz*.5+.065,1.); ds=dt*.5
    square=np.where(ps<.5,1.,-1.)+blep(ps,ds)-blep(np.mod(ps-.5,1),ds)
    return .52*saw+.31*pulse+sub*square

def envelope(t,p):
    a=p['attack']; env=np.minimum(t/a,1.)
    after=t>=a
    env[after]=p['sustain']+(1-p['sustain'])*np.exp(-(t[after]-a)/p['decay'])
    end=p['sustain']+(1-p['sustain'])*np.exp(-(p['gate']-a)/p['decay'])
    release=t>=p['gate']; env[release]=end*np.exp(-(t[release]-p['gate'])/p['release'])
    return env

def lowpass(x,cutoff,rate):
    # Two biquads give a four-pole response; this is not a transistor ladder
    # or a circuit fit. Updating every 64 frames (~0.36 ms) follows the envelope.
    y=x.copy()
    for q in (.707,1.10):
        state=np.zeros(2); out=np.empty_like(y)
        for start in range(0,len(y),64):
            end=min(start+64,len(y)); fc=float(cutoff[(start+end-1)//2])
            w=2*np.pi*fc/rate; c=np.cos(w); alpha=np.sin(w)/(2*q)
            b=np.array([(1-c)/2,1-c,(1-c)/2])/(1+alpha)
            a=np.array([1.,-2*c/(1+alpha),(1-alpha)/(1+alpha)])
            out[start:end],state=signal.lfilter(b,a,y[start:end],zi=state)
        y=out
    return y

@functools.lru_cache(maxsize=40)
def source(voice='pad',midi=48,color=.5,chorus=.45,velocity=.8,rate=INTERNAL,seconds=SECONDS):
    p=PROFILES[voice]; t=np.arange(round(seconds*rate))/rate; hz=hz_for(midi)
    x=oscillator(hz,t,rate,p['sub'])
    # Gentle HPF + envelope-driven four-pole lowpass. Velocity changes brightness.
    x=signal.sosfilt(signal.butter(1,35,fs=rate,btype='highpass',output='sos'),x)
    contour=np.exp(-t/(.23 if voice!='pad' else 1.2))
    cutoff=np.clip((p['cutoff']+p['sweep']*contour*(.35+.8*velocity))*2**(3*(color-.5)),70,14000)
    x=lowpass(x,cutoff,rate)*envelope(t,p)*(.5+.5*velocity)
    # Short modulated delays preserve movement in mono; no stereo widening claim.
    grid=np.arange(len(x)); d1=(.008+.0025*chorus*np.sin(2*np.pi*.63*t))*rate
    d2=(.012+.0030*chorus*np.sin(2*np.pi*.83*t+1.7))*rate
    dark=signal.sosfilt(signal.butter(2,9000,fs=rate,output='sos'),x)
    delayed=(np.interp(grid-d1,grid,dark,left=0)+np.interp(grid-d2,grid,dark,left=0))*.5
    post=(1-.45*chorus)*x+.65*chorus*delayed
    return x,post

def capture(x,voice,midi,rate=INTERNAL):
    start,end=PROFILES[voice]['capture']; start=round(start*rate)
    # Round the circular length near an integer number of DCO periods. A short
    # splice joins the captured history without a hard discontinuity.
    period=2*rate/hz_for(midi)  # include the octave sub in the repeating waveform
    core=round(round((end*rate-start)/period)*period)
    fade=min(round(.012*rate),core//4)
    segment=x[start:start+core+fade].copy()
    if voice in ('bass','pluck'):
        # Remove the slow amplitude contour from the captured history. Otherwise
        # each wrap restarts a little note, especially at half playback speed.
        # Measure well below audio rate so the octave sub and chorus survive.
        block=max(1,round(rate*.0025)); count=len(x)//block
        centers=(np.arange(count)+.5)*block
        energy=np.mean(x[:count*block].reshape(count,block)**2,axis=1)
        slow=np.sqrt(gaussian_filter1d(energy,16,mode='nearest'))
        local=np.interp(start+np.arange(len(segment)),centers,slow)
        target=float(np.median(local))
        gain=np.clip(target/np.maximum(local,1e-8),1/3,3)
        corrected=segment*gain
        # One attenuation-only budget for the complete captured buffer, not
        # per-grain normalization. Neither its RMS nor its peak may increase.
        budget=min(1.,sk.rms(segment)/max(sk.rms(corrected),1e-12),
                   np.max(np.abs(segment))/max(np.max(np.abs(corrected)),1e-12))
        segment=corrected*budget
    out=segment[:core].copy()
    w=.5-.5*np.cos(np.pi*np.arange(fade)/fade)
    out[:fade]=segment[core:core+fade]*(1-w)+segment[:fade]*w
    return out,(start+core+fade)/rate

def read_loop(buf,pos):
    pos=np.mod(pos,len(buf)); i=pos.astype(np.int64); frac=pos-i
    return buf[i]*(1-frac)+buf[(i+1)%len(buf)]*frac

def frozen_capture(x,voice,midi,rate=INTERNAL):
    """Freeze a phase-folded waveform, not the note's amplitude/timbre trajectory.

    This is a different musical behavior from a tape micro-loop. Four settled
    sub periods become one periodic table. Slow filter-envelope and chorus motion
    are intentionally frozen for the diagnostic; the pad retains its live history.
    """
    period=2*rate/hz_for(midi)
    _,ready=capture(x,voice,midi,rate)  # retain R1's capture completion time
    count=4; start=ready*rate-count*period
    phases=np.arange(4096)/4096
    positions=start+np.arange(count)[:,None]*period+phases[None,:]*period
    folded=np.mean(np.interp(positions,np.arange(len(x)),x),axis=0)
    spectrum=np.fft.rfft(folded); spectrum[0]=0
    # Band-limit the periodic table before resynthesis, including upward clock ratios.
    highest=min(len(spectrum)-1,int(14000/(hz_for(midi)/2)))
    spectrum[highest+1:]=0
    buf=np.fft.irfft(spectrum,n=4096)
    # Single attenuation-only capture budget; never normalize individual cycles.
    segment=x[max(0,int(start)):min(len(x),int(ready*rate))]
    budget=min(1.,sk.rms(segment)/max(sk.rms(buf),1e-12),
               np.max(np.abs(segment))/max(np.max(np.abs(buf)),1e-12))
    return buf*budget,ready

def frozen_read(buf,frames,ratio,hz,rate=INTERNAL):
    # Table length is arbitrary. Exact musical pitch follows the known sub clock.
    return read_loop(buf,np.arange(frames)*len(buf)*(hz/2)*ratio/rate)

def memory_read(buf,frames,mode,ratio,hz,rate=INTERNAL,stretch=.13):
    if mode=='tape': return read_loop(buf,np.arange(frames)*ratio)
    # Windowed overlap-add: read speed sets pitch, cursor progression sets stretch.
    # Grain starts align to sub-oscillator periods so neither the root nor its
    # octave-down companion gets an arbitrary polarity flip at a grain boundary.
    period=2*rate/hz
    grain=round(max(4,round(.105*hz/2))*period); hop=max(1,grain//4)
    win=np.hanning(grain); out=np.zeros(frames+grain); weights=np.zeros_like(out)
    for start in range(-grain+hop,frames,hop):
        # Snap the analysis position while retaining the output grain's phase.
        # Simply rounding start*stretch resets phase at every hop and detunes
        # steady tones toward the grain repetition rate.
        cursor=round((start*stretch-start*ratio)/period)*period+start*ratio
        grain_audio=read_loop(buf,cursor+np.arange(grain)*ratio)
        lo=max(0,start); hi=min(len(out),start+grain); a=lo-start; b=hi-start
        out[lo:hi]+=grain_audio[a:b]*win[a:b]; weights[lo:hi]+=win[a:b]
    return out[:frames]/np.maximum(weights[:frames],1e-9)

@functools.lru_cache(maxsize=12)
def space_ir(rate,clock):
    # Feedforward diffusion has a finite tail and no recirculating feedback.
    # L1 normalization bounds peak gain by 1. This is a small texture, not a MOOD reverb model.
    seconds=.55/max(.5,abs(clock)); n=round(seconds*rate)
    rng=np.random.default_rng(sk.seed_for('afterimage','diffusion',rate))
    ir=np.zeros(n); count=96
    positions=np.linspace(.018*rate,n-round(.002*rate),count).astype(int)
    positions+=rng.integers(-round(.001*rate),round(.001*rate)+1,count)
    ir[positions]=rng.choice([-1.,1.],count)*np.exp(-5*positions/n)
    ir=signal.sosfilt(signal.butter(1,5000,fs=rate,output='sos'),ir)
    return ir/max(np.sum(np.abs(ir)),1e-12)

def render(voice='pad',midi=48,mode='stretch',capture_at='post',color=.5,chorus=.45,memory=.65,clock=1.,space=.25,velocity=.8,rate=INTERNAL,seconds=SECONDS,component='mix'):
    pre,dry=source(voice,midi,color,chorus,velocity,rate,seconds)
    if mode=='dry': return dry.copy()
    capture_source=pre if capture_at=='pre' else dry
    freeze=voice in ('bass','pluck')
    buf,ready=(frozen_capture if freeze else capture)(capture_source,voice,midi,rate)
    onset=round(ready*rate); n=len(dry)-onset; hz=hz_for(midi)
    tail=frozen_read(buf,n,clock,hz,rate) if freeze else memory_read(buf,n,mode,clock,hz,rate)
    t=np.arange(n)/rate
    attack=np.minimum(t/(.16 if freeze else .045),1); release=np.minimum(np.maximum((n/rate-t)/.45,0),1)
    tail*=np.sin(attack*np.pi/2)**2*np.sin(release*np.pi/2)**2*np.exp(-t/3.2)
    wet=np.zeros_like(dry); wet[onset:]=tail
    if space:
        diffuse=signal.fftconvolve(wet,space_ir(rate,clock))[:len(wet)]
        wet=(1-.25*space)*wet+space*diffuse
    if component=='memory': return wet
    # Unity dry anchor; MEMORY adds captured sustain. No isolated-tail normalization.
    return dry+memory*wet

def condition(raw,rate=INTERNAL):
    x=sk.band_limit(raw,rate)
    if rate==INTERNAL: x=sk.decimate4(x)
    else: x=signal.resample_poly(x,RATE,rate)
    x=x-x.mean(); alpha=(1/(2*np.pi*20))/(1/(2*np.pi*20)+1/RATE)
    # Same one-pole DC equation as snipkit, evaluated by SciPy's compiled filter.
    x=signal.lfilter([alpha,-alpha],[1,-alpha],x)
    return sk.fade_tail(x,RATE,12)

def level(x,target=.10):
    gain=min(target/max(pr.rms(x),1e-12),.95/max(pr.peak(x),1e-12))
    return x*gain,gain

def tone_peak(x,rate,expected=None):
    # Expected-partial tracker, NOT a claim about perceived pitch of sub-rich sounds.
    n=2**int(np.ceil(np.log2(len(x)*4))); mag=np.abs(np.fft.rfft(x*np.hanning(len(x)),n))
    freqs=np.fft.rfftfreq(n,1/rate)
    sel=(freqs>20)&(freqs<4000) if expected is None else (freqs>expected*.90)&(freqs<expected*1.10)
    indexes=np.flatnonzero(sel); k=int(indexes[np.argmax(mag[sel])]); logs=np.log(np.maximum(mag[k-1:k+2],1e-30))
    d=.5*(logs[0]-logs[2])/(logs[0]-2*logs[1]+logs[2])
    return (k+d)*rate/n

def stats(x):
    f,p=signal.welch(x,RATE,nperseg=8192)
    return dict(finite=bool(np.isfinite(x).all()),peak=pr.peak(x),rms=pr.rms(x),dc=pr.dc(x),centroid_hz=pr.spectral_centroid(x),energy_above_19k_ratio=float(p[f>19000].sum()/max(p.sum(),1e-30)))
