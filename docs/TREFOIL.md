# TREFOIL — finite three-body resonator study

The first listening prototype adapts the supplied Trefoil engineering spec v1.0
(18 October 2026) to SnipSnap's offline path. Equal-mass Newtonian motion drives
contact between three pitched banks, with actual internal energy exchange and a
feedforward sympathetic trace. WEAVE, HOLLOW, WIRE, TANGLE and TRACE share the
same structure and equal excitation budgets. Every bank has its own root mode;
there is no fourth anchor. Output is dry mono, 4x internal / 44.1 kHz delivery.

HOLD, a validated PAIR departure family, bank/hardware listening and on-device
performance remain later work. Model-two presets and phone registration are
implemented in the playable-bank round below. COMPLETE means only that a finite simulation
horizon was reached. No trajectory is claimed to escape forever.

## Physics and deterministic recall

`TrefoilTrajectory` uses unsoftened planar G=m1=m2=m3=1 gravity. Apache Commons
Math 3.6.1's `DormandPrince853Integrator` supplies adaptive 8(5,3) stepping and
dense output. Model 1 fixes rtol=1e-10, atol=1e-12, max step=.04, 250,000 RHS
evaluations and 50,000 accepted steps. Work is bounded and interruptible.

Reference family 1 fixes the supplied positions at (.97000436,-.24308753),
their negative and the origin. A/B velocities are
(.46620368406955315,.4323657309174746); C is minus twice that. Period is
6.3259140097821724. `scripts/trefoil_refine_reference.py` reproduces numerical
refinement from the supplied rounded seed using SciPy DOP853/least squares.
The separate double-precision refinement measured 5.20e-14 closure; it is not
certification. Mathematical family: Chenciner & Montgomery (2000),
https://annals.math.princeton.edu/articles/12364 .

NUDGE adds one seeded velocity perturbation, with mean removed and RMS normalized
to .38 * NUDGE^1.35 of reference RMS speed. General perturbations may change E and
Lz; diagnostics use each perturbed initial baseline. NUDGE zero ignores seed.
Pitch and velocity do not alter gravity. An eight-entry cache keys dynamical seed,
nudge, weave and duration; solver and reference constants belong to model 1.

WEAVE is a common clock: reference cycles/second are 0 at zero, 1/16 at .1, 1/8
at .25, .5 at .5, 2 at .75 and 8 at one, with smooth interpolation. Horizon comes
from duration and never wraps. Dense output supplies 1 kHz musical-time readouts.
Six pairwise separation minima per reference period supply contact opportunities;
origin crossings trigger none.

Derivative stages entering separation 1e-3 stop conservatively, including trial
stages that would be rejected. Dense intermediate checks complement that guard.
This prototype does not claim a formally certified detector for every unresolved
encounter. On exclusion or work-cap termination, the last accepted snapshot is
retained for release, flagged, and continuing excitation ends smoothly. That
snapshot is not presented as continued motion.

## Original audio model 1, frozen for replay

Each bank has six modes at ratios 1,1,2,3,4,6. A local root mode keeps its tuning
while the second root degree of freedom can exchange strongly. THREAD changes excitation, pickup
and bounded upper-mode inharmonicity within those banks. A/B/C favor lower,
middle and upper modes while retaining roots. Note-relative finite onsets and
geometry-derived accents join a bounded friction-like drive. This is a designed
exciter, not a calibrated bow. Noise is seeded and band-limited.

In (omega*q,v) energy coordinates, free modes are exact damped rotations. Each
bank exposes one normalized velocity port. EXCHANGE applies exp(K), K real skew
3x3, to all three old ports before writes. Replacing the projections preserves
complete core squared-state norm even as orthogonal coefficients change; damping
contracts it. Zero EXCHANGE is exact identity. The local root has zero port weight;
it remains within each participant rather than supplying a fourth oscillator.
Transfer speed scales down in the bass. Positive external work has an equal finite
budget per bank.

This uses an instantaneous modal-port junction instead of the brief's suggested
propagating-wave junction, adapting CHAMBER's energy coordinates and OVERLAP's
scattering approach. MEMORY sends into eight damped harmonic modes with no return
path. Memory energy is reported separately from the passive core. Duration caps at
eight seconds with a delivery tail fade. HOLD must be zero and other values refuse.

