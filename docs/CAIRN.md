# CAIRN: KNOCK and ANSWER reconstruction

Status: finite two-voice listening milestone, 2026-10-24. New source, not a
recovery of the six-voice renderer. Sound acceptance, phone timing, MPC checks
and a continuous HOLD remain open.

The characteristic gesture is **one launch → direct groove contacts → motion
through the mount → quieter higher replies → a padded landing**. KNOCK stops
at the close contact cluster; ANSWER lets the mounting carry the response.

Repository truth: implemented against SnipSnap
`91384fa189a68c4993c8e359af6b748412bf95c3`. The actual APIs read are
`Modes.Bank.tune/drive/step/velocityAlong/energy`, `Dsp.seedFor`, `Dsp.Noise.next`,
`Tide.bandLimit`, `Dsp.decimate`, `Dsp.OnePole.lp`, `Dsp.levelTo`,
`Dsp.fadeTail`, `Keys.midiHz`, `Pitch.detect`, `Classifier.classify`,
`Velocity.atVelocity`, and the patch/prototype registry. No `siblingPatches`
API is assumed.

Before handoff, upstream `7659ed77d5448f140b1a01c2d35cc358600b24b6` was
merged into the reconstruction branch. Its added fixed-filter helper does not
alter Cairn's output path. Rendering against the combined production classes
reproduced all 92 native 24-bit WAVs byte for byte. The final source backup
applies against that integrated upstream revision.

