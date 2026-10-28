# FILAMENT — guided paths

Filament is a deterministic, sample-free pitched instrument for SnipSnap's
offline renderer. One excitation enters a normalized set of guided paths.
Ordered bend sites divide each path's remaining allocation between its
endpoint, escaped sound and absorption. The escaped sound alone feeds a
shared, damped halo. The gesture is **contain → bend → spill**.

The supplied [engineering specification](superpowers/specs/2026-10-19-filament-engine-engineering-spec.md)
is preserved as the proposal. The approved instrument implements THREAD, PULSE and
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
fixture. Model version 3 selects reactive routing; model 2 retains the first
character revision and model 1 the original response. JSON without a model
still selects model 1. Derived taps and runtime filter state remain internal.
Macro edits and velocity rendering preserve
the nonmacro configuration.

An explicit EARLY/LATE/COIL profile replaces the voice's bend-location pattern;
BEND still sets its severity. `perfectRetention` takes precedence over bend,
absorption and endpoint loss. The seed fixes strand variation across macro
edits. Near-axis route offsets are fitted to root periods across strands to
support the requested note inside the launch budget; other path classes keep
their fractional-period interference. This is a musical tuning choice.

The owner approved the model-3 direction after the third listening revision.
The twelve approved `FilamentPresets` recipes now belong to the shared factory
library, CLI and phone SYNTH picker: three finite sounds and one held sound
per voice. Their controls, geometry, roots, velocities and model versions are
unchanged from the published audition. All factory and audition recipes are dry.

On the phone, choose FILAMENT, then THREAD, PULSE or CURL. ROOT selects MIDI
36–84 and VELOCITY changes the native excitation. Loading a preset, editing,
SCRAMBLE, navigating away, saving to YOURS and reopening a pad retain the
complete recipe, including older model versions. SCRAMBLE varies the six
controls near the current patch and returns HOLD to a finite gesture.

The FILAMENT starter places four C-minor-pentatonic notes from Glass Thread
before the twelve factory recipes. Every pad carries its full dry recipe for
replay and native velocity layers. Held pads contain a settled sustain cycle
and retain the starter's one-shot playback setting; playing them once does
not add an attack or automatically repeat the sample.

## Rendering and playable THREAD keys

Ordinary rendering and diagnostics share the same synthesis core. Playback
skips optional stem buffers and energy ledgers; the activity memory, filters,
DC state, arithmetic order and held convergence rule remain part of both
paths. Routing uses fixed arrays at the sample rate. Rendering checks
cancellation during source generation and propagation, including thread
interrupts from the phone preview job.

The phone keeps at most eight dry previews and 16 MiB of sample data for one
SYNTH visit. The cache key includes voice, settled controls, root, velocity,
configuration and model. Stored audio and returned hits own their arrays;
processing a reopened pad cannot mutate a later preview. Effects run after
the cache. Cache hits improve repeated previews; a newly edited patch still
requires synthesis. Benchmark cold edits separately from cache hits.

Model-3 THREAD, PULSE and CURL expose MAKE INSTRUMENT. Each renders native C3,
C4 and C5 roots covering MIDI 48–72, with one velocity layer. It retains
the actual zero-state onset of the periodically driven network and marks
its settled final cycle for repetition. Independently advanced preceding
and following cycles check recurrence. It does not splice a finite pluck
onto a different sustain source. The ordinary HOLD pad remains sustain-only.

The existing keygroup host owns note-velocity gain and note-off release.
The captured velocity still changes source articulation and routing; exported
layers remove its separate native gain law once. Standalone previews retain
native gain. The program release fades the whole note, including its halo;
an independent post-release halo tail remains outside the held-instrument path.

`FilamentKeys`, `FilamentPadMaker` and the phone's existing instrument sheet
use the established `KeyNote`, `OneNote` and MPC 2/MPC 3 package paths. WAV
sampler markers, native roots and loop boundaries accompany the audio.
`TrackData/filament.patch.json` retains the original source recipe, while
release remains a program property. Older saved models continue through their
existing pad renderer. The capability is shared by the phone, package maker and
renderer, so a voice cannot appear in the sheet and fail a different entry guard.

A render-only Java 17 benchmark on this workspace's four-vCPU Linux host used
separate before/after JVMs, two warmups and five complete renders per recipe.
Median times below exclude caching, file encoding and I/O. Both runs produced
identical native Float32 hashes; a separate pass also matched all 36 complete
factory recipes across models 1–3, including frame counts.

