# A second shape for SnipSnap — four places and a dock

*Status: proposal, 2026-10-13. Companion to [`MODERN_LOOK.md`](MODERN_LOOK.md),
which covers colour and surface. This document covers structure: what the
top level of the app is, where things live, and how you get between them.
Nothing in `:app` or `:shell` changes with this document. The third frame in
[`../design/ModernLook.html`](../design/ModernLook.html) draws the shape.*

## 1. Why this is a shape question and not a skin question

Three reviews in September (`UX_JOURNEY_REVIEW_2026_09.md`,
`UX_PERSONA_REVIEW_2026_09.md`, `UAT_2026_09.md`) and their remediation
plans fixed, by count, almost everything they found: the back stack is real,
the shelf is called SHELF, the twelve tabs have seams, the loop's two ends
offer a next step, the pad sheet has a permanent legend and press feedback,
the destructive writes warn. What is left is a short list, and every item on
it was parked with the same words: *this is an initiative, not a follow-up*.

| Parked finding | What it says | Where |
|---|---|---|
| **P3.1** — thirteen destinations against roughly one | "grouping thirteen is not reducing thirteen … a question about what the app *is*" | `UX_PERSONA_REVIEW_2026_09.md` §Decided |
| **P5.1** — a project of patterns vs a kit of one groove | "a bigger mismatch this finding never names … an initiative" | same, §Half-closed |
| **P2.2** — the audience's vocabulary doesn't navigate | PROGRAM, SAMPLE, SEQUENCE appear on no tab | same, §Standing |
| **P1.4** — HELP is 13th of 13 and is a 21-item list | "the wall is as described, and wider than when it was measured" | same, §Standing |
| **J10** (residue) — the loop's handoffs are timed toasts | "a non-timed next-step route belongs in the IA work" | `UX_JOURNEY_PLAN_2026_09.md` PR 6 |
| **W#22** (residue) — the pad sheet has no tap path and no menu entry | legend added, door not | `UX_WIRING_REVIEW_2026_09.md` |

Four measured facts sit under those:

- **KIT is the hub.** 11 of 24 programmatic navigations land on it; the
  next destination gets 4 (J14). It is drawn as step 3 of a 4-step strip.
- **The strip does not fit.** `UatSimTest`'s own arithmetic: thirteen tabs
  at about 482 dp in a 330 dp usable row. SURFACE, SNAP, SETUP and HELP
  need a drag the row cues with two glyphs that are not tappable.
- **Half the depth is one gesture deep.** The pad sheet is about 4,100 lines
  today and is reached by a hold on a pad, a GO ▸ on DOUBLES, or a RE-TRIM
  return. There is no tap and no tab.
- **Three personas out of five arrive with a one-surface model** (Koala,
  Pocket Operators, the novice) and the review rated "twelve screens" a
  *high* risk for the one the app most resembles.

So the question the user has now asked, "is the structure right", is the
question the repo's own reviews declined to answer because it was bigger
than a fix. This document answers it as a proposal.

## 2. What the audition page says about shape

`MODERN_LOOK.md` took the page's colours and surfaces. The page also has a
structure, and it is the more useful half for this question:

- **It is one place you scroll, not a set of places you switch between.**
  Its sections are numbered in the order of the work (`01 / LISTEN`,
  `02 / DECIDE`, `03 / THE TRANSLATION`), and each has one primary action.
- **One dock never leaves.** The player sits at the foot of every section:
  what is playing, a transport, a volume, a stop. The page's two surfaces
  are *paper for deciding* and *a dark dock for sound*, and the dock is
  persistent while the paper scrolls.
- **Filters, not modes.** VOICE and PRESET narrow one list; they do not
  take you somewhere else.

SnipSnap's menu row is the opposite shape: thirteen peers, each a mode, and
the LCD (where sound lives) is a panel inside some of them rather than a
fixture under all of them. The proposal below takes the page's shape as
literally as the look proposal took its palette.

## 3. The proposal: four places and a dock

**Four places** across the top, all visible at every width, no scroll:

