# FILAMENT — first listening prototype

Filament is a deterministic, sample-free pitched instrument for SnipSnap's
offline renderer. One excitation enters a normalized set of guided paths.
Ordered bend sites divide each path's remaining allocation between its
endpoint, escaped sound and absorption. The escaped sound alone feeds a
shared, damped halo. The gesture is **contain → bend → spill**.

The supplied [engineering specification](superpowers/specs/2026-10-19-filament-engine-engineering-spec.md)
is preserved as the proposal. This first round implements THREAD, PULSE and
CURL with four path classes, four loss sections per strand, up to four
persistent strands and eight halo modes. BRAID, HALO, FRAY and the proposed
36-preset library remain later work. The reduced bend model is a musical
approximation, not an electromagnetic solver or measured optical fiber.

## Controls and saved sound

INJECT changes source articulation and launch distribution. BEND changes
path retention and leakage position. LENGTH changes transit time, arrival
spread and path loss. BUNDLE admits additional persistent routes within the
same launch budget. COLLECT selects endpoint and escaped-halo emphasis.
HOLD selects sustained/repeating material at its top step.

Requested MIDI root (36–84), velocity and deterministic configuration are
saved separately from the six controls in `FilamentPatch`. The configuration
records the seed, optional early/late/coil profile and ideal-retention
fixture. Model version 1 identifies this response. Derived taps and runtime
filter state remain internal. Macro edits and velocity rendering preserve
the nonmacro configuration.

The provisional `FilamentPresets` roster stays outside shared factory
presets, the phone picker and starter kits until listening. The CLI, patch
decoder, native velocity renderer and `SynthKits.filament()` support the
prototype. All audition recipes use an empty external FX chain.

## Model and evidence

Launch amplitudes have a normalized squared sum. At each site, retention,
escape and absorption are nonnegative power fractions whose sum is one.
Audio gains use their square roots. A site's escaped allocation is removed
before later sites receive the path. Collectors have bounded weights;
frequency-dependent processors and actual coherent output are checked
separately from scalar allocation accounting.

Geometry is fixed within a render. Every escape tap keeps its cumulative
travel time and path filtering; endpoint taps keep the remaining allocation.
No hidden bypass oscillator supports the endpoint, and perfect retention
produces no escaped input or halo. More strands redistribute launch effort
rather than adding input energy. No detuning is required for the bundle.

Raw audio and separate source, THROUGH, escaped-input and SPILL stems make
depletion reviewable before output calibration. Native output and listening
matches are separate files. A level match is a listening convenience, not
evidence of conserved branch power. Diagnostic recipes keep collapsed-path,
generic-spill and disabled-bend options separate from ordinary patch JSON.

HOLD fits the excitation and pulse schedule to a loop, populates delay
history and settles the filters in a bounded search. Evidence must compare
genuinely synthesized successive cycles before wrap treatment, including
exported PCM. Individual path delays need not divide the loop period.
A held pad is sustain-only material; a separate attack-plus-loop keygroup
adapter and phone/MPC hardware acceptance are later work.

## Reproduction and listening

```sh
./gradlew --no-daemon :synth:generateFilamentAudition
./gradlew --no-daemon :cli:run --args='synth FILAMENT CURL --all --midi 60 --velocity .7 --out /tmp/filament'
./gradlew --no-daemon test
```

The generator writes WAVs, exact recipes, branch diagnostics, measurements,
a browser listening page and a downloadable listening pack under
`testkit/filament-audition/`. Each clip reports its actual source, controls,
note, velocity and diagnostic options. Machine/runtime metadata accompanies
preparation and synthesis timing. Generated audio is not committed.

The [private audition](https://filament-snipsnap-audition.bertcalm.chatgpt.site)
compares straight and bent routes, early/late/distributed escape, arrival
length, one/four strands, isolated collectors, mechanism substitutions,
macro sweeps and held loops. Notes entered there stay in the listener's
browser and can be downloaded.

The owner's listening verdict completes sonic acceptance: length should
unfold arrivals, bends should deplete THROUGH while feeding SPILL, and the
full model should reveal ordered escape that a generic reverb send loses.
Numerical checks establish implementation properties, not that this gesture
is musically convincing.
