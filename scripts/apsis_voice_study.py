"""Apsis voice identity study, adapted from Luthier's proto_template.py.

Python/Numba audition prototypes, not new Kotlin voices or saved Apsis models.
The PASSAGE profile is a study reference, not a byte-compatible port of PASSAGE.
All physical/acoustic profile constants are musical shape choices awaiting listening.
Run with numpy, scipy and numba installed. Output is deliberately gitignored testkit/.
"""
from __future__ import annotations

import argparse
from functools import lru_cache
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys
import time

import numpy as np
from numba import njit
from scipy import signal

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.claude/skills/luthier/scripts'))
import snipkit as sk
import probes as pr

RATE = 44100
STRIDE = 128
MACROS = dict(STRETCH=.65, DISTANCE=.40, BODIES=.40, ALIGN=.55, COUPLE=.45, HOLD=0.)
VOICES = ('PASSAGE', 'TETHER', 'VESSEL', 'CORONA', 'GRAIN', 'ANCHOR')
DESCRIPTIONS = (
    'Approach, bright contact, darker ringing reply. A common-harness reference for the existing idea.',
    'Catch a taut tether, release a pluck, hear the upper strings fall away.',
    'A short mallet contact leaves separated, long-lived inharmonic rings.',
    'A broad encounter opens an airy, vowel-shaped tone, then lets the breath close.',
    'Fine surface contacts make a cluster of dry grains around the played root.',
    'Pressure accumulates away from the center, then releases a low, odd-harmonic surge.',
)


def seed_for(*parts):
    """Dsp.seedFor's signed Int arithmetic for the string/integer inputs used here."""
    h = 17
    for part in parts:
        p = 0
        for char in str(part):
            p = (31 * p + ord(char)) & 0xffffffff
        h = (31 * h + p) & 0xffffffff
    return h if h < 0x80000000 else h - 0x100000000


def settled(macros):
    return {k: float(np.clip(macros.get(k, v), 0, 1))
            if np.isfinite(macros.get(k, v)) else v for k, v in MACROS.items()}


@lru_cache(maxsize=6)
def motion(stretch, distance, align, seconds, rate, held=False):
    """Bounded Kepler solve, cached independently of pitch, velocity, voice and COUPLE.

    Same ellipse/period laws as Apsis.kt at the recorded base revision. Interpolation
    is a separate prototype approximation; solver residual is checked, not assumed.
    """
    frames = round(seconds * rate)
    stride = STRIDE * rate // (RATE * 4)
    times = np.minimum(np.arange(math.ceil(frames / stride) + 1) * stride, frames) / rate
    eccentricity = .85 * stretch
    inner = (frames / rate) / 6 if held else .60 * (2 / .60) ** distance
    result = np.empty((len(times), 6, 4))
    max_residual = 0.
    for body, period_ratio in enumerate((1, 1.2, 1.5, 2, 3, 6)):
        period = inner * period_ratio
        passage = .28 + (1 - align) * (.18 + .35 * body)
        orientation = (1 - align) * (.25 + body * .58) + align * .06 * body
        mean = (2 * np.pi * (times - passage) / period + np.pi) % (2 * np.pi) - np.pi
        anomaly = mean.copy()
        for _ in range(14):
            anomaly -= (anomaly - eccentricity * np.sin(anomaly) - mean) / (1 - eccentricity * np.cos(anomaly))
        residual = np.max(np.abs(anomaly - eccentricity * np.sin(anomaly) - mean))
        max_residual = max(max_residual, float(residual))
        assert residual < 1e-11, residual
        x = np.cos(anomaly) - eccentricity
        y = np.sqrt(1 - eccentricity ** 2) * np.sin(anomaly)
        angle = np.arctan2(y, x) + orientation
        near = 1 / (1 + (np.hypot(x, y) / .7) ** 4)
        result[:, body, 0] = near
        result[:, body, 1] = np.exp((np.cos(angle + .10) - 1) / .28 ** 2)
        result[:, body, 2] = np.exp((np.cos(angle + .10) - 1) / .95 ** 2)
        result[:, body, 3] = ((.5 + .5 * np.cos(11 * angle)) ** 6) * result[:, body, 2]
    result.setflags(write=False)
    return result, max_residual


