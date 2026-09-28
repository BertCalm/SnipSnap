# GLINT Depth D2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add GLINT's three remaining voices — CICADA, RATCHET and PLATE — each one a *mechanism* rather than a window shape, taking the engine from three voices to six.

**Architecture:** `GlintVoice` grows to six entries. The render loop in `Glint.synthesize` gains three branches, each supplying a different *kind* of formant motion: CICADA re-clocks the carrier inside every cycle, RATCHET steps the formant between fixed integer harmonics, PLATE couples the formant to the amplitude envelope. REED, BOTTLE and KAZOO are untouched. BODY, the second formant added in D1, applies to all six.

**Tech Stack:** Kotlin/JVM 17, Gradle 8.14.3, `kotlin.test` with backtick test names. Pure-JVM `:synth` module — no Android SDK needed.

**Spec:** `docs/superpowers/specs/2026-09-26-glint-depth-design.md` (the depth design; D2's scope is its §1 voice table, its BLOOM row, and its Testing section).

---

## Global Constraints

- **The window must reach exactly zero at the end of every cycle it governs.** This is the engine's founding property (`Glint.kt` class doc). Every new mechanism must preserve it. Nothing may be smoothed, offset, or mean-removed in a way that breaks it.
- **Six macros on every voice.** `macrosFor` stays uniform: TUNE, PEAK, FOLLOW, BODY, BLOOM, DECAY. The spec's registration table says "five for RATCHET and PLATE"; that contradicts the spec's own macro table, which defines BLOOM's meaning for both, and it was settled in favour of six. Do not add a per-voice branch to `macrosFor`.
- **BLOOM means one thing — how far the formant travels — and each voice supplies how.** REED/BOTTLE/KAZOO/CICADA: a sweep at the fixed `BLOOM_T60 = 0.45f`. RATCHET: how far up the harmonic ladder the steps climb. PLATE: the depth of the loudness coupling.
- **U6 oversample contract:** `synthesize` renders at `Dsp.RATE * Dsp.OVERSAMPLE` and `render` decimates. Never hardcode 44100; always use the `rate` parameter passed in.
- **Never hardcode a sample rate** anywhere, in engine or test.
- **No presets.** D3 authors the roster; D2 ships none.
- **Every new constant gets a named `const val` with a KDoc stating its value's justification.** No bare numbers in the render loop.
- Commit messages: plain declarative prose, no `feat:`/`fix:` prefixes, body explains why. Never put a model identifier in a commit message or code comment.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt` | the engine | three enum entries, three window/root entries, three mechanism branches, new constants |
| `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt` | the engine's tests | existing per-voice tests re-pointed at six voices; three mechanism tests added |
| `synth/src/test/kotlin/com/snipsnap/synth/GlintD2AuditionGenerator.kt` | the D2 listening set | new |
| `synth/src/test/resources/audition/glint-d2-audition.html` | the D2 listening page | new |
| `synth/build.gradle.kts` | build | one new `generateGlintD2Audition` task |
| `.gitignore` | build | one new audition output ignore |
| `docs/SYNTH_ROADMAP.md` | the roadmap | amend the S10 row |
| `docs/superpowers/specs/2026-09-26-glint-depth-design.md` | the spec | correct the "five macros" line |

---

### Task 1: Six voices, three of them still plain

Add the enum entries and every piece of per-voice wiring, so the engine compiles and renders six voices — but with CICADA, RATCHET and PLATE each behaving as an ordinary single-carrier voice on its own window. The mechanisms arrive in Tasks 2–4. This separates "the enum grew" (which touches many places) from "the mechanism works" (which touches one loop), so a failure in either is unambiguous.

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `GlintVoice.CICADA`, `GlintVoice.RATCHET`, `GlintVoice.PLATE`; `Glint.windowAt` and `Glint.rootHz` answer for all six.

- [ ] **Step 1: Find every place the voice list is enumerated, and write down what you found**

Run each of these and record the result in your report — this is a deliverable, not a warm-up. The spec's own registration table says it is **not exhaustive**, and Phase 1 was bitten twice by trusting it (`Velocity.macroSpecsFor`'s sealed `when` stopped `:synth` compiling; `shell`'s `UserPresetsTest` roster was missed).

```bash
grep -rn 'GlintVoice' synth/src shell/src cli/src app/src kit/src mpc3/src loop/src
grep -rn 'GlintVoice\.\(REED\|BOTTLE\|KAZOO\)' synth/src shell/src cli/src app/src
grep -rn 'is GlintPatch' synth/src shell/src cli/src app/src
```

Expected findings, for comparison — if yours differ, yours are right and this list is stale:
- `GlintPatch.kt` — decodes by name from `GlintVoice.entries`; no change needed.
- `Velocity.kt:214` — `is GlintPatch -> Glint.macrosFor(patch.voice)`; delegates, no per-voice branch, no change needed.
- `SynthScreen.kt` — uses `GlintVoice.entries` throughout and `drumClass` is a blanket `get() = DrumClass.TONAL` getter, not an exhaustive `when`; no change needed. **`:app` is not in the build without an Android SDK, so this will not fail a local compile — read it, do not rely on the compiler.**
- `PadRecipeTest.kt:33`, `DeterminismTest.kt:88`, `VelocityGrooveShuffleTest.kt:139` — each names a specific voice (BOTTLE, BOTTLE, REED) as a fixture; all three still exist, so no change needed.

Report any `when` over `GlintVoice` with no `else` branch — that is a compile break waiting to happen.

- [ ] **Step 2: Write the failing test**

Add to `GlintTest.kt`:

```kotlin
@Test
fun `every voice has a window that reaches zero at the cycle end`() {
    for (voice in GlintVoice.entries) {
        val end = Glint.windowAt(voice, 1f)
        assertTrue(
            kotlin.math.abs(end) < 1e-6f,
            "$voice's window is $end at phase 1, not zero — the wrap would click",
        )
        val open = Glint.windowAt(voice, 0.05f)
        assertTrue(open > 0.01f, "$voice's window is silent at phase 0.05 ($open)")
    }
}

@Test
fun `every voice renders at its own root and is not silent`() {
    for (voice in GlintVoice.entries) {
        val root = Glint.rootHz(voice)
        assertTrue(root > 20f && root < 2000f, "$voice root $root Hz is out of range")
        val snip = Glint.render(voice)
        assertTrue(snip.frameCount > 1000, "$voice rendered ${snip.frameCount} frames")
        assertTrue(snip.peak() > 0.1f, "$voice rendered near-silence, peak ${snip.peak()}")
    }
}
```

- [ ] **Step 3: Run it and watch it fail**

```bash
./gradlew --no-daemon :synth:test --tests '*GlintTest*'
```
Expected: compile failure — `GlintVoice` has only three entries, so `entries` cannot produce the new ones and nothing references them yet. That is the correct first failure.

- [ ] **Step 4: Grow the enum**

In `Glint.kt`:

```kotlin
enum class GlintVoice { REED, BOTTLE, KAZOO, CICADA, RATCHET, PLATE }
```

- [ ] **Step 5: Give the three new voices windows**

Extend `windowAt`. Each new voice reuses an existing window shape, per the spec's voice table — CICADA the triangle, RATCHET the trapezoid, PLATE the saw:

```kotlin
fun windowAt(voice: GlintVoice, phase: Float): Float {
    val p = phase.coerceIn(0f, 1f)
    return when (voice) {
        GlintVoice.REED, GlintVoice.PLATE -> 1f - p
        GlintVoice.BOTTLE, GlintVoice.CICADA -> if (p < 0.5f) p * 2f else (1f - p) * 2f
        GlintVoice.KAZOO, GlintVoice.RATCHET -> if (p < KAZOO_FLAT) 1f else (1f - p) / (1f - KAZOO_FLAT)
    }
}
```

Add a KDoc line recording *why* each pairing, taken from the spec: CICADA takes the triangle because it is the softest base, so the nesting supplies the edge rather than doubling one already there; RATCHET takes the trapezoid because the toy character is the point; PLATE takes the saw because a struck plate is brightest at the strike.

- [ ] **Step 6: Give them roots**

```kotlin
fun rootHz(voice: GlintVoice): Float = when (voice) {
    GlintVoice.REED -> 110f      // A2
    GlintVoice.BOTTLE -> 220f    // A3
    GlintVoice.KAZOO -> 220f     // A3
    GlintVoice.CICADA -> 220f    // A3 — shares BOTTLE's register with its window
    GlintVoice.RATCHET -> 220f   // A3 — shares KAZOO's register with its window
    GlintVoice.PLATE -> 110f     // A2 — a struck plate sits low, like REED
}
```

- [ ] **Step 7: Run the tests**

```bash
./gradlew --no-daemon :synth:test --tests '*GlintTest*'
```
Expected: PASS. Gate on the exit code, not on grepping output for FAILED.

Every pre-existing test that loops `GlintVoice.entries` now covers six voices instead of three. **If any of them fail, that is a real finding about the three new voices, not a reason to narrow the loop.** Report the failure and its numbers rather than scoping the test back to three.

- [ ] **Step 8: Full module regression**

```bash
./gradlew --no-daemon :synth:test
```
Expected: PASS, exit 0. If a sibling suite breaks, a voice list somewhere was not found in Step 1 — go back and find it.

- [ ] **Step 9: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Glint.kt synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt
git commit -m "Grow GLINT to six voices, three of them still plain"
```

---

### Task 2: CICADA — the carrier re-clocks inside every cycle

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt`

**Interfaces:**
- Consumes: `GlintVoice.CICADA` and its triangle window from Task 1.
- Produces: `Glint.CICADA_SUBCYCLES`, and a `subPhase` concept the later tasks do *not* use.

**The mechanism, and the one thing that makes it work.** An ordinary voice runs one window and one carrier burst per cycle of `f0`. CICADA runs `N` of them, nested inside that same cycle:

```
subPhase = frac(phase * N)
w        = windowAt(CICADA, subPhase)     // the window, applied per sub-cycle
burst    = w * sin(2π * k * subPhase)
```

Applying the window to `subPhase` rather than to `phase` is the whole design, and it satisfies both of the engine's hard invariants at once:

- **No click.** The triangle reaches zero at the end of *each sub-cycle*, so the carrier's restart at every sub-boundary lands on silence — exactly as the ordinary voices' restart lands on silence at the cycle wrap. Window it on `phase` instead and it clicks `N` times per cycle.
- **Periodicity at `f0`.** `N` is an integer, so the sub-cycles divide the cycle a whole number of times and the whole pattern still repeats at `f0`. The pitch does not move. If CICADA cannot hold the 0.98 periodicity bar, its sub-division is wrong — not the test.

**BODY must use `subPhase` too.** The second formant is `bodyMix * w * sin(2π * k2 * subPhase)`. If the burst re-clocks while the body does not, the body is non-zero at every sub-boundary and clicks there on its own, even though the burst is clean.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `CICADA stays periodic at f0 despite re-clocking inside the cycle`() {
    // The sub-cycles divide the cycle an integer number of times, so the
    // whole pattern still repeats at f0 and the pitch does not move. This
    // is the test the spec names as CICADA's risk.
    for (bloom in listOf(0f, 1f)) {
        val snip = Glint.render(GlintVoice.CICADA, mapOf("BLOOM" to bloom))
        val f0 = Glint.frequencyFor(GlintVoice.CICADA, 0.5f)
        val corr = periodCorrelation(snip, f0, fromSec = Glint.BLOOM_T60 * (7f / 9f))
        assertTrue(corr > 0.98f, "CICADA at BLOOM $bloom: period correlation $corr")
    }
}

@Test
fun `CICADA does not click at its inner restarts`() {
    // Probed at fractional part 0.25 of a sub-cycle, where a discontinuity
    // would be maximal — NOT at 0 or 0.5, where sin(2*PI*k*x) is zero for
    // integer k and a real break would be nulled by the probe itself.
    val snip = Glint.render(GlintVoice.CICADA, mapOf("BLOOM" to 0f))
    val worst = worstAdjacentJump(snip, from = 0.01f, to = 0.20f)
    val reed = worstAdjacentJump(
        Glint.render(GlintVoice.REED, mapOf("BLOOM" to 0f)), from = 0.01f, to = 0.20f,
    )
    assertTrue(
        worst < reed * 3f,
        "CICADA's worst sample-to-sample jump $worst is more than 3x REED's $reed — the inner restarts are clicking",
    )
}

@Test
fun `CICADA puts energy at its sub-cycle rate that REED does not`() {
    // The lattice is audible as energy at N * f0. This is what makes CICADA
    // a different voice rather than a differently-windowed one.
    val f0 = Glint.frequencyFor(GlintVoice.CICADA, 0.5f)
    val lattice = Glint.CICADA_SUBCYCLES * f0
    val cicada = Glint.render(GlintVoice.CICADA, mapOf("BLOOM" to 0f, "BODY" to 0f))
    val bottle = Glint.render(GlintVoice.BOTTLE, mapOf("BLOOM" to 0f, "BODY" to 0f))
    val c = energyAt(cicada.samples, lattice, cicada.sampleRate)
    val b = energyAt(bottle.samples, lattice, bottle.sampleRate)
    assertTrue(c > b * 3f, "CICADA has $c at the lattice rate, BOTTLE (same window) has $b")
}
```

If `worstAdjacentJump` does not already exist in `GlintTest.kt`, add it beside the other helpers:

```kotlin
/** The largest absolute difference between adjacent samples in a time window. */
private fun worstAdjacentJump(snip: Snip, from: Float, to: Float): Float {
    val a = (from * snip.sampleRate).toInt().coerceAtLeast(1)
    val b = (to * snip.sampleRate).toInt().coerceAtMost(snip.samples.size)
    var worst = 0f
    for (i in a until b) worst = maxOf(worst, kotlin.math.abs(snip.samples[i] - snip.samples[i - 1]))
    return worst
}
```

- [ ] **Step 2: Run them and watch them fail**

```bash
./gradlew --no-daemon :synth:test --tests '*GlintTest*'
```
Expected: compile failure on `Glint.CICADA_SUBCYCLES`, then — once the constant exists but the mechanism does not — the lattice-energy test fails because CICADA renders identically to BOTTLE.

**Before writing the mechanism, record the lattice test's control value.** Run it with the constant added but the mechanism absent and note what `c` and `b` actually are. If they are equal, the `3f` bar is meaningful. If `c` already exceeds `b * 3f` with no mechanism, the test is measuring something else and must be fixed before it can guard anything.

- [ ] **Step 3: Add the constant**

```kotlin
/**
 * How many times CICADA's carrier re-clocks inside one cycle of `f0`.
 * Integer by necessity: the sub-cycles must divide the cycle a whole
 * number of times or the output stops repeating at `f0` and the pitch
 * moves, which is the one thing this engine promises not to do.
 *
 * Four gives a lattice dense enough to read as its own texture at the
 * voice's A3 root (four edges per cycle, 880 edges a second) without the
 * sub-rate climbing so far that the burst's own harmonics fold. A value
 * to revisit at the audition, not a derived quantity.
 */
const val CICADA_SUBCYCLES = 4
```

- [ ] **Step 4: Branch the render loop**

Inside `synthesize`'s `for` loop, replace the single `w`/`burst`/`body` computation with a carrier phase that CICADA redefines:

```kotlin
// CICADA re-clocks the carrier inside every cycle: N nested copies of
// the window and burst, instead of one. The window is applied to the
// SUB-phase, which is what makes each inner restart land on silence —
// window it on `phase` and it clicks N times a cycle instead of never.
// N is an integer, so the whole pattern still repeats at f0.
val carrier = if (voice == GlintVoice.CICADA) {
    val scaled = phase * CICADA_SUBCYCLES
    scaled - floor(scaled)
} else {
    phase
}
val w = windowAt(voice, carrier)
val burst = w * sin(2.0 * PI * k * carrier).toFloat()
// The body rides the same carrier, for the same reason: a second formant
// still running on `phase` would be non-zero at every sub-boundary and
// would click there even though the burst is clean.
val body = bodyMix * w * sin(2.0 * PI * k2 * carrier).toFloat()
```

Add `import kotlin.math.floor` if it is not already present.

- [ ] **Step 5: Run the tests**

```bash
./gradlew --no-daemon :synth:test --tests '*GlintTest*'
```
Expected: PASS. Gate on exit code.

- [ ] **Step 6: Mutation-verify the no-click test**

Temporarily change `windowAt(voice, carrier)` to `windowAt(voice, phase)` for CICADA only — the exact mistake the design warns against. Re-run. The no-click test **must** fail. Record both numbers (the broken jump and the passing one) in the test's comment, then revert.

A test you did not watch fail is not a test. If it passes with the window on `phase`, it is not measuring clicks and must be fixed before this task is done.

- [ ] **Step 7: Full module regression, then commit**

```bash
./gradlew --no-daemon :synth:test
git add -A synth/src
git commit -m "CICADA: a carrier that re-clocks inside every cycle"
```

---

### Task 3: RATCHET — the formant steps, and only at the wrap

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt`

**Interfaces:**
- Consumes: `GlintVoice.RATCHET` and its trapezoid window from Task 1; `Glint.snapRatio` for landing on integers.
- Produces: `Glint.RATCHET_STEP_SECONDS`.

**The mechanism.** The formant climbs a ladder of integer harmonics in hard jumps — no glide, no interpolation — one step roughly every 150 ms. BLOOM sets how far up the ladder it climbs. When it reaches the top it holds there for the rest of the note.

**The trap, and it is not obvious.** RATCHET's trapezoid window is `1` at phase 0 — it reaches zero at the *end* of a cycle, not the start. So changing `k` partway through a cycle changes `sin(2π·k·φ)` discontinuously while the window is wide open. That clicks, once per step, and it would sound like a fault rather than a mechanism.

So the step index is computed from time, but the new `k` is **only applied when `phase` wraps** — the one instant where the window has just reached zero and any `k` is free. This is the same property the whole engine rests on, showing up a third time.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `RATCHET's formant is piecewise constant, not a ramp`() {
    // The spec's requirement: measure the centroid in windows and assert
    // the plateaus. A glide would show a different centroid in every
    // window; steps show runs of equal ones with jumps between.
    val snip = Glint.render(GlintVoice.RATCHET, mapOf("BLOOM" to 1f, "DECAY" to 0.9f, "BODY" to 0f))
    val step = Glint.RATCHET_STEP_SECONDS
    // Two probes inside one step must agree; probes either side of a step
    // boundary must not.
    val early = spectralCentroid(slice(snip, step * 0.25f, step * 0.45f))
    val late = spectralCentroid(slice(snip, step * 0.55f, step * 0.75f))
    val next = spectralCentroid(slice(snip, step * 1.25f, step * 1.45f))
    assertTrue(
        kotlin.math.abs(late - early) < kotlin.math.abs(next - early) * 0.25f,
        "RATCHET: within-step centroid moved $early -> $late, across-step moved $early -> $next — that is a ramp, not a staircase",
    )
}

