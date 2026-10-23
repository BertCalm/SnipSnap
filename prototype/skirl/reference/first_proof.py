"""Skirl acoustic proof: synthetic schedule, NOT a rolling-disk simulation.

Adapted from Luthier's proto_template.py and imports its snipkit/probes helpers.
The time/energy laws and phase relationship here are design hypotheses.
No mechanical contact reactions, friction feasibility or Euler-disk physics are tested.
"""
from pathlib import Path
import hashlib
import json
import math
import shutil
import sys
import time
import zipfile

import numpy as np
from scipy import signal

HERE = Path(__file__).resolve().parent
# Bundled source helpers keep this repository copy independent of scratch paths.
SOURCE = HERE
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(SOURCE))
import snipkit as sk
import probes as pr

INTERNAL = sk.INTERNAL
SECONDS = 5.0
ROLL_SECONDS = 3.0
TERMINAL_SECONDS = 0.08
R0, R1 = 7.0, 37.0
ACCEL = math.log(R1 / R0) / ROLL_SECONDS


def pitch_nsdf(x, rate, lo=40.0, hi=2000.0):
    """Correct NSDF denominator; validate below against pure-sine fixtures.

    The bundled probes.pitch uses a cumulative-energy denominator which is
    incorrect at lag zero. Keep the same first-strong-peak choice, fix sums.
    """
    x = np.asarray(x, dtype=float)
    x = x - np.mean(x)
    n = len(x)
    if n < 64 or np.sqrt(np.mean(x*x)) < 1e-9:
        return {'hz': 0.0, 'confidence': 0.0}
    ac = signal.fftconvolve(x, x[::-1], mode='full')[n-1:]
    cs = np.concatenate(([0.0], np.cumsum(x*x)))
    lag = np.arange(n)
    den = cs[n-lag] + cs[n] - cs[lag]
    nsdf = 2 * ac / np.maximum(den, 1e-30)
    low = max(1, int(rate / hi))
    high = min(n-2, int(rate / lo))
    candidates, _ = signal.find_peaks(nsdf[low:high+1])
    candidates += low
    if len(candidates) == 0:
        return {'hz': 0.0, 'confidence': 0.0}
    candidates = candidates[nsdf[candidates] >= 0.8 * np.max(nsdf[candidates])]
    i = int(candidates[0])
    a,b,c = nsdf[i-1:i+2]
    frac = 0.5 * (a-c) / (a-2*b+c) if abs(a-2*b+c) > 1e-20 else 0.0
    return {'hz': float(rate / (i+frac)), 'confidence': float(np.clip(b, 0, 1))}


def pulse(width, rate=INTERNAL):
    # Smooth finite contact, not an impulse. At least 0.7 ms, then 8 kHz LP.
    n = max(4, round(width * rate))
    p = np.sin(np.pi*np.arange(n)/(n-1))**2
    return p / np.sqrt(np.sum(p*p) / rate)


def place(dst, p, at, energy, rate=INTERNAL):
    start = round(at * rate)
    if start >= len(dst):
        return
    end = min(len(dst), start+len(p))
    dst[start:end] += math.sqrt(max(0, energy)) * p[:end-start]


