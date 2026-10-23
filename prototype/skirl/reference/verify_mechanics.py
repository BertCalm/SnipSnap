"""Reproducible Skirl motion checks; assertions verify mechanics, not listening.

The independent audit_mechanics.py supplied alongside this file derives the
finite-difference Newton--Euler checks without reusing the geometry derivative.
This file covers reproducibility, terminal policy, solver convergence and cache.
"""
from dataclasses import replace
import json
from pathlib import Path
import time

import numpy as np

from mechanics import MotionConfig, generate_motion, MODEL_VERSION

HERE = Path(__file__).resolve().parent


def check_motion(motion):
    d=motion['diagnostics']
    for value in motion.values():
        if isinstance(value,np.ndarray):
            assert np.all(np.isfinite(value))
    assert d['min_normal_reaction_N']>0
    assert d['max_required_static_friction']<.800001
    assert d['relative_energy_work_error']<1e-6
    assert d['max_quaternion_norm_error']<1e-7
    assert d['min_resistance_power_W']>=0
    assert not d['velocity_projection_used']


def export_motion(name,motion):
    arrays={key:value for key,value in motion.items() if isinstance(value,np.ndarray)}
    np.savez_compressed(HERE/(name+'.npz'),**arrays)


def main():
    proposed=MotionConfig()
    curated=replace(proposed,collapse=0.)
    original=generate_motion(proposed)
    rolling=generate_motion(curated)
    late_config=replace(proposed,lean=0.,brake=.2,collapse=.85)
    late=generate_motion(late_config)
    check_motion(original)
    check_motion(rolling)
    check_motion(late)
    assert late['status']=='TERMINAL_TRANSITION'
    assert abs(late['alpha_rad'][-1]-late_config.terminal_alpha_rad)<1e-9
    assert original['status']=='FAILURE'
    assert original['exit_reason']=='static_friction_exceeded'
    assert original['terminal_onset_s'] is None
    assert rolling['status']=='TERMINAL_TRANSITION'
    assert rolling['exit_reason']=='chosen_terminal_threshold'
    assert abs(rolling['alpha_rad'][-1]-curated.terminal_alpha_rad)<1e-9
    tighter_config=replace(curated,rtol=2e-11,atol=2e-13,max_step_s=.002)
    tight=generate_motion(tighter_config)
    convergence={
        'terminal_time_difference_s':abs(rolling['terminal_onset_s']-tight['terminal_onset_s']),
        'final_contact_phase_difference_rad':abs(rolling['contact_phase_rad'][-1]-tight['contact_phase_rad'][-1]),
        'final_rim_phase_difference_rad':abs(rolling['rim_phase_rad'][-1]-tight['rim_phase_rad'][-1]),
        'final_energy_difference_J':abs(rolling['mechanical_energy_J'][-1]-tight['mechanical_energy_J'][-1]),
    }
    assert convergence['terminal_time_difference_s']<1e-6
    assert convergence['final_contact_phase_difference_rad']<1e-5
    assert convergence['final_rim_phase_difference_rad']<1e-5
    late_tight=generate_motion(replace(late_config,rtol=2e-11,atol=2e-13,max_step_s=.002))
    late_convergence={
        'terminal_time_difference_s':abs(late['terminal_onset_s']-late_tight['terminal_onset_s']),
        'final_contact_phase_difference_rad':abs(late['contact_phase_rad'][-1]-late_tight['contact_phase_rad'][-1]),
        'final_rim_phase_difference_rad':abs(late['rim_phase_rad'][-1]-late_tight['rim_phase_rad'][-1]),
        'final_energy_difference_J':abs(late['mechanical_energy_J'][-1]-late_tight['mechanical_energy_J'][-1]),
    }
    assert late_convergence['terminal_time_difference_s']<1e-6
    assert late_convergence['final_contact_phase_difference_rad']<1e-5
    assert late_convergence['final_rim_phase_difference_rad']<1e-5
    generate_motion.cache_clear()
    repeat=generate_motion(curated)
    for key,value in rolling.items():
        if isinstance(value,np.ndarray):
            assert np.array_equal(value,repeat[key]),key
    late_repeat=generate_motion(late_config)
    for key,value in late.items():
        if isinstance(value,np.ndarray):
            assert np.array_equal(value,late_repeat[key]),key
    cache_started=time.perf_counter()
    cached=generate_motion(curated)
    cache_seconds=time.perf_counter()-cache_started
    assert cached is repeat
    unresolved=generate_motion(replace(curated,max_physical_seconds=.1))
    assert unresolved['status']=='UNRESOLVED' and unresolved['terminal_onset_s'] is None
    invalid_launch=False
    try:
        generate_motion(replace(curated,max_musical_contact_hz=1.))
    except ValueError as error:
        invalid_launch='descriptor_rate_invalid_at_launch' in str(error)
    assert invalid_launch
    export_motion('curated_roll_motion',rolling)
    export_motion('proposed_default_failure_motion',original)
    export_motion('curated_late_motion',late)
    report={
        'model_version':MODEL_VERSION,
        'status':'Python constraint-eliminated rolling reference; not Kotlin; not heard',
        'repository_read_commit':'4e36a2bf8cf8bfb07dba35379558b54429b5d73d',
        'curated_config':rolling['config'],
        'curated_diagnostics':rolling['diagnostics'],
        'late_config':late['config'],
        'late_diagnostics':late['diagnostics'],
        'proposed_default_config':original['config'],
        'proposed_default_diagnostics':original['diagnostics'],
        'convergence':convergence,
        'late_convergence':late_convergence,
        'deterministic_regenerated_arrays':True,
        'warm_cache_seconds_python':cache_seconds,
        'unresolved_horizon_correctly_flagged':True,
        'invalid_initial_rate_rejected':invalid_launch,
        'time_mapping':'t_physical = physical_seconds_per_musical_second * t_musical; all phases/events use it',
        'acoustic_power_mapping':'musical_resistance_power_W = physical_seconds_per_musical_second * resistance_power_W',
        'assumptions':[
            'Uniform rigid cylinder with ideal sharp rim, no slip, one instantaneous contact.',
            'Horizontal angular-velocity resistance is phenomenological; coefficient map is shape, not measurement.',
            'Static friction coefficient .8 is assumed, not measured; trajectories end when required margin is exhausted.',
            '7-degree curated cutoff is an authored exit; no real-world final collapse is simulated.',
            'No acoustic state feeds force back into the rigid motion.',
        ],
        'limits':[
            'This is a small numerical prototype, not complete validation over the production macro domain.',
            'Descriptor residuals marked algebraic are guaranteed by state elimination; independent finite differences are in independent-mechanics-audit.json.',
            'The original default fails rolling feasibility before its chosen terminal threshold; it must fade as FAILURE, not shudder as success.',
            'Python timings on this executor do not establish Kotlin or phone render performance.',
            'The initial/dissipative motion was not checked against experimental disk measurements.',
        ],
        'owner_listening':{
            'prior_synthetic_acoustic_proof':'User said Sounds pretty cool.',
            'current_mechanical_motion':'Not heard by owner yet.'},
    }
    destination=HERE/'mechanics_report.json'
    destination.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'report':str(destination),'curated':report['curated_diagnostics'],
                      'convergence':convergence},indent=2))


if __name__=='__main__':
    main()
