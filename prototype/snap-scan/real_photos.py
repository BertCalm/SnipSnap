"""SNAP on the owner's photos, read as the app reads them (long side shrunk to 512 px).
Python harness, not Kotlin. Prints only derived numbers; no pixels leave this folder."""
import sys, os, glob, json
import numpy as np
from PIL import Image, ImageOps
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import scan_proto as sp   # reuse cycle/bands/align/render/spec_dist
UP = OUT = None
if __name__ == "__main__": UP = sys.argv[1]; OUT = sys.argv[2]

def load(path):
    im = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    s = 512 / max(im.size)
    if s < 1: im = im.resize((round(im.width*s), round(im.height*s)), Image.BILINEAR)
    return np.asarray(im, dtype=np.float64) / 255.0

def lum(rgb): return 0.299*rgb[..., 0] + 0.587*rgb[..., 1] + 0.114*rgb[..., 2]

def look(rgb):  # Snap.look
    L = lum(rgb); mx = rgb.max(2); mn = rgb.min(2); ch = mx - mn
    sat = np.where(mx > 1e-6, ch/np.maximum(mx, 1e-6), 0)
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    hue = np.where(mx == r, 60*(((g-b)/np.maximum(ch, 1e-9)) % 6),
          np.where(mx == g, 60*((b-r)/np.maximum(ch, 1e-9)+2), 60*((r-g)/np.maximum(ch, 1e-9)+4)))
    w = np.where(ch > 1e-6, sat, 0); hx = (np.cos(np.radians(hue))*w).sum(); hy = (np.sin(np.radians(hue))*w).sum()
    H, W = L.shape; st = max(1, round(max(W, H)/128)); Ls = L[::st, ::st]
    detail = np.mean(np.concatenate([np.abs(np.diff(Ls, axis=1)).ravel(), np.abs(np.diff(Ls, axis=0)).ravel()]))
    return dict(lum=L.mean(), contrast=L.std(), sat=sat.mean(), hue=np.degrees(np.arctan2(hy, hx)) % 360,
                hue_strength=np.hypot(hx, hy)/max(sat.sum(), 1e-9), detail=detail)

def macros_today(f):  # Snap.macrosFrom
    tune = 0.5 if (f["sat"] < .08 or f["hue_strength"] < .2) else ((f["hue"]-330+720) % 360)/360
    return dict(TUNE=round(tune*24)/24, BRIGHT=.15+.8*f["lum"], DECAY=.2+.7*min(f["sat"]*2, 1),
                GRIT=min(f["detail"]*8, 1)*.8)

def portrait(rgb):  # the proposed new readings
    L = lum(rgb); H, W = L.shape
    light = L[:H//3].mean() - L[-H//3:].mean()                       # + sky-bright, - ground-bright
    warmth = (rgb[..., 0] - rgb[..., 2]).mean()                      # + warm, - cool
    a = L - L.mean(); m = a[:, ::-1]
    symmetry = float((a*m).sum() / max((a*a).sum(), 1e-9))           # 1 mirror-symmetric, ~0 none
    clipped = float(((L <= 2/255) | (L >= 253/255)).mean())
    # Immerkaer's noise estimate: a 3x3 kernel that cancels smooth image structure
    k = L[:-2, :-2] - 2*L[:-2, 1:-1] + L[:-2, 2:] - 2*L[1:-1, :-2] + 4*L[1:-1, 1:-1] - 2*L[1:-1, 2:] + L[2:, :-2] - 2*L[2:, 1:-1] + L[2:, 2:]
    grain = float(np.sqrt(np.pi/2) / (6*(W-2)*(H-2)) * np.abs(k).sum())
    return dict(light=light, warmth=warmth, symmetry=symmetry, clipped=clipped, grain=grain)

if __name__ == "__main__":
    files = sorted(glob.glob(UP + "/*"))
    names = {f: os.path.basename(f)[:8] for f in files}
    rows = {}
    for f in files:
        rgb = load(f); feats = look(rgb); feats.update(portrait(rgb))
        feats["macros"] = macros_today(feats); feats["kind"] = "screenshot" if f.endswith(".png") else "photo"
        L = lum(rgb); feats["_L"] = L
        rows[names[f]] = feats

    def spread(key, sub=None):
        v = np.array([(r["macros"][key] if sub else r[key]) for r in rows.values()])
        return [round(float(np.percentile(v, 10)), 3), round(float(np.percentile(v, 90)), 3)]

    rep = {"photos": len(rows),
           "today_knob_10_90pct": {k: spread(k, True) for k in ["TUNE", "BRIGHT", "DECAY", "GRIT"]},
           "today_tune_centre_detent_share": float(np.mean([r["macros"]["TUNE"] == .5 for r in rows.values()])),
           "reading_10_90pct": {k: spread(k) for k in ["lum", "sat", "detail", "light", "warmth", "symmetry", "clipped", "grain"]}}
    # blend dips, real photos
    dip_raw = [sp.blend_dip(sp.bands(r["_L"])) for r in rows.values()]
    dip_ali = [sp.blend_dip(sp.align(sp.bands(r["_L"]))) for r in rows.values()]
    rep["worst_blend_dip_db"] = {"unaligned_median": float(np.median(dip_raw)), "unaligned_worst": float(min(dip_raw)),
                                 "aligned_median": float(np.median(dip_ali)), "aligned_worst": float(min(dip_ali))}
    # timbre distances: the real 4-second re-shot (hammock), round robin, different photos
    A, B = rows["cf9d23c2"]["_L"], rows["aff75ec1"]["_L"]
    def snd(L, seed=1, scan=.6): return sp.render(sp.align(sp.bands(L)), scan=scan, direction=sp.light_dir(L), seed=seed)
    def today(L): return sp.render([sp.cycle(L.mean(0))]*16, scan=0)
    rep["hammock_reshot_today_db"] = sp.spec_dist(today(A), today(B))
    rep["hammock_reshot_scan_db"] = sp.spec_dist(snd(A, 1), snd(B, 9))
    keys = [k for k in rows if k not in ("aff75ec1",)]
    rr = [sp.spec_dist(snd(rows[k]["_L"], 1), snd(rows[k]["_L"], 2)) for k in keys[:8]]
    diff_today = [sp.spec_dist(today(rows[a]["_L"]), today(rows[b]["_L"])) for a, b in zip(keys[:8], keys[8:16])]
    diff_scan = [sp.spec_dist(snd(rows[a]["_L"]), snd(rows[b]["_L"])) for a, b in zip(keys[:8], keys[8:16])]
    rep["round_robin_takes_db_median"] = float(np.median(rr))
    rep["different_photos_today_db_median"] = float(np.median(diff_today))
    rep["different_photos_scan_db_median"] = float(np.median(diff_scan))
    for k in ("cf9d23c2", "aff75ec1", "f4e98422"):
        import snipkit as sk
        sk.write_wav(f"{OUT}/wav/{k}_today.wav", today(rows[k]["_L"])); sk.write_wav(f"{OUT}/wav/{k}_scan.wav", snd(rows[k]["_L"]))
        sk.write_wav(f"{OUT}/wav/{k}_scan_take2.wav", snd(rows[k]["_L"], 2))
    json.dump(rep, open(f"{OUT}/real_report.json", "w"), indent=2, default=float)
    print(json.dumps(rep, indent=1, default=float))
