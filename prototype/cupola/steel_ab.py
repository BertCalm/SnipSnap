"""Steel listening pack: for each reference loop, the reference, round 3, then steel v2, all
playing the same transcribed notes, each part matched in loudness. Python harness."""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
import refs, resynth as rs, cupola_proto as cp

OUT = refs.OUT / "steel_ab"
os.makedirs(OUT, exist_ok=True)
gap = np.zeros(int(.9 * rs.R))
parts = {}
for case in rs.CASES:                                   # round 3 first: steel_v2.install() changes the voices
    ex, mix, *_ = rs.render_case(*case)
    parts[case[0]] = [rs.level(ex), rs.level(mix)]
    print("round 3", case[0], flush=True)
import steel_v2
steel_v2.install()
for case in rs.CASES:
    _, mix, *_ = rs.render_case(*case)
    parts[case[0]].append(rs.level(mix))
    ps = [cp.sk.fade_tail(p.copy(), rs.R, 60.0) for p in parts[case[0]]]      # every part fades out, never cut
    seq = np.concatenate([ps[0], gap, ps[1], gap, ps[2]])
    seq = cp.sk.fade_tail(seq, rs.R, 60.0)
    cp.sk.write_wav(str(OUT / f"{case[1]}_{case[0]}_yours_then_round3_then_v2.wav"), seq / max(1.0, np.max(np.abs(seq)) / .9))
    print("v2", case[0], flush=True)
open(OUT / "README.txt", "w").write("""Steel voices. Each file: 8 s of your loop, then CUPOLA round 3, then CUPOLA steel v2,
both playing the notes transcribed from your loop (tuning offset kept, notes snapped to the file's
scale, so a few may differ). Loudness matched. Python renders.
""")
