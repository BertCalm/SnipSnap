# MIDI sync: where the timeline lives

Written 2026-09-12, against the September decision that SnipSnap is **an
instrument, synced** — live MIDI in scope *including clock and transport
sync*, not just note input (`docs/SPECS_2026_09.md` §2).

That decision unblocked this design pass. It does not unblock an
implementation, and this document is the reason why.

---

## A correction, first

`docs/SPECS_2026_09.md` §2 says, and I repeated it twice in conversation:

> Sync means the audio engine stops owning the timeline. `OrbitClock`,
> GROOVE's transport and the native sink currently assume the app is
> master; under external clock they follow instead.

**That is wrong in a way that matters.** It describes a single timeline
being handed from one owner to another. There is no single timeline. The
app runs **four separate notions of "now"**, none of which is the
hardware's, and none of which is exposed as a clock another component
could follow.

That is the precise claim, and it is narrower than "unreadable". GROOVE's
position *is* readable — it is Compose state, and the needle and the
recorder both read it. What it is not is a transport: nothing outside
that composable can ask it what time it is, and it is derived from the
display rather than from audio.

The work is not transferring ownership. It is **creating one clock where
there are currently four**, and that is a larger and more interesting
problem than the spec described.

Every claim below prints its own check, because this document exists to
be re-run rather than believed — the same rule `SPECS_2026_09.md` opens
with, and which that document then broke twice.

---

## What time it is, according to whom

| transport | "now" is | held in | exposed as |
|---|---|---|---|
| ORBIT — `OrbitEngine` | frames **written to the sink** | `AtomicLong frame` | `position()`, a render cursor |
| Loop grid — `LoopEngine` | intervals **written to the sink** | `AtomicInteger interval` | `position()`, one interval's resolution |
| GROOVE | **wall time** since play started | `withFrameNanos` loop | Compose state, inside one composable |
| PLAY / pads — `PadEngine` → `NativePads` | nothing at all | — | **nothing** |

Check each:

```
grep -n "AtomicLong\|fun position" loop/src/main/kotlin/com/snipsnap/loop/OrbitEngine.kt
grep -n "AtomicInteger\|fun position" loop/src/main/kotlin/com/snipsnap/loop/LoopEngine.kt
grep -n "withFrameNanos" app/src/main/kotlin/com/snipsnap/app/ui/GrooveScreen.kt
grep -n "external fun" app/src/main/kotlin/com/snipsnap/app/NativePads.kt
```

Three facts follow, and each is worse than the table looks.

### GROOVE is clocked off the display, not the audio

The screen a user would most want locked to external gear is the one
whose transport is a `withFrameNanos` loop converting **elapsed wall
time** to elapsed steps. Its own comment says so, and names the
dt-clamp it needs so a stale frame gap (backgrounding, a debugger pause)
does not fast-forward the needle.

Notes then fire **immediately** down `GrooveScreen.hit` → `PadEngine.hit`
→ `NativePads.noteOn` — no timestamp, no scheduling ahead, no lookahead
buffer. (`PadEngine` has no `noteOn` of its own; an earlier draft of this
document named one that does not exist.)

```
grep -n "fun hit(" -A 6 app/src/main/kotlin/com/snipsnap/app/ui/GrooveScreen.kt
grep -n "fun hit\|fun noteOn" app/src/main/kotlin/com/snipsnap/app/PadEngine.kt
```

So GROOVE's timing accuracy is bounded by the display's refresh
(~16.7 ms at 60 Hz) plus whatever the audio path adds. That is fine for
a needle you watch. It is not a foundation for clock sync, where the
error budget is closer to a millisecond.

### No *transport* reads the hardware's playback position — but two voices do

An earlier draft of this section said "nothing reads the hardware's
playback position", on the strength of a `grep -n "fun "` over
`AndroidAudioSink` — a check that lists declarations and so could not
have found a call inside a body even if one existed. It was the wrong
claim proved the wrong way, in the document that opens by insisting on
printed checks. Both halves are corrected here.

**What is true:** `AndroidAudioSink` — the sink ORBIT and the loop grid
write through — has no position accessor, so those two engines' counters
are frames *written*, not frames *heard*.

