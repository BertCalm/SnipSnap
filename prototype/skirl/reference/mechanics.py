"""Skirl Python rolling reference: constraint-eliminated Newton--Euler dynamics.

MODEL_VERSION 1 is an independently reduced ideal sharp-rim cylinder model, not
a measurement of a real disk. No slip and contact height are eliminated from the
state, rather than imposed with velocity corrections. All vectors are world-frame.
The orientation quaternion q=[w,x,y,z] rotates body vectors into world vectors.

With n=q*k*q^-1, selected lowest-rim offset r(n), and n_dot=omega cross n:
  v_COM = -omega cross r
  a_COM = -omega_dot cross r - omega cross r_dot
  [I+m*(|r|^2 Id-r r^T)] omega_dot =
      m*r cross (g*k-omega cross r_dot) + tau
      -(I_axis-I_transverse)*(omega dot n)*n_dot.
Here r_dot is the derivative of the *selected* point map, not omega cross r.

DOP853 advances the reduced ODE with a bounded step, tight tolerances, and dense
event bracketing. q is not renormalized in the integrator; its normalized readout
gives a smooth orientation while the raw norm error is diagnosed. Neither omega
nor v is rescaled. Energy versus integrated negative torque work is a diagnostic.
The terminal threshold is an authored cutoff, not a universal collapse prediction.
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
from functools import lru_cache
import math
import time

import numpy as np
from scipy.integrate import solve_ivp

MODEL_VERSION = 1
K = np.array([0.0, 0.0, 1.0])
IDENTITY = np.eye(3)


@dataclass(frozen=True)
class MotionConfig:
    lean: float = .55
    brake: float = .40
    collapse: float = .65
    mass_kg: float = .3
    radius_m: float = .04
    thickness_m: float = .01
    gravity_m_s2: float = 9.81
    # This maps musical seconds to physical seconds, identically for all readouts.
    physical_seconds_per_musical_second: float = 1.0
    max_physical_seconds: float = 12.0
    descriptor_rate_hz: float = 1000.0
    max_musical_contact_hz: float = 200.0
    assumed_static_friction: float = .8
    epsilon_omega_rad_s: float = .1
    rtol: float = 2e-9
    atol: float = 2e-11
    max_step_s: float = .004
    max_rhs_evaluations: int = 250000
    # An explicit override supports a conservative zero-resistance fixture.
    mu_roll_override: float | None = None
    model_version: int = MODEL_VERSION

    def __post_init__(self):
        for name in ('lean', 'brake', 'collapse'):
            value = getattr(self, name)
            if not np.isfinite(value) or not 0 <= value <= 1:
                raise ValueError(f'{name} must be finite and in [0,1]')
        if self.model_version != MODEL_VERSION:
            raise ValueError('unsupported mechanical model version')
        for name in ('mass_kg', 'radius_m', 'thickness_m', 'gravity_m_s2',
                     'physical_seconds_per_musical_second', 'max_physical_seconds',
                     'descriptor_rate_hz', 'max_musical_contact_hz',
                     'assumed_static_friction', 'epsilon_omega_rad_s',
                     'rtol', 'atol', 'max_step_s'):
            if not np.isfinite(getattr(self, name)) or getattr(self, name) <= 0:
                raise ValueError(f'{name} must be finite and positive')
        if self.mu_roll_override is not None and (
                not np.isfinite(self.mu_roll_override) or self.mu_roll_override < 0):
            raise ValueError('mu_roll_override must be finite and nonnegative')
        if not isinstance(self.max_rhs_evaluations, int) or self.max_rhs_evaluations <= 0:
            raise ValueError('max_rhs_evaluations must be a positive integer')

    @property
    def initial_alpha_rad(self):
        return math.radians(8 + 27*self.lean)

    @property
    def terminal_alpha_rad(self):
        return math.radians(7 - 6*self.collapse)

    @property
    def mu_roll(self):
        # Shape mapping, not a measured resistance coefficient. Positive floor.
        return (.002 + .030*self.brake**1.5 if self.mu_roll_override is None
                else self.mu_roll_override)

    @property
    def inertia(self):
        return (.5*self.mass_kg*self.radius_m**2,
                self.mass_kg*(3*self.radius_m**2+self.thickness_m**2)/12)


def cross(a, b):
    # np.cross incurs shape-dispatch overhead on every ODE evaluation.
    return np.array([a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2],
                     a[0]*b[1]-a[1]*b[0]])


def rotation_matrix(q):
    w, x, y, z = q / np.linalg.norm(q)
    return np.array([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)],
                     [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)],
                     [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])


def initial_state(config=MotionConfig()):
    alpha = config.initial_alpha_rad
    s, c = math.sin(alpha), math.cos(alpha)
    a, R, m, g = config.thickness_m/2, config.radius_m, config.mass_kg, config.gravity_m_s2
    ia, it = config.inertia
    b = R*c-a*s
    denom = s*(it*c-ia*(a/R)*s)
    if b <= 0 or denom <= 0:
        raise ValueError('steady launch family does not cover this geometry/tilt')
    precession = math.sqrt(m*g*b/denom)
    n = np.array([s, 0., c])
    omega = precession*(K-(b/R)*n)
    q = np.array([math.cos(alpha/2), 0., math.sin(alpha/2), 0.])
    return np.r_[q, omega, 0., 0., 0.]


def evaluate_state(t, y, config=MotionConfig()):
    q, omega = y[:4], y[4:7]
    rotation = rotation_matrix(q)
    n = rotation[:, 2]
    c = float(np.clip(n[2], -1., 1.))
    s = math.sqrt(max(1-c*c, 1e-16))
    alpha = math.atan2(s, c)
    R, a, m, g = config.radius_m, config.thickness_m/2, config.mass_kg, config.gravity_m_s2
    ia, it = config.inertia
    r = (R*c/s-a)*n - (R/s)*K
    n_dot = cross(omega, n)
    c_dot = n_dot[2]
    r_dot = (R*c/s-a)*n_dot + (R*c_dot/s**3)*(n-c*K)
    inertia = it*IDENTITY + (ia-it)*np.outer(n,n)
    roll = omega-omega[2]*K
    torque = -config.mu_roll*m*g*R * roll/math.sqrt(float(roll@roll)+config.epsilon_omega_rad_s**2)
    mass_matrix = inertia + m*((r@r)*IDENTITY-np.outer(r,r))
    rhs_angular = (m*cross(r, g*K-cross(omega,r_dot)) + torque
                   -(ia-it)*(omega@n)*n_dot)
    omega_dot = np.linalg.solve(mass_matrix, rhs_angular)
    velocity = -cross(omega,r)
    acceleration = -cross(omega_dot,r)-cross(omega,r_dot)
    reaction = m*(acceleration+g*K)
    energy = .5*m*(velocity@velocity)+.5*(omega@inertia@omega)+m*g*(-r[2])
    power = float(-torque@omega)
    contact_rate = (r[0]*r_dot[1]-r[1]*r_dot[0])/(r[0]**2+r[1]**2)/(2*math.pi)
    friction = float(np.linalg.norm(reaction[:2])/max(reaction[2],1e-16))
    return dict(q=q, rotation=rotation, n=n, r=r, r_dot=r_dot, n_dot=n_dot,
                omega=omega, omega_dot=omega_dot, torque=torque,
                inertia=inertia, mass_matrix=mass_matrix, velocity=velocity,
                acceleration=acceleration, reaction=reaction, alpha=alpha,
                energy=float(energy), resistance_power=power,
                contact_rate_hz=float(contact_rate), friction_ratio=friction)


def rhs(t, y, config=MotionConfig()):
    d = evaluate_state(t,y,config)
    qw, qv = y[0], y[1:4]
    omega = y[4:7]
    q_dot = .5*np.r_[-omega@qv, qw*omega+cross(omega,qv)]
    return np.r_[q_dot, d['omega_dot'], d['velocity'][:2], d['resistance_power']]


@lru_cache(maxsize=12)
def generate_motion(config=MotionConfig()):
    started = time.perf_counter()
    calls = 0
    def ode(t,y):
        nonlocal calls
        calls += 1
        if calls > config.max_rhs_evaluations:
            raise RuntimeError('mechanical solver work limit reached')
        return rhs(t,y,config)
    def threshold(t,y):
        return evaluate_state(t,y,config)['alpha']-config.terminal_alpha_rad
    def normal(t,y):
        return evaluate_state(t,y,config)['reaction'][2]
    def friction(t,y):
        return config.assumed_static_friction-evaluate_state(t,y,config)['friction_ratio']
    def rate(t,y):
        return (config.max_musical_contact_hz -
                abs(evaluate_state(t,y,config)['contact_rate_hz'])*config.physical_seconds_per_musical_second)
    for event in (threshold,normal,friction,rate):
        event.terminal=True
        event.direction=-1
    launch=initial_state(config)
    # Crossing detectors cannot detect a constraint that is invalid at launch.
    initial_validity = [normal(0., launch), friction(0., launch), rate(0., launch)]
    if min(initial_validity) <= 0:
        reasons_at_launch = ('normal_reaction_invalid_at_launch',
                             'static_friction_invalid_at_launch',
                             'descriptor_rate_invalid_at_launch')
        reason_at_launch = next(name for name, margin in zip(reasons_at_launch, initial_validity)
                                if margin <= 0)
        raise ValueError('FAILURE: '+reason_at_launch)
    sol=solve_ivp(ode, (0.,config.max_physical_seconds), launch,
                  method='DOP853',rtol=config.rtol,atol=config.atol,
                  max_step=config.max_step_s,dense_output=True,
                  events=(threshold,normal,friction,rate))
    if not sol.success:
        raise RuntimeError(f'rolling solver failed: {sol.message}')
    reasons=['chosen_terminal_threshold','normal_reaction_lost',
             'static_friction_exceeded','descriptor_rate_limit']
    reason=next((reasons[i] for i,event in enumerate(sol.t_events) if len(event)),
                'unresolved_horizon')
    status=('TERMINAL_TRANSITION' if reason=='chosen_terminal_threshold' else
            'UNRESOLVED' if reason=='unresolved_horizon' else 'FAILURE')
    end_physical=float(sol.t[-1])
    scale=config.physical_seconds_per_musical_second
    end=end_physical/scale
    times=np.linspace(0,end,max(2,int(math.ceil(end*config.descriptor_rate_hz))+1))
    states=sol.sol(times*scale).T
    rows=[evaluate_state(t*scale,y,config) for t,y in zip(times,states)]
    contact=np.unwrap(np.array([math.atan2(d['r'][1],d['r'][0]) for d in rows]))
    axis_phase=np.unwrap(np.array([math.atan2(d['n'][1],d['n'][0]) for d in rows]))
    # Rim map coordinate is read in body frame, independently of world azimuth.
    rim=np.unwrap(np.array([math.atan2((d['rotation'].T@d['r'])[1],
                                     (d['rotation'].T@d['r'])[0]) for d in rows]))
    marker=np.unwrap(np.array([math.atan2(d['rotation'][1,0],d['rotation'][0,0]) for d in rows]))
    energies=np.array([d['energy'] for d in rows])
    work=states[:,-1]
    error=energies-energies[0]+work
    normal_values=np.array([d['reaction'][2] for d in rows])
    no_slip=np.array([np.linalg.norm(d['velocity']+cross(d['omega'],d['r'])) for d in rows])
    height_velocity=np.array([abs(d['velocity'][2]+d['r_dot'][2]) for d in rows])
    diagnostics=dict(
        state=status,exit_reason=reason,initial_energy_J=float(energies[0]),
        final_energy_J=float(energies[-1]),integrated_resistance_work_J=float(work[-1]),
        max_abs_energy_work_error_J=float(np.max(abs(error))),
        relative_energy_work_error=float(np.max(abs(error))/energies[0]),
        min_normal_reaction_N=float(np.min(normal_values)),
        max_required_static_friction=float(max(d['friction_ratio'] for d in rows)),
        max_quaternion_norm_error=float(np.max(abs(np.linalg.norm(states[:,:4],axis=1)-1))),
        max_algebraic_no_slip_residual_m_s=float(np.max(no_slip)),
        max_height_velocity_residual_m_s=float(np.max(height_velocity)),
        min_resistance_power_W=float(min(d['resistance_power'] for d in rows)),
        start_contact_hz=float(rows[0]['contact_rate_hz']*scale),
        end_contact_hz=float(rows[-1]['contact_rate_hz']*scale),
        start_inclination_degrees=math.degrees(rows[0]['alpha']),
        end_inclination_degrees=math.degrees(rows[-1]['alpha']),
        solver_rhs_evaluations=calls,accepted_steps=len(sol.t)-1,
        physical_duration_s=end_physical,musical_duration_s=end,
        descriptor_count=len(times),python_cold_generation_s=time.perf_counter()-started,
        velocity_projection_used=False)
    result=dict(time_s=times,physical_time_s=times*scale,
                alpha_rad=np.array([d['alpha'] for d in rows]),
                contact_phase_rad=contact,axis_phase_rad=axis_phase,
                rim_phase_rad=rim,body_marker_phase_rad=marker,
                contact_rate_hz=np.array([d['contact_rate_hz']*scale for d in rows]),
                normal_N=normal_values,
                resistance_power_W=np.array([d['resistance_power'] for d in rows]),
                musical_resistance_power_W=np.array([d['resistance_power']*scale for d in rows]),
                mechanical_energy_J=energies,
                remaining_energy_J=energies-config.mass_kg*config.gravity_m_s2*config.thickness_m/2,
                omega_axis_rad_s=np.array([d['omega']@d['n'] for d in rows]),
                center_xy_m=states[:,7:9],quaternion_wxyz=states[:,:4],
                center_xyz_m=np.c_[states[:,7:9],[-d['r'][2] for d in rows]],
                contact_world_xyz_m=np.c_[states[:,7:9]+np.array([d['r'][:2] for d in rows]),np.zeros(len(rows))],
                omega_world_rad_s=states[:,4:7],reaction_N=np.array([d['reaction'] for d in rows]),
                resistance_work_J=work,cumulative_resistance_work_J=work,
                status=status,terminal_onset_s=end if status=='TERMINAL_TRANSITION' else None,
                exit_reason=reason,config=asdict(config),diagnostics=diagnostics,
                provenance='Constraint-eliminated no-slip cylinder Newton--Euler ODE; Python prototype; not measured disk mechanics')
    for value in result.values():
        if isinstance(value,np.ndarray):
            value.setflags(write=False)
    return result


if __name__=='__main__':
    import json
    print(json.dumps(generate_motion()['diagnostics'],indent=2))
