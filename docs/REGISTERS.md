# REGISTERS — first organ/reed audition

REGISTERS adapts the organ/reed family idea in the
[XOctave architecture](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/octave-engine-architecture.md)
to SnipSnap's deterministic offline renderer. This first pass contains PIPE
and MUSETTE. The dry sound awaits the owner's listening verdict.

PIPE uses twelve harmonic partials with registration-inspired dark/principal
weighting, staggered speech, a brief chiff and a seeded wind field. MUSETTE
combines three odd-harmonic reeds with symmetric frequency offsets, a finite
bellows swell and excitation-dependent saturation. These are original musical
approximations, not measured historical instruments or physical free-reed models.
Neither uses external samples or a room effect.

## Playing and saving

The shared controls are COLOR, AIR, EDGE, SPREAD and MOTION, plus TUNE and HOLD.
COLOR shapes harmonic weighting; AIR changes articulation and breathing;
EDGE shapes pipe speech or reed saturation; SPREAD sets ensemble/reed offsets;
MOTION supplies tremulant/vibrato. TUNE selects MIDI 48–72 when no explicit
note is supplied. An explicit MIDI note overrides TUNE.

`RegistersPatch` is a versioned standalone audition recipe. It stores the voice,
complete macro values, note, velocity and renderer model. It deliberately remains
outside the app engine registry while the sound is under review. Existing
TONEWHEEL and PRISM patches are unchanged. The prototype is not yet a phone
picker entry or an MPC instrument export.

## Finite notes and held material

Finite renders retain their speech/bellows onset and release. HOLD at .975 or
above exports a settled loop with no initial articulation. Carrier frequency is
fitted to whole sample frames; reed offsets, motion and the seeded wind components
are fitted to the same period. The manifest records the effective frequencies.
Very small requested offsets may round to zero in the held period.

Loop evidence compares separately synthesized adjacent periods after conditioning
and decimation. The renderer requires relative cycle difference below 1e-5 and
seam difference below `Keys.MAX_SEAM_ERROR` (1e-3). Repeating an exported WAV alone
is not evidence that its synthesis states recur. The audition repeats three copies
of each verified period to make the wrap easy to hear.

Velocity changes excitation and harmonic response before loudness matching.
Matched audition clips intentionally share a listening level so timbre can be
compared; raw playback retains their level differences. Live aftertouch and
cross-note pressure interactions are not part of this sample renderer.

## Reproduce the audition

```text
./gradlew :synth:test --tests '*RegistersTest*'
./gradlew :synth:generateRegistersAudition
```

The output is `testkit/registers-audition/`: actual Kotlin-generated mono
44.1 kHz PCM24 WAVs, raw/matched partners, complete recipes, measured diagnostics,
source/audio hashes and a standalone listening page. Existing TONEWHEEL and PRISM
ORGAN references use their supported pitches without clamping or transposition.
The shared reference notes are C3 and C4; the new voices also have an A4 probe.

Start with the twelve first-listen clips, then the velocity and control studies.
Compare the existing organ references at the shared pitches and repeat the held
material. Listening notes remain in the browser and can be exported as JSON.
Numerical checks establish reproducibility, bounds and loop behavior; phone/MPC
playback and sonic approval remain separate checks.
