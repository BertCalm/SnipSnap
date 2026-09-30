# GLINT Paths Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild GLINT so a voice is the path its formant takes — SWEEP, STEP, BRASS, VOWEL on one saw window with a bipolar BLOOM — and give every voice a held note (BREATHE) whose loop closes exactly.

**Architecture:** A new `GlintPath` class owns "where the two bursts sit at path position x" for every voice; `Glint.synthesize` (one-shot) and a new `GlintHeld` (held) are two clocks driving the same path. Legacy voice names decode through a table in `GlintPatch`. The held note reaches MAKE INSTRUMENT through `Keys.glintPad` and a `GlintPadMaker` that mirrors `SirenPadMaker`.

**Tech Stack:** Kotlin, JUnit via `kotlin.test`, Gradle modules `:audio`, `:synth`, `:shell`, `:app`.

**Spec:** `docs/superpowers/specs/2026-09-29-glint-paths-design.md` (commit f31892da). Read it before any task.

## Global Constraints

- Worktree: `/Users/joshuacramblet/SnipSnap/.claude/worktrees/glint-depth-d1`, branch `claude/glint-depth-d2`. Never `cd` elsewhere.
- **Never run two Gradle invocations at once in this worktree.** Run tests in the foreground with a long timeout.
- Gate a test run on the exit code **and** on `synth/build/test-results/test/*.xml` (or `shell/…`) containing no `<failure` / `<error` element. Never grep the console for "FAILED". Never pass `-x :app:test`.
- Run only the test classes a task names, e.g. `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintTest' --console=plain`. The full `:synth:test` runs once, in Task 8.
- One window: `w(φ) = 1 − φ`. k changes **only at phase wraps**, for every voice, one-shot and held.
- BLOOM is bipolar: `s = 2·BLOOM − 1`; `MacroSpec("BLOOM", …, neutral = 0.5f)`. `s > 0` starts above PEAK and falls in; `s < 0` starts below and rises; `s = 0` is still.
- Constants, verbatim from the spec: `K_MIN = 2f`, `K_MAX = 40f`, `BLOOM_MAX = 3f`, `BLOOM_T60 = 0.45f`, `STEP_SECONDS = 0.15f` (was `RATCHET_STEP_SECONDS`), `VOWEL_K_MIN = 1f`, `VOWEL_LEVEL2 = 0.5f`, `BREATHE_SECONDS = 3f`, `BREATHE_SHARE = 0.25f`, `BRASS_REST = 0.5f`.
- Vowel line (Hz): OO 300/870, OH 570/840, AH 730/1090, EH 530/1840, EE 270/2290, positions 0..4.
- Roots: SWEEP, BRASS, VOWEL at A2 = 110 Hz (MIDI 45); STEP at A3 = 220 Hz (MIDI 57).
- Defaults: SWEEP/STEP/BRASS `TUNE 0.5, PEAK 0.45, FOLLOW 0.8, BODY 0.4, BLOOM 0.675, DECAY 0.5`. VOWEL `TUNE 0.5, PEAK 0.5, BODY 0.5, BLOOM 0.6, DECAY 0.5` (no FOLLOW).
- A measured number that misses a bar is reported with the number (DONE_WITH_CONCERNS). Never retune a constant or loosen a bar to make a test pass.
- Commit messages end with:
  ```
  Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01XdQuyqmnaSqRVPTo87MNUr
  ```

**Ruling recorded here (refines spec §3, which the Task 5 commit amends):** BRASS's held breath scales its amplitude swing by `|s|`: `amp = BRASS_REST·(1 + BREATHE_SHARE·|s|·sin)`. Spec text had `±BREATHE_SHARE·BRASS_REST` regardless of BLOOM, which would make BRASS the one voice that is not still at BLOOM 0.5, contradicting §3's own "BLOOM at 0.5 gives a still pad."

**Pre-flight rulings (2026-09-29, before Task 1; the ledger holds the same list with what each costs if wrong):**

- **BLOOM 0 no longer means "no sweep".** With the bipolar BLOOM, `0f` is the widest *rising* path and `0.5f` is still. Every existing `GlintTest.kt` fixture that pins `"BLOOM" to 0f` to get a static formant becomes `"BLOOM" to 0.5f`. Find them with `grep -n '"BLOOM" to 0f' synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt` (today: lines 177, 420, 483, 510, 556, 644, 723, 783, 858 and 1208; the CICADA and PLATE ones are deleted or rewritten anyway). `"BLOOM" to 1f` keeps its old meaning, the widest falling sweep. Fixtures that loop both `0f` and `1f` (period correlation at :386, legality at :1093) stay as they are: both extremes are legal there. A BLOOM-0 fixture left unmigrated would still compile and might still pass while testing something else, which is why this is a rule and not a finding.
- **VOWEL and the centroid gates.** `FeatureExtractor` reads only the first 4096 samples, and a vowel's centroid need not order along the line: EE has the lowest F1 of all five. If VOWEL fails `PEAK opens`, `PEAK sweep is monotonic` or `atVelocity is genuinely darker at low velocity`: an OO/OH inversion alone is fixed by swapping those two rows, as the spec allows; for anything else do not touch thresholds, table rows or macro values. Exclude VOWEL from that one test with `if (voice == GlintVoice.VOWEL) continue` under a `// RULING PENDING` comment, put the measured centroids (soft and hard for the velocity gate) in the report file, and finish DONE_WITH_CONCERNS naming the gates. The controller rules.
- **A path's first read is its start.** In `synthesize`, the first `path.ratios` call takes `x = 1f`, never `x(0f)`: BRASS's `x` is its level, which is 0 at `t = 0`, and the first cycle would otherwise play at PEAK instead of the start ratio. (`GlintHeld` already reads `x = 1` at frame 0.)

**Click coverage (spec §5):** `synthesize` and `GlintHeld.render` move k only inside their wrap branch, the same code path for every voice. `GlintTest`'s `a non-integer ratio clicks no more than an integer one` (all path voices) and `STEP does not click when it steps` pin the mechanism; VOWEL rides the same branch.

## Review Focus

1. **VOWEL at the top of its range with the darkest vowel** (TUNE 1, PEAK 0, BODY 0): both formants pin to the fundamental and the two bursts coincide. A player expects a thin but legal, audible note, not silence or clipping. Test in Task 3.
2. **A held pad at BLOOM 0.5 on the lowest zone:** the breath has zero depth. A player expects a steady, seamless, audible pad. Test in Task 5.
3. **Held STEP at BLOOM 1 and PEAK 0.54:** there `kBase·4` just reaches K_MAX, so the ladder is its longest (about 31 rungs, a 4.6 s onset). A player expects the zone to render in bounded time and length (under 12 s of audio). Test in Task 5.
4. **An old saved patch whose BLOOM is exactly 0, exactly 1, or missing:** it must load, validate, and play the remapped gesture — a missing BLOOM means the old default 0.35. Test in Task 2.
5. **A kit pad saved before this change** (a `PadRecipe` JSON holding a GLINT `REED` patch): the kit must still open. Test in Task 2.

---

### Task 1: Paths — SWEEP, STEP, BRASS on one window, bipolar BLOOM

The enum change breaks compilation everywhere at once, so this task is atomic: engine, test migration, and every compile break land in one commit.

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/GlintPath.kt`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt` (enum, constants, `macrosFor`, `rootHz`, `windowAt`, `ratioAtReference`, `ratioFor`, `synthesize`; delete `KAZOO_FLAT`, `CICADA_SUBCYCLES`, `kCeilingFor`, `ratchetLadder`)
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/GlintPatch.kt` (drop the TRACE `window` paragraph from the KDoc)
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt:438-478` (KDoc only)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/PadRecipeTest.kt:33`, `DeterminismTest.kt:88`, `VelocityGrooveShuffleTest.kt:139` (`BOTTLE`/`REED` → `SWEEP`)
- Delete: `synth/src/test/kotlin/com/snipsnap/synth/GlintAuditionGenerator.kt`, `GlintD1AuditionGenerator.kt`, `GlintD2AuditionGenerator.kt`; `synth/src/test/resources/audition/glint-audition.html`, `glint-d1-audition.html`, `glint-d2-audition.html`; the three `generateGlint*Audition` tasks in `synth/build.gradle.kts:71-97`

**Interfaces:**
- Produces (used by Tasks 2–8):
  - `enum class GlintVoice { SWEEP, STEP, BRASS }` (Task 3 appends `VOWEL`)
  - `Glint.windowAt(phase: Float): Float` — no voice parameter
  - `Glint.bloomSign(bloom: Float): Float`
  - `Glint.startRatio(kBase: Float, s: Float): Float`
  - `internal fun Glint.stepLadder(kBase: Float, kStart: Float): FloatArray`
  - `Glint.STEP_SECONDS`, `Glint.rootMidi(voice: GlintVoice): Int`
  - `internal class GlintPath` with `companion fun of(voice: GlintVoice, macros: Map<String, Float>, f0: Float): GlintPath`, `fun ratios(x: Float, rung: Int, out: FloatArray)`, `val level2: Float`, `val ladder: FloatArray?`, `val onsetSeconds: Float`, `val bloom: Float` (the sign value s)

- [ ] **Step 1: Write the path unit tests (new tests in `GlintTest.kt`)**

Add these tests. They reference symbols that do not exist yet, and `PATH_VOICES`, which Step 5 adds to the class.

```kotlin
    @Test
    fun `one window - the saw ramp - and it ends at exactly zero`() {
        assertEquals(1f, Glint.windowAt(0f))
        assertEquals(0.4f, Glint.windowAt(0.6f), 1e-6f)
        assertEquals(0f, Glint.windowAt(1f))
    }

    @Test
    fun `BLOOM is signed around its centre`() {
        assertEquals(-1f, Glint.bloomSign(0f))
        assertEquals(0f, Glint.bloomSign(0.5f))
        assertEquals(1f, Glint.bloomSign(1f))
        // Up and down are mirror images in log-ratio.
        assertEquals(8f * 4f, Glint.startRatio(8f, 1f), 1e-4f)
        assertEquals(8f / 4f, Glint.startRatio(8f, -1f), 1e-4f)
        assertEquals(8f, Glint.startRatio(8f, 0f))
        // Clamped both ways.
        assertEquals(Glint.K_MAX, Glint.startRatio(30f, 1f))
        assertEquals(Glint.K_MIN, Glint.startRatio(3f, -1f))
    }

    @Test
    fun `STEP's ladder runs from the start to PEAK in whole harmonics, either way`() {
        assertTrue(Glint.stepLadder(8f, 12.6f).contentEquals(floatArrayOf(12f, 11f, 10f, 9f, 8f)))
        assertTrue(Glint.stepLadder(8f, 4.2f).contentEquals(floatArrayOf(5f, 6f, 7f, 8f)))
        assertTrue(Glint.stepLadder(8f, 8f).contentEquals(floatArrayOf(8f)))
        // Outside the snap band the landing stays unrounded (ratchetLadder's old rule).
        val free = Glint.stepLadder(16.28f, 20.5f)
        assertEquals(16.28f, free.last(), 1e-4f)
        assertTrue(free.dropLast(1).all { it == Math.round(it).toFloat() })
    }

    @Test
    fun `every path lands on PEAK - x at zero is kBase for every voice`() {
        val k = FloatArray(2)
        for (voice in PATH_VOICES) {
            for (bloom in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val m = Glint.defaults(voice) + ("BLOOM" to bloom)
                val f0 = Glint.frequencyFor(voice, m.getValue("TUNE"))
                val path = GlintPath.of(voice, m, f0)
                val landed = if (path.ladder != null) path.ladder!!.lastIndex else -1
                path.ratios(0f, landed, k)
                val kBase = Glint.ratioFor(voice, m.getValue("TUNE"), m.getValue("PEAK"), m.getValue("FOLLOW"))
                assertEquals(kBase, k[0], 1e-4f, "$voice BLOOM $bloom does not land on PEAK")
            }
        }
    }

    @Test
    fun `BLOOM below centre starts darker, above centre starts brighter`() {
        val still = mapOf("TUNE" to 0.3f, "PEAK" to 0.4f, "BODY" to 0.2f, "FOLLOW" to 1f, "DECAY" to 0.7f)
        for (voice in listOf(GlintVoice.SWEEP, GlintVoice.BRASS, GlintVoice.STEP)) {
            fun head(bloom: Float): Float =
                FeatureExtractor.extract(slice(Glint.render(voice, still + ("BLOOM" to bloom)), 0.01f, 0.03f)).centroidHz
            val down = head(1f)
            val flat = head(0.5f)
            val up = head(0f)
            assertTrue(down > flat * 1.3f, "$voice BLOOM 1 should open above PEAK: $down vs $flat")
            assertTrue(up < flat / 1.2f, "$voice BLOOM 0 should start below PEAK: $up vs $flat")
        }
    }
