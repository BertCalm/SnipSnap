# File Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three things: (1) make the bin's already-promised 30-day expiry actually run, and sweep orphaned staging/cache dirs, at launch; (2) a SNIPS shelf — list, play, assign-to-pad, open-in-TAPE, delete, with total size shown; (3) kit delete (via the per-kit-bin pattern, recoverable) and kit rename.

**Architecture:** (1) is a one-line addition beside the existing `sweepRooms()` launch call. (2) is a new `SnipsScreen` (App-level overlay, same state pattern as `padCaptureSlot`/`grainFieldSlot`) backed by a small new `SnipStore.delete` + duration/size helpers; assigning an existing snip to a pad reuses `KitBuilderModel.assign` directly (skip the ring/grabOneShot DSP — this is "load an existing WAV" not "grab from the mic"). (3) copies `Rooms.forget`'s bin-move pattern for delete and `ShelfImport.moveOntoShelf`'s rename-with-fallback for rename, both added to `KitShelf`.

**Tech Stack:** Kotlin; existing `KitBuilderModel`/`KitStore`/`SnipStore`/`WavReader`/`Cleanup`; Compose; `kotlin.test`+JUnit5 for `:shell`/`:kit`.

## Global Constraints

- `eval "$(fnm env)" && fnm use 20` before gradle. `:app` has no unit tests (compile+review gate); `:shell`/`:kit` tasks get TDD.
- **No auto-delete of user work, ever.** Kit deletion goes through the per-kit bin (recoverable, 30 days, same as pad ejects). Snip deletion is confirm-then-gone (per the approved design — snips have no bin). The launch sweep only ever purges things ALREADY marked for deletion past their promised window (bin) or is pure derived/cache data (exports/share/orphaned staging) — never a live kit or live snip.
- The "never used" badge on a snip is shown ONLY when computable and true; when the underlying pad doesn't carry `source["file"]` (all pre-existing pads, and any pad assigned before Task 2's source-tagging lands), the badge is simply omitted — never guessed, never defaulted to "used" or "unused."
- `git add` only touched files, never `-A`. Leave FOLLOWUP-REPORT.md / spike/ alone. Commit trailers:
  ```
  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01MDVos8CfKQ4ewvwrcNUhrS
  ```
- Concurrent-session repo: re-check `git fetch`/merge status before starting if this plan is picked up much later — other sessions are active on this repo.

---

## Task 1: Keep the bin's promise + sweep orphaned/derived storage at launch

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/App.kt` (the launch `LaunchedEffect(Unit)` block, ~line 353-357)

**Interfaces:**
- Consumes: `KitBuilderModel.purgeBin(olderThanDays: Double = BIN_KEEP_DAYS, nowMillis: Long = ...): Int` (`KitBuilder.kt:714`); `KitStore.list(root): List<File>`; `KitBuilderModel.open(dir)`; the existing `shelf.sweepRooms()` call this sits beside; `ShelfImport.STAGING_DIR` (".landing") constant; `ShareInbox`'s `cacheDir/landing/` path; export/share cache paths (`getExternalFilesDir("exports")`, `cacheDir/share/`).
- Produces: nothing new exported — this is pure launch-time housekeeping.

- [ ] **Step 1: Purge every kit's bin at launch, alongside sweepRooms**

In `App.kt`'s existing launch effect (find the exact block via the `sweepRooms()` call), add immediately after it, still inside the same `withContext(Dispatchers.IO)`:
```kotlin
// Same promise as sweepRooms() above, for pad ejects: BIN_KEEP_DAYS was
// always the intent (KitBuilder.kt's own docs), but nothing ever called
// purgeBin() outside the manual "EMPTY THE BIN NOW" button — every ejected
// pad has been living forever. This is the fix: run it once per launch,
// per kit, same as Rooms already does for itself.
runCatching {
    KitStore.list(shelf.root).forEach { kitDir ->
        runCatching { KitBuilderModel.open(kitDir).purgeBin() }
    }
}
```
(Import `KitStore`/`KitBuilderModel` if not already imported in App.kt — they likely are, given existing kit-open call sites.)

- [ ] **Step 2: Sweep orphaned import staging + derived caches**

Add a small private helper in App.kt (or a new tiny file `app/.../StorageSweep.kt` if App.kt is already large — check its line count first; prefer the new file if App.kt exceeds ~1000 lines):
```kotlin
/**
 * Orphaned staging from a crashed/killed import, and regenerable derived
 * output (exports, share-sheet temp copies) — never a live kit or snip.
 * Age is judged by [File.lastModified], NOT any name-embedded timestamp:
 * ShelfImport's ".landing-<nanoTime>" suffix is monotonic process time, not
 * wall-clock, so it cannot be parsed as an age.
 */
