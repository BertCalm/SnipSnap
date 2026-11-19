"""Render the four synthetic chop kits from kit_patches.json. No source audio is needed or used.

    python3 render_kits.py OUT_DIR [--bleed 1.0] [--slop 0.5]

Each pad is rendered by resynth.chopped(): the pad's own patch, with its two neighbours' patches
supplying the bleed and the overrun. Renders are deterministic: the same patches give the same bytes."""
import sys, os, json, hashlib, argparse
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import chop, resynth as rs

ap = argparse.ArgumentParser(); ap.add_argument('out'); ap.add_argument('--bleed', type=float, default=None); ap.add_argument('--slop', type=float, default=None)
a = ap.parse_args(); K = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'kit_patches.json'))); h = hashlib.sha256()
for kid, pads in K.items():
    d = os.path.join(a.out, f"Synthetic Chop Kit {kid}"); os.makedirs(d, exist_ok=True)
    for p in pads:
        y, info = rs.chopped(p['patch'], p['prev'], p['next'], bleed=p['bleed'] if a.bleed is None else a.bleed, slop=p['slop'] if a.slop is None else a.slop)
        y = np.clip(y*0.9/max(1.0, np.abs(y).max()), -1, 1); h.update((y*32767).astype('<i2').tobytes())
        chop.write(os.path.join(d, f"Kit{kid}_{p['pad']:02d}_{p['cls']}.wav"), y)
print('rendered', sum(len(v) for v in K.values()), 'pads; sha256 of all PCM', h.hexdigest()[:16])
