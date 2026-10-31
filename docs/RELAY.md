# Relay

Relay turns one pitched note into a short phrase of timbral handoffs. Four
original generated roles — damped string, drawbar body, bright bell and soft
swell — share the requested root. Slots change the source balance, while a
separate accent schedule changes emphasis. No external PCM or factory programs
are used. The supplied [engineering specification](RELAY_SPEC.md) is retained.

The performance bank has 36 authored dry recipes: eight KEY, six BASS, six
PLUCK, six ORGAN, four CHIME and six FLOW presets. The six earlier seeds remain
unchanged and first in their voice lists. New recipes use their own source
balances, sparse accent figures and phrase clocks; each voice includes one held
recipe. The owner approved this bank's sound on 2026-10-21. Physical-device
playability still needs hands-on verification.

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
| PULSE | Raises accent contrast and finite articulation without changing order or tempo. |
| BLEND | Broadens the source transitions from defined handoffs to overlapping timbres. |
| COLOR | Changes source brightness and the shared filter, preserving the source roles. |
| LENGTH | Changes the finite gate and tail; in HOLD, changes articulation within the phrase. |
| HOLD | The top setting exports a repeating phrase; other settings render a finite note. |

TUNE is the existing host pitch control, additional to the five timbral macros
and HOLD in the proposal. The SYNTH panel shows the selected note and a native
VELOCITY slider. Velocity reaches the engine as excitation and initial brightness;
it is stored separately from the macro settings. Editing it preserves the complete
loaded phrase through preview, save and SEND TO PAD.

## Phrase and host behavior

`RelayPatch` retains source definitions, positive slot dwell and transition
durations, the order roster, accents, tempo and loop configuration. Four slots
are the default; at most eight are accepted. Tempo is a patch setting, so PULSE
does not secretly become a speed control. Independent renders start from fresh
state with deterministic seeded variation.

Source excitation follows one continuous note history, so entering another
slot does not restart a source's decay or swell. Normal voice settings use
smooth, dwell-relative overlap. PULSE adds gentle internal emphasis at low
settings and reveals stronger rhythmic figures near its high end. HOLD uses
periodic source histories; low PULSE does not introduce an extra gate at every
slot boundary.

Model 1 deliberately sustains the ORGAN source. Its source contour rises from
0.86 to 1 over a fixed 35 ms and retains a body throughout the note; the finite
note's shared attack and release still shape it. The common source record retains
and serializes `decaySeconds`, but ORGAN does not use that field. It controls PLUCK and BELL
damping and SOFT's swell time. Making ORGAN decay active later would change
saved and approved sounds, so it requires an explicit model compatibility
decision rather than a silent reinterpretation of existing recipes.

Finite notes admit accent events only before the selected gate. Their envelopes
finish naturally during release; no fresh accent begins in the terminal tail.
The finite handoff vector also stops at the selected gate. The current sources
continue their own decay through the release, rather than starting later slots.
LENGTH may select a shorter part of the roster, while ORDER and PULSE retain
the selected event times and count. HOLD uses the complete periodic roster.

Output is mono at 44.1 kHz, synthesized at four times that rate and reduced
through the shared resampler. Normal playback uses
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
rendered frequency and the actual phrase duration.

Finite Relay pads route as TONAL and held pads as LOOP. Neither receives a drum
choke group. `Patches`, `Presets`, `Velocity`, recipe replay, `FxChain`, the phone
picker, FRESH TAPE starter and generic CLI all use the same engine path.
`SynthKits.relay()` supplies a fixed sixteen-pad dry kit: eight C-minor-pentatonic
KEY notes, six named voice contrasts and two held phrases. AFTERIMAGE KEY replaces
the earlier duplicate of Handoff 6 / FIRST HANDOFF. Explicit selections keep the
kit at sixteen pads as the library grows. MPC export uses the
existing kit pipeline and its one-shot drum-program convention.

## Render performance

Repeated previews reuse a bounded bank of 256 waveform tables (about 4 MiB),
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
fingerprints in the compatibility probe remain identical, covering factory
recipes, macro sweeps, velocity, source edits and the long custom loop.

## Audition and checks

The [published Relay audition](https://snipsnap-relay-audition.bertcalm.chatgpt.site)
is private to the owner's account and includes raw and matched WAV downloads.

Run `./gradlew --no-daemon :synth:generateRelayPerformanceAudition` to regenerate
`testkit/relay-performance-audition/`. Revision 6 contains 88 clips: the original
76 bank, musical, velocity and starter-pad cases, plus 12 PULSE endpoint probes.
The original cases include the full bank, six short musical performances, three
native velocities for every voice and all sixteen actual starter pads.
Notes retain the chosen preset's full phrase; musical mixes include every
tail and record their fixed headroom gain. HOLD clips contain three exact repeats.
The manifest carries full patches, note events, levels and WAV hashes. Listening
notes stay in the browser. Physical-device timing, touch and listening still need
hands-on verification.

The page opens on six PULSE 0-versus-1 pairs, one for each voice. They use the
same six finite recipes as the previous comparison. Each pair fixes its note,
velocity, source definitions, phrase clock and every other macro, changing only
PULSE from 0 to 1 through the existing renderer. The manifest records the source,
reference and exact rendered recipes alongside the endpoint and pair ID. Raw
playback retains native level changes; matched playback supports comparison of
articulation and color after level matching. The diagnostic tests the existing
control's full range before deciding whether its mapping or accent behavior needs
improvement. It makes no new sonic approval claim, and the production PULSE
response, factory macros and saved patch model remain unchanged.

Revision 5's earlier experiment compared an eased `nominal^0.75` mapping at
nominal positions 0.25 and 0.5. On 2026-10-26 the owner reported that the eased
candidate sounded the same. That mapping was not adopted. The endpoint diagnostic
replaces those 24 subtle comparison clips while retaining the original approved
76 cases.

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
