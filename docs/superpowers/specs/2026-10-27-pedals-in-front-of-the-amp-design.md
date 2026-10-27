# Stage 3: pedals in front of the amp, for guitar pads

**Status:** built, 2026-10-27 (see "As built" at the end). The owner's gate: "yes, build it" (in conversation, 2026-10-27; the Amp Front Gate page saved no per-question answers). This is Stage 3 of `2026-10-04-pedal-board-design.md`. Repository truth: commit `f6a47dba`.
**Builds on:** the pedal library and its board (`Pedals.kt`, `PedalBoard.kt`, `2026-10-21-pedal-library-and-board-design.md`), the WAH and COMPRESSOR sections, and two owner verdicts: WAH in front of the amp "a bit better" on the guitars (2026-10-23), and SUSTAIN's guitars "fine until the pedal goes in front of the amp" (2026-10-24).

## Why

A pedal on the pad sheet acts on the pad's baked audio. On a guitar pad (a MAGNET pad, or any synth pad whose landing chain has VALVE) that audio is already through its amp, so every pedal lands after the amp: a wah filters the finished distortion, a compressor squeezes a sound the amp has already squeezed, and DRIVE stacks a second amp. A real board puts them in front.

**The characteristic gesture is: string → pedals → the guitar's own amp → whatever comes after it.**

## What the repository does today (verified at `f6a47dba`)

- `KitBuilderModel.pedalPad` renders every file the pad references through `PedalBoard.process` and writes the recipe as `PadRecipe(pedals = board)` alone (`KitBuilder.kt:770-779`). On a synth pad this **drops the patch and its landing chain** from the recipe, so nothing is left to re-render from.
- `unEraPad` restores every file from the bin and sets the recipe to null (`KitBuilder.kt:872-882`), which also drops a synth pad's patch. Both are true today for every chip on every row, not only pedals.
- A synth pad's recipe is `patch` plus an optional landing `fx`; `PadRecipe.render()` is `fx`, then `pedals`, then `board`, over `patch.render()` (`PadRecipe.kt:82-93`). Landing chains come from `Magnet.landingChain` (VALVE), `Siren.landingChain`, `Bore.landingChain` (TAPE) and `Presets.landingFor` on the synth screen.
- A velocity-layered synth pad with a landing chain gets its soft layers from `Velocity.layerAt`, which falls back to softening the main take when the recipe has `fx` (`KitBuilder.kt:391-447`): those layers are not re-renders of the patch.

## The design

**The render (built):** `PedalBoard.processAround(dry, landing)`: the PRE picks, then the pre-amp end fade and the AMP pick (a drive pedal into the amp), then the pad's own landing chain, then the POST picks, under the board's one tail budget measured from the dry sound. With no landing chain it is `process(dry)`; a bypassed board is the landing chain alone. A PRE-only board equals the pedal's chain then the landing chain, two renders in a row (`PedalBoardTest`); a POST-only board equals today's board on the baked pad.

**The door (proposed, after the gate):**
1. `pedalPad` on a pad whose recipe parses to a `patch` (with or without `fx`), with no velocity layers, no `treatment` and no WIDE board, renders `board.processAround(patch.render(), fx)` and writes the recipe as `PadRecipe(patch, fx, pedals = board, terminalFadeMs)`: the patch is kept.
2. `PadRecipe.render()` with both `patch` and `pedals` renders the same way (`processAround`), so a replay, a copy of the treatment and a twin all agree with the door. A recipe without a patch, and every recipe saved before this change, renders exactly as before (a recipe could not hold `patch` and `pedals` together until now).
3. Undo keeps the patch: when the pad's recipe has a `patch`, `unEraPad` restores the files and writes back `PadRecipe(patch, fx, terminalFadeMs)` instead of null. This also stops the pad sheet's restore-then-apply step from dropping the patch on the second pedal tap.
4. Everything else keeps today's way and says nothing new: a captured pad, a pad whose recipe lost its patch to an earlier chip on another row, and a velocity-layered pad (its soft layers are copies of the main take; re-deriving them is a later round).

**The side now means something on synth pads.** A movable pick's PRE or POST used to land after the amp either way on a baked guitar; with this door PRE is in front of the amp and POST is behind it, which is the choice the library's middle path promised.

## What the clips measured (Kotlin, `generateAmpFrontAudition`, 22 clips)

| | JANGLE after | JANGLE in front | CHUG after | CHUG in front |
|---|---:|---:|---:|---:|
| WAH sweep, octaves over 0.6 s | 1.86 | 2.18 | 1.84 | 1.21 |
| SUSTAIN, level 1.5 s in (baked -6.3 / -7.6 dB) | +0.9 | +0.4 | -0.5 | -0.2 |
| SUSTAIN in front, RECOVER 0 to 100 | | -3.8 to +0.4 | | -4.7 to -0.2 |
| DRIVE, pick over the note, dB | 5.1 | 8.5 | 5.0 | 8.1 |

