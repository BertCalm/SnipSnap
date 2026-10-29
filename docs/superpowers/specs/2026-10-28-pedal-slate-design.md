# The pedal slate: five pedals on row seven, the rest behind MORE

**Status:** design, 2026-10-28. The owner's answers are recorded in section 2. The shared logic (`shell`, with tests) is built in this round. The screens (`app`) wait for a session with an Android SDK; section 6 lists exactly what they wire. Nothing has been heard.
**Builds on:** the pedal library design (`2026-10-21-pedal-library-and-board-design.md`: sections 2, 3, 7, 9 and R2 in section 12), whose row-seven window this design replaces, and DELAY's spec (`2026-10-22-delay-head-design.md`, "The quick check and the recipe"), where the owner chose a slate over a seventh chip or a second pedal row.
**Who this is for:** the owner first, then the implementing session. Plain words for what a user taps; code names only where the next step has to go there.

## 1. Why

Row seven draws six pedals (DRIVE, SUSTAIN, WAH, TOUCH, ROCK, FORGE), and six is the card's ceiling: `PadSheetTest` holds every row to six chips so they stay a usable size on a phone. DELAY passed its gate on 2026-10-28 and has no place. TREM and FLANGE (Stage 4) will follow. The library design planned for this (row seven as "a window onto the registry", with MORE behind it), and the owner has now said what the window is: **a slate**. Five pedals on the row, chosen by the user, the same everywhere. MORE holds every pedal.

The phone also has a gap the slate cannot work without. A pad carries **one** pedal from the sheet today: row seven taps go through `characterPad`, so a second tap replaces the first (`PadSheetScreen.kt:1043-1050`). Boards of several pedals reach a pad only from the CLI (`treat --pedals`) and the replay, and the phone does not even show them: such a pad reads as NONE (`PadSheetScreen.kt:2752`, which never consults `PadSheet.readPedals`). "MORE · 2" means two pedals on one pad, so the slate brings the library design's board to the sheet: tap to add, tap again to remove, AMT on one pedal.

**Success:** a user has DRIVE, SUSTAIN, WAH, FORGE and DELAY on the row in every kit. They tap DRIVE and DELAY and hear both on the pad. MORE stays dark. They open MORE, turn TOUCH on, and MORE reads "MORE · 1". They put TOUCH on the row in place of FORGE: TOUCH lights on the row, MORE goes dark, and every other kit shows TOUCH on its row too. Nothing that already plays changes sound.

## 2. The owner's answers (2026-10-28)

1. **Where it is built:** "Logic here, screens on SDK". The logic and its tests come first, in the shared code this session can build. The screens come after, where the app compiles.
2. **The default five:** DRIVE, SUSTAIN, WAH, FORGE, DELAY.
3. **Swapping:** MORE's list has **PUT ON ROW** on a pedal. You then tap the row chip it replaces. Nothing moves unless you ask.
4. **A pad's pedal that is not on the row:** MORE lights up with a count ("MORE · 1"), and inside MORE the pedal shows as on.
5. **Scope:** the row is the same everywhere, app-wide. It is not per kit or per pad.

Earlier, on the Stage 1b page (`pedal-listens.html`, question `s1b_slate`), the owner chose "Up to five on the row, swapped in from a larger slate" (`row`) over "up to five on one pad" (`pad`). So the row's five and a pad's limit are two different numbers. A pad still holds up to six pedals (`PedalBoard.MAX_PICKS`).

## 3. What the user does (the sheet, in words)

