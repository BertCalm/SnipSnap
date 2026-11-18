"""FACSIMILE stage 2: from NMFD components to named drums with timed, weighted hits.

Three jobs:
1. Merge components that always fire together. NMFD often splits one drum into two parts (a
   kick's sub and its body); two parts that co-fire on most strong hits are one drum.
2. List every hit of every drum: time (refined to the millisecond on the isolated audio, so the
   groove keeps its real micro-timing), and gain (so ghost notes stay ghost notes).
3. Build the drum's fitting target. The merged NMFD template is the authoritative target for
   the spectrum over time: by construction it is one strike that only decays. The isolated
   audio (soft masks) is trusted only until the drum's own next hit, so the cut stops there;
   it supplies what a 5.8 ms magnitude template cannot: the kick's pitch drop and the exact
   frequencies of ringing tones. A median over the aligned hits rejects one-off bleed.

Writes inventory.json (numbers: times, gains, features) and, out of the repo, reference hits.

    python3 -I drums.py REF_DIR WORK_DIR
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy import signal

RATE = 44_100
EVENT_FLOOR = 0.08      # a hit must reach 8% of the drum's loudest firing (about -22 dB)
COFIRE = 0.7            # share of strong hits two components must share to be one drum
HIT_SECONDS = 1.6       # long enough for the longest 808 tail measured (about 1.5 s to -30 dB)


def bpm_of(name: str) -> int:
    return int(re.search(r"TRNDY_(\d+)_", name).group(1))


def peaks_of(h: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    pk, pr = signal.find_peaks(h, height=EVENT_FLOOR * h.max(), distance=3)
    return pk, pr["peak_heights"]


def cofire(a: np.ndarray, b: np.ndarray) -> float:
    """Share of a's strong hits (above a third of its max) with a strong b hit within a frame."""
    pa, ha = peaks_of(a)
    pb, hb = peaks_of(b)
    strong_a = pa[ha > a.max() / 3]
    strong_b = pb[hb > b.max() / 3]
    if len(strong_a) == 0 or len(strong_b) == 0:
        return 0.0
    hit = [np.min(np.abs(strong_b - p)) <= 1 for p in strong_a]
    return float(np.mean(hit))


def merge_groups(H: np.ndarray) -> list[list[int]]:
    K = len(H)
    parent = list(range(K))

    def root(i):
        while parent[i] != i:
            i = parent[i]
        return i
    for i in range(K):
        for j in range(i + 1, K):
            if min(cofire(H[i], H[j]), cofire(H[j], H[i])) >= COFIRE:
                parent[root(j)] = root(i)
    groups: dict[int, list[int]] = {}
    for i in range(K):
        groups.setdefault(root(i), []).append(i)
    return list(groups.values())


def refine_onset(y: np.ndarray, centre: int, search: int) -> int:
    """The steepest rise of a 1 ms envelope within +-search samples: where the hit starts."""
    a = max(0, centre - search)
    seg = np.abs(y[a:centre + search])
    if len(seg) < 64:
        return centre
    env = np.convolve(seg, np.ones(44) / 44, mode="same")
    rise = np.diff(np.log(env + 1e-6))
    return a + int(np.argmax(rise))


def features(hit: np.ndarray) -> dict:
    """Plain descriptors used to name the drum's role; the fit stage measures properly."""
    n = len(hit)
    spec = np.abs(np.fft.rfft(hit[:int(0.25 * RATE)] * np.hanning(min(n, int(0.25 * RATE))),
                              8192))
    f = np.fft.rfftfreq(8192, 1 / RATE)
    centroid = float((f * spec).sum() / (spec.sum() + 1e-12))
    env = np.sqrt(np.convolve(hit ** 2, np.ones(441) / 441, mode="same"))
    pk = int(np.argmax(env))
    below = np.nonzero(env[pk:] < env[pk] * 10 ** (-30 / 20))[0]
    decay = float(below[0] / RATE) if len(below) else n / RATE
    low = spec[(f > 25) & (f < 250)]
    flow = f[(f > 25) & (f < 250)]
    low_share = float((spec[f < 250] ** 2).sum() / ((spec ** 2).sum() + 1e-12))
    # tonality: how peaky the 0.03..0.25 s spectrum is (spectral flatness, inverted)
    body = np.abs(np.fft.rfft(hit[int(0.03 * RATE):int(0.25 * RATE)], 8192)) + 1e-9
    flatness = float(np.exp(np.mean(np.log(body))) / np.mean(body))
    return dict(centroid_hz=round(centroid), decay30_s=round(decay, 3),
                low_share=round(low_share, 3), low_peak_hz=round(float(flow[np.argmax(low)]), 1),
                flatness=round(flatness, 3))


def role_of(ft: dict) -> str:
    """A first guess at the drum's role, reviewed by hand in the inventory."""
    if ft["low_share"] > 0.6 and ft["decay30_s"] > 0.45:
        return "808"
    if ft["low_share"] > 0.5:
        return "kick"
    if ft["centroid_hz"] > 6000 and ft["decay30_s"] < 0.12:
        return "closed-hat"
    if ft["centroid_hz"] > 6000:
        return "open-hat"
    if ft["flatness"] < 0.08 and ft["low_share"] > 0.15:
        return "tom"
    if ft["flatness"] < 0.08:
        return "perc"
    return "snare"


