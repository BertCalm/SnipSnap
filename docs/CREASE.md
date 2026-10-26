# CREASE — native integration

CREASE is an original, offline pitched engine inspired by phase-distortion synthesis.
Its gesture is bend → crease → unfold: pressure changes two periodic phase maps,
and the maps remember and release that pressure at different rates.

The first audition was rejected by its owner for sounding too similar. Harmonic
power analysis confirmed that the three defaults placed about 99% of their
harmonic energy in the fundamental. Small numerical changes passed the original
checks without establishing useful timbral variation.

The owner preferred the wider model-2 voices. Model 3 keeps those analytic phase
shapes and refines their pressure/recovery timing. Six listening presets explore
fast, delayed and slowly unfolding gestures across ROUND, PINCH and RUBBER;
the owner approved this listening direction. The three voices and six presets
are now registered in the native patch/preset, kit, CLI and phone paths. The
remaining voice expansion is later work. Model-3 patches also have a dedicated
held-keyboard builder, with Paper Keys and Ink Ribbon as the first listening
instruments.

## Controls and performance

MIDI pitch and velocity are render arguments, leaving FOLD, PRESSURE, DRAG,
SPLIT, RELAX and HOLD as the six controls. FOLD sets phase geometry; PRESSURE
sets its excursion; DRAG delays its response; SPLIT separates layer shapes and
timing; RELAX sets their recovery. Finite HOLD extends the gate; its top setting
renders a recurring pressure gesture as a sustained loop.

The supported range is MIDI 24–84. The two oscillators share the requested root.
Phase mapping changes waveform shape within each cycle, with no detune or phase
reversal. No samples or effects are required.

## Versioned synthesis models

Model 1 is preserved in `CreaseV1.kt` for exact old recipe replay and comparison.
It uses a short Fourier warp with `sum(k * abs(c[k])) <= 0.9`, guaranteeing
`0.1 <= w'(phase) <= 1.9`. That deliberately limited family proved too restrained
in the first listening test.

Model 2 uses smooth positive analytic phase maps with wider compression:

- ROUND uses a full-cycle Möbius map, moving from a rounded wave toward a rich
  all-harmonic shape.
- PINCH uses a half-cycle elliptical map with odd-harmonic narrow pulses. A
  bounded lead between fast deformation and slow recovery makes a push pinch
  ahead of equilibrium and open again as pressure falls.
- RUBBER composes the two maps so pressure and recovery move harmonic lobes and
  notches as well as pulse width.

Each map is periodic and monotonic. Its derivative has a positive analytic bound;
total absolute log compression is capped at 3.6 through C4 and gradually reduced
to 2.85 at C6, constraining the sharper shapes. The model-1
Fourier coefficient budget does not apply to model 2. Diagnostics therefore report
that budget only for model 1 and report map slope and curvature separately.

Model 3 keeps the same phase-map families and safety ceilings. Its pressure
profiles are independent of DRAG and RELAX: DRAG changes the material response
time, while RELAX changes deformation recovery. The finite amplitude envelopes
retain the accepted model-2 behavior. Explicit model 2 retains its original
pressure profiles and arithmetic for exact replay.

The shape-sensitive reciprocal detector runs before output leveling. STATEFUL /
INSTANTANEOUS and coupled / independent probes retain identical pressure and
amplitude schedules. Every held boundary check includes deformation, detector,
filter, DC and decimator memory; the preceding audio period is evolved separately.

Public `render`, `probe` and `synthesize` calls default to model 3 and accept an
explicit `model`. New audition recipes save that field. Old recipes without it
select model 1, preserving the first audition's behavior.

## Reproduce the audition

From the repository root:

```sh
./gradlew --no-daemon :synth:test --tests com.snipsnap.synth.CreaseTest
./gradlew --no-daemon :synth:generateCreaseAudition
./gradlew --no-daemon :synth:generateCreaseAudition -PcreaseFull
```

