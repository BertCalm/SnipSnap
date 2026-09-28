# SIREN — the dub siren engine

**Status:** S12 built and merged (PR #348: engine, presets, tests, SYNTH
picker, landing, testkit kit), **audition gate passed** 2026-09-27: good
across the board on the first listen, SWEEP inaudible and fixed (below),
then confirmed on the second. S12.1 (`→ SURFACE ▸`), S12.2 (SIREN held as a
keys instrument) and S12.3 (the loop-grid drone) are also built. The
listening page for that gate is
`https://claude.ai/artifact/DXEn8DC3ZkcHGzFrTP6VEz`, rendered by
`./gradlew :synth:generateSirenAudition` (the kit as it lands, every knob
at both ends per voice, and the four LOOPs held under a finger with an
echo through Web Audio, a stand-in for the SURFACE); its verdicts save to
the artifact's store under `verdicts/siren_s12_*`. Where the build
departs from the design, and why, is under "As built" at the end. The
SURFACE section is the second pass, written after reading how the surface
actually plays a pad.
**Date:** 2026-09-27
**Plan:** the phasing table below is the plan; no separate plan file yet.
**Roadmap:** the `SYNTH_ROADMAP.md` phasing row is added when implementation
lands, not now.

## Why a siren

Twelve engines and every one of them is a *strike*: a hit, a pluck, a
stab, a note that starts loud and decays. The fleet has no instrument whose
sound **is** a movement of pitch. A dub siren is exactly that: a tone whose
pitch is thrown up and down by a slow oscillator, held under a thumb for as
long as the moment wants, released into a delay. It is the one sound-system
instrument a drum sampler has no substitute for, and it sits naturally next
to what the app already does: sirens land on pads (every dub producer has a
row of them), they run through the rack's ECHO and SPRING, and on the MPC
they are one-shots on a pad, which is how most people play them today.

### The inspiration, and the guardrail

The brief names a maker. Their boxes are the modern reference for the
sound: a hand-built analogue tone generator with a momentary button, a
switch for the modulator's shape, two knobs for how fast and how far it
moves, and an echo you cannot turn off. That is the *architecture*; it is
the same architecture the DIY siren circuits of the 1970s and 80s shared
(a square-wave tone chip, a second oscillator wobbling its pitch), and
none of it is anyone's to own. What is theirs is the name, and the rule
from `SYNTH_ROADMAP.md` applies as written: **sound yes, names never.** No
product surface (engine, voice, preset, description, toast, commit) names
the maker or a model. This document may, because it is naming the rule.

`PresetTestSupport.trademarkBlocklist` knows drum-machine, keyboard and
West Coast makers. Implementation adds this style's own: `benidub`, and
model names as they come up in review.

### Against the fleet

| Engine or effect | Why SIREN is not it |
|---|---|
| **FATHOM** (GLIDE) | A pitch envelope travelling *once* between two notes. A siren's pitch travels *repeatedly*, and the repetition is the instrument. |
| **TIDE** (WANDER) | Seeded, smooth, random nudges to timbre, never pitch, and never periodic. A siren is periodic by definition and moves nothing but pitch. |
| **VELVET / RESIN / WOBBLE** | All sweep a *filter*, pitch fixed. WOBBLE is the nearest relative — a tempo-locked LFO baked onto a hit — and it proves the app is happy with baked motion. Pitch is the axis it does not have. |
| **VOX** (vibrato) | Vibrato is a pitch LFO, but one deliberately small (a few cents) and fast. A siren's LFO goes two octaves at a third of a hertz. Same mechanism, opposite scale, and VOX's belongs to its throat. |
| **ECHO** (the rack) | The siren's onboard delay *is* the rack's ECHO: one line, one filter, repeats darkening. So the engine renders dry and the rack does what it already does — the CRUNCH rule, not a second delay. |

So SIREN is worth building for exactly one thing none of that does: **a
periodic, shaped, deep modulation of pitch, on a tone made for it**, with
the gesture (a sweep into the note when the button goes down) that turns a
wobble into a siren.

### The name

SIREN is a generic word for a generic device, so it passes the guardrail
on its face. KLAXON and WAIL were the runners-up; KLAXON is the sound of one
specific voice, and WAIL is better spent on that voice's name.

## What a dub siren is, in the circuit's own terms

```
LFO (shape · RATE · DEPTH) ──▶ pitch of ──▶ TONE (square, GRIT) ──▶ AMP (gate) ──▶ [ECHO, in the rack]
                                 ▲
SWEEP: a one-shot pitch envelope when the button goes down (rising in, or falling in)
```

