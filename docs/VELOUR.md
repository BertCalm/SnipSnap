# Velour Close, Velour and Velour Bloom

## Approved Round 5 shelf integration (2026-11-13)

The owner selected the recording-derived tambourine on 2026-11-10 and accepted the
silence-trimmed delivery on 2026-11-12. `VelourApprovedKits` freezes that exact bank
from audition source commit `147679e4a88d61ad1fdb735a0b75e1881b1baad0`.
Its 144 PCM24 files are copied without re-rendering or normalization from the three
published Round 5 archives. A manifest pins every file hash and its original level,
class and choke group. Shelf creation gives each kit its own mutable PCM buffers.

FRESH TAPE now offers **VELOUR CLOSE**, **VELOUR** and **VELOUR BLOOM**, at 84 BPM.
A01–A12 keep their approved roles, including dry kick/snare at A05/A06. A13–A16
are low conga, high bongo, tambourine and woodblock. Every pad has soft, medium
and hard layers. Only the two hats share a choke group.

A15 is reference-derived spectral resynthesis, not an independent physical model.
Its CC0 source is VSCO Community Edition: Sam Gossner and Simon Dalzell, sample
cutting by Elan Hickler / Soundemote. The reference license and provenance ship
under `synth/src/main/resources/velour/approved-v1/`; sample provenance also travels
in the saved kit. These sources are unrelated to the original video's audio.

The trim retains every sample through the last signal above -100 dBFS, then 25 ms
of safety and a 5 ms sub-threshold fade. Late echoes survive quiet gaps. The Close
snare is about 0.33 s; the longer Velour and Bloom tails remain intact. The checked
bank totals 218.86 s across all layers instead of 478.72 s before trimming.

### Why it is built this way

- Loading the final approved samples preserves the precise source, effect treatment,
  gain and trim the owner selected; another DSP port would introduce a new comparison.
- The existing model-1 boards and `VelourKits` renderer are kept separate, so old
  saved boards and their parity tests retain their original behavior.
- The shelf bank carries source provenance instead of an editable synth recipe:
  replaying a board over already-printed samples would apply the effects twice.
- Three touch layers are retained verbatim. Inventing variants during integration
  would change the accepted sound; authored round robin needs its own delivery.

### Acceptance ledger

- **Heard:** owner-approved tambourine and trimmed Round 5 baseline.
- **Software checks:** `VelourApprovedKitsTest` pins all 144 hashes, layer energies,
  buffer ownership and trimmed lengths. `VelourApprovedStarterTest` checks shelf
  registration, save/load, every MIDI velocity's playback window, PCM preservation
  and both MPC program read-backs. Test execution results belong to the delivered
  commit/CI status, not to these test descriptions.
- **Velocity character:** existing recorded/resynthesized and procedural layers are
  preserved. Some inherited SKIN voices are chiefly gain-scaled; this does not certify
  independent strike-character changes for every pad.
- **Round robin:** open. This bank has one strike per velocity; no duplicated takes
  or volume-only copies are counted as round robin.
- **Genre shelf:** open. Close/Velour/Bloom are treatments of the same layout, not
  three distinct genre kits.
- **Percussion rolls/risers:** not authored in this baseline. Individual finite pads
  retain the approved layout.
- **Physical playback:** Android and MPC audio behavior is unverified. Export
  read-back and JVM playback-window checks do not substitute for a hardware audition.
- **Melodic/keygroup/chord/STRUM gates:** not applicable to this finite drum sample bank.

The sections below document the earlier model-1 renderer and boards. Their original
kit generator remains available for regression comparisons, not as the Round 5 source.


Velour is a fixed drum starter and reusable compound board. The owner selected
the C kick/snare sources, then accepted both the restrained atmosphere-through-amp
treatment and the stronger Bloom treatment. No new synth engine is registered.

## Use

FRESH TAPE → skins group → VELOUR or VELOUR BLOOM builds sixteen pads with three
touch zones. The C source bank is stored as versioned synthetic Float PCM resources
so the selected drum body is not changed by a synthesis port. The added hats, toms,
cross stick, shaker and ride use SKIN, model-local DC cleanup and explicit touch
level scaling, and remain listening candidates. A05/A06 keep
dry C kick/snare alongside the printed treatments. Closed and open hats share the
normal hat choke group; the ride is PERC and is not choked by hats.

Both starters are fixed rendered sample banks. Their WAVs and source provenance
travel with the kit; their pads intentionally have no fake synth recipe or fx-only
recipe which would apply the treatment twice. Rebuilding a starter uses the frozen
source bank and native renderer. Regular captured-audio editing remains available.
This is not an editable C synthesis engine. The independent compound board is saved
by `BoardRecipe` as `velour` or `velour-bloom`, model/version-one mapping, default
amount 1. Zero is a hard bypass; intermediate amount blends the whole processed
result against the original at fixed source gain, then applies linear attenuation
only if needed to keep the printed sample below .95 peak. Raw bus diagnostics
retain the reference gain. Existing board encodings are
unchanged. Android captions and choice layout have source changes but no SDK build
or phone verification in this session.

## Signal path

