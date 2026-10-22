# Skitter in SnipSnap

Skitter is a deterministic offline mono pitched engine inspired by the EDP Wasp.
The two oscillators, signal-history memory and agitation drive one nonlinear
multimode filter. History and flutter are new proposals, not a reconstruction of
the historical circuit. The [supplied specification](superpowers/specs/2026-10-11-skitter-engine-engineering-spec.md)
is retained alongside this implementation.

## Persistent source bodies (R6 listening candidate)

The characteristic gesture is contact → strained upper harmonics → quick
unequal recovery → a distinct sustained body. This changes the source for
LEAD and HOLLOW, rather than adding another short gain bump. The existing
nonlinear filter, six controls and primary/reply/breath schedule remain the
instrument around that source.

LEAD combines a protected fundamental with six upper components whose phases
are `A + (n−1)B`. At rest they are harmonics 2–7. A narrow, smooth harmonic
packet supplies a persistent formant; BUZZ moves its center, while contact
and the previous circuit state disturb it. The two oscillators' unequal
recovery changes the upper components without replacing the rooted carrier.
A recurrence generates the packet without six separate sine evaluations.

HOLLOW combines an explicit carrier with sine phase modulation from the
octave oscillator, using a quadrature modulator. Its nonzero body index
produces odd-harmonic character throughout the note. Contact and delayed
history change that index around the body value. This is literal phase
modulation, not an acoustic reed model. Neither source uses oscillator resets,
an output effect, a new random stream, or additional recursive state.

| Control | Additional R6 source behavior |
| --- | --- |
| BUZZ | Moves LEAD's harmonic focus and HOLLOW's modulation index. |
| SKITTER | The existing unequal oscillator recoveries now strain the coupled upper spectrum. |
| AGITATE | Gates the delayed history/position contribution to source color. |
| VENT | Retains the existing low/band/high filter blend. |
| STATIC | Retains the existing audible noise and agitation forcing. |
| HOLD | Retains finite gate extension and the converged periodic gesture path. |

`SkitterProbe.bodyDisabled` selects the exact R5 source path. Combining it
with `articulationDisabled` selects R4. The four other voices bypass the new
body and retain their previous source arithmetic. R5's tests and generator
explicitly disable the new body so their historical contract stays testable.
The ordinary house output stage is unchanged; the audition uses unlevelled
raw output and one common playback gain.

The compact R6 proof imports the exact four frozen R5 proof WAVs. It adds only
two new raw PCM24 tracks, each containing full soft, medium and hard finite
notes at C3, seed 0 and the common controls. Revisions share a cue-relative
clock; a 20 ms linear crossfade permits comparison during the body. Restart
returns to contact. Matching remains optional and applies one constant gain
to the complete selected note. R4 and R5 pages and assets remain archived.

Reproduce with
`./gradlew --no-daemon :synth:generateSkitterCharacterR6Audition -PskitterR5Baseline=/absolute/frozen-r5-proof`.
Package with
`python3 scripts/package_skitter_r6.py /absolute/r5-hosted testkit/skitter-character-r6 /tmp/skitter-r6-hosted`.
The final tar must fit 256 MiB while every retained audio asset stays intact.

### R6 validation and limits

All 39 focused tests pass, including the unchanged prior admission margins.
The 60-case touch/pitch probe covers both voices, C2–C6, three velocities and
common/moderate-default controls: maximum root error is 2.315 cents and minimum
confidence .959. Both measured held loops converge in three periods with zero
raw seam and no bound hits.

An independent measurement of the actual HOLD 0 proof, with separate normalized
FFT power windows wholly inside 500–1500 ms, gives R6/R5 body redistribution of
57.1/43.5/30.3% in LEAD and 39.0/47.5/19.0% in HOLLOW at soft/medium/hard touch.
The same R5/R4 comparisons were only .030/.062/.161% and .721/.891/1.064%.
These measure spectral change, not perceived quality. R6 body levels also rise
by .24–5.25 dB in these comparisons; use optional complete-note matching when
judging tone without that level bias. Fixed gain still preserves relative touch
response, and no gain is written into the raw downloads.

