# Persona review: what is left, and what it would take

The plan for what survives `docs/UX_PERSONA_REVIEW_2026_09.md` after its
verification pass (that document's own `# Verification pass, 2026-09-20`
section), and after the four PRs that followed it. Of its 29 items — 22
numbered findings, 5 synthesis items, 2 headlines — **12 are closed, 3 are
decided, 4 are half-closed, and 10 stand.**

This plans the 10 standing, the open half of P4.4, and two things the pass
turned up that no finding names.

Written 2026-09-20, straight after the four PRs that closed the others
(#279, #284, #289, #291).

---

## The shape of what is left, and why it is not a backlog

The journey review's 47 findings were mostly one bug wearing 47 hats, so
its plan sequenced by root cause. **This is the opposite case.** The
ten standing findings do not share a root cause and mostly are not
defects at all. Sorted by what they actually need:

| | count | what they need |
|---|---|---|
| **A judgement about the product** | 6 | P1.1, P1.5, P2.4, P3.1, P3.2, P5.3 |
| **A measurement first** | 2 | P1.4, P2.2 |
| **Already settled, kept for the record** | 2 | P3.3, S1 |

Plus **P4.4's open half** — the review files it as half-closed now that
#291 fixed its export side, and what remains is a design call. Group D
below.

Nothing here is blocking anything. Nothing here is red. **The honest
reading is that the app is past the point where this document's findings
are the best guide to what to build next** — which is itself worth
knowing, and is why the two items in "Found by the pass" below are ranked
above most of the ten.

## The constraint that changed, and it changes the plan

`docs/UX_JOURNEY_PLAN_2026_09.md` opens on a fact that is **no longer
true**: that nothing runs a composable in CI.

`emulator-tests` (`.github/workflows/emulator-tests.yml`, added by #288)
boots an emulator and runs `app/src/androidTest` on it when anything under
`app/` changes. It exists, it is wired to this branch, and it is green.

**It covers exactly one file:** `SurfaceScreenTest.kt`. That is the whole
suite.

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

These are ranked first because they are the only two items here that are
defects or near-defects rather than judgements, and because one of them
compounds.

### N1 — point the on-device suite at GROOVE ▸ **recommended first**

**Why this one first.** Four times this session a bug lived in `:app`
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
screen it does not cover. The GROOVE cycler shipped in #289 with **no
behavioural test of any kind**, and its whole interaction — tap `YOURS`
when `YOURS` is already live and it steps to your next program — is
invisible to a compiler and to `ConventionTest` alike.

**Scope.** A `GrooveScreenTest` in `app/src/androidTest`, in
`SurfaceScreenTest`'s own shape: laid out at 360dp so a wide emulator
cannot hide what a narrow phone shows; the clock driven by hand, per that
file's own hard-won note about `waitForIdle` and a screen with a frame
loop.

What to assert, in rough order of value:

1. The program row's five labels draw whole at 360dp. This is the
   measurement from #289 turned into a check — `CAPTURED` needs 48dp and
   gets 67.6, and if a sixth segment is ever added the row breaks.
2. Tapping `YOURS` while it is live advances to the next program, and
   wraps.
3. The PROGRAM sub-line shows `n OF N` only when N > 1.
4. `EDIT STEPS` opens the program the cycler is on, not the first.

**Verified by:** `emulator-tests` in CI. Not runnable in a cloud session —
no SDK, no emulator — so the same read-it-adversarially discipline applies
to writing it, and #288's own history (three runs to get one suite green)
is the realistic expectation for iteration cost.

**Size:** one PR, but budget for two or three CI cycles.

### N2 — sample-rate control: no code, no decision

P4.2 names three absences. The audio bounce is built; MIDI and clock sync
are accepted as a documented gap. **Sample-rate control is the third, and
it is the one nobody has ruled on.**

A search finds it named only inside a refusal — *"THOSE TWO TAKES DON'T
MATCH - SAMPLE RATE OR CHANNELS. SPLICE WON'T RESAMPLE OR FOLD ONE TO
FIT."* — and nowhere as a control.

**This is a decision, not a task.** Three honest options:

- **Accept it**, the way P4.2's MIDI half was accepted, and record that in
  the review so it stops reading as an open question.
- **Build it**, which is a real feature touching capture, the WAV writer
  and every export path.
- **Test it first**, since P4's expectation here is argued rather than
  measured, like every persona claim in that document.

**Nothing below should be started before this is answered**, because if
the answer is "build it" it reorders everything.

---

## The ten, grouped by what they need

### Group A — a judgement about the product (6)

No code question is open in any of these. Each is a thing the app does on
purpose that one persona would do differently.

| | the finding | what a fix would cost |
|---|---|---|
| **P1.1**, X2 | `FIRST_RUN_LOOP_NOTE` ends on "DUB IT", which is opaque to a beginner. Verified: it reads `RECORD IT, CUT IT, KIT IT, DUB IT. FOUR TABS, IN ORDER.` | A word. J13 already changed step three from `PLAY IT` to `KIT IT` for exactly this reason, so there is precedent and a shape to copy. The cheapest item in this document. |
| **P1.5** | All-caps sentences throughout. | Everything. The caps are the app's voice — `PERSONALITY.md` is built on them. Not a bug; a house style one persona dislikes. |
| **P2.4** | The advertised loop starts at "record", which assumes you have something to record. | A second entry point in the first-run note. Interacts with P3.2 below — they are the same complaint from two directions. |
| **P3.1** | Too many destinations against roughly one for a rival app. **Stale as written: it says twelve, and the menu now holds thirteen** — `SHELF TAPE CHOP KIT EXPORT PLAY GROOVE ORBIT SYNTH SURFACE SNAP SETUP HELP`, since SNAP landed. J12's grouping rules shipped, but grouping thirteen is not reducing thirteen. | Large. It is a question about what the app is, not about the menu strip. |
| **P3.2** | `INSTANT KIT` and `CATCH A HIT` both exist and neither is in the loop note, so the record-to-pad path is real but unadvertised. | Small, and it is the same edit as P2.4. Do them together or not at all. |
| **P5.3** | SURFACE is a macro pad with `LATCH` and `PRINT`, not punch-in FX held live. | Architectural. Its output is a print, not a live effect on a master bus, and changing that is a different product. |

**Recommendation:** P1.1 and P2.4+P3.2 are one small copy PR between them.
The other three are not tasks, and should be marked as accepted rather
than left looking open.

### Group B — measure before deciding (2)

| | the finding | the measurement |
|---|---|---|
| **P1.4**, X6 | The vocabulary wall, and HELP's position. Counted during the pass: `HELP_LOOP` is 4 items, `HELP_MORE` is 20, HELP is the 12th tab of what was then twelve. **It is now 13th of thirteen.** | Whether a first-run user finds HELP at all. Five people, one afternoon. |
| **P2.2** | Their vocabulary does not navigate — a user's word for a thing is not the tab's word. Two of its example rows are already stale (`SEND TO GRID` is `SEND TO PADS`, `KITS` is `SHELF`). | Which words real users reach for. Same afternoon, same five people. |

Both are cheap to test and expensive to guess at. Neither should be built
from the review's own reasoning, which that document says of itself: its
persona claims are *"argued, not measured"*.

### Group C — settled, kept for the record (2)

- **P3.3** — the app never says "resample". Confirmed: the word appears
  only as `Resampler` in code and in one refusal string, never as a
  control. This is deliberate vocabulary, and S1's own verdict supports
  keeping it.
- **S1** — keep the cassette metaphor. Standing as a *recommendation*
  that the app already follows, and independently settled by J39, whose
  `PERSONALITY.md` catalogs the reels as hidden eggs under its own law 4.

**Neither needs work.** They are listed so a future reader does not
mistake them for open.

### Group D — the one small build (1)

**P4.4 — provenance, the in-app half.**

The export half is fixed (#291): a phone-made expansion now carries its
J-Card and liner notes, as a CLI-made one always did. What stands is that
per-pad provenance — `provenanceOrigin`/`provenanceLine`,
`PadSheetScreen.kt` — is reachable only by long-pressing a pad.

**The obvious fix does not work, and this is worth writing down so nobody
tries it twice.** Adding a word to `PAD_SHEET_LEGEND` fails not on width
(`ORIGIN` leaves 66dp spare at the 390dp frame; `WHERE IT CAME FROM`
leaves 2dp) but on purpose: that legend is a **sample, not an inventory**.
Its own site says the sheet holds *"twenty treatments plus shape, tune,
mutate, layers, takes and GRAIN FIELD"* — so a sixth word would make
provenance no more discoverable than layers or takes, which are equally
unnamed.

If this is wanted, the real options are a mark on the KIT grid (which
already carries J36's treated dog-ear and W12's mini-waveform, so a third
mark is a crowding question), or accepting the sheet as the right home.
**Needs a design call before any code.**

---

## Sequencing

1. **N2** — answer the sample-rate question. It is the only item that
   could reorder the rest.
2. **N1** — the GROOVE on-device suite. Highest compounding value: it
   makes the next `:app` change verifiable instead of re-read.
3. **P1.1 + P2.4 + P3.2** — one small copy PR, three findings.
4. **P4.4's in-app half** — after a design call, if wanted.
5. **Mark Group A's remaining three and all of Group C as accepted**, so
   the review stops reading as ten open items when it is really four.

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
