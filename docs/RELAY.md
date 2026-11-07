# Relay

Relay turns one pitched note into a short phrase of timbral handoffs. Four
original generated roles, damped string, drawbar body, bright bell and soft
swell, share the requested root. Slots change the source balance while every
source keeps its own continuous history. No external PCM or factory programs
are used. The supplied [engineering specification](RELAY_SPEC.md) is retained.

New model-2 patches add a smooth repeating amplitude pulse to that continuous
voice. This follows the periodic reference identified by the owner, with a
shallower 7 dB maximum range. Existing model-1 patches retain their original
independent accent schedule. The two behaviors are serialized explicitly.

The performance bank has 36 authored dry recipes: eight KEY, six BASS, six
PLUCK, six ORGAN, four CHIME and six FLOW presets. The six earlier seeds remain
unchanged and first in their voice lists. The authored recipes use their own source
balances, sparse accent figures and phrase clocks; each voice includes one held
recipe. These factory recipes and the sixteen-pad starter remain model 1. The
owner approved this bank's sound on 2026-10-21. That approval belongs to the
retained bank; physical-device playability still needs hands-on verification.

The second audition gave the source roles different harmonic profiles and
articulation, and each voice its own handoff and accent score. The third audition
responds to feedback that those handoffs sounded like separate gestures. It
refines source continuity, transition overlap and internal emphasis toward a
single evolving voice. The later performance audition received the owner's
positive listening verdict; new sound-changing candidates need their own listen.

## Controls

| Control | Behavior |
| --- | --- |
| TUNE | SnipSnap's root-note control: two octaves, C1–C3 for BASS and C3–C5 for the other voices. |
| ORDER | Continuously blends corresponding times in serialized source-order schedules. |
| PULSE | Model 2 changes the depth of a smooth repeating gain contour, from steady at 0 to 3.5 dB at 0.5 and 7 dB at 1. Model 1 retains its original accent contrast. |
| BLEND | Broadens the source transitions from defined handoffs to overlapping timbres. |
| COLOR | Changes source brightness and the shared filter, preserving the source roles. |
| LENGTH | Changes the finite gate and tail; in HOLD, changes articulation within the phrase. |
| HOLD | The top setting exports a repeating phrase; other settings render a finite note. |

TUNE is the existing host pitch control, additional to the five timbral macros
and HOLD in the proposal. The SYNTH panel shows the selected note, native
VELOCITY and an explicit pulse behavior choice. Velocity reaches source
excitation and initial brightness and is stored separately from the macros.
Editing it or changing pulse behavior preserves the complete loaded phrase
through preview, save and SEND TO PAD.

## Pulse models and saved sounds

`RelayPatch` defaults new recipes to model 2. A saved model-1 patch, or an older
JSON file whose model is omitted or null, continues through the original
renderer. `copy`, `withMacros`, velocity editing and recipe replay retain the
chosen model. Unsupported model numbers report a file-format error.

The 36 factory recipes and all sixteen starter pads explicitly retain model 1,
so their approved sounds do not change. The phone also preserves older Relay
navigation settings that contain macros without a complete saved recipe.
Selecting the repeating behavior is an explicit edit of the complete patch;
its source definitions, handoff phrase, other controls and velocity remain in
place. Fresh patches use the repeating behavior.

`RelayModels` dispatches saved patches to the selected behavior. The original
`Relay` source renderer remains unchanged. Model 2 reuses that renderer at
PULSE 0, then applies the same finished-sample gain stage used in the listening
reference, before optional engine leveling:

```text
depthDb = 7 * PULSE
floor = 10^(-depthDb / 20)
gain(t) = floor + (1 - floor) * (0.5 + 0.5 * cos(2 * pi * rateHz * t))
```

Finite notes use 3 Hz and begin at the top of the gain contour. Every note
starts its own clock. PULSE changes depth while speed, source color and source
history stay fixed. At 0 the native and normalized output delegate exactly to
the original PULSE-0 renderer. At 0.5 and 1 the gain floors are about 0.6683 and
0.4467; the diagnostic reference used 0.18, about 14.9 dB. The body therefore
continues through every dip.

Model 2 keeps the serialized accent roster as recipe data but does not use it
as the sounding pulse. Its diagnostic report exposes the actual pulse depth,
gain floor, rate and held-cycle count separately. The reported 4x source peak
precedes this finished-sample stage. Gain stays in 0..1 and cannot increase the
native source peak. Multiplication can introduce a small DC component after
source conditioning, so the checks measure that output directly; another filter
would alter the reference-derived sound.

## Phrase and host behavior

`RelayPatch` retains source definitions, positive slot dwell and transition
durations, the order roster, accents, tempo and loop configuration. Four slots
are the default; at most eight are accepted. Tempo is a patch setting, and PULSE
changes depth rather than speed. Independent renders start from fresh state
with deterministic seeded variation. The finite model-2 pulse is a per-note
3 Hz clock; its relationship to an external song tempo depends on note timing.

