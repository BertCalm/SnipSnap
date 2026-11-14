# CAIRN: GRIND and VESSEL

CAIRN had four voices: KNOCK, ANSWER, TUMBLE and SING. Its handoffs named GRIND and VESSEL as
planned voices, with no design. This adds both voices as saved models 6 and 7. In the first
listening round, `cairn-voices-1` (14 Nov 2026), the owner kept VESSEL and asked for a tweak to
GRIND; see [Owner listening](#owner-listening).

- **GRIND** (model 6): a stone dragged across a rough bed. The gesture is push → catch →
  bursts of slipping that bunch up and stall → a last catch and ring.
- **VESSEL** (model 7): a stone dropped into a hollow bowl. The gesture is drop → roll around
  the wall → the circles narrow and speed up → it settles at the bottom with a low hollow hum.

RIME already has a voice called VESSEL. Voice names belong to their engine, so this is allowed.
Round 1 offered BOWL or BASIN instead; the owner kept VESSEL with no rename, so the name stays.

## Sibling check

- **GRIND:** ARCO is bowed stick-slip, but periodic and sustained. SCUFF scrapes drums, with no
  pitched stones. TUMBLE's hits are regular and shrinking, and KNOCK's are clean direct strikes.
  GRIND's slips are irregular, bunch up and stall, and each one carries a scrape.
- **VESSEL:** SING is a sustained rub at one spot. VESSEL's motion audibly orbits and speeds up,
  and its low hollow air resonance is new to CAIRN.

## GRIND: piecewise-exact stick-slip

`CairnGrind.kt`. Literal, normalised mechanics:

- **The drag:** the stone is pulled by a spring whose far end moves at a steady speed. SLOPE sets
  the speed, scaled by velocity, so a harder touch drags faster. The drag lasts 1.0–1.6 s.
- **Sticking:** the spring stretches until its pull beats the bed's grip at that spot. The grip
  varies smoothly along the bed. Seeded notches grip much harder; GROOVE sets how many and how
  deep they are.
- **Slipping:** kinetic friction (FRICTION) opposes the slip. Each slip is solved exactly, as a
  harmonic oscillator about a moving equilibrium, so no stepping error enters the timing.
- **Notches:** a slip that reaches a notch slowly is caught there. A fast slip loses a share of its
  energy to a tick and runs on.
- **Ratchet:** the notches' steep faces take any backward pull, so the stone never slides back.
- **Energy:** the work ledger closes to 1e-9. Driver work in = elastic and kinetic energy +
  friction + catches.

The sound is a one-way readout. Catches strike an eight-mode stone with its own ratio table.
Every slip is also a short band-limited noise burst, as long as the slip itself, at a level set
by the slip's friction power. The burst drives the stone and is also heard directly: that path
goes through a two-pole lowpass at 2.5–5.5 kHz, which FRICTION sets. The burst is the scrape.

## VESSEL: an averaged orbit in a cone

`CairnVessel.kt`. A literal energy law, averaged over each turn, not a 2-D simulation:

- **The orbit:** the stone stays on a nearly circular orbit on a conical wall. SLOPE sets the
  steepness and WEIGHT the stone's size.
- **Energy:** a rolling sphere's energy on that orbit is 1.7·m·g·tan(slope)·r. That counts its
  7/5 rolling kinetic factor plus height.
- **The spiral:** constant rolling resistance (FRICTION) makes √r fall linearly in time. Radius,
  speed and orbit angle all have closed forms.
- **Speeding up:** the angular speed √(g·tan/r) rises as the circles narrow.
- **Bumps:** bumps on the wall (GROOVE: one to five) knock once per pass and take a small share
  of the energy.
- **Settling:** at the flat bottom the stone rolls to rest. The ledger closes to 1e-9.

The readout is a bowl:

- **Wall modes:** paired modes on Rayleigh's free-rim ratios (1, 2.83, 5.42, 8.77). The pairs
  give a real bowl's slow beating.
- **Air resonance:** one low resonance an octave below the note. BODY sets its strength and ring.
- **Rolling drive:** seeded noise drives the bank, darker when the stone rolls slowly and
  swelling once per orbit.
- **Knocks:** knocks and the drop strike the wall.

## Models and old saves

- **New models:** `Cairn.MODEL_VERSION` is 7. GRIND requires model 6 or above and VESSEL model
  7. Both are refused below, in `Cairn.checked` and `CairnPatch.init`.
- **Old voices unchanged:** every older branch is `model >= N` for an older N. The KNOCK/ANSWER
  terrain seed ignores the model, and the new voices have their own solvers and readouts. So
  KNOCK, ANSWER, TUMBLE and SING render byte-identically at models 5, 6 and 7. A test checks this
  at MIDI 48, 60 and 72, HOLD 0, .5 and 1, and with non-default controls.
- **Existing pins:** every existing CAIRN hash pin runs unchanged. That includes the SING motion
  pins, the nine Wave 3 SING anchors and the sampler studies.
- **Test changes:** two earlier tests needed small edits.
  - `CairnSingMotionTest` compares models 4 and 5 for every voice. It now names the four voices
    that existed then, since GRIND and VESSEL refuse model 4.
  - Its new-recipe check now expects `Cairn.MODEL_VERSION` rather than the literal 5.

## HOLD

At HOLD .99 or above, both voices render a true two-second loop.

- **GRIND:** the spring is dragged around a circular bed whose lap takes exactly two seconds. One
  deep notch per lap catches every stone that reaches it, so each lap restarts from the same
  caught state. The motion repeats exactly from the third lap, and the render keeps that lap
  after its real predecessor.
- **VESSEL:** a small drive keeps the stone on a steady orbit with a whole number of turns in two
  seconds. It tops up exactly what rolling and the knocks take.
- **Seam:** the bank's periodic starting state is solved for both. The seam is checked against the
  house limit on every held render.

## Verification (`CairnGrindVesselTest`)

- **Old voices:** byte-identical at models 5, 6 and 7.
- **Versioning:** gating, plus JSON save and replay for both new voices.
- **GRIND ledger:** the work ledger closes, and the stone never ends with more energy than the drag
  supplied. Checked across extreme controls, three velocities and HOLD.
- **GRIND irregularity:** the slips are irregular. The spread of log interval ratios is above 0.3,
  where TUMBLE's flights contract steadily (below 0.1).
- **Pitch:** both voices stay within 12 cents of the note at MIDI 48, 60 and 72.
- **Loops:** both lock to a closed two-second loop across GROOVE, SLOPE and WEIGHT extremes.
- **VESSEL orbit:** the radius more than halves, the orbit speeds up by over 1.4×, and the knocks
  quicken.
- **VESSEL air resonance:** its share at BODY .9 is over 1.5× its share at BODY .1.
- **Silence and determinism:** both are silent at zero velocity and reproduce from a cold motion
  cache. `DeterminismTest`'s CAIRN canary covers all six voices.

## Verified / measured / proposed / heard

- **Verified:** the tests above, on this branch.
- **Measured** (the Kotlin render log, default controls at C4, round 1):
  - GRIND at full velocity: 33 events (22 slips, 9 catches, 2 ticks) over a 1.75 s note.
  - VESSEL: 10 events over a 4.1 s note; its orbit rises from 1.4 Hz to 3.7 Hz.
  - Both held loops close with a measured seam of 0.
  - Each render takes 30–190 ms.
- **Proposed:** every constant (spring rate, grip levels, bowl shape, gains) is a design shape,
  not measured stone, bed or bowl data.
- **Heard:** the owner's verdicts below.

## Owner listening

**Round `cairn-voices-1` (14 Nov 2026)**

- **VESSEL, V1–V4: KEEP.** No rename was asked for. `CairnGrindVesselTest` pins every render
  those four clips were built from (eleven float SHA-256s: three drops, the deep bowl and small
  stone, the loop, and the five phrase notes). The clips were rebuilt from those renders to
  within one 16-bit step, so VESSEL cannot move without the test failing.
- **GRIND, G1–G4: TWEAK.** The notes: "The percussive top is a bit overpowering" (G1) and
  "Too static-y sounding on top" (G3). G4's note was left unfinished.

**What changed for round `cairn-voices-2`**

- **Diagnosis:** the stone's modes carried almost nothing above 2 kHz. The top came from the
  scrape heard directly: round 1 sent each slip's noise burst to the output through the
  same one-pole lowpass that drives the stone (about 5 kHz at the default FRICTION). That
  left 13% of the energy above 4 kHz and 6% above 8 kHz, as hiss on every slip.
- **The fix:** the directly heard scrape now has its own two-pole lowpass, at 2.5 kHz plus
  3 kHz × FRICTION (3.85 kHz at the default), and a level of 0.012, up from 0.008. The force
  that drives the stone, the motion, the catches and the loop are unchanged.
- **Measured** (round-1 clip → round-2 clip, the same shapes):

  | Clip | Energy above 4 kHz | Above 8 kHz | 2–4 kHz (grit) | Brightness (centroid) |
  | --- | --- | --- | --- | --- |
  | Three drags (G1 → G5) | 12.8% → 2.3% | 5.9% → 0.2% | 5.9% → 6.1% | 1,580 → 669 Hz |
  | Slow, then coarse (G2 → G6) | 12.7% → 2.4% | 5.8% → 0.2% | 6.4% → 7.0% | 1,580 → 696 Hz |
  | Loop (G3 → G7) | 12.9% → 2.2% | 5.8% → 0.2% | 6.1% → 6.0% | 1,607 → 683 Hz |
  | Phrase (G4 → G8) | 14.3% → 2.6% | 6.5% → 0.2% | 7.0% → 7.4% | 1,756 → 730 Hz |

- **Gentler option (G9):** the same filter at level 0.008 gives 1.2% above 4 kHz and 3.3% grit
  (centroid 496 Hz). It is offered beside G5 in case the owner wants less scrape still. It is
  not in the code; choosing it is a one-constant change (`CairnGrind.DIRECT_GAIN`).
- **Not changed:** the transient crest stays within about 1.5 dB of round 1. The catches,
  which carry the peaks, were not the bright part.
- **Test:** `GRIND's heard scrape keeps its grit without the hiss` holds GRIND below 5% above
  4 kHz and 1% above 8 kHz, above 2% at 2–4 kHz, at MIDI 48, 60 and 72 and three velocities.
  It also checks that FRICTION still brightens the scrape.
- **Unchanged voices:** KNOCK, ANSWER, TUMBLE, SING and VESSEL are byte-identical; the tests
  above prove it.

## Still open

- **Presets:** CAIRN has no factory presets and no SYNTH picker entry; those are a later PR.
- **GRIND velocity:** velocity changes the drag's speed and the strike width. The output is still
  levelled to the house loudness, as for the other CAIRN voices.
- **Velocity layers and round robin:** none yet.
- **Devices:** no phone or MPC run.

## Why it is built this way

- **GRIND is solved exactly between events.** Stick-slip timing is the sound. A fixed-step
  solver would quantise every release to the step and smear the bunching that makes it a
  grind and not a buzz.
- **The bed is irregular on purpose.** A uniform bed gives a steady squeak, which is ARCO's
  territory. The seeded roughness and notches are what make the slips bunch up and stall.
- **The heard scrape has its own, darker filter.** Round 1 used the stone's drive filter for
  it too, and the owner heard the result as static. Splitting the two keeps the stone's
  excitation (and so its tone) as it was and takes only the hiss away.
- **The scrape lasts exactly as long as the slip.** Each noise burst is as long as the slip that
  makes it, at a level set by its friction power. A longer, harder slip is heard as a longer,
  louder scrape, with no separate envelope to tune.
- **VESSEL uses an averaged orbit, not a 2-D ball simulation.** The closed form keeps a knob
  turn to tens of milliseconds and makes the speed-up exact. The cost is that the orbit
  is always nearly circular: it never wobbles or bounces across the bowl.
- **The risky choice is the air resonance an octave below the note.** It keeps VESSEL in
  tune and gives the hollow hum, but on low notes it may read as boomy. Round
  `cairn-voices-1` asked about it directly (V2, V4), and the owner kept both.