```
grep -n "playbackHeadPosition\|AudioTimestamp" app/src/main/kotlin/com/snipsnap/app/AndroidAudioSink.kt
```

That one prints **nothing**, and nothing is the answer — it is the only
check here whose empty output is the finding. It searches the API names
themselves rather than listing declarations, so unlike the draft it
replaces, a call buried in a function body could not hide from it.

**What is also true, and more useful:** the app already reads
`AudioTrack.playbackHeadPosition` in two places —

```
grep -rn "playbackHeadPosition" --include=*.kt app/src/main
```

`MixVoice.kt:125` and `TapeVoice.kt:253` both use it to know when a
one-shot has actually drained, each falling back to `framesWritten`
inside a `runCatching`. So the API is in the codebase, and the fallbacks
say something about how much it is trusted.

**And `TapeVoice` has already paid for that knowledge**, which is the
part a sync design must not rediscover the hard way. Its KDoc records a
measured on-device finding: some HALs report `playbackHeadPosition == 0`
for **800 ms** while `playState == PLAYING`, when a buffer is not filled
to capacity.

```
sed -n 310,325p app/src/main/kotlin/com/snipsnap/app/TapeVoice.kt
```

A clock built naively on that number would sit at zero for most of a bar
on those devices. Whatever reads hardware position for timing needs the
same defensive shape those two voices already use, and the bench item
below exists to find out how widespread the quirk is.

### Latency is measured and then only printed

`NativePads.latencyMillis` and `NativeSurface.latencyMillis` exist, and
every consumer feeds a **display string**:

```
grep -rn "latencyMillis" --include=*.kt app/src/main | grep -v Native
```

`StreamFacts.latency` puts a number on screen in PLAY and SURFACE.
Nothing offsets a note by it.

**But it is not the correction term either**, and an earlier draft of
this document said it was. `latencyMillisOf` calls Oboe's
`calculateLatencyMillis`, and its own KDoc says what that is:

```
sed -n 67,78p app/src/main/cpp/OboeOutput.h
```

> The stream's own **round-trip** latency in milliseconds, or -1 when
> there is no stream or the device declines to say (not every HAL
> implements it).

Round-trip is input-to-output. What sync needs is the **output-side
delay**: how far ahead of the speaker the engine must write so a note
lands where a listener expects. Those are different quantities, and the
number already on screen is the wrong one. It also may not exist at all
— "not every HAL implements it" is in that same sentence.

So the correction term is a measurement the app does not currently take.
That is a bench item, not an arithmetic detail.

---

## The three questions, answered

The spec said this needs a design pass before any code, and named the
questions. Here they are with answers.

### 1. Where does the timeline live?

**It must become one thing, and that thing must be audio frames** —
specifically, frames as the *hardware* reports them, not as an engine
counts them.

Audio frames are the only clock in the system that cannot drift against
the sound the user hears, because they *are* the sound the user hears.
Wall time drifts against it (the device's clock and its audio clock are
different oscillators). The display's vsync drifts against both and
stops entirely when the screen does.

This is one piece of work with no MIDI in it at all, and it is the
prerequisite for everything below:

- a single `Transport` holding **two cursors, not one** — and this is a
  correction to an earlier draft, which said the engines should read the
  hardware position "instead of" their own counters. They must not.
  `OrbitEngine` mixes `[frame, frame + blockFrames)` and only advances
  `frame` *after* `sink.write`, so the render cursor necessarily **leads**
  the hardware by the device buffer. Feeding it the hardware position
  would make it re-render audio already queued. The two cursors are:
  - a **scheduling cursor** — where the engine is writing to, which is
    the future;
  - a **hardware position** — where the listener is, used for phase
    comparison and latency correction, never for deciding what to render.

  Sync is the business of keeping the second aligned with the master
  while the first stays exactly one buffer ahead of it;
- `OrbitEngine` and `LoopEngine` take their scheduling cursor from that
  transport rather than each owning a private counter;
- GROOVE's needle reads it instead of `withFrameNanos` — the needle can
  still be *drawn* on a frame callback, but it must be drawn **from** the
  transport, not driven by it;
