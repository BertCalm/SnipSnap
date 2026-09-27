# SIREN — the dub siren engine

**Status:** workshop draft, 2026-09-27. Not approved, not implemented. The
open questions at the end are the ones that decide the shape; nothing
below is settled until they are answered in conversation.
**Date:** 2026-09-27
**Plan:** none yet. Written after approval, the rule GLINT and RESIN followed.
**Roadmap:** the `SYNTH_ROADMAP.md` phasing row is added when implementation
starts, not now.

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
sweep(t)   = ±SWEEP_OCTAVES · envAt(t, sweepT60)  // the button-press gesture, one-shot
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
hold = DECAY, decay2T60 = RELEASE_T60)` with a fixed short release
(~60 ms) so the cut is clean but not a click. DECAY is therefore a *hold
time*, and the macro should probably say so (see the open questions).

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
| **TUNE** | the centre pitch | 24 semitones from the voice's root (C4), snapped, like every melodic engine — *see open question 2* |
| **RATE** | the LFO's speed | 0.25 Hz → 25 Hz, `Dsp.expMap` (the ear hears rate by ratio too) |
| **DEPTH** | how far the pitch travels each way | 0 → 24 semitones, linear; 0 is a plain tone, which is a legitimate siren too |
| **SWEEP** | the button-press gesture | bipolar around 0.5 (`Dsp.around`): below centre the note *falls in* from up to two octaves above; above centre it *rises in* from two below; the centre is no sweep. The sweep's t60 shortens as it deepens (a big dive is a fast one), 0.6 s → 0.15 s |
| **GRIT** | drive + tone on the pulse | one-pole cutoff 1.2 kHz → 12 kHz and `Dsp.drive` 0 → 0.8 together |
| **DECAY** | how long the button is held | 0.3 s → 4 s, `Dsp.expMap`; the one-shot promise's own budget, and above 1.5 s the classifier reads a LOOP — *see open question 3* |

SCRAMBLE uses `Dsp.scrambleNear` around the voice's defaults, so a dice
roll stays a siren.

## The hold problem — where the button lives

This is the design question that matters, and the reason the brief says
"workshop". A siren is a *held* instrument. Everything in `:synth` renders
a fixed length. There are four doors, and they are not exclusive:

**Door 1 — a one-shot with DECAY.** Ships with the engine. It is what most
MPC users actually play: a siren sampled at a few lengths across a row of
pads, retriggered by hand. The SYNTH screen's re-render-and-retrigger loop
already gives the instant feedback the playability rules ask for, and
SEND TO PAD lands it like anything else.

**Door 2 — the SURFACE, for free.** `SurfaceEngine` already plays one
looping sample under a finger, with pitch on an axis, a gate on
touch-down, and ECHO and SPRING in its chain. A siren rendered with
DECAY at full and its LFO closed on a whole number of cycles is a loop
the surface can hold indefinitely, pitched by the finger, released into
the echo on lift. That *is* the siren-box experience — thumb down, thumb
up, the echo carries it — and it costs no native code. Implementation is
a landing that says so: SEND TO PAD's toast for a SIREN pad names the
SURFACE, the way a photo kit's landing toast explains its own layout.

**Door 3 — MAKE INSTRUMENT, held.** RESIN's held-note path renders a loop
of whole periods with the pitch fitted so the wrap is exact
(`Keys.planLoop`). A siren's loop is one LFO period: the LFO closes by
construction, and the pulse closes if the *total* phase it accumulates over
that period is a whole number of cycles — the same fraction-of-a-cent
adjustment `planLoop` already makes, applied to the centre pitch. Then a
siren holds on the keys' pads for as long as a key is down and releases on
lift, through the keygroup's existing sustain loop. This is the truest
"held siren" on a pad and it is a second phase, gated on hearing door 1.

**Door 4 — DRONE TO LOOP.** A siren that runs for the whole of a loop-grid
bar, with RATE snapped to divisions of the bar so the wail lands on the
grid (WOBBLE's `DIVISIONS`), re-rendered on tempo change like RESIN's
drone. The dub-mix move (a siren riding across the drop) and a natural
third phase. Not proposed until doors 1 and 2 have been heard.

Not proposed at all: a new native live voice. Door 2 already holds a siren
under a finger with an echo behind it, and `LiveSnapEngine` shows what a
continuous voice costs to build and keep threaded correctly. If the
surface turns out not to *feel* like a siren box, that is the moment to
revisit it, with a reason.

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
  class (TONAL under 1.5 s), and 200 SCRAMBLEs are audible and unclipped.
- **Recipe:** `SirenPatch` round-trips through JSON and `Patches.fromJsonValue`.
- **Names:** the roster passes the blocklist with `benidub` added, and
  `ThumpPresetsTest`'s near-miss and clean-name checks cover the new term.
- **Door 2's loop (if taken):** at DECAY 1 the render is a whole number of
  LFO periods and `Keys.seamError` at the wrap is at floating-point noise.

## Phasing

| Step | Ships |
|---|---|
| **S12** | `synth/Siren.kt` (`SirenVoice`, macros, render), `SirenPatch` in `Patches.kt`, `SirenPresets.kt` (8–12 per voice, named for the sound: AIR RAID, TWO TONE, RAY GUN, CHIRP…) and its `Presets` branch, the tests above, SIREN in the SYNTH picker (… → GLINT → SIREN → THUMP; README's engine count moves up one), `benidub` in the blocklist, and a `SnipSnap Siren Kit` under `testkit/` (`./gradlew :synth:generateSirenKit`). **Ends at an audition gate.** |
| **S12 (door 2)** | The SIREN landing toast names the SURFACE, and DECAY at full renders whole LFO periods so the surface's loop has no seam. Cheap enough to ship with S12 if the audition says the surface feels right. |
| **S12.1** | Door 3: a SIREN patch as a held keys instrument through `MAKE INSTRUMENT ▸`, the centre pitch fitted so one LFO period is a seamless loop. |
| **S12.2** | Door 4: `DRONE TO LOOP ▸` for SIREN, RATE snapped to bar divisions, re-rendered on tempo change. |

## Open questions — the ones that shape the build

1. **Which hold matters most?** Door 1 (one-shots on pads) is the cheapest
   and lands in the MPC workflow; door 2 (the SURFACE) is the closest to a
   siren box in the hand and nearly free; door 3 (held on keys) is the
   truest but a second phase. The recommendation is 1 + 2 together, then
   listen. Is that the order?
2. **Snapped TUNE or free?** Every melodic engine snaps to semitones, and
   snapping is what lets SPREAD and in-key work put a siren *in the tune's
   key*. But a hardware siren's pitch knob is free, and part of the dub
   move is a siren that is deliberately *not* in key. Recommendation: snap,
   because the fleet does and the rack can detune afterwards.
3. **DECAY, or HOLD?** The knob is a hold time on a gated voice, not a decay
   on a struck one. Every engine calls its length knob DECAY, and nothing in
   `SynthScreen` special-cases the name (checked: the screen renders whatever
   `macrosFor` lists). A siren that says HOLD is more honest and costs
   nothing. Recommendation: HOLD.
4. **Echo by default?** A siren without echo is half a siren, but presets
   are `Patch` lists and carry no `FxChain`. Options: (a) SEND TO PAD for a
   SIREN lands with the rack's ECHO at a preset setting, the way factory
   kits carry `melodicMotion`; (b) nothing special, the rack is a tap away.
   Recommendation: (a), with the toast saying so.
5. **A fifth voice?** A one-shot dive with no LFO at all (SWEEP at full,
   DEPTH 0) is the "bomb", and it is reachable on any voice by the knobs.
   Not adding it unless the roster shows people cannot find it.
6. **Stereo?** A hardware siren is mono. Two detuned pulses (FATHOM's
   SPREAD idea) would thicken it, but would also blur the two-tone TRILL.
   Recommendation: mono, and let the rack's SPRING and PHASE widen it.
