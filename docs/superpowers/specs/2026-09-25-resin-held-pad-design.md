# RESIN, held — a pad instrument that sounds until you let go

**Status:** design, approved 2026-09-25 (answers to the open questions are
recorded under "Decided"). Implementation per the plan.
**Date:** 2026-09-25
**Plan:** [`docs/superpowers/plans/2026-09-25-resin-held-pad.md`](../plans/2026-09-25-resin-held-pad.md)
**Next on deck:** [`2026-09-25-resin-drone-design.md`](2026-09-25-resin-drone-design.md) — the long,
slowly moving texture. Separate problem, separate spec; it reuses this one's loop math.

## What this is

Pick a RESIN sound on the SYNTH screen, press MAKE INSTRUMENT, and get a
keys instrument on the shelf. Hold a pad on KEYS and the note keeps sounding
for as long as your finger is down. It can fade in slowly (ATTACK), and when
you lift your finger it fades out (RELEASE). It is the same sound RESIN
already makes, the same oscillators through the same ladder, held instead of
struck.

This is "RESIN held down" in exactly the sense the Organ is "TONEWHEEL held
down" (`Keys.kt:64`). It is **not** a fourth RESIN voice and **not** a tenth
engine:

- **Not a voice.** Every RESIN voice goes through one render with a fixed
  3 ms attack and a decay that ends within 1.2 s (`Resin.kt:133`, `:158`).
  The SYNTH screen also re-renders on every knob turn and replays the result
  (roadmap principle 5, "the instant loop"). A note that lasts as long as it
  is held breaks both.
- **Not an engine.** A new engine would rebuild the saws and the ladder
  just to change how long the note lasts. The sound source is already right;
  what is missing is *holding*, and the shop already knows how to hold.

## Why not just MAKE PAD?

