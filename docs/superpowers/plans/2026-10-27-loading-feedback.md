# Loading feedback implementation plan (PR 2a, 2b, 2c)

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** every operation slower than 0.2 s shows something moving. That
means a progress bar in the dock, a spinner on the control that started
it, and a placeholder where the result will land. A count is shown
wherever the count is knowable.

**Architecture:** a `Busy` value type and a `BusyLatch` timing state
machine live in `:shell`, where they are tested on the JVM. `:app` gets
these composables:

- `BusyBar`, `Spinner`, `LoadingPanel` and `PadPlaceholderGrid`
- `LocalReduceMotion`, read from the system animator scale
- a `LocalBusySink`, so screen-local jobs reach the dock

App's `busy: String?` becomes `busy: Busy?`. The dock draws whichever job
is visible: App's own job first, otherwise a screen's.

**Tech stack:** Kotlin, Jetpack Compose (foundation only; Material3 is
not used on TapeOS screens), kotlin.test (JUnit 5) in `:shell`, and
`ComposeScreenTest` (JUnit 4, emulator) in `:app` androidTest.

**Spec:** `docs/superpowers/specs/2026-10-27-ui-clarity-design.md` §7.6
(and §4 anti-goals, §5 constraints).

## Global constraints

- **Base and branching.**
  - The base is `claude/mobile-mpc-drum-sampler-t58x74`. Never `main`.
  - PRs land as merge commits. Never rebase or force-push a branch that
    has an open PR. Merge the base into the head instead.
  - Branches are `claude/loading-a`, `claude/loading-b` and
    `claude/loading-c`. Branch `claude/loading-a` only after PR 1
    (`claude/retire-classic-look`) has merged. Branch each later one
    after the previous one merges.
- **Commits.** Read `.claude/skills/steward/SKILL.md` before the first
  commit.
  - Titles are plain declarative prose, with no `feat:` prefixes.
  - No model identifier anywhere except the harness footer:
    `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`, then
    `Claude-Session: https://claude.ai/code/session_011mdfbYzrdmCr1B8F6bq2vp`.
- **Gradle.**
  - Never run two Gradle invocations at once. Always use `--no-daemon`.
  - Fast gate:
    `./gradlew --no-daemon :shell:test :kit:test :cli:test :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin`.
    Gate on the exit code.
  - Don't run `:synth:test`. Five synth golden tests fail on arm64
    locally and are not ours.
- **Device tests.** Run them on `emulator-5554` (AVD `snipsnap_pixel`):
  `./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`.
- **Copy laws until PR 5.**
  - Every `Copy` `const val` must be upper case. It must end in "." or
    be listed in `notASentence` in
    `shell/src/test/kotlin/com/snipsnap/shell/PersonalityTest.kt`, with a
    one-line comment.
  - Parameterised lines are `fun`s, which the law does not scan.
  - Sentence case arrives in PR 5, so don't pre-empt it.
- **Timing.** `BusyLatch.SHOW_AFTER_MS = 200L`, `BusyLatch.MIN_VISIBLE_MS = 500L`.
- **The dock bar.**
  - At least 4 dp tall, full dock width, inside the dock along its top
    edge, without adding dock height.
  - Colour `scheme.amber` (the dock is the LCD surface, where amber reads
    6.9:1).
- **Reduced motion.** When
  `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`, nothing loops. The bar
  and placeholders are static, and the label carries the count or
  `Copy.WORKING_BUSY`.
- **Touch and access.**
  - Touch targets are at least `Layout.MIN_HIT_TARGET` (48 dp).
  - Every progress element carries `progressSemantics` (determinate or
    indeterminate) and the dock line keeps `LiveRegionMode.Polite`.
- **Labels.** No control shows a busy label unless it started the job.
  Other controls keep their own label and are disabled.
- **On disk.** Nothing on disk is renamed.

## Review focus

1. **A job shorter than 200 ms must never flash** a bar, spinner or
   label. BusyLatch tests pin this (Task 1).
2. **A long kit name must not push the count off the dock line.** The
   count has its own text node that never ellipsizes (Task 3,
   `DockBusyTest`).
3. **A starter that fails to render** clears the placeholders, returns to
   Library and says `Copy.CREATE_FAILED`. It must not leave the user on a
   pulsing grid (Task 4, manual step 6, with a forced failure).
4. **The library that is truly empty** shows the empty state once loading
   ends. It must not stay on "Loading the library" forever (Task 5,
   `KitsScreenLoadingTest`).
5. **A double tap** on any guarded trigger runs the job once. Guards are
   raised synchronously before `launch` (Task 8 law;
   Task 13 guards).

---

## PR 2a: the core, the starter kits and cold start

### Task 1: the `Busy` model and the `BusyLatch` timing

