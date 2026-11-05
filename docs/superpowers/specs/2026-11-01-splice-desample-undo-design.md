# SPLICE and DE-SAMPLE undo themselves on the pad sheet

**Status:** design, 2026-11-01, with the owner's answers in section 1. Built in the same round: the readers, the two model doors and the words in `shell`, `desample --undo` in `cli`, with tests; the two buttons in `app` (compiled and run by CI).
**Answers:** the "separate change" the bin spec left open (`2026-10-31-bin-keeps-what-a-pad-needs-design.md`, section 6, "Where BACK stays on a kept row"), and the journey review's finding that DE-SAMPLE was the one destructive bench with no undo control (`docs/UX_JOURNEY_REVIEW_2026_09.md`, the second of its "four places").
**Changes another spec:** the bin spec's *(review)* rule that a synth send over a de-sampled pad keeps the `desampled` stamp is reversed (section 3). The bin spec carries dated notes pointing here.
**Who this is for:** the owner first, then the implementing session. Plain words for what a user sees; code names only where the next step has to go there.

## 1. The owner's answers (2026-11-01)

The question: TAPE SPLICE and DE-SAMPLE (MAKE SYNTH) both replace a pad's audio and bin the take before it, but neither had an undo on the pad sheet. BACK in THE BIN was their only way back. BACK copies the take over the pad's audio and leaves the recipe, so the pad went on saying it was spliced, or a patch. Since 2026-10-31 the bin keeps that take for as long as the pad needs it, so BACK sat on a row that never leaves.

**The owner took the recommended option on all four:**

1. **Placement.**
   - SPLICE: an `UNDO SPLICE` button sharing one row with `SPLICE ▸`, half width each.
   - DE-SAMPLE: a visible `UNDO` in the DE-SAMPLE header row inside the MAKE box, read aloud as `UNDO DE-SAMPLE`.
   - Both are always drawn, dimmed when they would refuse, and disabled only while the sheet is busy. Neither has a ▸, because neither opens anything.
2. **Wording.** The seven lines in section 5, exactly. On a pad MUTATE spliced, "THIS PAD CARRIES NO SPLICE." is accepted: the button sits beside SPLICE ▸, and MUTATE's own UNDO is on the MUTATE card.
3. **DE-SAMPLE's mark.** Any later audio replacement clears the `desampled` stamp (section 3).
4. **SPLICE's landing names its way back.** `Copy.spliced` ends " THE TAKE BEFORE IT SLEEPS IN THE BIN." on both of its variants, as DE-SAMPLE's, MUTATE's and DRIFT's landings already do.

## 2. The two doors

Both copy MUTATE's UNDO, not OUTSIDE's (which is disabled rather than dimmed, and never says why). On a tap:

1. read the screen's pad;
2. ask the door's refusal of it, with `binned = binDaysLeft != null`;
3. on a refusal, toast it and stop, touching nothing;
4. otherwise `commitPadEditNow("UNDO", onSuccess = { toast the landing }) { mm -> mm.unsplicePad(slot) }` (or `undesamplePad`).

`commitPadEditNow` already holds the busy guard, the kit lock and fresh open, the `sampleFile` identity check (`BIN_ITEM_GONE`) and `failure("UNDO", e)`. The model doors ask the same refusal again of the kit they open and throw it, so `desample --undo` prints the sheet's words, and a stale screen cannot undo the wrong thing.

### UNDO SPLICE

- **What it reads.** `PadSheet.isSpliced(recipe)`: the top-level `"splice"` key that `splicePad` writes, the same test the TAKES diff and DO IT AGAIN use. Never MUTATE's SPLICE move (`{"mutate":{"mode":"splice"}}`).
- **What it does.** `KitBuilderModel.unsplicePad(slot)` runs `untreatPad`. The newest bin entry under the pad's main file comes back, which is the take `replaceAudio` binned at the splice, and the recipe comes off. A splice adds no source stamp, so there is none to clear. After two splices the take that comes back is the first splice's result; the landing says "THE TAKE BEFORE THE LAST SPLICE" for that reason, as `UNMUTATED` does.
- **What it refuses**, most final first (`PadSheet.spliceUndoRefusal`), so nobody is told to turn SOFT HITS off and is then refused anyway:
  - no TAPE SPLICE on top: never spliced, already undone, MUTATE's SPLICE move, or a print over the splice (`SPLICE_UNDO_NOTHING`);
  - no earlier take of the main file in the bin: EMPTY THE BIN NOW, or a BACK from before this change spent it (`SPLICE_UNDO_NOT_BINNED`);
  - soft hits on, from GHOSTS or STACK THE TAKES after the splice (`UNDO_LAYERED`);
  - a round-robin chain, which `untreatPad` refuses itself.
