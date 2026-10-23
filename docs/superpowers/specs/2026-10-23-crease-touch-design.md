# CREASE touch-responsive held instruments

Status: implementation in progress. Listening and physical-device acceptance remain open.
Repository truth: `461cbc8e3e4323a7155dbbee63b62e486d644832`.

The characteristic gesture is **soft or firm touch → different pressure and
deformation → independently settled recurring motion → key-up volume fade**.

## Review and landing

The model already changes pressure with excitation velocity. `Crease.Model`
multiplies the pressure source by `1.25 * velocity^1.25`; its deformation,
recovery and reciprocal load states then change the phase maps. The held builder
previously exported one authored velocity, and `KeyHit.resolve` applied only
linear live gain to that sample. The missing expression is in what the builder
retains, rather than a missing phase-map equation.

This round exposes those existing mechanics as an optional three-layer sampled
instrument. It adds no synthesis model, voice family, global velocity rule or
player schema. The ordinary single-layer builder remains the default and keeps
its previous rendering. Existing exported instruments remain unchanged.

The closest synthesis sibling is GLINT, whose periodic formant source does not
carry Crease's coupled deformation memory. BELLOWS already demonstrates three
velocity layers in this same player/export path, but its pressure affects reed
and wheel sources instead of two phase maps. The lightest landing is therefore
an extension to CREASE held-instrument authoring.

| Direction | Gesture clarity / novelty | Risk | Build cost |
| --- | --- | --- | --- |
| PORT existing velocity-driven mechanics into three held sample layers | Direct touch-to-deformation gesture; adds expressive keys to this engine | Discrete timbre boundaries; larger sample bank | One localized round; selected |
| MUTATE the phase geometry into another held voice | New color, but the same live touch response | Another unapproved voice and preset family | DSP and listening round |
| INVERT key-up into a new pressure-release event | A new ending driven by the state at release | Current sample player cannot resume arbitrary circuit states | Separate release-aware transport and export design |

Pressure and deformation equations run literally. Paper/material language and
touch-to-pressure scaling are sound-design analogies, not a simulated sheet or
physical keyboard action. The selected change does not add a modeled key-up
trajectory or persistent state across separately triggered notes.

## Signal flow

```text
saved model-3 patch, MIDI root, authored velocity
    → touch factors [0.35, 0.65, 1.0]
    → three independent CreaseKeys circuits from rest
       (pressure → delayed deformation/recovery → coupled phase maps)
    → continuous 4× band-limit / DC / decimator paths
    → each real onset + settling prefix + independently verified loop
    → one loop-measured scalar per whole note to a common sustain target
    → one shared bank headroom scalar
    → three velocity windows and their own loop markers
    → existing phone / MPC sample selection, live gain and release
```

`CreaseTouchKeys` is the proposed isolated adapter. It reuses `CreaseKeys.probe`
with normalization disabled; `Crease.kt` and the old key transport arithmetic
do not change. Each sample keeps its own prefix length and loop marker. No
audio interpolation, state splice or duplicated cycle establishes recurrence.

## Controls and layer contract

| Control | Default | Quantity changed | Interaction and proof |
| --- | --- | --- | --- |
| TOUCH LAYERS | Off | One authored layer or three independent pressure histories | On produces different pressure traces and gain-independent spectra |
| Saved VELOCITY | Source patch value | Hard-touch excitation anchor | Soft/mid excitation is 0.35/0.65 times this value; zero stays silent |
| FOLD / PRESSURE | Source patch values | Baseline phase geometry / deformation excursion | Existing model laws remain intact at each excitation |
| DRAG / RELAX | Source patch values | Deformation following / recovery times | Each layer must converge independently with these exact values |
| SPLIT | Source patch value | Difference and loading between the phase maps | Coupled circuit runs separately for every layer |
| HOLD | Forced internally | Recurring pressure gesture | Saved patch is not rewritten |
| RELEASE | 0.6 s | Player amplitude fade after key-up | Existing 0.1–1.5 s range, tested after package reload |

The initial factors and windows are **shape choices**. Soft covers MIDI
velocities 1–42, medium 43–84, firm 85–127. These match the existing BELLOWS
layering convention. The saved velocity is the firm anchor, never silently
replaced by 1.0. Live velocity still applies the existing player's linear gain.

All three converged loops target the same house loudness scaled by the saved
anchor's existing `velocity^0.4` law. Each note receives only one uniform scalar
from its onset through its loop. A single further bank attenuation protects the
loudest complete note. This removes an extra baked steady-level step at the
layer boundaries; it does not promise perceptually seamless timbre switching.
The optional bank's hard layer may be quieter than a standalone hard render if
another layer requires more shared headroom. Touch authoring accepts zero or a
positive anchor at least `Float.MIN_NORMAL` (approximately 1.17549435e-38).
Smaller positive anchors are rejected before generation because their softer
audio can underflow to zero and invalidate the inherited seam measurement.
Ordinary single-layer authoring keeps its existing input domain.