**Files:**
- Create: `shell/src/main/kotlin/com/snipsnap/shell/Busy.kt`
- Create: `shell/src/test/kotlin/com/snipsnap/shell/BusyTest.kt`
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Personality.kt`:
  - replace `dubbingBusy` (:2781) with `loadingBusy`
  - add `busyCount`, `LOADING_BUSY`, `WORKING_BUSY`, `LIBRARY_LOADING`
    and `CANCEL_JOB`
- Modify: `shell/src/test/kotlin/com/snipsnap/shell/PersonalityTest.kt`.
  Add `LOADING_BUSY`, `WORKING_BUSY`, `LIBRARY_LOADING` and `CANCEL_JOB`
  to `notASentence` (:538), each with a comment.
- Add: `docs/superpowers/specs/2026-10-27-ui-clarity-design.md` and
  `docs/superpowers/plans/2026-10-27-loading-feedback.md`, both copied
  from the session scratchpad.

**Interfaces:**
- Produces:
  - `data class Busy(val label: String, val done: Int = 0, val total: Int? = null, val cancel: (() -> Unit)? = null)`.
    It has `val fraction: Float?`, which is `done/total` clamped to
    0..1, or null when `total` is null or ≤ 0.
  - `class BusyLatch` with:
    - `fun start(now: Long)` (idempotent while running)
    - `fun stop(now: Long)`
    - `fun visible(now: Long): Boolean`
    - `fun nextChangeAt(now: Long): Long?`
    - `companion object { const val SHOW_AFTER_MS = 200L; const val MIN_VISIBLE_MS = 500L }`
  - New Copy entries:
    - `fun loadingBusy(name: String) = "LOADING $name…"`
    - `fun busyCount(done: Int, total: Int) = "$done OF $total"`
    - `const val LOADING_BUSY = "LOADING…"`
    - `const val WORKING_BUSY = "WORKING…"`
    - `const val LIBRARY_LOADING = "LOADING THE LIBRARY…"`
    - `const val CANCEL_JOB = "CANCEL"`

- [ ] **Step 1: Copy the spec and this plan into `docs/superpowers/`** on
  `claude/loading-a`, and commit them as "Record the UI clarity design
  and the loading plan".
- [ ] **Step 2: Write the failing tests** in `BusyTest.kt`, in kotlin.test
  style (`@Test fun \`...\`()`, `assertEquals(expected, actual, "why")`):
  - `a job shorter than the show delay never shows`. `start(0)`,
    `stop(150)`. `visible(t)` is false for t in 0, 100, 150, 200 and
    1000.
  - `a job shows from 200 ms`. `start(0)`. `visible(199)` is false and
    `visible(200)` is true.
  - `once shown it stays at least 500 ms`. `start(0)`, `stop(250)`.
    `visible(699)` is true and `visible(700)` is false.
  - `a long job hides when it stops`. `start(0)`, `stop(2000)`.
    `visible(1999)` is true and `visible(2000)` is false.
  - `a restart while lingering stays visible`. `start(0)`, `stop(250)`,
    `start(400)`. `visible(400)` and `visible(5000)` are true.
  - `nextChangeAt says when to look again`. After `start(0)`,
    `nextChangeAt(0)` is 200. After `stop(250)`, `nextChangeAt(300)` is
    700. A fresh latch's `nextChangeAt(0)` is null.
  - `start is idempotent`. `start(0)`, `start(150)`. `visible(200)` is
    true, so the clock was not reset.
  - `fraction`. `Busy("X", 5, 16).fraction` is 0.3125f.
    `Busy("X", 20, 16).fraction` is 1f. `Busy("X").fraction` is null.
    `Busy("X", 1, 0).fraction` is null.
  - `copy`. `Copy.loadingBusy("FACTORY")` is `"LOADING FACTORY…"` and
    `Copy.busyCount(5, 16)` is `"5 OF 16"`.
- [ ] **Step 3: Run the tests and confirm they fail.**
  `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.BusyTest'`
  should fail to compile with an unresolved `Busy` / `BusyLatch`.
- [ ] **Step 4: Implement `Busy`, `BusyLatch` and the Copy entries.**
  - `BusyLatch` stores `startedAt` and `endedAt`. It is visible iff
    `now >= startedAt + SHOW_AFTER_MS`, and either it is still running or
    `now < max(endedAt, startedAt + SHOW_AFTER_MS + MIN_VISIBLE_MS)`. A
    stop before `startedAt + SHOW_AFTER_MS` means it is never visible.
  - Delete `dubbingBusy` once its only caller (`App.kt:1263`) moves in
    Task 4. Until then, keep it so this task compiles alone.
- [ ] **Step 5: Run the tests and confirm they pass.**
  `./gradlew --no-daemon :shell:test --tests 'com.snipsnap.shell.BusyTest' --tests 'com.snipsnap.shell.PersonalityTest'`
  exits 0.
- [ ] **Step 6: Commit:** "Give a job in flight a type and a show/linger
  clock".

### Task 2: reduced motion, `Spinner`, `BusyBar`, `LoadingPanel`

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/theme/ReduceMotion.kt`
- Create: `app/src/main/kotlin/com/snipsnap/app/ui/Busy.kt`, holding
  `Spinner`, `BusyBar`, `LoadingPanel` and `rememberBusyVisible`
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Schemes.kt`. In
  `object Motion` (:570) add:
  - `BUSY_SWEEP_MS = 1100`
  - `SPINNER_MS = 900`
  - `PLACEHOLDER_PULSE_MS = 900`

  Update its KDoc's budget count.
