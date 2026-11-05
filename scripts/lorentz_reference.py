"""Frozen raw equations from the approved third LORENTZ Python audition.
NumPy-only independent reference; it does not reproduce Kotlin's output stage.
Regenerate the sparse fixture with: python3 scripts/generate_lorentz_reference.py
"""
import numpy as np

DEFAULTS = dict(TUNE=.5, CAPACITOR=.7, GAP=.2, IMPACT=.6, WELD=.5, HOLD=0.)
MODEL_VERSION = 3

def smooth(x):
    x = np.clip(x, 0., 1.)
    return x*x*x*(10+x*(-15+6*x))

def plan(macros):
    m = {k: float(np.clip(macros.get(k, v), 0, 1))
         if np.isfinite(macros.get(k, v)) else v for k, v in DEFAULTS.items()}
    midi = 24 + int(np.floor(24*m['TUNE']+.5))
    hz = 440*2**((midi-69)/12)
    ratio = 40*2**(2*(m['GAP']-.2))
    # A wider gap stretches the flight as well as increasing its span. Keep
    # the default unchanged and avoid expanding the flyer's upper bandwidth.
    k = 35*10**m['CAPACITOR'] / 2**(1.5*(m['GAP']-.2))
    collision = np.log(ratio)/k
    t60 = (.12*10**m['WELD'])*(1-m['HOLD'])+1.2*m['HOLD']
    seconds = min(1.45, collision+1.1*t60+.010)
    return m, hz, ratio, k, collision, t60, seconds

def blep(p, dt):
    a = np.clip(p/dt, 0, 1)
    b = np.clip((p-1)/dt, -1, 0)
    return np.where(p < dt, 2*a-a*a-1, 0)+np.where(p > 1-dt, b*b+2*b+1, 0)

def settling_phase(age, seconds, hz, start_ratio, end_ratio):
    """Integral of a frequency ratio that bends quadratically toward unison.

    Integrating the moving ratio avoids the unintended extra frequency term
    that would result from multiplying a changing ratio directly by time.
    """
    u = np.minimum(age, seconds)
    return hz*(end_ratio*age+(start_ratio-end_ratio)*
               (u-u*u/seconds+u*u*u/(3*seconds*seconds)))

def render(voice, macros=None, rate=176_400):
    m, hz, ratio, k, tc, t60, seconds = plan(macros or {})
    t = np.arange(int(np.ceil(seconds*rate)))/rate
    # Analytic phase integral: the flyer crosses f0 exactly at tc, then holds f0.
    u = np.minimum(t, tc)
    flyer_phase = hz*ratio/k*(-np.expm1(-k*u))+hz*np.maximum(t-tc, 0)
    flyer_hz = hz*ratio*np.exp(-k*u)
    target_phase = hz*t
    target = np.sin(2*np.pi*target_phase)
    p = np.mod(flyer_phase, 1.)
    if voice == 'FUSE':
        flyer = np.sin(2*np.pi*p)
    else:
        dt = flyer_hz/rate
        flyer = np.where(p < .5, 1., -1.)+blep(p, dt)-blep(np.mod(p+.5, 1), dt)
    age = np.maximum(t-tc, 0)
    # IMPACT owns contact duration and roughness, not merely gain. A smooth
    # entry and release retire the entire collision branch at a finite time.
    impact = m['IMPACT']
    contact_seconds = (.010 + .044*impact if voice == 'FUSE'
                       else .012 + .066*impact)
    contact = smooth(age/.0015)*(1-smooth((age-.003)/(contact_seconds-.003)))
    progress = np.clip(age/contact_seconds, 0, 1)
    mod_phase = settling_phase(age,contact_seconds,hz,2.37,1.)
    secondary_phase = settling_phase(age,contact_seconds,hz,5.13,2.)
    if voice == 'FUSE':
        # One continuous compression; the modulation resolves into harmonics.
        modulator = np.sin(2*np.pi*mod_phase)+.12*impact*np.sin(2*np.pi*secondary_phase)
        index = (1+5*impact)*contact
        root_share, collision_drive = .65, 1+4*impact
        contact_mix = .85*impact**.75*contact
    else:
        # Smooth pressure ripples belong to this finite contact. They modulate
        # the same branch, with no new impacts, feedback, or extra noise layer.
        ripple = 1-.6*impact**2*np.sin(np.pi*(1+4*impact)*progress)**2
        modulator = np.sin(2*np.pi*mod_phase)+.55*impact*np.sin(2*np.pi*secondary_phase)
        index = (1+9*impact)*contact*ripple
        root_share, collision_drive = .52, 1+7*impact
        contact_mix = .9*impact**.75*contact*(.65+.35*ripple)
    pm = np.sin(2*np.pi*target_phase+index*modulator)
    drive = 1+3*m['WELD']
    base_weld = .7*target+.3*np.tanh(drive*target)/np.tanh(drive)
    collision = root_share*target+(1-root_share)*np.tanh(collision_drive*pm)/np.tanh(collision_drive)
    welded = (1-contact_mix)*base_weld+contact_mix*collision
    a = smooth((t-tc)/.0015)
    # Convex mix keeps |raw| <= 1 independently of the finishing stage.
    raw = ((1-a)*(.75*flyer+.25*target)+a*welded)
    # At wider GAP the powered flight rises into contact instead of staying at
    # full strength throughout. The rise is a bounded voicing law, not physics.
    approach = smooth(np.minimum(t/tc, 1))
    flight_gain = 1-.3*m['GAP']*(1-approach)
    env = smooth(t/.0003)*flight_gain*np.exp(-np.log(1000)*age/t60)
    raw *= env
    return raw, dict(hz=hz, collision_s=float(tc), lock_complete_s=float(tc+.0015),
                     contact_end_s=float(tc+contact_seconds),
                     t60_s=t60, seconds=len(raw)/rate, model_version=MODEL_VERSION)
