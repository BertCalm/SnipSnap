# FX Rack Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add five sections to the per-pad FX rack (SPIKE, RING, DUST, PHASE,
PITCH) and two treatments to the keyed family (ROLL, GATE), behind two refactors
that make the additions safe, and regroup the PAD SHEET card to hold them.

**Architecture:** `FxChain` stops enumerating its sections by hand. One
`SECTIONS` table drives macro validation, bypass, processing order, JSON
emission and parsing; a small public accessor API (`SECTION_NAMES`,
`section`, `withSection`, `macrosOf`) lets `:shell`'s `Treatments` and `Breed`
iterate it across the module boundary. `MacroSpec` gains a `neutral` so the AMT
knob scales centered macros toward flat rather than toward zero. New sections
are then one table entry each. ROLL and GATE join `Keyed` rather than the rack,
because they read the kit's tempo and the rack reads nothing but the sound.

**Task order:** 1–5 are the registry and are independently reviewable; nothing
after them is safe without them. 6–7 are the `neutral` fix, which Task 15 then
depends on. 8–13 are the four contract-clean sections. 14–17 are PITCH, which
restructures `process()`. 18–20 are the keyed pair. 21–22 regroup the card once,
after every chip exists. Tasks 7, 17 and 22 change behaviour for existing kits
and say so in their commit messages.

**Tech Stack:** Kotlin 2.0.21, JVM 17, Gradle multi-module (`:audio`, `:json`,
`:kit`, `:synth`, `:shell`, `:cli`, `:app`), `kotlin.test` on JUnit Platform.

**Spec:** `docs/superpowers/specs/2026-09-13-fx-rack-expansion-design.md`

## Global Constraints

- **Gate for every task:** `./gradlew test` must pass. No task is done with a red gate.
- **`Snip.equals` ignores sample content.** It compares `channels`, `sampleRate`
  and `samples.size` only — `assertEquals(snipA, snipB)` passes on completely
  different audio. Every audio assertion uses `samples.contentEquals(...)`.
- **`FxChain.VERSION` stays `1`.** `fromJsonValue` throws on any mismatch, so a
  bump breaks every existing `kit.json`. An absent section key round-trips as bypass.
- **Never add to `Shuffle.TREATMENTS`.** It is index-addressed by a seeded
  `Random`; adding to it changes what every saved seed produces. New characters
  go in `Treatments.EXTRA`.
- **Kotlin `internal` is Gradle-module-scoped.** `:shell` cannot see `:synth`'s
  `internal` declarations. `Dsp` and `Section` are internal to `:synth`; anything
  `Treatments`/`Breed`/`Keyed` needs must be public.
