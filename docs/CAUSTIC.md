# Caustic: four finite voices

Caustic turns an ordinary source into a position-dependent singing body through
reflected paths. The native instrument now develops four gestures within the
same CAUSTIC engine: ALLOY, HOLLOW_MALLET, RUBBED_RING and
TRAVELLING_CATCHES. SOURCE and FOCUS retain the circular geometry; BODY changes
the contact or pressure appropriate to the voice, and LENGTH shapes a finite
gesture. Root and excitation velocity remain explicit saved fields. HOLD is
reserved. The working name has not been checked for release.

## Native character expansion

The owner heard the three character-study candidates as distinct and chose to
develop all three. That verdict applies to the study recordings. The Kotlin
ports, their intermediate controls and their native output conditioning still
need the owner's listening verdict.

| Native voice | Characteristic gesture | BODY reference position |
| --- | --- | --- |
| ALLOY | Metallic strike → tonal body grows → position-dependent ring → finite release. | 1 retains the approved Fuller-body excitation; 0 retains preferred Alloy. |
| HOLLOW_MALLET | Soft contact → hollow low/odd-mode knock → short pitched decay. | .5 is the heard Python contact/body reference. |
| RUBBED_RING | Rough contact → pressure swell → driven pitched body → finite release. | .5 is the heard Python pressure/noise reference. |
| TRAVELLING_CATCHES | First contact → three spatial catches → shared metallic ring. | 0 retains the study's preferred-Alloy body for all four contacts. |

These are voices because the exciter and material response change while the
same coherent source-to-pickup mapping remains central. MERCURY already offers
struck and rubbed modal objects with water loading. REVEL already combines
scheduled skins and moving microphones. Caustic keeps one vessel's circular
reflection paths as the excitation map. A new engine or an external rack
effect would duplicate that shared work.

### Signal path and model limits

```text
voice contact or pressure gesture
  → circular reflected paths with signed modal participation and causal delays
  → voice's damped modal body
  → one shared native output stage
  → finite mono 44.1 kHz sample
```

Hollow Mallet ports the study's soft finite contact and six low/odd modes.
Rubbed Ring ports its shaped pressure rise, rough harmonic/noise drive, seven
modes and quiet path-routed contact radiation. Rubbing remains a one-way driven
approximation: the resonator does not feed friction back into the contact.
Neither profile claims measured wood, glass or a particular physical vessel.

Travelling Catches schedules four illumination contacts through different
source positions. The four unconditioned responses combine before one output
stage. Their absolute contact amplitudes share a fixed L1 budget; no contact
receives its own loudness makeup. This is the superposition of one linear
body's responses, not four independently leveled notes. The schedule is an
authored musical gesture, not a collision solver or a moving-wave ORBIT model.
Its reference launch times are rounded to periods of the requested note.

The circular solver runs literally. The material tables, source gestures,
modal participation and note-relative delay scale are sound-design choices.
Geometry preparation stays outside the audio loop and uses the bounded
SOURCE/FOCUS cache. Each render owns its source, delay and modal state.
Additional catches reuse prepared geometry; mutable audio state is not cached.

### Controls and compatibility

| Control | Contract across the four voices |
| --- | --- |
| SOURCE | Illumination/source distance with the exact parallel limit. Catches shifts its four reference sources 0, .25, .6 and 1 by `.7 * (SOURCE - .5)`, clamped to 0–1. |
| FOCUS | Position of the same finite pickup band; reception changes before the modal sum. |
| BODY | ALLOY and catches: metallic tonal fullness. Hollow: contact character. Rubbed: pressure/contact character. The reference positions above anchor the heard studies. |
| LENGTH | .5–8 seconds. Source/contact timing and modal release scale with the finite gesture; this is more than a crop of an unchanged eight-second recording. |
| HOLD | 0 only. Positive values are refused until a real sustained source and independently checked loop exist. |
| ROOT / VELOCITY | Saved outside the macro map. C2–C6, MIDI 36–84; velocity changes excitation before output conditioning. |