| Place | What it is | What moves into it |
|---|---|---|
| **SHELF** | the library | kits, SNIPS, instruments, rooms, the bins, NEW KIT ▸ STARTERS. Unchanged in content. |
| **TAPE** | sound in | the deck, and **CUT** as its second stage (today's CHOP). INSTANT KIT, CATCH A HIT, READ AS GROOVE, STEAL THE FEEL stay where they are. |
| **KIT** | the thing you are making | three stages inside: **PADS · BEAT · OUT** (below) |
| **MAKE** | sound from nothing | SYNTH, SNAP, SURFACE, KEYS and the instruments. Each lands a pad or a tape in the open kit, which is what they already do. |

**Three stages inside KIT**, as a segment row under the title (the control
CHOP already owns and the GROOVE program row now uses):

| Stage | Holds | Today's tabs and doors it absorbs |
|---|---|---|
| **PADS** | the 4×4 grid, BANK A/B, the benches | KIT, PLAY (as a fullscreen toggle on the grid, which already exists), the pad sheet and its doors, SPLIT, BREED, SCULPT, STRETCH, DUST, VERSIONS + BIN |
| **BEAT** | the kit's time | GROOVE, ORBIT, SONG. ORBIT already has a second door from GROOVE; this makes that the only door. |
| **OUT** | the kit leaving | EXPORT, SHARE, BOUNCE. The three names for one destination (J47's BOUNCE) collapse to one place. |

**One dock**, fixed at the foot of every screen, drawn on the LCD surface:

- the transport for the open kit's BEAT (play, stop, tempo), so a beat can
  run while you treat a pad or scrub a tape;
- the capture light and counter (`ARMED · 00:41 ON TAPE`, `SNIP`), which
  today sit on the shelf and nowhere else;
- what is playing, which is where the toast lands and where a toast's door
  (`CHOP ▸`, `EXPORT ▸`) can stay until it is taken instead of for 7
  seconds. That is the non-timed next-step route PR 6 said belonged in
  the IA work.

**A visible door into the pad sheet.** Tapping a pad plays it and selects
it; a one-line strip under the grid reads the selected pad the way the pad
sheet's own closed boxes already do (`A02 · SNARE · CRUSH 35%`) with
`OPEN ▸` at its end. The hold stays as the fast path. This closes W#22
without touching the sheet.

**SETUP and HELP** move to a gear in the title bar, the slot the audition
masthead keeps on its right. In a row of four that does not scroll, a gear
is more visible than today's 12th and 13th tabs, not less.

The empty-shelf note becomes four places instead of four tabs: *TAPE IT,
CUT IT, KIT IT, WRITE IT — TAPE, THEN KIT, OR START FURTHER IN.*

### Where every destination goes

| Today | Proposed | Note |
|---|---|---|
| SHELF | SHELF | same |
| TAPE | TAPE | same |
| CHOP | TAPE ▸ CUT | CHOP always knows its tape now (J30 cannot recur) |
| KIT | KIT ▸ PADS | the hub, drawn as the hub |
| EXPORT | KIT ▸ OUT | |
| PLAY | KIT ▸ PADS, fullscreen toggle | the ⟳ already exists |
| GROOVE | KIT ▸ BEAT | |
| ORBIT | KIT ▸ BEAT ▸ ORBIT | one door, from GROOVE, as the KDoc says |
| SYNTH, SNAP, SURFACE | MAKE | |
| KEYS, SPLIT (no tab today) | MAKE, KIT ▸ PADS | unchanged as screens |
| SETUP, HELP | title-bar gear | |
| PAD SHEET and its doors | KIT ▸ PADS, from the pad strip or the hold | unchanged inside |
| SONG | KIT ▸ BEAT | |
| LOOP (its own Activity) | MAKE | unchanged |

## 4. What it fixes, what it costs, what it leaves alone

**Fixes, by the reviews' own numbers.** P3.1 (13 → 4 top-level, 7 with
stages); J14 (the hub is the hub); the strip-fit finding (4 tabs fit at
360 dp with room for the gear); P1.4 (HELP is one tap from every screen,
and the 21-line wall can become three lines per place); W#22 (a tap door to
the pad sheet); J10's residue (a standing next-step in the dock); J47's
BOUNCE names (one OUT); the CHOP source problem (CUT belongs to a tape).
P2.2 is not fixed by shape; it is fixed by words, and a four-place row has
room for a second line under each name (`KIT · PROGRAM`) if question 2
below says the MPC owner comes first.

