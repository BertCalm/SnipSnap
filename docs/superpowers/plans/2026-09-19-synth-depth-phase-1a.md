# Synth Depth Phase 1A — The Modal Primitive

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `Dsp.Modes` — a bank of two-pole resonators with **independent per-mode decay** — plus the sourced physical mode tables, the MATERIAL morph, and STRIKE's excitation-position weighting. Engine-agnostic: nothing adopts it yet.

**Architecture:** A resonator bank generalized from `audio/Body.kt`, which already has the correct two-pole structure but computes **one shared pole radius for every mode**. Independent per-mode t60 is the half that does not exist — and it is the half that makes a sound read as *struck* rather than *played*. Mode tables are physical (inharmonic) rather than musical; MATERIAL resamples every material onto a common ordinal index in log-ratio space so a morph crossfades ratio and amplitude per slot instead of switching modes in and out.

**Tech Stack:** Kotlin, `:synth` module, `kotlin.test` with backtick names. Offline render-to-buffer — a 200-mode bank is free here and unthinkable in a realtime mobile synth.

**Spec:** `docs/superpowers/specs/2026-09-18-synth-depth-design.md`
**Research (mode ratios, with citations):** `.superpowers/sdd/2026-09-19-synth-depth-phase-0/mode-ratios-research.md`

## Global Constraints

- **`PadRecipe.VERSION` stays 1.** Phase 1A adds no macros and changes no rendered output — nothing here reaches an engine. The bump belongs to 1B.
- **Renders must stay deterministic:** same inputs → byte-identical output. No clock, no shared mutable RNG.
- **Never hardcode 44100.** Every function takes a `rate` parameter.
- **Naming rule (`SYNTH_ROADMAP.md:27`):** no trademarked names, model numbers, or near-misses, in code or output.
- **Measure, never guess.** A number deciding how something sounds is derived from a measurement the report shows, from a cited physical source, or is a marked placeholder. There is no third category.
- **Acoustic claims need a real spectrum** (`Fft` in `:audio`), never autocorrelation — it misled Phase 0 twice, once reporting a 523.93 Hz string as 1047 Hz.
- **Any bounded parameter gets a reachability test** proving it is not swallowed. Phase 0 shipped a floor that silently overrode a macro across its entire range while every test stayed green.
- **Test command:** `./gradlew :synth:test`. Kick long runs off in the background and hand them to the controller; do not wait on them.

## Not in scope — these are Phase 1B, after the audition gate

Adopting `Dsp.Modes` into any engine. The MOTION stage. SPACE / stereo. The `PadRecipe.VERSION` bump. The STRIKE engine. Choosing which engine gets rebuilt — the spec assumes TINES, and the audition exists to confirm or overturn that.

---

## File Structure

**Create:**
- `synth/src/main/kotlin/com/snipsnap/synth/Modes.kt` — the resonator bank and the mode tables. One file: the bank and the tables change together, and a table without the bank is meaningless.
- `synth/src/test/kotlin/com/snipsnap/synth/ModesTest.kt`

**Modify:** nothing. Phase 1A is purely additive — no existing render path changes, so the suite's existing assertions are untouched and any failure is genuinely new.

---

