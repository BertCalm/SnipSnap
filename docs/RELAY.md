# Relay

Relay turns one pitched note into a short phrase of timbral handoffs. Four
original generated roles — damped string, drawbar body, bright bell and soft
swell — share the requested root. Slots change the source balance, while a
separate accent schedule changes emphasis. No external PCM or factory programs
are used. The supplied [engineering specification](RELAY_SPEC.md) is retained.

The first port is a listening candidate. Its six dry presets establish KEY,
BASS, PLUCK, ORGAN, CHIME and FLOW. The proposed 36-preset expansion waits for
the owner's sonic approval, as required by the specification.

The second audition responds to listening feedback that the voices were too
similar. The first version put 86–93% of each shared-C3 identity's spectral
energy around the root and reused one timing score. The revision gives the
source roles different harmonic profiles and articulation, and gives each
voice a separate dwell, handoff and accent score. These measurements explain
the change; they do not establish sonic approval.

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
and HOLD in the proposal. Velocity reaches the engine as excitation and initial
brightness; it is stored separately from the macro settings.

## Phrase and host behavior

`RelayPatch` retains source definitions, positive slot dwell and transition
durations, the order roster, accents, tempo and loop configuration. Four slots
are the default; at most eight are accepted. Tempo is a patch setting, so PULSE
does not secretly become a speed control. Independent renders start from fresh
state with deterministic seeded variation.

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
`SynthKits.relay()` supplies a sixteen-pad dry kit, including scale notes,
voice contrasts and two held phrases. MPC export uses the
existing kit pipeline and its one-shot drum-program convention.

## Audition and checks

The [published Relay audition](https://snipsnap-relay-audition.bertcalm.chatgpt.site)
is private to the owner's account and includes raw and matched WAV downloads.

Run `./gradlew --no-daemon :synth:generateRelayAudition` to regenerate
`testkit/relay-audition/`. The page and manifest travel with the raw and matched
24-bit WAVs. The listening pack compares dry identities at a common root,
register and velocity, five-point sweeps, required interaction corners,
all-high settings, tempo extremes and repeated HOLD material. Four isolated
source probes expose the individual timbres. Supply
`-PrelayBaseline=<previous audition directory>` to include the six unchanged
original identities with direct Original/Revised playback buttons; the
previous manifest and WAV hashes are checked before copying.
It is a review
artifact; measured checks do not establish sonic approval.

Run `./gradlew --no-daemon test` for the repository's JVM checks. Relay's
contract tests cover determinism, tuning, bounded output, macro behavior,
accent/order independence, loop continuity, complete phrase serialization,
native velocity, pitched classification, recipe replay and MPC export.
Android build and device listening require an Android SDK and hardware.