[Private audition](https://cairn-voice-comparison.bertcalm.chatgpt.site/reconstruction/)
compares the reconstruction with four preserved recordings. The existing
[six-voice sample bank](https://cairn-voice-comparison.bertcalm.chatgpt.site)
and original comparison retain their reference audio.

## Scope and the sibling check

This round proves a common motion/contact core for **KNOCK and ANSWER**. It
contains two provisional starting points, saved recipes, native velocity,
a first-contact cue, a bounded motion cache and 92 reproducible audio cases.
There is no factory preset expansion, new MPC kit, Android picker button,
acoustic feedback into the mechanics, general rigid-body rolling simulation,
continuous friction exciter, or accepted HOLD loop.

MERCURY already owns struck/rubbed material colors. TREMOR already owns
coordinated strikers and loose contacts. Cairn's useful distinction is the
way terrain position controls contact timing and motion through a mounting
awakens separate higher contacts. A new timbre table alone would not earn
another engine.

The selected COUPLE direction puts one shared passive mount between the
stones: clear gesture, moderate novelty beside those siblings, bounded cost,
and a small listening round. A SPLICE direction using TREMOR's strikers would
be cheaper but would lose terrain timing. A literal five-body rolling/contact
solver would retain more proposed mechanics at substantially higher validation
and render cost. The shared reduced model is the first proof.

## What is literal and what is a design analogy

Four bead coordinates and one mount coordinate form a linear mechanical
network. Mass, positive spring energy and viscous losses use normalized units.
One initial velocity supplies all launch energy. Springs carry equal and
opposite forces. A 2 kHz implicit-midpoint solve advances

```
M dv/dt + D v + K q = 0
q_next = q + dt/2 (v + v_next)
(M + dt D/2 + dt² K/4) v_next
    = (M - dt D/2 - dt² K/4) v - dt K q
```

`K` is ground springs plus nonnegative mount springs, so it is positive
definite. `D` is positive. Implicit midpoint cannot increase this quadratic
energy. The 5×5 inverse is computed once per motion trace, outside audio.

Grooves are asymmetric edges in each bead's position coordinate. Crossing a
steep outward face withdraws a fraction of that bead's kinetic energy:

```
work = mass * speed_before² * fraction / 2
speed_after = speed_before * sqrt(1 - fraction)
```

The reverse face is smooth and makes no impact pulse. There is a quiet starting
pocket and an 18 ms refractory interval. Fractional position crossings provide
substep timestamps. Nothing directly launches an upper bead. Disabling mount
coupling therefore removes upper contacts without removing the primary launch.

After 2.5 seconds a terminal pocket gradually contracts both position and
velocity. This is a disclosed dissipative gesture approximation. It removes
quadratic energy, rather than correcting instability or adding another
excitation. Its removed work is recorded separately. Motion ends at 3.5 seconds;
remaining mechanical energy is measured, not described as mathematically zero.

Groove layout, springs, damping, modal tables and the 2.5-second closure are
**shape constants**, not measured ceramic or stone properties. No deformation,
3D sphere rotation, air field or reciprocal modal/mechanical collision is
simulated. The earlier renderer's default waveform is not reproduced.

## Signal flow

```
voice, WEIGHT, SLOPE, GROOVE, FRICTION, BODY, velocity, model
           |
           v
[5-coordinate motion, 2 kHz; immutable events + work + trace]
[24-entry LRU cache: every input above; TUNE and HOLD excluded]
           |
           | contact time, stone ID, withdrawn work (one way)
           v
[unit-area raised-cosine pulse, width from mass and velocity]
           |
           v
[Modes.Bank: 3 primary + 3 upper roots + 2 body modes, 176.4 kHz]
           |
           v
[Tide.bandLimit -> Dsp.decimate -> mean/DC -> 20 Hz HP]
           |
           v
[Dsp.levelTo(.1834) -> 4 ms tail fade -> optional first-contact cue]
           |
           v
[mono 44.1 kHz Snip]
```

The body is a one-way acoustic readout of the same contacts. Its root shares
the note. No spring in the acoustic bank shifts the note; upper roots are
2×, 3× and 4×. Primary material ratios are `[1, 2.756, 5.404]` for KNOCK and
`[1, 2.4, 4.1]` for ANSWER; these are retained design shapes from the previous
implementation notes. Modes above 12 kHz receive no drive. Each contact's
impulse is `.12 * sqrt(withdrawnWork)` under one fixed calibration. Upper
contacts are never individually normalized. Acoustic energy is not claimed
identical to mechanical work; the graph is a bounded one-way observer.

## Macro contract

All controls are 0..1. Nonfinite direct-render controls fall back to defaults;
unknown keys are ignored. Saved patches validate names and values.

| Control | KNOCK / ANSWER defaults | Model quantity | Pair and proof |
| --- | --- | --- | --- |
| TUNE | .50 / .50 | Snapped root, C3..C5 at the default MIDI 60 | No motion rebuild; three-register pitch test |
| WEIGHT | .45 / .60 | Bead mass and acoustic contact width | WEIGHT × SLOPE changes travel and withdrawn work |
| SLOPE | .30 / .45 | Launch speed and pocket stiffness, an analogy of slope | WEIGHT × SLOPE changes contact timing |
| GROOVE | .25 / .45 | 2..6 edges, spacing and withdrawn fraction | Works through actual position crossings |
| FRICTION | .15 / .40 | Viscous motion loss | BODY × FRICTION changes upper-contact work |
| BODY | .35 / .75 | Mount mass/spring strength, body participation, ring time | BODY × FRICTION; middle cube and uncoupled control |
| HOLD | 0 / 0 | Finite ring extension below .99 | Does not change motion; .99..1 explicitly refused |

The common comparison vector is TUNE .5, WEIGHT .5, SLOPE .4, GROOVE .35,
FRICTION .3, BODY .4, HOLD 0. Supporting C3..C5 is deliberate: the short C2
KNOCK failed the pitched-engine classifier guard by reading as a tom. A lower
voicing is deferred rather than quietly filed as a pitched instrument.

## Timing and cost

`CairnPatch.startAtContact` defaults to true. It keeps two milliseconds before
the first physical impact; playback uses a suffix of the same rendered samples.
Setting it false retains the complete quiet launch. Both the model and cue
choice are serialized alongside note, velocity and controls. Zero velocity
produces no event and no audio. The two choices are available on the page.

The proposed interaction budget is 50 ms per render. Motion setup is bounded
by five coordinates, 7,000 steps, at most 256 events, and a 24-entry LRU cache.
Its key includes model, voice, all five mechanical controls and velocity.
Pitch and finite HOLD reuse motion. Fresh acoustic states are always rendered;
no cached oscillator state can leak from another note.

The audition manifest separates first-call time from warmed, uncached-control
renders and cached-motion pitch turns. Initial measurements on this server JVM
were 5.6 ms median KNOCK and 18.9 ms ANSWER across 12 new-control trials each;
the first ANSWER call was 82 ms including initialization. These are not phone
results. The first call misses the 50 ms budget; do not substitute warm playback
latency for that number. The regenerated manifest is the authority for a run.

## Verification and listening

Run with JDK 21:

```
./gradlew --no-daemon :synth:test --tests com.snipsnap.synth.CairnTest
./gradlew --no-daemon :synth:generateCairnAudition
./gradlew --no-daemon test
```

`CairnTest` checks 243 middle combinations: each of WEIGHT, SLOPE, GROOVE,
FRICTION and BODY at .25/.50/.75. The first measured minimum was seven higher
contacts. Default ANSWER made twelve; common controls made nine. KNOCK at common
controls made three direct contacts and no higher contacts. Uncoupling ANSWER
removed all higher contacts. Counts describe physical events, not a claim that
every quiet event is perceptually distinct.

Passivity is tested before output conditioning. Recorded energy plus withdrawn
work cannot exceed the preceding independent trace window or initial launch.
A deliberately growing trace is rejected by that same predicate. Contact work
is independently checked against its before/after kinetic energy. Free modal
tails must lose energy after the last force. Other gates cover fresh-cache
replay, map ordering, tuning without motion changes, three-register pitch,
output bounds/DC, classifier, velocity, exact cue suffixes, unknown controls,
nonfinite defaults, JSON replay, scramble bounds and reserved HOLD. The
`DeterminismTest` canary clears the cache between fresh renders of both voices.

A separately synthesized 8× reference at C5 is compared with 4× output. The
initial relative RMS differences were 2.90% KNOCK and 1.93% ANSWER. This bounds
rate sensitivity for those cases; it does not prove zero aliasing at every
control corner. Pitch is measured over 200..450 ms of cued audio, after the
primary impact cluster. Earlier windows spanning several attacks can bias the
low-register autocorrelation estimate.

The generator writes 92 cases with cued and full 24-bit WAVs, actual-rate raw
WAVs, complete JSON recipes, per-contact work CSVs, motion traces, hashes and a
manifest. For the two uncoupled diagnostic cases, the patch JSON remains the
ordinary instrument recipe; the separate `uncoupled: true` manifest field
selects `Cairn.Options(disableCoupling = true)`. Loading that patch alone does
not disable coupling. The generator records and replays that diagnostic choice.
Sections include defaults at C3/C4/C5, common controls, all control
sweeps, BODY × FRICTION corners, velocity and isolated coupling. No loop is
repeated and called a natural seam. The top HOLD step remains refused.

Package for the existing Site:

```
python3 scripts/prepare-cairn-site.py --site /absolute/path/to/cairn-site
```

The packager verifies renderer hashes, FLAC/24-bit PCM round trips, exact cue
suffixes and the original comparison hash. Four historical recordings are
copied byte for byte. Preview gains use a double-precision port of the house
loudness proxy, separate from PCM. A source ZIP carries the engine files and a
complete patch against the named base revision. It contains no credentials or
Site identity. The repaired Luthier bundle is retained under `.claude/skills/`
with the `.agents/skills/luthier` discovery link and resource checker.

## Sonic risk still open

At WEIGHT = 0 in the full control sweep, the Kotlin 200..450 ms pitch reader
reports 276.79 Hz against the 261.63 Hz root, about 97.5 cents sharp. The mode
frequencies themselves are fixed, but their relative phases and amplitudes can
weaken the perceived root. The Python harness gives a different, suboctave
estimate for this case; neither estimate establishes listening acceptance.
The <12-cent Kotlin guard applies to the explicitly tested middle settings,
not every sweep endpoint. Default C3 KNOCK's matrix estimate is about 21.4 cents
flat. The page includes these cases rather than excluding them. Tuning that
corner before a factory release is open work.

Independent Python probes on six actual Kotlin WAVs found finite samples,
zero detected peaks above 95% of Nyquist, and negligible DC. Their broad pitch
estimates differ from the Kotlin post-cluster window, particularly on ANSWER.
These are analysis results on saved audio, not another renderer or an ear check.

## Integration and deferred work

Implemented: core, model-pinned patch dispatcher, native velocity, provisional
starting recipes, prototype exemption, generic patch/recipe test entries,
determinism canary, DSP probes, audition Gradle task, retained page resources,
source backup and these notes. Presets remain outside the factory roster until
listening. New kit generators, factory recipes and phone UI are deferred. The
app picker is not guessed without an Android SDK. The existing sample kit
continues to serve the approved recordings.

Next gate: Josh compares earlier/default/common ANSWER and decides whether the
higher gesture and new timing preserve what he likes. Then tune this core,
measure on a phone, and implement an independently converged powered HOLD before
adding TUMBLE, SING, GRIND and VESSEL.

## Verified / proposed / heard ledger

| Claim | Status | Evidence |
| --- | --- | --- |
| Real toolkit APIs and registry integration | Verified | Named base revision, compiled Kotlin and registry tests |
| Middle-setting reply, cue, energy and pitch guards | Measured | `CairnTest`, actual Kotlin renderer |
| Render costs and individual contact counts | Measured on server JVM | Generated `manifest.json`, first call separated |
| Historical higher hits and original PCM | Preserved evidence | Original comparison hash and four original FLAC byte hashes |
| Physical material constants | Proposed shapes | No measured stone geometry or material calibration |
| Continuous HOLD, other four voices, phone latency | Deferred | No loop, phone or MPC validation claimed |
| The new audio retains the approved character | Not heard | Owner's comparison audition is the acceptance gate |

## As built

This deliberately replaces the missing source with a reduced, explicit model.
It does not promise byte identity with historical Cairn renders. Mechanical
passivity and causal replies survived the first Kotlin checks. The first
server render exceeded the proposed 50 ms budget, while warmed new-control
renders fit. Register support was bounded to C3..C5 after the C2 classifier
probe. Numeric acceptance is separate from listening acceptance.

## Why it is built this way

- The common mount makes higher contacts a consequence of the first motion.
  Removing it really stops the reply; a delayed layer would miss that point.
- KNOCK spends its motion locally and loses it quickly. ANSWER carries some
  of it onward. That changes the phrase as well as the material color.
- One fixed work-to-impulse calibration keeps small late contacts small.
  Turning each answer up separately would erase the useful scale difference.
- The cue makes pads responsive while preserving the full launch for listening.
  It changes where playback starts, not the generated sound.
- The reduced mechanics make each control turn cheap and testable. The risky
  choice is its simpler timing and acoustic readout; only the reference
  comparison can tell us whether that trade keeps the gesture you liked.
