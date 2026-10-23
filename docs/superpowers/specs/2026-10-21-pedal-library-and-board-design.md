# The pedal library and the board: pick five or six pedals, in two halves around the amp

**Status:** design, 2026-10-21, proposed, nothing built, nothing heard. Written after the two-tool benchmark (`2026-10-21-pedal-board-benchmark.md`) and the owner's direction on 2026-10-21: a library of effects a user picks from, five or six on a pad at once, built to grow one pedal at a time, and the middle path on order (fixed stages, one choice per pedal, before or after the amp). Repository truth: commit `565551e7`; every API named below was read there and is listed in the ledger (section 12).
**Builds on:** the pedal board spec (`2026-10-04-pedal-board-design.md`: the PEDALS row, DRIVE and COMP, the pre-amp end fade, Stages 1 to 5), the WAH section (`2026-10-17-wah-section-design.md`), the brainstorm (`2026-10-19-pedal-board-brainstorm.md`: MERGE, item 14; BOARDS ROW, set aside; FRONT OF AMP, item 13) and WIDE (`docs/WIDE.md`, the first compound board, which this design leaves alone and must not collide with).
**Who this is for:** the owner first, then the implementing session. Plain words for what a user taps and hears; code names only where the next step has to go there.

## 1. Why

The owner's vision: not a fixed row of six pedals but a library, from which a user puts five or six pedals on a pad, and a library that grows as development continues. Today the engine can already do this and the sheet cannot:

- A pad's recipe holds an `FxChain` where any of the 21 sections may be set at once (`FxChain.kt:49-71`, one nullable map per section), and the remix bank's `punched` is a two-section character (`Shuffle.kt:94`). A six-pedal chain is a legal recipe now.
- The sheet applies one chip at a time: `applyTreatment` restores the bin's original (`unEraPad`) and then applies the new chip alone (`PadSheetScreen.kt:898-960`), so a second tap silently replaces the first. The brainstorm names this MERGE, "a product defect dressed as a missing feature".
- The PEDALS row is a hand-written list of six at most (`PadSheet.PEDAL_SEGMENTS`, `PadSheet.kt:100`; the six-per-row and 33-chip pins in `PadSheetTest.kt:120, 154`), so the row cannot be the library.

**Success:** a user opens the library, taps WAH, DRIVE and SLAP, hears all three on the pad in the order a guitar board would put them, taps WAH again and hears it leave, moves AMT on DRIVE alone, taps NONE and gets the clean pad back; a developer adds the next pedal by writing one registry entry and running its gate; nothing existing changes sound.

## 2. Decisions taken

1. **A library, not a row.** Pedals are entries in a registry (section 4). The sheet's row seven becomes a window onto the registry (section 7) and a picker shows all of it. The registry is the one place a pedal is declared; everything else (the sheet, the CLI, the replay, the tests) reads it.
2. **A board is a list of picks on one pad, each with its own AMT.** A pick is a registry entry plus an amount plus a side. The pad's audio is rendered from the bin's original through the whole board every time it changes, so picks never stack on each other.
3. **Order: fixed stages, one choice per pick.** The chain keeps TRANSPORT, ARRIVAL, the amp and MOTION where the rack has them. A pick sits on one of three sides: `pre` (before the amp), `amp` (it is the amp: DRIVE, and any future amp-class pedal) or `post` (after the amp). Within a side the rack's own fixed order applies. The render is two `FxChain` passes around the amp (section 5). This gives "wah before the amp or after it" and "echo before the wah", which the owner's listens have asked for, and never gives "reverb before the tape stop", which nothing has. The list is saved in the order the user picked, so a later round can honour that order within a side without a format change; this round does not.
4. **Boards are picks too.** A registry entry may set several sections (RIG, WALL, BOOM from the brainstorm). It is picked, faded and removed like a pedal. Where two picks set the same section, the later pick wins that section and the sheet names the owner (section 7).
5. **The saved key is `pedals`, not `board`.** `board` is WIDE's key on `PadRecipe` (`PadRecipe.kt:57`) and `boardPad` is its sheet wrapper (`BoardSheet.kt:153`). A WIDE pad refuses the library the way it refuses chips today (`WIDE_REMOVE_FIRST`, `PadSheetScreen.kt:902`); WIDE is not in the library this round.
6. **Gated pedals plus boards on day one; the library grows one gate at a time.** An entry carries a `gated` flag. Ungated entries exist for the CLI and saved kits and draw no chip, which is the pedal board spec's standing rule for a section that fails its gate.
7. **No drag.** The picker is a list with tap buttons (add, remove, a pre/post toggle). The October direction's accessibility read sets "a hold is a shortcut, never the only door" (`docs/DIRECTION_2026_10.md:220`).