### Task 1: The resonator bank with independent per-mode decay

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/Modes.kt`
- Create: `synth/src/test/kotlin/com/snipsnap/synth/ModesTest.kt`

**Interfaces:**
- Consumes: `Dsp.RATE`, `com.snipsnap.audio.Snip`
- Produces: `Modes.Mode(ratio: Float, gain: Float, t60: Float)`, `Modes.ring(excitation: FloatArray, fundamentalHz: Float, modes: List<Mode>, rate: Int): FloatArray`

**Read first:** `audio/src/main/kotlin/com/snipsnap/audio/Body.kt:77-105`. It is the precedent and it is *almost* right. Its two-pole form is correct:

```kotlin
val r = exp(-6.9078 / (decaySec * rate)).toFloat()   // ← computed ONCE, before the loop
for (mode in modes(...)) {
    val theta = 2.0 * Math.PI * hz / rate
    val a1 = (2.0 * r * cos(theta)).toFloat()
    val a2 = -(r * r)
    val gain = mode.weight * (1f - r)                 // (1-r) normalises ring level
    // y = gain*x + a1*y1 + a2*y2
}
```

**Every mode shares one `r`, so every mode decays at the same rate.** That is the half this task adds: `r` moves *inside* the loop and is derived from each mode's own `t60`. Note the `(1f - r)` gain compensation must become per-mode too, or modes with different decays will ring at wildly different levels.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.snipsnap.synth

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The modal bank. Its whole reason to exist is that each mode decays at its
 * OWN rate — `audio/Body.kt` has the right two-pole form but one shared pole
 * radius, so everything it rings falls silent together. Highs dying before
 * lows is what makes a sound read as struck rather than played.
 */
class ModesTest {

    /** RMS of a window, for comparing how much energy is left at a given time. */
    private fun rmsAt(buf: FloatArray, fromSec: Float, windowSec: Float, rate: Int): Float {
        val from = (fromSec * rate).toInt().coerceIn(0, buf.size)
        val to = (from + windowSec * rate).toInt().coerceIn(from, buf.size)
        if (to <= from) return 0f
        var sum = 0.0
        for (i in from until to) sum += buf[i].toDouble() * buf[i]
        return kotlin.math.sqrt(sum / (to - from)).toFloat()
    }

    private fun click(rate: Int, seconds: Float): FloatArray =
        FloatArray((seconds * rate).toInt()).also { it[0] = 1f }

    @Test
    fun `each mode decays at its own rate, not a shared one`() {
        val rate = Dsp.RATE
        // Two modes an octave apart. The high one is told to die four times
        // faster. If the bank shares one pole radius, both survive equally
        // and this fails.
        val modes = listOf(
            Modes.Mode(ratio = 1f, gain = 1f, t60 = 1.2f),
            Modes.Mode(ratio = 2f, gain = 1f, t60 = 0.3f),
        )
        val out = Modes.ring(click(rate, 1.5f), fundamentalHz = 220f, modes = modes, rate = rate)

        val lowOnly = Modes.ring(click(rate, 1.5f), 220f, listOf(modes[0]), rate)
        val highOnly = Modes.ring(click(rate, 1.5f), 220f, listOf(modes[1]), rate)

        // Early on both ring; by 0.8s the fast mode should be far quieter.
        val earlyRatio = rmsAt(highOnly, 0.02f, 0.05f, rate) / rmsAt(lowOnly, 0.02f, 0.05f, rate)
        val lateRatio = rmsAt(highOnly, 0.8f, 0.05f, rate) / rmsAt(lowOnly, 0.8f, 0.05f, rate)
        assertTrue(
            lateRatio < earlyRatio * 0.25f,
            "the fast mode must fall away relative to the slow one: early=$earlyRatio late=$lateRatio",
        )
        assertTrue(out.all { it.isFinite() }, "bank rendered NaN or Inf")
    }

    @Test
    fun `a mode rings at its own frequency`() {
        val rate = Dsp.RATE
        val out = Modes.ring(
            click(rate, 0.5f),
            fundamentalHz = 300f,
            modes = listOf(Modes.Mode(ratio = 2.756f, gain = 1f, t60 = 0.4f)),
            rate = rate,
        )
        // 300 * 2.756 = 826.8 Hz. Measured by zero crossings over a settled
        // window — crude but independent of any FFT windowing choice.
        var crossings = 0
        val from = (0.05f * rate).toInt()
        val to = (0.25f * rate).toInt()
        for (i in from + 1 until to) if ((out[i - 1] < 0f) != (out[i] < 0f)) crossings++
        val hz = crossings / 2f / ((to - from).toFloat() / rate)
        assertTrue(abs(hz - 826.8f) < 826.8f * 0.05f, "expected ~826.8 Hz, measured $hz")
    }

    @Test
    fun `modes with different decays ring at comparable levels`() {
        val rate = Dsp.RATE
        // The (1-r) gain compensation must be PER MODE. Without it a long
        // mode swamps a short one purely because its pole sits closer to the
        // unit circle, which is a bug, not a timbre.
        val slow = Modes.ring(click(rate, 1f), 220f, listOf(Modes.Mode(1f, 1f, 1.5f)), rate)
        val fast = Modes.ring(click(rate, 1f), 220f, listOf(Modes.Mode(1f, 1f, 0.2f)), rate)
        val slowPeak = slow.maxOf { abs(it) }
        val fastPeak = fast.maxOf { abs(it) }
        assertTrue(
            slowPeak < fastPeak * 4f && fastPeak < slowPeak * 4f,
            "onset levels should be comparable regardless of decay: slow=$slowPeak fast=$fastPeak",
        )
    }

    @Test
    fun `the bank is deterministic`() {
        val rate = Dsp.RATE
        val modes = listOf(Modes.Mode(1f, 1f, 0.5f), Modes.Mode(2.756f, 0.6f, 0.3f))
        val a = Modes.ring(click(rate, 0.5f), 220f, modes, rate)
        val b = Modes.ring(click(rate, 0.5f), 220f, modes, rate)
        assertTrue(a.contentEquals(b), "same inputs must give byte-identical output")
    }

    @Test
    fun `a mode above Nyquist is skipped, not aliased`() {
        val rate = Dsp.RATE
        val out = Modes.ring(
            click(rate, 0.2f),
            fundamentalHz = 15_000f,
            modes = listOf(Modes.Mode(ratio = 4f, gain = 1f, t60 = 0.2f)),  // 60 kHz
            rate = rate,
        )
        assertTrue(out.all { it.isFinite() }, "an over-Nyquist mode must not produce NaN")
        assertTrue(out.all { abs(it) < 1e-6f }, "an over-Nyquist mode must be silent, not folded down")
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests '*ModesTest*'`
Expected: FAIL — `Unresolved reference: Modes`

