# Persona review: what is left, and what it would take

The plan for what survives `docs/UX_PERSONA_REVIEW_2026_09.md` after its
verification pass (that document's own `# Verification pass, 2026-09-20`
section), and after the four PRs that followed it. Of its 29 items — 22
numbered findings, 5 synthesis items, 2 headlines — **16 are closed, 8 are
decided, 3 are half-closed, and 2 stand.**

Written 2026-09-20 to plan the 10 that were standing then, plus two things
the pass turned up that no finding names. **All of that is now done.** The
sections below are kept rather than deleted because the reasoning is the
part worth keeping — three of them record a finding that turned out to be
aimed wrong, which is not visible from a closed row.

**What is actually left is two findings, and neither is code:** P1.4 (does
a first-run user find HELP at all) and P2.2 (which words do they reach
for). Both want five real users for an afternoon. The review says of its
own persona claims that they are *"argued, not measured"* — building from
its reasoning on these two would be guessing twice.

---

## The shape of what is left, and why it is not a backlog

The journey review's 47 findings were mostly one bug wearing 47 hats, so
its plan sequenced by root cause. **This was the opposite case.** The ten
standing findings shared no root cause and mostly were not defects at
all. Sorted by what they turned out to need:

| | count | what they needed | where they went |
|---|---|---|---|
| **A judgement about the product** | 6 | P1.1, P1.5, P2.4, P3.1, P3.2, P5.3 | 3 built, 3 accepted |
| **A measurement first** | 2 | P1.4, P2.2 | still standing — they want people |
| **Already settled, kept for the record** | 2 | P3.3, S1 | accepted |

**P4.4 is not among them** — the design call it was waiting on was made on
2026-09-20, and making it turned up a defect underneath. Group D below
keeps the reasoning.

Nothing here was blocking anything and nothing here was red. **The honest
reading was that the app is past the point where this document's findings
are the best guide to what to build next** — which is why the two items in
"Found by the pass" were ranked above most of the ten, and both of those
found real defects that no finding named.

## The constraint that changed, and it changes the plan

`docs/UX_JOURNEY_PLAN_2026_09.md` opens on a fact that is **no longer
true**: that nothing runs a composable in CI.

