"""ETCH round 1: a step-line bass voice, a drive blend, and a tempo delay into a reverb.

Python harness, not Kotlin. Nothing here has been heard by its author; every constant
marked "shape" is a starting value for a listening pass, not a measurement.

The chain, in the order a pedalboard would put it:

    16-step line ─► VOICE (4x) ─► BITE (4x) ─► band-limit + decimate ─► SPACE (1x) ─► finish
                    osc, ladder,   clean/drive                          delay ─► reverb
                    accent, slide  blend

Why each stage sits where it does:
  * The voice runs at 4x (176.4 kHz) like every SnipSnap engine, because the ladder's
    resonant peak and the oscillator edges would alias at 44.1 kHz.
  * BITE runs at 4x too, before decimation, because a clipper makes new harmonics and they
    have to be filtered off before the rate drops (the VALVE and FORGE rule).
  * SPACE runs at 1x after decimation. Delay and reverb are linear (apart from a gentle
    bound in the feedback loop), so oversampling them buys nothing.
  * Reverb follows delay, as in the reference pedal's manual ("Reverb ... follows delay in
    the signal path"), so the repeats are smeared by the room, not the other way round.

Run:  python3 etch_proto.py OUT_DIR
"""
from __future__ import annotations

import math
import os
import sys
from dataclasses import dataclass

import numpy as np
from numba import njit
from scipy import signal

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", ".claude", "skills", "luthier", "scripts"))
import snipkit as sk  # noqa: E402
import probes as pr   # noqa: E402

RATE = sk.RATE
FS = sk.INTERNAL  # 176_400

# ----------------------------------------------------------------------------- macros

# Six macros, the house limit, TUNE included. ACCENT is not a macro: it is velocity.
DEFAULTS = {"TUNE": 0.5, "CUTOFF": 0.3, "PEAK": 0.7, "SWEEP": 0.5, "BITE": 0.45, "SPACE": 0.4}
VOICES = ("SAW", "SQUARE")
ROOT_MIDI = 36  # C2 at TUNE 0.5
CUT_LO, CUT_HI = 110.0, 1200.0  # CUTOFF's base range, Hz. "shape"
FB_HP_HZ = 140.0  # high-pass in the resonance feedback, Hz. "shape"
FC_MAX = 3500.0  # soft ceiling on the opened cutoff, Hz: above it the peak stops squelching and only buzzes. "shape"
TUNE_SEMITONES = 24


def lin(x, lo, hi):
    return lo + (hi - lo) * x


def expmap(x, lo, hi):
    return lo * (hi / lo) ** x


# ----------------------------------------------------------------------------- patterns

@dataclass
class Step:
    """One sequencer step. `note` is semitones above the line's root, None for a rest.
    `vel` is the step's velocity: 1.0 is a full accent, 0.6 an ordinary step.
    `slide` means: glide INTO the next step and keep the gate open across the boundary."""
    note: int | None
    vel: float = 0.6
    slide: bool = False


def parse(line: str) -> list[Step]:
    """Compact pattern text: tokens like `0`, `12a`, `7s`, `13as`, `-` (rest).
    a = accent (vel 1.0), s = slide into the next step."""
    out = []
    for tok in line.split():
        if tok == "-":
            out.append(Step(None))
            continue
        acc = "a" in tok
        sl = "s" in tok
        n = int(tok.rstrip("as"))
        out.append(Step(n, 1.0 if acc else 0.6, sl))
    return out


# Two original lines written for this audition. They follow the habits of the genre
# (root pedal, octave jumps, a semitone push, accents on off-beats) and copy neither
# reference clip. Roots and tempi come from the reference measurements:
#   reference A measured ~112 BPM, low notes F1 and C2; reference B ~130 BPM, C2 with C3/C#3.
LINE_A = dict(name="LINE A", bpm=112.0, root=29, voice="SQUARE",   # F1, darker, sparse
              steps=parse("0a - 0 12s 7 - 0a 3 - 0 10s 12a - 7 0 5"),
              macros={"CUTOFF": 0.32, "PEAK": 0.62, "SWEEP": 0.45, "BITE": 0.4, "SPACE": 0.55})