- Modify: `app/src/main/kotlin/com/snipsnap/app/App.kt:3173`. Provide
  `LocalReduceMotion` in the `TapeTheme(scheme, LocalHaptics provides haptics, …)`
  vararg.
- Test: `app/src/androidTest/kotlin/com/snipsnap/app/ui/BusyBarTest.kt`

**Interfaces:**
- Consumes: `Busy`, `BusyLatch` (Task 1).
- Produces:
  - `val LocalReduceMotion = staticCompositionLocalOf { false }`
  - `fun Context.animationsOff(): Boolean`
  - `@Composable fun Spinner(color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp)`
  - `@Composable fun BusyBar(busy: Busy, color: Color, modifier: Modifier = Modifier)`.
    It is 4 dp tall and fills the width.
  - `@Composable fun LoadingPanel(label: String, modifier: Modifier = Modifier)`.
    It is an `lcdPanel` box with the centred label in
    `TapeType.lcdSmall` and an indeterminate `BusyBar` under it. It
    merges semantics with `contentDescription = label`.
  - `@Composable fun rememberBusyVisible(busy: Busy?): Boolean`. It drives
    a `BusyLatch` with `SystemClock.uptimeMillis()`, and a
    `LaunchedEffect` that delays until `nextChangeAt`.

- [ ] **Step 1: Write the failing device tests** in `BusyBarTest`
  (extends `ComposeScreenTest`). Wrap the content in
  `CompositionLocalProvider(LocalReduceMotion provides true)` unless the
  test says otherwise, because infinite transitions under the frozen
  clock are the hazard the base class KDoc warns about.
  - `a determinate bar reports its fraction`.
    `BusyBar(Busy("X", 5, 16), Color.White)`. One node has
    `ProgressBarRangeInfo(0.3125f, 0f..1f)`.
  - `an indeterminate bar says so`. `BusyBar(Busy("X"), …)`. The node
    has `ProgressBarRangeInfo.Indeterminate`.
  - `the loading panel names its job`. `LoadingPanel("LOADING THE LIBRARY…")`.
    `onNodeWithContentDescription("LOADING THE LIBRARY…")` exists.
  - `the spinner draws without animation under reduced motion`.
    `Spinner(Color.White)`, then `pump()`. The node with
    `ProgressBarRangeInfo.Indeterminate` exists, and `compose.mainClock`
    can advance 2,000 ms without hanging.
- [ ] **Step 2: Compile and confirm the tests fail:**
  `:app:compileDebugAndroidTestKotlin`, with unresolved references.
- [ ] **Step 3: Implement.**
  - `BusyBar`:
    - **Determinate:** a filled rect of width `fraction`, with
      `progressSemantics(fraction)`.
    - **Indeterminate:** a 30%-wide segment sweeping left to right over
      `Motion.BUSY_SWEEP_MS`, linear, repeating, with
      `progressSemantics()`.
    - **Reduced motion, indeterminate:** a static full-width rect at 40%
      alpha.
  - `Spinner`: a 270° arc rotating over `Motion.SPINNER_MS`. Static under
    reduced motion. `progressSemantics()`.
  - `animationsOff()` reads
    `Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f`.
  - In `App.kt`, use `val reduceMotion = remember { context.animationsOff() }`.
- [ ] **Step 4: Run the device tests.**
  `./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.snipsnap.app.ui.BusyBarTest`
  exits 0.
- [ ] **Step 5: Commit:** "Draw a job in flight: bar, spinner and loading
  panel, still when animations are off".

