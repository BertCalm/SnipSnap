"""Accord refinement auditions, Python harness only.

Preserve the liked C3 reference, keep fifteen exact event histories, and apply
note/velocity laws without per-velocity leveling. No mechanical state changes.
The house filters/loudness are approximations, as in the preceding prototypes.
"""
import hashlib
import importlib.util
import json
from pathlib import Path
from time import perf_counter

import numpy as np
from scipy import signal

from controls import config_for

OUT = Path(__file__).parent
APPROVED = (Path(__file__).resolve().parent.parent / 'musicality')
spec = importlib.util.spec_from_file_location('approved_accord', APPROVED/'render.py')
approved = importlib.util.module_from_spec(spec)
spec.loader.exec_module(approved)
sk = approved.sk
RATE = sk.INTERNAL
EXCITE = approved.EXCITE
REFERENCE_VELOCITY = .75
NOTES = [36, 48, 60, 72]
VELOCITIES = [.3, .75, 1.]
WINDOWS = [.75, 1.5, 2.75, 3.5, 4.75, 5.5, 6.75, 7.25]


def acoustic_config(voice, midi, velocity):
    cfg = config_for(voice, midi, velocity)
    # This is the sole proposed frequency adjustment. Root modes stay exactly
    # on note. The final pitch study must validate this near-sixth upper mode.
    if voice == 'tine':
        cfg['tongue'][-1] = (6., *cfg['tongue'][-1][1:])
    return cfg


def render_unleveled(voice, midi, velocity, inputs):
    events, pressure, roughness, gate = inputs
    cfg = acoustic_config(voice, midi, velocity)
    hz = cfg['root_hz']
    n = len(pressure)
    force = np.zeros(n)
    kept = events[(events[:, 0] >= approved.START) &
                  (events[:, 0] < approved.START+EXCITE)]
    kernels = {}
    for direction in [-1, 1]:
        width = max(4, round(cfg['width_ms']*(1. if direction > 0 else 1.12)*.001*RATE))
        kernel = np.hanning(width)
        kernels[direction] = kernel/kernel.sum()
    for tc, mechanism, direction, speed in kept:
        position = (tc-approved.START)*RATE
        index = int(position)
        fraction = position-index
        pulse = kernels[int(direction)]*np.sqrt(min(speed/.8, 2.))/15.
        for shift, weight in [(0, 1.-fraction), (1, fraction)]:
            end = min(n, index+shift+len(pulse))
            force[index+shift:end] += pulse[:end-index-shift]*weight
    force *= gate*cfg['pulse_gain']
    force = sk.band_limit(force, RATE, cutoff_hz=10000)
    direct = sk.modal_ring(force, hz, cfg['tongue'], RATE, compensate=True)
    direct += cfg['body_gain']*sk.modal_ring(force, hz, cfg['body'], RATE, compensate=True)
    brushed = np.zeros(n)
    if cfg['brush']:
        brush_force = sk.band_limit(cfg['brush']*pressure*roughness, RATE, cutoff_hz=10000)
        brushed = sk.modal_ring(brush_force, hz, cfg['tongue'], RATE, compensate=True)
        brushed += cfg['body_gain']*sk.modal_ring(brush_force, hz, cfg['body'], RATE, compensate=True)
    raw = direct+brushed
    active = round(EXCITE*RATE)
    component_ratio = (float(20*np.log10(sk.rms(brushed[:active])/sk.rms(direct[:active])))
                       if cfg['brush'] else None)
    conditioned = sk.dc_block(sk.decimate4(sk.band_limit(raw, RATE)))
    info = dict(config=cfg, root_hz=hz, events=int(len(kept)),
                raw_peak=float(np.max(np.abs(raw))),
                raw_finite=bool(np.isfinite(raw).all()),
                brushing_to_direct_rms_db=component_ratio,
                max_pole_radius=float(max(np.exp(-6.9078/(m[2]*RATE))
                                          for m in cfg['tongue']+cfg['body'])))
    return conditioned*cfg['amp_gain'], info


def metrics(x, info):
    active = x[:round(EXCITE*sk.RATE)]
    f, p = signal.welch(active, sk.RATE, nperseg=32768)
    h = info['root_hz']
    root = (f >= h*.94) & (f <= h*1.06)
    upper = f > h*1.7
    pitch = [approved.pitch_nsdf(x, h, t) for t in WINDOWS]
    cents = [abs(v['cents']) for v in pitch if v['cents'] is not None]
    return dict(info, finite=bool(np.isfinite(x).all()), peak=float(np.max(np.abs(x))),
                rms=sk.rms(x), dc=float(x.mean()),
                active_spectral_centroid_hz=float(np.sum(f*p)/np.sum(p)),
                active_root_band_energy_share=float(p[root].sum()/p.sum()),
                active_upper_energy_share=float(p[upper].sum()/p.sum()),
                near_nyquist_energy_share=float(p[f > .95*sk.RATE/2].sum()/p.sum()),
                pitch_windows=pitch, median_abs_short_window_cents=float(np.median(cents)),
                min_pitch_confidence=float(min(v['confidence'] for v in pitch)),
                last_100ms_rms=sk.rms(x[-4410:]))