- **Landing:** `Copy.UNSPLICED`.

### DE-SAMPLE's UNDO

- **What it reads.** `PadSheet.isDesampled(pad)`: the de-sample is still what sits on top. That means the `desampled` stamp, no print, and exactly the recipe `desamplePad` writes: a patch with nothing riding it (`fx`, `treatment`, `amount`, `board`, `terminalFadeMs`, `pedals` and `layers` all null).
- **What it does.** `KitBuilderModel.undesamplePad(slot)` runs `untreatPad`, then removes the `desampled` stamp.
  - The take before the de-sample comes back. A bare patch has no pedals for the undo to keep, so the recipe goes.
  - With the stamp gone, the MAKE strip stops saying "A PATCH NOW", the KIT grid stops marking the MAKE box, and the bin stops keeping anything for the pad.
- **What it refuses**, in the same order (`PadSheet.desampleUndoRefusal`):
  - the de-sample is not on top (`DESAMPLE_UNDO_NOTHING`): never de-sampled or already undone, a later replacement that cleared the stamp (section 3), pedals riding it, or a print, a treatment or a layer bank over it. With pedals, NONE is the door: it takes the pedals off and keeps the patch, and then this UNDO lights and gives back what the de-sample replaced;
  - no earlier take in the bin (`DESAMPLE_UNDO_NOT_BINNED`);
  - soft hits on, which GHOSTS renders from the patch (`UNDO_LAYERED`);
  - a chain, refused by `untreatPad`.
- **Landing:** `Copy.UNDESAMPLED`.
- **The CLI.** `snipsnap desample <kit> <pad> --undo` runs the same `undesamplePad` and prints "pad A01 restored - the take before the de-sample came back out of the bin". It used to call `untreatPad` alone: that left the stamp, popped whatever take was newest on a pad never de-sampled, and said "the capture", which is wrong when the take before the de-sample was itself treated.

### Under a print

Both doors read the **live** pad's recipe. A printed pad's recipe is the print's wrapper, so both refuse with their NOTHING line until the print is removed. Running `untreatPad` under a print would overwrite the print's render, and REMOVE's hash check would then refuse for good. THE BIN judges the pad under the print (section 4), and the print's own NONE or REMOVE comes first.

## 3. The `desampled` stamp now means "this audio is a de-sample's render"

