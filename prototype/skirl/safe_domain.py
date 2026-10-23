"""Calibrate authored, limited Skirl control domains against the Python reference.

Exploration measures the first .72 required-friction crossing, leaving margin
below the reference's assumed available static friction .8. This does not change
or repair the original reference. Final domain candidates are tested separately.

Reproduce in this order:
    python3 safe_domain.py --validate
    python3 safe_domain.py --augment
    python3 safe_domain.py --spot-checks
With no arguments, the script reproduces the original 77-case exploration.
"""
import concurrent.futures
import dataclasses
import json
import math
from pathlib import Path
import sys
import time
from scipy.optimize import brentq

import numpy as np
from scipy.integrate import solve_ivp

sys.path.insert(0, str(Path(__file__).resolve().parent / 'reference'))
import mechanics as reference

ROOT = Path(__file__).parent


def trajectory_case(case):
    angle, mu, target, tight = case[:4]
    precise=len(case)>4 and case[4]
    config = reference.MotionConfig(lean=(angle-8)/27, brake=.4,
        mu_roll_override=mu, max_physical_seconds=12,
        rtol=2e-10 if precise else 2e-9 if tight else 3e-7,
        atol=2e-12 if precise else 2e-11 if tight else 3e-9,
        max_step_s=.002 if precise else .004 if tight else .008,
        descriptor_rate_hz=300.)
    def ode(t,y): return reference.rhs(t,y,config)
    def terminal(t,y): return reference.evaluate_state(t,y,config)['alpha']-math.radians(target)
    def friction(t,y): return .72-reference.evaluate_state(t,y,config)['friction_ratio']
    def normal(t,y): return reference.evaluate_state(t,y,config)['reaction'][2]
    for event in (terminal,friction,normal): event.terminal=True; event.direction=-1
    started=time.perf_counter()
    sol=solve_ivp(ode,(0.,12.),reference.initial_state(config),method='DOP853',
        rtol=config.rtol,atol=config.atol,max_step=config.max_step_s,
        events=(terminal,friction,normal),dense_output=True)
    end=float(sol.t[-1]);times=np.linspace(0,end,math.ceil(end*300)+1)
    states=sol.sol(times).T
    rows=[reference.evaluate_state(t,y,config) for t,y in zip(times,states)]
    reason=next((name for name,event in zip(('terminal','friction_margin','normal'),sol.t_events) if len(event)),'horizon')
    energies=np.array([row['energy'] for row in rows]);work=states[:,-1]
    return {'angle_deg':angle,'mu':mu,'target_deg':target,'tight':tight,
        'reason':reason,'duration_s':end,'nfev':sol.nfev,
        'end_angle_deg':math.degrees(rows[-1]['alpha']),
        'friction_max':max(row['friction_ratio'] for row in rows),
        'normal_min_N':min(row['reaction'][2] for row in rows),
        'contact_start_hz':rows[0]['contact_rate_hz'],
        'contact_end_hz':rows[-1]['contact_rate_hz'],
        'energy_error_relative':float(max(abs(energies-energies[0]+work))/energies[0]),
        'runtime_s':time.perf_counter()-started,
        'solver_rtol':config.rtol,'solver_max_step_s':config.max_step_s}


def run_cases(cases,path):
    results=[]
    with concurrent.futures.ProcessPoolExecutor(max_workers=4) as pool:
        for result in pool.map(trajectory_case,cases):
            results.append(result)
    path.write_text(json.dumps(results,indent=2)+'\n')
    return results


def parameters(voice, lean, brake, collapse):
    if voice == 'ROLL':
        alpha=18+8*lean
        return alpha, .006+.004*brake, .42*alpha-1.1+2*(1-collapse)
    alpha=8+4*lean
    return alpha, .004+.002*brake, .42*alpha-1.4+1.2*(1-collapse)


