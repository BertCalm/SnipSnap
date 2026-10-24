# The COMPRESSOR section: a sustain compressor for the COMP chip

**Status:** design, 2026-10-23, prototyped in Python (not Kotlin), owner gate pending on the prototype's clips. Nothing built. This is Stage 2 of `2026-10-04-pedal-board-design.md`. Repository truth: commit `b503b379`.
**Builds on:** SQUASH (`Squash.kt`, the rack's punch compressor and the COMP chip's recipe today), the pedal library (`Pedals.kt`, where COMP is an entry), and the owner's verdict on the WAH gate's page (2026-10-23): COMP at AMT 70 lifts the tail too little on the snare and the choir, and the guitars need it now.

## Why

The COMP chip is `compressed` = SQUASH at AMOUNT 1.0, ATTACK 0.05 (`Treatments.kt:93`), "the best SQUASH can do". SQUASH is built for punch: its threshold sits at most 18 dB under the peak and its release is fixed at 90 ms. The owner heard it as too little, twice (Stage 0, and the WAH gate's page). The pedal board spec already staged a compressor built for sustain as Stage 2; this spec designs it.

**The characteristic gesture is: hit → the body pressed down → the decay held up → the note still ringing where it used to be gone.**

**Success:** the COMP chip at the pad sheet's default AMT 70 makes a snare ring about three times as long and a choir about two and a half times, holds a guitar's note up through its second half, leaves silence silent, and passes the owner's gate.

## Sibling check

- **SQUASH** (the nearest): a peak-referenced feed-forward compressor with a 2 ms lookahead. It cannot sustain because its threshold is shallow and its release is fixed and slow for a one-shot (see "What the prototype found"). Making SQUASH's range deeper would change every saved pad that uses `squash` and its punch character, which the card's PUNCH chip relies on.
- **SPIKE** (the transient shaper, with its own SUSTAIN macro): it multiplies the body against the attack by a fixed gain of up to 3, so the decay's slope never changes. Measured (a Python port of `Spike.process`, its one-pole assumed as `y += k(x - y)` with `k = 1 - exp(-2π f / rate)`): at SUSTAIN 1.0 the snare rings 101 ms (dry 85) and the choir 295 ms (dry 286), and the baked guitars come out slightly shorter. A decay is only flattened by a gain that depends on level.

So the landing is a new **rack section**, not a recipe over an old one.

## What the prototype found (Python harness, not Kotlin)

The prototype is `2026-10-23-compressor-section-prototype.py`, run on `testkit/pedal-audition/` (the snare and the choir dry, JANGLE and CHUG baked: what the chip acts on today). Measures: **ring time**, the time after the loudest point for a 5 ms level to fall 20 dB under it; **late level**, the level 1.5 s in against the level 50 ms in (guitars only); **spike**, how far the sample peak stands over the body 30 to 80 ms in.

1. **The baked guitars are already flat.** Through their landing amp JANGLE is only 2.8 dB down 400 ms after the pick and 8.1 dB at 1.5 s; CHUG is 2.1 and 9.0. A "tail over body" lift measured at 200 to 400 ms (the WAH gate page's COMP metric) sees almost nothing to lift on them, which is why COMP read 0.2 to 1.9 dB there. On guitars a sustain compressor works on the second half of the note, so the gate measures the late level.
2. **On a one-shot, a short release is sustain.** A long release keeps the gain down through the tail. At the chosen threshold and ratio, a 20 to 60 ms release gives the snare a 251 to 258 ms ring and the choir 725 to 742 ms; 300 ms gives 136 and 432; 1 s gives 127 and 329. (The pedal board spec's "a release of 300 ms or more adds 2.6 to 3.5 dB" came from a different metric and does not hold for ring time; this spec supersedes it.)
3. **The peak-match needs a limiter in front of it.** The rack's contract keeps a section's output peak at its input peak. With any attack slower than instant, the first milliseconds of the hit escape the compressor and stay the peak, so the peak-match adds no make-up and the tail never rises (the first prototype: the guitars' late level got worse). A lookahead limiter whose ceiling is the compressor's own output at the loudest level, plus a headroom, fixes it. The headroom is ATTACK's second job (point 5).
4. **The detector and the release must be separate.** The first prototype smoothed the level detector with the release: at 300 ms the snare's measured level never fell below 17 dB under its peak in its whole 333 ms, so nothing downstream could act on the tail. The design uses a short level detector (5 ms mean square) and applies attack and release to the gain in dB (the standard form: Giannoulis, Massberg and Reiss, "Digital Dynamic Range Compressor Design: A Tutorial and Analysis", JAES 60(6), 2012).
5. **ATTACK only reads as punch when the limiter's headroom follows it.** With a fixed headroom the slow end flattened the hit (the snare's spike went down). Headroom = 12 dB × ATTACK: the snare's spike runs 14.3 dB at 0 to 18.8 at 1, the guitars' about 4 to 11.5.
6. **Upward compression was tried and rejected.** Lifting what falls under the threshold leaves the hit untouched, but the choir and both guitars never fall under a useful threshold in their body: it changed nothing on them and 2 dB on the snare.
7. **Low notes do not distort.** On a decaying 82.4 Hz sine (a low E), the added harmonics (2nd to 5th) sit 55 dB under the fundamental at a 20 ms release and 64 dB under at the default.

The chosen setting at AMOUNT 0.7 (the chip at AMT 70), against dry and against the COMP chip at AMT 70 today:

| Pad | Dry | COMP today at 70 | New at 70 | New at 100 |
|---|---:|---:|---:|---:|
| Snare, ring time | 85 ms | 125 ms | 250 ms | 277 ms |
| Choir, ring time | 286 ms | 446 ms | 725 ms | 836 ms |
| JANGLE, level at 1.5 s | −6.3 dB | −4.1 dB | −1.8 dB | −1.5 dB |
| CHUG, level at 1.5 s | −7.6 dB | −3.1 dB | −2.4 dB | −2.1 dB |
| Last 20 ms under the peak (all 24 new clips) | | | at least 64.8 dB | |

70 is close to 100 on purpose: the Stage 0 listen and this one both found a chip that is weak at the default AMT, and the WAH section set the same rule.

## Three directions

- **Strong: the classic, peak-referenced, with a limiter in front of the peak-match** (DISTIL of the sustain pedal: an OTA or optical compressor whose make-up is the point). Gesture clear; new against the rack; risk low (one level-dependent gain, measured); cost about SQUASH's size plus a limiter.
- **Alternate A: upward compression with a floor** (INVERT). Keeps the hit exactly, which is attractive, but measured to do nothing where the body never falls below the threshold. Rejected on the numbers above.
- **Alternate B: an auto-gain "sustainer"** (MUTATE toward an infinite ratio, the e-bow end): divide the signal by its envelope toward a target. The extreme of Strong, with no musical middle; Strong at AMOUNT 1 (10:1) already reaches most of it. Not built.

Strong wins because it is the only one that moves all four pads, and its middle settings are as usable as its ends.

## Signal flow

```
                    linked level (5 ms mean square, max over channels), dB under the pad's loudest level
in ──┬──────────────────────────────────────────────────┐
     │                                                  v
     │                               static curve: above T, (T - L)(1 - 1/R); under the FLOOR, make-up taken back
     │                                                  v
     │                               gain smoother, in dB: ATTACK when falling, RELEASE when rising
     │                                                  v  (applied 2 ms early)
     └───────────────────────────────────────────> x gain ──> lookahead limiter (ceiling = static output at the
                                                                loudest level + 12 dB × ATTACK + 3 dB) ──> peak-match ──> out
```

## Macro contract

| Control | Default | Neutral | What it changes | Required interaction | Test |
|---|---:|---:|---|---|---|
| AMOUNT | 0.5 | 0 | threshold 10 dB + 30 dB × AMOUNT under the loudest level; ratio 2 + 8 × AMOUNT (31 dB, 7.6:1 at 0.7) | at 0 a bit-exact copy | ring time grows with AMOUNT on the snare and the choir |
| ATTACK | 0.5 | 0.5 | gain fall time 0.5 ms to 40 ms (exponential, 4.5 ms at 0.5) and the limiter's headroom 0 to 12 dB | ATTACK × AMOUNT: punch only exists where there is gain reduction to escape | spike over body grows with ATTACK on the snare and a guitar |
| RELEASE | 0.3 | 0.3 | gain rise time 20 ms to 1 s (exponential, 64 ms at 0.3) | RELEASE × AMOUNT: a long release undoes the sustain AMOUNT adds | ring time falls as RELEASE rises |

ATTACK's and RELEASE's neutrals equal their defaults, so the AMT fade (`Treatments.fade`) walks AMOUNT alone and the chip keeps its timing at every AMT. AMOUNT's fade law is LINEAR: it already maps to dB linearly, so the LOG walk (`FadeLaw.LOG`, for gain macros) does not apply.

**The recipe:** `compressed` = `compressor = {AMOUNT 1.0}`, replacing its SQUASH map. The COMP entry in `Pedals.kt` keeps its key, side (PRE) and movability; its chain follows the character by reference, so the library needs no edit.

## Placement and name

- **The saved key is `compressor`** (a section name is a permanent saved key). `sustain` was the brainstorm's suggestion, but SPIKE already has a macro called SUSTAIN, and the key should name the mechanism. `COMPRESSOR` appears in no preset name today (grep, `b503b379`); a new section's name becomes a forbidden preset word.
- **In the rack after `wah`, before `squash`** (the brainstorm's diagram: wah, then the compressor, then SQUASH; the guitar convention: wah, compressor, drive, amp).
- **The chip's word** (COMP or SUSTAIN) is one of the gate's questions; the saved key `compressed` does not change either way.

## Tests (proposed bars: measured value minus a margin, each mutation-checked)

- AMOUNT 0 is a bit-exact copy; deterministic; finite; the shared rack contract (`FxTest` iterates `SECTIONS`: peak-matched within 0.05, stereo identical, JSON round trip, AMT 0 on every neutral) with no exemption.
- Sustain: at AMOUNT 0.7, defaults otherwise, the snare's ring time is at least 2.5 times dry (measured 2.94) and the choir's at least 2.2 times (measured 2.53), and both beat `compressed` as it is today.
- Guitars: a baked JANGLE note at AMOUNT 0.7 holds within 3 dB of its level 50 ms in at 1.5 s (measured −1.8).
- The floor: on every pad, the last 20 ms sit at least 50 dB under the peak (the WAH section's bar; measured 64.8 dB at the worst, the snare at ATTACK 1).
- ATTACK raises the spike over the body (snare 14.3 to 18.8 dB, guitars about 4 to 11.5); RELEASE lowers the ring time (snare 258 to 127 ms).
- Low notes: on a decaying 82.4 Hz sine at RELEASE 0, harmonics 2 to 5 at least 50 dB under the fundamental (measured 55).
- Placement: `compressor` sits right after `wah` and before `squash`.

The Kotlin section must reproduce the prototype's table within a stated tolerance before the chip moves; if it does not, the difference is a finding, not a re-tune.

## The owner's gate

On the SUSTAIN Gate page (the prototype's clips; to be opened in Chrome, because the Claude app's Android viewer plays no sound): is the sustain enough at 70 on the snare and the choir, and on the guitars; do ATTACK's and RELEASE's ends go far enough ("the ends must be extreme, the ugly end allowed"); COMP or SUSTAIN on the chip. A section that fails its gate ships without the chip's recipe moving.

## Integration (each row greps at `b503b379`)

- `FxChain`: one `Section("compressor", Compressor.MACROS, ...)` row in `SECTIONS` between `wah` and `squash` (`FxChain.kt:199-200`), one appended `compressor: Map<String, Float>?` constructor field; `FxChain.VERSION` stays 1. The KDoc order line, `README.md`'s rack lines and `docs/CLI.md` move with it.
- `Treatments.EXTRA`: `compressed` changes recipe (`Treatments.kt:93`); `TreatmentsTest`'s names list does not change (no new character).
- `FxTest`'s pinned order lists and `SECTION_NAMES` users (`FxTest.kt:696`, `TremorPresetsTest.kt:92`, the other presets tests' forbidden words).
- A pad saved with `compressed` keeps its baked audio; replaying its recipe renders the new sound. That is the point of the change, and the As built section should say how many goldens moved.
- No app change: row seven derives from `Pedals`.

## Out of scope

A sidechain or ducking (PUMP, the brainstorm's idea); a parallel blend (MIX); multiband; a noise gate; Stage 3 (the pedal in front of a guitar's amp); retuning SQUASH.

## Effort

About SQUASH's size (96 lines) plus a limiter: a section of about 140 lines, its test class, the registration rows and a recipe line.

## Ledger

| Claim | Status | Evidence |
|---|---|---|
| `compressed` is SQUASH AMOUNT 1.0, ATTACK 0.05 | verified | `Treatments.kt:93` |
| SQUASH: threshold `peak × expMap(1 - AMOUNT, 0.12, 0.9)`, release 90 ms, 2 ms lookahead, peak-matched | verified | `Squash.kt` |
| SPIKE's SUSTAIN is a fixed body gain up to 3 | verified | `Spike.kt:25-60` |
| `wah` then `squash` in `SECTIONS` | verified | `FxChain.kt:199-200` |
| Baked JANGLE and CHUG fall only 8 to 9 dB in 1.5 s | measured | prototype, `level_db` contours |
| A short release is sustain on a one-shot (table above) | measured | prototype sweep |
| SPIKE at SUSTAIN 1 barely extends a ring (101 ms snare, 295 ms choir) | measured | Python port; the one-pole form assumed |
| Added harmonics 64 dB under the fundamental at the default release | measured | prototype, 82.4 Hz decaying sine |
| The Kotlin section reproduces the prototype's numbers | proposed | the tests above |
| The sustain is enough at 70; the ends are extreme enough | heard? | not heard; the SUSTAIN Gate page |

## Why it is built this way

- **It compresses downward and lets the peak-match do the make-up**, because every section in the rack keeps its output peak where its input peak was. Pressing the loud body down, then bringing the peak back up, is what raises the quiet part: that is the sustain.
- **The limiter is there so the hit cannot cancel the make-up.** Without it, the first few milliseconds of the hit slip through, stay the loudest point, and the peak-match has nothing to give back. Remove it and the guitars get quieter, not longer.
- **The release is short by default** because a one-shot is one hit, not a stream of notes: the gain has to come back up while the note is still ringing. A long release is the "natural" end of the knob, not the sustain end.
- **ATTACK does two things at once** (how fast the squeeze lands, and how much room the hit has above the body), because one without the other did not sound like punch at all.
- **The one risky decision** is the floor: 50 dB under the loudest level the make-up is taken back out, so silence and the pad's own end fade stay where they were. Set it too high and quiet tails stop sustaining; too low and hiss comes up with the note.
