# PITCHWHEEL

Pitchwheel ports the [supplied 5 October 2026 engineering specification](superpowers/specs/2026-10-05-pitchwheel-engine-engineering-spec.md) into
SnipSnap's deterministic offline melodic rendering path. A resonant wooden
wheel turns through resin: teeth catch flexible fingers, their release sounds
a requested note, and stretching filaments can bow, snap or pull the wheel
back. Its phrase follows the evolving wheel, contact and resin state.

This is an invented musical DSP instrument. The normalized forces, thermal
response and modal ratios are expressive design choices, not a validated
resin model. Pitchwheel remains a working name. The first dry listening verdict
is pending; the initial preset scope stays small until the owner approves it.

## Playing and saving

CLUNK, PLUCK, DRAW, RECOIL, THAWED and TURN select calibrations inside the same
mechanism. TURN starts in finite mode like the other voices; HOLD enables its
continuing powered gesture explicitly.

| Control | Behavior |
| --- | --- |
| TUNE | Semitone adjustment, centered at .5, spanning ±12 from the saved root |
| PUSH | Initial angular impulse and encounter density |
| TOOTH | Rounded compliant catches → short hard releases |
| ADHESION | Light resin drag → longer stretch, strain and recoil |
| HEAT | Cool resistant starting resin → warmer easier passage |
| BODY | Compact dry wood → broader hollow wooden response |
| HOLD | Finite push below .99; settled powered loop at .99 and above |

Saved root notes span MIDI 24–96, with MIDI 48 (C3) as the default. TUNE's
relative offset is applied without clamping the effective note to that saved
range, so the combined controls span MIDI 12–108. Upper modes beyond the
output bandwidth are excluded from excitation and pickup before decimation.
Velocity spans 0–1 and scales gesture energy separately from PUSH's playing character.
`PitchwheelPatch` stores `midi`, `velocity` and render `model` beside the macro
map. The current model is 1. Missing fields read as the defaults; unsupported
models, voices and invalid normalized macro values are refused.

The render seed derives from the engine, model, voice and saved root through
`Dsp.seedFor`. Tooth geometry stays fixed when comparing macro settings at
that root. Each render constructs a fresh wheel, fingers, attachments,
temperature, controller and resonators. Independently exported pads share
no phase, heat or resin history. `Patches`, `PadRecipe`, preset saving and
recipe replay retain the complete regenerable recipe. Editing its name or
macros preserves the saved note, velocity and model.

Output is mono at 44.1 kHz. Nonlinear contacts and resonance run on the 4×
internal path, followed by the established decimation and shared
`MELODIC_LOUDNESS_TARGET` processing. Diagnostic probes expose raw audio
and mechanical state before matching loudness.

Finite gestures render for up to eight seconds, with quiet termination after
a two-second minimum. A silent stalled object can finish without exhausting
the duration limit. Finite patches carry TONAL metadata, so IN KEY can retune
them. Settled held patches carry LOOP metadata. These explicit pitched classes
keep Pitchwheel outside KICK, SNARE, CLAP, HAT and TOM routing.

The phone SYNTH picker exposes all six voices, macros, preview and saving.
FRESH TAPE's PITCHWHEEL starter and `SynthKits.pitchwheel()` provide sixteen
dry pads. A01–A08 walk CLUNK through C minor pentatonic from C3; A09–A14
contain the six voice defaults; A15 continues the scale; A16 is Endless Turn.
No rack effect establishes the dry wheel's identity.

The initial roster is Wooden Ratchet, Pitched Tooth, Resin Thread, Returning
Tooth, Warm Passage, Balanced Turn and Endless Turn. The first six use their
voice defaults. Endless Turn changes HOLD alone. More authored variations
wait for the owner's dry audition approval.

The CLI supports a factory-preset audition with separate note and velocity:

```text
./gradlew :cli:run --args="synth PITCHWHEEL PLUCK --all --midi 60 --velocity 0.4 --out /tmp/pitchwheel"
./gradlew :cli:run --args="synth PITCHWHEEL TURN --preset 2 --midi 48 --out /tmp/pitchwheel-held"
```

## Motion, contacts and energy