- [ ] **Step 3: Implement the bank**

```kotlin
package com.snipsnap.synth

import com.snipsnap.synth.Dsp.RATE
import kotlin.math.cos
import kotlin.math.exp

/**
 * MODES — a bank of tuned resonators, each with its own decay.
 *
 * `audio/Body.kt` got here first and got the hard part right: a mode is a
 * two-pole resonator, the hit is the mallet. What it does not have is decay
 * *independence* — it computes one pole radius before its mode loop and every
 * mode inherits it, so everything it rings falls silent together. That is an
 * organ, not a bell. High partials dying before low ones is most of what
 * makes a sound read as *struck*.
 *
 * Offline rendering is what makes this affordable: a two-hundred-mode bank is
 * unthinkable inside a realtime mobile voice and costs nothing here.
 */
internal object Modes {

    /**
     * One partial: where it sits relative to the fundamental, how hard the
     * strike excites it, and how long it takes to fall 60 dB. [ratio] is
     * dimensionless on purpose — it is the physics of the body, independent
     * of what note the body is tuned to, and independent of sample rate.
     */
    data class Mode(val ratio: Float, val gain: Float, val t60: Float)

    /**
     * Rings [modes] with [excitation] — the mallet — at [fundamentalHz].
     *
     * Each mode is `y[n] = g*x[n] + a1*y[n-1] + a2*y[n-2]`, the same two-pole
     * form `Body.ring` uses, but `r` is derived per mode from its own t60
     * rather than once for the bank. The `(1 - r)` gain term must be per-mode
     * too: a pole nearer the unit circle rings louder for the same input, so
     * without it a long decay would simply sound louder than a short one and
     * the DAMP control would read as a volume knob.
     */
    fun ring(
        excitation: FloatArray,
        fundamentalHz: Float,
        modes: List<Mode>,
        rate: Int = RATE,
    ): FloatArray {
        val out = FloatArray(excitation.size)
        if (excitation.isEmpty() || modes.isEmpty() || fundamentalHz <= 0f) return out
        val nyquist = rate / 2f

        for (mode in modes) {
            val hz = fundamentalHz * mode.ratio
            // Skipped, not folded: a resonator tuned past Nyquist would alias
            // down to an arbitrary audible pitch that belongs to no body.
            if (hz <= 0f || hz >= nyquist) continue
            if (mode.t60 <= 0f || mode.gain == 0f) continue

            val r = exp(-6.9078 / (mode.t60.toDouble() * rate)).toFloat()
            val theta = 2.0 * Math.PI * hz / rate
            val a1 = (2.0 * r * cos(theta)).toFloat()
            val a2 = -(r * r)
            val g = mode.gain * (1f - r)

            var y1 = 0f
            var y2 = 0f
            for (i in out.indices) {
                val y = g * excitation[i] + a1 * y1 + a2 * y2
                y2 = y1
                y1 = y
                out[i] += y
            }
        }
        return out
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :synth:test --tests '*ModesTest*'`
Expected: PASS, all five.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Modes.kt \
        synth/src/test/kotlin/com/snipsnap/synth/ModesTest.kt
