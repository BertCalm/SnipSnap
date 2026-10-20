# TIDAL — first finite listening prototype

TIDAL turns a restricted two-center encounter into a pitched sound. Two original
populations retain different harmonic fingerprints as their disks deform into
surviving cores and extended streams. The defining gesture is gather, encounter,
and unravel; the late sound should contain evolving ringing strands.

This milestone implements PAIR and STREAM, complete replayable recipes, dry
listening comparisons, and a prototype kit. It is a musical adaptation of a
softened restricted gravitational model, not a calibrated galaxy prediction.
The remaining proposed voices, 36-preset bank, phone picker, and HOLD
reconstruction remain later work. Listening acceptance is still open.

The [private audition](https://tidal-snipsnap-audition.bertcalm.chatgpt.site)
contains 58 cases with raw/matched WAVs, A/B listening, replayable recipes,
descriptor traces, and a ten-clip starter download. Start with STREAM for the
developed afterstructure; PAIR is a distant compact-core reference whose tail
tap is effectively silent at the delivered PCM16 level.

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
Model 1 fixes the dynamics, reduction, source, and finite-window policies.
Derived particles, descriptor curves, oscillator phases, and resonator states
are rebuilt. Macro and name edits retain the saved note, velocity, seed, and
model. Provisional recipes remain outside the shared factory roster and phone
picker. Native playback continues to use rendered samples.

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

The audio stage interpolates prepared controls, drives two contrasting harmonic
populations at one requested root, and feeds stable damped tail modes. Displacement
does not become unrestricted musical pitch. Output is deterministic mono 44.1 kHz,
with standard 4× internal rendering, band limiting, decimation, DC control, and
shared melodic leveling. Preparation is independent of the requested pitch and
can be reused within a bounded process-local cache.

PAIR and STREAM share the encounter model. Their defaults and source spectra
differ; STREAM eases root support slightly and exposes a brighter population
profile. These are two starting characters for one instrument, not distinct
gravitational laws. The cache retains at most eight prepared settings and does
not retain rendered audio.

## Listening evidence

The audition contains native raw and matched WAVs, population/core/tail taps,
macro sweeps, encounter interactions, note/velocity comparisons, and mechanism
controls. Related diagnostic branches share the reference gain; isolated quiet
tails are not individually amplified. Complete recipes, descriptor traces,
source hashes, and measured diagnostics accompany the audio.

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
./gradlew --no-daemon :cli:run --args='synth TIDAL PAIR --all --midi 60 --velocity .75 --seed 0 --out /tmp/tidal-pair'
./gradlew --no-daemon :cli:run --args='synth TIDAL STREAM --all --midi 48 --velocity .75 --seed 0 --out /tmp/tidal-stream'
./gradlew --no-daemon :cli:run --args='synth TIDAL PAIR --patch /absolute/path/to/tidal.patch.json --out /tmp/tidal-replay'
```

The listening generator writes `testkit/tidal-audition/`. Its manifest records
the source revision, source-file hashes, clip hashes, gain policy, and diagnostics.
The retained frontend lives in `synth/src/test/resources/tidal-audition/`.
Android compilation, target-phone latency/playback, and MPC hardware acceptance
are separate checks; the JVM environment does not include an Android SDK.