`PadFromAnything` (the PAD SHEET's MAKE PAD card, `PadFromAnything.kt`) already
turns *any* pad's sound, including a RESIN stab sent to a pad, into an
instrument that holds. It is the nearest thing to this, and it is a different
instrument:

| | MAKE PAD (exists) | RESIN, held (this) |
|---|---|---|
| Source | one captured sound | the RESIN patch itself |
| How it holds | time-stretches the sound into a wash (paulstretch, or the clear stretch for a pitched source), then loops the wash | renders the synth *sustaining*, then loops it |
| What you hear | a smeared, stretched texture of the stab | the synth's own steady tone: same saws, same filter, same width |
| Seam | a baked 0.75 s crossfade, inaudible on a stationary wash by construction | no crossfade: the loop is a whole number of every waveform's cycles, so the seam is mathematics (the Organ's method) |
| Keys | one sample, rooted at the detected pitch and transposed across all 128 keys | nine zones, every minor third, each rendered at exact pitch (the S5 suite's convention) |
| Fade in / out | BLOOM 0–1 s; release fixed at 0.6 s | ATTACK 0.01–2.5 s; RELEASE 0.1–1.5 s |

Both belong in the shop. MAKE PAD is "turn anything into weather"; this is
"play the synth like a keyboard pad".

## What already exists, and is reused as-is

| Piece | Where | Role here |
|---|---|---|
| `KeyNote(snip, loopStartFrame)` | `Keys.kt:14` | one rendered, looped zone |
| The whole-period loop cut | `Keys.organ`, `Keys.kt:64–98` | the method this generalizes |
| Held envelope | `Dsp.Env(..., holdSeconds)`, `Dsp.kt:422` | flat level after the attack |
| Hold while pressed, fade on release | `InstrumentEngine.kt:71–115` (shell), `InstrumentPlayer.kt:99–120` (app) | the phone already loops a zone while a key is down and fades it over the instrument's release |
| Package writer | `OneNote.writePackage`, `OneNote.kt:248` | `.xty` + `_[TrackData]/` WAVs + `.xpm` twin + the phone's `.instrument.json` sidecar; WAVs carry their `smpl` loop |
| No clobbering | `OneNote.freshName`, `OneNote.kt:242` | MAKE PAD's lesson, kept |
| The shelf | `KitShelf.instrumentsDir`; KITS → INSTRUMENTS shows `· HOLDS` for looped instruments (`KitsScreen.kt:686`) | where the instrument lands and how the user finds it |

Nothing in the phone's player, the package writers or the instrument store
changes. The work is a held render with a seam that closes, plus two doors to
reach it: the SYNTH screen, and the CLI for desktop auditioning.

## The one hard problem: a seam that isn't there

A loop sounds seamless only if the looped stretch repeats *exactly*: the
last sample of the loop must lead straight into the first. So everything that
moves in a held RESIN note has to come back to where it started, at the same
moment, at the loop point. Going through the signal path:

| Part | Periodic? | What makes it so |
|---|---|---|
| Amp envelope | yes, once past the attack | `Dsp.Env` with `holdSeconds` longer than the render: flat at 1 |
| CONTOUR's filter sweep | it *lands*, and a landed sweep is constant | the loop starts well after it lands (below) |
| Saw at the note, saw an octave down | yes, together, every 2/f seconds | integer frequency ratio 2:1 |
| **Detuned square (STACK's top half)** | **no** | its frequency is f × 2^(cents/1200), not a whole-number ratio to f, so it *beats* against the saws, and no short loop closes on a beat |
| The ladder filter | yes, once settled, if the input is periodic and the cutoff is steady | time-invariant at steady cutoff; its tanh stages are memoryless |
| … unless it self-oscillates | **no** | a self-oscillating ladder rings at its own frequency near the cutoff, not locked to the note |
| 4× oversample → decimate | preserves periodicity | decimation is linear and time-invariant (Tonewheel's KDoc makes the same argument) |

Two things break the loop: the detuned square, and self-oscillation. The
rest just needs time to settle before the loop starts.

### Snapping the detune, instead of dropping the square

Dropping the square wave would close the seam, but it would also throw away
what STACK's top half *is* (width and slow movement). The better fix uses the
fact that we choose every frequency ourselves:

- Make the loop exactly **K periods of the sub-octave saw**: `2K / f` seconds.
  Both saws complete whole cycles in it (K and 2K).
- Set the square's frequency ratio to exactly **`1 + 1/(2K)`**. In one loop it
  then completes `2K + 1` cycles, a whole number, which is *exactly one beat*.
- Choose K from the detune STACK asks for: `K = round(1 / (2 × (2^(cents/1200) − 1)))`.
- Round the loop to a whole number of frames and **move the pitch by a hair
  to fit it**: `f' = 2K × RATE / loopFrames`. The move is at most half a frame
  over the loop, 0.14 cents at the shortest loop (A5), far below hearing and
  below the keygroup's own tuning. (The first draft searched K for a loop
  that happened to land near a whole frame, the Organ's method. Building it
  showed why that isn't enough; see "What building it measured" below.)

The beat stays in. It becomes the loop's own clock: one beat per pass.

When STACK is at 0.4 or below, the square's gain is zero (`stackGains`,
`Resin.kt:112`). There is no beat to snap, and the loop falls back to the
Organ's rule: whole sub-periods near 0.5 s.

### Measured, not guessed

A throwaway probe, deleted after measuring, rendered held RESIN notes with
the real `Dsp.Ladder`, `Dsp.decimate`, `Resin.stackGains` and
`Resin.frequencyFor`, at 4× oversampling. Seams were scored with
`InstrumentSuiteTest`'s own metric: the energy of the difference across the
wrap divided by the signal's energy, over the 256 frames before the loop
start. The Organ ships at **< 1e-3**.

**Strategies** (STACK 0.6, CREAM 0.35, loop starting at 1.5 s):

| Note | Keep detune, 0.5 s organ loop | Drop the square | **Snap the detune** (loop length) |
|---|---|---|---|
| BASS A1, 55 Hz | 6.7e-01 | 1.9e-11 | **5.2e-10** (3.20 s) |
| BRASS A2, 110 Hz | 2.9e-01 | 3.3e-12 | **3.1e-10** (1.60 s) |
| BRASS A3, 220 Hz | 4.8e-01 | 1.4e-11 | **2.9e-10** (0.80 s) |
| BRASS A4, 440 Hz | 4.3e-03 | 2.8e-10 | **2.2e-10** (0.40 s) |
| LEAD A5, 880 Hz | 3.9e-01 | 1.1e-09 | **3.7e-10** (0.20 s) |

Keeping the raw detune fails everywhere, by up to three orders of magnitude.
Snapping lands at about 1e-10, as clean as dropping the square, and keeps
the width. At STACK 1.0 snapped seams ran 1e-11 to 3e-10. Across CREAM 0.35,
0.8 and 0.9 every snapped seam passed. The only ones above 1e-9 were
BASS A1 at high CREAM (5.7e-7 at 0.8, 2.5e-5 at 0.9): the filter settling
slowly at a low cutoff, which the worst-case table below explores.

**Self-oscillation.** At CREAM 1.0 (r = 4.3, past the ladder's threshold
r = 4) most notes still closed, because a strong input tends to pull the
oscillation into step with it. BASS A1, the lowest note, did not: 7.9e-3 with
the square dropped. That is a fail, and it is the lowest note of the most
common pad voice. "Most notes" is not a promise.

**Worst case below the threshold.** BASS A1 at the lowest cutoffs (CUTOFF 0
and 0.35), CONTOUR 1 and DECAY 1 (the slowest-landing sweep), r = 3.5 / 3.9 /
4.0, and STACK 0.3 / 0.6 / 1.0:

| Loop starts at | Worst seam, over all 18 cases |
|---|---|
| 1.5 s (3 ms attack) | 7.1e-5, i.e. 14× under the Organ's bar |
| 2.5 s | 1.9e-6 |
| 3.5 s, also with a 2 s attack | 3.2e-7 |

**Loop lengths.** Snapped loops run from about 0.14 s (LEAD A5, STACK 1) to
about 4 s (BASS A1, just above STACK 0.4, where the detune is smallest and the
beat slowest).

**Render cost.** 1.06 s of CPU per 4 s held zone on this cloud machine
(JVM, 4× oversampled, single thread). The phone is untested; see Decided 3.

### What building it measured

The probe's snapped seams (≈1e-10) were not the whole story. The first
implementation searched K ±10% for a loop that landed *near* a whole frame,
the way `Keys.organ` does, and a loop can only be a whole number of samples.
Where the true length of K periods fell a few hundredths of a frame off, the
leftover was audible in the seams of bright zones:

| LEAD at CUTOFF 1, CREAM 0 | Frame residue | Seam |
|---|---|---|
| STACK 0.6, A3 | 0.000 | 1.5e-10 |
| STACK 0.6, C4 | +0.068 | 2.2e-4 |
| STACK 0.6, C5 | −0.025 | **6.5e-4** |
| STACK 1.0, F#5 | −0.013 | 5.3e-4 |

That passes the 1e-3 bar, but not by much. The fix is the pitch fit above:
round the loop to whole frames and move the pitch to fill it exactly. Measured
after the fit (the plan's tests pin all of it):

| | Worst seam |
|---|---|
| Every zone of every voice at defaults | 1.3e-14 |
| Bright LEAD (the table above), all zones, STACK 0.6 and 1.0 | 1.8e-15 |
| BASS A1 worst corner (CUTOFF 0, CREAM 1 → r 4.0, CONTOUR 1, DECAY 1) | ≤ 2.8e-14 |
| Largest pitch move, any zone | < 0.2 cents |

Everything is floating-point noise, including the worst corner the probe put
at 7.1e-5. The probe had the same fractional residue, so that figure measured
the residue as much as the filter settling. **Detune** now comes straight
from STACK: only K's rounding moves it, by under a quarter of a cent (the
probe's ±10% search had moved 14.0 cents to 15.7).

Nine zones, single thread, on this cloud machine: BASS 9.3 s, BRASS 6.5 s,
LEAD 5.3 s (JIT warm-up included).

## Decisions

1. **Range and zones.** Nine zones every minor third across the voice's own
   TUNE range: root, root + 3, …, root + 24 semitones. BASS is A1–A3, BRASS
   A2–A4, LEAD A3–A5. Edge zones reach ±9 semitones past the ends and inner
   zones tile at ±1, exactly `InstrumentSuite.build`'s layout.
2. **Exact pitch through TUNE.** The voice roots are exact A's (55/110/220 Hz,
   `Resin.kt:84–86`) and TUNE snaps to whole semitones. So TUNE =
   semis / 24 renders each zone at true equal temperament, which is how
   `Keys.organ` drives TONEWHEEL.
3. **Held amp.** `Dsp.Env(attackSeconds = ATTACK, decay2T60 = t60,
   holdSeconds = the whole render)`: a linear rise, then flat.
4. **ATTACK is an instrument setting, not a seventh macro.** 0.01–2.5 s,
   exponential (`Dsp.expMap`). The one-shot never uses it, so it lives on the
   MAKE sheet and not in the macro list (six shared macros is a RESIN rule).
5. **RELEASE** is 0.1–1.5 s, exponential, and written as the program's
   `volumeRelease`. The phone reads that as seconds. The ceiling is modest
   because the shop has only ever written 0.3–0.6 and the MPC's reading of
   larger values is unverified (the writer's own KDoc calls it
   "seconds-ish").
6. **CREAM tops out at r = 4.0 when held.** A held render maps CREAM 0..1 onto
   resonance 0..4.0, the threshold itself, not onto 0..4.3. This follows
   CONTOUR's precedent (`Contour.MAX_RESONANCE = 4f`): a knob shouldn't
   promise a sound the mode can't deliver. **Cost:** the held pad never
   whistles on its own; the singing top of RESIN stays a one-shot sound.
7. **Loop start = ATTACK + 1.5 s**, loop end = the end of the sample. This is
   the smallest measured start that clears the bar with margin (14×). Later
   starts measure cleaner but make every zone longer to render.
8. **Detune snapped** when the square is audible (STACK > 0.4); Organ-style
   whole sub-periods near 0.5 s when it isn't.
9. **What the other macros mean held.** STACK, CUTOFF and CREAM are unchanged.
   CONTOUR's sweep plays once at the start of every note (the wah on the
   way in) and has landed before the loop. DECAY only sets how fast that
   sweep lands, since the amp no longer decays. TUNE is ignored because each
   zone sets its own pitch.
10. **Level, measured on the loop.** Each zone is scaled so its *loop*, the
    part you hear while holding, reaches the melodic loudness target, using
    the same `Loudness.of` that `Dsp.levelTo` uses, then `Dsp.limitPeak`.
    Measuring the whole buffer (`levelTo` as-is) would let a slow ATTACK
    drag the reading down by a different amount per zone, since loop
    lengths differ per pitch, and the keys would come out uneven. No
    `Dsp.fadeTail` inside the kept part: the sample ends at the loop end.
11. **The one-shot render stays byte-identical.** Every existing RESIN sound,
    preset, pad in a `kit.json` and determinism test depends on it. This is
    guarded by a hash pinned *before* the change (plan, Task 1).
12. **Packaging** happens in `:shell`, because `:synth` has `:xpm` only as a
    test dependency. `ResinPadMaker` turns nine `KeyNote`s into a
    `KeygroupProgram` and calls `OneNote.writePackage`. That function goes
    from `internal` to public; it is already the one packaging door that
    MAKE INSTRUMENT, MAKE PAD, `keys` and `pad` share.

## Where the user meets it

**Phone: the SYNTH screen, RESIN only.** A full-width `MAKE INSTRUMENT ▸`
button sits under the SCRAMBLE / SAVE PRESET / SEND TO PAD row, shown only
when the engine is RESIN (the same full-width placement the `DELETED
PRESETS ▸` button already uses). It opens a sheet with:

- **NAME**: the current preset's name, otherwise `RESIN BRASS` (the voice),
  made unique with `OneNote.freshName`. It is shown, not typed, in the
  sheet's line (`Copy.heldInstrumentNote`), the way PAD SHEET's MAKE
  INSTRUMENT names without asking. The confirmation toast is the shop's
  existing `Copy.madeNamed("INSTRUMENT", name)`.
- **ATTACK** and **RELEASE** sliders, with readouts in seconds.
- **PREVIEW**: renders the middle zone only (about one render's worth of
  wait, shown as `RENDERING…`, the scope's own busy word) and plays the head
  plus two passes of the loop, which is what a short hold sounds like. You
  hear the pad before paying for all nine zones.
- **MAKE**: renders the nine zones in parallel off the main thread. A
  processing indicator shows throughout: `RENDERING 3/9` beside a progress
  bar that fills as zones finish, so a slow phone reads as working, not
  frozen (`Copy.instrumentRendering`). It writes only after *all* zones
  succeed, then confirms with the shop's `INSTRUMENT MADE — NAME. ON THE
  SHELF.` CANCEL during a render writes nothing; once the last zone lands
  the write is left to finish.

**Desktop: the CLI.** `snipsnap synth RESIN BRASS --preset 3 --instrument
--attack 0.8 --release 0.6 --out <dir>` writes the same package. This is the
audition path before the phone UI exists, and the one this cloud environment
can actually run.

## Failure handling

- **Every zone's seam is checked after the cut** with the test metric. If a
  seam is above 1e-3, the loop start moves 1.5 s later and the zone
  re-renders once. If it is still above, the build fails, naming the zone,
  its pitch and its seam. **No silent crossfade.** The measurements say this
  never fires; it exists because the macro space is continuous and a probe
  is a sample, not a proof.
- **Name collisions** use `freshName`, and `DestinationExists` remains the
  writer's refusal, unchanged.
- **Cancel or failure mid-render** leaves nothing on the shelf, because the
  write happens once, after all zones succeed.

## Testing

The plan pins each of these as a real test:

- **One-shot unchanged:** `Resin.render` at every voice's defaults hashes to
  the value captured before any edit.
- **Every zone lands its pitch:** `Pitch.detect` within 2% (octave-folded) of
  `Keys.midiHz` at both ends of each voice's range (`InstrumentSuiteTest`'s
  `assertPitched`).
- **Seam < 1e-3 on a grid:** 3 voices × 9 zones at defaults, plus the
  measured worst corner (BASS A1, CUTOFF 0, CREAM 1 → r 4.0, CONTOUR 1,
  DECAY 1) at STACK 0.3 / 0.6 / 1.0, and ATTACK 2.5 s.
- **Detune snapped:** the square's ratio is exactly `1 + 1/(2K)`, the loop is
  exactly the rounded length of K sub-periods, and the snapped detune is
  within a quarter cent of the asked one.
- **Whole frames:** in every zone of every voice, the fitted pitch completes
  exactly 2K cycles in the loop's frames, and it moves less than 0.2 cents.
- **Bright zones stay clean:** LEAD at CUTOFF 1, every zone, seams under 1e-8
  (measured 1.8e-15; the fractional-frame residue it guards against
  produced 2e-4 and up).
- **Held means held:** the level over the last loop pass is within 1% of the
  level over the first pass, so nothing pumps.
- **ATTACK is slow:** a 1 s attack is below −6 dB at 0.25 s and within 1 dB of
  full by 1.2 s.
- **CREAM capped when held:** CREAM 1 held renders at r = 4.0 (asserted on the
  resonance the render uses, not inferred from sound).
- **Deterministic:** two renders are byte-identical.
- **Package round trip (`:shell`):** nine zones tile, the roots are the
  minor-third steps, every zone's `loopStartFrame > 0` survives into the
  `.xpm` (`SliceLoop`), the `.xty` (`LoopMode`) and the `.instrument.json`
  sidecar, and `InstrumentStore.list` reads it back.
- **The phone's own player holds it:** `InstrumentEngine` noteOn, render 5 s,
  still sounding at the end; noteOff, and it is silent within RELEASE.
- **CLI:** `--instrument` writes the package; `--instrument` with a
  non-RESIN engine is refused with a reason.
- **App (CI only):** `android-build` compiles the sheet; if a SynthScreen
  on-device test exists, MAKE INSTRUMENT is shown only for RESIN.

## Hardening, round one

What a pass with hostile, extreme and unlucky inputs found and changed:

- **A hand-edited sidecar could hold a note forever.** `release` 1e39 reads
  as a Double but becomes an infinite Float, so `InstrumentEngine`'s release
  step was 1/∞ = 0 and a looped note never let go. `InstrumentStore.read` now
  refuses a release that is non-finite, negative, or too big for a Float, and
  caps a large one at `MAX_RELEASE_SECONDS` (30 s). Both players read
  `playableRelease`, which is finite and in 0..30 s whatever the object
  holds, so an `Instrument` built in code can't do it either.
- **A sidecar could point outside its folder.** A zone's `sample` of
  `../../secret.wav`, an absolute path, a backslashed climb, or a blank name
  is refused, along with zones outside MIDI 0–127, upside-down zones, a
  sample with no frames and a negative loop start. A refused sidecar is
  skipped by the shelf, as a malformed one always was (13 cases pinned).
- **MAKE INSTRUMENT's CANCEL stopped the progress bar, not the renders.**
  Nine zones kept rendering to the end for an instrument nobody would get.
  The held render now asks every 2^15 oversampled samples whether it is
  still wanted, and checks its thread's interrupt flag, the drone's pattern.
  The one-shot path never asks, so `Resin.render`'s pinned hashes are
  unchanged. The seam retry catches `IllegalArgumentException` only, so a
  stop is not retried. PREVIEW stops the same way.
- **A NaN knob failed the verb.** `coerceIn` passes NaN through, so a NaN
  ATTACK fraction reached `resinPad`'s range check as NaN seconds.
  `Knob.value` now answers the knob's default for NaN; ±∞ already clamped
  to the ends.
- **A 300-letter name failed its own write.** File names stop at 255 bytes
  and a name becomes `<name>_[TrackData]` and `<name>_<note>.wav`.
  `OneNote.freshName` now caps a name at 64 characters (`MAX_NAME_LENGTH`),
  trims what the cut leaves dangling, and falls back to `Sample` if nothing
  is left. The app's own names are 14 at most, so only the CLI and a future
  caller could reach this.

**Fuzzed:** 48 seeded random zones (every voice, every macro including its
exact ends, junk keys, ATTACK 0.01–2.5 s, any note in the voice's register). All finite, all
under the 0.99 ceiling, every seam under 1e-10 (the worst measured 1.0e-13),
every loop at least 16 frames and starting at attack + one or two settles.
Loudness was within −4.44..0.00 dB of target; the limiter only ever pulls a
resonant patch down.

**Hostile names:** empty, blank, `..`, `../../escape`, `/etc/passwd`,
unicode, emoji, forbidden characters, 60 and 300 letters. Every one
packages inside `Instruments`, writes nothing beside it, and reads back
from the shelf.

## Out of scope

- Held versions of other engines (VELVET, FATHOM, VOX). Same method, each
  with its own periodicity audit (VELVET's FAT detune has the same beat
  problem; FATHOM's glide doesn't loop at all). One engine first.
- Velocity layers. A pad has one dynamic here; `InstrumentPlayer` still maps
  velocity to gain.
- Stereo. RESIN is mono.
- A regenerate-from-recipe sidecar. `InstrumentStore` has no recipe field;
  adding one is its own change.
- A factory "SnipSnap Resin Pad" in `testkit/Instruments/` for MPC-hardware
  listening. It is cheap once `Keys.resinPad` exists, but it changes
  committed testkit files and `InstrumentSuiteTest`'s count of four, so it
  is a follow-up.
- The drone: [`2026-09-25-resin-drone-design.md`](2026-09-25-resin-drone-design.md).

## Decided (the author's answers, 2026-09-25)

1. **The button is `MAKE INSTRUMENT ▸`.** It matches the PAD SHEET's verb for
   "make something KEYS plays". `MAKE PAD` was avoided because SYNTH already
   has `SEND TO PAD`.
2. **The whistle is given up.** A held pad stops just short of
   self-oscillation (r = 4.0). The alternative, allowing r = 4.3 and refusing
   the zones that don't close, would leave an instrument with holes in it.
3. **Nine zones always, with a progress indicator.** Measured cost is 1.06 s
   of CPU per 4 s zone here, and a phone is likely slower. The zone count is
   *not* reduced if a render runs long. Instead, MAKE shows progress for the
   whole render (`RENDERING 3/9…` beside a bar that fills as zones land),
   so a render that takes longer than 15 s reads as working, not frozen.
   PREVIEW shows `RENDERING…` while its one zone renders.
4. **RELEASE on hardware is a listening check.** Does the MPC read
   `volumeRelease` 1.5 as a 1.5-second release? This is checked by ear on
   real hardware, and the check is added to the testkit acceptance notes.

## Appendix — the probe's held render

The core the plan builds on, as it ran. It is `Resin.synthesize` with a held
envelope and an overridable square ratio:

```kotlin
val detune = ratio3 ?: 2.0.pow(Dsp.lin(stack, 3f, 14f) / 1200.0)   // snapped: 1 + 1/(2K)
val env = Dsp.Env(attackSeconds = attack, decay2T60 = t60, holdSeconds = seconds)
// ... the loop body is Resin.synthesize's, unchanged ...
p1 += base / rate; p2 += base * 0.5 / rate; p3 += base * detune / rate

// K sub-periods holding exactly one beat of the square:
val k0 = (1.0 / (2.0 * (2.0.pow(cents / 1200.0) - 1.0))).roundToInt()
val period = 2.0 * RATE / base            // one sub-octave period, in frames
// (the probe then searched k0 ±10% for the smallest frame residue;
// the implementation instead rounds the loop and fits the pitch to it:
// loopFrames = round(k0 * period); baseHz = 2 * k0 * RATE / loopFrames)
val ratio3 = 1.0 + 1.0 / (2.0 * k0)
```
