# BECOME's Card Row (A1b) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Put BECOME on the phone. The MUTATE card gets a second `StepperSlider` row under the move's knob. On MORPH it reads `BECOME` (`OFF`, or `400 ms`); every other move shows the same disabled `—` row, so the card never jumps. HEAR and KEEP both play what the row shows, and a DRIFT tap puts the row back to OFF. A1 landed the sound, and the owner approved it at the A1 gate on 2026-10-02, so the gate for this plan is the owner's phone check, not a listen.

**Architecture:** A1 already put every piece of BECOME's logic the card needs into `:shell`, with tests: `MutateSheet.BECOME`, `becomeFor`, `label`, `value`, the sixth `Knobs` slot that keeps HEAR equal to KEEP, and the rule that every move but MORPH ignores a dialled BECOME. So this plan adds no `:shell` production code. It adds two sheet tests that pin the contracts the card now leans on, then edits `PadSheetScreen.kt` in two steps. Each step starts with a `ConventionTest` source-text law, run red first:
- **The state and the two doors.** `pendingBecome` is a `remember(slot)` float held beside `mutateKnobs`. `onMutate` and `onHear` pass it to `MutateSheet.apply` and `preview`, and `onDrift` resets it.
- **The row.** `MutateCard` gains its second `StepperSlider`, and the card call wires it.

`:app` cannot be compiled here, so the compiler checks run in CI (`android-build`, plus `emulator-tests` because the PR touches `app/**`), and the owner's phone has the last word.

**Tech Stack:** Kotlin, Jetpack Compose (`:app`, not compiled in this environment), Gradle (`./gradlew --no-daemon`), `kotlin.test`. The sheet side is `:shell`'s `MutateSheet`, `Knob` and `KitBuilderModel`. The laws use `:shell`'s `ConventionTest`, which reads `../app/src/main/kotlin/com/snipsnap/app/ui/PadSheetScreen.kt` as text.

**Spec:** [`docs/superpowers/specs/2026-09-30-become-strung-say-design.md`](../specs/2026-09-30-become-strung-say-design.md). The relevant sections:
- "The card and the sheet", every bullet, including the recorded deviation now written into it.
- "Testing", BECOME's "The :app laws".
- "Phasing and gates": the A1b row, and "The A1 gate: BECOME" with its "Answered 2026-10-02" paragraph.
- "Decisions already taken": the three A1 gate rows.
- "Decisions for the owner": Decisions 1 and 2 (taken 2026-10-02), Decision 3 at its default, and Decision 14 (not triggered).

The plan it follows is [`2026-09-30-a1-become.md`](2026-09-30-a1-become.md). Its Task 4 interfaces and its closing A1b note set this plan's surfaces. Code cites are at this branch's head, `28523f4b`. Line numbers move as each task edits the screen, so every edit below is located by its quoted text, and the line numbers are a guide only.

## Global Constraints