A clean stationary C6 check at BUZZ 1 compares 4× against 16× rendering.
Excess nonharmonic power is −94.7 dB for LEAD and −109.7 dB for HOLLOW;
harmonic-envelope distance is .987%/.560%. This single clean setting is not a
universal alias floor. All 24 causal mechanism checks retain their previous
audio and cutoff margins. A classloader comparison of the retained R5 path and
four untouched voices verifies 120 cases and 12,249,678 bit-identical raw samples.

The assembled proof retains all 1,745 earlier non-index assets byte-for-byte,
adds two losslessly transported PCM24 WAVs and six copied note events, and
validates 1,873 asset references. Chromium passes 36 comparison sessions at a
48 kHz device rate using the original 44.1 kHz cue clock, all six exact downloads,
synchronized revision switching, pause/restart/stop/loading races, archived
playback and 320/390/1440 px layouts. The final publication also checks the
actual Git archive against the 256 MiB cap. Full unfiltered JVM regression
results belong in the pull request's validation record.

### Why it is built this way

- A persistent source body gives the voice an identity after the attack ends.
- LEAD's upper components share both oscillator phases, so the existing quick
  recoveries change its edge while a separate fundamental anchors the note.
- HOLLOW's octave modulation supplies a different harmonic structure; its
  explicit carrier protects the requested root when the upper body grows.
- Both feed the same history-sensitive filter, so the macros still operate
  one instrument rather than an attached effect rack.
- Stronger upper harmonics are the main risk. Root, loop and high-register
  oversampling checks guard the implementation; listening decides the value.

## Luthier review after the R5 listening test

The owner's listening verdict is that R4 and R5 seem like the same sound.
The review starts from that verdict, not from the passing numerical margins.
Source reviewed: `b8ae2b958057080ad2d06238d4a2e1bf4c618e22`.

**Fix now, sound character.** R5 deliberately restores R4's steady source.
Its contact moves only oscillator B, with 30/70 ms recoveries, while A stays
powered. At the published common controls, medium-touch LEAD redistributes
1.07% of normalized spectral power in the first 100 ms but only .063% in
500–1500 ms. HOLLOW moves 11.54% initially and .894% in that body window.
Body levels differ by only .025/.037 dB. A brief contact detail did not meet
the request for deeper voice identity.

**Fix now, audition.** All four source WAVs are distinct. The published
bundle decodes exactly to those files, caches use revision-specific keys,
and the fixed playback gain does not substitute or equalize the takes.
The old click-to-restart comparison and long clips make a small transient
change harder to judge. The next proof uses full short notes with revision
switches at the same note time and a separate restart control for attacks.

**Try next, source architecture.** Three candidate operators were considered:
root-period harmonic shaping (SPLICE), octave phase modulation (COUPLE), and
sum/difference ring interaction (MUTATE). The first two offer the clearest
contrasting sustained roles with bounded cost. Literal hard sync would need
proper reset antialiasing, so the LEAD proposal uses a finite harmonic formant
instead. HOLLOW uses smooth octave modulation with a protected root carrier.
Ring interaction risks cancellation and extra pitch ambiguity without a
clearer gesture, so it is deferred.

These remain Skitter voices. `Glint.render` already provides a separate
root-synchronous phase-distortion instrument; `Murmur.render` provides a
four-operator, five-formant voice. Here the new excitation feeds Skitter's
existing history-driven nonlinear filter and retains its six controls,
quicker agitation and seeded primary/reply/breath schedule.

**Disclosed.** R5's continuously powered source keeps its contact-memory load
near saturation. The prior tests prove a causal dependency, not a clearly
restored contact after a breath. Fresh rendered notes reset that state.

**Evidence ledger.** Verified: source paths, separate PCM, exact hosted
transport and gain behavior. Measured: the published-control spectral and
level comparisons above. Proposed: stronger sustained source identities.
Heard: the owner reports that R4 and R5 seem the same. The Luthier main skill
was available; its referenced review/convention/catalog files could not be
read, so this review uses the actual repository and render evidence.

## Touch and articulation proof (R5)

LEAD and HOLLOW now keep oscillator A's existing source contribution while
oscillator B gains its own transient contact envelope. LEAD's 2 ms rise and
30 ms recovery make a quick tear; HOLLOW's 30 ms rise and 70 ms recovery
produce a delayed octave bloom. These are envelope time constants, not measured
audible attack times. B returns to its existing gain of one. Neither voice adds
an output effect, another pitch, or a new macro. BASS, FLUTTER, CHIRP and FRAY
retain their R4 render path.

