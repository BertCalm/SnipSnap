"""VESSEL/CORONA encounter variation and resonant carryover, Python prototype 2.

The original six render paths remain available through apsis_voice_study.render.
Only render_articulated opts into the new planner and longer material memory.
"""
from __future__ import annotations
import math
import numpy as np
import apsis_voice_study as a


def encounter_plan(macros, seconds, rate, periodic=False):
    m = a.settled(macros)
    fields, _ = a.motion(m['STRETCH'], m['DISTANCE'], m['ALIGN'], seconds, rate, periodic)
    stride = a.STRIDE * rate // (a.RATE * 4)
    inner = round(seconds * rate) / rate / 6 if periodic else .6 * (2 / .6) ** m['DISTANCE']
    tau = .75 * inner
    eccentricity = .85 * m['STRETCH']
    contacts = []
    for body, ratio in enumerate((1., 1.2, 1.5, 2., 3., 6.)):
        participation = float(np.clip(1 + 5 * m['BODIES'] - body, 0, 1))
        if participation <= 0:
            continue
        values = fields[:, body, 1]
        crossings = np.flatnonzero((values[1:] > .2) & (values[:-1] <= .2)) + 1
        times = [(i - 1 + (.2 - values[i - 1]) / (values[i] - values[i - 1])) * stride / rate for i in crossings]
        if not periodic and values[0] > .2:
            times.insert(0, 0.)
        period = inner * ratio
        passage = .28 + (1 - m['ALIGN']) * (.18 + .35 * body)
        for t in times:
            if not 0 <= t < seconds:
                continue
            if not periodic and t >= 3.6 + 1.8 * m['HOLD']:
                continue
            mean = (2 * np.pi * (t - passage) / period + np.pi) % (2 * np.pi) - np.pi
            anomaly = mean
            for _ in range(14):
                anomaly -= (anomaly - eccentricity * np.sin(anomaly) - mean) / (1 - eccentricity * np.cos(anomaly))
            radius = 1 - eccentricity * np.cos(anomaly)
            near = 1 / (1 + (radius / .7) ** 4)
            speed = np.sqrt(np.sin(anomaly) ** 2 + (1 - eccentricity ** 2) * np.cos(anomaly) ** 2) / radius
            speed_fraction = speed / np.sqrt((1 + eccentricity) / (1 - eccentricity))
            strength = (.25 + .75 * speed_fraction) * (.45 + .55 * near)
            contacts.append(dict(time=float(t), body=body, request=float((.65 + 1.1 * strength) * participation),
                                 encounterStrength=float(strength), speedFraction=float(speed_fraction)))
    contacts.sort(key=lambda e: (e['time'], e['body']))
    groups = []
    for event in contacts:
        if not groups or event['time'] - groups[-1][0]['time'] > 1 / rate:
            groups.append([])
        groups[-1].append(event)

    def run(initial, record=False):
        available, previous, rows = initial, 0., []
        for group in groups:
            t = group[0]['time']
            available = 1 - (1 - available) * math.exp(-(t - previous) / tau)
            total_request = sum(e['request'] for e in group)
            spent = available * (1 - math.exp(-total_request))
            for event in group:
                energy = spent * event['request'] / total_request
                hardness = float(np.clip(.18 + .82 * np.sqrt(.4 * event['encounterStrength'] + .6 * energy / .65), 0, 1))
                if record:
                    rows.append(dict(event, energy=energy, amplitude=math.sqrt(energy / .35),
                                     hardness=hardness, availableBefore=available))
            available -= spent
            previous = t
        available = 1 - (1 - available) * math.exp(-(seconds - previous) / tau)
        return available, rows

    initial = 1.
    if periodic:
        f0, _ = run(0.)
        f1, _ = run(1.)
        initial = f0 / (1 - (f1 - f0))
    final, rows = run(initial, True)
    slots = max((sum(e['body'] == b for e in rows) for b in range(6)), default=0) + 1
    plan = np.zeros((6, slots, 3))
    plan[:, :, 0] = np.inf
    for body in range(6):
        own = [e for e in rows if e['body'] == body]
        for j, e in enumerate(own):
            plan[body, j] = e['time'], e['amplitude'], e['hardness']
    report = dict(events=rows, recoverySeconds=tau, initialAvailable=initial, finalAvailable=final,
                  periodicBudgetError=abs(final - initial) if periodic else None,
                  spentEnergy=sum(e['energy'] for e in rows), energyUpperBound=initial + seconds / tau)
    return plan, report


