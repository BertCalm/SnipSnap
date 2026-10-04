# FLOTILLA — pitched emitters on a pool: as built

**Status:** R1 built, not yet listened to. **Date:** 2026-10-04.
**Name:** provisional. Naming availability was not checked.

Six voices (RIPPLE, KNOCK, HOLLOW, CROSSWAVE, DRIFT, GATHER) and seven
macros (PULSE, CROSSING, FLOTILLA, VESSEL, SURFACE, SKIN, HOLD). Pitch is
MIDI 36–84, default 60, stored on the patch beside velocity and
`model: 1`. It is not an eighth knob.

## Round 0

The screen's macro list is `for (spec in macroSpecs)`. There is no cap of
five or six. MERCURY already ships seven knobs, and TIDE does on some
voices. SKIN stays a macro. FLOTILLA is a new value on the screen's private
engine enum, after FORK, with the same slider column. The Android picker
is compiled by `android-build`; this session has no SDK.

Standard voices are not drum names. The classifier's length rule files a
pad LOOP past 1.5 s, which is the honest class for a note that rings. The
tests refuse KICK, SNARE, CLAP, HAT_CLOSED, HAT_OPEN and TOM.

## What is actually simulated

Two clocks. The pool is eight damped spatial modes, four diametric pairs
at 0°, 45°, 90° and 135°, stepped at 210 Hz so a step is 210 audio
samples. The heard signal is 44.1 kHz mono: an additive band-limited
source (the note, not the ripple rate), wood and cavity resonators that
fire only when a contact happens, sparse laps, four dome modes, and four
early-reflection taps. The contact kernel is formed at 4×, band-limited
with `Tide.bandLimit`, decimated, DC-removed and cached. The resonators
are linear IIRs at 44.1 kHz, under Nyquist, so the long buffer is not
rendered at 4×. Their poles sit near the unit circle, so the wet bus is
scaled to a share of the source's energy (about 0.16 to 0.40). Without
that, KNOCK's wood took the pitch detector about 145 cents sharp. With
it, KNOCK, DRIFT and HOLLOW at C4 stay inside 15 cents, and RIPPLE at
C2, C4 and C6 stays inside 12.

Vessel count is `6 + FLOTILLA * 12`, rounded up, six to eighteen, with the
last hull only partly engaged. Placement is a golden-angle spiral relaxed
for twenty iterations. Geometry is seeded from the voice, the note, VESSEL
and FLOTILLA. HOLD and velocity are not in that seed, so moving them does
not rearrange the pool.

PULSE tilts the source harmonics and steepens the onset. CROSSING fades
route pairs in with a smoothstep; pair 0 is always on. SURFACE sets modal
frequency, damping and how hard the field pushes the hulls. VESSEL scales
radius, mass and cavity pitch. SKIN is the dome and a small reflection
gain. Velocity is a render argument (energy, steepness, motion), not a
scaled brightness macro.

## Approximations, stated as approximations

Vessel reaction on the pool is extra nonnegative modal damping where a hull
covers a mode. That changes how fast a dense pool settles. It is not a
reciprocal fluid force: hull acceleration is not fed back as inertia, and
the dome does not drive the pool.

The coupling into the water is exaggerated on purpose. Airborne sound at
these levels would not produce these motions.

The hide is a warm membrane. It is not a kangaroo-hide acoustic model.

HOLD at or above 0.85 does not integrate the hulls until they repeat. A
free vessel does not have to come home. The held note is a stationary,
phase-locked pattern: a periodic source, contacts keyed to the route
field, filters warmed until the seam is under `Keys.MAX_SEAM_ERROR`
(1e-3), then cut on that period. The public snip is that period played
twice, levelled, and not faded. The measured equal-power overlap gain is
recorded and not applied: the seam already passes, so a crossfade would
only hide a join that is already closed. Passing the seam is not evidence
that the vessels returned to their start.

The absolute period-to-period peak difference on a held DRIFT plateaus
near 0.02 against an internal peak near 350 (relative residual about
6e-5). The seam energy, which compares the tail of one period with the
next, is about 1e-9. Copying the tail into the preroll would make that
metric tautological, so the comparison stays on two real successive
periods.

## Measured on the built engine

RIPPLE at the defaults, MIDI 60, after the wall-contact refractory (18
steps; without it the wall chattered and a default ripple reported 102
contacts): 10 vessels, 11 contacts, 20 splashes, overlap 0.0036,
obstruction 0.0786, mean speed 0.2365, mean heave 0.0144, surface peak
1.46, surface at the end 0.055, about 2.0 s, peak 0.80, pitch 260.95 Hz
(−4.5 cents, confidence 0.99), class LOOP. A 2 s render was about 100 ms.
Drive off: 0 contacts, 0 splashes, surface peak 0.

MIDI 36: +3.2 cents, confidence 0.96. MIDI 84: +5.8 cents, confidence
0.997. MIDI 84 is C6.

CROSSING 0 versus 1 (CROSSWAVE, SURFACE 0.7): 17 versus 65 contacts, and
the vessel/step pairs differ. SURFACE 0 still moves (2 contacts, speed
0.127, heave 0.012). SURFACE 1: 69 contacts, speed 0.456.

VESSEL 0 versus 1 on HOLLOW: cavity about 410 Hz versus 275 Hz, speed 0.50
versus 0.27, radius 0.040 versus 0.110, count unchanged.

PULSE brightness (first-difference energy over energy, first 120 ms) at
0, 0.5 and 1: 0.00186, 0.00393, 0.0124.

Velocity 0.25 versus 1 on KNOCK: 5 versus 27 contacts, speed 0.187 versus
0.337. Placement is the same.

FLOTILLA 1 and VESSEL 1: 18 vessels, area fraction 0.231, no initial
overlap. Route weights at 0 are `1, 0, 0, 0` and at 1 are `1, 1, 1, 1`.

A 4.5 s ripple ends at surface 0.001 against a peak of 1.46.

HOLD on DRIFT (FLOTILLA 0.25): seam 9.5e-10, period difference 0.022,
overlap gain 0.60, 8 iterations, about 82 ms, duration 1.84 s, 21
stationary contacts. The wrap step (3.305) matches the natural step
(3.308) well inside 5% of the period peak. HOLD on GATHER: seam 1.1e-10,
period difference 0.169, 48 contacts.

The tests lock these as bounds (pitch, seam, a quiet surface that still
moves, a packed pool that does not overlap, each knob changing the audio).
They do not freeze a contact count.

## Roster, kit, screen

Fourteen presets, every knob named: Small Wake, Low Texture, Open Wood,
Strong Pulse, Brighter Wood, Deep Cavity, Wide Bowl, Crossing Paths,
Split Wake, Gentle Current, Warm Canopy, Held Sparse, Gathered Vessels,
Held Dense. They land dry.

`SynthKits.flotilla()` is A01–A08 RIPPLE up the minor pentatonic from
MIDI 60 (through MIDI 77, inside 36–84), then those eight named presets.
`generateFlotillaKit` and `generateFlotillaAudition`. The page is
`testkit/flotilla-audition/index.html`.

JSON keeps `model`, `midi` and `velocity`. Missing or null reads as the
default. An unknown field is ignored. A model other than 1 is refused
before decode, the same way a bad patch version is.