### Task 3: the dock shows `Busy`, with bar, count and Cancel

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/Frame.kt:337-430`
  (`Dock`).
- Modify: `app/src/main/kotlin/com/snipsnap/app/App.kt`:
  - :456 becomes `var busy by remember { mutableStateOf<Busy?>(null) }`.
  - Each of the 27 sets (listed below) changes from `busy = Copy.X` to
    `busy = Busy(Copy.X)`.
  - :3326, :3646 and :3944 stay `busy != null`.
  - :4131 passes `busy`.
- Test: `app/src/androidTest/kotlin/com/snipsnap/app/ui/DockBusyTest.kt`

The 27 sets in `App.kt`: 1171, 1183, 1263, 1297, 1328, 1379, 1437, 1512,
1555, 1660, 1713, 1777, 2031, 2162, 2213, 2258, 2298, 2320, 2345, 2386,
2410, 2438, 2478, 2533, 2568, 2613 and 2652. Re-grep before editing, since
line numbers drift.

**Interfaces:**
- Consumes: `Busy`, `rememberBusyVisible`, `BusyBar` (Tasks 1–2).
- Produces: `fun Dock(dock: DockState, busy: Busy?, onArm…, onTakeDoor…, modifier)`.
  Same parameters as today; only the type of `busy` changes.

- [ ] **Step 1: Write the failing device tests** in `DockBusyTest`, with
  reduced motion provided:
  - `nothing shows in the first 200 ms`. Set content with
    `busy = Busy("LOADING FACTORY…")`, then `pump(150)`. The text
    `"LOADING FACTORY…"` does not exist, and no node has progress
    semantics.
  - `the bar, label and count show after 200 ms`. Use
    `Busy("LOADING FACTORY…", 5, 16)`, then `pump(250)`.
    `"LOADING FACTORY…"` and `"5 OF 16"` are displayed, and a progress
    node reads 0.3125.
  - `a long label keeps its count`. Use a label of 60 `"X"` characters
    with `done = 3`, `total = 8`, then `pump(250)`. `"3 OF 8"` is
    displayed.
  - `cancel appears only when the job can stop`. With
    `cancel = { cancelled = true }`, `tap("CANCEL")` sets the flag.
    Without it, `"CANCEL"` does not exist.
  - `the door hides while busy`. A `DockState` with a door and a busy
    job, `pump(250)`. The door label does not exist.
- [ ] **Step 2: Compile the tests and confirm they fail.**
- [ ] **Step 3: Implement.**
  - `val visible = rememberBusyVisible(busy)`.
  - Keep the last non-null `Busy` in a `remember`ed holder, so the
    500 ms linger shows the last label and not a blank.
  - When visible:
    - Overlay `BusyBar(shown, scheme.amber.tape)` at `Alignment.TopCenter`
      inside the dock's existing box, adding no height.
    - The line text is `shown.label`.
    - If `shown.total != null`, draw a separate `TapeText(Copy.busyCount(done, total))`
      after the line, without `weight`, so it never ellipsizes.
    - If `shown.cancel != null`, draw `DockChip(Copy.CANCEL_JOB, …, onClick = shown.cancel)`
      in place of the door.
  - When not visible, the dock behaves exactly as today.
- [ ] **Step 4: Migrate the 27 sets in `App.kt`** to `Busy(...)`. It is a
  mechanical edit, so confirm with
  `grep -n "busy = Copy\|busy = \"" app/src/main/kotlin/com/snipsnap/app/App.kt`
  returning nothing.
- [ ] **Step 5: Run the fast gate and `DockBusyTest`.** Both exit 0.
- [ ] **Step 6: Commit:** "Show the job in flight in the dock: a bar,
  its count, and Cancel where it can stop".

### Task 4: starter kits land behind placeholders, and the label says Loading

**Files:**
- Create: `app/src/main/kotlin/com/snipsnap/app/ui/PadPlaceholders.kt`
- Modify:
  - `App.kt` `fresh()` (:1261-1286); new state
    `var pendingKit by remember { mutableStateOf<String?>(null) }` beside
    `busy`; the `KitScreen(` call (:3623), which passes
    `pendingKitName = pendingKit`; the `KitsScreen(` call (:3319), which
    passes `startingKit = pendingKit != null`.
  - `ui/KitScreen.kt:131`, adding the parameter
    `pendingKitName: String? = null` and an early branch before
    `if (entry == null)` (:229).
  - `ui/KitsScreen.kt`, removing the "DUBBING…" literals (:610, :632)
    and adding `startingKit: Boolean = false`.
  - `KitsScreen.kt:1259` `PrimaryAction` and `PadSheetScreen.kt:4813`
    `ActionButton`: add `working: Boolean = false`.
  - `ui/KitScreen.kt:1345`, removing the "DUBBING…" literal.
- Test: `app/src/androidTest/kotlin/com/snipsnap/app/ui/PadPlaceholderGridTest.kt`

**Interfaces:**
- Consumes: `Busy`, `Spinner`, `LocalReduceMotion`, `Copy.loadingBusy`,
  `Copy.LOADING_BUSY`.
- Produces:
  - `@Composable fun PadPlaceholderGrid(name: String, modifier: Modifier = Modifier)`
  - `PrimaryAction(label: String, enabled: Boolean, working: Boolean = false, onClick: () -> Unit)`.
    `working` goes before `onClick` so trailing-lambda callers still
    compile.
  - `ActionButton(label, scheme, enabled, dimmed, lit, working: Boolean = false, accessibilityLabel, modifier, onClick)`. When `working`, it
    draws a `Spinner` left of the label and sets
    `stateDescription = Copy.WORKING_BUSY`.

- [ ] **Step 1: Write the failing device tests** in
  `PadPlaceholderGridTest` (reduced motion on):
  - `the placeholder grid names the kit it is loading`.
    `PadPlaceholderGrid("FACTORY")`.
    `onNodeWithContentDescription("LOADING FACTORY…")` exists and has
    `ProgressBarRangeInfo.Indeterminate`.
  - `a working button keeps its label and shows a spinner`.
    `PrimaryAction("NEW KIT ▸ STARTERS", enabled = false, onClick = {}, working = true)`.
    The text exists, a node with indeterminate progress exists, and the
    button is not enabled.
- [ ] **Step 2: Compile the tests and confirm they fail.**
- [ ] **Step 3: Implement `PadPlaceholderGrid`.**
  - Four rows of four pad-shaped boxes: `Layout.KIT_PAD_H` height,
    `Layout.PAD_GAP` gaps, `raisedBevel` skin.
  - Alpha pulses between 0.45 and 1.0 over `Motion.PLACEHOLDER_PULSE_MS`.
    Under reduced motion it is a static 0.6.
  - The header line is `Copy.loadingBusy(name)`.
  - The grid node merges semantics, with the description
    `Copy.loadingBusy(name)` and `progressSemantics()`.
- [ ] **Step 4: Rework `fresh()`.**
  - After the guard, set
    `busy = Busy(Copy.loadingBusy(starter.displayName))`, then
    `pendingKit = starter.displayName`, then `goToScreen(AppScreen.KIT)`.
  - **On failure:** `pendingKit = null`, `busy = null`, return to the
    AppScreen entry that `locationOf` maps to `Place.SHELF`, then
    `dock.say(Copy.CREATE_FAILED)`.
  - **On success**, in this order: `kits = …`, `dock.say(…)`,
    `landKit(entry)`, then `pendingKit = null` and `busy = null`. This
    closes today's gap, where `busy` cleared before `landKit` (:1277
    against :1285).
  - Replace the `Copy.dubbingBusy` call, then delete `dubbingBusy` from
    `Personality.kt`.
- [ ] **Step 5: Wire the screens.**
  - `KitScreen`: `if (pendingKitName != null) { PadPlaceholderGrid(pendingKitName); return }`
    before the `entry == null` branch.
  - `KitsScreen`: the NEW KIT ▸ STARTERS buttons keep their label.
    Disabled while `busy`, as today, and `working = startingKit`.
  - `KitScreen.kt:1345`: keep `"$kind ▸ NEW TAPE"`. It gets
    `working = busy && startedHere`, where `startedHere` is a local
    `remember` flag set on click and reset by
    `LaunchedEffect(busy) { if (!busy) startedHere = false }`.
- [ ] **Step 6: Run the fast gate, `PadPlaceholderGridTest`,
  `StarterMenuTest` and `ExportScreenTest`.** All exit 0. The last two
  cover `PrimaryAction` and `ActionButton` callers.
- [ ] **Step 7: Commit:** "Land a starter behind sixteen placeholders,
  and say Loading, not Dubbing".

### Task 5: cold start shows the Library loading, not an empty Library

**Files:**
- Modify:
  - `App.kt`: add `var kitsLoaded` and `var projectsLoaded`
    (`mutableStateOf(false)`) beside `kits` (:353). Set
    `kitsLoaded = true` after the first `kits = …` in the
    `LaunchedEffect(Unit)` (:1054). Set `projectsLoaded = true` after
    `projects` is assigned in the `LaunchedEffect(kits, onShelf, …)`
    (:510). Pass `loading = !(kitsLoaded && projectsLoaded)` to
    `KitsScreen`.
  - `ui/KitsScreen.kt`: add the parameter `loading: Boolean = false`.
- Test: `app/src/androidTest/kotlin/com/snipsnap/app/ui/KitsScreenLoadingTest.kt`

**Interfaces:**
- Consumes: `LoadingPanel`, `Copy.LIBRARY_LOADING`.
- Produces: `KitsScreen(…, loading: Boolean = false, …)`.

- [ ] **Step 1: Write the failing device tests** (reduced motion on):
  - `a loading library says so and offers no first-run button`.
    `KitsScreen(kits = emptyList(), projects = emptyList(), loading = true, …)`.
    `Copy.LIBRARY_LOADING` is displayed. `Copy.EMPTY_SHELF` and
    `Places.CATCH_FIRST` do not exist.
  - `an empty library after loading shows the empty state`. The same
    with `loading = false`: `Copy.EMPTY_SHELF` and `Places.CATCH_FIRST`
    exist.
- [ ] **Step 2: Compile the tests and confirm they fail.**
- [ ] **Step 3: Implement.** When `loading`, draw
  `LoadingPanel(Copy.LIBRARY_LOADING)` in the list area, in place of both
  the empty block (:418) and the list (:473). Skip the action-button
  block (:592-635). `coldShelf` and `projectFirst` are evaluated only
  when not `loading`.
- [ ] **Step 4: Run the fast gate and `KitsScreenLoadingTest`.** Both
  exit 0.
- [ ] **Step 5: Commit:** "Say the library is loading until it has
  loaded".

### Task 6: device check and PR 2a

- [ ] **Step 1:** Run
  `./gradlew --no-daemon :app:installDebug` and launch
  `com.snipsnap.app.brassgrow/com.snipsnap.app.MainActivity`.
- [ ] **Step 2:** Record a starter load. Run
  `adb shell screenrecord --time-limit 8 /sdcard/loading-a.mp4`, pick
  Library ▸ NEW KIT ▸ STARTERS ▸ TIDAL, then pull the recording. Also
  take screenshots about 300 ms and 1.5 s after the pick. Expected:
  - the dock bar sweeps
  - "LOADING TIDAL…" shows
  - Pads shows 16 pulsing placeholders, then the kit
- [ ] **Step 3:** Cold start. Run `adb shell am force-stop …`, relaunch,
  and screenshot within 300 ms. Expected: "LOADING THE LIBRARY…", never
  "NOTHING TAPED YET." on a populated library.
- [ ] **Step 4:** Reduced motion. Run
  `adb shell settings put global animator_duration_scale 0`, repeat
  step 2 and check that the bar and placeholders are static. Then run
  `adb shell settings put global animator_duration_scale 1`.
- [ ] **Step 5:** EMPTY GRID starter. Expected: no bar ever appears,
  because the job finishes in under 200 ms.
- [ ] **Step 6:** Forced failure (review focus 3). Temporarily make
  `shelf.render` throw in a local debug build, pick a starter, and check
  that you land on Library with `CREATE_FAILED`. Revert, and don't
  commit this.
- [ ] **Step 7:** Merge the base in. Push `claude/loading-a`. Open the
  PR against the default branch. Its body gives the why (owner,
  2026-10-27: "a loading bar or spinner that draws attention"), what
  changed, the gate and device-test exit codes, the screenshot and
  recording paths, and what was not run.

---

## PR 2b: screens report their own jobs; Record and Chop load visibly

### Task 7: the busy sink

**Files:**
- Modify:
  - `app/src/main/kotlin/com/snipsnap/app/ui/Busy.kt`: add
    `LocalBusySink` and `ReportBusy`.
  - `App.kt`: add `var screenBusy by remember { mutableStateOf<Busy?>(null) }`.
    Provide `LocalBusySink provides { screenBusy = it }` at :3173. The
    Dock call (:4131) passes `busy = busy ?: screenBusy`.
- Test: `app/src/androidTest/kotlin/com/snipsnap/app/ui/ReportBusyTest.kt`

**Interfaces:**
- Produces:
  - `val LocalBusySink = staticCompositionLocalOf<(Busy?) -> Unit> { {} }`
  - `@Composable fun ReportBusy(busy: Busy?)`. It is a
    `DisposableEffect(busy)` that calls the sink with `busy` and, on
    dispose, with `null`.

- [ ] **Step 1: Write the failing test.** `ReportBusyTest`:
  `a screen's job reaches the sink and leaves with the screen`. Provide
  a sink that records into a list. Show `ReportBusy(Busy("X"))`, then
  remove it with a `var shown` toggle and pump. The list ends with
  `[Busy("X"), null]` (compare labels).
- [ ] **Step 2: Implement, then run the test and the fast gate.** Both
  exit 0.
- [ ] **Step 3: Commit:** "Let a screen's own job reach the dock".

### Task 8: the pad editor raises its guard before launching and names its jobs

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/PadSheetScreen.kt`:
  - the local `busy` (:353) plus new `var padJob by remember(model) { mutableStateOf<String?>(null) }`
  - every guard-then-launch site (see the step)
  - :3369 MAKE PAD
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/SplitScreen.kt`
  (:256-304, and the concession at :469).
- Modify: `Personality.kt`, adding `MUTATE_BUSY = "MUTATING…"`,
  `DESAMPLE_BUSY = "DE-SAMPLING…"`, `MAKE_PAD_BUSY = "MAKING THE PAD…"`,
  `MAKE_INSTRUMENT_BUSY = "MAKING THE INSTRUMENT…"`,
  `SPREAD_BUSY = "SPREADING…"` and `SPLIT_BUSY = "SPLITTING…"`, plus their
  `notASentence` entries.
- Test: `shell/src/test/kotlin/com/snipsnap/shell/ConventionTest.kt`, a
  new law.

- [ ] **Step 1: Write the failing law** in `ConventionTest`:
  `a busy guard is raised before its launch, not inside it`. Read
  `PadSheetScreen.kt` and `SplitScreen.kt` as text, using the same
  source-reading helper the file's other laws use. Assert that no match
  of `Regex("""launch\s*\{\s*\n\s*(busy|working)\s*=\s*true""")` exists,
  and name the offending line numbers in the message.
- [ ] **Step 2: Run it and confirm it fails** on today's sites:
  `PadSheetScreen.kt` :1012 and the MUTATE, DE-SAMPLE, MAKE PAD, MAKE
  INSTRUMENT and SPREAD launches; `SplitScreen.kt` :292-region.
- [ ] **Step 3: Move each `busy = true` to before its `appScope.launch {`.**
  Keep the `finally { busy = false }` inside. At each site also set
  `padJob = Copy.X` (and clear it in the same `finally`). The jobs are
  treatment, mutate family, de-sample, make pad, make instrument and
  spread.
  - Add `ReportBusy(if (busy) Busy(padJob ?: Copy.WORKING_BUSY) else null)`
    once near the top of the screen.
  - MAKE PAD (:3369) keeps its label `"MAKE PAD ▸ INSTRUMENT"` with
    `working = busy && padJob == Copy.MAKE_PAD_BUSY`.
- [ ] **Step 4: Run the law, the fast gate and `PadSheetScreenTest`.** All
  exit 0.
- [ ] **Step 5: Commit:** "Raise the pad editor's busy guard before it
  launches, and name each job in the dock".

### Task 9: Record and Chop show "reading" instead of a blank box

**Files:**
- Modify: `ui/TapeScreen.kt:359` and `ui/ChopScreen.kt:182-184`.
- Modify: `Personality.kt`, adding
  `READING_RECORDING = "READING THE RECORDING…"` plus its `notASentence`
  entry.

- [ ] **Step 1:** In `TapeScreen`, replace the blank-box branch
  `else Box(Modifier.fillMaxSize().lcdPanel(scheme))` with
  `else LoadingPanel(Copy.READING_RECORDING, Modifier.fillMaxSize())`.
  Add `ReportBusy(if (loaded == null && !failed) Busy(Copy.READING_RECORDING) else null)`.
- [ ] **Step 2:** Do the same in `ChopScreen`. There, "decoding" means
  `sourceFile == null && model == null && emptyReason == null`.
- [ ] **Step 3: Run the fast gate,** then on the device: Record a
  10-minute file through the share sheet, or open the longest tape on
  the emulator, and screenshot within 300 ms of opening Record. Expected:
  "READING THE RECORDING…" with a moving bar.
- [ ] **Step 4: Commit:** "Say the recording is being read instead of
  showing a blank deck".

### Task 10: loading animations stop under reduced motion, then PR 2b

**Files:**
- Modify: `ui/ExportScreen.kt:1508` `DubReel` and `ui/GroupBox.kt:148`
  `ReelsStrip`. When `LocalReduceMotion.current`, don't start the
  infinite transition; draw the static frame.
- Leave alone: the tape-position reels in `TapeScreen.kt:1533/1543/1558`.
  They show real position (spec §7.6).

- [ ] **Step 1:** Implement both, then run the fast gate,
  `ExportScreenTest` and `BusyBarTest`. All exit 0.
- [ ] **Step 2:** On the device, with `animator_duration_scale 0`, open
  Export and write a kit. The reel is static, and the bar still advances.
- [ ] **Step 3:** Commit "Stop the loading reels when animations are
  off". Merge the base in, push `claude/loading-b`, and open the PR as
  in Task 6 step 7.

---

## PR 2c: honest Export progress, and the silent operations

### Task 11: Export reports the files it actually wrote

**Files:**
- Modify: `kit/src/main/kotlin/com/snipsnap/kit/KitExporter.kt`:
  - `exportProgramFolder` (:80)
  - `buildSlots` (:126), which gains `onFile: (File) -> Unit = {}`
  - `copySample` (:144), which calls `onFile(dst)` after `written += dst`
    (:149)
- Modify: `kit/src/main/kotlin/com/snipsnap/kit/ExportFormats.kt:101`.
  `Exporters.export(…, onFile: (File) -> Unit = {})` passes it to every
  exporter whose writer goes through `buildSlots`. Exporters that don't
  report keep the default.
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/ExportWizard.kt:155`,
  `write(destRoot: File, overwrite: Boolean = true, onFile: (Int) -> Unit = {})`,
  which reports the cumulative count. Also :250, where `dubLabel`
  becomes `"EXPORTING…"`.
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/ExportScreen.kt`:
  - delete the tick loop (:410-418) and the `filesShown` timer
    arithmetic (:758-762)
  - add `var written by remember { mutableIntStateOf(0) }`, updated on
    the main thread from `onFile`
  - `DubProgressCard` stays indeterminate (`BusyBar(Busy(label))`) until
    `written > 0`, then becomes determinate against `model.fileCount`
  - `ReportBusy(if (session.busy) Busy(model.dubLabel, written, model.fileCount.takeIf { written > 0 }) else null)`
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Schemes.kt:585`.
  Remove `DUB_FILE_MS` if no reader remains.
- Test: `kit/src/test/kotlin/com/snipsnap/kit/KitExporterTest.kt`, and
  `shell/src/test/kotlin/com/snipsnap/shell/ExportWizardTest.kt`.
  Extend the existing files; if either is absent, create it in that
  module's style.

- [ ] **Step 1: Write the failing tests.**
  - `program-folder export reports every sample it copies`. Export a
    3-pad test kit (use the module's existing kit fixture) with a
    recording `onFile`. The recorded files equal the `.wav` files in the
    output folder.
  - `the wizard's write counts up to the samples written`. The `onFile`
    counts are `1, 2, …, n`, strictly increasing, and `n` equals the
    number of `.wav` files written.
- [ ] **Step 2: Run them and confirm they fail.**
  `./gradlew --no-daemon :kit:test --tests '*KitExporterTest' :shell:test --tests '*ExportWizardTest'`
- [ ] **Step 3: Implement** the threading and the screen change.
- [ ] **Step 4: Run the fast gate and `ExportScreenTest`.** Both exit 0.
  Update any `ExportScreenTest` assertion that relied on the timer, and
  say so in the commit body.
- [ ] **Step 5: Commit:** "Count the files Export really wrote, and stop
  timing a fake bar".

### Task 12: real counts, and Cancel where the loop is ours

Spec §7.6: determinate wherever the count is known; Cancel only where the
work loops and can stop between items; additive jobs keep finished work.

**Files:**
- Modify `App.kt`:
  - `chopAll` (:1703). It loops over the picked files, so set
    `busy = Busy(Copy.CHOP_ALL_BUSY, i, uris.size, cancel = { stop = true })`
    before each file. Check `stop` between files. On a stop, keep the
    kits already made and say `Copy.stoppedAfter(i, n)`.
  - `evilTwins` (:1500), `dustAll` (:1542) and `finishBreed` (:1657)
    call into `KitShelf`. Read the `KitShelf` function each one calls
    (`evilTwins`, the dust path, `breed` at `KitShelf.kt:290/311`):
    - **Loop in `KitShelf`:** if the per-pad loop is in `KitShelf`
      itself, add `onPad: (done: Int, total: Int) -> Unit = { _, _ -> }`
      and report `Busy(label, done, total)`. No Cancel: these overwrite
      pads or build a kit in one write.
    - **Loop deeper:** if the loop lives deeper (`:kit`/`:synth`), leave
      the job indeterminate and record that in the commit body.
- Modify: `ui/SynthScreen.kt`. Its Make Instrument already counts
  (`Copy.instrumentRendering(done, total)`, :4418) and cancels (:4267).
  Add `ReportBusy(Busy(Copy.instrumentRendering(done, total), done, total, cancel = ::closeHeld))`
  while it runs, so the dock shows it too.
- Modify: `Personality.kt`, adding
  `fun stoppedAfter(done: Int, total: Int) = "STOPPED AFTER $done OF $total."`.
- Test: `shell/src/test/kotlin/com/snipsnap/shell/PersonalityTest.kt`.
  `stoppedAfter(3, 8)` is `"STOPPED AFTER 3 OF 8."`.

- [ ] **Step 1: Write the copy test, run it, and confirm it fails.**
- [ ] **Step 2: Implement Chop All's count and Cancel.** On the device,
  pick 5 files, cancel after the second, and check that Library holds
  exactly the kits finished and the dock says "STOPPED AFTER 2 OF 5.".
- [ ] **Step 3: Implement the `onPad` threading where the loop is in
  `KitShelf`,** and the Synth `ReportBusy`. Run the fast gate; it exits
  0.
- [ ] **Step 4: Commit:** "Count Chop All, Breed, Dust All and bank B
  variations in the dock, and let Chop All stop between files".

### Task 13: the silent operations get guards and a voice

**Files:**
- Modify `App.kt` handlers:
  - `openProject` :2839, `openKit` :2852, `activateKit` :2887,
    `addKitToProject` ~:2915 and `newProject` :2939 each start with
    `if (busy != null) return`.
  - `newProject` sets `busy = Busy(Copy.NEW_PROJECT_BUSY)` before its
    IO and clears it after `openProject`.
  - `setKey` :1473 and `inKey` :2721: read the comments at :1488 and
    :2737, which explain why they set no busy. Report through
    `screenBusy = Busy(Copy.KEY_BUSY)` so other controls stay enabled,
    and clear it in `finally`.
- Modify: `app/src/main/kotlin/com/snipsnap/app/PadHost.kt:99-113`. Add
  `var loading by mutableStateOf(false) private set`, true around
  `engine.load`.
- Modify: `ui/KitScreen.kt`, adding
  `ReportBusy(if (host.loading) Busy(Copy.KIT_LOADING_BUSY) else null)`.
- Modify: `ui/KeysScreen.kt:84-92`, `ui/SurfaceScreen.kt:466` and
  `ui/TakesBinScreen.kt:247-345`. Each adds `ReportBusy` with
  `Copy.KEYS_LOADING_BUSY`, `Copy.SURFACE_LOADING_BUSY` and
  `Copy.RESTORE_BUSY` (the existing `ROOM_RESTORE_BUSY` wording is
  "RESTORING…"; reuse that string).
- Modify: `Personality.kt`, adding `NEW_PROJECT_BUSY = "STARTING THE PROJECT…"`,
  `KEY_BUSY = "SETTING THE KEY…"`, `KIT_LOADING_BUSY = "LOADING THE KIT…"`,
  `KEYS_LOADING_BUSY = "LOADING THE KEYS…"`,
  `SURFACE_LOADING_BUSY = "LOADING THE PAD…"` and
  `RESTORE_BUSY = "RESTORING…"`, plus their `notASentence` entries.
- Test: `ConventionTest`, a new law:
  `every App handler that writes the shelf is guarded`. For the five
  handlers named above, assert the first statement of each `fun` body
  in `App.kt` source matches `if (busy != null) return`. Use the file's
  existing source-reading helpers.

- [ ] **Step 1: Write the law and confirm it fails.**
- [ ] **Step 2: Implement.** Run the law and the fast gate; both exit 0.
- [ ] **Step 3: Device check.**
  - Double-tap NEW PROJECT ▸ quickly: exactly one new project appears on
    Library.
  - Open Keys on an instrument: "LOADING THE KEYS…" shows if the
    decode takes longer than 200 ms.
- [ ] **Step 4: Commit:** "Guard the project handlers against a double
  tap, and give the silent loads a line". Merge the base in, push
  `claude/loading-c`, and open the PR as in Task 6 step 7.
