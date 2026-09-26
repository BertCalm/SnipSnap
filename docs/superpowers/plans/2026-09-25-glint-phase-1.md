# GLINT Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build GLINT's three built-in voices — REED, BOTTLE, KAZOO — as a registered `:synth` engine rendering one-shot pads, with all six macros, the glass tail, harmonic snap, and the tests that prove the engine's claim.

**Architecture:** GLINT generates a formant rather than filtering one. Each cycle of the fundamental runs a sine burst at `k` times `f0`, multiplied by a window that reaches zero by the cycle's end. Because the window forces zero at the wrap, `k` is free of the integers and slides continuously while the pitch does not move. The bare window, mean-removed, doubles as the body waveform, so BODY costs one macro instead of a second oscillator.

**Tech Stack:** Kotlin, JVM 17, Gradle 8.14.3 (`./gradlew`), `kotlin.test` with backtick test names. Zero new dependencies — `:synth` is pure Kotlin.

**Spec:** [`docs/superpowers/specs/2026-09-25-glint-phase-distortion-design.md`](../specs/2026-09-25-glint-phase-distortion-design.md)

## Global Constraints

- **Module:** all engine code lives in `:synth` (`synth/src/main/kotlin/com/snipsnap/synth/`). Tests in `synth/src/test/kotlin/com/snipsnap/synth/`.
- **`Dsp` is `internal object Dsp`** — reachable from `:synth` only. Always qualify: `Dsp.RATE`, `Dsp.OVERSAMPLE`.
- **Never hardcode a sample rate.** `synthesize` takes `rate: Int`; `render` passes `Dsp.RATE * Dsp.OVERSAMPLE` and decimates to `Dsp.RATE`.
- **Oversample contract (U6):** every engine renders at `Dsp.RATE * Dsp.OVERSAMPLE` then `Dsp.decimate(raw, Dsp.RATE)`. There is a shared test that proves `render()` is not a native-rate `synthesize()`.
- **Macro values are always 0..1 floats.** DSP ranges are mapped inside `synthesize` via `Dsp.lin` / `Dsp.expMap`, never stored pre-mapped.
- **One-shot:** `snip.durationSeconds < 2f` for every voice at every macro corner.
- **Naming rule:** no preset, voice, macro, or comment on a product surface may name a real machine. `PresetTestSupport.trademarkBlocklist` enforces it for preset names.
- **Macro names claimed by this engine:** `PEAK`, `FOLLOW`, `BODY`, `BLOOM` are unused anywhere else in the fleet (verified 2026-09-25). `TUNE` and `DECAY` are shared with ten and eight other engines respectively — this is normal.
- **Out of scope for Phase 1:** the TRACE voice, `GlintPresets.kt`, `Presets.kt` registration, `Keys.kt`, `SynthKits.kt`. Phases 2 and 3 own those.
- **Commit after every task.** Never commit a red test.

### Constants fixed by the spec

| Constant | Value | Why |
|---|---|---|
| `TUNE_SEMITONES` | `24` | two octaves, same as RESIN |
| `K_MIN` | `2f` | below two burst cycles per window there is no recognizable peak |
| `K_MAX` | `40f` | musical ceiling; aliasing does not bind until `k·f0 ≈ 88 kHz` |
| `SNAP_CEILING` | `12f` | `k` snaps to integers at or below this, runs continuous above |
| `KAZOO_FLAT` | `0.7f` | trapezoid stays at 1 for this fraction of the cycle |
| `BODY_DECAY_RATIO` | `0.45f` | BODY's own `t60` as a fraction of the amp `t60` — the glass tail |

### Build commands

```bash
./gradlew :synth:test                                              # whole module
./gradlew :synth:test --tests "com.snipsnap.synth.GlintTest"       # one class
./gradlew :synth:test --tests "com.snipsnap.synth.GlintTest.PEAK opens"   # one method
./gradlew :synth:compileTestKotlin                                 # compile only
```