- **Row seven** shows five pedals, then MORE. The five are the same on every pad of every kit until the user changes them.
- **Tap an unlit pedal:** it goes on this pad at 70, on its own side of the amp, and takes the AMT. Pedals already on the pad stay on. The one exception: a pedal that would silence another on the same side takes its place (WAH, TOUCH and ROCK are three moves of one wah, so one wah plays before the amp and one after). A seventh pedal is refused in words.
- **Tap a lit pedal:** it comes off. When the last one comes off, the pad is itself again, exactly as NONE leaves it.
- **AMT** moves one pedal: the one put on or tapped most recently. Its chip carries the mark, or MORE carries it when that pedal sits behind MORE. The stepper's label names it ("AMT · TOUCH"). AMT down to 0 takes that pedal off, because a lit pedal is one you hear.
- **MORE** lights, with a count, when the pad carries pedals the row does not show. Tapping it opens the slate:
  - Every pedal and board that passed its gate, in library order. Each reads ON or OFF, and tapping it puts it on or takes it off.
  - Each one on this pad has an AMT button that gives it the AMT without changing its sound.
  - A movable one on this pad (SUSTAIN, WAH, TOUCH, ROCK) has a before/after-the-amp toggle.
  - Each pedal the row does not draw has PUT ON ROW (a board never goes on the row).
  - A pick on the pad that the library does not offer (a RIG from the CLI while RIG waits for its gate) is listed at the end. It can take the AMT, move and come off; once off it is gone from MORE.
- **PUT ON ROW**, then tap a row chip: the new pedal takes that chip's place, and the old one goes behind MORE. Nothing else moves. The row is remembered across kits and launches. Any other tap, closing MORE or the box, or changing pad cancels a PUT ON ROW in waiting.
- **NONE** takes every pedal off at once, as it takes any treatment off today.
- **Any other row:** tapping an era, a character or a keyed chip replaces the pad's pedals, as a chip replaces a chip today. One recipe per pad.

## 4. Decisions this design makes where the answers are silent (Proposed; the gate can overturn any)

- **D1, several pedals on a pad from the sheet.** The library design's board (section 3 of that spec) is what MORE's count presumes. On and off are taps; the pad re-renders from the bin's original through the whole board every time, so pedals never stack on each other (section 7 lists the existing paths that can break that guarantee).
- **D2, a lit pedal is a pedal you hear.**
  - AMT 0 on the marked pedal takes it off, as AMT 0 on a chip leaves the pad clean today (`characterPad` at 0 is a no-op after the restore).
  - Two pedals on one side that set the same section cannot both sound: the board folds a side in tap order and the later replaces the section whole (`PedalBoard.chainFor`, `FxChain.withSection`). So the newer one takes the older one's place, and the toast says so ("TOUCH ON A01 IN PLACE OF WAH."). A flip moves the pedal to the end of the order, so it wins on its new side. DRIVE then RIG plays RIG's amp; RIG then DRIVE keeps RIG's compressor and slapback, so both stay.
  - Picks from elsewhere (the CLI, an old kit) that are at 0 or silenced are not read as on: the card never lights them, and the next change drops them, which changes no sample (`PedalSlate.audible`).
  - The cost: when AMT reaches 0, the mark moves to the newest pedal left, and the toast says which ("DELAY OFF A01. AMT NOW MOVES DRIVE."). The gate asks about this (section 8).
- **D3, a new pedal goes on at 70** (`PadSheet.DEFAULT_AMOUNT`), not at the AMT on screen. This deviates from the library design's section 3. On a pad with pedals, the AMT on screen is the marked pedal's, so copying it would be arbitrary, and the habit "set AMT, then tap" would move the old pedal and leave the new one where AMT had been. At 70 the first tap is meant to be heard, the card's own reason for starting AMT there; the new pedal takes the mark, so the next AMT move is its.
- **D4, the mark.**
  - The marked pedal is the one tapped last, on the row or in MORE (the app remembers it).
  - Failing that, it is the newest pedal on the pad. Picks are saved in tap order, so this survives a restart with no new state.
  - Tapping a lit chip takes it off, so on the row the only way to give AMT to an older pedal would be off and on again. MORE's AMT button is the door that does it without changing the sound.