Native ALLOY model 1 and its eight starter patches retain their interpretation.
The archived `CausticRecipe` models 1–4 are a separate format and remain
unchanged. The three added native voices also carry explicit native model 1;
they are not aliases for the old audition-only Python recipes. A finite note
longer than 1.5 seconds can carry LOOP class metadata without becoming a held
or seamless sample.

Hollow Mallet keeps a minimum root-mode T60 of twelve note periods, with
the other modes' floors proportional to their reference T60. This prevents
the shortest low note from losing its pitched body while preserving relative
high-frequency loss. At LENGTH=1 the nine new-voice default recordings retain
exact float-output hashes after this guard.

The factory roster contains the unchanged eight ALLOY starting patches and
one reference patch per added voice, eleven total. The dry sixteen-pad starter
keeps eight ALLOY notes in C minor pentatonic, then the two ALLOY anchors,
the three new voices, Sparse Alloy, Short Glint and Low Vessel. These additions
do not increase the engine count or claim a held instrument export.

### Why it is built this way

- Each new voice changes the contact and audible main-note gesture. The earlier
  tail-only extension did not give the owner a useful listening difference.
- The same path map keeps SOURCE and FOCUS meaningful across all four voices.
  It also lets a geometry fix be checked once against the shared reference.
- Hollow and rubbed references have a defined BODY midpoint, so controls can
  grow around the heard candidate without silently replacing its starting shape.
- The catches mix their raw forces before finishing. Separate loudness makeup
  would inflate quieter contacts and change the intended sequence.
- Native output conditioning and control extremes can change the heard study.
  Port agreement and listening therefore remain separate acceptance checks.

### Expansion evidence ledger

- **Heard:** the owner heard distinct Hollow Mallet, Rubbed Ring and Travelling
  Catches study gestures and requested native development of all three. The
  production ports and new control positions have not yet been heard.
- **Verified:** the shared CAUSTIC geometry, saved root/velocity contract,
  original native model 1 and archived models 1–4 were read at `53366f9c` before
  this expansion. The published pack and completed build stage use clean source
  `8f988463`. The registry exposes four voices, eleven starting patches and a
  sixteen-pad finite starter. Merged source `04071091` includes the current
  default branch and retains the published Caustic DSP unchanged.
- **Measured:** the independent reference comparisons below cover the nine
  new-voice defaults at C2/C4/C6. All sixteen compiled character checks pass,
  including the 27-case short-bass matrix. The generated recordings, archives,
  kit readback and browser payload checks pass as detailed below. JDK 17
  focused runtime, cross-runtime waveform checks and the full nine-module JVM
  gate pass. Historical results below apply only to their named earlier packs.
- **Proposed:** new profile constants away from the heard reference positions,
  factory variations and all unlistened control extremes remain sound-design
  choices rather than hearing approval or measured physical properties.

