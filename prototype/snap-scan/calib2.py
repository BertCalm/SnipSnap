"""Full calibration on every upload. Python harness, not Kotlin. Prints derived numbers only."""
import sys, os, glob, json, hashlib, datetime as dt
import numpy as np
from PIL import Image
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scan_proto as sp, real_photos as rp, calib as cb
UP = sys.argv[1]
files = sorted(glob.glob(UP + "/*"))
seen, rows, dup = {}, {}, 0
EXIF_FIELDS = {33434: "exposure", 34855: "iso", 37386: "focal", 41989: "focal35", 37385: "flash", 33437: "fnumber"}
field_count = {v: 0 for v in EXIF_FIELDS.values()}; gps = 0
for f in files:
    rgb = rp.load(f); h = hashlib.sha1((rgb*255).astype(np.uint8).tobytes()).hexdigest()
    if h in seen: dup += 1; continue
    seen[h] = f; im = Image.open(f); ex = im.getexif(); sub = ex.get_ifd(0x8769)
    for k, v in EXIF_FIELDS.items():
        if k in sub: field_count[v] += 1
    if ex.get_ifd(0x8825): gps += 1
    r = rp.look(rgb); r.update(rp.portrait(rgb)); r["macros"] = rp.macros_today(r)
    r["time"] = ex.get(306); r["L"] = rp.lum(rgb)
    # screenshots: PNG, or phone-screen aspect with no camera EXIF
    r["kind"] = "photo" if (33434 in sub or (f.endswith((".jpg", ".webp")) and im.size[0] >= 2000)) else "screenshot"
    rows[os.path.basename(f)[:8]] = r
kinds = {k: [n for n, r in rows.items() if r["kind"] == k] for k in ("photo", "screenshot")}
def pct(vals): return [round(float(np.percentile(vals, p)), 3) for p in (10, 50, 90)]
rep = {"uploads": len(files), "duplicates_removed": dup, "photos": len(kinds["photo"]),
       "screenshots": len(kinds["screenshot"]), "exif_fields_present": field_count, "with_gps": gps,
       "with_capture_time": sum(r["time"] is not None for r in rows.values())}
for kind, names in kinds.items():
    rep[f"{kind}_today_knobs_10_50_90"] = {m: pct([rows[n]["macros"][m] for n in names]) for m in ("TUNE", "BRIGHT", "DECAY", "GRIT")}
    rep[f"{kind}_readings_10_50_90"] = {k: pct([rows[n][k] for n in names]) for k in ("lum", "sat", "detail", "light", "warmth", "symmetry", "clipped", "grain")}
# how many photos share TUNE within one semitone of another (crowding on the pitch knob)
t = np.array([round(r["macros"]["TUNE"]*24) for r in rows.values()])
rep["tune_semitones_used_of_25"] = int(len(set(t))); rep["most_common_semitone_share"] = round(float(np.bincount(t).max()/len(t)), 2)
dips = [sp.blend_dip(sp.bands(r["L"])) for r in rows.values()]; dipa = [sp.blend_dip(sp.align(sp.bands(r["L"]))) for r in rows.values()]
rep["blend_dip_db"] = {"unaligned_worst": round(min(dips), 2), "unaligned_share_below_-3dB": round(float(np.mean(np.array(dips) < -3)), 2),
                       "aligned_worst": round(min(dipa), 2), "aligned_share_below_-3dB": round(float(np.mean(np.array(dipa) < -3)), 2)}
tl = sorted((dt.datetime.strptime(r["time"], "%Y:%m:%d %H:%M:%S"), n) for n, r in rows.items() if r["time"])
pairs = [(a[1], b[1]) for a, b in zip(tl, tl[1:]) if (b[0]-a[0]).total_seconds() <= 120]
rep["reshot_pairs_within_2_min"] = len(pairs)
L = {n: r["L"] for n, r in rows.items()}; names = sorted(L)
rnd = np.random.default_rng(5); diffp = [tuple(rnd.choice(names, 2, replace=False)) for _ in range(30)]
def med(x): return round(float(np.median(x)), 2)
rep["today_timbre_db"] = {"reshot": med([cb.tdist(cb.today(L[a]), cb.today(L[b])) for a, b in pairs]),
                          "different": med([cb.tdist(cb.today(L[a]), cb.today(L[b])) for a, b in diffp])}
for fp in (0.35, 0.5, 0.7):
    rep[f"scan_timbre_db_fp{fp}"] = {
        "round_robin": med([cb.tdist(cb.snd(L[n], 1, fp), cb.snd(L[n], 2, fp)) for n in names[:14]]),
        "reshot": med([cb.tdist(cb.snd(L[a], 1, fp), cb.snd(L[b], 7, fp)) for a, b in pairs]),
        "different": med([cb.tdist(cb.snd(L[a], 1, fp), cb.snd(L[b], 1, fp)) for a, b in diffp])}
# percentile tables for the rank mapping: 9 points per reading, photos and screenshots together
rep["percentile_table_0_to_100_by_12.5"] = {k: [round(float(np.percentile([r[k] for r in rows.values()], p)), 4) for p in np.linspace(0, 100, 9)]
                                           for k in ("lum", "sat", "detail", "light", "warmth", "symmetry", "clipped", "grain")}
json.dump(rep, open(f"{os.path.dirname(os.path.abspath(__file__))}/calib_report.json", "w"), indent=1)
print(json.dumps(rep, indent=1))