def excitation(edge, rate=INTERNAL):
    n = round(SECONDS * rate)
    rolling = np.zeros(n)
    terminal = np.zeros(n)
    support = np.zeros(n)
    p = pulse(sk.exp_map(edge, 0.006, 0.0007), rate)
    count = math.floor(R0 * math.expm1(ACCEL * ROLL_SECONDS) / ACCEL)
    events = []
    # Events are crossings of the integrated contact phase, never random times.
    for k in range(count+1):
        t = math.log1p(k*ACCEL/R0) / ACCEL
        if t >= ROLL_SECONDS:
            continue
        contact_rate = R0 * math.exp(ACCEL*t)
        power = math.exp(-t/1.15)
        # Synthetic body rotation is intentionally distinct from contact phase.
        body_phase = 2*math.pi*(1.4*t - 0.11*t*t)
        rim_weight = (0.82 + 0.18*math.cos(body_phase + 0.2))
        support_weight = (0.82 + 0.18*math.cos(2*math.pi*k/5 + 0.9))
        e = power / contact_rate
        place(rolling, p, t, e * rim_weight**2, rate)
        place(support, p, t, e * support_weight**2, rate)
        events.append({'time_s':t,'contact_rate_hz':contact_rate,
                       'body_phase_radians':body_phase,'pulse_energy':e*rim_weight**2})
    # A finite 0.020 squared-amplitude-seconds budget is split over contacts.
    # Units are squared excitation amplitude * seconds, not calibrated joules.
    terminal_budget = 0.020
    weights = np.array([1., .65, .45, .3, .18])
    weights = weights / np.sqrt(np.sum(weights**2))
    for offset, w in zip([0., 0.4, 0.9, 1.6, 2.2], weights):
        place(terminal, p, ROLL_SECONDS+offset/R1, terminal_budget*w*w, rate)
    prefilter_energy = {
        'rolling':float(np.sum(rolling**2)/rate),
        'terminal':float(np.sum(terminal**2)/rate),
        'terminal_budget':terminal_budget,
        'terminal_to_rolling':float(np.sum(terminal**2)/np.sum(rolling**2)),
        'terminal_support_route_energy':float(0.7**2*np.sum(terminal**2)/rate),
    }
    # Input filtering is explicit; oversampling alone does not bandlimit contacts.
    sos = signal.butter(6, 8000, fs=rate, output='sos')
    total = signal.sosfilt(sos, rolling+terminal)
    support = signal.sosfilt(sos, support+0.7*terminal)
    # A 10 ms finite withdrawal makes REST excitation exactly zero, including LP state.
    withdraw_start = round((ROLL_SECONDS+TERMINAL_SECONDS-0.01)*rate)
    rest = round((ROLL_SECONDS+TERMINAL_SECONDS)*rate)
    fade = np.ones(n)
    fade[withdraw_start:rest] = np.linspace(1.,0.,rest-withdraw_start)
    fade[rest:] = 0.
    total *= fade
    support *= fade
    return total, support, {**prefilter_energy,'events':events,
        'rest_excitation_peak':float(max(np.max(abs(total[rest:])),np.max(abs(support[rest:])))),
        'excitation_energy_after_filter':float(np.sum(total**2)/rate),
        'contact_start_hz':R0,'contact_end_hz':R1,'rolling_seconds':ROLL_SECONDS,
        'rest_start_s':rest/rate}


def render(voice='ROLL', macros=None, midi=48, rate=INTERNAL, seconds=SECONDS):
    # Template-compatible callable. No randomness is needed for this proof.
    macros = {'EDGE':0.4, **(macros or {})}
    edge = macros['EDGE']
    drive, support_drive, diag = excitation(edge, rate)
    hz = 440. * 2**((midi-69)/12)
    disk = [(1.,1.,1.10),(2.,.25,0.80),(3.02,.17,.62),
            (4.17,.10,.49),(5.61,.07,.36),(7.29,.045,.25)]
    # Broad, weak inharmonic support avoids a 3:2-like tail masking the root.
    # The first proof's .13 gain / 1.1 s mode produced octave-ambiguous NSDF.
    support = [(1.,.40,1.65),(1.52,.05,.65),(2.1,.09,.84),
               (2.88,.055,.65),(4.25,.035,.42)]
    # Prototype compensation differs from Modes.ring and is disclosed in report.
    raw = sk.modal_ring(drive,hz,disk,rate,compensate=True)
    raw += .30*sk.modal_ring(support_drive,hz,support,rate,compensate=True)
    return raw, diag


def rate_energy_probe(width=.002):
    result=[]
    duration=2.
    p=pulse(width)
    power=.3
    for rate_hz in [5.,20.,80.]:
        x=np.zeros(round(duration*INTERNAL))
        events=int(duration*rate_hz)
        for k in range(events):
            place(x,p,k/rate_hz,power/rate_hz)
        energy=float(np.sum(x*x)/INTERNAL)
        result.append({'events_per_s':rate_hz,'pulse_width_s':width,'event_energy':power/rate_hz,
                       'measured_total_energy':energy,'mean_power':energy/duration,
                       'relative_error':energy/(power*duration)-1})
    return result