- The A1 gate stands as answered on 2026-10-02 (Q1 "They all do", Q2 "A different sound", Q3 "6 is as good or better"). `MutateSheet.BECOME` stays `Knob("BECOME", 0f, Mutate.MAX_BECOME_MS.toFloat(), 0f, exponential = false)`: linear, 0 to 2000 ms, 50 ms steps at the phone's 1/40 snap, OFF at 0 (Decision 2's default). None of the spike's five extras is added (Decision 1's default).
- **Recorded deviation (from the A1 plan's closing note and the preflight ruling):** the BECOME row's enabled state keys on `becomeLabel != null` (MORPH only), not on `knobLabel` as the spec words it, because `knobLabel` is non-null for SPLICE, SPLIT, ROOM and TRANSPLANT, which ignore BECOME. The row is `enabled = !busy && becomeLabel != null`, and a ConventionTest law pins it, so it is not left to review by eye.
- The holder is `var pendingBecome by remember(slot) { mutableFloatStateOf(MutateSheet.BECOME.defaultFraction) }`, declared beside `mutateKnobs`.
- `mutateKnobs` stays declared exactly as J24's law reads it: `val mutateKnobs = remember(slot) { mutableStateMapOf<String, Float>() }`. `remember(slot, mutateMode)` is never written.
- DRIFT's reset is `pendingBecome = MutateSheet.BECOME.defaultFraction`. It goes on its own line after `onDrift`'s `if (!onMorph) { … }` block and before `appScope.launch`, and it runs for every tap. The `if (!onMorph)` block is not edited, because J24's `law - DRIFT leaves the knob showing the blend it actually used` reads it.
- The reset is spelled as the knob's own default, where the spec writes `pendingBecome = 0f`. That keeps the opening value and the reset as one quantity in one place, and Task 1 pins it at 0, OFF.
- Both doors read `val becomeFraction = pendingBecome` beside `val fraction = pendingMutateKnob`, before their coroutine starts.
  - KEEP calls `MutateSheet.apply(f, slot, who, move, fraction, becomeFraction)`.
  - HEAR calls `MutateSheet.preview(m, slot, who, move, fraction, becomeFraction)`.
- The second row sits directly under the move's knob row in `MutateCard` and is drawn for every move. Its arguments:
  - `label = becomeLabel ?: "—"`
  - `fraction = if (becomeLabel == null) 0f else becomeFraction`
  - `valueText = becomeText`
  - `fillColor = padColor`
  - `enabled = !busy && becomeLabel != null`
  - `onFractionChange = onBecomeChange`
  - `onFractionCommit = {}`
- The card call passes these values:
  - `becomeLabel = becomeKnob?.label`
  - `becomeFraction = pendingBecome`
  - `becomeText = becomeKnob?.let { MutateSheet.label(it, MutateSheet.value(it, pendingBecome)) } ?: ""`
  - `onBecomeChange = { f -> pendingBecome = (f * 40f).roundToInt() / 40f }`, the knob row's own snap, character for character
- `becomeKnob` is `val becomeKnob = MutateSheet.becomeFor(MutateSheet.modeFor(mutateMode))`, declared beside `mutateKnob`.
- No `Copy` string is added. The keep toast stays `Copy.mutated(mutateMode, padName, MutateSheet.name(who))` and says MORPH (Decision 3). The row's words live in `MutateSheet` beside AT, HZ and BANDS, where `PersonalityTest`'s `every Copy string constant shouts and stops (reflective)` (`PersonalityTest.kt:726`) cannot see them, so Task 1 holds them to the house style instead. Any `Copy` string added later must pass that law and "jokes never gate function" (`docs/PERSONALITY.md`, law 3).
- No `:shell` production change: `MutateSheet`, `Mutate`, `Knob` and the CLI are untouched. There is no version bump: `FxChain.VERSION` stays 1, `PadRecipe.VERSION` stays 2, and `kit.json` gains no field.
- `:app` is not compiled here. Its edits are checked by `ConventionTest`, then by CI (`android-build`, and `emulator-tests`, which runs because the PR touches `app/**`), then by the owner's phone. Never claim a compile you did not see.
- Run `./gradlew --no-daemon` from the worktree root, and judge green by Gradle's exit code, never by grepping output.
  - With no Android SDK the suite line is `./gradlew --no-daemon test` (no `-x :app:test`; `:app` is not in the graph).
  - Where `./gradlew --no-daemon projects` lists `:app`, it is `./gradlew --no-daemon test -x :app:test`.
- J24's two laws (`law - a move's dialled knob is remembered per move, not reset by switching move`, `law - DRIFT leaves the knob showing the blend it actually used`) pass unedited.
- Never skip, disable or loosen a test or a law. A law is proved by watching it fail with its own message before the edit.
- Commit titles are plain declarative prose (no `feat:`). No maker or product names and no model identifier appear in code, comments, commits or PR text.
- A KDoc block sits directly above its declaration (`a doc-comment is never stranded above another doc-comment`), and notes inside function bodies are `//`. No KDoc line puts two asterisks next to a slash (`no Kotlin source ends a doc comment by accident`).

## Review Focus

- **A dropped argument that still compiles.** `preview` and `apply` default `becomeFraction` to 0, so a door that forgets it compiles and plays a flat MORPH while the row says 400 ms, and no `:shell` test can see it. Pinned in Task 2 (`law - HEAR and KEEP hand MutateSheet the BECOME the card shows`).
- **A row copied from the knob row.** The likely slip is `enabled = !busy && knobLabel != null`, which lights BECOME on four moves that ignore it. Pinned in Task 3 (`law - MUTATE draws BECOME's row on every move, enabled by BECOME's own label`), which also refuses `knobLabel` anywhere in BECOME's row.
- **DRIFT leaves a ramp on screen.** DRIFT is a flat morph. If BECOME kept a remembered 400 after a DRIFT tap, the card would show a value the drift did not use, which is J24's bug again. Pinned in Task 2 (`law - BECOME is remembered per pad, and DRIFT puts it back to OFF`) and in Task 1 (`a DRIFT from the card carries no BECOME, so the row's reset to OFF tells the truth`).
- **A snap that lands between steps.** If the BECOME row snapped differently from the knob row, a thumb could land on 26 ms, under one 23 ms analysis window, where a ramp reads as a step. Pinned in Task 1 (`the BECOME row opens OFF, lands on 50 ms steps wherever a thumb lets go, and shouts its label`) and in Task 3's law, which holds the two snaps equal.
- **The text and the fraction from two places.** The row's readout and its fraction must both come from `pendingBecome`, and the holder must be one value per pad, never one per move. Pinned in Task 3's law (`becomeText` reads `pendingBecome`) and in Task 2's law (the holder's exact declaration). A1's `a BECOME left dialled does nothing to a move that is not MORPH` covers the stale value on another move.

---

## File Structure

| File | Responsibility |
|---|---|
| Modify `shell/src/test/kotlin/com/snipsnap/shell/MutateSheetTest.kt` (before the closing brace, `:453`) | two pins of what the row leans on: the opening value reads OFF, the snap lands on 50 ms steps, the words follow the house style, and a DRIFT carries no BECOME |
| Modify `shell/src/test/kotlin/com/snipsnap/shell/ConventionTest.kt` (after `law - DRIFT leaves the knob showing the blend it actually used`, `:1901-1912`) | three laws: the holder and DRIFT's reset; HEAR and KEEP passing BECOME; the row and its call |
| Modify `app/src/main/kotlin/com/snipsnap/app/ui/PadSheetScreen.kt` | `pendingBecome` and `becomeKnob` beside `mutateKnobs` (`:1240-1242`); `onMutate` (`:1264`); `onHear` (`:1328`); `onDrift` (`:1400`, after the `if (!onMorph)` block at `:1432-1445`); the card call (`:2548-2577`); `MutateCard`'s KDoc (`:3473-3487`), parameters (`:3506-3510`) and second `StepperSlider` (after `:3722-3732`) |
| Modify `docs/FEATURE_PLAN.md` (row QQ4, `:1173`) | the opening after A1b lands, and again after the phone check |

Not touched:
- `MutateSheet.kt`, `Mutate.kt`, `Knob.kt`, `MutateCommand.kt`: A1 landed their part.
- `Personality.kt` (`Copy`): no new string, see Global Constraints.
- `OutsideCard`, and the OUTSIDE call's `onKnobChange` (`:2596`).
- `app/src/androidTest`: `PadSheetScreenTest.kt` drives no MUTATE apply (its KDoc says so, `:66`).
- The native tree: no `app/src/main/cpp` edit, so the CMake block need not run.

---

### Task 1: Pin what the row leans on, at the sheet

A1 built and tested the knob, its readout and the stale-knob rule. The card adds three things those tests do not state:
- The holder opens on `BECOME.defaultFraction`, and DRIFT returns it there, so that fraction must read OFF.
- The screen's 1/40 snap must land only on whole 50 ms steps, wherever a thumb lets go, not just at the exact `step / 40f` points A1's test walks.
- A DRIFT through the sheet's own door must carry no ramp, which is what makes the reset to OFF true.

These pins are of A1's code, so they are **green before and after**: there is no production edit for them to wait on, as with A1's Task 4 Step 1. A red run here means A1 regressed. Stop and report it; do not edit the test to pass.

**Files:**
- Test: `shell/src/test/kotlin/com/snipsnap/shell/MutateSheetTest.kt`

**Interfaces:**
- Consumes (all at `28523f4b`):
  - `MutateSheet.BECOME: Knob` (`MutateSheet.kt:70`)
  - `MutateSheet.value(knob: Knob, fraction: Float): Float`
  - `MutateSheet.label(knob: Knob, value: Float): String` (`:95`)
  - `MutateSheet.knobFor(mode: Mutate.Mode): Knob?`
  - `MutateSheet.drift(model: KitBuilderModel, slot: Int, root: File, seed: Int, fraction: Float): Mutate.Drifted` (`:219`)
  - `MutateSheet.read(recipe: JsonValue.Obj?): MutateSheet.Applied?` (`:199`)
  - `Knob.defaultFraction`
  - the test class's own `model(name: String): KitBuilderModel` (`MutateSheetTest.kt:74`) and `temp: File` (`:57`)
- Produces: two tests in `MutateSheetTest`. No API.

- [ ] **Step 1: Write the row's pin**

In `shell/src/test/kotlin/com/snipsnap/shell/MutateSheetTest.kt`, before the class's closing brace (after `a ramped pad reads BECOME everywhere the move label goes, and a hostile become reads as none`, whose last line is `        assertEquals("MORPH", MutateSheet.Applied("MORPH", listOf("Soul:A03")).word)` then `    }`), add:

```kotlin

    // ---------- BECOME's card row (A1b) ----------

    /**
     * What the MUTATE card's second row leans on, pinned at the sheet.
     *
     * The row opens on `BECOME.defaultFraction` and DRIFT puts it back
     * there (ConventionTest's BECOME laws read both off the screen), so
     * that fraction must read OFF. The screen snaps the row with the knob
     * row's own expression (a law again), and that snap must only ever
     * land on whole 50 ms steps, the first of which clears one 23 ms
     * analysis window. The words live here and not in `Copy`, where
     * PersonalityTest's shout law cannot see them, so this holds them to
     * the house style instead.
     */
    @Test
    fun `the BECOME row opens OFF, lands on 50 ms steps wherever a thumb lets go, and shouts its label`() {
        val b = MutateSheet.BECOME
        assertEquals(0f, b.defaultFraction, "the row's holder opens on this fraction and DRIFT puts it back here")
        assertEquals("OFF", MutateSheet.label(b, MutateSheet.value(b, b.defaultFraction)))

        // The screen's snap, restated; ConventionTest holds the BECOME row's expression equal to the knob row's.
        fun snap(f: Float): Float = (f * 40f).roundToInt() / 40f
        val readouts = mutableListOf<String>()
        for (i in 0..1000) {
            val thumb = i / 1000f
            val ms = MutateSheet.value(b, snap(thumb))
            assertEquals(0, ms.roundToInt() % 50, "a thumb let go at $thumb landed on $ms ms, between steps")
            val text = MutateSheet.label(b, ms)
            assertTrue(text == "OFF" || Regex("""[1-9]\d* ms""").matches(text), "a thumb at $thumb reads '$text'")
            readouts += text
        }
        assertEquals("50 ms", readouts.first { it != "OFF" }, "the first step above OFF")
        assertEquals("2000 ms", readouts.last(), "the far end")

        // House style by hand: a label that shouts and fits the 44 dp column ATTACK and CUTOFF already fill.
        assertEquals(b.label.uppercase(), b.label, "a card label shouts")
        assertTrue(b.label.length <= "ATTACK".length, "'${b.label}' is wider than the label column's widest word")
        // The lowercase unit is this card's own precedent (AT reads "40 ms"), not a slip.
        assertEquals("40 ms", MutateSheet.label(MutateSheet.knobFor(Mutate.Mode.SPLICE)!!, 40f))
    }