- **The change.** `replaceAudio` drops the stamp along with `rawSha256` (`source = it.source - "rawSha256" - "desampled"`). `desamplePad` stamps again straight after its own replacement.
- **What clears it.** Every audio replacement through `replaceAudio`: SEND TO PAD, SNAP, the DO IT AGAIN patch paste, SMEAR, DUST, MUTATE, OUTSIDE, TAPE SPLICE, ROBIN, CLEAN, the mix doctor, and the unlabelled rewrites (SNAP's, SPLIT's, SURFACE's) that keep the old recipe.
- **What keeps it.** Pedals, eras, characters, the keyed treatments and the CLI's fx-rack treatments rewrite the file without `replaceAudio`. They keep the stamp, and their recipe makes `isDesampled` false. Pedals keep the patch too, so it reads false only until their NONE gives the bare patch back. The others replace the patch, and their NONE leaves no recipe, so it stays false (section 7).
- **Why.** SEND TO PAD, SNAP and the patch paste leave exactly the stamp and recipe shape a de-sample leaves. With the stamp surviving them, the UNDO would have said "the take before the last de-sample" and given back the de-sample's own render. The other way was to record when the de-sample binned its take and compare that with the newest entry. That is exact, but it changes `hasOwnWayBack`'s and the screens' API, which only know file names.
- **What it reverses.** The bin spec's section 2 and the `dependsOnBin` KDoc said a synth send over a de-sampled pad keeps the stamp, so the bin keeps the render that send replaced, "which is what `--undo` would restore". Now that send leaves a plain synth pad. Nothing is kept for it: the render the send replaced and the capture under it both go on the 30-day clock, with BACK showing.

## 4. The code

**`shell`:**

- `PadSheet.isSpliced`, `isDesampled`, `spliceUndoRefusal(pad, binned)` and `desampleUndoRefusal(pad, binned)`, beside `hasOwnWayBack`. One reader serves the door and THE BIN.
- `KitBuilderModel.unsplicePad(slot)` and `undesamplePad(slot)`, beside the forward doors. They ask the refusal of the live pad, with `binned` meaning the bin holds an entry under the pad's main file (the sheet's `binDaysLeft != null`), and then reuse `untreatPad`, as `Mutate.undo` and `OutsideSheet.undo` do.
- `replaceAudio` drops `desampled` (section 3).
- `PadSheet.hasOwnWayBack` gains `isSpliced(pad.recipe) || isDesampled(pad)` for the main file only.
  - `TakesBinScreen` needs no code change: it already asks this of the pad under any print, so a spliced or de-sampled pad's kept row loses BACK.
  - That closes the trap where BACK spent the entry and left `{"splice"}` or the patch on the pad, so a later name-keyed undo went one step too far.
  - BACK stays on older rows under the same name, where the newest entry is still there.
  - A layered spliced or de-sampled pad still answers true for its main file: its UNDO refuses until SOFT HITS is off and says so, as NONE's GHOSTS_POSTDATE does. A layer's file answers false.

**`cli`:** `DesampleCommand`'s `--undo` calls `undesamplePad` and prints the new line.

**`app`, `PadSheetScreen`:**

- **The SPLICE row.** The foot's `SPLICE ▸` becomes a `Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp))` with `SPLICE ▸` and `ActionButton("UNDO SPLICE", scheme, enabled = !busy, dimmed = PadSheet.spliceUndoRefusal(pad, binDaysLeft != null) != null, modifier = Modifier.weight(1f), onClick = ::onUnsplice)`, the COPY/PASTE row's shape. A weight inside a Row is safe; the GRAIN ▸ trap is a weight inside a Column.
  - The label is the button's own because the foot is always composed. A bare `UNDO` there would collide with `WidePadSheetScreenTest.activate("UNDO")`.
- **The DE-SAMPLE header.** The `DE-SAMPLE · THE NEAREST PATCH` line becomes the header row MUTATE and OUTSIDE use: the title at `Modifier.weight(1f)`, then `ActionButton("UNDO", scheme, enabled = !busy, dimmed = PadSheet.desampleUndoRefusal(pad, binDaysLeft != null) != null, accessibilityLabel = "UNDO DE-SAMPLE", onClick = ::onUndesample)`. MAKE holds five doors, so TalkBack needs more than "UNDO".
- `onUnsplice` and `onUndesample` are copies of `onUnmutate` (section 2).
- **Comments that change with this:**
  - `binDaysLeft`'s "six" checks become eight;
  - `hasOwnWayBack`'s KDoc, THE BIN's KDoc, `TapeSpliceScreen`'s "undoable from THE BIN", and `splicePad`'s and `dependsOnBin`'s KDoc;
  - `SAVING_PAD_BUSY`'s comment says "the UNDOs", not "both UNDOs".

## 5. The words

All in `Copy` (`Personality.kt`), each a `const`:

| Constant | Text |
|---|---|
| `UNSPLICED` | `UNDONE. THE TAKE BEFORE THE LAST SPLICE IS BACK FROM THE BIN.` |
| `SPLICE_UNDO_NOTHING` | `THIS PAD CARRIES NO SPLICE. NOTHING TO UNDO HERE.` |
| `SPLICE_UNDO_NOT_BINNED` | `THE BIN HOLDS NO EARLIER TAKE OF THIS PAD. THE SPLICE STAYS.` |
| `UNDESAMPLED` | `UNDONE. THE TAKE BEFORE THE LAST DE-SAMPLE IS BACK FROM THE BIN.` |
| `DESAMPLE_UNDO_NOTHING` | `THIS PAD CARRIES NO DE-SAMPLE. NOTHING TO UNDO HERE.` |
| `DESAMPLE_UNDO_NOT_BINNED` | `THE BIN HOLDS NO EARLIER TAKE OF THIS PAD. THE PATCH STAYS.` |
| `UNDO_LAYERED` (shared) | `SOFT HITS IS ON: THIS PAD HAS LAYERS. UNDO WANTS ONE SAMPLE - TURN SOFT HITS OFF FIRST.` |

`Copy.spliced(crossfaded)` keeps its two variants and adds ` THE TAKE BEFORE IT SLEEPS IN THE BIN.` to each.

The laws they pass:

- **PersonalityTest:** every line shouts and ends on a full stop. The button labels stay inline literals, as MUTATE's `"UNDO"` is, so they need no `notASentence` entry.
- **ReversalTest:**
  - `UNDONE` is a destructive word, and both landings answer it with `BACK` and `FROM THE BIN`;
  - no refusal carries a destructive word, and no number comes before DAYS;
  - every line is a `const`, because the source scan reads a templated `fun`'s name as well as its literals;
  - the `noWayBack` count stays 6.
- **MutateWordsTest** is unchanged. MUTATE's `UNDO_NOTHING`, `UNDO_NOT_BINNED` and `MUTATE_NEEDS_ONE` name MUTATE or GHOSTS, so none is reused here.

## 6. Tests

`shell`, `KitBuilderTest`:

- DE-SAMPLE's builder test now undoes with `undesamplePad`: the capture comes back byte for byte, the recipe and the stamp are gone, and a second UNDO refuses with `DESAMPLE_UNDO_NOTHING`.
- `undesamplePad` refuses under pedals, after a later plain-patch replacement, under soft hits, and with an emptied bin. Each refusal leaves the audio, the pad and the bin as they were. After NONE takes the pedals off, the UNDO lands and gives the capture back.
- A later audio replacement clears the stamp, with a patch of its own or unlabelled. Every other stamp stays, the bin keeps nothing for the pad, and a fresh DE-SAMPLE stamps again.
- The splice test: the take before the splice is kept for A01. `unsplicePad` gives back the pre-splice audio byte for byte with a null recipe, nothing is kept afterwards, and a second UNDO refuses with `SPLICE_UNDO_NOTHING`.
- `unsplicePad` refuses MUTATE's SPLICE move, soft hits, an emptied bin and a print, touching nothing each time.
  - After SOFT HITS off, the UNDO lands.
  - Under the print, `hasOwnWayBack` on the pad underneath is true; after REMOVE, the UNDO lands.
- The bin's de-sample test is now "a de-sampled pad keeps its capture past the sweep, and its UNDO gives it back"; the stamp is gone afterwards.

`shell`, `PadSheetTest`:

- `hasOwnWayBack`: a SPLICE and a DE-SAMPLE now answer true; they were false. Also:
  - true for the main file of a spliced pad with soft hits after the splice, false for its layer, and false with nothing binned under the main file;
  - MUTATE's SPLICE move answers true through MUTATE's own UNDO;
  - a plain synth send answers false, and so does an fx chain riding the de-sample's patch;
  - pedals over a de-sample answer true through NONE.
- `isSpliced` answers only to the top-level key: not MUTATE's move, not the word as a value.
- `isDesampled` answers only to the stamp over the bare patch. It is false with no stamp, with no patch, and with an fx chain, a treatment, pedals, a layer bank, an era or a print marker.
- Both refusals come in their order, most final first. A splice over a de-sample is not a de-sample, and a de-sample is not a splice.

`shell`, `PersonalityTest`:

- Each door's four answers are distinct. The landing names its door and the bin; the NOTHING line names its door and claims no BACK; the not-binned line names the bin and claims no BACK. None says MUTATE, and `UNDO_LAYERED` names SOFT HITS.
- Both of SPLICE's landings end " THE TAKE BEFORE IT SLEEPS IN THE BIN." and still differ.