@Test
fun `RATCHET's steps land on integer harmonics`() {
    // "Steps between fixed harmonics, never glides." Every step's ratio
    // must be a whole number, or the ladder is not a ladder.
    for (bloom in listOf(0.25f, 0.5f, 1f)) {
        val ks = Glint.ratchetLadder(
            kBase = Glint.ratioFor(GlintVoice.RATCHET, 0.5f, 0.45f, 0.8f),
            bloomAmount = Dsp.lin(bloom, 0f, Glint.BLOOM_MAX),
        )
        assertTrue(ks.isNotEmpty(), "RATCHET ladder is empty at BLOOM $bloom")
        for (k in ks) {
            assertTrue(k == Math.round(k).toFloat(), "RATCHET ladder rung $k is not an integer")
        }
        assertTrue(ks.toSet().size == ks.size, "RATCHET ladder repeats a rung: ${ks.toList()}")
    }
}

@Test
fun `RATCHET climbs further as BLOOM opens`() {
    val kBase = Glint.ratioFor(GlintVoice.RATCHET, 0.5f, 0.45f, 0.8f)
    val small = Glint.ratchetLadder(kBase, Dsp.lin(0.25f, 0f, Glint.BLOOM_MAX)).last()
    val large = Glint.ratchetLadder(kBase, Dsp.lin(1f, 0f, Glint.BLOOM_MAX)).last()
    assertTrue(large > small, "RATCHET's ladder top did not rise with BLOOM: $small -> $large")
}

