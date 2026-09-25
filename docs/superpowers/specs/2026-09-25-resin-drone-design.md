# DRONE — a RESIN texture that loops on the bar line

**Status:** built (2026-09-25), to
[`../plans/2026-09-25-resin-drone.md`](../plans/2026-09-25-resin-drone.md).
The five open questions are answered in **Decided**; the probe's
measurements are in **What the probe measured**; and what building it
changed is in **What building it measured**, at the end. The pre-roll is
2.0 s, not the probe's 1.0 s.
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

Three smaller calls follow from those answers and the probe. They are
recorded here so the plan doesn't have to argue them again:

- **n is re-chosen when the tempo changes.** "Stay in tune" is the promise
  you picked. A drone placed at 90 BPM with n = 4 would drift up to 7 cents
  off at 220 BPM if n were frozen, so a tempo change re-slices each drone
  track to its new n. The drone restarts from its first slice at that
  moment; a tempo change already re-renders it.
- **n is picked on the actual nudge, not the worst case.** The worst-case
  table below is the ceiling; a particular ROOT at a particular tempo is
  often closer. The smallest n whose *actual* nudge is ≤ 3 cents wins, which
  means fewer slices and a cheaper render whenever the note is lucky. If
  even n = 8 misses 3 cents, n = 8 and the readout shows the nudge. Across
  RESIN's whole register (A1 and up), every whole BPM from 40 to 220, and
  both 44.1 and 48 kHz, that happens once: A1 at 216 BPM, 3.15 cents flat (3.14 at 48 kHz).
