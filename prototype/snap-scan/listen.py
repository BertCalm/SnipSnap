"""Listening set: four of Josh's photos, today's SNAP vs SCAN, plus a round-robin take.
Same knobs for every render (TUNE .5, BRIGHT .6, DECAY .5) so only the read differs."""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
# snipkit (the house output stage and WAV writer) lives in the Luthier bundle.
sys.path.insert(1, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '.claude', 'skills', 'luthier', 'scripts'))
import scan_proto as sp, real_photos as rp, snipkit as sk
U, OUT = sys.argv[1], sys.argv[2]
PICKS = {"sign": "aba96705", "flowers": "56bd073b", "clouds": "b01bb86b", "rabbit": "e11d55dd"}
for name, h in PICKS.items():
    f = next(os.path.join(U, x) for x in os.listdir(U) if x.startswith(h))
    L = rp.lum(rp.load(f)); T = sp.align(sp.bands(L)); d = sp.light_dir(L)
    sk.write_wav(f"{OUT}/{name}_1_today.wav", sp.render([sp.cycle(L.mean(0))]*16, scan=0))
    sk.write_wav(f"{OUT}/{name}_2_scan.wav", sp.render(T, scan=.6, direction=d, seed=1, fp=.5))
    sk.write_wav(f"{OUT}/{name}_3_scan_take2.wav", sp.render(T, scan=.6, direction=d, seed=2, fp=.5))
    sk.write_wav(f"{OUT}/{name}_4_scan_full.wav", sp.render(T, scan=1.0, direction=d, seed=1, fp=.5, decay=.8))
