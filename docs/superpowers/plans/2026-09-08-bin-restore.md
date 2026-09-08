# Deleted Kits: Restore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the kit bin the restore path its own copy currently has to apologize for — a DELETED KITS screen that lists what's sleeping in `Kits/.bin/`, restores a kit to the shelf, and empties the bin early.

**Architecture:** Task 1 adds the read/restore side to `KitShelf` (`binnedKits()`, `restoreKit()`, `emptyKitBin()`), named to mirror the Rooms wrappers that already sit beside them (`binnedRooms()`, `restoreRoom()`, `sweepRooms()`). Task 2 builds `DeletedKitsScreen` on `TakesBinScreen`'s established shape (list + RESTORE per row + a two-tap armed EMPTY THE BIN NOW), wires it as a shelf-level overlay exactly like SNIPS, and — because a restore path now exists — corrects the delete copy and the KDoc that currently state, truthfully as of today, that it doesn't.

**Tech Stack:** existing `KitShelf`/`KitStore`/`Rooms`/`ShelfImport` helpers; Jetpack Compose.

## Global Constraints

- **Never auto-delete user work.** Nothing in this plan may delete a live kit. `emptyKitBin()` deletes only inside `binDir`, and only on an explicit armed second tap.
- **The original name comes from `kit.json`, never from the folder name.** `deleteKit` names the bin folder `"${entry.dir.name}-$nowMillis"` (and `"${name}-$n-$nowMillis"` on a same-millisecond collision), so `substringBeforeLast('-')` is ambiguous for any kit whose own name contains a hyphen, and wrong for the collision form. The binned directory still contains its whole `kit.json`, so `KitStore.load(dir).name` is lossless and authoritative. Folder-name parsing is for the TIMESTAMP only.
- Age is read off the trailing `-<millis>` stamp, the same wall-clock `System.currentTimeMillis()` value `sweepDeletedKits` already parses — **extract that parse into one shared helper so listing and sweeping can never disagree about a kit's age.**
- Every kit-directory mutation goes through `KitWrites.mutex.withLock { }` — restore moves a whole directory and is no exception.
- House voice for new strings: short, caps, honest. No promise the code doesn't keep.
- Concurrent-session repo: `git add` only files you touched, never `-A` or `.`. Never touch `FOLLOWUP-REPORT.md` or `spike/`.
- Commit trailers on every commit:
  ```
  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01MDVos8CfKQ4ewvwrcNUhrS
  ```
- Gate: `./gradlew test :app:compileDebugKotlin` green.

---

