# TREMOR — engineering note

TREMOR is a tuned hide drum: one head, up to four coordinated strikers, loose beads on an
internal tray, and a six-string cage with a powered pickup. The sequence is the instrument.
A hit starts in the skin, the beads scatter and come back, the cage rings, the circuit blooms
and faults, and what is left decays.

This note is the as-built record. The constants marked as shape are a first design, not a
measured hide and not a claim about a real circuit. Nothing in the roster has been listened to.
The audition page (`./gradlew :synth:generateTremorAudition`) is the listening gate.

## Round 0 — where it sits

TERRA is the pitched-percussion contract already in the tree: a pad declares a `DrumClass`,
the kit test checks that the classifier agrees, and export leaves the mute group at 0 unless
the class is a hat (`AutoPlace.muteGroupFor`). Hats are the only class that choke. A kick, a
tom, a snare and a perc pad do not.

The melodic contract (file PERC or LOOP, and forbid KICK, SNARE, HAT, CLAP and TOM) would
mean taking the attack off the drum so the classifier stops hearing one. That is the wrong
instrument. TREMOR files whatever the classifier hears:

- HIDE through A#2 (anchor under 123 Hz, FAULT under 0.5) files KICK. C2's centroid measured
  69 Hz against a 65 Hz anchor.
- C3 through F3 file TOM. C3's centroid measured 133 Hz against 131 Hz, lowRatio 0.88,
  decay about 99 ms.
- C4 leaves the bass band and files PERC. Centroid 243 Hz against 262 Hz.
- CHARGED TAIL (CURRENT 0.78, CAGE 0.68 at C3) measures lowRatio 0.53, just under the bass
  gate, and files PERC. The CHARGE default stays above that gate and files TOM.
- BROKEN RETURN is a low note with a dense fault. The centroid leaves the kick rule and the
  pad files TOM.
- HOLD at or above 0.5 is longer than 1.5 s and files LOOP by the classifier's own length rule.

`Tremor.drumClassFor` is that measured map. `TremorTest` and `TremorPresetsTest` compare it
to the classifier on the kit and on every preset. One-shots are cut by 1.40 s so they stay
under the 1.5 s LOOP line. The spec's 2–10 s one-shot budget would file every pad LOOP and
throw away the drum class. The short cut is the integration, written down here rather than
smuggled into the classifier.

Eight macros fit. THUMP's kick already declares eight, and the phone screen renders every
macro it is given. ENSEMBLE stays a macro. TUNE is the house pitch input: MIDI 36 at 0, 48
at 0.5, 60 at 1, C2 to C4. The phone picker is not wired. TERRA, MERCURY, GYRE and MAGNET
are not on that screen either, and this session did not take an Android change.

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
not 0.1. The audio hears the modal banks, the strings and the fuzz. It does not hear a
second click layer on top of the tray. Beads change the tray, and the tray is what is heard.

Output is 4× oversampled, DC-blocked, low-passed, and decimated with `Dsp.decimate`.
Loudness is `Dsp.levelTo` at `MELODIC_LOUDNESS_TARGET`. There is no limiter hiding a
runaway: the internal mix has an explicit rail at ±4, and factory renders record 0 hits.
A HIDE one-shot is about 0.46 s and about 90 ms of runtime on the desktop JVM. Energy
falls by orders of magnitude before the render stops. One-shots may stop early once the
sources are off and the modal-plus-bead energy has been quiet, which is why a default
HIDE is shorter than the 1.40 s cap.

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

Velocity is a number on the render, the way MERCURY's rubbed voices take it. It scales the
strike. It does not move a macro and it is not a low-pass over a finished hit. Full velocity
matches `patch.render()`.

FAULT at CURRENT 0 still loads the strings (the loop gain drops while a fault is on) and
the actuator peak and the circuit work stay 0. A low FAULT waits longer between events
than a high one. The stream that places those events is seeded, per render, from the voice
and the note. There is no shared RNG.

## HOLD

The drive is periodic: micro-strikes quantised to the loop, and at most one fault per loop
at a fixed phase, with no random nudge. The loop length is an integer number of cycles of
the anchor. A passive hold, circuit off, measures a seam under 1e-7.

The actuator is not closed back into the strings on HOLD. That feedback sits on the
strings' own detuned pitches and is not the strike period; with it closed the seam stayed
near 0.4, and with it open the passive seam came back. The fuzz is still in the mix, as a
filter of the pickup, so CURRENT still colours the loop. One-shots do close the actuator.

The join is also a wrap crossfade. The last 1280 output frames blend onto the same stretch
of the preroll, and the last 256 of those are a copy. `Keys.seamError` compares those 256
frames and the bar is 1e-3. Where the two stretches already match, the blend changes
nothing. The exported loop played eight times repeats that same join.

## What is not done

- No one has listened. The presets and the kit are authored against the measurements.
- The phone picker is deferred with the other engines that are not on `SynthScreen`.
- The bead tray is one axis, not a plate.
- The head ratios, the body, the circuit and the coupling gains are shape.
- "Tremor" was not checked as a product name. The preset names avoid the trademark list
  the other rosters use, and they avoid the voice names, which is why the clean head is
  BROAD DRUM and not a name that repeats the voice.
