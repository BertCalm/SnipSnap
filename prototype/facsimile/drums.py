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


EVIDENCE_LOW_DB = 15.0   # a low hit: the loop's level under 150 Hz jumps this much (30 ms)
EVIDENCE_HIGH_DB = 7.0   # any other hit: the loop's 150 Hz to 2 kHz or >2 kHz level, 12 ms
EVIDENCE_SEARCH_S = (-0.010, 0.005)   # the attack may read this far before or after the event
SNAP_S = 0.1             # a strong firing with no attack goes to a real attack this close
SNAP_GAIN = 0.3          # ... if it is this strong; a weaker one is dropped
SAME_HIT_S = 0.05        # one drum's firings this close are one hit (the louder one)
RISE_HOP = 44            # samples per step of the rise curves (about 1 ms)


def loop_rises(mono: np.ndarray) -> dict[str, np.ndarray]:
    """How much the loop itself jumps at each millisecond, per band, in dB: the loudest of
    what follows against the mean power of what precedes. The low band is read over 30 ms,
    since a sub swells over 20 to 40 ms where a click takes 2; the others over 12 ms. The
    loop wraps. Returned on a RISE_HOP grid: index a time as t * RATE / RISE_HOP (44 samples
    is not 1 ms; read as 1 ms the index drifted 13 ms by the loop's sixth second)."""
    blk = RISE_HOP
    m = len(mono) // blk
    out = {}
    for name, sos, span in (
            ("low", signal.butter(4, 150, "low", fs=RATE, output="sos"), 0.030),
            ("mid", signal.butter(4, [150, 2000], "band", fs=RATE, output="sos"), 0.012),
            ("high", signal.butter(4, 2000, "high", fs=RATE, output="sos"), 0.012)):
        y = signal.sosfiltfilt(sos, mono)[:m * blk]
        p = (y.reshape(m, blk) ** 2).mean(1) + 1e-12
        if name == "low":   # 5 ms power, so the level does not ride each 70 Hz cycle
            w = int(round(0.005 * RATE / blk))
            p = np.convolve(np.concatenate([p[-(w - 1):], p]), np.ones(w) / w, mode="valid")
        k = int(round(span * RATE / blk))
        gap = int(round((0.005 if name == "low" else 0.002) * RATE / blk))
        idx = np.arange(m)
        after = np.max([p[(idx + j) % m] for j in range(k)], axis=0)
        before = np.mean([p[(idx - j) % m] for j in range(gap, k)], axis=0)
        out[name] = 10 * np.log10(after / before)
    return out


def attack_evidence(rises: dict, t: float, low: bool) -> bool:
    """Whether the loop shows an attack at t (seconds): for a low drum, a 15 dB jump under
    150 Hz; for any other, a 7 dB jump in the mids or highs."""
    m = len(rises["low"])
    a, b = (int(round((t + s) * RATE / RISE_HOP)) for s in EVIDENCE_SEARCH_S)
    idx = np.arange(a, b + 1) % m
    if low:
        return bool(rises["low"][idx].max() >= EVIDENCE_LOW_DB)
    return bool(max(rises["mid"][idx].max(), rises["high"][idx].max()) >= EVIDENCE_HIGH_DB)


