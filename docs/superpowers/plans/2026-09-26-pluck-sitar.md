# PLUCK Phase 3a — SITAR Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add SITAR as PLUCK's fifth string: a jawari limiter inside the Karplus-Strong loop driven by velocity, four sympathetic loops under DOUBLE, a per-voice stiffness allpass folded into the loop's tuning budget, twelve presets, the tests the spec names, and the audition set for Josh's gate.

**Architecture:** Every change is inside `Pluck.kt`'s existing render path — `synthesize` → `ks` → `trimToDecay(withBody(...))` — plus one call in `Velocity.atVelocity`. `ks` gains two optional parameters (`stiffness`, `jawari`) whose defaults leave the Phase 2 arithmetic untouched; a new private `sympathetic` loop rings beside the main string on SITAR when DOUBLE is up; `render` gains a `velocity` parameter that reaches the jawari drive. SITAR has no body table: the research found nothing reachable, so `bodyFor(SITAR)` is empty and `withBody` already returns the string unchanged for an empty table.

**Tech Stack:** Kotlin/JVM, Gradle (`./gradlew`, JDK 17), kotlin.test; renders at 4× oversampling (176 400 Hz) and decimate to 44 100 Hz; the audition page is a static HTML file published as an artifact.

**Spec:** `docs/superpowers/specs/2026-09-26-pluck-sitar-design.md` (read it first; its "The jawari", "Sympathetic strings under DOUBLE", "Dispersion", "The gourd body" and "Testing" sections are the requirements this plan argues from). Research note: `docs/superpowers/plans/2026-09-26-pluck-sitar-body-research.md` (zero body modes; nothing from it reaches code).

## Global Constraints

- Branch `claude/pluck-depth-phase-3`, worktree `/Users/joshuacramblet/SnipSnap/.claude/worktrees/pluck-depth-phase-1`. Run every command from that directory; never `cd` to the parent checkout.
- Gradle in the foreground only, always `--max-workers=2 --console=plain`, never in the background. Before the first Gradle command of a task run `pgrep -fl "Gradle Test Executor" | wc -l` and wait until it prints 0. A single test class run is one to two minutes.
- Every commit message ends with exactly these two lines:
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`
  `Claude-Session: https://claude.ai/code/session_01DsZ2eJRrwVXd4cwMqvAGam`
  Commit subjects are plain declarative prose, no prefixes, no ticket numbers, no model names anywhere in commit or code text. Where a task's commit command shows `-m "<body>"`, the implementer writes the body: what changed and why, in the repo's long-form style, including every number the task's tests printed (cents, shares, dB, the measured stiffness candidates).
- The six PLUCK macros TUNE/DAMP/PICK/STRIKE/BODY/DOUBLE keep their names and their meanings on every voice, except that DOUBLE on SITAR means the sympathetic strings (spec, "Sympathetic strings under DOUBLE"). No seventh macro. Velocity is a render parameter, never a macro key: `Patches.validateMacros` rejects unknown keys.
- No Hz reaches a body table without an opened source. `bodyFor(SITAR)` stays `emptyList()` in this plan.
- With `stiffness = 0f` and `jawari = 0f`, `ks` must produce the Phase 2 output byte for byte on every voice (a test asserts it).
- The listening page stays pure ASCII: `LC_ALL=C grep -c -P '[^\x00-\x7F]' synth/src/test/resources/audition/pluck-audition.html` must print 0. Glyphs go through `String.fromCharCode` or HTML entities as the page already does.
- The render's decay-following length, the 4 s ceiling, `Dsp.levelTo`, `Dsp.fadeTail` and `Dsp.decimate` are untouched.
- Never loosen a test to get green. A test that pins a value this plan changes may be updated to the new value with a one-line comment naming this plan; anything else that fails is reported, not edited.

---

## File map

| File | Responsibility in this plan |
|---|---|
| `synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt` | the enum member, constants, defaults, empty body table, the stiffness and jawari stages in `ks`, the sympathetic loop, the `velocity` parameter, internal overrides for the audition |
| `synth/src/main/kotlin/com/snipsnap/synth/PluckPresets.kt` | twelve SITAR presets |
| `synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt` | `atVelocity` calls `Pluck.render` with the velocity for a `PluckPatch` |
| `synth/src/test/kotlin/com/snipsnap/synth/PluckSpectra.kt` | two new measurement helpers: `peakHz` (a partial's frequency near a guess) and `highShare` (the high band's share of energy over a window) |
| `synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt` | byte-for-byte default test, jawari tests, sympathetic tests, SITAR classification |
| `synth/src/test/kotlin/com/snipsnap/synth/TuningAccuracyTest.kt` | full-drive and DOUBLE 1 sweeps |
| `synth/src/test/kotlin/com/snipsnap/synth/StiffnessTest.kt` (new) | the probe that finds the two stiffness candidates, and the dispersion assertion |
| `synth/src/test/kotlin/com/snipsnap/synth/PluckAuditionGenerator.kt`, `synth/src/test/resources/audition/pluck-audition.html` | the p3a audition set and page |
| `docs/SYNTH_ROADMAP.md`, the spec | the phasing row; status |

---

### Task 1: SITAR exists — enum, constants, defaults, empty body, presets, roadmap row

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt` (the enum on line 29; `macrosFor`; `rootFor`; the voice-constants `when` inside `synthesize`; `bodyFor`)
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/PluckPresets.kt` (`forVoice`, a `sitarPresets` list)
- Modify: `docs/SYNTH_ROADMAP.md` (a PLUCK phasing row), `docs/superpowers/specs/2026-09-26-pluck-sitar-design.md` (status line)
- Test: the existing `PluckTest`, `TuningAccuracyTest`, `PluckPresetsTest`, `SynthKitTest`, `ExportRegressionTest` — every one already sweeps `PluckVoice.entries`, so SITAR is covered the moment it exists

**Interfaces:**
- Produces: `PluckVoice.SITAR`; `Pluck.macrosFor(SITAR)` = TUNE 0.5, DAMP 0.35, PICK 0.65, STRIKE 0.30, BODY 0.0, DOUBLE 0.40; root 139 Hz; loop 7000 / pick 2500–12000 / ring 1.4; `Pluck.bodyFor(SITAR)` = `emptyList()`; `PluckPresets.forVoice(SITAR)` = twelve patches.

- [ ] **Step 1: Add the enum member and the four `when` branches**

In `Pluck.kt`:

```kotlin
enum class PluckVoice { NYLON, HARP, KOTO, BANJO, SITAR }
```

In `macrosFor`, after the BANJO entry:

```kotlin
        // A sitar: plucked near the bridge with a wire mizrab, the sympathetic
        // strings present by default. BODY is 0 and inert on this voice: the
        // research found no reachable body measurement (research note
        // 2026-09-26), so bodyFor(SITAR) is empty until a source is read.
        PluckVoice.SITAR -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("DAMP", 0.35f), MacroSpec("PICK", 0.65f),
            MacroSpec("STRIKE", 0.3f), MacroSpec("BODY", 0f), MacroSpec("DOUBLE", 0.4f),
        )
```

In `rootFor`:

```kotlin
        PluckVoice.SITAR -> 139f   // C#3, the common tonic of the playing string
```

In the voice-constants `when` inside `synthesize`, after BANJO:

