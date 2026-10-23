"""Second Skirl audition: independently reduced disk motion into the first modal body.

This is a Python research prototype, not a SnipSnap Kotlin engine. The disk
equations are ideal no-slip rigid-body dynamics. Acoustic transmission, surface
maps, finite shudder and mode tables are authored musical readouts, not a
joule-calibrated radiation model. The first audition remains preserved separately.
"""
from __future__ import annotations

from dataclasses import replace
from pathlib import Path
import hashlib
import json
import math
import time

import numpy as np
from scipy import signal

import snipkit as sk
import probes as pr
from first_proof import pitch_nsdf, render as render_first_proof
from mechanics import MotionConfig, generate_motion
from contact_budget import unit_energy_pulse, add_budgeted_pulse

HERE = Path(__file__).resolve().parent
OUT = HERE / 'audio'
RATE = sk.RATE
INTERNAL = sk.INTERNAL
ACOUSTIC_SCALE = 13.0
DISK_MODES = [(1.,1.,1.10),(2.,.25,.80),(3.02,.17,.62),
              (4.17,.10,.49),(5.61,.07,.36),(7.29,.045,.25)]
SUPPORT_MODES = [(1.,.40,1.65),(1.52,.05,.65),(2.1,.09,.84),
                 (2.88,.055,.65),(4.25,.035,.42)]


def contact_events(motion,features_per_revolution=1):
    """Crossings of a declared support-azimuth imperfection, in either direction.

    The support map has one localized feature per 2*pi of contact azimuth.
    Rim-coordinate texture separately modulates transmission at that contact.
    Neither a face marker nor an independent oscillator schedules contacts.
    """
    ts, phase = motion['time_s'], motion['contact_phase_rad']
    events = [float(ts[0])]
    tau = 2*math.pi/features_per_revolution
    for i in range(len(ts)-1):
        a, b = phase[i:i+2]
        if b > a:
            levels = range(math.floor(a/tau)+1, math.floor(b/tau)+1)
        elif b < a:
            levels = range(math.ceil(b/tau), math.ceil(a/tau))
        else:
            continue
        for k in levels:
            fraction = (k*tau-a)/(b-a)
            event = float(ts[i]+fraction*(ts[i+1]-ts[i]))
            if event > events[-1]+1e-9:
                events.append(event)
    return np.asarray(events)


