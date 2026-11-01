# CAIRN: TUMBLE and the three-voice listening gate

Status: model 3 listening milestone, 2026-10-31; verification updated 2026-11-01.
The full nine-module JVM gate passes: 7,450 tests, no failures, errors or skips.
The owner heard the model-2 KNOCK/ANSWER audition and approved its direction.
TUMBLE has not yet been heard. No new factory presets or phone controls are
part of this round.

The characteristic gesture is **one launch → first landing → smaller,
closer rebounds → a tiny contact sticks → ringing stillness**.

The three pad roles are now deliberately different:

| Voice | Physical cause | Phrase role |
| --- | --- | --- |
| KNOCK | A lossy mount permits a compact cluster of direct contacts | Punctuation |
| ANSWER | The primary bead moves a shared mount, waking separate higher stones | Conversation |
| TUMBLE | One stone loses height at each bed impact, shortening its next flight | Cascade / fill |

[Three-voice audition](https://cairn-voice-comparison.bertcalm.chatgpt.site/tumble/)
compares defaults and identical controls, C3/C4/C5, one launch and HOLD, every
TUMBLE control, pair corners, velocity and two dry phrases. The
[model-2 HOLD audition](https://cairn-voice-comparison.bertcalm.chatgpt.site/hold/),
[first rebuilt engine](https://cairn-voice-comparison.bertcalm.chatgpt.site/reconstruction/)
and original six-voice recordings remain separate references.

## Repository truth and landing

The earlier HOLD source was checkpointed at local commit `2d931b932`, then
merged with GitHub default `a5caac7fd6c8a2e6d09ec740c60fb995c6bef368` at local
commit `f7a5dde44`. Production APIs read: `Modes.Bank.tune/drive/step/energy`,
`advanceUncoupled`, `closeUncoupledCycle`, `Tide.bandLimit`, `Dsp.decimate`,
`Dsp.levelTo`, `Dsp.OnePole.lp`, `Keys.seamError/requireSeam`, `Pitch.detect`,
`Classifier.classify`, `Velocity.atVelocity`, `PadRecipe` and `CairnPatch`.
Local checkpoint identities are provenance; the source ZIP's complete patch
against the named GitHub base and verified restored tree are the recovery path.

This is a **voice of Cairn**. TREMOR already contains bead contacts in a drum
instrument; CISTERN has a droplet field. Neither is a reason to create another
engine here. TUMBLE earns its place within Cairn through the time contraction
of one finite launch, sharing the pitched contact bank and sampler delivery.

The selected direction is a unilateral rebound model, a SPLICE of the contact
mechanics. Compressing spatial grooves was considered: it could tighten a
train, but would need an arbitrary geometry law to overcome slowing motion.
A scheduled grain rain would be cheaper but would weaken the required physical
cause. Rebounds give the clearest gesture with one coordinate and bounded cost.

## The mechanical law

`CairnTumble.kt` owns the dynamics. Height `y >= 0` and vertical velocity `v`
advance under gravity and positive viscous drag:

```
dy/dt = v
dv/dt = -g - beta v
E = mass v²/2 + mass g y
dE/dt = -mass beta v² <= 0

mass = .55 + 1.3 WEIGHT
g = 10 + 8 SLOPE
beta = (.08 + 1.1 FRICTION) / mass
launch speed = (1.05 + .35 SLOPE) velocity / sqrt(mass)
```

The free-flight solution is analytic. A 2 kHz observation step detects a bed
crossing; 40 bisection iterations locate its actual substep time. The step
splits there and advances the rebound through its remaining time. Position
is put on the contact surface, never left penetrating it. Positive flight
loss is independently integrated with three-point Gaussian quadrature of
`mass beta v²`, rather than assigning an energy residual to damping.

At a bed impact:

```
restitution = .69 + .10 GROOVE + .06 BODY - .10 FRICTION
escape speed = .028 + .09 (1 - GROOVE) + .02 FRICTION
outgoing speed = restitution * abs(incoming speed)
contact work = mass (incoming speed² - outgoing speed²) / 2
if outgoing speed < escape speed:
    capture loss = mass outgoing speed² / 2
    enter supported rest
```

Restitution remains between .59 and .85. Even the lossless-flight upper bound
for an infinite rebound train is below 1.888 seconds:
`2 * (1.05 / sqrt(.55)) / (10 * (1 - .85))`. Drag and capture shorten it,
leaving room inside the two-second powered period. Below the escape threshold the soft bed catches the final rebound. Its
remaining kinetic energy is recorded separately as capture loss, rather than
making the final audible impact louder. The event records velocity immediately
after restitution; the trace then records supported rest. Rest is supported by the bed's normal
force. It is a physical exit from the finite cascade, not a reset at a loop
boundary. Gravity only exchanges potential and kinetic energy. Drag and
impacts remove energy. No event receives independent normalization.

All quantities and coefficients above are **normalized shape constants**.
Gravity, drag and restitution are literal reduced equations. SLOPE maps an
imagined chute inclination into effective return acceleration and launch
work; GROOVE maps bed shape into restitution/capture. BODY's return is a
restitution analogy, not another simulated mount coordinate. The isolation
probe removes that `.06 BODY` term. There is no three-dimensional rolling,
elastic contact deformation or audio feedback into the trajectory.

The old five-position diagnostic schema is retained: primary is TUMBLE's
height; the unused four positions are zero. Contact `stone` is always zero
and `groove` identifies the rebound number. No higher bead is launched.

## Acoustic readout and macro contract

```
model, voice, WEIGHT/SLOPE/GROOVE/FRICTION/BODY, velocity, finite/powered
 → analytic free flight + unilateral impacts, observed at 2 kHz [motion LRU]
 → actual time + withdrawn work, one way
 → unit-area raised-cosine contact at 176.4 kHz
 → existing 8-mode bank and house output conditioning
 → finite: contact cue + tail fade
 → HOLD: real preceding segment + continuing cycle + independent seam check
```

The existing fixed `.12 sqrt(contact work)` acoustic calibration is retained.
All rebounds excite the same primary note. TUMBLE's primary ratios are
`[1, 2.71, 5.80]`; its body ratios are `[1, 1.47]`. The other three modes carry
2×, 3× and 4× harmonics of each same-stone impact, with participation
`1.10/.85/.55`; these are not later upper replies. Primary participation
`[1.65, .34, .12]` keeps the root ahead of the two inharmonic colors. No driven mode reaches 12 kHz. Contact pulses
are shorter than ANSWER's; ring time is `.15 + .28 BODY`, multiplied by
`1 + .6 HOLD`. The acoustic readout is not reciprocal collision physics.

| Control | TUMBLE default | Model quantity / required interaction |
| --- | ---: | --- |
| TUNE | .50 | The requested snapped note, C3–C5; reuses motion |
| WEIGHT | .35 | Mass, launch height/time and contact width; interacts with SLOPE |
| SLOPE | .55 | Effective return acceleration and launch work |
| GROOVE | .65 | Restitution and escape threshold; controls the small-contact trail |
| FRICTION | .20 | Positive flight loss, restitution and escape threshold |
| BODY | .30 | Bed return, modal participation and ring time; interacts with FRICTION |
| HOLD | 0 | Finite ring extension below .99; powered 2 s cradle at .99..1 |

## HOLD, compatibility and cost

HOLD supplies an upward momentum impulse every two seconds, a free-running
reference one-bar gesture at 120 BPM. No host-tempo API exists. Height and
velocity continue between periods. A cascade naturally reaches supported
rest before the next impulse; neither coordinate is reset at the boundary.
The budget is initial energy + supplied work = final energy + contact work +
independently integrated drag loss + captured rebound work. Mechanical state and event work/times
must repeat before the existing acoustic periodic-state solver is used.
The real preceding and kept event lists remain separate. TUMBLE renders a real
one-second acoustic lead-in to suppress output-filter startup below its nearly
silent tail. The unchanged older two voices retain their quarter-second path;
the full preceding-cycle reference remains independent. If both actual 256-frame
boundary windows are exactly digital zero, TUMBLE reports zero seam error instead
of the relative metric's undefined 0/0. Any changed nonzero sample still fails;
there is no tolerance floor, fade or copied waveform. KNOCK/ANSWER retain their
existing seam path.

KNOCK/ANSWER's model-1 and model-2 paths stay intact. `MODEL_VERSION=3`
introduces TUMBLE, and only TUMBLE refuses models below 3. Missing model
fields still decode as model 1. Old generator voice lists and model metadata
are pinned to their original scope. No earlier published audition is rebuilt
in place. Newly authored recipes use model 3.

The existing 24-entry motion cache includes the model, voice, five mechanical
controls, velocity, isolated flag and powered regime. Pitch and finite HOLD
reuse motion. No acoustic state or finished PCM is cached. Work is capped at
256 contacts, four collisions per observation step and eight held cycles;
cap or convergence failure is explicit. The interaction target remains tens
of milliseconds, proposed ceiling 50 ms. Timings below must identify the
server run; phone timing is a later gate.

## Verification and reproduction

```
./gradlew --no-daemon :synth:test --tests com.snipsnap.synth.CairnTest --tests com.snipsnap.synth.CairnHoldTest --tests com.snipsnap.synth.CairnTumbleTest --tests com.snipsnap.synth.ModesPeriodicTest
./gradlew --no-daemon :synth:generateCairnTumbleAudition
python3 scripts/prepare-cairn-tumble-site.py --site /absolute/path/to/cairn-site
./gradlew --no-daemon test
```

Optional `-PcairnLegacyHold=/path/to/native/cairn-hold` replays all 55 previous
comparison recipes against their original WAV hashes. Explicit old-model
comparisons retain that model; model-2 clips are also checked through model 3.
The packager checks exact WAV/FLAC PCM, real continuation suffixes and their
seams, old-file hashes, and a complete patch restored into a fresh Git index.

The Kotlin tests compare the first landing against an independent fine-step
RK4 trajectory, check 243 mechanical control points, reject a uniform ratchet
as the gesture, verify work and cache behavior, probe post-contact roots and C3 classification at all 32 binary control corners
with full/softer velocity, and
compare 4× output with an independently rendered 8× reference. HOLD is measured
against real preceding audio, and compared with a full-cycle preroll path.

As built, actual Kotlin renders and independent packaging checks:

- All 26 focused tests pass, including the independent RK4 landing check,
  243 mechanical combinations and 64 C3 endpoint/velocity classifications.
  The cube gives 4–26 impacts; the latest is 1.771301904 s.
- Default TUMBLE has 11 contacts. The first/last inter-contact gaps are
  126.640/9.466 ms. GROOVE 0/.5/1 gives 7/9/16 contacts at the other defaults.
- All 36 held cases settle in three continuing mechanical cycles. Maximum
  Float seam error is zero; maximum relative RMS difference from the full-cycle
  preroll reference is 1.6155e-7. The independent 24-bit PCM seam check is zero.
  The largest mechanical work-budget residual is 8.92e-14.
- Post-contact pitch readings at the two tested BODY/WEIGHT values stay within
  3.38 cents over C3/C4/C5, confidence .773–.959. Default C3/C4/C5 readings are
  10.85/.65/.49 cents. This is not a proof of perceived tuning at every corner.
- Default C5 4×/8× relative RMS differences are 2.42% finite and 2.41% held;
  the dense held test measures 2.23%. These comparisons do not prove zero
  aliasing at every setting. Maximum published raw peak stays below 2.
- All 55 earlier HOLD comparison WAVs regenerate byte for byte, including
  model-2 cases through the model-3 path. All 92 model-1 audition recipes were
  replayed separately: their 276 cued/full/raw WAVs also match byte for byte.
  The idle replay of all 74 new WAVs matches the earlier loaded run exactly.
- The packager preserves 731 earlier Site files, checks all 74 new FLACs
  against their native 24-bit PCM, and verifies every held export as an exact
  suffix of its real continuation. Chromium decodes all 74 files and passes
  playback, eight-cycle scheduling, failure retry, source download and layout
  checks at widths 1440/1024/768/390. No hearing or physical-phone claim.

After the full suite finished, the **idle server run on 2026-11-01** measured
TUMBLE medians of **9.64 ms finite and 31.32 ms HOLD** over 12 warmed renders
with new controls per regime. Motion took .15/.44 ms; audio took 9.48/30.88 ms.
Pitch changes reusing cached motion took 9.32/31.15 ms. First TUMBLE HOLD was
**168.58 ms including initialization**: the proposed 50 ms target is still
missed on the cold path. These are server measurements, not phone acceptance.
The earlier loaded run measured 19.65/89.40 ms with a 394.39 ms cold HOLD while
repository tests were also running; it is retained only as load context.

The complete nine-module JVM run exited successfully with 7,450 tests and no
failures, errors or skips, including all 4,014 synth tests. That run precedes
GitHub base `2f909395feee9962e15fcf60ea0feee0c16f1615`. After merging that base,
the complete shell suite passed again (1,732 tests), followed by all 26 focused
Cairn and periodic-Modes tests. The upstream delta changes shell workflows and
Registers audition assets/generator, with no production DSP changes.

The kit test task owns its temporary directory and uses one fork, keeping its cleanup assertion
independent of concurrent restore tests without disabling or weakening tests.
Android compilation/device tests were unavailable because this environment
has no Android SDK. This change touches no Android or native C++ files.

## Risks and evidence ledger

The main sonic risk is that the denser trail reads as an electronic ratchet.
The three-voice audition, common controls and GROOVE examples are its listening
gate. Very quiet final contacts may be masked by the preceding pitched ring.
Extreme short rings can challenge relative seam readings near silence; the
real continuation and full-preroll comparison must still pass. A pitch reader
is not proof of perceived pitch at every corner. Short finite samples can
fall into drum classes, so actual renders must pass the classifier probe.

| Claim | Status | Evidence |
| --- | --- | --- |
| KNOCK/ANSWER direction is approved | Heard by owner | Positive model-2 audition feedback |
| The cascade follows passive flight and restitution | Verified in Kotlin | `CairnTumble.kt`; independent RK4/work probes pass |
| 243 normalized corners give 4–26 impacts, latest 1.7713 s | Measured in Python and Kotlin | 243-point Kotlin test agrees |
| New TUMBLE is distinct and musically useful | Proposed | Three-voice listening gate; not heard yet |
| Phone/MPC performance, factory presets, app picker | Open | Outside this round |

## Why it is built this way

- A smaller rebound has less height to travel. That makes the phrase tighten
  for a reason you can hear and understand, without a separate rhythm ramp.
- Every rebound stays on one stone and one note. This leaves the higher
  answering stones as ANSWER's defining behavior.
- One work calibration keeps tiny late contacts small. Raising each one to
  the same level would erase the impression of a stone settling.
- TUMBLE owns its contact law and shares the audio stage. It can change its
  gesture without changing the two voices you already liked.
- The two-second motor supplies fresh energy for HOLD. The seam comes from
  the continuing rendered state; browser repetition is only a listening aid.