## 3. What the user does (the sheet, in words)

- Row seven shows the pedals on this pad, lit, in chain order, then the rest of the gated library up to six chips, then MORE. Tapping an unlit pedal adds it at the sheet's AMT on its default side. Tapping a lit pedal removes it. NONE clears the board and restores the original.
- The AMT stepper moves the last-tapped pick only, and the chip it moves is marked. (Today re-tapping the lit chip re-treats at the AMT on screen; on row seven that gesture becomes "remove", so AMT needs its own target and the mark says which.)
- MORE opens the picker: every gated entry, pedals and boards, each with ADD or REMOVE and, for a pick on the pad, a PRE/POST toggle where the entry allows it. No order dragging.
- A pad that already carries an old-style single chip (a `treatment` recipe) reads as a board of one pick on its default side, so nothing a user made before changes.

## 4. The registry

One object in `synth` beside `Treatments`, call it `Pedals`, holding an ordered list of entries. The shape, as data:

| Field | Meaning | Rule |
|---|---|---|
| `word` | the chip word (`WAH`, `DRIVE`, `RIG`) | unique across the library and across every other sheet row (the one-word-one-row law) |
| `key` | the saved name of the pick | unique; for a pedal that is today's character name (`wahed`, `driven`, `compressed`); for a board a new name; checked against preset names the way section names are |
| `chain` | the `FxChain` the pick sets (one section for a pedal, several for a board) | no macro at its neutral (AMT could not move it; the AMT-scaling test false-fails on it), the standing rule from `Treatments.EXTRA` |
| `side` | default side: `PRE`, `AMP`, `POST` | from the rack: a section before `valve` defaults to `PRE`, `valve` itself to `AMP`, after it to `POST`; a board takes the side of its earliest section |
| `movable` | whether the user may flip PRE and POST | true for a pedal whose real board has both uses (WAH, COMP, the staged TREM and FLANGE); false for the amp, and for a board |
| `gated` | passed its owner listen | only gated entries draw a chip; every entry is reachable from the CLI |
| `fade` | whether the pre-amp end fade applies | true for any entry whose chain sets `valve` DRIVE above VALVE's default; replaces today's by-name lookup (`Treatments.PRE_AMP_FADE`, keyed on `"driven"`) |