Parallel short chorus / four finite eighth-note echoes / windowed onset grains →
wet-only finite room and tone filter → dry+wet sum → fixed-gain asymmetric tube
surrogate → envelope sag → speaker filters. Mono or stereo 44.1 kHz only; stereo
channels are processed independently with the same event schedule. Generic board
processing treats the pad as one strike at zero. It is not a live onset detector.

The explicit `renderBus` API receives same-length dry and send buffers, sorted
snare-onset seconds, and the treatment. The API is used for parity/audition phrases.
It preserves the shared-bus interaction. Independent printed pad effects in an MPC
kit do not reproduce one common amp or grain memory when hits overlap. No real-time
bus processor is claimed.

The generic board appends 3.15 seconds of release and refuses source+release longer
than 16 seconds. Bus buffers include their own release and are capped at 16 seconds
and 256 ordered onsets. The echo clock is explicitly fixed at 84 BPM in model 1.
There is no HOLD or seamless-loop claim. Existing FxChain's one-second tail budget
is unchanged; a compound board owns its explicit finite tail.

## Model provenance and numerical behavior

`experiments/velour/` retains the exact approved Python source and effect model.
`freeze_assets.py` creates the synthetic C source bank (soft .42, medium .7, hard 1),
the two finite room kernels, the deterministic grain draw table, filter coefficients,
and independent Python bus fixtures. No source audio from the uploaded video or
commercial pedal is shipped. Resource `manifest.json` pins SHA-256 hashes and settings.
No NumPy/SciPy dependency is needed on the phone.

The native board preserves the audition's Double SOS coefficients and centered FIR
4x resampling. It intentionally does not substitute VALVE's different peak-relative
gain law. Shared `Fft` performs room convolution. Coefficients and procedural tables
are frozen model data; the runtime does not introduce a replacement random generator.
The last two room spectra are cached under treatment and transform size; source input
and amp state are never cached, so concurrent calls cannot share envelope state.

All stages are finite feed-forward operators. The grain overlap denominator is at
least one; decay tap gains decrease; the room has finite support and bounded L1 norm;
the tube is bounded; the sag denominator is at least one. The output is DC-cleaned
and faded. Kits apply one attenuation per role shared by its three zones only when
necessary to keep peak below .95. Quiet strikes are never normalized up to full hits.

## Verification and reproduction

* `./gradlew --no-daemon :synth:test --tests '*VelourTest'` compares both treatments
  against independent Python full-bus WAVs with a relative-RMS error gate of -80 dB.
* Checks cover source hashes, deterministic replay after cache eviction, hard bypass,
  recipe round trips, source ownership, silence, malformed/oversized inputs, source
  role provenance, touch levels, finite samples, peak and DC bounds.
* `./gradlew --no-daemon :synth:generateVelourKit` exports both three-zone kits, MPC 3
  tracks with MPC 2 XPM twins, a native/reference listening page and verification.txt.
  The generator reads both programs back and compares every decoded PCM24 zone to
  its source. Output is under `testkit/velour-kit/` unless `-PvelourOutput=...` is set.
* `./gradlew --no-daemon test` is the full JVM regression gate. Hardware playback,
  mobile timing, Android rendering and listening to the added kit voices are separate
  checks. Host measurements are not phone performance claims.

The original approved sound is documented in
[the design and audition ledger](superpowers/specs/2026-11-08-velour-kit-design.md).
As built on 2026-11-08: both native/reference comparisons passed at -151.09 dB
(Velour) and -150.45 dB (Bloom) relative RMS error. All five Velour tests passed.
Both exports read back successfully in both program formats; all 96 PCM24 zone
files matched their rendered source within 2e-7. Render plus export took 12.79 s
and 11.23 s on the cloud JVM. These are not phone timings or hardware acceptance.
The original raw bus peaks exceed full scale (1.408 / 1.499); parity retains that
calibration, while shared listening gain and printed-sample attenuation prevent
PCM clipping. The initial dry-tom DC finding was corrected only for the new
SKIN candidates. C source assets were unchanged.

## Why it is built this way

1. Frozen synthetic sources preserve the exact drum identity selected by listening.
2. A separate compound board keeps the requested effects-before-amp order without
   changing the sound of every existing rack recipe.
3. Two treatments expose the two accepted characters without a new control maze.
4. Printed kits remain portable and reproducible; honest source metadata avoids a
   recipe which would mistakenly reprocess its own already-wet sample.
5. New kit voices remain candidates: matching their family does not establish that
   the owner has approved them.

## Regression-run status

The full `./gradlew --no-daemon test` run was interrupted by the workspace
restart after 3,438 logged test passes and no logged failures. It did not return
a final Gradle result and is **not** recorded as a passing full suite. The
Velour-specific suite and generated-export checks completed successfully before
the restart. Broader CI, Android compilation and hardware playback remain
separate gates.

The post-restart shell test compilation exposed an existing call in
`BeatScheduleTest` which omitted `forEachNoteDue`'s required `lap` argument.
The same omission was present at the base commit. Supplying `0L` repairs that
test call without changing production scheduling behavior.

## Round-robin listening candidates

The opt-in [Round 6 candidate](VELOUR_ROUND_ROBIN.md) adds four sample-derived
takes per velocity using the approved bank as take 1. It does not replace the
approved shelf defaults or close the MPC round-robin gate. Generate the full-grid
native/SFZ/DecentSampler audition with `:synth:generateVelourRoundRobin`.