`TrefoilPatch` saves explicit MIDI 36–84, velocity, exact signed 64-bit seed as
decimal text, duration, active duration and model within shared patch version 1.
`Patches`, `PadRecipe` and physical `Velocity` replay support it. Prototype recipes
originally stayed outside the factory roster and phone picker. The model-two
playable bank is now registered separately. Edits preserve
non-macro state. No hidden solver or DSP state is serialized.

## Reproduce the audition

```sh
./gradlew --no-daemon :synth:test --tests com.snipsnap.synth.TrefoilTest
./gradlew --no-daemon :synth:generateTrefoilAudition
./gradlew --no-daemon test
./gradlew --no-daemon :synth:generateTrefoilAudition \
  -PtrefoilReplay=/absolute/path/to/recipes/transfer-on-1.json \
  -PtrefoilReplayWav=/tmp/receiver.wav
```

The model-two generator writes 67 cases / 134 actual PCM16 WAVs, complete diagnostic recipes,
trajectory/energy traces, raw/matched metrics, source hashes and a starter ZIP under
ignored `testkit/trefoil-model-two-audition/`. Retained frontend source lives under
`synth/src/test/resources/trefoil-audition/`. Five-step controls, three registers and
three velocities join moving/frozen, exchange-off, memory-off and additive stems.
The single-body impulse disables B/C exciters and memory. Off receivers are silent.

Matched clips target loudest-200ms RMS .03. Causal branches/stems retain their
reference mix's one gain; raw files retain unit pre-leveling gain. Diagnostic
options and matching gain are saved beside each patch. PCM hashes identify files.
The starter ZIP includes first sounds, comparisons, stems, transfer proof and source.

Classifier diagnostics retain both the first-1.2-second result and a whole-gesture
result with only the generic duration-to-LOOP shortcut bypassed. A rising bass
excerpt can read KICK when its latest peak has no following decay interval in that
short excerpt. The whole-gesture guard retains the actual envelope. Neither result
changes routing metadata or claims that a listener has accepted the timbre.

The page supports one audible player at a time, pause/seek/stop, finite-clip repeat,
raw/matched switching, measured trajectories, local listening notes and JSON export.
Notes are never submitted to a server. Publication implies neither listening
acceptance nor phone/MPC hardware validation.

## Comparison review, listening interface revision 2

**Heard:** Josh reports difficulty distinguishing the comparisons. The first
recordings remain unaccepted and are preserved byte for byte. The audio model,
recipes, fixed matching gains and source provenance remain model 1 at
`a637ee4a61edec30441114ae98922383f562a93c`.

**Fix now, sound character:** the five starters put 99.13–99.97% of Hann-windowed
spectral power within +/-5% of the MIDI root. The default ordered memory on/off
residual is -47.27 dB relative to the reference after removing one scalar gain.
The bank colors and motion do not establish useful audible differences simply
because their internal states differ. `scripts/trefoil_comparison_audit.py`
measures the published PCM16 files and records hashes and the exact method in
`comparison-audit.json`. These are signal measurements, not listening thresholds.

**Fix now, playability:** the original list required restarting each recording.
The guided player runs both decoded mono buffers on one Web Audio clock with
complementary 8 ms gain ramps. A/B switches preserve position, pause and seek.
Five-millisecond edge fades on identical focus windows avoid hard loop cuts;
full recordings are untouched. The individual player pauses while the paired
player runs and starting an individual recording releases both paired sources.

The guided sequence compares perturbed moving/frozen geometry, a connected/silent
unstruck receiver, and the wider 0%/100% memory endpoints, then labels the original
ordered exchange and memory pairs as subtle. The receiver retains the complete
mix's gain. Memory endpoints keep their existing per-clip loudness target. Each
policy is stated beside the player. Neither isolation nor endpoint selection
establishes that the production mix has sufficient character.

**Try next, sound character:** a new acoustic model should let geometry change
the audible partial balance and the contact gesture while preserving a useful
root. The present coherent root drive, protected local root modes and similar
pickups are candidate causes of masking. Altering those mechanisms needs a new
model/version comparison, root and energy probes, and Josh's listening verdict.
No acoustic constants were changed in this interface revision.