Day one entries: DRIVE (`driven`, AMP, not movable, fade), COMP (`compressed`, PRE, movable), and WAH (`wahed`, PRE, movable) the moment its gate passes. Boards: RIG as the first, authored at its own gate (DRIVE 1.0 with COMP and SLAP, values pushed so the 0.7 tap reads as the board, the brainstorm's recommendation). TREM, FLANGE and the benchmark's later pedals join as entries when their sections pass.

The entry's `chain` reuses the characters in `Treatments.EXTRA` by reference, so the CLI `treat` and the registry never disagree on what WAH means (one quantity in one place). New characters still go on the end of `EXTRA`, never in the middle: `Shuffle.TREATMENTS` is seed-indexed and the names list is pinned in order (`TreatmentsTest.kt:31-45`).

## 5. The render: two passes around the amp

```
 original (from the bin)
   |
   v
 pass PRE : FxChain folded from every PRE pick, rack order, each pick's chain faded by its own AMT
   |          (Treatments.fade per pick, then FxChain.withSection per section; later pick wins a shared section)
   v
 end fade : Treatments.endFaded-style tail fade, applied when the AMP pass lifts (the registry's `fade`)
   |
   v
 pass AMP : the amp pick's chain (valve) faded by its AMT; absent when no AMP pick
   |
   v
 pass POST: FxChain folded from every POST pick, rack order
   |
   v
 cap      : total tail at most FxChain.MAX_CHAIN_TAIL_SECONDS over the original, then Dsp.fadeTail 10 ms
   |         (each FxChain pass caps its own tail; three passes could add three seconds; the board owns one budget, as WIDE does)
   v
 peak-matched to the original's peak (the rack contract, so a board changes shape, never loudness)
```

- A board whose picks are all on one side renders **bit-identically** to the single folded `FxChain` run through `Treatments.apply`. That equivalence is the first test: it ties the board to every existing character golden, and it means a one-pick board is exactly today's chip.
- The AMP pass is VALVE alone. The pre-amp end fade runs between PRE and AMP, measured on the audio the amp will see, with the length law `Treatments.endFaded` uses today (the amp's lift at the pick's AMT over its lift at full). Today that fade is reachable only by the name `driven`; this design keys it on the registry's `fade` flag, which also closes the brainstorm's "no pre-amp fade for a non-`driven` hot valve" hazard.
- A pad that is a synth pad with a landing amp (MAGNET) is treated as today: the board acts on the baked audio, so a POST pick lands after the landing amp and a PRE pick lands after it too. Stage 3 (FRONT OF AMP) is the design that composes the board into the landing chain and re-renders from the patch; this design gives it the `pedals` list to compose and does not attempt it.

## 6. The saved shape

A new optional field on `PadRecipe`, `pedals`, beside `board` (WIDE) and `fx`:

```
{ "recipe": 2,
  "pedals": { "pedals": 1,
              "picks": [ { "key": "wahed",  "amount": 0.7, "side": "pre"  },
                         { "key": "driven", "amount": 1.0, "side": "amp"  },
                         { "key": "slapback", "amount": 0.5, "side": "post" } ] },
  "alias": false }
```

- `PadRecipe.VERSION` stays 2: the field is optional, `fromJsonValue` reads named keys only, so an older recipe is untouched and an older build drops the key it does not know (the same tolerance `FxChain` has for an unknown section). `pedals` carries its own version, as `board` does.
- `fx` is null on a board pad. `PadRecipe.process` runs `fx`, then `pedals`, then `board`; `render` keeps doing `patch` then `process`. The `init` rule "an empty recipe records nothing" gains `pedals` as a fourth thing that counts.
- The list is the record and the render both: unlike `treatment` + `amount`, which record how an `fx` was made that AMT has already scaled, a pick list is enough to re-render, so nothing is stored twice.
- Picks are saved in the order tapped; this round renders by side then rack order and ignores list order within a side (decision 3).

## 7. The doors

- **`KitBuilderModel.pedalPad(slot, picks)`** (shell): restore is the caller's job as today; the door runs `rewriteEveryFile` with the board's render, bins every file the pad references, and writes the `pedals` recipe. `requireNotChained` applies (a round-robin chain is refused, as every rewrite refuses it). A WIDE pad is refused before anything is touched (`BoardSheet.read(recipe) != null`).
- **The sheet's read-back:** `PadSheet.read` gains a `pedals` branch returning every pick, so the card lights each; `unTreatState` treats a `pedals` recipe as a card recipe (undoable through the bin, the four answers unchanged). An old `treatment` recipe whose name is a registry key reads as a one-pick board on the entry's default side.
- **The app's tap handler:** `applyTreatment` on a row-seven chip computes the new pick list (add, remove, or AMT on the marked pick), restores through `unEraPad` when the bin is READY, then calls `pedalPad` with the whole list. NOT_BINNED stacks and toasts, exactly as chips do today (section 10 discloses it). GHOSTS_POSTDATE refuses, as today.
- **The CLI:** the `treat` verb gains the list form (`--pedals wahed@0.7:pre,driven@1,slapback@0.5:post`), and every registry entry, gated or not, is reachable there. The replay (`RecipeReplay.Plan`) gains a `Pedals` plan that walks `pedalPad`, so COPY LAST TREATMENT carries a board.
- **BREED:** today it keeps pads whose recipe has a patch or an `fx` and no `board` (`Breed.kt:115, 140, 254`). A `pedals` pad has neither `fx` nor `board`, so BREED skips it this round; folding a single-sided board into `fx` for BREED's sake is a one-line follow-up once the equivalence test (section 5) holds.

## 8. Tests

In `synth`:
- registry shape: words unique across every sheet row; keys unique; every chain names real sections (`FxChain.SECTION_NAMES`) with no macro at its neutral; a board's side equals its earliest section's; `fade` true for every entry with a hot `valve`; keys collide with no preset name (the existing name tests extended to registry keys);
- fold: later pick wins a shared section; each pick faded by its own AMT; rack order within a side;
- equivalence: a single-sided board equals `Treatments.apply` of the folded chain, sample for sample, for every gated entry at AMT 0.7 and 1;
- two passes: WAH `pre` + DRIVE `amp` equals the audition generator's "rig order" render (two renders in a row, `generatePedalAudition`'s path), and WAH `post` + DRIVE differs from it by at least the centroid margin the WAH tests use;
- the end fade: a DRIVE pick at AMT 1 ends a snare at least 53 dB under its peak (the pedal board spec's bar plus margin), through the board's door;
- the tail: three tail-making picks on one pad add at most `MAX_CHAIN_TAIL_SECONDS`;
- determinism; finite and in range; peak within 0.05 of the original's; stereo per channel; JSON round trip; an old `treatment` recipe reads as one pick; a recipe without `pedals` renders byte-identically to before.

In `shell`: `pedalPad` bins every file and writes the list; NONE restores; a WIDE pad refuses; a chained pad refuses; the sheet's read lights every pick; the CLI list form round-trips; replay carries a board; `PadSheetTest`'s inventory re-pinned on the registry (section 9).

## 9. Registration, pins and hazards

- **The sheet's pins move.** `PadSheet.PEDAL_SEGMENTS` becomes derived from `Pedals` (gated words), so `ALL_SEGMENTS` and the "33 chips" and "at most six wide" pins (`PadSheetTest.kt:120, 131, 154, 158`) are re-pinned on the registry's count; row seven's width law becomes "at most six drawn, the rest behind MORE". MORE is a sheet affordance, not a segment, so `treatmentFor` never sees it.
- **The re-tap gesture changes on row seven only.** Every other row keeps "re-tap re-treats at the AMT on screen" (`PadSheet.tappable`'s KDoc). The marked-pick rule for AMT is new sheet state and needs an app test (an SDK session).
- **`Treatments.PRE_AMP_FADE` is keyed by name.** It becomes keyed by the registry's `fade` flag; `Treatments.apply` keeps its behaviour for `driven` by construction (the flag is true for it). The test "the door's output equals the chain's for every name but driven" (`TreatmentsTest.kt:369-375`) is the one to re-read.
- **`PadRecipe.process` order** (`fx`, then `pedals`, then WIDE's `board`) is a decision this spec makes and WIDE's doc should state once it lands.
- **Six per pad.** The picker refuses a seventh pick in words; the number is a constant with the vision's reason, not a measured limit.
- **A board pick's AMT fades all its members at once** (the brainstorm's section 4 finding). The benchmark's fade-law proposal (F2, a `LOG` law on gain macros) is the fix; until it lands, board entries are authored so the 0.7 tap reads as the board.
- **The SAY become-strung design and the staged sections** each append to `Treatments.EXTRA` and `FxChain`; the registry appends too. Whichever lands second re-pins the lists (the pedal board spec's standing merge hazard).

## 10. Disclosed, not fixed

- **NOT_BINNED stacks.** A board applied to a pad whose original the bin no longer holds renders on the treated audio, and the `pedals` list then names less than the sound carries. Chips do the same today and the toast says so; the record is honest about the board and silent about what was under it.
- **Guitar pads still hear every pick after their landing amp.** Stage 3 owns this; the design hands it the list.
- **List order within a side is saved and ignored.** A later round may honour it; the format already carries it.
- **BREED skips board pads** this round (section 7).
- **Nothing was rendered.** The two-pass equivalence and the end-fade bar are the first measurements.

## 11. Out of scope

Reordering within a side (saved, not honoured); drag gestures; a user-saved board ("MY BOARD", deferred in the brainstorm until a boards row exists; the registry is that row, so it becomes possible next); WIDE in the library; the front-of-amp composition for synth pads (Stage 3); per-pick dials beyond AMT and side; tempo-locked picks (the KEYED family); presets.

## 12. Effort, rounds and the gate

- **R1, `synth` and `shell`, M:** the registry with the day-one entries; `PedalBoard` (the type behind `pedals`) with fold, two-pass render, cap, peak match; the `PadRecipe` field; `pedalPad`; the sheet readers; the CLI list form; the replay plan; the tests in section 8. No app change, no audible change to any existing pad.
- **R2, `app`, M, an SDK session:** row seven as a window, MORE, the picker list with tap buttons, the marked pick for AMT, the toasts; `app/src/androidTest` coverage for add, remove, flip side, AMT on the marked pick, NONE.
- **R3, the owner's gate:** on a snare: DRIVE at 70 then WAH at 100, both lit, the quack dirty rather than the drive gone; WAH off, the drive exactly as before; NONE, the clean snare. On JANGLE: WAH `pre` against WAH `post` with DRIVE. On the choir: COMP, WAH and SLAP together, AMT moved on SLAP alone. RIG as one pick beside the three taps that make it. Then the question the gate exists to answer: can you build a board with no drag and know which pedal AMT is moving?

**Verification:** `./gradlew --no-daemon test` judged by its exit code (the steward skill's rule); R2 additionally `./gradlew :app:assembleDebug` and the Android suite on a device; then the gate.

## 13. Ledger

| Claim | Status | Evidence |
|---|---|---|
| Any subset of sections may be set on one `FxChain`; `withSection`, `section`, `SECTION_NAMES`, `macrosOf`, `MAX_CHAIN_TAIL_SECONDS` exist | verified | `FxChain.kt:49-71, 150-230` |
| `FxChain.process` runs TRANSPORT and ARRIVAL, then the rack, then caps the tail at 1.0 s with a 10 ms fade | verified | `FxChain.kt:88-122` |
| `Treatments.EXTRA`, `chain`, `fade`, `apply`, `endFaded`, `PRE_AMP_FADE` keyed on `"driven"` | verified | `Treatments.kt` |
| `PadRecipe(patch, fx, treatment, amount, alias, board, terminalFadeMs)`, `VERSION = 2`, reads named keys only | verified | `PadRecipe.kt:21-60, 110-146` |
| `board` is WIDE's (`BoardRecipe`, `BoardType.WIDE`), the sheet wraps it as `boardPad`, a WIDE pad refuses chips | verified | `BoardRecipe.kt`, `BoardSheet.kt:153`, `PadSheetScreen.kt:902`, `docs/WIDE.md` |
| `characterPad`, `keyedPad`, `unEraPad`, `rewriteEveryFile`, `requireNotChained` | verified | `KitBuilder.kt:740-852, 811-831, 1240` |
| `applyTreatment` restores when READY and then applies one chip; NOT_BINNED stacks with a toast | verified | `PadSheetScreen.kt:898-960` |
| `PadSheet.PEDAL_SEGMENTS`, `ROWS`, `CHARACTER_FOR`, `treatmentFor`, `read`, `unTreatState`, `tappable` | verified | `PadSheet.kt:100-110, 179-230, 264-420` |
| The 33-chip and six-wide pins; the names list pinned in order | verified | `PadSheetTest.kt:120-158`, `TreatmentsTest.kt:31-45` |
| `Shuffle.TREATMENTS` is seed-indexed; `punched` sets two sections | verified | `Shuffle.kt:89-98, 112` |
| BREED keeps pads with a patch or `fx` and no `board` | verified | `Breed.kt:115, 140, 254` |
| `RecipeReplay.Plan` has Era, Character, Board, KeyedPlan, Smear, Dust, Patch, RobinDeal, Refused | verified | `RecipeReplay.kt:28-60` |
| The accessibility rule on holds | verified | `docs/DIRECTION_2026_10.md:220` |
| A single-sided board equals the folded chain sample for sample | proposed | the R1 equivalence test |
| WAH `pre` with DRIVE equals the audition's rig-order render | proposed | the R1 two-pass test |
| A six-pick board stays within one second of tail | proposed | the R1 tail test |
| A user can build a board with no drag and know which pick AMT moves | heard? | not heard; the R3 gate |

## 14. Why it is built this way

- **A registry, because the library has to grow.** Today a pedal is three edits in three files (a character, a chip word, a word-to-character line) and two pinned counts. With one entry per pedal, the fiftieth pedal costs what the third does, and the tests pin the entry's shape, not the number of entries.
- **Two passes around the amp, because that is the one order the listens have asked for.** The owner preferred the wah before the amp on jangle and after it on the snare. Two `FxChain` passes give that choice with the chain's code untouched, and the rack's fixed order inside each pass keeps "no wrong answers" for the novice. A free list would have cost a format migration, the pinned order tests and a drag screen for a freedom nobody has asked for yet; the saved list keeps the door open.
- **Re-render from the original every time, because stacking is how the sheet lies.** The brainstorm found that a second tap silently replaced the first; a board that re-rendered on top of itself would silently double. Rendering the whole list from the bin's original is the only way the chips on the card and the sound in the pad stay the same thing.
- **The saved key is `pedals` and WIDE is left alone,** because `board` is taken by a shipped feature with its own baseline store, undo and refusal rules, and sharing a key with it would make two different things read as one.
- **The one risky decision** is the sheet's new gesture on row seven: tapping a lit chip removes it instead of re-treating, and AMT needs a marked target. Every other row keeps the old gesture, so the card has two rules. The gate's last question is whether that reads on a phone.

## As built (R1, 2026-10-22, `synth`, `shell` and `cli`; no app change, no audible change to any existing pad)

Against the design above, read at the branch's actual base (the default branch after PRs #613, #616), with these differences:

- **The registry** is `Pedals` in `synth` (`Pedals.kt`): `Entry(word, key, chain, side, movable, gated)` with `sections`, `isBoard`, `fade` (derived, not stored: true where the chain's VALVE DRIVE sits above VALVE's own default), `sides` (the amp only at AMP; a movable pedal PRE or POST; a board its own side) and `chainAt(amount)`, which is `Treatments.chain`'s law (full at 0.999 and above, faded between, nothing at 0), so a one-pick board is exactly a chip. `PedalSide` is `PRE`, `AMP`, `POST`. Day one: DRIVE (`driven`, AMP, gated), COMP (`compressed`, PRE, movable, gated), WAH (`wahed`, PRE, movable, gate pending so not gated), RIG (`rig`, a board of `compressed`'s squash, `driven`'s valve and `slapback`'s echo, AMP, not gated: authored as a hypothesis for its own gate, as section 4 says). **A board's side rule changed:** a board that contains the amp sits at AMP (section 4 said "the side of its earliest section", which would have put RIG's compressor side first and its amp nowhere); a board without the amp takes its earliest section's side. The registry's `init` refuses duplicate words or keys, a pedal whose key is not a character, and a board whose key is one.
- **The board** is `PedalBoard(picks)` with `Pick(key, amount, side)` (`PedalBoard.kt`): `chainFor(side)` folds a side's live picks in pick order with `withSection` (later wins; `reverse` ORs), `owner(side, section)` names the winner, `process` runs PRE, then the pre-amp end fade and AMP, then POST, then the board's own cap at `FxChain.MAX_CHAIN_TAIL_SECONDS` over the original with the chain's 10 ms fade. **No board-level peak match was added:** every pass already peak-matches section by section, and a second match would have broken the equivalence with the chip by a float factor. Six picks at most (`MAX_PICKS`), no pedal twice, a side the entry does not allow refused in words; AMT 0 everywhere is a bypass. `fromCharacter(name, amount)` reads an old single-chip recipe as a one-pick board on the pedal's default side. JSON is `{"pedals":1,"picks":[{"key","amount","side"}]}`, read strictly (exact keys, version, a known pedal, a legal side, a finite amount in 0..1), as WIDE's `BoardRecipe` is.
- **The pre-amp end fade is keyed on the chain, not on a character's name** (`Treatments.needsPreAmpFade(chain)`, `Treatments.endFaded(snip, faded, full)`): `driven` fades exactly as before (its full chain is the same chain), `amped` still does not (DRIVE 0.6 is under VALVE's default 0.7), and the board's AMP pass fades against the winning pick's full chain. The by-name overload stays for `Treatments.apply`. The test "the end fade is driven's alone" still passes unchanged; `PedalBoardTest` repeats it as the chain rule's own check.
- **`PadRecipe.pedals`** is the last constructor field, optional, runs after `fx` and before `board` (WIDE), counts as content for the "empty recipe" rule, is written as `pedals` and read back; `VERSION` stays 2. A recipe without it writes no key and renders byte-identically.
- **The door** is `KitBuilderModel.pedalPad(slot, board)`: a WIDE (or RUSH) pad refuses first, AMT 0 everywhere is a no-op touching neither file nor bin, otherwise `rewriteEveryFile` renders every referenced file through the board and writes the `pedals` recipe. Restore-before-apply stays the caller's job, as for a chip; undo is `unEraPad`. **Deviation from section 7:** `PadSheet.read` does not return a board (an `Applied` names one treatment, and lighting one chip for several picks would be the lie the September UAT found); the board's reader is `PadSheet.readPedals`, and `unTreatState` consults it, so NONE is live over a board. `KitDiff.recipeName` names every pick ("COMP 70% PRE · DRIVE 100% AMP"), so the copy clip and the diff say what the sheet will light.
- **Row seven derives from the registry:** `PadSheet.PEDAL_SEGMENTS` is the gated, non-board entries' words, and their rows in `CHARACTER_FOR` come from the registry too (one quantity, one place). Today that is DRIVE and COMP, so every pinned count (33 chips, six wide) holds; `PadSheetTest` gained the registry law (gated words are the row, ungated words draw no chip).
- **The replay** gained `Plan.Pedals(board)` (read by `PadSheet.readPedals`, applied through `pedalPad`), so COPY LAST TREATMENT carries a board and twins render the same bytes.
- **The CLI** is `treat <kit> <pad> --pedals key[@amount][:side],...` on the existing verb (no new verb): a side left out is the pedal's own; refusals are the library's and the board's own words; `--undo` is unchanged. `docs/CLI.md` says so.
- **Tests** (all green by exit code, `./gradlew --no-daemon test`): `PedalsTest` (the registry's laws), `PedalBoardTest` (the equivalence of a one-pick board with the chip for every pedal entry at 0.7 and 1; RIG against its folded chain with the fade; fold order and the later pick winning; WAH `pre` + DRIVE equals the rig order and WAH `post` + DRIVE equals the pad-sheet order, and they differ; a DRIVE pick ends the snare 53 dB under its peak over the last 20 ms; the tail budget; determinism, finiteness, bypass, refusals; strict JSON; an untouched old recipe; the one-pick reading of an old chip), `PedalPadTest` (layers rewritten and binned, read-back, the diff line, READY then restore byte for byte; a one-pick board equals the chip file for file; AMT 0 no-op; a WIDE pad refuses; the replay carries a board), the `PadSheetTest` registry law, and the CLI round trip with two refusals. **Not built this round:** the three-tail-pick budget test (the library holds one tail-making entry, RIG; the budget is exercised by RIG alone), and a chained-pad refusal test of its own (the door shares `requireNotChained` with every rewrite).
- **Measured:** nothing in audio beyond what the tests assert; nothing heard.

## The WAH gate (2026-10-23)

WAH passed the owner's gate, and the owner asked for TOUCH and ROCK chips as well. The registry gains two entries after WAH (`touchwahed`, `rockwahed`, PRE, movable), and all three turn `gated`. Row seven derives from the registry, so it now reads DRIVE, COMP, WAH, TOUCH, ROCK, and the card draws 36 chips (the pinned inventory and count in `PadSheetTest`, and `PedalsTest`'s gated list, re-pinned). That is five of the row's six places: TREM and FLANGE (Stage 4) do not both fit, so before they pass their gate either the picker (R2) holds the rest of the library or the row's layout is decided again.