def main():
    HERE.mkdir(parents=True,exist_ok=True)
    t=time.perf_counter()
    report={'status':'Python acoustic feasibility proof, not Kotlin; not heard',
      'model':'Synthetic prescribed contact schedule; not rolling mechanics',
      'source_commit':'4e36a2bf8cf8bfb07dba35379558b54429b5d73d',
      'limitations':[
        'No rolling-disk dynamics, force, friction, energy-work balance or terminal physics.',
        'No HOLD loop, classifier, JSON integration or Android performance tested.',
        'Snipkit decimation and noise differ from Kotlin; output level uses RMS proxy, not Loudness.of.',
        'Modal gains include sin(theta) compensation; this is not the unmodified Kotlin Modes.ring.',
        'Initial/terminal contact histories are synthetic; body/contact phase relationship is prescribed.',
        'Probe pitch confidence uses corrected NSDF denominator; original bundled pitch probe denominator is wrong.',
        'No full anti-aliasing reference comparison; 8 kHz input lowpass and house-inspired output filtering only.',
        'The equal-power 2ms-pulse probe has no pulse overlap. Per-event energy scaling alone does not bound coherent overlapping pulses; a separate 50ms stress probe demonstrates this.'
      ],'renders':[],'rate_power_probe_nonoverlap':rate_energy_probe(),
      'rate_power_probe_overlap_stress':rate_energy_probe(.050),
      'determinism_by_render':{},
      'pitch_measurement_stage':'Exact exported PCM16 WAV decoded through snipkit.read_wav'}
    report['targeted_revision']={
      'finding':'Initial 7-to-37Hz schedule made the C3 tail octave-ambiguous under unconstrained NSDF.',
      'measured_prior_tail_cents':{'01_ROLL_C3':-1183.405,'03_EDGE_C3':-1181.894,'04_SMOOTH_C3':-1189.199},
      'prior_baseline_tail_fft':{'window_s':[3.4,3.9],'root_peak_hz':130.88,
        'root_peak_magnitude':0.815,'support_peak_hz':198.85,'support_peak_magnitude':0.476},
      'change':'1.52-ratio support mode gain .13 to .05 and t60 1.1s to .65s; no pitch-detector constraint.',
      'reason':'Keep the inharmonic support broad/weak enough that root remains unambiguous after contact stops.'}
    fixtures={}
    for hz in [65.406391325,130.81278265,440.]:
        ts=np.arange(round(.3*sk.RATE))/sk.RATE
        fixtures[str(hz)]=pitch_nsdf(np.sin(2*np.pi*hz*ts),sk.RATE)
    report['pitch_probe_pure_sine_fixtures']=fixtures
    for name,midi,edge in [('01_ROLL_C3',48,.4),('02_ROLL_C2',36,.4),
                          ('03_EDGE_C3',48,.95),('04_SMOOTH_C3',48,.05)]:
        start=time.perf_counter()
        raw,diag=render(macros={'EDGE':edge},midi=midi)
        out=sk.finish(raw)
        elapsed=time.perf_counter()-start
        hz=440.*2**((midi-69)/12)
        item={'name':name,'midi':midi,'edge':edge,'ref_hz':hz,
              'frames':len(out),'seconds':len(out)/sk.RATE,'peak':pr.peak(out),
              'dc':pr.dc(out),'rms':pr.rms(out),'loudness_proxy':pr.loudness_proxy(out),
              'finite':pr.finite(out),'alias_proxy_raw':pr.alias_proxy(raw,INTERNAL),
              'python_render_seconds':elapsed,'pitch_windows':{}}
        sk.write_wav(str(HERE/(name+'.wav')),out)
        decoded,_=sk.read_wav(str(HERE/(name+'.wav')))
        for window,at in [('opening',.03),('flutter',1.30),('late_whirr',2.65),('tail',3.40)]:
            segment=decoded[round(at*sk.RATE):round((at+.28)*sk.RATE)]
            result=pitch_nsdf(segment,sk.RATE)
            result['cents_from_ref']=pr.cents(result['hz'],hz)
            result['centroid_hz']=pr.spectral_centroid(segment,sk.RATE)
            item['pitch_windows'][window]=result
        item['exported_pcm_finite']=pr.finite(decoded)
        item['exported_pcm_dc']=pr.dc(decoded)
        item['exported_pcm_peak']=pr.peak(decoded)
        item['exported_pcm_sha256']=hashlib.sha256((HERE/(name+'.wav')).read_bytes()).hexdigest()
        item['excitation']={key:value for key,value in diag.items() if key!='events'}
        item['event_count']=len(diag['events'])
        item['first_event']=diag['events'][0]
        item['last_event']=diag['events'][-1]
        report['renders'].append(item)
        raw2,_=render(macros={'EDGE':edge},midi=midi)
        out2=sk.finish(raw2)
        report['determinism_by_render'][name]=bool(np.array_equal(out,out2))
        if name=='01_ROLL_C3':
            pr.spectrogram_png(out,sk.RATE,str(HERE/'01_ROLL_C3_spectrogram.png'),
                               'Skirl acoustic proof: synthetic 7 to 37 Hz contacts, fixed C3 modes')
    report['total_python_seconds']=time.perf_counter()-t
    (HERE/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    for file in ['snipkit.py','probes.py','proto_template.py']:
        if (SOURCE/file).resolve() != (HERE/file).resolve():
            shutil.copyfile(SOURCE/file,HERE/file)
    with zipfile.ZipFile(HERE/'Skirl-acoustic-proof.zip','w',zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(HERE.iterdir()):
            if path.suffix in ['.wav','.py','.json','.png']:
                archive.write(path,path.name)
    print(json.dumps(report,indent=2))


if __name__=='__main__':
    main()
