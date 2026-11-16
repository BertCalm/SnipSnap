"""Bristle R0, adapted from Luthier's proto_template.py.
Python listening study, not a Kotlin engine or an original-recording reconstruction.
Run from any directory: python3 bristle_proto.py OUTPUT_DIRECTORY
Dependencies: numpy, scipy, matplotlib. All voicing constants are provisional.
"""
from __future__ import annotations
import base64
import hashlib
import html
import itertools
import json
from pathlib import Path
import subprocess
import sys
import time
import numpy as np
from scipy import signal

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / '.claude/skills/luthier/scripts'))
import snipkit as sk
import probes as pr

VOICES = ['A', 'B']  # A: drive then filter; B: filter then drive
MACROS = dict(TUNE=.5, WEIGHT=.55, GRIT=.55, DARK=.55, BITE=.25, SLIDE=0., HOLD=.5)
ROOT_MIDI = 36
SECONDS = 1.0

def hz_for(midi):
    return 440.0 * 2 ** ((midi - 69) / 12)

def settled(macros):
    return {k: float(np.clip(macros.get(k, v), 0, 1)) if np.isfinite(macros.get(k, v)) else v
            for k, v in MACROS.items()}

def tpt_low(x, cutoff, rate, damping=1.2):
    """Double-precision analogue of Dsp.TptSvf's unsaturated recurrence.
    Positive damping and bilinear integrators avoid the submitted Euler SVF's
    high-cutoff instability. No limiter is used inside the state update.
    """
    g = np.tan(np.pi * np.clip(cutoff, 10, rate * .49) / rate)
    a1 = 1 / (1 + g * (g + damping)); a2 = g * a1; a3 = g * a2
    out = np.empty_like(x)
    s1 = s2 = 0.
    for i in range(len(x)):
        v3 = x[i] - s2
        v1 = a1[i] * s1 + a2[i] * v3
        v2 = s2 + a2[i] * s1 + a3[i] * v3
        s1 = 2 * v1 - s1; s2 = 2 * v2 - s2
        out[i] = v2
    return out

def render(voice, macros, midi, rate=sk.INTERNAL, seconds=SECONDS, source='saw', support=True):
    """Pure render with a shared phase and a finite, baked slide from below.
    Sine polarity follows the rising saw's negative fundamental. Sharing phase
    alone would otherwise encourage cancellation before the filter's phase lag.
    """
    if voice not in VOICES or source not in ('saw', 'sine', 'pulse'):
        raise ValueError('unknown route or source')
    if not 24 <= midi <= 60 or rate < sk.RATE or not .1 <= seconds <= 4:
        raise ValueError('outside prototype register/rate/duration bounds')
    m = settled(macros)
    t = np.arange(round(seconds * rate)) / rate
    # Linear in semitones, like FATHOM. No kick-style pitch drop.
    freq = hz_for(midi) * 2 ** (-12 * m['SLIDE'] * np.maximum(1-t/.12, 0) / 12)
    dt = freq / rate
    phase = np.mod(np.cumsum(dt) - dt, 1)
    def blep(p):
        y = np.zeros_like(p)
        left = p < dt; z = p[left] / dt[left]
        y[left] = 2*z-z*z-1
        right = p > 1-dt; z = (p[right]-1)/dt[right]
        y[right] = z*z+2*z+1
        return y
    sine = -np.sin(2*np.pi*phase)
    if source == 'saw':
        osc = 2*phase - 1 - blep(phase)
    elif source == 'pulse':
        osc = -(np.where(phase < .5, 1., -1.) + blep(phase) - blep((phase+.5)%1))
    else:
        osc = sine
    # Darkness is a descending frequency map; bite opens only the onset.
    cutoff = sk.exp_map(m['DARK'], 2400, 180) * (hz_for(midi)/hz_for(36))**.6
    cutoff = cutoff * 2**(2*m['BITE']*np.exp(-t/.065))
    drive = sk.exp_map(m['GRIT'], 1, 12)
    if voice == 'A':
        grit = tpt_low(np.tanh(drive*osc)/np.tanh(drive), cutoff, rate)
    else:
        grit = np.tanh(drive*tpt_low(osc, cutoff, rate))/np.tanh(drive)
    gate = sk.lin(m['HOLD'], .12, .72)
    # Millisecond-defined attack and finite cosine release, independent of rate.
    attack = .5-.5*np.cos(np.pi*np.minimum(t/.004, 1))
    release = .5+.5*np.cos(np.pi*np.clip((t-gate)/.045, 0, 1))
    env = attack * release * np.exp(-t/.95)
    return (.6*grit + (.9*m['WEIGHT']*sine if support else 0)) * env

