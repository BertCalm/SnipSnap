# Live Record: Play A Beat In

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the gap the UX audit named plainly: you cannot play a beat in. PLAY has a full, tested, velocity-sensitive pad grid and no clock; GROOVE has the app's only transport clock and no pad grid at all. This plan wires a live-record path directly into GROOVE — the screen that already owns the loop — by lending it PLAY's pad-hit surface, so hitting pads while the loop plays lands notes on the kit's own groove, exactly the way READ AS GROOVE already lands notes heard from a microphone.

**The central finding, stated plainly:** PLAY and GROOVE do not share a transport, and this plan does not build one. Each screen constructs its own `PadEngine` instance (`PlayScreen.kt:144`, `GrooveScreen.kt:213`), and `App.kt`'s `when(screen)` (`App.kt:1583`/`:1585`) disposes whichever screen isn't showing — there is no cross-screen engine, clock, or state to unify short of hoisting engine ownership to `App.kt`, which is a separate initiative, not a sub-task of this one. GROOVE's own KDoc comment that the roll and editor "sound through the same `PadEngine` as PLAY, KIT and KEYS" (`GrooveScreen.kt:206-211`) means the same *class*, not a shared instance — worth flagging so no implementer misreads it as evidence a transport already crosses screens.

What GROOVE *does* have, uniquely, is a single authoritative clock: the `withFrameNanos` loop at `GrooveScreen.kt:369-396` advances `posSteps` (`GrooveScreen.kt:265`) from wall time using `kit.tempoBpm ?: KitPreview.DEFAULT_BPM` (`GrooveScreen.kt:380`, `KitPreview.kt:26`). This plan puts recording there, not on PLAY, and not on a new shared abstraction — see Design Question 1 below for the full reasoning.

