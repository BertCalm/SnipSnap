"""One-axis TINE sound-design options, Python harness, not Kotlin.

Adapted from the Luthier prototype and approved musicality renderer. Preserve
the approved 5.96 upper-mode ratio and the exact fifteen-mechanism contacts.
One constant gain per option matches RMS over ACTIVE eight seconds only.
"""
from copy import deepcopy
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
from time import perf_counter
import wave

import numpy as np
from scipy import signal

OUT=Path(__file__).parent
APPROVED=(Path(__file__).resolve().parent.parent / 'musicality')
spec=importlib.util.spec_from_file_location('approved_accord',APPROVED/'render.py')
approved=importlib.util.module_from_spec(spec)
spec.loader.exec_module(approved)
sk=approved.sk
RATE=sk.INTERNAL
MIDI=48
HZ=440*2**((MIDI-69)/12)
START=4.
EXCITE=8.
RELEASE=2.2
PACE=2.
BASE=deepcopy(approved.CONFIGS['tine'])
WINDOWS=[.75,1.5,2.75,3.5,4.75,5.5,6.75,7.25]


def configs():
    soft=deepcopy(BASE);soft['width_ms']=3.2
    bright=deepcopy(BASE)
    bright['tongue']=[(1.,1.,.83),(2.,.85,.40),(3.,.50,.24),(5.96,1.50,.26)]
    long=deepcopy(BASE)
    for bank in ['tongue','body']:
        long[bank]=[(ratio,gain,t60*3.) for ratio,gain,t60 in long[bank]]
    return {'soft_attack':soft,'bright_edge':bright,'long_ring':long}


def read_wav(path):
    with wave.open(str(path)) as w:
        assert w.getframerate()==sk.RATE and w.getnchannels()==1 and w.getsampwidth()==2
        pcm=w.readframes(w.getnframes())
        return np.frombuffer(pcm,dtype='<i2').astype(float)/32768.,pcm


def force_for(cfg,events):
    n=round((EXCITE+RELEASE)*RATE)
    force=np.zeros(n)
    kernels={}
    for direction in [-1,1]:
        width=max(4,round(cfg['width_ms']*(1. if direction>0 else 1.12)*.001*RATE))
        pulse=np.hanning(width)
        kernels[direction]=pulse/pulse.sum()
    # TC remains the start of the force kernel. Contact width changes its
    # pressure profile; no crossing is snapped, merged or moved.
    kept=events[(events[:,0]>=START)&(events[:,0]<START+EXCITE)]
    for tc,mechanism,direction,speed in kept:
        position=(tc-START)*RATE
        index=int(position);fraction=position-index
        pulse=kernels[int(direction)]*np.sqrt(min(speed/.8,2.))/15
        for shift,weight in [(0,1-fraction),(1,fraction)]:
            end=min(n,index+shift+len(pulse))
            force[index+shift:end]+=pulse[:end-index-shift]*weight
    t=np.arange(n)/RATE
    gate=np.ones(n)
    zone=t>EXCITE-.060
    gate[zone]=np.maximum(0.,.5+.5*np.cos(np.pi*np.minimum((t[zone]-EXCITE+.060)/.060,1.)))
    return sk.band_limit(force*gate,RATE,cutoff_hz=10000),kept


def render(cfg,events,target_rms):
    force,kept=force_for(cfg,events)
    raw=sk.modal_ring(force,HZ,cfg['tongue'],RATE,compensate=True)
    raw+=cfg['body_gain']*sk.modal_ring(force,HZ,cfg['body'],RATE,compensate=True)
    conditioned=sk.dc_block(sk.decimate4(sk.band_limit(raw,RATE)))
    gain=target_rms/sk.rms(conditioned[:round(EXCITE*sk.RATE)])
    out=sk.fade_tail(conditioned*gain)
    head=round(.003*sk.RATE)
    out[:head]*=np.linspace(0,1,head)
    if not np.isfinite(out).all() or np.max(np.abs(out))>=.98:
        raise ValueError('Output needs sound-design headroom; no limiter is applied')
    return out,dict(raw_peak=float(np.max(np.abs(raw))),normalization_gain=float(gain),
                    event_count=int(len(kept)),event_history_sha256=hashlib.sha256(kept.tobytes()).hexdigest(),
                    max_pole_radius=float(max(np.exp(-6.9078/(m[2]*RATE)) for m in cfg['tongue']+cfg['body'])))