def finish_at(raw, rate):
    """Higher-rate reference uses the same 4x finish after FIR rate conversion."""
    if rate != sk.INTERNAL:
        raw = signal.resample_poly(raw, sk.INTERNAL, rate)
    return sk.finish(raw)

def main(out_dir):
    out_dir = Path(out_dir); out_dir.mkdir(parents=True, exist_ok=True)
    try:
        commit = subprocess.check_output(['git','rev-parse','HEAD'], cwd=ROOT,
                                         stderr=subprocess.DEVNULL, text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        commit = '23cde0d1c107039c23fd74ff5c3d2b359a7f6e75 (source snapshot; no checkout)'
    report = dict(status='Python harness, not Kotlin; not heard',
                  commit=commit,
                  macros=MACROS, renders=[], macro_activity_dB={}, checks={},
                  limits=['RMS matching is not Kotlin Loudness.of', 'No reference recording analyzed',
                          'No HOLD loop, velocity/RR, keygroup, phone or MPC integration'])
    clips = []; buffers = {}; timings = []
    def save(name, out, group, recipe=None):
        assert pr.finite(out) and pr.peak(out) <= .991 and abs(pr.dc(out)) < .005, name
        sk.write_wav(str(out_dir/(name+'.wav')), out)
        buffers[name] = out
        entry = dict(name=name, group=group, recipe=recipe,
                     sha256=hashlib.sha256((out_dir/(name+'.wav')).read_bytes()).hexdigest())
        clips.append(entry)
        return entry
    def take(name, voice, midi=36, macros=None, source='saw', support=True, group='Notes'):
        macros = macros or {}
        start = time.perf_counter()
        raw = render(voice, macros, midi, source=source, support=support)
        out = sk.finish(raw); timings.append(1000*(time.perf_counter()-start))
        save(name, out, group, dict(route=voice, midi=midi, macros=settled(macros), source=source, support=support))
        r = pr.report(out, name=name)
        # Low C1 is below the template detector's default 40 Hz floor.
        gate = sk.lin(settled(macros)['HOLD'], .12, .72)
        start_pitch = .15 if gate >= .35 else .025
        pitch_window = min(.2, gate-start_pitch-.01)
        hz, confidence = pr.pitch(out, lo_hz=25, hi_hz=1200,
                                  start_seconds=start_pitch, window_seconds=pitch_window)
        r.update(pitch_hz=hz, pitch_confidence=confidence, cents_from_ref=float(pr.cents(hz,hz_for(midi))),
                 raw_peak=pr.peak(raw), raw_above_output_nyquist=pr.alias_proxy(raw,sk.INTERNAL))
        report['renders'].append(r)
        assert abs(r['cents_from_ref']) < 15 and confidence > .8, r
        return out
    # Three register notes with identical recipes and RMS matching.
    for voice in VOICES:
        for midi in (24,36,48):
            take(f'{voice}_note_{midi}',voice,midi)
        take(f'{voice}_slide',voice,macros={'SLIDE':.65},group='Slides')
    # Isolate the source choice while keeping the filter order and settings fixed.
    for source,support,label in [('saw',False,'saw_only'),('sine',False,'saturated_sine'),('pulse',True,'pulse_plus_sine')]:
        take('A_'+label,'A',source=source,support=support,group='Sources')
    # Original test phrase, not a transcription of the reference song.
    score=[(0,36,.32,0),(.48,36,.18,0),(.72,39,.24,0),(1.12,36,.4,.55),
           (1.78,34,.25,0),(2.18,36,.35,0),(2.72,43,.22,.4),(3.12,36,.48,0)]
    for voice in VOICES:
        phrase=np.zeros(round(4.4*sk.RATE))
        for at,midi,duration,slide in score:
            hold=(duration-.12)/.6
            out=sk.finish(render(voice,{'HOLD':hold,'SLIDE':slide},midi))
            i=round(at*sk.RATE); phrase[i:i+len(out)] += out[:len(phrase)-i]
        save(f'{voice}_phrase',sk.level_to(phrase), 'Start here', dict(score=score, bpm=None))
    # Macro sweeps preserve the template's spectral activity probe.
    for macro in MACROS:
        if macro=='TUNE': continue
        pair=[]
        for value in (0.,1.):
            out=take(f'A_{macro}_{int(value)}','A',macros={macro:value},group='Control extremes')
            pair.append(out)
        report['macro_activity_dB'][macro]=pr.macro_activity(*pair)
        assert np.max(np.abs(pair[0]-pair[1]))>.001, macro
    # Reproducibility, malformed controls, and the two key interaction pairs.
    baseline=render('A',{},36)
    assert np.array_equal(baseline,render('A',{},36))
    assert np.array_equal(baseline,render('A',{'GRIT':float('nan'),'unknown':1},36))
    peaks=[]
    for voice,weight,grit,dark,bite in itertools.product(VOICES,(0.,1.),(0.,1.),(0.,1.),(0.,1.)):
        raw=render(voice,dict(WEIGHT=weight,GRIT=grit,DARK=dark,BITE=bite),36)
        assert pr.finite(raw) and pr.peak(raw)<4
        out=sk.finish(raw)
        assert pr.peak(out)<.991 and abs(pr.dc(out))<.005
        peaks.append(pr.peak(raw))
    report['checks'].update(deterministic=True, nonfinite_defaults=True, corner_count=len(peaks), raw_corner_max=max(peaks))
    report['macro_low_mid_activity_dB'] = {}
    for macro in MACROS:
        if macro == 'TUNE': continue
        a = sk.finish(render('A', {macro:.25}, 36))
        b = sk.finish(render('A', {macro:.5}, 36))
        report['macro_low_mid_activity_dB'][macro] = pr.macro_activity(a,b)
        assert np.max(np.abs(a-b)) > .001, macro
    # An unforced filter must decay after a bounded excitation, at extreme cutoff.
    for fc in (20,650,.45*sk.INTERNAL):
        x=np.zeros(sk.INTERNAL); x[:round(.01*sk.INTERNAL)]=1
        y=tpt_low(x,np.full(len(x),fc),sk.INTERNAL,damping=.1)
        assert pr.finite(y) and pr.rms(y[-4410:]) < pr.rms(y[:17640])*.02
    report['checks']['filter_decay_extremes']=True
    # A 16x reference detects rate-dependent error, including but not isolating aliasing.
    report['rate_reference_error_db']={}
    for voice in VOICES:
        m=dict(GRIT=1.,DARK=0.,BITE=1.,WEIGHT=0.)
        x=sk.finish(render(voice,m,48))
        ref=finish_at(render(voice,m,48,rate=sk.RATE*16),sk.RATE*16)
        report['rate_reference_error_db'][voice]=float(20*np.log10(pr.rms(x-ref)/pr.rms(ref)))
    report['render_ms_python']=dict(median=float(np.median(timings)),max=max(timings),includes_output=True)
    report['manifest']=clips
    (out_dir/'report.json').write_text(json.dumps(report,indent=2,allow_nan=False))
    pr.spectrogram_png(buffers['A_note_36'],sk.RATE,str(out_dir/'A_spectrum.png'),'A: drive then filter')
    pr.spectrogram_png(buffers['B_note_36'],sk.RATE,str(out_dir/'B_spectrum.png'),'B: filter then drive')
    cards=[]
    for group in ['Start here','Notes','Slides','Sources','Control extremes']:
        cards.append(f'<h2>{group}</h2>')
        for c in clips:
            if c['group']!=group: continue
            data=base64.b64encode((out_dir/(c['name']+'.wav')).read_bytes()).decode()
            cards.append(f'<article><b>{html.escape(c["name"])}</b><audio controls preload="none" src="data:audio/wav;base64,{data}"></audio></article>')
    page='''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Bristle bass audition</title>
<style>body{font:17px system-ui;background:#111822;color:#e7edf3;max-width:850px;margin:40px auto;padding:20px}h1{color:#f3bc61}h2{margin-top:40px}article{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:14px;border-bottom:1px solid #354150;flex-wrap:wrap}audio{width:310px}p{line-height:1.6}button{padding:10px}</style>
<h1>Bristle · bass study 01</h1><p>Start with the two phrases. A puts distortion before the filter; B puts it after. Listen for weight, rasp and tight stops. Both use the same notes and matched RMS, which does not guarantee equal perceived loudness.</p>
<p>Original test phrase, no samples or transcription from the reference recording. MIDI 24 / 36 / 48 are C1 / C2 / C3. This is a Python listening prototype, not an installed SnipSnap instrument.</p><button onclick="document.querySelectorAll('audio').forEach(a=>a.pause())">Stop audio</button>'''+''.join(cards)+'''<p>Which route is closer: A or B? Is it missing weight, buzz, or movement?</p><script>document.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>document.querySelectorAll('audio').forEach(b=>{if(b!==a)b.pause()})))</script></html>'''
    (out_dir/'listen.html').write_text(page)
    print(json.dumps({k:v for k,v in report.items() if k not in ('renders','manifest')},indent=2))

if __name__=='__main__':
    main(sys.argv[1] if len(sys.argv)>1 else str(ROOT/'testkit/bristle-r0'))
