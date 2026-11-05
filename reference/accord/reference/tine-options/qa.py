"""Independent, compact PCM and one-axis checks for TINE options.

This checks measurable contrast, not whether a person hears or prefers it.
The original audition assets and renderer are never modified.
"""
import hashlib
import importlib.util
import json
from pathlib import Path
import wave

import numpy as np
from scipy import signal

ROOT=Path(__file__).parent
APPROVED=(Path(__file__).resolve().parent.parent / 'musicality' / 'tine.wav')
RATE=44100
ACTIVE=8*RATE


def read(path):
    with wave.open(str(path),'rb') as w:
        meta=dict(channels=w.getnchannels(),sample_width=w.getsampwidth(),rate=w.getframerate(),frames=w.getnframes())
        raw=w.readframes(w.getnframes())
    return np.frombuffer(raw,dtype='<i2').astype(float)/32768.,meta,raw


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def scalar_shape_difference(a,b):
    scale=float(np.dot(a,b)/np.dot(a,a))
    return dict(best_fitted_scalar=scale,correlation=float(np.corrcoef(a,b)[0,1]),
                residual_rms=float(np.sqrt(np.mean((b-scale*a)**2))))


def audio_metrics(x,hz):
    active=x[:ACTIVE]
    f,p=signal.welch(active,RATE,nperseg=32768)
    energy=p.sum()
    root=(f>=hz*.94)&(f<=hz*1.06)
    rms=lambda a:float(np.sqrt(np.mean(a*a)))
    block=x[:len(x)//441*441].reshape(-1,441)
    env=np.sqrt(np.mean(block*block,axis=1))
    high=np.where(env[800:]>env[:800].max()*.01)[0]
    return dict(active_rms=rms(active),peak=float(np.abs(x).max()),dc=float(x.mean()),
                upper_energy_share=float(p[f>hz*1.7].sum()/energy),
                root_energy_share=float(p[root].sum()/energy),
                centroid_hz=float(np.dot(f,p)/energy),
                tail_first_900ms_energy=float(np.sum(x[ACTIVE:ACTIVE+round(.9*RATE)]**2)/RATE),
                tail_500_to_900ms_rms=rms(x[ACTIVE+round(.5*RATE):ACTIVE+round(.9*RATE)]),
                release_above_minus40db_s=float((high[-1]+1)*.01) if len(high) else 0.)


def main():
    source=json.loads((ROOT/'report.json').read_text())
    report=dict(status='Independent Python PCM/source QA; no listening claim',failures=[],files={},one_axis={})
    reference,_,original_pcm=read(APPROVED)
    spec=importlib.util.spec_from_file_location('tine_options_renderer',ROOT/'render.py')
    renderer=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(renderer)
    configurations=renderer.configs()
    base=renderer.BASE
    all_cfg={'reference':base,**configurations}
    for name,cfg in all_cfg.items():
        x,meta,raw=read(APPROVED if name == 'reference' and not (ROOT/'reference.wav').exists() else ROOT/(name+'.wav'))
        duration=8.9 if name=='reference' else 10.2
        target=dict(channels=1,sample_width=2,rate=RATE,frames=round(duration*RATE))
        metrics=audio_metrics(x,source['root_hz'])
        metrics['metadata']=meta
        metrics['active_rms_difference_db']=float(20*np.log10(metrics['active_rms']/np.sqrt(np.mean(reference[:ACTIVE]**2))))
        if meta!=target or not np.isfinite(x).all() or metrics['peak']>=.95:
            report['failures'].append(name+': header, finite PCM or peak bound failed')
        if not source['renders'][name]['finite'] or not np.isfinite(source['renders'][name].get('raw_peak',0)):
            report['failures'].append(name+': reported internal finite check failed')
        if abs(metrics['active_rms_difference_db'])>.02:
            report['failures'].append(name+': active RMS differs by more than .02dB')
        if name!='reference':
            metrics['shape_vs_approved_active']=scalar_shape_difference(reference[:ACTIVE],x[:ACTIVE])
        report['files'][name]=metrics
    copied=(ROOT/'reference.wav') if (ROOT/'reference.wav').exists() else APPROVED
    report['reference']=dict(file_byte_identical=sha(copied)==sha(APPROVED)==source['reference_file_sha256'],
                            active_pcm_byte_identical=read(copied)[2][:ACTIVE*2]==original_pcm[:ACTIVE*2])
    if not all(report['reference'].values()):
        report['failures'].append('Reference was changed')
    soft=configurations['soft_attack']
    bright=configurations['bright_edge']
    long=configurations['long_ring']
    report['one_axis']['soft_attack']=dict(width_increased=soft['width_ms']>base['width_ms'],
        all_other_config_unchanged={k:v for k,v in soft.items() if k!='width_ms'}=={k:v for k,v in base.items() if k!='width_ms'})
    report['one_axis']['bright_edge']=dict(other_fields_unchanged={k:v for k,v in bright.items() if k!='tongue'}=={k:v for k,v in base.items() if k!='tongue'},
        root_mode_unchanged=bright['tongue'][0]==base['tongue'][0],
        upper_frequency_decay_unchanged=all(a[0]==b[0] and a[2]==b[2] for a,b in zip(bright['tongue'],base['tongue'])))
    report['one_axis']['long_ring']=dict(other_fields_unchanged={k:v for k,v in long.items() if k not in ['tongue','body']}=={k:v for k,v in base.items() if k not in ['tongue','body']},
        frequencies_and_gains_unchanged=all(a[:2]==b[:2] for bank in ['tongue','body'] for a,b in zip(long[bank],base[bank])),
        decay_tripled=all(np.isclose(a[2],3*b[2]) for bank in ['tongue','body'] for a,b in zip(long[bank],base[bank])))
    if not all(value for axis in report['one_axis'].values() for value in axis.values()):
        report['failures'].append('An option changes an unintended axis')
    report['root_centers']=dict(hz=source['root_hz'],same_frequencies_all_options=all(
        [m[0] for m in cfg[bank]]==[m[0] for m in base[bank]] for cfg in all_cfg.values() for bank in ['tongue','body']))
    event_source=json.loads((Path(__file__).resolve().parent.parent / 'mechanics' / 'gathered-events.json').read_text())
    events=np.asarray(event_source['events'],dtype=float)
    events[:,0]/=source['reference_cycles_per_second']
    before=events.tobytes()
    forces={}
    for name,cfg in all_cfg.items():
        forces[name],kept=renderer.force_for(cfg,events)
        if hashlib.sha256(kept.tobytes()).hexdigest()!=source['event_history_sha256']:
            report['failures'].append(name+': mechanical event history mismatch')
    report['mechanics']=dict(input_array_unmodified=events.tobytes()==before,event_count=len(kept),
                            bright_and_long_force_identical_to_baseline=all(np.array_equal(forces['reference'],forces[name]) for name in ['bright_edge','long_ring']),
                            no_acoustic_noise_in_any_config=all(cfg['brush']==0 for cfg in all_cfg.values()),
                            cached_model_seed=json.loads((Path(__file__).resolve().parent.parent / 'mechanics' / 'report.json').read_text())['seed'])
    if not report['mechanics']['input_array_unmodified'] or not report['mechanics']['bright_and_long_force_identical_to_baseline']:
        report['failures'].append('Mechanical/force input was changed')
    f=report['files']
    report['contrast_checks']=dict(soft_upper_energy_lower=f['soft_attack']['upper_energy_share']<f['reference']['upper_energy_share'],
        bright_upper_energy_ratio=f['bright_edge']['upper_energy_share']/f['reference']['upper_energy_share'],
        bright_difference_not_only_scalar=f['bright_edge']['shape_vs_approved_active']['residual_rms']>100/32768,
        long_tail_first900ms_energy_ratio=f['long_ring']['tail_first_900ms_energy']/f['reference']['tail_first_900ms_energy'],
        long_decay_duration_larger=f['long_ring']['release_above_minus40db_s']>f['reference']['release_above_minus40db_s'])
    if not report['contrast_checks']['soft_upper_energy_lower'] or report['contrast_checks']['bright_upper_energy_ratio']<2 or not report['contrast_checks']['bright_difference_not_only_scalar'] or report['contrast_checks']['long_tail_first900ms_energy_ratio']<2 or not report['contrast_checks']['long_decay_duration_larger']:
        report['failures'].append('A requested contrast was not established numerically')
    report['limits']='RMS matching is a fixed gain over active8s; it does not establish equal perceived loudness. Reference is8.9s, options10.2s. Release comparison uses their common first900ms after excitation stops. No active-note pitch perfection or listening preference is asserted.'
    (ROOT/'qa_results.json').write_text(json.dumps(report,indent=2))
    print(json.dumps({k:report[k] for k in ['failures','reference','one_axis','root_centers','mechanics','contrast_checks']},indent=2))
    return 1 if report['failures'] else 0


if __name__=='__main__':
    raise SystemExit(main())