- **Every rack section:** macros are `0..1`; **every macro at its own `neutral`**
  is transparent (return the input's samples unchanged). For most sections
  neutral is 0, so that reads as "all-zeros is transparent" as it always has —
  but SPIKE's `SUSTAIN` and PITCH's `SEMITONES` are centered, so for them
  transparency is `0.5`, and literal all-zeros is a real change to the sound.
  Nothing enforces literal all-zeros across sections; the neutral is the rule.
  Output is deterministic across two identical runs;
  every sample finite and in `-1f..1f`; output peak within `0.05f` of input peak;
  a mono-duplicated stereo input keeps both channels identical.
- **Naming, exact:** PITCH is Kotlin object `Speed`, `FxChain` field `speed`,
  JSON key `"speed"`, treatment `"pitched"`, chip `PITCH`. SPIKE's second macro
  is `SUSTAIN`, never `BODY` (`BODY` is already a chip).
- **Test style:** `@Test` from `kotlin.test`, backticked sentence function names,
  `assertEquals`/`assertTrue`/`assertNull`/`assertFailsWith`. Fixtures in
  `FxTest` are `Thump.render(ThumpVoice.KICK)` / `ThumpVoice.SNARE`.

---

## Task 1: The section registry, additive only

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/FxChain.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt`

**Interfaces:**
- Produces: `FxChain.SECTION_NAMES: List<String>`, `FxChain.macrosOf(name: String): List<MacroSpec>`, `FxChain.section(name: String): Map<String, Float>?`, `FxChain.withSection(name: String, macros: Map<String, Float>?): FxChain`, and internal `FxChain.SECTIONS: List<Section>` with `Section(name, macros, get, with, run, stage)` and `enum class Stage { TRANSPORT, ARRIVAL, RACK }`.

Nothing existing changes in this task. The table is added beside the hand-written
code and proved to agree with it.

- [ ] **Step 1: Write the failing test**

In `FxTest.kt`:

```kotlin
    @Test
    fun `the section table agrees with the hand-written fields`() {
        assertEquals(
            listOf("swell", "smear", "ghost", "eq", "squash", "crunch", "dub", "tape", "echo", "spring", "motion"),
            FxChain.SECTION_NAMES,
        )
        for (name in FxChain.SECTION_NAMES) {
            val macros = FxChain.macrosOf(name).associate { it.name to 0.7f }
            val chain = FxChain().withSection(name, macros)
            assertEquals(macros, chain.section(name), "$name: withSection and section disagree")
            assertEquals(null, FxChain().section(name), "$name: an empty chain is not bypassed there")
        }
    }

    @Test
    fun `an unknown section name is refused by name`() {
        assertFailsWith<IllegalArgumentException> { FxChain().section("nope") }
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.FxTest"`
Expected: FAIL — `Unresolved reference: SECTION_NAMES`.

- [ ] **Step 3: Add the table and accessors**

In `FxChain.kt`, add inside the `companion object`, above `fun fromJsonValue`:

```kotlin
        /** Which pass a section belongs to: the two before the tail budget is measured, and the rack. */
        enum class Stage { TRANSPORT, ARRIVAL, RACK }

        /**
         * One rack section, described rather than hand-written. The list below
         * IS the order — in the signal path and in JSON alike — so the two can
         * no longer drift apart.
         */
        internal class Section(
            val name: String,
            val macros: List<MacroSpec>,
            val get: (FxChain) -> Map<String, Float>?,
            val with: (FxChain, Map<String, Float>?) -> FxChain,
            val run: (Snip, Map<String, Float>) -> Snip,
            val stage: Stage = Stage.RACK,
        )

        internal val SECTIONS: List<Section> = listOf(
            Section("swell", Swell.MACROS, { it.swell }, { c, m -> c.copy(swell = m) }, Swell::process, Stage.ARRIVAL),
            Section("smear", Smear.MACROS, { it.smear }, { c, m -> c.copy(smear = m) }, Smear::process),
            Section("ghost", Ghost.MACROS, { it.ghost }, { c, m -> c.copy(ghost = m) }, Ghost::process),
            Section("eq", Eq.MACROS, { it.eq }, { c, m -> c.copy(eq = m) }, Eq::process),
            Section("squash", Squash.MACROS, { it.squash }, { c, m -> c.copy(squash = m) }, Squash::process),
            Section("crunch", Crunch.MACROS, { it.crunch }, { c, m -> c.copy(crunch = m) }, Crunch::process),
            Section("dub", Dub.MACROS, { it.dub }, { c, m -> c.copy(dub = m) }, Dub::process),
            Section("tape", Tape.MACROS, { it.tape }, { c, m -> c.copy(tape = m) }, Tape::process),
            Section("echo", Echo.MACROS, { it.echo }, { c, m -> c.copy(echo = m) }, Echo::process),
            Section("spring", Spring.MACROS, { it.spring }, { c, m -> c.copy(spring = m) }, Spring::process),
            Section("motion", Motion.MACROS, { it.motion }, { c, m -> c.copy(motion = m) }, Motion::process),
        )

        /**
         * Every section's name, in rack order — the door other modules use.
         * [Section] itself stays internal: `internal` is module-scoped, and
         * `:shell`'s Treatments and Breed live outside this one.
         */
        val SECTION_NAMES: List<String> get() = SECTIONS.map { it.name }

        /** [name]'s macro specs, or an error naming the sections there are. */
        fun macrosOf(name: String): List<MacroSpec> = sectionOf(name).macros

        internal fun sectionOf(name: String): Section =
            SECTIONS.firstOrNull { it.name == name }
                ?: throw IllegalArgumentException(
                    "unknown fx section '$name' - the rack has: ${SECTION_NAMES.joinToString(", ")}",
                )
```

And as instance members, directly above `fun toJsonValue()`:

```kotlin
    /** [name]'s macros on this chain, or null when the section is bypassed. */
    fun section(name: String): Map<String, Float>? = sectionOf(name).get(this)

    /** This chain with [name] set to [macros]; null bypasses the section. */
    fun withSection(name: String, macros: Map<String, Float>?): FxChain = sectionOf(name).with(this, macros)
```

- [ ] **Step 4: Run the tests and make sure they pass**

Run: `./gradlew test`
Expected: PASS — the whole suite, unchanged.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/FxChain.kt synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt
git commit -m "Add the FxChain section table beside the hand-written fields"
```

---

## Task 2: `FxChain` reads its own table

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/FxChain.kt:42-89, 120-164`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt`

**Interfaces:**
- Consumes: `SECTIONS`, `Stage`, `sectionOf` from Task 1.
- Produces: no new API. `init`, `isBypass`, `process`, `processRest`, `toJsonValue` and `fromJsonValue` all iterate `SECTIONS`.

- [ ] **Step 1: Write the failing test**

In `FxTest.kt`:

```kotlin
    @Test
    fun `every section round-trips through json and defeats bypass`() {
        for (name in FxChain.SECTION_NAMES) {
            val macros = FxChain.macrosOf(name).associate { it.name to 0.7f }
            val chain = FxChain().withSection(name, macros)
            assertTrue(!chain.isBypass, "$name: isBypass does not see it")
            assertTrue(chain.toJsonText().contains("\"$name\""), "$name: toJsonValue does not emit it")
            assertEquals(chain, FxChain.fromJsonText(chain.toJsonText()), "$name: fromJsonValue drops it")
            val out = chain.process(kick)
            assertTrue(!out.samples.contentEquals(kick.samples), "$name: process is a no-op")
        }
    }

    @Test
    fun `json emits sections in rack order`() {
        var chain = FxChain()
        for (name in FxChain.SECTION_NAMES) {
            chain = chain.withSection(name, FxChain.macrosOf(name).associate { it.name to 0.6f })
        }
        val text = chain.toJsonText()
        val positions = FxChain.SECTION_NAMES.map { text.indexOf("\"$it\"") }
        assertEquals(positions.sorted(), positions, "json order is not rack order")
        assertTrue(positions.all { it > 0 }, "a section was not emitted")
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.FxTest"`
Expected: PASS already — the hand-written code is correct today. That is the
point: this pair is the characterization net for Step 3. If either fails now,
stop and report, because the refactor below would bake in a bug.

- [ ] **Step 3: Replace the hand-enumerated bodies**

In `FxChain.kt`, replace the whole `init { ... }` block with:

```kotlin
    init {
        for (s in SECTIONS) {
            val macros = s.get(this) ?: continue
            val names = s.macros.map { it.name }.toSet()
            for ((k, v) in macros) {
                require(k in names) { "unknown ${s.name} macro $k (knows $names)" }
                require(v in 0f..1f) { "${s.name} macro $k out of 0..1: $v" }
            }
        }
    }
```

Replace `isBypass`:

```kotlin
    val isBypass: Boolean
        get() = !reverse && SECTIONS.all { it.get(this) == null }
```

Replace `process` and `processRest`:

```kotlin
    fun process(snip: Snip): Snip {
        // TRANSPORT then ARRIVAL: the tail budget is measured from what they leave behind.
        var head = snip
        for (sec in SECTIONS) {
            if (sec.stage == Stage.RACK) continue
            sec.get(this)?.let { head = sec.run(head, it) }
        }
        return capTail(head, processRest(head))
    }

    private fun processRest(snip: Snip): Snip {
        var s = snip
        if (reverse) s = reversed(s)
        for (sec in SECTIONS) {
            if (sec.stage != Stage.RACK) continue
            sec.get(this)?.let { s = sec.run(s, it) }
        }
        return s
    }
```

Replace the `for ((name, macros) in listOf(...))` loop in `toJsonValue` with:

```kotlin
        for (sec in SECTIONS) {
            val macros = sec.get(this) ?: continue
            obj[sec.name] = JsonValue.Obj(
                macros.entries.associateTo(LinkedHashMap()) { (k, v) -> k to JsonValue.Num(v.toDouble()) },
            )
        }
```

Replace the `return FxChain(...)` in `fromJsonValue` with:

```kotlin
            var chain = FxChain(reverse = (obj["reverse"] as? JsonValue.Bool)?.value ?: false)
            for (sec in SECTIONS) {
                val macros = (obj[sec.name] as? JsonValue.Obj)?.entries?.mapValues { (_, v) -> v.num().toFloat() }
                    ?: continue
                chain = sec.with(chain, macros)
            }
            return chain
```

Delete the now-unused local `fun section(name: String)` helper inside `fromJsonValue`.

- [ ] **Step 4: Run the tests and make sure they pass**

Run: `./gradlew test`
Expected: PASS — every existing test unmodified. A refactor that needs test edits
is not behaviour-preserving; if one fails, revert and report rather than editing it.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/FxChain.kt synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt
git commit -m "Drive FxChain's six enumeration sites from the section table"
```

---

## Task 3: `Treatments` scales from the table

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt:57-71`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt`

**Interfaces:**
- Consumes: `FxChain.SECTION_NAMES`, `FxChain.section`, `FxChain.withSection`.

A section missing from `scale()` is silently unscaled by the AMT knob. This task
makes that structurally impossible and adds the test that proves it.

- [ ] **Step 1: Write the failing test**

In `FxTest.kt`:

```kotlin
    @Test
    fun `AMT scales every section a treatment sets`() {
        for (name in Treatments.names) {
            val full = Treatments.chain(name, 1f)
            val half = Treatments.chain(name, 0.5f)
            for (section in FxChain.SECTION_NAMES) {
                val a = full.section(section) ?: continue
                val b = half.section(section)
                    ?: throw AssertionError("$name: AMT 0.5 dropped section $section entirely")
                for ((macro, v) in a) {
                    assertTrue(
                        b.getValue(macro) < v + 1e-6f,
                        "$name: AMT 0.5 left $section.$macro at $v - the section is not scaled",
                    )
                }
            }
        }
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.FxTest"`
Expected: PASS — all eleven sections are currently listed in `scale()`. This is a
characterization test: it must stay green through Step 3 and will fail loudly the
first time a future section is added to `FxChain` but forgotten here.

- [ ] **Step 3: Replace `scale()`'s hand-written `copy`**

In `Treatments.kt`, replace everything from `fun scale(params:` to the closing
`)` of `base.copy(...)` with:

```kotlin
        var out = base
        for (name in FxChain.SECTION_NAMES) {
            val macros = base.section(name) ?: continue
            out = out.withSection(name, macros.mapValues { (_, v) -> (v * amount).coerceIn(0f, 1f) })
        }
        return out
```

- [ ] **Step 4: Run the tests and make sure they pass**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt
git commit -m "Scale treatments from the section table, not a hand-written copy"
```

---

## Task 4: `Breed` crosses from the table

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Breed.kt:180-197`
- Test: `shell/src/test/kotlin/com/snipsnap/shell/BreedFxTest.kt` (create)

**Interfaces:**
- Consumes: `FxChain.SECTION_NAMES`, `FxChain.section`, `FxChain.withSection` — the public API from Task 1, reached across the `:shell` → `:synth` module boundary.

A section missing from `Breed.cross` is silently dropped from every bred child.

- [ ] **Step 1: Write the failing test**

Create `shell/src/test/kotlin/com/snipsnap/shell/BreedFxTest.kt`:

```kotlin
package com.snipsnap.shell

import com.snipsnap.synth.FxChain
import com.snipsnap.synth.PadRecipe
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class BreedFxTest {

    @Test
    fun `breeding two parents keeps every rack section`() {
        var chain = FxChain()
        for (name in FxChain.SECTION_NAMES) {
            chain = chain.withSection(name, FxChain.macrosOf(name).associate { it.name to 0.6f })
        }
        val parent = PadRecipe(fx = chain)
        for (seed in 0 until 8) {
            val child = Breed.cross(parent, parent, Random(seed))
            for (name in FxChain.SECTION_NAMES) {
                assertTrue(
                    child.fx?.section(name) != null,
                    "seed $seed: breed dropped section '$name' - both parents had it",
                )
            }
        }
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.BreedFxTest"`
Expected: PASS — all eleven sections are listed in `cross` today. Characterization
net, as in Task 3. If `Breed.cross` is not visible from the test, widen it from
`internal` to `internal` with the test in the same module (it already is) — do
not make it public.

- [ ] **Step 3: Replace the hand-written `FxChain(...)` call**

In `Breed.kt`, replace the `FxChain(...)` constructor call inside `cross` with:

```kotlin
            var chain = FxChain(
                reverse = if (fa != null && fb != null) {
                    if (rng.nextBoolean()) fa.reverse else fb.reverse
                } else {
                    fa?.reverse ?: fb!!.reverse
                },
            )
            for (name in FxChain.SECTION_NAMES) {
                chain = chain.withSection(name, crossMacros(fa?.section(name), fb?.section(name), rng))
            }
            chain
```

- [ ] **Step 4: Run the tests and make sure they pass**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add shell/src/main/kotlin/com/snipsnap/shell/Breed.kt shell/src/test/kotlin/com/snipsnap/shell/BreedFxTest.kt
git commit -m "Cross bred recipes from the section table, not a hand-written constructor"
```

---

## Task 5: The shared-contract test covers every section

**Files:**
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt:150-182`

The existing contract test registers 3 of 11 sections. Driving it from `SECTIONS`
covers all of them, including the eight uncovered since they shipped.

**Interfaces:**
- Consumes: `FxChain.SECTION_NAMES`, `FxChain.macrosOf`.

- [ ] **Step 1: Replace both tests with table-driven versions**

Replace `every effect is deterministic, clean and peak-matched everywhere` and
`stereo stays stereo with identical channels intact` with:

```kotlin
    @Test
    fun `every effect is deterministic, clean and peak-matched everywhere`() {
        // The section's own process, not the whole chain: this is the old test
        // widened to every section, not a new and stricter one. A chain call
        // would drag in capTail and the TRANSPORT/ARRIVAL stages, which the
        // three original sections were never measured through.
        for (sec in FxChain.SECTIONS) {
            val defaults = sec.macros.associate { it.name to it.default }
            assertTrue(
                sec.run(snare, defaults).samples.contentEquals(sec.run(snare, defaults).samples),
                "${sec.name} not deterministic",
            )
            for (seed in 0 until 6) {
                val rng = Random(seed)
                val macros = sec.macros.associate { it.name to rng.nextFloat() }
                val out = sec.run(snare, macros)
                assertTrue(out.samples.all { it.isFinite() && it in -1f..1f }, "${sec.name} roll $seed broke")
                assertTrue(abs(out.peak() - snare.peak()) < 0.05f, "${sec.name} roll $seed changed loudness")
            }
        }
    }

    @Test
    fun `stereo stays stereo with identical channels intact`() {
        val stereo = Snip(FloatArray(kick.frameCount * 2) { kick.samples[it / 2] }, 2, 44_100)
        for (sec in FxChain.SECTIONS) {
            val out = sec.run(stereo, sec.macros.associate { it.name to it.default })
            assertEquals(2, out.channels, "${sec.name} changed the channel count")
            for (f in 0 until out.frameCount) {
                assertEquals(out.samples[f * 2], out.samples[f * 2 + 1], "${sec.name}: channels diverged at $f")
            }
        }
    }
```

- [ ] **Step 2: Run them**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.FxTest"`
Expected: possibly FAIL. Eight sections have never been through this contract, so
any failure is a **pre-existing bug in shipped code**, not something this task
introduced.

Do not weaken the assertion, and **do not block the rest of the plan on it.**
Report each failure with the section name and the clause that failed, then add
that section to a documented exclusion list at the top of the test with a
one-line comment naming the bug, and carry on. Tasks 6–21 do not depend on these
eight being clean — they depend on the contract existing so the *new* sections
are held to it. Fixing a pre-existing failure is its own piece of work.

- [ ] **Step 3: Commit**

```bash
git add synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt
git commit -m "Cover every rack section with the shared-contract test, not three"
```

---

## Task 6: `MacroSpec` learns its neutral

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Thump.kt:25`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Eq.kt:20-24`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt`

**Interfaces:**
- Produces: `MacroSpec(name: String, default: Float, neutral: Float = 0f)`.

`scale()` moves macros toward zero. EQ's macros are `0.5 = flat`, so AMT 0.5 on a
flat EQ is a 6 dB cut. Unreachable today (no shipped treatment sets `eq`), and
the first real bug PITCH would hit.

- [ ] **Step 1: Write the failing test**

In `FxTest.kt`:

```kotlin
    @Test
    fun `AMT zero lands every macro on its neutral`() {
        for (name in FxChain.SECTION_NAMES) {
            for (spec in FxChain.macrosOf(name)) {
                val chain = FxChain().withSection(name, mapOf(spec.name to 1f))
                val faded = Treatments.fade(chain, 0f)
                assertEquals(
                    spec.neutral,
                    faded.section(name)!!.getValue(spec.name),
                    "$name.${spec.name}: AMT 0 did not land on its neutral",
                )
            }
        }
    }

    @Test
    fun `a flat EQ stays flat as AMT falls`() {
        val flat = FxChain(eq = mapOf("BASS" to 0.5f, "MID" to 0.5f, "AIR" to 0.5f))
        for (amount in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val faded = Treatments.fade(flat, amount)
            for ((macro, v) in faded.eq!!) {
                assertEquals(0.5f, v, "AMT $amount moved a flat $macro off flat")
            }
        }
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.FxTest"`
Expected: FAIL — `Unresolved reference: neutral` and `fade`.

- [ ] **Step 3: Add `neutral`, set EQ's, expose the scaler**

In `Thump.kt` line 25, replace the declaration with:

```kotlin
/**
 * One macro: its name, where it starts, and where it *does nothing*.
 * [neutral] is 0 for a macro that fades to silence and 0.5 for a centered
 * one (EQ's flat, PITCH's native) — `Treatments.chain` fades toward it, so
 * a centered macro is not dragged off centre as AMT falls.
 */
data class MacroSpec(val name: String, val default: Float, val neutral: Float = 0f)
```

In `Eq.kt`, replace the `MACROS` list with:

```kotlin
    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("BASS", 0.5f, neutral = 0.5f),
        MacroSpec("MID", 0.5f, neutral = 0.5f),
        MacroSpec("AIR", 0.5f, neutral = 0.5f),
    )
```

In `Treatments.kt`, replace the scaling loop added in Task 3 with a call to a
named function, and add that function to the object:

```kotlin
        return fade(base, amount)
    }

    /**
     * [chain]'s macros faded toward their neutrals by [amount] — 1 leaves the
     * chain alone, 0 lands every macro where it does nothing. A macro whose
     * neutral is 0 fades to silence, exactly as `v * amount` always did; a
     * centered one (EQ's flat, PITCH's native) fades to its centre instead of
     * being dragged off it.
     */
    fun fade(chain: FxChain, amount: Float): FxChain {
        var out = chain
        for (name in FxChain.SECTION_NAMES) {
            val macros = chain.section(name) ?: continue
            val neutrals = FxChain.macrosOf(name).associate { it.name to it.neutral }
            out = out.withSection(
                name,
                macros.mapValues { (macro, v) ->
                    val neutral = neutrals[macro] ?: 0f
                    (neutral + (v - neutral) * amount).coerceIn(0f, 1f)
                },
            )
        }
        return out
    }
```

- [ ] **Step 4: Run the tests and make sure they pass**

Run: `./gradlew test`
Expected: PASS. For `neutral = 0` the formula reduces exactly to `v * amount`, so
every existing section is bit-identical.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Thump.kt synth/src/main/kotlin/com/snipsnap/synth/Eq.kt synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt
git commit -m "Fade macros toward their neutral, not toward zero

Behaviour change for any treatment that sets eq: AMT now fades a flat
band toward flat instead of toward a 12 dB cut. No shipped treatment
sets eq, so nothing in the bank moves."
```

---

## Task 7: SPIKE

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/Spike.kt`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/FxChain.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt`

**Interfaces:**
- Produces: `object Spike` with `MACROS`, `defaults()`, `scramble(Random)`, `process(Snip, Map<String, Float>): Snip`; `FxChain` field `spike`.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `SPIKE at its neutral is a copy, and ATTACK sharpens the transient`() {
        val flat = Spike.process(kick, mapOf("ATTACK" to 0f, "SUSTAIN" to 0.5f))
        assertTrue(flat.samples.contentEquals(kick.samples), "SPIKE at neutral is not a copy")
        val sharp = Spike.process(kick, mapOf("ATTACK" to 1f, "SUSTAIN" to 0.5f))
        assertTrue(
            peakIn(sharp, 0f, 0.01f) / rms(sharp) > peakIn(kick, 0f, 0.01f) / rms(kick),
            "ATTACK did not raise the transient against the body",
        )
    }

    @Test
    fun `SPIKE SUSTAIN below centre dries the tail and above it swells`() {
        val dry = Spike.process(kick, mapOf("ATTACK" to 0f, "SUSTAIN" to 0f))
        val wet = Spike.process(kick, mapOf("ATTACK" to 0f, "SUSTAIN" to 1f))
        assertTrue(sustainRms(dry) < sustainRms(kick), "SUSTAIN 0 did not dry the tail")
        assertTrue(sustainRms(wet) > sustainRms(dry), "SUSTAIN 1 is not fuller than SUSTAIN 0")
    }

    @Test
    fun `a spiked kick is still a kick`() {
        assertEquals(DrumClass.KICK, Classifier.classify(Spike.process(kick)).drumClass)
        assertEquals(DrumClass.SNARE, Classifier.classify(Spike.process(snare)).drumClass)
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.FxTest"`
Expected: FAIL — `Unresolved reference: Spike`.

- [ ] **Step 3: Write `Spike.kt`**

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs

/**
 * SPIKE — the attack exaggerated, the inverse of the sections either side
 * of it. SMEAR takes the attack out and GHOST takes the tone out with it;
 * SPIKE leans the other way, so the rack can shape a hit's anatomy in both
 * directions rather than only downward.
 *
 * Two envelope followers at different speeds, and their *difference* is
 * the transient: where the signal rises faster than the slow follower can
 * track, the gap is the attack. ATTACK gains that gap; SUSTAIN gains what
 * is left, bipolar so 0.5 is untouched, below tightens and above swells.
 * Peak matched: shape, never loudness.
 *
 * Sits after GHOST and before EQ — anatomy before tone, like its
 * neighbours.
 */
object Spike {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("ATTACK", 0.4f),                    // how far the transient is pushed above the body
        MacroSpec("SUSTAIN", 0.5f, neutral = 0.5f),   // the body: 0.5 untouched, below drier, above fuller
    )

    /** The followers' time constants: fast enough to ride the attack, slow enough to miss it. */
    const val FAST_HZ = 700f
    const val SLOW_HZ = 18f

    /** The most either half may be multiplied by at the ends of its knob. */
    const val MAX_GAIN = 3f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val attack = m.getValue("ATTACK")
        val sustain = m.getValue("SUSTAIN")
        if (attack <= 0f && abs(sustain - 0.5f) < 1e-6f) {
            return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)
        }

        val transientGain = 1f + attack * (MAX_GAIN - 1f)
        val bodyGain = if (sustain >= 0.5f) {
            Dsp.lin((sustain - 0.5f) * 2f, 1f, MAX_GAIN)
        } else {
            Dsp.lin(sustain * 2f, 1f / MAX_GAIN, 1f)
        }

        val out = FloatArray(snip.samples.size)
        for (ch in 0 until snip.channels) {
            val fast = Dsp.OnePole(snip.sampleRate)
            val slow = Dsp.OnePole(snip.sampleRate)
            var i = ch
            while (i < out.size) {
                val x = snip.samples[i]
                val a = abs(x)
                val f = fast.lp(a, FAST_HZ)
                val s = slow.lp(a, SLOW_HZ)
                // The gap between a quick reading and a slow one IS the attack.
                val transient = (f - s).coerceAtLeast(0f)
                val total = f.coerceAtLeast(1e-9f)
                val share = (transient / total).coerceIn(0f, 1f)
                out[i] = x * (share * transientGain + (1f - share) * bodyGain)
                i += snip.channels
            }
        }

        // Shape, not loudness: match the input's peak.
        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val k = inPeak / outPeak
            for (i in out.indices) out[i] *= k
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }
}
```

- [ ] **Step 4: Register it in the table**

In `FxChain.kt`, add the field after `val ghost:`:

```kotlin
    val spike: Map<String, Float>? = null,
```

and the `Section` entry immediately after `ghost`'s:

```kotlin
            Section("spike", Spike.MACROS, { it.spike }, { c, m -> c.copy(spike = m) }, Spike::process),
```

Update the rack-order line in the class KDoc to name SPIKE between GHOST and EQ.

- [ ] **Step 5: Run the tests and make sure they pass**

Run: `./gradlew test`
Expected: PASS, including the table-driven contract test from Task 5 now
covering SPIKE automatically.

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Spike.kt synth/src/main/kotlin/com/snipsnap/synth/FxChain.kt synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt
git commit -m "SPIKE: the attack exaggerated, the inverse of SMEAR"
```

---

## Task 8: RING

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/Ring.kt`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/FxChain.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt`

**Interfaces:**
- Produces: `object Ring` with the same four members as `Spike`; `FxChain` field `ring`.

**RING is exempt from classifier identity.** Ring mod destroys pitch identity by
design, as GHOST destroys anatomy. Do not add a "a ringed kick is still a kick"
test and do not cap `MIX` to make one pass.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `RING MIX zero is a copy and a rung tone gains sidebands`() {
        val dry = Ring.process(tone, mapOf("MIX" to 0f))
        assertTrue(dry.samples.contentEquals(tone.samples), "RING at MIX 0 is not a copy")
        val wet = Ring.process(tone, mapOf("FREQ" to 0.5f, "MIX" to 1f))
        assertTrue(
            crossings(wet, 0.2f, 0.8f) != crossings(tone, 0.2f, 0.8f),
            "RING left the tone's pitch untouched",
        )
    }

    @Test
    fun `a ringed hit is no longer the hit it was`() {
        // The GHOST exemption: ring mod destroys pitch identity by design.
        val wet = Ring.process(kick, mapOf("FREQ" to 0.6f, "MIX" to 1f))
        assertTrue(likeness(kick, wet) < 0.9, "RING at full MIX barely changed the sound")
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.FxTest"`
Expected: FAIL — `Unresolved reference: Ring`.

- [ ] **Step 3: Write `Ring.kt`**

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.sin

/**
 * RING — the sound multiplied by a sine, which is the oldest way to make a
 * sampler sound like it is lying. Every partial splits into a sum and a
 * difference with the modulator, and neither is in the harmonic series any
 * more: metal, bells, radio.
 *
 * CRUNCH and DUB are the converter's own damage, quantized and honest.
 * RING is damage of a different species — inharmonic rather than coarse —
 * and sits between them for that reason.
 *
 * Two macros, MIX 0 transparent, peak matched, no tail, no seed: a sine is
 * deterministic. **A ringed hit is deliberately no longer the hit it was**,
 * the same exemption GHOST carries.
 */
object Ring {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("FREQ", 0.35f),  // the modulator: LOW_HZ..HIGH_HZ
        MacroSpec("MIX", 0.4f),    // wet against the untouched dry
    )

    /** Low enough to read as tremolo, high enough to read as metal. */
    const val LOW_HZ = 30f
    const val HIGH_HZ = 3_000f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** The modulator's frequency for a FREQ setting, Hz. */
    fun freqHz(macro: Float): Float = Dsp.expMap(macro.coerceIn(0f, 1f), LOW_HZ, HIGH_HZ)

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val mix = m.getValue("MIX")
        if (mix <= 0.001f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val step = 2.0 * Math.PI * freqHz(m.getValue("FREQ")) / snip.sampleRate
        val out = FloatArray(snip.samples.size)
        for (f in 0 until snip.frameCount) {
            // One modulator across the frame, so a stereo pair rings together.
            val mod = sin(step * f).toFloat()
            for (ch in 0 until snip.channels) {
                val i = f * snip.channels + ch
                val x = snip.samples[i]
                out[i] = x * (1f - mix) + x * mod * mix
            }
        }

        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val k = inPeak / outPeak
            for (i in out.indices) out[i] *= k
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }
}
```

- [ ] **Step 4: Register it in the table**

Field after `val crunch:`:

```kotlin
    val ring: Map<String, Float>? = null,
```

`Section` entry immediately after `crunch`'s:

```kotlin
            Section("ring", Ring.MACROS, { it.ring }, { c, m -> c.copy(ring = m) }, Ring::process),
```

Update the rack-order KDoc line to name RING between CRUNCH and DUB.

- [ ] **Step 5: Run the tests and make sure they pass**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Ring.kt synth/src/main/kotlin/com/snipsnap/synth/FxChain.kt synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt
git commit -m "RING: inharmonic damage, between CRUNCH and DUB"
```

---

## Task 9: An allpass for `Dsp.Biquad`

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/DspAllpassTest.kt` (create)

`Biquad` has `lowShelf`, `highShelf`, `bandpass` and `peaking` — no allpass.
PHASE is a cascade of them, so it needs one first.

**Interfaces:**
- Produces: `Dsp.Biquad.allpass(f0: Float, q: Float, rate: Int = RATE)`.

- [ ] **Step 1: Write the failing test**

Create `synth/src/test/kotlin/com/snipsnap/synth/DspAllpassTest.kt`:

```kotlin
package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

class DspAllpassTest {

    /** An allpass passes every magnitude and changes only phase. */
    @Test
    fun `an allpass keeps the level of every tone it is given`() {
        for (toneHz in listOf(100f, 440f, 2_000f, 8_000f)) {
            val n = 44_100
            val input = FloatArray(n) { sin(2.0 * Math.PI * toneHz * it / 44_100).toFloat() * 0.5f }
            val bq = Dsp.Biquad()
            bq.allpass(1_000f, 0.7f)
            val output = FloatArray(n) { bq.process(input[it]) }
            // Compare RMS over the second half, past the filter's settling.
            fun rms(a: FloatArray): Float {
                var s = 0.0
                for (i in a.size / 2 until a.size) s += (a[i] * a[i]).toDouble()
                return sqrt(s / (a.size / 2)).toFloat()
            }
            assertTrue(
                abs(rms(output) - rms(input)) < 0.01f,
                "allpass changed the level of ${toneHz}Hz: ${rms(input)} -> ${rms(output)}",
            )
        }
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.DspAllpassTest"`
Expected: FAIL — `Unresolved reference: allpass`.

- [ ] **Step 3: Add the factory**

In `Dsp.kt`, inside `class Biquad`, beside the other factory methods:

```kotlin
        /**
         * An allpass: every magnitude through untouched, the phase rotated
         * through 180 degrees around [f0]. The RBJ cookbook's form — the
         * numerator is the denominator reversed, which is what makes the
         * magnitude flat at every frequency.
         */
        fun allpass(f0: Float, q: Float, rate: Int = RATE) {
            val w0 = 2.0 * Math.PI * f0 / rate
            val cosW0 = Math.cos(w0)
            val alpha = Math.sin(w0) / (2.0 * q)
            set(
                (1.0 - alpha).toFloat(),        // b0
                (-2.0 * cosW0).toFloat(),       // b1
                (1.0 + alpha).toFloat(),        // b2
                (1.0 + alpha).toFloat(),        // a0 - set() divides the rest by this
                (-2.0 * cosW0).toFloat(),       // a1
                (1.0 - alpha).toFloat(),        // a2
            )
        }
```

`Biquad.set` has the signature `set(b0, b1, b2, a0, a1, a2)` and does the
division by `a0` itself, exactly as `lowShelf` and `peaking` already rely on.
Pass the raw coefficients, not pre-divided ones.

- [ ] **Step 4: Run the tests and make sure they pass**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt synth/src/test/kotlin/com/snipsnap/synth/DspAllpassTest.kt
git commit -m "Add an allpass biquad, the shape PHASE is built from"
```

---

## Task 10: PHASE

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/Phase.kt`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/FxChain.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt`

**Interfaces:**
- Consumes: `Dsp.Biquad.allpass` from Task 9.
- Produces: `object Phase`; `FxChain` field `phase`.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `PHASE DEPTH zero is a copy and a swept tone moves`() {
        val dry = Phase.process(tone, mapOf("DEPTH" to 0f))
        assertTrue(dry.samples.contentEquals(tone.samples), "PHASE at DEPTH 0 is not a copy")
        val wet = Phase.process(tone, mapOf("RATE" to 0.5f, "DEPTH" to 1f, "FEEDBACK" to 0.5f))
        assertTrue(
            abs(peakIn(wet, 0.1f, 0.2f) - peakIn(wet, 0.5f, 0.6f)) > 0.001f,
            "PHASE swept nothing - the notches are not moving",
        )
    }

    @Test
    fun `a phased kick is still a kick`() {
        assertEquals(DrumClass.KICK, Classifier.classify(Phase.process(kick)).drumClass)
        assertEquals(DrumClass.SNARE, Classifier.classify(Phase.process(snare)).drumClass)
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.FxTest"`
Expected: FAIL — `Unresolved reference: Phase`.

- [ ] **Step 3: Write `Phase.kt`**

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.sin

/**
 * PHASE — four allpasses in a row, their corner swept by one slow sine,
 * the result summed back with the dry. Where the rotated copy and the
 * original disagree they cancel, and the notches that makes slide up and
 * down the spectrum: the sound of a pedal that was on every record between
 * 1968 and 1979.
 *
 * Sits after TAPE and before ECHO. The sweep happens to the finished tone,
 * and ECHO's repeats then carry it at earlier points in the sweep, which is
 * what a phaser into a delay has always sounded like.
 *
 * The LFO starts at phase zero every time, so PHASE is deterministic
 * without a seed. DEPTH 0 is transparent; no tail.
 */
object Phase {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("RATE", 0.35f),      // sweep speed: SLOW_HZ..FAST_HZ
        MacroSpec("DEPTH", 0.6f),      // how far the notches travel
        MacroSpec("FEEDBACK", 0.3f),   // resonance: how sharp the notches get
    )

    const val SLOW_HZ = 0.15f
    const val FAST_HZ = 4f

    /** The sweep's corner travels between these. */
    const val LOW_HZ = 250f
    const val HIGH_HZ = 4_000f

    /** Four stages: two notches, the classic voicing. */
    const val STAGES = 4

    /** Resonance ceiling — past this the feedback path rings rather than colours. */
    const val MAX_FEEDBACK = 0.7f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val depth = m.getValue("DEPTH")
        if (depth <= 0.001f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val lfoHz = Dsp.expMap(m.getValue("RATE"), SLOW_HZ, FAST_HZ)
        val feedback = m.getValue("FEEDBACK") * MAX_FEEDBACK
        val step = 2.0 * Math.PI * lfoHz / snip.sampleRate

        val out = FloatArray(snip.samples.size)
        val stages = Array(snip.channels) { Array(STAGES) { Dsp.Biquad() } }
        val last = FloatArray(snip.channels)
        for (f in 0 until snip.frameCount) {
            // One LFO across the frame, so a stereo pair sweeps together.
            val lfo = (sin(step * f).toFloat() + 1f) * 0.5f
            val corner = Dsp.expMap(lfo * depth, LOW_HZ, HIGH_HZ)
            for (ch in 0 until snip.channels) {
                val i = f * snip.channels + ch
                val x = snip.samples[i]
                var v = x + last[ch] * feedback
                for (stage in stages[ch]) {
                    stage.allpass(corner, 0.7f)
                    v = stage.process(v)
                }
                last[ch] = v
                out[i] = (x + v) * 0.5f
            }
        }

        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val k = inPeak / outPeak
            for (i in out.indices) out[i] *= k
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }
}
```

- [ ] **Step 4: Register it in the table**

Field after `val tape:`:

```kotlin
    val phase: Map<String, Float>? = null,
```

`Section` entry immediately after `tape`'s:

```kotlin
            Section("phase", Phase.MACROS, { it.phase }, { c, m -> c.copy(phase = m) }, Phase::process),
```

Update the rack-order KDoc line to name PHASE between TAPE and ECHO.

- [ ] **Step 5: Run the tests and make sure they pass**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Phase.kt synth/src/main/kotlin/com/snipsnap/synth/FxChain.kt synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt
git commit -m "PHASE: four swept allpasses between TAPE and ECHO"
```

---

## Task 11: DUST

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/Dust.kt`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/FxChain.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt`

**Interfaces:**
- Produces: `object Dust`; `FxChain` field `dust`.

**DUST is the only section that adds signal.** All-zeros must return samples
bit-identical to the input, not input-plus-inaudible-noise. Noise beds are scaled
against the input's peak before summing, then the sum is peak-matched, so a
quiet source does not get a loud record under it.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `DUST at all zeros is bit-identical, not merely quiet`() {
        val out = Dust.process(kick, mapOf("CRACKLE" to 0f, "RUMBLE" to 0f, "HISS" to 0f))
        assertTrue(out.samples.contentEquals(kick.samples), "DUST at zero added something")
    }

    @Test
    fun `DUST is the same record every time`() {
        val a = Dust.process(kick, mapOf("CRACKLE" to 0.8f, "RUMBLE" to 0.5f, "HISS" to 0.5f))
        val b = Dust.process(kick, mapOf("CRACKLE" to 0.8f, "RUMBLE" to 0.5f, "HISS" to 0.5f))
        assertTrue(a.samples.contentEquals(b.samples), "DUST is not deterministic")
    }

    @Test
    fun `DUST puts noise in the silence after the hit`() {
        val quiet = Snip(FloatArray(44_100), 1, 44_100)
        val dusty = Dust.process(quiet, mapOf("CRACKLE" to 0f, "RUMBLE" to 0f, "HISS" to 1f))
        // Silence in, silence out: there is no peak to scale the beds against.
        assertTrue(dusty.samples.all { it == 0f }, "DUST made noise out of pure silence")
        val over = Dust.process(kick, mapOf("CRACKLE" to 0f, "RUMBLE" to 0f, "HISS" to 1f))
        assertTrue(sustainRms(over) > sustainRms(kick), "HISS did not reach the hit's tail")
    }

    @Test
    fun `a dusted kick is still a kick`() {
        assertEquals(DrumClass.KICK, Classifier.classify(Dust.process(kick)).drumClass)
        assertEquals(DrumClass.SNARE, Classifier.classify(Dust.process(snare)).drumClass)
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.FxTest"`
Expected: FAIL — `Unresolved reference: Dust`.

- [ ] **Step 3: Write `Dust.kt`**

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs

/**
 * DUST — the record, as TAPE is the cassette. A sampling app's source
 * medium is vinyl, and vinyl is never silent: a low rumble off the
 * platter, a hiss in the groove, and the crackle of every play before
 * this one.
 *
 * Sits after DUB and before TAPE, deliberately. TAPE then processes the
 * crackle along with everything else, because the chain being modelled is
 * a record dubbed to tape, not a record with tape painted beside it.
 *
 * The only section here that **adds** signal, so it carries two rules the
 * others get for free. All-zeros returns the input bit for bit. And the
 * beds are scaled against the *input's own peak* before they are summed,
 * so a quiet hit never ends up under a loud record; the sum is then peak
 * matched like everything else. Seeded, so a pad crackles the same way
 * forever. Ceilings follow `TapeWear`'s: earned noise is capped, and the
 * cap is the feature.
 */
object Dust {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("CRACKLE", 0.4f),  // how busy the surface is
        MacroSpec("RUMBLE", 0.25f),  // the platter under everything
        MacroSpec("HISS", 0.3f),     // the groove's own floor
    )

    /** One seed for every record: the same hit wears the same way. */
    const val SEED = 19

    /** The ceilings, against the input's peak. Honest maxima, as TapeWear's are. */
    const val CRACKLE_CEILING = 0.35f
    const val RUMBLE_CEILING = 0.05f
    const val HISS_CEILING = 0.004f   // about -48 dBFS against a full-scale hit

    const val RUMBLE_HZ = 60f
    const val CRACKLE_HZ = 2_200f
    const val CRACKLE_Q = 0.8f

    /** Clicks per second at CRACKLE 1. */
    const val MAX_CLICKS_PER_SEC = 90f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val crackle = m.getValue("CRACKLE")
        val rumble = m.getValue("RUMBLE")
        val hiss = m.getValue("HISS")
        if (crackle <= 0f && rumble <= 0f && hiss <= 0f) {
            return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)
        }

        // Nothing to scale the beds against: silence in, silence out.
        val inPeak = snip.peak()
        if (inPeak <= 1e-9f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val out = snip.samples.copyOf()
        val noise = Dsp.Noise(SEED)
        val rumbleFilter = Dsp.OnePole(snip.sampleRate)
        val crackleFilter = Dsp.Biquad().apply { bandpass(CRACKLE_HZ, CRACKLE_Q, snip.sampleRate) }
        val clickChance = crackle * MAX_CLICKS_PER_SEC / snip.sampleRate

        for (f in 0 until snip.frameCount) {
            var bed = 0f
            if (rumble > 0f) {
                bed += rumbleFilter.lp(noise.next(), RUMBLE_HZ) * rumble * RUMBLE_CEILING * inPeak
            }
            if (hiss > 0f) {
                bed += noise.next() * hiss * HISS_CEILING * inPeak
            }
            if (crackle > 0f) {
                // A click is a single impulse through a bandpass: a pop, not a tone.
                val impulse = if (noise.next() * 0.5f + 0.5f < clickChance) noise.next() else 0f
                bed += crackleFilter.process(impulse) * crackle * CRACKLE_CEILING * inPeak
            }
            // One bed across the frame, so a stereo pair shares one record.
            for (ch in 0 until snip.channels) out[f * snip.channels + ch] += bed
        }

        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        if (outPeak > 0f) {
            val k = inPeak / outPeak
            for (i in out.indices) out[i] *= k
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }
}
```

- [ ] **Step 4: Register it in the table**

Field after `val dub:`:

```kotlin
    val dust: Map<String, Float>? = null,
```

`Section` entry immediately after `dub`'s:

```kotlin
            Section("dust", Dust.MACROS, { it.dust }, { c, m -> c.copy(dust = m) }, Dust::process),
```

Update the rack-order KDoc line to name DUST between DUB and TAPE.

- [ ] **Step 5: Run the tests and make sure they pass**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Dust.kt synth/src/main/kotlin/com/snipsnap/synth/FxChain.kt synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt
git commit -m "DUST: the record under the tape, between DUB and TAPE"
```

---

## Task 12: Four new treatments on the card's behalf

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt`

Each new section needs a named character before the CLI or the card can reach it.
`TreatCommand` is generic over `Treatments.names`, so this alone makes all four
reachable from `snipsnap treat`.

**Interfaces:**
- Produces: treatment names `"spiked"`, `"ringed"`, `"dusted"`, `"phased"`.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `the four new characters exist and each sets its own section`() {
        for ((name, section) in listOf(
            "spiked" to "spike", "ringed" to "ring", "dusted" to "dust", "phased" to "phase",
        )) {
            assertTrue(name in Treatments.names, "'$name' is not a treatment")
            assertTrue(Treatments.chain(name, 1f).section(section) != null, "'$name' does not set $section")
            assertTrue(Treatments.chain(name, 0f).isBypass, "'$name' at AMT 0 is not a bypass")
        }
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.FxTest"`
Expected: FAIL — `'spiked' is not a treatment`.

- [ ] **Step 3: Add them to `EXTRA`**

Append inside `Treatments.EXTRA`, after `"swelled"`:

```kotlin
        // The attack leaned on rather than taken away.
        "spiked" to FxChain(spike = mapOf("ATTACK" to 0.7f, "SUSTAIN" to 0.45f)),
        // Multiplied by a sine: metal, bells, radio.
        "ringed" to FxChain(ring = mapOf("FREQ" to 0.45f, "MIX" to 0.5f)),
        // The record under the hit: rumble, groove, and every play before this one.
        "dusted" to FxChain(dust = mapOf("CRACKLE" to 0.5f, "RUMBLE" to 0.3f, "HISS" to 0.35f)),
        // Four allpasses, swept.
        "phased" to FxChain(phase = mapOf("RATE" to 0.3f, "DEPTH" to 0.7f, "FEEDBACK" to 0.4f)),
```

- [ ] **Step 4: Run the tests and make sure they pass**

Run: `./gradlew test`
Expected: PASS. Note `Shuffle.TREATMENTS` is untouched — the remix bank's five
stay five, so no saved seed changes.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt
git commit -m "Name the four new sections as characters: spiked, ringed, dusted, phased"
```

---

## Task 13: One variable-speed read, shared

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Motion.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/DspResampleTest.kt` (create)

`Motion` already owns a private `read(snip, outFrames, headAt)` — a
variable-speed head that takes a **position and a gain** per output frame, not a
speed. PITCH needs the same head at a constant rate. Move it to `Dsp` unchanged
and both varispeed stages share one interpolator.

Take the head as-is. It is position-based because `Motion.stop`/`start` compute
their positions in closed form (`k0 + (k - k0) * (1 - u/2)` is the integral of
the speed ramp); re-expressing them as speeds would be a rewrite of working
physics, not a refactor.

**Interfaces:**
- Produces: `Dsp.readAt(snip: Snip, outFrames: Int, headAt: (Int) -> Pair<Double, Float>): Snip` — for each output frame, `headAt(k)` gives the fractional source position to read and the gain to read it at. Linearly interpolated; a position at or past the last source frame, or a gain of zero, leaves the output frame silent.

- [ ] **Step 1: Write the failing test**

Create `synth/src/test/kotlin/com/snipsnap/synth/DspResampleTest.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DspResampleTest {

    private val ramp = Snip(FloatArray(1_000) { it / 1_000f }, 1, 44_100)

    @Test
    fun `a head that walks one frame at a time is the source back again`() {
        val out = Dsp.readAt(ramp, ramp.frameCount) { k -> k.toDouble() to 1f }
        assertEquals(ramp.frameCount, out.frameCount)
        // The last frame has no neighbour to interpolate toward, so the head stops short of it.
        for (i in 0 until ramp.frameCount - 1) {
            assertTrue(abs(out.samples[i] - ramp.samples[i]) < 1e-5f, "frame $i moved")
        }
    }

    @Test
    fun `a half-speed head reads the source twice as slowly`() {
        val out = Dsp.readAt(ramp, ramp.frameCount * 2) { k -> k * 0.5 to 1f }
        assertEquals(ramp.frameCount * 2, out.frameCount)
        assertTrue(abs(out.samples[ramp.frameCount] - ramp.samples[ramp.frameCount / 2]) < 0.01f)
        assertTrue(abs(out.samples[500] - ramp.samples[250]) < 0.01f)
    }

    @Test
    fun `reading past the end is silence, not a crash`() {
        val out = Dsp.readAt(ramp, ramp.frameCount * 3) { k -> k.toDouble() to 1f }
        assertEquals(0f, out.samples[out.frameCount - 1])
    }

    @Test
    fun `the head's gain rides the output`() {
        val out = Dsp.readAt(ramp, ramp.frameCount) { k -> k.toDouble() to 0.5f }
        assertTrue(abs(out.samples[500] - ramp.samples[500] * 0.5f) < 1e-5f, "gain was not applied")
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.DspResampleTest"`
Expected: FAIL — `Unresolved reference: readAt`.

- [ ] **Step 3: Move the head into `Dsp`**

Cut the `private inline fun read(...)` out of `Motion.kt` and paste it into
`Dsp.kt` at object level, renamed and with its KDoc widened:

```kotlin
    /**
     * A variable-speed head: for each output frame, [headAt] says where to
     * read (fractional) and how loud. Linearly interpolated between
     * neighbours; a position at or past the last source frame, or a zero
     * gain, leaves that output frame silent — a stop that outruns its
     * material simply runs out.
     *
     * The rack's two varispeed stages read through here — MOTION's capstan,
     * where the position is the integral of a speed ramp, and SPEED's pitch,
     * where it is a straight line — so they cannot drift apart in quality.
     */
    inline fun readAt(snip: Snip, outFrames: Int, headAt: (Int) -> Pair<Double, Float>): Snip {
        val ch = snip.channels
        val src = snip.samples
        val last = snip.frameCount - 1
        val out = FloatArray(outFrames * ch)
        for (k in 0 until outFrames) {
            val (pos, gain) = headAt(k)
            if (pos >= last || gain <= 0f) continue
            val i = pos.toInt()
            val frac = (pos - i).toFloat()
            for (c in 0 until ch) {
                val a = src[i * ch + c]
                val b = src[(i + 1) * ch + c]
                out[k * ch + c] = (a + (b - a) * frac) * gain
            }
        }
        return Snip(out, ch, snip.sampleRate)
    }
```

`Dsp.kt` needs `import com.snipsnap.audio.Snip` if it does not already have it.

In `Motion.kt`, change the two call sites from `read(snip, ...)` to
`Dsp.readAt(snip, ...)`. The lambdas are unchanged — this is the only edit;
`stop` and `start` keep their curves exactly.

- [ ] **Step 4: Run the tests and make sure they pass**

Run: `./gradlew test`
Expected: PASS, including MOTION's existing tests **bit-identically**. The body
moved without a character changing, so any MOTION test that moves at all means
the paste was not faithful — revert and redo rather than adjusting the test.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Dsp.kt synth/src/main/kotlin/com/snipsnap/synth/Motion.kt synth/src/test/kotlin/com/snipsnap/synth/DspResampleTest.kt
git commit -m "Share MOTION's variable-speed head with the rest of the rack"
```

---

## Task 14: `Speed` — the transport stage

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/Speed.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt`

**Interfaces:**
- Consumes: `Dsp.readAt(snip, outFrames) { k -> position to gain }` from Task 13.
- Produces: `object Speed` with `MACROS` (one macro `SEMITONES`, default 0.5, neutral 0.5), `SEMITONE_RANGE`, `semitones(macro: Float): Int`, `ratio(macro: Float): Float`, `defaults()`, `scramble(Random)`, `process(Snip, Map<String, Float>): Snip`.

The Kotlin object is `Speed`, not `Pitch`: `com.snipsnap.audio.Pitch` already
exists as the pitch *detector*.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `SPEED at native is a copy`() {
        val out = Speed.process(kick, mapOf("SEMITONES" to 0.5f))
        assertTrue(out.samples.contentEquals(kick.samples), "SPEED at native is not a copy")
    }

    @Test
    fun `SPEED snaps to semitones and an octave down doubles the length`() {
        assertEquals(0, Speed.semitones(0.5f))
        assertEquals(12, Speed.semitones(1f))
        assertEquals(-12, Speed.semitones(0f))
        val down = Speed.process(kick, mapOf("SEMITONES" to 0f))
        assertTrue(
            abs(down.frameCount - kick.frameCount * 2) < kick.frameCount / 20,
            "an octave down should be about twice as long: ${down.frameCount} vs ${kick.frameCount}",
        )
        val up = Speed.process(kick, mapOf("SEMITONES" to 1f))
        assertTrue(up.frameCount < kick.frameCount, "an octave up should be shorter")
    }

    @Test
    fun `SPEED moves the pitch of a tone the way it says`() {
        val up = Speed.process(tone, mapOf("SEMITONES" to 1f))
        assertTrue(
            crossings(up, 0.1f, 0.3f) > crossings(tone, 0.1f, 0.3f),
            "an octave up did not raise the pitch",
        )
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.FxTest"`
Expected: FAIL — `Unresolved reference: Speed`.

- [ ] **Step 3: Write `Speed.kt`**

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * PITCH — the transport, not an effect. In a sampler pitch *is* speed, and
 * the length comes along with it: that is the mechanism, and pretending
 * otherwise would need a third stretch engine in a rack whose discipline is
 * one idea per knob (SWELL already owns paulstretch, GRAINS the granular
 * cloud).
 *
 * MOTION is the capstan letting go at the end of a hit; this is the pitch
 * knob before the sound ever leaves the machine. It runs first, so SWELL's
 * stretched head, REVERSE's flip and the whole rack all see the pitched
 * sound — which is what a sampler plays.
 *
 * One macro, snapped to semitones, **0.5 = native**: the macro's neutral,
 * so AMT fades a pitched treatment back toward the original note rather
 * than toward a full octave down. No length cap of its own — capping a
 * varispeed truncates the note, which is worse than a long sample.
 *
 * The Kotlin object is `Speed` because `com.snipsnap.audio.Pitch` is
 * already the pitch *detector*; the user-facing word stays PITCH.
 */
object Speed {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("SEMITONES", 0.5f, neutral = 0.5f),  // -RANGE..+RANGE semitones, 0.5 native
    )

    /** How far the knob reaches either way. */
    const val SEMITONE_RANGE = 12

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** The snapped interval a macro names, -[SEMITONE_RANGE]..+[SEMITONE_RANGE]. */
    fun semitones(macro: Float): Int =
        ((macro.coerceIn(0f, 1f) - 0.5f) * 2f * SEMITONE_RANGE).roundToInt()

    /** How fast the head reads: 2 an octave up, 0.5 an octave down. */
    fun ratio(macro: Float): Float = 2f.pow(semitones(macro) / 12f)

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val steps = semitones(m.getValue("SEMITONES"))
        if (steps == 0) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val speed = ratio(m.getValue("SEMITONES"))
        val frames = (snip.frameCount / speed).toInt().coerceAtLeast(1)
        val out = Dsp.readAt(snip, frames) { k -> (k * speed).toDouble() to 1f }

        // Speed is not loudness: interpolation can overshoot, so hold the input's peak.
        var outPeak = 0f
        for (v in out.samples) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > inPeak) {
            val k = inPeak / outPeak
            for (i in out.samples.indices) out.samples[i] *= k
        }
        return out
    }
}
```

- [ ] **Step 4: Run the tests and make sure they pass**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Speed.kt synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt
git commit -m "SPEED: varispeed pitch, snapped to semitones, native at centre"
```

---

## Task 15: PITCH joins the rack at the transport

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/FxChain.kt`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt`

**Interfaces:**
- Consumes: `Speed` from Task 14; `Stage.TRANSPORT` from Task 1.
- Produces: `FxChain` field `speed`, JSON key `"speed"`, treatment `"pitched"`.

The tail budget needs no new arithmetic: `capTail` measures from whatever
`process` hands it, and it is now handed the pitched signal. The extra duration
is the user's instruction, not tail.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `the tail budget is measured from the pitched sound, not the original`() {
        val chain = FxChain(speed = mapOf("SEMITONES" to 0f))
        val out = chain.process(kick)
        assertTrue(
            out.frameCount > kick.frameCount * 1.5,
            "capTail truncated an octave-down hit to ${out.frameCount} from ${kick.frameCount}",
        )
    }

    @Test
    fun `SPEED runs before SWELL so the swell is grown from the pitched sound`() {
        assertEquals("speed", FxChain.SECTION_NAMES.first(), "speed is not first in the rack")
        assertEquals("swell", FxChain.SECTION_NAMES[1], "swell is not second in the rack")
    }

    @Test
    fun `AMT fades a pitched treatment back toward native`() {
        val full = Treatments.chain("pitched", 1f).speed!!.getValue("SEMITONES")
        val half = Treatments.chain("pitched", 0.5f).speed!!.getValue("SEMITONES")
        assertTrue(abs(half - 0.5f) < abs(full - 0.5f), "AMT 0.5 did not move PITCH toward native")
        assertTrue(Treatments.chain("pitched", 0f).isBypass, "AMT 0 is not a bypass")
    }

    @Test
    fun `a kit saved before PITCH existed still loads`() {
        val old = """{"fx":1,"reverse":false,"echo":{"TIME":0.4,"REPEAT":0.4,"TONE":0.5,"MIX":0.4}}"""
        val chain = FxChain.fromJsonText(old)
        assertEquals(null, chain.speed, "an absent speed key is not a bypass")
        assertEquals(1, FxChain.VERSION, "VERSION was bumped - every existing kit.json would throw")
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.FxTest"`
Expected: FAIL — `Unresolved reference: speed`.

- [ ] **Step 3: Register it first, at the transport**

In `FxChain.kt`, add the field as the **first** constructor parameter:

```kotlin
    /** The transport: pitch is speed, and it runs before everything. */
    val speed: Map<String, Float>? = null,
```

Add the `Section` entry as the **first** element of `SECTIONS`:

```kotlin
            Section("speed", Speed.MACROS, { it.speed }, { c, m -> c.copy(speed = m) }, Speed::process, Stage.TRANSPORT),
```

Update the class KDoc's order line to open with PITCH, and amend
`MAX_CHAIN_TAIL_SECONDS`'s doc comment:

```kotlin
        /**
         * Most tail the whole rack may add over its input, seconds —
         * measured from the *pitched, swelled* sound, not the original. A
         * hit pitched down an octave is twice as long by instruction, and
         * that length is not tail; the budget exists to stop ECHO and
         * SPRING running away, not to truncate a note.
         */
        const val MAX_CHAIN_TAIL_SECONDS = 1.0f
```

In `Treatments.kt`, append to `EXTRA`:

```kotlin
        // The transport: the same hit, played slower.
        "pitched" to FxChain(speed = mapOf("SEMITONES" to 0.25f)),
```

- [ ] **Step 4: Run the tests and make sure they pass**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/FxChain.kt synth/src/main/kotlin/com/snipsnap/synth/Treatments.kt synth/src/test/kotlin/com/snipsnap/synth/FxTest.kt
git commit -m "PITCH runs at the transport, before SWELL

Behaviour change for chains that set speed: the chain tail budget is now
measured from the pitched sound, so an octave-down hit keeps its full
length instead of being cut to the original plus one second."
```

---

## Task 16: ROLL

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/Roll.kt`
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Keyed.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/RollTest.kt` (create)

**Interfaces:**
- Produces: `object Roll` with `refusal(snip: Snip, bpm: Float, division: String): String?` and `roll(snip: Snip, bpm: Float, division: String = Wobble.DEFAULT_DIVISION, amount: Float = 1f): Snip`; `Keyed.NAMES` gains `"rolled"`.

ROLL reads the kit's tempo, so it belongs in `Keyed`, not the rack. It reuses
`Wobble.DIVISIONS` and `Wobble.periodSec` rather than defining its own grid, and
`Dials.division` rather than adding a field.

- [ ] **Step 1: Write the failing test**

Create `synth/src/test/kotlin/com/snipsnap/synth/RollTest.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RollTest {

    private val kick = Thump.render(ThumpVoice.KICK)

    @Test
    fun `AMOUNT zero is a copy`() {
        val out = Roll.roll(kick, 92f, "1/16", 0f)
        assertTrue(out.samples.contentEquals(kick.samples), "ROLL at 0 is not a copy")
    }

    @Test
    fun `a roll keeps the hit's length and repeats its head`() {
        val out = Roll.roll(kick, 92f, "1/16", 1f)
        assertEquals(kick.frameCount, out.frameCount, "ROLL changed the hit's length")
        val period = (Wobble.periodSec(92f, "1/16") * kick.sampleRate).toInt()
        // The second repeat starts with the same audio the first one did.
        assertTrue(
            abs(out.samples[period] - out.samples[0] * Roll.DECAY) < 0.05f,
            "the second repeat is not the head again, decayed",
        )
    }

    @Test
    fun `a hit shorter than one division is refused in words`() {
        val tiny = Snip(FloatArray(64), 1, 44_100)
        val why = Roll.refusal(tiny, 92f, "1/4")
        assertTrue(why != null && why.contains("1/4"), "the refusal does not name the division: $why")
        assertNull(Roll.refusal(kick, 92f, "1/16"), "a long enough hit was refused")
    }

    @Test
    fun `a roll is the same roll every time`() {
        val a = Roll.roll(kick, 92f, "1/16", 1f)
        val b = Roll.roll(kick, 92f, "1/16", 1f)
        assertTrue(a.samples.contentEquals(b.samples), "ROLL is not deterministic")
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.RollTest"`
Expected: FAIL — `Unresolved reference: Roll`.

- [ ] **Step 3: Write `Roll.kt`**

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs

/**
 * ROLL — the hit's own head, struck again on the grid. The sampler's
 * signature edit: hold the pad, and one hit becomes a run of them at a
 * note value, each a little quieter than the last.
 *
 * A keyed treatment rather than a rack section, because it reads the kit's
 * tempo and the rack reads nothing but the sound. It shares WOBBLE's grid
 * ([Wobble.DIVISIONS], [Wobble.periodSec]) so the two land on the same
 * beats, and the kit's own dial ([Keyed.Dials.division]) chooses it.
 *
 * AMOUNT is how much of the hit the roll replaces: 0 leaves it alone, 1
 * rolls the whole thing. Length is unchanged — a roll fills the hit, it
 * does not extend it. Peak matched, deterministic, no seed.
 */
object Roll {

    /** Each repeat against the one before it. */
    const val DECAY = 0.82f

    /** A hit must hold at least this many divisions to be worth rolling. */
    const val MIN_DIVISIONS = 2

    /**
     * Why [snip] cannot be rolled at [division] and [bpm], in words, or null
     * when it can — asked before anything is touched.
     */
    fun refusal(snip: Snip, bpm: Float, division: String): String? {
        val period = Wobble.periodSec(bpm, division)
        val need = period * MIN_DIVISIONS
        if (snip.durationSeconds < need) {
            return "the hit is shorter than $MIN_DIVISIONS of $division at ${Math.round(bpm)} BPM " +
                "- it has %.2f s and needs %.2f s".format(java.util.Locale.ROOT, snip.durationSeconds, need)
        }
        return null
    }

    /** [snip] rolled at [division] of [bpm], [amount] of the way. */
    fun roll(snip: Snip, bpm: Float, division: String = Wobble.DEFAULT_DIVISION, amount: Float = 1f): Snip {
        require(amount in 0f..1f) { "amount is 0..1, got $amount" }
        if (amount <= 0f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)
        refusal(snip, bpm, division)?.let { throw IllegalArgumentException(it) }

        val period = (Wobble.periodSec(bpm, division) * snip.sampleRate).toInt().coerceAtLeast(1)
        val out = FloatArray(snip.samples.size)
        var gain = 1f
        var start = 0
        while (start < snip.frameCount) {
            val n = minOf(period, snip.frameCount - start)
            for (f in 0 until n) {
                for (ch in 0 until snip.channels) {
                    val src = snip.samples[f * snip.channels + ch]
                    val dry = snip.samples[(start + f) * snip.channels + ch]
                    // Each repeat is the head again, faded toward the dry by AMOUNT.
                    out[(start + f) * snip.channels + ch] = dry * (1f - amount) + src * gain * amount
                }
            }
            gain *= DECAY
            start += period
        }

        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val k = inPeak / outPeak
            for (i in out.indices) out[i] *= k
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }
}
```

- [ ] **Step 4: Register it in `Keyed`**

In `Keyed.kt`: add `import com.snipsnap.synth.Roll`; extend `NAMES` to
`listOf("retuned", "bodied", "wobbled", "eternal", "rolled")`; add to the KDoc's
bullet list; add a `refusal` branch and an `apply` branch:

```kotlin
            "rolled" -> Roll.refusal(snip, context.bpm, dials.division)
```

```kotlin
            "rolled" -> Result(
                Roll.roll(snip, context.bpm, dials.division, amount),
                "%s AT %d BPM".format(Locale.ROOT, dials.division, Math.round(context.bpm)),
            )
```

- [ ] **Step 5: Run the tests and make sure they pass**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Roll.kt shell/src/main/kotlin/com/snipsnap/shell/Keyed.kt synth/src/test/kotlin/com/snipsnap/synth/RollTest.kt
git commit -m "ROLL: the hit struck again on the kit's grid"
```

---

## Task 17: GATE

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/Gate.kt`
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/Keyed.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/GateTest.kt` (create)

**Interfaces:**
- Produces: `object Gate` with `refusal(snip: Snip, bpm: Float, division: String): String?` and `chop(snip: Snip, bpm: Float, division: String = Wobble.DEFAULT_DIVISION, amount: Float = 1f): Snip`; `Keyed.NAMES` gains `"gated"`.

- [ ] **Step 1: Write the failing test**

Create `synth/src/test/kotlin/com/snipsnap/synth/GateTest.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GateTest {

    private val steady = Snip(FloatArray(44_100) { 0.5f }, 1, 44_100)

    @Test
    fun `AMOUNT zero is a copy`() {
        val out = Gate.chop(steady, 92f, "1/16", 0f)
        assertTrue(out.samples.contentEquals(steady.samples), "GATE at 0 is not a copy")
    }

    @Test
    fun `a gate cuts holes without changing the length`() {
        val out = Gate.chop(steady, 92f, "1/8", 1f)
        assertEquals(steady.frameCount, out.frameCount, "GATE changed the length")
        assertTrue(out.samples.any { it < 0.05f }, "GATE never closed")
        assertTrue(out.samples.any { it > 0.4f }, "GATE never opened")
    }

    @Test
    fun `the edges of the gate do not click`() {
        val out = Gate.chop(steady, 92f, "1/8", 1f)
        for (i in 1 until out.frameCount) {
            assertTrue(
                kotlin.math.abs(out.samples[i] - out.samples[i - 1]) < 0.1f,
                "a step of ${out.samples[i] - out.samples[i - 1]} at $i is a click",
            )
        }
    }

    @Test
    fun `a hit shorter than one division is refused in words`() {
        val tiny = Snip(FloatArray(64), 1, 44_100)
        val why = Gate.refusal(tiny, 92f, "1/4")
        assertTrue(why != null && why.contains("1/4"), "the refusal does not name the division: $why")
        assertNull(Gate.refusal(steady, 92f, "1/16"), "a long enough hit was refused")
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.GateTest"`
Expected: FAIL — `Unresolved reference: Gate`.

- [ ] **Step 3: Write `Gate.kt`**

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs

/**
 * GATE — the hit chopped on the grid. A square envelope at a note value,
 * open for the first half of each division and closed for the second, with
 * a short fade on both edges so the chop is rhythm rather than a row of
 * clicks.
 *
 * Keyed rather than racked, for the same reason as ROLL: it reads the
 * kit's tempo. Shares WOBBLE's grid and the kit's own division dial.
 *
 * AMOUNT is depth, not speed — 0 leaves the gate open, 1 closes it all the
 * way. Length unchanged, peak matched, deterministic.
 */
object Gate {

    /** The fade on each edge: long enough to not click, short enough to still chop. */
    const val EDGE_SEC = 0.003f

    /** How much of each division the gate is open for. */
    const val DUTY = 0.5f

    /** A hit must hold at least this many divisions to be worth gating. */
    const val MIN_DIVISIONS = 2

    /** Why [snip] cannot be gated at [division] and [bpm], in words, or null when it can. */
    fun refusal(snip: Snip, bpm: Float, division: String): String? {
        val period = Wobble.periodSec(bpm, division)
        val need = period * MIN_DIVISIONS
        if (snip.durationSeconds < need) {
            return "the hit is shorter than $MIN_DIVISIONS of $division at ${Math.round(bpm)} BPM " +
                "- it has %.2f s and needs %.2f s".format(java.util.Locale.ROOT, snip.durationSeconds, need)
        }
        return null
    }

    /** [snip] chopped at [division] of [bpm], [amount] deep. */
    fun chop(snip: Snip, bpm: Float, division: String = Wobble.DEFAULT_DIVISION, amount: Float = 1f): Snip {
        require(amount in 0f..1f) { "amount is 0..1, got $amount" }
        if (amount <= 0f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)
        refusal(snip, bpm, division)?.let { throw IllegalArgumentException(it) }

        val period = (Wobble.periodSec(bpm, division) * snip.sampleRate).toInt().coerceAtLeast(2)
        val open = (period * DUTY).toInt().coerceAtLeast(1)
        val edge = (EDGE_SEC * snip.sampleRate).toInt().coerceIn(1, open / 2)

        val out = FloatArray(snip.samples.size)
        for (f in 0 until snip.frameCount) {
            val phase = f % period
            // A trapezoid, not a square: the ramps are what keep it from clicking.
            val gate = when {
                phase < edge -> phase.toFloat() / edge
                phase < open - edge -> 1f
                phase < open -> (open - phase).toFloat() / edge
                else -> 0f
            }
            val g = 1f - amount * (1f - gate)
            for (ch in 0 until snip.channels) {
                out[f * snip.channels + ch] = snip.samples[f * snip.channels + ch] * g
            }
        }

        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val k = inPeak / outPeak
            for (i in out.indices) out[i] *= k
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }
}
```

- [ ] **Step 4: Register it in `Keyed`**

Add `import com.snipsnap.synth.Gate`; extend `NAMES` with `"gated"`; add to the
KDoc bullets; add the branches:

```kotlin
            "gated" -> Gate.refusal(snip, context.bpm, dials.division)
```

```kotlin
            "gated" -> Result(
                Gate.chop(snip, context.bpm, dials.division, amount),
                "%s AT %d BPM".format(Locale.ROOT, dials.division, Math.round(context.bpm)),
            )
```

- [ ] **Step 5: Run the tests and make sure they pass**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Gate.kt shell/src/main/kotlin/com/snipsnap/shell/Keyed.kt synth/src/test/kotlin/com/snipsnap/synth/GateTest.kt
git commit -m "GATE: the hit chopped on the kit's grid"
```

---

## Task 18: `roll` and `gate` CLI verbs

**Files:**
- Create: `cli/src/main/kotlin/com/snipsnap/cli/RollCommand.kt`
- Create: `cli/src/main/kotlin/com/snipsnap/cli/GateCommand.kt`
- Modify: `cli/src/main/kotlin/com/snipsnap/cli/Main.kt:113-123, 349-353`
- Test: `cli/src/test/kotlin/com/snipsnap/cli/CliTest.kt`

Every keyed treatment has its own CLI verb (`BodyCommand`, `WobbleCommand`,
`EternalCommand`). `TreatCommand`'s genericity covers *characters* only.

**Interfaces:**
- Consumes: `Keyed.Dials(division = ...)`, `KitBuilderModel.keyedPad`, `Wobble.DIVISIONS`.
- Produces: `RollCommand.run(args: List<String>, out: PrintStream): Int`, `GateCommand.run(...)` with the same signature.

- [ ] **Step 1: Write the failing test**

In `cli/src/test/kotlin/com/snipsnap/cli/CliTest.kt`. These two need no kit
fixture — both failures happen before any file is touched, so they hold whatever
fixture style the file already uses:

```kotlin
    @Test
    fun `roll and gate each want a kit and a pad`() {
        val sink = PrintStream(ByteArrayOutputStream())
        val rollErr = assertFailsWith<CliError> { RollCommand.run(emptyList(), sink) }
        assertTrue(rollErr.message!!.contains("roll"), "the error does not name the verb: ${rollErr.message}")
        val gateErr = assertFailsWith<CliError> { GateCommand.run(emptyList(), sink) }
        assertTrue(gateErr.message!!.contains("gate"), "the error does not name the verb: ${gateErr.message}")
    }

    @Test
    fun `roll and gate refuse a folder that is not a kit`() {
        val sink = PrintStream(ByteArrayOutputStream())
        val notAKit = createTempDirectory("not-a-kit").toFile()
        for (run in listOf(RollCommand::run, GateCommand::run)) {
            val err = assertFailsWith<CliError> { run(listOf(notAKit.path, "A01"), sink) }
            assertTrue(err.message!!.contains("kit.json"), "the error does not say why: ${err.message}")
        }
    }
```

Add `import kotlin.io.path.createTempDirectory` if the file does not have it. If
`CliTest` already builds a real kit in a fixture, add a third test asserting
`RollCommand.run(listOf(kitDir.path, "A01", "--rate", "1/3"), sink)` throws and
that the message lists `Wobble.DIVISIONS` — but do not invent a fixture for it.

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :cli:test --tests "com.snipsnap.cli.CliTest"`
Expected: FAIL — `Unresolved reference: RollCommand`.

- [ ] **Step 3: Write both commands**

`RollCommand.kt` is `WobbleCommand.kt` with three substitutions — the treatment
name, the verb in every message, and the closing line. Copy `WobbleCommand.kt`
whole, then change:

- `object WobbleCommand` → `object RollCommand`
- the KDoc to: `` `snipsnap roll <kit-dir> <pad>` — the hit's own head struck again on the grid, at a note division (`--rate 1/16`) and the kit's tempo (`--bpm` sets it first). `--amount` is how much of the hit the roll replaces, `--undo` pops the earlier audio back out of the bin. ``
- both `"wobble ...` error strings → `"roll wants a kit and a pad: snipsnap roll <kit-dir> A02 [--rate 1/16] [--bpm 92] [--amount 0..1]"` and `"roll takes a kit and a pad, nothing more"`
- `model.keyedPad(slot, "wobbled", ...)` → `model.keyedPad(slot, "rolled", ...)`
- the success line → `"pad $padArg rolled at %s (%.0f ms a repeat)%s - original in the bin, recipe recorded (undo: --undo)"`
- `?: Wobble.DEFAULT_DIVISION` stays; ROLL shares WOBBLE's grid deliberately.

`GateCommand.kt` is the same with `"gated"`, `gate`, and
`"pad $padArg gated at %s (%.0f ms a division)%s - ..."`.

Both must keep `WobbleCommand`'s `parsePad` helper verbatim — it is private, so
copy it into each file rather than widening it.

- [ ] **Step 4: Register both in `Main.kt`**

In the `when (args[0])` block, after the `"eternal"` line:

```kotlin
                "roll" -> RollCommand.run(args.drop(1), out)
                "gate" -> GateCommand.run(args.drop(1), out)
```

In `Cli.USAGE`, after the `eternal` block:

```
        |  roll <kit-dir> <pad>   the hit's own head struck again on the grid
        |                        (--rate 1/16, --bpm, --amount, --undo)
        |  gate <kit-dir> <pad>   the hit chopped on the grid
        |                        (--rate 1/8, --bpm, --amount, --undo)
```

- [ ] **Step 5: Run the tests and make sure they pass**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add cli/src/main/kotlin/com/snipsnap/cli/RollCommand.kt cli/src/main/kotlin/com/snipsnap/cli/GateCommand.kt cli/src/main/kotlin/com/snipsnap/cli/Main.kt cli/src/test/kotlin/com/snipsnap/cli/CliTest.kt
git commit -m "Add the roll and gate CLI verbs"
```

---

## Task 19: The card's inventory test, before the regroup

**Files:**
- Modify: `shell/src/test/kotlin/com/snipsnap/shell/PadSheetTest.kt:109-136`

The regroup rewrites the very assertions that would police it, so the test has to
check **properties** rather than contents. The existing
`ROWS.drop(1).dropLast(1)` test also assumes keyed chips live only on the last
row — which the regroup breaks, since ROLL and GATE sit in the TIME row.

**Interfaces:**
- Consumes: `PadSheet.ALL_SEGMENTS`, `PadSheet.treatmentFor`, `PadSheet.segmentFor`, `Treatments.names`, `Keyed.NAMES`.

- [ ] **Step 1: Replace the position-dependent test**

Replace `every character segment names a real rack character, and the keyed
segments a real keyed treatment` with a version that does not care which row a
chip is on:

```kotlin
    @Test
    fun `every segment names something real, whatever row it sits on`() {
        for (segment in PadSheet.ALL_SEGMENTS) {
            if (segment == PadSheet.NONE) continue
            val t = PadSheet.treatmentFor(segment)
                ?: throw AssertionError("$segment draws a chip but does nothing")
            when (t) {
                is PadSheet.Treatment.Era -> {}
                is PadSheet.Treatment.Character ->
                    assertTrue(t.name in Treatments.names, "$segment maps to '${t.name}', which Treatments does not know")
                is PadSheet.Treatment.Keyed ->
                    assertTrue(t.name in Keyed.NAMES, "$segment maps to '${t.name}', which Keyed does not know")
            }
            assertEquals(segment, PadSheet.segmentFor(t), "segmentFor is not treatmentFor's inverse for $segment")
        }
        assertNull(PadSheet.segmentForKeyed("frozen"), "a keyed name no segment draws lights nothing")
    }

    @Test
    fun `the card draws every chip once and only once`() {
        assertEquals(
            PadSheet.ALL_SEGMENTS.size,
            PadSheet.ALL_SEGMENTS.toSet().size,
            "a word is drawn on two rows: ${PadSheet.ALL_SEGMENTS.groupBy { it }.filterValues { it.size > 1 }.keys}",
        )
        assertEquals(PadSheet.ROWS, PadSheet.ROWS.filter { it.isNotEmpty() }, "an empty row would draw nothing")
        assertTrue(PadSheet.ROWS.maxOf { it.size } <= 5, "a row wider than 5 makes every chip narrower")
    }
```

Leave the row-content assertions in `rows two and three draw four characters each
and all rows read in order` alone for now — Task 20 updates them together with
the rows themselves.

- [ ] **Step 2: Run the tests and make sure they pass**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.PadSheetTest"`
Expected: PASS against the current five rows.

- [ ] **Step 3: Commit**

```bash
git add shell/src/test/kotlin/com/snipsnap/shell/PadSheetTest.kt
git commit -m "Check the card by property, not by row position"
```

---

## Task 20: The regroup

**Files:**
- Modify: `shell/src/main/kotlin/com/snipsnap/shell/PadSheet.kt:40-75, 139-176`
- Test: `shell/src/test/kotlin/com/snipsnap/shell/PadSheetTest.kt`

27 chips: the 20 that exist plus SPIKE, RING, DUST, PHASE, PITCH, ROLL, GATE.
Row one is pinned — `eraFor` requires `segment in SEGMENTS`. Rows below group by
what an effect *does*, which `TUNE` already establishes as permitted.

**Interfaces:**
- Consumes: treatment names from Tasks 12 and 15; `Keyed.NAMES` from Tasks 16–17.

- [ ] **Step 1: Write the failing test**

Replace the row-content assertions with the new layout:

```kotlin
    @Test
    fun `the card reads in zones, and holds every chip`() {
        assertEquals(listOf("NONE", "CRUSH", "TAPE", "DIRT", "SMEAR"), PadSheet.SEGMENTS)
        assertEquals(listOf("SWELL", "TAIL", "SKIM", "GHOST", "SPIKE"), PadSheet.ANATOMY_SEGMENTS)
        assertEquals(listOf("PUNCH", "RING", "DUB", "DUST", "PHASE"), PadSheet.CHARACTER_SEGMENTS)
        assertEquals(listOf("SLAP", "WASH", "ROLL", "GATE"), PadSheet.TIME_SEGMENTS)
        assertEquals(listOf("FLIP", "STOP", "START", "PITCH"), PadSheet.TRANSPORT_SEGMENTS)
        assertEquals(listOf("TUNE", "BODY", "WOBBLE", "ETERNAL"), PadSheet.KEYED_SEGMENTS)
        assertEquals(27, PadSheet.ALL_SEGMENTS.size, "the card should draw 27 chips")
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :shell:test --tests "com.snipsnap.shell.PadSheetTest"`
Expected: FAIL — `Unresolved reference: ANATOMY_SEGMENTS`.

- [ ] **Step 3: Rewrite the rows**

In `PadSheet.kt`, replace the `CHARACTER_SEGMENTS` / `MORE_SEGMENTS` /
`EXTRA_SEGMENTS` / `KEYED_SEGMENTS` / `ROWS` declarations with:

```kotlin
    /** Row two — what the hit *is*: its arrival, its attack, its body. */
    val ANATOMY_SEGMENTS: List<String> = listOf("SWELL", TAIL, "SKIM", "GHOST", "SPIKE")

    /** Row three — what the hit sounds like once it is itself: the damage. */
    val CHARACTER_SEGMENTS: List<String> = listOf("PUNCH", "RING", "DUB", "DUST", "PHASE")

    /** Row four — what happens to it in time: repeats, rooms, the grid. */
    val TIME_SEGMENTS: List<String> = listOf("SLAP", "WASH", "ROLL", "GATE")

    /** Row five — the machine's own transport. */
    val TRANSPORT_SEGMENTS: List<String> = listOf("FLIP", "STOP", "START", "PITCH")

    /** Row six — the treatments that read the kit itself: its key, its tempo. */
    val KEYED_SEGMENTS: List<String> = listOf(TUNE, "BODY", "WOBBLE", "ETERNAL")

    /**
     * All rows, in drawing order. Grouped by what an effect *does*, not by
     * which family implements it: ROLL and GATE are keyed treatments sitting
     * on the TIME row because that is what they sound like, the way TUNE has
     * always sat among the characters.
     */
    val ROWS: List<List<String>> = listOf(
        SEGMENTS, ANATOMY_SEGMENTS, CHARACTER_SEGMENTS, TIME_SEGMENTS, TRANSPORT_SEGMENTS, KEYED_SEGMENTS,
    )
```

Move `const val TUNE = "TUNE"` above `KEYED_SEGMENTS` so it is declared before use.

Update the doc comments the move invalidates, in the same edit:
- `TAIL`'s KDoc says "Row two, first chip" — it is now row two, *second* chip.
- `eraFor`'s KDoc says "Row-two segments are not eras: ask [treatmentFor]" —
  rewrite as "Segments on any row but the first are not eras."
- the object KDoc's description of the rows (around line 15) names "row four ends
  on TUNE and row five is the keyed family" — restate it as the six zones.

Extend `CHARACTER_FOR` with the five new characters:

```kotlin
        // The attack leaned on rather than taken away.
        "SPIKE" to "spiked",
        // Multiplied by a sine: metal, bells, radio.
        "RING" to "ringed",
        // The record under the hit.
        "DUST" to "dusted",
        // Four allpasses, swept.
        "PHASE" to "phased",
        // The transport: pitch is speed. AMT fades it back toward native, not toward silence.
        "PITCH" to "pitched",
```

Extend `KEYED_FOR`:

```kotlin
        // The hit's own head, struck again on the grid.
        "ROLL" to "rolled",
        // The hit chopped on the grid.
        "GATE" to "gated",
```

- [ ] **Step 4: Run the tests and make sure they pass**

Run: `./gradlew test`
Expected: PASS. Grep for any remaining reference to `MORE_SEGMENTS` or
`EXTRA_SEGMENTS` across `shell/`, `cli/` and `app/` and update each — they are
gone.

- [ ] **Step 5: Commit**

```bash
git add shell/src/main/kotlin/com/snipsnap/shell/PadSheet.kt shell/src/test/kotlin/com/snipsnap/shell/PadSheetTest.kt
git commit -m "Regroup the treatment card into zones, 27 chips

Behaviour change: every chip except row one moves. The card now groups by
what an effect does rather than by which family implements it, so the
rack's logic is legible instead of its implementation history."
```

---

## Task 21: Documentation

**Files:**
- Modify: `README.md:219-239`
- Modify: `docs/CLI.md:335-351`
- Modify: `docs/FEATURE_PLAN.md`

- [ ] **Step 1: Update the rack list and order string**

In `README.md`, rewrite the section list and the processing-order string to:

```
pitch → swell → reverse → smear → ghost → spike → eq → squash → crunch
      → ring → dub → dust → tape → phase → echo → spring → motion
```

Add a line for each of SPIKE, RING, DUST, PHASE and PITCH describing it in one
sentence, matching the voice of the entries already there.

- [ ] **Step 2: Update the CLI docs**

In `docs/CLI.md`, add `spiked, ringed, dusted, phased, pitched` to `treat`'s
character list, document the new `roll` and `gate` verbs beside `wobble`, and
replace the pad-sheet row layout with the six zone rows from Task 20.

- [ ] **Step 3: Add the wave entry**

In `docs/FEATURE_PLAN.md`, add a new Wave entry in the established VV format
(owner, size, exit test) covering all six landings, marked done.

- [ ] **Step 4: Commit**

```bash
git add README.md docs/CLI.md docs/FEATURE_PLAN.md
git commit -m "Document the five new rack sections and the keyed pair"
```

---

## Verification

After Task 21, confirm the whole thing end to end:

- [ ] `./gradlew test` — green
- [ ] `./gradlew :app:compileDebugKotlin` — green (skip if no Android SDK is configured; `settings.gradle.kts` includes `:app` conditionally)
- [ ] `FxChain.SECTION_NAMES` reads exactly: `speed, swell, smear, ghost, spike, eq, squash, crunch, ring, dub, dust, tape, phase, echo, spring, motion`
- [ ] `FxChain.VERSION` is still `1`
- [ ] `Shuffle.TREATMENTS` still holds exactly five entries
- [ ] `PadSheet.ALL_SEGMENTS.size` is 27, with no duplicates
- [ ] A `kit.json` written before this work still loads and renders