def main(ref_dir: Path, work: Path) -> None:
    inv = {}
    (work / "hits").mkdir(exist_ok=True)
    for path in sorted(ref_dir.glob("*.wav")):
        name = path.stem
        key = str(bpm_of(name))
        z = np.load(work / f"{key}_nmfd.npz")
        H, W, hop = z["H"], z["W"], int(z["hop"])
        n = sf.info(path).frames
        groups = merge_groups(H)
        drums = []
        for gi, comps in enumerate(groups):
            drum_id = f"{key}-d{gi}"
            y = sum(sf.read(work / f"{key}_c{c}.wav")[0] for c in comps)
            h = sum(H[c] / H[c].max() for c in comps)
            pk, _ = peaks_of(h)
            # Each component's share of this drum, measured at the drum's strong hits, so the
            # merged template keeps the balance the parts had in the loop.
            near = lambda hk, p: hk[max(0, p - 1):p + 2].max()
            strong_pk = [p for p in pk if h[max(0, p - 1):p + 2].max() > h.max() / 3]
            scale = {c: float(np.median([near(H[c], p) for p in strong_pk])) for c in comps}
            template = sum(scale[c] * W[c] for c in comps)
            gains = np.array([sum(near(H[c], p) for c in comps) for p in pk])
            gains = gains / np.median([sum(near(H[c], p) for c in comps) for p in strong_pk])
            gains = gains / gains.max()
            times = [refine_onset(y, int(p * hop), int(0.015 * RATE)) for p in pk]
            # Reference hit: align the strong hits on their onsets and take the median sample
            # by sample after a per-hit polarity and gain match.
            strong = [t for t, g in zip(times, gains) if g > 0.5]
            L = int(HIT_SECONDS * RATE)
            pre = int(0.003 * RATE)
            segs = []
            all_t = np.array(sorted(times))
            for t in strong:
                idx = (np.arange(-pre, L) + t) % n  # the loop wraps
                seg = y[idx].copy()
                gaps = ((all_t - t) % n)
                gaps = gaps[gaps > int(0.01 * RATE)]
                stop = int(min(gaps.min() if len(gaps) else L, L)) - int(0.004 * RATE)
                fade = int(0.004 * RATE)
                seg[pre + stop:] = 0  # the drum's own next hit begins here: stop before it
                seg[pre + stop - fade:pre + stop] *= np.linspace(1, 0, fade)
                segs.append(seg)
            segs = np.array(segs)
            ref = segs[int(np.argmax(np.abs(segs[:, :int(0.05 * RATE)]).max(1)))]
            aligned = []
            for s in segs:  # align each hit to the loudest by cross-correlation within 2 ms
                lag_range = int(0.002 * RATE)
                cc = [np.dot(ref[pre:pre + 4410], np.roll(s, -d)[pre:pre + 4410])
                      for d in range(-lag_range, lag_range + 1)]
                d = int(np.argmax(np.abs(cc))) - lag_range
                s = np.roll(s, -d) * np.sign(cc[d + lag_range])
                g = np.sqrt(np.sum(ref[:4410] ** 2) / (np.sum(s[:4410] ** 2) + 1e-12))
                aligned.append(s * g)
            aligned = np.array(aligned)
            # The listening reference is the loudest single hit, real audio. The fit does not
            # take a sample-by-sample median: across hits that differ by a fraction of a
            # millisecond it cancels tonal sweeps, and the median of different noise bursts is
            # quieter than any one of them. fit.py takes medians of band envelopes instead.
            loud = [g for t, g in zip(times, gains) if g > 0.5]
            order = np.argsort(loud)[::-1][:12]
            takes = aligned[order]
            median_hit = takes[0]
            np.save(work / "hits" / f"{drum_id}_takes.npy", takes.astype(np.float32))
            # Instance spread: how different the reference's own repeats are, in dB per band.
            spread = instance_spread(aligned)
            ft = features(median_hit[pre:])
            sf.write(work / "hits" / f"{drum_id}.wav", median_hit.astype(np.float32), RATE,
                     subtype="FLOAT")
            np.save(work / "hits" / f"{drum_id}_template.npy", template)
            clean_s = float(np.median([np.count_nonzero(np.abs(s) > 0) for s in segs]) / RATE)
            drums.append(dict(id=drum_id, components=comps, role=role_of(ft), features=ft,
                              hits=len(times), strong_hits=len(strong),
                              clean_seconds=round(clean_s, 3),
                              instance_spread_db=spread,
                              events=[dict(t=round(t / RATE, 5), gain=round(float(g), 3))
                                      for t, g in sorted(zip(times, gains))]))
        inv[key] = dict(name=name, bpm=bpm_of(name), seconds=round(n / RATE, 5), drums=drums)
        print(f"== {key} {name}")
        for d in drums:
            print(f"  {d['id']:8s} {d['role']:10s} comps={d['components']} hits={d['hits']:3d} "
                  f"strong={d['strong_hits']:3d} spread={d['instance_spread_db']} {d['features']}")
    (work / "inventory.json").write_text(json.dumps(inv, indent=1))


def instance_spread(aligned: np.ndarray) -> float | None:
    """Median distance between the reference's own repeats, in dB over 24 bands of the first
    150 ms. Near zero means the loop re-triggers one sample; larger means a live player."""
    if len(aligned) < 2:
        return None
    edges = np.geomspace(40, 16_000, 25)
    f = np.fft.rfftfreq(8192, 1 / RATE)
    prof = []
    for s in aligned:
        sp = np.abs(np.fft.rfft(s[:int(0.15 * RATE)], 8192)) ** 2
        prof.append([10 * np.log10(sp[(f >= lo) & (f < hi)].sum() + 1e-12)
                     for lo, hi in zip(edges[:-1], edges[1:])])
    prof = np.array(prof)
    prof -= prof.mean(axis=1, keepdims=True)
    d = [np.mean(np.abs(prof[i] - prof[j])) for i in range(len(prof))
         for j in range(i + 1, len(prof))]
    return round(float(np.median(d)), 2)


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
