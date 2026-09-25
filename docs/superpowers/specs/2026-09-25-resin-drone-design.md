# DRONE — a RESIN texture that loops on the bar line

**Status:** approved direction (2026-09-25). The five open questions are
answered in **Decided** below. The probe the spec asks for is running; its
findings and the plan follow in this branch.
**Date:** 2026-09-25
**Builds on:** [`2026-09-25-resin-held-pad-design.md`](2026-09-25-resin-held-pad-design.md),
the "every moving part completes whole cycles" loop math, generalized from
one note's loop to a whole bar-length block.

## Decided

1. **How long before it repeats: (a).** The drone spans the smallest n in
   {1, 2, 4, 8} intervals that keeps its note within 3 cents.
2. **Block or file: a `DroneBlock`** on the loop grid, re-rendered on a BPM
   change.
3. **Mono** for the first version. The grid bakes stereo, so the one
   channel goes to both sides.
4. **Module boundary: inject a renderer.** This wasn't put to the user as a
   question; it is the spec's recommendation, taken as the default because
   it only changes where code lives, not what the drone sounds like.
5. **MOTION is filter breathing**, as designed. Width drift stays out of
   scope.

## What this is

A long, slowly moving RESIN sound that sits under a groove: a low note
breathing through the ladder filter, the detuned square drifting against the
saws, playing forever on the loop grid without a click at the bar line. The
held pad is played by holding a key. The drone is placed on a track and just
runs.

## Why it is a different problem from the held pad