- **D5, a pad whose original the bin no longer holds refuses a pedal change when it already carries pedals.**
  - This deviates from the library design's section 7, which said such a pad stacks as a chip does. A chip replaces a chip, so stacking leaves one stale sound underneath. A board re-renders whole, so stacking would play every pedal already on the pad twice, and taking one off would leave it in the audio.
  - The common way in is time: the bin keeps a take 30 days after it went in (`BIN_KEEP_DAYS`, purged at every launch), and each pedal change re-bins the original, so a pedal pad left alone for 30 days refuses from then on. Its pedals are part of the sound now, and the refusal says exactly that ("THE BIN NO LONGER HOLDS THIS PAD'S ORIGINAL, SO ITS PEDALS ARE PART OF THE SOUND NOW. THEY STAY AS THEY ARE."). NONE refuses the same pad today, for the same reason.
  - **Open question for the owner:** should the bin keep the original of any pad whose recipe still depends on it, for as long as it does? That would keep NONE and the slate working on old pads, at the cost of one kept file per treated pad. It changes the bin's promise for every treatment, not just pedals, so it is not built here.
  - A pad with no pedals still stacks as a chip does (an era from the CLI under a new pedal), with the existing "over the last one" toast.
- **D6, pedals the library does not offer still count.** MORE's count is every pedal on the pad that the row does not show, including an ungated pick or a board from the CLI or an old kit. The card never hides what the sound carries.
- **D7, the row is stored as saved keys, not chip words**, in the app's own preferences (`tapeos`, the store every app-wide setting uses), under `pedals.row`, as versioned JSON written by the shell (the `MidiLearnStore` pattern). COMP became SUSTAIN while its key stayed `compressed`; a stored word would have broken on that rename.
  - Reading is forgiving: anything damaged, unknown, ungated, a board or a duplicate leaves a hole and the other places keep their positions. Good keys past the fifth place fill the holes first, then the default five, then the slate in library order.
  - The row is written only on PUT ON ROW, never on read, so a pedal that is briefly missing from a build is not forgotten until the user edits the row.
- **D8, MORE is not a segment.** It is drawn on row seven but never reaches `PadSheet.treatmentFor`, which refuses it. The library design's section 9 said the same. A library with no gated pedals would draw MORE alone.
- **D9, the phone draws today's six until the app round.**
  - DELAY turns `gated` now, because the default row holds it and a row may hold only slate pedals.
  - `PadSheet.ROWS`, which the app draws, keeps row seven at the slate's first six in library order, the card's ceiling. Those are today's six, so the phone shows no change.
  - The new `PadSheet.rows(row)` is the slate's row seven (five plus MORE) for the app round to draw.
  - Section 7 discloses the side effects.
- **D10, the strip and RE-TRIM name a board.** Today a pedal board pad reads UNTOUCHED on the TREATMENT strip and the grid, and RE-TRIM's toast says nothing stayed behind. The slate makes boards common, so the strip names each pedal and its AMT ("WAH 70% · DRIVE 100%", then the words alone, then "6 PEDALS" when they would not fit its 44 characters), and RE-TRIM names the one pedal or PEDALS.
- **D11, an old pedal chip counts as that pedal only while its chain is the pedal's today.** A chip carries the chain it was made with. SUSTAIN was the SQUASH section until 2026-10-24, so a SUSTAIN chip from before then would re-render as today's SUSTAIN the moment another pedal joined it, changing a sound the user never touched. Such a chip reads as an older treatment instead: no pedal lights for it, and a pedal tap replaces it as a chip replaces a chip.

## 5. The shared logic (built this round)

All of it is in `shell`, pure where it can be. The app has no unit-test source set (`app/src` holds only `androidTest`), so whatever the screens need to decide is decided here, under test.

**`PedalSlate`** (`shell/src/main/kotlin/com/snipsnap/shell/PedalSlate.kt`):