Three oscillator shapes on the modulator are the whole vocabulary of the
genre, and each one is a sound people recognise by name:

- **triangle** — the slow rise-and-fall, the air-raid wail;
- **square** — two alternating pitches, the two-tone trill (police, phone);
- **ramp** — a pitch that falls and snaps back (the laser), or rises and
  snaps back (the bird).

The tone underneath is nearly always a square wave through something that
takes its edge off. That is what makes it cut through a sound system, and
it is why the engine's tone is a pulse into a one-pole low-pass, not a sine.

## The synthesis

Per output sample at `Dsp.RATE * Dsp.OVERSAMPLE` (the U6 contract), decimated
at the end like every engine:

```
lfo(t)     = shape(φ_lfo)                        // -1..1, φ_lfo advancing at RATE
sweep(t)   = ±SWEEP_OCTAVES · (1 − t/T)²         // the button-press gesture: a glide of T seconds
hz(t)      = f0 · 2^( DEPTH_SEMIS/12 · lfo(t) + sweep(t) )
tone       = pulse(φ_tone) → drive(GRIT) → onePole(lp, toneHz(GRIT))
out        = amp(t) · tone
```

Four properties to respect, each with a consequence:

**1. The LFO modulates in the log domain.** `hz = f0 · 2^(x)` rather than
`f0 + k·x`, so a triangle LFO sounds like an even rise and fall to the ear
(which hears intervals, not hertz), and DEPTH reads as semitones. A linear
LFO spends most of its travel in the top of its range and sounds lopsided.

**2. The LFO starts at a fixed phase.** Every render begins at the bottom of
the triangle, the low half of the square, and the top of a ramp. A siren
that starts mid-swing sounds like a sample cut from the middle of one, and
the regenerate-from-`kit.json` promise needs the phase to be a constant,
not a seed.

**3. The pulse's phase accumulator is continuous while the pitch moves.**
Pitch changes per sample by changing the phase *step*, never by resetting
the phase, so there are no clicks however fast RATE goes. This is the same
rule FATHOM's GLIDE follows.

**4. GRIT is drive and tone together.** At 0 the pulse goes through a low
one-pole and the drive is off: soft, nearly a triangle. At 1 the low-pass
is open and `Dsp.drive` is on: the raw, cheap-chip edge. One knob, because
"harder" and "brighter" are the same wish on a siren.

### The amplitude envelope

A siren is *gated*, not struck: full level the moment the button is down,
no thwack, and it stops when the thumb lifts. `Dsp.Env(attack = 5 ms,
hold = HOLD, decay2T60 = RELEASE_T60)` with a fixed short release
(~60 ms) so the cut is clean but not a click. The macro is therefore
called HOLD, not DECAY: it is how long the button is down. Nothing in
`SynthScreen` special-cases the name DECAY (checked), so the honest name
costs nothing.

## Voices

A voice **is** the LFO's shape, the way a GLINT voice is a window. That is
the whole of the enum's meaning, and it is the switch on the front of every
siren box ever built.

```kotlin
enum class SirenVoice { WAIL, TRILL, LASER, BIRD }
```

| Voice | LFO shape | The sound | Default RATE | Default DEPTH |
|---|---|---|---|---|
| **WAIL** | triangle | the slow air-raid rise and fall | 0.5 Hz | 12 semitones |
| **TRILL** | square (smoothed over ~3 ms so the jump is not a click) | two-tone alarm; fast, it is the classic "police" | 6 Hz | 5 semitones |
| **LASER** | falling ramp | a pitch that dives and snaps back up; fast, it is the ray gun | 8 Hz | 18 semitones |
| **BIRD** | rising ramp | the mirror: climbs and snaps back down; the chirp | 10 Hz | 14 semitones |

Four rather than three because LASER and BIRD are the same ramp mirrored
and sound nothing alike; a DIRECTION macro would have spent a knob on a
switch.

## Macros

Six, the top of the roadmap's 3–6 budget (precedented: GLINT, RESIN BASS).
Plain words and bounded ranges (playability rules 2 and 3).