| Expansion check | Current evidence |
| --- | --- |
| Original ALLOY preservation | Seventeen earlier cases retain exact SHA-256 agreement at internal raw, prelevel and native output: all 51 comparisons pass. |
| Raw port agreement | Independent reference for all nine new-voice C2/C4/C6 defaults: worst relative RMS error `7.67e-8`, maximum absolute sample error `2.17e-8`. |
| Finished port agreement | The reference uses the same Kotlin house finish and PCM24 path. Worst relative RMS error against native output is `8.90e-7`; fitted gain stays within `1.64e-7` of unity. |
| Default native bounds | Across those nine comparisons, maximum native peak is `.8391` and maximum absolute mean is `1.32e-8`. These are default measurements, not all-control bounds. |
| Low-note short gesture | With the twelve-period modal-release floor, C2/LENGTH=0/velocity=.75 passes the broad pitch probe at BODY 0, .5 and 1: 12.93–14.40 cents error, confidence .583–.595. Late raw/attack RMS is approximately `1e-6`, before the output fade. |
| Expanded short-bass matrix | All 27 C2 Hollow cases across LENGTH 0/.05/.125, BODY 0/.5/1 and velocity .25/.75/1 pass the dedicated evidence check. Worst returned house pitch error is 14.482 cents and minimum measured note-period correlation .5824. Five house-detector nulls use the independent broad-spectrum check described below. |
| Control activity | The weakest measured adjacent-control normalized spectral L1 difference is .113. SOURCE × FOCUS interaction is .195–.742; BODY × LENGTH envelope interaction is .159–.755 across the three added voices. These are shape measurements, not an audibility verdict. |
| Higher-rate comparison | At C6/hard effort, 4× versus 16× normalized spectral L1 differences are .000223, .00798 and .01916 for Hollow, Rubbed and Catches respectively. Maximum measured power above 20 kHz is `1.22e-8`; the noise realization differs between internal rates. |
| Generated pack | All 42 clips, 84 mono PCM24 WAVs and 42 native patches pass the artifact audit; 126 declared hashes match. Twelve defaults pass the voiced-window pitch gate. Maximum native peak is .9900 and worst reported broad pitch error is 13.70 cents. The early C2 Hollow classifier warning remains visible. |
| Downloads and kit | All six ZIPs pass CRC checks and remain below 25 MiB; the largest is 14.09 MiB. Both MPC readers pass all 32 pad readbacks. The thirteen retained ALLOY pads keep exact patch and WAV bytes; the three added voices occupy A11–A13. |
| Browser payload | All 84 WAVs, named comparison routing, renderer/matched handoffs, retry, keyboard playback and downloads pass browser checks. Widths 320, 375, 390, 768, 1024 and 1440 have no horizontal overflow. |
| Published render build | At `8f988463`, the build completed all nine JVM `testClasses` tasks, debug APK, Android test APK and the 42-clip generator with exit 0 in 6m 2s. Independent invocation of the sixteen compiled character tests passes in 21.474s, exit 0. |
| Merged JDK 17 build | At `04071091`, all nine JVM `testClasses` tasks, debug APK and Android test APK pass with exit 0 in 5m 28s on Temurin 17.0.21+9. The local compiler command uses `-Pkotlin.daemon.jvmargs=-Xmx4g`; no repository memory setting changes. |
| Cross-runtime audio | On Temurin 17.0.21+9, the sixteen compiled character tests pass in 20.101s. All 51 earlier ALLOY stage hashes and all 27 stage hashes for the nine added-voice defaults match the published JDK 21 output exactly. |
| Focused compiler stress | Three JDK 17 profiles complete 720, 2520 and 720 calls, all exit 0. The original 1046-byte `synthesize` method reaches tier-4 compilation with no compiler exclusions. This is focused runtime evidence, not the full suite result. |
| Merged APK artifact | The `04071091` debug APK is 52,441,963 bytes, SHA-256 `f3dc6732662cfd6902b9dfa17af2185a8aafe6788463a3c716216d925026959a`. ZIP, debug signature, four native ABIs and all four voice names in DEX pass the packaging audit. Device instrumentation was not run. |
| Full JVM gate | At validation commit `d17359db`, the Temurin 17 nine-module gate exits 0 with 7,001 passing results across 858 classes, zero failures, errors or skips. The final invocation takes 35.757s: all 421 loop tests rerun, while the eight unchanged modules are legitimately UP-TO-DATE from the completed first run. |

The first full JVM attempt stopped in OpenJDK 21's HotSpot compiler while
compiling `CausticInstrument.synthesize`. The complete gate is being rerun on
Temurin 17, matching `.github/workflows/tests.yml` and the synth module's
`JvmTarget.JVM_17`. Production DSP remains the published `8f988463` source;
no renderer change is used for this runtime failure. The compiler crash's
cause has not been established. The completed JDK 17 checks above preserve
the original compiled method and audio. The JDK 21 failure remains unresolved;
the complete passing gate uses the repository's CI runtime.

The first JDK 17 run took 1h 58m 38s and completed 7,001 tests with one
failure: a `ResidencyTest` wall-clock assertion observed 79 ms against a 50 ms
limit. All 3,806 synth tests passed. Commit `d17359db` changes only that test
guard: actual caller/renderer threads and latches prove the drone starts
elsewhere and remains blocked when `buffersFor` returns. A scratch-only mutant
that runs just the drone executor inline fails with the named caller-thread
assertion, exit 1 at 68.5 ms; the normal guard exits 0. Silent-drone, audible
other-track, frame-count, single-bake and completed-buffer assertions remain.

