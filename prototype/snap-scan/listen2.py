"""Round 0b listening set: today against the distinct build (L4), plus a round-robin take.
Each photo plays at its own hue pitch (today's TUNE mapping) so only the character differs."""
import sys, os
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import distinct as d, real_photos as rp, snipkit as sk
U, OUT = sys.argv[1], sys.argv[2]
PICKS = {"sign": "aba96705", "flowers": "56bd073b", "clouds": "b01bb86b", "rabbit": "e11d55dd",
         "smiley": "0cc20429", "redlight": "ed8e833e"}
for name, h in PICKS.items():
    r = d.read(next(os.path.join(U, x) for x in os.listdir(U) if x.startswith(h)))
    tune = rp.macros_today(r["f"])["TUNE"]
    sk.write_wav(f"{OUT}/{name}_A_today.wav", d.render(r, "L0_today", tune=tune))
    sk.write_wav(f"{OUT}/{name}_B_distinct.wav", d.render(r, "L4_contrast", tune=tune))
    sk.write_wav(f"{OUT}/{name}_C_distinct_take2.wav", d.render(r, "L4_contrast", take=1, tune=tune))