def confirm_attacks(drums: list[dict], rises: dict, attack_time: bool = False) -> tuple[int, int]:
    """Keep only the hits the loop itself shows. NMFD fires a part wherever its shape explains
    some energy, including inside another drum's tail and a few tens of ms off the real hit:
    in the knocker groove a snare layer sat 57 to 85 ms from three backbeats (heard as flams),
    and parts fired on tails where the loop rises 0 to 5 dB in every band. Played back each is
    a fresh strike, heard as a fragmented groove (2026-11-19). Real hits jump 12 to 65 dB.

    For the drums that are not low (low ones are checked in kick_first): a firing the loop
    shows an attack at stays; a strong one (gain >= SNAP_GAIN) without one moves to the
    nearest real attack within SNAP_S where the drum has no hit yet (the misplaced layer);
    the rest are dropped. A drum's own firings within SAME_HIT_S are then one hit, the
    louder (a snare layer fired twice 13 and 26 ms apart). Returns (moved, dropped)."""
    m = len(rises["low"])
    up = np.maximum(rises["mid"], rises["high"])
    pk, _ = signal.find_peaks(np.concatenate([up, up[:1]]), height=EVIDENCE_HIGH_DB)
    attacks = pk[pk < m] * RISE_HOP / RATE
    moved = dropped = 0
    for d in drums:
        if d["features"]["low_share"] > LOW_SHARE:
            continue
        keep = []
        for e in d["events"]:
            if attack_evidence(rises, e["t"], low=False):
                keep.append(e)
                continue
            if any(0 < abs(k["t"] - e["t"]) <= SAME_HIT_S for k in d["events"]):
                # part of the drum's own hit next to it (the merge below keeps the louder):
                # sent to the nearest free attack instead, a snare's late layer landed 38 ms
                # before the snare, a flam turned round (loop 90, 2026-11-20)
                keep.append(e)
                continue
            if e["gain"] >= SNAP_GAIN:
                taken = [k["t"] for k in d["events"]]
                cand = [a for a in attacks if abs(a - e["t"]) <= SNAP_S
                        and all(abs(a - k) > ALIGN_WINDOW_S for k in taken)]
                if cand:
                    e["t"] = round(float(min(cand, key=lambda a: abs(a - e["t"]))), 5)
                    keep.append(e)
                    moved += 1
                    continue
            dropped += 1
        keep = sorted(keep, key=lambda e: e["t"])
        one = []
        for e in keep:
            if one and e["t"] - one[-1]["t"] <= SAME_HIT_S:
                dropped += 1
                louder, softer = (e, one[-1]) if e["gain"] > one[-1]["gain"] else (one[-1], e)
                # the louder firing's strength, at the time the loop shows the attack: the
                # Fusion snare's layer fired softly on the crack and loudly 36 to 48 ms later
                # in its body, where the loop shows no attack, and kept there it played as a
                # flam (2026-11-20)
                if (attack_time and not attack_evidence(rises, louder["t"], low=False)
                        and attack_evidence(rises, softer["t"], low=False)):
                    louder = dict(louder, t=softer["t"])
                one[-1] = louder
                continue
            one.append(e)
        d["events"] = one
    return moved, dropped


TIGHTEN = {"90", "163", "102", "103", "154"}        # loops whose hits are set to the groove's own average timing (by ear)
SLOT_S = 0.025          # a hit this close to a common position in the beat goes to it
SLOT_MIN_HITS = 4       # ... a position at least this many hits share


def tighten(drums: list[dict], beat_s: float, seconds: float) -> int:
    # beat_s is the cycle the places repeat in: a bar where the loop is whole bars (2026-11-20:
    # per beat, the owner heard the World groove as still stilted; tightened per beat its
    # timing against the reference spread 20 ms where untightened it spread 9)
    """Set every hit to its groove's average timing. Replicating each hit's own measured time
    copied the analysis's scatter too (a few ms between layers of one hit, more between
    repeats); the owner heard the result as stuttery and stilted and asked for the grooves to
    be tightened (2026-11-20). The groove's feel is kept: each hit's place within its beat is
    grouped with the hits at the same place (a shuffle's swung off-beat lands about 55 ms after
    the straight 16th, every time), and each group's median is that place's time. A hit more
    than SLOT_S from any place SLOT_MIN_HITS hits share (a real ghost or drag) keeps its own
    time. Every layer of one hit then lands on the same sample. Returns how many moved > 1 ms."""
    ev = [e for d in drums for e in d["events"]]
    phase = np.array([e["t"] % beat_s for e in ev])
    order = np.sort(phase)
    # group the phases (around the circle: a hit just before the beat belongs with the beat)
    gaps = np.diff(np.concatenate([order, [order[0] + beat_s]]))
    cut = int(np.argmax(gaps)) + 1                      # start after the widest empty stretch
    ring = np.concatenate([order[cut:], order[:cut] + beat_s])
    groups, cur = [], [ring[0]]
    for v in ring[1:]:
        if v - cur[-1] > SLOT_S / 2:
            groups.append(cur); cur = []
        cur.append(v)
    groups.append(cur)
    places = [float(np.median(g)) % beat_s for g in groups if len(g) >= SLOT_MIN_HITS]
    moved = 0
    for e in ev:
        beat_no = np.floor(e["t"] / beat_s)
        best = None
        for c in places:
            for k in (-1, 0, 1):                         # the place may be in the next beat
                t = (beat_no + k) * beat_s + c
                if abs(t - e["t"]) <= SLOT_S and (best is None or abs(t - e["t"]) < abs(best - e["t"])):
                    best = t
        if best is not None:
            best = round(float(best % seconds), 5)
            if abs(best - e["t"]) > 0.001:
                moved += 1
            e["t"] = best
    for d in drums:
        d["events"].sort(key=lambda e: e["t"])
    return moved