private const val ORPHAN_MAX_AGE_MS = 24L * 60 * 60 * 1000  // 1 day — a crashed import, not an in-progress one

private fun sweepOrphanedStorage(filesDir: File, cacheDir: File, externalFilesDir: File?) {
    val now = System.currentTimeMillis()
    fun deleteIfStale(dir: File) {
        if (dir.exists() && now - dir.lastModified() > ORPHAN_MAX_AGE_MS) {
            dir.deleteRecursively()
        }
    }
    // ShelfImport's crashed-mid-import staging dirs.
    filesDir.listFiles { f -> f.isDirectory && f.name.startsWith(".landing-") }?.forEach(::deleteIfStale)
    // ShareInbox's staging.
    File(cacheDir, "landing").listFiles { f -> f.isDirectory }?.forEach(::deleteIfStale)
    // Share-sheet temp copies — fully regenerable on next share.
    File(cacheDir, "share").listFiles()?.forEach(::deleteIfStale)
    // NOTE: exports (getExternalFilesDir("exports")) are NOT swept — a
    // user may have exported an .xpn specifically to keep/move it
    // elsewhere; sweeping it would delete something they asked to KEEP.
}
```
Call it from the same launch effect: `sweepOrphanedStorage(filesDir, cacheDir, getExternalFilesDir(null))` (adjust params to what's actually in scope at that point — App.kt's Composable has `context` available via `LocalContext.current` already used elsewhere in the file for similar calls; match that pattern).

- [ ] **Step 2b: Correct the exports call if wrong.** Re-read the note in Step 2 — exports must NOT be swept. If you find yourself tempted to add an export sweep because "it accumulates forever," don't: a user-initiated export is the user's file now, not app-internal derived cruft, even though nothing in-app currently surfaces it. (This is a deliberate scope line, not an oversight — flag it in your report if you think it should be revisited, but don't implement a sweep here.)

- [ ] **Step 3: Compile + reason through**

`./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL. In your report: confirm `purgeBin()` is idempotent/safe to call on a kit with an empty or nonexistent `.bin/` (read its implementation to verify — it should be a no-op, not a crash, when there's nothing to purge). Confirm the orphan sweep can't touch a `.landing-*` dir from an import that's genuinely still in progress (1-day threshold vs. any realistic import duration).

- [ ] **Step 4: Commit** — `fix(storage): the bin's 30-day promise actually runs, orphaned staging gets swept`.

---

## Task 2: `SnipStore` gains delete + size/duration, and pads gain source-tagging

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/SnipStore.kt`
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/PadCaptureScreen.kt` (thread `source["file"]` through the existing assign calls — see brief note below)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/SnipStoreTest.kt` (extend existing)

**Interfaces:**
- Consumes: existing `SnipStore.list(root): List<File>`; `WavReader.read(file): Snip` (for duration — full decode is the only way today, no header peek exists; accept this cost, it only runs when the SNIPS shelf is opened, not per-frame).
- Produces:
  ```kotlin
  object SnipStore {
      // existing: commit, import, list, newest — unchanged
      fun delete(file: File): Boolean   // straight File.delete(), returns success
      data class Info(val file: File, val sizeBytes: Long, val capturedAtMillis: Long)
      fun listWithInfo(root: File): List<Info>   // list() + file size + parsed timestamp from filename, NO audio decode (cheap)
  }
  ```
  Duration is deliberately NOT part of `Info` — it requires a full decode; the SNIPS screen computes it lazily per-row (or on play) via `WavReader.read`, not upfront for the whole list.

- [ ] **Step 1: Write failing tests**

```kotlin
@Test
fun `delete removes the file and reports success`() {
    val dir = Files.createTempDirectory("snips").toFile()
    val f = SnipStore.commit(FloatArray(4_410) { 0.1f }, 44_100, dir, 1_000L)
    assertTrue(f.exists())
    assertTrue(SnipStore.delete(f))
    assertFalse(f.exists())
}

@Test
fun `delete on an already-gone file returns false, not a throw`() {
    val dir = Files.createTempDirectory("snips").toFile()
    val ghost = File(dir, "snip_999.wav")
    assertFalse(SnipStore.delete(ghost))
}

@Test
fun `listWithInfo carries size and the captured timestamp, newest first`() {
    val dir = Files.createTempDirectory("snips").toFile()
    val a = SnipStore.commit(FloatArray(4_410) { 0.1f }, 44_100, dir, 1_000L)
    val b = SnipStore.commit(FloatArray(8_820) { 0.1f }, 44_100, dir, 2_000L)
    val info = SnipStore.listWithInfo(dir)
    assertEquals(listOf(b, a), info.map { it.file })
    assertTrue(info.all { it.sizeBytes > 0 })
    assertEquals(2_000L, info.first().capturedAtMillis)
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :shell:test --tests "com.snipsnap.shell.SnipStoreTest"` → FAIL.

- [ ] **Step 3: Implement** `delete`/`Info`/`listWithInfo` in `SnipStore.kt`. `capturedAtMillis` parses the same filename convention `list()`/`newest()` already sort by (read that sort logic — it's numeric, not lexical — and reuse the exact same parse for `capturedAtMillis` so they never disagree).

- [ ] **Step 4: Green + full `:shell:test`.**

- [ ] **Step 5: Source-tagging (so "never used" becomes true going forward).** In `PadCaptureScreen.kt`, find the `commitToPad`/assign call sites (GRAB and HOLD's shared commit path, from the capture-to-pad work) and add `source = mapOf("file" to /* the snip/ring origin if this commit came from an existing snip file — see Task 3 */ )` — for THIS task, only handle the case Task 3 introduces (assigning an EXISTING snip file to a pad): when the source snip is a real `File` (not a live ring snapshot), pass `KitBuilderModel.assign(slot, snip, cls, name, source = mapOf("file" to sourceFile.name))` if `assign` accepts a source param (check its signature — `KitBuilder.kt:61`; if it doesn't take source today, that's a small signature addition here, defaulted to `emptyMap()` so every other caller is unaffected). GRAB/HOLD from the live mic ring have no filename to tag — leave those uncommented as still-untaggable (they capture directly from the ring, never touching a snip file), which is honest: only snip-shelf-assigned pads get the "used" tracking in v1.

- [ ] **Step 6: Compile `:app`, run `:shell:test` full suite.**

- [ ] **Step 7: Commit** — `feat(snips): delete, size/timestamp listing, and source-tagging for pad assigns`.

---

## Task 3: The SNIPS shelf screen

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/ui/SnipsScreen.kt`
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/KitsScreen.kt` (entry button)
- Modify: `app/src/main/kotlin/com/snipsnap/app/App.kt` (overlay state + wiring)

**Interfaces:**
- Consumes: `SnipStore.listWithInfo(root): List<SnipStore.Info>`, `SnipStore.delete(file)`; `WavReader.read(file): Snip` + `Cleanup.toMono` (the exact pattern `TapeScreen.readMono` already uses, `TapeScreen.kt:253` — copy it, don't reinvent) for duration + for building a `Snip` to assign; `KitBuilderModel.open(dir)`/`.assign(...)`/`.save()` under `KitWrites.mutex` (the app-wide kit-write lock from the hardening pass — EVERY kit save in this app now goes through it); `TapeVoice`-style or simpler one-shot playback (reuse whatever the app's existing "play an arbitrary WAV file" mechanism is — check `PadPlayer`/`TapeVoice` for the simplest fit; a SNIPS row just needs play/stop, no scrubbing).
- Produces: `SnipsScreen(shelf: KitShelf, onBack, onToast, onOpenInTape: (File) -> Unit, onPickPadFor: (File) -> Unit)`.
  **→ PAD MUST navigate, not gate on an already-open kit.** SNIPS lives on the shelf
  (`AppScreen.KITS`), where `open` is null by definition — a "requires an open kit"
  restriction would make the button permanently disabled on the screen's own natural
  entry path (shelf → SNIPS ▸ → → PAD). That is the exact TAPE/CHOP kit-gate bug
  fixed earlier this project, reintroduced by hand; do not repeat it. Wiring:
  `onPickPadFor(file)` closes SNIPS, stashes the picked snip file in App-level state
  (e.g. `var pendingSnipAssign by remember { mutableStateOf<File?>(null) }`), and
  routes to the shelf's kit list with a header hint ("PICK A KIT FOR THIS SNIP");
  picking a kit opens it, and a further hint/mode on `KitScreen` routes the NEXT
  empty-pad long-press straight into assigning `pendingSnipAssign` instead of
  opening the normal capture surface (v1: empty pads only, matching this task's
  badge/assign scope), then clears the pending state and returns to KIT with the
  pad filled. If that hand-off is more wiring than fits this task cleanly, the
  fallback is moving the SNIPS entry point itself so it is ALSO reachable from
  inside an open KIT (not only the shelf) — choose whichever is simpler to
  implement correctly, but → PAD must be reachable and functional on some real
  path before this task is done. A permanently-disabled button is not an
  acceptable v1.

**Screen behavior (locked):**
- Header: `SNIPS` + total count + total size (sum of `Info.sizeBytes`, human-readable — reuse any existing byte-formatting helper in the repo; grep for one before writing a new one).
- Row per snip: relative time (reuse whatever "2h ago"/"yesterday" formatting exists elsewhere — grep Personality.kt/Copy for a relative-time helper before writing a new one), size, a play/stop toggle, `→ PAD` (always enabled — navigates per the wiring above, never gated on an already-open kit), `→ TAPE` (calls `onOpenInTape(file)` — wire this to however TAPE already accepts `lastCommitSource`-style file overrides, or the simplest available "load this file into TAPE" path), `DELETE` (confirm dialog — "DELETE THIS SNIP? CAN'T UNDO." / cancel / delete — then `SnipStore.delete` + toast + remove from the list).
- "USED" badge: only shown for a snip when at least one pad across ALL kits has `source["file"] == thatSnip.file.name`. Since Task 2 only tags NEW snip-shelf assigns (pre-existing pads never carry this), a full per-kit scan will return nothing useful until pads accumulate through this new path — so cheapen it: first check (on IO, once, at screen open) whether ANY pad in ANY kit carries a non-empty `source["file"]` at all; if none do, skip the per-snip matching entirely and render with no badges rather than paying a full shelf read for a guaranteed-empty result. Omit the badge entirely (no badge either way) rather than guess for older data — per the Global Constraints honesty rule.
- Empty state: plain — "NO SNIPS YET" (not the tape-metaphor voice; match the language-pass tone).
- KitsScreen entry: a `SNIPS ▸` button beside the existing shelf actions (near BACKUP, `KitsScreen.kt:168-174` per the interface doc) wired to a new `onSnips: () -> Unit` param.
- App.kt: `var snipsOpen by remember { mutableStateOf(false) }`, added to whatever reset list exists for shelf-level overlays (mirror the `padCaptureSlot`/`grainFieldSlot` pattern but at the `AppScreen.KITS` level, not KIT-scoped, per the interface doc's note that this entry point sits one level up). Render `SnipsScreen(...)` when true, `onBack = { snipsOpen = false }`.

- [ ] **Step 1: Implement the screen + wiring per locked behavior.**
- [ ] **Step 2: Compile** — `./gradlew :app:compileDebugKotlin`.
- [ ] **Step 3: Reason through** (report): open with zero snips → empty state; play toggles correctly (stop-on-second-tap, stop-on-navigate-away via DisposableEffect); delete → confirm → gone → list updates + size total recomputes; → PAD path under the v1 restriction; the USED-badge scan doesn't block the screen opening (runs on IO, shows list first with badges arriving/updating, or blocks briefly — your call, note which).
- [ ] **Step 4: Commit** — `feat(app): SNIPS shelf — list, play, assign, delete every catch`.

---

## Task 4: Kit delete (via per-kit-style bin) + rename

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/KitShelf.kt` (new `deleteKit`/`renameKit`)
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/KitsScreen.kt` (`KitRow` long-press → reveal DELETE/RENAME, copying `RoomRow`'s gesture template)
- Modify: `app/src/main/kotlin/com/snipsnap/app/App.kt` (wire the new actions + `sweepRooms()`-style call site for a shelf-level deleted-kits sweep — see Step 3)
- Test: if `KitShelf` has an existing test file in `:app`, none exists per house convention (no `:app` unit tests) — this task is compile+review gated like other `:app` work; put any pure-logic pieces (e.g. a name-collision resolver) in `:shell`/`:kit` with tests if you factor them out there, otherwise it's fine inline.

**Interfaces:**
- Consumes: `Rooms.forget`/`Rooms.sweepBin` (`Rooms.kt:173,211`) as the exact template for "move to a bin, sweep old entries later"; `ShelfImport.moveOntoShelf` (`ShelfImport.kt:296-318`) as the exact template for rename-with-collision-fallback (`renameTo` → `copyRecursively`+delete fallback → `KitStore.save(kit.copy(name=...), dest)`); `Names.isMpcSafe`/`Names.sanitizeStem` for validating a new name; `KitStore.list`/`KitStore.save`.
- Produces:
  ```kotlin
  // KitShelf.kt
  fun deleteKit(entry: Entry): Boolean          // moves the whole kit dir under Kits/.bin/<name>-<timestamp>/, mirrors Rooms.forget's shape
  fun sweepDeletedKits(keepDays: Double = 30.0, nowMillis: Long = System.currentTimeMillis()): Int  // purges Kits/.bin/ entries past the window, mirrors Rooms.sweepBin
  fun renameKit(entry: Entry, newName: String): Entry?   // null on collision/invalid name after fallback attempts; else the renamed Entry
  ```
  `Kits/.bin/` is a NEW top-level dir, architecturally identical to `Kits/Rooms/` (invisible to `KitStore.list()` because entries there won't carry a normal discoverable `kit.json` scan path the shelf lists from — confirm by reading how `Kits/Rooms/` currently stays invisible to the main shelf list, and replicate exactly).

- [ ] **Step 1: Implement `deleteKit`/`sweepDeletedKits`/`renameKit` in `KitShelf.kt`**, each modeled directly on its named template above. `renameKit` validates via `Names.isMpcSafe(newName)` first (return null immediately on an invalid name — no partial rename attempt); a genuine directory collision falls back exactly like `moveOntoShelf` does.

- [ ] **Step 2: `KitRow` gesture + revealed actions.** Copy `RoomRow`'s `pointerInput` + `detectTapGestures(onLongPress, onTap)` armed-state pattern (`KitsScreen.kt:222-280`) onto `KitRow` (`KitsScreen.kt:318-339`): long-press reveals DELETE + RENAME (a small inline text-entry for rename — match whatever simple rename-input pattern exists elsewhere in the app, e.g. how a kit is initially named during FRESH, or a plain single-line text field if nothing to copy). DELETE requires a confirm ("DELETE THIS KIT? IT GOES TO THE BIN FOR 30 DAYS." — this one CAN say "the bin" since it's true here, unlike snips). Tap elsewhere disarms without acting, same as Rooms.

- [ ] **Step 3: Wire the sweep.** In App.kt's same launch effect from Task 1, add `runCatching { shelf.sweepDeletedKits() }` alongside `sweepRooms()`/`purgeBin()`.

- [ ] **Step 4: Handle the currently-open-kit case — this WILL happen, it is not hypothetical.**
  `KitRow` renders on `AppScreen.KITS`; `open` (the currently-open kit, if any) persists
  across a tab switch back to KITS. A user can open kit A, switch to the KITS tab, and
  long-press kit A's own row in the shelf list — delete/rename of the open kit IS
  reachable. Required guards, wired in App.kt at the call sites of `deleteKit`/`renameKit`
  (not inside `KitShelf`, which has no notion of "currently open"):
  - `deleteKit`: after a successful move-to-bin, if `open?.dir == entry.dir`, clear
    `open = null` (the kit the user was looking at no longer exists at that path) and
    navigate back to the shelf if the current screen was showing it.
  - `renameKit`: after a successful rename returning the new `Entry`, if
    `open?.dir == entry.dir`, set `open = renamedEntry` — the same identity-guard
    discipline the hardening pass applied to every `onKitUpdated` closure; a stale
    `open` pointing at the pre-rename path must not silently linger.

- [ ] **Step 5: Compile + reason through** (report): confirm both guards above actually
  fire by tracing the call site; confirm the → PAD hand-off from Task 3 lands on a real,
  reachable, functional path (not a disabled button) and describe exactly which of the
  two wiring options (in-KIT pad-pick mode, or a second SNIPS entry point) was implemented
  and why.

- [ ] **Step 6: Commit** — `feat(kit): delete (recoverable, 30-day bin) and rename from the shelf`.

---

## Self-Review (done)
Bin promise: fixed via a launch-time sweep mirroring the one area (Rooms) that already does it correctly ✓. Orphan/cache sweep explicitly excludes exports (user-owned) ✓. Snips: delete+listWithInfo tested in :shell, "never used" honestly scoped (badge omitted, not guessed, for all pre-existing data; scan cheapens to a single any-pad-tagged check before paying a full per-kit read) ✓. → PAD navigates from the shelf regardless of whether a kit is open — the TAPE/CHOP kit-gate bug is not reintroduced here ✓. Kit delete: recoverable via a bin, mirroring the Rooms template exactly, not a new invented mechanism; clears/updates `open` by identity if the deleted/renamed kit is the one currently open (same discipline as the hardening pass's onKitUpdated guards) ✓. Kit rename: reuses ShelfImport's proven collision-fallback rather than a naive renameTo ✓. Type consistency: `SnipStore.Info`/`delete`/`listWithInfo` names match across Tasks 2-3; `KitShelf.deleteKit`/`renameKit`/`sweepDeletedKits` match across Task 4's own steps ✓.