- **A drone owns its whole track.** Its n slices *are* the chain, because
  the chain's wrap has to be the drone's wrap. Nothing else can share the
  track.

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
| The ladder itself | render a settle pre-roll first and keep only the last L. A stable filter driven by an input and a modulation that are periodic with period L settles into output periodic with period L. **Measured:** it does, to the bit, after a 1 s pre-roll (see the end) |

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
(`SessionBuilder.DEFAULT_BARS = 1`, `DEFAULT_BPM = 90`). The grid has a
tempo control (`LoopGrid.kt`'s `TempoControl`, one BPM per tap) but no bar
control; `barsPerInterval` is not live. The original
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
  source)` already uses for samples: a defaulted `SampleSource.drone(...)`
  that a `:shell` decorator answers. `:shell` depends on `:synth` today and
  gains `:loop` for this (no cycle: `:loop` depends on neither). `:loop`
  stores the recipe as opaque `JsonValue`.

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
   whenever `intervalFrames` changes, which is every settled tempo tap. The
   loop-player design accepts a "brief re-bake pause" with parallel baking;
   measure whether drones make it not brief, on a phone.

## Out of scope for the first drone

Stereo width (two decorrelated renders); drones from other engines; motion
on anything but CUTOFF; per-bar variation; and a WAV export beyond what the
grid's existing BOUNCE already does.

## What the probe measured

A throwaway test (not committed, like the held pad's) rendered the drone
exactly as designed: phases taken from the sample index modulo the loop, so
each period recomputes identical oscillator values; the square's cycle
count snapped to a whole number (with at least one beat); the filter
breathing as a sine with RATE whole cycles per loop; everything rendered at
4× the session's rate and decimated with `Dsp.decimate`. The metric is the
energy of one period minus the period before it, over the energy of the
period before: the seam metric, taken across a whole period instead of 256
frames. The bar is 1e-3.

**1. The ladder settles, exactly.** 108 corners: the three voices at their
default roots (A1, A2, A3), CREAM 0 / 0.5 / 1 (r = 4.0), CUTOFF 0.1 / 0.9,
MOTION 0 / 1 / 2 octaves, RATE 1 / 4, six periods each with no pre-roll.
The first period (the transient) differs from the second by up to 1.4e-2 (BASS,
r = 4, CUTOFF 0.1, MOTION 2). **Every later period is identical to the one
before it, bit for bit (0.0), in all 108.** A stable filter in finite
precision doesn't just approach a periodic orbit; it lands on one.

The pre-roll it needs, on the worst corners (r = 4, the lowest cutoffs,
full MOTION), period after the pre-roll against the next:

| Corner | 0.10 s | 0.25 s | 0.50 s | 1.00 s | 1.50 s |
|---|---|---|---|---|---|
| BASS, CUTOFF 0.1, MOTION 2, RATE 1 | 1.3e-3 | 2.6e-9 | 0 | 0 | 0 |
| BASS, CUTOFF 0, MOTION 2, RATE 4 | 3.1e-5 | 2.5e-10 | 4.1e-13 | 7.4e-17 | 0 |
| BRASS, CUTOFF 0.1, MOTION 2, RATE 1 | 2.8e-5 | 1.6e-13 | 0 | 0 | 0 |
| LEAD, CUTOFF 0.1, MOTION 1, RATE 4 | 1.3e-8 | 1.3e-15 | 0 | 0 | 0 |
| BASS, STACK 0.3, CUTOFF 0, MOTION 0 (slowest ring) | 4.9e-4 | 1.8e-6 | 2.2e-10 | 1.0e-14 | 0 |

**Decision: a 1.0 s pre-roll.** It is 11 orders of magnitude under the bar
on the slowest corner, and it costs 0.24 s of CPU. No crossfade, as
promised. *(Superseded when built: 2.0 s. See "What building it
measured".)*

**2. The session's rate works.** Nothing in the chain assumes `Dsp.RATE`:
`Dsp.Ladder(rate)`, `Dsp.decimate(buf, rate)` (4× → 2× → 1× through
`Resampler`, whose weights repeat every period because the oversampled
loop is a multiple of 4 frames) and the phase math all take the rate. At
44.1 and 48 kHz, BASS n = 4, BRASS n = 2 and LEAD n = 1 (r = 4, MOTION 2,
RATE 4) all came out with a period diff of 0 and a 256-frame seam of 0.
The snapped note was -1.97 cents in all six, inside the 3-cent promise.
The detuned square landed on 4 whole beats per loop at STACK 0.8.

**3. Bake cost: too slow for the grid's warm window.** 0.24 s of CPU per
second of audio at 44.1 kHz, 0.26 s at 48 kHz (4× oversampled, one core of
this cloud machine):

| Drone at 90 BPM, 1 bar | Rendered (with pre-roll) | CPU, 44.1 kHz | CPU, 48 kHz |
|---|---|---|---|
| BASS A1, n = 4 | 11.7 s | 2.8 s | 3.1 s |
| BRASS A2, n = 2 | 6.3 s | 1.5 s | 1.7 s |
| LEAD A3, n = 1 | 3.7 s | 0.9 s | 1.0 s |

A phone is plausibly 2 to 3 times slower. **That is past
`Residency.WARM_TIMEOUT_MS` (1.5 s)**. When a tempo change's warm runs
out, the next `buffersFor` bakes whatever is missing *on the engine
thread*, and a 3 to 8 s render there is a long dropout for every track, not
just the drone's. It is also longer than one 2.67 s interval, the window
`prefetch` has on a fresh start. So:

- **A drone never bakes on the engine thread.** When `buffersFor` finds a
  drone slice missing, it hands the engine one interval of silence for that
  track (not cached) and queues the bake on the executor. The drone comes
  in at the first interval after its render lands; every other track plays
  on. The offline bounce (`Bouncer`) still bakes synchronously, as it
  should: it has no deadline.
- **The n slices render once.** The renderer holds one in-flight render per
  (recipe, n × `intervalFrames`, rate), so n slices baking in parallel
  wait on the same render instead of starting n.

**Memory.** The longest drone is one interval at 8 bars and 40 BPM:
48 s, 8.5 M oversampled floats (34 MB), plus the decimator's intermediates.
Spanning never makes a drone longer than about 21 s: a low A needs
roughly 10.5 s to stay within 3 cents, and doubling n can overshoot that
by at most 2×. So the 48 s case is a single long interval. The plan
measures the peak on the JVM and keeps it; streaming decimation is a later
optimization if a phone objects.

## What building it measured

**The pre-roll is 2.0 s, not 1.0 s.** The probe's loop started wherever its
pre-roll happened to leave the breath. The built render starts the loop at
the breath's zero (the LFO at the middle of its swing, rising), because
that is where the grid's bar line is and where a listener expects a breath
to begin. From that start, the darkest full-MOTION corners were still
2.1e-7 apart period to period after 1.0 s. That is far under the seam bar,
but it is not "to the bit". So the pre-roll was re-measured on the six
slowest corners at 44.1 and 48 kHz:

| Corner | 1.0 s | 1.5 s | 2.0 s | 3.0 s |
|---|---|---|---|---|
| BASS, CUTOFF 0.1, CREAM 1, MOTION 1, RATE 1 | 2.1e-7 | 1.4e-15 | 0 | 0 |
| BASS, CUTOFF 0, CREAM 1, MOTION 1, RATE 1 | 2.9e-8 | 3.5e-15 | 0 | 0 |
| BASS, CUTOFF 0, CREAM 1, MOTION 1, RATE 4 | 3.0e-14 | 0 | 0 | 0 |
| BRASS, CUTOFF 0.1, CREAM 1, MOTION 1 | 1.2e-14 | 0 | 0 | 0 |
| LEAD, CUTOFF 0.1, CREAM 1, MOTION 0.5, RATE 4 | 0 | 0 | 0 | 0 |
| BASS, STACK 0.3, CUTOFF 0, MOTION 0 (slowest ring) | 0 | 0 | 0 | 0 |

(44.1 kHz shown. 48 kHz follows the same pattern, with its worst leftover at
1.5 s being 6.9e-15 on the slowest ring; every corner is 0 at 2.0 s.) At 2.0 s, every
corner repeats exactly. It costs 0.24 s more CPU per drone: a default A1
drone measured 3.05 s here, not 2.8 s. `ResinDroneTest` holds every corner
to a period-to-period difference below 1e-12, at both rates.

**The plan's periodicity test couldn't work as written.** It said to render
the loop, then render twice the length and compare. But a drone twice as
long snaps its note to a finer grid, so it is a different loop.
`ResinDrone.synthesize` takes a number of periods instead, and the test
compares consecutive periods of one render, which is what the probe did.

**Pitch is checked exactly, not with a pitch detector.** `Pitch.detect` is
good to about 2%. The drone's note is proved by its spectrum over one loop:
all the energy sits on exactly 2m cycles per loop (m the snapped
sub-octave count) and less than 1e-4 of it a cycle either side. The same
check in `:shell` confirms that the note `DroneFit` reads out on the grid is
the note `ResinDrone` plays.

**Breathing is checked on a brightness curve.** Counting peaks was too
noisy. The test takes the breath count as the dominant cycle rate of
first-difference-over-energy across the loop, and it matches RATE for 1, 2
and 4. At STACK 0.3, where the detuned square is silent, MOTION 0 swings
1.04× and MOTION 1 swings 15.9×. The square's beat at higher STACK moves
brightness too, which is a sound, not a bug.

**The n = 8 miss is flat.** A1 at 216 BPM is -3.15 cents (-3.14 at
48 kHz), pinned in `DroneFitTest` with its sign. On the grid, that one case
reads `DRONE A1 · 1/8 · -3.2¢ OFF`.

**Things the plan didn't name that the build needed:**
- `DroneMaker` in `:shell` holds what the CLI and the SYNTH screen share:
  the recipe (only STACK, CUTOFF and CREAM, so a drone that differs only in
  an ignored knob is one render), the register, the span, the render and
  the readout.
- `Scales.midiOf` is the inverse of `nameOf`, for `--root A1`. No parser
  existed.
- `DroneSource` holds one render per track (`MAX_RESIDENT` =
  `Session.TRACK_COUNT`), least recently used goes first, and a drone's old
  length goes as soon as its new one lands. A cap below the track count
  would re-render a live drone every interval.
- LOOP refits drones on load as well as on tempo, since the device's rate
  changes the interval in frames. After a tempo change the screen's own copy
  of the session takes the refit too, or the next mute would hand the
  engine the old span back.
- `Residency` tracks drone bakes in flight, so `prefetch`, which runs every
  interval, doesn't restart a render that takes longer than an interval.

**Still to hear on a device:** the tempo-change drop-out, the one interval
of silence while a drone re-renders, has only been measured on this cloud
machine, not a phone. Also still to listen for: whether RATE 1 across four
bars sounds like breathing.

## Hardening, round one

What a pass with hostile, extreme and unlucky inputs found and changed:

- **A tempo change left the old render running.** Each settled tempo change
  starts a render at the new length. The old one ran to its end, seconds of
  CPU and tens of MB for a length nothing would play. `ResinDrone.render`
  now asks every 2^15 oversampled samples (about 0.2 s of audio) whether it
  is still wanted, and checks its thread's interrupt flag. `Residency`
  answers "wanted" only while the render's interval length is the one
  playing or the one on its way in, so a tempo on its way in is never
  cancelled by the one playing. A stopped bake is never cached, so a return
  to that tempo renders again instead of playing silence. PREVIEW on the
  SYNTH screen stops the same way on CANCEL.
- **Drones held LOOP's whole bake pool.** LOOP bakes on two threads. Two
  drones rendering held both, so every other track's bake queued behind them
  and ran on the audio thread after all: the stall this design exists to
  prevent. Drones now render on their own single thread
  (`Residency`'s `droneExecutor`). That also caps in-flight render memory
  to one drone.
- **Drones were held and sliced in stereo.** `DroneSource` now keeps renders
  mono, and `BlockBaker` widens each slice as it cuts it. It used to widen
  the whole drone for every slice.
- **A real bug the new tests caught:** `CompletableFuture.get()` rethrows a
  `CancellationException` as itself, not wrapped in an
  `ExecutionException`. A stopped render would have escaped `DroneSource` as
  an exception, not come back as silence.

**Worst-case memory**, computed for the longest drone (one 48 s interval,
8 bars at 40 BPM) at 48 kHz:

| | Before | After |
|---|---|---|
| One render in flight (4× buffer, decimator halves, the cut) | ~77 MB, and up to two at once on the shared pool | ~77 MB, one at a time |
| Cached renders (one per track, six tracks) | 6 × 18.4 MB stereo = 110 MB | 6 × 9.2 MB mono = 55 MB |
| Baking one slice | a stereo copy of the whole drone, 18.4 MB | a stereo copy of the slice only |

The baked slices `Residency` keeps (this interval's and the next, per
track) are the grid's own design and the same for any block.

**Fuzzed:**
- 32 seeded random recipes: every macro including its exact ends, junk keys,
  any MIDI root 0–127, rates from 22.05 to 96 kHz, any tempo and bar count.
  All finite, all under the 0.99 ceiling. Loudness was within
  −1.69..0.00 dB of target over 48 cases; the limiter only ever pulls a
  resonant patch down. Every case repeated exactly: the worst
  period-to-period difference was 0.0.
- 300 random grids: one `refit` settles every drone, a second changes
  nothing, and no other track is touched.

**Hostile inputs:**
- Every bad value of every `--drone` and `--instrument` flag is refused with
  exit 2 and the flag named, and nothing is written. Odd but valid
  spellings (`a3`, ` A3 `, `Bb3`, `-0`) render.
- Ten malformed `loop.json` drone blocks are refused whole. Thirteen odd but
  whole ones load, refit and bounce finite audio of the right length. Those
  include half a drone, two drones in one track, another engine's recipe, a
  recipe that is text or null, and roots at MIDI 0 and 127. A recipe the
  renderer can't read plays as silence.