def render_articulated(voice, macros=None, midi=60, velocity=.65, held=False, oversample=4, reset_on_attack=False):
    if voice not in ('VESSEL', 'CORONA'):
        raise ValueError('Articulation revision is only VESSEL and CORONA')
    if not 36 <= midi <= 84 or not np.isfinite(velocity) or not 0 <= velocity <= 1 or oversample not in (4, 8):
        raise ValueError('Invalid note, velocity or rate')
    m = a.settled(macros or {})
    held = held or m['HOLD'] >= .99
    hz = 440 * 2 ** ((midi - 69) / 12)
    rate = a.RATE * oversample
    inner = .6 * (2 / .6) ** m['DISTANCE']
    seconds = 6. + 1.8 * min(m['HOLD'], .98)
    if held:
        cycles = round(6 * inner * hz)
        frames_out = round(cycles * a.RATE / hz)
        seconds = frames_out / a.RATE
        hz = cycles / seconds
        inner = seconds / 6
    fields, residual = a.motion(m['STRETCH'], m['DISTANCE'], m['ALIGN'], seconds, rate, held)
    idx, co, si, rho, rad = a.profile(voice, hz, rate)
    # Positive loss remains contractive. The old ringing state is never cleared.
    decays = np.array([4., .7, .55] + ([4., 3.4, 2.8, 2.] if voice == 'VESSEL' else [3.2, 1.6, 1.1, .8]) * 6) * inner
    rho = np.maximum(rho, np.exp(-np.log(1000) / (rate * decays)))
    plan, planner_report = encounter_plan(m, seconds, rate, held)
    state = np.zeros(96)
    state[54:60] = 100.
    state_error, blocks = 0., []
    diag = np.zeros((6, plan.shape[1], 1))
    stable_previous = False
    for cycle in range(12 if held else 1):
        before = state.copy()
        raw, _ = a.run_cycle(fields, round(seconds * rate), rate, idx, hz, co, si, rho, rad,
                              m['BODIES'], m['COUPLE'], velocity, -1. if held else 3.6 + 1.8 * m['HOLD'],
                              a.seed_for('APSIS_VOICE_STUDY', midi, 0), state, False, plan, diag, reset_on_attack)
        # Fixed radiation calibration for the more persistent air body. This
        # applies once to the whole voice, never separately to individual hits.
        blocks.append(raw * (.15 if voice == 'CORONA' else 1.))
        if held and cycle >= 2:
            state_error = float(np.max(np.abs(state - before)))
            if state_error < 1e-10 and stable_previous:
                break
            stable_previous = state_error < 1e-10
    assert not held or state_error < 1e-8, state_error
    if held:
        # resample_poly has a symmetric FIR. Give it real future audio instead
        # of letting zero padding contaminate the kept cycle's last samples.
        continuation, _ = a.run_cycle(fields, round(seconds * rate), rate, idx, hz, co, si, rho, rad,
                                       m['BODIES'], m['COUPLE'], velocity, -1.,
                                       a.seed_for('APSIS_VOICE_STUDY', midi, 0), state, False, plan)
        blocks.append(continuation * (.15 if voice == 'CORONA' else 1.))
    out = a.condition(np.concatenate(blocks), oversample, held)
    n = round(seconds * a.RATE)
    kept = out[-2*n:-n] if held else out[-n:]
    previous = out[-3*n:-2*n] if held else np.zeros(0)
    counters = [0] * 6
    for event in planner_report['events']:
        b = event['body']; j = counters[b]; counters[b] += 1
        event['energyAlreadyRinging'] = float(diag[b, j, 0])
    report = dict(planner_report, stateError=state_error, prerollCycles=len(blocks) - (2 if held else 1),
                  solverResidual=residual, effectiveHz=hz,
                  seamError=a.sk.seam_error(np.r_[previous,kept], n) if held else None,
                  adjacentCycleRelativeError=float(np.linalg.norm(kept - previous) / max(1e-12, np.linalg.norm(kept))) if held else None)
    return kept, report
