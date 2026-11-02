"""Continuous Accord note/velocity voicing laws for the Python refinement.

The approved C3/v=.75 study is the exact acoustic anchor. Constants other than
the measured brush-register offsets are proposed sound-design shapes, not
listening-approved settings. No seed, phase, timing, or mechanical input appears
here. Width returned by config_for is already note-scaled; do not scale it again.
"""
from copy import deepcopy
import math


BASE_CONFIGS = {
    'wood': dict(width_ms=2.1, tongue=[(1., .85, .48), (2., .50, .29),
                  (3., .24, .17), (4.04, .10, .10)],
                 body=[(1., .22, .72), (1.51, .07, .22), (2.29, .035, .16),
                       (3.41, .018, .10)], body_gain=.50, brush=0., pulse_gain=1.),
    'tine': dict(width_ms=.72, tongue=[(1., 1., .83), (2., .38, .40),
                  (3., .20, .24), (5.96, .60, .26)],
                 body=[(1., .14, .62), (1.50, .035, .28), (2.42, .025, .17),
                       (3.24, .012, .11)], body_gain=.32, brush=0., pulse_gain=1.),
    'brush': dict(width_ms=4.5, tongue=[(1., 1., 1.05), (2., .13, .52),
                  (3., .025, .29), (4., .009, .17)],
                 body=[(1., .26, 1.35), (1.50, .06, .55), (2.25, .018, .31),
                       (3.25, .009, .19)], body_gain=.58, brush=.0024, pulse_gain=.55),
}
REFERENCE_MIDI = 48.
REFERENCE_VELOCITY = .75
MIDI_MIN, MIDI_MAX = 36., 72.


def _smoothstep(x):
    x=max(0., min(1., x))
    return x*x*(3.-2.*x)


def brush_register_db(midi):
    """Fixed, bounded texture correction from previous brush/direct readouts.

    C2/C3/C4 measured ratios were -16.4465/-9.78395/-9.28855 dB.
    C5's -.75dB correction is a deliberately restrained extrapolation.
    """
    r=(midi-REFERENCE_MIDI)/12.
    if r <= 0.:
        return 6.662590280187672 * _smoothstep(-r)
    if r <= 1.:
        return -.495396182549136 * _smoothstep(r)
    return -.495396182549136 + (-.75+.495396182549136)*_smoothstep(r-1.)


def config_for(voice='BRUSH', midi=REFERENCE_MIDI, velocity=REFERENCE_VELOCITY):
    """Return acoustic fields plus amplitude gain, with no audio normalization.

    Input note accepts fractional MIDI for continuous laws. Invalid finite
    ranges are rejected; the renderer can apply its own project-input policy.
    Apply amp_gain AFTER a saved reference calibration gain. Per-velocity
    loudness matching would undo this law and is intentionally absent.
    """
    voice=str(voice).lower()
    if voice not in BASE_CONFIGS:
        raise ValueError('voice must be BRUSH, WOOD, or TINE')
    midi=float(midi); velocity=float(velocity)
    if not math.isfinite(midi) or not MIDI_MIN <= midi <= MIDI_MAX:
        raise ValueError('midi must be finite and within C2..C5 (36..72)')
    if not math.isfinite(velocity) or not 0. <= velocity <= 1.:
        raise ValueError('velocity must be finite and in 0..1')
    cfg=deepcopy(BASE_CONFIGS[voice])
    register=(midi-REFERENCE_MIDI)/12.
    # Two linear pieces meet continuously at the approved reference. Low
    # touches have room to soften; the upper quarter adds a bounded hard edge.
    delta=((velocity-REFERENCE_VELOCITY)/REFERENCE_VELOCITY if velocity<=REFERENCE_VELOCITY
           else (velocity-REFERENCE_VELOCITY)/(1.-REFERENCE_VELOCITY))
    cfg['width_ms'] *= min(1., 2.**(-register))*math.exp(-math.log(1.5)*delta)
    decay_scale=2.**(-.08*register)*math.exp(.12*delta)
    high_register=max(register,0.)
    for bank in ['tongue','body']:
        changed=[]
        for ratio,gain,t60 in cfg[bank]:
            upper_weight=max(0.,min(1.,math.log2(ratio)/2.))
            tilt=(.42 if bank=='tongue' else .20)*delta
            gain *= math.exp((tilt-.10*high_register)*upper_weight)
            changed.append((ratio,gain,t60*decay_scale))
        cfg[bank]=changed
    cfg['body_gain'] *= math.exp(.10*delta)
    # A softer touch trades some direct catch for pressure-gated texture.
    # Register correction is fixed by note, never derived from this render.
    register_db=brush_register_db(midi)
    cfg['brush'] *= 10.**(register_db/20.)*math.exp(-.18*delta)
    cfg['pulse_gain'] *= math.exp(.13*delta)
    if velocity<=REFERENCE_VELOCITY:
        cfg['amp_gain']=(velocity/REFERENCE_VELOCITY)**.9 if velocity>0. else 0.
    else:
        t=(velocity-REFERENCE_VELOCITY)/(1.-REFERENCE_VELOCITY)
        # This cubic joins the lower law with matching first derivative,
        # then approaches a restrained 1.2 ceiling at maximum velocity.
        cfg['amp_gain']=1.+.3*t-.1*t*t*t
    cfg['midi']=midi
    cfg['velocity']=velocity
    cfg['root_hz']=440.*2.**((midi-69.)/12.)
    cfg['controls_meta']=dict(reference_midi=REFERENCE_MIDI,
                              reference_velocity=REFERENCE_VELOCITY,
                              final_effective_width=True,
                              velocity_delta=delta,
                              brush_register_correction_db=register_db,
                              decay_scale=decay_scale,
                              status='Proposed continuous note/velocity shapes; no listening claim')
    return cfg