```kotlin
            // Steel strings under a wire plectrum: brighter than KOTO, darker
            // than BANJO, and the longest ring of the five (spec, "Voice constants").
            PluckVoice.SITAR -> { loopHz = 7000f; pickLo = 2500f; pickHi = 12000f; ring = 1.4f }
```

In `bodyFor`, after the BANJO table:

```kotlin
        // No body: the sitar research (docs/superpowers/plans/
        // 2026-09-26-pluck-sitar-body-research.md) opened six sources and
        // none measures the gourd or the soundboard; the modal analysis that
        // would is paywalled. Under the rule that no Hz reaches this table
        // without an opened source, SITAR has none, withBody returns the
        // string unchanged, and the BODY macro is inert on this voice.
        PluckVoice.SITAR -> emptyList()
```

Also update the `macrosFor` KDoc's list of defaults to add `SITAR BODY 0.0 (inert, no table)`.

- [ ] **Step 2: Add the presets**

In `PluckPresets.kt`, `forVoice` gains `PluckVoice.SITAR -> sitarPresets`, and after `banjoPresets`:

```kotlin
    // Authored for the audition, not by ear (the parent spec's by-ear pass
    // re-authors them). DOUBLE is the sympathetic strings on this voice.
    private val sitarPresets = listOf(
        p(PluckVoice.SITAR, "ALAAP", "TUNE" to 0.5f, "DAMP" to 0.3f, "PICK" to 0.6f, "STRIKE" to 0.3f, "DOUBLE" to 0.4f),
        p(PluckVoice.SITAR, "JHALA", "TUNE" to 0.6f, "DAMP" to 0.5f, "PICK" to 0.85f, "STRIKE" to 0.2f, "DOUBLE" to 0.3f),
        p(PluckVoice.SITAR, "GAT", "TUNE" to 0.45f, "DAMP" to 0.4f, "PICK" to 0.7f, "STRIKE" to 0.3f, "DOUBLE" to 0.45f),
        p(PluckVoice.SITAR, "DRONE", "TUNE" to 0.5f, "DAMP" to 0.15f, "PICK" to 0.5f, "STRIKE" to 0.35f, "DOUBLE" to 1.0f),
        p(PluckVoice.SITAR, "DRY STRING", "TUNE" to 0.5f, "DAMP" to 0.45f, "PICK" to 0.65f, "STRIKE" to 0.3f, "DOUBLE" to 0.0f),
        p(PluckVoice.SITAR, "MUTED", "TUNE" to 0.4f, "DAMP" to 0.85f, "PICK" to 0.4f, "STRIKE" to 0.4f, "DOUBLE" to 0.2f),
        p(PluckVoice.SITAR, "HIGH STRING", "TUNE" to 0.85f, "DAMP" to 0.35f, "PICK" to 0.75f, "STRIKE" to 0.25f, "DOUBLE" to 0.35f),
        p(PluckVoice.SITAR, "BRIDGE PICK", "TUNE" to 0.5f, "DAMP" to 0.35f, "PICK" to 0.7f, "STRIKE" to 0.0f, "DOUBLE" to 0.4f),
        p(PluckVoice.SITAR, "CENTRE PICK", "TUNE" to 0.5f, "DAMP" to 0.35f, "PICK" to 0.5f, "STRIKE" to 1.0f, "DOUBLE" to 0.4f),
        p(PluckVoice.SITAR, "RINGING", "TUNE" to 0.55f, "DAMP" to 0.0f, "PICK" to 0.6f, "STRIKE" to 0.3f, "DOUBLE" to 0.5f),
        p(PluckVoice.SITAR, "LOW TONIC", "TUNE" to 0.0f, "DAMP" to 0.3f, "PICK" to 0.55f, "STRIKE" to 0.3f, "DOUBLE" to 0.4f),
        p(PluckVoice.SITAR, "BRIGHT MIZRAB", "TUNE" to 0.5f, "DAMP" to 0.25f, "PICK" to 0.95f, "STRIKE" to 0.15f, "DOUBLE" to 0.3f),
    )
```

- [ ] **Step 3: Run the suites that sweep the voices**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.PluckTest" --tests "com.snipsnap.synth.TuningAccuracyTest" --tests "com.snipsnap.synth.PluckPresetsTest" --tests "com.snipsnap.synth.SynthKitTest" --tests "com.snipsnap.synth.ExportRegressionTest" --max-workers=2 --console=plain 2>&1 | grep -E "FAILED|BUILD" | head`
Expected: BUILD SUCCESSFUL. If `factory defaults classify as percussion, and the banjo may read as a snare` fails on SITAR reading SNARE, do NOT edit the test in this task: report the class and the `highRatio` from the failure message, and Task 3 (which changes the attack) records the final allowed set. If any tuning sweep fails on SITAR, report the cents; a plain Karplus-Strong string at 139–556 Hz must pass, so a failure here is a constants error, not a tuning-budget error.

- [ ] **Step 4: The roadmap row and the spec status**