The earlier warm-cache fixture uses already-fitting audio so its bounded
deadline tests waiting rather than tempo analysis. It retains a real two-thread
queue, a 40 ms delay, six completed-bake checks and the assertion that applying
the warmed interval does not bake again. These test changes do not alter
production DSP or the APK built from `04071091`.

The generated pack's eight-second C4 captures took 258 ms for ALLOY, 523 ms
for Hollow, 323 ms for Rubbed and 346 ms for Catches. Across all 42 captures,
the maximum was 2.626 seconds. These Linux/OpenJDK 21 generation timings
include the renderer's actual preparation and finish; they are not warm phone
latency measurements.

The independent reports are `independent-reference-comparison.json` and
`independent-finished-reference-comparison.json` from the native-capture probe.
These numerical agreements establish port fidelity at the reference settings;
they do not establish the owner's acceptance of the production instruments.
The [portable evidence aggregate](evidence/caustic-native-voices-2026-10-28.json)
retains the measured reports, source hashes and build fingerprint.

### Disclosed limits

Five of the 27 soft/short C2 Hollow matrix settings return no result from the
house autocorrelation pitch detector. Their independent unconstrained spectral
check finds the requested root within 2.425 cents and note-period correlation
at least .6248. The detector's relative-peak cutoff can reject that period.
These checks support the authored root at those settings; they do not claim
that every house pitch probe passes. The earlier independent diagnosis also
found the strongest 40–1200 Hz peak at C2 without a root-constrained search.

The audio-only classifier can call dark C2 Hollow notes TOM or KICK. The
accepted Python prototype's first .5 seconds already receives TOM, so this
heuristic does not distinguish a port regression from the heard low-register
gesture. Native patches retain their saved MIDI and duration-derived
TONAL/LOOP filing, following the existing authored-instrument approach used
for low ARCO notes. The measured heuristic result remains in diagnostics;
it is not presented as universally safe drum classification.

The production ports still need listening, phone performance and MPC hardware
load/play checks. HOLD, live moving geometry and held keygroup export remain
outside this finite expansion.

## Character listening study

The owner found the first native positions insufficiently different. The
main-note audit supports that concern: Fuller Body versus Rim concentration
has normalized 1/6-octave power L1 .052 and RMS-envelope residual .014 over
.05–1.5 seconds. Large waveform residuals partly reflect phase changes; they
do not establish an audible new character. Seven prominent comparison links
also replayed the same reference. Cards now have explicit primary Play buttons.

