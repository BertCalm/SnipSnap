# The PEDALS card: a pad's pedalboard, drawn as a chain

**Status:** design, 2026-10-30. The owner's answers are recorded in section 2.
- **Built this round:** the shared logic the card needs that the slate did not already provide (`shell`, with tests).
- **Waits for a session with an Android SDK:** the screens (`app`). Section 6 lists exactly what they wire.
- **Heard:** nothing yet.

**Builds on:**
- The pedal slate (`2026-10-28-pedal-slate-design.md`). Its logic is built, its row seven stays, and its MORE list becomes this card.
- The pedal library design (`2026-10-21-pedal-library-and-board-design.md`).
- Pedals in front of the amp (`2026-10-27-pedals-in-front-of-the-amp-design.md`).

**Mockup:** the "Pad Pedalboard" canvas, https://claude.ai/artifact/D167xZA8KmMrttRbPf6GeB. Two boards: the proposed card, and today's TREATMENT card for comparison. Both switch between the eight colourways. The mockup is a picture of this spec; where the two ever differ, this spec wins.

**Who this is for:** the owner first, then the implementing session. Plain words for what a user taps; code names only where the next step has to go there.

## 1. Why

The slate gives a pad several pedals from the sheet, but shows them as a row of lit chips and a MORE list. The owner asked to see what a pedal looks like in the app, and the answer they kept was a pedalboard: each pedal as a stompbox, the pedals joined in the order the sound passes through them, and the pad's own amp drawn where it sits. Two things that are true of the sound are invisible on a chip row:
- **Where the amp is.** On a guitar pad, a pedal before the amp reaches the amp, and one after it follows it. Stage 3 made that real.
- **The order.** Within one side of the amp, the sound meets pedals in the rack's fixed order, not in the order they were tapped (section 5).

**Success:** a user opens PEDALS on a guitar pad and reads WAH → SUSTAIN → AMP → DELAY, from the picture and from the line under the legend. They turn the WAH knob up two steps and hear it. They move SUSTAIN after the amp and watch it cross. They add FORGE from the library. On a snare, the same card shows one chain with no amp in it.

## 2. The owner's answers (2026-10-27 to 2026-10-30)