The default compact audition writes to `testkit/crease-audition-r3/`: three voice
examples, three matched model-2/model-3 pairs, six named presets, constant-level
DRAG/RELAX pairs, three-step control sweeps, two registers, held loops and
4×/16× reference pairs (87 clips). `-PcreaseFull` adds quarter steps,
velocity coverage, mechanism comparisons, interactions and held RELAX probes
for 178 clips in total.
The first and second auditions' output directories are retained separately.

Browser playback uses these Kotlin-engine WAVs. Matched files use the common
audition loudness; separate prelevel WAVs retain original dynamics. The original audition JSON files retain their standalone schema. Native
`CreasePatch` JSON is separate and carries the common Patch version, model, MIDI
and velocity. `CreasePresets.kt` is the shared source of truth: `all()` retains
the listening data while `patches()` exposes the same six sounds as native
factory patches.

| Voice | Preset | Listening role |
| --- | --- | --- |
| ROUND | Corner Bass | Rounded, quick accent at MIDI 36 |
| ROUND | Paper Keys | Richer key tone with a slower bloom |
| PINCH | Reed Mark | Firm, focused reed accent |
| PINCH | Return Stroke | More open reed with a longer afterimage |
| RUBBER | Late Mark | Note onset followed by delayed deformation |
| RUBBER | Ink Ribbon | Long vowel-like recovery |

All six bank presets are finite notes. Each render starts a fresh note state;
held comparisons evolve a recurring pressure gesture rather than simulating
persistent legato performance.

Replay a downloaded recipe with:

```sh
./gradlew --no-daemon :synth:generateCreaseAudition -PcreaseRecipe=/absolute/path/recipe.json -PcreaseOutput=/absolute/path/output
```

## Listening and verification

The owner's listening verdict remains the acceptance gate. The revision checks
require substantial gain- and phase-independent harmonic differences between
voices and over time, alongside pitch, aliasing, deterministic replay, numerical
bounds and independently measured held seams. Original model 1 must fail the new
contrast checks, so tiny spectral differences cannot stand in for useful variation.

Pressure should audibly crease the tone; DRAG should reveal lag; SPLIT should
expose differing shapes; RELAX should make their return audible. Numerical
contrasts describe the rendered sound and do not establish musical acceptance.

## Delivery and integration

`Crease.kt` implements models 2 and 3; `CreaseV1.kt` preserves model 1. `CreaseTest.kt`
checks the contracts, and `CreaseAuditionGenerator.kt` generates the evidence.
The audition site's static source is kept in its own Sites source repository.

`CreasePatch` is registered in `Patches`, `Presets`, native `Velocity` routing,
`SynthKits`, FRESH TAPE, CLI and the phone SYNTH picker. Patch edits preserve the
saved model, MIDI root and velocity. Native JSON without a model selects the
first native schema's model 3; old standalone audition JSON without a model
continues to select model 1. Explicit models 1, 2 and 3 replay their own sound.

The sixteen-pad dry starter has eight C-minor-pentatonic Corner Bass notes, the
six approved presets, and Paper Keys/Ink Ribbon HOLD loops. Its fourteen finite
pads are TONAL regardless of duration; the two held pads are LOOP. The phone
uses the saved MIDI root for SPREAD and offers ROOT/VELOCITY beside the six
controls. MAKE INSTRUMENT/`Keys.creasePad` renders model-3 patches as held keyboard
instruments through the existing sampled-instrument player.

## Held keyboard instruments

`CreaseKeys` starts one model and one output-conditioning path from rest. A
speaking pressure onset smoothly becomes the existing periodic pressure gesture;
all actual settling audio is retained before the final loop. The loop is accepted
only after successive full circuit states recur and an independently evolved
audio period closes. There is no finite-note/held-note audio splice.

