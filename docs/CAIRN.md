# CAIRN: KNOCK and ANSWER

Status: preserved model-2 foundation, 2026-10-27. The owner approved the
KNOCK/ANSWER HOLD direction. Model 3 adds a separate TUMBLE rebound
voice; see [the three-voice implementation](CAIRN_TUMBLE.md). This document records
the two-voice milestone, C3–C5, without factory expansion or phone UI.

The characteristic gestures are:

- **KNOCK: push → close direct contacts → short ring → space → next push.**
- **ANSWER: push → primary call → mount carries motion → quieter higher replies → space.**

[Current HOLD audition](https://cairn-voice-comparison.bertcalm.chatgpt.site/hold/)
compares one-shots and continuing gestures at C3/C4/C5, the minimum-WEIGHT
adjustment, common controls and a dry 120 BPM example. The
[model-1 audition](https://cairn-voice-comparison.bertcalm.chatgpt.site/reconstruction/)
and [six-voice recorded sample bank](https://cairn-voice-comparison.bertcalm.chatgpt.site/)
retain their previous audio and source downloads.

Repository truth: this extension started from
`bf17f4561bda7418cc4ebf7bae8e78d7c8c45329`, which includes the merged first
milestone. Actual APIs read: `Modes.Bank.tune/drive/step/velocityAlong/energy`,
`Dsp.seedFor`, `Dsp.Noise.next`, `Tide.bandLimit`, `Dsp.decimate`,
`Dsp.OnePole.lp`, `Dsp.levelTo`, `Dsp.fadeTail`, `Keys.midiHz/seamError/requireSeam`,
`Pitch.detect`, `Classifier.classify`, `Velocity.atVelocity`, and the patch
registry. This round adds two internal uncoupled-bank helpers in `Modes.kt`;
no existing coupled-bank rendering path changes.

## Scope and the sibling check

MERCURY already covers struck/rubbed material colors; TREMOR covers strikers
and loose contacts. Cairn's distinction is that bead position determines
contact timing, and the shared mounting awakens separate higher contacts.
The chosen reduced COUPLE model retains that cause and effect with bounded
cost. A new material table alone would not distinguish another engine.

This extends the existing two Cairn voices. It does not add a generic rhythm
scheduler, friction exciter, new material family, Android picker, factory
presets, MPC kit, or attack-plus-loop sample format. A one-shot and a steady
HOLD cycle are separate render choices; `Snip` does not carry both regions.

## Mechanical model

Four bead coordinates and one mount coordinate form a literal normalized
mass/spring/damper network. Positive springs join each bead to the mount;
positive ground springs and damping keep the network passive between pushes.
At 2 kHz, implicit midpoint advances:

```
M dv/dt + D v + K q = 0
q_next = q + dt/2 (v + v_next)
(M + dt D/2 + dt² K/4) v_next
    = (M - dt D/2 - dt² K/4) v - dt K q
```

The 5×5 inverse is setup work. No geometry or iterative mechanical solver
runs at audio rate. Each outward crossing of a steep groove face withdraws
actual bead kinetic work:

```
work = mass * speed_before² * fraction / 2
speed_after = speed_before * sqrt(1 - fraction)
```

The return face is smooth. An 18 ms refractory period and fractional crossing
timestamps prevent chatter without creating a separately scheduled reply.
Only the primary bead receives a launch. Removing coupling therefore removes
all higher contacts, including in HOLD. KNOCK's mounting is weak and lossy;
ANSWER's is stronger and more mobile.

The late padded pocket contracts both position and velocity. This disclosed
gesture approximation removes positive quadratic network energy. It is not
an instability correction or a claim to simulate gravity, rolling spheres,
deformation or air. Groove layout, springs, damping and material ratios are
**shape constants**, not measured stone properties. The original six-voice
renderer was not recovered; its recordings remain separate references.

## Excitation and acoustic readout

```
voice, mechanical controls, velocity, model, powered/finite HOLD regime
  → 5-coordinate mechanics at 2 kHz [24-entry motion LRU]
  → contact time + stone ID + withdrawn work, one way
  → unit-area raised-cosine force at 176.4 kHz
  → Modes.Bank: 3 primary + 3 upper roots + 2 body modes
  → Tide.bandLimit → Dsp.decimate → mean/20 Hz high-pass → Dsp.levelTo
  → finite: 4 ms fade and optional first-contact cue
  → HOLD: actual continuing segment, seam check, retain one cycle without fade
```

A fixed `.12 * sqrt(work)` impulse calibration is shared by all contacts.
There is no per-contact normalization. Contact width depends on WEIGHT and
velocity; KNOCK's pulse is shorter. Upper contacts remain quieter because
their work is smaller. Acoustic energy is a bounded one-way observer, not a
claim of identical mechanical and acoustic units or reciprocal collision.

Primary ratios are `[1, 2.756, 5.404]` for KNOCK and `[1, 2.4, 4.1]` for
ANSWER. Upper roots are 2×, 3× and 4×. Body modes share the root and 1.59×.
No drive reaches a mode at or above 12 kHz. There are no acoustic springs
shifting the root frequency.

## Minimum-WEIGHT adjustment and model compatibility

In model 1, ANSWER at WEIGHT 0 and common controls gave a 276.79 Hz Kotlin
pitch estimate against C4's 261.63 Hz, about 97.5 cents sharp. Spectral analysis
still located the root mode at the requested frequency; close contacts and
inharmonic partials made the pitch estimate ambiguous. Retuning would move a
correctly tuned mode.

Model 2 instead multiplies the primary root participation by
`1 + 1.5 * clamp((.25 - WEIGHT)/.25, 0, 1)^2`, only for ANSWER. Above WEIGHT
.25 it is exactly unchanged. Motion, withdrawn work, mode frequencies and
upper-contact calibration stay unchanged. At WEIGHT 0, Kotlin's same pitch
window measured 3.15, 11.87 and 10.20 cents at C3/C4/C5, with confidence
.954/.938/.950. These are reader measurements, not a listening verdict.

All 92 model-1 audition WAVs were replayed after this extension and matched
the previous native WAV hashes byte for byte. Six default one-shot WAVs are
also pinned in regression tests to hashes from the previous audition.
Both models also render identical default float samples. Model-1 JSON recipes
continue to dispatch to model 1, including its top-HOLD refusal. Missing model
fields decode as 1. At this milestone, newly authored patches defaulted to model 2; unsupported
models are refused. The terrain seed retains its original geometry version.

## Macro contract

All controls are 0..1. Nonfinite direct-render values fall back to defaults;
unknown keys are ignored. Saved patches validate their control names/values.

| Control | KNOCK / ANSWER defaults | Changes | Interaction / evidence |
| --- | --- | --- | --- |
| TUNE | .50 / .50 | Snapped note in supported C3–C5 span | Reuses motion; three-register probes |
| WEIGHT | .45 / .60 | Bead mass, contact width; model-2 light-root participation | WEIGHT × SLOPE changes travel/work |
| SLOPE | .30 / .45 | Launch momentum and pocket stiffness, a slope analogy | Changes physical crossing times |
| GROOVE | .25 / .45 | 2..6 edges, spacing and withdrawn fraction | Events follow positions, not a phrase schedule |
| FRICTION | .15 / .40 | Positive viscous motion loss | BODY × FRICTION changes upper work |
| BODY | .35 / .75 | Mount mass/coupling, body participation and ring duration | BODY × FRICTION corners and uncoupled probes |
| HOLD | 0 / 0 | Below .99: finite ring extension. Model 2 at .99..1: powered periodic cradle | Powered regime enters motion cache; real seam probe |

The common vector is TUNE .5, WEIGHT .5, SLOPE .4, GROOVE .35, FRICTION .3,
BODY .4. KNOCK has three direct contacts and no higher contacts there; ANSWER
has three direct and nine higher contacts. Default ANSWER has four direct and
twelve higher contacts. Counts describe mechanical contacts, not guaranteed
separate perceptual hits. The original 243-point middle cube retained at least
seven higher ANSWER contacts. Zero velocity remains silent in both regimes.

## Powered HOLD and the real seam

At the top step, a periodic motor gives the primary bead a momentum increment.
KNOCK uses a two-second cycle; ANSWER uses four seconds. These are one/two bars
at a **reference 120 BPM**, not host-tempo synchronization. No tempo enters the
render API. The different rest lengths preserve punctuation and conversation.

Mechanical position and velocity persist between cycles. The padded closing
pocket starts at .75 s for held KNOCK and 2.5 s for ANSWER, and is dissipative.
It never sets either state to zero. Each next push records its signed added
kinetic work. Between pushes, state energy plus withdrawn work cannot grow.
The cycle budget is:

```
initial energy + supplied motor work
  = final energy + contact work + dissipated work
```

A maximum of eight mechanical cycles may settle. Both boundary coordinates
and velocities must repeat within 1e-8; contact IDs, times and work must also
repeat (time <1e-7 s, work <1e-8). Failure is an explicit render refusal.
The actual preceding and kept event lists are retained separately. Current
default and edge probes settle in three cycles.

The uncoupled acoustic bank has a diagonal damped rotation, `z_next = A z + f`.
Sparse impulses first accumulate one cycle's forced response `g` from rest.
`Modes.Bank.closeUncoupledCycle` solves `z0 = A^L z0 + g` using complex division.
`advanceUncoupled` traverses quiet gaps analytically. Both helpers refuse
coupled banks; tests compare them with independently stepped/warmed banks.

The renderer advances the solved state to a real quarter-second segment
preceding the retained cycle, then renders all forces and acoustic samples
normally through that segment, the kept cycle and 1,024 continuation frames.
The preceding segment settles the output filters. The continuation protects
the kept end from finite resampler padding. Nothing tiles a waveform or adds
a seam crossfade. HOLD gets no ending fade and no first-contact trim.

`Keys.seamError` compares the final 256 frames of the actual preceding segment
with the corresponding end of the kept cycle, before cropping. It must be
below 1e-3. A separate diagnostic path renders a full preceding acoustic cycle;
the audition generator compares retained audio against that slower reference.
The exported continuation file retains the real preceding segment and the
kept cycle, with the marker recorded in the manifest. Playing the exported
loop eight times is a listening aid, not the source of the seam measurement.

## Cost and caching

The interaction target is 50 ms per knob turn. The motion LRU key includes
model, voice, WEIGHT, SLOPE, GROOVE, FRICTION, BODY, velocity, uncoupled flag
and the finite/powered regime. TUNE and finite HOLD do not affect mechanics.
The bounded cache holds 24 immutable motion traces. No finished sample or
modal state is shared across renders. Changing notes reuses motion only.

A trace has five coordinates, 2 kHz steps, at most eight cycles and 256 contacts
per cycle. One-shots still use the original 3.5-second finite simulation.
Acoustic priming avoids a long audio-rate warmup on each control change.
The manifest records the first call separately from 12 warmed new-control
trials per voice/regime, and separately times cached-motion pitch turns.
Those are server JVM measurements, not phone latency or cached playback time.
The cold first call remains outside the 50 ms target; a phone measurement is
still required. See the current audition manifest for regenerated timings.

This run measured warmed new-control medians of 16.27 ms KNOCK and 32.29 ms
ANSWER in HOLD (12 trials each); mechanics took .94/1.65 ms. Cached-motion
pitch turns measured 14.93/28.89 ms. One-shot medians were 5.26/15.78 ms.
The first ANSWER HOLD call was 139.25 ms, including class initialization.
Across 41 held cases the maximum natural seam metric was 8.48e-15, below
1e-3, and the maximum relative RMS difference from the full-preroll reference
was 7.53e-8. The held C5 4×/8× comparison differed by 2.90%/1.93% RMS.

## Verification and reproduction

With JDK 21:

```
./gradlew --no-daemon :synth:test --tests com.snipsnap.synth.CairnTest --tests com.snipsnap.synth.CairnHoldTest --tests com.snipsnap.synth.ModesPeriodicTest
./gradlew --no-daemon :synth:generateCairnHoldAudition
python3 scripts/prepare-cairn-hold-site.py --site /absolute/path/to/cairn-site
./gradlew --no-daemon test
```

`generateCairnAudition` explicitly pins model 1 for the earlier finite milestone.
The new task writes 55 comparison cases plus a 16-second dry example:
C3/C4/C5 one-shots/HOLD, common controls, BODY × FRICTION corners, every
mechanical control's endpoints, softer velocity, disconnected coupling, and
model-1/model-2 minimum-WEIGHT comparisons. Recipes, current/preceding events,
energy traces, PCM hashes, marker positions, rate checks and benchmarks travel
with the audio. The uncoupled flag is diagnostic metadata; loading its ordinary
patch JSON alone does not disable coupling.

`CairnHoldTest` covers supplied-work accounting, passive intervals, continuing
mechanical convergence, causality, silence/velocity, JSON compatibility,
cache separation, fresh deterministic one-shots/HOLD, real seam mutation,
three-register classifier/output health, full-preroll parity and the root
corner. Six preserved default WAV hashes prevent both model paths drifting
together. `ModesPeriodicTest` independently verifies the new toolkit math.
`DeterminismTest` clears motion cache between both regimes and both voices.
The generator compares independently rendered 4× and 8× held C5 audio; this
bounds rate sensitivity in those cases, not aliasing at every possible corner.

The Site packager verifies source hashes, native 24-bit PCM → FLAC exactness,
and hashes all existing reconstruction audio/source files and bank downloads
before/after. It writes a new `/hold/` section. Its source ZIP includes a whole
Git patch against the named base plus the relevant DSP, tests and documentation.
No credentials or Site identity enter that backup.

## Disclosed limits and next gate

The <12-cent root claim applies to the tested minimum-WEIGHT common vector
and selected middle corners, not the entire macro hypercube. Default C3
KNOCK's earlier reader estimate was about 21.4 cents flat; its approved audio
is preserved. C2 remains unsupported because the short KNOCK read as a tom.
The six-voice historical renderer, continuous friction, material measurements,
phone timing, MPC playback, factory kit and Android picker remain outside
this milestone. The app picker waits for an SDK-backed implementation.

The next listening gate is whether KNOCK's rests and ANSWER's higher replies
remain distinct through repeated cycles, and whether the light corner still
has the right character. After that, add **TUMBLE** as a tightening cascade of
short contacts. SING, GRIND and VESSEL wait for their own mechanism decisions.

## Verified / proposed / heard ledger

| Claim | Status | Evidence |
| --- | --- | --- |
| Toolkit, patch/velocity integration, legacy dispatch | Verified | Named base, compiled source and compatibility tests |
| Reply causality, powered work, convergence, seam and root readings | Measured | Actual Kotlin tests and audition manifest |
| Server render cost and high-rate comparison | Measured on server JVM | Cold call, new-control and pitch-turn benchmarks; 4×/8× renders |
| Earlier recordings and source archive | Preserved | Packager hashes and six default WAV canaries |
| Material constants, motor/pocket shape and reference phrase lengths | Design shapes | No measured stone or cradle model |
| Direction of the first reconstructed audition | Heard by owner | Owner: “I love the new direction” |
| New HOLD and minimum-WEIGHT sound acceptance | Awaiting owner | New comparison page; no assistant hearing claim |

## Why it is built this way

- The shared mount makes the higher replies consequences of the first motion.
  Disconnecting it removes those contacts rather than muting an added layer.
- KNOCK's short cradle and ANSWER's longer one leave different amounts of space.
  Continuing the motion preserves their roles as punctuation and conversation.
- A periodic push supplies the energy HOLD needs. The late padded closure only
  removes it; no state reset or final limiter hides the mechanical budget.
- The light-corner change strengthens an existing root instead of retuning it.
  Versioned recipes and preserved defaults protect the direction already approved.
- Sparse acoustic priming keeps control turns responsive while real continuation
  checks the seam. The remaining risky trade is the simplified cradle shape;
  listening decides whether its repeating gesture stays interesting.
