# TIDAL — finite encounter instrument

TIDAL turns a restricted two-center encounter into a pitched sound. Two original
populations retain different harmonic fingerprints as their disks deform into
surviving cores and extended streams. The defining gesture is gather, encounter,
and unravel; the late sound should contain evolving ringing strands.

This milestone implements PAIR and STREAM, complete replayable recipes, dry
listening comparisons, six factory presets, and a sixteen-pad starter. It is a musical adaptation of a
softened restricted gravitational model, not a calibrated galaxy prediction.
The owner approved the model-2 source direction after the second audition.
The remaining proposed voices, 36-preset bank, and HOLD reconstruction remain
later work. The complete preset bank and physical phone/MPC playback still need
their own listening pass.

The [private audition](https://tidal-snipsnap-audition.bertcalm.chatgpt.site)
opens with 18 focused comparisons for the second source pass. The original
58-case audition is retained under `legacy/`. Both provide raw/matched WAVs,
A/B listening, replayable recipes, descriptor traces, and download packs.
The second pass responds to listening feedback: STREAM's movement was distinct,
but both voices' source sound was too plain. Musical acceptance remains open.

Model 2 gives the original populations more distinct excitation and spectral
roles, with a rough string character and stretched metallic resonance. It reduces
the separate steady root reinforcement. PAIR now starts with a closer encounter;
a source-only comparison also keeps PAIR's original encounter settings so the
source change can be heard separately. The original distant PAIR reference is
still available in model 1 and the archived audition.

## Playing and saving

ROOT requests MIDI 36–84. VELOCITY changes source excitation independently of the
encounter. The five timbral controls are APPROACH, PASSAGE, TILT, BIND, and TAIL.
HOLD is saved at zero; positive HOLD is rejected. Repeating a clip on the audition
page repeats the complete finite performance and does not establish a sustained
instrument or seamless loop.

| Control | Action |
| --- | --- |
| APPROACH | Changes the initial relative encounter speed within a supported flyby family. |
| PASSAGE | Moves the target closest separation from a distant pass toward a close encounter. |
| TILT | Changes the actual 3D disk orientations and spin relationships. |
| BIND | Places material farther out or deeper within the same softened central potential, with compatible circular velocities. |
| TAIL | Changes listening emphasis and bounded resonance persistence toward material that actually extends. |

A fast passage can strip less material than a slow passage at the same closest
separation. Its brighter musical impact is an explicit excitation mapping, not a
claim that faster encounters always make longer tails. Distant encounters and
strong binding can leave little material in the tail pickup. Those comparisons
must preserve that quiet result rather than normalize noise into a stream.

`TidalPatch` saves voice, all controls, requested note, velocity, exact signed
64-bit seed, and rendering model. The seed is canonical decimal text in JSON.
Models 1 and 2 fix their dynamics, reduction, source, and finite-window policies.
New recipes use model 2; saved model-1 recipes keep their original defaults and
rendering. Published model-1 baseline sample hashes are checked during generation.
Derived particles, descriptor curves, oscillator phases, and resonator states
are rebuilt. Macro and name edits retain the saved note, velocity, seed, and
model. The six finite model-2 recipes are in the shared factory roster and phone
picker. Native playback continues to use rendered samples.

In SYNTH, select TIDAL and PAIR or STREAM. The five timbral sliders are joined
by ROOT, VELOCITY, SOURCE (ORIGINAL or MATERIAL), and VARIATION SEED. SOURCE
selects the saved rendering model; changing it keeps the current encounter
controls. An invalid or overflowing seed keeps the previous valid variation.
Drafts, saved presets, SCRAMBLE, SEND TO PAD, and reopening a pad retain the
complete typed recipe. SPREAD uses the authored MIDI root rather than asking a
pitch detector to infer it from upper-rich material.

TIDAL on FRESH TAPE supplies sixteen dry, editable encounters. Every delivered
pad plays once. Their long buffers are filed as LOOP by the generic duration
classifier, but that does not enable sample looping or a held instrument.

## Model and sound

Preparation advances two mutually attracting centers and 2,048 massless particles
with softened forces and a deterministic fixed-step leapfrog integrator. Particles
do not attract one another or their centers. Initial conditions use one consistent
softened potential for center pericenter and particle circular speeds.

Model 1 uses unit center masses, softening 0.08, initial separation 8, and a
standard integration step of 0.005 dimensionless units. PASSAGE selects target
pericenter `3.8 - 3.1 * PASSAGE`; APPROACH selects asymptotic relative speed
`0.25 + 1.1 * APPROACH`. The full 22-unit trajectory maps to eight seconds at one
common scale. Source release ends at eight seconds, followed by 1.8 seconds of
passive modal decay, making a 9.8-second finite render. Diagnostic quality and
step overrides are recorded in diagnostic recipes and remain outside ordinary
production patch controls.

Thirty-two original radial/azimuthal cohorts retain source identity throughout
the encounter. The reducer measures radial extension, coherent motion, and
concentration without particle-particle force searches. Ordinary differential
rotation can broaden a cohort even in an undisturbed disk, so broadening alone
is not treated as a tail. Core and tail supply remain bounded and share the
same original population budget.

Affinity is a smooth distance-based audio descriptor, not proof of permanent
capture or escape. The prototype does not claim a complete physical bridge
classifier; its exposed afterstructure is driven by extension and evolving
cohort excitation.

The audio stage interpolates prepared controls, drives two contrasting pitched
populations at one requested root, and feeds stable damped tail modes. Displacement
does not become unrestricted musical pitch. Output is deterministic mono 44.1 kHz,
with standard 4× internal rendering, band limiting, decimation, DC control, and
shared melodic leveling. Preparation is independent of the requested pitch and
can be reused within a bounded process-local cache.

PAIR and STREAM share the encounter model. In model 2 they use the same two
material profiles, with different encounter defaults and slightly less root
support in STREAM. They are two starting gestures for one instrument. Model 1
retains its original voice-specific harmonic profiles.

## Rendering and edit cost

The geometry cache retains at most eight prepared settings. Its key contains
every dynamics input; note, velocity, source model, and TAIL do not change the
trajectory. A separate completed-audio cache retains at most 8 MiB of sample
data, keyed by voice, rendering model, note, velocity, exact seed, and all
resolved controls. Names and macro insertion order do not create different
sounds. Returned samples are caller-owned copies, so later pad processing
cannot damage a cached performance.

Production output and diagnostic taps share the same synthesis stages. The
source channels are independent and use one reusable oversampled work buffer;
their Float summation and separate conditioning order remain pinned by frozen
model-1 and model-2 references. Ordinary playback omits diagnostic traces and
derived taps. The diagnostic path remains available to the audition generator.

Completed cache hits avoid synthesis; changing an audible recipe input requires
a fresh render. Preview jobs are debounced and cooperatively cancellable, so
obsolete edits can stop instead of publishing an old result over the latest
controls. Desktop measurements and phone acceptance are separate evidence.
See [the rendering report](TIDAL_PERFORMANCE.md) for the benchmark method,
before/after timings, memory definitions, and remaining limits.

## Listening evidence

The second audition focuses on old/new source comparisons, population/core/tail
taps, and mechanism controls. Full old/new mixes target the same perceptual
loudness, with shared headroom per voice. Related diagnostic branches inherit
their full-mix reference gain; isolated quiet tails are not individually amplified.
The original macro sweeps, encounter interactions, and note/velocity comparisons
remain in the archived audition. Complete recipes, descriptor traces, source
hashes, and measured diagnostics accompany the audio.

The compensated **no tidal gradient** comparison preserves center motion and the
companion's uniform acceleration at each original center, while removing its
differential force on that population. Simply deleting the entire companion
force would let the accelerating center leave its disk behind and create a false
tail. Additional comparisons replace evolving tails with a fixed envelope,
collapse cohort controls, or deliberately reassign current-center identity.

Numerical convergence, finite audio, and audible usefulness are separate gates.
The listening question is whether the encounter changes structure, original
population timbres persist in displaced material, and TAIL reveals evolving
strands beyond ordinary decay or repeated echoes. This is the owner's verdict.

## Reproduction

From the repository root:

```sh
./gradlew --no-daemon test
./gradlew --no-daemon :synth:generateTidalAudition
./gradlew --no-daemon :synth:benchmarkTidal -PtidalBenchmarkOutput=/tmp/tidal-performance.json
./gradlew --no-daemon :synth:generateTidalKit -PtidalKitOutput=/tmp/tidal-kit
./gradlew --no-daemon :cli:run --args='synth TIDAL PAIR --all --midi 60 --velocity .75 --seed 0 --out /tmp/tidal-pair'
./gradlew --no-daemon :cli:run --args='synth TIDAL STREAM --all --midi 48 --velocity .75 --seed 0 --out /tmp/tidal-stream'
./gradlew --no-daemon :cli:run --args='synth TIDAL PAIR --patch /absolute/path/to/tidal.patch.json --out /tmp/tidal-replay'
```

The listening generator writes `testkit/tidal-audition/`. Its manifest records
the source revision, source-file hashes, clip hashes, gain policy, and diagnostics.
The retained frontend lives in `synth/src/test/resources/tidal-audition/`.
Android compilation, target-phone latency/playback, and MPC hardware acceptance
are separate checks. The kit generator requires a fresh destination directory
and writes both MPC generations, the editable kit, complete per-pad recipes,
verification files, and `tidal-kit.zip`. A successful export/read-back check is
separate from loading and listening on MPC hardware.