Two positive, exactly discretized states carry contact excitation and its
attack follower. Velocity changes the contact strength; previous circuit
energy reduces a loaded reply. This dependency is gated by AGITATE. Existing seeded
gesture events trigger contact with their absolute strength, preserving both
upward and downward pitch departures. Finite note-on still articulates at
SKITTER zero. HOLD has no synthetic note-on: contact follows its existing
periodic events, and both new states must converge during preroll.

This arrangement gives the voices different attack-to-body trajectories
without moving A's contribution when B speaks. It retains the current pitch
recovery, flutter rates, phrase scheduler and output treatment. Finite breath
timing can respond to the changed circuit history through its existing
feedback. The audible result still needs the owner's listening verdict.

The isolated C3 checks measure HOLLOW's gain-independent spectral-change
centroid 31–46 ms later than LEAD's across the three velocities. Both recover
to within .0014% of the previous body's RMS in the measured .6–.95 s window;
requested-root error stays below .706 cents. Loaded held contacts reach about
46% of their history-disabled peaks and both new states converge in three
periods. Continuous oscillator A keeps history strongly loaded: within-phrase
contact-factor recovery is at most 1.68% in LEAD and .0074% in HOLLOW in this
probe. This establishes causal loading, not an audible breath-restoration claim.

The focused proof compares the R4 source path with R5 on the current shared
runtime. `SkitterProbe.articulationDisabled` removes only the new source path;
`contactMemoryDisabled` removes only its history dependency. The original
published R4 page and all its audio remain available as the archive.

The audition uses C3/MIDI 48, seed 0, velocities .25/.60/1, medium retriggers,
and a longer finite note for each of these two voices. All four raw performance
tracks share one playback gain. Optional cue/take matching uses one constant
gain for the selected region and explicitly hides its level response; it
never levels individual notes within a phrase. Downloads restore the original
unlevelled mono 44.1 kHz PCM24 WAV bytes. Fresh note retriggers reset the circuit;
they do not imply memory carried between separate renders.

Reproduce the small proof with
`./gradlew --no-daemon :synth:generateSkitterArticulationAudition`.
The generator records sample-index cues, controls, source hashes, timing and
gain measurements under `testkit/skitter-articulation/`.
Package it beside the retained R4 hosted directory with
`python3 scripts/package_skitter_articulation.py /absolute/r4-hosted testkit/skitter-articulation /tmp/skitter-r5-hosted`.
The packager retains R4 assets, adds its archive banner and losslessly encodes
only the four new WAVs. Check the final deployment tar against 256 MiB.

An independent isolated-classloader check compares 72 articulation-disabled
cases and 48 unchanged-voice cases against the compiled R4 engine on the current
shared runtime: all 12,249,678 raw samples match exactly. All 24 existing causal
cutoff probes retain their thresholds. Independent transport checks restore
the four new WAVs exactly, verify 28 copied note events and retain all 1,735
other R4 assets byte-for-byte. Chromium passes all 48 track/cue/gain combinations,
including a 48 kHz playback device using the original 44.1 kHz cue clock,
original downloads, pause/stop/loading races and layouts down to 320 px.

## Voice character revision (R4)

The six voices previously shared their oscillator morph, source balance and
nonlinear transfer. Defaults and cutoff/recovery settings did much of the
distinguishing work. R4 gives each voice its own excitation and circuit response
while retaining the quicker flutter and primary/reply/breath phrasing.

| Voice | Character direction |
| --- | --- |
| BASS | Rounded low-register weight, a quieter second oscillator and a firmer settling response. |
| LEAD | Saw and narrow-pulse bite with stronger history-dependent edge. |
| HOLLOW | An open octave pair, odd harmonics and cleaner nasal resonance. |
| FLUTTER | Contrasting pulse shapes and a more lively response to recovery gestures. |
| CHIRP | A smoother pitched carrier that provokes brief resonant filter bursts. |
| FRAY | Dense edges and a rougher nonlinear response to signal history and noise forcing. |

