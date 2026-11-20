"""Each DRAWL voice at its defaults beside the reference loops it answers to (measured, not heard).

    python3 prototype/drawl/compare_refs.py REFS_METRICS_JSON [OUT_JSON]
"""
import json
import os
import sys
import tempfile

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import drawl_proto as dp  # noqa: E402
import measure  # noqa: E402

# Which reference loops each voice answers to, by the words in their file names.
GROUPS = {
    "CONE": ["dobro"],
    "BOTTLE": ["wasteland_slide", "country_edm_slide", "slide_guitar_one_shot"],
    "LAP": ["Lap_Steel"],
    "STEEL": ["pedal_steel"],
    "FLATTOP": ["acoustic_trotting"],
    "GUT": ["nylon"],
}
KEYS = ["centroid_hz", "decay_db_per_s_loudest_note", "vibrato_rate_hz_median", "vibrato_depth_cents_pp_median",
        "glide_speed_cents_per_s_median", "glide_extent_cents_median"]


def main():
    refs = json.load(open(sys.argv[1]))
    out = {}
    for v in dp.VOICES:
        y, p = dp.render(v)
        fd, tmp = tempfile.mkstemp(suffix=".wav", dir=os.environ.get("SCRATCH"))
        os.close(fd)
        dp.sk.write_wav(tmp, y)
        x, sr = measure.load(tmp)
        os.remove(tmp)
        cen, shares, peaks = measure.ltas(x, sr)
        f0, dt = measure.pitch_track(x, sr)
        ps = measure.pitch_stats(f0, dt)
        ours = {"centroid_hz": round(cen), "decay_db_per_s_loudest_note": measure.decay(x, sr, np.array([0.0]))[0],
                **{k: ps[k] for k in KEYS if k in ps}, "ltas_peaks_hz_db": peaks}
        group = {k: r for k, r in refs.items() if any(w in k for w in GROUPS[v])}
        rng = {}
        for k in KEYS:
            vals = [r[k] for r in group.values() if r.get(k) is not None]
            rng[k] = [min(vals), max(vals)] if vals else None
        out[v] = {"drawl": ours, "reference_range": rng, "references": sorted(group)}
        print(f"\n{v}  (refs: {len(group)})")
        for k in KEYS:
            print(f"  {k:34s} drawl {str(ours.get(k)):>8s}   refs {rng[k]}")
        print("  ltas peaks", peaks)
    if len(sys.argv) > 2:
        json.dump(out, open(sys.argv[2], "w"), indent=1)


if __name__ == "__main__":
    main()
