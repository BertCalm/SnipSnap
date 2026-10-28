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
- **Tap an unlit pedal:** it goes on this pad on its own side of the amp, at the AMT on screen (at 70 if AMT reads 0, so a tap is always heard). Pedals already on the pad stay on. A seventh pedal is refused in words.
- **Tap a lit pedal:** it comes off. When the last one comes off, the pad is itself again, exactly as NONE leaves it.
- **AMT** moves one pedal: the one put on or tapped most recently. Its chip carries the mark, or MORE carries it when that pedal sits behind MORE. AMT down to 0 takes that pedal off, because a lit chip always sounds.
- **MORE** lights, with a count, when the pad carries pedals the row does not show. Tapping it opens the slate:
  - Every pedal that passed its gate, in library order. Each reads ON or OFF, and tapping it puts it on or takes it off.
  - Each pedal on this pad has an AMT button that gives it the AMT.
  - A movable pedal on this pad (SUSTAIN, WAH, TOUCH, ROCK) has a before/after-the-amp toggle.
  - Each pedal not on the row has PUT ON ROW.
  - A pedal on the pad that the slate does not hold (a RIG from the CLI) is listed at the end. It reads ON, and the only thing you can do to it is take it off.
- **PUT ON ROW**, then tap a row chip: the new pedal takes that chip's place, and the old one goes behind MORE. Nothing else moves. The row is remembered across kits and launches.
- **NONE** takes every pedal off at once, as it takes any treatment off today.
- **Any other row:** tapping an era, a character or a keyed chip replaces the pad's pedals, as a chip replaces a chip today. One recipe per pad.

## 4. Decisions this design makes where the answers are silent (Proposed; the gate can overturn any)

- **D1, several pedals on a pad from the sheet.** The library design's board (section 3 of that spec) is what MORE's count presumes. Tap adds or removes; the pad re-renders from the bin's original through the whole board every time, so pedals never stack on each other.
- **D2, a lit chip always sounds.** AMT 0 on the marked pedal takes it off, as AMT 0 on a chip leaves the pad clean today (`characterPad` at 0 is a no-op after the restore). A lit pedal at 0 would be the card claiming a sound the pad does not carry, the September UAT's lie. The cost: when AMT reaches 0, the mark moves to the next pedal and the stepper shows that pedal's AMT. The gate asks about this (section 8).
- **D3, a tap is always heard.** A pedal put on while AMT reads 0 lands at 70 (`PadSheet.DEFAULT_AMOUNT`), for the same reason the card's AMT starts at 70.
- **D4, the mark.**
  - The marked pedal is the one tapped last, on the row or in MORE (the app remembers it).
  - Failing that, it is the newest pedal on the pad. Picks are saved in tap order, so this survives a restart with no new state.
  - Tapping a lit chip takes it off, so on the row the only way to give AMT to an older pedal would be off and on again. MORE's AMT button is the door that does it without changing the sound.
- **D5, a pad whose original the bin no longer holds refuses a pedal change when it already carries pedals.**
  - This deviates from the library design's section 7, which said such a pad stacks as a chip does.
  - A chip replaces a chip, so stacking leaves one stale sound underneath. A board re-renders whole, so stacking would play every pedal already on the pad twice, and taking one off would leave it in the audio.
  - The refusal says so, and VERSIONS rolls back.
  - A pad with no pedals still stacks as a chip does (an era from the CLI under a new pedal), with the existing "over the last one" toast.
- **D6, pedals the slate does not hold still count.** MORE's count is every pedal on the pad that the row does not show, including an ungated pick or a board from the CLI or an old kit. The card never hides what the sound carries.
- **D7, the row is stored as saved keys, not chip words**, in the app's own preferences (`tapeos`, the store every app-wide setting uses), under `pedals.row`, as versioned JSON written by the shell (the `MidiLearnStore` pattern). COMP became SUSTAIN while its key stayed `compressed`; a stored word would have broken on that rename.
  - Reading is forgiving: anything damaged, unknown, ungated, a board or a duplicate is dropped. The empty places are filled from the default five, then from the slate in library order, and the other places keep their positions.
  - The row is written only on PUT ON ROW, never on read, so a pedal that is briefly missing from a build is not forgotten until the user edits the row.
