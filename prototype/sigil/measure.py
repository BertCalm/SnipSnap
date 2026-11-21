"""SIGIL: measure drum and percussion loops (the owner's references, or our own grooves). Python harness.

Usage: python3 measure.py <refs_dir> <out_dir>
Writes refs.json (numbers only) and a spectrogram PNG per file. The references themselves are never committed.
"""
import json, sys, os, wave, re
import numpy as np
from PIL import Image

RATE = 44100


def read_wav(path):
    with wave.open(path, "rb") as w:
        ch, sw, sr, n = w.getnchannels(), w.getsampwidth(), w.getframerate(), w.getnframes()
        raw = w.readframes(n)
    assert sw == 3 and sr == RATE, (path, sw, sr)
    b = np.frombuffer(raw, dtype=np.uint8).reshape(-1, 3)
    v = (b[:, 0].astype(np.int32) | (b[:, 1].astype(np.int32) << 8) | (b[:, 2].astype(np.int32) << 16))
    v = np.where(v >= 1 << 23, v - (1 << 24), v).astype(np.float64) / (1 << 23)
    return v.reshape(-1, ch)


def db(x):
    return 20 * np.log10(max(x, 1e-12))


def stft(x, n=2048, hop=512):
    win = np.hanning(n)
    pad = np.concatenate([np.zeros(n // 2), x, np.zeros(n)])
    frames = 1 + (len(pad) - n) // hop
    idx = np.arange(n)[None, :] + hop * np.arange(frames)[:, None]
    return np.abs(np.fft.rfft(pad[idx] * win, axis=1)), hop


BANDS = [(20, 60), (60, 150), (150, 400), (400, 1000), (1000, 3000), (3000, 6000), (6000, 12000), (12000, 20000)]


def band_shares(mag):
    f = np.fft.rfftfreq(2048, 1 / RATE)
    p = (mag ** 2).sum(axis=0)
    tot = p[(f >= 20) & (f < 20000)].sum()
    return [float(p[(f >= lo) & (f < hi)].sum() / tot) for lo, hi in BANDS], f, p


def onsets(mag, hop, lo, hi, f):
    sel = (f >= lo) & (f < hi)
    e = np.log1p(100 * mag[:, sel]).sum(axis=1)
    flux = np.maximum(np.diff(e, prepend=e[0]), 0)
    thr = np.median(flux) + 1.5 * np.std(flux)
    peaks = [i for i in range(1, len(flux) - 1) if flux[i] > thr and flux[i] >= flux[i - 1] and flux[i] > flux[i + 1]]
    # refractory 50 ms
    out, last = [], -1e9
    for i in peaks:
        t = i * hop / RATE
        if t - last > 0.05:
            out.append((t, float(flux[i])))
            last = t
    return out


def swing_and_tightness(times, bpm, length):
    """Fit grid phase on onsets, report 16th-offbeat placement (swing) and deviation."""
    s16 = 60 / bpm / 4
    best = None
    for ph in np.linspace(-s16 / 2, s16 / 2, 41):
        d = ((np.array(times) - ph) / s16)
        dev = np.abs(d - np.round(d))
        score = np.median(dev)
        if best is None or score < best[0]:
            best = (score, ph)
    ph = best[1]
    d = (np.array(times) - ph) / s16
    k = np.round(d).astype(int)
    dev_ms = (d - k) * s16 * 1000
    odd = (k % 2 == 1)
    # swing: mean delay of odd 16ths relative to straight, as % of a 16th; 50 = straight, 66 = triplet
    if odd.sum() >= 3:
        late = np.median(dev_ms[odd]) - np.median(dev_ms[~odd]) if (~odd).sum() else np.median(dev_ms[odd])
        swing_pct = 50 + 50 * (late / (s16 * 1000))
    else:
        swing_pct = None
    return {
        "median_abs_dev_ms": float(np.median(np.abs(dev_ms))),
        "swing_pct_16th": None if swing_pct is None else float(swing_pct),
        "odd16_share": float(odd.mean()) if len(odd) else 0.0,
    }


def spectrogram_png(mag, path, title_h=0):
    # log-frequency spectrogram 20 Hz..20 kHz, 256 rows
    f = np.fft.rfftfreq(2048, 1 / RATE)
    rows = 256
    edges = np.geomspace(20, 20000, rows + 1)
    img = np.zeros((rows, mag.shape[0]))
    for r in range(rows):
        sel = (f >= edges[r]) & (f < edges[r + 1])
        if not sel.any():
            sel = np.argmin(np.abs(f - edges[r]))
            img[r] = mag[:, sel]
        else:
            img[r] = mag[:, sel].mean(axis=1)
    img = 20 * np.log10(img + 1e-9)
    img = np.clip((img - (img.max() - 80)) / 80, 0, 1)
    img = img[::-1]
    w = min(img.shape[1], 1600)
    rgb = np.stack([img ** 0.6, img ** 1.2, (1 - img) * 0.4 + img ** 3], axis=-1)
    im = Image.fromarray((rgb * 255).astype(np.uint8)).resize((w, rows * 2))
    im.save(path)


def analyse(path, out_dir):
    name = os.path.basename(path)[:-4]
    m = re.match(r"([A-Z_]+?)_(\d+)_(.*)", name)
    st = read_wav(path)
    r = analyse_signal(name, int(m.group(2)), st, out_dir, pack=m.group(1))
    r["hits"] = hit_summary(st.mean(axis=1))
    return r


def analyse_signal(name, bpm, st, out_dir=None, pack=""):
    """st: frames x channels float array at 44.1 kHz (a mono array is fine)."""
    if st.ndim == 1:
        st = st[:, None]
    mono = st.mean(axis=1)
    L, R = st[:, 0], st[:, 1] if st.shape[1] > 1 else st[:, 0]
    mid, side = (L + R) / 2, (L - R) / 2
    dur = len(mono) / RATE
    beats = dur * bpm / 60
    rmsv = np.sqrt(np.mean(mono ** 2))
    pk = np.max(np.abs(st))
    # frames
    fr = int(0.05 * RATE)
    nfr = len(mono) // fr
    frms = np.sqrt((mono[: nfr * fr].reshape(nfr, fr) ** 2).mean(axis=1))
    floor = np.percentile(frms, 5)
    mag, hop = stft(mono)
    shares, f, p = band_shares(mag)
    cen = float((f * p).sum() / p.sum())
    cum = np.cumsum(p) / p.sum()
    roll95 = float(f[np.searchsorted(cum, 0.95)])
    roll99 = float(f[np.searchsorted(cum, 0.99)])
    # HF ceiling: highest freq whose long-term level is within 60 dB of the peak bin
    pdb = 10 * np.log10(p + 1e-20)
    ceil = float(f[np.where(pdb > pdb.max() - 60)[0].max()])
    # onsets by band
    on_low = onsets(mag, hop, 30, 150, f)
    on_mid = onsets(mag, hop, 150, 2500, f)
    on_hi = onsets(mag, hop, 5000, 16000, f)
    on_all = onsets(mag, hop, 30, 16000, f)
    groove = swing_and_tightness([t for t, _ in on_all], bpm, dur) if len(on_all) > 6 else {}
    groove_hi = swing_and_tightness([t for t, _ in on_hi], bpm, dur) if len(on_hi) > 6 else {}
    # transient sharpness: median attack over 5 ms vs body 50 ms around all-band onsets
    atk = []
    for t, _ in on_all:
        i = int(t * RATE)
        a = mono[max(0, i - 256): i + 512]
        b = mono[i + 512: i + 4410]
        if len(b) > 100:
            atk.append(db(np.max(np.abs(a))) - db(np.sqrt(np.mean(b ** 2))))
    # clipping / saturation proxy: share of samples within 0.5 dB of peak
    near_peak = float(np.mean(np.abs(mono) > np.max(np.abs(mono)) * 0.944))
    # spectral flatness (tonal vs noisy), over loud frames
    fe = (mag ** 2).sum(axis=1)
    loud = fe > np.percentile(fe, 50)
    mm = mag[loud][:, (f > 100) & (f < 10000)] + 1e-12
    flat = float(np.median(np.exp(np.mean(np.log(mm), axis=1)) / np.mean(mm, axis=1)))
    # stereo
    side_db = db(np.sqrt(np.mean(side ** 2))) - db(np.sqrt(np.mean(mid ** 2)) + 1e-12)
    corr = float(np.corrcoef(L, R)[0, 1]) if np.std(R) > 0 else 1.0
    # dominant pitch class (for keyed loops): chroma on 100..2000 Hz
    sel = (f > 100) & (f < 2000)
    pc = np.zeros(12)
    midi = 69 + 12 * np.log2(f[sel] / 440)
    np.add.at(pc, np.round(midi).astype(int) % 12, p[sel])
    names = "C C# D D# E F F# G G# A A# B".split()
    top_pc = [names[i] for i in np.argsort(pc)[::-1][:3]]
    # strongest low peak (kick/sub fundamental)
    lowsel = (f > 35) & (f < 200)
    low_peak = float(f[lowsel][np.argmax(p[lowsel])])
    if out_dir:
        spectrogram_png(mag, os.path.join(out_dir, name + ".png"))
    return {
        "name": name, "pack": pack, "bpm": bpm, "dur_s": round(dur, 3), "beats": round(beats, 2),
        "peak_dbfs": round(db(pk), 2), "rms_dbfs": round(db(rmsv), 2), "crest_db": round(db(pk) - db(rmsv), 2),
        "floor_db_under_rms": round(db(rmsv) - db(floor), 1),
        "bands": {f"{lo}-{hi}": round(s * 100, 1) for (lo, hi), s in zip(BANDS, shares)},
        "centroid_hz": round(cen), "roll95_hz": round(roll95), "roll99_hz": round(roll99), "ceiling_60db_hz": round(ceil),
        "onsets_per_beat": {"all": round(len(on_all) / beats, 2), "low": round(len(on_low) / beats, 2),
                             "mid": round(len(on_mid) / beats, 2), "high": round(len(on_hi) / beats, 2)},
        "groove_all": groove, "groove_high": groove_hi,
        "attack_over_body_db": round(float(np.median(atk)), 1) if atk else None,
        "near_peak_share": round(near_peak * 100, 3), "flatness": round(flat, 3),
        "side_over_mid_db": round(side_db, 1), "lr_corr": round(corr, 3),
        "top_pitch_classes": top_pc, "low_peak_hz": round(low_peak, 1),
    }


# ---- per-hit measures (kick/snare/hat filing)
def f0_at(x, lo=35, hi=200):
    n = 8192
    seg = x[:n] * np.hanning(min(n, len(x)))[: len(x[:n])]
    sp = np.abs(np.fft.rfft(seg, n * 4))
    f = np.fft.rfftfreq(n * 4, 1 / RATE)
    s = (f > lo) & (f < hi)
    return float(f[s][np.argmax(sp[s])])


def band_share(x, lo, hi):
    sp = np.abs(np.fft.rfft(x * np.hanning(len(x)))) ** 2
    f = np.fft.rfftfreq(len(x), 1 / RATE)
    return float(sp[(f >= lo) & (f < hi)].sum() / sp[(f >= 20)].sum())


def centroid(x):
    sp = np.abs(np.fft.rfft(x * np.hanning(len(x)))) ** 2
    f = np.fft.rfftfreq(len(x), 1 / RATE)
    return float((f * sp).sum() / sp.sum())


def decay_ms(x, drop_db=20):
    env = np.sqrt(np.convolve(x ** 2, np.ones(220) / 220, mode="same"))
    pk = np.argmax(env)
    thr = env[pk] * 10 ** (-drop_db / 20)
    after = np.where(env[pk:] < thr)[0]
    return float(after[0] / RATE * 1000) if len(after) else float((len(x) - pk) / RATE * 1000)


def harm_ratio(x, f0):
    """energy in harmonics 2..8 over the fundamental, first 150 ms: a drive proxy."""
    n = min(len(x), int(0.15 * RATE))
    sp = np.abs(np.fft.rfft(x[:n] * np.hanning(n), 1 << 15)) ** 2
    f = np.fft.rfftfreq(1 << 15, 1 / RATE)
    def e(h):
        return sp[(f > f0 * h * 0.94) & (f < f0 * h * 1.06)].sum()
    return 10 * np.log10(sum(e(h) for h in range(2, 9)) / (e(1) + 1e-20) + 1e-12)




def hit_summary(x):
    """Segment a mono loop at its onsets, file each hit K (kick), S (snare), H (hat) or T (tonal/other) by band
    over its first 120 ms, and return medians per class. Crude by design: the same probe reads the references
    and our grooves, so the comparison is like for like even where the filing is wrong."""
    mag, hop = stft(x)
    f = np.fft.rfftfreq(2048, 1 / RATE)
    ons = [t for t, _ in onsets(mag, hop, 30, 16000, f)]
    rows = []
    for i, t in enumerate(ons):
        a = int(t * RATE) - 128
        b = int(ons[i + 1] * RATE) - 128 if i + 1 < len(ons) else len(x)
        seg = x[max(0, a): min(b, a + int(0.6 * RATE))]
        if len(seg) < 2000:
            continue
        head = seg[: int(0.12 * RATE)]
        low, hi, mid = band_share(head, 30, 150), band_share(head, 5000, 16000), band_share(head, 1000, 5000)
        kind = "K" if low > 0.6 else ("H" if hi > 0.35 else ("S" if mid + hi > 0.15 else "T"))
        rows.append((kind, 20 * np.log10(np.max(np.abs(seg)) + 1e-12), centroid(head), decay_ms(seg), seg))
    out = {}
    for k in "KSHT":
        r = [q for q in rows if q[0] == k]
        if not r:
            continue
        d = {"n": len(r), "peak_dbfs": float(np.median([q[1] for q in r])),
             "centroid_hz": float(np.median([q[2] for q in r])), "to_m20db_ms": float(np.median([q[3] for q in r]))}
        if k == "K":
            d["f0_attack_hz"] = float(np.median([f0_at(q[4][:2048]) for q in r]))
            body = [f0_at(q[4][int(0.08 * RATE):]) for q in r if len(q[4]) > 0.2 * RATE]
            d["f0_body_hz"] = float(np.median(body)) if body else None
            d["harmonics_over_fund_db"] = float(np.median([harm_ratio(q[4], f0_at(q[4])) for q in r]))
        out[k] = d
    return out


if __name__ == "__main__":
    src, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    res = [analyse(os.path.join(src, n), out) for n in sorted(os.listdir(src)) if n.endswith(".wav")]
    json.dump(res, open(os.path.join(out, "references.json"), "w"), indent=1)
    hdr = ["name", "beats", "rms", "crest", "floor", "<60", "60-150", "150-400", "400-1k", "1-3k", "3-6k", "6-12k", ">12k",
           "cent", "r95", "ceil", "on/b", "lo/b", "mid/b", "hi/b", "dev", "swing", "swingH", "atk", "flat", "side", "corr", "pcs", "lowpk"]
    print(" | ".join(hdr))
    for r in res:
        b = list(r["bands"].values())
        g, gh = r["groove_all"], r["groove_high"]
        print(" | ".join(str(x) for x in [r["name"][:34], r["beats"], r["rms_dbfs"], r["crest_db"], r["floor_db_under_rms"], *b,
              r["centroid_hz"], r["roll95_hz"], r["ceiling_60db_hz"], *r["onsets_per_beat"].values(),
              round(g.get("median_abs_dev_ms", -1), 1), round(g.get("swing_pct_16th") or -1, 1), round(gh.get("swing_pct_16th") or -1, 1),
              r["attack_over_body_db"], r["flatness"], r["side_over_mid_db"], r["lr_corr"], "".join(r["top_pitch_classes"]), r["low_peak_hz"]]))