Source excitation follows one continuous note history, so entering another
slot does not restart a source's decay or swell. Normal voice settings use
smooth, dwell-relative overlap. Model 1 adds its original sparse internal
emphasis. Model 2 follows the repeating gain contour above. HOLD uses periodic
source histories, so the slots do not introduce an extra amplitude gate.

The shared source renderer deliberately sustains the ORGAN source. Its contour
rises from 0.86 to 1 over a fixed 35 ms and retains a body throughout the note; the finite
note's shared attack and release still shape it. The common source record retains
and serializes `decaySeconds`, but ORGAN does not use that field. It controls PLUCK and BELL
damping and SOFT's swell time. Making ORGAN decay active later would change
saved and approved sounds, so it requires an explicit model compatibility
decision rather than a silent reinterpretation of existing recipes.

Model-1 finite notes admit accent events only before the selected gate. Their
envelopes finish naturally during release; no fresh accent begins in the terminal tail.
The finite handoff vector also stops at the selected gate. The current sources
continue their own decay through the release, rather than starting later slots.
LENGTH may select a shorter part of the roster, while ORDER and PULSE retain
the selected event times and count. HOLD uses the complete periodic roster.

Output is mono at 44.1 kHz. Sources are synthesized at four times that rate and
reduced through the shared resampler; model 2 adds its finished-sample gain afterward.
Normal playback uses
`Dsp.MELODIC_LOUDNESS_TARGET`; auditions also retain unnormalized output so
leveling cannot hide velocity response or unsafe gain.

HOLD exports the loop region, without the initial note-on gesture. Relay's pad
render path does not implement attack-plus-loop keygroup export. The generated
sources retain their
phase history, the loop has bounded preroll, and the wrap uses the pitched
engine convention. Repeating an exported WAV at another pitch also changes its
phrase timing; exported samples cannot remain locked to a host tempo.

Voice recipes have their own phrase clocks. LENGTH selects a finite gate and
complete release within that score; the authored clocks span about 1.55–4.73
seconds across LENGTH. Deliberately longer patches may use eight beats
at 60 BPM; the maximum finite render is 9.4 seconds including its release.
HOLD fits the phrase to whole root periods and output frames. This introduces
a small phrase-tempo adjustment, while the pitch correction stays below a
fraction of one output frame. The audition manifest records requested and
rendered frequency and the actual phrase duration. Model 2 then fits the nearest
positive whole number of pulse cycles to that actual phrase duration. Its held
rate can differ from 3 Hz, especially for short custom phrases; diagnostics
report the resulting rate and cycle count. The real preceding rendered cycle
receives the same periodic gain phase and output-level gain before the seam
check. This preserves the full loop region rather than cutting a fixed 3 Hz
contour at an arbitrary phase.

Finite Relay pads route as TONAL and held pads as LOOP. Neither receives a drum
choke group. `Patches`, `Presets`, `Velocity`, recipe replay, `FxChain`, the phone
picker, FRESH TAPE starter and generic CLI all use the same engine path.
`SynthKits.relay()` supplies a fixed sixteen-pad dry kit: eight C-minor-pentatonic
KEY notes, six named voice contrasts and two held phrases. AFTERIMAGE KEY replaces
the earlier duplicate of Handoff 6 / FIRST HANDOFF. Explicit selections keep the
kit at sixteen pads as the library grows. MPC export uses the
existing kit pipeline and its one-shot drum-program convention.

## Render performance

The shared source renderer reuses a bounded bank of 256 waveform tables (about 4 MiB),
keyed by source role, effective spectral color, exact fitted frequency and seed.
Only complete tables are shared; schedule and contour buffers belong to each
render, and returned sample buffers remain independent. Invariant calculations
move out of the sample loop while the shared output filtering stays unchanged.

On 2026-10-23, a desktop JVM comparison using before/after/after/before runs and fourteen measured
renders per case reduced the median per-case cost by about 20% across the six
voices' finite and held defaults. Finite medians changed from 50–97 ms to 36–78 ms;
held medians changed from 95–205 ms to 80–159 ms. A supported eight-beat custom
loop with eight preroll cycles changed from 855 ms to 736 ms. These are warmed
desktop measurements, not phone timing guarantees. All 578 raw/normalized float
fingerprints in that model-1 compatibility probe remained identical, covering
factory recipes, macro sweeps, velocity, source edits and the long custom loop.
Those measurements describe the earlier source optimization. Model 2 adds one
bounded pass over the finished note and, for HOLD, its real preceding cycle.
It has no solver or additional source simulation. The new response's render
cost needs its own measurements; the earlier figures are not a phone timing
guarantee or a measurement of that additional stage.

## Audition and checks

