# The bin keeps what a pad still needs

**Status:** design, 2026-10-31, with the owner's answer in section 1. Built in the same round: the rule in `shell` with tests, the two screens in `app` (compiled and run by CI).
**Answers:** the open question in the pedal slate's D5 (`2026-10-28-pedal-slate-design.md`, section 3 and section 10).
**Who this is for:** the owner first, then the implementing session. Plain words for what a user sees; code names only where the next step has to go there.

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
  - Everything else counts: era, character, keyed, SMEAR, DUST, pedals, MUTATE, OUTSIDE, and the fx-only recipes the CLI's `--undo` commands pop.
  - The test is "any recipe but these two", not a list of recipe readers, so a new treatment is kept without anyone remembering to add it.

**The pads that can claim an entry:**

- **(a)** every live pad in the kit that is not printed;
- **(b)** for every live printed pad (a board or an aquarium print), the pad its saved source describes: the source snapshot's main file, layers and recipe. REMOVE brings that pad back, and its NONE then needs the entry;
- **(c)** for every ejected pad whose tombstone is still in the bin after this sweep, the pad the tombstone holds. BACK brings that pad back with its recipe, and its NONE then needs the entry.

**An entry is kept** when either:

- by (a) or (b), a claimant depends on the bin, the entry's name is one of its files (main or a velocity layer), the entry is the newest under that name, and the entry has no tombstone of its own; or
- by (c), the tombstone's pad depends on the bin, and the entry is the newest one under the tombstone's name that went in **before** the tombstone and has no tombstone of its own. It is kept for exactly as long as the tombstone: it leaves in the same sweep.

The tombstone guard matters. Only an eject writes a tombstone; every treatment bins without one. So an entry with a tombstone is a record of an ejected pad, never a live pad's original. A new pad that reuses an old file name (an EVIL TWINS reroll does) must not pin a stranger's sound forever.

**Every other entry keeps today's clock:** it leaves, with its tombstone, at the first sweep after `BIN_KEEP_DAYS` (still 30, still pinned to `Reversal.DAYS`).

**Fail closed.** The kept set is built in full before the first delete. If it cannot be built (a printed pad's saved source will not read, a damaged kit), the sweep deletes nothing in that kit and throws; the launch's `runCatching` moves on to the next kit. Deleting an original that a pad needs cannot be undone; keeping a file one more day can.

**How the need ends:** the pad's undo uses the entry up, a re-treat bins a fresh original that becomes the newest, or the pad is ejected and the claim passes to its tombstone for 30 more days.

**Cost:** at most one kept file per file a dependent pad plays, plus one per ejected pad still in the bin. A one-second drum hit is about 170 KB.

## 3. What it does not keep

These keep today's 30 days, as before:

- older entries under a kept name (the levels under the newest, which only repeated CLI `--undo` pops reach);
- leftover takes of a pad that no longer depends on the bin (after NONE, for example). The pad sheet's SPLICE and STACK still offer them until they leave;
- what VERSIONS (`restoreTake`) would read: take history stays 30 days, and the takes KDoc stays true;
- ejected pads with layers or a chain, which get no tombstone;
- the audio a synth send replaced (a plain synth render does not depend on the bin);
- anything after EMPTY THE BIN NOW.

## 4. The code

**`shell`, `KitBuilderModel`:**

- `binHolds(keepDays = BIN_KEEP_DAYS): List<BinHold>`: every bin entry, newest first, with when the bin lets it go. `BinHold(entry, keptFor, purgeAtMillis)`:
  - `keptFor` is the slot of a pad that keeps it (the lowest slot when several do), and `purgeAtMillis` is null;
  - otherwise `keptFor` is null and `purgeAtMillis` is when the sweep may take it: `binnedAtMillis + keepDays`, or the tombstone's own time for an entry kept by (c);
  - `daysLeft(nowMillis)`: whole days left, rounded up so 0 means the next sweep takes it; null while a pad keeps it. This is the one countdown both screens draw, which also ends their old disagreement (the pad sheet rounded to nearest, the bin screen rounded up).
- `purgeBin(olderThanDays, nowMillis)` keeps its signature and its `Int` return, and deletes exactly the entries whose `purgeAtMillis` is before `nowMillis`. It still never saves and never touches `kit.json`, so the launch call in `App.kt` and ConventionTest's READ_ONLY entry for it stay as they are.
- `emptyBin()` is unchanged.
- **Printed sources** need one new read: from a printed pad's recipe, the source snapshot's main file, layers and recipe, for boards and aquariums both. It reads the snapshot's JSON (checking the snapshot's own hash) without re-hashing every saved audio file, and throws when it cannot read it.

**`app`:**

- **THE BIN** (`TakesBinScreen`): a kept row shows `KEPT FOR A01` in amber where the day count was, never in the warn colour. Other rows keep `ND LEFT`, now from `daysLeft`. BACK is unchanged (section 6).
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
- A printed pad over a treated pad keeps the source's original; after REMOVE, NONE works.
- An ejected treated pad: the original is kept while the tombstone stays, and leaves with it. After BACK, NONE works.
- A new pad reusing an ejected pad's file name does not keep the ejected pad's entry.
- A printed pad whose saved source is damaged: the sweep deletes nothing in that kit and throws.
- `daysLeft` rounds up, is 0 exactly when the sweep would take the entry, and is null for a kept entry.
- `emptyBin` still empties kept entries.

androidTests, run on CI's emulator: the pad sheet shows `original in bin, kept` for a treated pad whose original went in more than 30 days ago and was swept, and NONE restores it.

## 6. Left alone, on purpose

- **BACK on a kept row** still does what it does today: it copies the original over the pad's audio and leaves the pad's recipe. A kept row never leaves now, so that tap is easier to find. Routing it through the pad's own undo (so the card agrees with the sound) is a separate question for the owner.
- **File names that come back.** `nextStem` does not look in the bin, so a new pad can reuse an ejected pad's name. The tombstone guard stops that pinning a stranger's sound, but a twin with a copied recipe can still read as undoable. That predates this change.
- **The launch sweep's lock.** It still runs outside `KitWrites.mutex`. It decides from the kit it loaded a moment before, and a write in that moment only adds newer entries, which the sweep never sees.