`emulator-tests` (`.github/workflows/emulator-tests.yml`, added by #288)
boots an emulator and runs `app/src/androidTest` on it when anything under
`app/` changes. It exists, it is wired to this branch, and it is green.

It covered exactly one file, `SurfaceScreenTest.kt`, until N1 below
added `GrooveScreenTest.kt`. Two screens of eleven — still the thinnest
of the three kinds of coverage, and the one worth growing.

So for the first time there are three kinds of coverage, not two:

- **Behavioural, on a device** — `app/src/androidTest`, `emulator-tests`.
  New. One screen.
- **Structural, over the source as text** — `:shell`'s `ConventionTest`,
  reading `../app/src/main/kotlin` and asserting laws over it.
- **Logic, in the nine JVM modules** — everything decided outside a
  composable.

Every task below says which of the three proves it. That is the point of
listing them.

---

## Found by the pass, and not in any finding

These were ranked first because they were the only two items here that
were defects or near-defects rather than judgements, and because one of
them compounds. **Both are now done**, and both are kept rather than
deleted: N2's ruling is the reason the rest of this plan is unchanged,
and N1's account of what writing a test found is the argument for
writing the next one.

### N1 — the on-device suite reaches GROOVE ▸ **built, 2026-09-21**

**Why it was first.** Four times in one session a bug lived in `:app`
where no local check could reach it, and each was caught by re-reading
rather than by a tool:

- `EDIT STEPS` re-opened the *first* user program however far the cycler
  had moved (caught pre-merge).
- The fork toast reported the intent rather than what happened (caught
  pre-merge).
- A Compose file with mutually-recursive local functions would not have
  compiled at all (caught pre-push).
- The completion stage's reset button stayed enabled during an async copy
  (caught pre-push).

All four are the species `SurfaceScreenTest` was written to catch, on a
screen it did not cover. The GROOVE cycler shipped in #289 with **no
behavioural test of any kind**.

**What landed.** `app/src/androidTest/.../GrooveScreenTest.kt`, in
`SurfaceScreenTest`'s shape — laid out at 360dp, clock driven by hand
(GROOVE runs a `withFrameNanos` loop for its whole life, so auto-advance
makes every test time out in `waitForIdle`), six tests:

| | what it holds down |
|---|---|
| the program row reads in full at 360dp | #289's measurement, which lived in a comment |
| the line under it reads in full for every program | including the cycler's own `1 OF 8`, which nobody had measured |
| each program selects, and the line says which | the row works at all |
| YOURS is absent until a kit holds one | the fifth segment is conditional and the other four are not |
| tapping YOURS while live steps on, and wraps | **the cycler**, untested until now |
| one program of your own gets no count and nowhere to step | the `> 1` gate |

**A seventh was written, ran once, and was taken out — and what it cost
is the thing worth recording here.** It checked the #289 bug itself
(FORK TO YOURS opens the program the cycler is on) and had to reach a
button inside GROOVE's scrolling control region. `performScrollTo` drives
`Modifier.verticalScroll`'s `ScrollBy` semantics action, which *animates*:
it launches a coroutine on the frame clock and returns, and the
`waitForIdle` inside `performScrollTo` then waits for work only the clock
can finish. With the clock driven by hand it never arrives. The first CI
run said so exactly — **four tests passed, the fifth hung, and the job
was cancelled at its 45-minute cap with nothing failed.**

So: a hand-driven clock and an animated scroll do not mix, and every
assertion in that file now lives above the scroll region — which is where
the cycler is anyway. The header the dropped test would have read exists
and is JVM-tested; what is missing is a way to reach that button, and a
slow drag on the container (no fling) is the untried candidate.

**Writing it found two labels J18's rename had missed.** The step editor's
header read `STEP EDIT — PROG E` and RECORD's status line read
`OVERDUBBING ONTO PROG A` — a letter the row above them stopped using in
J18, and one that since #289 could not have said *which* of eight
programs you were in even had it been right. `ConventionTest`'s own law
says *"the letters are gone from the screen's own labels"*; it was reading
`PROG_NAMES` and stopping there, so the two labels not in that list were
exactly the two it could not see. The law now sweeps every string literal
in the file, and it found the second one itself.

The header names which of yours is open — **read from the clip the editor
was handed, not from the index it was forked at.** That is what makes the
last test a check rather than an echo: `GrooveEdit.fork` takes an index
and returns a clip, and the bug it guards was the index being right and
ignored, so an index-derived header would have said "2 OF 3" while
program one was on screen.

**Verified by:** `emulator-tests` in CI, which is the only place it can
run — no SDK and no emulator in a cloud session, so the same
read-it-adversarially discipline applied to writing it as to any other
`:app` change. The copy and the law are JVM-tested.

### N2 — sample-rate control ▸ **ruled on and built, 2026-09-20**

P4.2 names three absences. The audio bounce is built; MIDI and clock sync
are accepted as a documented gap. **Sample-rate control was the third, and
it was the one nobody had ruled on.**

**What the ruling turned on: the question was two questions.** A producer
asking a sampler for "sample-rate control" may mean a rate as a *sound*
or a rate as a *format*, and those have opposite answers here.

- **As a sound, it shipped long ago under a better name than a number.**
  `Eras` is a rate control: SP1200 is 26.04 kHz at 12 bits, MPC60 is
  40 kHz through a companding DAC, and both are on the pad sheet, in
  `snipsnap era`, and inside `Dub`. The search that found the word only
  in a refusal was searching for the wrong word.
- **As a format it is fixed at 44.1 kHz, and should stay fixed.**
  `WavWriter` refuses any other rate at the card's edge, `Preflight`
  FAILs a pad that is not at it, `SnipStore.commitPrepared` requires it.
  A picker offering a second setting the export would then refuse is a
  trap, not a control — and the destination that makes this app worth
  using is the one destination that will not take the second setting.

**So what was missing was neither: it was that the app never said so.**
Three things, all built:

1. **SETUP says the rate.** A `THE RATE` row beside the three things
   finding 23 already found the app knew and never said, and its note
   points at the TIME MACHINE for the other half of the question.
2. **IMPORT reports what it changed.** `SnipStore.import` has always
   moved a 48 k or 22.05 k share onto the MPC's rate and said nothing.
   `Imported.resampledFrom` carries it and the toast says it. This was
   the only part of P4.2's third limb that was a defect rather than a
   judgement: a conversion performed on somebody else's audio is theirs
   to be told about.
3. **The number has one owner.** `WavWriter.MPC_SAMPLE_RATE`, with
   seventeen code lines across six modules pointed at it and a
   `ConventionTest` law — `no source retypes the rate the MPC reads` —
   keeping the next one honest. Its allowlist holds three files, each
   with the reason the number there is a different quantity.

**Verified by:** JVM logic tests (`SnipStoreTest`) and the structural law
(`ConventionTest`). The SETUP row itself is three `TapeText` calls over
static copy — no state, no IO — which is as close to unbreakable as an
`:app` change gets.


---

## The ten, grouped by what they need

### Group A — a judgement about the product (6) ▸ **all six answered, 2026-09-21**

No code question was open in any of these. Each is a thing the app does on
purpose that one persona would do differently. Three got the small copy
PR; three were accepted as the app's own decisions and moved to the
review's *Decided* bucket.

| | the finding | what a fix would cost |
|---|---|---|
| **P1.1**, X2 ✅ | `FIRST_RUN_LOOP_NOTE` ended on "DUB IT", which is opaque to a beginner. | **Built.** Step four is `WRITE IT` — the word on EXPORT's own button (`WRITE KIT`), not the one on its progress line. DUB stays the app's word everywhere else. J13's rule applied to the last step instead of the third. |
| **P1.5** ▸ accepted | All-caps sentences throughout. | Everything. The caps are the app's voice — `PERSONALITY.md` is built on them. Not a bug; a house style one persona dislikes. |
| **P2.4** ✅ | The advertised loop starts at "record", which assumes you have something to record. | **Built, and the finding was aimed wrong.** It asks for a second entry point in the note; the second entry point is already the screen's *primary action* (`NEW KIT ▸ STARTERS`, directly under that panel), and the note's KDoc argues against naming it — a third copy of a control twice on screen. What was actually wrong: the note claimed `FOUR TABS, IN ORDER`, and a starter kit begins at step three. The clause now says so. |
| **P3.1** ▸ accepted | Too many destinations against roughly one for a rival app. **Stale as written: it says twelve, and the menu now holds thirteen** — `SHELF TAPE CHOP KIT EXPORT PLAY GROOVE ORBIT SYNTH SURFACE SNAP SETUP HELP`, since SNAP landed. J12's grouping rules shipped, but grouping thirteen is not reducing thirteen. | Large. It is a question about what the app is, not about the menu strip. |
| **P3.2** ✅ | `INSTANT KIT` and `CATCH A HIT` both exist and neither is in the loop note, so the record-to-pad path is real but unadvertised. | **Built, and half of it was already closed.** Both are buttons on TAPE, so nothing is hidden — the same order clause as P2.4 covers the "unadvertised" part. The real gap was narrower and nobody had checked it: `CATCH` has had a `HELP_MORE` line since HELP was written, and `INSTANT KIT` was **in no list at all**. It has one now. |
| **P5.3** ▸ accepted | SURFACE is a macro pad with `LATCH` and `PRINT`, not punch-in FX held live. | Architectural. Its output is a print, not a live effect on a master bus, and changing that is a different product. |

**That recommendation — one small copy PR for three of them, the other
three marked accepted — is what happened.** Worth keeping: two of the
three that were "built" were built against a different problem than the
one they named. Reading the screen before writing the copy is what found
that, and it is the third time in this document a finding was worth less
than the code it pointed at.

### Group B — measure before deciding (2)

| | the finding | the measurement |
|---|---|---|
| **P1.4**, X6 | The vocabulary wall, and HELP's position. Counted during the pass: `HELP_LOOP` is 4 items, `HELP_MORE` is 20, HELP is the 12th tab of what was then twelve. **It is now 21 items and 13th of thirteen** — P3.2's fix added the INSTANT KIT line, so the wall this finding describes got one row wider while the finding waited. | Whether a first-run user finds HELP at all. Five people, one afternoon. |
| **P2.2** | Their vocabulary does not navigate — a user's word for a thing is not the tab's word. Two of its example rows are already stale (`SEND TO GRID` is `SEND TO PADS`, `KITS` is `SHELF`). | Which words real users reach for. Same afternoon, same five people. |

Both are cheap to test and expensive to guess at. Neither should be built
from the review's own reasoning, which that document says of itself: its
persona claims are *"argued, not measured"*.

### Group C — settled, kept for the record (2) ▸ **accepted, 2026-09-21**

- **P3.3** — the app never says "resample". Confirmed: the word appears
  only as `Resampler` in code and in one refusal string, never as a
  control. This is deliberate vocabulary, and S1's own verdict supports
  keeping it.
- **S1** — keep the cassette metaphor. Standing as a *recommendation*
  that the app already follows, and independently settled by J39, whose
  `PERSONALITY.md` catalogs the reels as hidden eggs under its own law 4.

**Neither needs work**, and the review now says so in its own *Decided*
bucket rather than leaving them in *Standing*. They are listed here so a
future reader does not mistake them for open.

### Group D — P4.4's in-app half ▸ **decided and built, 2026-09-20**

The export half was fixed by #291. What stood was that per-pad provenance
was reachable only by holding a pad.

**Two fixes were on the table and neither was taken.** Adding a word to
`PAD_SHEET_LEGEND` fails not on width (`ORIGIN` leaves 66dp spare at the
390dp frame; `WHERE IT CAME FROM` leaves 2dp) but on purpose: that legend
is a **sample, not an inventory**, and a sixth word would make provenance
no more discoverable than layers or takes, which are equally unnamed. A
third mark on the KIT grid would crowd J36's treated dog-ear and W12's
mini-waveform to state a fact — *which* parent — that a mark cannot state.

**What was built instead: a line under the grid, and one author for it.**

`Provenance.ofKit` phrases where the kit came from and KIT prints it as a
third always-there line beside the two legends — the same move S2 already
made for the hold itself: keep the gesture, add a line that cannot be
dismissed. Per-pad detail stays on the sheet, which is the right home for
per-pad detail.

**And the design call turned up a defect underneath it.** The app had
*three* readers of a pad's `source` map:

| | keys | order |
|---|---|---|
| `PadSheetScreen.provenanceOrigin` (`:app`) | 10 | song → file → tapeFile → resampled → imported → … |
| `LinerNotes.render` (`:shell`) | 4 | song → resampled → file → imported |
| `Lineage.kitNode` (`:shell`) | 4 (different 4) | song → file → imported → app/title |

The pad sheet's own KDoc claimed it read *"in the same priority order
`LinerNotes.kt` uses"*. It had not for a long time. A pad captured from
another app — a key only `Lineage` knew — showed as its bare filename on
the pad sheet and as *"Built by hand, pad by pad."* in the liner notes
that went on the card, and a sculpted or dissected kit came out of
`snipsnap lineage` as *"made from scratch"*.

`Provenance` (`:shell`) is the only reader now: eleven kinds in one
declared order, one phrase each, checked exhaustively over the enum by
`ProvenanceTest` so the next door added cannot reach two readers out of
three.

**Verified by:** JVM logic tests. Moving the order out of `:app` is half
the point — `:app` has no unit test source set, so an order typed there
could only ever be checked by re-reading it.

---

## Sequencing

1. ~~**N2** — answer the sample-rate question.~~ **Done 2026-09-20.** It
   was the only item that could have reordered the rest, and it did not:
   the ruling was that the rate is fixed, so nothing below moves.
2. ~~**N1** — the GROOVE on-device suite.~~ **Done 2026-09-21.** It was
   ranked here for compounding value, and it compounded immediately: the
   three stale labels above were found by writing it, not by reading the
   screen.
3. ~~**P1.1 + P2.4 + P3.2** — one small copy PR, three findings.~~ **Done
   2026-09-21.** Step four of the first-run note is `WRITE IT`, the word
   on EXPORT's own button. P2.4 and P3.2 turned out to be aimed slightly
   wrong — the second entry point is already that screen's primary action
   and the shortcuts are buttons on TAPE, so nothing was hidden; what was
   wrong was the note ending `FOUR TABS, IN ORDER`, which is stricter than
   the app. One further real gap under P3.2: `CATCH A HIT` had a HELP line
   and `INSTANT KIT` was in no list at all. Both are named now.
4. ~~**P4.4's in-app half** — after a design call, if wanted.~~ **Done
   2026-09-20.** The call was made and the build was smaller than the
   defect it uncovered; see Group D.
5. ~~**Mark Group A's remaining three and all of Group C as accepted.**~~
   **Done 2026-09-21.** P1.5, P3.1, P5.3, P3.3 and S1 moved to the
   review's *Decided* bucket with the reason each was accepted. The review
   now reads as two open findings, which is what it has.

## What is deliberately not here

- **Re-opening anything closed.** The pass checked all 29 at source and
  three findings did not survive it — the "GROOVE is buried behind a drag"
  claim in X3/P2.2/P3.1 is simply false, P4.1 was a bundle of five items
  that are all now done, and Headline 2's argument lost half its support.
  Those are recorded in the review, not re-litigated here.
- **A fifth persona pass.** The document's own postscript is honest about
  its error rate; a fourth reading of the same five personas would find
  less than five real users would.
- **The framing question underneath P5.1**, which the pass surfaced and
  which is bigger than any finding: a Pocket Operator user thinks in a
  *project* holding sixteen patterns, and SnipSnap's unit is a *kit*
  holding one groove. The cycler shipped in #289 gives you eight programs
  inside a kit, which is not the same shape. If that shape is eventually
  wanted it is its own initiative, not a persona-review follow-up.
