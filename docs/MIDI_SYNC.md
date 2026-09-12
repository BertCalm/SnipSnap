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
hardware's, and two of which cannot follow anything because they cannot
be read at all.

The work is not transferring ownership. It is **creating one clock where
there are currently four**, and that is a larger and more interesting
problem than the spec described.

Every claim below prints its own check, because this document exists to
be re-run rather than believed — the same rule `SPECS_2026_09.md` opens
with, and which that document then broke twice.

---

## What time it is, according to whom

| transport | "now" is | held in | can it be read? |
|---|---|---|---|
| ORBIT — `OrbitEngine` | frames **handed to the sink** | `AtomicLong frame` | yes, `position()` |
| Loop grid — `LoopEngine` | intervals **handed to the sink** | `AtomicInteger interval` | yes, `position()`, one interval's resolution |
| GROOVE | **wall time** since play started | `withFrameNanos` loop | only as Compose state |
| PLAY / pads — `PadEngine` → `NativePads` | nothing at all | — | **no** |

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

Notes then fire **immediately** through `PadEngine.noteOn` into the
native engine — no timestamp, no scheduling ahead, no lookahead buffer:

```
grep -n "fun hit(" -A 6 app/src/main/kotlin/com/snipsnap/app/ui/GrooveScreen.kt
```

So GROOVE's timing accuracy is bounded by the display's refresh
(~16.7 ms at 60 Hz) plus whatever the audio path adds. That is fine for
a needle you watch. It is not a foundation for clock sync, where the
error budget is closer to a millisecond.

### Nothing reads the hardware's playback position

`AndroidAudioSink` is `write`, `silence`, `resume`, `close`. It exposes
no `getPlaybackHeadPosition`, no `AudioTimestamp`:

```
grep -n "fun " app/src/main/kotlin/com/snipsnap/app/AndroidAudioSink.kt
```

So ORBIT's `frame` counter is **frames the engine has handed over**, not
frames the speaker has produced. The difference is the buffer — real,
variable, and currently invisible to every part of the app that reasons
about time.

### Latency is measured and then only printed

`NativePads.latencyMillis` and `NativeSurface.latencyMillis` exist, and
every consumer feeds a **display string**:

```
grep -rn "latencyMillis" --include=*.kt app/src/main | grep -v Native
```

`StreamFacts.latency` puts a number on screen in PLAY and SURFACE.
Nothing offsets a note by it. For sync that number stops being trivia
and becomes the correction term.

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

- a single `Transport` that owns "now" in frames, fed by the hardware
  position rather than by a count of buffers written;
- `OrbitEngine` and `LoopEngine` read it instead of holding their own
  counters;
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

What actually happens is in `NativePads`' own KDoc: commands cross
through lock-free rings and the audio thread consumes them on its next
callback. So a hit lands **at the next buffer boundary**, whenever that
is. For a finger on a pad that is correct and desirable — it is as soon
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
| `start` | go to zero and run | play always starts at frame 0, so this is the only one that maps |
| `stop` | freeze **here** | there is no "here" to freeze; stopping resets |
| `continue` | resume from where `stop` left off | no concept of a resume point |
| Song Position Pointer | relocate to bar N beat M | nothing can seek; ORBIT's rings are a pure function of frame, so a jump is arithmetic, but GROOVE and the loop grid cannot express it |
| clock stalls | the master died or the cable went | undefined — nothing would notice |

**The stall case is the one that decides the design.** A follower that
trusts the last pulse interval will run away when pulses stop; a
follower that freezes on the first missed pulse will stutter on every
jittery master. The usual answer is a phase-locked loop with a slew
limit — follow the master's *average* rate, bounded, and ride through
gaps rather than chase them. That is a real DSP component with its own
tests and its own failure modes, not a callback.

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

**Not testable here at all**: actual drift against real hardware, and
the latency offset that makes a note land where a listener expects. Both
need a device, a second clock source, and a measurement. They go on
`docs/BENCH.md` as live items, and they are the ones that decide whether
any of this is worth shipping.

**The seam this shares with everything else**: a readable transport is
also what would make `PadEngine` JVM-testable, which
`docs/SPECS_2026_09.md`'s closing section already flagged as the gate on
testing the runtime at all. The two pieces of work want the same
interface. That is an argument for doing question 1 well and separately,
under its own measurement, rather than as a detail inside a MIDI feature.