SPLIT_DECAY = {"163"}    # loops whose parts may hold two instruments told apart by decay
SPLIT_GAP_DB = 8.0       # the two decay groups' centres at least this far apart
SPLIT_MIN_HITS = 4       # ... each with at least this many hits


def hit_falls(y: np.ndarray, times: list[int], n: int) -> list[float | None]:
    """How far each hit falls: its peak in the first 15 ms against its mean level 25 to 50 ms
    in (smooth Hilbert power, 2 ms), in dB. None where the drum's next hit comes within 30 ms."""
    e = np.abs(signal.hilbert(y)) ** 2
    k = int(0.002 * RATE)
    env = 10 * np.log10(np.convolve(e, np.ones(k) / k, "same") + 1e-14)
    out = []
    for i, t in enumerate(times):
        gap = ((times[(i + 1) % len(times)] - t) % n) or n
        w = min(int(0.05 * RATE), gap - int(0.005 * RATE))
        if w < int(0.03 * RATE):
            out.append(None)
            continue
        pk = env[np.arange(t, t + int(0.015 * RATE)) % n].max()
        out.append(float(pk - env[np.arange(t + int(0.025 * RATE), t + w) % n].mean()))
    return out


def split_by_decay(key, drums, work, n):
    """One NMFD part can hold two instruments that play at different moments: in the World
    groove a crisp hi-hat and a backbeat tambourine shared one part. The part's model was fitted
    to its loudest hits, the tambourine's, so every hat hit played a tambourine (heard by the
    owner, 2026-11-20). Their decays tell them apart: the hat falls 10 to 23 dB within 50 ms,
    the tambourine 0 to 3. For each part that is not low, each hit's fall (hit_falls) is
    split in two by 2-means; if the groups' centres stand SPLIT_GAP_DB apart with
    SPLIT_MIN_HITS hits each, the part becomes two drums: the fast-falling one keeps the id,
    the ringing one takes the next free id. Hits too close to the next to measure stay with the
    fast-falling one. Returns the new list of drums."""
    out = []
    next_id = 1 + max(int(d["id"].split("-d")[1]) for d in drums if "-d" in d["id"])
    for d in drums:
        if d["features"]["low_share"] > LOW_SHARE:
            out.append(d)
            continue
        y = sum(sf.read(work / f"{key}_c{c}.wav")[0] for c in d["components"])
        times = [int(round(e["t"] * RATE)) for e in d["events"]]
        gains = np.array([e["gain"] for e in d["events"]])
        falls = hit_falls(y, times, n)
        f = np.array([v for v in falls if v is not None])
        if len(f) < 2 * SPLIT_MIN_HITS:
            out.append(d)
            continue
        lo, hi = np.percentile(f, 25), np.percentile(f, 75)
        for _ in range(20):                              # 2-means in dB
            cut = (lo + hi) / 2
            lo, hi = f[f <= cut].mean(), f[f > cut].mean()
        cut = (lo + hi) / 2
        ring = [v is not None and v <= cut for v in falls]
        if hi - lo < SPLIT_GAP_DB or min(sum(ring), len(ring) - sum(ring)) < SPLIT_MIN_HITS:
            out.append(d)
            continue
        template = np.load(work / "hits" / f"{d['id']}_template.npy")
        n_fired = d["hits"] + d["refires_dropped"]
        for ids, sel in ((d["id"], [not r for r in ring]), (f"{key}-d{next_id}", ring)):
            t_sel = [t for t, k in zip(times, sel) if k]
            g_sel = gains[np.array(sel)]
            nd = build_drum(ids, d["components"], y, t_sel, g_sel / g_sel.max(), n_fired,
                            template, n, work, stops=times)
            nd["split_by_decay"] = dict(part=d["id"], ringing=ids != d["id"],
                                        fall_db=round(float(lo if ids != d["id"] else hi), 1))
            out.append(nd)
        next_id += 1
    return out


