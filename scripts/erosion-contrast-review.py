"""Attach actual previous-listen WAVs and diagnostics to a generated contrast round.

Run after generateErosionBank:
  python3 scripts/erosion-contrast-review.py NEW_OUTPUT OLD_PLAYING_ROUND
Requires NumPy and SciPy, like the Luthier prototype harness. Never overwrites
old audio. Matched comparison copies are PCM16; native originals retain bytes.
"""
from pathlib import Path
import argparse, hashlib, html, json, shutil
import numpy as np
from scipy.io import wavfile
from scipy.signal import welch


def load(path):
    rate, x = wavfile.read(path)
    if np.issubdtype(x.dtype, np.integer):
        x = x.astype(np.float64) / (2 ** (np.iinfo(x.dtype).bits - 1))
    else:
        x = x.astype(np.float64)
    assert x.ndim == 1 and rate == 44100 and np.all(np.isfinite(x))
    return rate, x


def match(x, rate):
    width = int(rate * .05)
    active = max(np.sqrt(np.mean(x[i:i+width] ** 2)) for i in range(0, len(x), width))
    gain = min(.08 / max(active, 1e-12), .95 / max(np.max(np.abs(x)), 1e-12))
    return x * gain, float(gain)


def measure(root, manifest):
    rows = [dict(row) for row in manifest['bank'] if row['velocity'] == 1]
    features = []
    for row in rows:
        rate, x = load(root / row['wav'])
        x /= max(np.max(np.abs(x)), 1e-12)
        f, power = welch(x, rate, nperseg=2048)
        power /= max(power.sum(), 1e-20)
        bands = np.geomspace(80, 18000, 33)
        v = 10 * np.log10(np.maximum([power[(f >= a) & (f < b)].sum()
                                      for a, b in zip(bands[:-1], bands[1:])], 1e-7))
        features.append(v - v.mean())
        energy = np.cumsum(x * x)
        row['t90_ms'] = float(np.searchsorted(energy, energy[-1] * .9) / rate * 1000)
    features = np.array(features)
    distances = np.sqrt(np.mean((features[:, None] - features[None, :]) ** 2, axis=2))
    pairs = sorted((float(distances[i, j]), rows[i]['name'], rows[j]['name'])
                   for i in range(len(rows)) for j in range(i + 1, len(rows)))
    return dict(method='RMS difference of centered log-power in 32 log-frequency bands, 80-18000 Hz. Diagnostic only, not an audibility score.',
                rows=rows, pairs=pairs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('new', type=Path)
    parser.add_argument('old', type=Path)
    args = parser.parse_args()
    new, old = args.new.resolve(), args.old.resolve()
    assert new != old
    current = json.loads((new / 'manifest.json').read_text())
    previous = json.loads((old / 'manifest.json').read_text())
    old_rows = {v['name']: v for v in previous['bank'] if v['velocity'] == 1}
    new_rows = {v['name']: v for v in current['bank'] if v['velocity'] == 1}
    related = dict(THUD='PIT', CHIP='PIN', RASP='SPALL', WIRE='WIRE', DUST='SHEEN',
                   DROPS='BASIN', SPLINTER='FILINGS', SCAR='SCAR DEFAULT')
    target = new / 'historical'
    target.mkdir(exist_ok=True)
    cards, evidence = [], []
    for name, old_name in related.items():
        source = old / (old_rows[old_name]['wav'] if old_name in old_rows else 'audio/scar-default.wav')
        frozen = target / f'{name.lower()}-previous-native.wav'
        shutil.copy2(source, frozen)
        assert frozen.read_bytes() == source.read_bytes()
        rate, x = load(frozen)
        matched, gain = match(x, rate)
        path = f'historical/{name.lower()}-previous-matched.wav'
        wavfile.write(new / path, rate, (np.clip(matched, -1, 1) * 32767).astype('<i2'))
        revised = new_rows[name]['matched']
        cards.append(f'<article><h3>{html.escape(name)}</h3><label>Earlier related example: {html.escape(old_name)}'
                     f'<audio controls preload="none" src="{path}"></audio></label>'
                     f'<label>Revised role<audio controls preload="none" src="{revised}"></audio></label></article>')
        evidence.append(dict(role=name, earlier=old_name, previous_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                             frozen=str(frozen.relative_to(new)), matched=path, matched_gain=gain,
                             revised=revised, note='Related-role comparison; recipes differ. Use GRAIN on/off pairs for the single-control experiment.'))
    diagnostics = dict(previous=measure(old, previous), revised=measure(new, current), historical=evidence)
    (new / 'contrast-review.json').write_text(json.dumps(diagnostics, indent=2))
    page = new / 'index.html'
    text = page.read_text()
    marker = '<section id="historical-review">'
    if marker in text:
        start = text.index(marker)
        end = text.index('</section>', start) + len('</section>')
        text = text[:start] + text[end:]
    section = (marker + '<h2>Related earlier examples</h2><p>Actual previous WAVs, with matched copies for listening. '
               'These compare related roles with changed recipes. The GRAIN pairs above isolate one control.</p>'
               + ''.join(cards) + '</section>')
    text = text.replace('<h2>Eight roles</h2>', section + '<h2>Eight roles</h2>')
    page.write_text(text)
    print('Attached 8 frozen earlier examples and the old/new feature report. New listening still required.')


if __name__ == '__main__':
    main()