1. **Does it read as a pedalboard?** "It reads as a set of pedals."
2. **The chain:** show it (pedal → pedal → amp → pedal), not separate tiles.
3. **The amount:** a knob face with − and + steppers at 5 % each, as in the mockup. The knob is a readout, not a drag target.
4. **Tile size:** two to a row.
5. **Where it lives:** its own card on the pad sheet, below TREATMENT, not in place of row seven.
6. **The slate and the card together:** "Both: row + board". The slate's row seven stays for quick on/off taps. The PEDALS card replaces MORE's plain list as the full view: knobs, the chain, flips, the library, PUT ON ROW. One set of pedals, two views, one logic (the slate's).

Earlier, on the UI discussion of 2026-10-27, the owner said treatments (pedals included) are applied to a sample as it lands on a pad, and pedals can be applied to multiple pads or keys when playing. That is the live, whole-kit pedalboard: a separate design (Aquarium's performance bus is the precedent), not this card. This card is one pad's printed pedals.

## 3. What the user does (the card, in words)

- **The PEDALS card** is a group box on the pad sheet, below TREATMENT, closed by default like every box.
  - Its strip reads the chain in words: "WAH → SUSTAIN → AMP → DELAY", or "NO PEDALS ON THIS PAD".
  - Its legend is "PEDALS · 3 OF 6", using the pad's limit (`PedalBoard.MAX_PICKS`).
- **Open, on a pad with its own amp** (a guitar or other synth pad whose pedals go in front of its amp):
  - The chain starts at "STRING ▸".
  - Then the pedals before the amp, two to a row, with arrows between them in the order the sound meets them.
  - Then the amp: an LCD panel that holds the AMP pedal (DRIVE), named for the pad ("THE AMP · JANGLE'S OWN"), with "clean" or "DRIVE pushes it".
  - Then the pedals after the amp.
  - The chain ends at "▸ PAD OUT".
  - An empty side is still drawn as a place ("Nothing before the amp."), so a pedal moved there has somewhere to go.
- **Open, on a pad with no amp of its own** (a drum, a sample, a stale or layered synth pad):
  - One chain from "SAMPLE ▸" to "▸ PAD OUT", with no amp panel and no side headings.
  - The order is still before, then the amp pedal, then after (section 5), so moving a pedal still changes where it sits in the chain.
- **A tile** is one pedal (the Stompbox in the SnipSnap design system). It has:
  - The name and an LED: lit, the pedal is on. Section 4 explains why there is no off state.
  - The knob face showing its amount, and "AMT 70%" under it.
  - **−** and **+**, 5 % each.
  - **OFF**, which takes the pedal off the pad.
  - **MOVE AFTER AMP** / **MOVE BEFORE AMP** for a pedal that may sit on either side. On a pad with no amp these read MOVE LATER / MOVE EARLIER.
  - The pedal AMT is moving (the slate's mark) carries the mark's look on its tile as on its chip.
- **+ ADD PEDAL** opens the library sheet. It shows every pedal and board the library offers, in library order. Each has:
  - ADD (or ON THE PAD, or PAD FULL).
  - PUT ON ROW, for a pedal the row does not draw.
  - A line saying where it goes ("Goes before the amp (can move after)").
- **REMOVE ALL** takes every pedal off, as NONE does on TREATMENT.
- **Row seven, unchanged from the slate:** tap a chip on or off, AMT moves the marked pedal, and MORE shows a count. **MORE now opens the PEDALS card** and scrolls to it, instead of a list inside TREATMENT. PUT ON ROW starts in the library sheet, then waits for a row-seven tap exactly as the slate specifies; the sheet closes so the row can be reached.

## 4. Decisions where the answers are silent (Proposed; the gate can overturn any)

- **P1, no BYPASSED.** The slate's D2 rule is that a lit pedal is a pedal you hear, and a pick at AMT 0 is not read as on. A pedal has no stored off state, so a footswitch cannot leave it on the board, silent, with its amount kept.
  - The tile's switch is **OFF**: `Gesture.Off`. The tile leaves the chain, and the slate's toast says where AMT went.
  - Putting it back brings it in at 70 (slate D3), not at its old amount.
  - **Open question for the owner:** a true bypass, a pedal kept on the board but silent with its knob where it was, needs a `bypassed` field on `PedalBoard.Pick`. That is a recipe format change (pedals v2), so it is not built here.
- **P2, the chain is rack order within a side.** This is the sound's order (section 5), so the picture never claims an order the render does not keep. Tap order decides only which of two same-section pedals plays, which the slate already resolves by replacing the silenced one (D2). Reordering by drag would need the engine to render a side in pick order. That is a sound change for its own design, not this card.
- **P3, the knob is a readout.** − and + are the doors, so are TalkBack's adjust gesture on the knob (`progressBarRangeInfo` and `setProgress`, as `StepperSlider` does) and row seven's AMT on the marked pedal. Dragging a 64 px circle with a thumb is not one of them (owner's answer 3).
- **P4, − at 5 % takes the pedal off**, as AMT 0 does on the row (slate D2). The toast says so. The tile does not dim at 5; it simply has a floor that removes.
- **P5, a step on a tile marks that pedal.** It sends `Gesture.Amount(key, value)` and records the key as last tapped, so the row's AMT and the tile agree on which pedal is being moved.
- **P6, the strip moves.** TREATMENT's strip stops naming pedals (slate D10 put them there) and names only an era, a character, a keyed treatment or a board. PEDALS' strip names the chain. Both change in the same app round, so no build shows pedals in neither place.
- **P7, the amp is asked, not guessed.** Whether the card draws an amp comes from `KitBuilderModel.pedalsAroundTheAmp(slot)` (section 5).
  - A pad whose patch no longer made its file, or a velocity-layered pad, gets the no-amp chain, because that is how its pedals render.
  - The card must not infer the amp from the pad's class or from a patch's presence.
- **P8, one card open at a time**, as every box. Opening PEDALS closes TREATMENT and the reverse. MORE opens PEDALS, which closes TREATMENT; the sheet scrolls so the card's top is in view.

## 5. The shared logic (built this round)

All of it is in `shell`, under test, so the app round is wiring.

**The order the sound meets the pedals.** `PedalBoard.render` runs three passes:
1. The PRE pass.
2. The AMP pass, with the pre-amp end fade.
3. The pad's landing chain (its amp) when there is one, then the POST pass.

Each pass folds its side's picks into one `FxChain` (`chainFor`), and a chain runs its sections in the rack's fixed order (`FxChain.SECTION_NAMES`: … CONTOUR, WAH, COMPRESSOR, SQUASH, FORGE, VALVE … ECHO …). So within a side, WAH always comes before SUSTAIN, however they were tapped. `PedalSlateTest` renders both tap orders and compares them sample for sample.

**`PedalSlate`** (`shell/src/main/kotlin/com/snipsnap/shell/PedalSlate.kt`):

| Member | What it is |
|---|---|
| `Link(word, key, amount, side, marked, sides)`, `canFlip` | One tile: a pedal the pad carries. `canFlip` when it may sit on either side. |
| `Pass(side, links)` | One pass: its side and its links in rack order (ties keep tap order). |
| `Chain(passes, aroundTheAmp)`, `links`, `isEmpty` | PRE, AMP and POST, always all three, and whether the pad's amp sits between AMP and POST. |
| `chain(recipe, lastTapped, aroundTheAmp)` | The card's chain: the pad's audible pedals (`boardOf`), the mark (`marked`). |
| `aroundTheAmp(recipe)` | From a pedals recipe alone: true when the patch rode beside the board and its landing chain plays (`pedalPad` keeps the patch only then). Null with no pedals, because then only the file can say. |

**`KitBuilderModel.pedalsAroundTheAmp(slot)`:**
- A pad with pedals answers from its recipe.
- A pad with none answers where `pedalPad` would put the next one: its file must still be its patch's render, with no layers (the Stage 3 guard).
- False for an empty slot.

**`Copy`:** `PEDALS_NONE`, and `pedalChain(chain)`, the strip and the card's accessible name ("WAH → SUSTAIN → AMP → DELAY"; no AMP on a pad without one).

Everything else the card does is the slate's, already built and tested:
- **Taps:** `Gesture`, `change` and `land`.
- **Toasts:** `toast`.
- **The library sheet's entries:** `more`, with `canPutOnRow` and `canFlip`.
- **PUT ON ROW:** `putOnRow`.
- **The row:** `card`.

## 6. The app round (a session with an Android SDK)

`PadSheetScreen.kt` unless named. The slate's section 6 is done first or in the same round. This list replaces its item 5, "The slate list", and adds to the rest.

1. **The box.**
   - `PadSheetBoxes.Box` gains `PEDALS("PEDALS")` after `TREATMENT`.
   - `summaries` gives it `Copy.pedalChain(...)`, and TREATMENT stops reading `readPedals` (P6).
   - `touched` keeps counting a pedalled pad as touched, now under PEDALS.
   - `PadSheetBoxesTest` and the grid marker follow.
   - The strip's 44-character budget still holds: the slate's long-board rule ("6 PEDALS") moves with it.
   - `summaries` cannot read a file, so the strip names the amp only when the recipe says so (`PedalSlate.aroundTheAmp(recipe) == true`). A clean pad's strip is `PEDALS_NONE` either way.
2. **The card.** A `GroupBox` after TREATMENT's (PadSheetScreen.kt ~3044), its legend "PEDALS · n OF 6". It holds:
   - The source label.
   - For each pass in `PedalSlate.chain(recipe, lastTapped, model.pedalsAroundTheAmp(slot))`: a heading (or none on a no-amp pad) and its links, two to a row.
   - A right-pointing arrow between the two tiles of a row, and a down arrow between rows and between passes, all drawn (2 px stroke, `ink2`; `lcdInk2` inside the amp panel), never glyphs.
   - The AMP pass inside an `lcdPanel` with "THE AMP · <PAD NAME>'S OWN" in `amber` when `aroundTheAmp`.
   - "▸ PAD OUT".
   - + ADD PEDAL and REMOVE ALL at the foot.
   - Call `pedalsAroundTheAmp` off the main thread, once per pad and per landing (it may render a patch), and hold the answer in state keyed on the pad's sample file and recipe.
3. **The tile.** The design system's Stompbox and Knob cards are the drawing.
   - **Layout:** a `raisedBevel` box with a `winFrame` line. The LED sits in a small `lcd` well, `amber` when lit. The knob is a 64 dp `lcd` disc: a `winFrame` rail, an `amber` arc, an `lcdInk` pointer.
   - **Buttons:** − and + (`Gesture.Amount`, P4, P5), OFF (`Gesture.Off`), and the move button (`Gesture.Flip(key, the side it shows)`; P1).
   - **The mark:** the marked tile gets the mark's look.
   - **Size:** every control is at least 48 dp.
   - **Accessibility:** the knob's semantics are a progress range with `setProgress`, labelled "<PEDAL> AMT".
   - **Dispatch:** each tap goes through `PedalSlate.land` inside `withFreshKit`, with the same busy, refresh and audition handling the slate specifies for row seven. Toasts come from `PedalSlate.toast`.
4. **The library sheet.**
   - It opens from + ADD PEDAL, as a sheet over the pad sheet (the shelf's pop-up shape, like `ChoiceSheet`).
   - It is built from `PedalSlate.more(row, recipe, lastTapped)`. Each entry has ADD (`Gesture.On`), ON THE PAD (disabled), or PAD FULL (disabled with `Copy.PEDALS_FULL`), and PUT ON ROW where `canPutOnRow`.
   - PUT ON ROW closes the sheet and starts the slate's wait for a row-seven tap. TREATMENT opens to show the row, and PEDALS closes (one box at a time). The slate's cancel rules hold.
5. **MORE** (slate item 3's "MORE opens the slate") opens the PEDALS box, scrolls to it, and moves no pedal.
6. **REMOVE ALL** is the slate's clear: NONE's path (`unTreat`) when the pad carries only pedals. On a pad whose board the bin cannot restore it refuses with `Copy.PEDALS_NOT_BINNED`, as NONE does.
7. **androidTest**, on `WidePadSheetScreenTest`'s pattern (PEDALS open, semantic clicks):
   - **Chain order:** on a guitar pad, add WAH and SUSTAIN in that order, and on a second pad in the other order. The chain reads WAH → SUSTAIN → AMP both times.
   - **Moves:** move SUSTAIN after the amp; DRIVE sits in the amp panel; + and − on a tile; − to 0 removes it, and the toast names where AMT went.
   - **No amp:** a snare shows no amp panel and the MOVE LATER label.
   - **Doors:** MORE opens PEDALS. PUT ON ROW from the sheet, then a row tap, swaps.
   - **Remove:** REMOVE ALL; on a pad whose original is gone, it refuses.

## 7. Disclosed, not fixed

- **No bypass** (P1). A pedal switched off loses its amount.
- **No reorder within a side** (P2). The chain shows the order there is; choosing another needs a render change.
- **The amp is a file check for a pad with no pedals yet** (P7). It costs one patch render. The card does it off the main thread and once per landing.
- **The live, whole-kit pedalboard** (section 2) is not this card. It plays pedals over everything you play, and offers to print them on MPC export. It has its own design to come, after Aquarium's performance bus.
- Everything the slate discloses in its section 7 still holds. This card adds no new door that writes a pad; it calls the slate's.

## 8. The gate (after the app round)

On a guitar pad, then a snare:
- Add WAH, SUSTAIN and DELAY; move SUSTAIN after the amp; add DRIVE.
- Turn WAH up two steps; turn DELAY down to nothing.
- Then the questions:
  - Does the card read as the order you hear?
  - Do you miss a bypass that keeps the knob where it was (P1)?
  - Do you want to choose the order within a side (P2)?
  - Is the library sheet the right door for adding, and PUT ON ROW the right door for the row?

## 9. Ledger

| Claim | Status | Evidence |
|---|---|---|
| A board renders PRE, then AMP with the end fade, then the landing chain, then POST | verified | `PedalBoard.render`, `PedalBoard.kt` at `bb19dc0f` |
| Within a side the sound meets pedals in rack order, not tap order | verified | `chainFor` folds a side into one `FxChain`; `PedalSlateTest` renders both tap orders and compares every sample |
| The chain draws that order | verified | `PedalSlateTest`; reverting `rackPlace` to tap order fails it (`[[SUSTAIN, WAH]…]` against `[[WAH, SUSTAIN]…]`) |
| `pedalPad` keeps the patch beside the board only when it rendered around the amp | verified | `KitBuilder.kt` `pedalPad` (the after-the-amp branch writes `PadRecipe(pedals = board)`); `PedalPadTest` |
| `pedalsAroundTheAmp` is true for a clean or pedalled guitar pad, false for a drum, a stale patch, a layered pad and an empty slot | verified | `PedalPadTest` |
| The slate's taps, toasts, library entries and row are built and tested | verified | `PedalSlate.kt`, `PedalSlateTest` (2026-10-28) |
| The owner's answers 1 to 6 | verified | this conversation, 2026-10-27 to 2026-10-30 |
| A user reads the order they hear from the card | heard? | not heard; section 8 |

## 10. Why it is built this way

- **One logic, two views,** because two sets of pedal rules would disagree the first time one of them changed. The row is the quick door, the card is the whole picture, and both send the slate's gestures through the slate's door.
- **The chain in rack order,** because the card is a promise about the sound. Drawing tap order would show WAH after SUSTAIN on a pad that plays WAH first. The September UAT's worst finding was a card that claimed one thing while the pad played another.
- **The amp asked of `KitBuilder`, never guessed,** because a guitar pad can lose its amp (a stale patch, ghost layers) without changing its class or its name, and the card must match the render.
- **No bypass yet,** because the slate's "a lit pedal is one you hear" is what keeps the card honest, and a real bypass is a recipe format change that deserves its own decision.