The ordinary root-support path is quieter so the voiced filter determines the
body. Its former full-high-pass budget and upper-register boost are retained.
CHIRP adds a focused third harmonic within its oscillator source; HOLLOW keeps
the 2:1 octave relationship. Audible noise gain/color, pitch-recovery time
constants, flutter frequencies and the seeded phrase scheduler remain intact.
Traces include the final frame as well as the 5 ms grid, exposing late gestures
that previously fell after the last diagnostic sample.

The first audition bench renders all six voices at MIDI 48, velocity 1 and seed
0 with identical BUZZ .55, SKITTER .50, AGITATE .60, VENT .35, STATIC .15 and
HOLD 0. It separates voice identity from preset settings. Matched R3/R4 pairs
follow, with both earlier comparison benches retained. R3 recordings are frozen
with source and SHA-256 provenance, just like the earlier snapshots.

Character checks use five 8192-sample Hann windows across the attack and pitched
body, before the shortest voice releases. Each window's power is normalized
before comparing root-relative spectral bands. At the common active settings,
all 15 voice pairs redistribute at least 9.80% of normalized spectral energy,
and each contrast exceeds twice the measured same-voice seed radius across
seeds 0, 17 and 91. Joint time/power signatures are reported separately because
they also include phrase loudness. Quiet-core and audio-reaction checks cover
identity with motion/noise off and the contrasting FLUTTER/CHIRP responses.

All 33 focused tests pass: the previous 30 engine/patch/agitation/phrasing and
decimator checks plus three character tests. The existing admission thresholds
are retained. All 24 causal mechanism probes move cutoff by more than 50 Hz.
The 144-case pitch study's 72 moderate cases stay within 2.791 cents, with
confidence at least .959, pre-release sustain ratio at least .837 and onset/body
flatness at most .0606/.0407. Measured replies remain 125 ms apart, with mean
breaths of 243–281 ms and softer real circuit responses in held phrases.

An independent comparison of the identical neutral sweep bank shows the ten
previously crowded pairs moving from 0.78–2.34% joint spectral contrast in R3
to 5.65–28.99% in R4. Every one of those ten pairs improves; HOLLOW's previous
outlier distances become smaller as the other voices gain individual character.
This comparison uses the same windows, controls, root and normalization in both
revisions.

The full pack contains 325 current variants, 993 current PCM24 WAVs and 33
frozen comparison WAVs. All 18 current held clips converge within three periods,
with state error at most `3.01e-9` and zero exported PCM24 seam error. There are
no emergency bound hits. Finite 4× render time has a 242 ms 95th percentile;
the slowest held render is 514 ms on the Linux environment described below.
Independent hosted decoding restores all 1,026 WAVs byte-for-byte and validates
1,845 references, sampler metadata, repeated loops and all three provenance
chains.
Chromium passes all 18 shared-voice playback modes, 54 revision-comparison
sides and 12 held-seam demonstrations. Exact WAV downloads across R1–R4,
shared-snapshot cache reuse, level controls, historical labels, keyboard/filter
controls and layouts at 1440, 390 and 320 pixels are checked.

## Phrase listening revision (R3)

R2 is the accepted baseline for this pass. R3 organizes its gestures into a
primary response, one or two softer replies, and a brief breathing gap. Pairs
are gentler in BASS/HOLLOW; the more reactive voices allow stronger replies
and more groups of three. Reply strength follows its own primary rather than
an unrelated random hit. Phrase choices have a separate deterministic seed
stream from oscillator departures and noise.

This redistributes the gesture cadence while keeping R2's quick flutter,
recovery times, pitch phase budget and source sound. Recent circuit energy and
agitation can shift a finite phrase's breath, gated by AGITATE. Held phrases
replay exactly; every gap, including the wrap, reserves the 8 Hz refractory
interval before the remaining period is allocated to close replies and breaths.

The audition opens with nine matched R2/R3 pairs and retains the earlier nine
R1/R2 pairs in a second comparison bench. Both benches share the same frozen
R2 recordings. Every pair keeps its macros, note, velocity, seed and render
settings; prior revisions are archival snapshots with exact WAV checksums.
Only R3 source patches are claimed to replay through the current renderer.

