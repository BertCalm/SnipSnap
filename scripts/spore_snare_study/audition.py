"""Package the snare study; reference audio stays outside the synthetic WAV download."""
import argparse
import hashlib
import json
import shutil
import wave
import zipfile
from pathlib import Path
import numpy as np
from scipy import signal
from model import RATE, render
from calibrate import load


def write(path, x):
    assert np.isfinite(x).all() and max(abs(x), default=0) < 1
    pcm = np.round(x * 8388607).astype(np.int32)
    data = np.column_stack([pcm & 255, (pcm >> 8) & 255, (pcm >> 16) & 255]).astype('uint8').tobytes()
    with wave.open(str(path), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(3); w.setframerate(RATE); w.writeframes(data)


def repeated(x, count=2, spacing=.7):
    y = np.zeros(round(RATE * (spacing * (count - 1) + .50)))
    for i in range(count):
        at = round(i * spacing * RATE)
        y[at:at + len(x)] += x
    return y


def main():
    p = argparse.ArgumentParser(); p.add_argument('output', type=Path); a = p.parse_args(); root = a.output
    model = json.loads((root / 'model.json').read_text())
    report = json.loads((root / 'report.json').read_text())
    audio = root / 'audio'; audio.mkdir(exist_ok=True)
    new = render(model)
    ref = np.pad(load(root / 'reference-crop.wav'), (0, len(new) - round(.14 * RATE)))
    old = load(root / 'previous.wav')
    signals = {'candidate': new, 'reference': ref, 'previous': old}
    def rms120(x):
        return np.sqrt(np.mean(x[:round(.12 * RATE)] ** 2))
    gains = {k: .12 / rms120(x) for k, x in signals.items()}
    safe = min(1., .90 / max(max(abs(x)) * gains[k] for k, x in signals.items()))
    gains = {k: v * safe for k, v in gains.items()}
    clips = []
    def add(id, title, group, x, gain):
        write(audio / (id + '.wav'), x)
        write(audio / (id + '-matched.wav'), x * gain)
        clips.append(dict(id=id, title=title, group=group, raw='audio/' + id + '.wav',
                          matched='audio/' + id + '-matched.wav'))
    for k, title in [('reference', 'Your live reference'), ('candidate', 'Rebuilt snare'), ('previous', 'Rejected prototype')]:
        add(k, title, 'compare', repeated(signals[k]), gains[k])
    for stem in ['attack', 'body', 'tail']:
        add(stem, stem.capitalize() + ' only', 'parts', repeated(render(model, stem=stem)), gains['candidate'])
    pack = root / 'synthetic-wavs'; pack.mkdir(exist_ok=True)
    all_hits = {}
    for zone, vel in [('soft', .3), ('medium', .65), ('hard', 1.)]:
        grid = np.zeros(round(2.1 * RATE))
        for take in range(3):
            hit = render(model, take=take, velocity=vel)
            all_hits[zone, take] = hit
            write(pack / f'snare-{zone}-take-{take + 1}.wav', hit)
            at = round(take * .65 * RATE); grid[at:at + len(hit)] += hit
        # Same full-strike gain on all velocity demonstrations, preserving the dynamics.
        add(zone, zone.capitalize() + ' · three fresh strikes', 'touch', grid, gains['candidate'])
    # Deliberately a snare-only 170 BPM phrase, no hats or kick masking its character.
    beat = 60 / 170
    score = [(1, 'hard'), (1.75, 'soft'), (2.5, 'medium'), (2.75, 'soft'),
             (3, 'hard'), (4.75, 'soft'), (5, 'hard'), (5.5, 'soft'), (6.75, 'medium'), (7, 'hard')]
    counters = {z: 0 for z in ['soft', 'medium', 'hard']}
    phrase = np.zeros(round((8 * beat + .5) * RATE))
    for when, zone in score:
        hit = all_hits[zone, counters[zone] % 3]; counters[zone] += 1
        at = round(when * beat * RATE); phrase[at:at + len(hit)] += hit
    phrase_scale = min(1., .90 / max(abs(phrase * gains['candidate'])))
    add('phrase', 'Ghosts into backbeats · 170 BPM', 'touch', phrase * phrase_scale, gains['candidate'])
    shutil.copy2(root / 'model.json', pack / 'model.json')
    (pack / 'README.txt').write_text('SPORE SNARE RESET — Python listening study\n\nNine sample-free synthesized WAVs: three velocities, three distinct strikes each.\nNo reference recording is in this download. model.json is a small coefficient table, not PCM.\nThis is a WAV study pack, not a registered SnipSnap instrument or an MPC/SFZ kit.\nNo claim of automatic velocity/RR selection in a sampler. Sound acceptance is pending.\nGenerator: scripts/spore_snare_study in the SnipSnap repository.\n')
    with zipfile.ZipFile(root / 'synthetic-snare-study.zip', 'w', zipfile.ZIP_DEFLATED) as z:
        for f in sorted(pack.iterdir()): z.write(f, f.name)
    manifest = dict(status='snare-study-awaiting-listen', harness='Python', clips=clips,
                    levelMatch='First 120 ms RMS; one gain per comparison source, shared across all candidate dynamics and stems',
                    phrase=dict(bpm=170, score=score),
                    sourceHashes={f.name: hashlib.sha256(f.read_bytes()).hexdigest()
                                  for f in Path(__file__).parent.glob('*.py')})
    manifest['assetHashes'] = {str(f.relative_to(root)): hashlib.sha256(f.read_bytes()).hexdigest()
                               for f in sorted(audio.glob('*.wav'))}
    (root / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    shutil.copy2(Path(__file__).with_name('index.html'), root / 'index.html')
    # A static measurement figure complements listening; it is not an acceptance score.
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(3, 1, figsize=(9, 7), sharex=True, sharey=True, constrained_layout=True)
    for ax, (name, label) in zip(axes, [('reference', 'Supplied live excerpt (mixed recording)'), ('previous', 'Rejected synthesis'), ('candidate', 'Rebuilt synthesis')]):
        f, t, z = signal.stft(signals[name] * gains[name], RATE, nperseg=512, noverlap=448)
        ax.pcolormesh(t * 1000, f / 1000, 20 * np.log10(np.maximum(abs(z), 1e-8)), vmin=-65, vmax=-20, cmap='magma', shading='auto')
        ax.set_ylim(.15, 12); ax.set_xlim(0, 120); ax.set_ylabel('kHz'); ax.set_title(label, loc='left', fontsize=10)
    axes[-1].set_xlabel('Milliseconds after onset'); fig.savefig(root / 'comparison.png', dpi=140); plt.close(fig)
    # An offline copy avoids fetch restrictions when index.html is opened from disk.
    html = (root / 'index.html').read_text()
    fetch_chain = "fetch('manifest.json').then(r=>{if(!r.ok)throw Error('manifest');return r.json()})"
    assert fetch_chain in html
    local_html = html.replace(fetch_chain, 'Promise.resolve(' + json.dumps(manifest) + ')')
    local_html = local_html.replace('href="../synthesis/"', 'href="https://spore-station-jungle-audition.bertcalm.chatgpt.site/synthesis/"').replace('href="../"', 'href="https://spore-station-jungle-audition.bertcalm.chatgpt.site/"')
    with zipfile.ZipFile(root / 'snare-audition-offline.zip', 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('index.html', local_html)
        for f in audio.glob('*.wav'):
            z.write(f, 'audio/' + f.name)
        for name in ['manifest.json', 'model.json', 'report.json', 'comparison.png', 'synthetic-snare-study.zip']:
            z.write(root / name, name)
        if (root / 'study.md').exists(): z.write(root / 'study.md', 'study.md')
        z.writestr('OPEN-ME.txt', 'Unzip this folder, then open index.html. No server is required. The reference excerpt is for your private comparison. The nested synthetic-snare-study.zip contains only synthesized audio.\n')
    print(f'Packaged {len(clips)} clips, nine synthetic WAVs and reference comparisons.')

if __name__ == '__main__': main()
