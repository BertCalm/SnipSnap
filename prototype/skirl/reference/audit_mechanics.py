"""Independent numerical checks of the Skirl Python rolling reference.

Geometry, inertia, exact precession and finite-difference Newton--Euler residuals
are evaluated here independently of mechanics.evaluate_state. This is evidence
for the specified ideal no-slip model, not measurements of a real disk.
Run next to mechanics.py: python3 audit_mechanics.py
"""
from dataclasses import asdict, replace
from pathlib import Path
import json
import math
import time

import numpy as np
from scipy.integrate import solve_ivp

import mechanics as model

HERE = Path(__file__).resolve().parent
UP = np.array([0., 0., 1.])


def matrix(q):
    """Independent scalar quaternion-to-matrix formula, body to world."""
    q = np.asarray(q)/np.linalg.norm(q)
    w, x, y, z = q
    return np.array([[w*w+x*x-y*y-z*z, 2*(x*y-w*z), 2*(x*z+w*y)],
                     [2*(x*y+w*z), w*w-x*x+y*y-z*z, 2*(y*z-w*x)],
                     [2*(x*z-w*y), 2*(y*z+w*x), w*w-x*x-y*y+z*z]])


def multiply(a, b):
    aw, av = a[0], a[1:]
    bw, bv = b[0], b[1:]
    return np.r_[aw*bw-av@bv, aw*bv+bw*av+np.cross(av,bv)]


def fields(y, cfg):
    Q = matrix(y[:4])
    n = Q@UP
    c = float(n@UP)
    s = np.linalg.norm(np.cross(n, UP))
    # Original geometric map, independently of the model's simplified form.
    u = (UP-c*n)/s
    r = -cfg.radius_m*u - cfg.thickness_m/2*n
    w = y[4:7]
    v = -np.cross(w,r)
    ia = .5*cfg.mass_kg*cfg.radius_m**2
    it = cfg.mass_kg*(3*cfg.radius_m**2+cfg.thickness_m**2)/12
    I = it*np.eye(3)+(ia-it)*np.outer(n,n)
    pos = np.r_[y[7:9], -r[2]]
    energy = .5*cfg.mass_kg*(v@v)+.5*(w@I@w)+cfg.mass_kg*cfg.gravity_m_s2*pos[2]
    roll = w-(w@UP)*UP
    torque = (-cfg.mu_roll*cfg.mass_kg*cfg.gravity_m_s2*cfg.radius_m
              * roll/np.sqrt(roll@roll+cfg.epsilon_omega_rad_s**2))
    return dict(Q=Q,n=n,r=r,w=w,v=v,I=I,L=I@w,pos=pos,E=float(energy),
                torque=torque,power=float(-torque@w))


def conservative_fixture():
    rows = []
    for degrees in (8.,22.85,35.):
        cfg = replace(model.MotionConfig(),lean=(degrees-8)/27,
                      mu_roll_override=0.,rtol=2e-11,atol=2e-13,max_step_s=.001)
        y0 = model.initial_state(cfg)
        a=cfg.thickness_m/2
        s,c=math.sin(math.radians(degrees)),math.cos(math.radians(degrees))
        ia=.5*cfg.mass_kg*cfg.radius_m**2
        it=cfg.mass_kg*(3*cfg.radius_m**2+cfg.thickness_m**2)/12
        b=cfg.radius_m*c-a*s
        precession=math.sqrt(cfg.mass_kg*cfg.gravity_m_s2*b/(s*(it*c-ia*a/cfg.radius_m*s)))
        sigma=-precession*b/cfg.radius_m
        duration=.25
        sol=solve_ivp(lambda t,y:model.rhs(t,y,cfg),(0,duration),y0,
                      method='DOP853',rtol=cfg.rtol,atol=cfg.atol,max_step=cfg.max_step_s,
                      dense_output=True)
        assert sol.success,sol.message
        alpha=math.radians(degrees)
        qtilt=np.array([math.cos(alpha/2),0.,math.sin(alpha/2),0.])
        orientation_error=[];omega_error=[];energy_error=[];com_error=[]
        initial=fields(y0,cfg)
        for t in np.linspace(0,duration,101):
            y=sol.sol(t)
            d=fields(y,cfg)
            qz=np.array([math.cos(precession*t/2),0,0,math.sin(precession*t/2)])
            qb=np.array([math.cos(sigma*t/2),0,0,math.sin(sigma*t/2)])
            exact=matrix(multiply(multiply(qz,qtilt),qb))
            expected_w=precession*UP+sigma*exact[:,2]
            orientation_error.append(np.max(abs(d['Q']-exact)))
            omega_error.append(np.linalg.norm(d['w']-expected_w))
            energy_error.append(abs(d['E']-initial['E']))
            com_error.append(np.linalg.norm(d['pos']-initial['pos']))
        row=dict(alpha_degrees=degrees,physical_seconds=duration,
                 max_rotation_matrix_error=float(max(orientation_error)),
                 max_omega_error_rad_s=float(max(omega_error)),
                 max_energy_error_J=float(max(energy_error)),
                 max_COM_displacement_m=float(max(com_error)))
        assert row['max_rotation_matrix_error']<2e-7,row
        assert row['max_omega_error_rad_s']<2e-6,row
        assert row['max_energy_error_J']<1e-8,row
        assert row['max_COM_displacement_m']<1e-8,row
        rows.append(row)
    return rows