def metrics(out):
    active=out[:round(EXCITE*sk.RATE)]
    f,p=signal.welch(active,sk.RATE,nperseg=32768)
    energy=p.sum()
    root=(f>=HZ*.94)&(f<=HZ*1.06)
    upper=f>HZ*1.7
    envelope=approved.pr.envelope(out,sk.RATE)
    active_blocks=round(EXCITE/.01)
    relative=np.maximum(envelope,1e-20)/max(envelope[:active_blocks].max(),1e-20)
    later=np.where(relative[active_blocks:]>10**(-40/20))[0]
    return dict(seconds=len(out)/sk.RATE,finite=bool(np.isfinite(out).all()),
                peak=float(np.max(np.abs(out))),dc=float(out.mean()),
                active_rms=sk.rms(active),whole_file_rms=sk.rms(out),
                active_spectral_centroid_hz=float(np.sum(f*p)/energy),
                active_root_band_energy_share=float(p[root].sum()/energy),
                active_upper_energy_share=float(p[upper].sum()/energy),
                active_upper_to_root_db=float(10*np.log10(p[upper].sum()/p[root].sum())),
                pitch_windows=[approved.pitch_nsdf(out,HZ,t) for t in WINDOWS],
                tail_first_500ms_rms=sk.rms(out[round(EXCITE*sk.RATE):round((EXCITE+.5)*sk.RATE)]),
                tail_500_to_900ms_rms=sk.rms(out[round((EXCITE+.5)*sk.RATE):round((EXCITE+.9)*sk.RATE)]),
                tail_last_100ms_rms=sk.rms(out[-4410:]),
                release_above_minus40db_s=(float((later[-1]+1)*.01) if len(later) else 0.),
                release_minus40db_reference='maximum10ms RMS over active8s; finite captured tail only')


def main():
    begin=perf_counter()
    source=json.loads((Path(__file__).resolve().parent.parent / 'mechanics' / 'gathered-events.json').read_text())
    events=np.asarray(source['events'],dtype=float);events[:,0]/=PACE
    reference_path=APPROVED/'tine.wav'
    reference,pcm=read_wav(reference_path)
    target=sk.rms(reference[:round(EXCITE*sk.RATE)])
    shutil.copyfile(reference_path,OUT/'reference.wav')
    kept=events[(events[:,0]>=START)&(events[:,0]<START+EXCITE)]
    common_hash=hashlib.sha256(kept.tobytes()).hexdigest()
    report=dict(status='Python one-axis TINE options; no Kotlin or listening claim',
                root_midi=MIDI,root_hz=HZ,velocity=.75,
                output_rate=sk.RATE,internal_rate=RATE,
                mechanics_preroll_seconds=START,acoustic_preroll_seconds=0.,
                excitation_seconds=EXCITE,option_release_seconds=RELEASE,
                reference_release_seconds=approved.RELEASE,reference_total_seconds=len(reference)/sk.RATE,
                reference_cycles_per_second=PACE,model_seed=20261022,
                upper_ratio_policy='Approved original5.96 retained in every option',
                matching='One constant gain per option, matching RMS over active0..8s to approved PCM; no tail-inclusive matching, no limiter',
                target_active_rms=target,event_history_sha256=common_hash,event_count=len(kept),
                reference_pcm_sha256=hashlib.sha256(pcm).hexdigest(),
                reference_file_sha256=hashlib.sha256(reference_path.read_bytes()).hexdigest(),
                reference_copy_file_sha256=hashlib.sha256((OUT/'reference.wav').read_bytes()).hexdigest(),
                changes=dict(soft_attack='Contact width .72→3.2ms only; opposite-direction width remains1.12x',
                             bright_edge='Tongue upper gains .38/.20/.60→.85/.50/1.50 only; root gain unchanged',
                             long_ring='All tongue/body t60s multiplied3; all frequencies/gains/contact widths unchanged'),
                limitations=['Approximate Python house filters and RMS proxy, not perceived-loudness or Kotlin validation',
                             'Reference is an untouched8.9s PCM copy; new options include2.2s release and total10.2s',
                             'Root centers remain exact C3 but active short-window pitch excursions remain reported',
                             'Longer ring can obscure individual timing; no fast AGC compensates this',
                             'One curated trajectory and register; no HOLD or broad note/velocity grid'],
                renders={})
    report['renders']['reference']=dict(config=BASE,event_count=len(kept),event_history_sha256=common_hash,
                                         file='reference.wav',**metrics(reference))
    for name,cfg in configs().items():
        out,info=render(cfg,events,target)
        sk.write_wav(str(OUT/(name+'.wav')),out)
        # Report the actual quantized playback files rather than a nearby float.
        playback,_=read_wav(OUT/(name+'.wav'))
        report['renders'][name]=dict(config=cfg,file=name+'.wav',**info,**metrics(playback))
    report['wall_seconds_python']=perf_counter()-begin
    (OUT/'report.json').write_text(json.dumps(report,indent=2))
    print(json.dumps(report,indent=2))

if __name__=='__main__':
    main()