```

- [ ] **Step 2: Run to verify it fails to compile**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintTest' --console=plain`
Expected: compilation failure naming `bloomSign`, `startRatio`, `stepLadder`, `GlintPath`, and `windowAt` arity.

- [ ] **Step 3: Create `GlintPath.kt`**

```kotlin
package com.snipsnap.synth

import kotlin.math.pow

/**
 * Where GLINT's two bursts sit at one point along a voice's path
 * (docs/superpowers/specs/2026-09-29-glint-paths-design.md §2).
 *
 * Every path is one number, `x`: 1 at the path's start, 0 on PEAK. The
 * one-shot runs `x` down on a clock (SWEEP, VOWEL), on the note's own level
 * (BRASS) or rung by rung (STEP); a held note's breath swings `x` either
 * side of 0 ([GlintHeld]). Two clocks, one path, so the held note and the
 * one-shot can never disagree about where a voice's formant goes.
 *
 * Every ratio this returns is read by its callers only at a phase wrap,
 * where the window is zero and a change is free.
 */
internal class GlintPath private constructor(
    val voice: GlintVoice,
    private val kBase: Float,
    private val kStart: Float,
    private val k2: Float,
    /** The second burst's level: BODY × [Glint.BODY_MIX] on the path voices. */
    val level2: Float,
    /** STEP's rungs, start first, PEAK last; null on every other voice. */
    val ladder: FloatArray?,
    /** The sign value s = 2·BLOOM − 1. */
    val bloom: Float,
) {
    /** How long the one-shot path takes to arrive: the whole ladder on STEP, [Glint.BLOOM_T60] otherwise. */
    val onsetSeconds: Float = ladder?.let { it.size * Glint.STEP_SECONDS } ?: Glint.BLOOM_T60

    /**
     * Writes the burst ratios at path position [x] into `out[0]` (main) and
     * `out[1]` (second burst). STEP reads `ladder[rung]` when [rung] ≥ 0 and
     * otherwise rounds the continuous path to a whole harmonic — the held
     * breath's case, which has no rung clock.
     */
    fun ratios(x: Float, rung: Int, out: FloatArray) {
        val continuous = (kBase * (kStart / kBase).pow(x)).coerceIn(Glint.K_MIN, Glint.K_MAX)
        out[0] = when {
            ladder != null && rung >= 0 -> ladder[rung.coerceAtMost(ladder.lastIndex)]
            // The landing replaces its nearest whole harmonic, as the one-shot
            // ladder's last rung does. (An exact x == 0f test would leave a
            // still pad, where every x maps to kBase, rounded off PEAK.)
            ladder != null -> {
                val whole = Math.round(continuous)
                if (whole == Math.round(kBase)) kBase else whole.toFloat().coerceIn(Glint.K_MIN, Glint.K_MAX)
            }
            else -> continuous
        }
        out[1] = k2
    }

    companion object {
        /** [macros] already merged over [Glint.defaults]; [f0] the note actually rendered. */
        fun of(voice: GlintVoice, macros: Map<String, Float>, f0: Float): GlintPath {
            val m = Glint.defaults(voice) + macros
            val s = Glint.bloomSign(m.getValue("BLOOM"))
            val kBase = Glint.ratioFor(voice, m.getValue("TUNE"), m.getValue("PEAK"), m.getValue("FOLLOW"))
            val kStart = Glint.startRatio(kBase, s)
            return GlintPath(
                voice = voice,
                kBase = kBase,
                kStart = kStart,
                k2 = Glint.bodyRatio(kBase),
                level2 = m.getValue("BODY") * Glint.BODY_MIX,
                ladder = if (voice == GlintVoice.STEP) Glint.stepLadder(kBase, kStart) else null,
                bloom = s,
            )
        }
    }
}
```

`f0` is unused until Task 3 adds VOWEL; keep the parameter so callers do not change then.

- [ ] **Step 4: Rewrite the voice layer of `Glint.kt`**

Make these edits; keep every KDoc that still describes true behaviour, and rewrite any KDoc that names REED, BOTTLE, KAZOO, CICADA, RATCHET or PLATE as a current voice.

1. `enum class GlintVoice { SWEEP, STEP, BRASS }`.
2. Class KDoc: replace the paragraph describing voices with: "A voice is the path the formant takes — see `GlintPath` and docs/superpowers/specs/2026-09-29-glint-paths-design.md. One window, the saw ramp." Keep the "That zero is the whole engine" paragraph.
3. Delete `KAZOO_FLAT`, `CICADA_SUBCYCLES`, `kCeilingFor` and `ratchetLadder`. Replace every `kCeilingFor(voice)` with `K_MAX`. Rename `RATCHET_STEP_SECONDS` to `STEP_SECONDS` (same value, 0.15f; KDoc: "How long STEP holds each rung").
4. Rewrite `BLOOM_MAX`'s KDoc: "How far the path travels at full BLOOM either way: the start ratio is `kBase·(1 + BLOOM_MAX)` above PEAK or `kBase/(1 + BLOOM_MAX)` below — 4x either way — clamped to K_MIN..K_MAX. At low PEAK the rising side meets K_MIN early and travels less than the falling side; at high PEAK the falling side meets K_MAX."
5. `macrosFor`:

```kotlin
    fun macrosFor(voice: GlintVoice): List<MacroSpec> = listOf(
        MacroSpec("TUNE", 0.5f, 0.5f),
        MacroSpec("PEAK", 0.45f),
        MacroSpec("FOLLOW", 0.8f),
        MacroSpec("BODY", 0.4f),
        // Bipolar: 0.5 is still, above falls into PEAK, below rises into it.
        // 0.675 is the old default 0.35 through GlintPatch's legacy remap.
        MacroSpec("BLOOM", 0.675f, 0.5f),
        MacroSpec("DECAY", 0.5f),
    )
```

6. `rootHz` and a new `rootMidi`:

```kotlin
    fun rootHz(voice: GlintVoice): Float = when (voice) {
        GlintVoice.SWEEP -> 110f  // A2
        GlintVoice.STEP -> 220f   // A3 - RATCHET's register
        GlintVoice.BRASS -> 110f  // A2
    }

    /** [rootHz] as a MIDI note, for the held pad's zones. */
    fun rootMidi(voice: GlintVoice): Int = when (voice) {
        GlintVoice.STEP -> 57
        else -> 45
    }
```

7. `windowAt`:

```kotlin
    /** The one window: a ramp from 1 to exactly 0 at the cycle's end. */
    fun windowAt(phase: Float): Float = 1f - phase.coerceIn(0f, 1f)
```

8. `ratioAtReference(peak: Float): Float = Dsp.expMap(peak, K_MIN, K_MAX)` — drop the voice parameter; fix its callers (`ratioFor`, tests).
9. Add, next to `snapRatio`:

```kotlin
    /** BLOOM as a sign value: -1 fully below, 0 still, +1 fully above. */
    fun bloomSign(bloom: Float): Float = 2f * bloom.coerceIn(0f, 1f) - 1f

    /** Where the path starts for sign value [s]: symmetric in log-ratio, clamped to K_MIN..K_MAX. */
    fun startRatio(kBase: Float, s: Float): Float {
        val a = kotlin.math.abs(s) * BLOOM_MAX
        val k = if (s >= 0f) kBase * (1f + a) else kBase / (1f + a)
        return k.coerceIn(K_MIN, K_MAX)
    }

    /**
     * STEP's rungs in time order: whole harmonics from [kStart] toward the
     * landing, then the landing itself — [snapRatio]`(kBase)`, which stays
     * unrounded outside SNAP_FLOOR..SNAP_CEILING for the reason
     * [SNAP_FLOOR]'s own doc gives (two velocity layers must not collapse
     * onto one rung). Falling for a start above PEAK, rising for one below,
     * one rung for BLOOM 0.5.
     */
    internal fun stepLadder(kBase: Float, kStart: Float): FloatArray {
        val landing = snapRatio(kBase.coerceIn(K_MIN, K_MAX))
        val rungs = ArrayList<Float>()
        if (kStart > landing) {
            var k = kotlin.math.floor(kStart)
            while (k > landing) { rungs.add(k); k -= 1f }
        } else if (kStart < landing) {
            var k = kotlin.math.ceil(kStart)
            while (k < landing) { rungs.add(k); k += 1f }
        }
        rungs.add(landing)
        return rungs.toFloatArray()
    }
```

10. Replace `synthesize` entirely:

```kotlin
    internal fun synthesize(voice: GlintVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = defaults(voice) + macros
        val f0 = frequencyFor(voice, m.getValue("TUNE"))
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.12f, 1.4f)
        val frames = (t60 * 1.35f * rate).toInt().coerceAtLeast(64)
        val path = GlintPath.of(voice, m, f0)
        val amp = Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60)
        // The second burst's own envelope - one envelope, not two composed
        // (see BODY_DECAY_RATIO's doc).
        val env2 = Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60 * BODY_DECAY_RATIO)
        // The one-shot clock: SWEEP runs x down on BLOOM_T60, BRASS on its
        // own level (so it lands as the note dies), STEP by rung.
        fun x(t: Float): Float = if (voice == GlintVoice.BRASS) amp.at(t) else Dsp.envAt(t, BLOOM_T60)
        fun rung(t: Float): Int = if (path.ladder != null) (t / STEP_SECONDS).toInt() else -1
        val k = FloatArray(2)
        // The first read is the path's start (x = 1), not x(0f): BRASS's x is
        // its own level, which is 0 at t = 0 - the first cycle would play at PEAK.
        path.ratios(1f, rung(0f), k)
        val step = f0.toDouble() / rate
        var phase = 0.0
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            val w = windowAt(phase.toFloat())
            val burst = w * sin(2.0 * PI * k[0] * phase).toFloat()
            val second = path.level2 * w * sin(2.0 * PI * k[1] * phase).toFloat()
            out[i] = amp.at(t) * burst + env2.at(t) * second
            phase += step
            if (phase >= 1.0) {
                phase -= 1.0
                // The only instant a ratio change is free: the window has
                // just reached zero. Every voice moves k here and only here.
                path.ratios(x(t), rung(t), k)
            }
        }
        return out
    }
```

Keep the existing comment explaining why DC is left uncorrected (move it above `second`). Delete the `floor`, `Random`-unused imports only if the compiler reports them unused.

- [ ] **Step 5: Migrate `GlintTest.kt`**