| | Held pad (approved direction) | Drone (this) |
|---|---|---|
| How you play it | hold a key on KEYS | place it on a loop-grid track |
| Length | as long as the key is down | a whole number of loop-grid intervals (bars at the session's BPM) |
| The loop | the steady tail of one note, 0.14–4.1 s | the whole drone; the wrap is a bar line |
| Motion | none once settled; the loop must be *still* | the point: the filter breathes, the width drifts |
| Tempo | irrelevant | the length follows BPM, so it must re-render when BPM changes |

A held pad's loop only works because nothing moves inside it. A drone
*wants* movement, so the movement itself has to be periodic over the block.

## The idea: one clock, the loop

The loop grid already has the right shape. Every block bakes to exactly
`Session.intervalFrames` (`loop/Session.kt:105`, = bars × 4 × 60/BPM ×
sample rate). A `PatternBlock` is *rendered* at bake time rather than stored
as audio, and `Residency` re-bakes every block when `intervalFrames` changes
(`Residency.kt:216`). A drone is a third kind of rendered block. Render it to
exactly *n* × `intervalFrames` (n = 1, 2, 4 or 8; see the tuning trade-off
below for how n is chosen), slice it into a chain of *n* blocks, and make
every moving part complete a whole number of cycles in that length, L:

| Part | Whole cycles per drone, by |
|---|---|
| Saw at the note, saw an octave down | nudging the note so the *sub-octave* completes an integer count (below) |
| Detuned square | a ratio snapped to an integer cycle count, the held pad's trick at interval scale; the beat count then comes out whole on its own |
| Filter breathing (new: MOTION) | a sine on CUTOFF (in octaves, like CONTOUR) with exactly 1, 2 or 4 cycles per drone (RATE) |
| The ladder itself | render a settle pre-roll first and keep only the last L. A stable filter driven by an input and a modulation that are periodic with period L settles into output periodic with period L. **Unmeasured:** this is the probe's first question, for the same self-oscillation reason the pad caps CREAM at r = 4.0 |

Nothing is crossfaded. If the probe shows the ladder doesn't settle into a
periodic state under modulation, that is the finding that changes the
design, not something a fade covers up.

## The one trade-off: a few cents of tuning

A pitch completes a whole number of cycles in L seconds only if it is a
multiple of 1/L Hz. The sub-octave saw forces multiples of 2/L on the note.
So the drone's note can be off true pitch by up to 1/L Hz. **Worst case**, in
cents:

| Loop length | L | A1 (55 Hz) | A2 (110 Hz) | A3 (220 Hz) |
|---|---|---|---|---|
| 1 bar @ 120 | 2.00 s | 15.7 | 7.9 | 3.9 |
| **1 bar @ 90, a fresh session's interval** | 2.67 s | **11.8** | **5.9** | 2.9 |
| 2 bars @ 90 | 5.33 s | 5.9 | 2.9 | 1.5 |
| 4 bars @ 90 | 10.67 s | 2.9 | 1.5 | 0.7 |
| 8 bars @ 90 | 21.33 s | 1.5 | 0.7 | 0.4 |

A fresh loop-grid session is **one bar at 90 BPM**
(`SessionBuilder.DEFAULT_BARS = 1`, `DEFAULT_BPM = 90`), and the grid has no
tempo or bar control yet (the builder's own KDoc says so). The original
loop-player design's "default 4 bars" is only `SessionStore`'s fallback for
a file that doesn't say. So a drone confined to one interval would, in the
common case, be up to 12 cents off at A1: audibly out against any other
pitched track.

The loop is *where the drone repeats*, and that doesn't have to be one
interval. A track's chain cycles (`Arrangement.indexAt` = interval % chain
size, up to `Session.MAX_CHAIN` = 8). That is exactly how `SessionBuilder`
already turns a long catch into a chain of two or three blocks. So:

- **(a) Span several intervals** (recommended). The drone renders once over
  *n* intervals and lands on its track as a chain of *n* slices of that one
  render. The chain's wrap is the drone's wrap, and it is whole-cycle over
  n × L. Pick the smallest n in {1, 2, 4, 8} whose worst-case nudge is at
  most 3 cents for the chosen ROOT. At the default interval that is n = 4
  for A1 (≤ 2.9¢), n = 2 for A2 (≤ 2.9¢), and n = 1 for A3 and up. A longer
  loop also suits a drone: one breath across four bars sounds like
  breathing; one per bar sounds like a wobble.
- **(b) One interval, with the nudge shown** ("A1 · 11.8¢ OFF"). This is
  simpler, but it ships an out-of-tune default.
- **(c) One interval, and refuse low roots** on short intervals. It is
  honest, but the bass register is exactly where drones live.

Recommendation: **(a)**. It uses a mechanism the grid already has, and in
the common case it turns a 12-cent problem into a 3-cent non-problem.

## Where it lives: a block, not a sound file

Two ways to get a drone onto the grid:

1. **A `DroneBlock`** (recommended): a third `Block` kind holding a *recipe*
   (the RESIN patch as JSON, plus ROOT, MOTION and RATE) and its slice,
   `slice i of n`. It is rendered at bake time like `PatternBlock`. A BPM
   change re-bakes it to the new `intervalFrames`, so the whole-cycle math is
   redone at the new length and it stays seamless and in tune. `loop.json`
   stores the recipe, not audio, the same promise `kit.json` makes for synth
   pads. The *n* slices of one drone share one render, so the renderer
   caches the full n × L render by (recipe, `intervalFrames`); `Residency`
   caches per block, and without that each slice would render the whole
   thing again.
2. **A WAV from the SYNTH screen** (`MAKE DRONE ▸` → SNIPS → a `LoopBlock`).
   This is simpler to build, but `LoopBlock`s are *fitted* to the interval
   (`BlockBaker.fitLoop`: stretched or trimmed). A drone rendered at one
   tempo and fitted to another loses both the whole-cycle seam and exact
   pitch. It works until the first BPM change, then quietly degrades.

**Module boundary:** `:loop` depends on `:json`, `:audio`, `:kit`, `:mpc3`,
**not** `:synth` (`loop/build.gradle.kts`). A `DroneBlock` needs a renderer.
Options (open question 4):

- **(i)** `:loop` → `:synth`. No cycle (`:synth` doesn't depend on `:loop`),
  but the grid learns about engines.
- **(ii)** Inject a renderer, the seam `BlockBaker.bake(block, session,
  source)` already uses for samples: a `DroneRenderer` fun interface that
  `:shell` (which depends on both) supplies. `:loop` stores the recipe as
  opaque `JsonValue`.

Recommendation: **(ii)**. The grid stays engine-agnostic, and a drone from
another engine later is a renderer change, not a grid change.

## The knobs

- **The RESIN patch** (any preset or user preset): STACK, CUTOFF and CREAM as
  they are (CREAM capped at r = 4.0, as for the held pad). CONTOUR and DECAY
  have no note-on to act on, so the drone ignores them. TUNE is replaced by
  ROOT.
- **ROOT:** the note, defaulting to the open kit's key (the KIT screen's key
  picker, F5.3) in the voice's register.
- **MOTION:** how far the filter breathes, 0 to ±2 octaves around CUTOFF.
- **RATE:** breaths per drone, 1, 2 or 4. Whole numbers are the design,
  not a limitation: fractional breaths are what make a loop click.

## Things the probe must answer before a plan

1. **Does the modulated ladder settle into a period-L state, and how long a
   pre-roll does it need?** Seam metric as in the held pad, bar 1e-3.
2. **The sample rate.** Sessions have their own `sampleRate`; RESIN renders at
   `Dsp.RATE` (44.1 kHz). Resampling a seamless 44.1 kHz render to another
   rate doesn't keep a whole-frame length, so the drone must *render at the
   session's rate* (`synthesize` takes a rate; `Dsp.Ladder` and
   `Dsp.decimate` do too). Confirm nothing in the chain assumes `RATE`.
3. **Bake cost.** 1.06 s of CPU per 4 s at 4× oversampling (the held pad's
   measurement), so a four-interval drone at the default (4 × 2.67 s =
   10.7 s) is about 3 s plus pre-roll, on this cloud machine. It re-bakes
   whenever `intervalFrames` changes. That is rare today (the grid has no
   tempo control), but it will not stay rare. The loop-player design
   accepts a "brief re-bake pause" with parallel baking; measure whether
   drones make it not brief, on a phone.

## Out of scope for the first drone

Stereo width (two decorrelated renders); drones from other engines; motion
on anything but CUTOFF; per-bar variation; and a WAV export beyond what the
grid's existing BOUNCE already does.

## Open questions (for the user)

1. **How long before it repeats:** (a) span as many intervals as it takes to
   stay within 3 cents (recommended: at a fresh session's 1 bar, that is 4
   bars for a low A), (b) one interval with the tuning error shown, or
   (c) one interval, refusing low notes?
2. **Block or file:** a `DroneBlock` on the grid (recommended) or a WAV from
   the SYNTH screen?
3. **Mono or stereo** for the first version? Mono is simpler and a drone
   under a groove often sits in the middle anyway; stereo is wider but
   doubles the render.
4. **Module boundary:** inject a renderer (recommended) or let `:loop` depend
   on `:synth`?
5. **Is MOTION on the filter the right first movement,** or would you rather
   the width drift (STACK breathing) or both?