| Member | What it is |
|---|---|
| `ROW_SIZE = 5`, `DEFAULT_ROW` (`driven`, `compressed`, `wahed`, `forged`, `delayed`) | The owner's numbers. |
| `slate()`, `library()` | The pedals the row can draw (gated, not boards; today DRIVE, SUSTAIN, WAH, TOUCH, ROCK, FORGE, DELAY), and everything MORE offers (every gated entry, boards included once one is gated). |
| `Row(keys)` | The app-wide row. It holds `min(ROW_SIZE, slate size)` distinct slate keys, in the user's order, and refuses anything else. `words` gives the chip words. |
| `DEFAULT`, `repair(keys)`, `read(text)`, `write(row)`, `PREFS_KEY = "pedals.row"` | The store (D7). `read` never throws. |
| `putOnRow(row, incoming, outgoing)` | PUT ON ROW: the incoming pedal takes the outgoing one's place, and every other place stays. It refuses a pedal already on the row, a chip not on the row, and anything off the slate. |
| `audible(picks)` | D2: the picks a listener hears. |
| `boardOf(recipe)` | The pedals a pad carries: the audible picks of its `pedals` board, or an old single-chip recipe read as a one-pick board (D11). Null for every other recipe. |
| `marked(board, lastTapped)` | D4. |
| `card(row, recipe, lastTapped)` | Row seven for one pad: five chips (word, key, lit, marked), MORE's count, lit and mark, the marked key and its AMT (null when the pad carries no pedals, so the screen's AMT keeps today's behaviour). |
| `more(row, recipe, lastTapped)` | MORE's list: the library, then any pick on the pad the library does not offer. Each entry has on, on the row, on the slate, in the library, marked, its AMT and side, the sides it may take, whether it can flip and whether it can go on the row. |
| `Gesture` (`On`, `Off`, `Amount`, `Flip(key, to)`), `change(board, gesture)` | The pure rule (sections 3 and 4). On, Off and Flip say what the card showed (Flip names the side it goes to) rather than toggle, so a second tap that reaches the door late does nothing, and AMT or a flip for a pedal no longer on the pad is nothing too. The result is a board to render (with the pedals it replaced), a clear, a refusal (a seventh pedal), or no change. |
| `land(model, slot, gesture, sampleFile)` | The door. It reads the pad as it is now and does nothing if its sample is not the one the screen showed. It computes the change, makes every refusal before any file moves (a printed board pad by `PrintedPads.has`, ghosts that postdate the treatment, D5, then a full board, so "tap one off" is never refused on the next tap), then restores through the bin when it can (`unEraPad`, as `applyTreatment` does) and renders through `KitBuilderModel.pedalPad`, or leaves the pad restored on a clear. The result is `Landed`. |
| `toast(gesture, landed, padName)` | The landing line for each outcome, from `Copy`, or null when nothing happened. |

**`PadSheet`:**

- `PEDAL_SEGMENTS` is now the slate's words, every pedal the card can put on a pad, whether row seven draws it or not.
- `ALL_SEGMENTS` is now the six fixed rows plus the slate, so `treatmentFor` accepts a pedal tapped in MORE.
- `ROWS` keeps row seven at the slate's first six until the app round (D9).
- `rows(row)` is the slate's card: rows one to six unchanged, then the row's five words and `MORE`.
- `MORE` is a constant outside every segment list.

**`Copy`:** MORE's label with its count ("MORE · 1"), the AMT label ("AMT · TOUCH"), PUT ON ROW and its prompt, the row-swap line, a pedal off and where AMT went, a pedal in place of another, a side flipped, a full pad, D5's refusal and a printed pad's.

**`PadSheetBoxes`, `Retrim`:** D10.

**`Pedals`:** DELAY's `gated` turns true. `gated` now means "on the sheet": a line in MORE and, for a pedal (not a board), a chip the row can draw.

## 6. The app round (R2, a session with an Android SDK)

`PadSheetScreen.kt` unless named. Each item calls the logic above; none of them decides anything new.