`cli`, `CliTest`: `desample --undo` prints the new line, gives the capture back byte for byte, and clears the recipe and the stamp. On a pad that was never de-sampled it exits non-zero with `DESAMPLE_UNDO_NOTHING`, and that pad's audio and its treatment's take in the bin are untouched.

`app`:

- ConventionTest gains a law for each door, cloned from MUTATE's two: the button is `enabled = !busy` with `dimmed = PadSheet.*UndoRefusal(...) != null`, and the handler toasts the refusal before it calls `commitPadEditNow(`.
- The androidTest `TakesBinScreenTest` (CI's emulator): the spliced pad's kept row (`KEPT FOR A03`) now has no BACK. A kept row whose recipe no door on the sheet reads (an fx-only recipe, as only the CLI makes) takes its place as the kept row that keeps BACK.

## 7. Left alone, on purpose

These are defaults taken by precedent. The owner can overrule any of them.

- **A layered pad refuses** rather than having its main file restored alone. This matches the forward doors (`requireRewritable`, MAKE SYNTH's own soft-hits refusal) and NONE's GHOSTS_POSTDATE.
- **The recipe from before the splice or the de-sample is not brought back.** The forward door overwrote it and nothing stored it; MUTATE's and OUTSIDE's UNDO and the old `desample --undo` clear it too. Storing a "before" recipe inside the splice's own would only help splices made from now on, and would nest one level deeper with each splice.
  - So undoing a splice or a de-sample over a treated pad gives back the treated audio reading untreated, and NONE cannot then take that treatment off.
  - Undoing a splice over a de-sample gives back the de-sample's render with neither patch nor stamp, because the splice's `replaceAudio` cleared the stamp.
- **Limits shared with MUTATE:**
  - An unlabelled `replaceAudio` over a spliced pad (SNAP's, SPLIT's or SURFACE's rewrites) keeps `{"splice"}`, so UNDO SPLICE then gives back what that write replaced. DE-SAMPLE is spared this by section 3.
  - A pad BACKed in THE BIN before this change still carries its recipe while the entry it needed is spent, so its UNDO gives back the next older take, or refuses when there is none.
  - A pad de-sampled and then sent over (a synth send, SNAP or a patch paste) before this change kept its `desampled` stamp under a bare patch, so `isDesampled` reads true for it. Its UNDO gives back the newest take, the de-sample's own render, not the capture; the capture was already on the bin's clock and keeps its BACK. Nothing migrates the old stamps: telling the two apart would mean recording the de-sample's bin entry, the alternative section 3 set aside.
  - VERSIONS can restore any mix of recipe and audio, and the doors trust the recipe.
- **An era, a character or a keyed treatment over a de-sample** replaces the patch in the recipe and keeps the stamp. Its NONE gives the de-sample's render back with no recipe, as before: only pedals keep a patch through an undo. DE-SAMPLE's UNDO then refuses, and the MAKE strip still says "A PATCH NOW". This was already the case, and is noted here, not fixed.
- **Found on the way, not decided here:**
  - TAPE SPLICE's COMMIT on a layered pad toasts the generic `SPLICE_FAILED` instead of the refusal.
  - Neither `splicePad` nor MAKE SYNTH refused a printed pad, so their explicit recipe overwrote the print's wrapper: REMOVE then found no print to take off, and the source it keeps could never come back. **Closed 2026-11-02:** `splicePad` and `desamplePad` start with `require(!PrintedPads.has(pad.recipe))` and refuse before any audio is read or written, in `Copy.boardRemoveBeforeSplice` and `Copy.boardRemoveBeforeDesample` ("REMOVE WIDE BEFORE SPLICE.", "REMOVE WIDE BEFORE DE-SAMPLE."), as SOFT HITS, STACK and SPREAD already did. On the pad sheet SPLICE ▸ (`openSplice`) and MAKE SYNTH (`onDesample`) toast the same line on the tap, before TAPE SPLICE opens or the search starts, as SPREAD ▸ does. `desample` in the CLI refuses with it, exit 2, as its other refusals do.
  - OUTSIDE's UNDO is disabled rather than dimmed and never says why, against the rule MUTATE's and these two follow.