LINE_B = dict(name="LINE B", bpm=130.0, root=36, voice="SAW",      # C2, brighter, busy
              steps=parse("0a 0 12s 13 0 0a 12 - 0 15s 12a 0 6 12s 13a 0"),
              macros={"CUTOFF": 0.3, "PEAK": 0.8, "SWEEP": 0.6, "BITE": 0.5, "SPACE": 0.32})


# ----------------------------------------------------------------------------- voice kernel

@njit(cache=True)
def _voice_kernel(target_midi, gate, trig, slide_on, vel, cut_oct, square, peak, sweep,
                  phase0, drift_cut, drift_dec, fs, fb_hp_hz, fc_max):
    """Per-sample voice at the internal rate.

    target_midi : pitch the oscillator heads for (MIDI, float)
    gate        : 1 while the note is held
    trig        : 1 on the sample a non-slid note starts (retriggers both envelopes)
    slide_on    : 1 while the pitch should glide (RC portamento) rather than jump
    vel         : velocity of the current note, 0..1; drives the accent
    cut_oct     : base cutoff as octaves above 20 Hz, per sample (so a knob ride is possible)
    """
    n = target_midi.shape[0]
    out = np.zeros(n)
    # Slide: a one-pole RC on the pitch CV. 100 k and 220 n is a 22 ms time constant
    # (synth-diy 2012 thread on the original circuit); ~66 ms to settle.
    a_glide = math.exp(-1.0 / (0.022 * fs))
    cv = target_midi[0]
    ph = phase0
    # Envelopes
    fenv = 0.0          # filter envelope, decays from 1
    aenv = 0.0          # amp envelope
    acc_cap = 0.0       # accent sweep capacitor
    acc_drive = 0.0     # the accented share of the filter envelope that charges the cap
    # Ladder state
    s1 = s2 = s3 = s4 = 0.0
    fb_hp = 0.0
    fb_hp_x = 0.0
    sq_lp = 0.0
    # SWEEP couples envelope depth with its decay: a small SWEEP is a short shallow blip,
    # a large one a long deep squelch. One knob because on the reference box the hand rides
    # both together. "shape"
    env_oct = 0.6 + 3.6 * sweep                  # octaves of filter envelope
    dec_s = (0.12 + 1.6 * sweep * sweep) * drift_dec
    a_fdec_norm = math.exp(-1.0 / (dec_s * fs))
    a_fdec_acc = math.exp(-1.0 / (0.16 * fs))    # accented notes decay short, fixed. "shape"
    a_att = math.exp(-1.0 / (0.002 * fs))        # 2 ms amp attack. "shape"
    a_rel = math.exp(-1.0 / (0.007 * fs))        # 7 ms amp release. "shape"
    a_hold = math.exp(-1.0 / (2.5 * fs))         # slow amp sag while held. "shape"
    # The accent capacitor: charges fast from the accented envelope, discharges slower,
    # and slower still at high PEAK, so a run of accents climbs (ladyada x0x notes: the
    # sweep "becomes more prominent as the Resonance pot is increased"). "shape"
    a_cap_up = math.exp(-1.0 / (0.012 * fs))
    a_cap_dn = math.exp(-1.0 / ((0.12 + 0.35 * peak) * fs))
    k = 4.15 * peak                              # ladder feedback; self-oscillates near 1.0
    a_fbhp = math.exp(-2.0 * math.pi * fb_hp_hz / fs)  # high-pass in the feedback path
    a_sq = math.exp(-2.0 * math.pi * 9000.0 / fs)   # square edge softening
    cur_vel = 0.6
    cur_acc = 0.0
    fdec = a_fdec_norm
    for i in range(n):
        if trig[i] > 0.5:
            fenv = 1.0
            # restart the oscillator cycle on every new (non-slid) note, so a loop's passes
            # are identical and the seam closes; the round-robin take picks phase0
            ph = phase0
            cur_vel = vel[i]
            # accent amount: nothing below vel 0.55, full at 1.0, smooth in between
            t = (cur_vel - 0.55) / 0.45
            if t < 0.0:
                t = 0.0
            if t > 1.0:
                t = 1.0
            cur_acc = t * t * (3.0 - 2.0 * t)
            fdec = a_fdec_norm + (a_fdec_acc - a_fdec_norm) * cur_acc
        # pitch
        if slide_on[i] > 0.5:
            cv = target_midi[i] + (cv - target_midi[i]) * a_glide
        else:
            cv = target_midi[i]
        hz = 440.0 * 2.0 ** ((cv - 69.0) / 12.0)
        dt = hz / fs
        # polyBLEP saw
        ph += dt
        if ph >= 1.0:
            ph -= 1.0
        saw = 2.0 * ph - 1.0
        if ph < dt:
            x = ph / dt
            saw -= x + x - x * x - 1.0
        elif ph > 1.0 - dt:
            x = (ph - 1.0) / dt
            saw -= x * x + x + x + 1.0
        if square:
            # a square shaped from the saw, as the reference circuit does, with soft edges
            sq = math.tanh(6.0 * saw + 1.2) * 0.9
            sq_lp = sq + (sq_lp - sq) * a_sq
            osc = sq_lp
        else:
            osc = saw
        # envelopes
        fenv *= fdec
        acc_drive = fenv * cur_acc
        if acc_drive > acc_cap:
            acc_cap = acc_drive + (acc_cap - acc_drive) * a_cap_up
        else:
            acc_cap = acc_drive + (acc_cap - acc_drive) * a_cap_dn
        if gate[i] > 0.5:
            aenv = 1.0 + (aenv - 1.0) * a_att
            aenv *= a_hold if aenv > 0.98 else 1.0
        else:
            aenv *= a_rel
        # cutoff: base + envelope (deeper when accented) + the accent capacitor
        # the take's drift scales the whole opening, so accented takes vary as well
        oct_ = (cut_oct[i] + env_oct * fenv * (1.0 - 0.35 * cur_acc)
                + (1.2 + 2.2 * peak) * acc_cap) * drift_cut
        fc = 20.0 * 2.0 ** oct_
        # soft ceiling: however far envelope and accents push, the peak stays in the range
        # where it squelches (FC_MAX) instead of turning into fizz above it. "shape"
        fc = fc_max * math.tanh(fc / fc_max)
        g = 1.0 - math.exp(-2.0 * math.pi * fc / fs)
        # resonance feedback through a high-pass, so the peak does not hollow out the bass
        fb_in = s4
        fb_hp = a_fbhp * (fb_hp + fb_in - fb_hp_x)
        fb_hp_x = fb_in
        u = math.tanh(osc * 0.9 - k * fb_hp)
        s1 += g * (u - s1)
        s2 += g * (math.tanh(s1) - s2)
        s3 += g * (math.tanh(s2) - s3)
        s4 += g * (s3 - s4)
        # mostly the four-pole output, a little of the third pole: a gentler slope. "shape"
        y = 0.8 * s4 + 0.2 * s3
        # make up a little of the passband the feedback takes away
        y *= 1.0 + 0.5 * k / 4.15
        amp = aenv * (0.62 + 0.38 * cur_acc + 0.25 * (cur_vel - 0.6))
        out[i] = y * amp
    return out