In `docs/SYNTH_ROADMAP.md`, find the phasing table's most recent PLUCK row (Phase 2's) and add a row after it in exactly the same column format, with the phase `3a`, the date `2026-09-26`, and the summary `SITAR: jawari limiter in the loop, four sympathetic loops under DOUBLE, per-voice stiffness allpass; no body until a source opens`. In the spec's header, change `**Status:** design, approved in conversation 2026-09-26. Not implemented.` to `**Status:** implementing on claude/pluck-depth-phase-3 (plan 2026-09-26-pluck-sitar.md).`

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt synth/src/main/kotlin/com/snipsnap/synth/PluckPresets.kt docs/SYNTH_ROADMAP.md docs/superpowers/specs/2026-09-26-pluck-sitar-design.md
git commit -m "Give PLUCK a SITAR voice: a plain steel string for now, with no body until a source opens" -m "<body: the constants and why, the twelve presets, the empty body table and the research that led to it>" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DsZ2eJRrwVXd4cwMqvAGam"
```

---

### Task 2: Stiffness inside the loop, folded into the tuning budget, with the two candidates measured

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt` (`ks` signature and loop; `synthesize`'s two `ks` calls; new constants)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/PluckSpectra.kt` (new `peakHz`)
- Create: `synth/src/test/kotlin/com/snipsnap/synth/StiffnessTest.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt` (byte-for-byte default test)

**Interfaces:**
- Consumes: `Pluck.ks(freq, seconds, damp, bodyLoopHz, pickHz, seed, rate, position)` as it is today.
- Produces: `Pluck.ks(..., position: Float = 0f, stiffness: Float = 0f)`; `Pluck.stiffnessFor(voice): Float` (internal); constants `Pluck.SITAR_STIFFNESS_LOW`, `Pluck.SITAR_STIFFNESS_HIGH`, `Pluck.SITAR_STIFFNESS` (internal); `PluckSpectra.peakHz(x, rate, nearHz, spanFraction, steps, fromSec, seconds): Double`.

- [ ] **Step 1: Fingerprint the four old voices before touching the loop**

The spec's "byte for byte" guarantee is proved by comparison, not by a test that can only see the new code: the audition generator renders every voice deterministically, so its clips are the fingerprint.

Run: `rm -rf testkit/pluck-audition && ./gradlew :synth:generatePluckAudition --max-workers=2 --console=plain 2>&1 | grep -E "wrote|FAILED"; md5 -r testkit/pluck-audition/NYLON/*.wav testkit/pluck-audition/HARP/*.wav testkit/pluck-audition/KOTO/*.wav testkit/pluck-audition/BANJO/*.wav testkit/pluck-audition/KIT_SEAM/*.wav > /tmp/p3a-task2-before.md5; wc -l < /tmp/p3a-task2-before.md5`
Expected: the `wrote` line and a count of 32 or more fingerprints. Step 6 repeats the render and diffs against this file; any difference on a voice other than SITAR is a defect in the branch structure of `ks`, not something to explain away.

Also add to `PluckTest.kt` the permanent determinism guard the comparison relies on:

```kotlin
    @Test
    fun `every voice renders deterministically at the oversampled rate`() {
        // The audition fingerprints (plan 2026-09-26-pluck-sitar.md, Task 2)
        // only mean something if two renders of the same voice agree.
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        for (voice in PluckVoice.entries) {
            val a = Pluck.synthesize(voice, mapOf("DOUBLE" to 0f), rate)
            val b = Pluck.synthesize(voice, mapOf("DOUBLE" to 0f), rate)
            assertTrue(a.contentEquals(b), "$voice is not deterministic")
        }
    }
```

- [ ] **Step 2: Add `peakHz` to `PluckSpectra`**

```kotlin
    /**
     * The frequency of the strongest component near [nearHz]: a Goertzel
     * scan over ±[spanFraction] of it in [steps] steps, over [seconds] from
     * [fromSec] into the buffer (past the attack). Resolution is
     * 2·spanFraction/steps of the guess — 0.05 % at the defaults, under a
     * cent — which is enough to read a partial's sharpness in percent.
     */
    fun peakHz(x: FloatArray, rate: Int, nearHz: Float, spanFraction: Double = 0.06, steps: Int = 240, fromSec: Float = 0.05f, seconds: Float = 0.25f): Double {
        val from = (fromSec * rate).toInt().coerceIn(0, x.size)
        val n = min(x.size - from, (seconds * rate).toInt())
        require(n > 0) { "peakHz needs samples past $fromSec s" }
        val slice = x.copyOfRange(from, from + n)
        var bestHz = nearHz.toDouble()
        var best = -1.0
        for (s in 0..steps) {
            val hz = nearHz * (1.0 - spanFraction + 2.0 * spanFraction * s / steps)
            val e = goertzel(slice, n, hz.toFloat(), rate)
            if (e > best) { best = e; bestHz = hz }
        }
        return bestHz
    }
```

- [ ] **Step 3: The stiffness stage in `ks`**

Change `ks`'s signature to end `position: Float = 0f, stiffness: Float = 0f,` and its KDoc to say: `stiffness` is a first-order allpass coefficient in (−1, 0]; 0 is no allpass and the Phase 2 loop exactly; a negative value delays low partials more than high ones so the upper partials sit sharp of harmonic, the stiff-string law `n·√(1 + B·n²)` with B rising as the coefficient falls; its phase delay at the fundamental is subtracted from the loop length so the note stays in tune. Then, right after `val filterDelay = -filterPhase / w`:

```kotlin
        // The stiffness allpass H(z) = (c + z⁻¹) / (1 + c·z⁻¹): its phase at the
        // fundamental is part of the loop's delay, the same way the low-pass's
        // is, so it enters the budget here and the fundamental stays put.
        val stiffDelay = if (stiffness != 0f) {
            val c = stiffness.toDouble()
            val phase = atan2(-sin(w), c + cos(w)) - atan2(-c * sin(w), 1.0 + c * cos(w))
            -phase / w
        } else 0.0
        val exact = (rate / freq) - filterDelay - stiffDelay - 0.5
```

(replace the existing `val exact = ...` line). Then in the loop, replace

```kotlin
            val tuned = a * (d - apY1) + apX1
            apX1 = d
            apY1 = tuned
            out[i] += fb * loopLp.lp(tuned, loopHz)
```

with

```kotlin
            val tuned = a * (d - apY1) + apX1
            apX1 = d
            apY1 = tuned
            val stiff = if (stiffness != 0f) {
                val s = stiffness * (tuned - stY1) + stX1
                stX1 = tuned
                stY1 = s
                s
            } else tuned
            out[i] += fb * loopLp.lp(stiff, loopHz)
```

and declare `var stX1 = 0f` and `var stY1 = 0f` next to `apX1`/`apY1`. Add `require(stiffness > -1f && stiffness <= 0f) { "stiffness must be in (-1, 0], got $stiffness" }` at the top of `ks`.

- [ ] **Step 4: Per-voice stiffness and the two candidates**

In `Pluck.kt`, next to `BODY_MAX`:

```kotlin
    /**
     * Stiffness allpass coefficients (see [ks]). SITAR's two candidates put
     * the tenth partial about 1 % and about 3 % sharp of harmonic, found by
     * StiffnessTest's probe, not by hand; the low one ships until the gate
     * chooses. KOTO and HARP carry zero: both passed a Phase 2 gate and do
     * not change unheard (the audition offers them the low candidate).
     */
    internal const val SITAR_STIFFNESS_LOW = -0.10f    // placeholder until Step 6 measures it
    internal const val SITAR_STIFFNESS_HIGH = -0.30f   // placeholder until Step 6 measures it
    internal const val SITAR_STIFFNESS = SITAR_STIFFNESS_LOW

    internal fun stiffnessFor(voice: PluckVoice): Float = when (voice) {
        PluckVoice.SITAR -> SITAR_STIFFNESS
        PluckVoice.NYLON, PluckVoice.HARP, PluckVoice.KOTO, PluckVoice.BANJO -> 0f
    }
```

In `synthesize`, pass `stiffness = stiffnessFor(voice)` to both `ks` calls (the main string and the DOUBLE detune string).

- [ ] **Step 5: The probe and the dispersion test**

Create `StiffnessTest.kt`:

```kotlin
package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertTrue

class StiffnessTest {

    private val rate = Dsp.RATE * Dsp.OVERSAMPLE

    /** The tenth partial's frequency over ten times the fundamental, at C#4 with DOUBLE 0. */
    private fun tenthPartialRatio(stiffness: Float): Double {
        val f0 = Pluck.frequencyFor(PluckVoice.SITAR, 12)
        val raw = Pluck.synthesize(PluckVoice.SITAR, mapOf("TUNE" to 0.5f, "DOUBLE" to 0f, "DAMP" to 0.2f), rate, stiffnessOverride = stiffness)
        val tenth = PluckSpectra.peakHz(raw, rate, 10f * f0, spanFraction = 0.08)
        return tenth / (10.0 * f0)
    }

    @Test
    fun `the probe: sharper as the coefficient falls, and the two candidates bracket one and three percent`() {
        // Printed for the plan's Step 6, asserted so the test says something:
        // more negative coefficients must stretch the partials more.
        val coefficients = listOf(0f, -0.05f, -0.10f, -0.15f, -0.20f, -0.30f, -0.40f, -0.50f)
        var last = 0.0
        for (c in coefficients) {
            val ratio = tenthPartialRatio(c)
            println("stiffness $c: tenth partial ${"%.4f".format(ratio)} of harmonic (${"%.2f".format((ratio - 1.0) * 100)} % sharp)")
            assertTrue(ratio >= last - 0.002, "stiffness $c is not sharper than the one before it ($ratio < $last)")
            last = ratio
        }
        val low = tenthPartialRatio(Pluck.SITAR_STIFFNESS_LOW)
        val high = tenthPartialRatio(Pluck.SITAR_STIFFNESS_HIGH)
        assertTrue(low in 1.005..1.02, "the low candidate should put the tenth partial about 1 % sharp, got $low")
        assertTrue(high in 1.02..1.045, "the high candidate should put the tenth partial about 3 % sharp, got $high")
    }

    @Test
    fun `stiffness zero leaves the tenth partial on the harmonic`() {
        val ratio = tenthPartialRatio(0f)
        assertTrue(kotlin.math.abs(ratio - 1.0) <= 0.003, "with no stiffness the tenth partial should sit within 0.3 % of harmonic, got $ratio")
    }

    @Test
    fun `stiffness does not move the fundamental`() {
        // The allpass's delay at the fundamental is in the tuning budget;
        // TuningAccuracyTest sweeps every note at the shipped stiffness, and
        // this pins the high candidate at three notes so the gate can choose it.
        for (semi in listOf(0, 12, 24)) {
            val f0 = Pluck.frequencyFor(PluckVoice.SITAR, semi)
            val raw = Pluck.synthesize(PluckVoice.SITAR, mapOf("TUNE" to semi / 24f, "DOUBLE" to 0f), rate, stiffnessOverride = Pluck.SITAR_STIFFNESS_HIGH)
            val measured = PluckSpectra.peakHz(raw, rate, f0, spanFraction = 0.03)
            val cents = 1200.0 * kotlin.math.ln(measured / f0) / kotlin.math.ln(2.0)
            assertTrue(kotlin.math.abs(cents) <= 5.0, "SITAR semitone $semi at the high stiffness is $cents cents off")
        }
    }
}
```

This needs `synthesize` to accept the override; add it now in `Pluck.kt`:

```kotlin
    internal fun synthesize(
        voice: PluckVoice,
        macros: Map<String, Float>,
        rate: Int,
        velocity: Float = 1f,
        stiffnessOverride: Float? = null,
    ): FloatArray {
```

and inside use `val stiffness = stiffnessOverride ?: stiffnessFor(voice)` for both `ks` calls. (`velocity` is unused until Task 3; declare it now so the signature settles once.)

- [ ] **Step 6: Run the probe, set the candidates, re-run**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.StiffnessTest" --max-workers=2 --console=plain 2>&1 | grep -E "FAILED|BUILD" | head`, then read the printed lines from `synth/build/test-results/test/TEST-com.snipsnap.synth.StiffnessTest.xml`. Set `SITAR_STIFFNESS_LOW` to the listed coefficient whose sharpness is nearest 1.0 % and `SITAR_STIFFNESS_HIGH` to the one nearest 3.0 % (if neither lands within the asserted windows, add coefficients between the two nearest and re-run; the window is the rule, the list is not). Replace the two `// placeholder` comments with `// measured: tenth partial N.NN % sharp`. Re-run the class: expected BUILD SUCCESSFUL. Then run `PluckTest` and `TuningAccuracyTest` (the default sweep now runs SITAR at the low candidate): expected BUILD SUCCESSFUL; if a SITAR note fails five cents, the `stiffDelay` sign or formula is wrong — check that `stiffDelay` is positive for negative coefficients at the fundamental and report the numbers rather than adjusting the bound.

Then the fingerprint: `rm -rf testkit/pluck-audition && ./gradlew :synth:generatePluckAudition --max-workers=2 --console=plain 2>&1 | grep -E "wrote|FAILED"; md5 -r testkit/pluck-audition/NYLON/*.wav testkit/pluck-audition/HARP/*.wav testkit/pluck-audition/KOTO/*.wav testkit/pluck-audition/BANJO/*.wav testkit/pluck-audition/KIT_SEAM/*.wav | diff - /tmp/p3a-task2-before.md5 && echo FINGERPRINT_UNCHANGED`
Expected: `FINGERPRINT_UNCHANGED`. Any differing line names a voice the loop change moved; stop and report it.

- [ ] **Step 7: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt synth/src/test/kotlin/com/snipsnap/synth/PluckSpectra.kt synth/src/test/kotlin/com/snipsnap/synth/StiffnessTest.kt synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt
git commit -m "Put stiffness inside the loop and fold its delay into the tuning budget, with the sitar's two candidates measured" -m "<body>" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DsZ2eJRrwVXd4cwMqvAGam"
```

---

### Task 3: The jawari — a one-sided limiter in the loop, driven by velocity

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt` (`ks` gains `jawari`; `synthesize` uses `velocity`; `render` gains `velocity`; constants)
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt` (`atVelocity` for `PluckPatch`)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/PluckSpectra.kt` (`highShare`)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt`, `synth/src/test/kotlin/com/snipsnap/synth/TuningAccuracyTest.kt`

**Interfaces:**
- Consumes: `ks(..., stiffness)` and `synthesize(voice, macros, rate, velocity, stiffnessOverride)` from Task 2.
- Produces: `ks(..., stiffness: Float = 0f, jawari: Float = 0f)`; `Pluck.render(voice, macros = emptyMap(), velocity: Float = 1f)`; `synthesize(..., jawariOverride: Float? = null)`; `Pluck.SITAR_JAWARI = 0.3f`; `Pluck.jawariFor(voice)`; `PluckSpectra.highShare(x, rate, cutoffHz, seconds): Double`.

- [ ] **Step 1: The measurement helper**

In `PluckSpectra.kt`:

```kotlin
    /** The share of energy above [cutoffHz] over the first [seconds]: a one-pole high-pass's energy over the total. */
    fun highShare(x: FloatArray, rate: Int, cutoffHz: Float, seconds: Float): Double {
        val n = min(x.size, (seconds * rate).toInt())
        val a = (1.0 - Math.exp(-2.0 * PI * cutoffHz / rate)).toFloat()
        var lp = 0f
        var high = 0.0
        var total = 0.0
        for (i in 0 until n) {
            lp += a * (x[i] - lp)
            val hp = x[i] - lp
            high += hp.toDouble() * hp
            total += x[i].toDouble() * x[i]
        }
        return high / (total + 1e-12)
    }
```

- [ ] **Step 2: Write the failing tests**

In `PluckTest.kt`:

```kotlin
    @Test
    fun `the jawari buzz follows velocity`() {
        // Harder plucks wrap further on the bridge: the high band's share of
        // the first 200 ms must rise with velocity on SITAR.
        val shares = listOf(0.3f, 0.65f, 1.0f).map { v ->
            PluckSpectra.highShare(Pluck.render(PluckVoice.SITAR, mapOf("DOUBLE" to 0f), velocity = v).samples, Dsp.RATE, 2000f, 0.2f)
        }
        println("SITAR high-band share by velocity: $shares")
        assertTrue(shares[0] < shares[1] && shares[1] < shares[2], "buzz should rise with velocity: $shares")
    }

    @Test
    fun `the jawari leaves no offset`() {
        val out = Pluck.render(PluckVoice.SITAR, mapOf("DOUBLE" to 0f), velocity = 1f).samples
        var mean = 0.0
        for (v in out) mean += v
        mean /= out.size
        val peak = PluckSpectra.peak(out)
        assertTrue(kotlin.math.abs(mean) <= 1e-4 * peak, "DC after the jawari: mean $mean against peak $peak")
    }

    @Test
    fun `the jawari never raises the loop's gain`() {
        // |z| <= |y| by construction; this pins the sign so a later edit
        // cannot turn the limiter into a boost inside feedback.
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        for (tenth in 0..10) {
            val damp = tenth / 10f
            val raw = Pluck.synthesize(PluckVoice.SITAR, mapOf("DAMP" to damp, "DOUBLE" to 0f), rate, velocity = 1f, jawariOverride = 0.6f)
            assertTrue(raw.all { it.isFinite() }, "non-finite sample at DAMP $damp")
            val onset = PluckSpectra.peak(raw.copyOfRange(0, minOf(raw.size, (0.01f * rate).toInt())))
            val whole = PluckSpectra.peak(raw)
            assertTrue(whole <= 2f * onset, "DAMP $damp: the note grew past twice its onset ($whole > 2 * $onset)")
        }
    }

    @Test
    fun `velocity reaches the sitar as a number through the velocity path`() {
        val patch = PluckPresets.forVoice(PluckVoice.SITAR).first()
        val soft = Velocity.atVelocity(patch, 0.3f).samples
        val hard = Velocity.atVelocity(patch, 1.0f).samples
        val softShare = PluckSpectra.highShare(soft, Dsp.RATE, 2000f, 0.2f)
        val hardShare = PluckSpectra.highShare(hard, Dsp.RATE, 2000f, 0.2f)
        assertTrue(softShare < hardShare, "the velocity path should carry the jawari, not just PICK: $softShare vs $hardShare")
    }
```

In `TuningAccuracyTest.kt`:

```kotlin
    @Test
    fun `the jawari at full drive keeps every sitar note inside a quarter tone, and names the ones it pulls`() {
        for (semi in 0..Pluck.TUNE_SEMITONES) {
            val macro = semi.toFloat() / Pluck.TUNE_SEMITONES
            val raw = Pluck.synthesize(PluckVoice.SITAR, mapOf("TUNE" to macro, "DOUBLE" to 0f), Dsp.RATE * Dsp.OVERSAMPLE, velocity = 1f, jawariOverride = 0.6f)
            val snip = Snip(Dsp.decimate(raw, Dsp.RATE), channels = 1, sampleRate = Dsp.RATE)
            val want = Pluck.frequencyFor(PluckVoice.SITAR, semi)
            val measured = measuredHz(snip, want)
            val err = abs(cents(measured, want.toDouble()))
            if (err > 5.0) println("SITAR semitone $semi: $err cents at full jawari")
            assertTrue(err <= 50.0, "SITAR semitone $semi is $err cents off at full jawari drive (want $want, got $measured)")
        }
    }
```

Run `PluckTest` and `TuningAccuracyTest`: expected compile failures on `velocity`/`jawariOverride`, which Step 3 adds.

- [ ] **Step 3: The jawari stage in `ks`**

`ks`'s signature ends `stiffness: Float = 0f, jawari: Float = 0f,`; KDoc: `jawari` is the bridge limiter's drive in [0, 1): after the low-pass, positive swings are pulled down by `jawari · y² / p0` (clamped so it never crosses zero), the way a string wrapping on a flat bridge is stopped on one side; a DC blocker follows because a one-sided term leaves an offset; both are skipped at 0. Add `require(jawari in 0f..0.95f) { "jawari drive must be in [0, 0.95], got $jawari" }`.

After the burst is built and zero-meaned, add:

```kotlin
        // The bridge limiter scales to the string's own level: p0 is what a
        // full swing looks like, so the same drive buzzes the same on every
        // note and fades as the note does.
        var p0 = 1e-6f
        for (v in burst) if (kotlin.math.abs(v) > p0) p0 = kotlin.math.abs(v)
        val dcA = (1.0 - exp(-2.0 * PI * 20.0 / rate)).toFloat()
        var dc = 0f
```

and replace the loop's last line `out[i] += fb * loopLp.lp(stiff, loopHz)` with:

```kotlin
            var y = loopLp.lp(stiff, loopHz)
            if (jawari > 0f) {
                if (y > 0f) y -= jawari * min(y, p0) * y / p0
                dc += dcA * (y - dc)
                y -= dc
            }
            out[i] += fb * y
```

(`min` is already imported from `kotlin.math`.)

- [ ] **Step 4: Velocity into `synthesize` and `render`; the drive constant**

In `Pluck.kt`, next to the stiffness constants:

```kotlin
    /**
     * The jawari's drive (see [ks]) times [velocityDrive]. 0.3 is the
     * starting point; the audition hears 0.15, 0.3 and 0.6 and the chips
     * choose (spec, "The jawari").
     */
    internal const val SITAR_JAWARI = 0.3f

    internal fun jawariFor(voice: PluckVoice): Float = when (voice) {
        PluckVoice.SITAR -> SITAR_JAWARI
        PluckVoice.NYLON, PluckVoice.HARP, PluckVoice.KOTO, PluckVoice.BANJO -> 0f
    }

    /** A soft note buzzes a little, a hard one fully. */
    private fun velocityDrive(velocity: Float): Float = Dsp.lin(velocity.coerceIn(0f, 1f), 0.3f, 1f)
```

`synthesize` gains `jawariOverride: Float? = null` after `stiffnessOverride`, computes `val jawari = (jawariOverride ?: jawariFor(voice)) * velocityDrive(velocity)` and passes `jawari = jawari` to the main-string `ks` call only (the DOUBLE detune string on the other voices keeps `jawari = 0f`; it is never rendered for SITAR after Task 4).

`render` becomes:

```kotlin
    fun render(voice: PluckVoice, macros: Map<String, Float> = emptyMap(), velocity: Float = 1f): Snip {
        val renderRate = RATE * Dsp.OVERSAMPLE
        val raw = synthesize(voice, macros, renderRate, velocity = velocity)
        ...
```

with the KDoc line: velocity is a render parameter, not a macro — no knob, no preset, no recipe carries it; only the jawari reads it today.

- [ ] **Step 5: The velocity path**

In `Velocity.atVelocity(patch, velocity, spec)`, replace the last line

```kotlin
        return patch.withMacros(patch.macros + (spec.name to scaled)).render()
```

with

```kotlin
        val moved = patch.macros + (spec.name to scaled)
        // PLUCK also takes the velocity as a number: the sitar's bridge
        // limiter reads it (Pluck.render's KDoc). validateMacros rejects any
        // key macrosFor does not list, so it cannot travel in the map.
        if (patch is PluckPatch) return Pluck.render(patch.voice, moved, velocity = v)
        return patch.withMacros(moved).render()
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.PluckTest" --tests "com.snipsnap.synth.TuningAccuracyTest" --tests "com.snipsnap.synth.StiffnessTest" --tests "com.snipsnap.synth.VelocityGrooveShuffleTest" --max-workers=2 --console=plain 2>&1 | grep -E "FAILED|BUILD" | head`
Expected: BUILD SUCCESSFUL. Paste every `cents at full jawari` line and the `high-band share by velocity` line into the report. If the default five-cent sweep fails on SITAR at drive 0.3, report the cents: the fallback in the spec (the limiter outside the loop) is a design decision the controller takes, not the implementer.

Then the fingerprint, the same way Task 2 took it: before the first edit of this task, `rm -rf testkit/pluck-audition && ./gradlew :synth:generatePluckAudition --max-workers=2 --console=plain 2>&1 | grep -E "wrote|FAILED"; md5 -r testkit/pluck-audition/NYLON/*.wav testkit/pluck-audition/HARP/*.wav testkit/pluck-audition/KOTO/*.wav testkit/pluck-audition/BANJO/*.wav testkit/pluck-audition/KIT_SEAM/*.wav > /tmp/p3a-task3-before.md5`; after the tests pass, repeat the render and `md5 -r ... | diff - /tmp/p3a-task3-before.md5 && echo FINGERPRINT_UNCHANGED`. Expected: `FINGERPRINT_UNCHANGED` — the jawari is 0 on the other four voices and must not touch them. Then run the classification test's class alone and record what the classifier calls SITAR at its default; if it is not PERC, change the allowed set in `factory defaults classify as percussion, and the banjo may read as a snare` to include SITAR with the measured class and extend the comment with one sentence naming the jawari as the reason (the buzz is high-band energy in the attack window).

- [ ] **Step 7: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt synth/src/test/kotlin/com/snipsnap/synth/PluckSpectra.kt synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt synth/src/test/kotlin/com/snipsnap/synth/TuningAccuracyTest.kt
git commit -m "Give the sitar its jawari: a one-sided limiter in the loop that velocity drives and the note's own decay fades" -m "<body>" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DsZ2eJRrwVXd4cwMqvAGam"
```

---

### Task 4: Sympathetic strings under DOUBLE

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt` (new private `sympathetic`; `synthesize`'s DOUBLE block)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt`, `synth/src/test/kotlin/com/snipsnap/synth/TuningAccuracyTest.kt`

**Interfaces:**
- Consumes: `synthesize`'s DOUBLE block as it is today; `PluckSpectra.toneEnergy`.
- Produces: `Pluck.SYMPATHETIC_RATIOS = floatArrayOf(0.5f, 1.5f, 2f, 3f)`, `Pluck.SYMPATHETIC_COUPLING = 0.05f`, `Pluck.SYMPATHETIC_LEVEL = 0.5f` (internal); a private `sympathetic(input, hz, rate): FloatArray`.

- [ ] **Step 1: Write the failing tests**

In `PluckTest.kt`:

```kotlin
    @Test
    fun `DOUBLE on the sitar is the sympathetic strings, not the detune`() {
        val f0 = Pluck.frequencyFor(PluckVoice.SITAR, 12)
        val dry = Pluck.render(PluckVoice.SITAR, mapOf("DOUBLE" to 0f))
        val wet = Pluck.render(PluckVoice.SITAR, mapOf("DOUBLE" to 1f))
        // The octave loop rings at 2·f0 for the whole note; measure late,
        // past the string's own second harmonic's loudest moment.
        val late = 0.6f
        fun octaveEnergy(s: com.snipsnap.audio.Snip): Double {
            val from = (late * s.sampleRate).toInt()
            val slice = com.snipsnap.audio.Snip(s.samples.copyOfRange(from, s.frameCount), channels = 1, sampleRate = s.sampleRate)
            return PluckSpectra.toneEnergy(slice, 2f * f0, 0.25f)
        }
        val gain = 10.0 * kotlin.math.log10(octaveEnergy(wet) / (octaveEnergy(dry) + 1e-12))
        println("SITAR octave energy late in the note, DOUBLE 1 over DOUBLE 0: $gain dB")
        assertTrue(gain >= 6.0, "DOUBLE 1 should ring the octave by 6 dB late in the note, got $gain dB")
        assertTrue(!dry.samples.contentEquals(wet.samples), "DOUBLE 1 must change the render")
    }

    @Test
    fun `DOUBLE below its threshold renders the sitar string alone`() {
        val a = Pluck.render(PluckVoice.SITAR, mapOf("DOUBLE" to 0f)).samples
        val b = Pluck.render(PluckVoice.SITAR, mapOf("DOUBLE" to 0.005f)).samples
        assertTrue(a.contentEquals(b), "DOUBLE under 0.01 should render nothing extra")
    }
```

In `TuningAccuracyTest.kt`:

```kotlin
    @Test
    fun `the sympathetic strings at DOUBLE 1 keep every sitar note within five cents`() {
        for (semi in 0..Pluck.TUNE_SEMITONES) {
            val macro = semi.toFloat() / Pluck.TUNE_SEMITONES
            val snip = Pluck.render(PluckVoice.SITAR, mapOf("TUNE" to macro, "DOUBLE" to 1f))
            val want = Pluck.frequencyFor(PluckVoice.SITAR, semi)
            val measured = measuredHz(snip, want)
            val err = abs(cents(measured, want.toDouble()))
            assertTrue(err <= 5.0, "SITAR semitone $semi at DOUBLE 1 is $err cents off (want $want, got $measured)")
        }
    }
```

Run `PluckTest`: expected the first test fails (today DOUBLE 1 is a detuned second string, which does not ring the octave 6 dB late).

- [ ] **Step 2: The sympathetic loop**

In `Pluck.kt`, after `ks`:

```kotlin
    /** The tarab's ratios to the played note: the octave below, the fifth, the octave, the octave and a fifth. */
    internal val SYMPATHETIC_RATIOS = floatArrayOf(0.5f, 1.5f, 2f, 3f)
    /** How much of the main string reaches each sympathetic loop, sample by sample, the way the bridge transmits it. */
    internal const val SYMPATHETIC_COUPLING = 0.05f
    /** Their sum enters the output at this times DOUBLE, so DOUBLE 1 is a drone on purpose. */
    internal const val SYMPATHETIC_LEVEL = 0.5f
    private const val SYMPATHETIC_LOOP_HZ = 4000f
    private const val SYMPATHETIC_FEEDBACK = 0.999f

    /**
     * One sympathetic string: a Karplus-Strong loop at [hz] with no burst of
     * its own, fed continuously by [input] at [SYMPATHETIC_COUPLING], ringing
     * with [SYMPATHETIC_FEEDBACK] under a darker low-pass. Tuned the way [ks]
     * is (integer delay, fractional allpass, the low-pass's delay in the
     * budget), so the loop rings at the ratio it was given.
     */
    private fun sympathetic(input: FloatArray, hz: Float, rate: Int): FloatArray {
        val filterA = 1.0 - exp(-2.0 * PI * min(SYMPATHETIC_LOOP_HZ, rate * 0.45f) / rate)
        val poleR = 1.0 - filterA
        val w = 2.0 * PI * hz / rate
        val filterPhase = -atan2(poleR * sin(w), 1.0 - poleR * cos(w))
        val exact = (rate / hz) - (-filterPhase / w) - 0.5
        require(exact >= MIN_LOOP_SAMPLES) { "sympathetic loop at $hz Hz is too short ($exact samples)" }
        val n = floor(exact).toInt()
        val frac = (exact - n).toFloat()
        val a = (1f - frac) / (1f + frac)
        var apX1 = 0f
        var apY1 = 0f
        val lp = Dsp.OnePole(rate)
        val out = FloatArray(input.size)
        for (i in input.indices) {
            val fed = SYMPATHETIC_COUPLING * input[i]
            if (i <= n) { out[i] = fed; continue }
            val d = 0.5f * (out[i - n] + out[i - n - 1])
            val tuned = a * (d - apY1) + apX1
            apX1 = d
            apY1 = tuned
            out[i] = fed + SYMPATHETIC_FEEDBACK * lp.lp(tuned, SYMPATHETIC_LOOP_HZ)
        }
        return out
    }
```

In `synthesize`, replace the DOUBLE block with:

```kotlin
        if (double > 0.01f) {
            if (voice == PluckVoice.SITAR) {
                // The tarab: strings under the frets that ring in sympathy with
                // the played note. With no scale to tune them to, they take the
                // note's own series (spec, "Sympathetic strings under DOUBLE").
                val g = SYMPATHETIC_LEVEL * double
                for (ratio in SYMPATHETIC_RATIOS) {
                    val s = sympathetic(out, freq * ratio, rate)
                    for (i in out.indices) out[i] += g * s[i]
                }
            } else {
                // The 12-string trick: a second, slightly sharp string under the
                // first. Detune grows with the macro so it goes chorus -> honky.
                val det = ks(
                    freq * Dsp.lin(double, 1.002f, 1.012f), seconds, damp, loopHz,
                    Dsp.expMap(pick, pickLo, pickHi), seed = Dsp.seedFor("PLUCK", voice.name, "DOUBLE"), rate = rate, position = position,
                    stiffness = stiffness,
                )
                val g = double * 0.7f
                for (i in out.indices) out[i] += det[i] * g
            }
        }
```

(The loop reads `out` before adding to it because `sympathetic` copies nothing: it reads `input[i]` sample by sample as it goes and `out` is only modified after each loop returns — the four loops are fed by the string plus whatever earlier loops added, which is acceptable and deterministic; note it in the KDoc.) Update the DOUBLE `MacroSpec`'s KDoc (in `macrosFor`'s comment block) to say what DOUBLE means on SITAR.

- [ ] **Step 3: Run the tests**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.PluckTest" --tests "com.snipsnap.synth.TuningAccuracyTest" --tests "com.snipsnap.synth.PluckPresetsTest" --max-workers=2 --console=plain 2>&1 | grep -E "FAILED|BUILD" | head`
Expected: BUILD SUCCESSFUL. Paste the `octave energy` line into the report. If the octave test's 6 dB is not reached, report the dB and the coupling; do not change the bound.

Then the fingerprint, the same way Task 2 took it: before the first edit of this task, `rm -rf testkit/pluck-audition && ./gradlew :synth:generatePluckAudition --max-workers=2 --console=plain 2>&1 | grep -E "wrote|FAILED"; md5 -r testkit/pluck-audition/NYLON/*.wav testkit/pluck-audition/HARP/*.wav testkit/pluck-audition/KOTO/*.wav testkit/pluck-audition/BANJO/*.wav testkit/pluck-audition/KIT_SEAM/*.wav > /tmp/p3a-task4-before.md5`; after the tests pass, repeat the render and `md5 -r ... | diff - /tmp/p3a-task4-before.md5 && echo FINGERPRINT_UNCHANGED`. Expected: `FINGERPRINT_UNCHANGED` — the other four voices keep the detune branch of DOUBLE exactly (the `p2_body_default` clips render with each voice's default DOUBLE, so this covers the branch). If the DOUBLE 1 sweep fails five cents, report which notes: the sympathetic loops feed the output, not the main loop, so they cannot move the fundamental — a failure means the measurement's window caught a sympathetic loop near the note (ratio 0.5's second harmonic sits on f0), which the controller rules on.

- [ ] **Step 4: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt synth/src/test/kotlin/com/snipsnap/synth/TuningAccuracyTest.kt
git commit -m "Ring four sympathetic strings under the sitar's DOUBLE, fed by the played string the way the bridge feeds the tarab" -m "<body>" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DsZ2eJRrwVXd4cwMqvAGam"
```

---

### Task 5: The audition set and the listening page (p3a)

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt` (an internal render overload for the generator)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/PluckAuditionGenerator.kt`
- Modify: `synth/src/test/resources/audition/pluck-audition.html`

**Interfaces:**
- Consumes: `synthesize(voice, macros, rate, velocity, stiffnessOverride, jawariOverride)`.
- Produces: `Pluck.renderWith(voice, macros, velocity, stiffness: Float?, jawari: Float?): Snip` (internal; the same tail as `render`); the p3a clips; the page's `SITAR`, `KOTO`, `HARP` cards under `PHASE = 'p3a'`.

- [ ] **Step 1: The internal render overload**

In `Pluck.kt`, after `render`:

```kotlin
    /** [render] with the audition's overrides: the generator hears stiffness and jawari values the shipped constants do not carry. */
    internal fun renderWith(voice: PluckVoice, macros: Map<String, Float>, velocity: Float = 1f, stiffness: Float? = null, jawari: Float? = null): Snip {
        val renderRate = RATE * Dsp.OVERSAMPLE
        val raw = synthesize(voice, macros, renderRate, velocity = velocity, stiffnessOverride = stiffness, jawariOverride = jawari)
        val out = Dsp.decimate(raw, RATE)
        Dsp.levelTo(out, RATE, target = Dsp.MELODIC_LOUDNESS_TARGET + LOUDNESS_OFFSET.getValue(voice))
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }
```

and make `render` call `renderWith(voice, macros, velocity)` so the tail exists once.

- [ ] **Step 2: The clips**

In `PluckAuditionGenerator.kt`, after the p2d kalimba block and before the page copy, add (the existing p2, seam, p2c and p2d sets stay; the KDoc gains a paragraph describing p3a):

```kotlin
        // p3a: the sitar's first gate. Every axis at the default note C#4,
        // the two notes at the ends, and KOTO/HARP with the low stiffness on.
        val sitarDir = File(root, PluckVoice.SITAR.name)
        fun writeSitar(name: String, macros: Map<String, Float>, velocity: Float = 1f, stiffness: Float? = null, jawari: Float? = null) {
            WavWriter.write(File(sitarDir, "$name.wav"), level(Pluck.renderWith(PluckVoice.SITAR, macros, velocity, stiffness, jawari)), WavWriter.BitDepth.PCM_16)
            count++
        }
        writeSitar("p3a_default", emptyMap())
        writeSitar("p3a_jawari_15", emptyMap(), jawari = 0.15f)
        writeSitar("p3a_jawari_60", emptyMap(), jawari = 0.6f)
        writeSitar("p3a_soft", emptyMap(), velocity = 0.3f)
        writeSitar("p3a_symp_0", mapOf("DOUBLE" to 0f))
        writeSitar("p3a_symp_1", mapOf("DOUBLE" to 1f))
        writeSitar("p3a_stiff_off", emptyMap(), stiffness = 0f)
        writeSitar("p3a_stiff_high", emptyMap(), stiffness = Pluck.SITAR_STIFFNESS_HIGH)
        writeSitar("p3a_root", mapOf("TUNE" to 0f))
        writeSitar("p3a_top", mapOf("TUNE" to 1f))
        writeSitar("p3a_thud", mapOf("DAMP" to 1f))
        writeSitar("p3a_ring", mapOf("DAMP" to 0f))
        for (voice in listOf(PluckVoice.KOTO, PluckVoice.HARP)) {
            val dir = File(root, voice.name)
            WavWriter.write(File(dir, "p3a_stiff_off.wav"), level(Pluck.renderWith(voice, emptyMap())), WavWriter.BitDepth.PCM_16)
            WavWriter.write(File(dir, "p3a_stiff_on.wav"), level(Pluck.renderWith(voice, emptyMap(), stiffness = Pluck.SITAR_STIFFNESS_LOW)), WavWriter.BitDepth.PCM_16)
            count += 2
        }
```

Expected total: the current 44 plus 16 = `wrote 60 clips + index.html`.

- [ ] **Step 3: The page**

In `pluck-audition.html`: `var PHASE = 'p3a';`. Title-bar tag: `PHASE 3A` + `SITAR, AND STIFFNESS ON KOTO AND HARP`. Guide, three paragraphs: (a) SITAR is new: a steel string with a bridge that buzzes, four strings that ring in sympathy under DOUBLE, and stiffness that puts its upper partials sharp; it has no body yet because no source for one could be opened, so BODY does nothing on it; (b) the cards hear each axis: the jawari at three drives and a soft pluck, the sympathetic strings off and at the drone, stiffness off and at the higher candidate, then the ends of TUNE and DAMP; (c) KOTO and HARP: the same note with stiffness off and on, and they only change if you chip on as closer. Replace `KALIMBA_GROUPS` and `VOICES` with:

```javascript
  var SITAR_GROUPS = [
    { label: 'THE JAWARI', key: true, clips: [
      ['p3a_default', 'DEFAULT', 'drive .30 at full velocity'],
      ['p3a_jawari_15', 'DRIVE .15', 'a rounder bridge'],
      ['p3a_jawari_60', 'DRIVE .60', 'a flatter bridge, the ugly end'],
      ['p3a_soft', 'SOFT PLUCK', 'velocity .30: the buzz backs off']
    ]},
    { label: 'THE SYMPATHETIC STRINGS', clips: [
      ['p3a_symp_0', 'DOUBLE 0', 'the string alone'],
      ['p3a_symp_1', 'DOUBLE 1', 'the drone, the ugly end']
    ]},
    { label: 'STIFFNESS', clips: [
      ['p3a_stiff_off', 'OFF', 'partials on the harmonics'],
      ['p3a_stiff_high', 'HIGH', 'the tenth partial three percent sharp']
    ]},
    { label: 'THE ENDS', clips: [
      ['p3a_root', 'C#3 ROOT', ''],
      ['p3a_top', 'C#5', 'two octaves up'],
      ['p3a_ring', 'DAMP 0', 'rings out'],
      ['p3a_thud', 'DAMP 1', 'the dead end']
    ]}
  ];
  var STIFF_GROUPS = [
    { label: 'STIFFNESS OFF AND ON', key: true, clips: [
      ['p3a_stiff_off', 'OFF', 'as it ships'],
      ['p3a_stiff_on', 'ON', 'the low candidate']
    ]}
  ];

  var VOICES = [
    { id: 'SITAR', display: 'SITAR', folder: 'SITAR', note: 'C#4', hz: '277 HZ', root: 'C#3' + DOT + '139 HZ', macros: 'DAMP .50' + DOT + 'PICK .65' + DOT + 'STRIKE .30' + DOT + 'DOUBLE .40', body: 'no body yet: no source could be opened', groups: SITAR_GROUPS },
    { id: 'KOTO', display: 'KOTO', folder: 'KOTO', note: 'C#4', hz: '278 HZ', root: 'D3' + DOT + '147 HZ', macros: 'the shipped defaults', body: 'only the stiffness changes', groups: STIFF_GROUPS },
    { id: 'HARP', display: 'HARP', folder: 'HARP', note: 'F4', hz: '350 HZ', root: 'E3' + DOT + '165 HZ', macros: 'the shipped defaults', body: 'only the stiffness changes', groups: STIFF_GROUPS }
  ];
```

Remove `SEAM_GROUPS` if nothing references it after this. Verdict questions: `q_jawari` "1 - Does the bridge buzz read as a sitar, and at which drive?", `q_symp` "2 - The sympathetic strings: keep the default, more, or off?", `q_stiff` "3 - Stiffness: does SITAR want the high candidate, and should KOTO or HARP turn it on?", `q_else` "Anything else". Replace the previous ids everywhere (HTML textareas, `state.overall`, `snapshotOf`, `apply`, `paintOverall`, the input-handler arrays, the footer). The `#` in `C#3`/`C#4`/`C#5` is ASCII and fine.

- [ ] **Step 4: Render and check**

Run: `rm -rf testkit/pluck-audition && ./gradlew :synth:generatePluckAudition --max-workers=2 --console=plain 2>&1 | grep -E "wrote|BUILD|FAILED"`; then `LC_ALL=C grep -c -P '[^\x00-\x7F]' testkit/pluck-audition/index.html` (expect 0); `find testkit/pluck-audition -name "*.wav" | wc -l` (expect 60); and cross-check every id in the page's group arrays against the files under `testkit/pluck-audition/SITAR`, `KOTO`, `HARP` — no dangling ids.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt synth/src/test/kotlin/com/snipsnap/synth/PluckAuditionGenerator.kt synth/src/test/resources/audition/pluck-audition.html
git commit -m "Render the sitar's first listen: the jawari, the sympathetic strings and stiffness, each at its ends" -m "<body>" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DsZ2eJRrwVXd4cwMqvAGam"
```

---

### Task 6: Verification

**Files:** none modified.

- [ ] **Step 1: The full JVM suite, in two halves that fit the foreground cap**

Run: `./gradlew --no-daemon :synth:test --max-workers=2 --console=plain > /tmp/p3a-synth.log 2>&1; echo "exit=$?"; grep -E "BUILD|FAILED" /tmp/p3a-synth.log | tail -3`
Expected: `exit=0`, BUILD SUCCESSFUL.
Run: `./gradlew --no-daemon test -x :app:test -x :synth:test --max-workers=2 --console=plain > /tmp/p3a-rest.log 2>&1; echo "exit=$?"; grep -E "BUILD|FAILED" /tmp/p3a-rest.log | tail -3`
Expected: `exit=0`, BUILD SUCCESSFUL. Gate on the exit codes, never on the grep.

- [ ] **Step 2: The app compiles with the new voice**

Run: `./gradlew :app:compileDebugKotlin --max-workers=2 --console=plain 2>&1 | grep -E "BUILD|error:" | tail -3` (an Android SDK is present via `local.properties`).
Expected: BUILD SUCCESSFUL — `SynthScreen` enumerates `PluckVoice.entries` and has no per-voice `when`, so nothing there needs editing; a compile error here means a `when` somewhere is not exhaustive and the task reports the file and line.

- [ ] **Step 3: A SITAR preset renders through the CLI**

Run: `./gradlew :cli:installDist --max-workers=2 --console=plain 2>&1 | tail -3 && ./cli/build/install/cli/bin/cli synth PLUCK SITAR --preset 1 --out /tmp/pluck-sitar-check && ls -la /tmp/pluck-sitar-check`
Expected: BUILD SUCCESSFUL and a `PLUCK_SITAR_01_ALAAP.wav` (the first preset) of more than 100 KB in the folder.

- [ ] **Step 4: Report**

State the two exit codes, the compile result, the CLI file, and the count of clips, in the task report.