@Test
fun `RATCHET does not click when it steps`() {
    // The step is quantised to the phase wrap, where the window has just
    // reached zero. Compare against KAZOO, which shares the window and
    // never steps.
    val ratchet = Glint.render(GlintVoice.RATCHET, mapOf("BLOOM" to 1f, "DECAY" to 0.9f))
    val kazoo = Glint.render(GlintVoice.KAZOO, mapOf("BLOOM" to 1f, "DECAY" to 0.9f))
    val r = worstAdjacentJump(ratchet, from = 0.01f, to = 0.60f)
    val k = worstAdjacentJump(kazoo, from = 0.01f, to = 0.60f)
    assertTrue(r < k * 3f, "RATCHET's worst jump $r vs KAZOO's $k — the steps are clicking")
}
```

If `spectralCentroid` does not already exist in `GlintTest.kt`, check whether the repo has one (`FeatureExtractor` may expose it) before writing a new one — the repo's recurring defect is one quantity computed in two places.

- [ ] **Step 2: Run them and watch them fail**

Expected: compile failure on `Glint.ratchetLadder` and `Glint.RATCHET_STEP_SECONDS`.

**Before writing the mechanism, establish the centroid metric's range.** Render RATCHET with the mechanism absent and measure `early`, `late` and `next`. If `next - early` is already near zero with no stepping, the `0.25f` ratio bar is meaningless and the test must be redesigned before it can guard anything. Record the numbers.

- [ ] **Step 3: Add the constant and the ladder**

```kotlin
/**
 * How long RATCHET holds each rung before jumping to the next. The spec
 * asks for "a hard jump every ~150 ms"; at the shortest DECAY (0.12 s
 * t60) that is under one step, so short notes render a single rung and
 * the ladder only reads on longer ones — which is correct for a tuning
 * dial being turned.
 */