- pad hits carry a frame timestamp across the JNI bridge instead of
  meaning "as soon as you get this".

That last one is the expensive part, and it needs stating precisely
because the signature invites a misreading. `NativePads.noteOn` *does*
take `startFrame` and `endFrame` — but those are the **window inside the
sample**, which slice of the WAV to play, filled from `PadHit.resolve`.
They are not a time to fire at. There is no argument anywhere on that
bridge that says *when*:

```
grep -n "external fun noteOn" -A 4 app/src/main/kotlin/com/snipsnap/app/NativePads.kt
grep -n "hit.startFrame" app/src/main/kotlin/com/snipsnap/app/PadEngine.kt
```

What actually happens is in the native source, not in `NativePads`' KDoc
— an earlier draft cited the KDoc, which establishes lock-free rings but
says nothing about *when* they are drained. The real citation:

```
grep -n "onAudioReady" -A 4 app/src/main/cpp/PadEngine.cpp
```

`onAudioReady` opens with `while (commands_.pop(c)) apply(c);`, so queued
commands are applied at the **top of each audio callback**. A hit lands
at the next buffer boundary, whenever that is. For a finger on a pad that is correct and desirable — it is as soon
as possible. For a sequenced note it is a quantisation error the size of
the buffer, and no amount of accuracy upstream can recover it.

Everything else in question 1 is refactoring. This is an interface
change on the audio hot path, which is why it wants measuring before it
is assumed (see the testing section).

### 2. What happens when clock stops or jumps?

External clock is not a tempo — it is a **series of events that can do
things a local clock never does**. MIDI beat clock is 24 pulses per
quarter note, with `start`, `stop` and `continue`, plus Song Position
Pointer for relocation.

The app currently has no opinion about any of it, because none of these
states exist locally:

| event | what it means | what the app has today |
|---|---|---|
| `start` | go to zero and run | the closest match: ORBIT rewinds, GROOVE's RECORD zeroes `posSteps` |
| `stop` | freeze **here** | *partly there already* — `OrbitEngine.stop()` and `LoopEngine.stop()` set `running = false` and **do not reset** their counters, and GROOVE's `posSteps` is state that survives. What is missing is not the position but the meaning: nothing treats it as a resume point |
| `continue` | resume from where `stop` left off | no control offers it, though the state to do it is sitting there |
| Song Position Pointer | relocate to bar N beat M | nothing can seek. ORBIT's rings are a pure function of frame, so a jump is arithmetic; GROOVE and the loop grid have no expression for it |
| clock stalls | the master died or the cable went | undefined — nothing would notice |

An earlier draft said "stopping resets". It does not, for any of the
three, and the difference matters: `continue` is closer than it looked.

```
grep -n "fun stop" -A 1 loop/src/main/kotlin/com/snipsnap/loop/OrbitEngine.kt
grep -n "fun stop" -A 1 loop/src/main/kotlin/com/snipsnap/loop/LoopEngine.kt
```

**The stall case is the one that decides the design**, and an earlier
draft answered it badly. It said the answer is "a phase-locked loop with
a slew limit". A slew limit bounds how fast the rate *estimate* changes;
it says nothing about what happens when pulses stop arriving. A slewed
PLL with no other rule keeps running at the last good rate **forever** —
which is precisely the runaway it was offered as the cure for.

Two separate mechanisms are needed, and they answer different questions:

| mechanism | question it answers |
|---|---|
| PLL with a slew limit | *how fast do we chase a master that speeds up or slows down?* — bounded, so jitter does not become tempo |
| **holdover with a timeout** | *what do we do when the pulses stop?* — coast for a stated window, then take a stated action |

The holdover policy is a decision, not a default, and it has to be
written down before anything is built. The candidates:

- **freeze** — hold position, keep sounding nothing new. Safest for
  recording; looks broken if the master merely hiccuped.
- **stop** — treat it as an implicit `stop`. Predictable, and loses the
  resume point unless one is kept.
- **coast, then stop** — run at the last rate for N pulses' worth of
  time, then stop. Rides a hiccup, bounds the runaway. Needs N chosen
  and justified.

