"""Write what the Kotlin port is checked against: for every pad in the bank, the Python render's length, level and
energy per band (clean and chopped). Also stamps each pad's SLOP draw into the bank, once.

    python3 prototype/kerf/freeze_reference.py

The Kotlin renderer uses the house noise generator, so its samples differ from Python's; these summaries are what
must agree (KerfRenderTest)."""
import sys, os, json
import numpy as np
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import resynth as rs
ROOT = os.path.join(HERE, '..', '..', 'synth', 'src'); BANK = os.path.join(ROOT, 'main', 'resources', 'kerf', 'v1', 'kit_patches.json')
K = json.load(open(BANK)); ref = {}
def summary(y):
    Z = rs.stft(y); return dict(frames=int(len(y)), rms=float(np.sqrt((y**2).mean())), peak=float(np.abs(y).max()),
                                low=float((np.abs(Z[:1])**2).sum()), bands=[float(v) for v in rs.band_power(Z).sum(1)])
for kid, pads in K.items():
    ref[kid] = []
    for p in pads:
        p.setdefault('draw', [float(f"{u:.9g}") for u in rs.slop_draw(p['patch']['seed'])])
        clean = rs.render(p['patch']); chop, info = rs.chopped(p['patch'], p['prev'], p['next'], bleed=p['bleed'], slop=p['slop'], draw=p['draw'])
        ref[kid].append(dict(pad=p['pad'], clean=summary(clean), chopped=dict(summary(chop), start_ms=info['start_ms'], over_ms=info['over_ms'])))
json.dump(K, open(BANK, 'w'), separators=(',', ':'))
json.dump(dict(bins=[list(b) for b in rs.BINS], kits=ref), open(os.path.join(ROOT, 'test', 'resources', 'kerf', 'python_reference.json'), 'w'), separators=(',', ':'))
print('froze', sum(len(v) for v in ref.values()), 'pads; bins', rs.BINS)
