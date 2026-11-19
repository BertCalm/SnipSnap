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

sys.path.insert(0, str(Path(__file__).resolve().parent))  # run with -I: our own modules
import glidefit  # noqa: E402

RATE = 44_100
EVENT_FLOOR = 0.08      # a hit must reach 8% of the drum's loudest firing (about -22 dB)
COFIRE = 0.7            # share of strong hits two components must share to be one drum
HIT_SECONDS = 1.6       # long enough for the longest 808 tail measured (about 1.5 s to -30 dB)
REFIRE_S = 0.25         # a firing this soon after the drum's last hit ...
REFIRE_RISE_DB = 6.0    # ... is a new hit only if the drum's audio jumps this much at it


def attack_rise_db(env: np.ndarray, t: int, n: int) -> float:
    """How far the drum's own (separated) audio jumps at t: the loudest of the next 12 ms
    against the average of the 25..3 ms before, in dB. The loop wraps."""
    pre = env[np.arange(t - int(0.025 * RATE), t - int(0.003 * RATE)) % n].mean()
    post = env[np.arange(t, t + int(0.012 * RATE)) % n].max()
    return float(20 * np.log10((post + 1e-9) / (pre + 1e-9)))


ALIGN_WINDOW_S = 0.03   # firings of different drums this close may be one hit, split by NMFD


def mix_onsets(mono: np.ndarray, hop: int = 32) -> tuple[np.ndarray, np.ndarray]:
    """Fine onsets of the whole loop: positive spectral flux above 2 kHz, on a 0.7 ms hop. A new
    hit brings a fresh crack up there; a kick's body blooming about 15 ms after its click does
    not, and counted from 200 Hz up it read as a second hit, so the body was still placed late.
    Returns times in samples and flux."""
    f, t, Z = signal.stft(mono, RATE, nperseg=512, noverlap=512 - hop, boundary=None,
                          padded=False)
    lm = np.log1p(100 * np.abs(Z[f >= 2000]))
    flux = np.maximum(0, np.diff(lm, axis=1)).sum(0)
    flux = flux - signal.medfilt(flux, 31)
    pk, pr = signal.find_peaks(flux, height=0, distance=int(0.004 * RATE / hop))
    return ((t[1:][pk] - 256 / RATE) * RATE).astype(int), pr["peak_heights"]


def align_hits(drums: list[dict], mono: np.ndarray) -> int:
    """One hit split into several NMFD components is timed per component, on that component's
    own audio, and a low component builds slowly, so its start reads 10 to 20 ms late. Played
    back, one kick became two or three attacks 15 to 19 ms apart: the doubling the owner heard
    on the knocker groove (2026-11-19). The loop itself shows where a hit starts. For firings
    of different drums within ALIGN_WINDOW_S of each other, the loop's onsets in that window
    are found (those within 30% of the window's strongest): one onset, and every firing snaps
    to it; several (a real flam, a hat played off the kick), and each firing goes to the latest
    onset at or before it. Where the loop shows no clear attack in the window (none as strong
    as its typical hits: a soft hit under other sounds), the firings become one hit at the
    earliest one's time. A drum's own successive firings are never merged. Returns how many
    firings moved more than 2 ms."""
    on_t, on_h = mix_onsets(mono)
    clear = float(np.percentile(on_h, 75))   # an attack as strong as the loop's typical hits
    # a low part's start reads late (it builds slowly), never early: it takes the earliest
    # clear attack in its window, not the latest before its reading
    low = {d["id"]: d["features"]["low_share"] > 0.5 for d in drums}
    ev = sorted(((e["t"], d["id"], e) for d in drums for e in d["events"]), key=lambda x: x[0])
    moved, i = 0, 0
    while i < len(ev):
        j = i + 1
        while j < len(ev) and ev[j][0] - ev[i][0] <= ALIGN_WINDOW_S:
            j += 1
        group = ev[i:j]
        if len({g[1] for g in group}) > 1:
            a = int(group[0][0] * RATE) - int(0.008 * RATE)
            b = int(group[-1][0] * RATE) + int(0.003 * RATE)
            sel = (on_t >= a) & (on_t <= b)
            if not sel.any() or on_h[sel].max() < clear:
                # no clear attack in the loop here (a soft hit under other sounds): nothing to
                # time the parts from, so they are one hit, at the earliest part's time
                t0 = group[0][0]
                for t, _, e in group:
                    if abs(t - t0) > 0.002:
                        moved += 1
                    e["t"] = round(t0, 5)
            else:
                cand_t, cand_h = on_t[sel], on_h[sel]
                cand_t = cand_t[cand_h >= 0.3 * cand_h.max()]
                for t, did, e in group:
                    ts = int(t * RATE)
                    before = cand_t[cand_t <= ts + int(0.003 * RATE)]
                    if low[did] or not len(before):
                        new = int(cand_t[0])
                    else:
                        new = int(before[-1])
                    if abs(new - ts) > int(0.002 * RATE):
                        moved += 1
                    e["t"] = round(new / RATE, 5)
        i = j
    for d in drums:
        d["events"].sort(key=lambda e: e["t"])
    return moved