def main():
    begin = perf_counter()
    inputs = approved.load_inputs()
    source_hash = {k: hashlib.sha256(a.tobytes()).hexdigest()
                   for k, a in zip(['events', 'pressure', 'roughness', 'release_gate'], inputs)}
    samples_dir = OUT/'samples'
    samples_dir.mkdir(exist_ok=True)
    report = dict(status='Refined Python auditions, not Kotlin production implementation',
                  default_voice='BRUSH', output_rate=sk.RATE, internal_rate=RATE,
                  mechanics_preroll_seconds=approved.START, acoustic_preroll_seconds=0,
                  excitation_seconds=EXCITE, release_seconds=approved.RELEASE,
                  reference_velocity=REFERENCE_VELOCITY, source_fingerprints=source_hash,
                  notes_midi=NOTES, velocities=VELOCITIES,
                  gain_policy='One reference-derived gain per voice/note, reused across all velocities; common static headroom if required',
                  pitch_policy='Unchanged root centers; TINE sixth upper mode 5.96 -> 6.00; no resampling or event retiming',
                  limitations=['Python output filters and RMS leveling approximate the house stage',
                               'Register and velocity changes await owner listening',
                               'Short-window pitch excursions during active contact remain reported',
                               'One curated mechanical seed and trajectory; no HOLD or Kotlin gate'],
                  calibrations={}, renders={})
    for voice in ['brush', 'wood', 'tine']:
        for midi in NOTES:
            candidates = {}
            for velocity in VELOCITIES:
                candidates[velocity] = render_unleveled(voice, midi, velocity, inputs)
            reference = candidates[REFERENCE_VELOCITY][0]
            wanted_gain = .105/sk.rms(reference)
            worst_peak = max(float(np.max(np.abs(x))) for x, _ in candidates.values())
            gain = min(wanted_gain, .94/max(worst_peak, 1e-20))
            label = f'{voice.upper()}_C{midi//12-1}'
            report['calibrations'][label] = dict(gain=gain, rms_reference_gain=wanted_gain,
                                                 shared_headroom_reduction_db=float(20*np.log10(gain/wanted_gain)))
            for velocity, (conditioned, info) in candidates.items():
                suffix = {.3: 'soft', .75: 'normal', 1.: 'hard'}[velocity]
                name = f'{label}_{suffix}'
                out = sk.fade_tail(conditioned*gain)
                out[:round(.003*sk.RATE)] *= np.linspace(0., 1., round(.003*sk.RATE))
                sk.write_wav(str(samples_dir/(name+'.wav')), out)
                entry = metrics(out, info)
                entry['calibration_gain'] = gain
                entry['file'] = str(samples_dir/(name+'.wav'))
                # Compare C3 normal against the owner's existing PCM reference.
                if midi == 48 and velocity == REFERENCE_VELOCITY:
                    import wave
                    with wave.open(str(APPROVED/(voice+'.wav')), 'rb') as w:
                        previous = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(float)/32768.
                    entry['approved_pcm_difference_rms'] = sk.rms(out-previous)
                    entry['approved_pcm_correlation'] = float(np.corrcoef(out, previous)[0, 1])
                report['renders'][name] = entry
            print(label, 'rendered soft / normal / hard', flush=True)
    report['velocity_summary'] = {}
    for voice in ['BRUSH', 'WOOD', 'TINE']:
        row = {}
        for suffix in ['soft', 'normal', 'hard']:
            x = report['renders'][f'{voice}_C3_{suffix}']
            row[suffix] = {k:x[k] for k in ['rms', 'peak', 'active_spectral_centroid_hz',
                                            'active_upper_energy_share']}
        report['velocity_summary'][voice] = row
    report['wall_seconds_full_python_matrix'] = perf_counter()-begin
    (OUT/'report.json').write_text(json.dumps(report, indent=2))
    print(json.dumps(dict(renders=len(report['renders']),
                          max_peak=max(r['peak'] for r in report['renders'].values()),
                          all_finite=all(r['finite'] and r['raw_finite'] for r in report['renders'].values()),
                          seconds=report['wall_seconds_full_python_matrix'])), flush=True)


if __name__ == '__main__':
    main()