const val RATCHET_STEP_SECONDS = 0.15f

/**
 * The integer harmonics RATCHET steps through, bottom rung first.
 *
 * The ladder starts at the snapped base ratio and climbs by whole
 * harmonics to `kBase * (1 + bloomAmount)`, BLOOM's extent. Every rung is
 * an integer because "steps between fixed harmonics" is the mechanism —
 * a fractional rung would be a glide that happens to be quantised in
 * time, which is a different and much duller thing.
 *
 * Always at least one rung, so a note shorter than one step still has a
 * ratio to render.
 */
fun ratchetLadder(kBase: Float, bloomAmount: Float): FloatArray {
    val bottom = Math.round(kBase.coerceIn(K_MIN, K_MAX)).toFloat()
    val top = (kBase * (1f + bloomAmount)).coerceIn(K_MIN, K_MAX)
    val rungs = ArrayList<Float>()
    var k = bottom
    while (k <= top && rungs.size < K_MAX.toInt()) {
        rungs.add(k)
        k += 1f
    }
    if (rungs.isEmpty()) rungs.add(bottom)
    return rungs.toFloatArray()
}
```

- [ ] **Step 4: Branch the render loop, stepping only at the wrap**

RATCHET needs the ladder resolved once before the loop, and a rung index that only advances when `phase` wraps:

```kotlin
val ladder = if (voice == GlintVoice.RATCHET) ratchetLadder(kBase, bloomAmount) else null
var rung = 0
```

and inside the loop, where `k` is computed — replacing the plain bloom sweep for this voice only:

```kotlin
val k = when (voice) {
    // The step is quantised to the phase wrap below, not applied here:
    // RATCHET's trapezoid window is 1 at phase 0 and only reaches zero at
    // the cycle's end, so changing k mid-cycle would break the waveform
    // under a wide-open window and click once per step.
    GlintVoice.RATCHET -> ladder!![rung]
    else -> (kBase * (1f + bloomAmount * Dsp.envAt(t, bloomT60))).coerceIn(K_MIN, K_MAX)
}
```

and at the wrap, at the bottom of the loop:

```kotlin
phase += step
if (phase >= 1f) {
    phase -= 1f
    // The only instant a ratio change is free: the window has just reached
    // zero, so any k starts from silence.
    if (ladder != null) {
        rung = ((t / RATCHET_STEP_SECONDS).toInt()).coerceAtMost(ladder.size - 1)
    }
}
```

- [ ] **Step 5: Run the tests, then mutation-verify the click test**

```bash
./gradlew --no-daemon :synth:test --tests '*GlintTest*'
```

Then temporarily move the `rung` update out of the `if (phase >= 1f)` block so it runs every sample — the exact mistake the design warns against. The no-click test **must** fail. Record both numbers in the test's comment and revert.

- [ ] **Step 6: Full module regression, then commit**

```bash
./gradlew --no-daemon :synth:test
git add -A synth/src
git commit -m "RATCHET: a formant that steps, and only where stepping is free"
```

---

### Task 4: PLATE — the formant falls with the loudness

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt`

