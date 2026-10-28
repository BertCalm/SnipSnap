# REGISTERS — organ, reed and transistor audition

REGISTERS adapts the organ/reed family idea in the
[XOctave architecture](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/octave-engine-architecture.md)
to SnipSnap's deterministic offline renderer. PIPE and MUSETTE form the working
audition baseline. The owner selected model-3 PIPE after listening to the stronger
chiff and sustained airflow. Model 4 introduced TRANSISTOR; model 5 adds a fuller same-pitch foundation after
the first listening verdict asked for more depth. The existing PIPE/MUSETTE
synthesis and six model-3 presets are preserved.

PIPE uses twelve harmonic partials with registration-inspired dark/principal
weighting, staggered speech, a brief chiff and a seeded wind field. MUSETTE
combines three odd-harmonic reeds with symmetric frequency offsets, a finite
bellows swell and excitation-dependent saturation. These are original musical
approximations, not measured historical instruments or physical free-reed models.
All three voices are dry and use no external samples or room effect.

The [published audition](https://snipsnap-registers-audition.bertcalm.chatgpt.site)
opens with First listen across all three voices and a direct TRANSISTOR entry.
The Current / With airflow and earlier Original / More chiff comparisons remain
available in their own tabs. The first listening verdict
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

The airflow comparison tab retains twelve Current R2 / With airflow R3 pairs,
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

## TRANSISTOR — R4

TRANSISTOR is a Farfisa-inspired electronic combo-organ voice. It uses a separate
bandlimited square-wave rank renderer, octave registration, fast keying, a short
electronic key-click, vibrato and transistor saturation. It is a musical
approximation, not a measured emulation of a particular instrument.

| Control | TRANSISTOR meaning |
| --- | --- |
| COLOR | Blend fundamental and octave ranks |
| AIR | Brief electronic key-click on a finite note |
| EDGE | Transistor drive |
| SPREAD | Subtle detuning between ranks |
| MOTION | Vibrato depth around a 5.5 Hz rate |
| TUNE / HOLD | Shared pitch and finite-length/held-loop controls |

Three new C4 starting points are Compact Keys, Clean Combo and Garage Buzz.
Original TRANSISTOR recipes use renderer model 4 and reject models 1–3.
The current three factory settings use model 6; saved model-4 and model-5 recipes
retain their original sound.
PIPE/MUSETTE still replay all earlier models; their six presets remain pinned
to model 3. Missing model metadata continues to mean legacy model 1.

Run `./gradlew :synth:generateRegistersTransistorAudition` to extend a preserved
R3 pack with new TRANSISTOR identities, control sweeps, velocity examples,
interaction studies, presets and held tones. The R4 pack preserves earlier WAVs,
recipes, comparison pairs and review scopes. Old browser notes remain attached
to the sound they describe; edits save under the new revision.

## TRANSISTOR body — R5

The next listening verdict asked for a little more depth. Model 5 adds a rounded
8-foot foundation at the played pitch, gentler upper-rank weighting and quiet
SPREAD-controlled detuned foundation ranks. The foundation stays outside the
transistor saturation stage so EDGE retains its bite while the sustained note
has more low-partial weight. The dry mono format and seven controls stay intact.

The original electronic click and keying are retained. The fuller body blends in
over 20–80 ms on finite notes and is fully present in a held loop. Both versions
share pitch, duration, velocity and macro settings; this is a timbre comparison
at the same listening level. Model 4 stays replayable exactly, including the
published PCM fixtures. PIPE and MUSETTE are unchanged.

Run `./gradlew :synth:generateRegistersTransistorDepthAudition` with
`-PregistersR4Baseline=<published R4 archive>` to build the body comparison.
R5 retains all 116 earlier recordings and recipes and adds 31 model-5 cases.
Eleven Original R4 / More body R5 pairs cover six identities, three presets and
two held tones. R1–R4 listening notes keep their original sound identities;
changed model-5 recordings start with separate reviews. The owner accepted the
fuller body in the browser audition. Physical phone/MPC listening remains open.

## TRANSISTOR sustain and render cost — R6

Luthier's follow-up found that the short held period rounded the factory SPREAD
settings to unison. Compact Keys at .12 lost its bank detuning on all nine exported
roots. Model 6 keeps the R5 foundation and finite-note registration, and replaces
that rounding with smooth periodic phase movement in held side banks. The peak
bank frequency swing before common vibrato follows the continuous SPREAD law. This is a looping ensemble
adaptation, not constant free-running detuning: a constant .075 Hz offset would
need a roughly 13-second cycle at C3. The existing roughly two-second loop stays.

The side-bank phase and its derivative match the finite bank at clock time 400 ms,
near the center of the instrument's 350–450 ms handoff. Both use the same
vibrato-modulated clock. `effectiveSpreadHz` records the nominal peak swing for this mode, and
`spreadModulationHz` identifies its loop-periodic rate. Legacy probes continue to
report constant bank offsets with a zero modulation rate.

Model 6 evaluates the foundation's third and fifth harmonics from one cosine per
bank, using Chebyshev identities. The original bright registration is evaluated
only while it contributes to the first 80 ms. Held synthesis warms the filters
for 1024 output frames (23.2 ms), then synthesizes two complete independent cycles.
At the minimum 500 Hz voice cutoff this exceeds 70 pole time constants. The same
post-filter recurrence and seam guards still compare separate generated cycles;
the exported buffer is never used as its own reference. Models 4 and 5 keep their
original arithmetic and whole-cycle preroll.

A warm Java 21 desktop benchmark with 12 balanced interleaved samples per model
measured finite renders at 129.95 → 100.56 ms, held renders at 270.52 → 169.41 ms,
and keyboard previews at 378.76 → 255.42 ms. These are within-run reductions of
22.6%, 37.4% and 32.6%; the finite timings varied substantially. They do not predict
phone performance. Twelve finite comparisons (C3/C4/C5/C7, SPREAD 0/.12/.5) retained
identical conditioned Float samples. Small held SPREAD .05/.12/.18 changed the
gain-normalized waveform at C3/C4/C5, with relative RMS differences .000919–.015936.

Run `./gradlew :synth:generateRegistersTransistorSustainAudition` with
`-PregistersR5Baseline=<published R5 archive>`. Seven comparisons exercise the
actual `Keys.registersPad` attack and loop: Compact Keys and Garage Buzz at C3,
C4 and C5, plus a zero-SPREAD Clean Combo control. Audition sequences retain the
instrument head and play three complete wraps, with an audition-only 4 ms terminal
fade. They do not repeat the attack as if it were a held loop. All 147 earlier
recordings and their recipes remain unchanged, including R5 review identities.

Why it is built this way:

- Periodic phase movement keeps low SPREAD settings active within bounded memory
  and render work; static detuning cannot recur exactly in such a short period.
- Matching the handoff clock avoids resetting the ensemble when the attack ends.
- The shorter warmup saves repeated synthesis work while retaining independent
  evidence that the conditioned cycle really repeats.
- Versioned arithmetic preserves accepted sounds. The owner reports that R6 sounds
  very much the same; phone timing and hardware listening are unmeasured.

## Depth direction study

R6 fixed low-SPREAD continuity and reduced render work, but did not supply the
stronger depth the owner wanted. The separate `directions/` audition compares
current-model recipes before changing the engine or factory defaults. At C3, C4
and C5, Current is Compact Keys; Warmer body changes COLOR to .08 and EDGE to .10;
Richer ensemble changes SPREAD to .65 and MOTION to .32. Other controls, pitch and
velocity stay the same. Warmer body reduces upper ranks and clipping; it does not
add a 16-foot rank or a room effect.

Run `./gradlew :synth:generateRegistersDepthDirectionsAudition` with the optional
`-PregistersOutput=<directory>`. The nine finite audition sequences use actual
`Keys.registersPad` attacks followed by three complete held-loop repeats and a
4 ms final fade. The generator verifies recipe replay, independent recurrence,
PCM24 readback and matched loudest-window RMS .03. The three Current clips are
byte-identical to the corresponding published R6 clips in both raw and matched
form. Publish this directory as the existing audition site's `directions/`
subdirectory, preserving the main pack and its listening-note identities.

Independent PCM checks measured gain-removed sustained RMS differences of
12.8–17.1% for Warmer body and 21.7–29.2% for Richer ensemble versus Current.
Those measurements establish stronger waveform contrast, not perceived depth or
preference. The owner selected the richer ensemble direction. It is now the
`Rich Ensemble` factory preset, using the exact SPREAD .65 / MOTION .32 setting
heard in this study. It is appended after the original three TRANSISTOR presets;
the other nine factory settings and the model-6 renderer stay the same.

The direction page now opens on Rich Ensemble without autoplay and offers its
nine-zone MPC instrument. Export it with
`./gradlew :cli:run --args='synth REGISTERS TRANSISTOR --preset 4 --instrument --release .6 --out /path/to/output'`.
The package preserves the keyed attack, uses the actual final held-loop region,
and releases over .6 seconds when the key is lifted. The C3/C4/C5 instrument
samples match the chosen audition before its repeated playback and terminal
fade. Physical phone/MPC listening is still pending.

Why it is built this way:

- Two separate settings distinguish a warmer harmonic balance from ensemble
  movement, so the next change can follow the owner's preferred direction.
- Matched levels reduce loudness as a reason to choose one setting.
- Existing renderers and the exact current reference keep this a small,
  reproducible listening decision before another model revision.
- A named preset carries the selected sound into normal rendering and instrument
  export while saved patches continue to reproduce their own settings.

## Playing and saving

The shared controls are COLOR, AIR, EDGE, SPREAD and MOTION, plus TUNE and HOLD.
COLOR shapes harmonic weighting; AIR changes articulation and breathing;
EDGE shapes pipe speech or reed saturation; SPREAD sets ensemble/reed offsets;
MOTION supplies tremulant/vibrato. TUNE selects MIDI 48–72 when no explicit
note is supplied. An explicit MIDI note overrides TUNE.

`RegistersPatch` implements the shared patch contract and also reads the original
audition recipes. It stores the voice, complete macro values, note, velocity and
renderer model. REGISTERS appears in the phone's SYNTH source picker with PIPE,
MUSETTE and TRANSISTOR, ten presets, saved patches and the shared SEND TO PAD workflow.
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
./gradlew :cli:run --args="synth REGISTERS TRANSISTOR --preset 1 --out /tmp/registers"
./gradlew :cli:run --args="synth REGISTERS PIPE --preset 1 --instrument --release 0.6 --out /tmp/registers"
```

The picker and export adapters leave the model-3 synthesis arithmetic unchanged.
Rendering accepts cancellation during synthesis so leaving a long instrument
render does not keep the phone busy. Actual phone and MPC listening remain a
hardware check after the automated render, package and playback checks.

Integration verification covers synthesis, presets, patch loading, velocity,
keyboard playback, CLI rendering and package readback. Fixed PCM24
fixtures preserve the published finite and held sounds. Instrument tests check
the first 80 ms, overlap energy, actual XPM/XTY/WAV loop markers, phone-side
reference pitch and sustain/release at 48 kHz, legacy models, silence and cancellation.
The Android debug APK assembles with all four native targets.

## Finite notes and held material

Finite renders retain their speech/bellows onset and release. HOLD at .975 or
above exports a settled loop with no initial articulation. Carrier frequency is
fitted to whole sample frames; reed offsets, motion and the seeded wind components
are fitted to the same period. The manifest records the effective frequencies.
Very small requested offsets may round to zero in the held period for PIPE,
MUSETTE and TRANSISTOR models 4–5. TRANSISTOR model 6 uses the periodic ensemble
adaptation described above instead.

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