1. **The row, in `App.kt`.**
   - Hold it there: `var pedalRow by remember { mutableStateOf(PedalSlate.read(prefs.getString(PedalSlate.PREFS_KEY, null))) }`, beside `scheme` and `shelfSort` (App.kt:312-330).
   - Pass `pedalRow` and `onPedalRowChange = { pedalRow = it; prefs.edit().putString(PedalSlate.PREFS_KEY, PedalSlate.write(it)).apply() }` to `PadSheetScreen` (call site App.kt:3510), the value-plus-callback pair `clipboard` and `onRecipeCopied` already are. Without the callback the next sheet would get App's stale row.
   - Defaults only for the four androidTest call sites, so a production call site cannot ship `DEFAULT` by omission.
2. **The card.**
   - Line 3088 draws `PadSheet.rows(pedalRow.words)` instead of `PadSheet.ROWS`.
   - Row seven's lit state comes only from `PedalSlate.card` (lines 2743-2762, 4210), never from the single `activeSegment`. MORE's label is `Copy.pedalMore(count)`, but its `tapeClick` label stays "MORE", so tests can find it. A marked chip, or MORE carrying the mark, needs a look of its own beside lit.
   - `isNoneState` (line 2752) must also check `PedalSlate.boardOf(recipe) == null`.
   - An old chip that `boardOf` declines (D11) lights no row-seven chip; the provenance line names it, the phone ruling.
   - The idle header's "ONE PER PAD" (line 4196) is no longer true on row seven.
3. **The taps.**
   - `onSegmentTap` (lines 3098-3109) gains three branches ahead of `applyTreatment`: MORE opens the slate; a PUT ON ROW in waiting turns a row-seven tap into `putOnRow` (then `Copy.rowSwapped`); any other row-seven word becomes `PedalSlate.land(f, slot, if (lit) Off(key) else On(key), staleSampleFile)`.
   - That call runs inside `withFreshKit` with the same busy, refresh and audition handling as `applyTreatment` (lines 1010-1081). Toasts come from `PedalSlate.toast`; null means none.
   - Defence in depth: `applyTreatment` returns early on `PadSheet.MORE`, because `treatmentFor` runs outside its try (line 1005).
   - `applyTreatment` itself, for an era, character or keyed chip on a pad whose `boardOf` is not null and whose state is NOT_BINNED, refuses with `Copy.PEDALS_NOT_BINNED` instead of stacking, so the rest of the card keeps D5's rule.
4. **AMT.** When `card.marked` is not null, the stepper shows `card.amount`, its label is `Copy.pedalAmt(word)`, and it commits `Gesture.Amount(marked, value)` (lines 2762, 2777, 3115-3120, 4263). `pendingAmt` must be keyed on the marked key as well as the amount (`remember(slot, card.marked, card.amount)`), or reset after every landing or refusal: two pedals both at 70 is the usual case, and when AMT 0 hands the mark on, an amount-only key would keep the stepper reading 0 over a pedal playing at 70.
5. **The slate list.**
   - A list beside DustFromRow (after line 4324), in the bench's ▸/▾ convention, built from `PedalSlate.more`. Each entry has ON/OFF, AMT, before/after where `canFlip` (it sends `Flip(key, the side it shows)`), and PUT ON ROW where `canPutOnRow`.
   - A PUT ON ROW in waiting shows `Copy.putOnRowPrompt(word)` and outlines the row's five until it ends. It ends on the next row-seven tap (the swap), on a second PUT ON ROW tap, on any tap outside the row's five, on MORE or the box closing, on Aquarium opening, and on a change of pad.
   - It closes when the box closes and when Aquarium opens.
   - Its state must not be keyed on `slot` (`ConventionTest`'s rule for state that is not one pad's), except that a pad change cancels a PUT ON ROW in waiting.