| Macro | Moves | Range / mapping |
|---|---|---|
| **TUNE** | the centre pitch | 24 semitones from the voice's root (C4), snapped — settled: snapping is what lets SPREAD and in-key work put a siren in the tune's key, and the rack's PITCH can detune it afterwards |
| **RATE** | the LFO's speed | 0.25 Hz → 25 Hz, `Dsp.expMap` (the ear hears rate by ratio too) |
| **DEPTH** | how far the pitch travels each way | 0 → 24 semitones, linear; 0 is a plain tone, which is a legitimate siren too |
| **SWEEP** | the button-press gesture | bipolar around 0.5: below centre the note *falls in* from up to two octaves above; above centre it *rises in* from two below; the centre is no sweep. A glide with a length, a quadratic ease-out that lands with no corner: 0.25 s for a small sweep up to 1 s for the full two octaves (further is longer, like a portamento), and never past 85% of HOLD, so a short press still lands |
| **GRIT** | drive + tone on the pulse | one-pole cutoff 1.2 kHz → 12 kHz and `Dsp.drive` 0 → 0.8 together |
| **HOLD** | how long the button is down | 0.3 s → 4 s, `Dsp.expMap`, up to the knob's last step; **the top of the knob is LOOP** — see "Living on the SURFACE" |

SCRAMBLE uses `Dsp.scrambleNear` around the voice's defaults, so a dice
roll stays a siren. SCRAMBLE never lands on LOOP: the top detent is a
choice, not a roll.

## The hold problem — where the button lives

A siren is a *held* instrument. Everything in `:synth` renders a fixed
length. There are four doors, and they are not exclusive. **Settled:**
doors 1 and 2 ship together in S12; 3 and 4 are later phases, gated on
hearing the first two.

**Door 1 — a one-shot with HOLD.** Ships with the engine. It is what most
MPC users actually play: a siren sampled at a few lengths across a row of
pads, retriggered by hand. The SYNTH screen's re-render-and-retrigger loop
already gives the instant feedback the playability rules ask for, and
SEND TO PAD lands it like anything else.

**Door 2 — the SURFACE.** The screen that already holds a looping sample
under a finger with a gate, pitch on an axis and a delay behind it. What
it needs from the engine is a loop that closes, and that is the whole of
the next section.

**Door 3 — MAKE INSTRUMENT, held.** RESIN's held-note path renders a loop
of whole periods with the pitch fitted so the wrap is exact
(`Keys.planLoop`). The LOOP render below is that same loop, so this door
is mostly plumbing once S12 exists: a siren holds on the keys' pads for as
long as a key is down and releases on lift, through the keygroup's
existing sustain loop.