All 29 focused engine, patch, agitation and phrasing tests pass. Rendered
high-SKITTER traces show 125 ms replies and mean breathing gaps of 243–281 ms;
the slowest overall mean event gap is 174 ms. Held replies produce agitation
peaks 28–66% of their primary, with a still softer third response. The existing
cadence check keeps its 200 ms limit on mean spacing; the new tests separately
require close replies, longer breaths and bounded reply strength.

The 144-case default/moderate pitch study retains all pitch/body limits. Its
72 moderate cases stay within 1.451 cents, with confidence at least .850,
pre-release sustain ratio at least .926, onset flatness at most .0852 and body
flatness at most .0383. These are stability and behavior checks; the matched
recordings remain the owner's listening gate.

The complete R3 pack contains 319 current variants, 975 current PCM24 WAVs and
22 frozen comparison WAVs. All 18 held clips converge within three periods;
maximum state error is `3.66e-10` and maximum exported PCM24 seam error is
`3.46e-14`. No render hits an emergency bound. On the Linux environment
described below, finite render time is 255 ms at the 95th percentile and the
slowest held render is 514 ms. Independent hosted decoding restores all 997
WAVs byte-for-byte and validates 1,756 references and both provenance chains.
Chromium plays all 36 comparison sides and eight held-seam demonstrations;
download hashes, comparison level locks, historical labels and layouts at
1440, 390 and 320 pixels pass.

## Agitation listening revision (R2)

The first listening feedback described the motion as a mechanical police-siren
"wee-ahh" and asked for quicker, more human phrasing. R2 keeps the same six
controls and saved recipes, but raises the flutter response by about 30%,
shortens oscillator recovery, and replaces compulsory alternating departures
with seeded episodes of unequal gestures. Event gaps and strengths vary; finite
notes also respond modestly to preceding circuit energy and agitation. A quick
filter provocation now has a weaker opposite recovery instead of an identical
positive swell each time. The actual event gaps, including the held wrap, retain
the 8 Hz ceiling.

Pitch departures have a smooth budget for relative oscillator phase travel,
accounting for register and recovery time. This reduces broad scoops and
sustained cancellation while leaving the fast filter gestures, waveform and
noise feed intact. FRAY anchors its pitch more tightly so its torn character
comes primarily from the reactive filter. A small minimum ceiling preserves
subtle departures in the upper register.

The original R2 audition supplied nine R1/R2 comparisons at the same note, velocity, seed
and shared matched-level target. They cover all six defaults, NERVOUS WIRE and
held FLUTTER/FRAY. The R1 sides are immutable render snapshots with SHA-256
provenance; their archived patch recipes describe the inputs but the current
renderer does not reproduce the old sound. R2 renders existing recipes with the
revised circuit. This is a listening revision, not a change to the patch format.

R2 passes 25 focused engine, patch and agitation tests, including the existing
216-case finite stability matrix, held export/seam checks, cancellation checks
and new rendered-trace tests for irregular timing and quick rebound. A separate
144-case default/moderate pitch study covers MIDI 36–72 at three velocities.
Its 72 moderate cases stayed within 1.451 cents with confidence at least .848,
body flatness at most .0682 and pre-release sustain ratio at least .942.
These checks establish stability and pitched behavior; the matched comparisons
are still the owner's listening gate.

## Rendering and saving

`Skitter.render(voice, macros, midi, velocity, seed)` exports 44.1 kHz mono audio
with 4× internal oversampling and shared `MELODIC_LOUDNESS_TARGET` leveling.
The saved root spans MIDI 24–84, default 48; velocity spans 0–1. Zero velocity
is silent for finite and held notes. Noise depends on voice, root and seed,
independently of macro values, so control comparisons share the same excitation.

BASS, LEAD, HOLLOW, FLUTTER, CHIRP and FRAY expose exactly BUZZ, SKITTER,
AGITATE, VENT, STATIC and HOLD. HOLLOW uses an octave interval. The original
fifth prototype produced a strong suboctave autocorrelation ambiguity and was
replaced during numerical probing. Pitch lives beside the macros in
`SkitterPatch`, together with velocity and seed; JSON edits, recipe replay,
velocity variants and preset promotion preserve those fields.

