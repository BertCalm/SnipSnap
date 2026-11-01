# The bin keeps what a pad still needs

**Status:** design, 2026-10-31, with the owner's answer in section 1. Built in the same round: the rule in `shell` with tests, the two screens in `app` (compiled and run by CI).
**Answers:** the open question in the pedal slate's D5 (`2026-10-28-pedal-slate-design.md`, section 3 and section 10).
**Who this is for:** the owner first, then the implementing session. Plain words for what a user sees; code names only where the next step has to go there.
**BACK on a kept row (the owner's call, 2026-10-31):** hidden where the pad can give the entry back itself; kept where BACK is the pad's only way back. Section 4 and section 6 say why.
**2026-11-01 (the owner's call):** a new pad never takes a file name the bin still holds. Section 6.
**Review amendments (2026-10-31, for the owner to confirm):** the adversarial review found five places where the rule as first written missed its own purpose, and a sixth where failing closed went further than it needed to. Each is marked *(review)* below: (c) follows an ejected printed pad to the pad underneath it, and prints over prints are followed all the way down; (c) takes the newest entry first and only then applies the tombstone guard; every eject writes a tombstone, layered and chained ones too; a de-sampled pad counts as depending on the bin; THE BIN's card title drops its blanket "KEPT 30 DAYS"; and a damaged print inside a tombstone the sweep is about to take is skipped, so it cannot stop the sweep that clears it.

## 1. The owner's answer (2026-10-31)

The question: should the bin keep a pad's original for as long as the pad needs it? Today the bin lets every take go 30 days after it went in (`BIN_KEEP_DAYS`, swept at every launch), so a treated pad left alone for 30 days can no longer go back: NONE refuses, and a pad with pedals refuses every pedal change (D5).

**The owner said yes**, to the proposal as put:

- Keep the original of **every treated pad**, not just pedal pads. Undoing an era, a character, a MUTATE or an OUTSIDE reads the same bin.
- **EMPTY THE BIN NOW still empties everything.** It is a choice made on purpose, so the refusals still apply after it.
- The bin screen says the original is kept, for which pad, instead of a day count.
- Everything else in the bin still leaves after 30 days.

## 2. The rule

Every undo door the app has restores the **newest** bin entry under a file name (`restoreFromBin(name)`, used by `unEraPad`, `untreatPad`, `Mutate.undo`, `OutsideSheet.undo` and `Robin.undo`), and `PadSheet.unTreatState` only asks whether the name is in the bin. So what a pad needs is exactly one entry per file it plays: the newest one under that name.

**A pad depends on the bin** when either:

- it is a round-robin chain (`chain != null`; `Robin.undo` reads the bin), or
- it carries a recipe that is neither a printed wrapper (`PrintedPads.has`) nor a plain synth render.
  - A plain synth render is a recipe that parses as a `PadRecipe` with a `patch` and no `pedals` and no `treatment`. The patch alone re-renders it, and no undo door reads the bin for it.
  - *(review)* Except a de-sampled pad: DE-SAMPLE leaves the same recipe, but it stamps the pad `desampled`, and `snipsnap desample --undo` brings the capture back out of the bin. A pad with that stamp counts. A synth send over a de-sampled pad keeps the stamp, so the bin then keeps the render that send replaced, which is what `--undo` would restore.
  - Everything else counts: era, character, keyed, SMEAR, DUST, pedals, MUTATE, OUTSIDE, and the fx-only recipes the CLI's `--undo` commands pop.
  - The test is "any recipe but these two", not a list of recipe readers, so a new treatment is kept without anyone remembering to add it.

**The pads that can claim an entry:**

- **(a)** every live pad in the kit that is not printed;
- **(b)** for every live printed pad (a board or an aquarium print), the pad its saved source describes: the source snapshot's main file, layers and recipe. REMOVE brings that pad back, and its NONE then needs the entry. *(review)* When that pad is itself printed (an aquarium over a board), its own source is followed in turn, down to the pad underneath every print;
- **(c)** for every ejected pad whose tombstone is still in the bin after this sweep, the pad the tombstone holds. BACK brings that pad back with its recipe, and its NONE then needs the entry. *(review)* When the tombstone holds a printed pad, the claimant is the pad underneath it, as in (b): BACK brings the print back, REMOVE brings that pad back, and its NONE then needs the entry.

**An entry is kept** when either:

- by (a) or (b), a claimant depends on the bin, the entry's name is one of its files (main or a velocity layer), the entry is the newest under that name, and the entry has no tombstone of its own; or
- by (c), the tombstone's pad depends on the bin, the entry's name is one of that pad's files, the entry is the newest one under that name that went in **before** the tombstone, and it has no tombstone of its own. It is kept for exactly as long as the tombstone: it leaves in the same sweep. For an ordinary eject the pad's one file is the tombstone's own name; for an ejected print it is the files of the pad underneath.

*(review)* "The newest" is chosen first and the guard applies after it, in every clause. When the newest entry under a name has a tombstone, nothing under that name is kept for that claimant: the rule never falls back to an older entry underneath. That older entry is not what the claimant's NONE would read, and after an EVIL TWINS reroll it is a stranger's.

The tombstone guard matters. Only an eject writes a tombstone; every treatment bins without one. So an entry with a tombstone is a record of an ejected pad, never a live pad's original. A new pad that reuses an old file name (an EVIL TWINS reroll does) must not pin a stranger's sound forever. *(review)* So every eject writes one, a layered or chained pad's too. BACK still reinstates only a single-sample pad without a chain from its tombstone; for any other it brings back the audio alone, as before.

**Every other entry keeps today's clock:** it leaves, with its tombstone, at the first sweep after `BIN_KEEP_DAYS` (still 30, still pinned to `Reversal.DAYS`).

**Fail closed.** The kept set is built in full before the first delete. If it cannot be built (a printed pad's saved source will not read, live or held in a tombstone; a damaged kit), the sweep deletes nothing in that kit and throws; the launch's `runCatching` moves on to the next kit. Deleting an original that a pad needs cannot be undone; keeping a file one more day can. *(review)* A tombstone only stops the sweep while it outlives it: one the sweep takes anyway holds nothing past that moment either, so when the print inside it will not read, it is skipped. A damaged print held in a tombstone therefore stops the sweep for the tombstone's own 30 days at most, not until someone taps BACK or EMPTY THE BIN NOW.

**How the need ends:** the pad's undo uses the entry up, a re-treat bins a fresh original that becomes the newest, or the pad is ejected and the claim passes to its tombstone for 30 more days.

**Cost:** at most one kept file per file a dependent pad plays, plus one per ejected pad still in the bin. A one-second drum hit is about 170 KB.

## 3. What it does not keep

These keep today's 30 days, as before:

- older entries under a kept name (the levels under the newest, which only repeated CLI `--undo` pops reach);
- leftover takes of a pad that no longer depends on the bin (after NONE, for example). The pad sheet's SPLICE and STACK still offer them until they leave;
- what VERSIONS (`restoreTake`) would read: take history stays 30 days, and the takes KDoc stays true;
- what sits under an ejected pad with layers or a chain: its tombstone marks the eject *(review)*, but BACK cannot bring such a pad back, so nothing under it is kept;
- the audio a synth send replaced (a plain synth render does not depend on the bin), except on a de-sampled pad *(review)*;
- anything after EMPTY THE BIN NOW.

## 4. The code

**`shell`, `KitBuilderModel`:**

- `binHolds(keepDays = BIN_KEEP_DAYS, nowMillis = now): List<BinHold>`: every bin entry, newest first, with when the bin lets it go. `nowMillis` only decides which tombstones' unreadable prints can still stop the sweep (fail closed, above); `purgeBin` passes its own. `BinHold(entry, keptFor, purgeAtMillis)`:
  - `keptFor` is the slot of a pad that keeps it (the lowest slot when several do), and `purgeAtMillis` is null;
  - otherwise `keptFor` is null and `purgeAtMillis` is when the sweep may take it: `binnedAtMillis + keepDays`, or the tombstone's own time for an entry kept by (c);
  - `daysLeft(nowMillis)`: whole days left, rounded up so 0 means the next sweep takes it; null while a pad keeps it. This is the one countdown both screens draw, which also ends their old disagreement (the pad sheet rounded to nearest, the bin screen rounded up). *(review)* It clamps before narrowing to an `Int`, and both screens' fallback clocks saturate as `binHolds`' own does, so a forged stamp the sweep never reaches reads as far off, not as a wrapped count.
- `purgeBin(olderThanDays, nowMillis)` keeps its signature and its `Int` return, and deletes exactly the entries whose `purgeAtMillis` is before `nowMillis`. It still never saves and never touches `kit.json`, so the launch call in `App.kt` and ConventionTest's READ_ONLY entry for it stay as they are.
- `emptyBin()` is unchanged.
- **Printed sources** need one new read: from a printed pad's recipe, the source snapshot's main file, layers and recipe, for boards and aquariums both. It reads the snapshot's JSON (checking the snapshot's own hash) without re-hashing every saved audio file, and throws when it cannot read it. *(review)* The rule follows it in a loop, a print over a print down to the pad underneath, and fails closed if the prints loop back on themselves.
- **Tombstones** *(review)*: `moveToBin` writes one for every eject. `binContents` reads it back as a pad snapshot only when BACK can reinstate that pad from this one file (a single-sample pad that played it, with no chain); the guard asks only whether the tombstone file is there.

**`app`:**

- **THE BIN** (`TakesBinScreen`): a kept row shows `KEPT FOR A01` in amber where the day count was, never in the warn colour. Other rows keep `ND LEFT`, now from `daysLeft`. A kept row whose pad can give the entry back itself has **no BACK** (the owner's call, section 6): NONE over a card treatment; for the main file, NONE after SOFT HITS off when soft hits came after the treatment; or, for the main file, MUTATE's or OUTSIDE's UNDO (`PadSheet.hasOwnWayBack`). A print is judged by the pad underneath it (`KitBuilderModel.unprinted`), because a printed pad's NONE removes the print first. Every other row keeps BACK as it was, including a kept row whose pad has no such door on its sheet (a SPLICE, a DE-SAMPLE, a round-robin chain, a treatment only the CLI makes): there BACK is the only way back in the app. When the kept set cannot be built, every row falls back to its countdown and keeps BACK. *(review)* The card's title, `THE BIN — DELETED PAD AUDIO, KEPT 30 DAYS`, becomes `THE BIN — DELETED PAD AUDIO`: each row now says how long, and a blanket 30 days is not true of a kept row.
- **The pad sheet** (`PadSheetScreen`): the provenance line under the waveform reads `original in bin, kept` when the pad's newest entry is kept, and `original in bin, Nd left` otherwise, from the same `daysLeft`. Its six "is it in the bin?" checks (◀ BEFORE, MUTATE and OUTSIDE UNDO, SPLICE, STACK) keep meaning exactly that.
- **Fallback.** If the kept set cannot be built, both screens show plain countdowns from `binnedAtMillis` rather than crash; the sweep keeps everything in that kit, so the files are still there.

**Copy:** one new line, `Copy.binKeptFor(pad)` = `"KEPT FOR $pad"`. It contains no destructive word and no number before DAYS, so ReversalTest and PersonalityTest accept it.

## 5. Tests

`shell` tests, written before the code. Old entries are made the way the existing purge tests do it (`olderThanDays = 0.0`, a `nowMillis` past the entry), or by renaming a bin file to an older `<millis>_<name>` where two entries need different ages.

- A treated pad's original outlives the sweep; NONE (`unEraPad`) then restores it byte for byte. Era, character, SMEAR, DUST, pedals, MUTATE, OUTSIDE, a chain, and an fx-only recipe each keep theirs.
- A pedal pad's next pedal change after the sweep lands (`Landed.Applied`), not `Landed.NotBinned`.
- Only the newest entry under a name is kept; older ones leave.
- A pad after NONE keeps nothing; its leftover takes leave.
- A velocity-layered pad keeps one entry per layer file.
- A plain synth render keeps nothing; a synth pad with pedals keeps its original.
- A printed pad over a treated pad keeps the source's original, under every file the source plays; after REMOVE, NONE works. *(review)* So does an aquarium over a board over a treated pad, after both REMOVEs.
- An ejected treated pad: the original is kept while the tombstone stays, and leaves with it. After BACK, NONE works. *(review)* An ejected board over a treated pad keeps the source's original the same way; after BACK and REMOVE, NONE works.
- A new pad reusing an ejected pad's file name does not keep the ejected pad's entry. *(review)* Nor the entry under it, when the eject record is the newest; nor a layered pad's ejected entries. A tombstone keeps nothing past an earlier eject record and never stretches one, and an untreated pad's tombstone keeps nothing.
- *(review)* A de-sampled pad keeps its capture; `desample --undo` gives it back.
- A printed pad whose saved source is damaged: the sweep deletes nothing in that kit and throws. *(review)* Held in a tombstone, only until the tombstone's days are up; then the sweep takes it, damaged source and all.
- `daysLeft` rounds up, is 0 exactly when the sweep would take the entry, and is null for a kept entry.
- `emptyBin` still empties kept entries.
- *(2026-11-01)* A new pad never takes a name the bin holds (a cleared pad's, a rerolled twin's), and the name comes free once the bin lets it go. The four guard tests that used a reroll to put a new pad on an old name now build a kit as a build from before the fix left it.

androidTests, run on CI's emulator: the pad sheet shows `original in bin, kept` for a treated pad whose original went in more than 30 days ago and was swept, and NONE restores it; and THE BIN shows `KEPT FOR A01` with no BACK on a treated pad's kept row, while a spliced pad's kept row (`KEPT FOR A03`) and an ejected pad's row on the clock both keep their BACK. `shell`: `PadSheet.hasOwnWayBack` is true for NONE over a card treatment (layers included), for the main file under soft hits that came after the treatment, and for MUTATE's or OUTSIDE's UNDO on the main file only; false for a SPLICE, a DE-SAMPLE, an untreated pad, a file not binned, a file the pad does not play, and a layer under late soft hits. A board over a treated pad: the pad under the board has its own way back under every file it plays, and the print itself has none.

## 6. Left alone, on purpose

- **BACK on a kept row: hidden where the pad can undo itself (the owner's call, 2026-10-31).** BACK copies the original over the pad's audio without binning what it replaces, and leaves the pad's recipe. On a kept row that meant the treated sound was gone for good, the card still showed the treatment, and the pad's own undo then refused, because BACK had used the original up. A kept row never leaves, so that tap would have been easy to find. Where the pad's sheet has a door that reads the entry (NONE over a card treatment; MUTATE's or OUTSIDE's UNDO for the main file), that door gives the same original back and takes the recipe off with it, and the row already names the pad, so BACK goes. Routing BACK through each treatment's own undo was the other way; it was turned down because the pad sheet already picks the right undo for every treatment, and a second copy of that choice in THE BIN is where the two would drift.
  - **Prints and late soft hits.** A print over a treated pad is judged by the pad underneath: the printed pad's NONE removes the print, and the next NONE reads the entry. BACK there would be worse than elsewhere: the print still plays from that file's saved source, so overwriting the file makes REMOVE refuse for good. Soft hits added after a treatment make NONE refuse with "GHOSTS CAME AFTER THE TREATMENT. CLEAR THEM FIRST."; SOFT HITS off, then NONE, gives the main file's entry back, so that row loses BACK too.
  - **Where BACK stays on a kept row.** A SPLICE, a DE-SAMPLE (MAKE SYNTH), a round-robin chain, and a treatment only the CLI makes all have no door on the pad sheet that reads the entry. The review that followed the owner's yes found this: the yes rested on "NONE gives the same original back", which is false for those pads, and hiding BACK there would have left their original kept forever with no way back in the app. So their kept rows read `KEPT FOR A03` and keep BACK, with the trap BACK has always had. Giving SPLICE and DE-SAMPLE their own undo on the sheet would let BACK go there too; that is a separate change.
- **BACK on an older row under a live pad's name** stays as it was: it also copies over the pad's audio and leaves the recipe. On a pad no print covers, the newest entry, kept, is still there, so the pad's NONE still works afterwards. Under a print it does not: the older row's BACK overwrites the file the print's saved source names, as BACK on the kept row would, and REMOVE then refuses for good. That predates this change; guarding older rows, or every row under a print, is a separate question.
- **File names that come back: fixed (the owner's call, 2026-11-01).** `nextStem` now passes any name the bin holds, as well as the live pads' names and the files on disk, so an EVIL TWINS reroll names its new twins past the old twins' eject records, and a new pad's NONE never finds a stranger's audio. Once the bin lets an entry go, its name is free again. The tombstone guard stays, for bins and kits from before the fix.
- **VERSIONS and eject records** *(review)*. `restoreTake` copies a bin entry back without consuming it. When the entry it picks is an eject record and the restored kit makes that pad live again, the pad's newest entry is its own eject record, tombstone intact. The guard then keeps nothing for it, and its original leaves with that tombstone, 30 days after the eject. The guard cannot tell this from a reroll (the bin looks the same), so it is a known limit, not a rule; fixing it belongs in `restoreTake`. That pad's NONE already read the eject record's treated audio rather than the original, before this change.
- **The launch sweep's lock.** It still runs outside `KitWrites.mutex`. It decides from the kit it loaded a moment before, and a write in that moment only adds newer entries, which the sweep never sees.
