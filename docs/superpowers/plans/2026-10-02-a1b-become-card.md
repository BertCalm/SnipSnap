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

The plan it follows is [`2026-09-30-a1-become.md`](2026-09-30-a1-become.md). Its Task 4 interfaces and its closing A1b note set this plan's surfaces. Code cites are at `28523f4b`. The branch head when this plan was reviewed, `e45bea47`, adds only docs on top of it, so every cited line of code is unchanged. Line numbers move as each task edits the screen, so every edit below is located by its quoted text, and the line numbers are a guide only.

## Global Constraints

- The A1 gate stands as answered on 2026-10-02 (Q1 "They all do", Q2 "A different sound", Q3 "6 is as good or better"). `MutateSheet.BECOME` stays `Knob("BECOME", 0f, Mutate.MAX_BECOME_MS.toFloat(), 0f, exponential = false)`: linear, 0 to 2000 ms, 50 ms steps at the phone's 1/40 snap, OFF at 0 (Decision 2's default). None of the spike's five extras is added (Decision 1's default).
- **Recorded deviation (from the A1 plan's closing note and the preflight ruling):** the BECOME row's enabled state keys on `becomeLabel != null` (MORPH only), not on `knobLabel` as the spec words it, because `knobLabel` is non-null for SPLICE, SPLIT, ROOM and TRANSPLANT, which ignore BECOME. The row is `enabled = !busy && becomeLabel != null`, and a ConventionTest law pins it, so it is not left to review by eye.
- The holder is `var pendingBecome by remember(slot) { mutableFloatStateOf(MutateSheet.BECOME.defaultFraction) }`, declared beside `mutateKnobs`.
- `mutateKnobs` stays declared exactly as J24's law reads it: `val mutateKnobs = remember(slot) { mutableStateMapOf<String, Float>() }`. `remember(slot, mutateMode)` is never written.
- DRIFT's reset is `pendingBecome = MutateSheet.BECOME.defaultFraction`. It goes on its own line directly after `onDrift`'s `if (!onMorph) { … }` block (nothing but comments between the block's `}` and the reset), at the function body's own depth, and before `appScope.launch`, so it runs for every tap. The `if (!onMorph)` block is not edited, because J24's `law - DRIFT leaves the knob showing the blend it actually used` reads it. Task 2's law pins all three placements: the reset alone on its line, adjacent to the block, at `val kitDir`'s brace depth.
- The reset runs before the write is launched, so a DRIFT that then fails (the `CRATE_EMPTY` toast, an empty crate) has still put BECOME back to OFF and landed the card on MORPH. That is intended: the spec prescribes the reset in `onDrift`, and `mutateKnobs`' write in the `if (!onMorph)` block already behaves the same way. Phone check line 11 exercises it.
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
- Every commit message ends with the single line `Claude-Session: https://claude.ai/code/session_01JttZq5ZXbhD6LNXvRhJbJY`, after a blank line, and carries no Co-Authored-By line. Every commit block below already ends with it; copy the blocks whole.
- A guard is proved by reverting it alone and watching its test fail with its own message (`.claude/skills/steward/SKILL.md`, "Claims, and what backs them"). The mutation steps in Tasks 1, 2 and 3 do that. Each mutation is temporary: make it, run, read the message, then undo it before going on. A file with no uncommitted work of the task's own (the `:shell` sources in Task 1) is undone with `git checkout -- <file>` and checked with `git diff --exit-code -- <file>`. The screen, which holds the task's uncommitted edits in Tasks 2 and 3, is undone by editing the line back exactly, then re-running the task's passing command. No mutation is ever committed.
- A KDoc block sits directly above its declaration (`a doc-comment is never stranded above another doc-comment`), and notes inside function bodies are `//`. No KDoc line puts two asterisks next to a slash (`no Kotlin source ends a doc comment by accident`).

## Review Focus

- **A dropped argument that still compiles.** `preview` and `apply` default `becomeFraction` to 0, so a door that forgets it compiles and plays a flat MORPH while the row says 400 ms, and no `:shell` test can see it. Pinned in Task 2 (`law - HEAR and KEEP hand MutateSheet the BECOME the card shows`).
- **A row copied from the knob row.** The likely slip is `enabled = !busy && knobLabel != null`, which lights BECOME on four moves that ignore it. Pinned in Task 3 (`law - MUTATE draws BECOME's row on every move, enabled by BECOME's own label`), which also refuses `knobLabel` anywhere in BECOME's row.
- **DRIFT leaves a ramp on screen.** DRIFT is a flat morph. If BECOME kept a remembered 400 after a DRIFT tap, the card would show a value the drift did not use, which is J24's bug again. Pinned in Task 2 (`law - BECOME is remembered per pad, and DRIFT puts it back to OFF`) and in Task 1 (`a DRIFT from the card carries no BECOME, so the row's reset to OFF tells the truth`).
- **A snap that lands between steps.** If the BECOME row snapped finer than 1/40 (say 1/100, 20 ms steps), a thumb could land under one 23 ms analysis window, where a ramp reads as a step. The literal is pinned once, on the screen: Task 3's law asserts that BECOME's `onBecomeChange` snap is exactly the text `(f * 40f).roundToInt() / 40f`, and that the knob row's `onKnobChange` snap equals it. Task 1's sweep (`the BECOME row opens OFF, lands on 50 ms steps wherever a thumb lets go, and shouts its label`) proves that same text lands only on whole 50 ms steps. The test's copy of the expression is held to the screen's by that literal assert, so the two copies cannot drift apart silently. Task 3's mutation step shows the law failing when both rows snap at 1/100.
- **The text and the fraction from two places.** The row's readout and its fraction must both come from `pendingBecome`, and the holder must be one value per pad, never one per move. Pinned in Task 3's law (`becomeText` reads `pendingBecome`) and in Task 2's law (the holder's exact declaration). A1's `a BECOME left dialled does nothing to a move that is not MORPH` covers the stale value on another move.

---

## File Structure

| File | Responsibility |
|---|---|
| Modify `shell/src/test/kotlin/com/snipsnap/shell/MutateSheetTest.kt` (before the closing brace, `:453`) | two pins of what the row leans on: the opening value reads OFF, the snap lands on 50 ms steps from any thumb, the label shouts, and a DRIFT through the sheet's door carries no BECOME |
| Modify `shell/src/test/kotlin/com/snipsnap/shell/ConventionTest.kt` (after `law - DRIFT leaves the knob showing the blend it actually used`, `:1901-1912`) | three laws: the holder and DRIFT's reset; HEAR and KEEP passing BECOME; the row and its call |
| Modify `app/src/main/kotlin/com/snipsnap/app/ui/PadSheetScreen.kt` | `pendingBecome` and `becomeKnob` beside `mutateKnobs` (`:1240-1242`); `onMutate` (`:1264`); `onHear` (`:1328`); `onDrift` (`:1400`, after the `if (!onMorph)` block at `:1432-1445`); the card call (`:2548-2577`); `MutateCard`'s KDoc (`:3473-3487`), parameters (`:3506-3510`) and second `StepperSlider` (after `:3722-3732`) |
| Modify `docs/FEATURE_PLAN.md` (row QQ4, `:1173`) | the opening once A1b is built (CI compile and phone check pending), and again after the phone check |
| Modify `docs/CLI.md` (the phone paragraph of `mutate`, `:553-556`) | the MUTATE card now has MORPH's second knob, BECOME, beside "the move's one knob" |

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

What A1's tests already state is not restated here. `BECOME is MORPH's second knob - linear 0 to 2000 ms, OFF at rest, 50 ms steps` (`MutateSheetTest.kt:346-366`) pins the default 0, `OFF` at 0, and the `50 ms`, `500 ms` and `2000 ms` readouts. `knobs open at the verb's defaults…` (`:179`) pins AT's `40 ms`. `BecomeTest.kt:343` pins `Mutate.drift`'s key list. The new coverage is the composed claim that the holder's opening fraction reads OFF, the arbitrary-thumb snap sweep, the label's shout and length, and the `MutateSheet.drift` door.

These pins are of A1's code, so they **do not fail before**: there is no production edit for them to wait on, as with A1's Task 4 Step 1. That falls short of "each `:shell` test fails before and passes after", so Step 4 proves them the other way, the steward's way: it breaks each guarded fact in `:shell` alone, temporarily, and watches the new test fail with its own message. A red run at Step 3 means A1 regressed. Stop and report it; do not edit the test to pass.

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
     * that fraction must read OFF. ConventionTest's row law holds the
     * screen's snap to exactly the text `(f * 40f).roundToInt() / 40f`,
     * and this sweep proves that text only ever lands on whole 50 ms
     * steps, the first of which clears one 23 ms analysis window. The
     * label lives here and not in `Copy`, where PersonalityTest's shout
     * law cannot see it, so this holds it to the house style instead.
     * BECOME's own test above already pins the default, OFF and the
     * 50/500/2000 ms readouts; this does not restate them.
     */
    @Test
    fun `the BECOME row opens OFF, lands on 50 ms steps wherever a thumb lets go, and shouts its label`() {
        val b = MutateSheet.BECOME
        assertEquals(
            "OFF",
            MutateSheet.label(b, MutateSheet.value(b, b.defaultFraction)),
            "the row's holder opens on BECOME.defaultFraction and DRIFT puts it back there, so that fraction must read OFF",
        )

        // The screen's snap. ConventionTest's BECOME row law asserts the screen's expression is exactly this text,
        // so this sweep is a proof about the phone's snap, not about a private copy of it.
        fun snap(f: Float): Float = (f * 40f).roundToInt() / 40f
        val readouts = mutableListOf<String>()
        for (i in 0..1000) {
            val thumb = i / 1000f
            val ms = MutateSheet.value(b, snap(thumb))
            assertEquals(0, ms.roundToInt() % 50, "a thumb let go at $thumb landed on $ms ms, between steps")
            val text = MutateSheet.label(b, ms)
            // The lowercase unit is this card's own precedent (AT reads "40 ms", pinned above), not a slip.
            assertTrue(text == "OFF" || Regex("""[1-9]\d* ms""").matches(text), "a thumb at $thumb reads '$text'")
            readouts += text
        }
        assertEquals("50 ms", readouts.first { it != "OFF" }, "the first step a thumb reaches above OFF")
        assertEquals("2000 ms", readouts.last(), "a thumb let go at the far end")

        assertEquals(b.label.uppercase(), b.label, "a card label shouts")
        // A coarse proxy only. The label font is Silkscreen (TapeTheme.kt:59), which is proportional, and
        // BECOME's M is wider than any letter of ATTACK, so a letter count cannot see a pixel overflow of the
        // 44 dp label column. The phone check (line 3, "BECOME is not cut off") is the real gate; this
        // catches only a longer word.
        assertTrue(b.label.length <= "ATTACK".length, "'${b.label}' has more letters than ATTACK, the label column's longest word")
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
        assertEquals(0, applied.becomeMs, "the drifted pad reads back a ramp, so the card's OFF would be a lie")
        assertEquals("DRIFT", applied.word, "the drifted pad reads back as ${applied.word}, not DRIFT")
    }
```

- [ ] **Step 3: Run them**

Run: `./gradlew --no-daemon :shell:test --tests "com.snipsnap.shell.MutateSheetTest"; echo "exit=$?"`
Expected: `exit=0`, with both new tests passing on their first run. They pin A1's code; see the task's opening paragraph.

- [ ] **Step 4: Break each guarded fact alone, and watch its test fail**

Three mutations, one at a time. Each edits a `:shell` production file that this plan otherwise leaves untouched, so each is reverted before the next (Global Constraints). For each one, run

`./gradlew --no-daemon :shell:test --tests "com.snipsnap.shell.MutateSheetTest" --tests "com.snipsnap.shell.BecomeTest"; echo "exit=$?"`

and expect a non-zero exit with the new test failing on the message named below. A1's own tests may fail beside it, as listed; that is expected, and nothing else should fail.

1. **The opening value.** In `shell/src/main/kotlin/com/snipsnap/shell/MutateSheet.kt` (`:70`), change `Knob("BECOME", 0f, Mutate.MAX_BECOME_MS.toFloat(), 0f, exponential = false)` to `Knob("BECOME", 0f, Mutate.MAX_BECOME_MS.toFloat(), 400f, exponential = false)`.
   - `the BECOME row opens OFF, …` fails with `the row's holder opens on BECOME.defaultFraction and DRIFT puts it back there, so that fraction must read OFF ==> expected: <OFF> but was: <400 ms>`.
   - A1's `BECOME is MORPH's second knob - …` fails beside it, on `assertEquals(0f, b.default)` (`expected: <0.0> but was: <400.0>`).
   - Revert: `git checkout -- shell/src/main/kotlin/com/snipsnap/shell/MutateSheet.kt && git diff --exit-code -- shell/src/main/kotlin/com/snipsnap/shell/MutateSheet.kt; echo "clean=$?"`, expecting `clean=0`.
2. **The shout.** `MutateSheet.label` dispatches on the knob's label (`"BECOME" -> if (value <= 0f) "OFF" else …`, `:99`), so lower-casing the label alone also breaks the readout, and the test then fails on its OFF assertion (`… but was: <0%>`) before it reaches the shout. Change both together: `Knob("BECOME", 0f,` to `Knob("Become", 0f,` (`:70`), and `        "BECOME" -> if (value <= 0f)` to `        "Become" -> if (value <= 0f)` (`:99`).
   - `the BECOME row opens OFF, …` fails with `a card label shouts ==> expected: <BECOME> but was: <Become>`.
   - A1's `BECOME is MORPH's second knob - …` fails beside it, on `assertEquals("BECOME", b.label)`.
   - Revert as in 1.
3. **No ramp on a drift.** In `shell/src/main/kotlin/com/snipsnap/shell/Mutate.kt`, in `fun drift(`'s `apply(` call (`:276-288`), add the line `becomeMs = 400,` directly after `morphAmount = amount,`. `MutateSheet.drift` has no way to pass a ramp itself, so this breaks the door at the one place it could break. Do **not** mutate with a `"become"` key in `extraRecipe` instead: `Mutate.kt:227` refuses that with a throw, so the test would fail on an exception rather than its own message.
   - `a DRIFT from the card carries no BECOME, …` fails with `DRIFT wrote a ramp: [mode, with, amount, become, roulette, drift]`.
   - `BecomeTest`'s `an extra recipe field named become is refused before anything moves, and DRIFT writes none` fails beside it, on its key-list assertion at `:343`.
   - Revert: `git checkout -- shell/src/main/kotlin/com/snipsnap/shell/Mutate.kt && git diff --exit-code -- shell/src/main/kotlin/com/snipsnap/shell/Mutate.kt; echo "clean=$?"`, expecting `clean=0`.

There is no snap mutation here. The snap lives on the screen, not in `:shell`. Task 3's law holds the screen's snap literal to this sweep's, and Task 3's own mutation step shows it failing.

After the three, re-run Step 3's command and expect `exit=0`, and `git status --short` lists only `MutateSheetTest.kt`.

- [ ] **Step 5: Commit**

```bash
git add shell/src/test/kotlin/com/snipsnap/shell/MutateSheetTest.kt
git commit -m "Pin what BECOME's card row leans on: OFF at rest, whole 50 ms steps, no ramp on a drift

The MUTATE card's second row opens on BECOME's default fraction and a
DRIFT tap puts it back there, so that fraction must read OFF, and a
DRIFT through the sheet's door must carry no become key for the reset
to be true. The screen's 1/40 snap lands only on whole 50 ms steps from
any thumb position, the first reading 50 ms; ConventionTest will hold
the screen's snap to that exact expression. The label shouts, and has
no more letters than ATTACK, a coarse proxy whose real gate is the
phone check. Facts A1's tests already state are not restated.

Both tests pin code A1 landed, so they pass on their first run. They
were proved instead by breaking each fact alone in :shell (BECOME's
default at 400, its label and label()'s branch in lower case, a 400 ms
ramp in Mutate.drift)
and watching each fail with its own message, then reverting.

Claude-Session: https://claude.ai/code/session_01JttZq5ZXbhD6LNXvRhJbJY"
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
     * tap, on its own line directly after the `if (!onMorph)` block (which
     * stays as the DRIFT law above reads it), at the body's own depth, and
     * before the write is launched. "Every tap" is checked three ways,
     * because each catches a guard the others miss: the reset's line holds
     * nothing else (a braceless `if (…) pendingBecome = …`), nothing but
     * comments stands between the block's `}` and the reset (a guard on
     * the line before, or an `else`), and its brace depth is `val kitDir`'s
     * (a braced `if`).
     */
    @Test
    fun `law - BECOME is remembered per pad, and DRIFT puts it back to OFF`() {
        val src = padSheetScreen.readText(Charsets.UTF_8)
        assertTrue(
            Regex(
                """var\s+pendingBecome\s+by\s+remember\(slot\)\s*\{\s*mutableFloatStateOf\(\s*MutateSheet\.BECOME\.defaultFraction\s*\)\s*\}""",
            ).containsMatchIn(codeOnly(src)),
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
        val afterBlock = drift.indexOf(toMorph) + toMorph.length
        val reset = Regex("""pendingBecome\s*=\s*MutateSheet\.BECOME\.defaultFraction""").find(drift, afterBlock)
        val launch = drift.indexOf("appScope.launch")
        assertTrue(launch >= 0, "expected `onDrift` to launch its write with `appScope.launch`")
        assertTrue(
            reset != null && reset.range.first < launch,
            "DRIFT never takes BECOME, but `onDrift` does not put BECOME back to OFF after the switch to MORPH " +
                "and before the write: the card would show a ramp the drift did not use, J24's value-shown/" +
                "value-used divergence. Write `pendingBecome = MutateSheet.BECOME.defaultFraction` there.",
        )
        val at = reset!!.range.first
        val line = drift.substring(drift.lastIndexOf('\n', at) + 1, drift.indexOf('\n', at).let { if (it < 0) drift.length else it })
        assertEquals(
            "pendingBecome = MutateSheet.BECOME.defaultFraction",
            line.trim(),
            "BECOME's reset shares its line with something else, so it may not run for every DRIFT tap. " +
                "Give it a line of its own.",
        )
        assertTrue(
            drift.substring(afterBlock, at).isBlank(),
            "something stands between the end of `if (!onMorph) { … }` and BECOME's reset " +
                "(`${normalizeSpan(drift.substring(afterBlock, at))}`), so the reset may be guarded and skip " +
                "some taps. Put it directly after the block.",
        )
        fun depth(i: Int) = drift.substring(0, i).count { it == '{' } - drift.substring(0, i).count { it == '}' }
        val kitDir = drift.indexOf("val kitDir")
        assertTrue(kitDir >= 0, "expected `onDrift` to declare `val kitDir`")
        assertEquals(
            depth(kitDir),
            depth(at),
            "BECOME's reset sits inside a block `val kitDir` is not in, so some DRIFT taps skip it. " +
                "Write it at `onDrift`'s own depth.",
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

- [ ] **Step 9: Guard the reset against a guard**

Three mutations of the line Step 7 wrote, one at a time, each run with Step 8's command and each expected to exit non-zero with only `law - BECOME is remembered per pad, and DRIFT puts it back to OFF` failing:

1. Change `        pendingBecome = MutateSheet.BECOME.defaultFraction` to `        if (spins > 0) pendingBecome = MutateSheet.BECOME.defaultFraction`. The law fails with `BECOME's reset shares its line with something else`.
2. Change it to `        if (spins > 0) {` / `            pendingBecome = MutateSheet.BECOME.defaultFraction` / `        }` (three lines). The law fails with ``something stands between the end of `if (!onMorph) { … }` and BECOME's reset``, naming `if (spins > 0) {`.
3. Change it to `        if (spins > 0)` / `            pendingBecome = MutateSheet.BECOME.defaultFraction` (two lines, braceless). The law fails with the same message, naming `if (spins > 0)`.

After each, put the line back exactly as Step 7 wrote it (by an edit, not `git checkout`: the screen holds this task's uncommitted work) and re-run Step 8's command, expecting `exit=0`. The depth assertion is a third net behind these two; no single mutation reaches it without tripping one of them first.

- [ ] **Step 10: Commit**

```bash
git add shell/src/test/kotlin/com/snipsnap/shell/ConventionTest.kt app/src/main/kotlin/com/snipsnap/app/ui/PadSheetScreen.kt
git commit -m "The pad sheet holds BECOME per pad, passes it to HEAR and KEEP, and DRIFT puts it back to OFF

pendingBecome sits beside mutateKnobs as one value per pad, opening on
the knob's own OFF default. onMutate and onHear read it before their
coroutines start and hand it to MutateSheet as the sixth argument; the
argument defaults to 0, so a dropped one would compile and play flat,
and a law now refuses that. onDrift resets it on its own line after the
if (!onMorph) block and before the write, for every tap, since DRIFT
never takes a ramp. A sibling of J24's laws pins both, and refuses a
guarded reset three ways: a line of its own, nothing between it and the
block, the body's own brace depth. Nothing draws the value yet.

Claude-Session: https://claude.ai/code/session_01JttZq5ZXbhD6LNXvRhJbJY"
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
     * - It is drawn unconditionally: its `StepperSlider(` stands alone on
     *   its line, directly after the knob row's closing `)`, so no inline
     *   or braceless guard (`if (…) StepperSlider(`) can hide it on some
     *   moves.
     * - Its fraction and its readout both read `pendingBecome`, and it
     *   snaps with exactly `(f * 40f).roundToInt() / 40f`, the knob row's
     *   own expression: `MutateSheetTest` sweeps that exact text and proves
     *   it lands only on whole 50 ms steps, so the literal is pinned here,
     *   on the screen, and not only as the two rows' equality.
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
        val lineStart = card.lastIndexOf('\n', second) + 1
        val lineEnd = card.indexOf('\n', second).let { if (it < 0) card.length else it }
        val previous = card.substring(0, lineStart).lines().lastOrNull { it.isNotBlank() }?.trim()
        assertTrue(
            card.substring(lineStart, lineEnd).trim() == "StepperSlider(" && previous == ")",
            "BECOME's row is guarded, so it is drawn only for some moves and the card jumps when the move " +
                "changes. Its `StepperSlider(` must stand alone on its line, directly after the knob row's " +
                "closing `)`; found `${card.substring(lineStart, lineEnd).trim()}` after `$previous`. Draw it " +
                "for every move and let `becomeLabel ?: \"—\"` show the disabled row.",
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
            "(f * 40f).roundToInt() / 40f",
            becomeSnap,
            "BECOME's row does not snap at 1/40. MutateSheetTest sweeps exactly `(f * 40f).roundToInt() / 40f` " +
                "and proves it lands on whole 50 ms steps; a finer snap (1/100 is 20 ms) lands under one 23 ms " +
                "analysis window, where a ramp is a step.",
        )
        assertEquals(
            knobSnap,
            becomeSnap,
            "BECOME's row does not snap with the knob row's expression; the card's two rows would step " +
                "differently under the same thumb.",
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

- [ ] **Step 9: Break the row's guards, one at a time**

Three mutations of the screen, each run with Step 8's command, each expected to exit non-zero with only `law - MUTATE draws BECOME's row on every move, enabled by BECOME's own label` failing, on the message named:

1. **A finer snap on both rows.** In the `MutateCard(` call, change both `(f * 40f).roundToInt() / 40f` (in `onKnobChange` and `onBecomeChange`) to `(f * 100f).roundToInt() / 100f`. The law fails with `BECOME's row does not snap at 1/40`. (The two rows still agree, so the equality assertion alone would pass: this is the hole the literal closes.)
2. **An inline guard.** In `MutateCard`, change BECOME's `        StepperSlider(` line to `        if (becomeLabel != null) StepperSlider(`. The law fails with `BECOME's row is guarded`.
3. **A guard on the line before.** Instead, insert the line `        if (becomeLabel != null)` directly above BECOME's `        StepperSlider(`. The law fails with `BECOME's row is guarded`.

After each, edit the screen back exactly as Steps 4 and 6 wrote it (not `git checkout`: the screen holds this task's uncommitted work) and re-run Step 8's command, expecting `exit=0`.

- [ ] **Step 10: Re-read the screen diff adversarially**

`:app` is compiled only by CI, so read `git diff app/src/main/kotlin/com/snipsnap/app/ui/PadSheetScreen.kt` once against this list before committing. A speculative fix pushed for a job you cannot run costs a full CI cycle.
- Every name the diff uses exists at the place it is used. `becomeKnob` and `pendingBecome` sit in the composable scope above `onMutate`, and `MutateCard`'s four new parameters are passed by name at its one call site.
- No import is needed: `mutableFloatStateOf` (`:35`) and `roundToInt` (`:117`) are already imported, and `MutateSheet` and `Mutate` are already in use.
- No `44_100`, no `/**` added inside a function body, and no Copy string.

- [ ] **Step 11: Commit**

```bash
git add shell/src/test/kotlin/com/snipsnap/shell/ConventionTest.kt app/src/main/kotlin/com/snipsnap/app/ui/PadSheetScreen.kt
git commit -m "The MUTATE card draws BECOME under MORPH's knob, and a disabled row for every other move

A second StepperSlider under the move's knob: BECOME on MORPH, reading
OFF or the milliseconds, and the same disabled dash row STACK's knob
shows on every other move, so the card never jumps. It is enabled on
its own label, not on knobLabel as the spec words it, because knobLabel
is set on four moves that ignore BECOME; a law refuses the copy. The
fraction and the readout both read pendingBecome, and the row snaps with
exactly the knob row's (f * 40f).roundToInt() / 40f, the expression the
sheet test sweeps and proves lands on whole 50 ms steps; the law pins
that literal, not only the two rows' equality. The row's StepperSlider
must stand alone on its line after the knob row's close, so no guard,
inline or on the line before, can hide it on some moves.

Claude-Session: https://claude.ai/code/session_01JttZq5ZXbhD6LNXvRhJbJY"
```

---

### Task 4: The whole suite, the plan row and the CLI doc

**Files:**
- Modify: `docs/FEATURE_PLAN.md` (row QQ4)
- Modify: `docs/CLI.md` (the phone paragraph of `mutate`)

- [ ] **Step 1: Run the whole JVM suite**

Run: `./gradlew --no-daemon test; echo "exit=$?"`. In a session where `./gradlew --no-daemon projects` lists `:app`, run `./gradlew --no-daemon test -x :app:test` instead; that is what CI runs.
Expected: `exit=0`. This includes `MutateSheetTest`, `BecomeTest`, `ConventionTest` and `PersonalityTest`, the last unchanged because no `Copy` string was added. The native block (`cmake … ctest`) need not run: nothing under `app/src/main/cpp` changed.

- [ ] **Step 2: Flip QQ4's opening to "built, CI compile and phone check pending"**

"Landed" would read as a compile claim, and no compiler has seen Tasks 2 and 3 yet: `android-build` is the first. So the row says built, and Task 5 flips it only after CI is green and the phone check passes.

In `docs/FEATURE_PLAN.md`, row QQ4, replace the opening

```markdown
| QQ4 | A1 landed; A1b, the MUTATE card's row, follows (`docs/superpowers/plans/2026-10-02-a1b-become-card.md`). The A1 gate (2026-10-02)
```

with

```markdown
| QQ4 | A1 landed; A1b built, CI compile and the owner's phone check pending: the MUTATE card's BECOME row (`docs/superpowers/plans/2026-10-02-a1b-become-card.md`). The A1 gate (2026-10-02)
```

and in the same row's exit-test column replace

```markdown
--become without --morph refused; drift unchanged |
```

with

```markdown
--become without --morph refused; drift unchanged; the card's laws: BECOME's row drawn on every move and enabled by its own label, read by HEAR and KEEP before their launch, put back to OFF by DRIFT |
```

Run: `grep -c 'A1b built, CI compile and the owner' docs/FEATURE_PLAN.md; grep -c "the card's laws: BECOME's row" docs/FEATURE_PLAN.md`
Expected: `1` and `1`.

- [ ] **Step 3: The CLI doc's phone paragraph**

`docs/CLI.md`'s `mutate` section says the phone's card has "the move's one knob (AT · HZ · MIX)". After A1b, MORPH has a second, so that line would go stale. In `docs/CLI.md` (`:553-556`), replace

```markdown
from the shelf, the move's one knob (AT · HZ · MIX), MUTATE and UNDO —
the same recipe, provenance and bin as the terminal.
```

with

```markdown
from the shelf, the move's one knob (AT · HZ · MIX), MORPH's second
knob BECOME (how long the hit takes to turn into the blend), MUTATE and
UNDO — the same recipe, provenance and bin as the terminal.
```

Run: `grep -c "MORPH's second" docs/CLI.md; grep -c 'knob BECOME (how long the hit takes' docs/CLI.md`
Expected: `1` and `1`.

- [ ] **Step 4: Commit**

```bash
git add docs/FEATURE_PLAN.md docs/CLI.md
git commit -m "QQ4 records the card row built, with CI's compile and the owner's phone check pending

A1b wrote BECOME's row on the MUTATE card under three ConventionTest
laws, but no compiler has seen the screen yet: android-build is the
first, so the row says built, not landed. The sound was approved at the
A1 gate, so after CI the phone check is the last gate, and the row stays
short of done until it passes. The CLI doc's phone paragraph now names
MORPH's second knob, BECOME, beside the move's one knob.

Claude-Session: https://claude.ai/code/session_01JttZq5ZXbhD6LNXvRhJbJY"
```

- [ ] **Step 5: What CI will run**

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
10. **TalkBack on MORPH:** the row reads "BECOME" and its value, and it can be adjusted. On any other move it reads as disabled, and the dead `—` rows (two on STACK, identical) announce sensibly or are skipped, not read out as a bare dash twice.
11. **DRIFT with an empty crate:** on a kit whose shelf has nothing to deal, from SPLICE with BECOME 400 ms remembered on MORPH, tap DRIFT. The toast says the crate is empty, and the card lands on MORPH with BECOME `OFF` (and MIX 50%), the pad unchanged. That reset before the write is intended (Global Constraints), so a yes here means it behaved as described, and a no that the owner would rather keep the dialled BECOME is a spec question, not a card fix.

- [ ] **Step 2: STOP and act on the answers**

Record the owner's answers verbatim in the PR description.

**All yes, with `android-build` and `emulator-tests` green on the PR:** in `docs/FEATURE_PLAN.md` row QQ4, replace the opening

```markdown
| QQ4 | A1 landed; A1b built, CI compile and the owner's phone check pending: the MUTATE card's BECOME row (`docs/superpowers/plans/2026-10-02-a1b-become-card.md`). The A1 gate (2026-10-02)
```

with

```markdown
| QQ4 | ✓ done (A1 and A1b; the A1 gate and the owner's phone check passed): BECOME on the MUTATE card. The A1 gate (2026-10-02)
```

then run `grep -c '✓ done (A1 and A1b' docs/FEATURE_PLAN.md` (expected `1`) and commit it alone:

```bash
git add docs/FEATURE_PLAN.md
git commit -m "QQ4 is done: the owner's phone check passed BECOME's card row

Every line of the A1b phone check came back yes, and android-build
compiled the screen; the answers are in the pull request.

Claude-Session: https://claude.ai/code/session_01JttZq5ZXbhD6LNXvRhJbJY"
```

**Any no:** QQ4 is not flipped.
- A no on lines 1, 2, 3 or 10 is a card fix. (A no on line 10 about the dead rows' announcement is a card fix too, outside the three laws: the law to write first is the one that pins what the fix adds.) Write the law that would have caught it first, watch it fail, then edit the screen, as in Tasks 2 and 3.
- A no on lines 4 to 9, or a no on line 11 that the card did something other than described, is a fault in what HEAR, KEEP, DRIFT or the holder do. Find the failing claim among Task 2's laws, or among A1's `MutateSheetTest` and `BecomeTest` pins, before changing anything.
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
- **Laws and tests against today's tree (re-run after the review fixes, 2026-10-02):** the worktree was copied to a scratch directory (no `.git`, no build dirs), and every Task 1-3 block was applied from this plan's fenced code by a script that requires each find anchor to match exactly once. All matched. Then, with `./gradlew --no-daemon --offline` and the exit code as the verdict:
  - Task 1 Step 3: `MutateSheetTest` exit 0, both new tests passing on first run.
  - Task 1 Step 4: each of the three `:shell` mutations exited 1 with the messages now quoted in that step, and nothing else failed in `MutateSheetTest` or `BecomeTest`. The lower-case-label mutation as the review proposed it (label only) fails on the OFF assertion with `<0%>`, because `MutateSheet.label` dispatches on the label, so the step now changes the `when` branch too.
  - Task 2 Step 3: exactly the two named laws failed, with the named messages. Step 8: `ConventionTest` exit 0. Step 9: all three reset mutations failed only that law, with the named messages, and restoring the line gave exit 0.
  - Task 3 Step 2: exactly the one named law failed, with the named message. Step 8: exit 0. Step 9: all three row mutations failed only that law, with the named messages, and restoring gave exit 0.
  - The full `./gradlew test` was not re-run after these fixes. The reviewer's run of the earlier version exited 0, and the fixes touch only the two test files run above.
  - `:app` was not compiled (no SDK). `android-build` is the first compiler to see the screen.
- **Review Focus:** each of the five lines names a test or law that Task 1, 2 or 3 writes. The snap line now says what is actually pinned: the literal on the screen (Task 3), the sweep of that literal (Task 1).
- **Review findings (2026-10-02), applied:**
  - The 1/40 literal was pinned nowhere. Task 3's law now asserts BECOME's snap is exactly `(f * 40f).roundToInt() / 40f`, as well as equal to the knob row's, and the Review Focus line says so. Task 3 Step 9 shows both rows at 1/100 failing it.
  - Task 1's tests do not fail before. That is disclosed in Task 1's opening, and Step 4 now proves each guard by mutation in `:shell`, reverted each time. The restatements of `MutateSheetTest.kt:346-366` and `:179` were trimmed: the bare `defaultFraction == 0` and the AT `40 ms` assertions. Kept: the composed "the holder's opening fraction reads OFF" assertion, which is the one claim the card leans on, now with a message. Also kept: the sweep's first (`50 ms`) and last (`2000 ms`) readouts. They come through the snap from arbitrary thumbs, so they are not restatements of A1's literal-value labels. Every kept assertion carries a message.
  - The row's "every move" guard: the second `StepperSlider(` must be alone on its trimmed line, as proposed. Beyond the proposed fix, the previous non-blank code line must be `)`, the knob row's close, because an exact-line check alone passes a braceless guard on the line before (Step 9 mutation 3). The old regex stays as a further net.
  - DRIFT's reset: the holder regex now runs over `codeOnly(src)`, and the reset is checked at `val kitDir`'s brace depth, as proposed. Beyond the proposed fix, the reset's trimmed line must be exactly `pendingBecome = MutateSheet.BECOME.defaultFraction`, and only comments may stand between the `if (!onMorph)` block and the reset. The depth check alone passes the review's own example, a braceless `if (spins > 0) pendingBecome = …`, at depth 1. The adjacency check also catches a guard on the line before. Global Constraints now says "directly after the block" to match.
  - Commit trailer: a Global Constraints line, plus the trailer on every commit block.
  - QQ4 now reads "A1 landed; A1b built, CI compile and the owner's phone check pending" until Task 5. Task 5 flips it to done only with `android-build` and `emulator-tests` green and every phone-check line yes. Task 4's commit body no longer says the row was drawn.
  - `docs/CLI.md`'s phone paragraph gains MORPH's second knob, BECOME (Task 4 Step 3, File Structure table).
  - The label-width check's comment no longer claims a pixel fit. It calls the check a coarse letter-count proxy, and phone check line 3 is the real gate.
  - Phone check: line 10 now asks that the dead `—` rows announce sensibly or are skipped. A new line 11 exercises a DRIFT with an empty crate. Global Constraints states that the reset before the write is intended.
- **Review findings, skipped:** none wholly. Two are applied in part, as recorded above. First, the review's lower-case-label mutation alone cannot reach the shout assertion, so it changes the `when` branch too. Second, the composed OFF assertion is kept rather than trimmed, because it is the claim the holder and the DRIFT reset lean on, and A1's test states its two halves only separately.
- **Gate answers carried:** the owner's A1 gate answers (2026-10-02: Q1 "They all do", Q2 "A different sound", Q3 "6 is as good or better") stand in Global Constraints unchanged. So does the recorded deviation, the row enabled on `becomeLabel != null` and not on `knobLabel`.
