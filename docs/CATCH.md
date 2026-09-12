# CATCH A HIT: the pad grid is the chopper

**Status: round one built.** The first of the four performed-chop ideas
(CATCH A HIT, HUM THE CHOP, GHOST CHOP, THE ZOOM LADDER) to land after
GHOST CHOP, and the one that tests their shared claim: that *performing*
a chop beats *configuring* one. It is the MPC's own chop workflow, the
one people learn by feel, and it makes choosing which hits to keep a
matter of taste rather than a count.

## 1. What was wrong

Every chop in the app is a configuration: a count, an ear, a grid, a
review of rows. All of it is right for a break you want in full. None
of it is how a player picks *the* kick and *that* snare out of a two
minute tape: by ear, in time, while it plays. The deck already had the
frame clock, the pad grid already existed as one composable, the assign
door already took a snip and a provenance tag. What was missing was
the gesture.

## 2. In the hand

On TAPE, under the EDIT rows, a full-width button: **CATCH A HIT**. Tap
it and the deck loops — the selection when there is one, else the whole
tape — and the pad grid comes up where the transport and destination
rows were, both banks, drawn as PLAY draws them. Over it, one line:
`HOLD A PAD AS THE HIT GOES BY. LET GO, IT'S CAUGHT.` and beside that a
▶/■ and DONE.

- **Hold A01 as the kick goes by.** It lands on A01 with a flash, named
  by ear (`KICK 01`), and the band it took shows on the waveform inside
  the selection. Hold A02 for the snare. Every landing is a real pad
  write: leave TAPE mid-catch and what landed is there.
- **Tap, and you get the whole hit.** A tap (under 100 ms) takes the
  hit as INSTANT KIT would cut it: to the next hit, or the end of the
  loop. **Hold, and the cut ends where you let go** — on a zero
  crossing, or at the next hit, or at the end of the loop, whichever
  comes first.
- **Miss one, hold the pad again on the next pass** and it replaces
  what it caught. A pad that had a sound *before* CATCH began is never
  replaced: the press refuses, and says so (`A05 IS SNARE 02 ALREADY.
  CATCH ONTO AN EMPTY PAD INSTEAD.`).
- **Between hits**, a hold takes the tape from the press to the lift
  (`NO HIT WENT BY. A07 TOOK THE TAPE YOU HELD.`), a tap takes nothing
  (`NOTHING WENT BY. HOLD THE PAD AS THE HIT PASSES.`).
- **DONE** puts the deck back and, with anything caught, opens KIT on
  the bank the first catch landed on, the way ONTO does after a chop:
  `3 HITS CAUGHT ONTO BREAK 1 KIT. GO PLAY THEM.`

No kit open refuses at the button: `OPEN A KIT FIRST. CATCH LANDS ON
ITS PADS.` A tape with no hits refuses too, and names the other doors.
CATCH is not offered while a RE-TRIM is live: that request owns the
deck.

## 3. Underneath (`shell/Catch.kt`, `CatchModel`)

Pure: frames in, cuts out. The screen owns the clock, the landing goes
through the door every capture uses.

- **A press is always late.** Reaction time is 80 to 150 ms on a good
  day, so the cut never starts where the finger landed. It starts at
  the strongest hit whose cut begins inside the window from
  `LOOK_BACK_SEC` (150 ms) before the press to `LOOK_AHEAD_SEC` (30 ms)
  after it — the look-ahead for a finger that knows the loop and jumps
  the gun. Two hits inside the window and the louder one is meant
  (`Onset.strength`); a tie goes to the nearer. Only hits inside the
  loop count: nothing outside it went by.
- **The hits are INSTANT KIT's.** `CatchModel.hitsOf` is
  `Chopper.byTransients` with the AUTO cap of 64, not the 16 a chop
  lands: a catch picks by ear, and the quiet hits are often the ones
  wanted. Only the ranges and strengths are kept.
- **Where the press lands on the tape.** The deck model steps on
  Compose's frame clock; TAPE keeps the last tick's nanoseconds, and a
  touch's own `uptimeMillis` (the same `CLOCK_MONOTONIC` — PadGrid's
  KDoc, verified for GROOVE's live record) places the press at
  `position + (touch − tick) · rate`, not at the next frame. Hardware
  output latency is not corrected here; see §6.
- **The loop is the voice's own.** `TapeVoice.start(frame, loop)` wraps
  its cursor at OUT, gapless, with no thread or track respawned per
  pass. The deck model's `loopPreview` wraps in step with it and now
  reports every wrap (`Event.Looped`) so a screen can act on it; TAPE
  has nothing to restart. The whole-tape loop ends two frames early:
  the deck's own end clamp fires HitEnd there, and OUT must sit before
  it for the wrap to win.