def profile(voice, hz, rate):
    idx = VOICES.index(voice)
    ratios = ((1, 2, 3, 5.43), (1, 2, 3, 4.01), (1, 2.01, 4.02, 6.03),
              (1, 3, 5, 7), (1, 3.17, 6.43, 10.1), (1, 3, 5, 7))[idx]
    decays = ((1.1, .50, .42, .85), (.75, .35, .20, .12), (1.6, 2.4, 1.5, .9),
              (.24, .09, .07, .06), (.16, .065, .04, .025), (.55, .28, .16, .09))[idx]
    hz_table = np.array([hz, 2 * hz, 3 * hz] +
                        [hz * ratio * (1 + ((.002 if idx in (0, 2) else .014) * b if m > 0 and idx in (0, 2, 4) else 0))
                         for b in range(6) for m, ratio in enumerate(ratios)])
    if voice == 'CORONA':
        for b in range(6):
            formants = np.array([520, 1250, 2450]) * (1 + .045 * b)
            hz_table[4 + 4 * b:7 + 4 * b] = np.minimum(48, np.maximum(np.rint(formants / hz), [2, 3, 5])) * hz
    center_decay = 2.1 if idx == 2 else .65
    rho = np.exp(-np.log(1000) / (rate * np.array([center_decay, .32, .22] + list(decays) * 6)))
    co, si = np.cos(2 * np.pi * hz_table / rate), np.sin(2 * np.pi * hz_table / rate)
    return idx, co, si, rho, np.clip((19000 - hz_table) / 3000, 0, 1)