**Measured:** PCM analysis above, paired-player continuity and actual silent
receiver output in browser QA, and the focused Kotlin Trefoil checks. The earlier
whole-repository run ended with the execution session before a final build result;
it is not recorded as a full-suite pass.

## Acoustic model 2, following the listening rejection

The characteristic gesture is colored contact → shifting upper-partial ownership
between three bodies → excitation ends → the upper-partial memory remains.
Josh heard no useful change in ordered exchange or default memory in model 1.
Its silent disconnected receiver proved isolation, rather than musical contrast.

The Newtonian solver, choreography reference, time map, exclusions and eight-entry
trajectory cache are unchanged. Both acoustic models reuse the same cached trace.
Model 1 retains its arithmetic and noise stream. The model field dispatches both
diagnostic and normal Patch rendering, including JSON and PadRecipe replay.

Model 2 uses eight modes at ratios 1,2,3,4,5,6,8,10. A normalized colored exciter
receives a finite band-limited harmonic contact drive plus seeded filtered noise.
THREAD changes excitation, pickup brightness and bounded upper-mode offsets.
The three fixed pickup colors make redistributed partial energy observable.
Exciter phases differ between bodies; the former duplicate protected root is gone.

EXCHANGE rotates both (omega*q,v) coordinates between all three banks separately
for each partial. The simultaneous real orthogonal rotations preserve the summed
core energy even with moving geometry. Root exchange is weaker than upper-partial
exchange, and exchange rate scales down with fundamental frequency in the bass.
Positive source work retains the equal per-body budget. Damping contracts the core.

MEMORY receives corresponding upper-mode velocities, rather than reinforcing the
same dominant root. Its T60 is (2 + 4.5*MEMORY)/(1 + .10*modeIndex) seconds, so
the default .25 outlasts the dry modes. Send and return each scale with sqrt(MEMORY),
giving a linear combined amount before decay changes. No memory signal returns to
the core. The dry pickup gain is constant for every memory value and ablation.

The revised audition lives beside the complete original page. Its first A/B pair
compares original and revised ordered sounds at the same full-clip loudness target;
the next pairs test revised exchange and default memory at common reference gain.
The isolated silent-receiver test is labeled technical. Original-model WAV hashes
are checked against the first published files. New browser notes use a separate
storage key; earlier notes remain with the original page.

### Why it is built this way

- Richer contact puts energy into the partials that the three bodies radiate
  differently. A nearly pure shared sine hid their different states.
- Per-partial exchange moves actual internal energy. Its audible claim can be
  judged with exchange disabled while the same exciters keep running.
- Longer upper-partial memory leaves a different tail. Disabling it removes only
  that contribution, so a compensating dry-volume change cannot mimic its effect.
- The original model stays replayable. This is a new acoustic candidate awaiting
  Josh's verdict, with finite gestures and on-device performance still to be judged.

**Verified/measured ledger:** numerical invariants, exact replay, pitch and audio
measurements are recorded in the generated diagnostics and verification report.
Browser QA checks switching, playback and signal/silence routing. Spectral residuals
are not perceptual thresholds. Josh subsequently described the revised audition as
"Sounds pretty good" and authorized the playable-bank round. That supports model 2
as the sound-direction baseline; the new preset bank and hardware still need listening.
The trajectory cache avoids repeat solver work; audio rendering remains an offline
prototype, and measured render times are disclosed per recording rather than a
claimed phone latency budget.

## Playable bank and SnipSnap registration

`TrefoilPresets` authors twelve model-two C4 recipes: four plucks, four keys, two
moving wire gestures and two memory gestures. Each keeps an explicit finite contact
time, duration, seed, note and native strike. The acoustic model itself is unchanged.
The original model-one renderer, model-two listening recordings and their recipes
remain replayable.

`Presets` exposes all five Trefoil voices. `SynthKits.trefoil` and the `trefoil`
starter expose sixteen finite pads. A01–A12 transpose the twelve colors onto
repeating C4/Eb4/F4/G4 columns; A13–A16 place First Weave on C3/Eb3/F3/G3. Native
strike velocities .3, .6 and .8 become MIDI zones 1–41, 42–84 and 85–127. Softer
layers re-render the exact same recipe and peak-match the main layer; host velocity
then owns playback level. This preserves source-response changes without adding
an equalizer to manufacture contrast. Short, nearly linear contacts can have
subtle timbral velocity differences.