## Cost, cache and cancellation

This is an explicit offline instrument-making operation. It renders three
circuits per zone instead of one, serially. The package is the bounded cache;
playback performs no synthesis. The cache identity is the complete saved patch
(model, voice, all macros, anchor velocity), zone MIDI, touch factors and adapter
render version. RELEASE is package metadata and does not change sample DSP.
There is no new global waveform cache or expensive precomputation on knob turns.

Every layer retains the existing 28-period and 64-second caps and cancellation
checks. Generation fails on nonconvergence rather than cutting or crossfading
an unproved loop. Three layers increase render work and saved audio storage by
approximately three times; actual timings must be reported by the generator.
Desktop measurements do not establish phone latency or peak memory.

## Verification and listening

Core checks cover deterministic independent circuits, whole-note uniform gain,
bounded finite raw/output state, zero anchor, cancellation, actual prior-cycle
recurrence, PCM quantization, native pitch and a higher-rate reference. Timbral
contrast is measured by normalized harmonic power, with pressure traces as
independent evidence. Waveform inequality alone does not prove useful expression.

Package/player checks cover three nonoverlapping windows at every root, their
individual WAV loop metadata, native `.xty`, compatibility `.xpm`, phone
sidecar reload and exact 42/43 and 84/85 selections through `KeyHit`. Actual
`InstrumentEngine` performances supply note-on, sustain and note-off evidence.

Paper Keys and Ink Ribbon are the complete first listening instruments; Reed
Mark supplies a root-only PINCH study. Auditions include native-level playing,
matched-level timbre comparisons and the prior single-layer gain-only control.
The previous listening sets and downloads are preserved.

Sonic risks are weak contrast at very low saved excitation, conspicuous timbre
changes at a layer boundary, attack peaks reducing the whole bank's level, long
RUBBER settling prefixes and unexpectedly large instruments. None is hidden by
a new factory preset or a claim of physical-device verification.

## Verified / proposed / heard ledger

| Claim | Status | Evidence |
| --- | --- | --- |
| Existing velocity drives pressure before phase-map deformation | Verified | `Crease.kt` read at the source commit above |
| Existing phone and MPC paths retain multiple layers and per-layer loops | Verified | `InstrumentStore`, `KeyHit`, `InstrumentPlayer`, `OneNote`, `KeygroupWriter`, `Mpc3TrackWriter` |
| Three-layer pressure/timbre contrast survives common leveling and package playback | Proposed until measured | Core probes and new touch audition |
| Phone generation cost and MPC playback | Unverified | Physical-device checks remain open |
| Musical quality and preferred touch boundaries | Not heard | Owner listening required |

## Initial Kotlin measurements

The initial focused run passed 15 tests: six touch-bank checks, the touch
determinism canary and eight existing held-key checks. On the three fixed source
presets, adjacent gain-independent harmonic-power differences were 0.055–0.165
over onset/body windows and 0.057–0.164 at matched sustain phases. The tests set
their minimum contrast before rendering; the thresholds were not lowered.

All three sustained levels matched within the test tolerance. Reed Mark
exercised the shared headroom stage with a 0.97211146 scalar; the two other
banks needed no further attenuation. The resulting 42/43 and 84/85 level
increments were 0.20438 and 0.10279 dB, the ordinary linear key-gain increments.
Nine root-layer pairs retained zero measured PCM16/24 seam error; the worst
fine-tuning probe was below 0.038 cents. These zero seam values are limited to
the measured quantized windows, not a claim of exact recurrence at every state.
The independent full-state error stayed below 1.101e-8. MIDI-84 4×/16× harmonic
power distance was at most 2.611e-7. This is a reference-shape comparison, not a
complete off-harmonic alias measurement.

The later adapter-domain guard and explicit common-gain assertion require the
final combined gate. Full package, Android, all-module JVM and listening
verification are tracked separately from these initial measurements.

## Why it is built this way

1. Touch changes Crease's own pressure memory. A generic brightness filter would
   bypass the mechanism that makes this engine useful.
2. Every layer starts its own circuit and proves its own loop. Sharing a loop
   marker would discard legitimate differences in settling time.
3. Matching sustained level makes a soft/firm comparison expose spectral change;
   live key velocity still supplies the normal performance dynamics.
4. The optional switch preserves the existing lightweight instrument path and
   makes the extra generation and storage cost an explicit choice.
5. Key-up remains a player fade because the exported sample has no state-aware
   release path. That larger change needs its own complete playback design.