git commit -m "Add a modal bank whose modes each decay at their own rate"
```

---

### Task 2: The physical mode tables

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Modes.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/ModesTest.kt`

**Interfaces:**
- Consumes: `Modes.Mode` from Task 1
- Produces: `Modes.Material` enum, `Modes.tableFor(material: Material): List<Mode>`

**The ratios are sourced, not recalled** — see the research file named in the header. Every value below carries provenance; do not adjust one without a source.

| Material | Ratios | Source |
|---|---|---|
| `METAL_BAR` (free–free, glockenspiel) | 1 : 2.756 : 5.404 : 8.933 | Euler–Bernoulli free-free eigenvalues 4.730/7.853/10.996/14.137 — Fletcher & Rossing, Blevins |
| `MEMBRANE` (circular, fixed rim) | 1 : 1.5933 : 2.1354 : 2.2954 : 2.9172 | Bessel zeros, modes (0,1)(1,1)(2,1)(0,2)(1,2) |
| `WOOD_XYLO` (undercut bar) | 1 : 3 : 6 | tuned partials, Fletcher & Rossing |
| `WOOD_MARIMBA` (undercut bar) | 1 : 4 : 10 | tuned partials, Fletcher & Rossing |
| `BELL` | 0.5 : 1 : 1.19 : 1.5 : 2 | hum/prime/tierce/quint/nominal; real bells within 1–2% (Perrin et al. 1982) |

**The stiff string is a formula, not a table:** `fₙ = n·f₀·√(1 + B·n²)` (Fletcher 1964). **B is not a constant anyone recalls** — sources disagree by orders of magnitude. Use a measured working range of 0.0003–0.025 (Steinway D measurements) and expose B as a parameter, never a literal.

- [ ] **Step 1: Write the failing test**

Add to `ModesTest.kt`:

```kotlin
    @Test
    fun `every material's ratios ascend and start at or below the fundamental`() {
        for (material in Modes.Material.entries) {
            val table = Modes.tableFor(material)
            assertTrue(table.isNotEmpty(), "$material has no modes")
            val ratios = table.map { it.ratio }
            assertTrue(
                ratios.zipWithNext().all { (a, b) -> b > a },
                "$material ratios must strictly ascend: $ratios",
            )
            assertTrue(ratios.first() > 0f, "$material has a non-positive first ratio")
        }
    }

    @Test
    fun `the metal bar is genuinely inharmonic`() {
        // 2.756 is the whole point: it is not 2, 3, or any integer, and that
        // is why a glockenspiel sounds like metal rather than a filtered saw.
        val ratios = Modes.tableFor(Modes.Material.METAL_BAR).map { it.ratio }
        assertTrue(abs(ratios[1] - 2.756f) < 0.001f, "second mode should be 2.756, got ${ratios[1]}")
        for (r in ratios.drop(1)) {
            val nearestInt = kotlin.math.round(r)
            assertTrue(abs(r - nearestInt) > 0.05f, "$r is suspiciously close to a harmonic")
        }
    }

    @Test
    fun `the bell's hum mode sits below its prime`() {
        val ratios = Modes.tableFor(Modes.Material.BELL).map { it.ratio }
        assertTrue(ratios.first() < 1f, "a bell's hum mode is an octave below the prime: ${ratios.first()}")
    }

    @Test
    fun `stiff string inharmonicity grows with mode index and with B`() {
        val low = Modes.stiffString(partials = 6, b = 0.0003f)
        val high = Modes.stiffString(partials = 6, b = 0.025f)
        // Mode n sits at n*sqrt(1+B*n^2) — stretched upward, more so for
        // higher n and higher B. Mode 1 barely moves; mode 6 moves a lot.
        assertTrue(low[0].ratio < low[5].ratio, "ratios must ascend")
        val lowStretch = low[5].ratio / 6f
        val highStretch = high[5].ratio / 6f
        assertTrue(highStretch > lowStretch, "larger B must stretch further: $lowStretch vs $highStretch")
        assertTrue(lowStretch > 1f, "any positive B stretches above the harmonic series")
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests '*ModesTest*'`
Expected: FAIL — `Unresolved reference: Material`

- [ ] **Step 3: Add the tables**

In `Modes.kt`:

```kotlin
    /**
     * The bodies. Ratios are sourced, not recalled — see
     * `mode-ratios-research.md` in the plan workspace for citations. An
     * earlier draft of the spec carried a table written from memory, which is
     * the exact failure this project exists to correct; do not adjust a value
     * here without a source.
     *
     * Two of these are the same object treated differently, and the
     * difference is the point: a free bar rings inharmonically (METAL_BAR),
     * while undercutting its belly pulls the partials onto near-harmonics
     * (WOOD_XYLO, WOOD_MARIMBA). That is most of what separates struck metal
     * from tuned wood.
     */
    enum class Material { METAL_BAR, MEMBRANE, WOOD_XYLO, WOOD_MARIMBA, BELL }

    /**
     * Gains and decays are the *shape* of a strike, not measured physics: high
     * partials are excited less and die sooner in every struck body. They are
     * a defensible starting point and a candidate for the audition gate;
     * the RATIOS are the sourced part.
     */
    private fun body(vararg ratios: Float): List<Mode> =
        ratios.mapIndexed { i, ratio ->
            // Falls away with index: -6 dB per partial in gain, and each
            // partial rings about 30% shorter than the one below it.
            Mode(
                ratio = ratio,
                gain = 0.5f.pow(i.toFloat() * 0.5f),
                t60 = 1.2f * 0.7f.pow(i.toFloat()),
            )
        }

    fun tableFor(material: Material): List<Mode> = when (material) {
        Material.METAL_BAR -> body(1f, 2.756f, 5.404f, 8.933f)
        Material.MEMBRANE -> body(1f, 1.5933f, 2.1354f, 2.2954f, 2.9172f)
        Material.WOOD_XYLO -> body(1f, 3f, 6f)
        Material.WOOD_MARIMBA -> body(1f, 4f, 10f)
        Material.BELL -> body(0.5f, 1f, 1.19f, 1.5f, 2f)
    }

    /**
     * A stiff string: `fn = n*f0*sqrt(1 + B*n^2)` (Fletcher 1964). The formula
     * is settled; **B is not**. Sources disagree by orders of magnitude, so it
     * is a parameter here and never a literal. Working range from measurements
     * of a Steinway D: [B_MIN] to [B_MAX]. Anything outside that is a caller's
     * deliberate choice, not physics.
     */
    const val B_MIN = 0.0003f
    const val B_MAX = 0.025f

    fun stiffString(partials: Int, b: Float, rootT60: Float = 1.2f): List<Mode> =
        (1..partials).map { n ->
            val nf = n.toFloat()
            Mode(
                ratio = nf * kotlin.math.sqrt(1f + b * nf * nf),
                gain = 0.5f.pow((n - 1).toFloat() * 0.5f),
                t60 = rootT60 * 0.7f.pow((n - 1).toFloat()),
            )
        }
```

Add `import kotlin.math.pow` at the top of the file.

- [ ] **Step 4: Run, then hear it**

Run: `./gradlew :synth:test --tests '*ModesTest*'`
Expected: PASS.

Then render one body per material through the bank into a WAV and listen. **These should already sound like struck objects** — if a material sounds like a filtered tone rather than a body, say so in the report with the measured spectrum. That is the whole claim of this phase and it is worth falsifying early.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Modes.kt \
        synth/src/test/kotlin/com/snipsnap/synth/ModesTest.kt
git commit -m "Add the sourced physical mode tables, ratios with provenance"
```

---

### Task 3: MATERIAL — the morph between bodies

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Modes.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/ModesTest.kt`

**Interfaces:**
- Consumes: `Modes.Material`, `Modes.tableFor`, `Modes.Mode`
- Produces: `Modes.morph(from: Material, to: Material, amount: Float, slots: Int = 6): List<Mode>`

**The design is settled by the research — do not redesign it.** The tables have different lengths (4 for a bar, 5 for a membrane, 3 for tuned wood). **Do not pad the short ones with silent modes.** The length difference is structural, not an amplitude gap: what makes a glockenspiel sound different from a drum head is *how fast the ratio grows with index*, not that some modes are missing. Padding would crossfade real partials against silence and thin the sound mid-sweep.

Instead:
1. Resample every material onto a common ordinal index — slot *k* is that material's *k*-th ascending partial.
2. Where a material runs out of sourced partials, **extrapolate its own ratio-growth trend** into the remaining slots, in **log-ratio space** (ratios grow geometrically, so a log-space fit is the honest continuation).
3. Give extrapolated slots lower gain than sourced ones — real upper partials are weaker, and it marks them as inference rather than data.
4. Crossfade **both ratio and amplitude** per slot.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `a morph endpoint reproduces its own table`() {
        val slots = 6
        val bar = Modes.morph(Modes.Material.METAL_BAR, Modes.Material.MEMBRANE, amount = 0f, slots = slots)
        val sourced = Modes.tableFor(Modes.Material.METAL_BAR)
        for (i in sourced.indices) {
            assertTrue(
                abs(bar[i].ratio - sourced[i].ratio) < 0.001f,
                "at amount=0 slot $i should be the bar's own ratio: ${bar[i].ratio} vs ${sourced[i].ratio}",
            )
        }
    }

    @Test
    fun `every slot carries a real partial - no silent padding`() {
        // The bar has 4 sourced partials, wood has 3, but a 6-slot bank must
        // be dense for BOTH or the morph thins out halfway through.
        for (material in Modes.Material.entries) {
            val morphed = Modes.morph(material, material, amount = 0f, slots = 6)
            assertTrue(morphed.size == 6, "$material should fill all 6 slots, got ${morphed.size}")
            assertTrue(
                morphed.all { it.gain > 0f },
                "$material has a silent slot — extrapolate the trend, do not pad with silence",
            )
            assertTrue(
                morphed.map { it.ratio }.zipWithNext().all { (a, b) -> b > a },
                "$material extrapolated ratios must keep ascending: ${morphed.map { it.ratio }}",
            )
        }
    }

    @Test
    fun `the morph is continuous - no jump at any step`() {
        // Sweep MATERIAL and confirm no slot's ratio lurches. A discontinuity
        // here would be audible as a click or a sudden change of body.
        val slots = 6
        var previous = Modes.morph(Modes.Material.METAL_BAR, Modes.Material.BELL, 0f, slots)
        for (step in 1..50) {
            val current = Modes.morph(Modes.Material.METAL_BAR, Modes.Material.BELL, step / 50f, slots)
            for (i in 0 until slots) {
                val jump = abs(current[i].ratio - previous[i].ratio) / previous[i].ratio
                assertTrue(jump < 0.15f, "slot $i jumped ${jump * 100}% at step $step")
            }
            previous = current
        }
    }

    @Test
    fun `morphing between different bodies actually changes the spectrum`() {
        // Reachability: MATERIAL must do something across its travel, or it
        // is a knob that does nothing — the exact defect Phase 0 shipped once.
        val bar = Modes.morph(Modes.Material.METAL_BAR, Modes.Material.MEMBRANE, 0f, 6)
        val membrane = Modes.morph(Modes.Material.METAL_BAR, Modes.Material.MEMBRANE, 1f, 6)
        val spread = bar.indices.maxOf { abs(bar[it].ratio - membrane[it].ratio) / bar[it].ratio }
        assertTrue(spread > 0.2f, "endpoints should differ meaningfully, max slot change was $spread")
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests '*ModesTest*'`
Expected: FAIL — `Unresolved reference: morph`

- [ ] **Step 3: Implement the morph**

```kotlin
    /**
     * [material]'s partials resampled onto [slots] ordinal positions.
     *
     * Slot k is "this body's k-th ascending partial." Where a body runs out of
     * sourced partials, its own ratio-growth trend continues into the
     * remaining slots, fitted in LOG-RATIO space because partial series grow
     * geometrically — a linear continuation would flatten exactly the
     * character that distinguishes one body from another. Extrapolated slots
     * ring quieter than sourced ones, which is both true of real upper
     * partials and an honest marker that they are inference rather than data.
     *
     * The alternative — padding short tables with silent modes — was rejected:
     * the length difference between a 3-partial tuned bar and a 5-partial
     * membrane is structural, not an amplitude gap, and crossfading real
     * partials against silence would thin the sound halfway through a sweep.
     */
    internal fun resample(material: Material, slots: Int): List<Mode> {
        val sourced = tableFor(material)
        if (slots <= sourced.size) return sourced.take(slots)

        // Growth per index in log space, from the sourced partials. With only
        // one partial there is no trend to read, so fall back to the harmonic
        // series — the least-assuming continuation available.
        val logs = sourced.map { kotlin.math.ln(it.ratio.toDouble()) }
        val step = if (logs.size >= 2) (logs.last() - logs.first()) / (logs.size - 1) else kotlin.math.ln(2.0)

        val out = sourced.toMutableList()
        for (k in sourced.size until slots) {
            val extrapolated = kotlin.math.exp(logs.last() + step * (k - sourced.size + 1)).toFloat()
            val last = out.last()
            out.add(
                Mode(
                    ratio = extrapolated,
                    // Quieter and shorter than the partial below it, and
                    // quieter again for being inferred rather than sourced.
                    gain = last.gain * 0.5f,
                    t60 = last.t60 * 0.7f,
                ),
            )
        }
        return out
    }

    /**
     * The MATERIAL knob: [from] at amount 0, [to] at amount 1, and genuine
     * bodies-that-do-not-exist in between. Both ratio and gain crossfade per
     * slot, so every slot always carries a real partial from both endpoints
     * and the morph stays dense the whole way across.
     */
    fun morph(from: Material, to: Material, amount: Float, slots: Int = 6): List<Mode> {
        val a = resample(from, slots)
        val b = resample(to, slots)
        val t = amount.coerceIn(0f, 1f)
        return (0 until slots).map { k ->
            Mode(
                // Interpolated in log space, for the same reason the
                // extrapolation is: ratios are geometric, not linear.
                ratio = kotlin.math.exp(
                    kotlin.math.ln(a[k].ratio.toDouble()) * (1 - t) + kotlin.math.ln(b[k].ratio.toDouble()) * t,
                ).toFloat(),
                gain = a[k].gain * (1 - t) + b[k].gain * t,
                t60 = a[k].t60 * (1 - t) + b[k].t60 * t,
            )
        }
    }
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :synth:test --tests '*ModesTest*'`
Expected: PASS.

- [ ] **Step 5: Hear the morph**

Render a sweep — the same strike through `morph(METAL_BAR, WOOD_MARIMBA, amount)` at amount 0, 0.25, 0.5, 0.75, 1 — and listen. It should travel continuously from struck metal to tuned wood. Report whether the midpoints sound like plausible bodies or like neither.

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Modes.kt \
        synth/src/test/kotlin/com/snipsnap/synth/ModesTest.kt
git commit -m "Morph between bodies on a common ordinal index, in log-ratio space"
```

---

### Task 4: STRIKE — where the body is hit

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Modes.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/ModesTest.kt`

**Interfaces:**
- Consumes: `Modes.Mode`
- Produces: `Modes.atPosition(modes: List<Mode>, position: Float): List<Mode>`

On a real bar, *where* you hit it decides which modes wake up: strike a node and that mode stays silent. As mode-gain weighting this is `gain_n *= |sin(n · π · position)|` — one line, and it moves a voice from woody thunk to glassy ping across one knob's travel. It exists only because there are modes to address.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun `striking a node silences that mode`() {
        val modes = Modes.tableFor(Modes.Material.METAL_BAR)
        // Mode 2 has a node at position 0.5 — sin(2*pi*0.5) = 0.
        val atCentre = Modes.atPosition(modes, 0.5f)
        assertTrue(atCentre[1].gain < modes[1].gain * 0.05f, "mode 2 should be near-silenced at its node")
        // Mode 1 is at its maximum there.
        assertTrue(atCentre[0].gain > modes[0].gain * 0.9f, "mode 1 should be near-full at centre")
    }

    @Test
    fun `strike position changes the spectrum across its travel`() {
        // Reachability: STRIKE must do something everywhere it can be set.
        val modes = Modes.tableFor(Modes.Material.METAL_BAR)
        val positions = listOf(0.05f, 0.25f, 0.5f, 0.75f, 0.95f)
        val profiles = positions.map { p -> Modes.atPosition(modes, p).map { it.gain } }
        for (i in 0 until profiles.size - 1) {
            val diff = profiles[i].indices.maxOf { abs(profiles[i][it] - profiles[i + 1][it]) }
            assertTrue(diff > 0.01f, "positions ${positions[i]} and ${positions[i + 1]} give near-identical gains")
        }
    }

    @Test
    fun `strike position never produces negative or non-finite gain`() {
        val modes = Modes.tableFor(Modes.Material.BELL)
        for (step in 0..100) {
            for (m in Modes.atPosition(modes, step / 100f)) {
                assertTrue(m.gain >= 0f && m.gain.isFinite(), "gain ${m.gain} at position ${step / 100f}")
            }
        }
    }
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew :synth:test --tests '*ModesTest*'`
Expected: FAIL — `Unresolved reference: atPosition`

- [ ] **Step 3: Implement**

```kotlin
    /**
     * [modes] as excited by a strike at [position] along the body, 0 to 1.
     *
     * Hit a bar at the centre and the even modes, which have a node there,
     * barely sound; hit it near the end and everything wakes up. That is
     * `|sin(n*pi*position)|` — the mode shape sampled at the striking point —
     * and it is the cheapest large timbral range in this whole document,
     * available only because there are individual modes to address.
     */
    fun atPosition(modes: List<Mode>, position: Float): List<Mode> {
        val p = position.coerceIn(0f, 1f)
        return modes.mapIndexed { i, mode ->
            val n = i + 1
            val weight = kotlin.math.abs(kotlin.math.sin(n * Math.PI * p)).toFloat()
            mode.copy(gain = mode.gain * weight)
        }
    }