Thirty-six provisional presets comprise 8 bass, 8 lead, 6 hollow, 6 flutter,
4 chirp and 4 frayed textures, including one held patch per voice.
`SynthKits.skitter()` provides a dry sixteen-pad C minor pentatonic kit.
Patches, presets, FRESH TAPE, the Android synth picker and CLI use the same
renderer. Finite recipes route as TONAL, held recipes as LOOP. The existing
audio classifier separately labels samples longer than 1.5 seconds LOOP.

CLI example:

```text
./gradlew :cli:run --args="synth SKITTER LEAD --preset 3 --midi 60 --velocity .7 --seed 17 --out /tmp/skitter"
```

`--scramble N` creates a repeatable variation around the chosen preset. Scramble
stays below the held-loop step.

## Coupled circuit

BUZZ increases edge content within each voice's mixture of a four-partial
rounded triangle/sine, PolyBLEP saw and PolyBLEP pulse. Waveform weights, pulse
width and oscillator balance belong to the voice profile. Fundamental polarities
agree through the morph; pulse width stays between .12 and .50. A quiet direct
fundamental supports the filtered signal. Unequal exponential recovery follows bounded departures in
cents; seeded episodes vary direction, strength and timing, with hysteresis and
an explicit 8 Hz event ceiling. SKITTER zero removes the departures.

The double-precision trapezoidal state-variable filter has coherent low-, band-
and high-pass outputs. Nonlinear excitation, small signed bias and delayed
band feedback belong inside the filter. Coefficients read the preceding
history/agitation update; no instantaneous unsolved feedback is used. VENT
crossfades those outputs continuously with measured gain budgets.

Voice profiles set positive drive/damping budgets, zero-centered nonlinear bias,
and the relative response to rising energy, recovery gestures and noise. The
second-order control keeps R2's natural frequencies, with distinct bounded
damping for each voice. These are changes inside the shared circuit.

Energy history uses internal input and band power, normalized against .22;
signed history has a tightly bounded .055 target. An exact discrete damped
second-order control responds to rising energy, recovery gestures and
zero-mean noise deviations. AGITATE uses a square-root response to retain
activity near zero. Its position moves smoothed log cutoff, drive and damping;
it does not modulate final output gain. AGITATE zero removes dynamic memory
modulation. STATIC has a shaped audible feed and a separate behavioral forcing
path. The noise-forcing ablation preserves the source noise gain and sequence.

Finite velocity changes excitation, early brightness and recovery strength.
A modest initial excitation crest supplies provocation before the circuit
settles. Repeated gestures stop before release; excitation and noise then stop,
allowing filter and history states to decay. DC removal and a 48-output-sample
Blackman-windowed sinc filter precede decimation. Internal-state bound failures
raise errors; there is no final clipper repairing a runaway model.

## HOLD approximation

HOLD at or above .999 returns two independently evolved settled periods;
lower values extend finite excitation. A near-one-second period is selected
from whole cycles of the requested root, with finely fitted output frame count.
Oscillator frequency integrals are corrected to whole cycles, while recovery
departures remain audible. Gestures replay a seeded, irregular periodic schedule;
seeded noise wraps through interpolation and recursive noise filters.
Stochastic forcing is reduced to 70%, rather than removing STATIC or AGITATE.

Filter integrators, delayed feedback, coefficient smoothing, energy and signed
histories, agitation position/velocity, noise filters and DC state must converge.
Phase, recovery and scheduler periodicity are constructed explicitly. Preroll
has a 16-period cap and state tolerance `2e-5`; a failed search raises an error.
This is a periodic operating regime, not a promise that an arbitrary finite
trajectory loops. No wrap crossfade was needed in the measured cases.
`loopStart` identifies the second period; sampler WAV metadata and exported
PCM are checked with the binding `Keys.seamError < 1e-3` requirement.

## Initial port evidence (R1)

The measurements below describe the original published R1. R2 has a separate
listening comparison and is checked against the existing pitch, state, macro,
release and exported-loop assertions; R1 measurements are not R2 results.

Round 3 probes covered 216 finite register/velocity/default/all-high cases,
36 held cases, 144 default/moderate pitch cases, every macro quarter step and
low nonzero setting, all four interaction pairs, and causal ablations.
The finite matrix had no bound hits or nonfinite output. Maximum raw peak was
.531, maximum absolute DC/RMS .00456 and maximum filter state .746.
All 36 held cases converged in three periods; maximum state error was
`5.16e-9`, with measured leveled seam below `1.35e-16`.