## Task 1: The bin's read and restore side (`KitShelf`)

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/KitShelf.kt`

**Interfaces:**
- Consumes: `binDir` (`KitShelf.kt:50`), `moveDir` (`:323`), `KitStore.load`/`KitStore.save`, `Names`, and `ShelfImport.moveOntoShelf`'s collision idiom already mirrored inside `renameKit` (`:291`).
- Produces:
  ```kotlin
  /** A kit asleep in the bin: where it lies, what it was called, when it went, when it goes for good. */
  data class BinnedKit(
      val dir: File,
      val name: String,          // from kit.json — authoritative, NOT parsed off dir.name
      val padCount: Int,
      val binnedAtMillis: Long,
      val daysLeft: Int,
  )

  fun binnedKits(nowMillis: Long = System.currentTimeMillis()): List<BinnedKit>   // newest-binned first
  fun restoreKit(binned: BinnedKit): Entry?    // null if the dir is gone or the move fails
  fun emptyKitBin(): Int                       // deletes every bin entry NOW; returns how many went
  ```

**Locked behavior:**
- `binnedKits()` lists `binDir`'s directories, and for each reads its `kit.json` via `KitStore.load(dir)` for `name` + `padCount`. A directory whose `kit.json` is missing or unreadable is **skipped entirely, not guessed at** — a bin entry we can't describe honestly isn't shown (same honesty rule the SNIPS USED badge follows). `binnedAtMillis` comes from the shared timestamp helper below; `daysLeft` = `BIN_DAYS - elapsedDays`, floored at 0.
- **Extract the age parse** currently inlined in `sweepDeletedKits` (`:264-271`, `dir.name.substringAfterLast('-').toLongOrNull() ?: dir.lastModified()`) into a `private fun binnedAt(dir: File): Long`, and call it from BOTH `sweepDeletedKits` and `binnedKits`. Do not copy the expression — the two must not be able to drift, and `sweepDeletedKits`'s existing KDoc already explains at length why this stamp (not `lastModified()`) is the honest source.
- `restoreKit(binned)` moves the directory back to `File(root, <name>)` where `<name>` is `binned.name` sanitized to a safe folder name. A collision with a kit already on the shelf falls back **exactly like `renameKit` does** — "NAME 2", "NAME 3", … — and `kit.json`'s own name is rewritten to whatever name actually landed, so the two never disagree. (Read `renameKit`'s body and reuse its approach; if the collision loop is worth sharing, factor it out rather than duplicating it.) Returns the new `Entry`, or null if `binned.dir` is gone or the move fails.
- `emptyKitBin()` deletes every child of `binDir` via `deleteRecursively()`, returning the count. It must never touch anything outside `binDir`.

- [ ] **Step 1: Implement `BinnedKit`, `binnedAt` (shared), `binnedKits`, `restoreKit`, `emptyKitBin`** per the locked behavior, each modeled on its named neighbor (`binnedRooms`/`restoreRoom`/`sweepRooms` at `:78-81`, `renameKit` at `:291`).
- [ ] **Step 2: Repoint `sweepDeletedKits` at the shared `binnedAt` helper** — behavior identical, expression no longer duplicated.
- [ ] **Step 3: Compile** — `./gradlew :app:compileDebugKotlin`.
- [ ] **Step 4: Reason through** (report): a bin entry with unreadable `kit.json` is skipped, not shown as "UNKNOWN"; restoring into a name collision lands "NAME 2" with `kit.json` rewritten to match; `emptyKitBin` on an empty/absent bin returns 0 without throwing; a kit whose name contains a hyphen round-trips its name correctly (this is the case folder-name parsing would have broken).
- [ ] **Step 5: Commit** — `feat(kit): the bin can be read, restored from, and emptied`.

---

## Task 2: DELETED KITS screen, and the copy that can finally stop apologizing

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/ui/DeletedKitsScreen.kt`
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/KitsScreen.kt` (entry point + the delete-confirm string + stale KDoc at `:470`)
- Modify: `app/src/main/kotlin/com/snipsnap/app/App.kt` (overlay state + wiring)
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Personality.kt` (`Copy.kitDeleted` + stale KDoc at `:240`)

**Interfaces:**
- Consumes: `KitShelf.binnedKits()`/`restoreKit()`/`emptyKitBin()` from Task 1; `KitWrites.mutex`; the `snipsOpen` shelf-overlay pattern in `App.kt`; `TakesBinScreen.kt`'s armed-confirm constants/idiom (`EMPTY_BIN_ARM_MS = 3_000L` at `:51-52`, the self-disarming `LaunchedEffect` at `:112-117`, the armed button label swap at `:436`).
- Produces: `DeletedKitsScreen(shelf: KitShelf, onBack: () -> Unit, onToast: (String) -> Unit, onRestored: (KitShelf.Entry) -> Unit)`.