The starting budget is one rotating wheel, ten teeth, two flexible fingers,
up to four resin attachments and ten resonant modes across the pitched
tooth/finger and wooden body networks. Wheel angle uses radians and speed
uses radians per second. Inertia is .08 in normalized internal units; energy
and work use the corresponding normalized units.

PUSH sets the initial mechanical energy, with a smooth low-end launch floor
so a gentle push can still clear the first finger. The floor tapers away by
PUSH .45, preserving the default gestures; velocity zero remains silent.
Wheel motion determines encounter
timing while resonant frequencies determine the note. Tooth contacts bend
fingers and apply reaction torque to the wheel. A release spends stored
finger energy on its excitation projection; a reverse encounter needs real
clearance before another release. Geometric hysteresis prevents slow motion
around one boundary from becoming an unbounded attack source.

Filaments attach at a resin-region crossing, within the active attachment
budget. Their stretch and relative slip produce lossy restoring force and
bounded bow excitation. A snap converts a share of the stored elastic energy
into the same resonant network and loses the remainder. Attachments and force
thresholds determine snaps rather than a separate crackle generator.

Nonnegative dissipated resin work supplies warming. Cooling moves temperature
toward HEAT's reference. Bounded temperature changes resistance and release
behavior while retaining adhesion at warm settings. Returned elastic energy
is accounted separately from heat. Finite renders have no powered source;
the mechanical/acoustic ledger charges excitation transfers and constrains
numerical energy growth. Probes expose those transfers and correction amounts
so a limiter cannot be the evidence for solver stability.

The root-bearing resonances stay tied to the requested note as PUSH changes
encounter timing and BODY changes receiving response. Contact and resin
excitation project accounted energy into one shared ten-mode network;
each mode receives an explicit share of available work. Repeated excitation
grows the root receiving modes without changing their phase, while velocity
kicks into upper/body modes carry the release edge. Slip-funded radial
excitation sustains the harmonic subset between catches. These receiving
approximations keep encounter timing from shifting the requested pitch;
upper modes remain subordinate to the root. This first version omits acoustic
feedback into wheel motion.

## HOLD and export

HOLD supplies an explicit bounded torque source and phase/speed regulation. It can
replenish losses while local catching and resin resistance still slow the
wheel. Held rendering uses a constrained deterministic attachment cycle and
caps its operating adhesion at .65 to support a repeatable settled orbit.
Held filament stiffness is scaled by .55, its stretch limit stays at or below
.235 radians before its thermal reduction, and controller torque is limited
to ±.9 in normalized units.
This loop calibration is part of HOLD; finite gestures retain the full
adhesion range and their thermal history.

The current drum-pad format exports settled loop-only audio. It has no
attack-plus-loop-region field. The initiating push is excluded from the held
buffer, and the starter pad plays that buffer once. Repeat the audition clip
to hear the wrap continuously.

Loop acceptance includes the complete retained state: wheel phase and speed,
fingers, attachment lifecycle, temperature, resonators and controller state.
A single revolution cannot establish convergence by itself. A bounded
settling stage selects recurring material before the existing wrap handling.
The exported buffer must meet `Keys.seamError < 1e-3`; repeated playback must
also retain useful contacts, pitch, timing and peak level.

## Audition and acceptance

```text
./gradlew --no-daemon test
./gradlew :synth:generatePitchwheelAudition
./gradlew :synth:generatePitchwheelAudition -PpitchwheelFull
```

The default audition task creates the compact first-listen pack at
`testkit/pitchwheel-audition/`, including a listening page, dry audio and
diagnostic records. The optional full pack adds note/velocity corners,
five-step macro sweeps, PUSH × ADHESION and ADHESION × HEAT comparisons,
raw/matched audio, difficult extremes and held-loop material.

Numerical acceptance checks deterministic samples and event traces, root
calibration, pitch independence from encounter rate, contact-funded releases
and snaps, recoil clearance, finite passive decay, thermal response, bounded
attachment count, raw energy and level, DC, solver-rate sensitivity, loop
state convergence and seams. Integration checks cover patch/recipe replay,
saved-state editing, dry pitched kit metadata, preset promotion and CLI note
and velocity overrides.

The owner's listening verdict remains necessary for dry identity, recognizable
root, distinct useful controls, audible reverse encounters and repeated-loop
quality. Numerical checks do not replace that verdict or authorize a wider
preset roster.