My recommendation is coast-then-stop with N small — on the order of one
beat — because a cable pulled mid-bar should cost at most a beat of
wrong audio, and a master that stutters for less than a beat is common
enough that stopping on it would be its own bug. **That number is a
bench item**, not something to settle from a chair.

### 3. What does the app do as master, and as follower?

They are not two halves of one feature. They are two features with very
different costs, and **the spec's "half of it is worse than none" was
about shipping a broken follower, not about shipping master alone.**

**As master**, the app sends 24 PPQ derived from its own transport, plus
`start`/`stop`. Every other device follows. This costs: a transport that
can be read (question 1), a MIDI output port, and arithmetic. It changes
no timing authority, because the app already believes it is in charge.

**As follower**, the app's transport is driven by arriving pulses. This
costs everything in question 2, plus the fact that a follower's audio
callback must produce samples *now* for a tempo it learns *later* —
which is why the PLL and its slew limit are not optional.

---

## The recommendation: master first, follower second

Not because master is a stepping stone. Because:

1. **"SnipSnap drives my gear" is a complete capability on its own.** It
   is not half a feature. A user with an MPC and a phone can already do
   real work with it.
2. **It forces question 1 and nothing else.** You cannot send a clock
   you cannot read, so building master *is* building the single
   transport — the part of the follower work that has no MIDI in it, and
   the part every other screen benefits from regardless.
3. **It proves the plumbing while the stakes are low.** Device
   enumeration, permissions, port lifecycle, what happens when the cable
   is pulled — all of it gets exercised, and a wrong answer means your
   drum machine is out of time, not that your recording is ruined.
4. **A wrong follower is worse than no follower**, and this is the
   asymmetry that matters. With no sync you know to line things up by
   hand. With a follower that half-works you trust it, record eight bars
   alongside your gear, and find out afterwards that it slid. The
   September UAT already settled that a misleading label costs more than
   a missing one; this is the same rule one level up.

Against master-first, honestly: it does not answer the question most
people mean by "sync", which is *play along with the thing already
running*. If the real requirement is playing SnipSnap into an existing
session, master-first delivers nothing they asked for. That is a product
call and it belongs to the person who made the "instrument, synced"
decision, not to this document.

---

## What this does not commit to

The decision that the app is an instrument does not settle:

- **Whether the loop grid is in scope at all.** It has no front door —
  `LoopActivity` has no launcher intent-filter and nothing in the app
  starts it (`docs/SPECS_2026_09.md` §1, corrected). Giving it a
  transport it can follow is work spent on a screen users cannot reach.
- **Whether MIDI *input* comes before or after clock.** Note input is
  genuinely easy and genuinely useful; it is also the thing most likely
  to make people believe the clock works.

---

## How it would be tested

**Pure and testable in `:kit`**, beside `MidiGroove`: message encode and
decode, Song Position Pointer arithmetic, the PLL's response to a given
pulse series. A PLL is a function from timestamps to a rate estimate —
that is a unit test, and a good one, with the stall and jitter cases
written down as fixtures.

**Not testable here at all**: actual drift against real hardware, the
output-side delay that makes a note land where a listener expects, and
how widespread `TapeVoice`'s 800 ms `playbackHeadPosition` quirk is.
Each needs a device, and two of them need a second clock source.

These are **now written into `docs/BENCH.md`** rather than merely
promised to it — an earlier draft said they "go on" the bench while the
bench contained no such rows, which is the same class of unchecked claim
this document exists to avoid. That file is paused, deliberately and by
the phone's owner; the rows sit in it waiting for the pause to lift,
which is the honest state rather than a pretence that they are being
measured.

They are the items that decide whether any of this is worth shipping, so
**none of the code below should start before they are answered.**

**The seam this shares with everything else**: a readable transport is
also what would make `PadEngine` JVM-testable, which
`docs/SPECS_2026_09.md`'s closing section already flagged as the gate on
testing the runtime at all. The two pieces of work want the same
interface. That is an argument for doing question 1 well and separately,
under its own measurement, rather than as a detail inside a MIDI feature.