6. **androidTest**, on `WidePadSheetScreenTest`'s pattern (TREATMENT open, semantic clicks): add, remove, TOUCH replacing WAH, MORE lit with a count, PUT ON ROW (reopen the sheet: the swap survived) and its cancel, AMT on the marked pedal (two pedals at 70, AMT to 0 on one: the stepper reads 70 and names the other), flip, NONE, a tap on MORE.
7. **The grid marker** (`KitScreen.kt:1499`) follows the strip (D10) with no app change, through `PadSheetBoxes.isTouched`.

## 7. Disclosed, not fixed

- **Until the app round, DELAY is on the slate but not on the phone.** The phone draws today's six and has no MORE yet. A pad given `delayed` from the CLI used to read as an unmapped character ("TREATED: DELAYED" on the provenance line) with AMT disabled. It now reads as the DELAY segment, which the phone does not draw: no chip lights, the provenance line is silent, and AMT is enabled and re-treats it. KitDiff, the strip and RE-TRIM now say "DELAY" instead of "DELAYED". The app round ends this.
- **Until the app round, a board pad's strip and card disagree.** A board from the CLI or PASTE now reads "WAH 70% · DRIVE 100%" on the TREATMENT strip, while the card still lights NONE and cannot tap it (item 2 of section 6 fixes `isNoneState`). Before, both said untouched.
- **A pedal tap still replaces an era, character or keyed treatment**, as a chip does. One recipe per pad.
- **Existing paths can bin a board render while keeping a `pedals` recipe**, and D1's "never stack" then fails on the next change, as it already does for chips: PASTE of a board onto a board pad and the CLI's `treat --pedals` do not restore first, and an audio replacement with no recipe (SPLIT, SURFACE and SNAP prints onto a taken pad) keeps the old one. Fixing those doors is their own change.
- **Stacking over a recipe the bin cannot restore, then clearing**, gives back the stacked-over audio with no recipe, so NONE lights over a treatment (a bank-B twin's SLAP under a DRIVE tapped on and off). Chips do the same today; the board inherits it.
- **Synth pads.** A pad that keeps its patch is re-rendered in front of its amp only while its file still matches the patch's render, checked at every change, so an engine change between two taps moves its pedals behind the amp. A velocity-layered synth pad has no patch path, so its pedals go after the amp and the patch leaves the recipe. Ghost layers added to a synth pad with pedals render from the bare patch, and the pad then reads GHOSTS_POSTDATE. All three predate the slate (Stage 3).
- **A failure after the restore.** If `pedalPad` fails after `unEraPad` has taken the original out of the bin (a full disk), the kit is not saved, and the pad keeps its old `pedals` recipe over the restored original. The same holds for a chip.
- **An unreadable `pedals` key** (a pedal from a newer build) reads as no board, so a tap renders over it without the stacked toast. Kits do not travel between builds today.
- **List order within a side is saved, and decides only which of two same-section pedals plays**, which D2 now resolves by replacing the silenced one.
- **Old kits keep working.** An old pedal chip whose chain is today's reads as a one-pick board, and its first change rewrites the recipe as `pedals`. The sound is the same: the one-pick board equals the chip sample for sample (`PedalBoardTest`, `PedalPadTest`). One whose chain is not today's is D11.

## 8. The gate (R3, after the app round)

The library design's R3, on the slate:

- On a snare: tap DRIVE (it goes on at 70), tap DELAY, then AMT to 100 on DELAY. Both should be lit and both heard.
- DELAY off: the drive exactly as before, and the toast says AMT now moves DRIVE. NONE: the clean snare.
- WAH on, then TOUCH from MORE: TOUCH takes WAH's place, MORE reads "MORE · 1". Then PUT ON ROW on TOUCH in place of FORGE: TOUCH lit on the row, MORE dark. Open another kit: TOUCH should be on its row.
- Then the questions the gate exists for:
  - Can you build a board with no drag?
  - Do you know which pedal AMT is moving?
  - Is it right that AMT down to 0 takes the pedal off and hands the AMT to the next one (D2)?
  - Is it right that a second wah on the same side replaces the first (D2)?

## 9. Ledger

| Claim | Status | Evidence |
|---|---|---|
| Row seven is `Pedals.gated()` minus boards; `ALL_SEGMENTS` is `ROWS.flatten()`; `treatmentFor` refuses anything outside it | verified | `PadSheet.kt:103, 112-117, 261-264` at `214aa082` |
| No row is wider than six chips | verified | `PadSheetTest.kt:226` |
| The app taps row seven through `characterPad`, one pedal per pad, and never reads a board | verified | `PadSheetScreen.kt:1005, 1043-1050, 2743-2752`; no `pedalPad` or `readPedals` in the app |
| `pedalPad` refuses a WIDE pad, is a no-op on a bypassed board, renders around a synth pad's landing; the caller restores first | verified | `KitBuilder.kt:758-788` |
| `unEraPad` clears the recipe, or gives back the patch recipe of an unlayered synth pad whose pedals kept it | verified | `KitBuilder.kt:905-923` |
| WAH, TOUCH and ROCK set one section; a side folds in tap order and the later pick replaces a section whole | verified | `Treatments.kt:98-103`, `PedalBoard.kt:67-77`, `FxChain.kt:227`; `PedalSlateTest` renders both and compares |
| The bin purges takes older than 30 days at every launch | verified | `App.kt:1077-1087`, `KitBuilder.purgeBin` |
| App-wide settings live in the `tapeos` preferences; `MidiLearnStore` is the shell-owned codec pattern | verified | `App.kt:197, 296-331`; `MidiLearnStore.kt:16-96`; `SurfaceScreen.kt:419-437` |
| The strip reads UNTOUCHED for a board pad | verified | `PadSheetBoxes.kt:120-123` reads `PadSheet.read` only |
| The owner's five answers | verified | this conversation, 2026-10-28; `row` on the Stage 1b page |
| A user can build a board on the slate and know which pedal AMT moves | heard? | not heard; section 8 |

## 10. Why it is built this way

- **The logic in the shared code, because that is where tests run.** The app has no unit tests and this session cannot compile it. Every rule the screens need (which five, what MORE counts, what a tap does, where AMT goes, what the toast says) is a function here with tests behind it, so the app round is wiring and its androidTests check the wiring, not the rules.
- **Keys, not words, in the store, because words get renamed.** SUSTAIN was COMP a week ago. A forgiving reader that keeps positions means a damaged or older store gives a sensible row, never a crash or a short row.
- **A lit pedal is one you hear, because the card is a promise about the sound.** The September UAT's worst finding was a card that claimed one thing while the pad played another. A silent pick, at 0 or under a later pick of the same section, is that lie in a new place, so the slate drops it and says so.
- **On and Off, not a toggle, and a door that reads the pad itself**, because the screen's copy of the kit can be stale by the time the work runs (`applyTreatment` re-reads the pad inside `withFreshKit` for the same reason), and a doubled tap must not undo the first.
- **The refusal in D5, because a board re-renders whole.** Stacking a chip leaves one old sound underneath. Stacking a board would double every pedal on it. Refusing tells the truth; stacking would hide it. Whether the bin should keep those originals longer is the owner's call.
- **DELAY turns `gated` now and the phone does not change yet**, because the default row holds DELAY and the row may only hold slate pedals, while drawing the new row is the app round's job. Keeping `ROWS` at today's six means nothing on the phone moves until MORE exists to reach what leaves the row.
- **The one risky decision** is still the library design's: on row seven a tap on a lit chip removes it, and AMT needs a mark. The slate adds two more, D2's AMT 0 and one wah per side. The gate asks about all three.

## As built: the screens (2026-10-29, `app`, compiled by CI)

The owner chose to build the screens in this cloud session, with CI's `android-build` as the compiler and `emulator-tests` as the device. Against section 6:

- **The row** lives in `App.kt` beside `recipeClip`. It is read once from `tapeos` with `PedalSlate.read`, passed to `PadSheetScreen` as `pedalRow`, and written back by `onPedalRowChange`. Neither parameter has a default, as `workshopOpen` has none, so the four existing androidTest call sites pass `PedalSlate.DEFAULT` explicitly.
- **The card** draws `PadSheet.rows(pedalRow.words)`.
  - Row seven lights from `PedalSlate.card`.
  - Rows one to six still light from the single `activeSegment`, which now ignores a pedal word.
  - A marked chip, or MORE when the marked pedal sits behind it, wears the accent rim that a lit `ActionButton` wears. Lit is also said (`semantics { selected }`), so TalkBack and the device tests can tell.
  - MORE's visible text is `Copy.pedalMore(count)`; its spoken name stays MORE.
  - `isNoneState` counts a board. The idle header reads "ONE PER PAD · PEDALS STACK".
  - An old chip that `boardOf` declines is named on the provenance line ("TREATED: COMPRESSED") and lights nothing.
- **The taps.** `onSegmentTap` checks four cases before `applyTreatment`:
  - a PUT ON ROW in waiting (a row chip swaps; any other tap only cancels);
  - MORE (opens or closes the list);
  - NONE (as before);
  - a row pedal (`On` or `Off` from what the card shows, through `applyPedal`).

  `applyPedal` is `applyTreatment`'s shape around one `PedalSlate.land` call: busy and the dock's job raised before the launch, the fresh-kit block, the model swap, the refresh audition and `PedalSlate.toast`. The AMT mark moves only with a change that landed, so a refused tap leaves AMT where it was. `applyTreatment` returns early on MORE. On a pad whose pedals the bin cannot restore, `applyTreatment`, SMEAR, DUST and DUST FROM refuse with `Copy.PEDALS_NOT_BINNED` instead of stacking, so D5 holds across the whole card.
- **AMT.** With a marked pedal the stepper is named `Copy.pedalAmt(word)` (a wider label), reads the pedal's AMT, and commits `Gesture.Amount`. `pendingAmt` is keyed on the marked key, the amount and a count of refused pedal changes, so the stepper reloads when the mark moves and lets go of a value that never landed.
- **MORE's list** (`PedalMoreList`) sits under the card, as DUST FROM does. Each line has:
  - the pedal (lit when on, a tap puts it on or takes it off);
  - AMT (gives it the mark);
  - PRE or POST where it can move (sends `Flip` to the other side);
  - PUT ON ROW where the row does not draw it (lit while it waits).

  The list stays open from pad to pad (keyed on the kit), so a board can be built across a kit, and closes on a box toggle or Aquarium opening. A PUT ON ROW in waiting ends on a change of pad, a box toggle, Aquarium, any other tap on the card, AMT, DUST FROM, COPY or PASTE. Each control carries a test tag (`pedal-more-<key>`, `-amt`, `-side`, `-row`), and its spoken name differs from the row chip's ("TOUCH, ON" beside the chip "TOUCH").
- **androidTest:** `PedalSlateScreenTest`, eight tests:
  - on and off, with the last pedal off giving the original back byte for byte;
  - AMT 0 handing the mark on, and the stepper then reading 70 for the next pedal;
  - TOUCH from MORE replacing WAH, with MORE · 1;
  - PUT ON ROW swapping FORGE and writing no file;
  - a PUT ON ROW in waiting cancelled by the next tap, and MORE treating nothing;
  - NONE over a board;
  - the PRE/POST toggle moving SUSTAIN after the amp;
  - a pad whose original the bin let go: a pedal tap and a CRUSH tap both refuse, and nothing is written.
- **Not built:** a Back gesture that closes MORE's list (DUST FROM's has none either), and a per-line AMT readout in the list (the stepper shows the marked pedal's).
