# TREMOR — engineering note

TREMOR is a tuned hide drum: one head, up to four coordinated strikers, loose beads on an
internal tray, and a six-string cage with a powered pickup. The sequence is the instrument.
A hit starts in the skin, the beads scatter and come back, the cage rings, the circuit blooms
and faults, and what is left decays.

This note is the as-built record. The integration and listening corrections were
checked on 23 October 2026 against `b17c3bd2010c04e1ce08909a9c647bde1ca66907`;
the velocity follow-up below records the subsequent implementation. The constants marked as shape are
musical calibration, not a measured hide or a claim about a real circuit. The owner
heard the first audition and reported a damped drum with an unwanted high squeal;
the revised voice still awaits an acceptance verdict. The audition generator
(`./gradlew :synth:generateTremorAudition`) supplies the full diagnostic set.

## Round 0 — where it sits

TERRA is the pitched-percussion contract already in the tree: a pad declares a `DrumClass`,
the kit test checks that the classifier agrees, and export leaves the mute group at 0 unless
the class is a hat (`AutoPlace.muteGroupFor`). Hats are the only class that choke. A kick, a
tom, a snare and a perc pad do not.

The melodic contract (file PERC or LOOP, and forbid KICK, SNARE, HAT, CLAP and TOM) would
mean taking the attack off the drum so the classifier stops hearing one. That is the wrong
instrument. `Tremor.drumClassFor` declares a drum role, normally aligned with the
generic file-import classifier. The original calibration established the first
three register cases below; two factory roles are now explicit exceptions:

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

`TremorTest` and `TremorPresetsTest` compare the other factory renders with the
classifier and pin the two exceptions to their authored roles. There is no change
to generic file-import classification. One-shots are cut by 1.40 s so they stay
under the 1.5 s LOOP line. The spec's 2–10 s one-shot budget would file every pad LOOP and
throw away the drum class. The short cut is the integration, written down here rather than
smuggled into the classifier.

Eight macros fit. THUMP's kick already declares eight, and the phone screen renders every
macro it is given. ENSEMBLE stays a macro. TUNE is the house pitch input: MIDI 36 at 0, 48
at 0.5, 60 at 1, C2 to C4. The phone picker now exposes TREMOR, all six voices,
macros, defaults, scramble, render, filing and patch construction. `StarterKits`
contains the dry TREMOR kit. Phone listening remains a separate check.

## Architecture

One voice is one architecture. The differences are the default row and a fixed cents table
on the strings.

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

## Velocity articulation follow-up, 23 October 2026

The old impulse law is retained: `0.55 * (0.28 + 0.72 * velocity) *
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
covers spectral articulation, the upper-register root, ensemble timing, soft
held determinism/join and those hard-touch hashes. The softer contacts remain a
listening candidate.

## HOLD

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

## What is not done

- The owner has heard the original voice. Acceptance of the revised voice and its
  factory presets remains pending; measurements are not a replacement for that verdict.
- The phone picker is wired. A phone and MPC listening check is still pending.
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
- **Heard:** the owner's first verdict supplied the squeal and short-body finding.
  The latest revision is a listening candidate. The assistant did not listen.