DOUBLES = {"163", "103"}        # loops whose low parts' tail re-firings are dropped (by ear)
TAIL_S = 0.12            # a low firing this soon after a real hit ...
OWN_LOW_AFTER_S = 0.05   # ... is its tail, unless it cracks itself, or jumps under 150 Hz this
                         # long after it


def drop_doubles(drums: list[dict], rises: dict) -> int:
    """A low part re-fired on the tail of the hit before it: in the World groove all seven
    low parts fire on the downbeat and two fire again 33 ms later with no attack of their own,
    a doubled hit on every beat (heard by the owner, 2026-11-20). Its bass plays legato, a
    note starting while the last still rings, so the loop's low band cannot tell a new note
    from a tail (its jumps spread evenly from 0 to 45 dB); the transient can. A real hit cracks
    in the mids or highs (7 dB); a re-fire does not. So the loop's hits are the firings with an
    attack in any band; a low firing within TAIL_S after one, with no crack of its own and no
    low jump of its own beyond OWN_LOW_AFTER_S, is dropped. Firings further from any hit stay
    (a legato 808 note). Returns how many were dropped."""
    m = len(rises["low"])
    up = np.maximum(rises["mid"], rises["high"])
    def at(curve, t):
        a, b = (int(round((t + s) * RATE / RISE_HOP)) for s in EVIDENCE_SEARCH_S)
        return curve[np.arange(a, b + 1) % m].max()
    seconds = m * RISE_HOP / RATE
    hits = sorted({e["t"] for d in drums for e in d["events"]
                   if at(up, e["t"]) >= EVIDENCE_HIGH_DB or at(rises["low"], e["t"]) >= EVIDENCE_LOW_DB})
    dropped = 0
    for d in drums:
        if d["features"]["low_share"] <= LOW_SHARE:
            continue
        keep = []
        for e in d["events"]:
            since = [(e["t"] - h) % seconds for h in hits]
            since = min([x for x in since if 0.004 < x <= TAIL_S], default=None)
            cracks = at(up, e["t"]) >= EVIDENCE_HIGH_DB
            own_low = since is not None and since > OWN_LOW_AFTER_S and \
                at(rises["low"], e["t"]) >= EVIDENCE_LOW_DB
            if since is not None and not cracks and not own_low:
                dropped += 1
                continue
            keep.append(e)
        # one part's firings within SAME_HIT_S are one hit, the louder
        one = []
        for e in sorted(keep, key=lambda e: e["t"]):
            if one and e["t"] - one[-1]["t"] <= SAME_HIT_S:
                dropped += 1
                if e["gain"] > one[-1]["gain"]:
                    one[-1] = e
                continue
            one.append(e)
        d["events"] = one
    return dropped


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


KICK_FIRST = {"113", "90"}
# 106, 127 and 134 measured further from their references with these fixes (band distance
# 8.22 to 8.88, 6.60 to 7.76, 6.39 to 7.40; tightening alone 8.57, 7.58, 6.91): their dropped
# firings carried real sound (soft notes under other hits, drum bodies), and the owner heard no
# fault in them, so they stay as they were (2026-11-20).
CONFIRM = {"102"}   # loops whose other drums keep only hits the loop shows,
                                         # without the kick rebuild (2026-11-20)        # loops whose low drums are rebuilt whole (kick first), by ear
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