| Recipe | Before | After | Speedup |
| --- | ---: | ---: | ---: |
| Glass Thread | 1.830 s | 0.998 s | 1.83× |
| Four Routes | 2.958 s | 1.440 s | 2.05× |
| Held Thread | 4.444 s | 1.853 s | 2.40× |
| Held Procession | 5.934 s | 2.646 s | 2.24× |

These are desktop measurements, not phone latency guarantees. New edits still
cost a full render; the cache only reuses an identical dry recipe. Run
`./gradlew :synth:benchmarkFilament -PfilamentBenchmarkOutput=/fresh/results.json`
for the explicit benchmark. Its JSON includes patches, timing samples, JVM
configuration, loaded engine/audio JAR hashes and native sample hashes. The
same compiled probe can run against preserved baseline JARs without relying
on the checkout label to identify the measured code.

Generate the small instrument and its listening handoff with
`./gradlew :shell:generateFilamentKeysAudition`. A fresh
`testkit/filament-thread-keys/` contains the MPC package, source settings,
three native attack/loop WAVs and a stereo reference-player demo. The demo
loads the exported files, holds each root beyond its first wrap and checks
note-off release. Its manifest labels the host gain and records sample
markers and hashes. Hardware import and listening acceptance remain separate.

Use `:shell:generateFilamentKeysAudition -PfilamentKeysVoice=ALL` for the complete
held family. A fresh `testkit/filament-held-keys/` contains three ordinary program
folders, individual ZIPs and `filament-held-keys.zip`. The combined ZIP has the
three program folders directly at its root. Select `PULSE` or `CURL` instead of
`ALL` to produce just that instrument; the default remains the original THREAD
handoff. Each voice uses its first approved factory recipe at native velocity 1.
The demos play only the exported files through wrapping and note-off release,
with the reference player's gain labeled separately from native sample gain.

PULSE keeps its recurring articulated arrivals and shorter activity-memory
release; CURL keeps its slower exchanged bloom and shared escaped halo. They
start their original voice-specific periodic drives from a quiet network. The
retained onset and accepted sustain belong to that one continuously advanced
network, with the approved source parameters and fixed native output gain.
The three-root/one-layer scope and 0.1–1.5 s program release apply to every voice.
The program fade includes the halo. Physical phone/MPC acceptance and listening
acceptance of the new PULSE/CURL keygroups remain open.

Why it is built this way:

- One DSP core keeps optimization and diagnostic evidence tied to the same sound.
- Retaining genuine startup preserves the guide's opening gesture before sustain.
- Three roots and one layer make a small export and playback proof before wider zoning.
- Existing keygroup formats already carry the attack and loop; no new sample format is needed.

## Revised voice character

Model 2 gives the same three voices different overtone envelopes: THREAD
sheds its upper glints, PULSE articulates changing harmonic groups, and CURL
lets its upper colour bloom after the root. The requested fundamental remains
part of that one bounded excitation. The source does not change when BEND,
geometry profiles or diagnostic routing substitutions change.

Static path colour and dispersion give the escaping branch a different
response from the endpoint. Each voice has its own eight-mode halo. Its
normalization bounds the combined modal response, allowing individual
resonances to speak without independently making up the collectors' losses.
These are internal synthesis changes, not an external effect chain.

The revised HOLD material has a longer carrier-fitted recurrence period with
periodic overtone motion. Its actual delay history and filter state must
still settle, and predecessor/current audio must still recur independently.
Model 1 keeps the shorter original period and its original audio exactly.
`FilamentPresets.original()` retains those twelve original recipes, and
`FilamentPresets.revision2()` retains the twelve model-2 recipes. The audition's
character comparisons keep the note, velocity, controls and configuration
fixed across models.

The model-2 audition rendered 125 successful clips: the original 88 plus 37
comparison, recipe, context, sweep and mechanism clips. All 366 original WAVs
retain their exact SHA256 fingerprints. Thirty-five new ordinary saved patches
also decode and reproduce their native samples exactly. Twelve revised presets
range from a focused held THREAD to a halo-led held CURL; the nine total held
clips retain independently rendered PCM evidence.

In the shared-note character comparisons, the revised mixed-output spectral
centroids develop as follows. The analysis windows are early (0.04–0.18 s),
body (0.20–0.55 s) and late (0.75–1.15 s), at MIDI 48 and velocity 0.7.