```

- [ ] **Step 4: Run the tests, then hear it**

Run: `./gradlew :synth:test --tests '*ModesTest*'`
Expected: PASS.

Render the same body struck at position 0.05, 0.25, 0.5 and listen. It should travel from bright and ringing to hollow and woody.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Modes.kt \
        synth/src/test/kotlin/com/snipsnap/synth/ModesTest.kt
git commit -m "Weight modes by strike position, so where you hit it matters"
```

---

## Audition Gate

**Stop here.** Phase 1B — adopting this into an engine, MOTION, SPACE — does not begin until:

1. The five materials have been heard as struck bodies, not filtered tones.
2. The MATERIAL sweep has been heard as continuous travel between bodies.
3. **The flagship engine for 1B has been chosen.** The spec assumes TINES; that assumption was never tested by ear and the gate exists to confirm or overturn it.
4. Phase 0's four placeholders have been set, since 1B's numbers sit on top of them.

Render for the gate: each material struck at three positions, plus a five-step MATERIAL sweep between the two most different bodies.

---

## Self-Review

**Spec coverage.** The spec's BODY section maps to Tasks 1–2 (`Dsp.Modes` with independent per-mode t60, the sourced tables), MATERIAL to Task 3, STRIKE's position weighting to Task 4. The spec's own correction note about `Body.kt` lacking per-mode decay is the premise of Task 1. **Deliberately not covered, all Phase 1B:** EXCITE as a stage, MOTION, DRIVE, SPACE, the macro panel, the version bump, and adoption into any engine.

**Placeholders.** Gains and decay shapes in `body()` and `stiffString()` are explicitly marked in the KDoc as *shape*, not measured physics, and flagged as audition-gate candidates. The ratios — the part that carries the character — are all sourced with provenance in the table. `B` is a parameter with a documented measured range and never a literal, because sources disagree by orders of magnitude.

**Type consistency.** `Modes.Mode(ratio, gain, t60)` is introduced in Task 1 and used unchanged by Tasks 2–4. `tableFor(Material): List<Mode>` (Task 2) feeds `resample`/`morph` (Task 3) and `atPosition` (Task 4). `ring(excitation, fundamentalHz, modes, rate)` keeps one signature throughout.

**Known ordering constraint.** Task 1 must land before 2–4; Task 2's tables are the input to both 3 and 4, which are otherwise independent of each other.
