# Apsis — musical bank

[Private listening page](https://snipsnap-apsis-audition.bertcalm.chatgpt.site)

Apsis is an experimental sample-free pitched instrument. A powered root and a
central resonator feed independently orbiting modal bodies. Elliptical motion
changes excitation and internal exchange: approach, rush past, then linger.
The orbital periods do not set the played note.

The second listening round responded to the owner's report that the first takes
had little variation. That round introduced model 2: the center follows the encounters,
body contacts have distinct upper resonances, and bright modes decay before the
next passage. Explicit model-1 patches retain the original renderer. Missing or
null model metadata in a saved PASSAGE recipe also decodes as model 1.

The musical-bank round uses model 3 for new patches. It retains model 2's
body-forward gesture, adds velocity-dependent excitation color and a normalized
material exchange port, and reuses prepared geometry across compatible renders.
Explicit model-1 and model-2 recipes retain their audio. A newly constructed
`ApsisConfig()` now selects model 3. The owner found model 2 more distinctive;
the musical bank still awaits the owner's listening decision.

The Kotlin engine now implements PASSAGE, GRAIN and ANCHOR, with three participating
bodies at the default BODIES setting and a stable roster of up to six. GRAIN and
ANCHOR port the owner-preferred Python study laws, with three starter presets
each. PASSAGE retains its six recipes and all three saved renderers. New voice
recipes use model 3; model-less GRAIN/ANCHOR recipes select their first model, 3.
The other study candidates remain Python prototypes. See
[the port notes](APSIS-POPULATIONS.md) for mechanisms, reproduction and limits. Dedicated keygroup export,
device acceptance, naming clearance, and the owner's sonic verdict remain later
work. All audition clips are dry.

## Android integration

SYNTH's engine picker includes APSIS with PASSAGE, GRAIN and ANCHOR: six
PASSAGE recipes and three starters per new voice. ROOT spans MIDI 36–84 (C2–C6); VELOCITY changes native excitation,
independently of STRETCH, DISTANCE, BODIES, ALIGN, COUPLE and HOLD. Loading an
older saved renderer retains that model and seed through edits, SCRAMBLE,
navigation, SAVE PRESET, YOURS and SEND TO PAD. SPREAD uses the authored root
rather than estimating it from an inharmonic contact.

HOLD at 0.99 or higher previews the entire settled cycle repeatedly until STOP.
STOP also suppresses autoplay from a render still in flight. Backgrounding the
screen stops Apsis and prevents a pending render from restarting its audition.
SEND TO PAD saves it as gated playback with an explicit full-sample loop flag;
releasing the pad uses the normal player's short stop fade. Finite notes play
their whole one-shot tail. Changing a held Apsis recipe back to a finite note
clears the loop flag. Sending a different synth to that pad also clears it.
Both MPC drum exporters write full-range slice-loop metadata and a sustaining
amplitude envelope for these new gated loop pads. Existing pads default to no
loop; one-shot and chain modes keep their existing behavior.

The published musical-bank ZIP predates this shared loop flag and retains its
dedicated native finishing step. Its source-kit re-export limitation below
describes that older artifact. The current bank generator and newly saved Android
Apsis pads carry the explicit flag through ordinary kit saves and exports, using
the shared exporters without an Apsis-only finishing step. MAKE INSTRUMENT and dedicated
keyboard multisampling are separate work.

Device pass: select each preset; change ROOT, VELOCITY and the movement controls;
leave and return; save/reload a YOURS preset; SEND TO PAD and reopen its recipe.
Compare finite tails with held repetition, release and retriggering. Then load
both exports on an MPC and check the same gate behavior. Emulator tests can check
controls and metadata, but physical audio, latency and MPC acceptance need devices.

## Render and recipe contract

- Deterministic mono, 44.1 kHz output, standard 4× internal processing and shared
  melodic leveling. Raw diagnostics are captured before final melodic gain.
- MIDI 36–84 and velocity are separate from the six normalized controls.
- STRETCH changes eccentricity at fixed semimajor axes and periods. DISTANCE
  changes the common orbital scale and therefore every period together.
- BODIES changes fractional participation without replacing body identities.
  ALIGN changes relative phases and orientations against the excitation map.
  COUPLE changes internal resonant exchange, without changing trajectories.
- ApsisPatch saves voice, macros, MIDI, velocity, model version, and seed
  context. Current body definitions and the reference-compatible HOLD policy
  belong to that model version. No user orbit/map override editor is exposed.
- Finite output routes as TONAL; successfully rendered held output routes as
  LOOP. Audio-classifier measurements remain separate from this metadata.
- Six provisional recipes and SynthKits.apsis() use the existing patch, recipe,
  native-velocity, preset, and kit infrastructure. The musical bank exports native
  MPC 3 and MPC 2 programs. Android integration uses the same saved patches.

## Geometry and internal sound

The attraction point is the focus of each ellipse. A safeguarded numerical
solver evaluates E − e sin(E) = M, with a bounded iteration count and checked
residual. Reference fixtures check closure, extrema, equal-time swept area,
independent numerical area integration, and a common T²/a³ ratio. Runtime
trajectory interpolation and excitation encounters require separate checks.

Position changes injection and the distribution of bright and sustained
excitation before the resonators ring. The sympathetic comparison disables
direct excitation of a recipient and auditions that recipient separately.
The uniform-angular comparison changes the motion clock while retaining the
same geometric path. These switches are listening diagnostics, not presets.

Normalized modal coordinates permit damped rotations and orthogonal pair
exchange. With no forcing, every free update has norm at most rho < 1 and
each coupling transform has norm 1; their product is contractive for every
allowed sequence of position-dependent coefficients. Source injection remains
separate and bounded. Pickup and melodic leveling do not establish this bound.
Stable state energy alone also does not guarantee musical tuning: pitch and
listening checks are independent.

## HOLD

HOLD uses a commensurate reference-compatible roster. Periods divide one common
loop interval, and semimajor axes follow a ∝ T^(2/3). Fitting the interval to the
output frame and root-source clock scales all orbital periods together; it does
not independently round each body and claim an unchanged third law.

The powered excitation, orbital controls, and event schedules repeat while
modal and output-conditioning state preroll toward a periodic response. Natural
resonance frequencies need not each complete integer cycles: the damped,
periodically forced system must converge. Adjacent audio cycles and internal
boundary states provide separate recurrence evidence. Keys.seamError < 1e-3 is
the project wrap requirement, not proof that the complete system recurs.

The ordinary Snip API has no attack-plus-loop marker. Held output therefore
contains the settled repeating orbit. The opening approach belongs to finite
output. Listen to several held repetitions for missing or doubled passages.

In model 2, each noninteger material carrier uses time since its contact, with
a deterministic body phase. The root contact keeps the common source phase so
it cannot fight the played fundamental. Natural modal frequencies need not be
integer loop harmonics: finite recurring forcing drives the damped network to
its periodic response. Contacts crossing the boundary retain their event age.

## Reproduce

```sh
./gradlew --no-daemon :synth:generateApsisAudition
./gradlew --no-daemon :synth:generateApsisAudition -PapsisOutput=/tmp/apsis-study
./gradlew --no-daemon :synth:generateApsisKit
./gradlew --no-daemon :synth:generateApsisMusicalAudition
./gradlew --no-daemon :cli:run --args="synth APSIS PASSAGE --all --out /tmp/apsis"
./gradlew --no-daemon :cli:run --args="synth APSIS PASSAGE --midi 60 --velocity .65 --macros STRETCH=.8,COUPLE=.6 --out /tmp/apsis"
./gradlew --no-daemon test
```

The retained model-1/model-2 generator writes WAV pairs, recipes, orbital traces, and a manifest beneath
testkit/apsis-audition. Raw and matched files make gain changes inspectable.
The published page begins silent, plays one clip at a time, and offers explicit
stop, playback level, file downloads, and repeating held playback. Start with
Musical examples and the native pad map. Voicing history retains Original /
steady root and Revoiced / bodies emerge alongside the earlier preset gestures.
Before/after takes and musical presets each target the same loudest-200ms RMS
independently, subject to the peak ceiling. Controlled macro, velocity and
recipient comparisons retain a shared gain. Each clip's settings declare its policy;
raw audio keeps the native balance.

The musical task writes to testkit/apsis-musical (override with
`-PapsisMusicalOutput=/absolute/output`). It first exports the native kit and
model-3 playing comparisons, then reads the exported PCM24 files to produce the
musical examples. The site opens with these musical examples; Voicing history
retains the earlier listening round. The downloadable ZIP contains one shared
WAV set, programs for both MPC versions, complete layer recipes and a pad map.

## Musical bank and playback

| Pads | Role | Notes / recipes | Velocity zones |
| --- | --- | --- | --- |
| A01–04 | Bass | Close Rush, C2 / E-flat2 / G2 / B-flat2 | Soft, middle, hard |
| A05–08 | Keys | Slow Apse, C3 / E-flat3 / G3 / B-flat3 | Soft, middle, hard |
| A09–12 | Chord tones | Ringing Bodies, C4 / E-flat4 / G4 / B-flat4 | Soft, middle, hard |
| A13–14 | Timing colors | Gathered Bloom / Spaced Replies, C4 | One layer |
| A15–16 | Held fields | Held Return, C3 / G3 | One layer |

There are 16 pads and 40 mono 44.1kHz PCM24 samples. A01–12 select native rendered
layers at MIDI velocities 1–41, 42–84 and 85–127. Layer excitation strengths are
35%, 65% and 100% of each pad's saved playing strength. Every layer retains a
complete patch recipe. The hardware's velocity-to-volume curve is unmeasured;
the layer inspections play the exported samples at unity before that scaling.

Finite pads play their complete one-shot tail and ignore note-off. A new trigger
of the same pad replaces its previous voice through a unique mute group; other
pads keep ringing. Held pads begin in an already settled cycle, loop the full
sample while gated, and cut immediately at note-off. Native release is zero.
There is no invented attack prefix, seam crossfade or preview-only release fade.

The musical examples preserve the approach before each bright passage. Onsets
are pad trigger times, not promises that a contact peak lands immediately on the
beat. Seven primary scores use velocity 127/unity and exact exported hard-layer
PCM. Three separate touch inspections select the actual soft/middle/hard layers.
One frozen monitor gain provides mixing headroom, with shared listening gains
for comparison groups. The optional color-inspection WAVs match each layer's
level independently and are labeled separately from the native-strength clips.

Score JSON records every trigger, pad, layer, patch, model, native playing
strength, gate frame and sample hash. Held gate times may choose a quiet frame
within 10ms of the requested gate; both requested and actual times are saved.
Scores do not trim the approach or reinterpret a finite renderer's duration.

The published native export used an Apsis-local finishing step for full-cycle
loop flags, gate envelopes and mute groups. Program readback, WAV `smpl` readback,
preflight and decoded PCM identity were separate checks. Its older kit.json
regenerates audio but does not retain all native loop flags on generic re-export.
The Android integration adds the shared full-cycle flag to newly generated banks
and removes that finishing step; generic re-export now retains their held loops.
Physical MPC playback and the velocity-to-volume response remain device checks.

## Model-three playing and preparation

Velocity changes upper and material-mode excitation before the resonators, while
the common fundamental keeps its pitch anchor. COUPLE retains the shared resonant
bridges and adds an orthogonal normalized material port. Both transfers preserve
the same passive-state energy bound; a brighter output mix does not establish
that bound. Listening comparisons hold geometry, note, seed and gain fixed.

Only model 3 uses the new prepared-motion cache. It stores immutable controls
and unscaled contact geometry for all six bodies. Note, velocity, seed, population
and coupling remain outside the reusable finite geometry. Frame-fitted held
periods, eccentricity, alignment, internal rate, control stride, duration and
diagnostic timing mode are included in the key. The cache retains at most six
entries and approximately 32MiB, with a 12MiB per-entry limit. Builds run outside
its lock; failed or cancelled preparations are not published. Audio and resonator
state are never cached. Oversized control grids retain only the small contact
schedule and solve controls at the original integer endpoints.

Production rendering also skips per-sample diagnostic energy bookkeeping. The
full probe path retains those passivity measurements, and final finite-output
checks remain active. Exact-audio comparisons cover the diagnostic switch,
cached/uncached rendering and saved models 1/2. Cache hits alone are not a latency
claim: report total production-render time as well as preparation time. The
prototype still does not claim tens-of-milliseconds uncached knob response.

### Final model-three measurements

Python/SciPy analysis of the actual raw PCM24 playing clips uses a 4096-point
Hann STFT, 10ms hops, 40Hz–10kHz and the powered interval 0.8–3.2 seconds.
Spectra are normalized per frame before comparing color.

| Playing velocity | Native RMS | Upper/root power ratio |
| --- | ---: | ---: |
| 0.25 | 0.003289 | 0.0355 |
| 0.65 | 0.009110 | 0.1733 |
| 1.00 | 0.016074 | 0.5537 |

Gain-normalized spectral total variation is 0.0571 from soft to middle and
0.1136 from middle to hard, or 0.1707 across the full range. COUPLE changes the
same measure by 0.0305 from 0 to 0.5 and 0.0400 from 0 to 1. Its upper/root
power ratios are 0.2203, 0.2923 and 0.2931, respectively. The upper/root balance
therefore changes less in the top half of COUPLE. These numbers establish
excitation-color and transfer changes, not their perceived musical value.

Independent artifact validation checked seven 0.35-second powered windows in
each of the 40 native layers. All 280 targeted fundamental-peak measurements
stay within 8.9 cents; the largest absolute per-layer median is below 2.044
cents. This targets the requested fundamental using `FineTuning.measuredHz`.
General autocorrelation can be pulled low during bright inharmonic contacts,
so the root-peak figures do not prove that every transient has unambiguous
perceived pitch.

Both exported held pads replay their native PCM exactly. Comparing each with
its independently evolved preceding cycle after PCM24 encoding gives seam
error 0; modal boundary-state relative errors are below 9.44e-16. No crossfade
establishes this recurrence. Ten normalized Float32 captures of models 1/2
(default, extreme and held at seed 0; default and held at seed 17) remain byte
identical to the `f4e55873` baseline.

Production timing uses an AMD EPYC 9V74, OpenJDK 21 and a warmed JVM, with six
warm-up renders before the timed runs. Finite renders are six seconds, mono
44.1kHz at 4× internally, without stems, traces or per-sample diagnostic
bookkeeping. The shared Gradle gate was idle during timing.

| Production render | Measured time |
| --- | ---: |
| Model 2 before / after diagnostic bypass, identical audio | 375 / 310ms |
| Model 3, preparation cache cleared before each run | 312ms |
| Model 3, reusable preparation already cached | 275ms |
| Model 3, 5.829-second held cycle with convergence and conditioning | 1.98s cold / 1.95s warm |

Finite times are medians. The model-2 comparison has 12 runs per path; model 3 has five preparation-cold
and 12 preparation-warm runs. Held timing is two observations, 1.98 and 1.95
seconds. A default six-second preparation occupies 5,558,960 retained bytes.
These are server JVM results, not phone measurements or a claim of
tens-of-milliseconds knob response. The retained `profile-ledger.json`,
`performance-spectra.json` and musical-validation `summary.json` record methods,
source hashes and the artifact checks.

### Why the bank is built this way

- Pitched rows give the gestures a bass, keys and chord role. The two timing
  colors and held fields add contrast without changing the established family.
- Three real sample zones carry softer and harder excitation onto the MPC.
  Volume scaling alone would lose the engine's new velocity color.
- Scores replay the exported PCM and native gate/retrigger policy so a polished
  substitute render cannot conceal a bad pad or a missing loop flag.
- Old model versions remain available. The musical round develops model 3 while
  preserving the model-2 gesture the owner already found more distinctive.

### Scoped validation

The final scoped gate passed 635 tests: 80 synth/registry/playing checks, all 389
kit tests, all 93 MPC 3 tests, all 39 XPM tests, three Apsis CLI checks and 31
user-preset checks. Browser QA passed 73 checks on desktop and mobile, including
all 16 playable pad previews, held wrapping, seven musical cards, downloads and
preservation of all 42 historical clips. The full legacy synth suite is not
claimed as passing for this revision.

The native ZIP is 21,251,115 bytes, with one shared set of 40 PCM24 WAVs rather
than duplicated source/export audio. Both programs retain their velocity zones,
held loop flags, immediate note-off and self mute groups. The musical scores
replay sample for sample from their saved JSON; maximum raw score peak is 0.380
with the common 0.25 monitor gain. There is no limiter or hidden release fade.

Ordinary clips use the renderer's finite policy; longer diagnostic clips declare
their requested duration in the audition recipe. The `.patch` download is the
ordinary engine patch. The `.json` audition recipe additionally records diagnostic
duration, ablations, and body isolation. The first passage and Kepler comparison
retain source, center, and three body WAV stems at the original shared gain.

The recipient-only response contains transferred upper modes, excluding the
protected root. Its native-level comparison is deliberately quiet. A separate
magnified pair uses a clearly declared matched inspection gain, with the same
gain applied to the silent bypass. Raw playback always retains native balance.

## Round-one measurements

These are historical measurements of model 1 at source revision
`3513f483ba6759cf1be358c1b7ff2b825f203dcf`, before the voicing revision below.

The 35-performance study contains 70 raw/matched WAVs plus ten component stems.
Across the complete-mix pitched fixtures at MIDI 36/60/84, the measured root error
is within 3.0 cents. Recipient-only upper-mode probes are excluded from that
pitch claim. Peak raw audio, including the overlapping phrase, is 0.659; maximum
absolute raw mean is below 2e-8. Standard pitched fixtures avoid the percussion
classifier buckets, while the generic length rule labels these long recordings
LOOP independently of finite engine metadata.

The 6.00476-second held fixture converges in two preroll cycles, with modal
boundary-state relative error 7.90e-9. Its independently evolved, conditioned
adjacent audio cycles match at float precision, including Keys.seamError = 0.
No seam crossfade is applied. Silent HOLD and repeated diagnostic event schedules
have separate checks.

A bright MIDI-84, high-stretch, close-orbit fixture compares the normal 4× path
with 16×: normalized spectral-power distance is at most 5.56e-7 over two measured
windows. This is a reference consistency measurement, not a formal alias-floor
claim. Halving the 64-internal-frame control interval gives a periapsis relative
RMS difference below 4.37e-7. The extreme finite probes show no unforced energy
growth. Complete prelevel diagnostics and source-file hashes accompany the
published manifest.

## Luthier review and second-round changes

### Fix now

**Sound character.** The first model's center and all six bodies share their
strongest modes at the root, twice the root and three times the root. A strong
continuous center dominated those bodies. Python/SciPy analysis of the actual
PCM24 audition WAVs found 99.31% of the default's powered spectral energy in the
fundamental band. Circular versus elongated motion changed the gain-independent
100ms envelope shape by only 0.211 dB RMS. The first audition therefore established
that parameters moved without establishing useful musical variation.

Model 2 reduces the invariant source, drives the center from the population's
encounter field, and exposes a distinct fourth mode in each body at imaginary
ratios 2.73, 3.91, 5.43, 7.17, 4.37 and 6.81. These are design choices, not measured
properties of a real material. Short finite contacts excite those modes; no
external effect or sample supplies the variation.

**Gesture and architecture.** Long fundamental memory and continuous powering
smoothed the first passages into a standing tone. Model 2 uses sharper regional
injection and shorter bright-mode memory while retaining a darker root-bearing
tail. Encounter shape uses radius relative to each body's semimajor axis; bounded
absolute-distance absorption remains separate. Outer bodies can now contribute
without four simultaneous distance penalties. Contact width follows the time
to leave a fixed angular region, with a bounded bandwidth floor. Kepler timing
therefore affects articulation as well as trigger time.

**Playing.** Local contact phase initially competed with the shared root during
a hard low-note attack. A MIDI-36 fixture measured +22.20 cents in its first
analysis window. Using the common source phase for the root contact reduced
that fixture to about -7.39 cents while leaving upper contacts local. A focused
regression check covers the failure; the complete range sweep remains separate.

### Why it is built this way

- The center supports the moving bodies so their encounters can shape the full
  sound. A constant dominant root would conceal the same behavior again.
- Short bright tails separate arrivals; a slower dark tail keeps the linger.
- Material contacts use local event time so noninteger colors can recur in HOLD.
  The root uses the shared source phase to preserve its pitch anchor.
- Model versions preserve old patches. A voicing improvement must not silently
  replace the audio saved by the first audition's recipes.

### Final second-round measurements

Python/SciPy analysis uses the actual raw PCM24 WAVs, a 4096-point Hann STFT,
10ms hops, and the powered interior from 0.8 seconds to 0.4 seconds before drive
release. Spectra are normalized per frame over 40Hz–10kHz. Envelope shape uses
100ms RMS windows and removes overall gain. Total variation is the mean fraction
of normalized spectral power redistributed between a pair, not an audibility score.

| Measurement | Original model 1 | Revised model 2 |
| --- | ---: | ---: |
| Default fundamental-band share of powered spectral energy | 99.31% | 74.22% |
| Default full-mix envelope P90/P10 | 0.81 dB | 18.71 dB |
| Body sum RMS relative to full mix | -12.24 dB | -1.98 dB |
| Source + center RMS relative to full mix | -2.35 dB | -11.17 dB |
| Circular/stretched gain-free envelope-shape difference | 0.21 dB | 6.53 dB |
| Circular/stretched normalized spectral total variation | 0.00158 | 0.17943 |
| Gathered/staggered envelope-shape difference | 0.68 dB | 13.59 dB |
| One/six-body envelope-shape difference | 0.67 dB | 12.23 dB |
| Connected/bypassed normalized spectral total variation | 0.00855 | 0.03700 |

COUPLE remains a smaller contrast than the motion controls; the source of its
response is still checked with a directly unexcited recipient. Six actual preset
gestures supplement the engineering comparisons. At the same C4 and velocity,
Slow Apse versus Close Rush gives 14.01 dB envelope-shape difference; Gathered
Bloom versus Spaced Replies gives 9.60 dB. These are articulation and spectral
measurements, not a claim that the presets have been heard or approved.

The final study has 42 performances, 84 raw/matched WAVs and ten component stems.
The original take's raw PCM24 WAV is byte identical to its first publication.
Across all admitted pitched fixtures, maximum measured root error is 7.39 cents;
maximum raw peak across the complete set is 0.295. Both model-2 held fixtures
recur with zero measured audio seam error; modal boundary relative errors are
below 3.37e-15. No crossfade establishes this recurrence.

The scoped Kotlin gate covers 198 distinct engine, patch/recipe, preset, velocity,
CLI, user-preset and XPM tests. Four added voicing guards use meaningful upper
power, gain-free envelope change, normalized spectral redistribution and the
hard low-note pitch fixture. The full legacy JVM suite is not claimed as passing.

### Try next

Rerender latency remains an open playability issue. The first study commonly
took roughly 1–1.6 seconds per full diagnostic performance. The final model-2
default measured 0.84 seconds of synthesis and 1.48 seconds
including stems and pitch analysis; its settled HOLD measured 2.18 seconds of
synthesis and 4.57 seconds for the complete diagnostic. These are single-run
observations, not a warmed production-render benchmark. Geometry controls and
unscaled contact schedules should be precomputed and cached by movement controls,
model, duration, internal rate, control stride, diagnostic motion mode and, for
frame-fitted HOLD, note. Apply velocity after lookup. A note or velocity change
should reuse source-independent trajectories. This was the proposal at the end
of round two; model 3 implements the bounded preparation cache described above.
The prototype does not claim tens-of-milliseconds knob response.

### Big idea

If future body colors leave the shared 2x/3x transfer behind, test an orthogonal
normalized velocity port that includes their colored modes. This is a proposed
extension, not part of model 2. Model 3 adds a weaker material port alongside the
resonant bridges. Full-mix transfer must remain distinguishable
from a separately magnified recipient-only demonstration.

### Disclosed, not fixed

This remains one PASSAGE voice and six provisional recipes. Phone integration,
device acceptance, the larger proposed production bank, and the owner's model-3
listening verdict remain open. Numerical contrast guards prevent the specific
masking regression; they do not certify musical quality. The full legacy JVM
suite is separate from the focused revision checks.
In model 2, velocity changed energy more than color; model 3's measured color
response is recorded above. The preset gestures can be strongly pulsed or
sparse; that trade still needs the owner's listening decision.

### What I measured, what I only read

Verified: repository APIs and original source at `3513f483`; the published WAVs'
source hashes match that version. Model-1 default, extreme and held normalized
Float32 captures replay sample for sample after versioning. Measured: actual
PCM24 WAV envelopes and normalized 40Hz–10kHz spectral power, plus Kotlin pitch,
stability and recurrence probes. At that round, caching and broader transfer
ports were proposed; model 3 now implements them, with separate measurements
above. Material character and useful musical contrast remain listening judgments.
Heard: the owner reported weak variation in round one and subsequently found
model 2 more distinctive. The model-3 musical bank still awaits that separate
listening decision. Luthier's main workflow was available; its bundled reference
files were not accessible in this session.

## Acceptance

Mathematical fixtures, bounded state, patch replay, macro activity, pitch,
classifier safety, recurrence, and listening answer different questions. The
first owner's listening decision is whether stretched motion creates an audible
patient linger and bright rush, and whether an otherwise unexcited recipient
answers through the shared center. A passing numerical gate does not settle
that decision.