**Architecture:**
- **Task 1** extracts PLAY's pad grid (`PlayPad`/`PlayBank`/`BankRow` and their supporting constants) out of `PlayScreen.kt` into a shared file both screens can render from, and threads real velocity through GROOVE's own `hit()`, which currently hardcodes `1f` (`GrooveScreen.kt:243`).
- **Task 2** adds the pure recording arithmetic — elapsed-time-to-pulses, loop wraparound, note assembly, and landing — as a new `kit`-module object (`LiveRecord.kt`) that reuses `GrooveVariations.standard` exactly the way `ReadGroove.land` does (`ReadGroove.kt:92-95`), so a recorded take becomes the kit's captured base, not a new concept.
- **Task 3** gives the engine a count-in click, appended into the same bank `PadEngine.load` already builds, triggered through the existing `hitLayers` path (`SplitScreen.kt:181` precedent) rather than any new native surface.
- **Task 4** wires RECORD into `GrooveScreen`, including the from-scratch case (`loadedBase == null`, currently a hard `EmptyGroove` return at `GrooveScreen.kt:252-259`) — this is the actual bootstrap path a user with a fresh kit and no captured groove needs.
- **Task 5** covers landing, the one-tap "FORK TO E" quantize offer (zero new quantize code — it's `GrooveEdit.fork`, already shipped), and undo.
- **Task 6** is copy, KDoc corrections, and the wiring details that are easy to get wrong (not bumping GROOVE's own `reloadRequest` on its own write).

**Tech stack:** existing `PadEngine`/`VoiceAllocator`/`GrooveStore`/`GrooveEdit`/`GrooveVariations`/`Mpc3Clip` machinery; Jetpack Compose; no new modules, no new native (C++) surface.

## Design Questions, Resolved

### 1. Where does the recorded hit come from — PLAY's path, a shared recorder, or both?

**Recommendation: extract PLAY's pad-grid *rendering and touch/velocity handling* into a shared composable; wire it into GROOVE, feeding GROOVE's own engine, allocator, and clock. Recording is a GROOVE-only capability — not available from the standalone PLAY screen.**

Reasoning: the two screens are structurally isolated (see above) — recording from literal `PlayScreen` would mean either (a) importing GROOVE's clock/tempo/pattern-loading logic into `PlayScreen` too, duplicating the one piece of state (`computeProgram` + the `withFrameNanos` clock) this codebase already treats as a single source of truth read by multiple consumers, or (b) hoisting engine ownership app-wide, which is the separate initiative named above. Neither is justified by this feature alone. What PLAY has that's genuinely worth reusing is the *interaction model* — `PlayPad`'s touch-Y velocity mapping (`velocityFromY`, `PlayScreen.kt:94-97`), the bank/row layout (`WINDOW_GRID_ROWS`/`BANK_A_ROWS`/`BANK_B_ROWS`, `PlayScreen.kt:87-89`), the tag/class-color rendering, and the accessibility semantics (`PlayScreen.kt:585-590`) — none of which GROOVE has today (GROOVE currently has *no* live pad-hit UI at all; its own `hit(slot)` at `GrooveScreen.kt:240-246` is called internally by the step editor and by playback, never from a rendered touch target). Extracting that surface once and rendering it inside GROOVE's own composition means every hit goes through GROOVE's own `PadEngine`/`VoiceAllocator`/clock — the same instance already sounding the loop — with no cross-screen plumbing needed. This is also explicitly aligned with `PlayScreen.kt:74-86`'s own KDoc, which already states PLAY didn't reuse `KitScreen`'s `PadCell` because it didn't extract cleanly; this plan does the extraction PLAY itself flagged as owed, just one level up.

### 2. Quantization — snap on capture, or store true time and quantize at playback?

**Recommendation: record at true time (960 PPQ, unquantized), land the take as the kit's captured base clip, and offer FORK TO E as a one-tap, already-shipped quantize step. No new quantization code ships.**

This is not a judgment call so much as a consistency finding: every existing capture path in this codebase stores true time and treats the grid as something applied later, on request. `ReadGroove.read` converts each detected audio transient to `timePulses = Math.round(hit.frame / framesPerPulse)` — true position, no snapping (`ReadGroove.kt:69-73`). PROG A is captioned "THE BREAK, AS PLAYED" (`GrooveScreen.kt:123`) specifically because it *isn't* quantized — only `GrooveEdit.quantized` (`GrooveEdit.kt:91-104`) snaps to the 16th grid, and it only runs when the user explicitly forks to PROG E. A live-recorded take is, structurally, the same kind of artifact as a mic capture: a human's true timing that the kit doesn't own an opinion about until asked. Landing it as the base (Task 2, via `GrooveVariations.standard`) means B/C/D (tight/half-time/sparse) are regenerated automatically from it, exactly as they are after READ AS GROOVE — and the FORK TO E button already on screen (`GrooveScreen.kt:562`, `forkToE()` at `:398-422`) becomes, with zero new logic, the "make it grid-perfect" action for whoever wants one. A hit landing between steps is simply a note at its true pulse position; nothing refuses it. The one piece of new arithmetic needed is the loop-boundary wraparound — see Task 2's locked behavior; without it, a hit a few milliseconds past the loop point produces a `timePulses` value that fails `Mpc3Clip`'s own `require(it.timePulses < bars * PULSES_PER_BAR)` (`Mpc3TrackWriter.kt:46`) and crashes the take.

This does depart from the default most hardware drum machines ship (snap-on-record is the MPC/Elektron convention). The tradeoff is deliberate: this codebase's whole GROOVE model is built around "capture honestly, quantize as a separate, visible, undoable step," and a from-scratch off-grid take that sounds wrong is one FORK TO E tap from fixed, with the un-quantized version never discarded (A stays "as played" even after E exists — E is fork-only, `GrooveEdit.kt:106-121`).

### 3. Count-in and loop behavior

**Recommendation: a synthesized click, one bar of count-in for the very first take on a kit with no captured groove (nothing to play back yet), no count-in when a base already exists and the loop is already audibly playing (the loop itself is the count-in, same as any drum machine's "record over what's already playing"). Overdub, not replace, matching the base default: a second take on an existing base adds notes rather than discarding them, and REPLACE is an explicit, separate, secondary action.**

Reasoning: there is no metronome or click asset anywhere in this codebase (verified — no `metronome`/`click` hits in the repo). For the case that actually needs one — a brand-new kit with no groove.json at all, where GROOVE currently hard-refuses with `EmptyGroove` (`GrooveScreen.kt:252-259`) — there is nothing playing back to cue the user, so a count-in is not optional, it's the only way to know when bar 1 starts. Once a base exists, playback (the needle-roll, the loop) is already running before RECORD is armed, which is a stronger and more familiar cue than a click. Overdub-as-default follows the same reasoning as the quantization answer: every existing GROOVE action is additive-and-reversible by convention (HUMANIZE reseeds without discarding, EDIT STEPS forks without touching A–D) — a REPLACE that silently discards a base on the first take of a new session is the surprising choice, not the safe one. Concretely: overdub merges new notes into the existing base's note list (same-(note, timePulses) collisions resolved exactly like `GrooveEdit.quantized`'s own dedup rule — louder wins, `GrooveEdit.kt:98-101` — reused, not reinvented), landed as a new base via the same `LiveRecord.land` used for a from-scratch take.

### 4. Undo

**Recommendation: a single in-memory pre-take snapshot, restored immediately after stopping a take — the smallest mechanism, not a stack, matching this codebase's established "revert one field" idiom (`Kit.kt:66-70`'s own comment: "undo is setting it back to null") rather than inventing history/redo machinery that doesn't exist anywhere else in this app.**

Before arming RECORD, GROOVE captures `preTake = base` (the current captured-base `Mpc3Clip`, or `null` on a from-scratch kit) in local composable state. Stopping a take shows an immediate "UNDO TAKE" affordance (mirroring the debounce-then-flush pattern GROOVE already uses for E, `GrooveScreen.kt:296-320`) that, if tapped, restores `preTake`: `GrooveStore.save(kitDir, GrooveVariations.standard(preTake) + listOfNotNull(eClip))` when `preTake != null` (the `ReadGroove.land` shape), or `GrooveStore.delete(kitDir)` when `preTake == null` and no E exists, or, when E exists but no base did, `GrooveStore.delete(kitDir)` followed by `GrooveEdit.save(kitDir, eClip)` — dropping the base and keeping E.

> **Correction (found during Task 2, verified against source).** An earlier draft of this paragraph cited `GrooveEdit.deleteProgE` as a "delete-and-keep-E shape" to reuse here. It is the exact opposite: `deleteProgE` does `filterNot { isProgE(it) }` (`kit/.../GrooveEdit.kt`), so it removes E and KEEPS the base — the reverse of what this branch needs. Do not reuse it here. Task 2's brief carried the correct recipe and its implementer flagged the discrepancy rather than following the design doc. `GrooveStore.save` itself refuses an empty clip list (`require(clips.isNotEmpty())`, `GrooveStore.kt:33`), which is exactly why the delete-vs-save branch has to be explicit rather than a single call. This does not survive navigating away from GROOVE — same lifetime as every other unsaved-edit safety net in this screen (the debounced E save, the ON_STOP flush) — which is an acceptable bound: "undo my last take before I do anything else" is the case being covered, not "undo across app restarts."

### 5. The clock problem

Addressed above and under Task 1/4: there is no shared transport, this plan does not build one, and recording lives entirely inside GROOVE's own clock loop. The one piece of real clock-precision engineering is in Task 4's locked behavior — capturing a touch's time in the *same* clock domain the playback loop already uses, not by re-reading `posSteps` (which lags one recomposition behind the frame that advanced it).

## Global Constraints

- **Reuse, don't reinvent.** Every constant already exists: bars-per-clip math is `Mpc3Clip.PULSES_PER_BAR`/`PULSES_PER_16TH` (`Mpc3TrackWriter.kt:52-53`), grid math is `GrooveEdit.STEPS_PER_BAR`/`STEP_PULSES` (`GrooveEdit.kt:50-53`), tempo fallback is `KitPreview.DEFAULT_BPM` (`KitPreview.kt:26`). No task in this plan introduces a second copy of any of these.
- **`PadEngine.loadSnips` is a bank-replacing call, not an additive one** (`PadEngine.kt:131-135`: "the engine plays these snips and nothing else"). The count-in click must be added inside the *same* `beginBank`/`addSample`/`commitBank` transaction `PadEngine.load` already runs (`PadEngine.kt:93-124`), not via a second `loadSnips` call, which would silence every kit pad.
- **Free-time notes must wrap the loop boundary before construction, not after.** `Mpc3Note`/`Mpc3Clip`'s own `require` (`Mpc3TrackWriter.kt:25`, `:46`) throws on an out-of-range `timePulses` — a hit landing after the nominal loop end is a real, expected case (a human's timing is never exactly on the sample), not an edge case to defer.
- **`GrooveStore.save` refuses an empty clip list** (`GrooveStore.kt:33`) — any code path that can end with zero clips (undo to a from-scratch state) must branch to `GrooveStore.delete` instead.
- **GROOVE must never reload out from under its own write.** `App.kt:1593` passes `grooveReload` into `GrooveScreen`'s `reloadRequest`, which re-keys the load effect at `GrooveScreen.kt:194`; that counter belongs to `App.kt`'s own external-mutation signal (READ AS GROOVE, STEAL THE FEEL from TAPE, `App.kt:500`). A live-recorded save inside GROOVE updates GROOVE's own local `base`/`eClip` state directly and must NOT bump `grooveReload` — doing so would re-run the load effect mid-take and blank the screen.
- **No `KitWrites.mutex`.** That mutex specifically serializes `KitBuilderModel` open→mutate→save sequences on `kit.json` (`KitWrites.kt:5-16`). `groove.json` writes (`GrooveStore.save`/`GrooveEdit.save`) have never gone through it — GROOVE's own existing debounced E-save doesn't either — so this plan follows the same convention rather than inventing a new locking requirement for a file that's never needed one.
- House voice for new strings: short, caps, honest, matching `Personality.kt`'s existing GROOVE section (`Personality.kt:241-260`).
- Concurrent-session repo: `git add` only files you touched, never `-A` or `.`. Never touch `FOLLOWUP-REPORT.md` or `spike/`.
- Commit trailers on every commit:
  ```
  Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01MDVos8CfKQ4ewvwrcNUhrS
  ```
- Gate: `./gradlew test :app:compileDebugKotlin` green.

---

## Task 1: Extract PLAY's pad grid; thread real velocity through GROOVE's `hit()`

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/ui/PadGrid.kt`
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/PlayScreen.kt` (delete the extracted pieces, import from `PadGrid.kt`)
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/GrooveScreen.kt` (`hit()` gains a velocity parameter)

**Interfaces:**
- Consumes: `WINDOW_GRID_ROWS`/`BANK_A_ROWS`/`BANK_B_ROWS` (`PlayScreen.kt:87-89`), `MIN_VELOCITY`/`velocityFromY`/`CENTER_VELOCITY` (`PlayScreen.kt:92-106`), `padTag` (`PlayScreen.kt:115`), `classTint` (`PlayScreen.kt:520-526`), `PlayPad`/`PlayBank`/`BankRow` (`PlayScreen.kt:399-517`, `:540-617`).
- Produces (in `PadGrid.kt`, same signatures, `internal` not `private` so both screens can import):
  ```kotlin
  internal val WINDOW_GRID_ROWS: List<IntRange>
  internal val BANK_A_ROWS: List<IntRange>
  internal val BANK_B_ROWS: List<IntRange>
  internal fun velocityFromY(y: Float, height: Float): Float
  internal fun padTag(slot: Int): String
  @Composable internal fun PlayPad(slot: Int, pad: KitPad?, glow: Animatable<Float, AnimationVector1D>?, onHit: (Int, Float) -> Unit, onRelease: (Int) -> Unit, modifier: Modifier = Modifier)
  @Composable internal fun PlayBank(kit: Kit, rows: List<IntRange>, glow: Map<Int, Animatable<Float, AnimationVector1D>>, onHit: (Int, Float) -> Unit, onRelease: (Int) -> Unit, modifier: Modifier = Modifier)
  @Composable internal fun BankRow(kit: Kit, glow: Map<Int, Animatable<Float, AnimationVector1D>>, onHit: (Int, Float) -> Unit, onRelease: (Int) -> Unit, modifier: Modifier = Modifier)
  ```

**Locked behavior:**
- Byte-for-byte move, not a rewrite: `PlayScreen.kt`'s own hit-flash glow (`Animatable` per pad, `flash`/`extinguish` at `:176-187`) stays in `PlayScreen.kt` — it closes over PLAY's `scope` and is passed into `PlayPad` via the existing `glow`/`onHit`/`onRelease` params, so it moves with no change to `PadGrid.kt`'s shape.
- `PlayScreen.kt` after this task calls the same `PlayBank`/`BankRow`/`WINDOW_GRID_ROWS` etc. via import; its own `hit(slot, velocity)` (`:208-232`) and `release(slot)` (`:234-240`) are unchanged.
- `GrooveScreen.kt`'s `hit(slot: Int)` (`:240-246`) gains a `velocity: Float = 1f` parameter (default preserves every existing call site — the step-editor toggle preview and the playback loop's own note-trigger both still want a fixed velocity, not the recorded one) and passes it to `allocator.noteOn(slot, velocity, ...)` and `player.hit(pad, velocity, ...)` instead of the hardcoded `1f`.
- No visual change to PLAY. `npx`-equivalent for this repo: a Kotlin compile is the only verification available pre-runtime.

- [ ] **Step 1: Create `PadGrid.kt`**, moving the listed declarations verbatim (visibility bumped from `private`/file-private to `internal`), no logic changes.
- [ ] **Step 2: Repoint `PlayScreen.kt`** to import from `PadGrid.kt`; delete the moved declarations from `PlayScreen.kt`.
- [ ] **Step 3: Add the `velocity` parameter to `GrooveScreen.hit()`**, defaulted to `1f`, threaded through both call sites in that function.
- [ ] **Step 4: Compile** — `./gradlew :app:compileDebugKotlin`.
- [ ] **Step 5: Reason through** (report): PLAY renders identically (same composables, same file just moved); GROOVE's existing callers (step editor toggle, playback loop) are unaffected because the new parameter defaults to the old hardcoded value; nothing in `PadGrid.kt` references anything PLAY-specific (no `entry`, no `PadEngine`) that would stop GROOVE from using it in Task 4.
- [ ] **Step 6: Commit** — `refactor(ui): pad grid rendering shared between PLAY and GROOVE`.

---

## Task 2: `LiveRecord` — the pure note-capture and landing arithmetic

**Files:**
- Create: `kit/src/main/kotlin/com/snipsnap/kit/LiveRecord.kt`
- Create: `kit/src/test/kotlin/com/snipsnap/kit/LiveRecordTest.kt`

**Interfaces:**
- Consumes: `Mpc3Clip`/`Mpc3Note` (`Mpc3TrackWriter.kt:17-55`), `GrooveVariations.standard` (`GrooveVariations.kt:18-25`), `GrooveStore.save`/`.delete` (`GrooveStore.kt:32-47`), `GrooveEdit.isProgE` (`GrooveEdit.kt:69`). NOT `deleteProgE` — see the correction under Design Question 4; it keeps the base and deletes E, the reverse of the undo branch's need.
- Produces:
  ```kotlin
  object LiveRecord {
      /** Seconds since the take armed → pulses at [bpm], 960 PPQ — same conversion ReadGroove.read runs in reverse (frame → pulse) at ReadGroove.kt:61/:71. */
      fun pulsesFor(elapsedSeconds: Double, bpm: Float): Long

      /** Wraps a free-time pulse position into one loop of [bars] bars — the free-time analog of GrooveEdit.quantized's own step-index-modulo wrap (GrooveEdit.kt:80-89, :95). */
      fun wrapped(timePulses: Long, bars: Int): Long

      /** Accumulates hits during one take; not itself persisted. */
      class Take(val bars: Int) {
          fun add(note: Int, elapsedSeconds: Double, bpm: Float, velocity: Float)
          fun notes(): List<Mpc3Note>   // time-sorted, same-(note,pulse) collisions deduped louder-wins (mirrors GrooveEdit.kt:98-101)
      }

      /** Take → a named base clip, overdubbed onto [existing] when given. */
      fun toClip(take: Take, name: String, existing: Mpc3Clip?): Mpc3Clip

      /** Lands [clip] as the kit's captured base, exactly as ReadGroove.land does (ReadGroove.kt:92-95): standard variations regenerated, any existing PROG E rides along untouched. */
      fun land(kitDir: File, clip: Mpc3Clip): File

      /** Discards back to [preTake] (null = no base existed before this take): save when non-null, GrooveStore.delete + GrooveEdit re-save-of-E-only when null and E exists, GrooveStore.delete outright when neither existed. */
      fun undo(kitDir: File, preTake: Mpc3Clip?)
  }
  ```

**Locked behavior:**
- `pulsesFor`: `elapsedSeconds * (bpm / 60.0) * 960.0` — the same `stepsPerSecond`-shaped formula GROOVE's own playback loop uses (`bpm / 60.0 * 4.0` steps/sec, `GrooveScreen.kt:381-382`), expressed in pulses instead of steps so it never needs its own separate constant for "pulses per step."
- `wrapped`: `((timePulses % limit) + limit) % limit` where `limit = bars.toLong() * Mpc3Clip.PULSES_PER_BAR` — the extra `+ limit) % limit` guards a pathological negative input (shouldn't occur from a monotonic elapsed-time source, but `Mpc3Note`'s own `require(timePulses >= 0)`, `Mpc3TrackWriter.kt:25`, means this must never be able to construct a negative value even from a caller bug).
- `Take.add` calls `wrapped` before storing — a hit is never held at an out-of-range pulse even transiently.
- `Take.notes()` collision rule: identical to `GrooveEdit.quantized`'s dedup (group by `(note, timePulses)`, keep the louder) — same behavior, not reimplemented differently, because two hits landing on the exact same pulse (unlikely at 960 PPQ resolution, but two pads bulk-triggered in one frame during overdub is plausible) must resolve the same way everywhere in this codebase.
- `toClip` with `existing == null`: `Mpc3Clip(name, take.bars, take.notes())`. With `existing != null` (overdub): notes = `existing.notes + take.notes()`, deduped by the same `(note, timePulses)` rule, **bars = `existing.bars`** (a take is always recorded against the currently-loaded base's own bar count — see Task 4's loop-length handling for the from-scratch case, where there is no `existing` yet).
- `land` requires `clip.notes.isNotEmpty()` (a `Mpc3Clip` with zero notes is legal per its own `init` — no such check exists there — but landing a silent take is never the intended action; refuse with a message the caller turns into a toast, matching `ReadGroove.read`'s own `require(notes.isNotEmpty())` pattern at `ReadGroove.kt:76-81`).
- `undo`: three branches exactly as specified in Design Question 4 above — write the tests for all three (existing base restored; from-scratch-with-E restored to E-only; from-scratch-with-nothing restored to no groove.json at all).

- [ ] **Step 1: Implement `LiveRecord.kt`** per the locked behavior above.
- [ ] **Step 2: Write `LiveRecordTest.kt`** covering: `pulsesFor` at a known BPM against a hand-computed value; `wrapped` on an in-range value (no-op), an over-the-end value (wraps to near zero), and the boundary value itself (`limit` wraps to `0`, matching `GrooveEdit.quantized`'s own `% stepsInClip` behavior at a step boundary); `toClip` overdub collision (two notes at the same `(note, pulse)`, louder wins); all three `undo` branches; `land` refusing an empty-note clip with a message, not a crash.
- [ ] **Step 3: Compile + test** — `./gradlew :kit:test`.
- [ ] **Step 4: Reason through** (report): every constant reused from `Mpc3Clip`/`GrooveEdit`, none redefined; `wrapped` can never hand `Mpc3Note` a value that fails its own `require`; the three `undo` branches cover every state `base`/`eClip` can be in before a take (base+E, base only, E only, neither).
- [ ] **Step 5: Commit** — `feat(kit): LiveRecord — the pure arithmetic for playing a beat in`.

---

## Task 3: Count-in click, added to the existing bank

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/PadEngine.kt`

**Interfaces:**
- Consumes: `loadLocked` (`PadEngine.kt:93-124`), `hitLayers`/`Layer` (`PadEngine.kt:199-224`, precedent at `SplitScreen.kt:181`).
- Produces:
  ```kotlin
  /** The reserved bank index of the count-in click, valid only after `load()`; -1 before then or if the engine has no stream. */
  var clickSampleIndex: Int = -1
      private set

  /** Plays the click at [voiceId], one-shot, full length. False (no voice queued) exactly when `hit` would be false — no stream, or the click never loaded. */
  fun clickHit(voiceId: Int, accent: Boolean): Boolean
  ```

**Locked behavior:**
- The click is synthesized in memory, never a bundled asset — a short sine burst (e.g. 1000 Hz accent / 800 Hz regular, ~30ms, quick linear or exponential decay to avoid a click-on-the-click) built as a `FloatArray` at the engine's own sample rate and wrapped in whatever minimal `Snip`-shaped structure `addSample` needs (`NativePads.addSample(handle, interleaved, channels, rate)`, `NativePads.kt:24`) — same call `loadLocked` already makes per kit sample (`PadEngine.kt:112`).
- Added inside `loadLocked`'s existing `beginBank`/`addSample`-loop/`commitBank` transaction (`PadEngine.kt:101-119`), as the last two samples added (accent, then regular) — **not** a second `beginBank`/`commitBank` pair, and **not** `loadSnips` (`PadEngine.kt:141-163`), which the KDoc at `:131-135` states outright replaces the whole bank ("the engine plays these snips and nothing else") — calling it here would silence every kit pad mid-session.
- `clickSampleIndex` (and a sibling `clickAccentIndex`, or a single index plus an `accent` bool selecting which of the two trailing bank slots to play) is recorded once `commitBank` succeeds, alongside the existing `sampleIndex`/`framesOf` bookkeeping (`PadEngine.kt:120-122`).
- `clickHit` plays via `hitLayers` with a single-element `Layer` list (the `SplitScreen.kt:181` precedent for playing a raw bank index with no `KitPad` involved), at a fixed, sensible gain — this is a cue, not a kit sound, so it does not go through `VoiceAllocator` (no choke group, no pad slot) and its voice id is caller-supplied exactly like every other `hitLayers`/`hit` call.
- If `load()` is called again (kit reload mid-session — `GrooveScreen.kt:222`'s `LaunchedEffect(entry.kit)`), the click is re-added each time, same as every kit sample is re-added; no special-casing needed since the whole bank is rebuilt from scratch on every `load()` call already.

- [ ] **Step 1: Implement the click synthesis** (a small private helper, e.g. `synthClick(sampleRate: Int, accent: Boolean): FloatArray`) and wire it into `loadLocked`'s existing bank-build sequence.
- [ ] **Step 2: Implement `clickSampleIndex`/`clickHit`** per the locked behavior.
- [ ] **Step 3: Compile** — `./gradlew :app:compileDebugKotlin`.
- [ ] **Step 4: Reason through** (report): the click never uses `loadSnips` (confirm by grep — `PadEngine.kt` should show exactly one `beginBank`/`commitBank` pair touched by this change, the one already in `loadLocked`); a kit with 32 loaded pads plus 2 click samples still fits (no bank-size ceiling is asserted anywhere in the reviewed code, but confirm `addSample`'s native side has no fixed array bound that this would overflow — if one exists, say so as an open question rather than assuming safety).
- [ ] **Step 5: Commit** — `feat(app): PadEngine gains a synthesized count-in click`.

---

## Task 4: RECORD, wired into `GrooveScreen` — including the from-scratch case

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/GrooveScreen.kt`

**Interfaces:**
- Consumes: `PadGrid.kt`'s `PlayBank`/`BankRow`/`WINDOW_GRID_ROWS` (Task 1), `LiveRecord` (Task 2), `PadEngine.clickHit` (Task 3), the existing playback `LaunchedEffect` (`GrooveScreen.kt:369-396`), `GrooveScreen.hit(slot, velocity)` (post-Task-1).
- Produces (within `GrooveScreen`): new composable state — `recording: Boolean`, `countingIn: Boolean`, `preTake: Mpc3Clip?`, `recordBars: Int` (the from-scratch loop length) — and a new `RECORD` control alongside the existing PLAY/STOP row (`GrooveScreen.kt:530-541`).

**Locked behavior:**
- **The from-scratch case is the load-bearing change.** `GrooveScreen.kt:252-259` currently returns `EmptyGroove(scheme)` unconditionally when `loadedBase == null`. That early return must become conditional: a kit with pads but no `groove.json` still shows `EmptyGroove`'s message *plus* a RECORD entry point, since RECORD is now how a from-scratch pattern gets created (mirroring the fact that TAPE's READ AS GROOVE has always been able to do this from audio — RECORD is the pad equivalent). A kit with **no pads at all** (`kit.pads.isEmpty()`) still has nothing to record with and keeps the unconditional refusal.
- **Timestamping a hit uses the clock loop's own domain, not a re-read of `posSteps`.** The playback `LaunchedEffect` (`:369-396`) already tracks `lastNanos`/`lastPos` locally; hoist these two into composable state (`var clockNanos`/`var clockPos`, updated each tick alongside `posSteps`) so a hit handler firing between frames can compute `elapsedSeconds = (System.nanoTime() - clockNanos) / 1e9` and add it to `clockPos`-derived pulses, rather than reading `posSteps`, which is one recomposition behind the frame that set it. **Open question requiring verification during implementation, not an assumption:** confirm whether `withFrameNanos`'s callback parameter and `System.nanoTime()` share a timebase on the target API levels — if Compose's frame clock is `SystemClock.elapsedRealtimeNanos()`-based rather than `System.nanoTime()`-based, capturing wall time in the pointer handler and mixing it with the frame loop's own nanos would introduce unbounded skew, not bounded one-frame error. If they don't share a timebase, capture the touch's own `PointerInputChange.uptimeMillis` (available at the same `awaitFirstDown` call site PLAY already uses, `PlayScreen.kt:593`) and correlate it against a `(uptimeMillis, posSteps)` pair the clock loop maintains instead of `(nanos, posSteps)` — same structure, different clock source. Either way, both the recorder and the playback loop must derive from the identical formula (`bpm / 60.0 * 4.0` steps/sec → `LiveRecord.pulsesFor`'s pulse-equivalent), so a note recorded on beat plays back on beat.
- **Recording surface:** while `recording == true`, render the same bank grid PLAY shows (via the Task 1 extraction) beneath or in place of the needle-roll — a compact single-bank view is enough (the needle-roll continues to show playhead position above it), reusing `PlayBank`/`BankRow` with `onHit` routed to a new `fun recordHit(slot: Int, velocity: Float)` that calls `GrooveScreen`'s own `hit(slot, velocity)` (for the sound) and appends to the in-flight `LiveRecord.Take` (for the capture) — one call produces both, so a recorded take can never diverge from what the user actually heard.
- **Whole grid records, not just the five needle-roll lanes.** `NOTE_TO_LANE` (`GrooveScreen.kt:155`) covers only KICK/SNARE/HAT_CLOSED/HAT_OPEN/PERC (slots 1/2/3/4/12); the other 27 pads across both banks are real, playable pads with real drum classes. Recording is restricted to the five-lane set would silently drop most of what a user plays on a real kit — refusing to record a clap or a tom the user clearly intends to hit is a worse surprise than the note being invisible in the roll. **Recommendation: record every pad the grid exposes, all 32 slots**, and rely on the existing, already-shipped `Copy.offLane` messaging (`GrooveScreen.kt:288`, `:573-575`) to tell the user afterward how many of their hits aren't drawn as roll blocks — the same honesty line already applied to every other groove-landing path (imported clips, READ AS GROOVE) applies here without new copy.
- **Count-in:** only when `loadedBase == null` (the from-scratch case — see Design Question 3). Tapping RECORD in that state sets `countingIn = true`, plays `recordBars` × 4 beats of `clickHit` (accent on beat 1) timed off the same clock, then flips to `recording = true` and starts the `Take`. When a base already exists, RECORD arms immediately against the already-playing loop (`playing` must already be `true`, or RECORD itself sets it — decide in favor of RECORD implying PLAY, so a user never has to tap PLAY then RECORD as two separate mental steps).
- **Loop length for the from-scratch case:** `recordBars` defaults to `2` — the value `GrooveScreen.kt:780-785`'s own KDoc calls "the normal case" for a groove clip — set before recording starts (not derived after the fact the way `ReadGroove.barsOf` derives bars from where the last hit landed, `ReadGroove.kt:148-149`, which only works because that path already has a complete audio buffer to look at; a live take has no "last hit" until it's over, and the loop has to already be looping before then).
- **Stopping:** RECORD stops on a second tap of the same control, or automatically if the user backs out of GROOVE entirely (`DisposableEffect(kitDir)` teardown, mirroring the existing editor-save safety net at `GrooveScreen.kt:326-328` — an in-progress take that's abandoned mid-recording is silently dropped, not landed, since a partial take auto-saving would be a much worse surprise than losing it).
- **On stop:** `LiveRecord.toClip(take, name, existing = loadedBase)` → `LiveRecord.land(kitDir, clip)`, updating local `base`/`eClip` state directly (no `grooveReload` bump — see Global Constraints) and showing the UNDO TAKE + FORK TO E affordances from Task 5.

- [ ] **Step 1: Restructure the `loadedBase == null` early return** to permit RECORD while still showing `EmptyGroove`'s message for every other control.
- [ ] **Step 2: Hoist the clock loop's `(nanos or uptimeMillis, pos)` pair into composable state**, verifying the timebase question above empirically (log both clocks side by side during manual testing if the platform docs don't settle it), and implement the hit-timestamping path.
- [ ] **Step 3: Wire the RECORD control, the recording pad-grid overlay, `recordHit`, count-in, and the `Take` lifecycle** per the locked behavior.
- [ ] **Step 4: Compile** — `./gradlew :app:compileDebugKotlin`.
- [ ] **Step 5: Reason through** (report): a from-scratch kit can record without ever visiting TAPE; a hit during count-in before `recording` flips true is not captured; leaving GROOVE mid-take drops the take rather than landing a partial one; a hit on a bank-B pad outside the five lanes is recorded, sounds, and lands, with `offLaneCount` reporting it afterward.
- [ ] **Step 6: Commit** — `feat(app): RECORD — play a beat in on GROOVE`.

---

## Task 5: Landing UI — FORK TO E offer and UNDO TAKE

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/GrooveScreen.kt`

**Interfaces:**
- Consumes: `forkToE()` (`GrooveScreen.kt:398-422`, unchanged), `LiveRecord.undo` (Task 2).
- Produces: a transient post-take row (two buttons: `FORK TO E ▸` calling the existing `forkToE()` unmodified since the just-landed base is already `currentClip` at `progIndex == 0`; `UNDO TAKE` calling `LiveRecord.undo(kitDir, preTake)` then restoring `base`/`eClip` local state to match).

**Locked behavior:**
- The row appears immediately after a take lands and stays until the user does anything else that changes the program (switches PROG, taps another action, leaves the screen) — same transience rule as the existing toast-driven affordances elsewhere in this screen, not a persistent UI element.
- `UNDO TAKE` is a single level, matching Design Question 4 — tapping it a second time (after the state it would restore is already gone) is a no-op, not an error toast, since `preTake` is cleared once consumed.
- Toast copy on landing follows `Personality.kt`'s existing GROOVE voice (Task 6 supplies the exact strings) — e.g. echoing `grooveRead`'s shape (`Personality.kt:246-249`) but naming what was played, not what was heard.

- [ ] **Step 1: Implement the post-take row and its two actions.**
- [ ] **Step 2: Compile** — `./gradlew :app:compileDebugKotlin`.
- [ ] **Step 3: Reason through** (report): FORK TO E after a take produces the identical quantized result `GrooveEdit.quantized` would produce from any other source clip (no special-casing needed — verify by reading `forkToE()` once more, not by re-testing logic Task 2 already covers); UNDO TAKE after FORK TO E has already happened does not also revert the fork (E is untouched by `LiveRecord.undo`, which only ever writes the base).
- [ ] **Step 4: Commit** — `feat(app): FORK TO E and UNDO TAKE after a live-recorded take`.

---

## Task 6: Copy, KDoc corrections, and wiring nuance

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Personality.kt`
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/GrooveScreen.kt` (KDoc only)
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/PlayScreen.kt` (KDoc only, noting the extraction)

**Interfaces:**
- Consumes: `Personality.kt`'s existing GROOVE section (`:241-260`) as the tone reference.
- Produces: new `Copy` strings for RECORD's states (arming/counting-in/recording/landed/undone), added beside the existing GROOVE block.

**Locked behavior:**
- New strings match the house voice exactly — short, caps, honest, no promise the code doesn't keep (e.g. an "UNDO TAKE" toast should say what actually happened, not "restored" if what happened was a delete of `groove.json` for a from-scratch undo).
- `GrooveScreen.kt`'s file-level KDoc (`:75-90`) is updated to mention RECORD as a third capability alongside the roll and the step editor — this file's own KDoc has historically stayed accurate to what the screen does (the `reloadRequest` KDoc comment at `:176` is a model of the style to match).
- `PlayScreen.kt`'s KDoc at `:74-86` (which currently explains PLAY didn't reuse `KitScreen`'s `PadCell`) gets one added sentence noting the grid was later extracted to `PadGrid.kt` so GROOVE could share it — so a future reader of that KDoc doesn't wonder why the "didn't extract cleanly" reasoning stopped being true.

- [ ] **Step 1: Add the new `Copy`/`Personality` strings.**
- [ ] **Step 2: Update the two KDoc comments named above.**
- [ ] **Step 3: Full gate** — `./gradlew test :app:compileDebugKotlin`.
- [ ] **Step 4: Commit** — `docs(app): RECORD copy and the KDoc it makes true`.

---

## Self-Review (done)

**Design questions:** all five answered with a recommendation and grounded reasoning, not a menu of options — reuse-PLAY's-interaction-model-not-its-instance (Q1), true-time-capture-landing-as-base (Q2), click-only-when-nothing's-playing-yet plus overdub-default (Q3), single-level in-memory undo across three branches (Q4), no-shared-transport stated plainly with the concrete reason it's out of scope (Q5) ✓.

**Every claim is `file:line`-grounded**, not estimated: the separate-`PadEngine`-per-screen finding is read directly off `PlayScreen.kt:144`/`GrooveScreen.kt:213`/`App.kt:1583`/`:1585`, not inferred from the "same PadEngine" comment that would have misled a less careful read; the `loadSnips`-wipes-the-bank finding (which blocks a naive count-in implementation) is read off `PadEngine.kt:131-135`'s own KDoc; the `Mpc3Clip`/`Mpc3Note` range `require`s that make loop-boundary wraparound mandatory, not optional, are read off `Mpc3TrackWriter.kt:25`/`:46`; `GrooveStore.save`'s empty-list refusal that forces the three-branch undo is read off `GrooveStore.kt:33` ✓.

**Consistency checks across tasks:** `LiveRecord`'s pulse formula (Task 2) and the clock-loop hoist (Task 4) are required to derive from the same `bpm/60*4` steps-per-second expression already in `GrooveScreen.kt:381-382` — stated as a cross-task constraint, not left to coincidence. The count-in click (Task 3) is explicitly barred from `loadSnips` with the exact KDoc line that would otherwise make that mistake look safe. `GrooveScreen.hit()`'s new velocity parameter (Task 1) defaults to the old hardcoded value so every existing call site is provably unaffected before Task 4 adds a new one ✓.

**Honesty about scope:** the plan states outright, in its own second paragraph, that a real shared transport is not being built and names the concrete reason (per-screen engine ownership, disposed on navigation) rather than quietly working around it or promising more than six tasks can honestly deliver ✓.

**Open question left genuinely open, not guessed at:** the `withFrameNanos`/`System.nanoTime()` timebase question in Task 4 is flagged for empirical verification during implementation rather than assumed either way, with a concrete fallback (correlate against `uptimeMillis` instead) if the assumption is wrong ✓. A second, smaller open question in Task 3 (whether the native bank has an unstated size ceiling the two extra click samples could hit) is likewise flagged rather than assumed safe ✓.