The [published Relay audition](https://snipsnap-relay-audition.bertcalm.chatgpt.site)
is private to the owner's account and includes raw and matched WAV downloads.

The current musical round compares a BASS bassline, KEY chords and a FLOW held
texture at PULSE 0, 0.5 and 1 through the model-2 production path. Finite examples
use 90 BPM, placing two 3 Hz pulse cycles in each beat when their note starts
align. They retain complete source phrases and releases, native velocity changes,
per-note recipes and actual trigger times. One headroom gain is shared by all
three depth variants of each performance; raw comparison therefore keeps the
same summing gain. Matched files use the established loudest-200 ms listening
target. The held texture repeats its actual exported region three times and
records the seam against the real preceding rendered cycle.

Run `./gradlew --no-daemon :synth:generateRelayPulseMusicalAudition` to regenerate
the nine comparisons under `testkit/relay-pulse-musical-audition/`. Supply
`-PrelayPulseMusicalOutput=<directory>` for another destination. The manifest
records the source revision and hashes, compiled renderer fingerprints, every
note recipe, pulse depth and clock, actual source weights, levels and WAV hashes.

The model-1 performance round remains reproducible with
`./gradlew --no-daemon :synth:generateRelayPerformanceAudition`, under
`testkit/relay-performance-audition/`. Its revision-6 roster has 88 cases: the
original 76 bank, musical, velocity and starter-pad cases plus twelve PULSE
endpoint probes. This retained bank audio is the 2026-10-21 approved material.
Its manifest carries complete patches, note events, levels and WAV hashes.

Listening history is kept distinct from numerical verification:

- On 2026-10-26 the owner reported no audible difference from revision 5's eased
  `nominal^0.75` mapping. That curve was not adopted.
- The full model-1 endpoint range was still described as minor at PULSE 1.
  A broader sparse-accent candidate also received no meaningful-difference verdict
  on 2026-11-01 and remained unadopted. Those experiments remain in Site history.
- Revision 8 added synchronized switching and a deliberately exaggerated 3 Hz
  amplitude reference. On 2026-11-01 the owner said a toned-down version was what
  they expected from a pulse.
- Revision 9 retained that repeating shape at 3.5 and 7 dB depths. The owner called
  it better on 2026-11-04 and authorized the musical check and compatibility-preserving
  integration on 2026-11-07.

The new musical recordings have no owner listening verdict yet. A working
implementation and passing numerical checks do not establish a merged release,
new bank approval or physical-device listening and timing. Browser notes remain
local and can be exported.

The earlier engineering matrix remains reproducible. Run
`./gradlew --no-daemon :synth:generateRelayAudition` to regenerate
`testkit/relay-audition/`. The page and manifest travel with the raw and matched
24-bit WAVs. The listening pack compares dry identities at a common root,
register and velocity, five-point sweeps, required interaction corners,
all-high settings, tempo extremes and repeated HOLD material. Four isolated
source probes expose the individual timbres. Supply
`-PrelayBaseline=<previous audition directory>` to include the six unchanged
previous identities with direct Previous/Refined playback buttons; the
previous manifest and WAV hashes are checked before copying. The third-audition comparison pack uses the unchanged second-audition recordings.
It is a review
artifact; measured checks do not establish sonic approval.

Run `./gradlew --no-daemon test` for the repository's JVM checks. Relay's
contract tests cover determinism, tuning, bounded output, macro behavior,
accent/order independence, loop continuity, complete phrase serialization,
native velocity, pitched classification, recipe replay and MPC export.
Android build and device listening require an Android SDK and hardware.

## Why it is built this way

- A gain contour over the continuous PULSE-0 source gives the repeating swell
  identified in listening while the original timbral handoffs keep evolving.
- The gain floor stays above zero, and the 7 dB range is shallower than the
  diagnostic reference, so depth changes can retain a body between pulses.
- An explicit saved model protects existing sounds. Factory recipes and old
  navigation state keep their original accents until the behavior is changed.
- Each note owns its pulse clock. This is predictable in offline renders and
  preserves complete saved recipes; a shared live tempo clock would require a
  different host contract.

### Compatibility and desktop render measurements

Independent Java probes compared the unchanged model-1 path with the pre-integration
compiled renderer: all 578 native/normalized sample and full-recipe fingerprints matched,
covering every factory recipe and macro/velocity/source-history cases. The 16 actual
starter-pad sample, frame and recipe fingerprints also matched. This is exact output
comparison, separate from the new model's published audition fixtures and tests.

Two warmed desktop Temurin 21.0.13 JVM runs alternated source/model-2 renders in ABBA/BAAB
blocks, hashing outside the render timer. Across twelve finite/held cases, the median
added cost was 2.30 ms (about 3.8%). Model-2 finite case medians ranged 28.6–64.3 ms, with a
39.9 ms median of case medians; held cases ranged 64.6–147.8 ms, median 90.1 ms. These are
desktop measurements, not a phone or MPC latency claim. Complete probe artifacts are
retained with the published audition's integration evidence.