**Interfaces:**
- Consumes: `GlintVoice.PLATE` and its saw window from Task 1.
- Produces: nothing new — PLATE reuses `bloomAmount` and the existing `amp` envelope.

**The mechanism.** From the spec, exactly: `k(t) = kBase · (1 + BLOOM · amp_env(t))`. No separate clock. The amplitude envelope starts near 1 and decays, so the formant starts high and falls with the note — a struck plate is brightest at the strike. BLOOM is the depth of that coupling.

Note what this means for `BLOOM_T60`: PLATE does not use it. Its motion has no timescale of its own; it borrows the amplitude envelope's.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `PLATE's formant falls as the note decays`() {
    val snip = Glint.render(GlintVoice.PLATE, mapOf("BLOOM" to 1f, "DECAY" to 0.9f, "BODY" to 0f))
    val head = spectralCentroid(slice(snip, 0.02f, 0.10f))
    val tail = spectralCentroid(slice(snip, 0.45f, 0.60f))
    assertTrue(tail < head * 0.8f, "PLATE's centroid went $head -> $tail — it did not fall")
}

@Test
fun `PLATE's fall tracks the amplitude envelope, not a separate curve`() {
    // The spec's requirement. k(t) = kBase * (1 + BLOOM * amp_env(t)), so a
    // LONGER note must hold its brightness longer in absolute time: the
    // coupling has no clock of its own. A fixed-rate sweep would fall at
    // the same wall-clock rate regardless of DECAY.
    val short = Glint.render(GlintVoice.PLATE, mapOf("BLOOM" to 1f, "DECAY" to 0.3f, "BODY" to 0f))
    val long = Glint.render(GlintVoice.PLATE, mapOf("BLOOM" to 1f, "DECAY" to 0.95f, "BODY" to 0f))
    val at = 0.25f
    val shortC = spectralCentroid(slice(short, at, at + 0.05f))
    val longC = spectralCentroid(slice(long, at, at + 0.05f))
    assertTrue(
        longC > shortC * 1.15f,
        "at ${at}s the long note's centroid is $longC and the short note's is $shortC — the fall is not tied to the envelope",
    )
}

