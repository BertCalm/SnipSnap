# REGISTERS — organ/reed audition

REGISTERS adapts the organ/reed family idea in the
[XOctave architecture](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/octave-engine-architecture.md)
to SnipSnap's deterministic offline renderer. This first pass contains PIPE
and MUSETTE. The owner selected model-3 PIPE as the working baseline after
listening to the stronger chiff and sustained airflow. MUSETTE remains provisional.

PIPE uses twelve harmonic partials with registration-inspired dark/principal
weighting, staggered speech, a brief chiff and a seeded wind field. MUSETTE
combines three odd-harmonic reeds with symmetric frequency offsets, a finite
bellows swell and excitation-dependent saturation. These are original musical
approximations, not measured historical instruments or physical free-reed models.
Neither uses external samples or a room effect.

The [published audition](https://snipsnap-registers-audition.bertcalm.chatgpt.site)
opens with Current / With airflow comparisons and retains the earlier
Original / More chiff comparisons. The first listening verdict
asked for more PIPE chiff. Renderer model 2 adds a separate short speaking burst
to finite PIPE notes. A seeded noise source feeds a note-relative presence band
and broad air band, with a 2 ms rise and smooth ending. EDGE sets its strength;
EDGE zero bypasses the addition. The burst lasts roughly 55–60 ms. The original
tone and wind path remain intact, including the original quieter chiff.

Models 1 and 2 remain supported for old recipes. Missing model fields decode to
model 1; new recipes explicitly save their current renderer model. Model 2 left
MUSETTE and held PIPE audio unchanged. Held material begins at the settled tone and therefore has no
chiff onset. Twelve comparison pairs preserve the published R1 WAVs byte for
byte and compare them with model 2 at the same settings and listening level.
Earlier browser notes remain visible with their original revision and audio
provenance; new listening edits are saved separately.

The R2 checks pass nine tests, including pre-edit model-1 float hashes,
unchanged MUSETTE/held samples and an exact settled PIPE tail from 120 ms onward.
At C4, matched 20–80 ms energy in the 1.8–12 kHz band rises by 8.14 dB at
defaults, 3.61 dB for the restrained gesture and 13.45 dB for expressive speech.
The archived comparison metrics use their separately documented first-80-ms
window; different bands and windows produce different numbers. These are
transient measurements, not a sonic approval.

## Sustained airflow — R3

The next listening verdict accepted the stronger attack and asked for a little
airflow through the pipe's sustain. Model 3 adds a separate soft wind field under
PIPE. Dense deterministic noise passes through warm and bright low-pass shades
and a gentle high-pass, with restrained pressure variation. AIR controls its
strength; zero bypasses the new layer. The initial model-2 speaking burst and
harmonic source remain intact. Finite airflow rises from 90–125 ms and follows
the note's release, leaving the first 80 ms of raw conditioned audio unchanged.

The default airflow is calibrated to approximately 28 dB below the body RMS in
the settled region. Held notes use a full-period seeded noise field and continuous
filter state through preroll and independently synthesized candidate cycles.
The noise must recur with the carrier and modulation before export. The manifest
records aligned airflow/body RMS taps and their analysis bounds.

The current audition opens with twelve Current R2 / With airflow R3 pairs,
including two held notes. The earlier R1/R2 chiff comparison remains available
with its archived WAVs. All three review histories retain their sound identity;
new feedback stays in the R3 namespace. The original models replay their earlier
sounds rather than inheriting the new wind layer.

The R3 pack contains 85 listening positions and 170 WAVs: 56 current cases,
twelve archived R1 clips and seventeen archived R2 clips. Twelve synthesis tests
pass, including legacy model hashes, unchanged attack and AIR-zero controls,
sustained airflow continuity, and mixed-signal and isolated-airflow recurrence.
Independent PCM readback verifies every WAV and recipe hash, all 24 finite PIPE
attacks through 80 ms, and the 30 unchanged MUSETTE/reference cases. At the default
AIR setting, measured added airflow is approximately 27.96 dB below the settled
body. These checks verify the addition and its boundaries; the listener decides
whether its level and character suit PIPE.

## Playing and saving

The shared controls are COLOR, AIR, EDGE, SPREAD and MOTION, plus TUNE and HOLD.
COLOR shapes harmonic weighting; AIR changes articulation and breathing;
EDGE shapes pipe speech or reed saturation; SPREAD sets ensemble/reed offsets;
MOTION supplies tremulant/vibrato. TUNE selects MIDI 48–72 when no explicit
note is supplied. An explicit MIDI note overrides TUNE.

`RegistersPatch` implements the shared patch contract and also reads the original
audition recipes. It stores the voice, complete macro values, note, velocity and
renderer model. REGISTERS appears in the phone's SYNTH source picker with PIPE
and MUSETTE, six presets, saved patches and the shared SEND TO PAD workflow.
TUNE edits change the explicit note; other edits preserve its note, velocity and
model. Imported audition notes align the slider without changing the sound.

MAKE INSTRUMENT uses nine C3–C5 zones spaced by minor thirds and the existing
phone/MPC keygroup package. Each zone retains the first 350 ms of its finite
speech, then blends over 100 ms into the independently verified held region.
The held phase advances on the same time axis as the attack to avoid a carrier
cancellation at the join. The nonzero loop marker selects a complete held cycle
at the end of the sample, which repeats while the key is held. RELEASE controls the player’s
key-up fade. The original onset-free audition WAVs remain unchanged.

The CLI can render saved presets or write the same instrument package:

```text
./gradlew :cli:run --args="synth REGISTERS PIPE --preset 1 --out /tmp/registers"
./gradlew :cli:run --args="synth REGISTERS PIPE --preset 1 --instrument --release 0.6 --out /tmp/registers"
```

The picker and export adapters leave the model-3 synthesis arithmetic unchanged.
Rendering accepts cancellation during synthesis so leaving a long instrument
render does not keep the phone busy. Actual phone and MPC listening remain a
hardware check after the automated render, package and playback checks.

Integration verification passes 213 synthesis/preset/patch/velocity tests,
91 shell tests, three CLI tests and ten package/readback tests. Fixed PCM24
fixtures preserve the published finite and held sounds. Instrument tests check
the first 80 ms, overlap energy, actual XPM/XTY/WAV loop markers, phone-side
reference pitch and sustain/release at 48 kHz, legacy models, silence and cancellation.
The Android debug APK assembles with all four native targets.

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
./gradlew :synth:generateRegistersAudition -PregistersBaseline=/path/to/verified-r1-archive -PregistersR2Baseline=/path/to/verified-r2-archive
```

The output is `testkit/registers-audition-r3/`: actual Kotlin-generated mono
44.1 kHz PCM24 WAVs, raw/matched partners, complete recipes, measured diagnostics,
source/audio hashes and a standalone listening page. Existing TONEWHEEL and PRISM
ORGAN references use their supported pitches without clamping or transposition.
The shared reference notes are C3 and C4; the new voices also have an A4 probe.

Start with the twelve first-listen clips, then the velocity and control studies.
Compare the existing organ references at the shared pitches and repeat the held
material. Listening notes remain in the browser and can be exported as JSON.
Numerical checks establish reproducibility, bounds and loop behavior; phone/MPC
playback and sonic approval remain separate checks.

The R2 pack contained 56 current cases plus twelve preserved original PIPE clips,
for 68 listening positions and 136 WAVs. The generator verifies the supplied R1
manifest, audio, recipes and archived source fingerprints before creating pairs.
The original archive is read only. First-80-ms RMS and band energy are measured
on both raw and matched PCM exports and accompany the comparison metadata.

The first R1 pack contained 56 cases: twelve identity clips, six velocity clips,
thirty control comparisons, four held tones and four existing-organ references.
Its six prototype tests passed. Independent WAV readback verified 112 PCM24 files
and 56 recipe JSONs against their hashes and recorded frame counts. The maximum
matched peak is .09143 and the loudest-window matching error is below 4.1e-9.
The prototype's pre-conditioning peak is at most .47740. The two TONEWHEEL raw
references require a recorded PCM peak guard; prototype clips require none.
All four held recurrence comparisons are below 3e-13. The held high settings
fit PIPE spread to .51910 Hz and MUSETTE spread to 4.15279 Hz; the low settings
fit to zero. These measurements establish the first pack's numerical behavior,
not its musical acceptance.