No environment setup is needed for Gradle in this repo (the root `CLAUDE.md`'s `fnm use 20` applies to npm, not here). Java 17 is already the default.

---

### Task 1: The three windows and the burst

Builds a renderable engine skeleton: three voices, TUNE, DECAY, and a sine burst at a fixed `k`. PEAK arrives in Task 2.

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt`

**Interfaces:**
- Consumes: `Dsp.RATE`, `Dsp.OVERSAMPLE`, `Dsp.lin`, `Dsp.expMap`, `Dsp.envAt`, `Dsp.Env`, `Dsp.decimate`, `Dsp.levelTo`, `Dsp.fadeTail`, `Dsp.MELODIC_LOUDNESS_TARGET`, `Dsp.scrambleNear`, `MacroSpec(name, default, neutral = 0f)`, `com.snipsnap.audio.Snip(samples, channels, sampleRate)`
- Produces: `enum class GlintVoice { REED, BOTTLE, KAZOO }`; `object Glint` with `TUNE_SEMITONES: Int`, `K_MIN/K_MAX/SNAP_CEILING/KAZOO_FLAT/BODY_DECAY_RATIO: Float`, `macrosFor(voice: GlintVoice): List<MacroSpec>`, `defaults(voice: GlintVoice): Map<String, Float>`, `scramble(voice: GlintVoice, random: Random, temperature: Float = 0.35f, near: Patch? = null): Map<String, Float>`, `rootHz(voice: GlintVoice): Float`, `frequencyFor(voice: GlintVoice, tune: Float): Float`, `windowAt(voice: GlintVoice, phase: Float): Float`, `windowMean(voice: GlintVoice): Float`, `internal synthesize(voice: GlintVoice, macros: Map<String, Float>, rate: Int): FloatArray`, `render(voice: GlintVoice, macros: Map<String, Float> = emptyMap()): Snip`

- [ ] **Step 1: Write the failing test**

Create `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.FeatureExtractor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlintTest {

    @Test
    fun `every voice renders clean audio at defaults and both corners`() {
        for (voice in GlintVoice.entries) {
            for (macros in listOf(
                emptyMap(),
                Glint.macrosFor(voice).associate { it.name to 0f },
                Glint.macrosFor(voice).associate { it.name to 1f },
            )) {
                val snip = Glint.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke range at $macros")
                assertTrue(snip.peak() > 0.5f, "$voice too quiet at $macros")
                assertTrue(snip.durationSeconds < 2f, "$voice must stay a one-shot")
                val dc = snip.samples.average().toFloat()
                assertTrue(kotlin.math.abs(dc) < 0.05f, "$voice has DC offset $dc at $macros")
            }
        }
    }

    @Test
    fun `every voice declares exactly the six macros`() {
        for (voice in GlintVoice.entries) {
            assertEquals(
                listOf("TUNE", "PEAK", "FOLLOW", "BODY", "BLOOM", "DECAY"),
                Glint.macrosFor(voice).map { it.name },
                "$voice's macro contract",
            )
        }
    }

    @Test
    fun `every window ends at exactly zero - the whole engine rests on this`() {
        for (voice in GlintVoice.entries) {
            assertEquals(0f, Glint.windowAt(voice, 1f), 1e-6f, "$voice window must close the cycle")
            assertTrue(Glint.windowAt(voice, 0.5f) > 0f, "$voice window must be open mid-cycle")
        }
    }

    @Test
    fun `declared window means match numeric integration`() {
        // BODY subtracts these to kill DC. A wrong constant is a DC offset
        // that only shows up after normalization, so pin them here.
        for (voice in GlintVoice.entries) {
            var sum = 0.0
            val n = 100_000
            for (i in 0 until n) sum += Glint.windowAt(voice, i.toFloat() / n)
            val measured = (sum / n).toFloat()
            assertEquals(measured, Glint.windowMean(voice), 1e-3f, "$voice window mean")
        }
    }

    @Test
    fun `TUNE snaps to semitones and actually tunes`() {
        val distinct = HashSet<Float>()
        for (i in 0..100) distinct.add(Glint.frequencyFor(GlintVoice.REED, i / 100f))
        assertEquals(Glint.TUNE_SEMITONES + 1, distinct.size)
        assertEquals(Glint.rootHz(GlintVoice.REED), Glint.frequencyFor(GlintVoice.REED, 0f), 1e-3f)
        assertEquals(Glint.rootHz(GlintVoice.REED) * 4f, Glint.frequencyFor(GlintVoice.REED, 1f), 1e-2f)
    }

    @Test
    fun `DECAY lengthens`() {
        for (voice in GlintVoice.entries) {
            val short = FeatureExtractor.extract(Glint.render(voice, mapOf("DECAY" to 0.1f)))
            val long = FeatureExtractor.extract(Glint.render(voice, mapOf("DECAY" to 0.9f)))
            assertTrue(long.decayMs > short.decayMs * 1.5f, "$voice DECAY should stretch the note: ${short.decayMs} -> ${long.decayMs}")
        }
    }

    @Test
    fun `every voice is deterministic`() {
        for (voice in GlintVoice.entries) {
            val a = Glint.render(voice, mapOf("PEAK" to 0.7f))
            val b = Glint.render(voice, mapOf("PEAK" to 0.7f))
            assertTrue(a.samples.contentEquals(b.samples), "$voice: same macros must render the same bytes")
        }
    }

    @Test
    fun `scrambles are reproducible and stay in range`() {
        for (voice in GlintVoice.entries) {
            val a = Glint.scramble(voice, Random(11))
            val b = Glint.scramble(voice, Random(11))
            assertEquals(a, b, "$voice scramble should be seed-stable")
            assertEquals(Glint.defaults(voice).keys, a.keys)
            assertTrue(a.values.all { it in 0f..1f })
        }
    }

    @Test
    fun `render dispatches through the oversampled path, not directly at RATE`() {
        // The same mean-abs-diff proof VELVET/FATHOM/TONEWHEEL/VOX/RESIN carry.
        for (voice in GlintVoice.entries) {
            val actual = Glint.render(voice)
            val direct = Glint.synthesize(voice, emptyMap(), Dsp.RATE)
            Dsp.levelTo(direct, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
            Dsp.fadeTail(direct)
            var diff = 0.0
            val n = minOf(actual.samples.size, direct.size)
            for (i in 0 until n) diff += kotlin.math.abs((actual.samples[i] - direct[i]).toDouble())
            assertTrue(diff / n > 0.002, "$voice: render should differ from a native-rate synthesize, avgDiff=${diff / n}")
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.GlintTest"`
Expected: FAIL — compilation error, `Unresolved reference: Glint` / `GlintVoice`.

- [ ] **Step 3: Write the minimal implementation**

Create `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

enum class GlintVoice { REED, BOTTLE, KAZOO }

/**
 * GLINT — phase distortion, where the formant is generated rather than
 * carved. Each cycle of the fundamental runs a sine burst at [k] times f0,
 * multiplied by a window that reaches exactly zero by the cycle's end.
 *
 * That zero is the whole engine. It means the burst always lands on silence
 * at the wrap, so `k` need not be an integer and can slide continuously
 * without a click — the formant sweeps while the pitch does not move at all.
 * Only the window's *slope* jumps across the wrap, and that slope
 * discontinuity is the buzz the engine is made of. Do not smooth it.
 *
 * The bare window doubles as the body waveform: a linear-decay window is a
 * sawtooth, a triangle window is a triangle, a trapezoid is a pulse. Mixing
 * it back in (mean-removed, so it carries no DC) gives "a saw with a
 * resonant peak riding on it" for one macro instead of a second oscillator.
 *
 * Design: `docs/superpowers/specs/2026-09-25-glint-phase-distortion-design.md`.
 */
object Glint {

    const val TUNE_SEMITONES = 24

    /** Below two burst cycles inside the window there is no peak, only a dull fragment. */
    const val K_MIN = 2f

    /**
     * The musical ceiling. Aliasing is not what bounds this: the burst is
     * generated at `Dsp.RATE * Dsp.OVERSAMPLE`, so folding starts only above
     * `k·f0 ≈ 88 kHz` — k ≈ 1443 at A1, ≈ 361 at A3, ≈ 76 at C6.
     */
    const val K_MAX = 40f

    /** At or below this, `k` snaps to integers so the peak lands on a harmonic. */
    const val SNAP_CEILING = 12f

    /** KAZOO's trapezoid holds at full for this fraction of the cycle, then ramps out. */
    const val KAZOO_FLAT = 0.7f

    /** BODY's own t60 as a fraction of the amp t60 — the body burns off, the glass rings on. */
    const val BODY_DECAY_RATIO = 0.45f

    /** TEMPORARY: replaced by the PEAK macro in Task 2. */
    private const val K_FIXED = 8f

    fun macrosFor(voice: GlintVoice): List<MacroSpec> = listOf(
        MacroSpec("TUNE", 0.5f, 0.5f),
        MacroSpec("PEAK", 0.45f),
        MacroSpec("FOLLOW", 0.8f),
        MacroSpec("BODY", 0.4f),
        MacroSpec("BLOOM", 0.35f),
        MacroSpec("DECAY", 0.5f),
    )

    fun defaults(voice: GlintVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    /**
     * No preset roster yet — Phase 3 authors one and this gains the
     * preset-seeded form every other engine uses (see `Resin.scramble`).
     */
    fun scramble(
        voice: GlintVoice,
        random: Random,
        temperature: Float = 0.35f,
        near: Patch? = null,
    ): Map<String, Float> {
        val base = defaults(voice)
        val seed = if (near != null) base + near.macros.filterKeys { it in base } else base
        return Dsp.scrambleNear(seed, temperature, random)
    }

    /** The bottom of each voice's own two-octave TUNE range. */
    fun rootHz(voice: GlintVoice): Float = when (voice) {
        GlintVoice.REED -> 110f     // A2
        GlintVoice.BOTTLE -> 220f   // A3
        GlintVoice.KAZOO -> 220f    // A3
    }

    fun frequencyFor(voice: GlintVoice, tune: Float): Float {
        val semis = Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
        return rootHz(voice) * 2f.pow(semis / 12f)
    }

    /**
     * The window at [phase] in [0, 1). Must reach exactly zero at phase 1 —
     * every voice's definition below is written so that it does.
     */
    fun windowAt(voice: GlintVoice, phase: Float): Float {
        val p = phase.coerceIn(0f, 1f)
        return when (voice) {
            GlintVoice.REED -> 1f - p
            GlintVoice.BOTTLE -> if (p < 0.5f) p * 2f else (1f - p) * 2f
            GlintVoice.KAZOO -> if (p < KAZOO_FLAT) 1f else (1f - p) / (1f - KAZOO_FLAT)
        }
    }

    /**
     * The window's own mean, subtracted before BODY mixes it. A window is
     * unipolar; mixing it raw would push DC into `Dsp.levelTo` and out to
     * the WAV. Pinned against numeric integration by `GlintTest`.
     */
    fun windowMean(voice: GlintVoice): Float = when (voice) {
        GlintVoice.REED -> 0.5f
        GlintVoice.BOTTLE -> 0.5f
        GlintVoice.KAZOO -> KAZOO_FLAT + (1f - KAZOO_FLAT) / 2f
    }

    internal fun synthesize(voice: GlintVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = defaults(voice) + macros
        val f0 = frequencyFor(voice, m.getValue("TUNE"))
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.12f, 1.4f)
        val frames = (t60 * 1.35f * rate).toInt().coerceAtLeast(64)

        val amp = Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60)
        val step = f0 / rate
        var phase = 0f
        val out = FloatArray(frames)

        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            val w = windowAt(voice, phase)
            out[i] = amp.at(t) * w * sin(2.0 * PI * K_FIXED * phase).toFloat()
            phase += step
            if (phase >= 1f) phase -= 1f
        }
        return out
    }

    fun render(voice: GlintVoice, macros: Map<String, Float> = emptyMap()): Snip {
        // U6: render at 4x RATE so the burst's harmonics fold above 22.05 kHz
        // instead of into the band, then decimate.
        val renderRate = Dsp.RATE * Dsp.OVERSAMPLE
        val raw = synthesize(voice, macros, renderRate)
        val out = Dsp.decimate(raw, Dsp.RATE)
        Dsp.levelTo(out, Dsp.RATE, target = Dsp.MELODIC_LOUDNESS_TARGET)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = Dsp.RATE)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.GlintTest"`
Expected: PASS, all nine tests.

If `every voice renders clean audio` fails on `peak() > 0.5f`, the cause is `Dsp.levelTo` targeting loudness rather than peak — check the sine burst is not being cancelled by an all-zero window (a sign `windowAt` returned 0 everywhere).

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Glint.kt synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt
git commit -m "Add GLINT's three windows and the burst that rides them

REED is a linear decay, BOTTLE a triangle, KAZOO a trapezoid, and each
one reaches exactly zero by the cycle's end — which is what will let k
leave the integers in the next commit. k is fixed at 8 for now.

The window means are pinned against numeric integration, because BODY
subtracts them to kill DC and a wrong constant would only surface after
normalization."
```

---

### Task 2: PEAK, the clamp, and harmonic snap

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt`

**Interfaces:**
- Consumes: everything Task 1 produced.
- Produces: `Glint.ratioAtReference(peak: Float): Float`, `Glint.snapRatio(k: Float): Float`. `K_FIXED` is deleted.

**Why this task matters:** the pitch-invariance test below is the empirical line between GLINT and TINES. FM's index changes the perceived pitch as sidebands crowd the fundamental; GLINT's peak sweeps with the pitch nailed down. If that test does not pass, the engine has no reason to exist alongside TINES.

- [ ] **Step 1: Write the failing test**

Append to `GlintTest.kt`, inside the class:

```kotlin
    @Test
    fun `PEAK opens`() {
        for (voice in GlintVoice.entries) {
            val still = mapOf("BLOOM" to 0f, "BODY" to 0.2f)
            val dark = FeatureExtractor.extract(Glint.render(voice, still + ("PEAK" to 0.05f)))
            val open = FeatureExtractor.extract(Glint.render(voice, still + ("PEAK" to 0.95f)))
            assertTrue(
                open.centroidHz > dark.centroidHz * 1.5f,
                "$voice PEAK up should brighten: ${dark.centroidHz} -> ${open.centroidHz}",
            )
        }
    }

    @Test
    fun `the formant sweeps and the pitch does not move - the line between GLINT and TINES`() {
        // FM moves perceived pitch as its index climbs. A windowed burst does
        // not: the window wraps at f0 no matter what k is doing, so the period
        // is untouched. This is the engine's whole claim.
        for (voice in GlintVoice.entries) {
            val still = mapOf("TUNE" to 0.5f, "BLOOM" to 0f, "BODY" to 0.5f, "FOLLOW" to 1f, "DECAY" to 0.7f)
            val expected = Glint.frequencyFor(voice, 0.5f)
            for (peak in listOf(0.1f, 0.35f, 0.6f, 0.9f)) {
                val hz = TestPitch.estimate(
                    Glint.render(voice, still + ("PEAK" to peak)),
                    fromSec = 0.05f,
                    windowSec = 0.2f,
                )
                assertTrue(
                    hz > expected * 0.94f && hz < expected * 1.06f,
                    "$voice at PEAK $peak: pitch drifted to $hz, expected $expected",
                )
            }
        }
    }

    @Test
    fun `the ratio snaps to harmonics below the ceiling and runs free above it`() {
        for (k in listOf(2.4f, 3.7f, 11.6f)) {
            assertEquals(Math.round(k).toFloat(), Glint.snapRatio(k), 1e-6f, "k=$k should snap")
        }
        for (k in listOf(12.7f, 23.4f, 39.1f)) {
            assertEquals(k, Glint.snapRatio(k), 1e-6f, "k=$k should run free")
        }
    }

    @Test
    fun `PEAK values inside one snap zone render identically`() {
        // Snapping is real, not cosmetic: two PEAK settings that land on the
        // same harmonic must produce the same bytes.
        val voice = GlintVoice.BOTTLE
        val still = mapOf("TUNE" to 0.5f, "BLOOM" to 0f, "FOLLOW" to 1f)
        fun ratioAt(peak: Float) = Glint.snapRatio(Glint.ratioAtReference(peak).coerceIn(Glint.K_MIN, Glint.K_MAX))
        val pairs = (0..100).map { it / 100f }.groupBy { ratioAt(it) }.values.firstOrNull { it.size >= 2 }
        assertTrue(pairs != null, "expected at least one snap zone with two PEAK values in it")
        val a = Glint.render(voice, still + ("PEAK" to pairs!!.first()))
        val b = Glint.render(voice, still + ("PEAK" to pairs.last()))
        assertTrue(a.samples.contentEquals(b.samples), "same snapped harmonic must render the same bytes")
    }

    @Test
    fun `a non-integer ratio clicks no more than an integer one - the window's promise`() {
        // Above SNAP_CEILING k is continuous, so this is where a click would
        // show. Compare the largest sample-to-sample step at a deliberately
        // non-integer k against an integer one; a discontinuity at the wrap
        // would spike the non-integer case.
        for (voice in GlintVoice.entries) {
            val still = mapOf("TUNE" to 0.5f, "BLOOM" to 0f, "BODY" to 0f, "FOLLOW" to 1f)
            fun maxStep(peak: Float): Float {
                val s = Glint.render(voice, still + ("PEAK" to peak)).samples
                var worst = 0f
                for (i in 1 until s.size) {
                    val d = kotlin.math.abs(s[i] - s[i - 1])
                    if (d > worst) worst = d
                }
                return worst
            }
            // Find a PEAK landing closest to an integer ratio above the
            // ceiling, and one landing closest to halfway between two.
            // minByOrNull rather than first{tolerance}: the ratio map is
            // exponential, so step size near the top is coarse and a fixed
            // tolerance can miss entirely and throw instead of failing.
            val candidates = (0..4000).map { it / 4000f }
                .filter { Glint.ratioAtReference(it) > Glint.SNAP_CEILING + 1f }
            val onHarmonic = candidates.minByOrNull { kotlin.math.abs(Glint.ratioAtReference(it) % 1f - 0f) }!!
            val offHarmonic = candidates.minByOrNull { kotlin.math.abs(Glint.ratioAtReference(it) % 1f - 0.5f) }!!
            assertTrue(
                maxStep(offHarmonic) < maxStep(onHarmonic) * 1.5f,
                "$voice: a fractional ratio must not click — ${maxStep(offHarmonic)} vs ${maxStep(onHarmonic)}",
            )
        }
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.GlintTest"`
Expected: FAIL — `Unresolved reference: snapRatio` and `ratioAtReference`.

- [ ] **Step 3: Write the minimal implementation**

In `Glint.kt`, delete the `K_FIXED` constant and add these two functions after `windowMean`:

```kotlin
    /**
     * PEAK as a ratio at the voice's own reference note. Exponential,
     * because the ear judges the peak's position by interval, not by Hz.
     */
    fun ratioAtReference(peak: Float): Float = Dsp.expMap(peak, K_MIN, K_MAX)

    /**
     * Integers put the peak exactly on a harmonic — k=2 the octave, k=3 the
     * octave-and-a-fifth, k=5 two octaves and a major third. Snapping matters
     * only where the ear reads the peak as related to the note, so it applies
     * below [SNAP_CEILING] and stops above it, where it would be inaudible.
     *
     * The base ratio snaps; BLOOM modulates continuously on top of it. That
     * is what makes the knob musical and the sweep smooth.
     */
    fun snapRatio(k: Float): Float = if (k <= SNAP_CEILING) Math.round(k).toFloat() else k
```

Then replace the body of `synthesize` between the `f0` line and the loop, and the loop's burst expression:

```kotlin
    internal fun synthesize(voice: GlintVoice, macros: Map<String, Float>, rate: Int): FloatArray {
        val m = defaults(voice) + macros
        val f0 = frequencyFor(voice, m.getValue("TUNE"))
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.12f, 1.4f)
        val frames = (t60 * 1.35f * rate).toInt().coerceAtLeast(64)

        val k = snapRatio(ratioAtReference(m.getValue("PEAK")).coerceIn(K_MIN, K_MAX))

        val amp = Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60)
        val step = f0 / rate
        var phase = 0f
        val out = FloatArray(frames)

        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            val w = windowAt(voice, phase)
            out[i] = amp.at(t) * w * sin(2.0 * PI * k * phase).toFloat()
            phase += step
            if (phase >= 1f) phase -= 1f
        }
        return out
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.GlintTest"`
Expected: PASS.

If `the formant sweeps and the pitch does not move` fails, the cause is almost certainly a free-running inner phase accumulator instead of `k * phase` — the inner sine must be a function of the window's own phase so it restarts at zero each wrap. If `PEAK opens` fails on KAZOO, widen `K_MAX` is the *wrong* fix; check that KAZOO's trapezoid is not so flat that the burst dominates the centroid at every setting.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Glint.kt synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt
git commit -m "Give GLINT its PEAK, and prove the pitch does not move

The test that matters here is the pitch-invariance one: four PEAK
settings across the sweep, and the detector reads the same f0 at all of
them. That is the measurable line between this engine and TINES, where
a climbing index drags the perceived pitch with it.

PEAK snaps to integer harmonics up to k=12 and runs continuous above,
where snapping stops being audible. Two PEAK values inside one snap zone
render byte-identically, which is the assertion that keeps the snap
honest."
```

---

### Task 3: FOLLOW — the peak between absolute Hz and the note

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt`

**Interfaces:**
- Consumes: `Dsp.keyTrack(cutoffHz: Float, baseHz: Float, referenceHz: Float, amount: Float): Float` — returns `cutoffHz * (baseHz / referenceHz).pow(amount)`, a log-domain blend.
- Produces: `Glint.referenceHz(voice: GlintVoice): Float`, `Glint.ratioFor(voice: GlintVoice, tune: Float, peak: Float, follow: Float): Float`.

- [ ] **Step 1: Write the failing test**

Append to `GlintTest.kt`:

```kotlin
    @Test
    fun `FOLLOW 1 rides the note and FOLLOW 0 stands still`() {
        for (voice in GlintVoice.entries) {
            val lowTune = 0.1f
            val highTune = 0.9f
            // Tracking: the ratio is the same at both notes, so the peak's Hz
            // scales with the note.
            val rideLow = Glint.ratioFor(voice, lowTune, 0.5f, 1f)
            val rideHigh = Glint.ratioFor(voice, highTune, 0.5f, 1f)
            assertEquals(rideLow, rideHigh, 1e-3f, "$voice at FOLLOW 1: the ratio must not change with the note")

            // Parked: the peak's Hz is the same at both notes, so the ratio
            // falls as the note rises.
            val parkLowHz = Glint.ratioFor(voice, lowTune, 0.5f, 0f) * Glint.frequencyFor(voice, lowTune)
            val parkHighHz = Glint.ratioFor(voice, highTune, 0.5f, 0f) * Glint.frequencyFor(voice, highTune)
            assertEquals(parkLowHz, parkHighHz, parkLowHz * 0.02f, "$voice at FOLLOW 0: the peak's Hz must not move")
        }
    }

    @Test
    fun `FOLLOW changes what the render measures, not only what the math says`() {
        // The centroid climbs with the note when the peak tracks it, and
        // stays put when the peak is parked. Measured on BOTTLE, whose
        // triangle window leaves the burst most exposed in the spectrum.
        val voice = GlintVoice.BOTTLE
        val still = mapOf("PEAK" to 0.5f, "BLOOM" to 0f, "BODY" to 0.2f, "DECAY" to 0.6f)
        fun centroid(tune: Float, follow: Float) = FeatureExtractor.extract(
            Glint.render(voice, still + ("TUNE" to tune) + ("FOLLOW" to follow)),
        ).centroidHz

        val ridesLow = centroid(0.1f, 1f)
        val ridesHigh = centroid(0.9f, 1f)
        assertTrue(ridesHigh > ridesLow * 1.8f, "FOLLOW 1 should carry the peak up with the note: $ridesLow -> $ridesHigh")

        val parkedLow = centroid(0.1f, 0f)
        val parkedHigh = centroid(0.9f, 0f)
        assertTrue(
            parkedHigh < parkedLow * 1.35f,
            "FOLLOW 0 should leave the peak where it was: $parkedLow -> $parkedHigh",
        )
    }

    @Test
    fun `the ratio floor holds at every note and every FOLLOW`() {
        // k below 2 is fewer than two burst cycles in the window: no peak,
        // just a dull fragment. The clamp must be unconditional.
        for (voice in GlintVoice.entries) {
            for (tune in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                for (follow in listOf(0f, 0.5f, 1f)) {
                    for (peak in listOf(0f, 0.5f, 1f)) {
                        val k = Glint.ratioFor(voice, tune, peak, follow)
                        assertTrue(
                            k >= Glint.K_MIN - 1e-4f && k <= Glint.K_MAX + 1e-4f,
                            "$voice tune=$tune follow=$follow peak=$peak gave k=$k",
                        )
                    }
                }
            }
        }
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.GlintTest"`
Expected: FAIL — `Unresolved reference: ratioFor`.

- [ ] **Step 3: Write the minimal implementation**

Add to `Glint.kt` after `snapRatio`:

```kotlin
    /** The centre of the voice's own TUNE range — where FOLLOW has no work to do. */
    fun referenceHz(voice: GlintVoice): Float = frequencyFor(voice, 0.5f)

    /**
     * The formant ratio actually used, after FOLLOW, the snap and the clamp.
     *
     * FOLLOW rides `Dsp.keyTrack`: at 1 the peak's Hz scales exactly with the
     * note, so the ratio is constant and the timbre is identical across the
     * range; at 0 the peak's Hz is fixed, so the ratio falls as the note
     * rises and the sound turns vocal — a body resonance rather than a
     * filter. In between, `keyTrack` blends in the log domain, so half
     * tracking means half the octaves of movement.
     *
     * The [K_MIN] floor is unconditional. Across a pad's two-octave range it
     * never binds (a 700 Hz peak is k=12.7 at A1 and 3.2 at A3), but across
     * a four-octave keygroup at FOLLOW 0 it would — see the spec's note, and
     * expect FOLLOW's bottom half to collapse toward tracking up there.
     */
    fun ratioFor(voice: GlintVoice, tune: Float, peak: Float, follow: Float): Float {
        val reference = referenceHz(voice)
        val f0 = frequencyFor(voice, tune)
        val peakHzAtReference = ratioAtReference(peak) * reference
        val peakHz = Dsp.keyTrack(peakHzAtReference, f0, reference, follow)
        return snapRatio((peakHz / f0).coerceIn(K_MIN, K_MAX))
    }
```

Then in `synthesize`, replace the `val k = ...` line with:

```kotlin
        val k = ratioFor(voice, m.getValue("TUNE"), m.getValue("PEAK"), m.getValue("FOLLOW"))
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.GlintTest"`
Expected: PASS.

If `FOLLOW changes what the render measures` fails on the parked case, check whether the snap is quantizing both notes onto the same harmonic and hiding the effect — raise PEAK in `still` so the ratio lands above `SNAP_CEILING` and re-measure. Record the numbers you observe in a comment above the test, the way `ResinTest` does.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Glint.kt synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt
git commit -m "Add FOLLOW: the peak rides the note, or stands still

FOLLOW is a thin wrapper over Dsp.keyTrack, which already blends in the
log domain — so half tracking is half the octaves of movement, not half
the Hz. At 1 the ratio is constant across the range; at 0 the peak's Hz
is, and the sound turns vocal.

The k >= 2 floor is unconditional and tested at every note, FOLLOW and
PEAK combination. It never binds across a pad's two octaves; the spec
records that it will across a four-octave keygroup."
```

---

### Task 4: BODY, mean removal, and the glass tail

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt`

**Interfaces:**
- Consumes: `Glint.windowMean`, `Dsp.envAt(t: Float, t60: Float): Float`.
- Produces: no new public functions — BODY is wired inside `synthesize`.

- [ ] **Step 1: Write the failing test**

Append to `GlintTest.kt`:

```kotlin
    /** [snip] between two times, mono, for a windowed measurement. */
    private fun slice(snip: com.snipsnap.audio.Snip, fromSec: Float, toSec: Float): com.snipsnap.audio.Snip {
        val a = (fromSec * snip.sampleRate).toInt().coerceIn(0, snip.samples.size)
        val b = (toSec * snip.sampleRate).toInt().coerceIn(a, snip.samples.size)
        return com.snipsnap.audio.Snip(snip.samples.copyOfRange(a, b), 1, snip.sampleRate)
    }

    @Test
    fun `BODY puts a fundamental under the peak`() {
        for (voice in GlintVoice.entries) {
            val still = mapOf("TUNE" to 0.2f, "PEAK" to 0.8f, "BLOOM" to 0f, "FOLLOW" to 1f)
            val bare = FeatureExtractor.extract(Glint.render(voice, still + ("BODY" to 0f)))
            val full = FeatureExtractor.extract(Glint.render(voice, still + ("BODY" to 1f)))
            assertTrue(
                full.lowRatio > bare.lowRatio * 1.3f,
                "$voice BODY should add low end: ${bare.lowRatio} -> ${full.lowRatio}",
            )
        }
    }

    @Test
    fun `BODY carries no DC at any setting`() {
        // The window is unipolar. Mixed in raw it would push DC straight
        // through Dsp.levelTo and out to the WAV; windowMean is subtracted
        // to stop that, and this is the assertion that catches a wrong mean.
        for (voice in GlintVoice.entries) {
            for (body in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                val snip = Glint.render(voice, mapOf("BODY" to body, "BLOOM" to 0f))
                val dc = snip.samples.average().toFloat()
                assertTrue(kotlin.math.abs(dc) < 0.02f, "$voice at BODY $body has DC $dc")
            }
        }
    }

    @Test
    fun `the glass tail - the body burns off and leaves the resonance ringing`() {
        // Differential, not absolute: an absolute head-to-tail centroid rise
        // is also produced by the amp envelope alone, which proves nothing.
        // What must be true is that the rise is LARGER when there is a body
        // to burn off than when there is not.
        for (voice in GlintVoice.entries) {
            val still = mapOf("TUNE" to 0.3f, "PEAK" to 0.75f, "BLOOM" to 0f, "FOLLOW" to 1f, "DECAY" to 0.8f)
            fun rise(body: Float): Float {
                val snip = Glint.render(voice, still + ("BODY" to body))
                val head = FeatureExtractor.extract(slice(snip, 0f, snip.durationSeconds * 0.2f)).centroidHz
                val tail = FeatureExtractor.extract(
                    slice(snip, snip.durationSeconds * 0.55f, snip.durationSeconds * 0.85f),
                ).centroidHz
                return tail / head
            }
            val withBody = rise(0.9f)
            val without = rise(0.02f)
            assertTrue(
                withBody > without * 1.25f,
                "$voice should turn to glass as it fades: rise ${withBody} with body vs ${without} without",
            )
        }
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.GlintTest"`
Expected: FAIL — `BODY puts a fundamental under the peak` fails (BODY is not yet read), and `the glass tail` fails.

- [ ] **Step 3: Write the minimal implementation**

Replace `synthesize`'s envelope setup and loop in `Glint.kt`:

```kotlin
        val amp = Dsp.Env(attackSeconds = 0.002f, decay2T60 = t60)
        val bodyMix = m.getValue("BODY")
        val bodyT60 = t60 * BODY_DECAY_RATIO
        val mean = windowMean(voice)
        val step = f0 / rate
        var phase = 0f
        val out = FloatArray(frames)

        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            val w = windowAt(voice, phase)
            val burst = w * sin(2.0 * PI * k * phase).toFloat()
            // The body decays faster than the burst, so the note opens as a
            // saw with a peak on it and fades to pure whistling resonance.
            val body = bodyMix * Dsp.envAt(t, bodyT60) * (w - mean)
            out[i] = amp.at(t) * (burst + body)
            phase += step
            if (phase >= 1f) phase -= 1f
        }
        return out
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.GlintTest"`
Expected: PASS.

If `BODY carries no DC` fails, `windowMean` disagrees with `windowAt` — the numeric-integration test from Task 1 should have caught it, so re-run that one first. If `the glass tail` fails marginally, tune `BODY_DECAY_RATIO` downward (0.45 → 0.35) rather than weakening the assertion, and record the measured ratios in a comment above the test.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Glint.kt synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt
git commit -m "Add BODY, and let the note turn to glass as it fades

The window doubles as the body waveform — a linear decay is a sawtooth —
so BODY costs one macro and no second oscillator. It is mean-removed
before it is mixed, because a unipolar window would otherwise push DC
through levelTo and out to the WAV.

The body gets its own faster envelope, so a note opens as a saw with a
peak riding it and decays into pure whistling resonance. The test for it
is a differential against BODY 0 — an absolute head-to-tail centroid
rise is also what the amp envelope alone produces."
```

---

### Task 5: BLOOM — the peak's envelope

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Glint.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt`

**Interfaces:**
- Consumes: `Glint.ratioFor`, `Dsp.envAt`, `Dsp.lin`, `Dsp.expMap`.
- Produces: `Glint.BLOOM_MAX: Float`, `Glint.BLOOM_FAST_T60: Float`, `Glint.BLOOM_SLOW_T60: Float`.

**Note on range:** BLOOM is deliberately allowed to go ugly at its top. GLINT has no feedback path, so there is no safety reason to keep it polite, and the audition-gate finding of 2026-09-08 requires a macro's extremes to include the ugly end.

- [ ] **Step 1: Write the failing test**

Append to `GlintTest.kt`:

```kotlin
    @Test
    fun `BLOOM opens the peak at the attack and lets it settle`() {
        for (voice in GlintVoice.entries) {
            val still = mapOf("TUNE" to 0.3f, "PEAK" to 0.4f, "BODY" to 0.2f, "FOLLOW" to 1f, "DECAY" to 0.7f)
            fun headToTail(bloom: Float): Float {
                val snip = Glint.render(voice, still + ("BLOOM" to bloom))
                val head = FeatureExtractor.extract(slice(snip, 0f, 0.012f)).centroidHz
                val tail = FeatureExtractor.extract(
                    slice(snip, snip.durationSeconds * 0.7f, snip.durationSeconds),
                ).centroidHz
                return head / tail
            }
            assertTrue(headToTail(1f) > 1.4f, "$voice BLOOM 1 should open the head well above the tail: ${headToTail(1f)}")
            assertTrue(headToTail(0f) < 1.2f, "$voice BLOOM 0 should leave head and tail alike: ${headToTail(0f)}")
        }
    }

    @Test
    fun `BLOOM lands before the note ends, whatever it did on the way`() {
        for (voice in GlintVoice.entries) {
            val still = mapOf("TUNE" to 0.3f, "PEAK" to 0.4f, "BODY" to 0.2f, "FOLLOW" to 1f, "DECAY" to 0.7f)
            fun tail(bloom: Float): Float {
                val snip = Glint.render(voice, still + ("BLOOM" to bloom))
                return FeatureExtractor.extract(
                    slice(snip, snip.durationSeconds * 0.75f, snip.durationSeconds),
                ).centroidHz
            }
            assertTrue(
                kotlin.math.abs(tail(1f) - tail(0f)) < tail(0f) * 0.2f,
                "the sweep must have landed by the tail: ${tail(1f)} vs ${tail(0f)}",
            )
        }
    }

    @Test
    fun `BLOOM at its ugliest still renders legal audio`() {
        // GLINT has no feedback path, so BLOOM is allowed to be ugly at the
        // top — but ugly must still be finite, in range and in tune.
        for (voice in GlintVoice.entries) {
            for (peak in listOf(0f, 0.5f, 1f)) {
                val snip = Glint.render(voice, mapOf("BLOOM" to 1f, "PEAK" to peak, "BODY" to 1f))
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice BLOOM 1 PEAK $peak broke range")
                assertTrue(snip.peak() > 0.5f, "$voice BLOOM 1 PEAK $peak too quiet")
            }
        }
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.GlintTest"`
Expected: FAIL — `BLOOM opens the peak at the attack` fails; BLOOM is declared but not read, so head and tail match at every setting.

- [ ] **Step 3: Write the minimal implementation**

Add the constants to `Glint.kt` beside the others:

```kotlin
    /** BLOOM's ceiling: the peak opens to this many times its settled ratio. */
    const val BLOOM_MAX = 3f

    /** BLOOM's sweep t60 at the knob's top and bottom — more BLOOM is further AND faster. */
    const val BLOOM_FAST_T60 = 0.06f
    const val BLOOM_SLOW_T60 = 0.30f
```

Then in `synthesize`, **replace** the single `val k = ratioFor(...)` line (added in Task 3) with these four lines — `k` is no longer a constant for the whole render, so it is renamed `kBase` and recomputed per sample inside the loop:

```kotlin
        val kBase = ratioFor(voice, m.getValue("TUNE"), m.getValue("PEAK"), m.getValue("FOLLOW"))
        val bloom = m.getValue("BLOOM")
        val bloomAmount = Dsp.lin(bloom, 0f, BLOOM_MAX)
        val bloomT60 = Dsp.expMap(bloom, BLOOM_SLOW_T60, BLOOM_FAST_T60)
```

and inside the loop, replace the burst line:

```kotlin
            // kBase is snapped; BLOOM modulates continuously on top of it, so
            // the knob is musical and the sweep is smooth. k moves on the
            // envelope's timescale, far slower than one cycle, so the inner
            // sine stays effectively periodic while restarting at each wrap.
            val k = (kBase * (1f + bloomAmount * Dsp.envAt(t, bloomT60))).coerceIn(K_MIN, K_MAX)
            val burst = w * sin(2.0 * PI * k * phase).toFloat()
```

After this edit there must be no `val k =` line above the loop — `k` exists only inside it. If the compiler reports `k` as unused above the loop, the rename was incomplete.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.GlintTest"`
Expected: PASS.

If `BLOOM lands before the note ends` fails, `BLOOM_SLOW_T60` is too long relative to the shortest DECAY — the sweep must finish well inside the note. Lower it rather than moving the measurement window. If `BLOOM opens the peak` fails only on KAZOO, its trapezoid's flat section may be masking the burst; raise `PEAK` in `still` and record what you measure.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Glint.kt synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt
git commit -m "Add BLOOM: how far and how fast the peak opens, on one knob

The base ratio snaps to a harmonic; BLOOM modulates continuously on top
of it. That split is deliberate — the knob steps musically, the sweep
stays smooth, and a snapped sweep stepping through the harmonic series
stays available as a sound rather than becoming the default.

BLOOM is allowed to be ugly at the top. There is no feedback path here
to protect, and the audition gate wants a macro's extremes to include
the ugly end — so the test asserts that ugly is still finite, in range
and loud enough, not that it is pretty."
```

---

### Task 6: `GlintPatch` and JSON registration

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/GlintPatch.kt`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Patches.kt` (the `when` in `fromJsonValue`, currently lines 38–49)
- Test: `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt`

**Interfaces:**
- Consumes: `sealed interface Patch` (`name`, `engine`, `voiceName`, `macros`, `render()`, `withMacros()`), `Patches.validateMacros(patch, known)`, `Patches.decode(value, engine, voiceOf, build)`, `Patches.fromJsonText(text): Patch`, `Patches.VERSION`.
- Produces: `data class GlintPatch(name, voice, macros) : Patch` with `companion object { const val ENGINE = "GLINT" }`, `fromJsonValue`, `fromJsonText`.

- [ ] **Step 1: Write the failing test**

Append to `GlintTest.kt`:

```kotlin
    @Test
    fun `a GLINT patch round-trips through JSON`() {
        val patch = GlintPatch("Glass Test", GlintVoice.BOTTLE, mapOf("PEAK" to 0.7f, "FOLLOW" to 0.2f))
        val restored = Patches.fromJsonText(patch.toJsonText())
        assertEquals(patch, restored)
        assertTrue(patch.render().samples.contentEquals(restored.render().samples))
    }

    @Test
    fun `a GLINT patch rejects a macro the voice does not have`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            GlintPatch("Bad", GlintVoice.REED, mapOf("CUTOFF" to 0.5f))
        }
    }

    @Test
    fun `a GLINT patch rejects a macro out of range`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            GlintPatch("Bad", GlintVoice.REED, mapOf("PEAK" to 1.4f))
        }
    }

    @Test
    fun `a GLINT patch will not load from another engine's JSON`() {
        val tines = TinesPatch("Bell", TinesVoice.BELL, mapOf("RATIO" to 0.5f))
        kotlin.test.assertFailsWith<com.snipsnap.json.JsonException> {
            GlintPatch.fromJsonText(tines.toJsonText())
        }
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.GlintTest"`
Expected: FAIL — `Unresolved reference: GlintPatch`.

- [ ] **Step 3: Write the minimal implementation**

Create `synth/src/main/kotlin/com/snipsnap/synth/GlintPatch.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue

/**
 * A saved GLINT sound — same contract as [TinesPatch], different engine tag.
 *
 * Phase 2 adds a `window: IntArray?` field here for the TRACE voice, baked
 * into the patch the way [SnapPatch] bakes its table. Nothing references a
 * source file: a patch that points at external material falls out of the
 * "a kit regenerates from kit.json" guarantee and stays out, which is why
 * the GRAINS kit sits outside `PadRecipeTest`'s list to this day.
 */
data class GlintPatch(
    override val name: String,
    val voice: GlintVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Glint.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Glint.render(voice, macros)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    companion object {
        const val ENGINE = "GLINT"
        const val VERSION = Patches.VERSION

        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> GlintVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                GlintPatch(name, voice, macros)
            }

        fun fromJsonText(text: String): Patch = fromJsonValue(Json.parse(text))
    }
}
```

In `Patches.kt`, add one line to `fromJsonValue`'s `when`, after the `SnapPatch` branch and before `else`:

```kotlin
            GlintPatch.ENGINE -> GlintPatch.fromJsonValue(value)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.GlintTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/GlintPatch.kt synth/src/main/kotlin/com/snipsnap/synth/Patches.kt synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt
git commit -m "Register GLINT patches for JSON

Standard patch contract plus one branch in Patches.fromJsonValue. The
engine tag is what keeps a TINES file from loading into a GLINT panel —
refusing early beats rendering the wrong sound quietly.

The KDoc carries Phase 2's window field forward, along with the reason it
will be baked rather than referenced."
```

---

### Task 7: Velocity — earn `PEAK`'s place on the brightness list

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt` (the `BRIGHTNESS_MACROS` list, currently line 418)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/VelocityGrooveShuffleTest.kt` (the `cases` list, currently lines 132–139)
- Test: `synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt`

**Interfaces:**
- Consumes: `Velocity.atVelocity(patch: Patch, velocity: Float): Snip`, `Velocity.brightnessSpec(patch: Patch): MacroSpec?`.
- Produces: nothing new — `PEAK` joins the existing list.

**This task has a gate before the edit.** `BRIGHTNESS_MACROS` is not a list you append to. Its KDoc records a failure: THUMP's SNARE was registered on `TONE` and velocity came out *backwards* — soft renders measured brighter than hard ones (1650.29 Hz vs 1648.09 Hz). The fix required moving SNARE to `SNAP` and justifying it with a nine-point centroid sweep recorded verbatim in the KDoc. Do the same here before touching the list.

- [ ] **Step 1: Run the monotonicity gate and record the numbers**

Write this scratch test in `GlintTest.kt`, run it, and **read its output**:

```kotlin
    @Test
    fun `PEAK sweep is monotonic - the gate for joining BRIGHTNESS_MACROS`() {
        // Velocity.BRIGHTNESS_MACROS' own KDoc records what happens when a
        // macro joins this list without being measured: THUMP SNARE on TONE
        // read 1650.29 Hz soft against 1648.09 Hz hard — backwards. A macro
        // earns its place with a sweep that rises at every step, per voice.
        for (voice in GlintVoice.entries) {
            val still = mapOf("TUNE" to 0.4f, "BLOOM" to 0f, "BODY" to 0.3f, "FOLLOW" to 1f, "DECAY" to 0.6f)
            val readings = (0..8).map { i ->
                FeatureExtractor.extract(Glint.render(voice, still + ("PEAK" to i / 8f))).centroidHz
            }
            println("PEAK sweep $voice: ${readings.joinToString(", ") { "%.1f".format(it) }}")
            for (i in 1 until readings.size) {
                assertTrue(
                    readings[i] > readings[i - 1],
                    "$voice PEAK is not monotonic at step $i: ${readings[i - 1]} -> ${readings[i]}",
                )
            }
        }
    }
```

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.GlintTest.PEAK sweep is monotonic - the gate for joining BRIGHTNESS_MACROS" --info`

**If it passes:** copy the three printed sweeps into a comment above the test and continue to Step 2.

**If it fails for one voice:** do not weaken the assertion and do not widen the list. The correct fix is `Velocity.brightnessOverride`, which scopes a brightness macro to specific patches — that is exactly what THUMP SNARE uses. Add a branch to it for the failing voice and note in this plan's task why. If it fails for *all three*, `PEAK` is not a brightness macro on this engine; leave `BRIGHTNESS_MACROS` untouched, let GLINT use the `Velocity.soften` fallback, and record the decision in the spec.

- [ ] **Step 2: Write the failing test**

Append to `GlintTest.kt`:

```kotlin
    @Test
    fun `GLINT uses PEAK for velocity, not the soften fallback`() {
        val patch = GlintPatch("Vel Test", GlintVoice.REED, Glint.defaults(GlintVoice.REED))
        assertEquals("PEAK", Velocity.brightnessSpec(patch)?.name, "GLINT should render velocity through PEAK")
    }

    @Test
    fun `atVelocity is genuinely darker at low velocity`() {
        for (voice in GlintVoice.entries) {
            val patch = GlintPatch("Vel $voice", voice, Glint.defaults(voice))
            val soft = FeatureExtractor.extract(Velocity.atVelocity(patch, 0.25f)).centroidHz
            val hard = FeatureExtractor.extract(Velocity.atVelocity(patch, 1f)).centroidHz
            assertTrue(soft < hard, "$voice: a soft hit must be darker, got soft=$soft hard=$hard")
        }
    }
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.GlintTest"`
Expected: FAIL — `GLINT uses PEAK for velocity` fails with `expected:<PEAK> but was:<null>`.

- [ ] **Step 4: Write the minimal implementation**

In `Velocity.kt`, add `"PEAK"` to the end of the list on line 418:

```kotlin
    private val BRIGHTNESS_MACROS = listOf("BRIGHT", "CUTOFF", "TONE", "METAL", "PERC", "PEAK")
```

Append to the list's KDoc, just above it, the sweep you recorded in Step 1, in the same form the SNAP entry uses:

```
 * PEAK (GLINT) joins on a measured sweep, not on the name: PEAK 0→1 in
 * eighths, nine points, FeatureExtractor.extract(_).centroidHz, rising at
 * every step on all three voices — REED <numbers>, BOTTLE <numbers>,
 * KAZOO <numbers>, measured 2026-09-25. Physically it is the honest knob:
 * strike a reed harder and the formant rises. Re-run the sweep if the
 * window shapes or ratioFor's mapping ever change.
```

Replace `<numbers>` with the three comma-separated readings actually printed in Step 1. **Do not invent them.**

Then add GLINT to the `cases` list in `VelocityGrooveShuffleTest.kt` (lines 132–139). GLINT has no presets in Phase 1, so construct the patch directly:

```kotlin
            "GLINT REED (PEAK)" to GlintPatch("Vel Canary", GlintVoice.REED, Glint.defaults(GlintVoice.REED)),
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.GlintTest" --tests "com.snipsnap.synth.VelocityGrooveShuffleTest"`
Expected: PASS for both classes. `VelocityGrooveShuffleTest`'s own darker-at-low-velocity assertion now covers GLINT too.

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt synth/src/test/kotlin/com/snipsnap/synth/GlintTest.kt synth/src/test/kotlin/com/snipsnap/synth/VelocityGrooveShuffleTest.kt
git commit -m "Register PEAK as GLINT's brightness macro, on a measured sweep

BRIGHTNESS_MACROS is not a list you append to — its KDoc records THUMP
SNARE reading brighter soft than hard on TONE, and the sweep that had to
be produced before SNAP replaced it. PEAK earns its place the same way:
nine points, all three voices, rising at every step, numbers recorded
above the list.

Physically it is the honest knob. Strike a reed harder and the formant
rises."
```

---

### Task 8: Wire GLINT into the app and the shared engine tests

**Files:**
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt` (the `Engine` enum at line 979, its seven dispatchers at lines 985–1072, and the `drumClass` extensions near line 1162)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/DeterminismTest.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/PadRecipeTest.kt` (`onePatchPerEngine()`, lines 25–33)

**Interfaces:**
- Consumes: everything Tasks 1–6 produced.
- Produces: GLINT selectable in the synth panel.

**Note:** `DeterminismTest` has no shared enumeration — each engine carries its own `@Test fun`. `PresetsTest` is Phase 3's, since it asserts preset rosters.

- [ ] **Step 1: Write the failing tests**

In `synth/src/test/kotlin/com/snipsnap/synth/DeterminismTest.kt`, add alongside the existing per-engine tests:

```kotlin
    @Test
    fun `GLINT is byte-identical across renders`() {
        val patch = GlintPatch("Canary", GlintVoice.BOTTLE, Glint.defaults(GlintVoice.BOTTLE))
        assertContentEquals(patch.render().samples, patch.render().samples)
    }
```

In `synth/src/test/kotlin/com/snipsnap/synth/PadRecipeTest.kt`, add one entry to `onePatchPerEngine()`'s list, after the `SnapPatch` line:

```kotlin
        GlintPatch("Glass Test", GlintVoice.BOTTLE, mapOf("PEAK" to 0.7f)),
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.DeterminismTest" --tests "com.snipsnap.synth.PadRecipeTest"`
Expected: FAIL — compilation error until Task 6's `GlintPatch` is on the classpath; if Task 6 is already committed these will PASS immediately, which is a legitimate outcome for this step. Note which happened and continue.

- [ ] **Step 3: Write the implementation**

In `app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt`, add `GLINT` to the enum (line 979–980), keeping the trailing semicolon:

```kotlin
private enum class Engine {
    THUMP, SKIN, TINES, VELVET, VOX, PLUCK, TONEWHEEL, FATHOM, RESIN, GLINT;
```

Add one line to each of the seven `when (this)` dispatchers, in each case as the last branch before the closing brace:

```kotlin
    // in voices()
        GLINT -> GlintVoice.entries

    // in macrosFor(voice)
        GLINT -> Glint.macrosFor(voice as GlintVoice)

    // in defaults(voice)
        GLINT -> Glint.defaults(voice as GlintVoice)

    // in scramble(voice, random)
        GLINT -> Glint.scramble(voice as GlintVoice, random)

    // in render(voice, macros)
        GLINT -> Glint.render(voice as GlintVoice, macros)

    // in drumClass(voice)
        GLINT -> (voice as GlintVoice).drumClass

    // in buildPatch(name, voice, macros)
        GLINT -> GlintPatch(name, voice as GlintVoice, macros)
```

Add the extension beside the others near line 1162:

```kotlin
// GLINT's voices are pitched notes with a formant on them — the same
// "tonal-pitched voices -> TONAL" fallback VELVET/VOX/PLUCK/TONEWHEEL/RESIN
// take, never judged from a render.
private val GlintVoice.drumClass: DrumClass
    get() = DrumClass.TONAL
```

- [ ] **Step 4: Run the full module and compile the app**

```bash
./gradlew :synth:test
./gradlew :app:compileDebugKotlin
```

Expected: `:synth:test` PASS in full. The app target compiles only when an Android SDK is present (`settings.gradle.kts` includes `:app` conditionally) — if it is absent, the task is still complete; say so explicitly rather than reporting a pass you did not observe.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt synth/src/test/kotlin/com/snipsnap/synth/DeterminismTest.kt synth/src/test/kotlin/com/snipsnap/synth/PadRecipeTest.kt
git commit -m "Wire GLINT into the synth panel and the shared engine tests

Seven parallel when-dispatchers plus the drumClass extension — all three
voices are pitched notes with a formant on them, so TONAL, the same
fallback every other tonal engine takes.

DeterminismTest carries one test per engine rather than an enumeration,
so GLINT gets its own byte-identity canary."
```

---

### Task 9: Roadmap row and the audition gate

**Files:**
- Modify: `docs/SYNTH_ROADMAP.md`

**Interfaces:** none — documentation.

- [ ] **Step 1: Add the S9 row**

In `docs/SYNTH_ROADMAP.md`, add to the phasing table after the S8 row:

```markdown
| S9 | **Phase 1 shipped** — GLINT, the phase-distortion engine (REED/BOTTLE/KAZOO: a sine burst at `k`× the fundamental windowed to zero by each cycle's end, so the formant sweeps while the pitch does not move; TUNE/PEAK/FOLLOW/BODY/BLOOM/DECAY, FOLLOW morphing the peak between absolute Hz and note-tracking over `Dsp.keyTrack`, PEAK snapping to integer harmonics up to k=12, the body decaying faster than the burst so a note fades to glass) — design in `docs/superpowers/specs/2026-09-25-glint-phase-distortion-design.md`. TRACE (the window taken from your own material) and the preset roster are Phases 2 and 3, gated on the audition. | S1 + U5 + U6 |
```

- [ ] **Step 2: Verify nothing else claims S9**

Run: `grep -n '^| S9' docs/SYNTH_ROADMAP.md`
Expected: exactly one line — the one you just added.

- [ ] **Step 3: Commit**

```bash
git add docs/SYNTH_ROADMAP.md
git commit -m "Record GLINT Phase 1 on the synth roadmap as S9"
```

- [ ] **Step 4: Stop for the audition gate**

**Do not begin Phase 2 or Phase 3.** The spec gates both on Josh auditioning Phase 1 — presets authored blind from the DSP are disposable by standing decision, and the voices themselves may change once they are heard. Report Phase 1 complete and say that the audition is the next step, along with the three PEAK sweeps recorded in Task 7 and anything that had to be tuned away from the constants this plan specified.

---

## Coverage against the spec

| Spec section | Task |
|---|---|
| Synthesis property 1 — `w(1) = 0` frees `k` | 1 (window test), 2 (no-click test) |
| Synthesis property 2 — inner sine resets at the wrap | 2 (pitch invariance), 5 (k time-varying) |
| Synthesis property 3 — `k` clamped `[2, 40]` | 3 (floor at every note/FOLLOW/PEAK) |
| Synthesis property 4 — window mean-removed | 1 (mean integration), 4 (DC at every BODY) |
| Sample rate / U6 oversample contract | 1 (oversampled-path test) |
| Three voices, window per voice | 1 |
| Six macros | 1 (contract test), 2–5 (behaviour) |
| FOLLOW over `Dsp.keyTrack` | 3 |
| Harmonic snap, base only | 2 |
| Glass tail | 4 |
| `PEAK` in `BRIGHTNESS_MACROS` | 7, with the monotonicity gate |
| Patch + JSON round-trip + rejections | 6 |
| Registration: `Patches`, `Velocity`, `SynthScreen`, shared tests | 6, 7, 8 |
| Roadmap row | 9 |
| TRACE, presets, `Presets.kt`, keygroups | **not in this plan** — Phases 2 and 3 |