def make_report():
    """Small numerical checks of the contract, not implementation-mirroring tests."""
    import json
    from pathlib import Path
    source=json.loads((Path(__file__).resolve().parent.parent / 'musicality' / 'report.json').read_text())
    acoustic_keys=['width_ms','tongue','body','body_gain','brush','pulse_gain']
    anchors={}
    for voice in BASE_CONFIGS:
        expected=deepcopy(source['renders'][voice]['config'])
        expected.setdefault('pulse_gain',1.)
        got=config_for(voice,48,.75)
        same=all(json.dumps(got[k])==json.dumps(expected[k]) for k in acoustic_keys)
        anchors[voice]=dict(exact_acoustic_anchor=same,amp_gain=got['amp_gain'])
        if not same or got['amp_gain']!=1.:
            raise AssertionError('approved C3 reference changed')
    extrema={k:[math.inf,-math.inf] for k in ['width_ms','t60','mode_gain','body_gain','brush','pulse_gain','amp_gain']}
    cases=0
    for voice in BASE_CONFIGS:
        for note_step in range(145):
            midi=MIDI_MIN+note_step*.25
            for velocity_step in range(101):
                v=velocity_step/100.
                c=config_for(voice,midi,v)
                values={k:[c[k]] for k in ['width_ms','body_gain','brush','pulse_gain','amp_gain']}
                values['t60']=[m[2] for m in c['tongue']+c['body']]
                values['mode_gain']=[m[1] for m in c['tongue']+c['body']]
                if any(not math.isfinite(x) or x<0. for xs in values.values() for x in xs):
                    raise AssertionError('nonfinite/negative control output')
                if c['tongue'][0][0]!=1. or c['body'][0][0]!=1.:
                    raise AssertionError('root ratio changed')
                for k,xs in values.items():
                    extrema[k][0]=min(extrema[k][0],min(xs));extrema[k][1]=max(extrema[k][1],max(xs))
                cases+=1
    velocity_samples={}
    for voice in BASE_CONFIGS:
        velocity_samples[voice]=[]
        for v in [.0,.3,.75,1.]:
            c=config_for(voice,48,v)
            velocity_samples[voice].append(dict(velocity=v,amp_gain=c['amp_gain'],
                 width_ms=c['width_ms'],second_tongue_gain=c['tongue'][1][1],
                 body_gain=c['body_gain'],brush=c['brush'],pulse_gain=c['pulse_gain']))
        assert velocity_samples[voice][1]['width_ms']>velocity_samples[voice][2]['width_ms']>velocity_samples[voice][3]['width_ms']
        assert velocity_samples[voice][1]['amp_gain']<1.<velocity_samples[voice][3]['amp_gain']
    register_samples=[]
    for midi in [36,48,60,72]:
        c=config_for('brush',midi,.75)
        register_samples.append(dict(midi=midi,correction_db=brush_register_db(midi),
                                    brush=c['brush'],width_ms=c['width_ms']))
    # Check continuity at the velocity hinge and interpolation note anchors.
    continuity={}
    for key,make_left,make_right in [
        ('velocity_.75',lambda:config_for('brush',48,.75-1e-8),lambda:config_for('brush',48,.75+1e-8)),
        ('note_C3',lambda:config_for('brush',48-1e-8,.75),lambda:config_for('brush',48+1e-8,.75))]:
        a,b=make_left(),make_right()
        continuity[key]=max(abs(a[k]-b[k]) for k in ['width_ms','body_gain','brush','pulse_gain','amp_gain'])
    report=dict(status='Python control-law checks only; acoustic results remain proposed until rendered',
                anchors=anchors,sweep_cases=cases,finite_nonnegative_and_root_ratios_unchanged=True,
                extrema=extrema,velocity_samples=velocity_samples,register_samples=register_samples,
                anchor_continuity_max_absolute_difference=continuity,
                measured_basis='Prior C2/C3/C4 BRUSH roughness/direct raw audio RMS ratio',
                proposed_shapes=['velocity amplitude exponent .9 below reference, C1 cubic to1.2 above','velocity width ratio1.5',
                                 'bounded velocity modal gain tilt','small register/velocity decay changes',
                                 'C5 brush correction -.75dB'],
                renderer_contract='width_ms is final; use fixed reference calibration then amp_gain, no per-velocity normalization',
                unchanged=['all modal ratios','mechanics','seed and roughness realization','event histories'])
    Path(__file__).with_name('controls_report.json').write_text(json.dumps(report,indent=2))
    return report


if __name__=='__main__':
    import json
    print(json.dumps(make_report(),indent=2))