`CreasePadMaker` samples minor-third roots over two octaves around the saved MIDI
note, clipped to MIDI 24–84. Keygroup boundaries stop at the outer sampled roots.
The full saved patch supplies the voice, model, macros and excitation velocity;
making an instrument leaves that source patch unchanged. HOLD is forced internally
for this transport: the key controls duration. Existing finite and HOLD exports
retain their original model behavior.

The waveform owns its attack. RELEASE is a separate 0.1–1.5 second phone/MPC
volume-envelope control, defaulting to 0.6 seconds. Note-off fades the currently
playing sample; it does not simulate a new deformation-release trajectory.
RELAX still controls recovery inside the held pressure gesture. By default there
is one authored velocity layer; live key velocity applies the existing player
gain. Optional TOUCH LAYERS adds independently rendered soft, medium and firm
pressure histories, with MIDI windows 1–42, 43–84 and 85–127. The saved velocity
remains the firm excitation anchor; soft and medium use 0.35 and 0.65 times it.
Each complete note is matched by its converged sustain, then all three receive
one shared headroom attenuation. Live velocity still applies linear player gain.
The three layers have separate onset/settling prefixes and loop markers. They
increase generation and audio storage by approximately three times. They use
discrete sample selection rather than a continuously morphing velocity model.

The builder exports the native `.xty`, compatibility `.xpm`, WAV loop metadata
and phone instrument sidecar through `OneNote`. The sidecar retains playback
metadata, while the listening download supplies the editable native Patch JSON
separately. Model-1/model-2 patches are rejected by instrument authoring rather
than silently upgraded; their ordinary renders still replay normally.

Generation is an explicit cancellable bulk operation. It retains at most 28
settling periods and a 64-second sample; failure to reach a measured periodic
orbit fails the render. Zones render serially in the phone authoring flow.
Desktop generation timings are reported by the audition and do not establish
phone latency. The design and its alternatives are recorded in
[the held-keyboard design](superpowers/specs/2026-10-23-crease-held-keys-design.md).

CLI example:

```sh
./gradlew :cli:run --args='synth crease round --preset 2 --midi 60 --velocity .8 --model 3 --out /tmp/crease-native'
./gradlew :cli:run --args='synth crease round --preset 2 --instrument --release .6 --out /tmp/crease-keys'
./gradlew :cli:run --args='synth crease rubber --preset 2 --instrument --release .6 --out /tmp/crease-keys'
./gradlew :cli:run --args='synth crease round --preset 2 --instrument --touch --release .6 --out /tmp/crease-touch'
```

Saved native recipes can replace `--preset` using `--patch recipe.json`.
Instrument mode accepts explicit MIDI/velocity/model overrides, rejects `--all`
and `--attack`, and validates the complete request before creating output.

Reproduce the two-instrument audition and real sampler performances with:

```sh
./gradlew --no-daemon :shell:generateCreaseKeysAudition
```

The separate `testkit/crease-keys-audition/` output contains measured zone renders,
dual-format instrument packages, native source recipes and actual
`InstrumentEngine` hold/release, velocity-gain and phrase performances. It does
not replace the original 87-clip model-3 listening set.

Reproduce the separate touch-layer study with:

```sh
./gradlew --no-daemon :shell:generateCreaseTouchAudition
```

`testkit/crease-touch-audition/` contains three-layer Paper Keys and Ink Ribbon
packages, native-level player performances, matched timbre comparisons and a
root-only Reed Mark study. Each package includes its native source recipe and
measured render evidence. The [touch design](superpowers/specs/2026-10-23-crease-touch-design.md)
records the level policy, cost and discrete-layer limits.

The audition offers six native patch JSON files and a shelf backup with a
playable starter kit and editable saved presets. The official shelf import
restores the kit's WAVs/settings; its six saved presets retain full recipes.

The full production JVM gate is `./gradlew --no-daemon test` without an Android
SDK. CI builds Android separately; JVM checks cannot validate future phone UI
wiring. Native integration is checked through engine replay, registry/serialization,
velocity, CLI, shelf import and app build validation. Device interaction still
requires a phone or emulator.
