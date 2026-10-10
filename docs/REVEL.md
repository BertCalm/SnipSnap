# Revel — a circle of skin and friction

REVEL ports the [supplied engineering specification](REVEL_SPEC.md) into
SnipSnap's deterministic offline melodic renderer. The model and factory
settings are musical design choices, not measured reconstructions of cuíca,
tabla, dholak or surdo. The owner's dry listening verdict remains the sonic
acceptance gate.

Six resonant heads occupy four fixed stations. A friction contact drives one
head, articulated ringing/edge/damped strokes drive two heads with a relaxing
pressure bend, alternating rolling strokes drive another pair, and a broad
finite pulse drives the remaining head. All belong to one interlocking phrase
and exchange motion with a shared passive floor. Microphones observe this
same ensemble; they do not create separate performances or feed it energy.

Each head has five modes and the floor has four. Exact damped modal rotations
and reciprocal velocity exchanges keep passive motion dissipative. Finite
contacts and the implicit slip junction spend explicit performer work budgets;
there is no source-state reset or final limiter hiding unstable growth.
The broad contact has a finite 1.2–2 ms compression lobe followed by its
compliant force. Its first two secondary modes sit close to root harmonics,
with two higher inharmonic modes; stronger early radiation and faster upper
loss preserve a body-rich attack followed by a root-bearing tail. The root
mode retains a longer decay. These are calibrated model choices.

Source and pickup run at 176.4 kHz before band-limiting and shared decimation.
Station radius is 2 m, microphone height is 0.30 m and REACH sets path radius
from 0.15 to 1.55 m. Direct gains stay between 0.55 and 1.95. Pickup evaluates
geometry at reception time and reads each shared source history. Delay
excursions around the central propagation delay use at most 16% of their
physical size, with an analytical speed bound reducing that further when
necessary. Delay rate stays within 0.004 internal samples per sample. This
musical Doppler approximation also compresses the distance differences of
stationary microphones. Proximity gain, absorption and sparse early returns
follow the actual geometry. Microphone signals use an arithmetic mean;
coincident paths reproduce one microphone. A restrained common pickup combines the root-only response with the actual
full head responses at the central delay. Its total root gain is unchanged by
that blend; it preserves some body through mono cancellation without changing
the performer states.

## Controls and saved state

The five timbral controls are PLAY, SKIN, ORBIT, WEAVE and REACH. TUNE is the
existing host note control, spanning C3–C5, and HOLD selects finite performance
or settled repeating material. Performance tempo and microphone orbit speed
are independent. CIRCLE, CLOSE, CROSSING, SPIRO, FRICTION and PROCESSION retain
all four source families while changing articulation balance and listening
perspective.

`RevelPatch` saves macros, voice, velocity and `RevelConfig`: microphone count,
phrase tempo and beats, trajectory roster, phases, directions, speed ratios
and seed. Seed strings preserve every bit of a Kotlin `Long`. Editing a patch
or rebuilding a dry pad recipe retains this configuration. Every render starts
with fresh performer, floor, contact and acoustic state. Independently exported
pads share no state and need no external samples.

Requested orbit is stationary at ORBIT zero and otherwise ranges from just
above 0.10 to 0.52 Hz. HOLD uses two complete phrase cycles, quantizes nonzero
travel to integer revolutions, and uses even revolutions for half-integer speed
ratios. Diagnostics record requested and actual rates. Three hidden preroll
periods precede two genuine candidate periods; their audio difference, complete
acoustic-state difference and pre-wrap seam must each be below 1e-3 before the
existing unity-sum wrap handling. Snip's current pad format exports the settled
loop alone.

Phrase tempo accepts 60–200 BPM and one to eight beats. If a finite performance
would exceed 4.35 seconds, the planner halves its beat count before scheduling,
then renders 2.65 seconds of passive tail. Every event duration is fitted after
deterministic jitter so its release finishes before the phrase boundary. The
default four-beat, 104 BPM phrase remains intact. HOLD below its 0.99 top step
remains finite; its upper finite settings request two compatible phrases.