def drop_refires(times, gains, y, n):
    """NMFD explains a drum's tail by firing its template again 40 to 90 ms after the hit.
    Played back, each firing is a fresh strike, so a re-fire sounds like slapback echo (heard
    by the owner on the grooves, 2026-11-19). A real hit makes the drum's audio jump; a re-fire
    lands on a sound already fading. So a firing within REFIRE_S of the drum's last kept hit
    stays only if the audio rises REFIRE_RISE_DB at it. Measured: kept events rise 8 to 53 dB
    (median per drum), dropped ones 1.4 to 4.4 dB, so the 16th-note hats and flams stay."""
    w = int(0.001 * RATE)
    env = np.sqrt(np.convolve(y * y, np.ones(w) / w, mode="same"))
    order = np.argsort(times)
    kept_t, keep = [], np.zeros(len(times), bool)
    for i in order:
        t = times[i]
        recent = any(0 < (t - k) % n < int(REFIRE_S * RATE) for k in kept_t)
        if not recent or attack_rise_db(env, t, n) >= REFIRE_RISE_DB:
            keep[i] = True
            kept_t.append(t)
    return [t for t, k in zip(times, keep) if k], np.asarray(gains)[keep]


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


def build_drum(drum_id, comps, y, times, gains, n_fired, template, n, work, stops=None):
    """Cut a drum's strong hits from its audio, align them, save the takes, the loudest hit
    and the template for fit.py, and describe the drum. `stops` are the times its hits are cut
    at (by default its own hits)."""
    # Reference hit: align the strong hits on their onsets and take the median sample
    # by sample after a per-hit polarity and gain match.
    strong = [t for t, g in zip(times, gains) if g > 0.5]
    L = int(HIT_SECONDS * RATE)
    pre = int(0.003 * RATE)
    segs = []
    all_t = np.array(sorted(stops if stops is not None else times))
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
    return dict(id=drum_id, components=comps, role=role_of(ft), features=ft,
                hits=len(times), strong_hits=len(strong),
                refires_dropped=n_fired - len(times),
                clean_seconds=round(clean_s, 3),
                instance_spread_db=spread,
                events=[dict(t=round(t / RATE, 5), gain=round(float(g), 3))
                        for t, g in sorted(zip(times, gains))])


KICK_FIRST = {"113"}        # loops whose low drums are rebuilt whole (kick first), by ear
LOW_SHARE = 0.5             # a part with more energy than this under 150 Hz is a low part
KICK_LOUD_DB = 6.0          # hits this close to the loudest define the kick
KICK_SWEEP = 2.5            # a kick sweeps down by more than this ratio (click and punch)
SAME_NOTE_SEMITONES = 2.0   # settled within this of the kick (or of a group) is the same sound
MIN_NOTE_HITS = 3           # a group with fewer hits joins its nearest group


def hit_glide(y: np.ndarray, t: int, stop: int, n: int):
    """The pitch glide of one low hit, fitted on the low track up to the next low hit (40 to
    300 ms): start, settle and how much of the hit it explains."""
    span = float(np.clip(stop / RATE - 0.004, 0.04, 0.3))
    seg = y[np.arange(t, t + int(span * RATE) + 10) % n]
    return glidefit.fit(seg, RATE, 75.0, span, 1500.0)