The [character study](https://caustic-snipsnap-audition.bertcalm.chatgpt.site/contrast/)
keeps Fuller Body byte-identical and auditions three new gestures at C2/C4/C6:

| Candidate | Changed mechanism | Status |
| --- | --- | --- |
| Hollow Mallet | Softer finite contact, six low/odd modes and faster high-frequency loss. | Heard Python reference, selected for native development. |
| Rubbed Ring | A .34-second pressure rise, rough driven contact and a finite release replace the strike. | Heard one-way rubbing approximation, selected for native development. |
| Travelling Catches | Four scheduled contacts illuminate different source paths into the same linear native body. | Heard raw-response sum, selected for native development. |

The references use actual Kotlin geometry exports. Hollow and rubbed bodies use
the uncoupled `Modes.Bank` rotate-then-kick recurrence through SciPy. Catches use
native raw responses with an L1 contact-force budget, not independent sample
leveling or a claim of acoustic energy conservation. Every output passes through
the existing Kotlin filter, decimator, DC/level stage and PCM24 writer.

**Measured:** all twelve full recordings pass the Kotlin broadband pitch gate
within 20 cents, confidence at least .3, finite samples and PCM headroom. The
reference WAVs retain their hashes. Main-window spectral and envelope screens
are reported separately; flux can count modal beating, so it does not prove
physical contacts. **Proposed:** all new profile constants and gestures.
**Heard:** the owner approved the earlier Fuller Body, rejected the first native
bank's degree of variation, then heard the three candidates as distinct and
selected all three for native development.

Reproduce with `python3 scripts/generate_caustic_character_study.py` after
building the native audition. Requires NumPy, SciPy and JDK 17+. The download
contains the complete study source snapshot and raw-input hashes. These are
archived audition recipes. The native ports use the separate saved patch
contract above.

### Why it is built this way

- The unchanged reference isolates the new contact, body and motion choices.
- The candidates change the audible gesture, beyond subtle pickup coloration.
- A single output stage keeps repeated contacts from receiving independent gain.
- Listening comes before another factory-bank expansion; measurements screen
  numerical failures and weak contrasts without deciding the owner's preference.

The original finite native milestone introduced ALLOY, editable
SOURCE/FOCUS/BODY/LENGTH controls, saved MIDI and velocity, eight starting
patches and a dry sixteen-pad kit for both MPC formats. The owner heard and
approved the fuller-body direction after finding the Bloom/Deep tail variants
indistinguishable. Those references remain available alongside the new voices;
the broader moving-geometry design remains later work.

## Listen and reproduce

The published [four-voice native audition](https://caustic-snipsnap-voices.bertcalm.chatgpt.site/)
contains 42 recordings, native patches and all six downloads. It uses a
companion site so the original listening archive can keep its recordings
within the hosting limit. All 275 earlier non-navigation files remain exact;
only the three original HTML pages gain links to the native voices.

The companion's 139 files match the generated payload except four documented
HTML/history-link metadata files. Its audio and native patch hashes remain
unchanged. The browser checks above exercise that payload.

Reproduce the current four-voice comparison with:

```text
./gradlew --no-daemon :synth:generateCausticCharactersAudition
./gradlew --no-daemon :synth:generateCausticKit
```

The voice generator writes `testkit/caustic-native-voices-audition/` separately
from every earlier pack. Its 42 cases include twelve defaults across four voices
at C2/C4/C6, then SOURCE, FOCUS, BODY, LENGTH and two control-pair comparisons.
Native WAVs are the complete saved patch render. Matched copies apply
`AuditionLevel` once. The compact comparison download contains all twelve
defaults; each voice has a separate download with its control cases and native
JSON patches. A sixth ZIP carries the sixteen-pad MPC starter. Every generated
asset and ZIP must stay below 25 MiB. The browser
plays fixed recordings and does not synthesize live knob changes.

The earlier [ALLOY instrument audition](https://caustic-snipsnap-audition.bertcalm.chatgpt.site/instrument/)
retains twenty finite recordings, eight original starting patches, portable
JSON recipes and its original starter download. Reproduce that comparison with:

```text
./gradlew --no-daemon :synth:generateCausticInstrumentAudition
./gradlew --no-daemon :synth:generateCausticKit
```

The original voice task writes `testkit/caustic-instrument-audition/`; the kit
task writes the current starter to `testkit/caustic-kit/`.

The [archived private audition](https://caustic-snipsnap-audition.bertcalm.chatgpt.site/)
retains the earlier circular studies on ChatGPT Sites: a suggested listening
order, a depth-and-character comparison, the original position studies, mechanism
controls, register/velocity probes and local notes.

```text
./gradlew --no-daemon :synth:test --tests 'com.snipsnap.synth.Caustic*'
./gradlew --no-daemon :synth:generateCausticAudition
./gradlew --no-daemon test
```

The generator writes `testkit/caustic-audition/`: 48 finite notes, shared-gain
and independently matched PCM24 WAVs, complete JSON recipes, measurements and
source fingerprints. All 44 recordings from the first three studies retain their
IDs and bytes. Separate original, depth, Alloy-bloom and Alloy-body ZIPs keep each download
compact. Use a local server or published page for decoded Web Audio playback.
The downloaded pack also supports local media playback. Repeat replays a finite
recording; it is not a HOLD or seam test.

In the archived studies, use **Level matched** to compare tone and **Shared gain**
to hear collection strength and velocity. One native export gain applies within each renderer
revision; the original pack keeps its original gain, including the source. No
per-position compensation or rack FX runs inside synthesis. Matched exports use
the existing `AuditionLevel` loudest-200-ms rule.

## Archived models and recipes

`Caustic.Config` stores SOURCE, FOCUS, MIDI, velocity, duration, aperture,
musical delay scale, ray count, renderer model and material character. Model 1
retains the first renderer and its original version-1 recipe bytes. Model 2
adds explicit BODY, REED and ALLOY choices; their recipes record the choice.
`CausticRecipe` adds an explicit diagnostic variant in strict, versioned JSON.
Replay a recipe with `CausticRecipe.fromJsonText(text).inspect()`. This
standalone prototype is outside the production `Patch` registry.

The circular boundary uses half-step, equal-arc quadrature and an explicit
transparent entrance: only interior-facing reflection points are illuminated.
Launch weights normalize before reception; nine pickups share a fixed budget.
SOURCE uses inverse distance with an exact parallel limit. FOCUS moves the same
band for all sources. Forward segments stop at the next circle boundary; a
smooth finite aperture bounds reception. Nonnegative density, coherent signed
audio and the mathematical envelope are separate quantities.

Up to twelve band-limited harmonics enter a finite envelope with a 12 ms attack
term and 1.1 s exponential decay. Velocity changes effort and harmonic slope
before excitation. Relative incident distance and forward path length determine
phase and envelope delay. `delayR * delayCycles / noteHz` is a musical scale,
not a physical cup dimension or measured speed of sound.

The two-exponential source permits causal coefficient events that preserve each
static path's delayed envelope and carrier phase without visiting every path at
every audio sample. Independent direct sums verify this representation,
including the lowest supported MIDI note at maximum delay scale. Moving paths
and arbitrary envelopes need separate validation.

In model 1, fixed pickup losses and signed participation feed sixteen shared,
uncoupled `Modes.Bank` resonances: twelve harmonic modes and four quiet
near-harmonic companions. Material gains are fixed; FOCUS adds no independent
oscillators. A quiet direct root supports sparse/canceling positions. Rendering
uses 4x processing, existing antialias filtering, decimation, DC removal and a
short finite-tail fade, yielding mono 44.1 kHz samples. Stable poles and
normalized launch weights do not establish acoustic energy conservation.

## Depth and character

Model 2 uses twenty-four modes: twelve main resonances and twelve nearby
sympathetic partners. A short transient and a longer tonal envelope illuminate
the same paths; each uses an exact causal delayed representation. The material
choices change excitation, spatial participation and resonant
frequency/lifetime relationships inside the vessel. BODY explores a fuller
low-mid response and slower bloom. REED emphasizes hollow harmonic colour and
nearby beating resonances. ALLOY explores a brighter, slightly inharmonic ring.
All retain the same circular reflection geometry, coherent path delays and
requested pitch. They are alternative listening candidates rather than a final
factory preset bank.

The first comparison holds note, effort, SOURCE and FOCUS constant against the
original. Register and pickup probes test whether each character carries beyond
one favourable note. Use matched playback for colour, and shared gain to assess
native balance. No external effects or room reverb create the added body.

## Alloy listening direction

Alloy was selected as the strongest direction in the second audition. Its
model-2 recordings remain the reference for the next pass, alongside the other
original recordings and their listening notes.

The Alloy bloom pass keeps the excitation, tuning, root balance and primary
resonances fixed. Model 3 adds one explicit `bloom` value: it lengthens the
companion resonances for partials 2–7 by up to 1.7 times their original T60. At
zero bloom it reproduces model 2 exactly. Half and full bloom provide two
controlled comparisons at C2, C4, C6 and a sparse pickup position. The new
notes ran for eight seconds to expose the late ring.

The owner found Bloom and Deep indistinguishable in normal listening.
Subsequent checks confirmed almost identical main-note spectra: their larger
relative changes occurred in a quiet tail. These recordings remain archived;
tail-energy percentages alone do not establish an audible choice.

Model 4 offers one fixed Fuller body comparison against the preferred Alloy.
It retains the metal transient, modal frequencies, gains, spatial routing and
lifetimes, but changes tonal excitation to a 35 ms attack, 1.5 s decay and
three times the force. This targets the audible body at 0.15–1.5 seconds. Tests
and exports compare normalized spectra and gain-fitted waveform differences in
that window, alongside pitch, headroom and exact legacy preservation.

The existing source and pickup controls still determine coherent excitation.
No output reverb or extra oscillators create this contrast. Model 4 requires
ALLOY and zero bloom; recipe schema 4 stores that explicit fixed model choice.

## Original ALLOY native model-1 contract

The characteristic gesture is **metallic strike → tonal body grows beneath it
→ position-dependent ringing → finite release**. The core uses literal circular
reflection and coherent delayed excitation; it does not simulate a measured
metal vessel. Modal frequencies, material gains and musical path-delay scaling
are sound-design choices. MERCURY changes a body's response through water
loading and REVEL mixes a circular ensemble through moving microphones;
Caustic's distinguishing controls move source illumination and coherent pickup
paths through one shared body.

The first native voice was ALLOY. `CausticInstrument` is the production control
and rendering API; `CausticPatch` stores the name, voice, complete macro map,
explicit MIDI root, excitation velocity and native model version. Native model
1 is a separate format from the archived `CausticRecipe` models 1–4. Archived
recipes retain their original interpretation and files.

| Control | Default | Model quantity |
| --- | --- | --- |
| SOURCE | .50 | Source distance/illumination, including the exact parallel limit. |
| FOCUS | .19 | Position of the nine finite pickups; paths retain signed phase and delay. |
| BODY | 1 | Tonal attack, decay and drive between preferred Alloy and Fuller body; the short metal exciter remains fixed. |
| LENGTH | 1 | Half-second to eight-second finite gesture; tonal and modal decay scale together. |
| HOLD | 0 | Reserved. Positive values are refused until sustained excitation and an independently checked loop exist. |

The phone's ROOT and VELOCITY controls are saved outside the macro map. The
first native range is C2–C6 (MIDI 36–84); lower legacy diagnostic notes do not
establish a native playable range. BODY=0, LENGTH=1 retains the preferred
Alloy excitation. BODY=1, LENGTH=1 retains the approved Fuller-body excitation:
35 ms tonal attack, 1.5 s decay and force 210, above the same 6 ms metal edge.
Intermediate BODY positions are new listening candidates. LENGTH shortens the
source and modal release rather than cutting an otherwise unchanged recording.

Native output follows the house melodic conditioning and velocity policy.
Unconditioned/raw probes remain available for transfer, headroom and endpoint
comparisons. Loudness conditioning is outside the body model. Finite notes
longer than the classifier's 1.5-second threshold carry LOOP class metadata;
that class does not claim a held source or a seamless join. The starter uses
ordinary finite pad playback and has no held instrument export.

The bounded geometry cache is keyed by SOURCE, FOCUS, ray count and
aperture. Pitch, velocity, BODY and LENGTH do not invalidate static geometry.
Audio state belongs to each render. The native fast path folds the static
linear response into forced and free modal coefficients, preserving causal
arrivals and signed carrier phase. Independent delayed-path modal calculations
verify the result, including events that arrive between samples.
The archived renderer remains the reference, not a mutable production preset.

### Why it is built this way

- One voice kept the heard metallic identity central while the original controls
  were tested; the earlier Body and Reed studies remain available as references.
- BODY changes energy entering the resonant body during the audible main note.
  Extending only a quiet tail failed to provide a useful listening choice.
- Static path preparation is shared, while each note owns its modal state.
  This avoids repeated geometry work without carrying sound between renders.
- Exact linear response removes repeated projection work from the audio loop.
  Independent numerical checks verify causal arrivals and free ringing.
- HOLD is reserved because a decaying recording has no settled sustained state.
  Building the finite instrument first keeps that separate DSP task explicit.

## Comparisons and verification

- Source only: the original finite tone before the vessel.
- Full vessel: coherent paths excite the shared modes.
- Carrier phases aligned: retain delayed envelopes and signed routing while
  removing relative carrier phases.
- Bank bypassed: retain signed excitation and the quiet root; a fixed excitation
  gain replaces modal dynamics.
- Gain only: one scalar collection ratio changes a fixed reference vessel sound.

SOURCE 0, .5 and 1 share FOCUS .12, .19, .245, .38 and .85, covering
concentrations, shoulders and sparse positions. A density maximum can cancel in
coherent audio and need not be loudest. Other clips cover C2/C6 and soft/hard
effort. The owner's listening verdict completes sonic acceptance.

Tests cover reflection, endpoints, forward segments, budgets, ray convergence,
direct/compressed transfer, determinism, silence, raw/state bounds, spectral
activity, velocity, scalar controls, cancellation and strict recipe replay. The
generator checks raw/PCM bounds, early-window drum classification, moderate
measured pitch error in both the FFT root and broadband detector, and
representative sample-identical replay. The included exploratory geometry
figure does not establish `.001R` envelope accuracy.

The initial 24-clip run measured a maximum raw sample peak of 0.3713, maximum
modal-state amplitude of 0.3292 and worst constrained root error below 0.62
cents. One common export gain of 1.0 preserved the original amplitudes.
Preparation took about 24 ms and rendering about 4.34 s for 108 s of audio on
an OpenJDK 21 Linux amd64 container. These are this pack's measurements, not
phone budgets or a sonic acceptance verdict.

## Later work

Deformation, SCATTER, exact-stop ORBIT, verified sustained HOLD, further
voices, the broader preset bank and held keygroup export remain later work.
The finite instrument includes native patch/kit registration and the phone
picker. Phone latency and the new kit's hardware load/play check remain
device verification, separate from JVM and export-reader checks. No real-time
ray-traced engine is required for this sample workflow.

## Original native model-1 evidence

- **Heard:** the owner preferred Alloy, could not distinguish Bloom/Deep in normal
  listening, then heard and approved Fuller body's main-note change. Native
  finishing, intermediate controls and six starter positions still need listening.
- **Verified:** native model-1 patches preserve MIDI and excitation velocity; four
  active macros plus reserved HOLD; one ALLOY voice, eight starter positions and
  sixteen dry finite pads. All 148 original study file hashes remain unchanged;
  the earlier 48-recording listening study is retained. Native patch and WAV hashes
  passed the artifact audit, and browser checks played all forty native/matched
  WAVs, including transport switching, load retry and nested page routing.
- **Measured:** the twenty-clip native pack has no generator warnings or hard
  failures. Its maximum native sample peak is 0.9900, worst measured broad pitch
  error is 13.43 cents, and minimum pitch confidence is 0.9333. The generator
  checks patch round trips, representative sample-identical replay and classifier
  safety; these measurements do not replace listening acceptance.
- **Export checked:** the starter contains sixteen native patches and WAVs,
  one MPC 3 track and one MPC 2 program. ZIP CRCs, twenty-two checksums and
  export-reader checks passed. All pads are finite one-shots and the MPC 2 loop
  flags are disabled. The reader does not check pad colour, one-shot or humanize;
  the separate artifact audit checks the exported one-shot and loop settings.
- **Render cost:** before the native rewrite, three eight-second C4 renders took
  4.36–5.19 s on this Linux/OpenJDK 21 container. The initial analytic renderer
  reached 0.329 s after warmup, with a full-note raw RMS error of 2.35e-7 against
  legacy Fuller body. In the native pack generated from `9c05bb8d`, warm
  eight-second examples took 151–157 ms and the two half-second notes took
  11–13 ms; other eight-second captures reached 499 ms during generation.
  These are container timings, not phone latency measurements.
- **Android:** the debug app, all four native ABIs and the instrumentation
  test APK assembled. Actual UI tests could not run: the software-only Android emulator's system watchdog killed
  its boot process, and the targeted timeout-property retry was unsupported.
  No phone performance or hardware MPC load/play claim follows from these checks.
- **DSP checks:** all 49 chromatic notes in the native C2–C6 range pass the
  requested-pitch check. Register/geometry corners, 4× versus 16× aliasing,
  causal modal evolution, low/middle control authority, deterministic replay
  and finite decay checks also passed in the recovered regression run.
- **JVM verification:** the initial 64 focused Caustic tests and authored-note
  SPREAD check passed. The added chromatic scan and DSP checks above also pass.
  The broader nine-module suite result is recorded in the change's pull-request
  validation section, separately from these focused and artifact checks.