def mechanical_excitation(motion, edge=.4,fine_texture=False):
    onset = float(motion['time_s'][-1])
    success = motion['status'] == 'TERMINAL_TRANSITION'
    last_rate = max(abs(float(motion['contact_rate_hz'][-1])), 1.)
    terminal_duration = float(np.clip(2.6/last_rate,.040,.200)) if success else .040
    rest_time = onset+terminal_duration
    seconds = rest_time+2.0
    routes = np.zeros((2,math.ceil(seconds*INTERNAL)))
    width = sk.exp_map(edge,.006,.0007)
    pulse = unit_energy_pulse(width,rate=INTERNAL)
    events = contact_events(motion)
    fine_events = contact_events(motion,4) if fine_texture else np.empty(0)
    grid, work = motion['time_s'], motion['resistance_work_J']
    config = motion['config']
    mg = config['mass_kg']*config['gravity_m_s2']
    assigned = 0.0
    rolling_stats = []
    # Each phase cell receives only its integrated resistance-work proxy. This
    # offline analysis mapping can assign the cell's energy at its first feature;
    # it does not feed energy or forces back into the mechanics.
    def add_event(event,budget,feature):
        rim = float(np.interp(event,grid,motion['rim_phase_rad']))
        azimuth = float(np.interp(event,grid,motion['contact_phase_rad']))
        normal = float(np.interp(event,grid,motion['normal_N']))
        rim_weight = .82+.18*math.cos(rim+.2)
        support_weight = .82+.18*math.cos(azimuth+.9)
        direction = (rim_weight,.85*support_weight)
        kernel = pulse if feature==0 else fine_pulse
        result = add_budgeted_pulse(routes,kernel,round(event*INTERNAL),budget,
                                    direction,rate=INTERNAL)
        rolling_stats.append(dict(time_s=float(event),assigned_energy=budget,
            feature=feature,normal_N=normal,rim_phase_rad=rim,**result))
    fine_pulse = unit_energy_pulse(width*.65,rate=INTERNAL)
    for i, event in enumerate(events):
        next_event = events[i+1] if i+1<len(events) else onset
        if next_event <= event:
            continue
        cell_work = float(np.interp(next_event,grid,work)-np.interp(event,grid,work))
        normal = float(np.interp(event,grid,motion['normal_N']))
        load_weight = min(max(normal/(1.3*mg),0.),1.)
        budget = ACOUSTIC_SCALE*max(cell_work,0.)*load_weight
        if fine_texture:
            # A fixed map has one coarse and three fine features per circuit.
            # Decreasing measured tilt exposes the fine detail. This smooth
            # transmission law is authored acoustic design, not a simulated
            # elastic contact patch. It redistributes one cell's existing work
            # budget; it creates neither another clock nor additional energy.
            degrees = math.degrees(float(np.interp(event,grid,motion['alpha_rad'])))
            aperture = float(np.clip((25.-degrees)/20.,0.,1.))
            aperture = aperture*aperture*(3.-2.*aperture)
            fine_share = (.15+.60*edge)*aperture
            coarse_budget = budget*(1.-fine_share)
            add_event(event,coarse_budget,0)
            assigned += coarse_budget
            local_fine = fine_events[(fine_events>event+1e-8)&(fine_events<next_event-1e-8)]
            for feature,fine_event in enumerate(local_fine[:3],start=1):
                sub_budget = budget*fine_share/3.
                add_event(float(fine_event),sub_budget,feature)
                assigned += sub_budget
        else:
            assigned += budget
            add_event(event,budget,0)
    rolling_energy = float(np.sum(routes*routes)/INTERNAL)
    remaining = max(float(motion['mechanical_energy_J'][-1])-
        mg*config['thickness_m']/2,0.)
    terminal_budget = 0.0
    terminal_stats = []
    if success:
        # Remaining energy is measured above the flat-rest gravitational floor.
        # The acoustic terminal fraction and 5% roll-relative ceiling are shape
        # choices; a high final contact rate cannot increase this budget.
        fraction = .10+.08*config['collapse']
        terminal_budget = min(ACOUSTIC_SCALE*remaining*fraction,.05*assigned)
        weights = np.asarray([1.,.65,.45,.30,.18])
        weights = weights*weights/np.sum(weights*weights)
        offsets = np.asarray([0.,.4,.9,1.6,2.2])/last_rate
        # Bound all contacts to the finite transition even at unusual rates.
        offsets *= min(1.,max(terminal_duration-width-.010,0.)/max(offsets[-1],1e-9))
        terminal_pulse = unit_energy_pulse(width*(.60+.30*config['collapse']),rate=INTERNAL)
        for offset,weight in zip(offsets,weights):
            result = add_budgeted_pulse(routes,terminal_pulse,
                round((onset+offset)*INTERNAL),float(terminal_budget*weight),
                (1.,.7),rate=INTERNAL)
            terminal_stats.append(dict(time_s=float(onset+offset),
                assigned_energy=float(terminal_budget*weight),**result))
    energy_before_filter = float(np.sum(routes*routes)/INTERNAL)
    sos = signal.butter(6,8000,fs=INTERNAL,output='sos')
    routes = signal.sosfilt(sos,routes,axis=1)
    rest_frame = round(rest_time*INTERNAL)
    withdraw = min(round(.010*INTERNAL),rest_frame)
    routes[:,rest_frame-withdraw:rest_frame] *= np.linspace(1.,0.,withdraw)
    routes[:,rest_frame:] = 0.
    diagnostics = dict(
        status=motion['status'],exit_reason=motion['exit_reason'],
        rolling_event_count=len(rolling_stats),rolling_duration_s=onset,
        coarse_contact_events=len(events),fine_texture_enabled=fine_texture,
        fine_texture_is_authored_tilt_readout=fine_texture,
        terminal_duration_s=terminal_duration if success else 0.,
        failure_withdrawal_s=terminal_duration if not success else 0.,
        rest_start_s=rest_time,source_seconds=seconds,
        integrated_mechanical_resistance_work_J=float(work[-1]),
        acoustic_work_scale=ACOUSTIC_SCALE,
        assigned_rolling_proxy_energy=assigned,
        measured_rolling_proxy_energy=rolling_energy,
        remaining_mechanical_energy_above_flat_J=remaining,
        terminal_assigned_proxy_energy=terminal_budget,
        terminal_incremental_proxy_energy=energy_before_filter-rolling_energy,
        total_prefilter_proxy_energy=energy_before_filter,
        total_postfilter_proxy_energy=float(np.sum(routes*routes)/INTERNAL),
        combined_energy_budget_ok=bool(energy_before_filter <= assigned+terminal_budget+1e-9),
        rest_excitation_peak=float(np.max(abs(routes[:,rest_frame:]))),
        event_proxy_units='sum over disk/support routes of amplitude squared times seconds',
        terminal_is_authored_acoustic_transition=success,
        rolling_events=rolling_stats,terminal_events=terminal_stats)
    return routes,diagnostics


def render(motion,midi=48,edge=.4,fine_texture=False):
    routes,diagnostics = mechanical_excitation(motion,edge,fine_texture)
    hz = 440.*2**((midi-69)/12)
    raw = sk.modal_ring(routes[0],hz,DISK_MODES,INTERNAL,compensate=True)
    raw += .30*sk.modal_ring(routes[1],hz,SUPPORT_MODES,INTERNAL,compensate=True)
    # Same fixed modal tables and sin(theta) compensation as the first proof.
    # All poles have radius <1; there is no nonlinear feedback or output limiter.
    out = sk.finish(raw)
    return out,diagnostics


