"""Export measured plots and validate the generated listening pack. Requires NumPy/SciPy/Matplotlib."""
import hashlib
import json
import statistics
from pathlib import Path
import sys
import wave
import zipfile

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np
from scipy.io import wavfile

root = Path(sys.argv[1]).resolve()
manifest = json.loads((root / 'manifest.json').read_text())
clips = {c['id']: c for c in manifest['clips']}

def signal(clip_id):
    rate, values = wavfile.read(root / clips[clip_id]['rawAudio'])
    return rate, values.astype(np.float64) / 2147483648.0

def centroid(values, rate):
    size = 4096
    times, result = [], []
    for start in range(0, int(5.9 * rate) - size, 2205):
        window = values[start:start + size] * np.hanning(size)
        power = np.abs(np.fft.rfft(window)) ** 2
        frequencies = np.fft.rfftfreq(size, 1 / rate)
        result.append(float(np.sum(power * frequencies) / max(1e-20, np.sum(power))))
        times.append((start + size / 2) / rate)
    return times, result

def rms_windows(values, rate):
    size = 4410
    times, result = [], []
    for start in range(0, len(values) - size, 2205):
        rms = np.sqrt(np.mean(values[start:start + size] ** 2))
        times.append((start + size / 2) / rate)
        result.append(20 * np.log10(max(1e-8, rms)))
    return times, result

plt.rcParams.update({'font.size': 9, 'axes.spines.top': False, 'axes.spines.right': False,
                     'axes.labelcolor': '#39434b', 'axes.edgecolor': '#aeb7bc', 'font.family': 'DejaVu Sans'})
fig, axes = plt.subplots(3, 3, figsize=(13, 10), constrained_layout=True)
colors = ['#b5763e', '#288991', '#4f6da3']
summary = {}
for col, name in enumerate(['gather', 'twins', 'departure']):
    clip = clips[name]
    particles = json.loads((root / clip['trajectories']).read_text())['particles']
    ax = axes[0, col]
    for particle in particles:
        trace = np.array(particle['trace'])
        color = '#288991' if particle['glass'] else '#b5763e'
        ax.plot(trace[:, 1], trace[:, 2], color=color, alpha=.50, linewidth=.75)
        ax.scatter(trace[0, 1], trace[0, 2], color=color, s=8)
    ax.scatter([0], [0], color='#20282d', s=18, marker='+')
    ax.set(xlabel='x displacement (normalized units)', ylabel='y displacement (normalized units)', title=clip['title'])
    ax.set_aspect('equal', adjustable='datalim')
    ax.grid(alpha=.15)

    for suffix, label, color in [('', 'Full', colors[0]), ('-no_pitch', 'Pitch movement off', colors[1]), ('-frozen', 'Motion frozen', colors[2])]:
        rate, values = signal(name + suffix)
        t, values = centroid(values, rate)
        axes[1, col].plot(t, values, color=color, label=label, linewidth=1)
    axes[1, col].set(xlabel='Audio time (s)', ylabel='Power spectral centroid (Hz)')
    axes[1, col].grid(alpha=.15)
    if col == 0: axes[1, col].legend(frameon=False, fontsize=8)

    for suffix, label, color in [('', 'Full', colors[0]), ('-no_memory', 'Memory off', colors[2])]:
        rate, values = signal(name + suffix)
        t, levels = rms_windows(values, rate)
        axes[2, col].plot(t, levels, color=color, label=label, linewidth=1)
    axes[2, col].axvline(6, color='#7c878e', linestyle='--', linewidth=.8)
    axes[2, col].set(xlabel='Audio time (s)', ylabel='100 ms RMS (dBFS)', ylim=(-100, -10))
    axes[2, col].grid(alpha=.15)
    if col == 0: axes[2, col].legend(frameon=False, fontsize=8)
    summary[name] = {
        'departedParticles': sum(p['departureSeconds'] is not None for p in particles),
        'departureTimesSeconds': [p['departureSeconds'] for p in particles if p['departureSeconds'] is not None],
        'maxExcursion': max(p['maxExcursion'] for p in particles),
    }
fig.suptitle(f'Libration model {manifest["model"]} — measured trajectories, spectral motion and modal memory\n'
             'Six-second dry source at C4; the dashed line marks the end of source excitation.', fontsize=12)
fig.savefig(root / 'diagnostics.png', dpi=180)
fig.savefig(root / 'diagnostics.svg')