def domain_case(case):
    voice, lean, brake, tight = case
    alpha,mu,last_target=parameters(voice,lean,brake,1.)
    config=reference.MotionConfig(lean=(alpha-8)/27,brake=.4,
        mu_roll_override=mu,max_physical_seconds=8,
        rtol=2e-9 if tight else 3e-7,atol=2e-11 if tight else 3e-9,
        max_step_s=.004 if tight else .008,descriptor_rate_hz=500.)
    def ode(t,y):return reference.rhs(t,y,config)
    def terminal(t,y):return reference.evaluate_state(t,y,config)['alpha']-math.radians(last_target)
    def friction(t,y):return .72-reference.evaluate_state(t,y,config)['friction_ratio']
    for event in (terminal,friction):event.terminal=True;event.direction=-1
    sol=solve_ivp(ode,(0,8),reference.initial_state(config),method='DOP853',
        rtol=config.rtol,atol=config.atol,max_step=config.max_step_s,
        events=(terminal,friction),dense_output=True)
    times=np.linspace(0,float(sol.t[-1]),math.ceil(sol.t[-1]*500)+1)
    states=sol.sol(times).T
    rows=[reference.evaluate_state(t,y,config) for t,y in zip(times,states)]
    reason='terminal' if len(sol.t_events[0]) else 'friction_margin' if len(sol.t_events[1]) else 'horizon'
    alpha_values=np.array([row['alpha'] for row in rows])
    collapse_cases=[]
    for collapse in (0.,.25,.5,.75,1.):
        _,_,target=parameters(voice,lean,brake,collapse)
        target_rad=math.radians(target)
        ix=np.flatnonzero(alpha_values<=target_rad+1e-12)
        if len(ix):
            index=int(ix[0]);left=times[max(0,index-1)];right=times[index]
            end=(float(sol.t[-1]) if collapse==1. and reason=='terminal' else
                 brentq(lambda t:reference.evaluate_state(t,sol.sol(t),config)['alpha']-target_rad,left,right)
                 if left<right and alpha_values[index]<=target_rad else right)
            endrow=reference.evaluate_state(end,sol.sol(end),config)
            collapse_cases.append({'collapse':collapse,'terminal_deg':target,
                'duration_s':end,'end_contact_hz':endrow['contact_rate_hz'],
                'max_friction':max(row['friction_ratio'] for row,t in zip(rows,times) if t<=end),
                'complete':True})
        else:collapse_cases.append({'collapse':collapse,'terminal_deg':target,'complete':False})
    energies=np.array([row['energy'] for row in rows]);work=states[:,-1]
    return {'voice':voice,'lean':lean,'brake':brake,'initial_deg':alpha,'mu':mu,
        'tight':tight,'reason':reason,'collapse_cases':collapse_cases,
        'max_friction':max(row['friction_ratio'] for row in rows),
        'min_normal_N':min(row['reaction'][2] for row in rows),
        'energy_error_relative':float(max(abs(energies-energies[0]+work))/energies[0]),
        'nfev':sol.nfev}


