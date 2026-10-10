# PITCHWHEEL

Pitchwheel ports the [supplied 5 October 2026 engineering specification](superpowers/specs/2026-10-05-pitchwheel-engine-engineering-spec.md) into
SnipSnap's deterministic offline melodic rendering path. A resonant wooden
wheel turns through resin: teeth catch flexible fingers, their release sounds
a requested note, and stretching filaments can bow, snap or pull the wheel
back. Its phrase follows the evolving wheel, contact and resin state.

This is an invented musical DSP instrument. The normalized forces, thermal
response and modal ratios are expressive design choices, not a validated
resin model. Pitchwheel remains a working name. The owner heard the first model
and reported six similar struck voices differing mainly in rhythm. Model 2
addresses acoustic character; its revised dry audition remains provisional.
The initial preset scope stays small until the owner approves it.

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
map. Newly authored recipes use model 2. Saved model 1 recipes use the preserved
original renderer, including velocity variants. An absent or null JSON `model`
reads as 1 so an older recipe keeps its original sound. Editing, replaying or
promoting a saved recipe retains its model. Missing note and velocity fields
read as their defaults; unsupported models, voices and invalid normalized
macro values are refused.

The second sound-design round changes the acoustic transfers that the first
model masked. BODY and TOOTH affect audible modal balance, decay and contact
rise; finger releases, resin snaps, filament slip and finger-friction creaks
use different receiving projections. Reverse motion weights the upper/body
response differently. The root, shared resonator and accounted mechanical
energy remain the basis of the instrument. These are prototype sound-design
choices awaiting the next dry audition, with the seven-preset roster unchanged.

The geometry seed derives from the engine, voice and saved root through
`Dsp.seedFor`, retaining the original model-1 seed tag in both renderers.
Initial tooth positions, attachment thresholds and variations therefore stay
the same across the sound revision and when comparing macros at that root.
Model 2 starts with the same mechanical calibration; changing the fraction
of friction work sent into sound can affect subsequent warming and drag.
Each render constructs a fresh wheel, fingers, attachments,
temperature, controller and resonators. Independently exported pads share
no phase, heat or resin history. `Patches`, `PadRecipe`, preset saving and
recipe replay retain the complete regenerable recipe. Editing its name or
macros preserves the saved note, velocity and model.

Output is mono at 44.1 kHz. Nonlinear contacts and resonance run on the 4×
internal path, followed by the established decimation and shared
`MELODIC_LOUDNESS_TARGET` processing. Diagnostic probes expose raw audio
and mechanical state before matching loudness.

Nonzero finite gestures render for up to eight seconds, with quiet termination after
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

Nonnegative dissipated resin work supplies warming after accounting for the
share transferred into slip sound. Cooling moves temperature
toward HEAT's reference. Bounded temperature changes resistance and release
behavior while retaining adhesion at warm settings. Returned elastic energy
is accounted separately from heat. Finite renders have no powered source;
the mechanical/acoustic ledger charges excitation transfers and constrains
numerical energy growth. Probes expose those transfers and correction amounts
so a limiter cannot be the evidence for solver stability. Finger-friction
loss can fund a continuous creak while a tooth remains caught, and active
filament friction can fund slip sound between releases. A diagnostic acoustic
mute routes its allocated work to a silent sink so it preserves the same
wheel trajectory and temperature. Static strain has no continuing sound
source without motion or an accounted elastic release.

The root-bearing tooth bank starts at ratios 1, 2 and 3, with sparse upper
modes at 3.9 and 6.2. The wooden body uses separate ratios 1.42, 1.86, 2.72,
4.12 and 6.43, scaled by BODY; it no longer duplicates the fundamental as
its first mode. Voice calibration changes mode losses and receiving weights
within this same ten-mode network. TOOTH changes release brightness and rise
time; BODY changes audible wooden response, frequency ratios and decay.

Every transfer divides its available work among the modes. Existing modal
states grow radially without resetting phase, adding exactly their allocated
energy; a previously silent mode receives a direction-dependent velocity seed.
Release and snap work first enters a short contact reservoir, which counts
as stored energy before it reaches the modes. Its TOOTH- and voice-dependent
transfer rate shapes the onset without another impulse budget. Slip and creak
work enters the same bank continuously. Slip's receiving distribution follows
filament strain, temperature and bounded modal-state feedback, while reverse
motion changes the spectral weighting. The requested root remains fixed as
wheel speed changes encounter timing. This model omits acoustic feedback into
wheel motion.

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

Held modal frequencies are rounded to whole cycles of the selected loop
period so tooth and body phases can recur together. Loop acceptance includes
the complete retained state: wheel phase and speed, fingers, attachment
lifecycle and remembered limits, temperature, resonators, pending contact
energy and its direction, and controller state.
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

The default audition task creates a 63-case compact first-listen pack at
`testkit/pitchwheel-audition/`, including a listening page, dry audio and
diagnostic records. Six fixed-energy isolated catches compare attack colour
without rhythm; six preserved model 1 defaults provide a listening reference.
Each clip records its rendering model. Isolated catches have no wheel
trajectory and intentionally omit the mechanical diagnostic ledger.
The optional 172-case full pack adds note/velocity corners,
five-step macro sweeps, PUSH × ADHESION and ADHESION × HEAT comparisons,
raw/matched audio, difficult extremes and held-loop material.

The [published audition](https://pitchwheel-audition.bertcalm.chatgpt.site)
starts with the isolated catches, then offers full gestures and previous
defaults. Hosted FLAC preserves every sample of the 24-bit audition WAVs.

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