**Door 4 — DRONE TO LOOP.** A siren that runs for the whole of a loop-grid
bar, with RATE snapped to divisions of the bar so the wail lands on the
grid (WOBBLE's `DIVISIONS`), re-rendered on tempo change like RESIN's
drone. The dub-mix move (a siren riding across the drop).

Not proposed: a new native live voice. Door 2 already holds a siren under
a finger with an echo behind it, and `LiveSnapEngine` shows what a
continuous voice costs to build and keep threaded correctly. The one thing
door 2 cannot do is bend pitch and wail speed *independently* while
holding (below). If that turns out to matter, it is the reason to build a
live voice, and not before.

## Living on the SURFACE

Read from `SurfaceScreen`, `SurfaceEngine.cpp`, `SurfaceStore` and
`TouchSurface` rather than from memory. What the surface does with a pad,
and what each fact means for a siren:

**The surface plays a pad's WAV whole, end to end, and wraps it with no
crossfade.** `readSlot` reads the sample with linear interpolation and
wraps the phase at the last frame straight back to the first. Whatever
the WAV contains is what loops. So a one-shot siren with a SWEEP dive at
its head and a release at its tail would replay the dive at every wrap
and dip at every seam. Playable, but it ticks.

**A touch-down restarts the loop from its head; lift closes a 3 ms gain
glide.** So each press starts the siren where the render starts (rule 2
above, the LFO's fixed phase), and the release is the surface's, not the
render's. The echo and spring are fed the *gated* signal, so the tail
rings on after the lift — the siren-box release, for free.

**X is pitch, ±1 octave, by resampling the whole loop.** Pitch up an
octave plays the loop twice as fast, so the wail's *speed* follows the
finger along with its pitch — a tape's behaviour, and a musical one, but
worth saying plainly: on the surface, RATE and TUNE are coupled by the
finger. KEY snaps X to the kit's key. Y is the surface's own low-pass,
tilt its resonance, a pinch (XYZ) its drive. LATCH holds the siren with
no finger down. GRAIN mode over a siren pad is a cloud of siren grains,
which nobody asked for and everybody will try.

**The surface's echo is a corner, not a rack.** In XY and XYZ the echo
mix is 0 by construction (`Corner.from`); it lives in MORPH and VECTOR
corners (the library's ECHO + / ECHO − are two), and the ECHO button locks
the delay's time to a division of the bar. Two consequences:

- A siren meant for the surface must land **dry**. A baked ECHO tail in
  a loop smears across the seam and doubles up under the surface's own
  delay. So "echo on by landing" (settled, question 4) applies to
  *one-shot* sirens only; a LOOP siren lands dry and its echo is the
  surface's, and the landing toast says exactly that.
- The genre's siren-into-echo on the surface is: MORPH or VECTOR mode
  with an ECHO corner armed, ECHO on the SET row locked to the bar. That
  is a *setting*, not code, and the toast can name it.

**PAD ◄ ► picks the pad; the choice lives in `surface.json`.** A landing
does not have to write that file — the toast names the pad, PAD ◄ ► gets
there — and S12 does not, because rewriting the player's surface choice
on every SEND TO PAD would be a surprise. A `→ SURFACE` action that lands
*and* opens the surface on that pad is a small later step (S12.1 below)
needing one App-level callback and one `SurfaceStore.save`.

### The LOOP render — HOLD at the top

HOLD's last step is not the longest hold; it is **LOOP**: the render is
one seamless loop for the surface (and, later, the keys). The recipe:

1. **Whole periods.** `L` frames = `k` LFO periods at RATE, with `k` the
   fewest that make `L` at least two seconds (so the WAV is also a usable
   one-shot on a pad and on the MPC), rounded to whole frames; the LFO's
   rate moves by the rounding, well under 0.1 %.
2. **Whole cycles.** The centre pitch is nudged so the pulse completes a
   whole number of cycles over `L`: the cycles per loop are
   `f0 · L / rate · mean(2^(DEPTH·lfo))`, rounded to an integer and solved
   back for `f0` — `Keys.planLoop`'s own fraction-of-a-cent trick. Under a
   cent at every setting; the snapped TUNE is not audibly moved.
3. **Steady state.** One extra period is rendered first and discarded, so
   the one-pole and the drive are in steady state at both ends of the loop.
4. **Cut at a crossing.** The loop is cut at the first zero crossing of the
   filtered tone at the start of period two, and one `L` later — the same
   point by construction. So the WAV starts and ends at silence: the
   surface's wrap is seamless *and* the one-shot ends without a click on a
   pad (`PadEngine` has no end window on a one-shot) or on the MPC (which
   plays the WAV raw).
5. **No SWEEP, no release, no `fadeTail`.** The finger is the sweep, the
   gate is the release, and a fade would be a dip at every wrap.

The SYNTH screen shows a 0..1 slider like every other macro; the readout
for the top step says LOOP, the way GLINT's PEAK readout says the snapped
harmonic. SCRAMBLE stops one step short.

## It already works with what shipped

- **The rack:** ECHO after a WAIL is the genre. SPRING after that is the
  rest of it. TAPE's wow on a slow siren is a bonus nobody asked for.
- **SPREAD / in-key:** TUNE snapped to semitones keeps a siren in the tune's
  key — the one thing a hardware siren box cannot do — and SPREAD across a
  bank gives a row of sirens in a scale.
- **The CLI:** `snipsnap synth SIREN WAIL --all --out <dir>` renders every
  preset once the `Presets` branch exists, so the roster can be heard
  without a phone.
- **GROOVE / SHUFFLE:** a siren on a pad is audited by the classifier like
  any other; a 0.3 s TRILL reads TONAL, a 3 s WAIL reads LOOP.

## Tests (CI measures the sound, not just the code)

- **Pitch centre:** at DEPTH 0, SWEEP centred, every snapped TUNE step
  detects within 5 cents, for every voice. Modulation off, it is a tone.
- **The LFO's rate:** a pitch track (zero-crossing frequency per 10 ms
  window, `TideTest`'s helper) over a WAIL at RATE 0.5 has a period of
  2.0 s ± 5 %; at RATE 1 the period is 40 ms ± 10 %. RATE means hertz.
- **The LFO's depth:** the pitch track's span at DEPTH 0.5 is 12
  semitones ± 1, at DEPTH 1 is 24 ± 1. DEPTH means semitones.
- **The LFO's shape:** WAIL's pitch track is symmetric about its midpoint
  in time (rise time ≈ fall time); LASER's is not (fall ≫ snap-back), and
  BIRD's is LASER's mirror. TRILL spends > 80 % of each period within a
  semitone of one of two pitches.
- **SWEEP's direction:** below centre, the first 50 ms is higher in pitch
  than the last 50 ms of the hold; above centre, lower; at centre, equal
  within 10 cents.
- **No clicks:** at RATE 1, DEPTH 1 and GRIT 0, the largest sample-to-sample
  step is below the largest step of the clean tone's own waveform × 1.5.
  Pitch moves by step, never by reset.
- **Aliasing floor:** at GRIT 1, DEPTH 1 and the top TUNE, energy between
  harmonics stays ≥ 45 dB under the harmonic energy (the TIDE bar).
- **Determinism:** the same recipe renders bit-identical audio; no seed
  anywhere in the engine (the LFO phase is a constant).
- **Loudness:** within the band the other melodic engines hold to
  (`Dsp.MELODIC_LOUDNESS_TARGET`); one-shot bound at
  `maxSecondsFor(voice)`; DC within 0.05.
- **Identity:** each voice's defaults classify as the `SynthScreen` table's
  class (TONAL under 1.5 s), and 200 SCRAMBLEs are audible, unclipped and
  never LOOP.
- **Recipe:** `SirenPatch` round-trips through JSON and `Patches.fromJsonValue`.
- **Names:** the roster passes the blocklist with `benidub` added, and
  `ThumpPresetsTest`'s near-miss and clean-name checks cover the new term.
- **The LOOP render:** at HOLD's top, for every voice and at RATE 0, 0.5
  and 1: the length is whole LFO periods and at least two seconds;
  `Keys.seamError` at the wrap is at floating-point noise; the first and
  last samples are within 1e-3 of zero; the centre pitch is within 1 cent
  of the snapped TUNE; and the render is bit-identical across two calls.

## Phasing

| Step | Ships |
|---|---|
| **S12** | `synth/Siren.kt` (`SirenVoice`, macros, the one-shot render and the LOOP render), `SirenPatch` in `Patches.kt`, `SirenPresets.kt` (8–12 per voice, named for the sound: AIR RAID, TWO TONE, RAY GUN, CHIRP…) and its `Presets` branch, the tests above, `benidub` in the blocklist, and a `SnipSnap Siren Kit` under `testkit/` (`./gradlew :synth:generateSirenKit`). Then the phone: SIREN in the SYNTH picker (… → GLINT → SIREN → THUMP; README's engine count moves up one), the HOLD readout's LOOP step, SEND TO PAD landing a one-shot siren with the rack's ECHO in its recipe and a LOOP siren dry, and a toast for each that says which it did and, for a LOOP, that the SURFACE plays it. **Ends at an audition gate.** |
| **S12.1** | **built** — `→ SURFACE ▸` on SYNTH, shown only while a SIREN's HOLD is at LOOP: the same slot chooser as SEND TO PAD, then `SurfaceStore.choosePad` points the kit's surface at the slot (keeping its corners and the rest) and App switches to the SURFACE screen, which reads the file as it opens. The toast says what to do with a finger. One App callback, one store write. |
| **S12.2** | **built** — Door 3: a SIREN patch as a held keys instrument through `MAKE INSTRUMENT ▸`, the LOOP render doubled into the keygroup's sustain loop (below), RELEASE the only knob. |
| **S12.3** | **built** — Door 4: `DRONE TO LOOP ▸` for SIREN, RATE's own Hz snapped onto the nearest whole cycle count the grid's loop can hold, re-rendered on tempo change. No MOTION or BREATHS knob: RATE and DEPTH already are the patch's own speed and swing. |

## Settled — 2026-09-27

1. **Where the button lives:** pads plus the SURFACE, together, in S12.
   Held keys and the loop grid after the audition.
2. **TUNE snaps to semitones**, like every melodic engine.
3. **The length knob is HOLD**, and its top step is LOOP.
4. **Echo by landing:** a one-shot siren lands with the rack's ECHO in its
   recipe; a LOOP siren lands dry, because the surface's echo is its own,
   and the toast says so.

## As built — 2026-09-27

- **DEPTH is each way.** The spec's test line said "spans 12 semitones at
  DEPTH 0.5"; the knob means 12 semitones *each way*, so the track spans
  24, and the test says so. The macro table was already right.
- **The classifier does not hear a siren as TONAL**, measured across the
  roster: a pitch that moves defeats the pitch detector, so a one-shot
  siren reads LOOP past 1.5 s (the classifier's own length rule) and SNARE
  or PERC under it. `SynthScreen` files a one-shot siren as TONAL *by
  design* — the fallback RESIN, GLINT and VELVET take — so it never lands in
  a hat's choke group; a LOOP siren is filed LOOP, and the classifier agrees
  with that one. `SirenPresetsTest` holds the measurement.
- **The cut is on the tone's own edge, not at silence.** A square wave
  has no gentle crossings: at every crossing one neighbour is up the edge.
  So the loop is cut at the crossing whose two neighbours are smallest, and
  the one-shot's last sample is at most one of the square's own steps —
  which is what "no click" means for a waveform made of steps. The spec's
  "within 1e-3 of zero" was the wrong test; the seam test (the render
  against a fresh stretch across its own wrap, under 1e-3 of peak) is the
  right one, and passes at float noise.
- **The tone is PolyBLEP, and the drive sits after the one-pole.** A naive
  square at 4× would have left the aliasing floor marginal at C6; PolyBLEP
  puts it well under the 45 dB bar with room. And a drive *before* a
  one-pole does nothing to a square (it is already at the rails), so GRIT's
  drive follows the filter and re-sharpens what the filter rounded.
- **One loop rendered, not two.** The stretch is periodic, so the loop from
  the cut is the stretch from there to its end and then from its start to
  the cut. The two-loop stretch exists only for the tests.
- **The LFO de-zipper is 1 ms, not 3.** A one-pole lags a triangle by its
  time constant, and 3 ms cost a 25 Hz triangle 30% of its depth; 1 ms
  costs 10%, and the pulse's phase never jumps anyway, so the smoothing
  only turns a step into a flick.
- **`benidub` is in the blocklist**, with the near-miss and clean-name
  checks extended (`BENIDUB WAIL` refused, `DUB SIREN` and `AIR RAID`
  allowed).

## After the first listen — 2026-09-27

Josh's verdict from the audition page: good across the board, except no
audible difference on SWEEP. The numbers agreed. The first build gave the
sweep an exponential approach whose time constant *shortened* as the
sweep deepened, 0.15 s at full, so a two-octave dive halved every 15 ms
and was over in about 100 ms, under a wail that itself moves an octave
each way. A blip, not a gesture, and the test that passed only asked
whether the first 50 ms sat higher than the last.

The fix: the sweep is a glide with a length. A quadratic ease-out from
the offset to the note, fast off the mark and slowing in, landing exactly
at T with no corner; T runs 0.25 s for a small sweep to 1 s for the full
two octaves (further is longer, the way a portamento is), capped at 85%
of HOLD so a 0.3 s press still lands on its note. `SirenTest` now holds
the gesture to what the ear needs: still six semitones out at 0.3 s, and
landed by 0.9 s. The audition page was re-rendered and republished so the
SWEEP clips can be heard again.

Second listen, same day: "Sweep is great." The gate is passed.

## S12.1 and S12.2, as built — 2026-09-27

- **`→ SURFACE ▸`** reads a flag captured once, at the moment SEND TO PAD is
  pressed — `sendToSurface` — so the send's own async chain can't race a
  macro edit made while it renders. Landing a LOOP with the flag set calls
  `SurfaceStore.choosePad`, which loads the kit's existing `surface.json`,
  changes only `padSlot`, and saves it back, so every corner, GRAIN, SWARM,
  modulator, KEY and echo-time setting the kit already had survives the
  hand-off untouched. `SurfaceStoreTest` holds that.
- **A keygroup layer's loop start of `0` means *no loop*, not *loop from
  frame 0*** (`VelocityLayer`'s own KDoc) — a sentinel RESIN's own held pad
  never has to think about, because its loop never starts at the file's
  first frame (there is always an attack ahead of it). SIREN's LOOP render
  has no attack: the whole file *is* the loop, so pointing the marker at
  frame 0 would misread as "don't loop this note at all." `Keys.sirenPad`
  renders the loop once, then doubles it — two bit-identical copies — and
  points `loopStartFrame` at the second copy's own start: the player's one
  pass through copy one gives way to copy two repeating forever, which is
  exactly what `Siren.renderLoop` alone produces, expressed through a
  marker the format can carry. `SirenHeldTest` (`:synth`) holds the frame
  count, the marker position and the bit-identical halves; `SirenPadMakerTest`
  (`:shell`) holds the export round trip and the phone's own hold/release.
- **No autocorrelation pitch check on the rendered audio, and no
  zero-crossing time-average either.** A held WAIL's own default DEPTH is a
  full swung octave; no single period near the centre repeats often enough
  for a general pitch detector to lock onto, and 2^x's convexity means a
  linear-Hz time-average of a symmetric vibrato reads measurably *sharp* of
  centre (a naive zero-crossing count landed WAIL's root two-plus semitones
  high before this was caught, on a plainly correct render). The musical
  centre a vibrato is heard *around* is the geometric mean of instantaneous
  frequency, which `planLoop`'s own construction fixes to `baseHz` exactly —
  so `SirenHeldTest` checks `Siren.planLoop(...).baseHz` against the target
  MIDI frequency directly, the same formula-level trick `ResinHeldTest`
  already uses for its own "square snapped to one whole beat" check.
- **SIREN's held pad has one knob, not two.** RESIN's ATTACK dials in the
  settle before its loop; SIREN's LOOP render already closes on itself
  exactly; there is nothing to settle, so `SirenPadMaker.Spec` carries only
  RELEASE, and `SynthScreen`'s `HeldInstrumentSheet` hides the ATTACK
  slider for it.
- **The `cancelled` callback reached every layer but the one that renders.**
  Review caught it before the second listen: `HeldSpec.Siren` took
  `cancelled` and dropped it on the floor, so CANCEL on the sheet could not
  stop a render already running — at RATE's floor a single zone is several
  seconds of audio, nine zones deep in parallel. `Siren.synthesizeLoopStretch`
  now asks every 32768 oversampled samples, the same cadence
  `Resin.renderHeld` and `ResinDrone` already use, and `renderLoop` →
  `Keys.sirenPad` → `SirenPadMaker` → `HeldSpec.Siren` each thread it
  through rather than discarding it. `SirenHeldTest` holds the stop and its
  time bound.

## S12.3, as built — 2026-09-27

- **No MOTION or BREATHS to invent.** RESIN's drone needs both because
  RESIN's held macros carry no swing of their own; SIREN already *is* a
  movement (README's own words) — RATE and DEPTH are its speed and swing,
  dialed on the panel above before DRONE TO LOOP is even opened. `SirenDrone.Spec`
  is a voice and the patch's own macros, kept to `SOUNDING_MACROS =
  listOf("RATE", "DEPTH", "GRIT")`; TUNE, HOLD and SWEEP have no note-on to
  act on, the same exclusion RESIN's own CONTOUR and DECAY get.
- **RATE is snapped, not replaced.** Rather than inventing a discrete
  breaths-per-loop knob, `SirenDrone.synthesize` reads RATE's own Hz
  (`Siren.rateHz`) and rounds it to the nearest whole number of cycles the
  grid's own loop can hold (`Math.round(desiredHz * frames / sampleRate)`,
  floored at one) — the same "further is longer" snap `DroneFit` already
  applies to pitch, applied here to speed. The knob keeps its ordinary
  meaning everywhere else; only the drone quantizes it, silently, to
  whatever whole cycle count is closest for the span it lands on.
- **The carrier snaps the way `Siren.planLoop` already does**, run at the
  grid's own loop length instead of `Siren.loopFrames`'s: the LFO's own
  phase integral over one loop (`g`) fixes how many whole carrier cycles
  fit, and `baseHz = cycles / g` lands within a fraction of a cycle of the
  root's true Hz over the whole span — provably exact in the phase domain
  (the accumulated phase over one loop is `cycles`, a whole number, by
  construction), not just approximately so.
- **A one-pole settles fast; the pre-roll doesn't need RESIN's two
  seconds.** RESIN's ladder is a resonant 4-pole that can ring for a
  second near self-oscillation; SIREN's GRIT stage and its LFO de-zipper
  are both single one-poles with sub-millisecond time constants, so 0.1 s
  of pre-roll (a hundred-plus time constants) reaches the periodic steady
  state with room to spare — confirmed by `SirenDroneTest`'s own two-period
  seam measurement landing at float noise, not merely under the bar.
- **`DroneSource` tries RESIN, then SIREN, then falls through to `inner`**,
  so a recipe from a later engine can still wrap this one; the cache key
  never reads which engine matched, only the recipe's own JSON, root,
  frames and rate.

## After review — 2026-09-27

Three findings on the first review, all real, none touching the audio a
finished render actually produces:

- **`DroneFit.spanFor`/`nudgeCents` are not engine-agnostic — they are
  RESIN's own model.** The as-built note above claimed otherwise, and it
  was wrong: `DroneFit`'s formula assumes RESIN's even-only sub-octave
  snap and pure pitch arithmetic, while SIREN's own carrier
  (`SirenDrone.fitCarrier`) permits any whole cycle count and depends on
  DEPTH through the LFO's own phase integral. Reusing `DroneFit` for SIREN
  never broke the tuning promise — SIREN's true achievable nudge is
  provably no worse than what `DroneFit`'s model estimates (its
  even-only constraint is strictly tighter than SIREN needs, and a
  zero-mean swing's phase integral is always at least the loop's plain
  duration, by Jensen's inequality) — but it could choose a longer span
  than necessary, and the readout would not be the number SIREN's own
  render lands on. `SirenDrone` now exposes `fitCarrier` (shared with
  `synthesize`, so the snap is computed once, not twice) and `nudgeCents`;
  `SirenDroneMaker.span`/`label` read those instead of `DroneMaker`'s.
  `SessionBuilder.sendDrone` takes an explicit `span` (default: the old
  RESIN-shaped guess, for RESIN's own callers and the existing tests), and
  `App.sendDroneToLoop` works it out against the session it just loaded,
  dispatching on the recipe's own engine — the one place outside `:synth`
  and `:shell` a SIREN drone's span is chosen, so it has to ask the right
  model too.
- **`Siren.planLoop`'s own warm-up and integral, and `SirenDrone`'s own
  settle and integral, ran before either's `cancelled` was ever asked.**
  At RATE's floor, or across an 8-interval drone's own loop, that
  planning pass is itself seconds of iteration — a render already
  cancelled would still have to wait it out. Both now check the same
  cadence their own audio loop already did (`SirenTest`'s "planLoop itself
  stops...", `SirenDroneTest`'s "the carrier's own settle and integral
  pass stops too...").

## Hardening round — 2026-09-28

A pass over all four doors after #368 merged, looking for what neither
the automated review nor the tests would have caught: malformed/corrupted
recipe JSON, NaN/Infinity propagation through macros, integer and
array-size edges in the drone and held-pad paths, and the new caching and
dispatch logic in `DroneSource`. Most of the surface held on inspection —
`Json`'s own number grammar can produce `Infinity` from an absurd exponent
but never `NaN` (no token for it), and `Infinity` clamps correctly through
`coerceIn`, unlike `NaN`; every render call site already wraps in
`try/catch` with a toast, a pattern `makeHeld`'s own `coroutineScope` (not
raw `appScope.launch`) exists specifically to preserve; the loop-grid's
own BPM/bars bounds keep every frame count `SirenDrone` ever sees safely
inside `Int` range with room to spare; and the native `SurfaceEngine` was
already mono-only, so a mono SIREN LOOP render introduced no new channel
mismatch.

Two real gaps, both about `SirenDroneMaker.span`/`label`'s own cost —
unlike RESIN's closed-form `DroneMaker.label`, SIREN's own reads an LFO
phase integral over the candidate span's own frame count, measured at
~850 ms per voice at the grid's slowest tempo and longest bars
(`SirenDroneMakerTest`'s new "span and label finish quickly even at the
grid's slowest, longest setting"):

- **`SynthScreen`'s DRONE TO LOOP sheet computed its own readout inline in
  the composable body** — synchronous, on the main thread, on every ROOT
  +/- tap. RESIN's own version was always free, so this never showed; it
  now runs through a `LaunchedEffect` off the main thread
  (`Dispatchers.Default`), the same way `droneSession` itself already
  loads off it, with the placeholder shown until it lands.
- **`App.sendDroneToLoop`'s new SIREN-vs-RESIN span dispatch ran unguarded
  inside the write path**, the one place in this whole feature that broke
  the "a render or fit computation never crashes the app, it toasts"
  pattern every other call site holds. No live input was found that makes
  it throw — `rootMidi` is always inside SIREN's own register, the session
  is validated before this runs, and the recipe is the app's own
  freshly-serialized spec — but it now falls back to RESIN's own (proven
  safe, if less precise) estimate on any failure rather than trusting that
  reasoning to hold forever.

## Still open, not blocking

5. **A fifth voice?** A one-shot dive with no LFO at all (SWEEP at full,
   DEPTH 0) is the "bomb", and it is reachable on any voice by the knobs.
   Not adding it unless the roster shows people cannot find it.
6. **Stereo?** A hardware siren is mono. Two detuned pulses would thicken
   it but blur the two-tone TRILL. Mono; the rack's SPRING and PHASE and
   the surface's SWARM widen it.