- **Tap or hold.** `TAP_SEC` (100 ms) separates them. A hold shorter
  than `MIN_SEC` (20 ms) of audio is a tap that took a while, and takes
  the whole hit; nothing shorter than 20 ms ever lands. A release
  before its press (the loop wrapped under the finger) runs to the end
  of the loop.
- **Landing** (`CatchModel.land`): `Retrim.cut` of the range — the
  same clamp, DC removal and click-guard fades INSTANT KIT's slice
  gets, no trim, no normalise — classed by ear, named by class, through
  `KitBuilderModel.assign`, tagged with RE-TRIM's three keys plus
  `origin=catch`. A caught pad opens on TAPE at its cut like any chopped
  pad. The tape is tagged only when it is a snip on the SNIPS shelf; a
  kit sample TAPE fell back to gets the origin alone (`TapeRef.ofSnip`'s
  rule).
- **App owns the write** (`App.catchOnto`): one short write per catch
  under `KitWrites.mutex`, the kit re-read into `open`, the toast
  naming what landed. No busy line: the pad's name lighting on the grid
  is the signal.

## 4. What it says

- `OPEN A KIT FIRST. CATCH LANDS ON ITS PADS.`
- `LISTENING FOR HITS…` (the button, while the hits are found)
- `NO HITS ON THIS TAPE TO CATCH. KEEP, OR INSTANT KIT, INSTEAD.`
- `HOLD A PAD AS THE HIT GOES BY. LET GO, IT'S CAUGHT.`
- `KICK 01 CAUGHT ONTO A01.` · `NO HIT WENT BY. A07 TOOK THE TAPE YOU HELD.`
- `NOTHING WENT BY. HOLD THE PAD AS THE HIT PASSES.`
- `A05 IS SNARE 02 ALREADY. CATCH ONTO AN EMPTY PAD INSTEAD.`
- `3 HITS CAUGHT ONTO BREAK 1 KIT. GO PLAY THEM.` · `NOTHING CAUGHT. THE KIT IS AS IT WAS.`
- `THAT CATCH DIDN'T LAND. THE PAD IS AS IT WAS.`
- HELP: `· CATCH ON TAPE: HOLD A PAD AS THE HIT GOES BY. IT LANDS THERE.`

## 5. Laws the tests hold (`CatchTest`, `TapeDeckTest`)

- On a break of kick, hat, snare and open hat, `hitsOf` hears four; a
  tap 100 ms after the kick catches exactly INSTANT KIT's cut of it,
  and `Retrim.cut` of that range is the range's length.
- A hold ends on the zero crossing before the lift; a hold through the
  hat ends where the hat starts, and is still the kick's.
- Between hits: `hitFor` is null, a tap lands nothing, a 120 ms hold
  takes zero crossing to zero crossing, a hold across the snare stops
  where the snare starts.
- Two hits in the window: the stronger; a tie: the nearer; past 150 ms
  back or 30 ms ahead: not meant; a hit outside the loop never counts,
  and the only hit inside it does.
- A loop bounds every cut: a hold across the wrap runs to OUT, a tapped
  hat whose own cut would run past OUT ends at OUT.
- A second pass replaces the first; a slot taken before CATCH began
  refuses at the press and lands nothing on release; no press, no
  catch; the landing carries the class, `tapeFile`/`tapeIn`/`tapeOut`
  and `origin=catch`, and the pad's WAV is exactly the range long.
- The deck's loop preview reports a `Looped` event on every wrap, and
  none once the loop is off.

## 6. What the phone should judge

- **The look-back.** 150 ms is generous reaction time; if a hit two
  hits back keeps winning on a busy break, it wants 120. If catches
  keep landing on the hit *before* the one heard, the phone's output
  latency is adding to the reaction and wants subtracting (the deck's
  position leads the speaker by the AudioTrack buffer). One constant,
  `LOOK_BACK_SEC`, and a latency constant if the second case shows.
- **Tap versus hold.** 100 ms. A thumb on a phone taps in 60 to 90 ms;
  if whole hits keep landing where a short cut was meant, raise it.
- **The round-two ideas the spec named**, once round one has been felt:
  showing the caught cut on the waveform for a beat *before* it commits;
  a press that anticipates by more than 30 ms; and HUM THE CHOP, which
  inherits this landing code.