## Audition and integration

Run `./gradlew --no-daemon :synth:generateRevelAudition` to generate the complete
raw/matched listening matrix and standalone page in `testkit/revel-audition/`.
Add `-Pquick` for the smaller first listening set. Raw and loudness-matched files
are derived from the same render, with no rack effects. Diagnostics and the
manifest accompany the audio. Path CSV floating-point fields use six decimal
places for compact hosting; waveform files and scalar diagnostic measurements
retain their original precision. HOLD cases can repeat across their wraps; source
and floor ablations and fixed-performance microphone comparisons help identify
what creates the sound.

For a hosting service with an expanded-archive limit, run
`python3 scripts/package_revel_audition.py testkit/revel-audition /tmp/revel-hosting`
after generation. This packages audio and diagnostics with lossless gzip
transport; the player restores the original WAV and diagnostic bytes for
playback and downloads. It requires a current browser with `DecompressionStream`
support. The ordinary generated pack continues to use direct files.

Ten provisional presets and `SynthKits.revel()` use the shared patch, recipe,
velocity and MPC export pipeline. Finite pads use melodic routing; HOLD uses
loop routing. Tests also call the actual audio classifier rather than relying
only on engine metadata. Long phrases naturally enter its duration-based LOOP
bucket, so that classification alone does not establish root retention.

## Validation

The repository checks use the existing CI paths: `./gradlew --no-daemon test`
(with `-x :app:test` when an Android SDK is available),
`./gradlew --no-daemon :app:assembleDebug`, and the host native harness
(`scripts/check_native_sources.py`, CMake, then CTest). The audition player is
checked in Chromium with real PCM24 playback, raw/matched switching, HOLD
repeat, note persistence/export and layouts from 320 to 1440 pixels.

The published [audition](https://snipsnap-revel-audition.bertcalm.chatgpt.site)
contains 299 listening positions and 538 PCM24 WAVs from 261 simulations.
Its manifest identifies source SHA256
`33b55769250b1b23d2fb387d13b20ed0ce0da4716183fefb53e0a143ca8198d4`.
The complete matrix records zero forbidden drum-family short classifications
and zero failed HOLD seams. The 54 voice/note/velocity cases all detect pitch;
the maximum error of the recorded best-confidence window is 16.17 cents.
Separate released-contact probes return to the modal root.

Raw ensemble peak is at most 0.4391 and absolute mean DC is below 6.07e-10.
Diagnostic source taps peak at 0.88494; no WAV required PCM safety attenuation.
Maximum direct pickup gain is 1.81261 and delay rate is 0.00395244. All 11
exported HOLD cases pass genuine pre-wrap comparison: maximum seam 5.86e-13,
waveform convergence 3.23e-13 and complete acoustic-state convergence 1.34e-16.
Three hidden preroll periods precede those comparisons. The regression suite
also covers a one-beat, 200 BPM, 0.6-second loop with a fast spiro path.

On this shared cloud run, median synthesis time is 643 ms per unique render;
the most expensive all-high SPIRO HOLD render takes 3.97 seconds, including
hidden preroll. These timings exclude file encoding and are not a phone
benchmark. Browser checks exercise real audio playback and all 1,345 referenced
assets at 320, 390 and 1440 pixels.

## Validation limits

The render uses SnipSnap's 4× internal path and 44.1 kHz mono output. Numerical
checks establish determinism, bounded state, passive decay, geometric safety,
configuration round trips, root probes, routing and loop closure. They cannot
establish convincing source identity, musical interlocking or a satisfying
moving perspective. Listen to the dry family comparisons, raw/matched pairs,
microphone studies and repeated loops before accepting the sound. Phone and
MPC listening remain separate hardware checks. The working name has not passed
release naming checks.