# Control evidence is computed from the same trajectories and WAVs used by the page.
control_summary = {'nudge': [], 'resonance': []}
fig2, (exit_ax, tail_ax) = plt.subplots(1, 2, figsize=(11, 4), constrained_layout=True)
for clip in manifest['clips']:
    if clip['control'] == 'NUDGE':
        ps = json.loads((root / clip['trajectories']).read_text())['particles']
        exits = [p['departureSeconds'] for p in ps if p['departureSeconds'] is not None]
        median = statistics.median(exits)
        control_summary['nudge'].append({'value': clip['value'], 'medianExitSeconds': median,
                                         'earliestExitSeconds': min(exits), 'latestExitSeconds': max(exits)})
        exit_ax.errorbar(clip['value'] * 100, median,
                         yerr=[[median - min(exits)], [max(exits) - median]],
                         fmt='o', color=colors[0], capsize=3)
    if clip['control'] == 'RESONANCE':
        rate, values = signal(clip['id'])
        body = np.sqrt(np.mean(values[int(.3 * rate):int(1.2 * rate)] ** 2))
        tail = np.sqrt(np.mean(values[int(2.1 * rate):int(2.6 * rate)] ** 2))
        control_summary['resonance'].append({'value': clip['value'], 'nativeTailRms': float(tail),
                                            'tailRelativeToBodyDb': float(20 * np.log10(max(1e-10, tail) / body))})
        t, levels = rms_windows(values * clip['diagnostics']['referenceGain'], rate)
        tail_ax.plot(t, levels, label=f'{round(clip["value"] * 100)}%', linewidth=1)
xs = [p['value'] * 100 for p in control_summary['nudge']]
ys = [p['medianExitSeconds'] for p in control_summary['nudge']]
exit_ax.plot(xs, ys, color=colors[0], label='Model 2 median')
if len(sys.argv) > 2:
    baseline = Path(sys.argv[2]).resolve()
    old = json.loads((baseline / 'manifest.json').read_text())
    old_points = []
    for c in old['clips']:
        if c['control'] == 'NUDGE':
            ps = json.loads((baseline / c['trajectories']).read_text())['particles']
            old_points.append((c['value'] * 100, statistics.median(p['departureSeconds'] for p in ps if p['departureSeconds'] is not None)))
    exit_ax.plot(*zip(*old_points), 'o--', color=colors[2], label='Preserved model 1 median')
exit_ax.set(xlabel='NUDGE (%)', ylabel='L2 neighborhood exit (s)', title='NUDGE: later to earlier departure')
exit_ax.legend(frameon=False, fontsize=8); exit_ax.grid(alpha=.15)
tail_ax.axvline(2, linestyle='--', color='#7c878e', linewidth=.8)
tail_ax.set(xlabel='Audio time (s)', ylabel='Matched 100 ms RMS (dBFS)',
            title='RESONANCE: source ends at two seconds', ylim=(-100, -15))
tail_ax.legend(frameon=False, fontsize=8); tail_ax.grid(alpha=.15)
fig2.savefig(root / 'controls.png', dpi=180)
fig2.savefig(root / 'controls.svg')

for clip in manifest['clips']:
    for key, expected in [('audio', 'sha256'), ('rawAudio', 'rawSha256')]:
        path = root / clip[key]
        rate, values = wavfile.read(path)
        assert rate == 44100 and values.ndim == 1 and np.isfinite(values).all()
        with wave.open(str(path)) as wav:
            assert wav.getsampwidth() == (2 if key == 'audio' else 3)
        assert hashlib.sha256(path.read_bytes()).hexdigest() == clip[expected]
report = {
    'wavCount': len(manifest['clips']) * 2,
    'wavFormatsAndHashesVerified': True,
    'maxRelativeJacobiDrift': max(c['diagnostics']['jacobiDrift'] for c in manifest['clips']),
    'maxMeasuredRootErrorCents': max(abs(c['diagnostics']['pitchCents']) for c in manifest['clips']),
    'maxNativeOutputPeak': max(c['diagnostics']['rawPeak'] for c in manifest['clips']),
    'voices': summary,
    'controls': control_summary,
    'listeningAcceptance': 'pending owner review',
}
(root / 'verification.json').write_text(json.dumps(report, indent=2) + '\n')
manifest['hasPlots'] = True
(root / 'manifest.json').write_text(json.dumps(manifest, separators=(',', ':')) + '\n')
archive = root / 'libration-listening-pack.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for path in sorted(root.rglob('*')):
        if path.is_file() and path != archive and path.name != 'libration-round-1.zip' and not path.relative_to(root).as_posix().startswith('audio/raw/'):
            z.write(path, path.relative_to(root).as_posix())
print(json.dumps(report, indent=2))