def _controls(steps, bpm, root, cycles, cut_oct_fn=None, gate_frac=0.55):
    """Turn steps into per-sample control arrays at the internal rate."""
    step_n = int(round(FS * 60.0 / bpm / 4.0))
    total = step_n * len(steps) * cycles
    tgt = np.zeros(total)
    gate = np.zeros(total)
    trig = np.zeros(total)
    sld = np.zeros(total)
    vel = np.zeros(total)
    last_note = root
    flat = steps * cycles
    for j, st in enumerate(flat):
        a, b = j * step_n, (j + 1) * step_n
        prev = flat[j - 1] if j > 0 else flat[-1]
        nxt = flat[j + 1] if j + 1 < len(flat) else flat[0]
        if st.note is None:
            tgt[a:b] = last_note
            continue
        m = root + st.note
        last_note = m
        tgt[a:b] = m
        vel[a:b] = st.vel
        slid_in = prev.note is not None and prev.slide
        if not slid_in:
            trig[a] = 1.0
        else:
            sld[a:b] = 1.0
        held_on = st.slide and nxt.note is not None
        gate[a:(b if held_on else a + int(step_n * gate_frac))] = 1.0
    cut = np.zeros(total) if cut_oct_fn is None else cut_oct_fn(np.arange(total) / total)
    return tgt, gate, trig, sld, vel, cut, step_n


