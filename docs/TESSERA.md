# TESSERA

Tessera implements the supplied [engineering specification](superpowers/specs/2026-10-05-tessera-engine-engineering-spec.md)
as a deterministic offline melodic instrument. Wooden bars, paired string
courses and metal tubes share a frame inside a changing chamber. Returning
pressure feeds the receiving materials and can release a small mechanical
answer. The sound is generated without samples or rack effects.

These mechanisms are musical DSP choices for an invented instrument. The
material ratios and moving paths are not measurements or validated chamber
physics. Tessera remains a working name.

## Playing and saving

WOOD, COURSE, TUBE, ANSWER, FOLDING and CHAMBER bias one shared architecture.
The voice changes losses, routing and response character; MATERIAL still
travels across all three material families within each voice.

| Control | Behavior |
| --- | --- |
| TUNE | Requested root, snapped to semitones from C3 to C5 |
| MATERIAL | Continuous overlapping wood → paired strings → metal excitation |
| HAMMER | Contact duration, hardness, upper-mode projection and bounded rebound |
| SCALE | Compact paths → spacious separated arrivals |
| FOLD | Smooth route lengths and material receiving preferences |
| MOTION | Static chamber → bounded gesture-driven wall reshaping |
| HOLD | Finite continuation; the top step exports settled recurring material |

Velocity changes hammer energy separately from HAMMER character. Each render
initializes the complete instrument. Exported pads do not share chamber state.
The engine follows SnipSnap's 44.1 kHz mono output, 4× synthesis clock, seeded
variation and shared melodic loudness conventions. Raw probes expose the state
before loudness processing.

Eight presets span Wooden Arrival, Paired Wire, Long Tube, Other Material,
Closing Passage, Expanding Hall, Crossed Returns and Held Chamber.
`TesseraPatch` and `PadRecipe` preserve the regenerable recipe, including an
optional existing `FxChain`. `SynthKits.tessera()` and FRESH TAPE's TESSERA
starter supply sixteen dry pads, beginning with C minor pentatonic notes.
The phone SYNTH picker uses the same render and macro adapters.

Finite patches carry TONAL metadata so in-key routing remains available.
Settled HOLD patches carry LOOP metadata. The recording classifier separately
labels long finite clips LOOP because of its duration threshold; that does not
mean a finite chamber response repeats seamlessly.

The current drum-pad format has no attack-plus-loop-region field. A held
buffer contains settled material without the initial isolated strike, and a
drum pad plays the buffer once. Repeat on the audition page lets its wrap be
heard continuously.

The CLI uses the normal factory-preset path and accepts separate pitch and
velocity overrides:

```text
./gradlew :cli:run --args="synth TESSERA WOOD --preset 1 --midi 60 --velocity 0.4 --out /tmp/tessera"
```

## Material and chamber model

Two wooden bars carry four modes each. Two paired courses carry six modes per
string, with separate losses, stiffness and seeded detuning. This is a
truncated modal string model rather than a delay-line waveguide. Eight
inharmonic tube modes and three frame modes complete the 43-mode object.
The principal modes stay at the requested root, with stronger damping and
subordinate radiation for the upper modes.

Free modal movement is an exact damped rotation of displacement scaled by
frequency and velocity. Reciprocal diffusive frame exchanges contract the
difference velocity while retaining the common velocity. This passive
transfer avoids adding a spring-induced tuning shift. The raised-cosine
hammer drives one normalized contact projection across the material morph;
harder gestures reserve a small share of their original energy for a later
rebound contact. Each finite contact accounts for positive incremental work
against its store, so the rebound supplies no additional gesture budget.

Six fractional delay paths carry traveling waves. FOLD mixes them through
orthogonal rotations without replacing their ringing buffers. Material/air
ports exchange energy through another orthogonal rotation, so emitted sound
comes from the same material state that receives returning pressure. Most
incoming pressure supplies continuous weak re-excitation; a smaller split
loads the collector trigger. Collector answers spend a finite reserved spring
budget, including the work against the material's existing velocity.

Each moving path accounts for stored and emitted energy, applies loss and
read-speed compensation, and charges extra wall amplification to a separate
bounded work credit. This aggregate ledger supports the musical moving-delay
rule; it does not track energy at individual positions in a physical chamber.
Walls follow smoothed instrument energy and return toward their reference
geometry. Delay lengths and read velocity remain bounded.

HOLD learns a finite reply score from returning pressure, closes its global
and per-material refractory intervals across the wrap, then replenishes its
restrained periodic sources and collector energy. The complete sounding object
continues to evolve after that score is fixed. A bounded preroll compares
material/frame state, traveling buffers, geometry, collector stores and event
gating across real successive cycles. Production export
requires this state convergence as well as the existing seam metric, before
a unity-sum wrap blend. Intermediate HOLD settings supply a finite continuation
and then stop powering the instrument.
The held period is 88,192 output frames (about two seconds), aligning source
quarters, wall controls and output frames. Source and control clock phases
must close exactly across the measured cycles.

## Audition and acceptance

```text
./gradlew --no-daemon test
./gradlew :synth:generateTesseraAudition
```

The generator writes `testkit/tessera-audition/index.html`, dry WAVs,
`manifest.json` and causal traces. Its full pack includes every voice at
low/middle/high notes and velocity, all factory presets, five-step macro
sweeps, the five required interaction grids, raw/matched pairs, receiving-port
and other mechanism switches, source taps, all-high corners and held loops.
The page provides single-clip playback, pause/resume, stop all, filtering and
sample-accurate repeating playback. It also works from relative local assets.
`-PtesseraQuick` selects the development pack.

The numerical gates cover deterministic samples and event order, direct-source
root calibration, full-mix tonal anchors, raw passive decay, finite collector
energy and refractory intervals, wall bounds, causal receiving-port isolation,
macro activity, patch/recipe round-trip, kit replay and held-loop seams.

The focused acceptance runs passed 20 Tessera checks plus 23 central
preset/recipe checks. In those cases, the worst isolated-source pitch error
was 0.103 cents, the largest tested raw peak was 0.753, and active wall work
stayed below its 0.126-unit credit. Default middle-register full mixes stayed
within four cents by the existing pitch detector; C3 WOOD measured about
22.6 cents low in that full-mix detector despite accurate isolated tuning.
Fifteen held recipes cover all six defaults, their all-high settings, low WOOD,
Held Chamber and neutral ANSWER. Their largest complete-state error was
0.002352 against the 0.003 gate, and their largest preceding-cycle audio
difference was 0.001384. Source/control clock and event-gate errors were exactly
zero, as were their exported seam errors after the wrap blend. The hard-contact
case reserved 0.0455 of its original 0.7-unit budget for rebound; measured total
primary work was 0.549. These are measured cases, not guarantees for every macro
combination or perceived-pitch judgments.

All eight other JVM-module suites passed (2,360 checks), as did the 102-case
native host harness. Local Android debug assembly passed for all four supported
native ABIs. The combined Tessera/Undertow registries also passed 67 targeted
preset, recipe, CLI, starter and convention checks, followed by a successful
Android rebuild; Tessera's DSP and audition inputs were unchanged.

The owner's listening verdict is still required for timbral distinctions,
recognizable root and useful motion. Host tests do not certify playback on a
phone or exported kit behavior on the MPC Live III.