```

- [ ] **Step 2: Write the DRIFT pin**

Add, before the class's closing brace:

```kotlin

    /**
     * DRIFT is a flat morph: the sheet's own door never hands it a ramp.
     * That is what makes the card's reset of BECOME to OFF on a DRIFT tap
     * a true readout rather than a guess (`Mutate.drift` is pinned the
     * same way in BecomeTest; this pins the door the phone calls).
     */
    @Test
    fun `a DRIFT from the card carries no BECOME, so the row's reset to OFF tells the truth`() {
        val m = model("Dbec")
        model("Dbec2")
        val d = MutateSheet.drift(m, 1, root = temp, seed = 2, fraction = 0.5f)
        val mutate = (d.outcome.pad.recipe!!.entries["mutate"] as JsonValue.Obj).entries
        assertTrue("become" !in mutate, "DRIFT wrote a ramp: ${mutate.keys}")
        val applied = MutateSheet.read(d.outcome.pad.recipe)!!
        assertEquals(0, applied.becomeMs)
        assertEquals("DRIFT", applied.word)
    }
```

- [ ] **Step 3: Run them**

Run: `./gradlew --no-daemon :shell:test --tests "com.snipsnap.shell.MutateSheetTest"; echo "exit=$?"`
Expected: `exit=0`, with both new tests passing on their first run. They pin A1's code; see the task's opening paragraph.

- [ ] **Step 4: Commit**

```bash
git add shell/src/test/kotlin/com/snipsnap/shell/MutateSheetTest.kt
git commit -m "Pin what BECOME's card row leans on: OFF at rest, whole 50 ms steps, no ramp on a drift

The MUTATE card's second row opens on BECOME's default fraction and a
DRIFT tap puts it back there, so that fraction must read OFF, and a
DRIFT through the sheet's door must carry no become key for the reset
to be true. The screen's 1/40 snap lands only on whole 50 ms steps from
any thumb position, the first reading 50 ms. The label shouts and fits
the column ATTACK fills; the lowercase ms is AT's own precedent. Both
tests pin code A1 landed, so they pass on their first run."
```

---

### Task 2: The holder, DRIFT's reset and the two doors

Write the two laws first, watch them fail against today's screen, then make the screen edits they describe. After this task the screen holds `pendingBecome`, uses it in HEAR and KEEP, and resets it on DRIFT. Nothing draws it yet; Task 3 adds the row.

**Files:**
- Test: `shell/src/test/kotlin/com/snipsnap/shell/ConventionTest.kt`
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/PadSheetScreen.kt`

**Interfaces:**
- Consumes (`ConventionTest`, at `28523f4b`):
  - `padSheetScreen: File` (`:1805`)
  - `codeOnly(block: String): String` (`:1668`)
  - `blockAfter(src: String, marker: String): String` (`:1693`)
  - `normalizeSpan(s: String): String` (`:233`)
- Consumes (the screen):
  - `MutateSheet.BECOME.defaultFraction`
  - `MutateSheet.preview(model, slot, partner, mode, fraction, becomeFraction: Float = 0f)` (`MutateSheet.kt:300`)
  - `MutateSheet.apply(model, slot, partner, mode, fraction, becomeFraction: Float = 0f)` (`:326`)
  - `mutableFloatStateOf`, already imported (`PadSheetScreen.kt:35`)
- Produces:
  - Two laws.
  - In `PadSheetScreen`'s composable scope, `var pendingBecome: Float`. Task 3 reads it.

