# MUTATE — the card redesign: words, layout, and an honest KEEP

**Status:** design, approved by the owner on 2026-10-04 (PR #448). Between 2026-10-03 and 2026-10-04 the owner approved the
design brief this document follows, in two sections: section 1 (what the card
shows and the words it uses) and section 2 (its buttons, its safety, and the
three rounds that ship it). Nothing here is implemented. The redesign keeps every capability
the card has today: six moves, five partner sources, the move's knob, BECOME,
HEAR, UNDO, ROULETTE and DRIFT. It changes what the card says, where its controls
sit, what a second KEEP starts from, and what UNDO claims. It lands in three
rounds, M1 (words and honesty), M2 (layout) and M3 (safety), and each round ends
with an owner phone check of at most three questions. This document lands as a
docs-only PR (zero check runs by design, `.github/workflows/tests.yml`), as the
BECOME and MAGNET specs did.
**Date:** 2026-10-04
**Plan:** one per round, under `docs/superpowers/plans/`:
`2026-10-04-mutate-m1.md` (words and honesty), `2026-10-04-mutate-m2.md`
(layout and the shared pick-a-pad component) and `2026-10-04-mutate-m3.md`
(KEEP from the original, BUILD ON THIS, the honest UNDO). None is written yet;
each is written after the previous round's phone check.
**Related:** [`2026-09-30-become-strung-say-design.md`](2026-09-30-become-strung-say-design.md)
(the BECOME spec) built MORPH's second row and set the card rules this document
keeps: the row is always drawn so the card never jumps, and HEAR and KEEP share
one knob mapping. Its A1b plan,
[`../plans/2026-10-02-a1b-become-card.md`](../plans/2026-10-02-a1b-become-card.md),
put that row on the phone (PR #439, merged as `316bfe90`); its owner phone check
is still open (`docs/FEATURE_PLAN.md:1173`, row QQ4) and folds into M1's.
[`2026-09-30-terra-hit-bend-talk-design.md`](2026-09-30-terra-hit-bend-talk-design.md)
(the Group B spec, the TERRA hook) plans its round R4 to lift MUTATE's partner
picker into one shared "pick a pad" chooser for TERRA's STRUCK BY and BENT BY and
FORK's STRIKE FROM (that spec, `:1167-1200`, `:1545-1546`, `:1787`). M2 builds that
component, so R4 reuses it instead of lifting it (Decision 8).
[`2026-09-29-magnet-valve-design.md`](2026-09-29-magnet-valve-design.md) and the
BECOME spec set this document's form.
**Roadmap:** no `SYNTH_ROADMAP.md` row. This is a card on a shipped verb, not an
engine or a rack section. `docs/FEATURE_PLAN.md` gains a row per round when its
plan is written, as QQ4 did for A1b.
**Evidence:** four reports written for this redesign, read in full, in
`~/Documents/snipsnap-chimera-evidence-2026-09-29/mutate-ux/` (local, not in the
tree): `card-walkthrough.md` (every control, label and surprise on the card, with
file:line), `house-design.md` (everything the house already wrote about MUTATE,
including `design/mutate-v2/`'s boards and copy), `patterns.md` (patterns from other
tools, from knowledge, no web search) and `synthesis.md` (the confusion points
ranked, the house's proposals, three directions and a list of quick wins). Tags
`(walkthrough)`, `(house)`, `(patterns)` and `(synthesis #N)` name the report a fact
comes from; `#N` is the confusion point's number in `synthesis.md` §1. The reports
read the `claude/a1-become` branch; **every code cite in this document was re-read
at `0ba9b053`** (the main line, which includes BECOME) and is tagged `(tree)` where
a number is this commit's own. A number this document chooses is tagged
*this spec's*; an arithmetic guess is tagged *estimate*.

Paths used in cites:

| Short | Path |
|---|---|
| `PSS` | `app/src/main/kotlin/com/snipsnap/app/ui/PadSheetScreen.kt` (4 007 lines, tree) |
| `MS` | `shell/src/main/kotlin/com/snipsnap/shell/MutateSheet.kt` (357 lines, tree) |
| `MU` | `shell/src/main/kotlin/com/snipsnap/shell/Mutate.kt` (563 lines, tree) |
| `KB` | `shell/src/main/kotlin/com/snipsnap/shell/KitBuilder.kt` (1 335 lines, tree) |
| `COPY` | `shell/src/main/kotlin/com/snipsnap/shell/Personality.kt` (`object Copy`; there is no `Copy.kt`) |
| `CT` | `shell/src/test/kotlin/com/snipsnap/shell/ConventionTest.kt` |
| `MST` | `shell/src/test/kotlin/com/snipsnap/shell/MutateSheetTest.kt` |
| `PT` | `shell/src/test/kotlin/com/snipsnap/shell/PersonalityTest.kt` |
| `PadSheetBoxes.kt` | `shell/src/main/kotlin/com/snipsnap/shell/PadSheetBoxes.kt` |
| `OutsideSheet.kt` | `shell/src/main/kotlin/com/snipsnap/shell/OutsideSheet.kt` |
| `RecipeReplay.kt` | `shell/src/main/kotlin/com/snipsnap/shell/RecipeReplay.kt` |
| `KitStore.kt` | `kit/src/main/kotlin/com/snipsnap/kit/KitStore.kt` |
| `MutateCommand.kt`, `DriftCommand.kt`, `RecipeCommand.kt`, `ChopCommand.kt` | `cli/src/main/kotlin/com/snipsnap/cli/<name>` |
| `RecipeReplayTest`, `RoomsTest`, `PadSheetBoxesTest`, `OutsideSheetTest`, `KitBuilderTest` | `shell/src/test/kotlin/com/snipsnap/shell/<name>.kt` |

Where a range is given for a composable or a function, it is the declaration
through its closing brace, without its KDoc (the KDoc's first line is given
separately where it matters).

## Why

**The owner's words.** After trying BECOME on a phone build of PR #439, the owner
said: *"I think mutate is complicated to understand how to use it. How could we
fool proof the feature without stripping utility".*

**What was hardest, by the owner's own pick.** Asked to choose, the owner picked
three things:

1. **which move to pick;**
2. **what the partner is;**
3. **what the buttons do.**

The knobs were not a problem.

**Earlier misses.** The owner could not find the controls at first, and two of
the owner's earlier phone-check answers were accidental. So every phone check in
this document gives step-by-step directions to each control, starting from
opening the app ("Phasing and gates").

**The direction chosen.** "Finish the house redesign": build
`design/mutate-v2/`'s boards, plus the quick wins. The owner did not choose the
other two directions `synthesis.md` §3 offered (named starting points that set the
real controls, and a guided step strip with DRIFT as the way in).

"Without stripping utility" is a hard rule here. The persona review calls MUTATE
"arguably the whole pitch" to the sound-design persona
(`docs/UX_PERSONA_REVIEW_2026_09.md:436`, house), and the CLI's full partner set is
on the card (`MS:15-35`, walkthrough §4). Nothing in this document removes a move,
a partner source, a knob or a button.

## What the card does today

The MUTATE card is a private composable inside the pad sheet (`PSS:3513-3798`,
called at `PSS:2565-2598`), inside the third of five group boxes, TREATMENT,
SHAPE, MUTATE, OUTSIDE and MAKE (`shell/src/main/kotlin/com/snipsnap/shell/PadSheetBoxes.kt:25-31`). All the
boxes start closed and only one is open at a time. Top to bottom, the open card
draws (tree):

| # | Row | Cite |
|---|---|---|
| 1 | `ONE HIT FROM TWO`, with `UNDO` at the right | `PSS:3548-3551` |
| 2 | status line: `<WORD>: <parents>` after a mutate, else `PICK A MOVE AND A PARENT` | `PSS:3552-3557` |
| 3 | six move chips, three to a row; STACK selected at first | `PSS:3559-3580`, `PSS:1184` |
| 4 | this kit's other pads as bare tags, four to a row | `PSS:3582-3609` |
| 5 | `ROOMS ON THE SHELF · ROOM PLAYS THE PAD INSIDE ONE` and room chips, only once OUTSIDE has kept a room | `PSS:3610-3642` |
| 6 | `ANOTHER KIT · PICK ITS PAD`, kit chips, then that kit's pads, only on a shelf with more than one kit | `PSS:3643-3704` |
| 7 | `A FILE ▸ PICK ONE OFF THE PHONE` | `PSS:3705-3715` |
| 8 | `ROULETTE · CRATE DEAL` beside `DRIFT · DEALS & SAVES` | `PSS:3716-3748` |
| 9 | the move's knob (AT, HZ, MIX, WET, BANDS), or a dead `—` row on STACK | `PSS:3750-3760` |
| 10 | BECOME on MORPH, a dead `—` row on every other move | `PSS:3762-3776` |
| 11 | `▶ HEAR`, then `MUTATE ▸`, both full width | `PSS:3778-3796` |

Every chip, button and slider is at least 48 dp tall (`Layout.MIN_HIT_TARGET = 48`,
`shell/src/main/kotlin/com/snipsnap/shell/Schemes.kt:430`, tree). With a 16-pad kit the card is about 700 dp
before any rooms or other-kit rows, so on a typical phone HEAR and MUTATE sit below
the fold (walkthrough §1, *estimate*, not measured).

The walkthrough's verdict, restated: the card is a form that asks for up to nine
inputs and never says what any of them will do to the sound. The house's own
verdict, three weeks earlier, was the same: "MUTATE is a listening tool presented
as a form" (`design/mutate-v2/README.md:17`, house).

### The findings, ranked

Ranked by how likely each is to stop a first-time user, as `synthesis.md` §1 ranks
them. Items 7 and 8 are bugs.

**1. The partner is never named, and HEAR and MUTATE are grey with no reason
given** (synthesis #1).
- Both buttons are `enabled = !busy && partner != null` (`PSS:3786`, `PSS:3793`).
  Their handlers also return silently with no partner (`val who = partner ?: return`,
  `PSS:1276`, `PSS:1341`). A disabled button differs from an enabled one only by its
  ink3 text colour. The house rule is "dimmed, not disabled; the toast explains":
  "Where the reason is not obvious from the screen, the control stays tappable and
  says it … The silent middle is what is banned" (`docs/UX_JOURNEY_PLAN_2026_09.md:944-956`).
- The status line says `PICK A MOVE AND A PARENT` (`PSS:3553`) although a move,
  STACK, is already picked (`PSS:1184`).
- One input has six names on and around the card: parent (the status line),
  partner (the code, `MS:114`), neighbour (DRIFT's KDoc and the walkthrough's
  reading of DRIFT), crate and deal (`ROULETTE · CRATE DEAL`, `PSS:3734`;
  `CRATE_EMPTY`, `COPY:1523`) and another kit (`PSS:3647`). The toasts add a
  seventh shape, "ONE HIT, TWO PARENTS" (`COPY:1503`).
- The shortest path to a sound is three taps (open the box, tap a partner, tap
  HEAR), and nothing on the card says so (walkthrough §1).

**2. The move names describe the DSP, and nothing on screen explains them**
(synthesis #2). STACK, SPLICE, SPLIT, MORPH, ROOM and TRANSPLANT are bare chips
(`PSS:3559-3580`). TRANSPLANT ("my timing, their tone") and ROOM ("any sound can be
the room; a kick can be the room") cannot be guessed from their names
(walkthrough §2). `design/mutate-v2/Moves.dc.html:45-50` already holds one plain
line per move, and none of it was placed on the card (house §0).

**3. The knob's meaning changes with the move, and its only label is a 44 dp
column** (synthesis #3). The one slot reads AT, HZ, MIX, WET or BANDS, meaning a
time, a frequency, a blend, a wet amount or a resolution (`MS:45-57`). The label
column is 44 dp wide (`PSS:3120`, tree). The house's board list already names
"one knob whose meaning changes silently between moves" as part of the diagnosis
(`design/mutate-v2/README.md:26`), and the BECOME spec calls that a smell
(`2026-09-30-become-strung-say-design.md:619-621`). The dead rows
say nothing: STACK draws `—` for its knob, and BECOME's row is `—` on five of the
six moves (`PSS:3752`, `PSS:3768`). The walkthrough reads them as broken, not as
"no option" (walkthrough §3.8).

**4. Partners are bare tags, the card is tall, and two ways in start dimmed**
(synthesis #4).
- A partner pad is a tag such as `A02` with no name, no sound and no way to hear it
  (`PSS:3582-3609`). The user chooses a sound by its position on a grid.
- HEAR and MUTATE sit below the fold (item 0 above, *estimate*).
- ROULETTE and A FILE start dimmed, exactly when a new user needs them to look
  tappable: `dimmed = deal == null` (`PSS:3737`) and `dimmed = held == null`
  (`PSS:3712`). The journey review recorded this as still open
  (`docs/UX_JOURNEY_REVIEW_2026_09.md:371`).

**5. MUTATE and DRIFT make no sound after they write** (synthesis #5). Both swap
the model for a fresh one and deliberately skip playback (KDocs at `PSS:1263-1266`
and `PSS:1403-1404`). You get a toast, then silence, and must find HIT elsewhere on
the sheet to hear what you made. The house already solves this for treatments: the
write sets `auditionOnRefresh` (`PSS:296`) and an effect plays the new file once the
fresh model has decoded it (`PSS:388-399`; set by SMEAR at `PSS:694`, and at
`PSS:784` and `PSS:923`). `onMutate` and `onDrift` never set it.

**6. DRIFT misleads** (synthesis #6).
- It shares a row with ROULETTE (`PSS:3732-3748`). ROULETTE only picks a partner
  and writes nothing (`PSS:1371-1389`). DRIFT writes.
- It commits in one tap and ignores the chosen partner, move and knob. It deals its
  own partner and morphs toward it (`PSS:1411-1494`, `MU:273-290`).
- It switches the card to MORPH, writes 50 % into MORPH's remembered MIX when the
  card was not already on MORPH (`PSS:1441-1456`), and puts BECOME back to OFF
  (`PSS:1462`).
- Its label, `DRIFT · DEALS & SAVES` (`PSS:3747`), does not say the pad's audio is
  replaced.

**7. BUG: a second MUTATE builds on the already-mutated pad, and the pad can then
no longer be regenerated** (synthesis #7).
- `Mutate.apply` reads the pad's current WAV (`MU:230`), and so does
  `MutateSheet.preview` (`MS:306`). Neither restores the original first, unlike
  DUST and SMEAR, which do (`KB:519-539`, `KB:586-633`).
- So HEAR right after a MUTATE previews the next compounding step, not the pad you
  just made, although `▶ HEAR` reads as "play what I have" (walkthrough §3.5).
- `replaceAudio` replaces the recipe on every write (`recipe ?: it.recipe`,
  `KB:505`). A twice-mutated pad's recipe describes only the second step, so the
  sound can no longer be rebuilt from its recipe and its original. That breaks the
  promise "the recipe — mode, parents, split, flips — rides the pad so the sound
  stays regenerable" (`docs/CLI.md:539-540`).
- The CLI chains on purpose, and that is real utility. The fix is to make chaining
  a choice on the phone, not to remove it.

**8. BUG: UNDO restores only the newest bin take, clears the recipe, and then
claims the original is back** (synthesis #8).
- UNDO calls `Mutate.undo` (`MS:356`), which calls `untreatPad` (`MU:293-296`).
  `untreatPad` restores the newest binned copy of the pad's file and sets
  `recipe = null` (`KB:804-810`, `KB:1128-1129`).
- After two MUTATEs one UNDO brings back the first mutation's sound. The recipe is
  now null, so the status line reads `PICK A MOVE AND A PARENT`, the box's strip
  reads `UNTOUCHED` (`PadSheetBoxes.kt:70-72`), and UNDO goes grey
  (`mutated != null` at `PSS:3550`). The pad is still changed, and the toast,
  `PARENTS SEPARATED. THE ORIGINAL IS BACK FROM THE BIN.` (`COPY:1521`), is false.
- A treatment before a mutate gives the same mismatch: UNDO restores the treated
  take and clears the recipe, so the TREATMENT box reads untouched while the audio
  is still treated (walkthrough §3.6).

**9. Refusals give the wrong reason** (synthesis #9).

| Situation | What the toast says today | Why it is wrong |
|---|---|---|
| A chained (round-robin) pad, on HEAR or MUTATE | `HEAR FAILED. TRY AGAIN.` or `MUTATE FAILED. TRY AGAIN.` (`failure`, `PSS:443-446`; `COPY:2145`) | `requireRewritable` refuses the pad (`KB:1174-1181`, `KB:1188-1192`); retrying never helps |
| A chained pad, on DRIFT | `THE CRATE HAS NOTHING TO DEAL. ONLY YOU ON THE SHELF.` | `onDrift` maps every `IllegalArgumentException` to `CRATE_EMPTY` (`PSS:1489`) |
| ROULETTE when every other sound is a copy of this pad | the same "only you on the shelf" line (`PSS:1384`) | `Mutate.roulette` throws for this case too (`MU:101-103`); the shelf is not empty |
| A velocity-layered pad | `GHOSTS ON. MUTATE WANTS ONE SAMPLE - CLEAR THEM FIRST.` (`COPY:1522`) | GHOSTS is not the word the pad sheet prints for this control, which is the `SOFT HITS` chip (`PSS:2393-2401`). GHOSTS also names a different feature that users see: CHOP's GHOSTS segment (`COPY:1790`), HELP's line (`COPY:696`) and the `GHOST LAYERS ON` toast (`COPY:1446`). The line does not say where to clear the layers |

A chained pad is not pre-checked at all; only the layered case is
(`PSS:1278-1281`, `PSS:1343-1346`, `PSS:1415-1418`).

**10. Minor** (synthesis tier 3).
- Nothing on the card reads out the pending choice (pad, partner, move, knob). The
  status line reports the last committed mutation (`PSS:3553`). Collapsing the
  picked other kit hides an active partner (`PSS:2576`).
- STACK silently flips a partner that would cancel the pad (`MU:303-305`). The CLI
  reports it (`cli/src/main/kotlin/com/snipsnap/cli/MutateCommand.kt:138`: `polarity: flipped '<label>' - it was
  cancelling the pad`); the phone ignores `Outcome.flipped` (`PSS:1297`).
- A move can change the pad's length: STACK runs as long as the longer of the two
  (`layers.maxOf { it.frameCount }`, `MU:311`), SPLICE as long as the partner's body
  (`MU:326`), SPLIT as long as the longer of the two (`maxOf(base.frameCount,
  top.frameCount)`, `MU:347`), MORPH's length moves toward the partner's, and ROOM
  adds the room's tail (walkthrough §2, §3.10, tree). Only TRANSPLANT always keeps
  the pad's length. The card never says so.
- The feature has four names on one screen: the legend MUTATE, the title ONE HIT
  FROM TWO, the toast's ONE HIT, TWO PARENTS, and MORPH's chip on a pad whose strip
  reads BECOME.

## What the house already designed

`design/mutate-v2/` (commit `d13b1923`, 2026-09-12) is five 390 × 844 OILSLICK
boards drawn against this card (house §3). An hour later a single `▶ HEAR` button
was wired (`8b3bc559`); nothing else on the boards was built. No document records
a decision to drop or defer them; they are open and unowned, not rejected
(house §5).

| Board | What it shows | Reused here |
|---|---|---|
| `AsBuilt.dc.html` | today's card: "nine rows of chips, one knob whose meaning changes silently" | the diagnosis |
| `Main.dc.html` (`:50-66`) | at rest: `A02 SNARE` × `SOUL B02` with a mini waveform each; `▶ HEAR MINE` / `▶ HEAR THEIRS`; `PARTNER · SOUL BREAKS B02` with `CHANGE ▸`; the six chips; `THIS ATTACK, THAT TAIL.`; `AT 40 ms` over `WHERE THEY HAND OVER`; `▶ HEAR THE RESULT`; `NOTHING IS WRITTEN UNTIL YOU KEEP IT`; `KEEP IT` beside `DRIFT` | the pairing line, the two plays (as `▶ MINE` / `▶ THEIRS`), the folded picker and `CHANGE ▸`, the move line, the knob's meaning, `▶ HEAR THE RESULT`, the note line (reworded, below), KEEP beside DRIFT |
| `Heard.dc.html` (`:62-69`) | after HEAR: a third waveform, `WHAT YOU WOULD KEEP`, and KEEP lit ("KEEP IT lights only once you have heard it", `README.md:28`) | KEEP lights once the settings are heard (the first-tap rule) |
| `Drift.dc.html` (`:50-55`) | the empty state: `A02 × ?  NO PARTNER YET`, DRIFT as the whole card, hand-picking folded under `OPEN ▾` | the empty state's `× ?` form only |
| `Moves.dc.html` (`:45-50`) | the copy sheet: one line per move and each knob's meaning | **verbatim**: the six move lines, the five knob meanings, and STACK's `NO KNOB — THEY LINE UP ON THE HIT` |

**What is reused verbatim.** The six move lines and the five knob meanings are
copied character for character from `Moves.dc.html:45-50`, and so is STACK's
no-knob line. The move names do not change: "The move *names* do not change: they
are in the CLI, the recipe and the lineage" (`design/mutate-v2/README.md:30`).

**What changes from the boards, and why.**
- The note line reads `NOTHING CHANGES YOUR PAD UNTIL YOU KEEP IT`, the brief's
  words, not the board's `NOTHING IS WRITTEN UNTIL YOU KEEP IT`.
- The commit button reads `KEEP · <MOVE> × <partner>`, not `KEEP IT`. The "button
  states its outcome" idea is pad-sheet-v2's: its MUTATE board drew
  `MUTATE ▸ ROOM × FUNK ROOM` (`design/pad-sheet-v2/MutateOpen.dc.html`, house §5).
- DRIFT is not the empty state's whole card, as `Drift.dc.html` drew it. The owner
  chose to keep DRIFT a one-tap blend-and-save beside KEEP (section 2).
- The mini waveforms are not built ("Out of scope").

**An adjacent design, not built.** `docs/AUDITION_SPEC_2026_09.md` is the house's
level-matched A/B at the deep end. Its rule 4 says an A/B that is not
loudness-matched "misleads" (house §3g). `▶ MINE` and `▶ THEIRS` are solo plays
through the card's existing audition path, not an A/B, so this document does not
level-match them ("Out of scope").

## The design

The two sections below are the brief's, restated with every label, line and
rule, and with the detail a plan needs. The sketches are the brief's own. Lower
case in the brief's sketches is the brief's shorthand: every word on the card is
drawn in the house's capitals, as `Moves.dc.html` prints them and as
`Personality.kt:3-14` and `PT:726` require for copy.

### Section 1: what you see and the words

```
A02 SNARE × B07 KICK                ▶ MINE   ▶ THEIRS
PARTNER · B07 KICK                         CHANGE ▸
[STACK] [SPLICE] [SPLIT] [MORPH] [ROOM] [TRANSPLANT]
   THIS ATTACK, THAT TAIL.
AT  ━━━━●━━━━  40 ms   · where they hand over
BECOME — only MORPH turns over time
```

**The pairing line.** The top line is your pad × the partner:
`A02 SNARE × B07 KICK`. The pad side is the pad's tag and its name; the partner
side is the partner's name as the picker names it (below). `▶ MINE` and
`▶ THEIRS` play each sound through the existing audition path, `audition()`
(`PSS:366-377`), which folds to mono and applies the pad's SHAPE. So both sounds
can be heard before choosing.
- **The pairing line's budget.** From M2 it shares its row with `▶ MINE` and `▶ THEIRS`,
  which leave it about 28 of the row's 44 characters (*estimate*: 44 less the two
  buttons' 14 characters and their gaps). Pad names and partner names can each run
  to 24 characters, so the line cannot always hold both in full. `pairLine` cuts
  each side to an even share of the budget, the tag always whole and the rest
  ending in `…` when cut (`A02 SNA… × SO… B02`, the tag kept where it comes last).
  The empty state's `× ?  — PICK A PARTNER` is never cut; the pad's name gives way
  and its tag stays (`A02 × ?  — PICK A PARTNER`). In M1 the line has the full row.
  TalkBack reads the uncut pair.
  The partner row under it (`PARTNER · <name>`, 10 characters plus a 24-character
  name, beside `CHANGE ▸`, 42 in all) is the place that always names the partner
  in full.
- `▶ MINE` plays the sound the move starts from, through the pad's level and
  SHAPE, exactly as HEAR THE RESULT plays the result. Until M3 that is the pad's
  current audio. From M3 it is whatever KEEP would start from (section 2,
  `MutateSheet.base`): the pad's original in the common case, the current audio
  with BUILD ON THIS on, and the newest bin take on a pad whose bin does not record
  its original (FROM_BEFORE). So MINE always plays the left-hand side of the move.
  When that differs from what the pad now holds (after a KEEP, MINE and ▶ HIT sound
  different), MINE says so: its spoken label adds `, BEFORE ITS MUTATE`, and a tap
  toasts `THAT IS THIS PAD BEFORE ITS MUTATE: WHAT KEEP STARTS FROM.`
  (`MUTATE_MINE_BEFORE`, *this spec's*, M3).
- `▶ THEIRS` plays the partner's own audio, the bytes the move reads
  (`MutateSheet.source`, `MS:236-252`), at level 1 with no SHAPE, because the move
  applies none of the partner's pad settings.
- Both are reads. Neither writes, and neither counts as hearing the result (the
  first-tap rule, section 2).

**One word: PARTNER.** The card, its toasts and its status lines use one word for
the one input, PARTNER. Parent, neighbour, crate and deal are retired from
MUTATE's user-facing copy. The CLI keeps its flag names (`--with`, `--roulette`),
its report lines and its recipe keys. One CLI line changes with the card: the
`recipe` command's refusal for a mutated pad is built in `:shell`
(`RecipeReplay.plan`, `RecipeReplay.kt:65`) and printed lower-cased by the CLI
(`RecipeCommand.kt:43`), so `needs its parent - not carried` becomes
`needs its partner - not carried` there too. It is one word in an error line, the
brief retires the word from MUTATE's copy, and keeping a CLI-only copy of the line
would leave two constants for one refusal. Its pins change deliberately
("Testing").

| Where | Today | After |
|---|---|---|
| status line, idle | `PICK A MOVE AND A PARENT` (`PSS:3553`) | `A02 SNARE × ?  — PICK A PARTNER` |
| ROULETTE | `ROULETTE · CRATE DEAL` (`PSS:3734`) | `ROULETTE · PICK A PARTNER OFF THE SHELF` (M1; Decision 12) |
| keep toast | `… ONE HIT, TWO PARENTS.` (`COPY:1503`) | `… THE TAKE BEFORE IT SLEEPS IN THE BIN.` (M1; M3 refines it) |
| UNDO toast | `PARENTS SEPARATED. …` (`COPY:1521`) | see section 2 |
| empty shelf | `THE CRATE HAS NOTHING TO DEAL. ONLY YOU ON THE SHELF.` (`COPY:1523`) | `THE SHELF HOLDS NO OTHER PAD. ROULETTE HAS NOTHING TO PICK.` |
| a file that cannot partner | `NOT A PARENT: …` (`COPY:1525`) | `NOT A PARTNER: …` |
| DO IT AGAIN on a mutated pad | `MUTATE (… WITH …) NEEDS ITS PARENT - NOT CARRIED.` (`COPY:1543-1544`) | `… NEEDS ITS PARTNER - NOT CARRIED.` |

The brief's own phrase "the crate only holds this pad's doubles" names a case,
not a line; its on-screen line uses neither retired word (section 2's refusal
table). DRIFT's toast, `<PAD> DRIFTED TOWARD <X>. ORIGINAL SLEEPS IN THE BIN.`
(`COPY:1520`), contains no retired word and stays as it is; `PT:799` pins it
exactly.

**The picker folds** into one row: `PARTNER · <name>  CHANGE ▸`. `CHANGE ▸`
opens all five sources in one panel: a pad on this kit, another kit, ROULETTE
(which picks one), the room, and a file. The panel is the shared pick-a-pad
component ("Architecture"). It opens over the card, so the card's own height
never changes, and `▸` keeps its house meaning, "goes somewhere"
(`docs/UX_WIRING_REVIEW_2026_09.md:100`; Decision 14). In the panel:
- a pad on this kit is its tag and name (`B07 KICK`), two to a row, as rooms and
  kits are drawn today (`PSS:3615`, `PSS:3648`), and never the pad itself
  (`MutateSheet.partners`, `MS:111`). Today's four-to-a-row bare tags fit a tag;
  a tag and a name of up to 24 characters do not (house §6c budgets about 9
  characters a chip in a four-chip row, 12 in a three-chip row, about 44 across a
  full row), so a two-chip row holds `<TAG> <NAME>` cut to about 20 characters
  (*estimate*), with the uncut name as the chip's spoken label;
- another kit is its name, then its pads, two taps as today; on a one-kit shelf
  the row reads `NO OTHER KIT ON THE SHELF`;
- `ROULETTE · PICK A PARTNER OFF THE SHELF` picks one and closes the panel;
- the room is the kept rooms; with none kept the row reads
  `NO ROOM YET · MAKE ONE IN OUTSIDE ▸ ROOM` (40 characters, inside the full row's
  44). That is how a room gets onto the shelf today (`COPY:1605-1607`). The line
  avoids `KEEP ROOM`, OUTSIDE's button, because KEEP is the card's commit verb
  and one word must not mean two things on the card;
- `A FILE ▸ PICK ONE OFF THE PHONE` opens the system picker, as today
  (`PSS:1504-1525`).

**The empty state** reads `A02 SNARE × ?  — PICK A PARTNER` on the pairing line,
and `PARTNER · NONE YET  CHANGE ▸` on the partner row. Nothing is greyed without
a reason. The house rule is "dimmed, not disabled; the toast explains". Tapping
HEAR with no partner says `PICK A PARTNER FIRST.` So does KEEP, and so does
`▶ THEIRS`.

**Each move shows what you'll hear**, in `design/mutate-v2/Moves.dc.html`'s
frozen copy, verbatim, on one line directly under the move chips:

| Move | Line |
|---|---|
| STACK | `BOTH AT ONCE. THICKER.` |
| SPLICE | `THIS ATTACK, THAT TAIL.` |
| SPLIT | `MY LOWS, THEIR HIGHS.` |
| MORPH | `A HIT BETWEEN THE TWO.` |
| MORPH with BECOME above OFF | `STARTS AS MINE, TURNS INTO THE MIX.` (a new line, the brief's) |
| ROOM | `MY HIT, PLAYED IN THEIR ROOM.` |
| TRANSPLANT | `MY TIMING, THEIR TONE.` |

The move names do not change: they are in the CLI, the recipe and the lineage.
The line changes when BECOME moves off OFF on MORPH, so it always describes what
HEAR would play.

**The knob sits directly under the moves**, below the move line, with its meaning
as a sub-label, in the house copy:

| Knob | Sub-label |
|---|---|
| AT (SPLICE) | `WHERE THEY HAND OVER` |
| HZ (SPLIT) | `WHERE LOWS BECOME HIGHS` |
| MIX (MORPH) | `HOW FAR TOWARD THEM` |
| WET (ROOM) | `HOW MUCH ROOM` |
| BANDS (TRANSPLANT) | `HOW FINELY TONE IS READ` |

The sub-label sits on the knob's own line, after the value, as the brief's
sketch draws it: `AT  ━━━━●━━━━  40 ms   · WHERE THEY HAND OVER`. The cost is
the bar's width: on a 390 dp sheet the bar keeps about 86 dp once the 44 dp label
column, the 56 dp value column (`PSS:3120`, `PSS:3180`) and a 25-character
sub-label at 6.4 dp a glyph are taken (*estimate*, not measured). The M1 plan
measures it on the 390 dp board first. If the bar is too narrow to drag, the
alternative is a caption under the bar, which keeps the bar full width but adds
about 14 dp to each knob row; that change of the approved layout is the owner's
call (Decision 16), not this document's. BECOME's row on MORPH gets a sub-label in
the same place: `HOW LONG THE TURN TAKES` (*this spec's*; the brief gives no
BECOME sub-label, Decision 10).

**The empty rows become words, not dashes.** The row stays, so the card never
changes height (the existing law, `CT:2058`). One form for both dead rows, used
in the sketch, the strings table and the tests:
- STACK's knob row: the label column is blank, and the words
  `NO KNOB — THEY LINE UP ON THE HIT` (`Moves.dc.html:45`) fill the rest of the
  line. `deadKnobLine` returns the words.
- BECOME's row on the other five moves: the label column reads `BECOME`, and the
  words `ONLY MORPH TURNS OVER TIME` fill the rest of the line. The `—` in the
  brief's sketch (`BECOME — only MORPH turns over time`) is the gap between the
  label column and the words, not a drawn dash. `becomeMeaning` returns only the
  words; the label column is the row's own.

A dead row draws its words where the bar would be, at the same height as a live
row. It is not a control: TalkBack reads its words, once, and
offers no adjustment. The A1b phone check asked that the dead `—` rows "announce
sensibly or are skipped, not read out as a bare dash twice" (A1b plan, Task 5
line 10); words answer that.

### Section 2: buttons, safety, shipping

```
▶ HEAR THE RESULT
nothing changes your pad until you keep it
[ KEEP · SPLICE × B07 ]        [ DRIFT · BLEND & SAVE ]
[ UNDO · back to the original ]
```

**▶ HEAR THE RESULT** plays the move on the pad and never writes. Its label says
so. It renders through `MutateSheet.preview` (`MS:300-317`), the same render KEEP
writes, and plays through `audition()` with the pad's level and SHAPE, as `▶ HEAR`
does today (`PSS:1353-1355`). The line under it reads
`NOTHING CHANGES YOUR PAD UNTIL YOU KEEP IT` (42 characters, inside the
44-character row budget, `docs/UI_DESIGN.md:203-204`, house).

**The KEEP button states what it will save:** `KEEP · <MOVE> × <partner>`, for
example `KEEP · SPLICE × B07`.
- The partner side is the partner's short name: a pad on this kit by its tag
  (`B07`), a pad on another kit as `<KIT> <TAG>` (`SOUL B02`), a ROULETTE pick by
  the strip's form (`PadSheetBoxes.parentName`, `PadSheetBoxes.kt:75-77`), a room by
  its name, a file by its name.
- **The fit rule.** KEEP shares its row with DRIFT, as the brief's sketch draws
  it, and a half row holds about 21 characters on one line: the house shortened
  ROULETTE's and DRIFT's labels to `ROULETTE · CRATE DEAL` and
  `DRIFT · DEALS & SAVES`, 21 characters each, after a screenshot showed longer
  ones ellipsizing (`PSS:3717-3731`, house). Many labels do not fit:
  `KEEP · TRANSPLANT × B07` is 23 characters, and a room or file partner can add 24.
  Cutting the partner would leave `KEEP · TRANSPLANT × …`, a button that does not
  say what it saves. So the label never loses its partner (*this spec's*,
  Decision 17): when `KEEP · <MOVE> × <short>` is at most 21 characters it is one
  line; otherwise it is two lines, `KEEP · <MOVE>` over `× <short>`, and only the
  second line is cut, to 21 characters with `…`, never losing the partner's tag
  (where the tag comes last, `× SOUL… B02`, the kit's part is cut) or, for a room or
  a file, its first 8 characters. Two lines of the pixel type
  fit the 48 dp button (*estimate*: about 16 dp a line, as the note line is
  estimated below). `ActionButton` draws one line today (`maxLines = 1`,
  `PSS:3963`), so M2 adds a `maxLines: Int = 1` parameter that KEEP alone passes
  as 2. DRIFT's 20 characters stay on one line. TalkBack reads the uncut label
  (`ActionButton`'s `accessibilityLabel`, `PSS:3941`). The partner row above names
  the partner in full.
- **The first-tap rule.** If the current settings have not been heard since the
  last change, the first tap plays them, and the second tap keeps. "The
  settings" are the move, the partner and the knob, counted by their effect:
  - the move's knob counts only on a move that has one, so a dialled AT is not a
    change while the card is on STACK;
  - BECOME counts only on MORPH, since no other move reads it (`MS:73`, `MS:282`);
  - from M3, BUILD ON THIS counts, since it changes what the move starts from, and
    so does the base itself (what the move starts from, `MutateSheet.base`).
  "Heard" means a ▶ HEAR THE RESULT, or a first tap of KEEP, that played these
  exact settings. `▶ MINE` and `▶ THEIRS` do not count: they play the inputs, not
  the result. The heard state is cleared by any change of move, partner, knob,
  BECOME or BUILD ON THIS; by moving to another pad (it is `remember(slot)`); and
  by any write to the pad other than this card's own KEEP: DRIFT, UNDO, a
  treatment, SMEAR, DUST, SOFT HITS, anything that swaps the model.
  - **After a KEEP, in M2: unheard.** Until M3, HEAR and KEEP both read the pad's
    current file (`MS:306`, `MU:230`; bug 7). After a KEEP, HEAR would play the
    move applied to what was just kept, a compounded sound nobody has heard. So in
    M2 a KEEP always leaves the settings unheard and KEEP unlit, and the next KEEP
    plays first.
  - **After a KEEP, from M3: heard while BUILD ON THIS is off.** Once KEEP starts
    from the original, HEAR after a KEEP plays the same move on the same original,
    which is what was just kept, so the settings stay heard. With BUILD ON THIS on
    they become unheard, because the next KEEP would build on the new result. M3
    changes `heardAfter` for this, with a test written first and watched failing
    against M2's rule.
  - **How the card tells KEEP's own write from any other.** Every write swaps the
    model for a fresh instance (`model = fresh`, `PSS:1301`). `heard` holds the
    `Pending` it heard together with the model it heard it on, and counts only
    while that is still the live model (`===`). Any other write (DRIFT, UNDO,
    SMEAR, a treatment) swaps the model and so clears it without being named. The
    KEEP door alone re-stamps `heard` after its own write, with the fresh model and
    `heardAfter(KEEP, …)`, which is null in M2. A swap that changed nothing audible
    also clears it; that costs one extra play, never an unheard save.
  KEEP is drawn lit (the amber rim, `ActionButton`'s `lit`, `PSS:3926`) exactly
  while the settings are heard, the board's "KEEP IT lights only once you have
  heard it" (`design/mutate-v2/README.md:28`). The first tap's toast says nothing was saved:
  `THAT IS WHAT KEEP WRITES. NOTHING HAS CHANGED YET - TAP KEEP AGAIN.`
  (*this spec's*; the house never uses saving words for a preview,
  `docs/PERSONALITY.md:49-56`, J44).
- **After keeping, the result plays**, through the existing `auditionOnRefresh`
  idiom (`PSS:296`, `PSS:388-399`): the write sets the flag, and the effect plays
  the new file through the pad's SHAPE once the fresh model has decoded it.
- **KEEP or SAVE.** The word KEEP collides with KEEP ROOM (`COPY:1605-1606`) and
  TAPE's KEEP on other screens. The default is KEEP, since the house rule is one
  meaning per word on a card (`2026-09-13-fx-rack-expansion-design.md:164-168`).
  The alternative is SAVE, which DRIFT's new label already uses for the same act
  (Decision 1). The owner chose KEEP on 2026-10-04.

**DRIFT moves beside KEEP and is relabelled to say it saves:**
`DRIFT · BLEND & SAVE`, 20 characters. It picks a partner at random off the shelf,
blends toward it, saves, and plays the result.
- ROULETTE stays with the partner sources and only picks.
- DRIFT's behaviour is otherwise unchanged: still a one-tap blend and save, MORPH
  at 50 % (`MutateSheet.DRIFT_FRACTION`, `MS:86`), BECOME put back to OFF
  (`PSS:1462`). So the existing DRIFT tests (`MST:34`, `MST:319`, `MST:510`;
  `MutateTest`'s `drift` block) and `ConventionTest`'s DRIFT laws (`CT:1901`,
  `CT:1938`) stay, unedited. The one addition is the play after it, through
  `auditionOnRefresh`, as KEEP's.
- DRIFT does not take the first-tap rule. It is the card's one-tap write, labelled
  as one.
- DRIFT does not start from the original in M3; it builds on whatever the pad
  holds, as today (Decision 6).

**UNDO · BACK TO THE ORIGINAL** returns the pad to its original and says so
truthfully.
- **M1: dimmed, not disabled, and a true toast.** Today UNDO is
  `enabled = !busy && mutated != null && canUndo` (`PSS:3550`): grey with no toast,
  the silent middle the house bans. From M1 it is `enabled = !busy` and dimmed
  when there is nothing to undo, and a dimmed tap says why: with no mutate on the
  pad, `THIS PAD CARRIES NO MUTATE. NOTHING TO UNDO HERE.` (`UNDO_NOTHING`); with
  the take gone from the bin (`canUndo` false, `binDaysLeft == null`, `PSS:2591`),
  `THE BIN HOLDS NO EARLIER TAKE OF THIS PAD. THE MUTATE STAYS.`
  (`UNDO_NOT_BINNED`). Until M3, UNDO restores the newest bin take, so M1's
  `UNMUTATED` says exactly that,
  `UNDONE. THE TAKE BEFORE THE LAST MUTATE IS BACK FROM THE BIN.`, which is true
  after one MUTATE or two.
- M2 moves the button to the bottom row, still reading `UNDO`.
- M3 gives it the label `UNDO · BACK TO THE ORIGINAL` and makes its state true
  (below), once that label is true.

**Safety fix 1, for bug 7: KEEP starts from the original.** From M3, a second
KEEP starts from the pad's original, not from the last result. It uses DUST's and
SMEAR's restore-first idiom: when the pad carries a MUTATE, the take from before
it comes back first, then the move applies (`KB:526-529`, `KB:607-610`). HEAR and
KEEP always mean "this move on my pad".
- **How KEEP finds the original.** DUST and SMEAR restore the newest take, which is
  one step back. One step back is not the original after a BUILD ON THIS chain,
  after a DRIFT on a kept pad (DRIFT builds on what the pad holds, Decision 6), or
  after another screen rewrote the pad without changing its recipe (below). So KEEP
  does not take the newest take. From M3 every phone KEEP and DRIFT bins the take it
  replaces with a tombstone that records the pad as it stood (Safety fix 2). The
  original is the newest bin entry of the pad's file whose tombstone is this pad's
  (`padSnapshot.slot == slot`) and whose recipe does not carry a MUTATE. KEEP
  restores that entry directly (`restoreFromBin(entry)`), whatever lies between it
  and the live file. That is the brief's rule, with no exception, wherever the bin
  records the original (Decision 15).
- **The one case the bin cannot answer.** A pad mutated before M3, or one whose
  original was binned by a writer that leaves no tombstone, has no entry that
  records the original. There KEEP restores the newest take of the pad's file, as
  DUST and SMEAR do, and the toast says plainly that the bin does not record
  whether that take is the original (FROM_BEFORE, below). Nothing can do better:
  the record does not exist. The case shrinks to nothing as old takes purge
  (`BIN_KEEP_DAYS`, `KB:1291`).
- **What the skipped entries cost.** The entries between the original and the live
  file (a chain's in-between results, the take a DRIFT replaced, another screen's
  untombstoned takes) stay in the bin. KEEP re-bins the restored original when it
  writes, so the original is the newest take again and the next UNDO finds it. The
  skipped entries sit behind it, reachable from the BIN screen. They become the
  newest take only after something restores the original out of the bin without
  re-binning it; that is the UNDO question (Decision 4).
- **What "carries a MUTATE" means.** The pad's recipe reads as a mutate
  (`MutateSheet.read`, `MS:199-211`) and not as an OUTSIDE ROOM trip. OUTSIDE's ROOM
  writes its recipe through `Mutate.apply` with an `outside` block inside `mutate`
  (`OutsideSheet.kt:151-156`, read at `:194-197`); OUTSIDE has its own UNDO
  (`PSS:2620-2624`), and MUTATE's KEEP must not take OUTSIDE's result off.
- **The last result is replaced.** The idiom restores the bin's take over the live
  file without binning the live one (`untreatPad` → `restoreFromBin`, `KB:804-810`,
  `KB:1101-1105`), so a second KEEP replaces the first result; the first result does
  not go to the bin. Its recipe still describes it, so it can be rebuilt while its
  partner exists, but a file partner is held one at a time (`MS:158`) and a ROULETTE
  pick may change. The keep toast says the last result was replaced. Binning it too
  is Decision 5's alternative, with its cost. The same holds when another screen
  rewrote the pad since without changing its recipe: `replaceAudio(slot, null)`
  keeps the recipe (`recipe ?: it.recipe`, `KB:505`), from SPLIT
  (`app/src/main/kotlin/com/snipsnap/app/ui/SplitScreen.kt:364`), SNAP
  (`app/src/main/kotlin/com/snipsnap/app/ui/SnapScreen.kt:514`, `:567`, `:620`,
  `:714`) and SURFACE (`app/src/main/kotlin/com/snipsnap/app/ui/SurfaceScreen.kt:1010`).
  The pad then still carries the MUTATE, so a default KEEP replaces that rewrite,
  as DUST's and SMEAR's restore-first already do over such a write. The take that
  rewrite replaced stays in the bin, untombstoned.
- **A BUILD ON THIS switch keeps today's chaining on purpose.** With it on, KEEP
  starts from the pad as it is now, and the current take goes to the bin. It is a
  toggle chip drawn on its own row above KEEP, on every pad, so the card never
  changes height. It is dimmed while the pad carries no MUTATE, and a tap then says
  `NOTHING TO BUILD ON YET. THIS PAD CARRIES NO MUTATE.` While it is on, the note
  line reads `KEEP BUILDS ON THIS RESULT` instead. It defaults to off, is held per
  pad (`remember(slot)`), and opens off on every pad (Decision 2).
  - **It never outlives the mutate it builds on.** When the pad stops carrying a
    MUTATE (an UNDO back to the original, a treatment that replaces the recipe, a
    DELETE), the card turns BUILD ON THIS off (an effect keyed on `carriesMutate`).
    A tap on it while it is on always turns it off, dimmed or not. And `base()`
    asks `carriesMutate` before it asks `buildOn`, so a stale switch can never
    produce ON_TOP, the `KEEP BUILDS ON THIS RESULT` note or the `BUILT ON THE LAST
    RESULT` toast on a pad with no result to build on. The note line reads
    `KEEP BUILDS ON THIS RESULT` only while `buildOn && carriesMutate`.
- **The recipe stays regenerable.** With BUILD ON THIS off, the recipe always
  describes the move applied to the pad's original, and the original is in the bin,
  so recipe and original rebuild the sound, the promise of `docs/CLI.md:539-540`.
  That holds while the last write to the pad was the card's own KEEP; a later
  recipe-keeping rewrite from another screen (above) breaks it until the next KEEP,
  as it breaks every treatment's recipe today. With BUILD ON THIS on, the recipe is
  the last step, exactly as the CLI's own chaining writes it and as the house rule
  says: "The recipe is the LAST step, not the stack" (`RecipeReplay.kt:22-26`;
  `REPLAY_LAST_ONLY`, `COPY:1541`). No recipe key is added by default (Decision 7).
- **What KEEP started from is said.** The keep toast names it, one of five cases
  ("Architecture", `MutateSheet.Base`):

  | Case | When | Toast tail (*this spec's* wording) |
  |---|---|---|
  | FIRST | the pad carried no MUTATE | `ORIGINAL SLEEPS IN THE BIN.` |
  | FROM_ORIGINAL | restore-first, from the entry the bin records as the original | `MADE FROM THE ORIGINAL. THE LAST RESULT IS REPLACED.` |
  | FROM_BEFORE | restore-first, and no entry records the original (a pad mutated before M3, or binned without a tombstone), so the newest take was used | `MADE FROM THE NEWEST TAKE IN THE BIN, WHICH THE BIN DOES NOT MARK AS THE ORIGINAL. WHAT THE PAD HELD IS REPLACED.` |
  | ON_TOP | BUILD ON THIS was on, on a pad that carries a MUTATE | `BUILT ON THE LAST RESULT, WHICH SLEEPS IN THE BIN.` |
  | NO_ORIGINAL | the pad carries a MUTATE but the bin holds no earlier take (purged after 30 days, emptied, or a copied twin) | `OVER THE LAST RESULT - NO EARLIER TAKE IN THE BIN TO START FROM.` |

  NO_ORIGINAL is SMEAR's `treatedStacked` honesty applied to MUTATE
  (`COPY:1455-1456`, `PSS:682-686`). Under Decision 15's alternative (one step
  back, as DUST and SMEAR), a sixth case returns, FROM_EARLIER,
  `MADE FROM AN EARLIER RESULT. THE LAST ONE IS REPLACED.`

**Safety fix 2, for bug 8: UNDO's state and message are true.** From M3 the card
and the strip reflect what the pad actually holds.
- Every phone KEEP and DRIFT bins the take it replaces together with the pad as it
  stood, recipe included: a tombstone, the JSON sidecar `moveToBin` already writes
  for an ejected pad (`KB:1256-1273`). So the bin knows what each binned take was.
- UNDO restores a binned take and puts back the recipe and provenance that take
  had, read from its tombstone. So after UNDO the TREATMENT box, the MUTATE strip
  and the card read what the pad holds, including a treatment applied before the
  mutate.
- **Which take: the original (Decision 4, answered by the owner on 2026-10-04).**
  The brief says UNDO returns the pad to its original. The default follows it:
  UNDO restores the entry the bin records as the original, found as KEEP finds it,
  directly. In the common case (KEEPs with BUILD ON THIS off, no DRIFT on top) that
  entry is also the newest take, so nothing changes from one step back. After a
  BUILD ON THIS chain or a DRIFT on a kept pad it is not, and the direct restore has
  a cost KEEP's does not: UNDO does not re-bin the original, so the skipped entries
  (the chain's in-between results) become the bin's newest takes of the pad's file.
  The newest-first restores then find them. TREATMENT's UNDO restores the newest
  take (`untreatPad`, `KB:804-810`), and DUST's and SMEAR's restore-first do too
  (`KB:526-529`, `KB:607-610`). So if the original carried a treatment, a later
  treatment UNDO would bring back a stale mutate result. The alternative is one step
  back in those cases (`UNDO · ONE STEP BACK`), which keeps the bin's order but
  does not do what the brief's label says. The owner chose the original on
  2026-10-04, with this cost.
- UNDO has four states, computed by `MutateSheet.undoState` ("Architecture"):

  | State | When | Button | On tap |
  |---|---|---|---|
  | TO_ORIGINAL | the bin records the original | `UNDO · BACK TO THE ORIGINAL` | restores it; `THE ORIGINAL IS BACK FROM THE BIN.` |
  | ONE_STEP | the pad carries a MUTATE, the bin holds takes of its file, and none is recorded as the original (a pad mutated before M3) | `UNDO · ONE STEP BACK` | restores the newest take; `ONE STEP BACK. ANY EARLIER TAKE WAITS IN THE BIN.` |
  | NOTHING | the pad carries no MUTATE | `UNDO · BACK TO THE ORIGINAL`, dimmed | `THIS PAD CARRIES NO MUTATE. NOTHING TO UNDO HERE.` |
  | NOT_BINNED | the pad carries a MUTATE and the bin holds no earlier take | `UNDO · BACK TO THE ORIGINAL`, dimmed | `THE BIN HOLDS NO EARLIER TAKE OF THIS PAD. THE MUTATE STAYS.` |

  ONE_STEP is the one place the brief's label cannot be true, because the record
  does not exist. Under Decision 4's alternative, ONE_STEP also covers a BUILD ON
  THIS chain and a DRIFT on a kept pad, and its toast names the result it returns
  to: `ONE STEP BACK: THE PAD IS <WORD> × <PARTNER> AGAIN.`

**Honest refusals, for bug 9.** Typed refusals live in `MutateSheet`. Each
refusal names the control by the word the screen prints
(`docs/UX_WIRING_REVIEW_2026_09.md:41-43`). The card checks the pad before it
launches anything, as it already pre-checks layers. The new lines (*this spec's*
wording, each a `Copy` constant that shouts and ends in a full stop, `PT:726`):

| Refusal | When | Line |
|---|---|---|
| `NO_PARTNER` | HEAR, KEEP or `▶ THEIRS` with no partner | `PICK A PARTNER FIRST.` (the brief's line) |
| `LAYERED` | the pad has velocity layers (SOFT HITS, or STACK THE TAKES) | `SOFT HITS IS ON: THIS PAD HAS LAYERS. MUTATE WANTS ONE SAMPLE - TURN SOFT HITS OFF FIRST.` |
| `CHAINED` | the pad plays a chain of slices in turn (CHOP's FOLD, the CLI's `robin`, or `--break-pad`) | `THIS PAD PLAYS ITS SLICES IN TURN. MUTATE WANTS ONE SAMPLE - SEND THE CHOP AGAIN IN CLASSIC FOR PADS OF ONE SAMPLE.` (Decision 3) |
| `SHELF_EMPTY` | ROULETTE or DRIFT finds no other pad on the shelf | `THE SHELF HOLDS NO OTHER PAD. ROULETTE HAS NOTHING TO PICK.` (the text of `CRATE_EMPTY`, whose name stays) |
| `ONLY_COPIES` | ROULETTE or DRIFT finds only near-doubles of this pad (every candidate within `Crate.DUPE_DISTANCE` = 0.02, `shell/src/main/kotlin/com/snipsnap/shell/Crate.kt:30`) | `EVERY OTHER SOUND ON THE SHELF IS A DOUBLE OF THIS PAD. PICK THE PARTNER YOURSELF.` (the brief's word, doubles) |
| `PARTNER_GONE` | the picked partner is no longer there: a pad deleted, another kit removed, a room or file gone | `THAT PARTNER IS GONE. PICK ANOTHER.` |

- **The layered line names SOFT HITS**, the chip the pad sheet prints
  (`PSS:2393-2401`). Turning it off calls `clearGhostLayers` (`PSS:1115`), which
  clears every velocity layer: GHOSTS' rendered soft hits, and STACK THE TAKES'
  layers, which are copies of bin takes whose sources stay in the bin
  (`KB:404-414`, `KB:813-819`). So the line points at a safe action for both
  sources. `MUTATE_NEEDS_ONE` (`COPY:1522`) stays as it is, because DE-SAMPLE
  also shows it (`PSS:1976`); MUTATE gets its own constant.
- **The chained line says what is true, and the brief's ask is not met.** The brief
  asks that a chained pad "says how to unchain". The phone has no control that
  unchains a pad, so no line can say that truthfully; this is flagged to the owner
  (Decision 3). On the phone a chain comes from CHOP's FOLD, landed through SEND
  TO PADS, which builds a new kit (`KitBuilderModel.fromChop`, `KB:1329-1333`) or
  lands on an empty bank only (`landArranged` requires one, `KB:312-316`); the FOLD
  and CLASSIC segments are at
  `app/src/main/kotlin/com/snipsnap/app/ui/ChopScreen.kt:922-931` and `SEND TO PADS`
  at `:1130`. Sending the chop again in CLASSIC makes new pads of one sample each;
  it leaves this pad chained. From the CLI a chain comes from `robin`, undone by
  `robin --undo` (`docs/CLI.md:574-590`; the gate's own message,
  `requireNotChained`, `KB:1186-1192`), and from `--break-pad` on `chop`, `dig` and
  `beat` (`ChopCommand.kt:316-319`), which `robin --undo` does not undo. The default
  line names only the phone's way to a pad of one sample, in the word SLICES, not
  TAKES, because a take is a bin file elsewhere in MUTATE's copy. Copy shouts, so a
  CLI command inside it would read `ROBIN --UNDO`, which does not run as typed
  (the flag is lower case); that is part of Decision 3's cost.
- **ROULETTE and DRIFT stop mapping every exception to the empty-shelf line.**
  `Mutate.roulette` throws a typed `Mutate.RouletteRefused(kind, message)`, a
  subclass of `IllegalArgumentException` with today's two messages, unchanged
  (`MU:89`, `MU:101-103`). The CLI catches `IllegalArgumentException` and prints the
  message (`MutateCommand.kt:95-96`, `DriftCommand.kt:45-46`), so its output does not
  change. The card maps only that type; every other exception is a real failure and
  gets `<ACTION> FAILED. TRY AGAIN.` with the screen's word for the action
  (`COPY:2145`).
- **A chained pad on DRIFT** now hits the pre-check and gets the chained line, not
  the empty-shelf line.

**Also, in M1:**
- **ROULETTE and A FILE are undimmed** in the starting state. They are actions,
  not states, so they are drawn at full ink at rest; a pick still shows in their
  label (`ROULETTE · SOUL A03`, `A FILE ▸ HIT.WAV`).
- **STACK's polarity flip is reported in the keep toast**, as the CLI reports it:
  `… B07 WAS FLIPPED: IT WAS CANCELLING THE PAD.` (`Outcome.flipped`, `MU:46`).
- **Length changes are mentioned where they happen**: in HEAR's play (a toast,
  only when the length changes) and in the keep and DRIFT toasts:
  `IT RUNS 1.4 S NOW, NOT 0.3 S.` A change counts when the result's length differs
  from the pad's by at least 10 % and at least 50 ms (*this spec's*, Decision 11).
  DRIFT's own toast, `Copy.drifted`, is unchanged (`PT:799`); the length line is a
  second sentence the card appends. Nothing in today's code hands the card the
  after-length (`Mutate.Outcome` is the pad and the flips, `MU:46`; `Drifted` is the
  pick and the outcome, `MU:264`; the card's own `snip` is a capped mono decode,
  `PSS:327`), so M1 adds a small `:shell` seam for it ("Architecture").

### Every string on the card, by round

What the card reads after each round, top to bottom. "—" means the row does not
exist in that round.

| Row | Today | After M1 | After M2 | After M3 |
|---|---|---|---|---|
| title | `ONE HIT FROM TWO` + `UNDO` | unchanged | removed (Decision 9) | — |
| pairing line | status line `PICK A MOVE AND A PARENT` / `<WORD>: <parents>` | `A02 SNARE × B07 KICK` / `A02 SNARE × ?  — PICK A PARTNER` (no buttons) | the same, with `▶ MINE` `▶ THEIRS` | MINE plays the original unless BUILD ON THIS |
| partner | chip rows, rooms, other kits, `A FILE ▸`, `ROULETTE · CRATE DEAL` | the same rows, undimmed; ROULETTE full width, `ROULETTE · PICK A PARTNER OFF THE SHELF`; they sit below BECOME | `PARTNER · <name>  CHANGE ▸`; the rows move into the panel | unchanged |
| moves | six chips | unchanged | unchanged | unchanged |
| move line | — | the move's line | unchanged | unchanged |
| knob | `AT` … or `—` | moved under the move line; its meaning after the value; STACK's words with a blank label column | unchanged | unchanged |
| BECOME | `BECOME` or `—` | `HOW LONG THE TURN TAKES` after the value on MORPH; elsewhere `BECOME` in the label column and `ONLY MORPH TURNS OVER TIME` as the words | unchanged | unchanged |
| hear | `▶ HEAR` | `▶ HEAR THE RESULT` | unchanged | unchanged |
| note | — | `NOTHING CHANGES YOUR PAD UNTIL YOU KEEP IT` | unchanged | or `KEEP BUILDS ON THIS RESULT` while BUILD ON THIS is on and the pad carries a MUTATE |
| build | — | — | — | `BUILD ON THIS` |
| commit | `MUTATE ▸` | `KEEP` beside `DRIFT · BLEND & SAVE` | `KEEP · <MOVE> × <partner>` (two lines when it does not fit one), lit when heard, first-tap rule | unchanged |
| undo | top right `UNDO`, grey with no reason | top right `UNDO`, dimmed with a toast when there is nothing to undo; true toast | bottom row, `UNDO` | `UNDO · BACK TO THE ORIGINAL`, or `UNDO · ONE STEP BACK` on a pad whose bin does not record its original |

Three rows move earlier than the brief's round table says, and this document
records why. The knob moves under the moves in M1, because the move line and the
knob's meaning are read together and the brief assigns the move to no round. The
commit verb becomes KEEP in M1, because DRIFT moves beside KEEP in M1 and the
note line names KEEP. The bare `▸` comes off the commit button in M1, since KEEP
writes in place and the house's glyph rule reserves `▸` for "goes somewhere"
(`docs/UX_WIRING_REVIEW_2026_09.md:100`; ROULETTE and DRIFT dropped theirs for that
reason, `PSS:3726-3729`).

**M1's status line.** Before M2 builds the header, the status line already reads
the pairing line, so the card names the pending partner from M1 on. What the pad
already carries stays on the box's strip, which the group box shows while the box
is open as well as closed (`app/src/main/kotlin/com/snipsnap/app/ui/GroupBox.kt:94-108`; the strip text is
`PadSheetBoxes.mutate`, `PadSheetBoxes.kt:70-72`).

**Card height.** After M2 the card is about 560 dp: the pairing line and the
partner row at 48 dp each, two move rows of 48 dp, the move line about 16 dp, the
knob and BECOME rows at 48 dp each, HEAR at 48 dp, the note
about 16 dp, KEEP and DRIFT at 48 dp, UNDO at 48 dp, and 4 dp gaps; M3's BUILD ON
THIS row adds 52 dp (*estimate*); Decision 16's caption alternative would add
about 28 dp. Today's card is about 700 dp before rooms and
other kits (walkthrough §1, *estimate*). The height no longer depends on the
kit's pad count, the rooms kept or the other kits, because those move into the
panel.

### Accessibility

- Every control is at least `MIN_HIT_TARGET` (48 dp, `Schemes.kt:430`) tall.
- A dimmed control stays enabled for TalkBack and touch. It reads its label, and a
  tap reads its toast. From M1, only `busy` disables a control on the card. Today
  UNDO is also disabled when there is nothing to undo (`PSS:3550`); M1 makes it
  dimmed with a toast, like HEAR and KEEP ("UNDO · BACK TO THE ORIGINAL").
- The dead rows' words are their content description. They are not adjustable and
  are not read as a bare dash.
- **State is spoken by folding it into the label,** the house's existing idiom:
  `ToggleChip` reads `"$label, ON"` or `"$label, OFF"` (`PSS:3206`), because
  `tapeClick` sets only `contentDescription` (`app/src/main/kotlin/com/snipsnap/app/ui/Chrome.kt:180-188`)
  and has no selected or state parameter. Today's move chips carry no selected
  state either; they show it only in the bevel's fill (`PSS:3562-3576`). The
  rounds add it as follows, and these are the signature changes listed under
  "What changes in `MutateCard`":
  - `▶ MINE` and `▶ THEIRS` (M2) carry spoken labels through `ActionButton`'s
    existing `accessibilityLabel` (`PSS:3941`): `PLAY MINE, A02 SNARE` and
    `PLAY THEIRS, B07 KICK`, with `, BEFORE ITS MUTATE` added to MINE from M3 when
    its base is not the live file.
  - KEEP's spoken label (M2) is the uncut `keepSpoken` plus its state,
    `, HEARD` or `, NOT HEARD YET`, through the same `accessibilityLabel`. No new
    `ActionButton` parameter is needed for this; `maxLines` is the one KEEP needs
    ("The fit rule").
  - The move chips (M1) and the partner panel's chips (M2) fold `, SELECTED` into
    their `tapeClick` label when selected. A move chip's label is then
    `"$m, SELECTED"` or `m`.
  - BUILD ON THIS (M3) is a `ToggleChip`, so it reads ON or OFF already. Being
    dimmed while still tappable needs a new `dimmed: Boolean = false` parameter on
    `ToggleChip` (`PSS:3187-3217` has `enabled` only), drawn as `ActionButton`'s
    `dimmed` is.

## Architecture

`:app` cannot be compiled in the authoring environment (no Android SDK; `:app` is
not in the local Gradle build, `settings.gradle.kts`). So every decision the card
makes moves into `:shell` as a pure, tested function, and `MutateCard` only draws
what those functions return. `:app` is checked by `ConventionTest`'s source-text
laws, by CI's `android-build` and `emulator-tests`, and by the owner's phone. This
is the house's answer to having no Compose harness
(`docs/UX_JOURNEY_PLAN_2026_09.md:28-39`, house).

### What moves into `:shell`

All in `MutateSheet` unless named. Words returned by these functions are card
labels, not `Copy` constants, so `PT:726` cannot see them; their tests hold them
to the house style (capitals, budgets, no retired word), as the BECOME row's
labels are held by hand today (`MS:94-101`).

**Words (M1).**
- `outcomeLine(mode: Mutate.Mode, becomeFraction: Float): String`: the move's line
  from `Moves.dc.html`, and `STARTS AS MINE, TURNS INTO THE MIX.` for MORPH when
  `becomeFraction` maps above 0 ms (the same `value(BECOME, …)` the render reads).
- `knobMeaning(mode): String?`: the five sub-labels; null for STACK.
- `deadKnobLine: String` = `NO KNOB — THEY LINE UP ON THE HIT`.
- `becomeMeaning(mode): String`: `HOW LONG THE TURN TAKES` on MORPH,
  `ONLY MORPH TURNS OVER TIME` on every other move.
- `pairLine(padTag: String, padName: String, partnerName: String?): String`:
  `A02 SNARE × B07 KICK`, or `A02 SNARE × ?  — PICK A PARTNER`. In M1 the line has
  the full row to itself (44 characters); from M2 it is at most `PAIR_CHARS` = 28
  (*this spec's*, the budget under "The pairing line"), each side cut to an even
  share with its tag whole. In the empty state `× ?  — PICK A PARTNER` is never
  cut: the pad's name is cut or dropped and its tag kept (`A02 × ?  — PICK A
  PARTNER`, 25). `pairSpoken(…)` is the uncut form.
- `partnerLine(partnerName: String?): String` (M2): `PARTNER · B07 KICK`, or
  `PARTNER · NONE YET`; the row's `CHANGE ▸` is a separate button.
- `partnerName(partner: Partner, padName: (Int) -> String?): String` (long, the
  pairing line and the partner row) and `partnerShort(partner): String` (short, KEEP).
  A pad on this kit is `<TAG> <NAME>` long and `<TAG>` short; another kit is
  `<KIT> <TAG>`; a ROULETTE pick is `PadSheetBoxes.parentName(label)` (`KIT A03`);
  a room is its name; a file is its name. All upper case (`Locale.ROOT`), all cut
  to 24 characters at most, the label rule the TERRA spec sets for the shared
  chooser (`MAX_FROM_CHARS`, that spec `:1434-1444`). Where the tag comes last
  (another kit, a ROULETTE pick), a cut shortens the kit's part and keeps the tag
  whole (`VERYLONGKITNAMEHE… B02`), here and in KEEP's second line.
- `HEAR_LABEL`, `NOTE_LINE`, `DRIFT_LABEL` = `DRIFT · BLEND & SAVE`,
  `ROULETTE_LABEL` = `ROULETTE · PICK A PARTNER OFF THE SHELF`, `NO_ROOM_LINE` =
  `NO ROOM YET · MAKE ONE IN OUTSIDE ▸ ROOM`, `NO_OTHER_KIT_LINE` =
  `NO OTHER KIT ON THE SHELF`.
- `lengthMs(model, slot): Int` and `lengthMs(snip: Snip): Int` (M1): the pad's
  file length and a render's length in milliseconds (`frameCount * 1000 /
  sampleRate`). The KEEP and DRIFT doors call the first inside `withFreshKit`
  before and after `MutateSheet.apply` or `MutateSheet.drift`, whose signatures and
  return types do not change, so `MST`, `CT:1901`, `CT:1938` and `CT:2002` read
  them as before. HEAR compares `lengthMs(rendered)` with the pad's. A `:shell`
  test checks both against a known WAV, at 44.1 and 48 kHz.
- `Copy` (M1): `MUTATE_PICK_PARTNER`, `MUTATE_LAYERED`, `MUTATE_CHAINED`,
  `ROULETTE_ONLY_COPIES`, `MUTATE_PARTNER_GONE`, `UNDO_NOTHING`, `UNDO_NOT_BINNED`,
  new text for `CRATE_EMPTY` and
  `UNMUTATED` (names kept: `PT:649-676` lists them as legacy toasts that must exist
  by name), `mutated(move, pad, partner, flipped: List<String> = emptyList(),
  lengthNote: String = "")` (the defaults keep `PT:810-813`'s three-argument calls
  compiling unedited, and it still starts `<MOVE>: <PAD> × <PARTNER>.`, the shape
  those lines pin), `lengthNote(beforeMs,
  afterMs)`, and the retired-word fixes to `fileRefused` and `replayNeedsParent`
  (renamed `replayNeedsPartner`; the CLI's `recipe` refusal changes with it, "One
  word: PARTNER").
- `Copy` (M2): `roomKept` and `roomLanded` stop sending the user to `MUTATE ▸
  ROOM`, which names no control once rooms move into the panel
  (`COPY:1606`, `COPY:1620`). They read `<NAME> IS ON THE SHELF. ANY PAD CAN PLAY
  IN IT - PICK IT AS THE PARTNER IN MUTATE.` and `<NAME> LANDED ON THE SHELF. ANY
  PAD CAN PLAY IN IT - PICK IT AS THE PARTNER IN MUTATE.` (*this spec's*). Both
  still start with the room's name, contain `MUTATE` and end in a full stop, which
  is what `RoomsTest:174` and `:188` pin, so those tests pass unedited. Their KDocs
  (`COPY:1605`, `COPY:1619`) and the comments at
  `shell/src/main/kotlin/com/snipsnap/shell/Rooms.kt:15` and
  `app/src/main/kotlin/com/snipsnap/app/ui/KitsScreen.kt:487` are updated, and so
  is `app/README.md:494-509`, which describes `MUTATE ▸ MORPH / STACK / ROOM` and
  which `ConventionTest` scans (the readme inputs in `shell/build.gradle.kts`).

**Typed refusals (M1).**
- `sealed interface Refusal` with `NoPartner`, `Layered`, `Chained`, `ShelfEmpty`,
  `OnlyCopies`, `PartnerGone`, each carrying its `Copy` line.
- `refusalBefore(pad: KitPad, partner: Partner?, needsPartner: Boolean): Refusal?`:
  the pre-check every door runs before it launches. Order: `Layered`, then
  `Chained`, then `NoPartner`; a pad that can never mutate says so before asking for
  a partner. DRIFT and ROULETTE pass `needsPartner = false`.
- `refusalOf(e: Throwable): Refusal?`: maps `Mutate.RouletteRefused` (its two kinds)
  and `MutateSheet.PartnerGone`. Every other exception maps to null, and the card
  shows `actionFailed(<screen word>)`.
- `PartnerGone`, an `IllegalArgumentException` subclass, thrown by `source` for
  every kind of partner, checked before any read:
  - `Pad`: no pad on the slot (`MS:238` today, same message);
  - `Other`: the kit folder or its `kit.json` is missing, checked before
    `KitStore.load`, which would otherwise throw a plain `IOException("no kit.json
    in …")` (`KitStore.kt:35`, reached from `MS:244`), or the kit has no pad on the
    slot (`MS:245-246`, same message);
  - `Deal`, `Room` and `Wav`: the file is missing (`!file.isFile`), checked before
    `WavReader.read`, which would otherwise throw an `IOException`
    (`MS:241`, `MS:242`, `MS:251`).
  A file that exists but does not decode is a real failure and keeps the generic
  toast. One `MST` test per kind deletes the partner and asserts `PartnerGone`.
- `Mutate.RouletteRefused(kind: EMPTY | ONLY_COPIES, message)`: thrown at `MU:89` and
  `MU:101-103` with today's messages. A subclass of `IllegalArgumentException`, so
  every existing catch and test still holds.

**The first-tap rule (M2).**
- `data class Pending(mode: String, partner: Partner?, knob: Float?, become: Float?,
  buildOn: Boolean, base: String?)`, built by `pending(mode, partner, knobFraction,
  becomeFraction, buildOn, base)`, which stores the knob only for a move that has
  one and BECOME only for MORPH. Equality is the rule's whole test. `base` is null
  in M2 (every move starts from the live file) and from M3 is `BaseChoice.key`
  (below), so a different base is a different `Pending` and counts as unheard.
- `keepTap(heard: Pending?, now: Pending): Tap` returns `PLAY_FIRST` or `KEEP`.
- `heardAfter(event: HEAR | FIRST_TAP | KEEP | OTHER_WRITE, now: Pending): Pending?`:
  `now` after HEAR and FIRST_TAP; null after OTHER_WRITE. After KEEP: null in M2,
  because HEAR and KEEP still start from the live file, which the KEEP just changed;
  from M3, `now` with BUILD ON THIS off and null with it on. After a KEEP the door
  passes a `now` whose `base` it computed on the fresh model inside the lock, after
  its write, because a KEEP can move the base: the first KEEP on a pad turns it
  from `FIRST` (the live file) into `FROM_ORIGINAL` (the binned original). The key
  is built from what the entry holds, not its name or time (below), so a later
  KEEP's re-binning of the same original leaves it unchanged.
- `KEEP_CHARS` = 21, the half row (*this spec's*, from `PSS:3717-3731`).
  `keepLabel(mode: Mutate.Mode, partner: Partner?): KeepLabel`, with
  `data class KeepLabel(line1: String, line2: String?)`: one line,
  `KEEP · <MOVE> × <short>`, when it is at most `KEEP_CHARS`; otherwise
  `KEEP · <MOVE>` over `× <short>`, the second line cut to `KEEP_CHARS` with `…`,
  always keeping the partner's tag whole (cutting the kit's part where the tag comes
  last) or a room's or file's first 8 characters. `KEEP · <MOVE>`
  alone with no partner. `keepSpoken(mode, partner)` is the uncut form for
  TalkBack. `KEEP_FIRST_TAP` is the first tap's `Copy` line.
- `KEEP_PAD_CHANGED` (M3, `Copy`): `THE PAD CHANGED SINCE YOU HEARD IT. NOTHING
  WAS KEPT - TAP KEEP TO HEAR IT FIRST.` (*this spec's*), for a base that changed
  between the tap and the lock ("Failure handling").

**The base, KEEP from the original, and UNDO (M3).**
- `carriesMutate(recipe): Boolean` = `read(recipe) != null &&
  OutsideSheet.read(recipe) == null`.
- `enum class Base { FIRST, FROM_ORIGINAL, FROM_BEFORE, ON_TOP, NO_ORIGINAL }` and
  `data class BaseChoice(base: Base, entry: BinEntry?)`, with `key: String`: the
  base's name plus the entry's tombstoned recipe and its file's length, or `LIVE`
  plus the pad's sample file, for `Pending`.
- `originalEntry(bin: List<BinEntry>, pad: KitPad): BinEntry?`: the newest entry
  with `originalName == pad.sampleFile`, a tombstone (`padSnapshot != null`) whose
  `slot` is the pad's, and a recipe that does not `carriesMutate`.
- `base(model, slot, buildOn): BaseChoice`, read-only, in this order: with no MUTATE
  carried, `FIRST` (whatever `buildOn` says); with `buildOn`, `ON_TOP`; else
  `originalEntry` gives `FROM_ORIGINAL`; else the newest entry of the pad's file
  (`binContents`, `KB:1077-1089`) gives `FROM_BEFORE`; with no entry,
  `NO_ORIGINAL`. A tombstone's recipe is judged by the same `carriesMutate` as the
  pad's, so an OUTSIDE ROOM take reads the same way on the pad and in the bin.
  The listing is read under `KitWrites.mutex` by the caller, as below.
- `baseSnip(model, choice): Snip`: the entry's bytes for the two FROM cases, read
  in place without restoring (`WavReader.read(entry.file)`); else the live file.
  This is what `▶ MINE` plays from M3.
- `preview(model: KitBuilderModel, slot: Int, partner: Partner, mode: Mutate.Mode,
  fraction: Float, becomeFraction: Float = 0f, buildOn: Boolean = false): Snip`:
  today's preview over `baseSnip`, so HEAR plays "this move on my pad". It keeps
  `requireRewritable(slot)` as its first line (`MS:305`).
- **Reading the bin on the live model.** HEAR and `▶ MINE` run on the live model
  with no write in flight, but `moveToBin` copies and then deletes bin files under
  `KitWrites.mutex` (`KB:1266-1267`), and `binContents` lists a half-copied file,
  since `BIN_NAME` matches it (`KB:1077-1089`). So, as `playBefore` does
  (`PSS:409-428`), the doors choose the base (`base(…)`, the listing included)
  inside `KitWrites.mutex.withLock { … }` and decode it outside the lock. A purge
  racing the decode reads as gone, which is true.
- `keep(model: KitBuilderModel, slot: Int, partner: Partner, mode: Mutate.Mode,
  fraction: Float, becomeFraction: Float, buildOn: Boolean, expected: String?): Kept`,
  the M3 replacement for the card's call to `apply`, called inside `withFreshKit`
  (so under the writers' lock), in this order:
  1. `model.requireRewritable(slot)` first, as `preview` does (`MS:305`), so a pad
     that became layered or chained between the tap and the lock refuses before the
     bin is touched. `restoreFromBin(entry)` has no such guard (`KB:1101-1125`),
     unlike `untreatPad` (`KB:806`), and the door's own re-check covers layers only
     (`freshPad.velocityLayers.isEmpty()`, `PSS:1296`);
  2. compute the base (`base`); when `expected` is not null and differs from the
     base's `key`, return `Kept.NotHeard` and touch nothing;
  3. read the partner (`source`); a missing partner throws `PartnerGone` here,
     before anything is touched;
  4. render from `baseSnip` with `Mutate.render`, and build the recipe with
     `Mutate.recipeFor` (below); a render that throws stops here, with the pad's
     file, its recipe and the bin exactly as they were;
  5. only then, for the two FROM cases, restore the entry (`restoreFromBin(entry)`,
     `KB:1101-1125`, which also deletes its tombstone) and put back the recipe and
     provenance its tombstone records;
  6. write the finished sound with `Mutate.write` (below), with the pad as it now
     stands as the bin snapshot.
  `Kept` is `Done(outcome, base, beforeMs, afterMs, heardKey)`, with the key of the
  base computed after the write for `heardAfter`, or `NotHeard`. Restoring first and
  then calling `apply` is the order this rules out:
  a render that failed after the restore would have overwritten the last result
  without binning it and kept nothing.
- `enum class UndoState { TO_ORIGINAL, ONE_STEP, NOTHING, NOT_BINNED }`,
  `undoState(pad: KitPad, bin: List<BinEntry>): UndoState` and
  `undoLabel(state): String`. It judges the pad with `carriesMutate` and the bin
  with `originalEntry`.
- `undo(model, slot): Undone`: `requireRewritable(slot)` first; then restores
  `originalEntry` when there is one (Decision 4's default; its alternative restores
  the newest entry), else the newest entry; sets `recipe` and `source` from the
  restored entry's tombstone when there is one, else clears them as today; and
  returns what it restored to (the `Applied` the tombstone reads as, or null) for
  the toast. `Mutate.undo` (`MU:293-296`) is not changed; the CLI keeps it.
- `carriesMutate` also feeds the strip and the card's `mutated` from M3:
  `PadSheetBoxes.mutate` reads `MutateSheet.read(recipe)` today
  (`PadSheetBoxes.kt:120`), so an OUTSIDE ROOM pad's MUTATE strip reads `ROOM × …`
  (`OutsideSheetTest.kt:187` pins that `MutateSheet.read` sees that recipe as a
  mutate) while M3's UNDO would say `THIS PAD CARRIES NO MUTATE`. M3 has the strip
  and the card ask `carriesMutate` first, so an OUTSIDE ROOM pad's MUTATE strip
  reads `UNTOUCHED` and its OUTSIDE strip carries the trip. A `PadSheetBoxesTest`
  case pins it; `OutsideSheetTest:187` stays, since `MutateSheet.read` itself does
  not change.
- `Copy` (M3): `mutatedFrom(move, pad, partner, base, flipped, lengthNote)` with the
  five tails, `UNMUTATED`'s M3 text, `UNMUTATED_STEP_UNKNOWN`, `BUILD_NOTHING`,
  `MUTATE_MINE_BEFORE`, `KEEP_PAD_CHANGED`; `NOTE_BUILDING` is a card label in
  `MutateSheet`, not `Copy`. Under Decision 4's alternative, `unmutatedStep(word,
  partner)` too.

**The seams in `Mutate` and `KitBuilder` (M3).**
- `Mutate.apply` does three things inline today: it reads the live file (`MU:230`),
  builds the recipe (`MU:235-255`), and writes and stamps `mutatedWith`
  (`MU:256-259`). M3 extracts the last two as functions,
  `recipeFor(mode: Mode, sources: List<Source>, spliceAtMs: Int, crossoverHz: Float,
  morphAmount: Float, roomMix: Float, bands: Int, becomeMs: Int, flipped:
  List<String>, extraRecipe: Map<String, JsonValue>): JsonValue.Obj` (the knob
  parameters are `apply`'s own, `MU:204-221`) and `write(model: KitBuilderModel,
  slot: Int, rendered: Rendered, recipe: JsonValue.Obj, sources: List<Source>,
  binSnapshot: KitPad?): Outcome` (`Rendered` is `MU:121`), and refactors `apply`
  onto `render`, `recipeFor` and `write`. That is a pure
  refactor: the CLI's recipe, file and report stay byte for byte, pinned by
  `MutateTest` and `CliTest` unedited. `MutateSheet.keep` calls `render`,
  `recipeFor` and `write` itself, so it can restore between the render and the
  write.
- `KitBuilderModel.replaceAudio(slot, recipe, binSnapshot: KitPad? = null, transform)`
  passes `binSnapshot` to `moveToBin` (`KB:503`, `KB:1256`). With the default null it
  writes what it writes today, byte for byte.
- `Mutate.apply(…, binSnapshot: Boolean = false)` and `Mutate.drift(…, binSnapshot:
  Boolean = false)` pass the pad as it stood when true. `MutateSheet` passes true for
  the card's KEEP and DRIFT; the CLI and OUTSIDE leave the default, so their bins are
  byte-identical to today's. The tombstone KDoc (`KB:1242-1255`) scopes tombstones to
  ejected pads; this widens it to rewrites. `restoreFromBin`'s slot check
  (`KB:1115`) keeps UNDO and KEEP from ever reinstating a pad through it: they
  restore onto a pad that is there, so its slot is full. One path does change.
  After a later DELETE (`clear`) the slot is empty, and restoring an older rewrite
  entry from the BIN screen then takes the reinstate branch (`KB:1115-1119`): the
  pad comes back with that older take's metadata, where today the bytes alone come
  back and no pad holds them. This document accepts that, because it is what the
  tombstone records and a take with no pad to play it is the worse result. A
  `KitBuilderTest` case pins it. `padSnapshot` is read nowhere outside
  `KitBuilder.kt`, so nothing else changes. The KDoc is updated to say all this.

### What changes in `MutateCard` and `PadSheetScreen`

**M1.**
- The rows reorder as the round table says: moves, move line, knob row, BECOME row,
  then the partner rows, HEAR, the note, KEEP and DRIFT.
- The two knob rows become one private composable, `KnobRow(label: String,
  meaning: String?, fraction: Float, valueText: String, deadText: String?,
  enabled: Boolean, scheme: Scheme, onChange: (Float) -> Unit)`, drawn twice,
  unconditionally. A live row (`deadText == null`) draws `StepperSlider`
  (`PSS:3099-3182`, unchanged, since many other rows use it) with `meaning` after
  the value on the same line, as the brief's sketch draws it; a dead row draws its
  label column (blank for STACK, `BECOME` for BECOME) and `deadText` at the same
  height, with `"$label, $deadText"` (or `deadText` alone) as its content
  description. `StepperSlider` itself is not edited; the plan decides whether
  `meaning` is drawn by `KnobRow` beside it or passed in through a new trailing
  slot, and measures the bar (Decision 16).
- HEAR and KEEP are `enabled = !busy` and `dimmed = partner == null`. The doors drop
  their silent `partner ?: return` (`PSS:1276`, `PSS:1341`) and run
  `MutateSheet.refusalBefore` first, toasting the refusal.
- UNDO is `enabled = !busy`, `dimmed = mutated == null || !canUndo`, and its door
  toasts `UNDO_NOTHING` or `UNDO_NOT_BINNED` when dimmed instead of undoing.
- The move chips fold `, SELECTED` into their `tapeClick` label ("Accessibility").
- `onRoulette` and `onDrift` replace `if (e is IllegalArgumentException)
  onToast(Copy.CRATE_EMPTY)` (`PSS:1384`, `PSS:1489`) with `refusalOf(e)`, and
  `onDrift` adds the pre-check at its top. Nothing inside `onDrift` from
  `val onMorph` to `val kitDir` changes, so `CT:1901` and `CT:1938` read it as before.
- `onMutate` (the KEEP door) and `onDrift` set `auditionOnRefresh = true` when they
  wrote, as `applySmear` does (`PSS:694`).
- ROULETTE and A FILE lose their `dimmed =` (`PSS:3712`, `PSS:3737`).
- The keep toast passes `outcome.flipped` and the length note, from
  `MutateSheet.lengthMs` before and after the write inside `withFreshKit`; DRIFT's
  toast appends the length note the same way; HEAR toasts it when
  `lengthMs(rendered)` differs from the pad's.

**M2.**
- The title row goes; the pairing line gains `▶ MINE` and `▶ THEIRS`
  (`onHearMine`, `onHearTheirs`, read-only, `scope.launch` like `onHear`).
- The partner rows (`PSS:3582-3748`, less DRIFT) move into the shared panel; the
  card draws `PARTNER · <name>  CHANGE ▸`.
- KEEP's label is `keepLabel` (one or two lines), its spoken label is
  `keepSpoken` plus `, HEARD` or `, NOT HEARD YET`, `lit` is
  `keepTap(heardNow, now) == KEEP`, and its door asks `keepTap` first: on
  `PLAY_FIRST` it runs HEAR's own path (preview, then `audition()` on the live
  model, outside any write) and toasts `KEEP_FIRST_TAP`.
- `heard` is a `remember(slot)` holder of `Heard(pending: Pending, model:
  KitBuilderModel)`; `heardNow` is `heard?.takeIf { it.model === model }?.pending`.
  HEAR and the first tap set it with the live model. After its own write the KEEP
  door sets it to `heardAfter(KEEP, …)` with the fresh model (null in M2). No other
  door assigns it; any other write clears it by swapping the model.
- `ActionButton` gains `maxLines: Int = 1` (today fixed at `PSS:3963`); only KEEP
  passes 2. Every other caller is unchanged.
- The panel's chips fold `, SELECTED` into their spoken labels.
- UNDO moves to a full row at the bottom.

**M3.**
- The KEEP door calls `MutateSheet.keep(…, buildOn)` and HEAR calls
  `MutateSheet.preview(…, buildOn)`. `CT:2002` pins today's six-argument calls, so it
  is re-proved for the seven-argument form ("Testing").
- The KEEP door passes `expected = heardNow?.base` and, on `Kept.NotHeard`, writes
  nothing, sets `heard = null` and toasts `KEEP_PAD_CHANGED`. It starts no audio
  from inside its `appScope` write, because `voice` is `remember(model)`-keyed and
  the write swaps the model (`PSS:290-296`, `PSS:379-399`, `applySmear`'s KDoc at
  `PSS:640-656`); the next tap of KEEP plays first, as any unheard tap does.
- A `buildOn` holder, `remember(slot)`, opening false, turned off by an effect
  keyed on `carriesMutate`, and the BUILD ON THIS row, a `ToggleChip` with the new
  `dimmed` parameter ("Accessibility").
- UNDO's label and enablement, and the base's key for `Pending`, come from
  `undoState` and `base`, read with the bin listing (under `KitWrites.mutex`) in the
  same effect that computes `binDaysLeft` today (`PSS:280`, `PSS:338`), keyed on the
  model and `buildOn`.
- `▶ MINE` adds `, BEFORE ITS MUTATE` to its spoken label and toasts
  `MUTATE_MINE_BEFORE` when the base is not the live file.

### The shared pick-a-pad component (M2), and how TERRA's R4 reuses it

The TERRA spec plans R4 to lift MUTATE's partner model into one `:shell` object, "a
`PadChooser`", with `MutateSheet` delegating to it, so that TERRA's STRUCK BY and
BENT BY and FORK's STRIKE FROM pick a pad through the same door. Each caller says
which kinds it offers: MUTATE all five; STRUCK BY, BENT BY and STRIKE FROM a pad on
this kit, a pad on another kit, or a file, never a crate deal or a room
(that spec, `:1179-1200`). The brief asks M2 to design that component once, so R4
reuses it. M2 therefore builds it to R4's description:

- **`:shell`: `PadChooser`.**
  - `enum class Kind { THIS_KIT, OTHER_KIT, ROULETTE, ROOM, FILE }`.
  - **The type stays where it is.** `sealed interface Partner` and its five cases
    (`Pad`, `Deal`, `Room`, `Other`, `Wav`, `MS:114-129`) stay in `MutateSheet` as
    the real type, and `PadChooser`'s functions take and return
    `MutateSheet.Partner`. Moving the type behind a type alias does not compile
    here: both modules pin Kotlin 2.0.21 (`shell/build.gradle.kts:2`,
    `app/build.gradle.kts:7`), a type alias cannot be nested inside
    `object MutateSheet` before Kotlin 2.2's opt-in nested aliases, and a top-level
    alias cannot qualify a nested class, so `MutateSheet.Partner.Pad(2)` would stop
    resolving. Those qualified uses are about 43: `PSS:1185`, `:1482`, `:2571`,
    `:2578`, `:3518`, `:3583`, `:3614`, `:3676`, `:3707`, `:3716`; `MST:103`, `:133`,
    `:147`, `:150`, `:159`, `:164`, `:201`, `:211`, `:243`, `:256`, `:372`, `:401`;
    `Rooms.kt:272`; and about 20 inside `MutateSheet.kt` (`:114-138`, `:236-252`,
    `:328-340`). For R4's name, `PadChooser.kt` may declare a top-level
    `typealias Pick = MutateSheet.Partner`, used only unqualified (`Pick`), never as
    `Pick.Pad`. R4 imports `MutateSheet.Partner` (or that alias), and this is settled
    before R4's plan is written.
  - `candidates(kit, slot)` (never the pad itself, `MS:111`), `otherKits`, `padsOf`,
    `hold`, `deal` and `source` (`MS:152-252`), moved into `PadChooser` as
    functions over `MutateSheet.Partner`, with `MutateSheet`'s functions delegating
    under their present names and signatures, so every caller and test that calls
    them compiles unchanged.
  - `label(pick, padName)`: the display form R4 stores (`"A03"`, `"SOUL A03"`, a
    file's name), cut to 24 characters (TERRA spec `:1434-1444`).
  - `kindsFor(caller)`: `MUTATE` → all five; `STRUCK_BY`, `BENT_BY`, `STRIKE_FROM` →
    `THIS_KIT`, `OTHER_KIT`, `FILE`.
  - an optional "—" pick at the top for callers that can clear (TERRA's STRUCK BY
    and BENT BY, that spec `:1238-1243`); MUTATE never shows it.
- **`:app`: `PadChooserPanel(kinds, current, onPick, onDismiss)`.** One panel over
  the sheet, opened by MUTATE's `CHANGE ▸` and, in R4, by TERRA's `[ A03 ▾ ]`. It
  draws only the kinds it is given, in the order this kit, another kit, ROULETTE,
  room, file. The empty-source lines above are the panel's.
- **What R4 then does.** R4 no longer lifts anything. It opens `PadChooserPanel` with
  `kindsFor(STRUCK_BY)` and friends, and writes `PadChooser.label` into
  `Striker.from` and `Bend.from`. Its own rows, capture and KEEP stay as that spec
  designs them. The TERRA spec's line "R4 lifts that partner model" becomes "R4
  reuses the component M2 lifted"; this document does not edit that spec, and
  Decision 8 asks the owner who lands the lift.
- **The order.** If M2 lands first, R4 rebases onto it. If R4 lands first, R4 does the
  lift as its spec says, and M2 builds the panel on R4's `PadChooser`. Both edit
  `PadSheetScreen.kt` and `Copy`, so the second to land rebases, as BECOME and R4
  already agreed for the picker (BECOME spec `:482-489`).

## Data flow and compatibility

- **Recipes.** No key is added, removed or renamed. A KEEP writes exactly the
  `mutate` recipe `Mutate.apply` writes today (`MU:235-255`): `mode`, `with`, the
  move's knob key, `become` on MORPH above 0, `flipped`, and the partner extras
  (`roulette`, `room`, `otherKit`). The move names stay the recipe's `mode` values. A
  DRIFT writes today's recipe with `drift: true`. So every reader, the strip
  (`PadSheetBoxes.kt:70-77`), the takes diff, `RecipeReplay`'s refusal
  (`RecipeReplay.kt:64-66`) and older builds, reads phone recipes as before.
- **Regenerability.** With BUILD ON THIS off (the default), the recipe describes the
  move applied to the pad's original, and the original is the bin entry the
  tombstones mark (`originalEntry`), with its own recipe in its tombstone. That holds
  while the card's KEEP was the last write to the pad; a later recipe-keeping
  rewrite from another screen (`KB:505`; "The last result is replaced") breaks it
  until the next KEEP. On a pad mutated before M3 the bin marks no original, and the
  promise holds from its first M3 KEEP on. Recipe plus original rebuild the sound,
  deterministically: same partner, same recipe, same bytes (`MU:36-37`). With BUILD
  ON THIS on, the recipe is the last step only, as the CLI's chaining and every
  treatment write it.
- **The bin.** The phone's KEEP and DRIFT write one tombstone beside each binned
  take. Older builds already read tombstones (the mechanism exists at `0ba9b053`,
  `KB:1077-1089`), and the slot check keeps a rewrite's tombstone from reinstating a
  pad while the pad is there, so a bin written by the new build is safe on an older
  one. After a DELETE, restoring an older rewrite take from the BIN screen brings the
  pad back with that take's metadata, on either build ("The seams"). An
  older build's UNDO restores the audio and clears the recipe, as today. Bin purging
  removes a tombstone with its take (`KB:1132-1142`).
- **The CLI is unchanged but for one word.** `mutate`, `drift`, `mutate --undo` and
  their flags, reports, exit codes and recipes are byte for byte as today. The one
  change is the `recipe` command's refusal for a mutated pad, which reads `partner`
  where it read `parent` ("One word: PARTNER"). `Mutate.apply` and
  `Mutate.drift` gain a default-false parameter the CLI never passes;
  `Mutate.roulette`'s refusals keep their messages; `Mutate.undo` is untouched. The
  CLI keeps chaining: a second `mutate` builds on the first, by design.
- **OUTSIDE is unchanged.** It calls `Mutate.apply` without the new parameter, and
  `carriesMutate` keeps MUTATE's KEEP and UNDO off OUTSIDE's ROOM result.
- **Kit and pad JSON are unchanged.** `buildOn` and `heard` are card state
  (`remember(slot)`), never written.
- **HEAR = KEEP holds.** Both read the base through one function (`baseSnip`), the
  knobs through one mapping (`knobs`, `MS:274-284`) and the render through one
  function (`Mutate.render`, `MU:172-202`). Restore-first copies the bin take's
  bytes back byte for byte (`KB:1104`), so HEAR's read of the entry in place and
  KEEP's read after restoring are the same samples.

## Failure handling

- **Every refusal is pre-checked and typed** ("Honest refusals"). A pad that changes
  between the tap and the lock is re-checked inside `withFreshKit`, as today
  (`PSS:1296`, `PSS:1472`), and the sample-file identity check still leads to
  `ALREADY GONE.` (`COPY:1134`). That re-check covers layers only; from M3, `keep`
  and `undo` call `requireRewritable` first, so a pad that became chained or layered
  in between refuses before the bin is touched, and the card maps the refusal to
  its typed line.
- **A render that fails writes nothing.** `keep` renders from the base before it
  restores or writes anything. If restoring then writing fails, the pad holds the
  restored take with the recipe its tombstone gave back: a true state, said by a
  failure toast.
- **The base changed between HEAR and KEEP** (M3: the bin was emptied, another
  write landed). `keep` computes the base inside the lock and compares its key with
  the one that was heard; a different base returns `NotHeard` before anything is
  touched. The door writes nothing, clears `heard` and says
  `THE PAD CHANGED SINCE YOU HEARD IT. NOTHING WAS KEPT - TAP KEEP TO HEAR IT FIRST.`
  It does not play from inside the write, because audio cannot be started from the
  `appScope` write coroutine (`PSS:290-296`, `PSS:379-399`, `PSS:640-656`); the next
  tap plays first. So KEEP never writes a sound different from the one that was
  heard. A write that swaps the model clears `heard` before this point anyway; the
  key catches a bin change that did not.
- **The partner disappeared** (a pad deleted, another kit or its `kit.json`
  removed, a kept room or a ROULETTE pick's file deleted, the held file gone).
  `source` checks each kind before it reads and throws `PartnerGone`; the card says
  `THAT PARTNER IS GONE. PICK ANOTHER.` A file that exists but no longer decodes is
  a real failure and gets the generic toast. A held file replaced by a newer pick is
  not gone; it is simply no longer the partner (`MS:158`).
- **No original in the bin** (purged after 30 days, `BIN_KEEP_DAYS`, `KB:1291`; the
  bin emptied; a bank-B twin's copied recipe). KEEP builds on the current audio and
  says so (NO_ORIGINAL); UNDO is dimmed and says why (NOT_BINNED).
- **A pad mutated before M3** has no tombstone behind it. KEEP restores the newest
  take and says `MADE FROM THE NEWEST TAKE IN THE BIN, WHICH THE BIN DOES NOT MARK
  AS THE ORIGINAL. WHAT THE PAD HELD IS REPLACED.`; UNDO reads `ONE STEP BACK` and
  clears the recipe as today, and its toast does not claim the original. If that pad was mutated twice before M3, its strip reads `UNTOUCHED`
  after one UNDO while the pad holds the first mutation: the bin never recorded
  otherwise. This is the one place the card cannot know the truth, and it shrinks to
  nothing as old takes purge.
- **A slow render** (PGHI, convolution) shows as `busy` on every control, as today.
  A HEAR-stage label, such as the house's `LISTENING…` idiom, is out of scope.

## Testing

Tests guard properties; they never author sounds. The claims tests are written
first and watched failing. A law is proved by watching it fail with its own
message, and no law is loosened; where one changes, the new law is written first
and watched failing against today's screen (A1b plan, Global Constraints). Every
threshold below is the house's own (`MST:96`'s one 24-bit step) or *this spec's*.

### The ones that carry the claims

**Copy laws** (M1, `MutateWordsTest` beside `MutateSheetTest`, and `PT`).
- Every new `Copy` constant shouts and ends in a full stop: `PT:726` finds them by
  reflection, with no exclusion added. `UNMUTATED`, `MUTATE_NEEDS_ONE` and
  `CRATE_EMPTY` still exist by name (`PT:649-676`).
- The six move lines equal `Moves.dc.html:45-50` character for character, and so do
  the five knob meanings and STACK's line; the BECOME line is pinned as written here.
- Every card label (`outcomeLine`, `knobMeaning`, `becomeMeaning`, `deadKnobLine`,
  `pairLine`, `partnerLine`, `partnerName`, `keepLabel`'s lines, `NOTE_LINE`,
  `DRIFT_LABEL`, `ROULETTE_LABEL`, `NO_ROOM_LINE`, `NO_OTHER_KIT_LINE`,
  `undoLabel`) equals its own upper case.
- Budgets, each with 24-character names on both sides (pad and partner) and over
  every move and every partner kind: a full row at most 44 characters
  (`docs/UI_DESIGN.md:203-204`, house), which covers `NO_ROOM_LINE`,
  `ROULETTE_LABEL`, `NOTE_LINE` and `partnerLine` plus `CHANGE ▸`; `pairLine` at
  most `PAIR_CHARS` from M2 and 44 in M1, the empty state included with a
  24-character pad name (which keeps `× ?  — PICK A PARTNER` whole); each of
  `keepLabel`'s lines and `DRIFT_LABEL` at most
  `KEEP_CHARS` = 21 (`PSS:3717-3731`); `partnerName` at most 24.
- **KEEP never loses its partner:** for every move and every partner kind, with
  names from 1 to 24 characters, including another kit and a ROULETTE pick with a
  24-character kit name, `keepLabel` contains `× ` and the partner's whole tag (or
  the first 8 characters of a room's or file's name), and never ends `× …`. The
  same holds for `partnerName` and `pairLine`: no cut drops a tag.
- No MUTATE string contains a retired word: every card label, and the `Copy` lines
  the card shows (`mutated`, `mutatedFrom`, `UNMUTATED`, the refusals, `fileRefused`,
  `replayNeedsPartner`), contain none of `PARENT`, `NEIGHBOUR`, `CRATE`, `DEAL` as a
  word. `RecipeReplayTest:46` and `MST:432` pin `NEEDS ITS PARENT` today and are
  updated deliberately to `NEEDS ITS PARTNER`; no `cli` test pins the line today,
  and the update adds one for the `recipe` command's lower-cased refusal, so the
  CLI's one changed line is pinned.
- One word, one meaning on the card: `KEEP` appears on the card only as the commit
  verb and in the note line. No card label or card line contains `KEEP ROOM` (the
  empty room row included), `TAKES` in the round-robin sense, or `GHOSTS`.
- `roomKept` and `roomLanded` (M2) contain no `MUTATE ▸`; `RoomsTest:174` and
  `:188` pass unedited.
- `mutated(…)` still starts `<MOVE>: <PAD> × <PARTNER>.` (`PT:810-813`); with
  `flipped` it carries `WAS FLIPPED: IT WAS CANCELLING THE PAD`; `lengthNote` is
  empty under 10 % or under 50 ms and present at both bounds.

**HEAR == KEEP** (`MST`).
- `MST:96` passes unedited.
- M3: for every move, a second KEEP after a first one: the preview with BUILD ON
  THIS off equals the written file within one 24-bit step (`2/8 388 607`), and so
  does the preview with BUILD ON THIS on. MORPH runs at BECOME fractions 0, 0.25
  and 1, as `MST:369` does.
- The first tap's play is `preview`'s output, not another render (a source law).

**Never jumps** (`MutateWordsTest` and `CT`).
- Every move yields a knob row (a label and its meaning, or a blank label and the
  dead line) and a BECOME row (`BECOME` and its meaning on MORPH, or `BECOME` and the
  dead line).
- The `CT` law below pins that `MutateCard` draws every row unconditionally.

**The first-tap rule** (`MST`, pure).
- Unheard settings give `PLAY_FIRST`; after `heardAfter(HEAR, now)`, `KEEP`.
- Each of move, partner, the move's knob, BECOME on MORPH and BUILD ON THIS, changed
  alone, gives `PLAY_FIRST`.
- BECOME changed on SPLICE, and AT changed while on STACK, give `KEEP` (effective
  values only).
- After `heardAfter(KEEP, now)` in M2: `PLAY_FIRST`, with BUILD ON THIS off and
  on. This is the M2 claim that carries the phone check's "KEEP never saved a sound
  you had not heard". From M3, written first and watched failing against M2's rule:
  `KEEP` with BUILD ON THIS off, `PLAY_FIRST` with it on. After `OTHER_WRITE`:
  `PLAY_FIRST`.
- M3: a `Pending` with a different `base` key, all else equal, gives `PLAY_FIRST`;
  `keep(…, expected = <stale key>)` returns `NotHeard` and leaves the pad's file,
  its recipe and the bin listing unchanged.

**Restore-first** (`MST`, on real kits in a temp folder).
- Two default KEEPs (SPLICE, then MORPH 0.7): the file equals one MORPH 0.7 KEEP on
  an untouched copy of the kit, within one 24-bit step, for every pair of moves. The
  bin holds exactly one take of the pad's file, byte-identical to the original, with
  a tombstone whose recipe is the original's. The recipe describes MORPH only.
- With BUILD ON THIS on, the second result equals `Mutate.render` over the first
  result; the bin holds two takes.
- From the original after a chain (Decision 15's default): SPLICE, then BUILD ON
  THIS on and STACK, then BUILD ON THIS off and MORPH 0.7. The file equals one
  MORPH 0.7 KEEP on an untouched copy, within one 24-bit step, and the chain's
  in-between result is still in the bin, behind the re-binned original. The same
  after a KEEP then a DRIFT then a KEEP. And after a KEEP, then a recipe-keeping
  `replaceAudio(slot, null)` (another screen's write), then a KEEP: the base is
  the original, not the other screen's take.
- `base` returns each of the five cases on a kit built to produce it, including a
  pre-M3 take with no tombstone and an emptied bin.
- A stale BUILD ON THIS: with `buildOn` true on a pad that carries no MUTATE (after
  an UNDO back to the original), `base` is `FIRST`, never `ON_TOP`.
- A pad that becomes chained (`Robin.apply`) or layered after one KEEP: a second
  `keep` refuses through `requireRewritable`, and the pad's file, its recipe and the
  bin listing are unchanged (the original's take still in the bin with its
  tombstone). `preview` refuses the same pads in different words, as `MST:142`
  pins today for HEAR and KEEP.
- A failed second KEEP changes nothing: after one KEEP, a second KEEP whose partner
  file has been deleted (`PartnerGone`), and another whose render is made to throw
  (a test seam), each leave the pad's file byte-identical, its recipe equal, and the
  bin listing (names, times, tombstones) unchanged.
- `Mutate.apply` after the `recipeFor` / `write` refactor writes the same file,
  recipe and provenance as before, for every move (the existing `MutateTest` cases,
  unedited).
- An OUTSIDE ROOM pad (`OutsideSheet`'s recipe) is not `carriesMutate`, and a KEEP
  on it is FIRST: its OUTSIDE result is binned, not replaced.

**The honest UNDO** (`MST`).
- After two default KEEPs, `undoState` is `TO_ORIGINAL`; one `undo` makes the file
  byte-identical to the original, the recipe equal to the original's (null, and in a
  second case a treatment's recipe, so the TREATMENT strip reads that treatment), the
  MUTATE strip `UNTOUCHED` (`PadSheetBoxes.mutate`), and `undoState` `NOTHING`.
- After a BUILD ON THIS chain, and after a DRIFT on a kept pad, under Decision 4's
  default: `undoState` is `TO_ORIGINAL`, and one `undo` makes the file
  byte-identical to the original. A further case pins the cost the decision names:
  the chain's in-between result is then the newest take of the pad's file. Under
  the alternative, the same cases give `ONE_STEP` and the previous result with its
  own mutate recipe. The M3 plan writes whichever the owner chose.
- On a pre-M3 take (no tombstone), `ONE_STEP` and the generic toast; with no bin
  take, `NOT_BINNED`.
- An OUTSIDE ROOM pad's MUTATE strip reads `UNTOUCHED` (`PadSheetBoxesTest`), and
  its `undoState` is `NOTHING`.
- `undo` on a pad that became chained refuses through `requireRewritable` and leaves
  the bin untouched.

**Refusal mapping** (`MST`).
- `refusalBefore`: a layered pad gives `Layered`, a chained one (`Robin.apply`, as
  `MST:142` builds it) `Chained`, both together `Layered`, a plain pad with no partner
  `NoPartner`, and DRIFT's call never `NoPartner`.
- `refusalOf`: `RouletteRefused(EMPTY)` gives `ShelfEmpty`, `(ONLY_COPIES)`
  `OnlyCopies`, `PartnerGone` `PartnerGone`, and any other `IllegalArgumentException`
  null. The bug itself: a DRIFT on a chained pad yields `Chained`, never `ShelfEmpty`.
- `source` throws `PartnerGone` for each kind with its partner removed: a deleted
  pad on this kit, a deleted pad on another kit, another kit's folder deleted,
  another kit's `kit.json` deleted, a ROULETTE pick's file deleted, a kept room's
  file deleted, a held file deleted. A file that exists but does not decode is not
  `PartnerGone`.
- `lengthMs` on a known WAV at 44.1 kHz and at 48 kHz.
- `Mutate.roulette`'s two messages are byte-identical to today's; the CLI's
  `CliTest` mutate and drift pins pass unedited.

**The bin seam** (`KitBuilderTest`, beside `:1057-1150`).
- `replaceAudio` with a `binSnapshot` writes a tombstone; without one it writes none
  (the CLI path, byte for byte).
- Restoring a rewrite's tombstoned take while the pad is there never reinstates a
  pad (the slot is full) and consumes the tombstone.
- After a DELETE, restoring an older rewrite take reinstates the pad with that
  take's metadata (the behaviour this document accepts, "The seams").
- A CLI `mutate` leaves no tombstone in the bin.

### The `:app` laws (`ConventionTest`, source text)

Kept unedited: `CT:1824` (per-move knob memory), `CT:1866`, `CT:1901` (DRIFT writes
MORPH's memory), `CT:1938` (BECOME per pad, DRIFT resets it).

Re-proved deliberately, the new law written first and watched failing:
- **`CT:2058`, the BECOME row** (M1). Today it pins exactly two `StepperSlider`s in
  `MutateCard`, the literals `label = knobLabel ?: "—"` and `label = becomeLabel ?:
  "—"`, and BECOME's slider alone on the line after the knob row's `)`. The dead-row
  words and the `KnobRow` composable break all three. The new law pins exactly two `KnobRow(`
  calls, each alone on its line and unguarded, the second directly after the first's
  `)`; BECOME's enabled on `becomeLabel != null`, never `knobLabel`; both snaps
  exactly `(f * 40f).roundToInt() / 40f`; dead text read from `MutateSheet`, never a
  literal `"—"`. The call-site half of today's law (`becomeKnob`, `pendingBecome`)
  is kept as it is.
- **`CT:2002`, HEAR and KEEP pass BECOME** (M3). Today it pins
  `MutateSheet.apply(f, slot, who, move, fraction, becomeFraction)` and
  `MutateSheet.preview(m, slot, who, move, fraction, becomeFraction)`. The new law
  pins `MutateSheet.keep(f, slot, who, move, fraction, becomeFraction, buildOn,
  expected)` and `MutateSheet.preview(m, slot, who, move, fraction, becomeFraction,
  buildOn)`, with the values read before the launch, as today.

New:
- **No silent middle on the card** (M1): `onHear` and `onMutate` contain no
  `partner ?: return`, call `MutateSheet.refusalBefore` before their launch, and
  HEAR's, KEEP's and UNDO's `ActionButton`s are `enabled = !busy`; UNDO's door
  toasts `UNDO_NOTHING` or `UNDO_NOT_BINNED` when there is nothing to undo.
- **The empty-shelf mapping is gone** (M1): no `IllegalArgumentException) onToast(Copy.CRATE_EMPTY)`
  in the file; `onRoulette` and `onDrift` call `MutateSheet.refusalOf`.
- **A write plays its result** (M1): `onMutate` and `onDrift` set
  `auditionOnRefresh = true` when they wrote, the shape of `applySmear`'s line
  (`PSS:694`).
- **ROULETTE and A FILE are not dimmed at rest** (M1).
- **KEEP asks the first-tap rule** (M2): the KEEP door calls `MutateSheet.keepTap`
  before any write; `heard` is a `remember(slot)` holder that stores the model it
  was heard on and is read through `=== model`; only HEAR, the first tap and the
  KEEP door assign it, and the KEEP door assigns it after its write from
  `heardAfter(MutateSheet.HeardEvent.KEEP, …)` with the fresh model.
- **No audio from the write** (M3): on `Kept.NotHeard` the KEEP door calls neither
  `audition(` nor sets `auditionOnRefresh`; it sets `heard = null` and toasts
  `KEEP_PAD_CHANGED`.
- **The bin is chosen under the lock** (M3): `onHear` and `onHearMine` call
  `MutateSheet.base(` only inside `KitWrites.mutex.withLock`, as `playBefore` does.
- **KEEP refuses first** (M3, `:shell` source law): the first statement of
  `MutateSheet.keep` and of `MutateSheet.undo` is `requireRewritable(slot)`.
- **One chooser** (M2): `MutateCard` draws no pad chips of its own; it opens
  `PadChooserPanel` with `PadChooser.kindsFor(MUTATE)`.
- **Every row is always drawn** (M2): `MutateCard`'s pairing, partner, move, knob,
  BECOME, hear, note, keep and undo rows (and M3's build row) are unconditional
  calls.

### The rest

- `MST`'s existing partner, roulette, file and BECOME tests pass unedited through
  the `PadChooser` delegation (M2).
- `SidecarFuzzTest` and `DeterminismTest` pass unedited: no recipe changes.
- `docs/CLI.md`'s phone paragraph (`:553-557`) is updated each round to name the
  card's words (PARTNER, KEEP, `CHANGE ▸`, BUILD ON THIS); its CLI sections are not.
- The emulator tests keep passing. None taps the MUTATE card today: the pad sheet's
  emulator test names MUTATE only to say why its apply paths' busy states are not
  driven there (`app/src/androidTest/kotlin/com/snipsnap/app/ui/PadSheetScreenTest.kt:66-75`).
  These rounds add no emulator test, so the phone checks are the card's behavioural
  test.

## Phasing and gates

Three rounds, in the brief's order. Each is built the house way: **the logic
first** in `:shell`, with its claims tests watched failing and then passing; then
the `:app` edits, with their `ConventionTest` laws written first; then CI
(`android-build`, and `emulator-tests`, which runs because each round touches
`app/**`); then **the owner's phone check, and stop**. Each check has at most three
questions, each question one decision, answered yes or no with a word on any no,
and each gives step-by-step directions to every control it names, starting from
opening the app (the owner's standing preference: at most 10 clips and 3
questions a page, each question one decision, as the BECOME spec records it,
`2026-09-30-become-strung-say-design.md:1596-1599`). No
round needs clips: the sounds are the shipped moves, and BECOME's sound was
approved at the A1 gate on 2026-10-02.

| Round | What lands | Gate |
|---|---|---|
| **M1 · words and honesty** | the move lines; the knob's meanings and its move under the move line; the dead rows as words (`KnobRow`, `CT:2058` re-proved); PARTNER everywhere; HEAR and KEEP dimmed, not disabled, with toasts; the commit verb KEEP and DRIFT beside it as `DRIFT · BLEND & SAVE`; the result playing after KEEP and DRIFT; the typed refusals; ROULETTE and A FILE undimmed; the polarity flip and the length note in the toasts (with the `lengthMs` seam); UNDO dimmed with a toast; the pairing line as the status line. Also BECOME's own phone check (A1b), folded in, all eleven lines kept as steps | M1's phone check, below. A pass with CI green also flips QQ4 to done (`docs/FEATURE_PLAN.md:1173`), because every A1b line is one of its steps |
| **M2 · layout** | the pairing line with `▶ MINE` and `▶ THEIRS`; the folded picker, `PARTNER · <name>  CHANGE ▸`, opening `PadChooserPanel` over `PadChooser` (the component TERRA's R4 reuses); KEEP stating its outcome (one or two lines, never without its partner); the first-tap rule, with a KEEP leaving the settings unheard, and KEEP lit when heard; the panel chips' spoken state; `roomKept` and `roomLanded` reworded; the title row removed; UNDO on the bottom row | M2's phone check |
| **M3 · safety** | KEEP from the original by default (restore-first from the entry the tombstones mark, the five `Base` cases); BUILD ON THIS, never outliving the mutate; the base in the first-tap rule; the tombstone seam; the honest UNDO (`undoState`, the restored recipe); the MUTATE strip asking `carriesMutate`; recipe regenerability, with its tests; `CT:2002` re-proved. UNDO back to the original after a chain or a DRIFT too (Decision 4, answered 2026-10-04). **As specified, M3 is narrower than the brief in one place**: a pad mutated before M3 cannot start from its original, because its bin records none (FROM_BEFORE, ONE_STEP) | M3's phone check |

**Effort**, from footprints (*estimate*): M1 is about 170 lines in `:shell`
(`MutateSheet` words and refusals, `Copy`, `Mutate.RouletteRefused`, the `lengthMs`
seam, `PartnerGone`'s checks), about 300 of
tests, and about 120 lines of `:app`. M2 moves about 200 lines from `MutateSheet`
into `PadChooser` and adds about 80, with about 200 lines of `:app` for the panel
and the header. M3 is about 200 lines in `:shell`, about 300 of tests, and about 60
lines of `:app`.

**What a no does.** A no on a word or a layout is a card fix: write the law that
would have caught it first, watch it fail, then edit the screen. A no on what a
button did is a fault in a claim: find the failing claim among the round's tests
before changing anything. A no about the design itself goes back to the owner as
one question; it does not reopen an approved section on its own.

### Getting to the card (every check starts here)

1. Open SnipSnap. It opens on **SHELF**, the list of your kits (`app/src/main/kotlin/com/snipsnap/app/App.kt:311`;
   the menu names it SHELF, `app/src/main/kotlin/com/snipsnap/app/ui/Chrome.kt:85`, `:289`).
2. Tap a kit that has at least three pads. **KIT** opens, with the pad grid.
3. Hold one pad down for about half a second (480 ms,
   `docs/UX_JOURNEY_REVIEW_2026_09.md:155`, house) and let go when the **PAD SHEET**
   opens. The line under KIT's grid says this: `HOLD A PAD · SHAPE, TUNE, TREAT,
   MUTATE, GRAIN` (`COPY:386`).
4. Scroll down past the row of chips that includes **SOFT HITS**. Five boxes follow:
   TREATMENT, SHAPE, **MUTATE**, OUTSIDE, MAKE (`PadSheetBoxes.kt:25-31`). Tap the
   third box's strip, **MUTATE**, to open it. Only one box is open at a time.

### M1's phone check (three questions)

Set-up: a kit with at least three pads, steps 1 to 4 above. Each question is one
decision; every step says what you should see, and a step that did not happen is
a no, with the step's number. Steps marked (A1b *n*) are the eleven lines of
BECOME's own pending check (the A1b plan, Task 5, `:955-969`), all eleven kept,
so a pass here closes A1b's gate too.

**1. Did the card's words tell you what each control would do before you tapped
it?**
1. The six move buttons are the first two rows inside the box: STACK, SPLICE, SPLIT
   on top; MORPH, ROOM, TRANSPLANT under them. Tap each in turn and read the one
   line under the buttons.
2. On STACK, the knob row under that line reads `NO KNOB — THEY LINE UP ON THE HIT`,
   the row under it reads `BECOME` and `ONLY MORPH TURNS OVER TIME`, and neither
   moves when you drag it. (A1b 1)
3. On SPLICE, SPLIT, ROOM and TRANSPLANT, the knob row reads AT, HZ, WET or BANDS
   with its meaning after the value, and the BECOME row still reads
   `ONLY MORPH TURNS OVER TIME`. The box does not change height as you switch.
   (A1b 2)
4. On MORPH, the BECOME row reads `BECOME` and `OFF`. Drag it: it steps in 50 ms
   (the first step reads `50 ms`, the far right `2000 ms`), `BECOME` is not cut off,
   and the line under the moves changes to `STARTS AS MINE, TURNS INTO THE MIX.`
   (A1b 3)
5. Set BECOME to 400 ms, tap SPLICE, then MORPH again: it still reads `400 ms`.
   (A1b 6)
6. Scroll down to the two buttons side by side under **▶ HEAR THE RESULT**: the
   right one reads `DRIFT · BLEND & SAVE`.
7. With TalkBack on (Settings, Accessibility, TalkBack), touch each row on MORPH,
   then on STACK. MORPH's BECOME row reads "BECOME" and its value and can be
   adjusted; on STACK both rows read their words once, and nothing reads a bare
   dash. Turn TalkBack off. (A1b 10)

Yes if you knew what each control would do before you tapped it.

**2. Did KEEP save exactly what HEAR played?**
1. Without choosing a partner, tap **▶ HEAR THE RESULT** (the wide button below
   the partner rows), then **KEEP** (the left button under it). Each says
   `PICK A PARTNER FIRST.`
2. Scroll up to the partner rows (pad names such as `A02`, under the BECOME row)
   and tap one. The line at the top of the box reads `<your pad> × <that pad>`.
3. Tap MORPH. Drag MIX to the far right (100 %) and BECOME to 250 ms. Tap ▶ HEAR
   THE RESULT: the pad turns into the partner. (A1b 4)
4. Drag BECOME back to `OFF` and tap ▶ HEAR THE RESULT: it is the plain MORPH the
   card made before BECOME existed, with no turn. Drag BECOME back to 250 ms.
   (A1b 5)
5. Tap KEEP. The result plays by itself, the toast names MORPH and both pads, and
   the strip at the top of the box reads `BECOME × …`. Tap **▶ HIT** at the top of
   the sheet: it plays what HEAR played. (A1b 4)
6. Tap **UNDO** (top right of the box). The pad you started with comes back, and
   the toast says the take before the last mutate is back. (A1b 9)
7. Tap UNDO again. It looks dimmed and says `THIS PAD CARRIES NO MUTATE. NOTHING TO
   UNDO HERE.`
8. Scroll to the bottom of the sheet. Under the boxes, pinned beside DELETE, are the
   pad arrows, `◄ <tag>` and `<tag> ►` (`PSS:2802`, `PSS:2813`). Tap `<tag> ►` to
   open the next pad, open its MUTATE box (step 4 above) and tap MORPH: BECOME reads
   `OFF`. Tap `◄ <tag>` to come back. (A1b 8)

Yes if what KEEP saved sounded the same as what HEAR played.

**3. Did every toast tell the truth and say what to do next?**
1. Tap **SPLICE**. DRIFT keeps a MIX you dialled on MORPH (`PSS:1441-1442`), so
   this starts from another move; MORPH's BECOME still remembers 250 ms from
   question 2.
2. Tap **DRIFT · BLEND & SAVE**. The result plays by itself, the toast says the pad
   drifted toward another pad, and the card now shows MORPH with MIX at 50 % and
   BECOME `OFF`; the strip says DRIFT. (A1b 7)
3. Tap UNDO to put the pad back.
4. Scroll up out of the box to the chip row and tap **SOFT HITS** so it lights.
   Scroll back down to the MUTATE box and tap KEEP. The toast names SOFT HITS and
   says to turn it off first. Tap SOFT HITS again to turn it off.
5. Only if you have a shelf whose kits hold no pad but this one (for example a new
   kit with a single pad, alone on the shelf): open that pad's MUTATE box, tap
   SPLICE, set BECOME to 400 ms on MORPH, tap SPLICE again, then tap DRIFT. The
   toast says `THE SHELF HOLDS NO OTHER PAD. ROULETTE HAS NOTHING TO PICK.`, the pad
   is unchanged, and the card lands on MORPH with MIX 50 % and BECOME `OFF`. That
   reset before the write is intended; a no here that you would rather keep the
   dialled BECOME is a design question, not a card fix. (A1b 11)

Yes if every toast was true and told you what to do next.

### M2's phone check (three questions)

Set-up: two kits on the shelf, the first with at least three pads; steps 1 to 4
above on the first kit.

**1. Could you hear both sounds before choosing?**
1. The top line of the box reads `<your pad> × ?  — PICK A PARTNER`, with **▶ MINE**
   and **▶ THEIRS** at its right end.
2. Tap ▶ MINE: your pad plays. Tap ▶ THEIRS: it says `PICK A PARTNER FIRST.`
3. On the second line, `PARTNER · NONE YET`, tap **CHANGE ▸** at its right end. A
   panel opens over the sheet. Tap one of this kit's pads. The panel closes and the
   PARTNER line names that pad.
4. Tap ▶ THEIRS: the partner plays.

Yes if you could hear your pad and the partner before keeping anything.

**2. Was every kind of partner where you looked for it?**
1. Tap CHANGE ▸ again. Tap **ROULETTE · PICK A PARTNER OFF THE SHELF**: the panel
   closes and the PARTNER line names what it picked.
2. Tap CHANGE ▸. Under another kit, tap the second kit's name, then one of its pads.
3. Tap CHANGE ▸. With no room kept, the room row reads
   `NO ROOM YET · MAKE ONE IN OUTSIDE ▸ ROOM`. Tap **A FILE ▸** and pick an audio
   file; the PARTNER line names the file.

Yes if every source was where you looked for it.

**3. Did KEEP play every sound before it saved it?**
1. Pick a pad as the partner again (CHANGE ▸, a pad). Tap SPLICE. The left button
   near the bottom reads `KEEP · SPLICE × <partner>`.
2. Tap KEEP once. It plays the result, the toast says nothing has changed yet, and
   KEEP gets an amber edge.
3. Tap KEEP again. It saves, the result plays, and the amber edge goes.
4. Tap KEEP once more: it plays first (this round, KEEP still builds on what the pad
   now holds, so that sound is new); a second tap saves.
5. Drag the knob (AT). The amber edge goes. One tap of KEEP plays again; a second
   saves.

Yes if KEEP never saved a sound you had not heard.

### M3's phone check (three questions)

Set-up: steps 1 to 4 above, on a pad with no treatment and no mutate on it (its
TREATMENT and MUTATE strips both read `UNTOUCHED`), with the sheet freshly opened
so the card starts on STACK with no partner.

**1. Did the second KEEP start from your original pad?**
1. Pick a partner (CHANGE ▸, a pad). Tap SPLICE, then KEEP twice. The toast ends
   `ORIGINAL SLEEPS IN THE BIN.`
2. Tap MORPH and leave MIX at 50 %. Tap KEEP twice. The toast says
   `MADE FROM THE ORIGINAL. THE LAST RESULT IS REPLACED.`
3. Listen: the result is your pad blended with the partner, with no splice in it.
   Tap ▶ MINE: it plays your untouched pad, and says it is the pad before its
   mutate.

Yes if the second KEEP sounded like MORPH on your original pad.

**2. Did UNDO's words match what you heard?**
1. The bottom row reads **UNDO · BACK TO THE ORIGINAL**. Tap it.
2. The toast says `THE ORIGINAL IS BACK FROM THE BIN.` Tap ▶ HIT at the top of the
   sheet: your original pad plays. The MUTATE box's strip reads `UNTOUCHED`.
3. Tap UNDO again. It is dimmed and says `THIS PAD CARRIES NO MUTATE. NOTHING TO UNDO
   HERE.`

Yes if every line the card showed matched what you heard.

**3. Did BUILD ON THIS chain only when you asked it to?**
1. Tap SPLICE and KEEP twice.
2. Tap **BUILD ON THIS**, the row above KEEP, so it is on. The line under ▶ HEAR THE
   RESULT reads `KEEP BUILDS ON THIS RESULT`.
3. Tap STACK and KEEP twice. The toast says `BUILT ON THE LAST RESULT, WHICH SLEEPS
   IN THE BIN.`
4. Tap BUILD ON THIS to turn it off, tap MORPH and KEEP twice. The toast says
   `MADE FROM THE ORIGINAL. THE LAST RESULT IS REPLACED.`: no splice and no stack in
   it.
5. Tap UNDO. The bottom row reads
   `UNDO · BACK TO THE ORIGINAL` and your original pad comes back, and BUILD ON THIS
   is off.

Yes if the result built on the last one only while BUILD ON THIS was on.

## Out of scope

- **The other two directions** `synthesis.md` §3 offered: named starting points that
  set the real controls, and a guided step strip with DRIFT as the way in. The owner
  chose to finish the house redesign.
- **The boards' mini waveforms** and the `WHAT YOU WOULD KEEP` third waveform
  (`Main.dc.html`, `Heard.dc.html`). Canvas work with its own TalkBack needs, not in
  the brief.
- **A level-matched A/B.** `▶ MINE` and `▶ THEIRS` are solo plays; the alternating,
  loudness-matched comparison is `docs/AUDITION_SPEC_2026_09.md`'s.
- **A live re-render** while a knob moves or on its release (patterns §5).
- **A hard gate** that disables KEEP until a HEAR. The first-tap rule is the brief's
  soft form: KEEP stays tappable and its first tap plays.
- **Carrying the move and partner to the next pad.** `mutateMode`, `partner` and
  `pickedKit` stay `remember(slot)`, as the journey plan recorded and left
  (`docs/UX_JOURNEY_PLAN_2026_09.md:348-353`).
- **Any change to the CLI** beyond the one word in the `recipe` command's refusal
  ("One word: PARTNER"), to the move names, to the recipe keys, or to DRIFT's
  write.
- **A phone control that unchains a pad.** The brief asks that a chained pad say
  how to unchain; with no such control, the chained refusal names the phone's way
  to pads of one sample instead, and Decision 3 flags the gap to the owner.
- **Other screens' recipe-keeping rewrites.** `replaceAudio(slot, null)` keeps a
  mutate recipe on a pad whose audio SPLIT, SNAP or SURFACE replaced (`KB:505`).
  M3 copes with it (KEEP finds the original by its tombstone) but does not change
  those writers.
- **A probable house bug found on the way, offered separately:** DE-SAMPLE shows
  `MUTATE_NEEDS_ONE`, which says `MUTATE WANTS ONE SAMPLE`, for its own layered-pad
  refusal (`PSS:1976`). (The MUTATE strip reading an OUTSIDE ROOM trip as a mutate
  is fixed in M3, "The base, KEEP from the original, and UNDO".)
- **HELP and the legend explaining MUTATE** (`docs/UX_WIRING_REVIEW_2026_09.md:102`,
  house §5). The card explains itself; HELP is a separate pass.
- **DRIFT's second meaning**, the GRAINS engine's DRIFT knob, on another screen
  (house §6a).
- **A busy stage label** such as `LISTENING…` for a slow render.
- **Compiling or running `:app` here.** There is no Android SDK in this
  environment.

## Decisions already taken

What this document treats as closed, and on whose authority. The owner's answers
to the brief were given in conversation on 2026-10-03 and 2026-10-04; this table
does not date those more finely than the brief does. Answers from earlier
documents carry their own dates.

| Question | Decision | By |
|---|---|---|
| The ask | "I think mutate is complicated to understand how to use it. How could we fool proof the feature without stripping utility" | owner, after the PR #439 phone build, 2026-10-03 to 2026-10-04 |
| What was hardest | which move to pick; what the partner is; what the buttons do. The knobs were not a problem | owner, 2026-10-03 to 2026-10-04 |
| The direction | "Finish the house redesign": build `design/mutate-v2/`'s boards, plus the quick wins; not starting points, not a guided strip | owner, 2026-10-03 to 2026-10-04 |
| Section 1 | the pairing line with ▶ MINE and ▶ THEIRS; PARTNER as the one word; the folded picker and CHANGE ▸ with all five sources; the empty state; dimmed, not disabled; the move lines verbatim plus the BECOME line; the knob under the moves with its meaning; the dead rows as words | owner, 2026-10-03 to 2026-10-04 |
| Section 2 | ▶ HEAR THE RESULT; KEEP stating its outcome; the first-tap rule; the play after KEEP; DRIFT beside KEEP as `DRIFT · BLEND & SAVE`, otherwise unchanged; UNDO back to the original; KEEP from the original with BUILD ON THIS; the honest UNDO; the typed refusals; ROULETTE and A FILE undimmed; the polarity flip; length changes | owner, 2026-10-03 to 2026-10-04 |
| The rounds | M1 words and honesty, M2 layout, M3 safety; each ends with a phone check of at most three questions with directions from opening the app | owner, 2026-10-03 to 2026-10-04 |
| BECOME's phone check | PR #439's pending check folds into M1's | owner, 2026-10-03 to 2026-10-04 |
| The shared chooser | the folded picker is the pick-a-pad component the TERRA hook's R4 needs; design it once so R4 reuses it | owner, 2026-10-03 to 2026-10-04 (the brief); "one shared chooser, lifted from MUTATE" was already the owner-approved default (TERRA spec `:1179`) |
| BECOME: the A1 gate | "They all do" (the full linear range, 0 to 2000 ms in 50 ms steps); "A different sound" (A1b is built); "6 is as good or better" (no extras) | owner, 2026-10-02 |
| BECOME on the card | MORPH alone; the row drawn on every move so the card never jumps; DRIFT never takes it and puts it back to OFF | owner, 2026-09-29 to 2026-09-30 (BECOME spec) |
| The move names | STACK, SPLICE, SPLIT, MORPH, ROOM, TRANSPLANT do not change: they are in the CLI, the recipe and the lineage | house, `design/mutate-v2/README.md:30` (2026-09-12) |
| Disabled controls | "Answered (b), but per control rather than as a blanket rule … The silent middle is what is banned" | owner, recorded 2026-09-19 (`docs/UX_JOURNEY_PLAN_2026_09.md:944-956`, commit `1349b0fb`) |
| Owner pages | at most 10 clips and 3 questions a page, each question one decision; phone checks give directions from the home screen | owner's standing preference, saved after round two's 111 clips were found too many (as recorded in `2026-09-30-become-strung-say-design.md:1596-1599`); directions added in the brief, 2026-10-03 to 2026-10-04 |
| Copy | stated as fact; every `Copy` constant shouts and stops; one word must not mean two things on the card; no product or maker names | house rules (`Personality.kt:3-14`; `PT:726`; `2026-09-13-fx-rack-expansion-design.md:164-168`) |
| HEAR == KEEP | preview and apply share one mapping and one render | house (`MST:96`; BECOME spec) |
| Where the logic lives | `:app` cannot be compiled here, so logic is in `:shell` with tests; `ConventionTest`, CI and the owner's phone check `:app` | the environment |
| Landing | a docs-only PR, zero check runs by design | house, as BECOME and MAGNET |
| This document | approved, with the defaults of the decisions below except where answered | owner, 2026-10-04, in review of PR #448 |
| The commit verb (Decision 1) | KEEP | owner, 2026-10-04, in review of PR #448 |
| UNDO after a BUILD ON THIS chain or a DRIFT on a kept pad (Decision 4) | back to the original, restored directly, with the cost Decision 4 records | owner, 2026-10-04, in review of PR #448 |

## Decisions for the owner

Each with the default this document takes (in bold). A default stands until the
owner says otherwise. Decision 1 alone is the brief's own default (KEEP or SAVE);
the rest are places where this document had to choose something the brief leaves
open, or where following the brief has a cost the owner should see. **The owner
approved this document on 2026-10-04, answered Decisions 1 and 4 (KEEP; back to
the original), and took the other defaults.** Each still stands only until the
owner says otherwise.

| # | Decision | Default | Alternatives | What it costs |
|---|---|---|---|---|
| 1 | The commit verb. **Answered by the owner, 2026-10-04: KEEP** | **KEEP** (the card rule is one meaning per word on a card) | SAVE | KEEP also means KEEP ROOM on OUTSIDE (`COPY:1605-1606`) and TAPE's KEEP, on other screens. SAVE matches DRIFT's new label, `BLEND & SAVE`, which describes the same act, so SAVE gives the card one verb instead of two |
| 2 | BUILD ON THIS | **Off by default, held per pad, opening off on every pad** | Remembered per kit; or on by default (today's chaining) | Per kit carries a deliberate chain onto pads that never asked for one; on by default keeps bug 7 as the common path |
| 3 | The chained-pad line. **The brief's "says how to unchain" is not met on the phone**: no phone control unchains a pad, and sending the chop again makes new pads, leaving this one chained | **`THIS PAD PLAYS ITS SLICES IN TURN. MUTATE WANTS ONE SAMPLE - SEND THE CHOP AGAIN IN CLASSIC FOR PADS OF ONE SAMPLE.`** | Also name the CLI's `robin --undo`; or name nothing and say only that it cannot be mutated; or build a phone unchain control (out of scope) | Copy shouts, so a CLI command in it reads `ROBIN --UNDO`, which does not run as typed (the flag is lower case, `KB:1190`), and `robin --undo` does not undo a `--break-pad` chain (`ChopCommand.kt:316-319`, also `dig` and `beat`). Naming nothing breaks "say the next step". The default is true for every chain but leaves the pad chained |
| 4 | **Answered by the owner, 2026-10-04: back to the original.** What UNDO restores after a BUILD ON THIS chain or a DRIFT on a kept pad, where the newest take is not the original | **The brief's: `UNDO · BACK TO THE ORIGINAL`, restoring the entry the tombstones mark as the original, directly** | `UNDO · ONE STEP BACK`, restoring the newest take | The direct restore does not re-bin the original, so the chain's in-between results become the newest takes of the pad's file, and newest-first restores (TREATMENT's UNDO, `untreatPad`; DUST's and SMEAR's restore-first) can then bring back a stale mutate result when the original carried a treatment. One step back keeps the bin's order but does not do what the brief's label says |
| 5 | What a second KEEP does to the last result | **Replaced, not binned** (DUST's and SMEAR's restore-first idiom) | Bin it too | Binning it puts the last result into the bin as a mutate take that newest-first restores can find after a later UNDO (Decision 4's cost). Replacing it loses the last result's bytes; its recipe can rebuild it while its partner exists |
| 6 | Does DRIFT start from the original too? | **No**: DRIFT builds on what the pad holds, as today | Restore-first for DRIFT as for KEEP | The brief keeps DRIFT unchanged, and its tests and laws stay; the cost is that a DRIFT on a kept pad builds on the result, so its UNDO depends on Decision 4 |
| 7 | The recipe a BUILD ON THIS keep writes | **The last step only**, as the CLI's chaining and every treatment write it; no new key | A `built` flag inside `mutate` (written only when true) so the strip can read `· BUILT ON` | The `mutate` keys are permanent and shared by several writers (BECOME spec `:585-592`); a new one needs the extras guard (`MU:227`) widened and every reader to tolerate it |
| 8 | Who lands the chooser lift | **M2**, building `PadChooser` and `PadChooserPanel` to R4's description; R4 reuses them | R4 lifts it as the TERRA spec says, and M2 waits for it, or builds on it | M2 first shrinks R4 to its own door; R4 first holds M2's layout round until TERRA's phone round lands |
| 9 | The `ONE HIT FROM TWO` title row | **Removed in M2**; the legend MUTATE names the box and the pairing line heads the card | Kept above the pairing line | It is one of the feature's four names, and the brief's sketch has no title; keeping it costs a row |
| 10 | BECOME's sub-label on MORPH | **`HOW LONG THE TURN TAKES`** | No sub-label on BECOME's live row | The brief gives the five knob meanings and the dead-row line, not this one |
| 11 | When a length change is mentioned | **When the result's length differs from the pad's by at least 10 % and at least 50 ms** | Always, with both lengths; or only for a list of moves | Always is noise on TRANSPLANT, which alone keeps the pad's length, and on STACK, SPLIT, SPLICE or MORPH with a partner no longer than the pad. A move list cannot say when STACK, SPLICE or SPLIT onto a long partner runs long (`MU:311`, `:326`, `:347`); the threshold can |
| 12 | ROULETTE's resting label | **`ROULETTE · PICK A PARTNER OFF THE SHELF`** (39 characters, a full row) | `ROULETTE · PICKS ONE` (20) | The long form says what it does; the short one fits a half row if ROULETTE ever shares one again |
| 13 | Three rows that land earlier than the brief's round table | **The knob under the moves, the verb KEEP, and the bare `▸` off the commit button, all in M1** | Hold them for M2 | M1's move line and knob meaning read together only if the knob sits under the line, and DRIFT beside KEEP needs the word KEEP; holding them makes M1's card read MUTATE ▸ beside a DRIFT that says SAVE |
| 14 | The partner picker | **A panel over the sheet, opened by CHANGE ▸** | An inline fold that opens the source rows inside the card | The inline fold changes the card's height when it opens and does not fit `▸`'s house meaning; the panel is one more surface, and R4 needs one anyway |
| 15 | What a default KEEP starts from when one step back is not the original (after a BUILD ON THIS chain, after a DRIFT on a kept pad, after another screen's recipe-keeping rewrite) | **The brief's: the original, the entry the tombstones mark, restored directly**; on a pad whose bin marks no original (mutated before M3), the newest take, said plainly (FROM_BEFORE) | One step back, as DUST and SMEAR restore, said in the toast (`MADE FROM AN EARLIER RESULT. THE LAST ONE IS REPLACED.`) | The default follows the brief wherever the bin records the original. Its costs: the chain's final result is replaced without binning (as every second KEEP's is, Decision 5), and the in-between entries stay in the bin behind the re-binned original. On a pre-M3 pad nothing records the original, so no rule can reach it; that one exception shrinks as old takes purge. One step back departs from the brief in all three cases |
| 16 | Where the knob's meaning sits | **After the value, on the knob's own line, as the brief's sketch draws it** | A caption line under the bar, inside the knob's row | The default leaves the bar about 86 dp wide on a 390 dp sheet (*estimate*, measured first in the M1 plan). The caption keeps the bar full width but changes the approved layout and adds about 14 dp to each knob row, 28 dp to the card; it needs the owner's yes |
| 17 | KEEP's label when `KEEP · <MOVE> × <partner>` does not fit the half row | **Two lines in the half row, `KEEP · <MOVE>` over `× <partner>`, the second cut only past the partner's tag** | KEEP on a full row with DRIFT on the row below; or a wider KEEP and a narrower DRIFT; or one line with the partner cut | Two lines need `ActionButton` to take `maxLines` and fit 48 dp (*estimate*). The full row departs from the brief's side-by-side sketch and adds a row. A narrower DRIFT ellipsizes its 20 characters. One line cut to 21 drops the partner entirely on `KEEP · TRANSPLANT × …`, so the button no longer says what it saves |

## Appendices

### Appendix A: the code this design touches, at `0ba9b053`

| Thing | Where |
|---|---|
| The card | `PSS:3513-3798` (`MutateCard`, its KDoc from `PSS:3492`); called at `PSS:2565-2598` inside the MUTATE group box (`GroupBox(` at `PSS:2558`) |
| Card state | `PSS:1183-1251` (`mutateMode`, `partner`, `spins`, `rooms`, `otherKits`, `pickedKit`, `mutateKnobs`, `pendingBecome`) |
| The doors | `onMutate` `PSS:1273-1316`; `onHear` `PSS:1338-1364`; `onUnmutate` `PSS:1366-1368`; `onRoulette` `PSS:1371-1389`; `onDrift` `PSS:1411-1494`; `onFilePicked` `PSS:1504-1525` |
| Play after a write | `auditionOnRefresh` `PSS:296`; its effect `PSS:388-399`; set by SMEAR at `PSS:694` |
| The audition door | `audition()` `PSS:366-377` |
| The knob row | `StepperSlider` `PSS:3099-3182` |
| The button | `ActionButton` `PSS:3914-3966` (`dimmed` `:3918`, `lit` `:3926`, `accessibilityLabel` `:3941`, `maxLines = 1` `:3963`); `ToggleChip` `PSS:3187-3217` (its ON/OFF label `:3206`); `tapeClick` `app/src/main/kotlin/com/snipsnap/app/ui/Chrome.kt:180-188` |
| SOFT HITS | the chip `PSS:2393-2401`; `onGhostsToggle` `PSS:1109-1117` |
| The sheet's door | `MS:36-357` (`KNOBS` `:45-57`, `BECOME` `:70`, `DRIFT_FRACTION` `:86`, `label` `:95-101`, `partners` `:111`, `Partner` `:114-129`, `name` `:132-138`, `hold` `:152-162`, `read` `:199-211`, `drift` `:219-222`, `deal` `:230-233`, `source` `:236-252`, `knobs` `:274-284`, `preview` `:300-317`, `apply` `:326-353`, `undo` `:356`) |
| The verb | `MU:39-296` (`roulette` `:76-112`, `render` `:172-202`, `apply` `:204-261`, `drift` `:273-290`, `undo` `:293-296`, `stack`'s flip `:300-321`) |
| The bin and rewrites | `replaceAudio` `KB:494-506`; `dustPad` `KB:519-539`; `smearPad` `KB:586-633`; `untreatPad` `KB:804-810`; `clearGhostLayers` `KB:813-819`; `binContents` `KB:1077-1089`; `restoreFromBin` `KB:1101-1129`; `requireRewritable` `KB:1174-1181`; `requireNotChained` `KB:1186-1192`; `moveToBin` `KB:1256-1273`; `landArranged` `KB:312-316`; `fromChop` `KB:1329-1333`; `playBefore`'s locked bin read `PSS:409-428` |
| Other writers that keep the recipe | `replaceAudio(slot, null)` at `SplitScreen.kt:364`, `SnapScreen.kt:514`, `:567`, `:620`, `:714`, `SurfaceScreen.kt:1010` (all under `app/src/main/kotlin/com/snipsnap/app/ui/`) |
| The partner reads | `source` `MS:236-252`; `KitStore.load` `KitStore.kt:33-37` |
| The strip | `PadSheetBoxes.kt:70-77`, `:120` |
| The copy | `COPY:1503` (`mutated`), `:1520` (`drifted`), `:1521` (`UNMUTATED`), `:1522` (`MUTATE_NEEDS_ONE`), `:1523` (`CRATE_EMPTY`), `:1525` (`fileRefused`), `:1543-1544` (`replayNeedsParent`), `:2145` (`actionFailed`) |
| The laws | `CT:1824`, `:1866`, `:1901`, `:1938`, `:2002`, `:2058`; `MST:96`, `:142`, `:369`; `PT:649-676`, `:726`, `:799`, `:810-813` |

### Appendix B: where the evidence lives

- `~/Documents/snipsnap-chimera-evidence-2026-09-29/mutate-ux/card-walkthrough.md`
  (`walkthrough`): §1 the path a first-time user walks, §2 every label, §3 the
  surprises ranked, §4 what is already good, §5 pointers.
- `…/mutate-ux/house-design.md` (`house`): §0 the bottom line, §2 critiques already
  made, §3 the mutate-v2 proposals with their board text, §4 what shipped, §5 what
  was deferred, §6 the rules a redesign must follow, §7 gaps, §8 the source map.
- `…/mutate-ux/patterns.md` (`patterns`): sixteen patterns from other tools, from
  knowledge.
- `…/mutate-ux/synthesis.md` (`synthesis #N`): §1 the nine confusion points ranked,
  §2 the house's answer to each, §3 three directions, §4 the quick wins, §5 open
  questions.
- The design brief the owner approved, restated in full in "Why" and "The design".
- The reports read the `claude/a1-become` branch. Every cite in this document was
  re-read at `0ba9b053`; where a report's line number differed (for example
  `MutateSheetTest`'s HEAR = KEEP test, `:93` in the reports and `:96` here, and the
  restore-first idiom, `~513-535` in the brief and `KB:519-539` here), this
  document gives the tree's.

### Appendix C: Review notes

One review pass, 2026-10-04, with 34 findings. Each was checked against the tree
at `0ba9b053` and against the brief before it was applied. Thirty-three are
applied; one, the reviewer's own verification record, needed no change. Where the
fix taken differs from the one the finding suggested, the reason is given.

1. **Blocker: the `Partner` type alias does not compile.** Applied. Confirmed: both
   modules pin Kotlin 2.0.21, and nested type aliases need 2.2. `Partner` stays in
   `MutateSheet` as the real type, `PadChooser` works over it, and an optional
   top-level `typealias Pick` is for R4 only ("The shared pick-a-pad component").
   The "pass unedited through the delegation" claim now holds, because only
   functions move.
2. **Major: the first-tap rule is unsound in M2.** Applied. Confirmed (`MS:306`,
   `MU:230`). In M2 a KEEP leaves the settings unheard (`heardAfter(KEEP) = null`).
   From M3, with BUILD ON THIS off, they stay heard, with a test written first
   against M2's rule.
3. **Major: nothing in M1 supplies the after-length.** Applied. Confirmed (`MU:46`,
   `MU:264`, `PSS:327`). M1 adds `MutateSheet.lengthMs`, which the doors call
   before and after inside `withFreshKit`, with a `:shell` test at 44.1 and
   48 kHz. A function was chosen over a new return type, so `apply`'s and
   `drift`'s signatures, `MST` and `CT:1901`/`1938`/`2002` stay as they are.
4. **Major: three of the four "partner gone" cases land on the generic toast.**
   Applied. Confirmed (`KitStore.kt:35`; `WavReader.read` at `MS:241`, `:242`,
   `:251`). `source` checks each kind before reading and throws `PartnerGone`,
   with one test per kind.
5. **Minor: `keep` has no `requireRewritable` before the restore.** Applied.
   Confirmed (`KB:1101-1125`; `PSS:1296` checks layers only). It is now `keep`'s
   and `undo`'s first line, pinned by a source law, with refusal tests asserting
   the bin is untouched.
6. **Minor: FROM_BEFORE is false after another screen's recipe-keeping rewrite.**
   Applied. Confirmed (`KB:505`; the six `replaceAudio(slot, null)` callers). The
   fix differs from both suggestions. `replaceAudio` is unchanged, so the CLI's bin
   stays byte-identical. `base()` no longer takes the newest take: it looks for the
   newest entry whose tombstone marks the original. FROM_BEFORE remains only where
   no entry marks it, and its toast says that plainly. Regenerability is narrowed
   to "while the card's KEEP was the last write".
7. **Minor: the CHAINED line does not unchain the pad.** Applied. Confirmed
   (`fromChop`, `landArranged`, `--break-pad` in chop, dig and beat). The line now
   says what is true. Decision 3 flags to the owner that the brief's "says how to
   unchain" is not met on the phone. The cites are corrected.
8. **Minor: SPLIT and STACK do not keep the pad's length.** Applied. Confirmed
   (`MU:311`, `:347`). Finding 10 and Decision 11 now say that only TRANSPLANT
   keeps the pad's length.
9. **Minor: the accessibility claims have no semantics behind them.** Applied.
   Confirmed (`Chrome.kt:180-188`, `PSS:3562-3576`, `PSS:3206`). State is folded
   into labels, the house idiom. The new parameters (`ActionButton.maxLines`,
   `ToggleChip.dimmed`) are listed under "What changes in `MutateCard`".
10. **Minor: "cleared by any write but this door's own", and "play instead of
    write", are unspecified.** Applied, by a different mechanism from the one
    suggested:
    - `heard` stores the model it was heard on and counts only while that model
      is live, and only the KEEP door re-stamps it after its write.
    - A base that changed returns `NotHeard`. The door writes nothing, clears
      `heard` and toasts, so no audio is started from the `appScope` write.
      Setting `auditionOnRefresh` would have played the pad's file, not a render.
    - ConventionTest laws are added for both.
11. **Minor: HEAR and MINE read the bin without the writers' lock.** Applied.
    Confirmed (`PSS:409-428`, `KB:1266-1267`). The base is chosen under
    `KitWrites.mutex` and decoded outside it, as `playBefore` does, with a law.
12. **Minor: "a tombstone never reinstates a pad" is overstated.** Applied.
    Confirmed (`KB:1115-1119`). The sentence is narrowed to UNDO and KEEP.
    Reinstating after a DELETE is decided acceptable, with the reason given, and a
    `KitBuilderTest` case pins it.
13. **Minor: the strip and UNDO disagree on an OUTSIDE ROOM pad.** Applied.
    Confirmed (`PadSheetBoxes.kt:120`, `OutsideSheetTest.kt:187`). From M3 the
    strip and the card ask `carriesMutate`, with a `PadSheetBoxesTest` case. The
    item leaves "Out of scope".
14. **Minor: `roomKept` and `roomLanded` still say `MUTATE ▸ ROOM`.** Applied in M2.
    Confirmed (`COPY:1606`, `:1620`; `RoomsTest:174`, `:188` pin a start with the
    room's name, `MUTATE` and a full stop, all kept). `app/README.md:494-509`, the
    KDocs and the two comments are listed too.
15. **Minor: name budgets.** Applied. This kit's pads go two to a row in the panel.
    The pairing line cuts both sides to a 28-character budget rather than wrapping,
    because wrapping would change the card's height. The partner row is the place
    that names the partner in full. The budgets test uses 24-character names on
    both sides.
16. **Minor: four mismatches with the tree.** Applied:
    - "a smell" is attributed to the BECOME spec `:619-621`;
    - `README.md:28` is quoted exactly;
    - GHOSTS is described as another feature's word that users see;
    - `ActionButton` is cited as `PSS:3914-3966`.
17. **Minor: the verification record.** Skipped: no defect, no change needed. The
    reviewer's re-read of about 110 cites found them correct.
18. **Blocker: the first-tap rule in M2, and `Pending` has no base.** Applied. The
    M2 part is the same change as #2. `Pending` gains `base` (null in M2, the
    base's key from M3). The KEEP door computes that key after its own write,
    because a first KEEP moves the base from the live file to the binned original;
    the key is built from the entry's content, so a later re-binning of the same
    original does not change it. The "base changed" failure
    case is now an equality the rule tests.
19. **Major: the knob's sub-label moved off the brief's line without a decision.**
    Applied. The default is now the brief's inline form. The caption under the bar
    is the alternative in Decision 16, and the M1 plan measures the bar first.
20. **Major: Decision 15's default departs from the brief.** Applied:
    - KEEP's default now follows the brief. It restores the entry the tombstones
      mark as the original, directly, wherever the bin records one. FROM_EARLIER
      is gone.
    - UNDO's direct restore leaves stale takes newest in the bin, a cost KEEP's
      re-binning avoids, so Decision 4 is marked "owner answer needed before the
      M3 plan", with the brief's behaviour as its default.
    - The phasing table says plainly where M3 is narrower than the brief.
21. **Major: the 21-character cut drops the partner.** Applied. KEEP keeps the
    brief's side-by-side row and goes to two lines when needed. This needs
    `ActionButton.maxLines`; the fit is an *estimate*. Decision 17 holds the
    alternatives, and a test asserts that no KEEP label loses its partner.
22. **Major: the empty room row is over budget and reuses KEEP.** Applied.
    `NO ROOM YET · MAKE ONE IN OUTSIDE ▸ ROOM` (40 characters) is added to the
    budget and one-word tests.
23. **Major: UNDO stays grey with no reason through M1 and M2.** Applied.
    Confirmed (`PSS:3550`). From M1, UNDO is dimmed with `UNDO_NOTHING` or
    `UNDO_NOT_BINNED`, both moved from M3's `Copy` list to M1's. The accessibility
    sentence is corrected, and UNDO joins the "no silent middle" law.
24. **Major: A1b's lines were dropped from the phone check.** Applied. All eleven
    are steps of M1's three questions, marked (A1b *n*), including TalkBack (10),
    the pad arrows (8) and the empty shelf (11), with its set-up. QQ4 flips only
    because all eleven are covered.
25. **Major: renaming `replayNeedsParent` changes CLI output.** Applied, by stating
    the change rather than keeping a CLI-only constant: one word in one error line,
    and the brief retires the word. Confirmed (`RecipeReplay.kt:65`,
    `RecipeCommand.kt:43`). The "byte for byte" claims and "Out of scope" now name
    the exception, and a CLI pin is added.
26. **Major: every phone-check question had two parts.** Applied. All nine
    questions are now one decision each, the steps say what should happen, and
    the "Getting to the card" directions are kept.
27. **Major: BUILD ON THIS can outlive its mutate.** Applied:
    - `base()` asks `carriesMutate` before `buildOn`;
    - an effect turns the switch off when the pad stops carrying a MUTATE;
    - a tap always turns it off;
    - the note line requires both;
    - a test covers it.
28. **Minor: undated owner answers, and "Decisions 1 and 2 are the brief's".**
    Applied. The disabled-controls answer is dated 2026-09-19 by `git blame`. The
    pages rule cites the BECOME spec's record of it. "Decision 1 alone is the
    brief's".
29. **Minor: two quotes do not match the house files.** Same change as #16.
30. **Minor: ONLY_COPIES says "copy".** Applied. It now uses the brief's word,
    DOUBLE, and cites `Crate.DUPE_DISTANCE`.
31. **Minor: `ROBIN --UNDO` shouted, and TAKES used for two things.** Applied
    together with #7. The default line names no CLI command and says SLICES.
    Decision 3's cost records the lower-case flag and the `--break-pad` gap.
32. **Minor: BECOME's dead row was given three forms.** Applied. There is now one
    form: the label column reads `BECOME` (blank for STACK's knob row), followed
    by the words. The sketch's `—` is the gap between them. The strings table and
    the tests use that form.
33. **Minor: from M3, ▶ MINE plays the original with no cue.** Applied. MINE's
    spoken label adds `, BEFORE ITS MUTATE` and a tap toasts `MUTATE_MINE_BEFORE`
    when the base is not the live file.
34. **Minor: inconsistent cite forms.** Applied:
    - repo-relative paths in the alias table;
    - no `.../` paths;
    - one range per thing (a declaration without its KDoc, stated);
    - `Moves.dc.html:45-50` throughout;
    - full parameter lists for `recipeFor`, `write`, `keep`, `preview` and
      `KnobRow`.
