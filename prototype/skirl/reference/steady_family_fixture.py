#!/usr/bin/env python3
"""Algebraic conservative steady-family fixture for the proposed Skirl geometry.

This is NOT a time-integrated dissipative rolling trajectory or a validated solver.
It checks an independently derived exact steady solution of the stated ideal
sharp-rim, no-slip cylinder equations with ZERO resistance torque.
The geometry and linear macro maps are design hypotheses, not disk measurements.

Derivation (world cylindrical basis e_r,e_phi,k; alpha is constant):
  a = h/2; s = sin(alpha); c = cos(alpha)
  n = s*e_r + c*k
  b = R*c-a*s; z = R*s+a*c; r_contact = b*e_r-z*k
  omega = Omega*k + sigma*n
  no-slip with stationary COM gives sigma = -Omega*b/R
  dn/dt = Omega*s*e_phi = omega cross n
  L_r = s*[I_axis*sigma+(I_axis-I_transverse)*Omega*c]
  dL/dt = Omega*L_r*e_phi
  F_contact = m*g*k; r_contact cross F_contact = -m*g*b*e_phi
  Therefore:
  Omega^2 = m*g*b / {s*[I_transverse*c-I_axis*(a/R)*s]}.

The thin-disk limit a=0,I_transverse=m*R^2/4 yields
Omega^2 = 4*g/[R*sin(alpha)]. This is precession, not omega dot n.
Along this steady family, omega dot n = Omega*(a/R)*sin(alpha).
Evaluating a family of equilibria at different alpha does not demonstrate that
a disk with resistance follows this family, nor that the contact excitation
will audibly traverse knock, flutter and whirr.

Run: python3 steady_family_fixture.py [optional-output.json]
"""

import json
import math
import pathlib
import sys


def cross(x, y):
    return (x[1]*y[2]-x[2]*y[1], x[2]*y[0]-x[0]*y[2],
            x[0]*y[1]-x[1]*y[0])


def dot(x, y):
    return sum(a*b for a, b in zip(x, y))


def add(x, y):
    return tuple(a+b for a, b in zip(x, y))


def scale(s, x):
    return tuple(s*a for a in x)


def norm(x):
    return math.sqrt(dot(x, x))


MASS, RADIUS, THICKNESS, GRAVITY = .3, .04, .01, 9.81
I_AXIS = .5*MASS*RADIUS**2
I_TRANSVERSE = MASS*(3*RADIUS**2+THICKNESS**2)/12


def fixture(degrees):
    alpha = math.radians(degrees)
    s, c = math.sin(alpha), math.cos(alpha)
    a = THICKNESS/2
    b = RADIUS*c-a*s
    z = RADIUS*s+a*c
    denominator = s*(I_TRANSVERSE*c-I_AXIS*(a/RADIUS)*s)
    if b <= 0 or denominator <= 0:
        raise ValueError('This stationary-COM family does not cover this angle')
    precession = math.sqrt(MASS*GRAVITY*b/denominator)
    k = (0., 0., 1.)
    n = (s, 0., c)
    r = (b, 0., -z)
    sigma = -precession*b/RADIUS
    omega = add(scale(precession, k), scale(sigma, n))
    n_dot = (0., precession*s, 0.)
    omega_dot = scale(sigma, n_dot)
    selected_r_dot = (0., precession*b, 0.)
    momentum = add(scale(I_TRANSVERSE, omega),
                   scale((I_AXIS-I_TRANSVERSE)*dot(omega, n), n))
    momentum_dot = (0., precession*momentum[0], 0.)
    reaction = (0., 0., MASS*GRAVITY)
    torque = cross(r, reaction)
    # COM velocity/acceleration are both zero on this exact steady family.
    no_slip_velocity = cross(omega, r)
    no_slip_acceleration = add(cross(omega_dot, r),
                               cross(omega, selected_r_dot))
    residuals = {
        'contact_height_m': abs(z+r[2]),
        'unit_axis': abs(norm(n)-1),
        'axis_kinematics_per_s': norm(add(cross(omega, n), scale(-1, n_dot))),
        'no_slip_velocity_m_s': norm(no_slip_velocity),
        'no_slip_acceleration_m_s2': norm(no_slip_acceleration),
        'linear_force_N': norm(add(reaction, (0., 0., -MASS*GRAVITY))),
        'angular_momentum_balance_Nm': norm(add(momentum_dot, scale(-1, torque))),
    }
    assert all(value < 1e-10 for value in residuals.values()), residuals
    return {
        'inclination_degrees': degrees,
        'precession_hz': precession/(2*math.pi),
        'omega_dot_n_rad_s': dot(omega, n),
        'omega_world_magnitude_rad_s': norm(omega),
        'normal_reaction_N': reaction[2],
        'required_tangential_reaction_N': 0.,
        'com_height_m': z,
        'residuals': residuals,
    }


def main():
    cases = {name: fixture(deg) for name, deg in [
        ('default_start', 8+27*.55),
        ('default_exit', 7-6*.65),
        ('min_lean_start', 8), ('early_exit', 7),
        ('max_lean_start', 35), ('late_exit', 1),
    ]}
    report = {
        'provenance': __doc__,
        'geometry': {'mass_kg': MASS, 'radius_m': RADIUS,
                     'thickness_m': THICKNESS, 'g_m_s2': GRAVITY,
                     'I_axis_kg_m2': I_AXIS,
                     'I_transverse_kg_m2': I_TRANSVERSE},
        'proposed_macro_maps': {'initial_alpha_degrees': '8+27*LEAN',
                                'exit_alpha_degrees': '7-6*COLLAPSE'},
        'cases': cases,
        'ratios': {
            'default_exit_over_start': cases['default_exit']['precession_hz']/cases['default_start']['precession_hz'],
            '8_to_7_degrees': cases['early_exit']['precession_hz']/cases['min_lean_start']['precession_hz'],
            '35_to_1_degrees': cases['late_exit']['precession_hz']/cases['max_lean_start']['precession_hz'],
        },
        'limits': [
            'No dissipative trajectory has been integrated.',
            'No solver convergence, rolling domain, or 1-degree terminal boundary has been validated.',
            'No acoustic output or listening judgement is established by this fixture.',
            'One common time scaling changes both rates equally, preserving their ratio.',
        ],
    }
    destination = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).with_suffix('.json')
    destination.write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps({'output': str(destination), 'ratios': report['ratios'],
                      'cases': {name: value['precession_hz'] for name, value in cases.items()}}, indent=2))


if __name__ == '__main__':
    main()