def render_voice(steps, bpm, root, voice, macros, cycles=1, seed=0, ride=None):
    """The dry voice at the internal rate. `ride(t)` adds a CUTOFF offset (0..1 knob units)
    over normalized time t in [0,1) of the whole render: the hand on the cutoff knob."""
    m = {**DEFAULTS, **macros}
    base = math.log2(expmap(m["CUTOFF"], CUT_LO, CUT_HI) / 20.0)  # "shape"

    def cut_fn(t):
        if ride is None:
            return np.full_like(t, base)
        return np.array([math.log2(expmap(min(1, max(0, m["CUTOFF"] + ride(x))), CUT_LO, CUT_HI) / 20.0)
                         for x in t[::64]]).repeat(64)[: len(t)]

    tune = int(round((m["TUNE"] - 0.5) * TUNE_SEMITONES))
    tgt, gate, trig, sld, vel, cut, step_n = _controls(steps, bpm, root + tune, cycles, cut_fn)
    rng = np.random.default_rng(seed)
    # Round robin is the analog box's own wander: where the oscillator cycle starts, about
    # 1.5% on how far the filter opens, about 6% on the decay. No detuning. "shape"
    phase0 = float(rng.random()) if seed else 0.0
    drift_cut = 1.0 + (float(rng.random()) - 0.5) * 0.03 if seed else 1.0
    drift_dec = 1.0 + (float(rng.random()) - 0.5) * 0.12 if seed else 1.0
    return _voice_kernel(tgt, gate, trig, sld, vel, cut, voice == "SQUARE", m["PEAK"], m["SWEEP"],
                         phase0, drift_cut, drift_dec, float(FS), FB_HP_HZ, FC_MAX), step_n


# ----------------------------------------------------------------------------- BITE (4x)

def bite(x, amount):
    """Clean/drive blend, after the bass overdrive's BALANCE control (dry against drive).

    The drive branch carries the whole signal, so the loud moments (accents, the filter's
    resonant peak) are what hit the clip hardest: that is the compression that glues the
    steps together. A pre-emphasis shelf (+9 dB above ~500 Hz) feeds the peak harder than
    the fundamental, so the bass itself stays round while the squelch saturates; a matching
    de-emphasis after the clip puts the balance back. The branch is level-matched to the
    clean signal it replaces, so BITE changes character, not loudness. At 0 it is an exact
    bypass.

    Measured on LINE B, level-matched (Python harness): accented steps sit 5.9 dB above
    plain ones at BITE 0, 3.6 dB at 0.5, 1.4 dB at 1.0; crest factor 13.6, 10.0, 5.5 dB."""
    if amount <= 0.0:
        return x
    g = 10 ** (lin(amount, 2.0, 24.0) / 20)      # drive into the clip. "shape"
    mix = lin(amount, 0.3, 0.85)                 # share of the drive branch. "shape"
    b, a = signal.butter(1, 500.0 / (FS / 2), "high")
    pre = x + 1.8 * signal.lfilter(b, a, x)      # pre-emphasis shelf, ~+9 dB on top
    d = np.tanh(g * pre + 0.12) - math.tanh(0.12)  # slightly asymmetric: some even harmonics
    lb, la = signal.butter(1, 500.0 / (FS / 2))
    low = signal.lfilter(lb, la, d)
    d = low + (d - low) / 2.8                    # de-emphasis: the shelf taken back out
    d = signal.sosfilt(signal.butter(2, lin(amount, 9000.0, 6000.0) / (FS / 2), output="sos"), d)
    d *= (np.sqrt(np.mean(x ** 2)) + 1e-12) / (np.sqrt(np.mean(d ** 2)) + 1e-12)
    return (1 - mix) * x + mix * d