@Test
fun `PLATE at BLOOM zero does not move its formant`() {
    // The control: with the coupling depth at zero, k(t) = kBase and PLATE
    // is an ordinary saw-window voice. If this fails, the coupling is not
    // actually gated on BLOOM.
    val snip = Glint.render(GlintVoice.PLATE, mapOf("BLOOM" to 0f, "DECAY" to 0.9f, "BODY" to 0f))
    val head = spectralCentroid(slice(snip, 0.02f, 0.10f))
    val tail = spectralCentroid(slice(snip, 0.45f, 0.60f))
    assertTrue(
        kotlin.math.abs(tail - head) < head * 0.15f,
        "PLATE at BLOOM 0 moved its centroid $head -> $tail",
    )
}
```

- [ ] **Step 2: Run them and watch them fail**

Expected: the fall test and the tracking test fail — PLATE currently renders with the ordinary bloom sweep, which *rises* rather than falls and runs on `BLOOM_T60` rather than the amp envelope.

**Record the numbers from this failing run.** They are the control that proves the passing version is doing something different.

- [ ] **Step 3: Branch the render loop**

Extend the `when` added in Task 3:

```kotlin
val k = when (voice) {
    GlintVoice.RATCHET -> ladder!![rung]
    // PLATE has no clock of its own: the formant is a function of how loud
    // the note currently is, so it falls exactly as the note falls and a
    // longer DECAY holds the brightness longer in absolute time. BLOOM is
    // the depth of that coupling, not a rate.
    GlintVoice.PLATE -> (kBase * (1f + bloomAmount * amp.at(t))).coerceIn(K_MIN, K_MAX)
    else -> (kBase * (1f + bloomAmount * Dsp.envAt(t, bloomT60))).coerceIn(K_MIN, K_MAX)
}
```

Confirm `amp.at(t)` is safe to call twice per sample (it is already called once, for the output gain). If `Dsp.Env.at` is stateful rather than pure, hoist it to a local `val ampNow = amp.at(t)` used by both — **check this, do not assume.**

- [ ] **Step 4: Run the tests**

Expected: PASS. Gate on exit code. If the tracking test's `1.15f` bar turns out to be outside what the coupling can produce, report the measured values rather than lowering the bar to whatever passes.

- [ ] **Step 5: Full module regression, then commit**

```bash
./gradlew --no-daemon :synth:test
git add -A synth/src
git commit -m "PLATE: a formant that falls with the loudness"
```

---

### Task 5: The D2 audition, the roadmap, and the spec's macro line

**Files:**
- Create: `synth/src/test/kotlin/com/snipsnap/synth/GlintD2AuditionGenerator.kt`
- Create: `synth/src/test/resources/audition/glint-d2-audition.html`
- Modify: `synth/build.gradle.kts`, `.gitignore`, `docs/SYNTH_ROADMAP.md`, `docs/superpowers/specs/2026-09-26-glint-depth-design.md`

**Interfaces:**
- Consumes: all six voices and the three mechanisms from Tasks 1–4.

- [ ] **Step 1: Build the generator, modelled on `GlintD1AuditionGenerator`**

Read `GlintD1AuditionGenerator.kt` first and copy its shape exactly: the `level()` helper verbatim, `AUDITION_LEVEL = 0.03f`, the per-clip `println` of frames/loudness/peak, WAVs into `<root>/clips/`, the page copied from the test classpath to `<root>/index.html`, and — importantly — **its bidirectional cross-check** that every `clips/*.wav` the page references exists on disk and every rendered clip is referenced, erroring with the offending names.

Clips, named `<section><nn>_<voice>_<what>.wav`, lowercase, zero-padded, ascending across the whole set:

- **A, the three new voices at their defaults** (3): CICADA, RATCHET, PLATE.
- **B, the old three against the new three** (3): REED, BOTTLE, KAZOO at defaults, so the set is a six-voice comparison.
- **C, CICADA's lattice** (3): BLOOM 0, 0.5, 1.
- **D, RATCHET's ladder** (4): BLOOM 0.25, 0.5, 0.75, 1 at DECAY 0.9, long enough for several steps. Print each ladder from `ratchetLadder` so the rungs are on the record.
- **E, PLATE's coupling** (4): BLOOM 0 and 1 at DECAY 0.3, and the same pair at DECAY 0.95 — the contrast that shows the fall is tied to the envelope.
- **F, Kakehashi's question** (3): RATCHET at PEAK 0.1, 0.45 and 0.9. The spec says if RATCHET is the one people reach for, that is his verdict arriving late and the roadmap should follow it.

- [ ] **Step 2: Write the page**

Model it on `synth/src/test/resources/audition/glint-d1-audition.html` — same structure, same KEEP/OK/DROP vocabulary, same `db` persistence into `verdicts/<clipid>` and `notes/<section>`, same `localStorage` mirror. State each section's question plainly at its head.

- [ ] **Step 3: Register the task and the ignore**

In `synth/build.gradle.kts`, beside `generateGlintD1Audition`:

```kotlin
tasks.register<JavaExec>("generateGlintD2Audition") {
    group = "distribution"
    description = "Render the GLINT D2 audition clips and listening page under testkit/glint-d2-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.GlintD2AuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/glint-d2-audition")
}
```

and in `.gitignore`, beside the D1 entry, `testkit/glint-d2-audition/`.

- [ ] **Step 4: Render and verify the cross-check fails when it should**

```bash
./gradlew --no-daemon generateGlintD2Audition
```
Then rename one rendered clip without clearing the directory, re-run, and confirm generation **fails naming that clip**. Record the exact message, then revert. A guard you did not watch fail is not a guard.

- [ ] **Step 5: Amend the roadmap's S10 row**

Append a `**D2 shipped**` clause describing what actually landed: three new voices as mechanisms — CICADA's carrier re-clocking `CICADA_SUBCYCLES` times per cycle with the window applied per sub-cycle, RATCHET's ladder of integer harmonics stepping only at the phase wrap, PLATE's formant coupled to the amplitude envelope — and that D3's preset roster is gated on the D2 audition.

Describe **what shipped**, not what this plan proposed. If a task's implementation diverged, the roadmap follows the code.

- [ ] **Step 6: Correct the spec's macro line**

In `docs/superpowers/specs/2026-09-26-glint-depth-design.md`, the registration table says `macrosFor` returns "five for RATCHET and PLATE". That contradicts the spec's own macro table, which defines BLOOM's meaning for both voices, and it was settled in favour of six on every voice. Correct the line and add a sentence recording that the contradiction was found and how it was resolved.

Do **not** edit the Phase 1 spec (`2026-09-25-glint-phase-distortion-design.md`) — it correctly records what Phase 1 shipped.

- [ ] **Step 7: Full module regression, then commit**

```bash
./gradlew --no-daemon :synth:test
git add -A
git commit -m "The GLINT D2 audition, and the roadmap row for six voices"
```

---

## Open questions for the audition

These are decided by ear, not by test, and D3 should not start before they are answered:

1. **`CICADA_SUBCYCLES = 4`** is a chosen value, not a derived one. Section C is where a different density would announce itself.
2. **`RATCHET_STEP_SECONDS = 0.15`** likewise. Too fast reads as a warble rather than a dial.
3. **Is RATCHET the one you reach for?** The spec records Kakehashi's dissent — that the bleep is a lineage rather than a defect. Section F is his case put to a listener.
4. **The D1 audition was never taken**, so BOTTLE's ring-versus-burn-off is still open, and `BODY_DECAY_RATIO` is shared by all six voices including the three added here.