def kick_first(key, drums, work, n, rises):
    """Rebuild a loop's low drums whole. NMFD has a few fixed shapes per loop, each one pitch
    and one decay, so a kick that varies from hit to hit is spread over several shapes, each
    hit a different blend of them: in the knocker groove four parts that fire together on only
    22 to 88% of each other's hits, so the 70% merge rule kept them apart, and each was fitted
    as its own drum with its own pitch drop and attack. Stacked, they played as four kicks at
    once, heard as a fragmented kick (2026-11-19).

    Here every low part's audio is summed into one low track; the parts' hits (already timed
    from the loop by align_hits) are merged into one hit wherever they fall within
    ALIGN_WINDOW_S, and a hit stays only if the low track jumps at it; each hit's loudness is
    read off the low track (confirmed against the loop's own low band, see attack_evidence);
    each hit's pitch glide is fitted. The loud hits define the kick
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
    # a low hit is real only where the loop itself jumps under 150 Hz: the knocker kick's
    # tail dips about 70 ms after the click and swells back 10 dB (it beats, 73 Hz against
    # 108), which the separated low track read as a new hit; cutting the kick there and
    # striking again played every kick as two (2026-11-19)
    keep = [i for i, t in enumerate(times) if attack_evidence(rises, t / RATE, low=True)]
    # two low hits within SAME_HIT_S are one, the louder: the 30 ms window lets a firing just
    # ahead of a kick borrow the kick's jump (5.019 against the kick at 5.053)
    one = []
    for i in keep:
        if one and times[i] - times[one[-1]] <= int(SAME_HIT_S * RATE):
            if gains[i] > gains[one[-1]]:
                one[-1] = i
            continue
        one.append(i)
    times = [times[i] for i in one]
    gains = np.asarray(gains)[one]
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


def rebuild(key, d, work, n):
    """Cut a drum again from its own audio at its (confirmed) event times."""
    y = sum(sf.read(work / f"{key}_c{c}.wav")[0] for c in d["components"])
    template = np.load(work / "hits" / f"{d['id']}_template.npy")
    times = [int(round(e["t"] * RATE)) for e in d["events"]]
    gains = np.array([e["gain"] for e in d["events"]])
    return build_drum(d["id"], d["components"], y, times, gains / gains.max(),
                      d["hits"] + d["refires_dropped"], template, n, work)


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
        confirmed = None
        if key in KICK_FIRST or key in CONFIRM:
            rises = loop_rises(mono)
            # loops approved by ear before this rule keep their merges as heard
            confirmed = confirm_attacks(drums, rises, attack_time=key in CONFIRM)
            # recut the takes of the drums whose hits moved or went: a phantom hit had cut
            # the drum's previous strong hit short
            drums = [rebuild(key, d, work, n) if d["features"]["low_share"] <= LOW_SHARE
                     else d for d in drums]
        if key in KICK_FIRST:
            drums = kick_first(key, drums, work, n, rises)
        doubles = None
        if key in DOUBLES:
            doubles = drop_doubles(drums, loop_rises(mono))
            # recut the takes of the low parts: a re-fire had cut each hit's take short
            drums = [rebuild(key, d, work, n) if d["features"]["low_share"] > LOW_SHARE
                     else d for d in drums]
        if key in SPLIT_DECAY:
            drums = split_by_decay(key, drums, work, n)
        tightened = None
        if key in TIGHTEN:
            beat = 60.0 / bpm_of(name)
            bars = n / RATE / (4 * beat)
            # per place in the bar when the loop is whole bars: averaged per place in the beat,
            # every beat was made the same and the groove's own push and pull went (the World
            # loop's beat 4 lands about 15 ms ahead of the others), heard as stilted
            cycle = 4 * beat if abs(bars - round(bars)) < 0.01 else beat
            tightened = tighten(drums, cycle, n / RATE)
        inv[key] = dict(name=name, bpm=bpm_of(name), seconds=round(n / RATE, 5), drums=drums,
                        aligned_firings=moved)
        if confirmed:
            inv[key]["confirmed_attacks"] = dict(moved=confirmed[0], dropped=confirmed[1])
        if doubles is not None:
            inv[key]["doubles_dropped"] = doubles
        if tightened is not None:
            inv[key]["tightened"] = tightened
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