The 72 moderate MIDI 36–72 pitch probes measured maximum absolute error
1.50 cents and minimum confidence .819. Experimental departures are broader:
the default FRAY at MIDI 36 and hard velocity reached approximately +39 cents
in the sampled window. This is a listening limit to assess, not evidence of
root accuracy for every extreme. The repository pitch detector's normal range
starts at 40 Hz; the audition's MIDI 24 diagnostic uses a constrained low-note
autocorrelation fallback.

The smallest gain-matched waveform change was .00144 for 0→.02 macro movement
and .0166 over a quarter step. These measurements establish activity, not
audibility or taste. Frozen memory, disabled agitation, equal recovery and
matched noise forcing all change the coupled response. Released control
motion and audio decay rather than persisting without excitation.

Classifier evidence has a practical limit: full samples take its LOOP duration
shortcut, while a cropped opening can label strongly pitched harmonic leads
SNARE, or a late peak in an otherwise steady tone KICK/TOM. Cropped labels are
reported as diagnostics. Independent requested-root, confidence, spectral
flatness and pre-release sustain tests assess the pitched body; classifier
labels alone do not establish sonic acceptance.

Run `./gradlew --no-daemon test` for the JVM gate and
`./gradlew :synth:generateSkitterAudition` for the full listening artifact.
`-Pquick` selects a smaller development pack. Add
`-PskitterBaseline=/absolute/path/to/skitter-r2-baseline` to include the preserved
R2/R3 comparisons. Add `-PskitterPrevious=/absolute/path/to/skitter-r1-baseline`
to retain R1/R2 beside them. Each baseline directory contains its manifest,
original render assets and SHA-256 inventory; the generator verifies these
before copying selected matched snapshots, patches and traces. R2 assets are
copied once and shared by both benches. The
[published private audition](https://skitter-snipsnap-audition.bertcalm.chatgpt.site)
contains dry raw/native/matched playback, presets, registers and velocities,
macro sweeps, interactions, common-gain causal comparisons, repeated exported
seams, traces, source patches and a bright 4×/8× reference. The reference shares
base-rate noise; waveform distance does not isolate aliasing from discretization.

The full pack contains 319 variants and 975 PCM24 WAVs. All 54 held source WAVs
(raw, native and matched) measured zero exported seam error, and the 18 seam
demonstrations repeat the exact final PCM period four times. The bright FRAY C6
4×/8× comparison measured matched waveform distance .00161, centroids 1334/1344 Hz
and pitch errors −1.14/−1.20 cents; listening still decides whether its high-end
character is acceptable.

Rendering was measured on Linux x86-64, an AMD EPYC 9V74 host with a four-CPU,
16 GiB container and Java 21.0.12.1. The 300 finite 4× renders had median 184 ms,
95th percentile 218 ms and maximum 279 ms; 18 held renders had median 356 ms and
maximum 409 ms. These timings cover the circuit render, excluding analysis,
file export and compilation, during the repository build. The prototype budget
on this environment is a 95th percentile below 500 ms for finite renders and
below one second for held renders. It is an offline-generation budget, not a
phone latency claim.

Sites has a 256 MiB archive input limit. The hosted pack uses lossless PCM24
second differences followed by gzip; the browser restores the original WAV
bytes for playback and downloads, including root and loop metadata. The local
generator continues to emit ordinary WAV files. Reproduce the hosted pack with
`python3 scripts/package_skitter_audition.py testkit/skitter-audition /tmp/skitter-hosted --template synth/src/test/resources/audition/skitter-audition.html`.
The script uses the Python standard library, with optional NumPy acceleration.
All 975 decoded hosted WAVs matched the original SHA256 bytes; both predictor
implementations agreed on representative files. Hosted playback requires a
current browser with `DecompressionStream` support. JavaScript syntax, mock DOM
interactions and the actual decompression/restore path passed runtime checks;
visual browser review and listening remain acceptance work.

The owner must still judge the sound, loop clicks and the identities of the
controls. Android phone/MPC validation and release naming remain open.