**What this says, plainly:** in front of the amp does not give SUSTAIN more range on the guitars. The amp squeezes the note itself, so the compressor's work before it is flattened again, and RECOVER's ends stay about 4 to 5 dB apart, as after the amp. What changes is character: the wah reaches the amp (wider on JANGLE, narrower on CHUG, as the owner heard before), and DRIVE becomes a drive pedal pushing the guitar's amp, which keeps about 3 dB more of the pick than a second amp on the finished pad.

## The owner's gate

The Amp Front Gate page (Chrome; the Claude app's Android viewer plays no sound): WAH, SUSTAIN and DRIVE after against in front; SUSTAIN's RECOVER ends in front; a four-pedal board (SUSTAIN, WAH, DRIVE, DELAY) all after against around the amp; and the decision: build the door, keep after, or only for some pedals. The door is built only on a yes.

## Out of scope

Re-deriving soft velocity layers for a guitar pad; restoring a patch that an earlier chip on another row already dropped; a pedal in front of a captured guitar recording (there is no dry signal to put it in front of); a per-pick order inside a side; FUZZ.

## Ledger

| Claim | Status | Evidence |
|---|---|---|
| `pedalPad` writes `PadRecipe(pedals = board)` alone, dropping a synth pad's patch | verified | `KitBuilder.kt:776-777` |
| `unEraPad` sets the recipe to null | verified | `KitBuilder.kt:881` |
| A layered pad with a landing chain has softened copies, not re-renders | verified | `KitBuilder.kt:408-445`, `Velocity.layerAt` |
| `processAround` puts PRE and AMP picks in front of the landing chain and POST after it | measured | `PedalBoardTest`, two tests |
| In front does not widen SUSTAIN's range on the guitars | measured | the table above |
| Guitar pedals belong in front of the amp | heard | the owner, 2026-10-27: "yes, build it" (the per-pedal comparisons were not recorded) |

## Why it is built this way

- **Render from the patch, not the file**, because a pedal in front of the amp needs the sound before the amp, and only the synth recipe still has it. A captured guitar has no "before".
- **Keep the patch in the recipe**, because today one pedal tap throws it away, and then the second tap, undo and replay can only work on the finished file. Keeping it is what makes the pedals changeable.
- **DRIVE goes between the pedals and the amp**, as a drive pedal does: it pushes the guitar's own amp instead of stacking a second one after it.
- **The one risky decision** is what "undo" means on a synth pad: the bin brings back the exact old audio, and the recipe is rebuilt from the patch rather than cleared. If the two ever disagreed the pad would read one way and sound another, so the door's test must check that a pad restored this way re-renders to the file the bin gave back.

## As built (2026-10-27, `synth` and `shell`)

- **The door:** `KitBuilderModel.pedalPad` takes the new path when the pad's recipe parses to a `patch` with no `treatment`, no compound `board` and no velocity layers, **and** the recipe still renders the file on disk (every sample within `KitBuilderModel.RENDER_MATCH`, 1e-3, of a fresh render). It then writes `recipe.copy(pedals = board).render()` over the file (binned as before) and keeps the patch, the landing chain and any terminal fade in the recipe. Every other pad takes the old path unchanged.
- **Why the render check (a deviation from the design):** `replaceAudio(slot, null)` keeps the pad's old recipe while it replaces the audio (the synth screen's neighbours, SPLIT and SNAP, do this), so a patch can outlive the sound it made. Without the check a pedal tap would throw that pad's real audio away and put the old synth sound back. With it, such a pad keeps the after-the-amp way.
- **The recipe:** `PadRecipe.render()` with both a patch and pedals is `pedals.processAround(patch.render(), fx)`, then the compound board and the terminal fade as before. No recipe saved before this change held both, so no existing pad renders differently.
- **Undo:** `unEraPad` (the sheet's) and `untreatPad` (the CLI's `--undo`) restore audio as before and, when the recipe they remove has a patch and pedals, write back the same recipe without the pedals instead of null (`recipeAfterUndo`). Every other undo still clears the recipe.
- **Tests:** `PedalPadTest` gained five (the fifth: the CLI's single-file undo keeps the patch too): the wah in front of the amp on a JANGLE pad (the file, the kept patch and landing chain, the recipe rendering the file, the sheet and `KitDiff` reading the board); undo bringing back the bytes and the patch recipe, then a second pedal in front again; a pad whose file is no longer its patch's render keeping the after-the-amp way; and a velocity-layered guitar pad keeping it too. Proven by mutation: dropping the render check fails the stale-recipe test, and undo clearing the recipe fails the undo test.
- **Not changed:** chips on the other rows still drop a synth pad's patch (an era, a character, a keyed treatment write their own recipe), as they did; a pad treated that way has no patch left to put pedals in front of.