# ----------------------------------------------------------------------------- SPACE (1x)

@njit(cache=True)
def _delay_kernel(x, d, fb, a_lp, a_hp):
    n = x.shape[0]
    buf = np.zeros(d + 1)
    w = 0
    lp = 0.0
    hp = 0.0
    hpx = 0.0
    wet = np.zeros(n)
    for i in range(n):
        r = w - d
        if r < 0:
            r += d + 1
        tap = buf[r]
        # tone in the feedback loop: each repeat loses lows AND highs, so the repeats
        # thin out above the bass instead of muddying it ("Tone ... for the delay line only")
        lp = tap + (lp - tap) * a_lp
        hp = a_hp * (hp + lp - hpx)
        hpx = lp
        rep = math.tanh(hp * 1.2) / 1.2      # a gentle bound: loop gain < fb by construction
        wet[i] = tap
        buf[w] = x[i] + fb * rep
        w += 1
        if w > d:
            w = 0
    return wet


@njit(cache=True)
def _fdn_kernel(x, lens, g, a_damp):
    """Eight-line feedback delay network with a Householder matrix, one-pole damping per
    line. Gains < 1 by construction (g from T60), so it cannot grow."""
    n = x.shape[0]
    L = lens.shape[0]
    maxlen = 0
    for j in range(L):
        if lens[j] > maxlen:
            maxlen = lens[j]
    bufs = np.zeros((L, maxlen))
    idx = np.zeros(L, dtype=np.int64)
    damp = np.zeros(L)
    out = np.zeros(n)
    v = np.zeros(L)
    for i in range(n):
        s = 0.0
        for j in range(L):
            v[j] = bufs[j, idx[j]]
            s += v[j]
        out[i] = s / L
        hh = 2.0 * s / L
        for j in range(L):
            y = (v[j] - hh) * g[j]
            damp[j] = y + (damp[j] - y) * a_damp
            bufs[j, idx[j]] = damp[j] + x[i] * 0.35
            idx[j] += 1
            if idx[j] >= lens[j]:
                idx[j] = 0
    return out


DIVISIONS = {"1/16": 0.25, "1/8": 0.5, "dotted 1/8": 0.75, "1/4": 1.0}


def space(x, amount, bpm, division="dotted 1/8", delay=True, reverb=True):
    """Tempo delay, then reverb on the whole signal (dry plus repeats).
    SPACE couples: delay mix and repeats rise together, the room grows longer and wetter.
    At 0 it is an exact bypass."""
    if amount <= 0.0:
        return x
    y = x.copy()
    if delay:
        d = int(round(RATE * 60.0 / bpm * DIVISIONS[division]))
        fb = lin(amount, 0.2, 0.62)                       # "shape"
        a_lp = math.exp(-2 * math.pi * lin(amount, 3800.0, 2600.0) / RATE)
        a_hp = math.exp(-2 * math.pi * 260.0 / RATE)      # repeats stay out of the bass
        wet = _delay_kernel(x, d, fb, a_lp, a_hp)
        y = x + lin(amount, 0.25, 0.6) * wet
    if reverb:
        t60 = lin(amount, 0.5, 3.2)                        # "shape"
        lens = np.array([1031, 1327, 1523, 1801, 2039, 2381, 2713, 3001], dtype=np.int64)
        g = 10 ** (-3 * lens / RATE / t60)
        send = signal.sosfilt(signal.butter(2, 220.0 / (RATE / 2), "high", output="sos"), y)
        wet = _fdn_kernel(send, lens, g, math.exp(-2 * math.pi * 4500.0 / RATE))
        y = y + lin(amount, 0.12, 0.38) * wet * 2.0
    return y


# ----------------------------------------------------------------------------- render paths

