# Shoal — materials and movement

Shoal implements the retained [engineering proposal](superpowers/specs/2026-10-16-shoal-engine-engineering-spec.md)
as a deterministic mono 44.1 kHz engine with 4× internal rendering. The first
harmonic audition established audible energy transport; the owner preferred its
movement to its basic synth core. The independent bowl, wood and wire study was
accepted as a better direction. Model 3 brings that separation into normal patches,
native velocity, presets, the starter kit, CLI and the phone editor.

## Material and motion

`ShoalVoice.GLASS` and `RIPPLE` select the energy controller. GLASS strikes sparse
groups and exchanges energy through adjacent and longer links. RIPPLE supplies a
broader population through a travelling injection point. The six controls remain
DENSITY, CURRENT, COHESION, STRAY, GLOW and HOLD. Controller exchanges read the
previous complete nonnegative energy state; diagnostic ablations retain their
original meanings.

`ShoalMaterial.WOOD`, `BOWL` and `WIRE` select the radiating body. Their modal
frequencies, contact coupling, excitation and decay differ before oscillator
summation. WOOD uses split free-bar families and short high-mode residence; BOWL
uses closely split inharmonic families and a longer ring; WIRE uses paired stiff
string ratios, contact-position coupling and frequency-dependent loss. These are
stylized resonant structures, not calibrated models of particular instruments.
A pitch-anchor mode follows the body's own resonant decay and bounded drive.
The controller and radiating modal energies are separate states. Excitation stops
at the controller end, followed by a bounded passive tail.

Velocity affects contact duration and relative modal excitation as well as the
common final loudness target. It does not merely filter or turn down a finished
sample. Modes are selected against their actual material frequencies and reserved
pitch departure before controller construction, avoiding inaudible high-mode
energy in the upper register. ROOT spans MIDI 24–84. Inharmonic materials expose a
requested pitch component; generic fundamental detectors can report another mode,
so a globally unambiguous perceived root is not promised.

`HARMONIC` retains the model-2 sound as a comparison and for captured HOLD loops.
The default for newly constructed `ShoalPatch` recipes is model 3, WOOD. Direct
`Shoal.render`/`probe` calls retain HARMONIC as their material default for source
compatibility; callers selecting a body pass it explicitly. The phone's new-patch
preview uses `ShoalPatch` so preview and saved recipe agree.

## Save and editing contract

Patch JSON remains version 1. It saves `model`, `material`, `midi`, `velocity`, the
motion in `voice`, and the six macro values. All are retained through macro edits,
pad recipes, native velocity regeneration and user-preset promotion.

- Models 1 and 2 accept HARMONIC only and keep their original render arithmetic
  and phase seeds. Missing or null JSON `model` means model 1; missing or null
  `material` on these models means HARMONIC.
- Model 3 accepts all four materials; omitted material defaults to WOOD.
  Model-3 HARMONIC delegates to the model-2 renderer.
- Unsupported models, material names, model/material combinations and material
  HOLD loops fail explicitly. Loading or editing an old patch never upgrades it.
- Selecting a different material in the phone opts into model 3. Choosing a finite
  body clears a previously selected harmonic HOLD loop, and the editor explains
  why HOLD is unavailable for that body.

The factory roster contains WOOD, BOWL and WIRE in both GLASS and RIPPLE motions,
plus one HARMONIC held recipe per motion. The dry sixteen-pad starter has eight
wooden GLASS-motion C-minor-pentatonic notes, the six material recipes and two
harmonic loops. CLI `--material WOOD|BOWL|WIRE|HARMONIC` selects a body for SHOAL
and works alongside `--midi` and `--velocity`. Overrides are validated before any
output directory or WAV is created.

## HOLD

WOOD, BOWL and WIRE currently play finite notes with passive tails. Their
inharmonic carriers do not automatically share the harmonic loop's phase closure;
HOLD at .975 or above is therefore rejected for these bodies.

HARMONIC retains the **captured periodic control-trajectory approximation**. A
bounded nonlinear population run is captured and joined by a smooth convex control
overlap, rather than claiming free-running state convergence. A frame-fitted root
and zero-mean detune in Hz close every harmonic's integrated phase. Independently
synthesized cycles pass through continuous conditioning before preceding-cycle,
state and seam checks. The moving population is not frozen to achieve closure.

## Listening and verification

Generate the production material, motion, note and velocity comparison with:

```sh
./gradlew --no-daemon :synth:generateShoalMaterialAudition
```

`testkit/shoal-playable-audition/` contains 48 cases: four materials, both motions,
C3/C4 and velocities .25/.6/1. Native WAVs use the actual patch renderer including
velocity loudness; matched WAVs share a common loudness target to compare color.
The ordinary JSON recipes replay in SnipSnap. Diagnostics and state traces
identify controller and radiating energies separately. The private audition keeps
the earlier model-1/model-2 and independent material-study pages available.

`./gradlew --no-daemon :synth:generateShoalAudition` still regenerates the previous
94-case model-2 matrix with explicit saved version selection and model-1 fixtures.
The original audio and source pack are preserved. Automated checks cover legacy
PCM identity, materials, velocity, guard bands, passive decay, determinism,
cancellation, serialization, editing and native regeneration. They do not establish
musical preference. Android compilation/playback and MPC hardware acceptance remain
separate checks. ORGAN, WHISTLE, DRIFT, SWARM and the proposed 36-preset expansion
remain later work.
