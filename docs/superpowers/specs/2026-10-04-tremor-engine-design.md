# TREMOR — engineering note

TREMOR is a tuned hide drum: one head, up to four coordinated strikers, loose beads on an
internal tray, and a six-string cage with a powered pickup. The sequence is the instrument.
A hit starts in the skin, the beads scatter and come back, the cage rings, the circuit blooms
and faults, and what is left decays.

This note records the original model 1 and the model 2 integration. The owner
rejected the earlier voice/touch contrast as too similar, then reported
"Tremor has good variation now" for the [R4 voices and touch audition](https://bertcalm-snipsnap-auditions.bertcalm.chatgpt.site/tremor-character/)
on 26 October 2026. That verdict covers the variation demonstrated there, not
individual approval of every factory recipe or phone/MPC playback. Model 2 takes
that R4 profile into versioned recipes. As of 30 October 2026, the integration
and wider coordination correction are locally verified at engine commit
`8c9c01254` on base `b6248f426`, with the editor follow-up at `a36667b3b` also
locally verified. The [factory audition](https://bertcalm-snipsnap-auditions.bertcalm.chatgpt.site/tremor-factory/)
now supplies all thirteen production recipes at three touch strengths; the
owner's factory verdict remains separate from the accepted R4 variation.

Constants marked as shape are musical calibration, not measurements of a real
hide, frame or circuit. The audition generator
(`./gradlew :synth:generateTremorAudition`) supplies the full diagnostic set.

## Round 0 — where it sits

TERRA is the pitched-percussion contract already in the tree: a pad declares a `DrumClass`,
the kit test checks that the classifier agrees, and export leaves the mute group at 0 unless
the class is a hat (`AutoPlace.muteGroupFor`). Hats are the only class that choke. A kick, a
tom, a snare and a perc pad do not.

The melodic contract (file PERC or LOOP, and forbid KICK, SNARE, HAT, CLAP and TOM) would
mean taking the attack off the drum so the classifier stops hearing one. That is the wrong
instrument. `Tremor.drumClassFor` declares an authored drum role independently
of the saved render model. The model 1 calibration at `b17c3bd` normally aligned
with the generic file-import classifier. It established the first three register
cases below, with two explicit factory exceptions:

- HIDE through A#2 (anchor under 123 Hz, FAULT under 0.5) files KICK. C2's centroid measured
  69 Hz against a 65 Hz anchor.
- C3 through F3 file TOM. C3's centroid measured 133 Hz against 131 Hz, lowRatio 0.88,
  decay about 99 ms.
- C4 leaves the bass band and files PERC. Centroid 243 Hz against 262 Hz.
- CHARGED TAIL (CURRENT 0.78, CAGE 0.68 at C3) stays authored PERC. After removal
  of the high feedback tone, the generic classifier hears its calmer tail as TOM.
- SETTLING BED stays authored TOM. Its revised bead contacts make the generic
  classifier hear PERC.
- BROKEN RETURN is a low note with a dense fault. The centroid leaves the kick rule and the
  pad files TOM.
- HOLD at or above 0.5 is longer than 1.5 s and files LOOP by the classifier's own length rule.

`TremorTest` and `TremorPresetsTest` retain that model 1 alignment and its two
exceptions. Model 2's broader profiles change generic import results: FOUR HANDS
reads PERC; LIGHT BED and SETTLING BED read CLAP; DRY CAGE, SOFT FAULT and BROKEN
RETURN read PERC. Those six recipes remain authored TOM. CHARGED TAIL stays
authored PERC and held recipes stay LOOP. The current tests record import results
separately from authored roles; the accepted sound is not retuned to satisfy an
import heuristic. There is no change to generic file-import classification.
One-shots are cut by 1.40 s so they stay
under the 1.5 s LOOP line. The spec's 2–10 s one-shot budget would file every pad LOOP and
throw away the drum class. The short cut is the integration, written down here rather than
smuggled into the classifier.

Eight macros fit. THUMP's kick already declares eight, and the phone screen renders every
macro it is given. ENSEMBLE stays a macro. TUNE is the house pitch input: MIDI 36 at 0, 48
at 0.5, 60 at 1, C2 to C4. The phone picker now exposes TREMOR, all six voices,
macros, defaults, scramble, render, filing and patch construction. `StarterKits`
contains the dry TREMOR kit. Phone listening remains a separate check.

## Architecture

The models share the same head, body, bead tray and six-string cage. In model 1,
voice differences mainly come from the default control row, pickup gain and a
fixed string cents table. Model 2 adds the excitation and radiation profiles
described below, while retaining that instrument structure.

- 12 head modes. Ratios are the circular-membrane Bessel zeros relative to (1,1), which is
  the anchor the player asked for. (0,1) is kept, at 0.12 of the others, so it does not
  become the note.
- 3 body modes and 4 tray modes, on `Modes.Bank`.
- 6 to 24 beads. The count is chosen once, from BEADS. The step is 16 internal samples,
  about 90 µs at the 4× rate. Geometry is one horizontal axis and one vertical: a reduced
  stand-in for a tray, not a 2D scatter. Contacts are an impulse and a position projection,
  in index order, with no sort. Resting contact is not an impact.
- 6 cage strings at 1, 2, 3, 4, 6 and 8 times the anchor, each a `Strings.Loop` with the
  DC block on in both `tune` and the loop. A fixed per-voice cents table detunes them.
- One circuit: a pickup, a load that sags, a short delay, an actuator, and a low-passed fuzz.

The head drives the body and the tray. The tray can drive the head. The head and the tray
drive the bridge. The cage can drive the head back. Tests turn one of those off at a time.
With the head not driving the tray, a full bead bed records 0 contacts. With both cage
drives off, string energy is 0.

Coupling gains are large on purpose. A mode's displacement is velocity over ω, and `drive`
multiplies by the sample period, so a spring between banks is on the order of 1e3 to 1e4,
not 0.1. The audio hears the modal banks, the strings, the fuzz and finite contact
texture. Actual bead impacts excite a seeded noise envelope with a 1.8 ms decay;
the texture is filtered between a 400 Hz low cut and a 4.5 kHz low pass. A modal
tray alone gave little change when BEADS moved, despite the changed contact count.
The added texture makes the modeled impacts available to the audio mix. It is
driven by those impacts, not by an independent click schedule.

The revised circuit converts pickup velocity to displacement scale before the
nonlinear amplifier. The one-shot actuator return is 0.0004 instead of the original
0.04. These changes remove the high-frequency feedback limit-cycle; CURRENT still
changes the amplified cage pickup and its fuzz. Voice-dependent cage radiation
and a longer head decay retain the struck instrument after that correction.

Output is 4× oversampled, DC-blocked, low-passed, and decimated with `Dsp.decimate`.
Loudness is `Dsp.levelTo` at `MELODIC_LOUDNESS_TARGET`. The internal mix has an
explicit rail at ±4, and the factory checks require 0 rail hits; the rail itself
is not evidence of feedback stability. One-shots may stop early once the sources
are off, modal-plus-bead energy is quiet, and the audible mixed return has settled. The cage now
participates in that stopping check, so it cannot be discarded merely because
the head and beads have decayed. A final 6 ms fade closes a capped one-shot.

## The controls

| Control | What it does | At 0 |
| --- | --- | --- |
| TUNE | The anchor, C2 to C4 | C2 |
| STRIKE | Hardness of the blow, on the one-shot and on the held strikes | A slower, duller blow, still a blow |
| ENSEMBLE | Crossfade of four fixed strikers. Weights' squares sum to 1 | One striker, the whole budget |
| BEADS | How many beads, and how lively | Six beads, not a disconnected layer |
| CAGE | How much of the cage is heard, and how long it rings | A floor of 0.12, not silence |
| CURRENT | The powered path | Actuator and fuzz are exactly 0. Strings stay passive |
| FAULT | Load faults. The gap is the density | No faults |
| HOLD | At or above 0.5, the sustained loop | A one-shot |

Velocity is a number on the render, the way MERCURY's rubbed voices take it. It
changes the strike's impulse, contact footprint and duration. It does not move a
stored macro or apply a low-pass over a finished hit. Full velocity matches
`patch.render()`.

FAULT at CURRENT 0 still loads the strings (the loop gain drops while a fault is on) and
the actuator peak and the circuit work stay 0. A low FAULT waits longer between events
than a high one. The stream that places those events is seeded, per render, from the voice
and the note. There is no shared RNG.

`circuitWork` is a signed diagnostic sum, `actuator * stringVelocity / rate`,
not supply consumption or an unsigned activity count. At CURRENT 1 and FAULT 0,
model 2 WIRE recorded approximately -2.043e-7, -6.577e-6 and -3.964e-5 for CAGE
0, 0.5 and 1 respectively, while the actuator remained active. A negative total
does not establish a disconnected powered path, and a positive total would not
prove passivity. These phase-dependent readings are not a complete energy ledger
for the head, cage and external sources.

## Model 1 velocity follow-up, 23 October 2026

The model 1 impulse law is retained: `0.55 * (0.28 + 0.72 * velocity) *
(0.62 + 0.38 * STRIKE)`. Gain levelling largely removed that impulse difference
from the exported timbre. A softer event now also has a broader contact footprint
and a longer force pulse, so fewer upper modes are excited even after the sample
is levelled.

The modal participation uses an effective contact hardness of
`STRIKE * (0.35 + 0.65 * velocity)`. The existing force pulse, including its seeded
grit, is generated first and then stretched in time. Its sum is preserved, so the
stretch changes contact duration without changing the event's specified impulse.
At the soft end the maximum extra duration is 1.5 times the original duration,
with a pitch-dependent cap to keep a newly stretched pulse within 90% of one note
period. A pulse already broader than that cap is left at its existing duration.
This avoids creating a force-spectrum notch across the requested root.

The same law shapes initial strikes and held micro-strikes. At velocity 1, the
original footprint and pulse are returned exactly. Drawing the pulse's grit
before stretching also preserves later strikers' random delay and jitter across
touch strengths. The constants are musical contact shapes, not measurements of
a real hide or mallet.

Standalone Kotlin renders analysed with a Python FFT measured the C3 power above
200 Hz as follows. These numbers describe the rendered spectrum, not perceived
brightness or a listening verdict:

| Voice | Soft, velocity 0.2 | Middle, velocity 0.55 | Hard, velocity 1 |
| --- | ---: | ---: | ---: |
| HIDE | 0.03633% | 0.11232% | 0.33908% |
| WIRE | 0.56328% | 1.78997% | 5.02104% |

All six existing one-shot and six held hard-touch sample hashes matched the
pre-articulation version in the standalone comparison. `TremorVelocityTest`
retains these model 1 regressions for saved recipes. The relative spectral
increases did not establish useful musical contrast: the owner subsequently
reported that the voices and soft-to-hard hits still sounded the same. That
listening verdict led to R4 rather than treating the small numerical differences
as acceptance.

## Model 2: the accepted R4 variation, 26–30 October 2026

The heard prototype source is identified by SHA-256
`b6f95b8e22bfcaa14416f8a8a894bb36d0bc2c8fbec4d21d2e0e5120120c105a`.
Its listening set is six voices at C3, each at velocity 0.2, 0.55 and 1, with
matched loudness. Model 2 integration pins all eighteen raw float buffers so
changes outside that set cannot silently replace the variation the owner heard.

The former output was dominated by the shared head anchor. Upper head radiation
tapered with frequency, displacement added another frequency loss, and cage
weights attenuated both excitation and pickup. Levelling then removed much of
the force difference. Model 2 changes the sources and their audible readout:

- The already-generated contact pulse is resampled from broad soft contact to
  shorter hard contact, preserving its summed impulse and the RNG draw count.
  Its new duration is capped at 0.82 note periods. Contact hardness also spans a
  wider modal footprint. Hard touch intentionally changes from model 1.
- Separate head and cage pickups expose existing upper motion. The mechanical
  displacement pickups still drive the couplings. Voice-specific radiation
  balances favour head/body for HIDE, coordinated strikers for UNISON, actual
  contacts for ROLL, passive cage for WIRE, driven pickup for CHARGE, and loaded
  interruptions for FRACTURE. These are design intentions backed by the R4
  variation verdict, not claims that every factory position was heard.
- A small share of each actual initial or held strike reaches the existing
  string bank through the frame. This input is zero between contacts and uses
  no additional event clock or oscillator. It supplies contact bandwidth that
  the displacement-only bridge had removed. It is a second externally driven
  port: preserving the force pulse's impulse does not prove conservation of
  combined head/cage energy.
- The audible powered pickup has bounded saturation and a root-rejecting
  readout filter. Its level budget follows the actual anchor mode's
  phase-independent motion, so a saturated upper partial cannot simply replace
  the requested note. These readout stages do not enter the actuator return.
  The one-shot return remains 0.0004; HOLD still opens it. FRACTURE makes the
  existing smoothed load interruptions more exposed in the audible return.

`Tremor.kt` dispatches by saved model. `TremorV1.kt` freezes the previous signal
path; `TremorV2.kt` contains the new profile. New `TremorPatch` constructors,
factory recipes and starter patches select model 2. JSON with an omitted or
null `model` selects model 1. Explicit model values round-trip, and native
velocity rendering passes the saved model through. The editor retains typed
recipes through edits, drafts, saving and sending; old macro-only work selects
model 1, while an explicit current factory choice selects model 2.

The editor follow-up at `a36667b3b` materializes a typed Tremor draft only for a
voice the player visits, before persisting that voice's macro values. Opening
another engine no longer creates six unsolicited Tremor drafts. A newly visited
voice therefore saves its model 2 identity before its macro bag can be mistaken
for legacy work on recreation. The Compose coverage includes opening/recreating
THUMP with no Tremor drafts and visiting exactly WIRE and ROLL with their typed
model 2 recipes surviving recreation. Pending named-recipe restoration remains
authoritative. This follow-up changes editor persistence, not DSP or audition
audio.

The R4 prototype's ninety-case Kotlin harness recorded no raw rail hits. Its
54 ordinary voice/note/touch cases had a worst open spectral-peak offset of
16.43 cents; the eighteen C3 listening cases had a worst offset of 6.64 cents.
Those prototype measurements do not replace the production test gate. The
prototype also exposed anchor cancellation in crowded UNISON gestures: the
1.4406× head mode could dominate when opposing strikers cancelled the anchor.

The coordination correction projects the actual prepared finite force kernels
onto the damped head modes, including their displacement and audible radiation
over a 370 ms Hann-weighted window. If the anchor already wins, the sampled
timing is returned exactly. Otherwise a local gradient solve adjusts contact
delays, with at most 48 steps and ten backtracking trials per step. Each step is
limited to 5% of a root period; total movement is bounded to half a root period
either side of the original delay, with causal nonnegative starts. Initial
contacts in both finite and held renders also have an explicit 12 ms ceiling;
recurring micro-contacts retain their half-period timing bound. Inactive contacts
are skipped, and gradients pointing out of the permitted interval are projected
out at its boundaries. Force pulses,
participation vectors, gains and RNG consumption are unchanged. Retiming changes
mechanical work, so unchanged impulses do not establish energy conservation.
This linear head projection is a planning shape, not a universal pitch proof for
the coupled nonlinear instrument; mode switches and integer-sample rounding also
make the solve piecewise rather than a proof of continuous parameter response.

The standalone corrected source has SHA-256
`a29a7a757629b52be75f1444dd489dd3a1fba4e5e20107e16dcf62273dedde7e`.
Its helper is now ported into `TremorV2.kt`. The corrected source recorded the
following open global spectral-peak checks, all with zero raw rail hits:

| Standalone set | Cases | Peaks beyond 25 cents | Worst absolute offset |
| --- | ---: | ---: | ---: |
| Diagnostic matrix | 90 | 0 | 16.4274 cents |
| UNISON finite grid | 972 | 0 | 5.5998 cents |
| Held corner set | 36 | 0 | 15.6668 cents |

All eighteen heard C3 float buffers remain exactly identical to the approved
prototype. Six other buffers in the ninety-case matrix change. Across these
measured sets the largest initial delay is 11.9218 ms, the largest initial
correction is 0.7937 ms, and the largest recurring-contact correction is 0.7313 ms;
the tested initial groups remain within the 12 ms unison window.

Independent review found an initial-window violation outside those grids:
CHARGE at MIDI 40, STRIKE 0.879606, ENSEMBLE 0.44305366 and velocity 1 moved a
prepared contact from 11.780 ms to 12.851 ms without the explicit ceiling. The
new window guard fails against that uncapped counterfactual (test process exit
1). With the ceiling, the reproduction's largest initial offset is 1902 internal
samples, about 10.782 ms; targeted finite/held root checks stay within 13.99
cents. The cap leaves all ninety matrix buffers exactly unchanged from the
previous corrected source, and both the 972-case and 36-case CSV outputs are
byte-identical. The eighteen approved buffers therefore remain exact.

`TremorCharacterTest` covers the heard buffers, absolute upper-band contrast,
register anchors and coordination corners. A production counterfactual with the
correction removed failed as intended (test process exit 1): UNISON at TUNE 0,
ENSEMBLE 0.8 and STRIKE 0.9 promoted a peak 641.914 cents from the root. The final
capped production source passed all 48 focused tests and the full nine-module
JVM suite; the local verification ledger below records their scope.

## HOLD

Model 2 first plans the actual recurring micro-contacts. It then solves the main
strike once while keeping that recurring timing fixed, checking both the main
strike alone and the combined attack. This prevents a recurring contact from
masking cancellation of the opening strike in the planning projection.

The scheduled drive is periodic: micro-strikes quantised to the loop, and at most
one fault per loop at a fixed phase, with no random nudge. The loop length is an
integer number of cycles of the anchor. This does not establish that every
simulation state is periodic. In particular, the contact noise stream advances
continuously rather than restarting each period.

The actuator is not closed back into the strings on HOLD. That feedback sits on the
strings' own detuned pitches and is not the strike period. The original comparison
measured a seam near 0.4 with it closed, and below 1e-7 for the passive case with it
open. Those are historical development measurements, not proof of convergence for
the current complete mix. The fuzz is still in the mix, as a
filter of the pickup, so CURRENT still colours the loop. One-shots do close the actuator.

The join is also a wrap crossfade. The last 1280 output frames blend onto the same stretch
of the preroll, and the last 256 of those are a copy. `Keys.seamError` compares those 256
frames and the bar is 1e-3. Because those frames are copied, this metric verifies
the constructed join, not natural periodic convergence of the drum, beads,
strings and noise. Where the two stretches already match, the blend changes
nothing. The exported loop played eight times repeats that same join; clicks and
cyclic changes still require listening.

## Production factory audition, 30 October 2026

The owner-private [factory audition](https://bertcalm-snipsnap-auditions.bertcalm.chatgpt.site/tremor-factory/)
is published as Site v12 from production engine commit `8c9c01254`. Its 39 clips
cover all thirteen actual factory recipes at their authored notes and velocities
0.2, 0.55 and 1. Every native `Velocity` buffer equals the direct model 2 render;
all thirteen middle-touch renders repeat deterministically. There are zero raw
rail hits, with a maximum raw peak of 0.98554003. The accepted R4 audition remains
unchanged.

An independent audit of the published PCM16 files verifies all 39 WAV hashes and
recipe MIDI values against the manifest. All 39 attack regions and six held-loop
regions have global spectral peaks within 25 cents of the requested note. The
largest attack offset is BROKEN RETURN at middle touch, G2, +9.5508 cents; the
largest held offset is HELD BLOOM at hard touch, A2, +6.4928 cents. Browser checks
confirm all 39 clip mappings and decodes, six correct held regions, notes and
verdict export, and mobile/desktop layouts. These checks establish provenance,
tuning and page operation, not individual musical approval of the recipes.

## What is not done

- The owner accepted the variation demonstrated by R4. Individual approval of
  all thirteen current factory recipes remains pending on the published page.
- Physical phone/MPC listening and measured phone render cost remain pending.
- The bead tray is one axis, not a plate.
- The head ratios, the body, the circuit and the coupling gains are shape.
- "Tremor" was not checked as a product name. The preset names avoid the trademark list
  the other rosters use, and they avoid the voice names, which is why the clean head is
  BROAD DRUM and not a name that repeats the voice.

## Verification ledger, 23 October 2026

- **Verified by source:** impact-driven contact texture, displacement-scaled
  pickup, reduced actuator return, explicit factory filing exceptions and the
  phone/editor/starter wiring described above. Sources are `Tremor.kt`,
  `TremorPresetsTest.kt`, `SynthScreen.kt` and `StarterKits.kt` at `b17c3bd`.
- **Measured previously:** the register numbers in Round 0 and the historical
  passive/closed-feedback seam comparison are retained as development evidence.
  This documentation correction did not rerun those measurements.
- **Measured for the follow-up:** the standalone Kotlin-render/Python-FFT contact
  comparison and the twelve hard-touch hash comparisons are reported in the
  velocity section. They do not establish an acoustic model of real hide.
- **Disclosed:** HOLD opens the feedback return and constructs its playback seam;
  one-shots remain limited to 1.40 s. Neither is a claim about an indefinitely
  continuing physical drum.
- **Heard at this stage:** the owner's first verdict supplied the squeal and
  short-body finding. The later model 1 articulation remained insufficient;
  the model 2 section records the subsequent R4 variation acceptance. The
  assistant did not listen.

## Model 2 integration ledger, 30 October 2026

- **Verified by source at `8c9c01254`, tested on base `b6248f426`:** separate
  frozen/current renderers, saved model decoding and encoding, native velocity
  dispatch, broader contact shaping, independent audible pickups, actual-force
  frame input, an anchor-referenced powered readout budget and prepared-force
  timing coordination, including recurring/main held-contact planning and the
  explicit 12 ms initial-contact ceiling.
- **Editor follow-up at `a36667b3b`:** only visited Tremor voices materialize a
  typed draft before macro persistence. Seven Compose methods cover model
  retention, legacy migration and restoration, including unrelated-engine and
  visited-default regressions. Factory audio remains sourced from the unchanged
  engine at `8c9c01254`.
- **Contract findings:** legacy classifier alignment remains model-specific;
  model 2's import classifications and the unchanged authored factory roles are
  recorded separately. WIRE's signed `circuitWork` can be negative with an active
  actuator, as reported above. Signed work and actuator activity do not provide
  a passive-energy proof.
- **Measured in the corrected standalone source:** ninety diagnostic cases,
  972 finite UNISON cases and 36 held corners had no open global peaks beyond
  25 cents, and all eighteen approved float buffers remained exact. Source
  identity, offsets and timing bounds are recorded above. The production
  regressions fail with coordination removed or its initial-window cap removed.
- **Local JVM verification, 30 October:** the final capped source passed 48
  focused Tremor tests (exit 0, 3 min 11 s), including the eighteen approved
  float-buffer goldens, dense UNISON and held ROLL guards, saved-model/native
  velocity compatibility, legacy velocity and authored/import filing contracts.
  The complete nine-module JVM suite passed 7,174 tests with zero failures,
  errors or skips (exit 0, 1 h 26 min 59 s).
- **Post-editor local JVM recertification, 30 October:** the full nine-module
  command also passed after `a36667b3b` (exit 0, 10 min 58 s; 41 tasks up to date,
  one executed). Shell's 1,606 tests reran because its inputs include app sources;
  the other eight modules reused their up-to-date results. Current reports still
  total 7,174 tests with zero failures, errors or skips.
- **Latest local Android verification, 30 October:** at editor `a36667b3b`,
  `:app:assembleDebug`, `:app:assembleStrikeFx` and
  `:app:compileDebugAndroidTestKotlin` passed together (exit 0, 2 min 41 s).
  The final corrected test-fixture source then passed a separate
  `:app:compileDebugAndroidTestKotlin` run (exit 0, 23 s).
  Device/emulator tests were not executed locally. The PR emulator check supplies
  that separate runtime verification; its status belongs to the PR's CI record.
- **Published factory verification:** Site v12 exposes the 39 production clips
  with native/direct identity, repeatability, manifest and browser checks, plus
  independent attack/held tuning checks reported above. The owner's factory
  listening verdict remains pending.
- **Disclosed:** the frame connection adds external input; the powered readout
  budget is not a proof of passive feedback. The current HOLD uses periodic
  external micro-strikes, an open actuator return and a constructed
  crossfade/copy join. The projection is a bounded numerical planning rule,
  not an energy or universal tuning proof.
  Physical phone/MPC playback and measured phone render cost remain pending.
- **Heard:** the owner rejected the earlier contrast and then accepted R4's
  variation with "Tremor has good variation now." No broader preset or hardware
  approval is inferred.