def validate_domain(steps=5,tight=True):
    cases=[(voice,float(lean),float(brake),tight) for voice in ('ROLL','WHIRR')
           for lean in np.linspace(0,1,steps) for brake in np.linspace(0,1,steps)]
    with concurrent.futures.ProcessPoolExecutor(max_workers=4) as pool:
        results=list(pool.map(domain_case,cases))
    subcases=[c for row in results for c in row['collapse_cases']]
    durations=[c['duration_s'] for c in subcases if c['complete']]
    summary={'complete':all(c['complete'] for c in subcases),
        'trajectory_count':len(results),'macro_grid_cases':len(subcases),
        'duration_min_s':min(durations),'duration_max_s':max(durations),
        'max_required_friction':max(row['max_friction'] for row in results),
        'min_normal_reaction_N':min(row['min_normal_N'] for row in results),
        'max_energy_work_error_relative':max(row['energy_error_relative'] for row in results)}
    report={'status':'Python reference calibration; finite grid, not proof of a continuum or Kotlin behavior',
        'time_scale':1.,'assumed_available_static_friction':.8,'calibration_friction_ceiling':.72,
        'mappings':{'ROLL':{'initial_deg':'18+8*LEAN','mu':'.006+.004*BRAKE',
            'terminal_deg':'.42*initial_deg-1.1+2*(1-COLLAPSE)'},
            'WHIRR':{'initial_deg':'8+4*LEAN','mu':'.004+.002*BRAKE',
            'terminal_deg':'.42*initial_deg-1.4+1.2*(1-COLLAPSE)'}},
        'tolerances':{'rtol':2e-9 if tight else 3e-7,'atol':2e-11 if tight else 3e-9,
            'max_step_s':.004 if tight else .008,'diagnostic_sampling_hz':500},
        'summary':summary,'cases':results}
    (ROOT/'safe_domain_report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(summary,indent=2),flush=True)
    for voice in ('ROLL','WHIRR'):
        part=[row for row in results if row['voice']==voice]
        ds=[c['duration_s'] for row in part for c in row['collapse_cases'] if c['complete']]
        print(voice,'duration',min(ds),max(ds),'friction',max(row['max_friction'] for row in part),flush=True)
    return report


def augment_report():
    report=json.loads((ROOT/'safe_domain_report.json').read_text())
    rng=np.random.default_rng(20261023)
    cases=[(voice,float(lean),float(brake),True) for voice in ('ROLL','WHIRR')
           for lean,brake in rng.random((16,2))]
    cases.extend([('ROLL',.55,.4,True),('WHIRR',0.,.2,True)])
    with concurrent.futures.ProcessPoolExecutor(max_workers=4) as pool:
        extra=list(pool.map(domain_case,cases))
    report['between_grid_seed']=20261023
    report['between_grid_pairs_per_voice']=16
    report['cases'].extend(extra)
    result=report['cases']
    complete=[c for row in result for c in row['collapse_cases']]
    durations=[c['duration_s'] for c in complete if c['complete']]
    report['summary'].update(complete=all(c['complete'] for c in complete),
        trajectory_count=len(result),macro_grid_cases=len(complete),
        duration_min_s=min(durations),duration_max_s=max(durations),
        max_required_friction=max(row['max_friction'] for row in result),
        min_normal_reaction_N=min(row['min_normal_N'] for row in result),
        max_energy_work_error_relative=max(row['energy_error_relative'] for row in result))
    report['recommended_defaults']={'ROLL':{'LEAN':.55,'BRAKE':.4,'COLLAPSE':.65},
                                    'WHIRR':{'LEAN':0.,'BRAKE':.2,'COLLAPSE':1.}}
    report['authored_mapping_notes']=[
        'Physical ranges intentionally replace the original unsafe all-voice macro map.',
        'COLLAPSE is an explicitly launch-dependent chosen threshold. No solver failure is repaired or relabelled.',
        'The exact original curated trajectories remain independent regression fixtures.',
        'All five COLLAPSE exits share the same valid rolling prefix; later thresholds are integrated directly.',
        'Finite grid plus between-grid sampling is evidence, not a mathematical proof over a continuum.',
        'Production must retain normal-force, friction, rate, finite-state and work-limit guards.',
        'Mechanical macro activity is demonstrated by timing and trajectory changes; audibility after Kotlin leveling still needs probes and owner listening.'
    ]
    (ROOT/'safe_domain_report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report['summary'],indent=2),flush=True)
    for row in extra[-2:]: print(json.dumps(row,indent=2),flush=True)


def spot_checks():
    report=json.loads((ROOT/'safe_domain_report.json').read_text())
    cases=[(22.4,.0076,.42*22.4-1.1+2*(1-.65),True),
           (8.,.004,1.96,True,True),(26.,.007,9.82,True,True)]
    with concurrent.futures.ProcessPoolExecutor(max_workers=3) as pool:
        rows=list(pool.map(trajectory_case,cases))
    report['exact_default_measurements']={'ROLL':rows[0],
        'WHIRR':next(row for row in report['cases'] if row['voice']=='WHIRR' and row['lean']==0. and row['brake']==.2)['collapse_cases'][-1]}
    convergence=[]
    for precise,voice,lean,brake in zip(rows[1:],('WHIRR','ROLL'),(0.,1.),(0.,.25)):
        nominal=next(row for row in report['cases'] if row['voice']==voice and row['lean']==lean and row['brake']==brake)
        nominal_end=nominal['collapse_cases'][-1]
        convergence.append({'voice':voice,'precise':precise,
            'duration_difference_s':abs(precise['duration_s']-nominal_end['duration_s']),
            'end_contact_rate_difference_hz':abs(precise['contact_end_hz']-nominal_end['end_contact_hz']),
            'nominal_energy_error_relative':nominal['energy_error_relative']})
    report['tighter_spot_checks']=convergence
    (ROOT/'safe_domain_report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'exact_defaults':report['exact_default_measurements'],'convergence':convergence},indent=2))


if __name__=='__main__':
    if '--spot-checks' in sys.argv:
        spot_checks()
        raise SystemExit
    if '--augment' in sys.argv:
        augment_report()
        raise SystemExit
    if '--validate' in sys.argv:
        validate_domain()
        raise SystemExit
    cases=[(angle,mu,.75,False) for angle in (8,9,10,11,12,18,20,22,22.85,24,26)
            for mu in (.0035,.004683281573,.006,.008,.009589466384,.011,.013)]
    results=run_cases(cases,ROOT/'exploration.json')
    for row in results:
        print(f"{row['angle_deg']:5.2f} {row['mu']:.6f} {row['reason']:15s} "
              f"end={row['end_angle_deg']:5.2f} t={row['duration_s']:5.2f} "
              f"friction={row['friction_max']:.4f}",flush=True)
