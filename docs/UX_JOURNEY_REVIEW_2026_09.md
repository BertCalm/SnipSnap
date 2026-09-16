# Journey and information-architecture review, 16 September 2026

An adversarial read of the app as a *sequence of things we ask a person to
do*, rather than as a set of features. Every step has to justify itself.
If a user cannot tell what to do next, or does something that makes sense
to the code and not to them, that is a finding here even when every
callback is correctly wired.

## Why this is a second review

[`UX_WIRING_REVIEW_2026_09.md`](UX_WIRING_REVIEW_2026_09.md) (12 September)
asked, per feature: is it wired, reachable, labelled, explained. It
answered well. This review asks a different question on a different axis:
**does the shape of the app match the shape of the work?**

The two overlap in one place: several of the earlier review's still-open
findings turn out to be structural problems wearing feature clothes. They
are carried forward with that reading, and **re-verified line by line** —
see the appendix. Four of its findings (#1, #3, #5, #14) are confirmed
fixed and are not repeated here.

## Method, and its honest limit

The same limit as the first review, and it is real: **the Android app
cannot be launched in this environment** — no SDK, no device. Nothing here
is a screenshot and nothing here is a stopwatch. What *is* real: every
count was taken by grep, every string quoted is the constant the screen
draws, every line number was opened and read, and every claim in the
S1 table below was independently re-verified against source before
publication.

- **I cannot see the screen.** Claims about what is visible at once or
  falls below a fold are inferences from layout constants, marked as such.
- **I cannot watch a real person.** This is a structural critique, not a
  usability test. "A user will not find X" means "nothing in the code
  offers X to them," which is what can actually be established here.

## Severity

The first review's scale, kept for continuity:

- **S1** — a user is misled, blocked, or hears the wrong thing.
- **S2** — friction that makes the tool slower or smaller than it is.
- **S3** — polish, stale words, and things that get worse with use.

---

## The headline

**Three architectural decisions generate most of the defects in this
document.** They are not forty independent mistakes; they are three
mistakes with forty symptoms, and that is the good news.

### 1. The app set itself a convention its components cannot express

`ChopScreen.kt:1136-1137` states the rule in its own source:

> *"this screen's convention: dimmed, not disabled; the toast explains"*

It is a good rule. It is also unenforceable as built, because **two of the
app's button components have no `enabled` parameter at all**:

- `SegmentButton` (`ChopScreen.kt:1521-1541`) — no `enabled`; announces
  `selected = active` regardless (`:1533`).
- `DeckButton` (`TapeScreen.kt:1255-1300`) — no `enabled`; its `active`
  flag only swaps label ink (`:1290-1297`), and `:1283` calls `tapeClick`
  with no `enabled` argument.

`tapeClick`'s `enabled` flag is what puts a control into Compose's
`disabled()` state (`Chrome.kt:157-166`). A component that never passes it
is permanently actionable — to the touch layer *and* to TalkBack. So every
segment on CHOP and every button on TAPE is live at all times, and
authors who want to refuse are left with only two options: an early
`return` (silent), or deleting the control from the tree (it vanishes).
Both appear throughout, and both violate the stated rule.

**This is why the convention is broken up to nineteen ways in one box. The
component library does not support the design rule the app set itself.**

### 2. `remember(key)` is used as though it were storage, and it is an eraser

Both halves of this review independently found the same shape: state keyed
to something that changes during ordinary use, discarding the user's work
with no warning and no undo. `remember(model)` where `model` is reassigned
at five sites, two of them helpers the whole bench calls through; `remember(slot)` on a pinned navigation button
that isn't gated; `remember(tapeData)` where `tapeData` is replaced by a
background file arrival.

**Eighteen** distinct pieces of user-facing state are catalogued below
across J20–J26 (nine in CHOP alone, keyed to a `model` reassigned at
five sites). None of them toast. The count is of named state in those
seven findings, not an exhaustive sweep of the codebase.

### 3. The seam is cut and nothing is attached to it

Repeatedly the model supports a capability, the screen declares a variable
for it, and no control ever writes it. `recordBars` is the clearest:
`GrooveScreen.kt:569`, a `var` initialised to `2`, read twice, and
**never reassigned anywhere in the file** — so it is a mutable variable
that can only ever hold its initial value. This is invisible on a feature
checklist, because the capability genuinely exists everywhere except
under a finger.

### And one thing that is not a UX finding at all

**J1 is a correctness bug in shipped audio code.** DRIFT commits a WAV
rewrite using a stale reading of an unrelated control, then displays a
different number than the one it used. It is at the top of the S1 table
and it should be fixed on its own, ahead of anything else in this
document.

*(A correction to this review's own first draft: it initially recorded "no
new S1s" on the strength of the navigation pass alone. That was wrong. The
per-screen passes found nine.)*

Of those nine, **five destroy work the user has already done** (J2, J3,
J5, J6, J7), and of those five, **three are irreversible writes to disk
with no confirm and no bin** — J2 and J3, the two halves of the export
overwrite, plus `MAKE PAD ▸` in the irreversibility section below. The
other two (J5, J6) destroy in-memory work: corrected chips, and a hum in
flight.

---

## Findings

### S1 — a user is misled, blocked, or loses work

| # | Finding | Evidence |
|---|---|---|
| **J1** | **DRIFT writes one number and shows you another.** `onDrift` sets `mutateMode = MORPH` at `:1375`, then reads `pendingMutateKnob` at `:1376` — but that value is `remember(slot, mutateMode)`, which does not re-evaluate on a synchronous state write, so it still holds the **previous** mode's fraction. That stale value is what performs the write (`:1387`). In the default case it is worse than stale: `MODES.first()` is `STACK` (`Mutate.kt:38`), `KNOBS` has no STACK entry (`MutateSheet.kt:43-47`), so the fraction is `0f`. **Open MUTATE, tap DRIFT — the common path — and the pad's WAV is rewritten with a 0% blend, after which the card recomposes and draws MIX 50%.** From other moves, SPLICE's `AT` (5–2000 ms) or TRANSPLANT's `BANDS` is reinterpreted as a 0..1 mix. The toast names a neighbour it blended none of. *Verified end to end against source.* | `PadSheetScreen.kt:1375-1376`, `:1387`, `:1203-1205`, `:1155`; `MutateSheet.kt:37,43-47,58`; `Mutate.kt:38` |
| **J2** | **EXPORT's overwrite arm is invisible and never expires.** `session.overwriting` is the armed state in which the next tap destroys an existing export. `grep` shows it is **never read by any composable** — the only read is inside the write handler (`:452`). So `WRITE KIT` reads identically armed and unarmed; the sole warning is a transient toast. The session is hoisted into `App` (`App.kt:535`), so the arm survives tab switches and remounts indefinitely. Tap, read the toast, come back an hour later, tap again — the export is replaced with no further warning. | `ExportScreen.kt:133`, `:452`, `:459-460`, `:463`, `:495`, `:617`, `:629` |
| **J3** | **The card copy always overwrites, and never warns — not even once.** The confirm in J2 governs only the phone leg (`model.write(destRoot, overwrite = …)`, `:452`). The card leg is unconditional (`CardWriter.copy(…)`, `:481-487`) and resolves through `replaceExisting` (`CardWriter.kt:142-155`), which *attempts* to delete the same-named child before writing. On a first-ever export for a kit there is no phone copy, so no arm, so no toast — and a same-named folder already on the user's SD card is replaced with no prompt at any point. For PROGRAM_FOLDER / EXPANSION that is a whole directory. **Precision, since it matters for the fix:** `replaceExisting` wraps its lookup and `deleteDocument` in `runCatching` and swallows failures, and the caller proceeds to `createDocument` regardless — so this is a best-effort replacement whose outcome is provider-dependent. Where deletion succeeds the user loses the old export silently; where it fails they may instead get a duplicate. Neither outcome was chosen by the user, and neither is announced. | `ExportScreen.kt:452`, `:481-487`; `CardWriter.kt:96`, `:142-155` |
| **J4** | **One pinned button turns PAD SHEET into a dead grey screen with no explanation.** The pad-nav arrows (`:2476-2492`) are not gated on `busy`. Tap `►` during a treatment and: `busy` is `remember(model)` so it stays `true` on the new pad (every control greys out); `applyingSegment` is `remember(slot)` so the "working on X" header resets to plain `TREATMENT`; `outsideStage` is `remember(slot)` so SEND drops `LISTENING…`. Result: everything disabled, nothing saying why — and unlike the prior review's #19, the user never left the screen, so "I navigated away" isn't even available as an explanation. | `PadSheetScreen.kt:2476-2492` vs `:257`, `:826`, `:1491` |
| **J5** | **RE-CHOP silently destroys every chip the user corrected.** `:955` calls `current.rechop()`, not the bench's own `rechopKeeping` (`ChopReview.kt:497`) that every segment and stepper uses (`:437`). Relabel sixteen slices — thirty-two taps — then tap RE-CHOP, which sits immediately left of SEND TO GRID in the same row, same component family, no confirm. The toast is `"RE-CHOPPED."` No undo. The destructive semantics are documented only in `docs/CHOP_CONTROLS.md:48`. | `ChopScreen.kt:944-956`; `Personality.kt:1112` |
| **J6** | **RE-CHOP during a hum destroys the take in flight.** Its gate (`:944`) omits `humming`, so it is bright and live while the user is performing. It reassigns `model`, which flips `humming` false (`remember(model)`, `:407`), cancels the `LaunchedEffect(humming)` auto-stop that would have *read* the hum (`:644-648`), and releases the voice (`:529-531`). `stopHum` is never reached; the mic tail is never read. Toast: `"RE-CHOPPED."` | `ChopScreen.kt:944`, `:956`, `:407`, `:644-648` |
| **J7** | **A share-sheet import destroys a live CATCH session with no word.** `LaunchedEffect(kitDir, reloadToken, reloadRequest, retrim)` (`:281`) fires on a bumped `reloadRequest` unconditionally — the `!playing && !hasSelection` guard at `:566` belongs to a different watcher. The reload swaps `tapeData` → swaps `model` → `catching` (`remember(model)`) becomes null → the grid collapses mid-catch. `finishCatch` never runs, so `onCatchDone()` never fires. Needs no user mistake at all. | `TapeScreen.kt:281`, `:566`, `:624`, `:808-818`, `:932` |
| **J8** | **`▶ HIT` plays the pre-treatment audio during a write, and is silently dead before the decode lands.** `HeaderChip` defaults `enabled = true` (`:2743`) and `▶ HIT` passes none (`:2734`). Its two siblings in the same Row are both explicitly gated for exactly this reason — `◄ KIT` `enabled = !busy` (`:2715`) and `◀ BEFORE` `enabled = !busy` (`:2731`), the latter commented "a treatment mid-write is about to swap the model, and the take it would play is about to change". ▶ HIT was missed. It is also fully lit and inert until the IO decode returns, and forever on an undecodable file. | `PadSheetScreen.kt:2734`, `:2743`, `:2050`, `:286-317` |
| **J9** | **Every *filled* KIT pad is silently dead if the audio engine didn't start — and still glows as though it played.** `hit()` returns on `!engineUp \|\| !player.isUp()` (`KitScreen.kt:189-199`); `engineUp` is never surfaced anywhere. For a filled pad the glow animation fires *before* `hit` (`:857-860`), unconditionally — so the pad animates correctly and produces silence, with nothing saying the engine is down. *(Empty pads are unaffected: `PadCell` returns at `:818` before the glow is created, so this is every filled pad, not all sixteen.)* | `KitScreen.kt:189-199`, `:818`, `:857-860`, `:153-173` |

### S2 — friction, misallocated attention, half-built seams

| # | Finding | Evidence |
|---|---|---|
| **J10** | **Two of the four steps in the app's stated loop have no handoff.** Counting `screen = AppScreen.X` across `App.kt`: KIT 11, KITS 4, TAPE 3, GROOVE 3, SPLIT/KEYS/HELP 1 — **CHOP 0, EXPORT 0**. Finishing a capture never offers CHOP; finishing a kit never offers EXPORT. CHOP → KIT *is* wired. The app automates the one join in the middle and leaves the two at the ends to the user. | `App.kt:2663`; `Chrome.kt:198-216`; `Personality.kt:46-49` |
| **J11** | **Back is "go to the shelf," not "go back."** One root `BackHandler` fires `goToScreen(AppScreen.KITS)` from every tab but KITS/SPLIT/KEYS. KIT → GROOVE → Back lands on KITS. *(Credit: overlays each own a correct `BackHandler`, LIFO layering is right, SPLIT and KEYS have real back chips. The root policy is the problem, not the plumbing.)* | `App.kt:2111-2129` |
| **J12** | **The menu row presents twelve peers that are not peers.** A library, a four-step sequence the app insists is ordered, five parallel instruments, two utilities — one flat scrolling strip, no grouping, no separator. | `Chrome.kt:217-230` |
| **J13** | **"PLAY IT" names a tab that is not that step.** The first-run note glosses step 3 (KIT) as "PLAY IT"; PLAY is a real tab at position 6. The guarded law covers the four stage names, not the sentence under them. | `Personality.kt:64`; `Chrome.kt:223`; `ConventionTest.kt:1086-1101` |
| **J14** | **KIT is the app's hub dressed as step 3 of 4.** 11 of 24 programmatic navigations land there — nearly half, and almost three times the next destination (KITS, 4) — yet it sits 4th in the strip and is named one letter from KITS, the screen Back always returns to. | `App.kt` (11 sites); `Chrome.kt:218,221` |
| **J15** | **PAD SHEET: the largest surface in the app costs one invisible gesture plus up to five taps.** 3,611 lines behind a 480 ms hold with **no press-and-hold feedback of any kind** — the only press animation is the hit glow, which *decays*, so the pad visibly darkens as the sheet opens. All five boxes are collapsed on arrival and only one may be open at once. Deepest useful control (MUTATE with another kit's pad) is hold + 5 taps + a scroll. Box state resets per kit. | entry `KitScreen.kt:861-864`, `:739`; boxes `PadSheetBoxes.kt:25-31`; `App.kt:329`; toggle `PadSheetScreen.kt:2014` |
| **J16** | **Two of PAD SHEET's three doors fail to retire its own hint.** The hint is cleared only inside `KitScreen`'s `onLongPress` (`App.kt:2545`). Users who found the sheet via DOUBLES' `GO ▸` (`:2214-2228`) or the RE-TRIM return (`:1544`) are told "HOLD A PAD TO OPEN ITS PAD SHEET" on every kit open, forever. The hint's own KDoc claims opening the sheet is "the only event that proves they found it" — two of the three events that open it don't count. | `App.kt:2545`, `:2214-2228`, `:1544`; `Personality.kt:300-311` |
| **J17** | **GROOVE's bar count: the seam is cut, nothing is attached.** `recordBars` is a `var` initialised to `2` with exactly two executable reads (`:900`, `:1369` — the other mentions are comments), which no control ever reassigns: a mutable variable that can only hold its initial value. `startEmpty`'s `bars` parameter is never passed by its one caller. And selecting **PROG C** doubles pattern length via `GrooveVariations.halfTime` (`GrooveProgram.kt:47`) — a length change reached through the program carousel, with nothing on screen saying the choice also changes how long the pattern is. The model supports any bar count. | `GrooveScreen.kt:569`, `:900`, `:1369`, `:1001`; `GrooveEdit.kt:153`; `GrooveProgram.kt:47`; `kit/GrooveVariations.kt:92` |
| **J18** | **PROG A–E is a carousel over a set the user never sees whole, labelled index-first.** Prev/next only; four taps to learn the options; `PROG A · THE BREAK` leads with the MPC clip-slot index and buries the meaning. The A–D/E editability split is legible only if you already know what to look for: `PROG E · EDITED` / `FORKED — YOUR STEPS` (`:174-187`) mark E as the fork, and `EDIT STEPS` sits mid-scroll, but nothing marks A–D read-only and nothing says a fork is what `EDIT STEPS` does. CHOP already owns the right control (`SegmentButton` rows, all options visible, `selected` semantics). | `GrooveScreen.kt:174-188`, `:1756-1768`, `:2122-2158` |
| **J19** | **GROOVE's only hand-editing door is mid-scroll under the third heading.** `EDIT STEPS` is the sole route to PROG E, inside the scrolling region below the swing stepper and feel row — while `RECORD` is correctly anchored below it. | `GrooveScreen.kt:1989`, `:1871-1876`, `:2084-2088` |
| **J20** | **CHOP: five sites reassign `model`, and nine pieces of user state are keyed to it.** Two of the five are shared helpers — `rechopTo` (`:438`) and `editSlices` (`:515`) — called from most of the bench, so the number of *controls* that can erase that state is much larger than five. Lost on any bench nudge: the layout row (snaps to CLASSIC — prior #12), the melodic placement and its pitch DSP, the `A2`/`C#4` labels, an **open class picker closing under the user's finger**, and the hum. A KEEP on TAPE or a kit switch discards the *entire* chop — every relabelled chip, merge, split, mode and ear — with no warning. | `ChopScreen.kt:388`, `:395-396`, `:378`, `:407-408`, `:127-134`; reassigned at `:438`, `:515`, `:625`, `:787`, `:956` |
| **J21** | **CHOP: leaving `BY HITS` and returning silently resets EAR, CUT and ON THE GRID — and the header stops printing them in the same frame.** `onByHits` (`:745-747`) falls through `hitsOf(...) ?: ByHits()`, constructing fresh defaults. `modeLabel()` prints those three only when they differ from default (`ChopReview.kt:273-279`), so the evidence disappears simultaneously with the settings. | `ChopScreen.kt:745-747`, `:757-758`; `ChopReview.kt:110`, `:273-279`, `:689-691` |
| **J22** | **TAPE: a snip landing silently replaces the tape under the user's finger.** Zoom, odometer mode, IN, OUT and position all reset (`model` is `remember(tapeData)`, `:533`; the idle guard at `:566` covers neither `zoomPx` nor `odometer`). The quick-settings SNIP tile makes this reachable without leaving the screen. The only toasts on that path are OOM and truncation. | `TapeScreen.kt:533`, `:555-573`, `:566`, `:281-303` |
| **J23** | **PAD SHEET: an unkept measured room is destroyed by the ungated `►`.** A ROOM trip is a multi-second live mic capture of the physical room; its result sits in `measuredRoom` (`remember(slot)`, `:1494`) until `KEEP ROOM ▸` shelves it. One tap of the un-gated pad-nav arrow discards it. Recovery means doing the trip again. Nothing warns. | `PadSheetScreen.kt:1494`, `:2487`, `:1675-1702` |
| **J24** | **PAD SHEET / MUTATE and OUTSIDE knobs reset on every move switch.** `pendingMutateKnob` is `remember(slot, mutateMode)` and `pendingOutsideKnob` is `remember(slot, outsideMove)`. Dial SPLICE's `AT` to 400 ms, tap MORPH to compare, tap back — 40 ms. This is also the mechanism behind J1. | `PadSheetScreen.kt:1203-1205`, `:1488` |
| **J25** | **KIT / TEXTURE: the knob resets on mode switch, the mode resets on panel switch, and the source pad jumps when the kit's lowest slot changes.** The last is keyed on *the value of the lowest assigned slot* (`:366`), so any chop landing or capture that fills A01 silently re-points the SOURCE stepper — and `SCULPT ▸ NEW TAPE` has no per-pad confirm. | `KitScreen.kt:366`, `:367`, `:369`; `TextureKits.kt:107` |
| **J26** | **PAD SHEET: DEPTH and BLOOM never persist, and look identical to three sliders two rows above that do.** Both are `remember(slot)` with `onFractionCommit = {}`, read only at the instant `MAKE PAD ▸` fires. LEVEL/PAN/TUNE, same component, persist. | `PadSheetScreen.kt:1705-1706`, `:2380`, `:2390`, `:1740` |
| **J27** | **CHOP's CUT box goes dead a whole box at a time, without dimming.** Fifteen `SegmentButton`s wrap their own handler in `if (!busy)` — 5 on the zoom ladder (COUNT + four `Ladder.Rung`s), 3 ear, 3 cut, 4 grid-snap — and four more (BY HITS / GRID / GHOSTS / HUM) call through to `rechopTo`, which returns on `rechopBusy \|\| sendBusy \|\| humming` (`:432`). How many are on screen is mode-dependent: the ladder row needs GRID/LADDER, the other three rows need a hits chop, so it runs from 4 dead controls to 19. No dim is possible — `SegmentButton` has no `enabled` — and `:1533` announces `selected` regardless. Prior #9, confirmed and wider. | `ChopScreen.kt:1299-1307`, `:1338-1368`, `:432`, `:1521-1541` |
| **J28** | **CHOP: `MERGE` / `SPLIT` and slice auditioning are bright and dead during a hum.** Both take `enabled = !busy` where `busy` excludes `humming` (`:897`), and `editSlices` returns silently at `:506`. Row audition is `tapeClick` with **no `enabled`** and `audition` opens `if (humming) return` (`:652`). So the one state where they are genuinely unusable is the state they are painted usable in. | `ChopScreen.kt:897`, `:1142`, `:1148`, `:506`, `:1389`, `:652` |
| **J29** | **CHOP: `GRID` reads SELECTED while on a ladder rung, and tapping it throws the rung away.** `:1300` sets `active = mode is Grid \|\| mode is Ladder`; `onGrid` only checks `mode !is Grid` (`:749-752`), so it re-chops. Meanwhile `COUNT` reads *not* selected. The user is shown "you are on GRID / not on COUNT," and tapping GRID puts them on COUNT. | `ChopScreen.kt:1300`, `:749-752`, `:1338` |
| **J30** | **CHOP never names the tape it is about to slice.** Nothing on screen identifies the source. The filename appears once, at `:981`, used to derive a kit name the user also never sees. The load is a silent three-tier fallback — last TAPE commit, else the open kit's *longest sample*, else nothing — so a user who opened CHOP without taping is about to slice up whichever pad happens to be longest, untold. TAPE, by contrast, names its source in the cassette row. | `ChopScreen.kt:324-338`, `:297-318`, `:707-729`, `:981`; cf. `TapeScreen.kt:870` |
| **J31** | **CHOP: `SEND TO GRID` names the new kit for the user, and shows the name only after the write.** `shelf.freshName(base)` derives it from the source filename (`:981`); `onSentToGrid(newEntry)` then opens KIT, which does render `kit.name` — so the name is visible afterwards, never offered beforehand. The smaller button one row below, by contrast, names its destination kit *and* bank in the label before the press and again in the toast. The bigger, more permanent action is the one that asks nothing. | `ChopScreen.kt:981`, `:992-994`, `:1038`; `Personality.kt:1156-1157` |
| **J32** | **CHOP: `HUM` starts recording before it states the one rule that makes it work.** In order: start the voice, *then* toast `"HUM ALONG. HEADPHONES ON, OR THE MIC HEARS THE TAPE TOO."` — advice that can no longer be acted on without wrecking the take already running. That string is the only place in the app stating the headphone requirement. The run is bounded by the whole source, not the selection, and the readout is `HUMMING…` with no elapsed time, so the user has committed to a performance of unknown length. | `ChopScreen.kt:566-572`, `:646`, `:1315`; `Personality.kt:789` |
| **J33** | **CHOP: the layout decision's only feedback is sixteen unlabelled rectangles.** CLASSIC/FOLD/MELODIC picks between three different `sendToGrid*` methods; `GridPreview` draws `Box`es with a background colour and **no text, no pad tag, no semantics node**. The only audition is per-slice in list order, which is not layout order. MELODIC additionally toasts its promise *before* the pitch detection that decides whether it can be kept. | `ChopScreen.kt:826-856`, `:985-989`, `:1487-1505`, `:837-854` |
| **J34** | **EXPORT: the format explanations are good, and shut by default.** All eight `ExportFormat.why` strings are real UI, drawn under each name and spoken to TalkBack. But `formatPickerOpen` is false on arrival and the closed row already shows a format name, which reads as "this is set." `why`'s own KDoc says it exists because "the user was choosing between eight names and no reasons"; shut-by-default returns them to exactly that. | `ExportScreen.kt:274`, `:858-925`; `ExportFormats.kt:22-29`, `:34-68` |
| **J35** | **EXPORT: `Blocked` writes nothing and says nothing.** No toast; the justification is that "the refreshed checklist below is the message" — but the checklist is the first card in a `verticalScroll` and the button is pinned at the bottom, so on a phone the changed row is very likely off-screen at the moment of the tap. | `ExportScreen.kt:494-500`, `:586-589`, `:628` |
| **J36** | **KIT: nothing on the grid distinguishes a treated pad from a raw one.** `PadCell` reads four fields off `KitPad`; no badge, rim or marker for a treatment recipe, SHAPE, MUTATE, OUTSIDE room, ghost layers, choke group or non-unity level. The pad sheet already computes exactly these summaries (`"CRUSH · 35%"`, `"ATK 12 MS · CUT 1.2K"`) and none reaches the grid. Sixteen treated pads can only be told apart by holding each in turn. | `KitScreen.kt:742-907`, `:405`; `PadSheetBoxes.kt:47-94` |
| **J37** | **KIT: velocity is hard-coded to 1.0, so `SOFT HITS` can be switched on but never heard.** `hit()` passes `1f` and forces `oneShot = true`. The one-shot forcing is documented; the velocity hard-code is not. The feature builds real velocity-layer WAVs that the grid cannot audition. | `KitScreen.kt:189-199`; `PadSheetScreen.kt:2129-2137` |
| **J38** | **TAPE: `FIND BREAK` and `CATCH A HIT` answer the same problem two different ways, side by side.** CATCH changes its *label* to `LISTENING FOR HITS…` while busy; FIND BREAK only dims and returns silently, so a second tap during a long dig produces nothing at all. | `TapeScreen.kt:1117-1123`, `:1134-1140` |
| **J39** | **TAPE: the tape counter and the pencil rewind are invisible controls.** The position LCD is tappable but drawn as an LCD panel, not a button — a sighted user finds it only by tapping a readout for no reason. The left reel is a hold-only control with no text anywhere in it, sitting beside a visually identical right reel that has no semantics and no gesture at all. | `TapeScreen.kt:1221-1238`, `:1401-1446`, `:1454-1459`, `:1492-1510` |
| **J40** | **TAPE: the `HIT 3/12` readout is secretly a third button that duplicates the one beside it.** Same `onClick` as `HIT ▶`; no `accessibilityLabel`, so TalkBack announces an actionable control named "HIT 3 OF 12". | `TapeScreen.kt:1038-1043` |

### S3 — words, and things that get worse with use

| # | Finding | Evidence |
|---|---|---|
| **J41** | **`GRID` now names five things on CHOP, and `16TH` names two.** The segment, the snap row, the `GRID ×N` readout, `SEND TO GRID`, and the landing toast "N SLICES ON THE GRID" (the pads). The two `16TH`s — a `GridSnap` and a `Ladder.Rung` — mean different things and sit in mutually exclusive rows, so the collision is never visible to learn from. Prior #24, now worse. | `ChopScreen.kt:1279`, `:1300`, `:1363-1367`, `:969`; `Ladder.kt:40`; `ChopReview.kt:196` |
| **J42** | **HELP says ZOOM; CHOP has no control called ZOOM.** The word appears on CHOP only inside a caption; the control a user must press is `COUNT`, which appears in no HELP line and in no caption naming it as the control. *(It does appear once in a toast — `CHOP_AUTO_NONE`, "NO HITS ON THIS TAPE TO COUNT" — but as a verb, not as the name of the segment, which if anything compounds the collision.)* Prior #13, intact. | `Personality.kt:448`, `:1121`; `ChopScreen.kt:1336`, `:1338` |
| **J43** | **`ONE PART FEWER` on a chop that has no parts.** On a hummed chop the stepper says PART while the readout beside it says `6 HUMMED`, and the handler returns on line one. Prior #11, intact. | `ChopScreen.kt:1286-1290`, `:1310-1317`, `:452` |
| **J44** | **`TAPED. NO TAKEBACKS.` is the first thing KEEP says, and it is false.** `commitSelection()` is a pure read that writes nothing and clears nothing; the same selection can be committed again immediately. Line 4 of the rotation promises a shelf entry the call does not create. *(Related: the source comment at `:1097-1098` asserting `commitSelection` clears the selection is also false — INSTANT KIT is built on that belief.)* | `Personality.kt:247`; `TapeDeck.kt:418`; `TapeScreen.kt:1075`, `:1097-1098` |
| **J45** | **`HITS…` — a readout string — is fired as a toast.** Its own KDoc calls it "the HITS stepper's readout." Its sibling refusal is a proper sentence with a next step. | `TapeScreen.kt:1023`; `Personality.kt:743-744` |
| **J46** | **`SEND IT SOMEWHERE` groups file-writing with navigation.** MIDI/CHART write in place; SONG ▸/ORBIT ▸ leave the screen. One ▸ glyph is the only thing separating them. | `GrooveScreen.kt:1999-2028` |
| **J47** | Carried forward, still open: BOUNCE has three names for one destination (#25); CATCH is both the capture verb and a gesture (#27); group-box legends are repeated verbatim as card titles in all four boxes (#30). *Unlike every other row here, these three were not re-verified against current source — they are carried on the prior review's word.* | prior #25, #27, #30 — **not independently re-checked** |

---

## Dead ends, and controls that vanish

Three findings share a shape worth naming separately: rather than refusing
in words, the app **removes the control**.

- **`CHOP FAILED` is a dead end whose own comment claims it is not one.**
  `EmptyStatePanel(Copy.CHOP_LAYOUT_FAILED, emptyList())` — no buttons.
  The comment argues routes are unnecessary because "GRID/MELODIC are the
  live ways out and both are in-place toggles right on this screen"; the
  `return` on the very next line is what removes them from the screen.
  `EmptyChop` fifteen lines earlier offers two routes for a *less* broken
  state. And it is sticky: re-entering re-runs the same deterministic
  layout over the same file and fails identically. (`ChopScreen.kt:697-704`,
  `:222-227`; `Chrome.kt:465-470`)
- **`ONTO MY KIT · BANK B` vanishes when both banks are full** rather than
  refusing — against the convention this same file states. TAPE handles
  the identical "no empty pad" case with a refusal in words
  (`Copy.CATCH_NO_ROOM`). (`ChopScreen.kt:1035-1040`; cf.
  `TapeScreen.kt:764-767`)
- **`AUTO`, `EAR`, `CUT` and `ON THE GRID` vanish** on a mode switch
  (`:1318`, `:1344`), so — with J21 — the settings and the controls that
  would reveal they were lost disappear in the same frame.

And one that manufactures a dead end:

- **`SEND TO GRID` is fully enabled on a zero-slice chop and will write an
  empty kit to the shelf and navigate to it**, toasting "0 SLICES ON THE
  GRID." The gate has nothing about `sliceCount`; `AutoPlace.arrange([],
  16)` yields sixteen nulls, `KitAssembler` `mkdirs()` and skips them all,
  no exception. (`ChopScreen.kt:970`, `:994`, `:1015`;
  `KitAssembler.kt:83-90`)

---

## Irreversibility

The design's stated bargain is: act immediately, pay for it with a 30-day
bin and per-bench undo. That is a good bargain **when the bin has the
take**. Four places where it does not hold:

1. **`MAKE PAD ▸` and `MAKE INSTRUMENT` both hard-code `overwrite = true`
   against a deterministic name — and `MAKE PAD ▸` also reseeds every
   press.** The two differ and the difference matters for the fix.
   `MAKE INSTRUMENT` calls `OneNote.export(...)`, whose signature takes no
   seed (`OneNote.kt:96-101`), so a second press reproduces the same
   output for the same snip: it overwrites, but with an identical file.
   `MAKE PAD ▸` builds `PadMaker.spec(..., Random.nextLong(0, 1_000_000))`
   fresh on every press (`:1740`), so **make a pad you like, press again
   to hear a variation, and the one you liked is gone.** The two toast different words — `Copy.INSTRUMENT_MADE`
   (“INSTRUMENT MADE. ON THE SHELF.”) and `Copy.PAD_MADE` (“PAD MADE. ON
   THE SHELF.”) — but neither says anything was replaced. Both write to `INSTRUMENTS_DIR`, not the kit's
   take history — no bin, no versions. The contrast is damning: EXPORT
   arms a named confirm before overwriting; PAD SHEET, writing to the same
   physical shelf, does not ask. (`PadSheetScreen.kt:1466`, `:1472`,
   `:1739-1746`; `OneNote.kt:96-101`)
2. **DE-SAMPLE is the one destructive bench with no undo control.**
   TREATMENT has `NONE`, MUTATE has `UNDO`, OUTSIDE has `UNDO`; the MAKE
   box has none, and there is no `unDesamplePad` call site. The only route
   back is a whole-kit rollback. (`PadSheetScreen.kt:2411-2417`,
   `:1775-1814`)
3. **`SOFT HITS` off deletes real layer WAVs and is the only action here
   with no feedback at all** — turning it *on* toasts; turning it off, the
   destructive direction, does not. (`PadSheetScreen.kt:1080-1088`)
4. **`UnTreat.NOT_BINNED` is a permanent one-way door announced after the
   fact.** On a pad whose prior take isn't in the bin, a treatment is baked
   onto already-treated audio and the user is told afterwards. The `NONE`
   chip then lights — `PadSheet.tappable` lights it whenever any recipe
   exists — and refuses. (`PadSheetScreen.kt:879`, `:901`, `:987`;
   `PadSheet.kt:376`)

---

## Decisions I am challenging

The code defends several of these in comments. The defences are
reasonable; I still think the calls are wrong.

**"Dimmed, not disabled; the toast explains."** (`ChopScreen.kt:1136-1137`)
The right rule, stated by the right file. But `SegmentButton` and
`DeckButton` have no `enabled` parameter, so up to nineteen controls in
that screen's own CUT box have nothing to dim *with* and no toast. The rule was written as a
convention when it needed to be a **component contract** — a button that
cannot express refusal should not be the base class for controls that
need to refuse.

**"The arrows are cues, not buttons."** (`Chrome.kt:270-278`) The
arithmetic is correct: two 48dp chips would spend a quarter of the row to
reveal ~34dp of tabs. But it evaluates one alternative and concludes the
status quo wins. The problem is not tappability, it is *twelve peers in a
one-dimensional strip*. Grouping, a second row, an overflow, a drawer:
none were on the table. The reasoning optimises inside a choice instead of
reopening it.

**"Matching the stated order exactly."** (`Chrome.kt:211-216`) It solved a
real problem — EXPORT was at position 10, outside the visible run. But the
strip now reads `KITS TAPE CHOP KIT EXPORT PLAY GROOVE ORBIT …` with no
visual break, so nothing tells a first-time reader the sequence *stops* at
EXPORT. The fix made step 4 visible and simultaneously made steps 5–12
look like part of the sequence. A separator would have cost nothing; the
framing was ordering, not grouping.

**"No routes: GRID/MELODIC are the live ways out."**
(`ChopScreen.kt:697-704`) Self-refuting — the `return` on the next line
deletes both. This is the clearest case in the document of a comment
describing an intent the code contradicts one line later.

**"This is real state in a slot shaped for state."** (`Chrome.kt:338-351`)
A genuine improvement on the rotating quip. But it put the app's most
useful persistent context into the one cell something else can take
(`val tail = busy ?: kitName`), and left 176dp of fixed width on a screen
name the row above already says three ways.

**"RECORD is the button every other control on this screen exists to
feed."** (`GrooveScreen.kt:2077-2088`) Agreed, and anchoring it was right.
The same pass put `EDIT STEPS` — the only route to the only editable
program — mid-scroll under the third heading.

**"A menu of twelve fits no phone."** (`Chrome.kt:82-88`) The premise is
right and the conclusion is half-taken: the app concluded that *two*
screens should live off the row and kept twelve on it.

---

## If only five things get fixed

1. **J1 — DRIFT.** Not a UX issue. It commits a WAV rewrite with a stale
   fraction and then displays a different number. Read the knob after the
   mode settles, or pass the mode explicitly. Fix ahead of everything else
   here.
2. **J2 + J3 — EXPORT's overwrite.** Give the armed state its own button
   label (the state exists and is never read), and put the card leg behind
   the same confirm as the phone leg. Today the card has no warning at any
   point.
3. **Give `SegmentButton` and `DeckButton` an `enabled` parameter** and
   thread it. This is the root cause of J27, J28, J38, most of J40, and
   the reason the app cannot keep its own stated convention. One change
   closes a class of defect rather than an instance.
4. **J5 + J6 — RE-CHOP.** Make it carry overrides like every other control
   on the bench, or confirm before discarding them; and gate it on
   `humming` so it cannot destroy a take in flight.
5. **J10 — wire the two missing handoffs.** Offer CHOP when a capture
   lands, EXPORT when a kit is ready. The cheapest change in this
   document, and the one that makes the app's own first-run promise true.

Then, cheaply: `▶ HIT`'s missing `enabled` (J8, one word, and its two
siblings already have it); the PAD SHEET hint retiring on all three of its
doors (J16); and a bar-length control attached to the `recordBars` seam
that already exists (J17).

---

## Appendix — prior review, re-verified

| Prior # | Status today | Note |
|---|---|---|
| 1 (HEAR stereo/mono) | **Fixed** | fold is inside `audition()`, `PadSheetScreen.kt:342` |
| 3 (CATCH grid too wide) | **Fixed** | `TapeScreen.kt:961-983`, bank switch + window grid |
| 5 (GRAIN ▸ zero-height) | **Fixed** | `fillMaxWidth()` at `PadSheetScreen.kt:2427-2433` |
| 14 (toast into a closed box) | **Fixed** | `Personality.kt:136` quoted on screen at `ChopScreen.kt:197` |
| 9, 10, 11, 12, 13, 15, 24 | **Still true** | re-verified at current line numbers; see J27, J6, J43, J20, J42, and J41 |
| 16, 17, 29 | **Still true** | and #17's "permanent selection" half is worse than written — a whole-tape catch *creates* a selection the user never drew (`TapeScreen.kt:783-787`) |
| 18, 19, 22, 30, 31 | **Still true** | #18 is wider than written: AMT is dead until a recipe exists, so *every* first treatment on an untreated pad runs at 70% (`PadSheetScreen.kt:3008`, `:1981`; `PadSheet.kt:108`) |
| 7 | **Still true** | and J-side inverse found: ROULETTE and A FILE ▸ are dimmed in their *initial* state, i.e. exactly when a new user needs them to look pressable (`PadSheetScreen.kt:3332`, `:3357`) |
