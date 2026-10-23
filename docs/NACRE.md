# Nacre — second finite listening model

Nacre is an original, sample-free paired-harp engine. Model 2 renders one complete six-second
mono gesture at 44.1 kHz through the existing 4× synthesis and antialias path. Listening
acceptance is pending. The audition is at https://nacre-first-audition.bertcalm.chatgpt.site.

## Why model 2

The first listening verdict was that most variation came from the strum pattern. Measurements
supported that: the two venue full mixes correlated about .997, and the default gesture had
spent 99% of its energy by about .77 seconds, before one ordinary current cycle. A small
waveform difference established that a control was connected, but did not establish useful tone.

Model 2 uses finite harmonic pick displacements, wider TOUCH-dependent spectral slopes and pick
positions, stronger upper-string participation, and distinct bass/upper body and pickup colors.
Longer string decay and a faster low-range field leave time for motion after the strum. Current
changes upper-string losses and pickups, body excitation, and sympathetic transfer. Root-related
sympathetic harmonics add a response beyond the active octave grid. Coral has short reflections
and ringing local cavities; seaweed uses changing band transmissions and bounded wavering taps
without an enclosed late tank. The new fixed-pattern comparisons test these listening hypotheses.

## Implemented and saved

- One PEARL profile; four independently plucked strings and four sympathetic resonators per harp.
- Bass reference at the requested MIDI root and the upper reference one octave above it.
- TOUCH, DUET, CURRENT, VENUE and BLOOM; MIDI 28–72 and physical excitation velocity.
- Six provisional recipes, CLI rendering, recipe replay, exact signed 64-bit seed, and saved model.
- New patches default to model 2. Saved model-1 patches use the frozen `NacreModel1` renderer and
  original defaults. Regression fixtures pin the original published samples and event schedule.

VENUE 0 is coral and VENUE 1 is seaweed, with a convex blend between them. Conch, cave, expanded
chord/geometry editing, the phone picker, and live SURFACE synthesis remain deferred. HOLD must
be zero; nonzero values are refused. Ordinary sample playback can use the rendered audio.

## Bounds

Active strings drive fixed body filters; a delayed body mixture drives sympathetic modes; the
instrument feeds venue paths. Bodies, sympathetic modes and venues do not feed active strings.
String delays and filter coefficients stay fixed and current can only reduce the built feedback,
which is below one. Other resonators have fixed poles inside the unit circle. Modulated excitation
and pickup weights are bounded. The seaweed's interpolated taps are strictly feed-forward, with
nonnegative interpolation weights summing to one. A future active venue return needs a separate
complete-network stability argument.

## Audition

Run `./gradlew --no-daemon :synth:generateNacreAudition`. Output in `testkit/nacre-audition/` contains
59 clips, 118 mono PCM24 WAVs, actual production patch JSON, event CSVs, diagnostics, energy and
pitch metrics, source snapshots, and SHA-256 hashes.

The first ten clips compare the original/revised models, soft/bright touch, still/moving current,
coral/seaweed, and low/high bloom. The four tone pairs keep event timing and order fixed. Each full
focus mix is independently level matched, with common peak attenuation within its pair. This
makes tone easier to assess while raw playback preserves relative prelevel amplitude.

Other comparison families and diagnostic stems share their reference's gain. Stems are never
boosted independently. Raw mode uses one common safe gain across the pack. Mute, timing-lock and
bypass settings are diagnostic options beside each production recipe; they are not patch fields.
The ordinary recipe always reproduces its full production gesture. Non-DUET control sweeps also
lock timing. Mechanism probes cover solo harps, post-output-only and venue-bypassed current, and
unplucked sympathetic response with cross-harp transfer enabled/disabled.

Listening and raw downloads are split into numbered ZIP parts of at most 24 WAVs so every hosted
asset fits the static host limit. Extract listening parts into one folder; add raw parts for both
modes. Serve the folder locally or play WAVs directly. The complete ZIP is retained locally.

CLI example: `./gradlew :cli:run --args="synth NACRE PEARL --all --midi 48 --velocity 0.7 --out /tmp/nacre"`.
The provisional recipes remain outside the factory roster until listening acceptance.

## Acceptance

Automated checks cover deterministic events/audio, frozen model-1 replay, current model recipe
replay, octave voicings, internal current influence, sympathetic transfer, finite decay, tuning,
classifier safety, zero velocity, fixed-pattern spectral contrast, and component contribution.
Energy duration and broad-band spectra help catch the first model's failure; they do not establish
whether the two performers, moving water and venue contrast are musically convincing. The owner's
listening verdict completes this milestone before broader voices or held operation are added.