- **D8, MORE is not a segment.** It is drawn on row seven but never reaches `PadSheet.treatmentFor`, which refuses it. The library design's section 9 said the same.
- **D9, the phone draws today's six until the app round.**
  - DELAY turns `gated` now, because the default row holds it and a row may hold only slate pedals.
  - `PadSheet.ROWS`, which the app draws, keeps row seven at the slate's first six in library order, the card's ceiling. Those are today's six, so the phone shows no change.
  - The new `PadSheet.rows(row)` is the slate's row seven (five plus MORE) for the app round to draw.
  - Section 7 discloses one side effect.
- **D10, the TREATMENT strip names a board.** Today a pedal board pad reads UNTOUCHED on the strip and the grid. The slate makes boards common, so the strip names each pedal and its AMT ("WAH 70% · DRIVE 100%").

## 5. The shared logic (built this round)

All of it is in `shell`, pure where it can be. The app has no unit-test source set (`app/src` holds only `androidTest`), so whatever the screens need to decide is decided here, under test.

**`PedalSlate`** (`shell/src/main/kotlin/com/snipsnap/shell/PedalSlate.kt`):

| Member | What it is |
|---|---|
| `ROW_SIZE = 5`, `DEFAULT_ROW` (`driven`, `compressed`, `wahed`, `forged`, `delayed`) | The owner's numbers. |
| `slate()` | Every pedal MORE lists: the library's gated, non-board entries, in library order. Today: DRIVE, SUSTAIN, WAH, TOUCH, ROCK, FORGE, DELAY. |
| `Row(keys)` | The app-wide row. It holds `min(ROW_SIZE, slate size)` distinct slate keys, in the user's order, and refuses anything else. `words` gives the chip words. |
| `DEFAULT`, `repair(keys)`, `read(text)`, `write(row)`, `PREFS_KEY = "pedals.row"` | The store (D7). `read` never throws. |
| `putOnRow(row, incoming, outgoing)` | PUT ON ROW: the incoming pedal takes the outgoing one's place, and every other place stays. It refuses a pedal already on the row, a chip not on the row, and anything off the slate. |
| `boardOf(recipe)` | The pedals a pad carries: its `pedals` board, or an old single-chip recipe whose name is a pedal key, read as a one-pick board (the library design's rule). Null for every other recipe. |
| `marked(board, lastTapped)` | D4. |
| `card(row, recipe, lastTapped)` | Row seven for one pad: five chips (word, key, lit, marked), MORE's count, lit and mark, the marked key and its AMT (null when the pad carries no pedals, so the screen's AMT keeps today's behaviour). |
| `more(row, recipe, lastTapped)` | MORE's list: every slate pedal plus any off-slate pick on the pad. Each entry has on, on the row, on the slate, marked, its AMT and side, the sides it may take, and whether it can go on the row. |
| `Gesture` (`Tap`, `Amount`, `Flip`), `change(board, gesture)` | The pure rule (sections 3 and 4): add, remove, set AMT (0 removes), flip the side. The result is a board to render, a clear, or a refusal (a seventh pedal). |
| `land(model, slot, gesture)` | The door. It reads the pad as it is now, computes the change, and restores through the bin when it can (`unEraPad`, as `applyTreatment` does). It then renders through `KitBuilderModel.pedalPad`, or leaves the pad clean on a clear. It refuses a printed board pad, ghosts that postdate the treatment, and D5's case. The result is `Landed`: applied (stacked or not), cleared, or refused. |
| `toast(gesture, landed, padName, wordOf)` | The landing line for each outcome, from `Copy`. |

**`PadSheet`:**

- `PEDAL_SEGMENTS` is now the slate's words, every pedal the card can put on a pad, whether row seven draws it or not.
- `ALL_SEGMENTS` is now the six fixed rows plus the slate, so `treatmentFor` accepts a pedal tapped in MORE.
- `ROWS` keeps row seven at the slate's first six until the app round (D9).
- `rows(row)` is the slate's card: rows one to six unchanged, then the row's five words and `MORE`.
- `MORE` is a constant outside every segment list.

**`Copy`:** MORE's label with its count ("MORE · 1"), PUT ON ROW and its prompt, the row-swap line, a pedal off, a side flipped, a full board, and D5's refusal.

**`PadSheetBoxes`:** D10.

**`Pedals`:** DELAY's `gated` turns true. `gated` now means "on the slate": a chip on the row, or a line in MORE.

## 6. The app round (R2, a session with an Android SDK)

`PadSheetScreen.kt` unless named. Each item calls the logic above; none of them decides anything new.

1. **The row.**
   - Read it once from `tapeos`: `PedalSlate.read(prefs.getString(PedalSlate.PREFS_KEY, null))`.
   - Hoist it as a parameter with a default, the way `clipboard` is (lines 196-199), so the four androidTest call sites still compile.
   - Write it on PUT ON ROW: `prefs.edit().putString(PREFS_KEY, PedalSlate.write(row)).apply()`.
2. **The card.**
   - Line 3088 draws `PadSheet.rows(row)` instead of `PadSheet.ROWS`.
   - Row seven's lit state comes from `PedalSlate.card` (lines 2743-2762, 4210), not from the single `activeSegment`. MORE's label is `Copy.pedalMore(count)`, but its `tapeClick` label stays "MORE", so tests can find it.
   - `isNoneState` (line 2752) must also check `PedalSlate.boardOf(recipe) == null`.
   - The idle header's "ONE PER PAD" (line 4196) is no longer true on row seven.
3. **The taps.**
   - `onSegmentTap` (lines 3098-3109) gains three branches ahead of `applyTreatment`: MORE opens the slate; a pending PUT ON ROW turns a row-seven tap into `putOnRow`; any other row-seven word becomes `PedalSlate.land(f, slot, Gesture.Tap(key, pendingAmt))`.
   - That call runs inside `withFreshKit` with the same busy, refresh and audition handling as `applyTreatment` (lines 1010-1081).
   - Toasts come from `PedalSlate.toast`.
4. **AMT.** When `card.marked` is not null, the stepper shows `card.amount` and commits `Gesture.Amount(marked, value)` (lines 2762, 2777, 3115-3120, 4263).
5. **The slate list.**
   - A list beside DustFromRow (after line 4324), in the bench's ▸/▾ convention, built from `PedalSlate.more`. Each entry has ON/OFF, AMT, before/after where `sides` holds both, and PUT ON ROW where `canPutOnRow`.
   - It closes when the box closes and when Aquarium opens.
   - Its state must not be keyed on `slot` (`ConventionTest`'s rule for state that is not one pad's).
6. **androidTest**, on `WidePadSheetScreenTest`'s pattern (TREATMENT open, semantic clicks): add, remove, MORE lit with a count, PUT ON ROW, AMT on the marked pedal, flip, NONE.
7. **The grid marker** (`KitScreen.kt:1499`) follows the strip (D10) with no app change, through `PadSheetBoxes.isTouched`.

## 7. Disclosed, not fixed

- **Until the app round, DELAY is on the slate but not on the phone.** The phone draws today's six and has no MORE yet. A pad given `delayed` from the CLI used to read as an unmapped character ("TREATED: DELAYED" on the provenance line). It now reads as the DELAY segment, which the phone does not draw, so no chip lights and the provenance line is silent. AMT on it still works. KitDiff and the strip now say "DELAY 70%" instead of "DELAYED 70%". The app round ends this.
- **A pedal tap still replaces an era, character or keyed treatment**, as a chip does. One recipe per pad.
- **List order within a side is saved and ignored**, as the library design says. Re-adding a pedal moves it to the end of the tap order, which matters only where two pedals on one side set the same section (WAH and TOUCH both set the wah): the later one wins.
- **Old kits keep working.** An old pedal chip reads as a one-pick board, and its first change rewrites the recipe as `pedals`. The sound is the same: the one-pick board equals the chip sample for sample (`PedalBoardTest`).

## 8. The gate (R3, after the app round)

The library design's R3, on the slate:

- On a snare: DRIVE at 70, then DELAY at 100. Both should be lit and both heard.
- DELAY off: the drive exactly as before. NONE: the clean snare.
- TOUCH from MORE: MORE should read "MORE · 1". Then PUT ON ROW in place of FORGE: TOUCH lit on the row, MORE dark. Open another kit: TOUCH should be on its row.
- Then the questions the gate exists for:
  - Can you build a board with no drag?
  - Do you know which pedal AMT is moving?
  - Is it right that AMT down to 0 takes the pedal off and hands the AMT to the next one (D2)?

## 9. Ledger

| Claim | Status | Evidence |
|---|---|---|
| Row seven is `Pedals.gated()` minus boards; `ALL_SEGMENTS` is `ROWS.flatten()`; `treatmentFor` refuses anything outside it | verified | `PadSheet.kt:103, 112-117, 261-264` at `214aa082` |
| No row is wider than six chips | verified | `PadSheetTest.kt:226` |
| The app taps row seven through `characterPad`, one pedal per pad, and never reads a board | verified | `PadSheetScreen.kt:1005, 1043-1050, 2743-2752`; no `pedalPad` or `readPedals` in the app |
| `pedalPad` refuses a WIDE pad, is a no-op on a bypassed board, renders around a synth pad's landing; the caller restores first | verified | `KitBuilder.kt:758-788` |
| `unEraPad` clears the recipe, or gives back a synth pad's patch recipe | verified | `KitBuilder.kt:905-923` |
| App-wide settings live in the `tapeos` preferences; `MidiLearnStore` is the shell-owned codec pattern | verified | `App.kt:197, 296-331`; `MidiLearnStore.kt:16-96`; `SurfaceScreen.kt:419-437` |
| The strip reads UNTOUCHED for a board pad | verified | `PadSheetBoxes.kt:120-123` reads `PadSheet.read` only |
| The owner's five answers | verified | this conversation, 2026-10-28; `row` on the Stage 1b page |
| A user can build a board on the slate and know which pedal AMT moves | heard? | not heard; section 8 |

## 10. Why it is built this way

- **The logic in the shared code, because that is where tests run.** The app has no unit tests and this session cannot compile it. Every rule the screens need (which five, what MORE counts, what a tap does, where AMT goes, what the toast says) is a function here with tests behind it, so the app round is wiring and its androidTests check the wiring, not the rules.
- **Keys, not words, in the store, because words get renamed.** SUSTAIN was COMP a week ago. A forgiving reader that keeps positions means a damaged or older store gives a sensible row, never a crash or a short row.
- **The door reads the pad itself**, instead of taking a board from the screen, because the screen's copy of the kit can be stale by the time the work runs (`applyTreatment` re-reads the pad inside `withFreshKit` for the same reason).
- **The refusal in D5, because a board re-renders whole.** Stacking a chip leaves one old sound underneath. Stacking a board would double every pedal on it. Refusing tells the truth; stacking would hide it.
- **DELAY turns `gated` now and the phone does not change yet**, because the default row holds DELAY and the row may only hold slate pedals, while drawing the new row is the app round's job. Keeping `ROWS` at today's six means nothing on the phone moves until MORE exists to reach what leaves the row.
- **The one risky decision** is still the library design's: on row seven a tap on a lit chip removes it, and AMT needs a mark. The slate adds a second, D2's AMT 0. The gate asks about both.