Add at the top of the class: `private val PATH_VOICES = GlintVoice.entries` (Task 3 narrows it to exclude VOWEL). First apply the pre-flight BLOOM rule to every test in the file. Then apply this disposition (line numbers are today's; find sites with grep, not by line):

| line | test | action |
|---|---|---|
| :50 | every voice renders clean audio… | keep |
| :70 | every voice declares exactly the six macros | keep (Task 3 splits it) |
| :81 | every window ends at exactly zero… | delete — replaced by `one window - the saw ramp…` |
| :89 | each voice's window is the shape the spec pairs it with… | rewrite: keep only the `rootHz` range check, loop `GlintVoice.entries`, rename `every voice's root is inside its own TUNE range`; also assert `Keys.midiHz(Glint.rootMidi(voice)) == Glint.rootHz(voice)` within 0.01 Hz (find `midiHz` in `Keys.kt`; if it is private, compute `440·2^((midi−69)/12)` inline) |
| :137 | TUNE snaps to semitones… | `REED` → `SWEEP` |
| :146, :155, :164, :175 | DECAY, deterministic, scramble, PEAK opens | keep |
| :250 | the formant sweeps and the pitch does not move… | loop `PATH_VOICES`; every voice now sweeps down at default BLOOM 0.675, so drop voice-specific branches; delete CICADA comments |
| :398 | snapRatio band | keep |
| :416 | PEAK values inside one snap zone… | `BOTTLE` → `SWEEP` |
| :443 | PEAK pins the formant on the named harmonic | loop `PATH_VOICES`, delete the CICADA branch at :481, render at BLOOM 0.5 |
| :500 | oversampled path | keep |
| :524 | non-integer ratio clicks no more… | loop `PATH_VOICES`, `kCeilingFor(v)` → `Glint.K_MAX`, render at BLOOM 0.5 |
| :604, :623, :662 | FOLLOW tests, ratio floor | loop `PATH_VOICES`; :623 `BOTTLE` → `SWEEP` |
| :692, :738, :797 | BODY tests | loop `PATH_VOICES`; delete the CICADA branch at :727 |
| :904 | `BLOOM_SWEEPS_AND_SETTLES` | `setOf(GlintVoice.SWEEP, GlintVoice.BRASS)` |
| :948 | BLOOM opens the peak at the attack… | replace the body's `when` with the code below |
| :1022 | BLOOM lands before the note ends… | replace the body's `when` with the code below |
| :1063 | BLOOM sweeps slowly enough to hear | `REED` → `SWEEP` |
| :1093 | BLOOM at its ugliest… | loop BLOOM over `listOf(0f, 1f)` as well as PEAK |
| :1106 | round-trips through JSON | `BOTTLE` → `SWEEP`, macros `PEAK 0.7, FOLLOW 0.2, BLOOM 0.2` |
| :1114, :1121 | rejects macros | `REED` → `SWEEP` |
| :1128, :1136, :1227, :1233, :1250 | keep; :1227 `REED` → `SWEEP` |
| :1306, :1386 | CICADA tests | delete |
| :1482 | RATCHET's formant is piecewise constant | `STEP`; `RATCHET_STEP_SECONDS` → `STEP_SECONDS` |
| :1497 | RATCHET's steps land on integer harmonics | replace with `STEP's ladder runs from the start to PEAK…` (Step 1) |
| :1572 | RATCHET climbs further as BLOOM opens | replace with the `STEP travels further…` test below |
| :1636 | RATCHET does not click when it steps | `STEP`; `RATCHET_STEP_SECONDS` → `STEP_SECONDS` |
| :1684, :1729 | PLATE's formant falls… / tracks the amplitude | `BRASS`, render at BLOOM 1 where they pinned BLOOM 1 |
| :1760 | PLATE at BLOOM zero does not move its formant | `BRASS at BLOOM 0.5 does not move its formant`; BLOOM 0 → 0.5 |

Delete every KDoc paragraph that reports measurements for a deleted voice; keep measurements whose voice survives (REED's become SWEEP's only if you re-measure them — otherwise delete the figure and keep the reasoning).

New body for :948 (`BLOOM opens the peak at the attack and lets it settle`):

```kotlin
        for (voice in PATH_VOICES) {
            val still = mapOf("TUNE" to 0.3f, "PEAK" to 0.4f, "BODY" to 0.2f, "FOLLOW" to 1f, "DECAY" to 0.7f)
            fun headToTail(bloom: Float): Float {
                val snip = Glint.render(voice, still + ("BLOOM" to bloom))
                val head = FeatureExtractor.extract(slice(snip, 0f, 0.012f)).centroidHz
                val tail = FeatureExtractor.extract(slice(snip, snip.durationSeconds * 0.7f, snip.durationSeconds)).centroidHz
                return head / tail
            }
            assertTrue(headToTail(0.5f) in 0.8f..1.2f, "$voice BLOOM 0.5 should leave head and tail alike: ${headToTail(0.5f)}")
            when (voice) {
                in BLOOM_SWEEPS_AND_SETTLES -> {
                    assertTrue(headToTail(1f) > 1.4f, "$voice BLOOM 1 should open the head above the tail: ${headToTail(1f)}")
                    assertTrue(headToTail(0f) < 1f / 1.2f, "$voice BLOOM 0 should start the head below the tail: ${headToTail(0f)}")
                }
                // STEP's ladder can outlast the note (STEP_SECONDS per rung),
                // so its tail need not reach PEAK: assert direction only.
                GlintVoice.STEP -> {
                    assertTrue(headToTail(1f) > 1.1f, "STEP BLOOM 1 should begin above where it is heading: ${headToTail(1f)}")
                    assertTrue(headToTail(0f) < 1f / 1.1f, "STEP BLOOM 0 should begin below where it is heading: ${headToTail(0f)}")
                }
                else -> error("$voice has no BLOOM assertion in this test")
            }
        }
```

New body for :1022 (`BLOOM lands before the note ends, whatever it did on the way`):

```kotlin
        for (voice in BLOOM_SWEEPS_AND_SETTLES) {
            val still = mapOf("TUNE" to 0.3f, "PEAK" to 0.4f, "BODY" to 0.2f, "FOLLOW" to 1f, "DECAY" to 0.7f)
            fun tail(bloom: Float): Float {
                val snip = Glint.render(voice, still + ("BLOOM" to bloom))
                return FeatureExtractor.extract(slice(snip, snip.durationSeconds * 0.75f, snip.durationSeconds)).centroidHz
            }
            val landed = tail(0.5f)
            for (bloom in listOf(0f, 1f)) {
                assertTrue(
                    kotlin.math.abs(tail(bloom) - landed) < landed * 0.2f,
                    "$voice BLOOM $bloom must have landed on PEAK by the tail: ${tail(bloom)} vs $landed",
                )
            }
        }
```

Replacement for :1572:

```kotlin
    @Test
    fun `STEP travels further the further BLOOM sits from centre, either way`() {
        val kBase = 8f
        fun rungs(bloom: Float) = Glint.stepLadder(kBase, Glint.startRatio(kBase, Glint.bloomSign(bloom))).size
        assertEquals(1, rungs(0.5f))
        assertTrue(rungs(0.75f) < rungs(1f), "falling: ${rungs(0.75f)} vs ${rungs(1f)}")
        assertTrue(rungs(0.25f) < rungs(0f), "rising: ${rungs(0.25f)} vs ${rungs(0f)}")
    }
```

- [ ] **Step 6: Fix the other compile breaks and delete the old generators**

- `PadRecipeTest.kt:33`, `DeterminismTest.kt:88`: `GlintVoice.BOTTLE` → `GlintVoice.SWEEP`.
- `VelocityGrooveShuffleTest.kt:139`: `GlintVoice.REED` → `GlintVoice.SWEEP`.
- Delete the three generator files, the three audition HTML pages, and the three `tasks.register<JavaExec>("generateGlint…Audition")` blocks with their preceding doc comments in `synth/build.gradle.kts`.
- `Velocity.kt:438-478`: replace the six-voice centroid tables with one paragraph: "PEAK (GLINT) joins on a measured sweep — `GlintTest`'s `PEAK sweep is monotonic` holds centroid non-decreasing across a nine-point PEAK sweep on every voice. That test is the gate; this doc does not repeat its numbers." Leave `BRIGHTNESS_MACROS` unchanged.
- `GlintPatch.kt`: delete the KDoc paragraph promising a `window: IntArray?` field.

- [ ] **Step 7: Run the GLINT tests and the three touched test classes**

Run, one at a time:
```
./gradlew :synth:test --tests 'com.snipsnap.synth.GlintTest' --console=plain
./gradlew :synth:test --tests 'com.snipsnap.synth.PadRecipeTest' --tests 'com.snipsnap.synth.DeterminismTest' --tests 'com.snipsnap.synth.VelocityGrooveShuffleTest' --console=plain
```
Expected: exit 0; the XML for each class has no `<failure` or `<error`. If a retained test fails on a measured bar, report the number — do not loosen it.

- [ ] **Step 8: Compile the app module**

Run: `./gradlew :app:compileDebugKotlin --console=plain`
Expected: exit 0 (SynthScreen uses only `GlintVoice.entries` and generic calls).

- [ ] **Step 9: Commit**

```bash
git add -A synth/ 
git commit -m "Rebuild GLINT's voices as paths: SWEEP, STEP, BRASS on one window, bipolar BLOOM

(the two trailer lines from Global Constraints)"
```

---

### Task 2: Old voice names still load

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/GlintPatch.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/GlintLegacyTest.kt` (create)

**Interfaces:**
- Consumes: `GlintVoice { SWEEP, STEP, BRASS }` from Task 1.
- Produces: `GlintPatch.fromJsonValue` accepts `REED, BOTTLE, KAZOO, CICADA, RATCHET, PLATE` and always writes an explicit BLOOM.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals

/** docs/superpowers/specs/2026-09-29-glint-paths-design.md §4 - the old voice name is the migration marker. */
class GlintLegacyTest {

    private fun old(voice: String, macros: String) =
        """{"engine":"GLINT","version":${Patches.VERSION},"name":"Old","voice":"$voice","macros":{$macros}}"""

    @Test
    fun `every old voice name loads as the voice that replaced it`() {
        val expected = mapOf(
            "REED" to GlintVoice.SWEEP, "BOTTLE" to GlintVoice.SWEEP, "KAZOO" to GlintVoice.SWEEP,
            "CICADA" to GlintVoice.SWEEP, "PLATE" to GlintVoice.BRASS, "RATCHET" to GlintVoice.STEP,
        )
        for ((name, voice) in expected) {
            val patch = GlintPatch.fromJsonText(old(name, "\"PEAK\":0.6")) as GlintPatch
            assertEquals(voice, patch.voice, name)
            assertEquals(0.6f, patch.macros.getValue("PEAK"), name)
        }
    }

    @Test
    fun `old BLOOM keeps its gesture - falling voices above centre, the ladder below`() {
        fun bloom(voice: String, b: Float) =
            (GlintPatch.fromJsonText(old(voice, "\"BLOOM\":$b")) as GlintPatch).macros.getValue("BLOOM")
        assertEquals(0.5f, bloom("REED", 0f))
        assertEquals(1f, bloom("REED", 1f))
        assertEquals(1f, bloom("PLATE", 1f))
        assertEquals(0.5f, bloom("RATCHET", 0f))
        assertEquals(0f, bloom("RATCHET", 1f))
    }

    @Test
    fun `an old patch with no BLOOM gets the old default's gesture, not today's default`() {
        fun bloom(voice: String) = (GlintPatch.fromJsonText(old(voice, "")) as GlintPatch).macros.getValue("BLOOM")
        assertEquals(0.675f, bloom("BOTTLE"), 1e-6f)
        assertEquals(0.325f, bloom("RATCHET"), 1e-6f)
    }

    @Test
    fun `today's names load untouched`() {
        val patch = GlintPatch("New", GlintVoice.STEP, mapOf("BLOOM" to 0.2f))
        assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
    }

    @Test
    fun `a kit pad saved before the change still opens`() {
        // A recipe as an old build wrote it: today's recipe JSON, with the
        // voice renamed back to what it was called then. (PadRecipe's JSON
        // needs a "recipe" version key, so it is not written by hand.)
        val current = PadRecipe(patch = GlintPatch("Old", GlintVoice.SWEEP, mapOf("PEAK" to 0.5f, "BLOOM" to 0.4f)))
        val saved = current.toJsonText().replace("\"SWEEP\"", "\"REED\"")
        val patch = PadRecipe.fromJsonText(saved).patch as GlintPatch
        assertEquals(GlintVoice.SWEEP, patch.voice)
        assertEquals(0.7f, patch.macros.getValue("BLOOM"), 1e-6f)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintLegacyTest' --console=plain`
Expected: FAIL — `JsonException: unknown voice REED`.

- [ ] **Step 3: Implement the legacy table in `GlintPatch`**

Replace the companion's `fromJsonValue`:

```kotlin
        /**
         * GLINT's voices before docs/superpowers/specs/2026-09-29-glint-paths-design.md.
         * The old name is the migration marker - `Patches.VERSION` is shared by
         * every engine and cannot mark GLINT alone. Old BLOOM ran 0 (still) to 1
         * (widest), falling on every voice but RATCHET, whose ladder climbed.
         * BLOOM is always written, because the old default (0.35) and today's
         * (0.675) mean different gestures on STEP.
         */
        private class Legacy(val voice: GlintVoice, val bloom: (Float) -> Float)

        private val FALLING: (Float) -> Float = { b -> 0.5f + b / 2f }
        private val LEGACY = mapOf(
            "REED" to Legacy(GlintVoice.SWEEP, FALLING),
            "BOTTLE" to Legacy(GlintVoice.SWEEP, FALLING),
            "KAZOO" to Legacy(GlintVoice.SWEEP, FALLING),
            "CICADA" to Legacy(GlintVoice.SWEEP, FALLING),
            "PLATE" to Legacy(GlintVoice.BRASS, FALLING),
            "RATCHET" to Legacy(GlintVoice.STEP) { b -> 0.5f - b / 2f },
        )
        private const val LEGACY_DEFAULT_BLOOM = 0.35f

        fun fromJsonValue(value: JsonValue): Patch {
            val saved = (value as? JsonValue.Obj)?.entries?.get("voice")?.let { (it as? JsonValue.Str)?.value }
            val legacy = saved?.let { LEGACY[it] }
            return Patches.decode(
                value, ENGINE,
                { n -> GlintVoice.entries.firstOrNull { it.name == n } ?: LEGACY[n]?.voice },
            ) { name, voice, macros ->
                val migrated = if (legacy == null) macros
                else macros + ("BLOOM" to legacy.bloom(macros["BLOOM"] ?: LEGACY_DEFAULT_BLOOM))
                GlintPatch(name, voice, migrated)
            }
        }
```

Check `com.snipsnap.json.JsonValue`'s real shape (`Obj.entries`, `Str.value` or accessor functions `obj()` / `str()` as `Patches.decode` uses) and use the same accessors `Patches.decode` uses.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintLegacyTest' --tests 'com.snipsnap.synth.GlintTest' --console=plain`
Expected: exit 0, no `<failure`/`<error` in either XML.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/GlintPatch.kt synth/src/test/kotlin/com/snipsnap/synth/GlintLegacyTest.kt
git commit -m "Load GLINT patches saved under the old voice names, keeping their BLOOM gesture

(the two trailer lines from Global Constraints)"
```

---

### Task 3: VOWEL

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt`, `GlintPath.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/GlintVowelTest.kt` (create); `GlintTest.kt` (narrow `PATH_VOICES`, split the macro-list test)

**Interfaces:**
- Consumes: `GlintPath`, `Glint.bloomSign` from Task 1.
- Produces: `GlintVoice.VOWEL`; `Glint.VOWEL_K_MIN`, `Glint.VOWEL_LEVEL2`, `Glint.VOWEL_LAST = 4f`; `Glint.vowelPosition(peak: Float): Float`; `internal fun Glint.vowelAt(pos: Float, out: FloatArray)` writing F1, F2 in Hz.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Snip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/superpowers/specs/2026-09-29-glint-paths-design.md §2.3. */
class GlintVowelTest {

    @Test
    fun `the vowel line - PEAK walks OO OH AH EH EE`() {
        val f = FloatArray(2)
        Glint.vowelAt(0f, f); assertEquals(300f, f[0], 0.01f); assertEquals(870f, f[1], 0.01f)
        Glint.vowelAt(2f, f); assertEquals(730f, f[0], 0.01f); assertEquals(1090f, f[1], 0.01f)
        Glint.vowelAt(4f, f); assertEquals(270f, f[0], 0.01f); assertEquals(2290f, f[1], 0.01f)
        // Halfway between OO and OH is the geometric mean - log2 interpolation.
        Glint.vowelAt(0.5f, f); assertEquals(kotlin.math.sqrt(300f * 570f), f[0], 0.05f)
        assertEquals(0f, Glint.vowelPosition(0f)); assertEquals(4f, Glint.vowelPosition(1f))
    }

    @Test
    fun `VOWEL has no FOLLOW - its formants are fixed Hz`() {
        assertEquals(listOf("TUNE", "PEAK", "BODY", "BLOOM", "DECAY"), Glint.macrosFor(GlintVoice.VOWEL).map { it.name })
    }

    @Test
    fun `the two bursts sit at F1 and F2 over the note`() {
        val m = Glint.defaults(GlintVoice.VOWEL) + mapOf("PEAK" to 0.5f, "BODY" to 0.5f, "BLOOM" to 0.5f, "TUNE" to 0f)
        val path = GlintPath.of(GlintVoice.VOWEL, m, 110f)
        val k = FloatArray(2)
        path.ratios(0f, -1, k)
        assertEquals(730f / 110f, k[0], 1e-3f)
        assertEquals(1090f / 110f, k[1], 1e-3f)
    }

    @Test
    fun `BODY scales both formants together`() {
        val k = FloatArray(2)
        fun at(body: Float): FloatArray {
            val m = Glint.defaults(GlintVoice.VOWEL) + mapOf("BODY" to body, "BLOOM" to 0.5f, "TUNE" to 0f)
            GlintPath.of(GlintVoice.VOWEL, m, 110f).ratios(0f, -1, k)
            return k.copyOf()
        }
        val small = at(1f); val big = at(0f)
        val ratio = kotlin.math.sqrt(2f)   // 2^(0.5*0.5) / 2^(-0.5*0.5)
        assertEquals(ratio, small[0] / big[0], 1e-3f)
        assertEquals(ratio, small[1] / big[1], 1e-3f)
    }

    @Test
    fun `a formant below the note pins to the fundamental instead of vanishing`() {
        val m = Glint.defaults(GlintVoice.VOWEL) + mapOf("PEAK" to 0f, "BODY" to 0f, "BLOOM" to 0.5f, "TUNE" to 1f)
        val f0 = Glint.frequencyFor(GlintVoice.VOWEL, 1f)   // A4, 440 Hz
        val k = FloatArray(2)
        GlintPath.of(GlintVoice.VOWEL, m, f0).ratios(0f, -1, k)
        assertEquals(Glint.VOWEL_K_MIN, k[0])
    }

    @Test
    fun `VOWEL at the top of its range with the darkest vowel still sounds`() {
        val snip = Glint.render(GlintVoice.VOWEL, mapOf("TUNE" to 1f, "PEAK" to 0f, "BODY" to 0f))
        assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f })
        assertTrue(snip.peak() > 0.5f, "too quiet: ${snip.peak()}")
    }

    @Test
    fun `BLOOM glides between vowels - above centre from a brighter one`() {
        fun head(bloom: Float): Float {
            val snip = Glint.render(GlintVoice.VOWEL, mapOf("PEAK" to 0.5f, "BLOOM" to bloom, "DECAY" to 0.7f))
            return FeatureExtractor.extract(Snip(snip.samples.copyOfRange(441, 1764), 1, snip.sampleRate)).centroidHz
        }
        assertTrue(head(1f) > head(0.5f) * 1.2f, "BLOOM 1 should start on a brighter vowel")
        assertTrue(head(0f) < head(0.5f) / 1.1f, "BLOOM 0 should start on a darker vowel")
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintVowelTest' --console=plain`
Expected: compilation failure (`VOWEL`, `vowelAt`, `vowelPosition`, `VOWEL_K_MIN` unresolved).

- [ ] **Step 3: Implement**

In `Glint.kt`:

```kotlin
enum class GlintVoice { SWEEP, STEP, BRASS, VOWEL }
```

```kotlin
    /** VOWEL's own floor: a formant below the note pins to the fundamental rather than vanishing. */
    const val VOWEL_K_MIN = 1f

    /** F2's level against F1 - the 2026-09-29 audition's ratio ("It works"). */
    const val VOWEL_LEVEL2 = 0.5f

    /** The last position on the vowel line (EE). */
    const val VOWEL_LAST = 4f

    /** OO, OH, AH, EH, EE as (F1, F2) Hz - adult formants, ordered dark to bright so PEAK keeps meaning "how bright". */
    private val VOWEL_LINE = arrayOf(
        floatArrayOf(300f, 870f),
        floatArrayOf(570f, 840f),
        floatArrayOf(730f, 1090f),
        floatArrayOf(530f, 1840f),
        floatArrayOf(270f, 2290f),
    )

    fun vowelPosition(peak: Float): Float = peak.coerceIn(0f, 1f) * VOWEL_LAST

    /** F1 and F2 at [pos] on the vowel line, interpolated in log2(Hz). */
    internal fun vowelAt(pos: Float, out: FloatArray) {
        val p = pos.coerceIn(0f, VOWEL_LAST)
        val i = kotlin.math.floor(p).toInt().coerceAtMost(VOWEL_LINE.size - 2)
        val f = p - i
        for (n in 0..1) {
            val a = VOWEL_LINE[i][n]
            val b = VOWEL_LINE[i + 1][n]
            out[n] = a * (b / a).pow(f)
        }
    }
```

`macrosFor` becomes a `when`:

```kotlin
    fun macrosFor(voice: GlintVoice): List<MacroSpec> = when (voice) {
        GlintVoice.VOWEL -> listOf(
            MacroSpec("TUNE", 0.5f, 0.5f),
            MacroSpec("PEAK", 0.5f),          // AH
            MacroSpec("BODY", 0.5f, 0.5f),    // vocal-tract size, centred
            MacroSpec("BLOOM", 0.6f, 0.5f),   // a short glide down into the vowel
            MacroSpec("DECAY", 0.5f),
        )
        else -> listOf(
            MacroSpec("TUNE", 0.5f, 0.5f),
            MacroSpec("PEAK", 0.45f),
            MacroSpec("FOLLOW", 0.8f),
            MacroSpec("BODY", 0.4f),
            MacroSpec("BLOOM", 0.675f, 0.5f),
            MacroSpec("DECAY", 0.5f),
        )
    }
```

`rootHz`: add `GlintVoice.VOWEL -> 110f  // A2`. `rootMidi` needs no change.

`ratioFor` reads `FOLLOW`; VOWEL never calls it (see `GlintPath` below), so leave it.

In `synthesize`, VOWEL's second burst is the same mouth: `val env2 = if (voice == GlintVoice.VOWEL) amp else Dsp.Env(...)`.

In `GlintPath`, add VOWEL fields and a branch. The constructor gains `private val f0: Float, private val vowelPos: Float, private val vowelStart: Float, private val scale: Float`; path voices pass `0f` for the three vowel values and `1f` for `scale`. `ratios`:

```kotlin
    fun ratios(x: Float, rung: Int, out: FloatArray) {
        if (voice == GlintVoice.VOWEL) {
            Glint.vowelAt(vowelPos + (vowelStart - vowelPos) * x, out)
            out[0] = (out[0] * scale / f0).coerceIn(Glint.VOWEL_K_MIN, Glint.K_MAX)
            out[1] = (out[1] * scale / f0).coerceIn(Glint.VOWEL_K_MIN, Glint.K_MAX)
            return
        }
        // ... Task 1's body unchanged
    }
```

`of`:

```kotlin
            if (voice == GlintVoice.VOWEL) {
                val pos = Glint.vowelPosition(m.getValue("PEAK"))
                return GlintPath(
                    voice = voice, kBase = 0f, kStart = 0f, k2 = 0f,
                    level2 = Glint.VOWEL_LEVEL2, ladder = null, bloom = s,
                    f0 = f0, vowelPos = pos,
                    vowelStart = (pos + s * Glint.VOWEL_LAST).coerceIn(0f, Glint.VOWEL_LAST),
                    scale = 2f.pow(0.5f * (m.getValue("BODY") - 0.5f)),
                )
            }
```

VOWEL has no harmonic snap: formants are Hz, not harmonics.

In `GlintTest.kt`: `private val PATH_VOICES = GlintVoice.entries - GlintVoice.VOWEL`. Split `every voice declares exactly the six macros` to loop `PATH_VOICES` (VOWEL's list is `GlintVowelTest`'s). Leave generic tests on `GlintVoice.entries` so VOWEL joins them: clean audio, DECAY, determinism, scramble, PEAK opens, oversampled path, BLOOM at its ugliest, `PEAK sweep is monotonic`, both velocity tests.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintVowelTest' --tests 'com.snipsnap.synth.GlintTest' --console=plain`
Expected: exit 0, with one exception the controller has already ruled on (see "VOWEL and the centroid gates" in the header): if VOWEL fails a centroid gate, an OO/OH inversion alone is fixed by swapping those two rows of `VOWEL_LINE` (spec §2.3), updating the first test's expectations and saying so in the commit message. For anything else, exclude VOWEL from that one test under a `// RULING PENDING` comment, report the measured centroids, and finish DONE_WITH_CONCERNS.

- [ ] **Step 5: Commit**

```bash
git add synth/
git commit -m "Add VOWEL: GLINT's two bursts at mouth formants, PEAK choosing the vowel

(the two trailer lines from Global Constraints)"
```

---

### Task 4: The paths separate by ear — measured

**Files:**
- Create: `synth/src/test/kotlin/com/snipsnap/synth/BandDistance.kt`
- Create: `synth/src/test/kotlin/com/snipsnap/synth/GlintSeparationTest.kt`

**Interfaces:**
- Consumes: `Glint.render`, all four voices.
- Produces: `internal object BandDistance { fun whole(a: FloatArray, b: FloatArray, rate: Int): Double; fun path(a: FloatArray, b: FloatArray, rate: Int, segments: Int = 4): Double }` (test source set only).

- [ ] **Step 1: Write the measure**

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Fft
import kotlin.math.abs
import kotlin.math.pow

/**
 * The GLINT distance measure (docs/superpowers/specs/2026-09-28-glint-voice-identity-design.md §7,
 * carried into 2026-09-29-glint-paths-design.md §5): energy-weighted third-octave L1 between
 * energy-normalised band vectors, 0 (identical) to 2 (disjoint).
 *
 * Tiles 4096-sample frames at hop 2048 across the whole span. `Fft.magnitudeSpectrum` applies
 * the Hann window itself, and reads only its first `size` samples, so one call over a long
 * buffer measures its first 93 ms and nothing else - the bug the 2026-09-28 probe caught.
 */
internal object BandDistance {
    private const val N = 4096
    private const val HOP = 2048
    private val CENTRES: List<Double> = generateSequence(50.0) { it * 2.0.pow(1.0 / 3.0) }.takeWhile { it <= 16000.0 }.toList()

    fun bands(x: FloatArray, rate: Int): DoubleArray {
        val e = DoubleArray(CENTRES.size)
        var start = 0
        do {
            val frame = FloatArray(N) { i -> if (start + i < x.size) x[start + i] else 0f }
            val mag = Fft.magnitudeSpectrum(frame, N)
            for (bin in mag.indices) {
                val hz = bin.toDouble() * rate / N
                for (b in CENTRES.indices) {
                    val lo = CENTRES[b] / 2.0.pow(1.0 / 6.0)
                    val hi = CENTRES[b] * 2.0.pow(1.0 / 6.0)
                    if (hz >= lo && hz < hi) { e[b] += mag[bin].toDouble() * mag[bin]; break }
                }
            }
            start += HOP
        } while (start + N / 2 < x.size)
        val sum = e.sum()
        if (sum > 0) for (b in e.indices) e[b] /= sum
        return e
    }

    fun whole(a: FloatArray, b: FloatArray, rate: Int): Double {
        val x = bands(a, rate); val y = bands(b, rate)
        return x.indices.sumOf { abs(x[it] - y[it]) }
    }

    fun path(a: FloatArray, b: FloatArray, rate: Int, segments: Int = 4): Double {
        val len = minOf(a.size, b.size) / segments
        return (0 until segments).sumOf { s ->
            whole(a.copyOfRange(s * len, (s + 1) * len), b.copyOfRange(s * len, (s + 1) * len), rate)
        } / segments
    }
}
```

Confirm `Fft.magnitudeSpectrum`'s signature and return size in `audio/src/main/kotlin/com/snipsnap/audio/Fft.kt:91` and adapt the call only if it differs.

- [ ] **Step 2: Write the separation tests**

```kotlin
package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The redefinition's claim, measured: paths are audible where windows were not
 * (docs/superpowers/specs/2026-09-29-glint-paths-design.md §1, §5). The bar is
 * relative to a same-run anchor - three reconstructions of this measure
 * disagreed up to 2x in absolute scale, so the anchor is what makes a number here
 * mean anything.
 */
class GlintSeparationTest {
    private val rate = Dsp.RATE

    /** A window swap at fixed k - the anchor the whole redefinition was measured against. */
    private fun fixed(window: (Double) -> Double, k: Double): FloatArray {
        val n = rate
        var phase = 0.0
        return FloatArray(n) { i ->
            val s = exp(-6.9078 * i / rate) * window(phase) * sin(2 * PI * k * phase)
            phase += 110.0 / rate
            if (phase >= 1.0) phase -= 1.0
            s.toFloat()
        }
    }

    private val anchor: Double by lazy {
        BandDistance.path(fixed({ 1 - it }, 8.0), fixed({ if (it < 0.5) 2 * it else 2 * (1 - it) }, 8.0), rate)
    }
    private val bar: Double get() = max(0.55, 2 * anchor)

    private fun render(voice: GlintVoice, m: Map<String, Float>) = Glint.render(voice, m).samples

    @Test
    fun `SWEEP up and SWEEP down are different voices`() {
        val m = mapOf("PEAK" to 0.45f, "DECAY" to 0.7f)
        val d = BandDistance.path(render(GlintVoice.SWEEP, m + ("BLOOM" to 1f)), render(GlintVoice.SWEEP, m + ("BLOOM" to 0f)), rate)
        assertTrue(d >= bar, "SWEEP down vs up: $d, bar $bar (anchor $anchor)")
    }

    @Test
    fun `STEP is not SWEEP`() {
        // Same pitch: SWEEP's root is A2, STEP's A3, so SWEEP plays TUNE 0.5.
        val d = BandDistance.path(
            render(GlintVoice.SWEEP, mapOf("TUNE" to 0.5f, "BLOOM" to 1f, "DECAY" to 0.9f)),
            render(GlintVoice.STEP, mapOf("TUNE" to 0f, "BLOOM" to 1f, "DECAY" to 0.9f)),
            rate,
        )
        assertTrue(d >= bar, "SWEEP vs STEP: $d, bar $bar (anchor $anchor)")
    }

    @Test
    fun `every neighbouring pair of vowels is a different sound`() {
        val peaks = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val names = listOf("OO", "OH", "AH", "EH", "EE")
        val renders = peaks.map { render(GlintVoice.VOWEL, mapOf("PEAK" to it, "BLOOM" to 0.5f, "DECAY" to 0.9f)) }
        for (i in 0 until renders.lastIndex) {
            val d = BandDistance.whole(renders[i], renders[i + 1], rate)
            assertTrue(d >= bar, "${names[i]} vs ${names[i + 1]}: $d, bar $bar (anchor $anchor)")
        }
    }
}
```

The vowel pairs are held vowels, so the whole-note form is the right one; the path form is for pairs that differ in motion.

- [ ] **Step 3: Run**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintSeparationTest' --console=plain`
Expected: exit 0. The anchor should land near 0.10–0.22. If any pair misses the bar, report every number and do not change the bar or the vowel table — a vowel pair that does not separate is a spec finding, not a test bug.

- [ ] **Step 4: Commit**

```bash
git add synth/src/test/kotlin/com/snipsnap/synth/BandDistance.kt synth/src/test/kotlin/com/snipsnap/synth/GlintSeparationTest.kt
git commit -m "Measure that GLINT's paths and vowels separate, against a same-run window anchor

(the two trailer lines from Global Constraints)"
```

---

### Task 5: BREATHE — the held render

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/GlintHeld.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/GlintBreatheTest.kt` (create)
- Modify: `docs/superpowers/specs/2026-09-29-glint-paths-design.md` §3 item 2 (the BRASS ruling in Global Constraints)

**Interfaces:**
- Consumes: `GlintPath.of`, `path.ratios`, `path.onsetSeconds`, `path.ladder`, `path.level2`, `path.bloom`, `Glint.windowAt`, `Glint.frequencyFor`, `Glint.defaults`, and Task 4's `BandDistance.whole` (test source set).
- Produces: `internal object GlintHeld` with `BREATHE_SECONDS`, `BREATHE_SHARE`, `BRASS_REST`, `data class BreathPlan(val cycles: Int, val loopFrames: Int, val f0: Double)`, `fun breathPlan(f0: Float): BreathPlan`, `class Held(val audio: FloatArray, val loopStart: Int)`, `fun render(voice: GlintVoice, macros: Map<String, Float>, cancelled: () -> Boolean = { false }): Held`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.log2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** docs/superpowers/specs/2026-09-29-glint-paths-design.md §3. */
class GlintBreatheTest {

    @Test
    fun `the breath plan is whole cycles in whole frames, in tune`() {
        for (hz in listOf(110f, 155.56f, 220f, 440f, 880f)) {
            val p = GlintHeld.breathPlan(hz)
            val cents = abs(1200 * log2(p.f0 / hz))
            assertTrue(cents < 0.01, "$hz Hz plays ${p.f0} Hz, $cents cents off")
            val seconds = p.loopFrames.toDouble() / Dsp.RATE
            assertTrue(abs(seconds - GlintHeld.BREATHE_SECONDS) <= 1.0 / hz, "$hz Hz loop is $seconds s")
        }
    }

    @Test
    fun `every voice's breath closes - the seam is below the organ's bar`() {
        for (voice in GlintVoice.entries) {
            for (bloom in listOf(0.2f, 0.5f, 0.8f)) {
                for (tune in listOf(0f, 1f)) {
                    val held = GlintHeld.render(voice, mapOf("BLOOM" to bloom, "TUNE" to tune))
                    val e = Keys.seamError(held.audio, held.loopStart)
                    assertTrue(e < Keys.MAX_SEAM_ERROR, "$voice BLOOM $bloom TUNE $tune seam $e")
                }
            }
        }
    }

    @Test
    fun `the loop is one breath long`() {
        for (voice in GlintVoice.entries) {
            val m = Glint.defaults(voice)
            val plan = GlintHeld.breathPlan(Glint.frequencyFor(voice, m.getValue("TUNE")))
            val held = GlintHeld.render(voice, m)
            assertEquals(plan.loopFrames, held.audio.size - held.loopStart, "$voice")
        }
    }

    @Test
    fun `a still pad at BLOOM 0 point 5 on the lowest zone is steady, seamless and audible`() {
        for (voice in GlintVoice.entries) {
            val held = GlintHeld.render(voice, mapOf("BLOOM" to 0.5f, "TUNE" to 0f))
            val loop = held.audio.copyOfRange(held.loopStart, held.audio.size)
            assertTrue(Keys.seamError(held.audio, held.loopStart) < Keys.MAX_SEAM_ERROR)
            val peak = loop.maxOf { abs(it) }
            assertTrue(peak > 0.3f, "$voice still pad is too quiet: $peak")
            // Steady: the first and last quarter of the breath carry the same energy.
            val q = loop.size / 4
            val a = loop.copyOfRange(0, q).sumOf { (it * it).toDouble() }
            val b = loop.copyOfRange(loop.size - q, loop.size).sumOf { (it * it).toDouble() }
            // 3%, not 1%: a quarter of the breath is not a whole number of cycles,
            // and a cycle's energy sits at its front, so the two quarters' partial
            // cycles alone differ by up to about 1% at 110 Hz.
            assertTrue(abs(a - b) / a < 0.03, "$voice still pad is not steady: $a vs $b")
        }
    }

    @Test
    fun `a breathing pad moves - its quarters differ in colour`() {
        val held = GlintHeld.render(GlintVoice.SWEEP, mapOf("BLOOM" to 1f))
        val loop = held.audio.copyOfRange(held.loopStart, held.audio.size)
        val q = loop.size / 4
        // The first quarter swings to the breath's brightest point, the third to its darkest.
        val d = BandDistance.whole(loop.copyOfRange(0, q), loop.copyOfRange(2 * q, 3 * q), Dsp.RATE)
        assertTrue(d > 0.1, "the breath does not move the colour: $d")
    }

    @Test
    fun `held STEP at its longest ladder stays bounded`() {
        // PEAK 0.54: kBase = 10, kBase * 4 = K_MAX - the most rungs STEP can have.
        val held = GlintHeld.render(GlintVoice.STEP, mapOf("BLOOM" to 1f, "PEAK" to 0.54f))
        assertTrue(held.audio.size < 12 * Dsp.RATE, "zone is ${held.audio.size / Dsp.RATE.toFloat()} s")
        assertTrue(Keys.seamError(held.audio, held.loopStart) < Keys.MAX_SEAM_ERROR)
    }

    @Test
    fun `held renders are deterministic`() {
        val a = GlintHeld.render(GlintVoice.VOWEL, mapOf("BLOOM" to 0.8f))
        val b = GlintHeld.render(GlintVoice.VOWEL, mapOf("BLOOM" to 0.8f))
        assertEquals(a.loopStart, b.loopStart)
        assertTrue(a.audio.contentEquals(b.audio))
    }

    @Test
    fun `a held render nobody wants any more stops`() {
        var asked = 0
        val t0 = System.nanoTime()
        assertFailsWith<java.util.concurrent.CancellationException> {
            GlintHeld.render(GlintVoice.STEP, mapOf("BLOOM" to 1f, "PEAK" to 0.1f)) { ++asked > 0 }
        }
        assertTrue(asked >= 1)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1_000)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintBreatheTest' --console=plain`
Expected: compilation failure (`GlintHeld` unresolved).

- [ ] **Step 3: Implement `GlintHeld.kt`**

```kotlin
package com.snipsnap.synth

import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * BREATHE - GLINT held (docs/superpowers/specs/2026-09-29-glint-paths-design.md §3).
 *
 * The onset plays the voice's own path onto PEAK; then the formant breathes,
 * one breath per loop, `x = BREATHE_SHARE · sin(2π t / loop)` on the same
 * [GlintPath] the one-shot uses. Phase distortion is exactly periodic, so N
 * whole cycles in L whole frames close with no seam search: from the first
 * wrap of the loop on, phase, k and level are all functions of `i mod L`.
 *
 * The render runs at `Dsp.OVERSAMPLE`x and decimates through a filter with
 * memory, so it synthesises three breaths and returns the onset, the first
 * breath and the second: by the second, the filter has forgotten the onset,
 * and the third exists only so the second's tail is filtered by real audio.
 */
internal object GlintHeld {
    const val BREATHE_SECONDS = 3f
    const val BREATHE_SHARE = 0.25f
    const val BRASS_REST = 0.5f
    private const val ATTACK_SECONDS = 0.002f
    private const val CANCEL_CHECK_SAMPLES = 4096

    data class BreathPlan(val cycles: Int, val loopFrames: Int, val f0: Double)

    /** N whole cycles in L whole frames at [Dsp.RATE], L as close to [BREATHE_SECONDS] as the note allows. */
    fun breathPlan(f0: Float): BreathPlan {
        val cycles = max(1, (BREATHE_SECONDS * f0).roundToInt())
        val loopFrames = (cycles * Dsp.RATE / f0.toDouble()).roundToInt()
        return BreathPlan(cycles, loopFrames, cycles.toDouble() * Dsp.RATE / loopFrames)
    }

    /** `audio[loopStart until audio.size]` is exactly one breath and repeats seamlessly. */
    class Held(val audio: FloatArray, val loopStart: Int)

    fun render(voice: GlintVoice, macros: Map<String, Float>, cancelled: () -> Boolean = { false }): Held {
        val m = Glint.defaults(voice) + macros
        val plan = breathPlan(Glint.frequencyFor(voice, m.getValue("TUNE")))
        val os = Dsp.OVERSAMPLE
        val rate = Dsp.RATE * os
        val loopOs = plan.loopFrames.toLong() * os
        val n = plan.cycles.toLong()
        val path = GlintPath.of(voice, m, plan.f0.toFloat())
        val depth = abs(path.bloom)

        // w0: the first phase wrap at or after the onset's end - from here on
        // everything is periodic. i0: the next frame boundary at the output
        // rate, where the file's own loop marker can sit.
        fun cycleOf(i: Long) = i * n / loopOs
        var w0 = ceil(path.onsetSeconds * rate).toLong().coerceAtLeast(1)
        while (cycleOf(w0) == cycleOf(w0 - 1)) w0++
        val i0 = (w0 + os - 1) / os * os
        val total = (i0 + 3 * loopOs).toInt()
        val loopSeconds = loopOs.toDouble() / rate

        val out = FloatArray(total)
        val k = FloatArray(2)
        var amp = 0f
        var x = 1f
        var rung = -1
        for (i in 0 until total) {
            if (i % CANCEL_CHECK_SAMPLES == 0 && (cancelled() || Thread.currentThread().isInterrupted)) {
                throw CancellationException("GLINT held render no longer wanted")
            }
            val attack = (i.toFloat() / rate / ATTACK_SECONDS).coerceAtMost(1f)
            if (i >= w0) {
                // The breath: a function of (i - i0) mod loop only.
                val b = sin(2.0 * PI * (i - i0) / rate / loopSeconds).toFloat()
                // BRASS's k follows its own level (spec §2.2): e = (amp - rest) / (1 - rest).
                x = if (voice == GlintVoice.BRASS) BREATHE_SHARE * depth * b else BREATHE_SHARE * b
                amp = if (voice == GlintVoice.BRASS) BRASS_REST * (1f + BREATHE_SHARE * depth * b) else 1f
                rung = -1
            } else {
                val t = i.toFloat() / rate
                val fall = Dsp.envAt(t, Glint.BLOOM_T60)
                x = fall
                amp = attack * (if (voice == GlintVoice.BRASS) BRASS_REST + (1f - BRASS_REST) * fall else 1f)
                rung = if (path.ladder != null) (t / Glint.STEP_SECONDS).toInt() else -1
            }
            val phase = ((i.toLong() * n) % loopOs).toDouble() / loopOs
            if (i == 0 || cycleOf(i.toLong()) != cycleOf(i.toLong() - 1)) path.ratios(x, rung, k)
            val w = Glint.windowAt(phase.toFloat())
            val second = if (voice == GlintVoice.VOWEL) amp else attack
            out[i] = amp * w * sin(2.0 * PI * k[0] * phase).toFloat() +
                second * path.level2 * w * sin(2.0 * PI * k[1] * phase).toFloat()
        }

        val down = Dsp.decimate(out, Dsp.RATE)
        val loopStart = (i0 / os).toInt() + plan.loopFrames
        val audio = down.copyOfRange(0, loopStart + plan.loopFrames)
        level(audio)
        return Held(audio, loopStart)
    }

    /**
     * One scalar gain for the whole file. `Dsp.levelTo` ends in a peak
     * limiter, and a limiter with memory would make the loop's first pass
     * differ from its repeats; a least-squares scalar fitted to what
     * `levelTo` would have done keeps the loudness target and the seam.
     */
    private fun level(audio: FloatArray) {
        val probe = audio.copyOf()
        Dsp.levelTo(probe, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        var num = 0.0
        var den = 0.0
        for (i in audio.indices) { num += probe[i].toDouble() * audio[i]; den += audio[i].toDouble() * audio[i] }
        if (den <= 0.0) return
        var gain = (num / den).toFloat()
        val peak = audio.maxOf { abs(it) }
        if (peak * gain > 0.99f) gain = 0.99f / peak
        for (i in audio.indices) audio[i] *= gain
    }
}
```

Notes for the implementer:
- `path.bloom` is Task 1's sign value `s`.
- VOWEL and BRASS both reach PEAK at `x = 0`; the onset's last value of `x` is `envAt(onset, BLOOM_T60) ≈ 0.001`, so the jump to the breath's `x = 0` happens at a wrap and is free.
- `second` is the BODY/F2 envelope: sustained after the attack on path voices, the amp envelope on VOWEL.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintBreatheTest' --console=plain`
Expected: exit 0. Record the largest measured seam error in `GlintHeld`'s KDoc ("measured: worst seam N across four voices × three BLOOMs × both ends of TUNE"). If a seam test fails, report its number — the design claims exact closure, so a failure is a real finding.

- [ ] **Step 5: Amend the spec's BRASS breath line**

In spec §3 item 2, replace "BRASS breathes its *amplitude* around `BRASS_REST` (±`BREATHE_SHARE · BRASS_REST`)" with "BRASS breathes its *amplitude* around `BRASS_REST` (±`BREATHE_SHARE · |s| · BRASS_REST`, so BLOOM 0.5 is still on BRASS too)".

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/GlintHeld.kt synth/src/test/kotlin/com/snipsnap/synth/GlintBreatheTest.kt docs/superpowers/specs/2026-09-29-glint-paths-design.md
git commit -m "Add BREATHE: a held GLINT note whose loop closes exactly

(the two trailer lines from Global Constraints)"
```

---

### Task 6: `Keys.glintPad` — zones for MAKE INSTRUMENT

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Keys.kt` (after `sirenPad`, ~line 305)
- Test: `synth/src/test/kotlin/com/snipsnap/synth/GlintHeldTest.kt` (create)

**Interfaces:**
- Consumes: `GlintHeld.render`, `Glint.rootMidi`, `Glint.TUNE_SEMITONES`, `Glint.defaults`, `Keys.requireSeam`.
- Produces: `Keys.glintPadMidis(voice: GlintVoice): List<Int>`, `Keys.glintPad(voice: GlintVoice, macros: Map<String, Float>, midi: Int, cancelled: () -> Boolean = { false }): KeyNote`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** GLINT held as a keys instrument (docs/superpowers/specs/2026-09-29-glint-paths-design.md §3). */
class GlintHeldTest {

    @Test
    fun `nine zones every minor third from the voice's own root`() {
        assertEquals((45..69 step 3).toList(), Keys.glintPadMidis(GlintVoice.SWEEP))
        assertEquals((57..81 step 3).toList(), Keys.glintPadMidis(GlintVoice.STEP))
    }

    @Test
    fun `a midi outside the zones is refused`() {
        assertFailsWith<IllegalArgumentException> { Keys.glintPad(GlintVoice.SWEEP, emptyMap(), 44) }
        assertFailsWith<IllegalArgumentException> { Keys.glintPad(GlintVoice.SWEEP, emptyMap(), 70) }
    }

    @Test
    fun `the marker sits at the loop and the loop closes`() {
        val note = Keys.glintPad(GlintVoice.VOWEL, mapOf("BLOOM" to 0.8f), 57)
        assertTrue(note.loopStartFrame > 0)
        assertTrue(Keys.seamError(note.snip.samples, note.loopStartFrame.toInt()) < Keys.MAX_SEAM_ERROR)
    }

    @Test
    fun `every zone lands on its MIDI pitch`() {
        for (midi in Keys.glintPadMidis(GlintVoice.SWEEP)) {
            val note = Keys.glintPad(GlintVoice.SWEEP, mapOf("BLOOM" to 0.5f), midi)
            val want = 440.0 * 2.0.pow((midi - 69) / 12.0)
            val start = note.loopStartFrame.toFloat() / note.snip.sampleRate
            val est = Pitch.detect(note.snip, fromSec = start, windowSec = 0.25f)
            requireNotNull(est) { "no pitch at MIDI $midi" }
            val cents = abs(1200 * log2(est.hz / want))
            assertTrue(cents < 5, "MIDI $midi plays ${est.hz} Hz, $cents cents off")
        }
    }

    @Test
    fun `the zone's TUNE comes from the key, not the panel`() {
        val a = Keys.glintPad(GlintVoice.BRASS, mapOf("TUNE" to 0f), 48)
        val b = Keys.glintPad(GlintVoice.BRASS, mapOf("TUNE" to 1f), 48)
        assertTrue(a.snip.samples.contentEquals(b.snip.samples))
    }

    @Test
    fun `a held zone nobody wants any more stops`() {
        assertFailsWith<java.util.concurrent.CancellationException> {
            Keys.glintPad(GlintVoice.STEP, mapOf("BLOOM" to 1f), 57) { true }
        }
    }
}
```

Check `Pitch.detect`'s real signature and `PitchEstimate`'s field name (`audio/…/Pitch.kt:37`) before running; `hz` is the expected field. If it octave-errors on a phase-distortion waveform, measure pitch the way `SirenHeldTest.kt:65` (`every zone's centre frequency lands on its MIDI pitch`) does.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintHeldTest' --console=plain`
Expected: compilation failure (`glintPadMidis`, `glintPad` unresolved).

- [ ] **Step 3: Implement in `Keys.kt`**

```kotlin
    // docs/superpowers/specs/2026-09-29-glint-paths-design.md §3 (BREATHE)

    /**
     * The nine GLINT pad zones: every minor third across the voice's own
     * two-octave TUNE range from [Glint.rootMidi] - per voice, since STEP
     * sits an octave above the rest.
     */
    fun glintPadMidis(voice: GlintVoice): List<Int> =
        (0..Glint.TUNE_SEMITONES step 3).map { Glint.rootMidi(voice) + it }

    /**
     * GLINT held - onset, one breath, and the same breath again with the
     * loop marker at its start ([GlintHeld]'s own shape). Unlike
     * [sirenPad]'s doubled loop, the onset and the first breath are
     * different audio from the loop, so [requireSeam] is a real check here:
     * the frames before the marker are the first breath's tail, and they
     * must match the file's own tail for the wrap to be silent. TUNE is
     * fixed by [midi].
     */
    fun glintPad(voice: GlintVoice, macros: Map<String, Float>, midi: Int, cancelled: () -> Boolean = { false }): KeyNote {
        val low = glintPadMidis(voice).first()
        require(midi - low in 0..Glint.TUNE_SEMITONES) {
            "GLINT $voice pads are MIDI $low..${low + Glint.TUNE_SEMITONES}, got $midi"
        }
        val defaults = Glint.defaults(voice)
        val tune = (midi - low) / Glint.TUNE_SEMITONES.toFloat()
        val m = defaults + macros.filterKeys { it in defaults } + ("TUNE" to tune)
        val held = GlintHeld.render(voice, m, cancelled)
        requireSeam("GLINT $voice at MIDI $midi", held.audio, held.loopStart)
        return KeyNote(Snip(held.audio, channels = 1, sampleRate = RATE), loopStartFrame = held.loopStart.toLong())
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :synth:test --tests 'com.snipsnap.synth.GlintHeldTest' --console=plain`
Expected: exit 0.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Keys.kt synth/src/test/kotlin/com/snipsnap/synth/GlintHeldTest.kt
git commit -m "Add Keys.glintPad: GLINT's held note as nine keygroup zones

(the two trailer lines from Global Constraints)"
```

---

### Task 7: MAKE INSTRUMENT for GLINT

**Files:**
- Create: `shell/src/main/kotlin/com/snipsnap/shell/GlintPadMaker.kt`
- Create: `shell/src/test/kotlin/com/snipsnap/shell/GlintPadMakerTest.kt`
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt` (:619-623, :1039, :1150, :1471-1500)

**Interfaces:**
- Consumes: `Keys.glintPadMidis(voice)`, `Keys.glintPad(...)`, `GlintVoice`.
- Produces: `object GlintPadMaker` with `RELEASE_MIN_SECONDS`, `RELEASE_MAX_SECONDS`, `RELEASE: Knob`, `secondsLabel`, `data class Spec(voice: GlintVoice, macros, releaseSeconds)`, `spec(voice, macros, releaseFraction)`, `zoneMidis(spec)`, `renderZone(spec, midi, cancelled)`, `assemble(name, spec, notes)`, `export(name, spec, notes, destRoot, overwrite = false)`, `preview(spec, cancelled)`.

- [ ] **Step 1: Write `GlintPadMaker.kt`**

Copy `shell/src/main/kotlin/com/snipsnap/shell/SirenPadMaker.kt` to `GlintPadMaker.kt` and make exactly these changes:
- `object SirenPadMaker` → `object GlintPadMaker`; import `com.snipsnap.synth.GlintVoice` instead of `SirenVoice`.
- `Spec.voice: SirenVoice` → `GlintVoice`; `spec(voice: SirenVoice, …)` → `GlintVoice`.
- `zoneMidis(spec) = Keys.glintPadMidis(spec.voice)`; KDoc: "Nine zones from the voice's own root ([Keys.glintPadMidis]) - STEP sits an octave above the rest."
- `renderZone(...) = Keys.glintPad(spec.voice, spec.macros, midi, cancelled)`.
- Object KDoc: "GLINT, held - a GLINT patch as a keys instrument (docs/superpowers/specs/2026-09-29-glint-paths-design.md §3). [SirenPadMaker]'s shape: RELEASE alone; the onset and the breath come from the patch."
- `preview` KDoc: "The middle zone's own render: onset, one breath, and the breath the key holds on."
- `assemble` and `export` bodies unchanged.

- [ ] **Step 2: Write `GlintPadMakerTest.kt`**

Copy `shell/src/test/kotlin/com/snipsnap/shell/SirenPadMakerTest.kt` and adapt each of its seven tests to GLINT: `SirenPadMaker` → `GlintPadMaker`, `SirenVoice.<X>` → `GlintVoice.SWEEP`, `Keys.sirenPadMidis()` → `Keys.glintPadMidis(GlintVoice.SWEEP)`. Rename `preview is the middle zone's own two loop passes, no separate head` to `preview is the middle zone's own render` and assert `preview(spec).samples.contentEquals(renderZone(spec, midis[4]).snip.samples)`. Add one test:

```kotlin
    @Test
    fun `STEP's zones sit an octave above SWEEP's`() {
        val sweep = GlintPadMaker.zoneMidis(GlintPadMaker.spec(GlintVoice.SWEEP, emptyMap(), 0.5f))
        val step = GlintPadMaker.zoneMidis(GlintPadMaker.spec(GlintVoice.STEP, emptyMap(), 0.5f))
        assertEquals(sweep.map { it + 12 }, step)
    }
```

Keep SIREN's assertions about the keygroup layout, loop marker, both-generation export and the phone sidecar exactly as written — they are the contract GLINT must meet too.

- [ ] **Step 3: Run the shell tests**

Run: `./gradlew :shell:test --tests 'com.snipsnap.shell.GlintPadMakerTest' --console=plain`
Expected: exit 0, no `<failure`/`<error` in `shell/build/test-results/test/TEST-com.snipsnap.shell.GlintPadMakerTest.xml`.

- [ ] **Step 4: Wire SynthScreen**

In `app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt`:
- Import `com.snipsnap.shell.GlintPadMaker`.
- `heldSpec()` (:619): add `Engine.GLINT -> HeldSpec.Glint(GlintPadMaker.spec(voice as GlintVoice, macros, holdRelease))`. Update the comment above it from "two engines" to "three engines".
- MAKE INSTRUMENT button gate (:1039) and sheet host gate (:1150): add `|| engine == Engine.GLINT`. Leave the DRONE TO LOOP gate (:1052) alone.
- `HeldSpec` (:1471): add

```kotlin
    class Glint(private val spec: GlintPadMaker.Spec) : HeldSpec {
        override val zoneMidis get() = GlintPadMaker.zoneMidis(spec)
        override val hasAttack get() = false
        override val knobLabel get() = "RELEASE ${GlintPadMaker.secondsLabel(spec.releaseSeconds)}"
        override fun renderZone(midi: Int, cancelled: () -> Boolean) = GlintPadMaker.renderZone(spec, midi, cancelled)
        override fun preview(cancelled: () -> Boolean) = GlintPadMaker.preview(spec, cancelled)
        override fun export(name: String, notes: List<KeyNote>, destRoot: File) = GlintPadMaker.export(name, spec, notes, destRoot)
    }
```

  and update the interface's KDoc from "two engines" to "three engines".

- [ ] **Step 5: Compile the app**

Run: `./gradlew :app:compileDebugKotlin --console=plain`
Expected: exit 0.

- [ ] **Step 6: Commit**

```bash
git add shell/ app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt
git commit -m "Offer MAKE INSTRUMENT for GLINT: nine breathing zones, RELEASE on the keygroup

(the two trailer lines from Global Constraints)"
```

---

### Task 8: The audition, and the whole suite

**Files:**
- Create: `synth/src/test/kotlin/com/snipsnap/synth/GlintPathsAuditionGenerator.kt`
- Modify: `synth/build.gradle.kts` (one JavaExec task)

**Interfaces:**
- Consumes: `Glint.render`, `Keys.glintPad`, `WavWriter.write(file, snip)`.
- Produces: `./gradlew :synth:generateGlintPathsAudition` writing WAVs to `testkit/glint-paths-audition/`.

- [ ] **Step 1: Write the generator**

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File

/**
 * The GLINT paths audition (docs/superpowers/specs/2026-09-29-glint-paths-design.md §5):
 * one WAV per gesture and one held pad per voice, for Josh to hear on his phone
 * before the branch merges. Not a test - run with `generateGlintPathsAudition`.
 */
object GlintPathsAuditionGenerator {
    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.firstOrNull() ?: "testkit/glint-paths-audition").apply { mkdirs() }
        val clips = linkedMapOf(
            "01-sweep-down" to Glint.render(GlintVoice.SWEEP, mapOf("BLOOM" to 1f, "DECAY" to 0.8f)),
            "02-sweep-up" to Glint.render(GlintVoice.SWEEP, mapOf("BLOOM" to 0f, "DECAY" to 0.8f)),
            "03-step-down" to Glint.render(GlintVoice.STEP, mapOf("BLOOM" to 1f, "DECAY" to 1f)),
            "04-step-up" to Glint.render(GlintVoice.STEP, mapOf("BLOOM" to 0f, "DECAY" to 1f)),
            "05-brass" to Glint.render(GlintVoice.BRASS, mapOf("BLOOM" to 1f, "DECAY" to 0.8f)),
            "06-vowel-ee-to-ah" to Glint.render(GlintVoice.VOWEL, mapOf("PEAK" to 0.5f, "BLOOM" to 1f, "DECAY" to 0.9f)),
            "07-vowel-oo-to-ah" to Glint.render(GlintVoice.VOWEL, mapOf("PEAK" to 0.5f, "BLOOM" to 0f, "DECAY" to 0.9f)),
        )
        for ((name, snip) in clips) WavWriter.write(File(dir, "$name.wav"), snip)
        for ((i, voice) in GlintVoice.entries.withIndex()) {
            val midi = Keys.glintPadMidis(voice)[3]
            val note = Keys.glintPad(voice, mapOf("BLOOM" to 0.85f), midi)
            WavWriter.write(File(dir, "%02d-held-%s.wav".format(8 + i, voice.name.lowercase())), heldFor(note, 10f))
        }
        println("wrote ${clips.size + GlintVoice.entries.size} clips to ${dir.absolutePath}")
    }

    /** Plays the note as a held key would: to the end, then the loop again until [seconds]. */
    private fun heldFor(note: KeyNote, seconds: Float): Snip {
        val s = note.snip.samples
        val start = note.loopStartFrame.toInt()
        val want = (seconds * note.snip.sampleRate).toInt()
        val out = FloatArray(want) { i -> if (i < s.size) s[i] else s[start + (i - s.size) % (s.size - start)] }
        return Snip(out, channels = 1, sampleRate = note.snip.sampleRate)
    }
}
```

- [ ] **Step 2: Register the Gradle task**

In `synth/build.gradle.kts`, where the deleted `generateGlint*Audition` tasks were:

```kotlin
/** Render the GLINT paths audition (WAVs only) under testkit/glint-paths-audition/. See GlintPathsAuditionGenerator. */
tasks.register<JavaExec>("generateGlintPathsAudition") {
    group = "distribution"
    description = "Render the GLINT paths audition clips under testkit/glint-paths-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.GlintPathsAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/glint-paths-audition")
}
```

Confirm `testkit/glint-paths-audition` is covered by the ignore file (the old audition directories are, `.gitignore` line ~49); add a line if not.

- [ ] **Step 3: Run the generator**

Run: `./gradlew :synth:generateGlintPathsAudition --console=plain`
Expected: exit 0 and "wrote 11 clips".

- [ ] **Step 4: Run the whole synth and shell suites**

Run, one at a time:
```
./gradlew :synth:test --console=plain
./gradlew :shell:test --console=plain
```
Expected: exit 0 each; no `<failure`/`<error` in any XML under `synth/build/test-results/test/` or `shell/build/test-results/test/`.

- [ ] **Step 5: Commit**

```bash
git add synth/src/test/kotlin/com/snipsnap/synth/GlintPathsAuditionGenerator.kt synth/build.gradle.kts .gitignore
git commit -m "Add the GLINT paths audition generator

(the two trailer lines from Global Constraints)"
```

The controller sends the 11 WAVs to Josh's phone. The branch does not merge until he rules on them.