**Locked behavior:**
- Header: `DELETED KITS` + count. Empty state: plain — `NOTHING DELETED.` (match the SNIPS empty-state tone, not the tape-metaphor voice).
- Row per binned kit: name, pad count, when it went ("3 DAYS AGO" — reuse whatever relative-time helper SNIPS already uses; grep before writing a new one), and **how long it has left** ("27 DAYS LEFT"). A kit at 0 days left still shows and still restores — the sweep, not the screen, decides when it's gone.
- `RESTORE` per row → runs `restoreKit` under `KitWrites.mutex` on IO → toast the name it actually landed under (which may differ from the requested name after a collision — say the real one, never the requested one) → remove from the list → `onRestored(entry)` so the shelf refreshes.
- `EMPTY THE BIN NOW — NO TAKEBACKS`: **two-tap armed confirm copied from `TakesBinScreen`** (first tap arms and swaps the label to `TAP AGAIN TO CONFIRM — NO TAKEBACKS`; it self-disarms after `EMPTY_BIN_ARM_MS`). Second tap calls `emptyKitBin()` under the mutex, toasts the count, empties the list. Hide this button entirely when the bin is empty.
- Entry point: a `DELETED KITS ▸` row in `KitsScreen`'s existing shelf action area, beside `SNIPS ▸`/`BACKUP` — **shown only when `binnedKits()` is non-empty**, so a user who has never deleted a kit never sees a door to an empty room.
- `App.kt`: `var deletedKitsOpen by remember { mutableStateOf(false) }`, mirroring `snipsOpen` — declared, reset in `MenuRow.onSelect`'s reset block, and rendered at the `AppScreen.KITS` level, exactly as SNIPS is.

**The copy correction (this is the point of the feature, not a footnote):**

The delete confirm and toast currently say the kit is *"OFF THE SHELF NOW, GONE FOR GOOD IN 30 DAYS."* That wording was chosen deliberately and correctly **because no restore path existed** — the earlier "IT GOES TO THE BIN" phrasing borrowed a noun that, everywhere else in this app, means *restorable*. Shipping this feature makes the softer promise true, so the copy should now keep it:

- `KitsScreen`'s delete-confirm dialog → `DELETE THIS KIT? IT WAITS IN DELETED KITS FOR 30 DAYS.`
- `Copy.kitDeleted` → `$NAME IS OFF THE SHELF. 30 DAYS TO CHANGE YOUR MIND.`
- Update the KDoc at `KitsScreen.kt:470` and `Personality.kt:240` — both currently explain, accurately as of today, that a deleted kit has *neither a restore UI nor an empty-now action* unlike Rooms/Takes+Bin. That is exactly what this task ships, so those comments must be rewritten to describe the new state, not left as fossils contradicting the code beside them.

- [ ] **Step 1: Implement `DeletedKitsScreen`** per the locked behavior.
- [ ] **Step 2: Wire the entry point + overlay state** in `KitsScreen.kt`/`App.kt`, mirroring SNIPS.
- [ ] **Step 3: Apply the copy correction + KDoc rewrites** in `KitsScreen.kt` and `Personality.kt`.
- [ ] **Step 4: Compile** — `./gradlew :app:compileDebugKotlin`.
- [ ] **Step 5: Reason through** (report): bin empty → no entry point on the shelf at all; restore of a kit whose name now collides toasts the name that actually landed; EMPTY arms, self-disarms after 3s, and only deletes on a genuine second tap; restoring the LAST kit in the bin makes the entry point disappear on return to the shelf; a restored kit opens normally from the shelf afterward.
- [ ] **Step 6: Commit** — `feat(kit): DELETED KITS — restore what you binned, or empty it early`.

---

## Self-Review (done)

Spec coverage: list ✓, restore ✓, empty-early ✓, entry point ✓ — the three things the whole-branch review named as missing. Name recovery reads `kit.json` rather than parsing the folder, closing the hyphenated-name ambiguity that folder parsing would have introduced ✓. The age parse is extracted and shared rather than duplicated, so listing and sweeping cannot disagree ✓. Restore collision reuses `renameKit`'s proven fallback instead of a second invented scheme ✓. Destructive empty-now copies `TakesBinScreen`'s armed two-tap rather than a bare button ✓. Mutex held on both mutating paths ✓. Honesty rule applied twice: an undescribable bin entry is skipped rather than guessed, and the delete copy is corrected in the same commit that earns the softer promise — the feature and the sentence about it ship together, so neither can lie about the other ✓. Type consistency: `BinnedKit`/`binnedKits`/`restoreKit`/`emptyKitBin` used identically in Tasks 1 and 2 ✓.