def finite_difference_audit(cfg):
    motion=model.generate_motion(cfg)
    end=motion['physical_time_s'][-1]
    # RK45 is intentionally independent of the production DOP853 trajectory.
    sol=solve_ivp(lambda t,y:model.rhs(t,y,cfg),(0,end),model.initial_state(cfg),
                  method='RK45',rtol=5e-11,atol=5e-13,max_step=.001,
                  dense_output=True)
    assert sol.success,sol.message
    h=2e-5
    rows=[]
    for t in np.linspace(max(.002,end*.01),end-max(.002,end*.01),19):
        dm,d,dp=[fields(sol.sol(t+offset),cfg) for offset in (-h,0,h)]
        vfd=(dp['pos']-dm['pos'])/(2*h)
        afd=(dp['v']-dm['v'])/(2*h)
        lfd=(dp['L']-dm['L'])/(2*h)
        reaction=cfg.mass_kg*(afd+cfg.gravity_m_s2*UP)
        angular_residual=lfd-np.cross(d['r'],reaction)-d['torque']
        derivative_energy=(dp['E']-dm['E'])/(2*h)
        rfd=(dp['r']-dm['r'])/(2*h)
        implementation=model.evaluate_state(t,sol.sol(t),cfg)
        rows.append(dict(physical_time_s=float(t),
            no_slip_position_derivative_m_s=float(np.linalg.norm(vfd+np.cross(d['w'],d['r']))),
            angular_momentum_residual_Nm=float(np.linalg.norm(angular_residual)),
            resistance_work_residual_W=float(abs(derivative_energy+d['power'])),
            selected_rim_derivative_error_m_s=float(np.linalg.norm(rfd-implementation['r_dot'])),
            independent_reaction_error_N=float(np.linalg.norm(reaction-implementation['reaction'])),
            min_effective_inertia_eigenvalue_kg_m2=float(np.linalg.eigvalsh(implementation['mass_matrix'])[0])))
    maxima={key:max(row[key] for row in rows) for key in rows[0]
            if key not in ('physical_time_s','min_effective_inertia_eigenvalue_kg_m2')}
    smallest_inertia=min(row['min_effective_inertia_eigenvalue_kg_m2'] for row in rows)
    # First-difference truncation is deliberately reported, not called roundoff.
    assert maxima['no_slip_position_derivative_m_s']<5e-6,maxima
    assert maxima['angular_momentum_residual_Nm']<1e-5,maxima
    assert maxima['resistance_work_residual_W']<1e-5,maxima
    assert maxima['selected_rim_derivative_error_m_s']<5e-5,maxima
    assert maxima['independent_reaction_error_N']<5e-4,maxima
    assert all(row['min_effective_inertia_eigenvalue_kg_m2']>0 for row in rows)
    yend=sol.sol(end)
    reference_q=motion['quaternion_wxyz'][-1]
    reference_w=motion['omega_world_rad_s'][-1]
    return dict(audited_config=asdict(cfg),finite_difference_step_s=h,
                sampled_states=len(rows),maxima=maxima,
                min_effective_inertia_eigenvalue_kg_m2=smallest_inertia,
                RK45_versus_DOP853=dict(
                    final_rotation_matrix_error=float(np.max(abs(matrix(yend[:4])-matrix(reference_q)))),
                    final_omega_error_rad_s=float(np.linalg.norm(yend[4:7]-reference_w)),
                    final_energy_work_error_J=float(abs(fields(yend,cfg)['E']-fields(model.initial_state(cfg),cfg)['E']+yend[-1]))),
                trajectory_status=motion['status'],trajectory_exit=motion['exit_reason'],
                reached_chosen_terminal=motion['exit_reason']=='chosen_terminal_threshold',
                distinct_readouts=dict(
                    contact_phase_span_rad=float(np.ptp(motion['contact_phase_rad'])),
                    rim_phase_span_rad=float(np.ptp(motion['rim_phase_rad'])),
                    face_marker_phase_span_rad=float(np.ptp(motion['body_marker_phase_rad'])),
                    integrated_axial_spin_rad=float(np.trapezoid(motion['omega_axis_rad_s'],motion['physical_time_s'])),
                    max_unwrap_step_rad=float(np.max(abs(np.diff(motion['contact_phase_rad']))))))


def main():
    started=time.perf_counter()
    result=dict(status='Python independent mechanics audit; not Kotlin or measured real-disk validation',
                model_version=model.MODEL_VERSION,
                exact_conservative_precession=conservative_fixture(),
                dissipative_equations={
                    'original_default_late_exit':finite_difference_audit(
                        model.MotionConfig(lean=.55,brake=.4,collapse=.65)),
                    'curated_earlier_valid_exit':finite_difference_audit(
                        model.MotionConfig(lean=.55,brake=.4,collapse=0.))},
                limits=['Only the selected ideal no-slip cylinder model and tested settings are checked.',
                        'No air film, elastic deformation, sliding, real stopping prediction or audio is validated.'])
    result['python_audit_seconds']=time.perf_counter()-started
    (HERE/'independent-mechanics-audit.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))


if __name__=='__main__':
    main()