**Costs, in code.** This is a navigation change, not a screen change:

1. `AppScreen` gains a notion of place and stage, or stays as it is with a
   mapping table; `MENU_GROUPS` becomes four `MenuItem`s plus a stage row
   drawn by `KitScreen`. `App.kt`'s `when (screen)` is unchanged except for
   the new dock composable under it.
2. `ConventionTest`'s *first-run loop names real menu tabs* law reads
   `MenuItem("...")` by regex. It has to learn that a stage is a tab's
   child, or the loop note has to name places only. Either is a few lines.
3. `UatSimTest`'s fit arithmetic measures a row of thirteen. It measures a
   row of four, trivially, and the "J12" finding it raises when a seam
   pushes a tab out retires.
4. The dock is new Compose. It is the one new surface, and it reuses
   `lcdPanel`, the GROOVE transport and the shelf's arm control.
5. Two stale counts (`Chrome.kt` and `app/README.md` say twelve and nine
   tabs; there are thirteen) retire with the row.

Nothing inside any bench screen changes. The `:shell` models are untouched.
The kit folder stays the unit of work unless question 1 says otherwise.

**Leaves alone, on purpose.** The bubble and the capture service. The
cassette metaphor and its hidden eggs (law 4). The pad sheet's internals
and its five boxes. Every refusal and every piece of copy that names a
button, until the buttons move.

## 5. How to check it without a phone

The repo cannot build `:app` in a cloud session, and the reviews say what
that limits. Two checks work anyway:

- **Tap counts on the persona journeys**, the way `UatSimTest` counts them
  today. Counted off the structure, not measured on a device:

  | Journey | Today | Proposed |
  |---|---|---|
  | P3's loop: tape → cut → pads → beat | 4 tabs, 1 off the visible run before J12 | TAPE, then KIT (CUT and BEAT are stages) |
  | Open a treated pad's bench from the grid | 1 hold, no visible door | 1 tap + 1 tap, or 1 hold |
  | Export the open kit | tab 5 of 13 | KIT ▸ OUT |
  | Reach HELP | drag, then tab 13 of 13 | gear, from anywhere |
  | Start a beat while treating a pad | leave the pad sheet, GROOVE, play, back, re-open | the dock's play |

- **The vocabulary ledger** in the persona review, re-run against the new
  names. SHELF · TAPE · KIT · MAKE has one invented word (SNIP stays
  inside); PADS · BEAT · OUT has none.

The modern **look** needs an SDK to prove; the modern **shape** can be
proved in `:shell` first, by the sim. That is why the recommendation in
§6 is shape first, in the OG look, and the look on top.

## 6. Questions before building

1. **Is the kit still the unit?** P5.1's real finding is that a Pocket
   Operator user thinks in a project of sixteen patterns, and SnipSnap's
   unit is a kit with one groove and up to eight of your own programs. The
   shape above keeps the kit as the unit and gives its BEAT a stage. If the
   answer is "a project", BEAT becomes a list of patterns and the SHELF
   holds projects, and that is a bigger change than this document.
2. **Who comes first?** The persona review says the app is built for the
   MPC owner (P2/P4) and its front door never says so. If they come first,
   the four places get MPC subtitles and HELP's first line names the
   formats. If the novice and the Koala user come first, the subtitles are
   the plain words already on screen.
3. **Does capture live in the dock?** Today the arm control is on the shelf
   and the bubble is the capture UI during a session. Putting the light and
   counter in the dock means capture state is visible on every screen,
   which the reviews asked for in three places, but it also means the
   dock has two jobs. The alternative is a dock that is only the transport
   and the toast.
4. **Shape first, or look first?** The recommendation is shape first, in
   the OG look, because the sim can check it and a device cannot be reached
   from here. The look lands on top, as `MODERN_LOOK.md` lays out, once an
   SDK session can compile `:app`.