def to_output_rate(raw):
    y = sk.band_limit(raw, FS)
    return sk.decimate4(y)


def finish(y, target=0.12):
    y = sk.dc_block(y, RATE)
    y = sk.level_to(y, target, ceiling=0.97)
    return sk.fade_tail(y, RATE)


def render_loop(line, macros=None, stages=("bite", "space"), bars=2, ride=None, seed_steps=False,
                division="dotted 1/8", delay=True, reverb=True, warm=2):
    """A seamless LOOP of `bars` bars. The line is rendered for `warm` extra passes first,
    the chain runs over everything, and the last pass is kept, so the delay and reverb tails
    from the end of the loop are already sounding at its start: the loop wraps."""
    m = {**DEFAULTS, **line["macros"], **(macros or {})}
    steps = line["steps"] * bars
    cycles = warm + 1
    ride_fn = None
    if ride is not None:
        ride_fn = lambda t: ride((t * cycles) % 1.0)  # noqa: E731  same ride every pass
    raw, step_n = render_voice(steps, line["bpm"], line["root"], line["voice"], m, cycles=cycles,
                               ride=ride_fn)
    if "bite" in stages:
        raw = bite(raw, m["BITE"])
    y = to_output_rate(raw)
    if "space" in stages:
        y = space(y, m["SPACE"], line["bpm"], division, delay, reverb)
    loop_len = step_n * len(steps) // 4
    full = y
    out = y[-loop_len:]
    return out, full, loop_len


def render_note(midi, voice, macros, vel=0.6, seconds=0.9, gate_s=0.45, seed=0, bite_on=True,
                space_on=False, bpm=120.0):
    """A single keygroup note: the gate held for gate_s, then released."""
    m = {**DEFAULTS, **macros}
    bpm_eff = 60.0 / (gate_s * 4)  # one step == gate length
    steps = [Step(midi - ROOT_MIDI, vel, False)]
    tgt, gate, trig, sld, velv, cut, step_n = _controls(steps, bpm_eff, ROOT_MIDI, 1,
                                                        gate_frac=1.0)
    tune = int(round((m["TUNE"] - 0.5) * TUNE_SEMITONES))
    n = int(seconds * FS)
    pad = n - len(tgt)
    if pad > 0:
        tgt = np.concatenate([tgt, np.full(pad, tgt[-1])])
        gate = np.concatenate([gate, np.zeros(pad)])
        trig = np.concatenate([trig, np.zeros(pad)])
        sld = np.concatenate([sld, np.zeros(pad)])
        velv = np.concatenate([velv, np.full(pad, vel)])
    tgt = tgt + tune
    base = math.log2(expmap(m["CUTOFF"], CUT_LO, CUT_HI) / 20.0)
    cut = np.full(len(tgt), base)
    rng = np.random.default_rng(seed)
    phase0 = float(rng.random()) if seed else 0.0
    drift_cut = 1.0 + (float(rng.random()) - 0.5) * 0.03 if seed else 1.0
    drift_dec = 1.0 + (float(rng.random()) - 0.5) * 0.12 if seed else 1.0
    raw = _voice_kernel(tgt, gate, trig, sld, velv, cut, voice == "SQUARE", m["PEAK"], m["SWEEP"],
                        phase0, drift_cut, drift_dec, float(FS), FB_HP_HZ, FC_MAX)
    if bite_on:
        raw = bite(raw, m["BITE"])
    y = to_output_rate(raw)
    if space_on:
        y = space(y, m["SPACE"], bpm)
    return y


# ----------------------------------------------------------------------------- measurements

def centroid(x):
    return pr.spectral_centroid(x, RATE)


def attack_centroid(x, ms=60):
    i0 = int(np.argmax(np.abs(x) > 0.05 * np.max(np.abs(x))))
    return centroid(x[i0:i0 + int(ms * 1e-3 * RATE)])


def crest_db(y):
    """Peak over RMS in dB: lower means the peaks have been pulled in towards the body."""
    return 20 * np.log10(np.max(np.abs(y)) / (np.sqrt(np.mean(y ** 2)) + 1e-12))