def kick_first(key, drums, work, n):
    """Rebuild a loop's low drums whole. NMFD has a few fixed shapes per loop, each one pitch
    and one decay, so a kick that varies from hit to hit is spread over several shapes, each
    hit a different blend of them: in the knocker groove four parts that fire together on only
    22 to 88% of each other's hits, so the 70% merge rule kept them apart, and each was fitted
    as its own drum with its own pitch drop and attack. Stacked, they played as four kicks at
    once, heard as a fragmented kick (2026-11-19).

    Here every low part's audio is summed into one low track; the parts' hits (already timed
    from the loop by align_hits) are merged into one hit wherever they fall within
    ALIGN_WINDOW_S, and a hit stays only if the low track jumps at it; each hit's loudness is
    read off the low track; each hit's pitch glide is fitted. The loud hits define the kick
    (their sweep and settle); a quieter hit with the same shape is the kick played softer; the
    remaining low hits (a following bass knock, a sub drop) group by where they settle. Each
    group becomes one drum, cut and saved like any other, k0 being the kick. Returns the new
    list of drums."""
    low = [d for d in drums if d["features"]["low_share"] > LOW_SHARE]
    if len(low) < 2:
        return drums
    comps = sorted(c for d in low for c in d["components"])
    y = sum(sf.read(work / f"{key}_c{c}.wav")[0] for c in comps)
    template = sum(np.load(work / "hits" / f"{d['id']}_template.npy") for d in low)
    raw = sorted(int(round(e["t"] * RATE)) for d in low for e in d["events"])
    times = []
    for t in raw:
        if not times or t - times[-1] > int(ALIGN_WINDOW_S * RATE):
            times.append(t)
    w = int(0.001 * RATE)
    env = np.sqrt(np.convolve(y * y, np.ones(w) / w, mode="same"))
    gains = np.array([env[np.arange(t, t + int(0.03 * RATE)) % n].max() for t in times])
    gains = gains / gains.max()
    n_fired = len(times)
    times, gains = drop_refires(times, gains, y, n)
    keep = [i for i, t in enumerate(times) if attack_rise_db(env, t, n) >= REFIRE_RISE_DB]
    times = [times[i] for i in keep]
    gains = np.asarray(gains)[keep]
    gaps = [((times[(i + 1) % len(times)] - t) % n) or n for i, t in enumerate(times)]
    gl = [hit_glide(y, t, g, n) for t, g in zip(times, gaps)]
    f1 = np.array([g["f1"] if g else np.nan for g in gl])
    sweep = np.array([g["f0"] / g["f1"] if g else 1.0 for g in gl])
    level = 20 * np.log10(np.maximum(gains, 1e-6))
    # The kick is what the loud hits are; a quieter hit with the same sweep and settle is the
    # kick played softer. A first reading by settled pitch alone split one knocker kick into
    # four, since the next hit or another drum's low end lands within 70 to 170 ms.
    loud = level >= -KICK_LOUD_DB
    kick_f1 = float(np.nanmedian(f1[loud]))
    near_kick = np.abs(12 * np.log2(f1 / kick_f1)) <= SAME_NOTE_SEMITONES
    is_kick = loud | ((sweep >= KICK_SWEEP) & near_kick)
    groups = [list(np.nonzero(is_kick)[0])]
    # The other low hits (a following bass knock, a sub drop) group by where they settle
    others = sorted(np.nonzero(~is_kick)[0], key=lambda i: np.nan_to_num(f1[i], nan=0.0))
    cur = []
    for i in others:
        if cur and abs(12 * np.log2(f1[i] / np.nanmedian(f1[cur]))) > SAME_NOTE_SEMITONES:
            groups.append(cur)
            cur = []
        cur.append(i)
    if cur:
        groups.append(cur)
    while len(groups) > 1 and min(len(g) for g in groups[1:]) < MIN_NOTE_HITS:
        k = 1 + min(range(len(groups) - 1), key=lambda j: len(groups[j + 1]))
        small = groups.pop(k)
        centre = np.nanmedian(f1[small])
        near = min(range(len(groups)), key=lambda j: abs(np.log2(np.nanmedian(f1[groups[j]]) / centre)))
        groups[near] += small
    pitch = f1
    rest = [d for d in drums if d not in low]
    new = []
    for gi, g in enumerate(groups):   # k0 is the kick, then the other low sounds
        g = sorted(g, key=lambda i: times[i])
        gt = [times[i] for i in g]
        gg = np.array([gains[i] for i in g])
        d = build_drum(f"{key}-k{gi}", comps, y, gt, gg / gg.max(), n_fired, template, n,
                       work, stops=times)
        d["kick_first"] = dict(parts=[x["id"] for x in low], kick=gi == 0,
                               settled_hz=round(float(np.nanmedian(pitch[g])), 1),
                               sweep=round(float(np.median(sweep[g])), 2))
        new.append(d)
    return rest + new


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
            n_fired = len(times)
            times, gains = drop_refires(times, gains, y, n)
            drums.append(build_drum(drum_id, comps, y, times, gains, n_fired, template, n,
                                    work))
        mono = sf.read(path, always_2d=True)[0].mean(axis=1)
        moved = align_hits(drums, mono)
        if key in KICK_FIRST:
            drums = kick_first(key, drums, work, n)
        inv[key] = dict(name=name, bpm=bpm_of(name), seconds=round(n / RATE, 5), drums=drums,
                        aligned_firings=moved)
        print(f"== {key} {name}  (firings moved onto the loop's own onsets: {moved})")
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