| Voice | Early | Body | Late |
| --- | ---: | ---: | ---: |
| THREAD | 246 Hz | 179 Hz | 149 Hz |
| PULSE | 280 Hz | 210 Hz | 155 Hz |
| CURL | 181 Hz | 342 Hz | 511 Hz |

These measurements support the different shedding and blooming gestures;
they do not replace listening. The corresponding requested-root errors stay
within three cents. Seventy finite examples pass the screen for kick, snare,
clap, hat and tom classifications. Generic PERC is the classifier's permitted
fallback for some brighter harmonic material; pitch and confidence are measured
separately. Native output keeps its fixed gain, so use Matched to compare tone
across models without their native loudness difference.

## Reactive guided exchange

Model 3 keeps model 2's excitation and static geometry. Halfway along the
guide, marginal paths exchange their remaining guided signals before entering
the destination paths' later bends. The near-axis path remains separate.
BUNDLE opens exchanges between participating strands as well as the existing
within-strand exchange. INJECT and velocity alter the actual upper-band
activity that drives a short attack and slower release memory; there is no
output LFO or added source.

PULSE's shorter memory release lets successive articulations reopen and relax
the exchange. THREAD and CURL retain slower control motion.

PULSE's fixed collection vectors put more weight on marginal endpoints
and post-junction escape. Each complete vector has norm at most one. Active,
disabled-exchange and frozen-memory comparisons use the same collection
vectors, so their differences come from the exchange behavior.

The junction uses orthogonal pair rotations, preserving the instantaneous sum
of squared channel amplitudes even while the activity memory changes the
angle. Early escaped input has already left the guide. Later retention,
escape and absorption fractions operate on the exchanged signal that actually
arrives, rather than donating a second copy of the original launch. Delays
stay fixed and no audio signal feeds back into an earlier section.

The constant-loss sections within each half use compiled delayed taps carrying
cumulative retention. Each arrival uses one fractional-delay interpolation;
the model does not cascade a separate fractional interpolator at every section.
The exchanged midpoint waveform itself supplies the destination half's history.

Coherent exchange can move power between paths, so the branch/site allocation
tables are explicitly an **uncoupled nominal reference** for model 3. Its
separate waveform-energy ledger records actual junction input/output, split
residuals, terminal energies, collector norms and activity memory. Residual
uncollected energy includes passive filtering and stored/undelivered history;
closing that accounting identity is not an independent proof of conservation.
The pointwise junction/split checks and terminal-energy bounds provide that
separate evidence.

Held evidence includes every post-junction delay and activity state. Integral
energy measurements describe the accepted output cycle, rather than mixing
preroll energy with a single exported period. Diagnostic disabled-exchange and
frozen-memory comparisons retain the same excitation. An 8× internal-rate
reference checks the ordinary 4× render, with antialiasing before decimation.

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
Native output uses a fixed gain of 1.20; raw/stem exports all use 0.25. Neither
collector receives independent loudness makeup.

HOLD fits the excitation and pulse schedule to a loop, populates delay
history and settles the filters in a bounded search. Evidence must compare
genuinely synthesized successive cycles before wrap treatment, including
exported PCM. Individual path delays need not divide the loop period.
A held pad is sustain-only material. The separate THREAD keygroup pilot
retains an attack before its loop; physical phone/MPC hardware acceptance
remains outstanding.

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

The original model-1 corpus contains 88 successful clips and twelve provisional
presets. All 39 standard finite default/preset/register cases avoid percussion
classes in a 1.2-second onset window. Six held clips include independently
generated predecessor/current PCM evidence; their largest complete-cycle
relative squared error is 5.31e-13 and largest PCM seam error is zero. Maximum
scalar allocation error is 3.13e-16, and the highest raw mixed peak is 0.228.
These are measurements of this listening matrix, not universal guarantees.

That original render used Temurin Java 17 on Linux/amd64 with four visible processors
(AMD EPYC 9V74) and a 768 MiB maximum heap. Median preparation was 0.16 ms and
median synthesis 657 ms; the 88-clip task took about 70 seconds including
analysis/export. The manifest distinguishes observed heap use and final audio
buffer size from a profiled peak. Java 17 matches the repository's CI runtime.

The owner's listening verdict completes sonic acceptance: length should
unfold arrivals, bends should deplete THROUGH while feeding SPILL, and the
full model should reveal ordered escape that a generic reverb send loses.
Numerical checks establish implementation properties, not that this gesture
is musically convincing.