- [ ] **Step 1: Write the holder-and-DRIFT law**

In `shell/src/test/kotlin/com/snipsnap/shell/ConventionTest.kt`, after the closing brace of `law - DRIFT leaves the knob showing the blend it actually used` (the `    }` that follows `"KDoc was written to end. Write it into the move's memory alongside the switch.",` and `        )`), and before the `    /**` KDoc whose first line is `     * J22.`, insert:

```kotlin

    // ---- Law: BECOME on the MUTATE card (A1b) ----

    /**
     * BECOME's card row, A1b of the BECOME spec
     * (`docs/superpowers/specs/2026-09-30-become-strung-say-design.md`, "The
     * card and the sheet"): the sibling of J24's two laws above.
     *
     * BECOME is MORPH's second knob and no other move reads it, so it is
     * one value per pad, held beside `mutateKnobs` rather than inside it
     * (the J24 law above reads that map exactly as it was), and it opens
     * on the knob's own default, OFF. DRIFT is a flat morph that never
     * takes a ramp: if the card went on showing a remembered `BECOME 400`
     * after a DRIFT tap, that would be J24's bug again, a value shown
     * against a value used. So `onDrift` puts BECOME back to OFF for every
     * tap, on its own line after the `if (!onMorph)` block (which stays as
     * the DRIFT law above reads it) and before the write is launched.
     */
    @Test
    fun `law - BECOME is remembered per pad, and DRIFT puts it back to OFF`() {
        val src = padSheetScreen.readText(Charsets.UTF_8)
        assertTrue(
            Regex(
                """var\s+pendingBecome\s+by\s+remember\(slot\)\s*\{\s*mutableFloatStateOf\(\s*MutateSheet\.BECOME\.defaultFraction\s*\)\s*\}""",
            ).containsMatchIn(src),
            "expected `var pendingBecome by remember(slot) { mutableFloatStateOf(MutateSheet.BECOME.defaultFraction) }` " +
                "- BECOME's one value per pad, opening OFF, reset only when the pad itself changes.",
        )
        val drift = codeOnly(blockAfter(src, "fun onDrift() {"))
        val toMorph = blockAfter(drift, "if (!onMorph) {")
        assertFalse(
            "pendingBecome" in toMorph,
            "BECOME's reset sits inside `if (!onMorph)`, so a DRIFT tap made from MORPH keeps a ramp the drift " +
                "does not use. Put it on its own line after the block, so it runs for every tap.",
        )
        val reset = Regex("""pendingBecome\s*=\s*MutateSheet\.BECOME\.defaultFraction""")
            .find(drift, drift.indexOf(toMorph) + toMorph.length)
        val launch = drift.indexOf("appScope.launch")
        assertTrue(launch >= 0, "expected `onDrift` to launch its write with `appScope.launch`")
        assertTrue(
            reset != null && reset.range.first < launch,
            "DRIFT never takes BECOME, but `onDrift` does not put BECOME back to OFF after the switch to MORPH " +
                "and before the write: the card would show a ramp the drift did not use, J24's value-shown/" +
                "value-used divergence. Write `pendingBecome = MutateSheet.BECOME.defaultFraction` there.",
        )
    }
```

- [ ] **Step 2: Write the two-doors law**

Directly after the law from Step 1, insert:

```kotlin

    /**
     * HEAR and KEEP both hand `MutateSheet` the BECOME the card shows.
     *
     * `MutateSheet.preview` and `MutateSheet.apply` take `becomeFraction`
     * with a default of 0, which kept the phone's calls compiling while A1
     * landed. The same default means a door that drops the argument still
     * compiles and still plays, flat: HEAR or KEEP would ignore the row
     * the player dialled, and no `:shell` test can see it, the gap the
     * TAPE view law below names. Each door reads the fraction before its
     * coroutine starts, as it reads `fraction`, so the value used is the
     * value on screen at the tap.
     */
    @Test
    fun `law - HEAR and KEEP hand MutateSheet the BECOME the card shows`() {
        val src = padSheetScreen.readText(Charsets.UTF_8)
        val doors = listOf(
            Triple(
                "fun onMutate() {",
                """MutateSheet\.apply\(\s*f\s*,\s*slot\s*,\s*who\s*,\s*move\s*,\s*fraction\s*,\s*becomeFraction\s*\)""",
                "appScope.launch",
            ),
            Triple(
                "fun onHear() {",
                """MutateSheet\.preview\(\s*m\s*,\s*slot\s*,\s*who\s*,\s*move\s*,\s*fraction\s*,\s*becomeFraction\s*\)""",
                "scope.launch",
            ),
        )
        for ((door, call, launcher) in doors) {
            val body = codeOnly(blockAfter(src, door))
            val read = Regex("""val\s+becomeFraction\s*=\s*pendingBecome\b""").find(body)
            val launch = body.indexOf(launcher)
            assertTrue(launch >= 0, "expected `$door` to start its work with `$launcher`")
            assertTrue(
                read != null && read.range.first < launch,
                "`$door` does not read `val becomeFraction = pendingBecome` before `$launcher`, so the BECOME it " +
                    "uses is not the one on screen at the tap.",
            )
            assertTrue(
                Regex(call).containsMatchIn(body),
                "`$door` calls MutateSheet without the card's BECOME. The argument defaults to 0, so this " +
                    "compiles and plays a flat MORPH while the row says otherwise. Pass `becomeFraction` as the " +
                    "sixth argument.",
            )
        }
    }
```

- [ ] **Step 3: Run the laws to see them fail**

Run: `./gradlew --no-daemon :shell:test --tests "com.snipsnap.shell.ConventionTest"; echo "exit=$?"`
Expected: a non-zero exit. Exactly two tests fail, each with its own message:
- `law - BECOME is remembered per pad, and DRIFT puts it back to OFF` fails on its first assertion, the message beginning ``expected `var pendingBecome by remember(slot) { mutableFloatStateOf(MutateSheet.BECOME.defaultFraction) }` ``.
- `law - HEAR and KEEP hand MutateSheet the BECOME the card shows` fails on `onMutate`, its message naming `fun onMutate() {` as not reading `val becomeFraction = pendingBecome` before `appScope.launch`.

Every other ConventionTest law passes, J24's two included. If anything else fails, stop: the tree is not what this plan was written against.

- [ ] **Step 4: Declare the holder**

In `app/src/main/kotlin/com/snipsnap/app/ui/PadSheetScreen.kt`, find (head `:1241-1242`):

