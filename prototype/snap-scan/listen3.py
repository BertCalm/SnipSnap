"""Round 0c listening set: today's SNAP against the round-0c build (own amounts, a family, a
register), plus a round-robin take; two photos per family and one same-scene pair."""
import sys, os, json
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import families as fm, distinct as d, real_photos as rp, snipkit as sk
U, OUT, REPORT = sys.argv[1], sys.argv[2], sys.argv[3]
fm.EXTRA.update(json.load(open(REPORT))["calibration_v1b_extra"])
PICKS = [("breath_sign", "aba96705"), ("breath_clouds", "b01bb86b"), ("pluck_rabbit", "e11d55dd"),
         ("pluck_sculpture", "21d6941a"), ("strike_flowers", "56bd073b"), ("strike_smiley", "0cc20429"),
         ("tone_redlight", "ed8e833e"), ("tone_mural", "fb905b14"),
         ("samescene_bear1", "933ffbf8"), ("samescene_bear2", "482a3df8")]
for name, h in PICKS:
    r = fm.read(next(os.path.join(U, x) for x in os.listdir(U) if x.startswith(h)))
    f = r["f"]; w = fm.family_weights(f)
    sk.write_wav(f"{OUT}/{name}_A_today.wav", d.render(r, "L0_today", tune=rp.macros_today(f)["TUNE"]))
    sk.write_wav(f"{OUT}/{name}_B_new.wav", fm.render(r, 0, midi=fm.note_new(f)))
    if not name.startswith("samescene"):
        sk.write_wav(f"{OUT}/{name}_C_new_take2.wav", fm.render(r, 1, midi=fm.note_new(f)))
    print(name, {k: round(v, 2) for k, v in w.items() if v}, "note", fm.note_new(f), "today", fm.note_today(f))
