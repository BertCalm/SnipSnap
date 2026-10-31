# OVERLAP: finite bank and held instruments

OVERLAP adapts the [XOmnibus guide](https://github.com/BertCalm/XO_OX-XOmnibus/blob/main/Docs/engines/overlap_synthesis_guide.md)
as a deterministic finite six-delay resonator in `:synth`. The owner approved
the twelve musical recipes for factory registration. They now enter the SYNTH
picker and a fixed sixteen-pad FRESH TAPE starter through the unchanged model-2
renderer. The [private audition](https://overlap-snipsnap-audition.bertcalm.chatgpt.site)
retains audition revision 3's twelve recipes and three scheduled studies, with
the MPC kit handoff alongside them. The
[second listen](https://overlap-snipsnap-audition.bertcalm.chatgpt.site/r2/index.html)
and [original audition](https://overlap-snipsnap-audition.bertcalm.chatgpt.site/r1/index.html)
remain available. The first, second and musical auditions keep their existing
audio; factory registration changes their availability in the app.
Explicit HELD playback now adds the accepted steady articulation to saved
phone instruments while preserving those finite recipes and their sound.

## Approved factory bank

`OverlapMusicalBank` contains the twelve complete approved model-2 recipes,
grouped by musical role. Each saves topology, all six macros, reference MIDI
note and velocity. `OverlapFactoryPresets` supplies these patches to
`Presets.forVoice`, `Presets.byName` and `Presets.all`. Its separate promotion
table accepts complete saved recipes, retaining their model and other state.
The earlier six `OverlapPresets` control references stay separate.

| Family | Recipes |
| --- | --- |
| Plucks | GLASS THREAD, HOLLOW PIN, CUP STRIKE, CHORD SEED |
| Finite textures | OPEN WEAVE, FIFTH CLOUD, SILVER VEIL, LOW HALO |
| Breaths | LOOSE TIDE, COMMON TIDE, PAIR PULSE, CIRCUIT RUN |

Plucks use a finite strike without breathing or continuing excitation. Textures
use extended finite excitation; LOW HALO also explores slow gathered swells.
Breaths explore independent or gathered excitation/absorption phases at different
rates. MIDI labels identify the geometry reference: intermediate
KNOT settings and multiple resonance families can produce a different strongest
perceived pitch. The phases are not synchronized to a host tempo.

## Phone controls and FRESH TAPE

SYNTH exposes SELF, TREFOIL, MIRROR and TORUS. Each topology serves three of the
approved recipes. KNOT, PULSE, LOCK, GLOW, RING and HOLD sit beside ROOT
(reference MIDI 24–84) and VELOCITY. Preview, macro edits, SAVE PRESET under
YOURS, reopening and SEND TO PAD retain the loaded model, note and velocity.
A legacy model-1 recipe keeps model 1 and fills missing controls using that
model's defaults.

FRESH TAPE → OVERLAP calls `SynthKits.overlap()` to build a fixed dry starter.
A01–A12 preserve the approved recipes in bank order. A13–A16 are LOW THREAD,
LOW PIN, LOW CUP and LOW SEED: copies of the first four plucks one octave down,
with their remaining patch fields preserved. Each pad carries its full
`PadRecipe`, reference-note metadata and native rendered sample.

All sixteen starter pads are TONAL one-shots. They play their complete finite
sample once, including its native end fade. HOLD extends excitation before
decay; individual finite renders end within eight seconds. The separate held
workflow below does not change this starter. Captured-snip excitation remains
future work.

## MPC kit handoff

```text
./gradlew :synth:generateOverlapKit
```

The generator writes `testkit/SnipSnap Overlap Kit/` and
`testkit/SnipSnap-Overlap-Kit.zip`. Use `-PoverlapKitOutput=/absolute/path` to
choose another output directory. The folder contains:

- `SnipSnap Overlap Kit.xtd` and `SnipSnap Overlap Kit_[TrackData]/`: the MPC 3
  track and its samples. The same data folder holds the MPC 2 `.xpm` twin.
- `Source Kit/kit.json` and all sixteen source WAVs, preserving editable
  recipes and the fixed pad order.
- `kit-manifest.json` with full recipes, frame lengths, reference notes and
  source/export WAV hashes, plus `README.txt` with load instructions and pad map.

Samples use native dry mono 44.1 kHz PCM24 renders and the standard kit mixer
level. Export keeps the complete sample lengths and one-shot settings without
audition matching or added effects. Copy the full folder together: MPC 3 loads
the `.xtd` beside its data folder; MPC 2 loads the `.xpm` inside that data folder.
The audition page provides access to the kit archive.

## Preserved audition and replay

```text
./gradlew :synth:generateOverlapMusicalAudition
```

The task writes `testkit/overlap-musical-audition/`, using page source from
`prototype/overlap-musical-audition/`. Its fifteen clips include the twelve
individual recipes and three dry musical studies made from scheduled notes.
Native preset WAVs use the regular patch renderer, shared house leveling and
saved velocity. Native studies sum those rendered notes with one declared
common mix gain of `.25`; their score JSON stores each complete `PadRecipe`
and its frame offset. There are no added effects.

To replay one downloaded study, run the same task with
`-PoverlapScore=/absolute/path/study.json -PoverlapWav=/absolute/path/study.wav`.
Each event retains its complete native tail; score replay refuses truncation.

Matched WAVs use `AuditionLevel`'s loudest-200-ms target `.03` for comparison.
The bank ZIP contains native WAVs, individual recipes and study scores; matched
WAVs remain separate audition downloads. Native playback preserves the regular
renderer levels, while matched playback changes their relative loudness for
listening. The approved recipes retain their pitch-reference limits in both modes.

Audition revision 3 changes the listening material and presentation, not the
engine model. The earlier `OverlapPresets` reference recipes and
`generateOverlapAudition` control-comparison task are preserved. HOLD remains
finite excitation; this bank provides no seamless or indefinite loop.

## Verification status

Host checks cover the preset registry, saved-model edits and shelf reopening,
native recipe replay, fixed starter pad order, complete WAV lengths and MPC 2/MPC 3
one-shot export. Relevant checks are:

```text
./gradlew :synth:test --tests com.snipsnap.synth.PresetsTest --tests com.snipsnap.synth.OverlapKitTest
./gradlew :shell:test --tests com.snipsnap.shell.UserPresetsTest --tests com.snipsnap.shell.OverlapIntegrationTest
./gradlew :synth:generateOverlapKit
```

Android CI builds the app and runs the focused `OverlapSynthScreenTest` on an
emulator. Local JVM checks do not establish physical phone or MPC behavior.
The owner accepted the separate held MPC trial on 2026-10-31; the acceptance
ledger below records the limit of that report. The finite starter and real-phone
checks below still need their own recorded results. Save a custom preset before
leaving SYNTH to retain its note and velocity across recreation.

## Manual real phone pass

1. Open SYNTH → OVERLAP. Select each of the four topologies, load its three
   factory presets and preview them. GLASS THREAD should load reference C4
   (MIDI 60), velocity `.85` and its six saved controls.
2. Change ROOT, VELOCITY and a macro, then preview. Save a named preset under
   YOURS, leave SYNTH and restart the app. Reopen it and verify the same control
   values and sound; inspect the saved recipe to confirm its model is retained.
   Repeat with a saved model-1 recipe when available.
3. SEND TO PAD into an empty slot. Reopen the kit and play it in KIT and PLAY;
   verify the name, recipe and full finite tail survive. Releasing a tap should
   leave the one-shot playing, and it should finish without repeating.
4. Create FRESH TAPE → OVERLAP. Check all sixteen pads: the approved twelve in
   order, followed by the four lower plucks. Listen through the longer textures
   and breaths; verify no accidental looping or premature tail cutoff.
5. Export and reimport through the normal kit flow. Reopen the imported kit and
   check pad order, one-shot settings and full tails. Keep `Source Kit/` as the
   editable recipe copy; inspect its saved reference notes, velocities and models.
6. Load the packaged `.xtd` and `.xpm` handoffs on the MPC in separate passes.
   Check all sixteen samples, one-shot endings and lower pluck answers. Record
   the phone/MPC models, build and any mismatches in the bench results.

## Listening verdict and revision

The first listening verdict found the four routing identities too similar,
KNOT halfway a short pluck, and the breathing pair indistinguishable. Three
mechanisms explained the failures:

- Every full-depth circuit had been tuned to the same root-period comb, so the
  routing mostly changed how that same harmonic structure was excited and read.
- `(1-d)I + dP` discarded differential energy. Its squared-energy loss is
  `d(1-d) ||x-Px||²`, greatest around the middle of the control.
- Shallow output-only modulation averaged six phases together, while a shared
  initiating hammer and a short rubbing passage dominated the comparison.

Audition revision 2 introduced engine model 2. The approved musical bank retains
that renderer without DSP changes. Factory registration leaves its geometry,
breathing and glow behavior unchanged.

## Routing and depth

The same six segment proportions `[1.2, 1.6, 2.0, 3.0, 2.4, 1.8]` belong to every
routing. One overall geometry scale tunes the longest effective circuit to the
reference note; shorter circuits keep their own families.

| Routing | Full-depth structure |
| --- | --- |
| SELF | Six separate lengths, with different upper intervals above the reference root |
| TREFOIL | Two positive three-line cycles, giving root and fifth families |
| MIRROR | Three reflected pairs, giving three odd-harmonic families |
| TORUS | One reflected six-line circuit, giving one odd-harmonic family |

KNOT uses proper rotations: Rodrigues rotations on the two triples, quarter-turn
pair rotations for MIRROR, and composed adjacent rotations for TORUS. Scattering
preserves its input's squared norm at every depth. MIRROR includes one polarity
inversion per pair; TORUS includes one inverted reflection. An unsigned three-pair
or six-cycle permutation has determinant -1 and cannot connect continuously to
identity inside six-dimensional orthogonal matrices. These reflections are an
explicit part of the new instrument, rather than a hidden gain correction.

Fractional-delay interpolation and two-tap damping are passive. Explicit per-line
feedback remains below one. Together with orthogonal scattering, this supplies
an L2 small-gain stability argument before normalization. The tests check the
matrix on every input direction and exercise actual unlevelled impulse responses.

At KNOT zero, every topology selection yields the identical SELF network. Overall
geometry moves toward the selected full-depth size as KNOT rises. Intermediate
pitch remains exploratory. Full-depth families retain the reference root, but a
multi-family sound's strongest perceived pitch or autocorrelation estimate can
follow another family. No independent root oscillator hides that behavior.

## Breathing and glow

Six slow Kuramoto phases gate their own noise excitation and complementary
absorption. Independent phases give staggered entrances; strong LOCK gathers
those energy processes into shared surges. Pickup weights remain fixed. There
is no separate master volume oscillator. PULSE zero bypasses both gates and
breath absorption. The breathing pair uses PULSE .65 (about 1.4 Hz), HOLD one,
and only changes LOCK. Its opening ramps in, without a common hammer masking
the ongoing gesture. Listen beyond the opening second.

GLOW derives bounded even/odd harmonics from the actual output, outside feedback.
It adds harmonic character; it does not promise that every spectrum becomes
brighter. The six deterministic excitation streams are shared between routing
and control comparisons. Synthesis and nonlinearity run at 4× and use the shared
antialias decimator. Output is mono 44.1 kHz.

| Control | Engine model 2 behavior |
| --- | --- |
| KNOT | Identity → selected passive scattering, with overall geometry changing |
| PULSE | Zero is off; otherwise sets the six slow phase rates |
| LOCK | Stronger synchronization of actual excitation and absorption |
| GLOW | More output-derived harmonics |
| RING | Nominal low-frequency T60 `.55 × 24^RING` seconds, before additional spectral losses |
| HOLD | Finite excitation duration `.040 + 5.96 × HOLD²` seconds; at one, six seconds plus decay |

All outputs from the finite model renderers remain finite, capped at eight
seconds. Their HOLD control does not export a seamless or indefinite loop.
The original factory bank and starter kit preserve that behavior. The separate
held workflow uses the steady source described below. Captured-snip input
remains future work.

## Recipes and regeneration

`OverlapPatch` stores an explicit model, MIDI 24–84, velocity and the six macros.
New patches use model 2. An old recipe with no model reads as model 1. The original
renderer is preserved in `OverlapV1`; a golden WAV hash checks that its published
sound remains byte-identical. Edits and velocity variants retain the saved model.
Six reference starting points live in `OverlapPresets`, outside the factory
preset roster. They remain separate from the approved twelve-recipe
`OverlapMusicalBank`, which is registered through `OverlapFactoryPresets`.

```text
./gradlew :synth:test --tests com.snipsnap.synth.OverlapTest
./gradlew :synth:generateOverlapAudition
```

The reference task writes the generated, ignored `testkit/overlap-audition/`.
Page source is `prototype/overlap-audition/`. Twelve clips compare routing, depth, breathing, glow
and two musical candidates. Revision-prefixed WAV and recipe paths avoid serving
stale first-listen audio. The manifest records hashes, reference/pitch observations,
phase order and audio envelope measurements. Raw listening files retain one fixed
engine gain and the regular end fade; matched files use `AuditionLevel`'s loudest
200 ms target `.03`. Raw internal diagnostics precede the fade and leveling.

The listening pages start silent and play one clip at a time. Keep/revise/pass
decisions and notes stay in browser storage and export as JSON. The musical bank
uses its own note key, separate from both earlier auditions. Earlier notes remain
accessible on the retained first- and second-listen pages.

## Separate steady held proof

The held experiment began as an opt-in Kotlin prototype, separate from saved
engine models and the approved factory bank. Its gesture is excitation builds → the
network settles into sustain → note-off withdraws the drive → stored energy
rings out. It keeps the six delay proportions, fractional-delay tuning, passive
scattering and pickup of model 2. A periodic six-node excitation replaces the
finite rubbing source. This is a driven adaptation, not an indefinite replay of
the original finite gesture.

```text
./gradlew :synth:generateOverlapHeldAudition
```

`OverlapHeldPrototype` supplies the separate physical proof; its experimental
audition recipes are not ordinary saved patches. `generateOverlapHeldAudition`
writes reproducible listening assets under
`testkit/overlap-held-audition/`, with source in
`prototype/overlap-held-audition/`. The original bank and both earlier auditions
remain the listening references. The experiment introduces no new factory presets.

This first proof requires HOLD=1, PULSE=0 and GLOW=0. KNOT changes geometry and
scattering as in model 2; it remains inactive in SELF. RING changes the nominal
low-frequency T60. LOCK is accepted but inactive while PULSE is off. Reference
MIDI and velocity remain explicit. These limitations avoid presenting unsupported
controls as finished sound design. In particular, a short loop of freely drifting
breathing phases would require changing their rates or repeating a constrained
control trajectory, and needs its own listening decision.

The source period fits an integer number of requested note cycles to whole output
frames. The resonator geometry still uses the requested reference frequency. The
finite source grid can change which nearby resonance receives the most energy;
reference note and perceived pitch remain separate listening questions. The
solver must verify full-state and full-period audio recurrence, then check a
further independently evolved period. Delay memories are compared in cursor
order. Decimation uses continuous physical audio before and after the kept
period. The seam is measured against genuine preceding and following audio,
using `Keys.seamError`, not against two copies of an exported loop.

Release withdraws excitation over a smooth transition while the same delay and
filter states continue. The prototype exposes causal internal audio for exact
prefix checks because the shared offline decimator looks ahead. It applies no
late output fade to disguise residual energy. The default tail budget follows
RING; explicitly shortened captures disclose their final measured energy.

The page contains four settled periods repeated eight times and four note-off
examples. Each topology's loop and release share one matched-level scalar, so
their level relationship survives the comparison. Native playback uses the
prototype's house leveling with one additional, collection-wide only-down
safety gain. Capture-dependent house leveling means exact note-off prefix tests
use the prelevel causal signal, not separately normalized native WAVs.

Downloads include one-period native loops, both preview levels, complete
experimental recipes, diagnostics, source/WAV hashes and a deterministic ZIP.
These recipes cannot be imported as ordinary `PadRecipe` files. Replay a downloaded
recipe with the audition task's `-PoverlapHeldRecipe=/absolute/path/recipe.json`,
`-PoverlapHeldWav=/absolute/path/replay.wav` and optional
`-PoverlapHeldMode=matched` properties. Replay verifies the recorded WAV hash.

The focused Kotlin verification passed all 18 tests across `OverlapTest`,
`OverlapV2ModalTest` and `OverlapHeldPrototypeTest`. Checks include low/middle/high
modal identities, held register/loss edges, full-state recurrence, both real
neighbouring seams, causal prefix invariance, quiet passive continuation and
monotonic source withdrawal for a key released during its attack. A scratch
mutation replacing every topology's scattering with SELF made the new modal test
fail; the approved source was restored afterward.

The generator records uncached setup, settling and conditioning milliseconds in
`metrics-runtime.json`, outside the deterministic download archive. Settling is
capped by a T60-derived budget and an absolute 192-period limit, with cancellation
checks during simulation. Those host timings do not establish phone performance.
Josh has approved the steady held listening proof. Moving-breath loops and an
engine-specific aliasing bound remain unverified.

### Why it is built this way

- A separate entry point protects existing recipes while the sustain is judged.
- Periodic excitation lets the entire passive network settle to a repeatable
  state. Deterministic noise alone would not guarantee a short seamless loop.
- Withdrawing the drive preserves the resonator's own release instead of
  replacing it with an arbitrary output envelope.
- The bounded settling search is export/prototype work. An interactive path
  needs to cache completed notes rather than only matrix and geometry setup:
  those small setup steps do not remove repeated network simulation. The phone
  integration below adds that bounded cache. This earlier proof does not
  establish phone timing.

## Held keygroup hardware trial

The next handoff packages those steady settings as four playable instruments,
one per topology, using the existing keygroup export path. Generate it with:

```text
./gradlew :synth:generateOverlapHeldKeygroups
```

The generated, ignored `testkit/overlap-held-keygroup/` contains
`overlap-held-keygroups.zip`, four native MPC 3 `.xty` tracks, four matching
legacy `.xpm` programs, twelve mono 44.1-kHz PCM24 WAVs, instrument sidecars,
experimental source recipes, a manifest and checksums. Extract the whole ZIP
and keep its folder structure. MPC 3 loads a top-level `.xty`; older MPC
versions load its matching `.xpm` from the `_[TrackData]` folder.

Each topology uses reference MIDI 36, 48 and 60. The three inclusive zones are
30–42, 43–54 and 55–66; notes outside 30–66 deliberately have no zone. Roots are
the synthesis reference notes, not a claim that each topology's strongest
partial is that note. The lower and upper references extend the settings heard
in the approved MIDI-48 proof. The owner accepted this MPC trial on 2026-10-31;
no separate register-by-register measurements were supplied.

Each WAV has an onset period followed by one untouched settled period. A short
fade at the start of the onset prevents an abrupt start; the second period is
the sustain loop. Its positive loop-start frame avoids the exporter's zero
sentinel for a sample with no loop. Loop endpoints come from the same program
data in the native track, legacy program, WAV sampler chunk and phone sidecar.
The trial opts into instrument-level `TriggerMode=2` in the legacy writer, the
NoteOn mode already used by the native writer and sustained reference programs.
Existing packages retain their original legacy serialization by default.

The program's release value is `0.6` in the existing MPC envelope field. Its
hardware time mapping has not been measured. A sampler fades the repeating
body after note-off; this differs from withdrawing excitation and allowing
the prototype's resonator state to decay. This trial tests that handoff. It
does not export a separately triggered physical release tail. PULSE and GLOW
remain off; LOCK is inactive. No new saved engine model or phone controls are
introduced by this package.

`README.txt` carries the hardware checklist: load each program, hold each
sampled reference through several repeats, tap and release early, play across
zone boundaries, try a small chord and check final note-off. The owner accepted
the trial on 2026-10-31. The MPC model, OS/firmware and individual checklist
results were not supplied, so this records acceptance rather than measured
envelope timing, polyphony or a device-specific pass. Future bench notes should
record those details and any clicks, pitch jumps, missing notes or stuck voices.

The package records deterministic audio and source hashes separately from
host render-time measurements. Generation is an offline, bounded export job;
it does not establish interactive phone performance. Saved held recipes,
phone controls and completed-note caching are the integration scope below.
Loopable breathing and GLOW remain deferred.

### Why it is built this way

- Three source notes keep the hardware trial small while exposing register and
  zone-boundary changes.
- The onset and steady loop share one waveform and gain, so the transition does
  not depend on matching independently normalized captures.
- Native and legacy programs use the same central packaging path and sample
  bounds, reducing format-specific disagreements.
- The sampler release is a deliberate approximation to test on the MPC before
  making it part of the phone workflow.

## Acceptance ledger

| Evidence | Status and limit |
| --- | --- |
| Finite factory bank | The owner accepted the twelve model-2 musical recipes. Their registration and finite audio remain separate from held playback. |
| Steady held listening proof | The owner accepted the steady proof. Its recorded host tests establish recurrence, independent-neighbour seam checks and source-withdrawal behavior under the tested settings. |
| Held MPC keygroup trial | Owner accepted in conversation on 2026-10-31. No MPC model, OS/firmware or per-check hardware measurements were supplied. Do not infer a particular device, measured release time or polyphony limit. |
| Phone integration | Authorized on 2026-10-31. An earlier checkpoint passed focused JVM checks and local Android APK builds. The final SEND/SPREAD delivery correction is being rechecked; full JVM and emulator/physical-device execution remain pending. |
| Production audio compatibility | At that checkpoint, all twelve complete production CLI/maker PCM24 WAVs, including sampler metadata, were byte-identical to the retained accepted trial. The final pad-delivery correction leaves the native instrument loop path unchanged. |

Owner acceptance is a listening and use decision. It does not replace a measured
latency, pitch, aliasing or sampler-envelope report. The existing held package
uses a collection gain of `1` for the approved samples; this records that export,
not a guarantee that every future KNOT, RING or reference setting stays below
the same peak without protection.

## Held phone workflow: as built, 2026-10-31

The integration adds explicit saved held playback, dry touch preview and
ordinary instrument-library/MPC export. The baseline for this update is
`2b3e1688`. An earlier checkpoint passed focused JVM checks, the complete
accepted-audio comparison and local Android APK builds. Final SEND/SPREAD
delivery revalidation, full JVM and emulator/physical-device execution remain
pending.
No physical phone measurements are claimed here.

### Saved playback and controls

`OverlapPatch` appends `playback`, `releaseSeconds` and `heldVersion`, with
explicit `OverlapPlayback.FINITE` and `OverlapPlayback.HELD` choices. HELD
uses `OverlapHeld.VERSION = 1` while the finite engine remains model `2`.
Default finite JSON omits the new default fields; a legacy recipe with no
playback remains finite, and missing model still means model 1. HELD JSON
requires its explicit version and refuses unsupported versions. Saving,
loading, editing macros and changing velocity preserve the playback choice
and its saved release. Focused checks cover finite JSON/audio compatibility.

The held path requires model 2, HOLD `1`, and PULSE, GLOW and LOCK `0`.
SELF exposes RING; the other three topologies expose KNOT and RING. All share
ROOT, VELOCITY and RELEASE. KNOT is physically inactive in the approved SELF
network, so hiding it does not revoice the sound. The complete six-macro map
remains saved. Entering HELD starts with RING `.6`, KNOT `0` for SELF or `1`
for the other topologies, and the steady controls above. The nominal source
clock period stays fixed at `1.5` seconds. This adds no breathing or new motion;
breathing and GLOW are deferred until their moving state has its own recurrence
and listening decision.

The saved reference remains MIDI `24` to `84`. Release defaults to `.6` and
accepts `.1` to `1.5` in the existing sampler-envelope field. The field's name
does not establish an MPC release duration in measured seconds. Its sampler
fade also differs from the prototype's physical source withdrawal and passive
network decay.

### Completed notes, preview and export

`OverlapHeld.render` requests a roughly `1.5`-second settled body, then builds
two periods: the first has a `20` ms onset ramp, and the second remains the
untouched sustain loop. Its positive loop start preserves the existing keygroup
convention. One only-down scalar per completed note enforces a `.90` peak
ceiling; the approved trial's gain-1 samples retain their values.

A completed-note LRU is bounded to `12` entries and `8 MiB`, with defensive
sample copies on insertion and retrieval. Release is not part of its audio
key because it changes the sampler envelope rather than the captured body.
Cancelled, failed or superseded work does not enter the cache. The cold solve
is still bounded simulation, not a measured instant phone render. A touch plays
already prepared attack-and-loop audio; it does not start DSP on the press.

`OverlapPerformanceControls` supplies explicit mode selection and saved
performance controls. `rememberOverlapHeldPreview` prepares cancellable audio
off the main thread and reuses the sample-only held gesture. It stops on touch
cancellation, disposal and backgrounding; audio-focus behavior belongs to the
existing sample player. The phone workflow adds saved presets and MAKE
INSTRUMENT through `OverlapPadMaker` and the ordinary instrument library/MPC
export path. One-shot SEND TO PAD and SPREAD play finite deliveries with a
saved `20` ms terminal fade. `OverlapPadMaker.ONE_SHOT_FADE_MS` supplies the
shared value; `deliveryRecipe(patch)` supplies SPREAD's rendered and saved
`PadRecipe`, so applying, reopening and replaying the pad retain the fade.
Those pad-only fades do not change the native exported sustain body.
`Spread.detect` uses the authored reference MIDI for held OVERLAP while finite
OVERLAP retains its existing detector behavior. For a default reference of
MIDI `48`, an instrument
uses sampled references `36`, `48`, `60` with inclusive zones `30–42`, `43–54`,
`55–66`. Other saved references shift the three-note layout while retaining
the saved reference itself. Below `36`, the sources are `[m, m+12, m+24]`;
above `72`, they are `[m-24, m-12, m]`; otherwise they are `[m-12, m, m+12]`.
This keeps synthesis references within MIDI `24` to `84`. Midpoint zone splits
and six-semitone end extensions keep each source's resampling span bounded.
The reference remains a geometry request rather than a guaranteed strongest
perceived root. The earlier owner acceptance covers the fixed MPC trial; it
does not supply a hardware measurement for every new reference or control edit.

### Replay a saved held recipe from the CLI

The CLI uses the saved playback choice. For a saved HELD MIRROR recipe:

```text
./gradlew :cli:run --args="synth OVERLAP MIRROR --patch /absolute/path/held-mirror.patch.json --out /tmp/overlap-held-note"
./gradlew :cli:run --args="synth OVERLAP MIRROR --patch /absolute/path/held-mirror.patch.json --instrument --release .6 --out /tmp/overlap-held-instrument"
```

The first command writes the native onset-plus-loop WAV with its sampler loop
metadata and a saved patch beside it. The second samples the three reference
zones through `OverlapPadMaker` and the normal native/legacy package path,
retaining an instrument sidecar and the complete patch. `--midi` and
`--velocity` override their saved values without discarding the playback state;
`--release` changes the instrument's saved sampler release.

There is no new `--held` flag. `--instrument` requires an explicitly saved HELD
patch and refuses a finite recipe instead of changing its playback. The engine
and topology arguments must match the saved patch. The original prototype
audition recipes remain distinct from these normal `OverlapPatch` files.

### Host measurements and verification

The results below describe the checkpoint before the final SEND/SPREAD
delivery correction. Three new shell regressions now cover saved finite fades
without changing the source, actual SPREAD apply/reopen/replay/tuning, and
finite-detector fallback. Focused validation including `SpreadTest` is running;
no passing result is claimed yet for those additions.

At the earlier checkpoint, the focused Gradle process exited `0`: `54` JVM
tests passed (`43` synth,
`9` shell and `2` CLI), with no failures, errors or skips. Coverage includes
finite JSON/audio compatibility, held round trip and version refusal, strict
steady-control validation, cache ownership/eviction, cancellation without
publication, loop/sample bounds, saved preset handling and complete
instrument-library/export replay. The new focused suites include
`OverlapHeldTest`, `OverlapPadMakerTest` and `OverlapHeldCommandTest`.

A negative control removed the completed-cache defensive sample copy. The
named cache-ownership test then failed with the expected caller-mutation
assertion. Restoring the source restored its exact SHA-256
`a595eaf38eb5349bacc8e4ab189728f36794fded331d876e9ce9d5467a37ea76`;
the restored seven-test `OverlapHeldTest` run exited `0` without failures,
errors or skips. This demonstrates that the ownership guard detects its
intended failure rather than only passing the implementation.

All twelve complete WAVs exported through the installed production CLI and
`OverlapPadMaker` are byte-identical to the retained accepted trial, including
PCM24 audio and `smpl` root/loop metadata. The references are MIDI `36`, `48`
and `60` for all four topologies; every approved capture kept gain `1`.

`OverlapHeldBenchmark` recorded `24` production-render calls on a Linux host,
OpenJDK `21.0.13`, with four visible processors. At MIDI `48`, velocity `.8`:

| Path | Render time range | Median |
| --- | --- | --- |
| Fresh completed-cache miss, one per topology | `127.389–281.649 ms` | `143.276 ms` |
| Exact completed-cache hit, one per topology | `0.134–0.349 ms` | `0.221 ms` |

Counters independently confirmed exact, name-only and release-only hits, and
changed-key misses. PCM hashes confirmed that renaming or changing release
does not change source audio. Callback cancellation and thread interruption
were observed on real uncached work and left no cache entry. Timers surround
`OverlapHeld.render` only; they exclude PCM encoding, hashes and file I/O.
These are host completed-cache measurements, not phone touch latency or fresh
JVM startup measurements. Reproduce the report with a new output filename:

```text
./gradlew :synth:benchmarkOverlapHeld -PoverlapHeldBenchmarkOutput=/absolute/path/new-held-report.json
```

The local Android build exited `0` on 2026-10-31, completing
`:app:assembleDebug`, `:app:assembleStrikeFx` and
`:app:assembleDebugAndroidTest`. Debug, StrikeFx and instrumentation APKs were
built. The initial four `OverlapHeldSynthScreenTest` cases compiled for explicit
mode/finite return, same-frame performance edits and saved one-shot delivery,
prepared release without name/release recapture, and MAKE INSTRUMENT's three
roots. The save/send cases now also exercise the actual SPREAD overlay and
compare complete recipes and PCM; their updated compile/run checks remain
pending. APK builds do not execute the instrumentation cases.

Final delivery revalidation, full JVM and on-device/emulator execution remain
pending. A physical phone pass must check press, early release, cancellation,
background/focus loss,
saved preset/instrument reopening and MPC export from the phone. Record
device/build and cold/warm timing rather than treating the host benchmark,
APK compilation or earlier MPC acceptance as that pass.

### Why it is built this way

- An explicit playback choice keeps accepted finite recipes and their sound
  intact while making a saved held instrument unambiguous.
- The steady restrictions expose controls this held body can reproduce;
  breathing and GLOW need their own moving-state proof.
- A prepared onset and untouched loop give the sampler a clean note entrance
  without changing every return of the accepted body. SEND and SPREAD save
  their finite terminal fade in the pad recipe, keeping replay consistent
  without modifying instrument loops.
- Caching complete notes removes repeated settling work on a warm edit, while
  bounds and owned copies limit memory use and accidental PCM mutation.
- The sampled release follows the phone and MPC instrument path; keeping it
  distinct from physical network decay makes the approximation explicit.