```kotlin
    val pendingMutateKnob =
        mutateKnobs[mutateMode] ?: (mutateKnob?.let { MutateSheet.fraction(it, it.default) } ?: 0f)
```

and insert directly after it:

```kotlin
    // MORPH's second knob, BECOME: how long the hit takes to turn from the
    // pad into the MIX blend. Only MORPH reads it (every other move ignores
    // it in HEAR and KEEP alike, MutateSheet's `knobs`), so it is one value
    // per pad, not one per move, and it sits beside `mutateKnobs` rather
    // than inside it, so J24's law reads that map exactly as before. It
    // opens OFF, and DRIFT puts it back there (`onDrift`).
    var pendingBecome by remember(slot) { mutableFloatStateOf(MutateSheet.BECOME.defaultFraction) }
```

- [ ] **Step 5: KEEP passes it**

In `onMutate`, find (head `:1275-1277`; unique, since `onHear`'s copy is followed by `scope.launch {`):

```kotlin
        val move = MutateSheet.modeFor(mutateMode)
        val fraction = pendingMutateKnob
        val kitDir = m.kitDir
```

and replace it with:

```kotlin
        val move = MutateSheet.modeFor(mutateMode)
        val fraction = pendingMutateKnob
        val becomeFraction = pendingBecome
        val kitDir = m.kitDir
```

Then find (head `:1287`)

```kotlin
                        MutateSheet.apply(f, slot, who, move, fraction)
```

and replace it with

```kotlin
                        MutateSheet.apply(f, slot, who, move, fraction, becomeFraction)
```

- [ ] **Step 6: HEAR passes it**

In `onHear`, find (head `:1337-1339`):

```kotlin
        val move = MutateSheet.modeFor(mutateMode)
        val fraction = pendingMutateKnob
        scope.launch {
```

and replace it with:

```kotlin
        val move = MutateSheet.modeFor(mutateMode)
        val fraction = pendingMutateKnob
        val becomeFraction = pendingBecome
        scope.launch {
```

Then find (head `:1342`)

```kotlin
                val rendered = withContext(Dispatchers.IO) { MutateSheet.preview(m, slot, who, move, fraction) }
```

and replace it with

```kotlin
                val rendered = withContext(Dispatchers.IO) { MutateSheet.preview(m, slot, who, move, fraction, becomeFraction) }
```

- [ ] **Step 7: DRIFT resets it**

In `onDrift`, find (head `:1444-1446`, the end of the `if (!onMorph)` block and the line after it):

```kotlin
            mutateKnobs[Mutate.Mode.MORPH.name] = MutateSheet.DRIFT_FRACTION
        }
        val kitDir = m.kitDir
```

and replace it with:

```kotlin
            mutateKnobs[Mutate.Mode.MORPH.name] = MutateSheet.DRIFT_FRACTION
        }
        // DRIFT is a flat morph: `MutateSheet.drift` never takes BECOME. So
        // the card puts BECOME back to OFF on every DRIFT tap, from MORPH as
        // well as from any other move. A remembered `BECOME 400` left on
        // screen would be a value shown against a value used, the exact
        // divergence the block above exists to prevent for MIX.
        pendingBecome = MutateSheet.BECOME.defaultFraction
        val kitDir = m.kitDir
```

Do not edit the `if (!onMorph) { … }` block itself.

- [ ] **Step 8: Run the laws to pass**

Run: `./gradlew --no-daemon :shell:test --tests "com.snipsnap.shell.ConventionTest"; echo "exit=$?"`
Expected: `exit=0`. Both new laws pass, J24's two pass unedited, and so do `a doc-comment is never stranded above another doc-comment` and `no Kotlin source ends a doc comment by accident`.

The screen should still compile: `pendingBecome` is read by `onMutate` and `onHear` and written by `onDrift`, the same capture pattern `onDrift` already uses for `mutateMode`. CI's `android-build` is the proof, so do not claim it here.

- [ ] **Step 9: Commit**

```bash
git add shell/src/test/kotlin/com/snipsnap/shell/ConventionTest.kt app/src/main/kotlin/com/snipsnap/app/ui/PadSheetScreen.kt
git commit -m "The pad sheet holds BECOME per pad, passes it to HEAR and KEEP, and DRIFT puts it back to OFF

pendingBecome sits beside mutateKnobs as one value per pad, opening on
the knob's own OFF default. onMutate and onHear read it before their
coroutines start and hand it to MutateSheet as the sixth argument; the
argument defaults to 0, so a dropped one would compile and play flat,
and a law now refuses that. onDrift resets it on its own line after the
if (!onMorph) block and before the write, for every tap, since DRIFT
never takes a ramp. A sibling of J24's laws pins both. Nothing draws
the value yet."
```

---

### Task 3: The second row on the card

Write the law first and watch it fail. Then give `MutateCard` its second `StepperSlider` and wire it from the card call.

**Files:**
- Test: `shell/src/test/kotlin/com/snipsnap/shell/ConventionTest.kt`
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/PadSheetScreen.kt`

**Interfaces:**
- Consumes (`ConventionTest`):
  - `topLevelFun(file: File, name: String): String` (`:57`). It matches `private fun MutateCard(` at column 0 (`PadSheetScreen.kt:3489`) through its closing `}` at column 0.
  - `blockAfterList(src: String, marker: String): String` (`:1672`)
  - `codeOnly`, `normalizeSpan`, `padSheetScreen`
- Consumes (the screen):
  - `MutateSheet.becomeFor(mode: Mutate.Mode): Knob?` (`MutateSheet.kt:73`)
  - `MutateSheet.label`, `MutateSheet.value`
  - `StepperSlider(label: String, fraction: Float, valueText: String, fillColor: Color, scheme: Scheme, enabled: Boolean, onFractionChange: (Float) -> Unit, onFractionCommit: () -> Unit)` (`PadSheetScreen.kt:3078`)
  - `pendingBecome` from Task 2
- Produces:
  - One law.
  - Four new `MutateCard` parameters: `becomeLabel: String?`, `becomeFraction: Float`, `becomeText: String`, `onBecomeChange: (Float) -> Unit`.
  - `val becomeKnob: Knob?` in the composable scope.

- [ ] **Step 1: Write the row law**

In `shell/src/test/kotlin/com/snipsnap/shell/ConventionTest.kt`, directly after `law - HEAR and KEEP hand MutateSheet the BECOME the card shows` (Task 2 Step 2), insert:

```kotlin

    /**
     * BECOME's row on the MUTATE card: drawn for every move, enabled by
     * BECOME's own label, fed from the one holder, snapped like the knob.
     *
     * - Every move draws it: MORPH as BECOME, every other move as the same
     *   disabled `—` row STACK's knob shows, so the card never jumps.
     * - It is enabled on `becomeLabel != null`, not on `knobLabel` as the
     *   spec words it (the A1 plan's recorded deviation): `knobLabel` is
     *   non-null on SPLICE, SPLIT, ROOM and TRANSPLANT, which ignore
     *   BECOME, so a row copied from the knob's would be live on four moves
     *   where it does nothing. That copy is the likely slip, so it is
     *   refused here rather than left to review by eye.
     * - Its fraction and its readout both read `pendingBecome`, and it
     *   snaps with the knob row's own expression: `MutateSheetTest` proves
     *   that snap lands only on whole 50 ms steps, and this keeps the
     *   screen using it.
     */
    @Test
    fun `law - MUTATE draws BECOME's row on every move, enabled by BECOME's own label`() {
        val card = codeOnly(topLevelFun(padSheetScreen, "MutateCard"))
        val first = card.indexOf("StepperSlider(")
        val second = card.indexOf("StepperSlider(", first + 1)
        assertTrue(
            first >= 0 && second > first && card.indexOf("StepperSlider(", second + 1) < 0,
            "expected MutateCard to draw exactly two StepperSliders: the move's knob, then BECOME under it",
        )
        val knobRow = normalizeSpan(blockAfterList(card, "StepperSlider("))
        assertTrue(
            "label = knobLabel ?: \"—\"" in knobRow,
            "the move's knob row is no longer MutateCard's first StepperSlider:\n  $knobRow",
        )
        val becomeRow = normalizeSpan(blockAfterList(card.substring(second), "StepperSlider("))
        for (want in listOf(
            "label = becomeLabel ?: \"—\"",
            "fraction = if (becomeLabel == null) 0f else becomeFraction",
            "valueText = becomeText",
            "enabled = !busy && becomeLabel != null",
            "onFractionChange = onBecomeChange",
        )) {
            assertTrue(want in becomeRow, "BECOME's row lacks `$want`:\n  $becomeRow")
        }
        assertFalse(
            "knobLabel" in becomeRow,
            "BECOME's row reads `knobLabel`, which is non-null on SPLICE, SPLIT, ROOM and TRANSPLANT: the row " +
                "would be live on four moves that ignore BECOME. Key it on `becomeLabel` (the recorded " +
                "deviation from the spec's wording).",
        )
        assertFalse(
            Regex("""becomeLabel\s*!=\s*null\s*\)\s*\{|becomeLabel\?\.let""").containsMatchIn(card),
            "BECOME's row is drawn only for some moves, so the card jumps when the move changes. Draw it for " +
                "every move and let `becomeLabel ?: \"—\"` show the disabled row.",
        )

        val src = padSheetScreen.readText(Charsets.UTF_8)
        assertTrue(
            Regex("""val\s+becomeKnob\s*=\s*MutateSheet\.becomeFor\(\s*MutateSheet\.modeFor\(\s*mutateMode\s*\)\s*\)""")
                .containsMatchIn(src),
            "expected `val becomeKnob = MutateSheet.becomeFor(MutateSheet.modeFor(mutateMode))` - the row's " +
                "knob, MORPH's alone.",
        )
        val call = normalizeSpan(blockAfterList(src, "MutateCard("))
        for (want in listOf(
            "becomeLabel = becomeKnob?.label",
            "becomeFraction = pendingBecome",
            "becomeText = becomeKnob?.let { MutateSheet.label(it, MutateSheet.value(it, pendingBecome)) } ?: \"\"",
        )) {
            assertTrue(
                want in call,
                "the MutateCard call lacks `$want`. The row's fraction and its readout must both come from " +
                    "`pendingBecome`, or the card shows one value and plays another.\n  $call",
            )
        }
        val knobSnap = Regex("""onKnobChange = \{ f -> mutateKnobs\[mutateMode\] = (.+?) \}""").find(call)?.groupValues?.get(1)
        val becomeSnap = Regex("""onBecomeChange = \{ f -> pendingBecome = (.+?) \}""").find(call)?.groupValues?.get(1)
        assertTrue(knobSnap != null, "expected the knob row's `onKnobChange = { f -> mutateKnobs[mutateMode] = … }`")
        assertEquals(
            knobSnap,
            becomeSnap,
            "BECOME's row does not snap with the knob row's expression. MutateSheetTest proves only that one " +
                "lands on whole 50 ms steps; any other can land under one analysis window, where a ramp is a step.",
        )
    }
```

- [ ] **Step 2: Run the law to see it fail**

Run: `./gradlew --no-daemon :shell:test --tests "com.snipsnap.shell.ConventionTest"; echo "exit=$?"`
Expected: a non-zero exit. Exactly one test fails: `law - MUTATE draws BECOME's row on every move, enabled by BECOME's own label`, with `expected MutateCard to draw exactly two StepperSliders: the move's knob, then BECOME under it`. Task 2's laws and J24's still pass.

- [ ] **Step 3: The row's knob**

In `PadSheetScreen.kt`, find (head `:1231`):

```kotlin
    val mutateKnob = MutateSheet.knobFor(MutateSheet.modeFor(mutateMode))
```

and replace it with:

```kotlin
    val mutateKnob = MutateSheet.knobFor(MutateSheet.modeFor(mutateMode))
    // MORPH's second knob, null on every other move: the card's BECOME row.
    val becomeKnob = MutateSheet.becomeFor(MutateSheet.modeFor(mutateMode))
```

- [ ] **Step 4: The card call**

Find the line in the `MutateCard(` call (head `:2568`; unique, since OUTSIDE's writes `outsideKnobs`):

```kotlin
                onKnobChange = { f -> mutateKnobs[mutateMode] = (f * 40f).roundToInt() / 40f },
```

and insert directly after it:

```kotlin
                becomeLabel = becomeKnob?.label,
                becomeFraction = pendingBecome,
                becomeText = becomeKnob?.let { MutateSheet.label(it, MutateSheet.value(it, pendingBecome)) } ?: "",
                onBecomeChange = { f -> pendingBecome = (f * 40f).roundToInt() / 40f },
```

- [ ] **Step 5: The card's parameters**

In `MutateCard`'s parameter list, find (head `:3509-3510`; unique, since `OutsideCard`'s `onKnobChange` is followed by `applied:`):

```kotlin
    onKnobChange: (Float) -> Unit,
    mutated: MutateSheet.Applied?,
```

and replace it with:

```kotlin
    onKnobChange: (Float) -> Unit,
    becomeLabel: String?,
    becomeFraction: Float,
    becomeText: String,
    onBecomeChange: (Float) -> Unit,
    mutated: MutateSheet.Applied?,
```

- [ ] **Step 6: The second row**

In `MutateCard`'s body, find the move's knob row (head `:3722-3732`; `enabled = !busy && knobLabel != null,` occurs once in the file):

```kotlin
        // The move's knob, when it has one; STACK's row stays so the card never jumps.
        StepperSlider(
            label = knobLabel ?: "—",
            fraction = if (knobLabel == null) 0f else knobFraction,
            valueText = knobText,
            fillColor = padColor,
            scheme = scheme,
            enabled = !busy && knobLabel != null,
            onFractionChange = onKnobChange,
            onFractionCommit = {},
        )
```

and insert directly after its closing `        )`:

```kotlin

        // MORPH's second knob, BECOME: how long the hit takes to turn from
        // the pad into the MIX blend, OFF at 0. Every other move draws the
        // same disabled "—" row STACK's knob shows above, so the card never
        // jumps. It is enabled on BECOME's own label, not on `knobLabel`:
        // SPLICE, SPLIT, ROOM and TRANSPLANT have a first knob and no BECOME.
        StepperSlider(
            label = becomeLabel ?: "—",
            fraction = if (becomeLabel == null) 0f else becomeFraction,
            valueText = becomeText,
            fillColor = padColor,
            scheme = scheme,
            enabled = !busy && becomeLabel != null,
            onFractionChange = onBecomeChange,
            onFractionCommit = {},
        )
```

- [ ] **Step 7: The card's KDoc**

In `MutateCard`'s KDoc (head `:3476-3477`), find:

```kotlin
 * ROULETTE spins off the shelf — the move's one knob when it has one,
 * then HEAR or MUTATE. The line under the title says what the pad
```

and replace it with:

```kotlin
 * ROULETTE spins off the shelf — the move's one knob when it has one,
 * and under it MORPH's second, BECOME (how long the hit takes to turn
 * from the pad into the blend; a disabled `—` row on every other move,
 * so the card never jumps), then HEAR or MUTATE. The line under the
 * title says what the pad
```

The next line, ` * already is ("SPLICE: Kit:A02") so a mutated pad never reads as an`, continues the sentence unchanged. The KDoc block still sits directly above `@Composable` / `private fun MutateCard(`.

- [ ] **Step 8: Run the law to pass**

Run: `./gradlew --no-daemon :shell:test --tests "com.snipsnap.shell.ConventionTest"; echo "exit=$?"`
Expected: `exit=0`. All three BECOME laws pass, J24's two pass unedited, and so do the doc-comment laws.

- [ ] **Step 9: Re-read the screen diff adversarially**

`:app` is compiled only by CI, so read `git diff app/src/main/kotlin/com/snipsnap/app/ui/PadSheetScreen.kt` once against this list before committing. A speculative fix pushed for a job you cannot run costs a full CI cycle.
- Every name the diff uses exists at the place it is used. `becomeKnob` and `pendingBecome` sit in the composable scope above `onMutate`, and `MutateCard`'s four new parameters are passed by name at its one call site.
- No import is needed: `mutableFloatStateOf` (`:35`) and `roundToInt` (`:117`) are already imported, and `MutateSheet` and `Mutate` are already in use.
- No `44_100`, no `/**` added inside a function body, and no Copy string.

- [ ] **Step 10: Commit**

```bash
git add shell/src/test/kotlin/com/snipsnap/shell/ConventionTest.kt app/src/main/kotlin/com/snipsnap/app/ui/PadSheetScreen.kt
git commit -m "The MUTATE card draws BECOME under MORPH's knob, and a disabled row for every other move

A second StepperSlider under the move's knob: BECOME on MORPH, reading
OFF or the milliseconds, and the same disabled dash row STACK's knob
shows on every other move, so the card never jumps. It is enabled on
its own label, not on knobLabel as the spec words it, because knobLabel
is set on four moves that ignore BECOME; a law refuses the copy. The
fraction and the readout both read pendingBecome, and the row snaps with
the knob row's own expression, which the sheet test proves lands on
whole 50 ms steps."
```

---

### Task 4: The whole suite, and the plan row

**Files:**
- Modify: `docs/FEATURE_PLAN.md` (row QQ4)

- [ ] **Step 1: Run the whole JVM suite**

Run: `./gradlew --no-daemon test; echo "exit=$?"`. In a session where `./gradlew --no-daemon projects` lists `:app`, run `./gradlew --no-daemon test -x :app:test` instead; that is what CI runs.
Expected: `exit=0`. This includes `MutateSheetTest`, `BecomeTest`, `ConventionTest` and `PersonalityTest`, the last unchanged because no `Copy` string was added. The native block (`cmake … ctest`) need not run: nothing under `app/src/main/cpp` changed.

- [ ] **Step 2: Flip QQ4's opening to "built, phone check pending"**

In `docs/FEATURE_PLAN.md`, row QQ4, replace the opening

```markdown
| QQ4 | A1 landed; A1b, the MUTATE card's row, follows (`docs/superpowers/plans/2026-10-02-a1b-become-card.md`). The A1 gate (2026-10-02)
```

with

```markdown
| QQ4 | A1 and A1b landed; the owner's phone check of the MUTATE card's BECOME row is pending (`docs/superpowers/plans/2026-10-02-a1b-become-card.md`). The A1 gate (2026-10-02)
```

and in the same row's exit-test column replace

```markdown
--become without --morph refused; drift unchanged |
```

with

```markdown
--become without --morph refused; drift unchanged; the card's laws: BECOME's row drawn on every move and enabled by its own label, read by HEAR and KEEP before their launch, put back to OFF by DRIFT |
```

Run: `grep -c 'A1 and A1b landed' docs/FEATURE_PLAN.md; grep -c "the card's laws: BECOME's row" docs/FEATURE_PLAN.md`
Expected: `1` and `1`.

- [ ] **Step 3: Commit**

```bash
git add docs/FEATURE_PLAN.md
git commit -m "QQ4 records the card row built and the owner's phone check pending

A1b drew BECOME's row on the MUTATE card under three ConventionTest
laws. The sound was approved at the A1 gate, so the phone check is the
last gate, and the row stays short of done until it passes."
```

- [ ] **Step 4: What CI will run**

The PR touches `app/**`, so it gets `jvm-tests`, `android-build`, `native-tests` and `emulator-tests`, the last taking ten to fifteen minutes. `android-build` is the first compiler to see Tasks 2 and 3. If it fails, read its log and reason about the Kotlin; do not push a guess. A three-second failure with no log is the metered-minutes case, not the diff (`.claude/skills/steward/SKILL.md`, "Reading CI").

---

### Task 5: The owner's phone check, then STOP

No listening gate follows: the owner approved the sound at the A1 gate on 2026-10-02. The phone check is A1b's gate. The orchestrator sends the owner the build CI produced and this list. The owner answers each line yes or no, with a word on any no.

- [ ] **Step 1: The phone check list**

On a kit with at least two pads, open a pad's PAD SHEET and its MUTATE box:

1. **STACK:** two rows under DRIFT, both reading `—` and both dead to a drag.
2. **SPLICE, SPLIT, ROOM, TRANSPLANT** in turn: the first row is the move's knob (AT, HZ, WET, BANDS) and the second stays a dead `—` row. The card does not change height as the move changes.
3. **MORPH:** the second row reads `BECOME` and `OFF`. Dragging it steps in 50 ms (the first step reads `50 ms`, the far end `2000 ms`), and the word `BECOME` is not cut off.
4. **MORPH with a partner pad, MIX 100%, BECOME 250 ms:** ▶ HEAR plays the pad turning into the partner. MUTATE ▸ then writes it, and the toast names MORPH. The line under the title and the box's strip both say BECOME. Tapping the pad plays what HEAR played.
5. **The same with BECOME back at OFF:** HEAR is the MORPH the card made before this change.
6. **BECOME 400 ms on MORPH:** tap SPLICE (HEAR sounds as it always did), then tap MORPH again. BECOME still reads `400 ms`.
7. **BECOME 400 ms on MORPH, then DRIFT:** the row reads `OFF` and the title line says DRIFT. Then, starting from SPLICE with BECOME at 400 ms remembered on MORPH, tap DRIFT: the card lands on MORPH with MIX 50% and BECOME `OFF`.
8. **The ◄ ► pad arrows:** the next pad opens with BECOME `OFF`.
9. **UNDO after a BECOME mutate:** the original comes back.
10. **TalkBack on MORPH:** the row reads "BECOME" and its value, and it can be adjusted. On any other move it reads as disabled.

- [ ] **Step 2: STOP and act on the answers**

Record the owner's answers verbatim in the PR description.

**All yes:** in `docs/FEATURE_PLAN.md` row QQ4, replace the opening

```markdown
| QQ4 | A1 and A1b landed; the owner's phone check of the MUTATE card's BECOME row is pending (`docs/superpowers/plans/2026-10-02-a1b-become-card.md`). The A1 gate (2026-10-02)
```

with

```markdown
| QQ4 | ✓ done (A1 and A1b; the A1 gate and the owner's phone check passed): BECOME on the MUTATE card. The A1 gate (2026-10-02)
```

then run `grep -c '✓ done (A1 and A1b' docs/FEATURE_PLAN.md` (expected `1`) and commit it alone:

```bash
git add docs/FEATURE_PLAN.md
git commit -m "QQ4 is done: the owner's phone check passed BECOME's card row

Every line of the A1b phone check came back yes; the answers are in the
pull request."
```

**Any no:** QQ4 is not flipped.
- A no on lines 1, 2, 3 or 10 is a card fix. Write the law that would have caught it first, watch it fail, then edit the screen, as in Tasks 2 and 3.
- A no on lines 4 to 9 is a fault in what HEAR, KEEP, DRIFT or the holder do. Find the failing claim among Task 2's laws, or among A1's `MutateSheetTest` and `BecomeTest` pins, before changing anything.
- A no that is about the sound itself rather than the card goes back to the owner as one question. It reopens the A1 gate's answers, which this plan does not do on its own.

This plan ends here.

---

## Self-review

- **Coverage:** every surface the A1 plan's closing note and the spec's "The card and the sheet" name for A1b has a task and a pin:
  - `MutateCard`'s second `StepperSlider`, and the `—` row on other moves so the card never jumps: Task 3.
  - `pendingBecome` held by `remember(slot)`: Task 2.
  - The DRIFT reset after `onDrift`'s `if (!onMorph)` block: Task 2.
  - Both `MutateSheet` call sites passing the BECOME fraction: Task 2.
  - The sibling ConventionTest law: Tasks 2 and 3, three laws.
  - The words and `PersonalityTest`: Task 1, plus the Global Constraints' no-`Copy` rule.
  - The recorded deviation: in Global Constraints verbatim, and pinned by Task 3's law.
  - The spec's ":app laws" bullet in "Testing", which the A1 plan deferred here: Tasks 2 and 3.
- **Placeholders:** none. Every edit is quoted old text and full new text, every command is exact, and the one owner-supplied text (the phone-check answers) goes to the PR description, not a file.
- **Types:** every new parameter and value has a single name and type throughout:
  - `becomeLabel: String?`, `becomeFraction: Float`, `becomeText: String`, `onBecomeChange: (Float) -> Unit`
  - `val becomeKnob: Knob?`, `var pendingBecome: Float`, `val becomeFraction` (local)

  Every `:shell` symbol consumed exists at `28523f4b` with the signature cited: `MutateSheet.preview`/`apply` with `becomeFraction: Float = 0f`, `becomeFor`, `label`, `value`, `drift`, `read`, `Applied.becomeMs`/`word`, `Knob.defaultFraction`.
- **Laws against today's tree:** while writing, a Python mirror of the three laws' text matching (not `ConventionTest` itself, which this plan's executor runs) was run against `PadSheetScreen.kt` at `28523f4b` and against a copy with Tasks 2 and 3's edits applied. It failed on the first and passed on the second, and found:
  - `fun onMutate() {`, `fun onHear() {`, `fun onDrift() {` and `if (!onMorph) {` each occur once, and brace-match to their own bodies.
  - The first `MutateCard(` in comment-stripped code is the call (`:2548`).
  - `MutateCard` draws one `StepperSlider` today.

  So each law fails first with the message named in its run step, not with a crash.
- **Review Focus:** each of the five lines names a test or law that Task 1, 2 or 3 writes.