The phone engine adapter exposes ROOT, VELOCITY, CONTACT and the five timbral
macros. Unsupported HOLD is hidden and SCRAMBLE keeps it at zero. Generic patch
editing and drafts retain full recipes; UserPresets promotion emits a complete
JSON constructor into a separate promoted list. SPREAD uses the authored MIDI
root instead of guessing an octave from strong upper harmonics.

`generateTrefoilPlayableBank` writes the twelve-preset, three-touch browser bank,
sixteen-pad MPC 3 track and MPC 2 twin, forty-eight mono PCM24 kit samples, complete
kit recipes, portable pad map, source patch, asset hashes and two musical scores
under ignored `testkit/trefoil-playable-bank/`. Weave Study is sparse at 96 BPM;
Crossings overlaps chords and a three-touch pluck pattern at 104 BPM. Both patterns
are embedded in the MPC 3 track and retained in `groove.json`; set project tempo
for the selected pattern. Musical previews read the actual exported velocity
WAVs, preserve full finite endings and disclose their fixed monitor gain and
SnipSnap preview velocity law. Matched demo WAVs add only a saved scalar gain.

```sh
./gradlew --no-daemon :synth:generateTrefoilPlayableBank
./gradlew --no-daemon :synth:test --tests com.snipsnap.synth.TrefoilBankTest
./gradlew --no-daemon :shell:test --tests com.snipsnap.shell.TrefoilIntegrationTest
```

### Why the bank is built this way

- Presets change contact and musical role while retaining the revised sound model.
  An acoustic-model change would require another original/revised listening gate.
- Every velocity zone comes from native excitation. Peak matching leaves playing
  level to the host while retaining the source's own contact response.
- The two demos use exported samples, so overlapping tails and zone selection can
  be judged before hardware. A valid program file does not establish a hardware
  listening result or on-device render performance.
- The earlier studies and their browser notes keep their own locations and storage
  keys. The playable bank is a new listening round, not a replacement verdict.

## Release handoff and hardware audition

Owner feedback on the published playable bank was positive on 2026-10-26.
This is listening feedback on the browser bank, not evidence of a phone or MPC
hardware pass. The integration now includes the current SnipSnap picker and
starter catalog, with the authored root retained when sending a sound to a pad.
The finite acoustic renderer and all twelve preset recipes are unchanged.

Download the [MPC kit](https://trefoil-snipsnap-audition.bertcalm.chatgpt.site/playable/SnipSnap-Trefoil-Kit.zip)
and extract it before copying it to the MPC. Keep `SnipSnap Trefoil Kit.xtd`
beside `SnipSnap Trefoil Kit_[TrackData]`. For MPC 2, open the `.xpm` inside that
data folder. These are finite one-shot samples; their endings continue after a
short tap. HOLD is a separate future design.

1. Open the program and check all sixteen pads for missing samples. A01-A12 are
   the twelve colors on repeating C4/Eb4/F4/G4; A13-A16 are lower First Weave on
   C3/Eb3/F3/G3. Compare the pad names with `pad-map.json` in the kit folder.
2. On the same pad, play soft, medium and hard strikes. The saved zones cover
   MIDI velocities 1-41, 42-84 and 85-127. If recording a test sequence, include
   41/42 and 84/85 to hear the boundaries. Judge the contact change and level
   transition separately: native soft samples are peak-matched, and the host
   controls playback level.
3. Overlap neighboring notes and low notes with the upper rows. Listen for
   unintended choking, clipped chords and a pad that dominates the others.
4. Let A11 Afterglow and A12 Long Arc end completely, then retrigger them during
   the tail. Check the ending and the overlap for a click or an abrupt cutoff.
5. On MPC 3, play Weave Study at 96 BPM and Crossings at 104 BPM. Set project
   tempo for each pattern. The browser performances select the same sample
   zones, but their disclosed monitor gain and velocity law are not a claim
   that the hardware mix is identical.

Record the MPC model, firmware, MPC 2/3 import path, affected pad/velocity and
whether each issue also occurs in the browser. Physical MPC import, velocity
transitions, polyphony and release endings remain pending until that check.
On the phone, separately check ROOT/VELOCITY/CONTACT editing, navigation,
YOURS and SEND TO PAD; compiler checks do not establish device responsiveness.