def measure_export(path,midi,onset):
    decoded,rate = sk.read_wav(str(path))
    hz = 440.*2**((midi-69)/12)
    windows = {'opening':.03,'middle':max(.03,.45*onset),
               'late':max(.03,.87*onset),'tail':onset+.35}
    pitch = {}
    for name,start in windows.items():
        segment = decoded[round(start*rate):round((start+.28)*rate)]
        p = pitch_nsdf(segment,rate)
        p['cents_from_root'] = pr.cents(p['hz'],hz)
        p['centroid_hz'] = pr.spectral_centroid(segment,rate)
        p['window_start_s'] = start
        pitch[name] = p
    return dict(frames=len(decoded),seconds=len(decoded)/rate,
        finite=pr.finite(decoded),peak=pr.peak(decoded),dc=pr.dc(decoded),
        rms=pr.rms(decoded),loudness_proxy=pr.loudness_proxy(decoded),
        pitch_windows=pitch,sha256=hashlib.sha256(path.read_bytes()).hexdigest())


def main():
    OUT.mkdir(exist_ok=True)
    # These settings are filled from successful, diagnosed mechanical cases.
    curated = MotionConfig(lean=.55,brake=.4,collapse=.0)
    cases = [
        ('02_motion_C3',curated,48,.4,False),
        ('03_motion_C2',curated,36,.4,False),
        ('04_motion_sharp_C3',curated,48,.95,False),
        ('05_motion_smooth_C3',curated,48,.05,False),
        ('06_shallower_launch_C3',replace(curated,lean=.4),48,.4,False),
        ('07_later_finish_C3',replace(curated,collapse=.05),48,.4,False),
        ('08_original_default_boundary_C3',MotionConfig(),48,.4,False),
        ('09_motion_fine_texture_C3',curated,48,.4,True),
        ('10_late_runout_texture_C3',MotionConfig(lean=0.,brake=.2,collapse=.85),48,.4,True),
        ('11_motion_fine_texture_C2',curated,36,.4,True),
    ]
    report = dict(status='Python mechanically driven audition, not Kotlin',
        source_commit='4e36a2bf8cf8bfb07dba35379558b54429b5d73d',
        prior_owner_feedback='First synthetic-motion audition: Sounds pretty cool',
        new_audio_heard_by_owner=False,
        acoustic_mapping='One-way work-funded phase features into fixed disk/support modes',
        limitations=[
            'Ideal sharp-rim geometry, regularized resistance and chosen friction coefficient are assumptions.',
            'A numerical rigid-body reference does not establish measured real-disk behavior.',
            'Terminal shudder is a finite musical adaptation, not simulated multi-contact impact.',
            'Python output uses RMS leveling and approximate decimation, not the Kotlin perceived-loudness stage.',
            'No HOLD, Kotlin integration, Android performance or complete alias-reference matrix.',
            'The acoustic event energy uses offline phase-cell integrated work, not a calibrated causal radiation model.'
        ],renders=[])
    reference_raw,_ = render_first_proof(midi=48,macros={'EDGE':.4})
    reference = sk.finish(reference_raw)
    reference_path = OUT/'01_original_synthetic_C3.wav'
    sk.write_wav(str(reference_path),reference)
    report['original_reference'] = dict(name=reference_path.stem,
        status='Original prescribed 7-to-37Hz contact curve, not mechanical dynamics',
        **measure_export(reference_path,48,3.0))
    for name,config,midi,edge,fine_texture in cases:
        started=time.perf_counter()
        motion=generate_motion(config)
        out,excitation=render(motion,midi,edge,fine_texture)
        path=OUT/(name+'.wav')
        sk.write_wav(str(path),out)
        replay,_=render(motion,midi,edge,fine_texture)
        assert np.array_equal(out,replay),'non-deterministic acoustic render'
        assert excitation['combined_energy_budget_ok'],'source exceeded its budget'
        assert excitation['rest_excitation_peak']==0.,'REST has new contact input'
        item=dict(name=name,midi=midi,edge=edge,fine_texture=fine_texture,config=motion['config'],
            mechanics=motion['diagnostics'],excitation=excitation,
            deterministic_audio=True,python_including_cold_motion_seconds=time.perf_counter()-started,
            **measure_export(path,midi,excitation['rolling_duration_s']))
        assert item['finite'] and item['peak']<.99,'invalid exported samples'
        report['renders'].append(item)
        print(json.dumps({k:item[k] for k in ('name','seconds','peak','deterministic_audio')},
            ensure_ascii=True),flush=True)
        if name=='02_motion_C3':
            comparison = np.concatenate([reference,np.zeros(round(.6*RATE)),out])
            sk.write_wav(str(OUT/'00_original_then_motion_C3.wav'),comparison)
            keep={k:v for k,v in motion.items() if isinstance(v,np.ndarray)}
            np.savez_compressed(HERE/'default_motion_descriptors.npz',**keep)
            pr.spectrogram_png(out,RATE,str(HERE/'motion_C3_spectrogram.png'),
                'Skirl: constrained rolling motion, fixed C3 acoustic modes')
    (HERE/'audio_report.json').write_text(json.dumps(report,indent=2)+'\n')


if __name__=='__main__':
    main()