@njit(cache=True)
def run_cycle(fields, frames, rate, idx, hz, co, si, rho, radiation,
              bodies, couple, velocity, source_end, seed, state, free=False,
              encounters=None, encounter_diagnostics=None, reset_on_attack=False):
    """Exact damped rotations then orthogonal velocity exchanges, with bounded input.

    state includes modal coordinates, contact ages/flags and a bounded reservoir.
    No listener gain or output limiter participates in stability.
    """
    x, v = state[0:27], state[27:54]
    ages, was_contact, pressure = state[54:60], state[60:66], state[66:72]
    out = np.zeros(frames)
    max_rise = 0.
    dt = 1 / rate
    injection = .0008 * (RATE * 4 / rate)
    allocation = math.sqrt(max(1., 1 + 5 * bodies))
    phase_step = 2 * math.pi * hz / rate
    omega = np.arctan2(si, co)
    noise_state = seed if seed != 0 else 1
    stride = STRIDE * rate // (RATE * 4)
    weights = ((1., 1.1, .7, .25), (.9, .9, .7, .4), (1.3, 1.4, 1., .7),
               (.9, 2., 2.4, 1.6), (.8, 1.8, 1.3, 1.), (.9, 1.8, 1.6, 1.1))[idx]
    center_gain = (.7, .45, 1.1, .6, .45, .45)[idx]
    exchange_rate = (12., 12., 12., 60., 48., 48.)[idx]
    center_upper1, center_upper2 = (.8, 1.1) if idx == 4 else (.18, .12)
    encounter_index = np.zeros(6, dtype=np.int64)
    for frame in range(frames):
        t = frame * dt
        source = 1. if source_end < 0 else min(1., max(0., (source_end - t) / .25))
        source *= min(1., t / .012) if source_end >= 0 else 1.
        if free:
            source = 0.
        before = 0.
        if free:
            for j in range(27):
                before += x[j] * x[j] + v[j] * v[j]
        for j in range(27):
            ox, ov = x[j], v[j]
            x[j] = rho[j] * (co[j] * ox + si[j] * ov)
            v[j] = rho[j] * (co[j] * ov - si[j] * ox)
        noise_state = (noise_state * 1103515245 + 12345) & 0x7fffffff
        noise = noise_state / 1073741823. - 1.
        phase = phase_step * frame
        root = math.sin(phase)
        row = min(frame // stride, len(fields) - 2)
        fraction = (frame - row * stride) / stride
        center_drive = 0.
        for body in range(6):
            participation = min(1., max(0., 1 + 5 * bodies - body))
            if participation <= 0:
                continue
            near = fields[row, body, 0] * (1 - fraction) + fields[row + 1, body, 0] * fraction
            contact = fields[row, body, 1] * (1 - fraction) + fields[row + 1, body, 1] * fraction
            broad = fields[row, body, 2] * (1 - fraction) + fields[row + 1, body, 2] * fraction
            grains = fields[row, body, 3] * (1 - fraction) + fields[row + 1, body, 3] * fraction
            active = contact > .2
            articulated = encounters is not None and idx in (2, 3)
            if articulated:
                event = encounter_index[body]
                if event < encounters.shape[1] and encounters[body, event, 0] <= t:
                    ages[body] = t - encounters[body, event, 0]
                    state[72 + body] = encounters[body, event, 1]
                    state[78 + body] = encounters[body, event, 2]
                    if encounter_diagnostics is not None:
                        energy = 0.
                        for j in range(27):
                            energy += .5 * (x[j] * x[j] + v[j] * v[j])
                        encounter_diagnostics[body, event, 0] = energy
                    if reset_on_attack:
                        # Counterfactual probe only: exposes the sound lost by retrigger resets.
                        x[:] = 0.
                        v[:] = 0.
                    encounter_index[body] += 1
            elif active and was_contact[body] == 0:
                ages[body] = 0.
            was_contact[body] = 1. if active else 0.
            age = ages[body]
            ages[body] += dt
            # Convex charge/release update: pressure remains in [0,1].
            target = 1 - broad
            pressure[body] += (target - pressure[body]) * (1 - math.exp(-dt / (.32 if target > pressure[body] else .035)))
            amp = velocity * participation / allocation * source
            hardness = velocity
            if articulated:
                if idx == 3:
                    slew = 1 - math.exp(-dt / .004)
                    state[84 + body] += slew * (state[72 + body] - state[84 + body])
                    state[90 + body] += slew * (state[78 + body] - state[90 + body])
                    amp *= state[84 + body]
                    hardness = velocity * (.25 + .75 * state[90 + body])
                    packet = (1 - math.exp(-age / .008)) * math.exp(-age / (.10 + .24 * (1 - hardness)))
                    broad = broad ** (1.8 - .9 * hardness) * packet
                else:
                    amp *= state[72 + body]
                    hardness = velocity * (.25 + .75 * state[78 + body])
            hit = (1 - math.exp(-age / .0015)) * math.exp(-age / (.018 + .018 * (1 - velocity)))
            if idx == 0:
                force = near * (contact * root + .16 * broad * math.sin(2 * phase))
                material = contact * math.sin(2 * math.pi * hz * 5.43 * age) * (.15 + velocity * velocity)
            elif idx == 1:
                force = hit * (root + .45 * math.sin(2 * phase) + (.1 + .4 * velocity) * math.sin(3 * phase))
                material = hit * noise * .4
            elif idx == 2:
                mallet_width = (.08 + .12 * (1 - hardness)) / hz
                mallet = .5 * (1 - math.cos(2 * math.pi * age / mallet_width)) if age < mallet_width else 0.
                force = mallet * 4.
                material = force * (.3 + hardness)
            elif idx == 3:
                force = broad * near ** .3 * (.55 * root + .2 * math.sin(2 * phase) + .45 * noise)
                material = broad * noise * (2 + 4 * hardness)
            elif idx == 4:
                force = near * grains * (.30 * root + noise * (1 + velocity))
                material = near * grains * noise * (12 + 12 * velocity)
            else:
                release = broad * pressure[body]
                force = release * (root + (.12 + .55 * velocity) * math.sin(3 * phase) + .22 * velocity * math.sin(5 * phase))
                material = release * math.tanh(2 * root) * .6
            base = 3 + 4 * body
            # Excite each intended harmonic at its own carrier. Sending only a
            # root sine into upper modes made every first-pass candidate a sine.
            if idx == 0:
                upper1 = near * contact * math.sin(2 * phase) * (1 + velocity)
                upper2 = near * contact * math.sin(3 * phase) * (.4 + velocity * velocity)
                upper3 = material * 2.
            elif idx == 1:
                upper1 = hit * math.sin(2 * phase) * (1 + velocity)
                upper2 = hit * math.sin(3 * phase) * (.5 + velocity)
                upper3 = hit * math.sin(4 * phase) * velocity
            elif idx == 2:
                upper1, upper2, upper3 = material, material * .8, material * .6
            elif idx == 3:
                upper1 = broad * math.sin(omega[base + 1] * frame) * (1 + hardness) + material
                upper2 = broad * math.sin(omega[base + 2] * frame) * (.7 + hardness) + material * .8
                upper3 = broad * math.sin(omega[base + 3] * frame) * (.4 + hardness) + material * .5
                force *= .5
            elif idx == 4:
                upper1, upper2, upper3 = material, material * .8, material * .5
                force *= .5
            else:
                upper1 = release * math.sin(3 * phase) * (.3 + velocity)
                upper2 = release * math.sin(5 * phase) * (.1 + .7 * velocity)
                upper3 = release * math.sin(7 * phase) * velocity * .5
            v[base] += injection * amp * force
            v[base + 1] += injection * amp * upper1
            v[base + 2] += injection * amp * upper2
            v[base + 3] += injection * amp * upper3
            center_drive += amp * (grains * root * .8 if idx == 4 else
                                   force if idx in (1, 2) else broad * root * (.2 if idx == 0 else .4))
            # Couple upper normalized ports only. The root remains an independent pitch anchor.
            angle = exchange_rate * dt * couple * participation * (.15 + near)
            c, s = math.cos(angle), math.sin(angle)
            for a, b in (((2, base + 1), (1, base + 2)) if idx in (4, 5) else ((1, base + 1), (2, base + 2))):
                va, vb = v[a], v[b]
                v[a], v[b] = c * va + s * vb, c * vb - s * va
        v[0] += injection * center_drive
        total = center_gain * v[0] + center_upper1 * v[1] + center_upper2 * v[2]
        for b in range(6):
            for m in range(4):
                gain = weights[m]
                total += gain * radiation[3 + 4 * b + m] * v[3 + 4 * b + m]
        out[frame] = total
        if free:
            after = 0.
            for j in range(27):
                after += x[j] * x[j] + v[j] * v[j]
            max_rise = max(max_rise, after - before)
    return out, max_rise


def condition(raw, oversample=4, held=False):
    """Prototype house-order linear output stage. Loudness matching is separate.

    This is the Luthier harness equivalent, not Kotlin Tide/Dsp byte identity.
    """
    sos = signal.butter(8, 20000, fs=RATE * oversample, output='sos')
    limited = signal.sosfilt(sos, raw)
    out = signal.resample_poly(limited, 1, oversample)
    out -= np.mean(out)
    alpha = 1 / (1 + 2 * np.pi * 20 / RATE)
    out = signal.lfilter([alpha, -alpha], [1, -alpha], out)
    if not held:
        out = sk.fade_tail(out, RATE)
    return out


def render(voice, macros=None, midi=60, velocity=.65, held=False, oversample=4):
    began = time.perf_counter()
    if not isinstance(midi, (int, np.integer)) or not 36 <= midi <= 84:
        raise ValueError('Study MIDI must be 36..84')
    if not np.isfinite(velocity) or not 0 <= velocity <= 1:
        raise ValueError('Study velocity must be finite and 0..1')
    if oversample not in (4, 8):
        raise ValueError('Study rate is 4x, or 8x for reference checks')
    m = settled(macros or {})
    held = held or m['HOLD'] >= .99
    hz = 440 * 2 ** ((midi - 69) / 12)
    rate = RATE * oversample
    seconds = 6.0 + 1.8 * min(m['HOLD'], .98)
    if held:
        wanted = 6 * .60 * (2 / .60) ** m['DISTANCE']
        root_cycles = round(wanted * hz)
        frames_out = round(root_cycles * RATE / hz)
        seconds = frames_out / RATE
        hz = root_cycles / seconds
    fields, residual = motion(m['STRETCH'], m['DISTANCE'], m['ALIGN'], seconds, rate, held)
    idx, co, si, rho, radiation = profile(voice, hz, rate)
    state = np.zeros(72)
    state[54:60] = 100.
    seed = seed_for('APSIS_VOICE_STUDY', midi, 0)
    frames = round(seconds * rate)
    prepared_ms = 1000 * (time.perf_counter() - began)
    cycles, state_error = [], 0.
    for cycle in range(12 if held else 1):
        before = state.copy()
        raw, _ = run_cycle(fields, frames, rate, idx, hz, co, si, rho, radiation,
                           m['BODIES'], m['COUPLE'], velocity, -1. if held else 3.6 + 1.8 * m['HOLD'], seed, state)
        cycles.append(raw)
        if held and cycle >= 2:
            state_error = float(np.max(np.abs(state - before)))
            if state_error < 1e-8:
                break
    assert not held or state_error < 1e-8, (voice, state_error)
    # Condition real warm-up and adjacent cycles together; never paste a loop to prove recurrence.
    out = condition(np.concatenate(cycles), oversample, held)
    length = round(seconds * RATE)
    kept = out[-length:]
    report = dict(renderMs=round(1000 * (time.perf_counter() - began), 2), preparationMs=prepared_ms, solverResidual=residual,
                  preparationEntryBytes=fields.nbytes,
                  prerollCycles=len(cycles) - 1, stateError=state_error, effectiveHz=hz,
                  seamError=sk.seam_error(out[-2 * length:], length) if held else None)
    if held:
        previous = out[-2 * length:-length]
        report['adjacentCycleRelativeError'] = float(np.linalg.norm(kept - previous) / max(1e-12, np.linalg.norm(kept)))
    assert np.all(np.isfinite(kept))
    return kept, report


def rms200(x):
    n = round(.2 * RATE)
    e = np.r_[0., np.cumsum(x * x)]
    return float(np.sqrt(np.max((e[n:] - e[:-n]) / n)))


def measurements(x, midi):
    # Choose the strongest independent 250 ms window, not the detector result closest to the target.
    windows = [(.2 + .15 * i) for i in range(19)]
    start = max(windows, key=lambda t: np.mean(x[int(t * RATE):int((t + .25) * RATE)] ** 2))
    hz, confidence = pitch_probe(x, start)
    ref = 440 * 2 ** ((midi - 69) / 12)
    return dict(rawPeak=float(np.max(np.abs(x))), rawRms=pr.rms(x), rawDc=pr.dc(x),
                pitchHz=hz, pitchConfidence=confidence, pitchWindowSeconds=start,
                pitchCents=pr.cents(hz, ref), centroidHz=pr.spectral_centroid(x, RATE),
                rms200=rms200(x))


def pitch_probe(x, start):
    """NSDF with independent prefix energies for both overlapping windows.

    The bundled harness's second energy term subtracts the wrong prefix. Do not
    silently reuse its clipped confidences as proof of pitch for this study.
    """
    y = x[round(start * RATE):round((start + .25) * RATE)].copy()
    y -= np.mean(y)
    if pr.rms(y) < 1e-8:
        return 0., 0.
    n = len(y)
    ac = np.fft.irfft(np.abs(np.fft.rfft(y, 2 * n)) ** 2)[:n]
    p = np.r_[0., np.cumsum(y * y)]
    lag = np.arange(n)
    nsdf = 2 * ac / np.maximum(1e-30, p[n - lag] + p[n] - p[lag])
    lo, hi = round(RATE / 2500), round(RATE / 40)
    peaks = [i for i in range(lo + 1, hi - 1) if nsdf[i] > nsdf[i - 1] and nsdf[i] >= nsdf[i + 1]]
    if not peaks:
        return 0., 0.
    threshold = .85 * max(nsdf[i] for i in peaks)
    i = next(i for i in peaks if nsdf[i] >= threshold)
    a, b, c = nsdf[i - 1:i + 2]
    delta = .5 * (a - c) / (a - 2 * b + c) if abs(a - 2 * b + c) > 1e-20 else 0.
    return RATE / (i + delta), float(np.clip(b, 0, 1))


def spectrum(x):
    f, _, s = signal.spectrogram(x, RATE, nperseg=4096, noverlap=3655)
    p = s[(f >= 40) & (f <= 10000)].mean(axis=1)
    return p / max(1e-20, p.sum())


def main(out_dir):
    out_dir = Path(out_dir)
    for directory in ('audio', 'recipes', 'reports'):
        (out_dir / directory).mkdir(parents=True, exist_ok=True)
    entries, signals = [], {}

    def add(voice, suffix, group, midi=60, velocity=.65, macros=None, held=False):
        ident = voice.lower() + '_' + suffix
        m = settled(macros or {})
        if held:
            m['HOLD'] = 1.
        audio, timing = render(voice, m, midi, velocity, held)
        info = measurements(audio, midi)
        info.update(timing)
        entry = dict(id=ident, voice=voice, title=voice.title() + ' / ' + suffix.replace('_', ' '),
                     group=group, prototype='Python/Numba voice study 1', midi=midi, velocity=velocity,
                     macros=m, description=DESCRIPTIONS[VOICES.index(voice)], loop=held,
                     durationSeconds=len(audio) / RATE, metrics=info,
                     rawFile=f'voices/audio/{ident}_raw.wav', matchedFile=f'voices/audio/{ident}_matched.wav',
                     recipeFile=f'voices/recipes/{ident}.json',
                     gainPolicy='Individual loudest-200ms RMS match with peak ceiling; raw uses one common monitor gain.')
        entries.append(entry)
        signals[ident] = audio
        print(ident, round(info['renderMs']), 'ms', round(info['pitchCents'], 1), 'cents', flush=True)
        return audio

    # Warm the JIT separately. Timings below include synthesis/output, not compilation.
    render('PASSAGE')
    for voice in VOICES:
        base = add(voice, 'identity_C4', 'identities')
        for midi in (36, 84):
            add(voice, f'register_{midi}', 'voice-range', midi=midi)
        for velocity in (.3, 1.):
            add(voice, f'touch_{velocity}', 'voice-touch', velocity=velocity)
        for name in ('STRETCH', 'COUPLE'):
            for value in (.15, .85):
                add(voice, f'{name.lower()}_{value}', 'voice-controls', macros={name: value})
        add(voice, 'held_cycle', 'voice-hold', held=True)
        # Same sparse three-note score for every identity; all original approaches/tails remain.
        mix = np.zeros(round(9.6 * RATE))
        for at, midi, strength in ((0., 48, .65), (1.8, 55, .45), (3.6, 51, .85)):
            note, _ = render(voice, midi=midi, velocity=strength)
            start = round(at * RATE)
            count = min(len(note), len(mix) - start)
            mix[start:start + count] += note[:count] * .35
        ident = voice.lower() + '_phrase'
        signals[ident] = mix
        entries.append(dict(id=ident, voice=voice, title=voice.title() + ' / three-note phrase',
                            group='voice-music', kind='score', scoreSummary='C3, G3, E-flat3, 1.8 seconds apart; velocity .65 / .45 / .85; complete six-second tails.',
                            description=DESCRIPTIONS[VOICES.index(voice)], prototype='Python/Numba voice study 1',
                            loop=False, durationSeconds=9.6, metrics=dict(rawPeak=float(np.max(np.abs(mix))), rawRms=pr.rms(mix), rawDc=pr.dc(mix)),
                            rawFile=f'voices/audio/{ident}_raw.wav', matchedFile=f'voices/audio/{ident}_matched.wav',
                            gainPolicy='Identical three-note score, individual loudest-200ms RMS match; raw uses one common monitor gain.'))

    common = .35 / max(float(np.max(np.abs(x))) for x in signals.values())
    for entry in entries:
        ident, voice = entry['id'], entry['voice']
        x = signals[ident]
        group = entry['group']
        if group in ('voice-touch', 'voice-controls'):
            reference = signals[voice.lower() + '_identity_C4']
            family = [signals[e['id']] for e in entries if e['voice'] == voice and e['group'] in ('identities', group)]
            gain = min(.12 / max(1e-12, rms200(reference)), .85 / max(np.max(np.abs(a)) for a in family))
            entry['gainPolicy'] = 'One frozen gain per voice comparison family, referenced to its C4 identity; no per-take gain correction.'
        else:
            gain = min(.12 / max(1e-12, rms200(x)), .85 / max(1e-12, np.max(np.abs(x))))
        entry['metrics']['listeningGain'] = float(gain)
        entry['metrics']['commonRawGain'] = common
        for suffix, y in (('raw', x * common), ('matched', x * gain)):
            path = out_dir / 'audio' / f'{ident}_{suffix}.wav'
            assert np.max(np.abs(y)) <= .850001
            sk.write_wav(str(path), y)
            entry[suffix + 'Sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
        (out_dir / 'recipes' / f'{ident}.json').write_text(json.dumps(entry, indent=2) + '\n')

    references = [signals[v.lower() + '_identity_C4'] for v in VOICES]
    spectra = [spectrum(x) for x in references]
    distances = {f'{a}/{b}': float(.5 * np.sum(np.abs(spectra[i] - spectra[j])))
                 for i, a in enumerate(VOICES) for j, b in enumerate(VOICES) if i < j}
    held = [e for e in entries if e['group'] == 'voice-hold']
    assert all(e['metrics']['seamError'] < sk.MAX_SEAM_ERROR for e in held)
    assert all(e['metrics']['adjacentCycleRelativeError'] < 1e-4 for e in held)
    assert all(abs(e['metrics']['pitchCents']) < 50 and e['metrics']['pitchConfidence'] >= .3
               for e in entries if e['group'] in ('identities', 'voice-range'))
    # Repeat native output before quantization, with cache already warm and all other voices rendered.
    deterministic = {}
    for voice, original in zip(VOICES, references):
        again, _ = render(voice)
        deterministic[voice] = bool(np.array_equal(original, again))
    assert all(deterministic.values())
    envelopes = [pr.envelope(x) for x in references]
    envelope_distances = {f'{a}/{b}': float(1 - np.dot(envelopes[i], envelopes[j]) /
                           (np.linalg.norm(envelopes[i]) * np.linalg.norm(envelopes[j])))
                          for i, a in enumerate(VOICES) for j, b in enumerate(VOICES) if i < j}
    report = dict(runtime='Python/Numba, not Kotlin or phone', repositoryBaseRevision=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                  studySourceSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  sampleRate=RATE, oversample=4, clipCount=len(entries), deterministic=deterministic,
                  spectralTotalVariation=distances, envelopeCosineDistance=envelope_distances,
                  held=[dict(voice=e['voice'], **e['metrics']) for e in held],
                  cacheInfo=motion.cache_info()._asdict(), commonRawGain=common,
                  listeningStatus='Not heard. Candidate names and acoustic constants are provisional.')
    (out_dir / 'reports/validation.json').write_text(json.dumps(report, indent=2) + '\n')
    (out_dir / 'manifest.json').write_text(json.dumps(dict(clips=entries, report=report), indent=2) + '\n')
    print(json.dumps(dict(clips=len(entries), minDistance=min(distances.values()), loopsPassed=len(held))), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output')
    main(parser.parse_args().output)
