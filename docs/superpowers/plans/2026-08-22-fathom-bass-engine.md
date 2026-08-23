# FATHOM Bass Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add FATHOM, a bass engine with three voices (DEEP/GRIND/GLASS) whose headline feature is a pitch glide available on every voice.

**Architecture:** One new file `Fathom.kt` shaped exactly like the existing `Velvet.kt` — a voice enum plus an `object` exposing `macrosFor` / `defaults` / `scramble` / `frequencyFor` / `render`. All voices share one signal path: source → engine-owned drive → `Dsp.TptSvf` low-pass → amp envelope. Two small additions to existing files register the patch type so a FATHOM pad round-trips through `kit.json`.

**Tech Stack:** Kotlin/JVM, JDK 17, Gradle 8.14.3, `kotlin.test`. No new dependencies.

## Global Constraints

- **Naming (legal guardrail, `docs/SYNTH_ROADMAP.md`):** no trademarked names, model numbers, obvious near-misses, or voices named after people. The engine is `FATHOM`; voices are `DEEP`, `GRIND`, `GLASS`. Never write "808", "303", "Reese", or "DX" in code, comments, KDoc, or commit messages.
- **Every macro is a `Float` in `0f..1f`** and no value anywhere in that range may produce a broken sound. Clamp with `coerceIn(0f, 1f)`.
- **Six macros per voice.** Five shared (`TUNE` `GLIDE` `DRIVE` `CUTOFF` `DECAY`) plus one voice-specific (`SWEEP` / `SPREAD` / `RATIO`).
- **`TUNE` snaps to semitones** over `TUNE_SEMITONES = 24`, exactly as `Velvet.frequencyFor` does.
- **Renders are deterministic** — same macros must produce byte-identical output. No `Random` inside `render`.
- **No NaN and no DC offset** under any macro combination.
- **One-shots only.** Do not add any keygroup or multisample rendering; roadmap step S5 converts all engines at once.
- **Run tests with:** `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` before any `./gradlew` command.

---

## File Structure

| File | Responsibility |
|---|---|
| `synth/src/main/kotlin/com/snipsnap/synth/Fathom.kt` | **Create.** The whole engine: voice enum, macro specs, frequency/ratio snapping, drive, and `render`. |
| `synth/src/test/kotlin/com/snipsnap/synth/FathomTest.kt` | **Create.** Engine tests, following `VelvetTest.kt` beat for beat. |
| `synth/src/main/kotlin/com/snipsnap/synth/Patches.kt` | **Modify.** Add `FathomPatch` data class; add one branch to `Patches.fromJsonValue`'s `when`. |

`Fathom.kt` stays one file: it is the same size and shape as `Velvet.kt` (≈150 lines), and splitting an engine across files would break the pattern every other engine follows.

---

### Task 1: Engine skeleton and the DEEP voice

Establishes the file, the macro contract, and one working voice — sine with a `SWEEP` attack blip, engine-owned `DRIVE`, filter and amp envelope. No `GLIDE` yet; that is Task 2.

**Files:**
- Create: `synth/src/main/kotlin/com/snipsnap/synth/Fathom.kt`
- Create: `synth/src/test/kotlin/com/snipsnap/synth/FathomTest.kt`

**Interfaces:**
- Consumes: `Dsp.RATE`, `Dsp.lin`, `Dsp.expMap`, `Dsp.envAt`, `Dsp.normalize`, `Dsp.fadeTail`, `Dsp.TptSvf`, `MacroSpec`, `com.snipsnap.audio.Snip` — all existing.
- Produces:
  - `enum class FathomVoice { DEEP, GRIND, GLASS }` (all three declared now; GRIND/GLASS render in later tasks)
  - `Fathom.TUNE_SEMITONES: Int = 24`
  - `Fathom.macrosFor(voice: FathomVoice): List<MacroSpec>`
  - `Fathom.defaults(voice: FathomVoice): Map<String, Float>`
  - `Fathom.scramble(voice: FathomVoice, random: Random): Map<String, Float>`
  - `Fathom.frequencyFor(voice: FathomVoice, tune: Float): Float`
  - `Fathom.render(voice: FathomVoice, macros: Map<String, Float> = emptyMap()): Snip`

- [ ] **Step 1: Write the failing test**

Create `synth/src/test/kotlin/com/snipsnap/synth/FathomTest.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FathomTest {

    @Test
    fun `DEEP renders clean audio at defaults and both corners`() {
        for (macros in listOf(
            emptyMap(),
            Fathom.macrosFor(FathomVoice.DEEP).associate { it.name to 0f },
            Fathom.macrosFor(FathomVoice.DEEP).associate { it.name to 1f },
        )) {
            val snip = Fathom.render(FathomVoice.DEEP, macros)
            assertTrue(snip.samples.isNotEmpty(), "DEEP rendered nothing for $macros")
            assertTrue(snip.samples.all { it.isFinite() }, "DEEP rendered NaN/Inf for $macros")
            assertTrue(snip.samples.any { kotlin.math.abs(it) > 0.1f }, "DEEP rendered silence for $macros")
            val dc = snip.samples.average().toFloat()
            assertTrue(kotlin.math.abs(dc) < 0.05f, "DEEP has DC offset $dc for $macros")
        }
    }

    @Test
    fun `DEEP is deterministic`() {
        val a = Fathom.render(FathomVoice.DEEP, mapOf("DRIVE" to 0.7f))
        val b = Fathom.render(FathomVoice.DEEP, mapOf("DRIVE" to 0.7f))
        assertTrue(a.samples.contentEquals(b.samples), "same macros must render the same bytes")
    }

    @Test
    fun `every voice declares exactly six macros`() {
        for (voice in FathomVoice.entries) {
            assertEquals(6, Fathom.macrosFor(voice).size, "$voice should declare six macros")
        }
    }

    @Test
    fun `TUNE snaps to semitones and actually tunes`() {
        val distinct = HashSet<Float>()
        for (i in 0..100) distinct.add(Fathom.frequencyFor(FathomVoice.DEEP, i / 100f))
        assertEquals(Fathom.TUNE_SEMITONES + 1, distinct.size)

        // Measured from TUNE 0.5 rather than 0. DEEP's root is 41.2 Hz and
        // Pitch.MIN_HZ is 40f, so the bottom of the range sits on the
        // detector's floor and reads as "no pitch" — a limit of the measuring
        // tool, not of the engine. 0.5 -> 1.0 is one octave, 82.4 Hz ->
        // 164.8 Hz, both comfortably inside the detector's range.
        val low = TestPitch.estimate(
            Fathom.render(FathomVoice.DEEP, mapOf("TUNE" to 0.5f, "SWEEP" to 0f)),
            fromSec = 0.05f, windowSec = 0.2f,
        )
        val high = TestPitch.estimate(
            Fathom.render(FathomVoice.DEEP, mapOf("TUNE" to 1f, "SWEEP" to 0f)),
            fromSec = 0.05f, windowSec = 0.2f,
        )
        assertTrue(
            high > low * 1.8f && high < low * 2.2f,
            "TUNE 0.5 -> 1 is one octave: $low Hz -> $high Hz",
        )
    }

    @Test
    fun `DRIVE adds harmonics without adding level`() {
        val clean = FeatureExtractor.extract(Fathom.render(FathomVoice.DEEP, mapOf("DRIVE" to 0f)))
        val dirty = FeatureExtractor.extract(Fathom.render(FathomVoice.DEEP, mapOf("DRIVE" to 1f)))
        assertTrue(
            dirty.centroidHz > clean.centroidHz * 1.3f,
            "DRIVE should brighten: ${clean.centroidHz}Hz -> ${dirty.centroidHz}Hz",
        )
    }

    @Test
    fun `CUTOFF opens`() {
        val dark = FeatureExtractor.extract(Fathom.render(FathomVoice.DEEP, mapOf("CUTOFF" to 0.05f)))
        val open = FeatureExtractor.extract(Fathom.render(FathomVoice.DEEP, mapOf("CUTOFF" to 0.95f)))
        assertTrue(
            open.centroidHz > dark.centroidHz * 1.5f,
            "CUTOFF up should brighten: ${dark.centroidHz}Hz -> ${open.centroidHz}Hz",
        )
    }

    @Test
    fun `a bass note is harmonic, not noise`() {
        // Deliberately NOT asserting a DrumClass. VelvetTest discovered the
        // classifier files harmonic stabs under PERC - "the classifier's
        // honest shelf for a harmonic hit" - so predicting the label for a
        // new engine is guesswork. Flatness measures the thing that actually
        // matters. See Step 4 for pinning the label once it is observed.
        for (voice in FathomVoice.entries) {
            val f = FeatureExtractor.extract(Fathom.render(voice))
            assertTrue(f.flatness < 0.2f, "$voice should measure harmonic, got flatness ${f.flatness}")
        }
    }

    @Test
    fun `DECAY lengthens`() {
        val short = FeatureExtractor.extract(Fathom.render(FathomVoice.DEEP, mapOf("DECAY" to 0.1f)))
        val long = FeatureExtractor.extract(Fathom.render(FathomVoice.DEEP, mapOf("DECAY" to 0.9f)))
        assertTrue(
            long.decayMs > short.decayMs * 1.5f,
            "DECAY should stretch the note: ${short.decayMs}ms -> ${long.decayMs}ms",
        )
    }

    @Test
    fun `scrambles are reproducible and never garbage`() {
        for (voice in FathomVoice.entries) {
            val a = Fathom.scramble(voice, Random(7))
            val b = Fathom.scramble(voice, Random(7))
            assertEquals(a, b, "$voice scramble must be reproducible from a seed")
            assertTrue(a.values.all { it in 0f..1f }, "$voice scramble left the 0..1 range")
        }
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :synth:test --console=plain --tests '*FathomTest*'
```

Expected: FAIL — compilation error, `Unresolved reference: Fathom`.

- [ ] **Step 3: Write the engine**

Create `synth/src/main/kotlin/com/snipsnap/synth/Fathom.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * FATHOM — the bass engine.
 *
 * The other engines already cover most of what "bass" usually means: VELVET's
 * BASS voice has a sub oscillator, SQUELCH is resonance-and-envelope acid, and
 * a THUMP kick with a long decay is a boom. FATHOM exists for the three things
 * none of them can do — a pitch envelope travelling *between* notes, two
 * detuned oscillators beating over a tail long enough to hear it, and FM tuned
 * for the bottom rather than for percussive bite.
 *
 * Every voice runs the same path: source → DRIVE → resonant low-pass → amp
 * envelope. Drive sits **before** the filter deliberately. Saturation makes
 * harmonics and the filter has to be downstream to shape them; running an FX
 * distortion after the filter is why that combination sounds like a blanket.
 *
 * GLIDE is on every voice rather than being one voice's trick, because a slide
 * is a performance gesture, not a timbre.
 */
enum class FathomVoice { DEEP, GRIND, GLASS }

object Fathom {

    const val TUNE_SEMITONES = 24

    fun macrosFor(voice: FathomVoice): List<MacroSpec> = when (voice) {
        FathomVoice.DEEP -> listOf(
            MacroSpec("TUNE", 0.25f), MacroSpec("GLIDE", 0f), MacroSpec("DRIVE", 0.3f),
            MacroSpec("CUTOFF", 0.4f), MacroSpec("DECAY", 0.55f), MacroSpec("SWEEP", 0.35f),
        )
        FathomVoice.GRIND -> listOf(
            MacroSpec("TUNE", 0.3f), MacroSpec("GLIDE", 0f), MacroSpec("DRIVE", 0.45f),
            MacroSpec("CUTOFF", 0.35f), MacroSpec("DECAY", 0.7f), MacroSpec("SPREAD", 0.4f),
        )
        FathomVoice.GLASS -> listOf(
            MacroSpec("TUNE", 0.35f), MacroSpec("GLIDE", 0f), MacroSpec("DRIVE", 0.4f),
            MacroSpec("CUTOFF", 0.5f), MacroSpec("DECAY", 0.45f), MacroSpec("RATIO", 0.25f),
        )
    }

    fun defaults(voice: FathomVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    fun scramble(voice: FathomVoice, random: Random): Map<String, Float> =
        macrosFor(voice).associate { it.name to random.nextFloat() }

    fun frequencyFor(voice: FathomVoice, tune: Float): Float {
        val root = when (voice) {
            FathomVoice.DEEP -> 41.2f    // E1 — low enough to feel
            FathomVoice.GRIND -> 55f     // A1
            FathomVoice.GLASS -> 55f     // A1
        }
        val semis = Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
        return root * 2f.pow(semis / 12f)
    }

    /**
     * The engine's own saturation. Normalised by `tanh(k)` so turning DRIVE up
     * adds harmonics without also acting as a volume knob — a drive control
     * that doubles as a gain control is impossible to set by ear.
     */
    private fun drive(x: Float, amount: Float): Float {
        // The ceiling is high because a sine starts with nothing above the
        // fundamental: without enough folding, CUTOFF has no harmonics to
        // open onto and the filter appears to do nothing.
        val k = Dsp.lin(amount, 1f, 48f)
        return (tanh((k * x).toDouble()) / tanh(k.toDouble())).toFloat()
    }

    fun render(voice: FathomVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val base = frequencyFor(voice, m.getValue("TUNE"))
        val driveAmt = m.getValue("DRIVE")
        val cutoff = m.getValue("CUTOFF")
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.2f, 1.6f)

        // Bass wants a low, gently resonant filter; the range tops out well
        // short of the SVF's stable limit because nothing here needs air.
        val fc = Dsp.expMap(cutoff, 90f, 4_000f)
        val damp = 1.2f

        // SWEEP: a fast downward pitch blip at the attack. This is the thump,
        // and it is a different envelope from GLIDE — attack, not journey.
        val sweepSemis = Dsp.lin(m["SWEEP"] ?: 0f, 0f, 30f)
        val sweepT60 = 0.035f

        val out = FloatArray((t60 * 1.4f * RATE).toInt().coerceAtLeast(64))
        val svf = Dsp.TptSvf()
        var phase = 0.0
        for (i in out.indices) {
            val t = i.toFloat() / RATE
            val blip = 2f.pow(sweepSemis * Dsp.envAt(t, sweepT60) / 12f)
            phase += base * blip / RATE

            val source = sin(2.0 * PI * phase).toFloat()
            val driven = drive(source, driveAmt)
            svf.process(driven, fc, damp)

            val attack = (t / 0.004f).coerceAtMost(1f)
            out[i] = svf.low * attack * Dsp.envAt(t, t60)
        }

        Dsp.normalize(out)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :synth:test --console=plain --tests '*FathomTest*'
```

Expected: all nine PASS.

If a macro test fails, widen that macro's range rather than weakening the assertion — a knob that doesn't audibly do anything across its full travel is a design bug. The `drive()` ceiling is already at `48f` for exactly this reason: at `24f` the sine had too few harmonics for `CUTOFF opens` to measure any brightening.

Then **observe** what the classifier makes of the voices and pin it, rather than predicting it. Add this temporary test, run it, and read the three labels out of the output:

```kotlin
    @Test
    fun `TEMPORARY - print the classifier verdicts`() {
        for (voice in FathomVoice.entries) {
            println("$voice -> ${Classifier.classify(Fathom.render(voice)).drumClass}")
        }
    }
```

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :synth:test --console=plain --tests '*FathomTest*' --info 2>&1 | grep -E "DEEP ->|GRIND ->|GLASS ->"
```

Then **delete that temporary test** and replace it with the real assertion:

```kotlin
    @Test
    fun `factory defaults classify consistently`() {
        // Labels observed, not predicted - see the note in `a bass note is
        // harmonic, not noise` for why.
        assertEquals(DrumClass.<OBSERVED>, Classifier.classify(Fathom.render(FathomVoice.DEEP)).drumClass)
        assertEquals(DrumClass.<OBSERVED>, Classifier.classify(Fathom.render(FathomVoice.GRIND)).drumClass)
        assertEquals(DrumClass.<OBSERVED>, Classifier.classify(Fathom.render(FathomVoice.GLASS)).drumClass)
    }
```

Replace each `<OBSERVED>` with what the run actually printed. A DEEP voice with a SWEEP blip may well read `KICK`, which is correct — it *is* one. Do not tune the engine to satisfy a label you guessed.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Fathom.kt synth/src/test/kotlin/com/snipsnap/synth/FathomTest.kt
git commit -m "FATHOM: the engine skeleton and its DEEP voice

Sine, a fast SWEEP pitch blip at the attack, engine-owned saturation before
the filter, amp envelope. Drive is normalised by tanh(k) so it adds harmonics
without doubling as a gain control."
```

---

### Task 2: GLIDE — the headline gesture, on every voice

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Fathom.kt` (inside `render`)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/FathomTest.kt` (add two tests)

**Interfaces:**
- Consumes: everything Task 1 produced.
- Produces: no new public signatures. `render` gains glide behaviour driven by the existing `GLIDE` macro.

- [ ] **Step 1: Write the failing tests**

Append to `FathomTest.kt`, inside the class:

```kotlin
    @Test
    fun `GLIDE actually glides - pitch rises into the target`() {
        // Long decay so both analysis windows sit inside the note, and SWEEP
        // off so the attack blip cannot be mistaken for the glide.
        // TUNE=1 puts the target at 164.8 Hz, so a full GLIDE starts an
        // octave below at 82.4 Hz. Both ends clear Pitch.MIN_HZ = 40f. At
        // TUNE=0.5 the glide would START at 41.2 Hz, on the detector's floor,
        // and read as no pitch — the same trap the TUNE test hit in Task 1.
        val snip = Fathom.render(
            FathomVoice.DEEP,
            mapOf("GLIDE" to 1f, "DECAY" to 0.9f, "SWEEP" to 0f, "TUNE" to 1f),
        )
        val start = TestPitch.estimate(snip, fromSec = 0.02f, windowSec = 0.12f)
        val end = TestPitch.estimate(snip, fromSec = 0.55f, windowSec = 0.25f)
        assertTrue(start > 0f && end > 0f, "pitch detection failed: $start Hz -> $end Hz")
        assertTrue(end > start * 1.3f, "GLIDE should rise into the target: $start Hz -> $end Hz")
    }

    @Test
    fun `GLIDE at zero holds a steady pitch`() {
        val snip = Fathom.render(
            FathomVoice.DEEP,
            mapOf("GLIDE" to 0f, "DECAY" to 0.9f, "SWEEP" to 0f, "TUNE" to 1f),
        )
        val start = TestPitch.estimate(snip, fromSec = 0.02f, windowSec = 0.12f)
        val end = TestPitch.estimate(snip, fromSec = 0.55f, windowSec = 0.25f)
        assertTrue(start > 0f && end > 0f, "pitch detection failed: $start Hz -> $end Hz")
        assertTrue(
            kotlin.math.abs(end - start) < start * 0.1f,
            "GLIDE 0 should hold steady: $start Hz -> $end Hz",
        )
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :synth:test --console=plain --tests '*FathomTest*'
```

Expected: `GLIDE actually glides` FAILS (pitch is flat because nothing reads the macro yet). `GLIDE at zero` already passes — that is fine and expected; it is the control.

- [ ] **Step 3: Implement glide**

In `Fathom.render`, add these declarations immediately after the `sweepT60` line:

```kotlin
        // GLIDE: start up to an octave below the target and slide into it.
        // Unipolar and upward-only on purpose — a downward or overshooting
        // slide puts "sounds like a mistake" inside the knob's travel.
        val glideSemis = Dsp.lin(m.getValue("GLIDE"), 0f, 12f)
        // Always completes well inside the note. A slide still travelling when
        // the sound ends is the one way this can sound broken, so make it
        // impossible rather than documenting it.
        val glideTime = t60 * 0.35f
```

Then replace the phase-advance line inside the sample loop:

```kotlin
            val blip = 2f.pow(sweepSemis * Dsp.envAt(t, sweepT60) / 12f)
            phase += base * blip / RATE
```

with:

```kotlin
            val blip = 2f.pow(sweepSemis * Dsp.envAt(t, sweepT60) / 12f)
            // Linear in semitones, which is what a portamento should be:
            // constant semitones per second reads as an even slide.
            val glideAt = (1f - t / glideTime).coerceIn(0f, 1f)
            val slide = 2f.pow(-glideSemis * glideAt / 12f)
            phase += base * blip * slide / RATE
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :synth:test --console=plain --tests '*FathomTest*'
```

Expected: all nine PASS.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Fathom.kt synth/src/test/kotlin/com/snipsnap/synth/FathomTest.kt
git commit -m "FATHOM: GLIDE, on every voice

A slide is a performance gesture rather than a timbre, so it is a macro every
voice carries, not one voice's trick. Unipolar and upward-only, and clamped to
finish inside DECAY - the two ways it could sound wrong are both made
unreachable rather than documented. Tested by measuring pitch at the start and
end of the note, which is the feature itself rather than a proxy for it."
```

---

### Task 3: The GRIND voice

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Fathom.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/FathomTest.kt`

**Interfaces:**
- Consumes: everything Tasks 1–2 produced.
- Produces: no new public signatures. `render` handles `FathomVoice.GRIND` with a `SPREAD`-detuned saw pair.

- [ ] **Step 1: Write the failing tests**

Append to `FathomTest.kt`, inside the class:

```kotlin
    /** Sign changes of the mean-removed amplitude envelope — a beat counter. */
    private fun beatCrossings(snip: Snip): Int {
        val win = Dsp.RATE / 100                       // 10 ms envelope frames
        val env = snip.samples.asIterable().chunked(win) { frame ->
            frame.maxOf { kotlin.math.abs(it) }
        }
        val body = env.drop(5).dropLast(5)             // skip attack and tail
        if (body.size < 4) return 0
        val mean = body.average().toFloat()
        var crossings = 0
        for (i in 1 until body.size) {
            if ((body[i - 1] - mean) * (body[i] - mean) < 0f) crossings++
        }
        return crossings
    }

    @Test
    fun `GRIND renders clean audio at defaults and both corners`() {
        for (macros in listOf(
            emptyMap(),
            Fathom.macrosFor(FathomVoice.GRIND).associate { it.name to 0f },
            Fathom.macrosFor(FathomVoice.GRIND).associate { it.name to 1f },
        )) {
            val snip = Fathom.render(FathomVoice.GRIND, macros)
            assertTrue(snip.samples.all { it.isFinite() }, "GRIND rendered NaN/Inf for $macros")
            assertTrue(snip.samples.any { kotlin.math.abs(it) > 0.1f }, "GRIND rendered silence for $macros")
        }
    }

    @Test
    fun `SPREAD beats - wider detune modulates the envelope faster`() {
        val narrow = beatCrossings(Fathom.render(FathomVoice.GRIND, mapOf("SPREAD" to 0f, "DECAY" to 1f)))
        val wide = beatCrossings(Fathom.render(FathomVoice.GRIND, mapOf("SPREAD" to 1f, "DECAY" to 1f)))
        assertTrue(wide > narrow, "SPREAD should beat faster: $narrow -> $wide envelope crossings")
    }
```

Add `import com.snipsnap.audio.Snip` to the test file's imports.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :synth:test --console=plain --tests '*FathomTest*'
```

Expected: `SPREAD beats` FAILS — GRIND currently renders the same sine DEEP does, so both detune settings give the same envelope.

- [ ] **Step 3: Implement GRIND**

Add this private helper to `Fathom`, next to `drive`:

```kotlin
    /** Naive saw from a 0..1 phase. Aliases; lo-fi is on-brand. */
    private fun saw(phase: Double): Float = (2.0 * (phase - Math.floor(phase)) - 1.0).toFloat()
```

Add this declaration in `render`, after `glideTime`:

```kotlin
        // SPREAD is a beat-rate knob. Bass is low, so even a wide detune
        // beats slowly — a throb at the bottom of the knob, a growl at the top.
        val spreadCents = Dsp.lin(m["SPREAD"] ?: 0f, 4f, 90f)
        val detune = 2f.pow(spreadCents / 1200f)
```

Add a second phase accumulator beside `var phase = 0.0`:

```kotlin
        var phase2 = 0.0
```

**First, hoist the pitch.** Task 2 folded `blip` and `slide` directly into the single `phase +=` line. With a second oscillator arriving, that has to become one shared value — otherwise a new oscillator can read `base` directly and silently skip the glide, and Task 4's FM would drift its carrier/modulator ratio mid-slide. Replace:

```kotlin
            phase += base * blip * slide / RATE
```

with:

```kotlin
            // One pitch, derived once: every oscillator must inherit the
            // SWEEP blip and the GLIDE slide, or it will drift away from
            // the others mid-note.
            val pitchHz = base * blip * slide
            phase += pitchHz / RATE
```

Then replace the source line inside the loop:

```kotlin
            val source = sin(2.0 * PI * phase).toFloat()
```

with:

```kotlin
            val source = when (voice) {
                FathomVoice.GRIND -> {
                    phase2 += pitchHz * detune / RATE
                    // The hollowness *is* the beating between the two saws.
                    // No comb or notch stage — interference alone does it.
                    0.5f * (saw(phase) + saw(phase2))
                }
                else -> sin(2.0 * PI * phase).toFloat()
            }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :synth:test --console=plain --tests '*FathomTest*'
```

Expected: all eleven PASS. If `SPREAD beats` is flaky, raise the `90f` cents ceiling — do not weaken the assertion, and do not add a comb filter, which the spec rules out.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Fathom.kt synth/src/test/kotlin/com/snipsnap/synth/FathomTest.kt
git commit -m "FATHOM: GRIND, two saws left to interfere

SPREAD reads as a beat-rate knob - slow throb at the bottom, growl at the top.
The hollowness is the beating itself, so there is no comb or notch stage. The
test counts envelope crossings, which measures the beating directly."
```

---

### Task 4: The GLASS voice

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Fathom.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/FathomTest.kt`

**Interfaces:**
- Consumes: everything Tasks 1–3 produced.
- Produces: `Fathom.ratioFor(macro: Float): Float` — the snapped FM ratio, public so the snap can be tested directly the way `frequencyFor` is.

- [ ] **Step 1: Write the failing tests**

Append to `FathomTest.kt`, inside the class:

```kotlin
    @Test
    fun `GLASS renders clean audio at defaults and both corners`() {
        for (macros in listOf(
            emptyMap(),
            Fathom.macrosFor(FathomVoice.GLASS).associate { it.name to 0f },
            Fathom.macrosFor(FathomVoice.GLASS).associate { it.name to 1f },
        )) {
            val snip = Fathom.render(FathomVoice.GLASS, macros)
            assertTrue(snip.samples.all { it.isFinite() }, "GLASS rendered NaN/Inf for $macros")
            assertTrue(snip.samples.any { kotlin.math.abs(it) > 0.1f }, "GLASS rendered silence for $macros")
        }
    }

    @Test
    fun `RATIO snaps - the knob yields exactly the ratio set and no more`() {
        val distinct = HashSet<Float>()
        for (i in 0..200) distinct.add(Fathom.ratioFor(i / 200f))
        assertEquals(Fathom.RATIOS.size, distinct.size, "RATIO must snap, never land between values")
        assertEquals(Fathom.RATIOS.toSet(), distinct, "every declared ratio should be reachable")
    }

    @Test
    fun `DRIVE on GLASS deepens the FM, not just the saturation`() {
        val soft = FeatureExtractor.extract(Fathom.render(FathomVoice.GLASS, mapOf("DRIVE" to 0f)))
        val hard = FeatureExtractor.extract(Fathom.render(FathomVoice.GLASS, mapOf("DRIVE" to 1f)))
        assertTrue(
            hard.centroidHz > soft.centroidHz * 1.5f,
            "DRIVE should add sidebands as well as harmonics: ${soft.centroidHz}Hz -> ${hard.centroidHz}Hz",
        )
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :synth:test --console=plain --tests '*FathomTest*'
```

Expected: FAIL — `Unresolved reference: ratioFor` and `Unresolved reference: RATIOS`.

- [ ] **Step 3: Implement GLASS**

Add to `Fathom`, above `macrosFor`:

```kotlin
    /**
     * The FM ratios RATIO snaps to, chosen for low end: sub-octave, unison, a
     * hollow fifth-ish, octave, and a metallic twelfth. Snapping is the same
     * guarantee TINES makes — the knob cannot land on a mistuning.
     */
    val RATIOS = floatArrayOf(0.5f, 1f, 1.5f, 2f, 3f)

    fun ratioFor(macro: Float): Float =
        RATIOS[Math.round(macro.coerceIn(0f, 1f) * (RATIOS.size - 1))]
```

Add these declarations in `render`, after the `spreadCents`/`detune` pair:

```kotlin
        // GLASS: DRIVE moves the FM index and the output saturation together,
        // the same one-knob-can't-be-ugly device VELVET uses for SQUEEZE.
        val fmRatio = ratioFor(m["RATIO"] ?: 0f)
        val fmIndex = Dsp.lin(driveAmt, 1f, 14f)
```

Add a third phase accumulator beside `phase2`:

```kotlin
        var phaseMod = 0.0
```

Extend the `when (voice)` in the loop with a `GLASS` branch, before `else`:

```kotlin
                FathomVoice.GLASS -> {
                    // Derived from the same pitchHz as the carrier: if the
                    // modulator missed the glide, the FM ratio would drift
                    // during the slide and the timbre would smear.
                    phaseMod += pitchHz * fmRatio / RATE
                    // The index rides the amp envelope, so the metallic edge
                    // decays faster than the fundamental. That is what real FM
                    // basses do, and it is what stops this being a static buzz.
                    val idx = fmIndex * Dsp.envAt(t, t60 * 0.6f)
                    sin(2.0 * PI * phase + idx * sin(2.0 * PI * phaseMod)).toFloat()
                }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :synth:test --console=plain --tests '*FathomTest*'
```

Expected: all fourteen PASS.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Fathom.kt synth/src/test/kotlin/com/snipsnap/synth/FathomTest.kt
git commit -m "FATHOM: GLASS, FM tuned for the bottom

RATIO snaps to five intervals so the knob cannot land on a mistuning. The
modulation index rides the amp envelope, so the metallic edge decays faster
than the fundamental - which is what keeps it from sounding like a static
preset. DRIVE moves index and saturation together, the same device VELVET
uses for SQUEEZE."
```

---

### Task 5: FathomPatch — round-tripping through kit.json

Without this a FATHOM pad cannot be saved. The repo already promises a kit folder rebuilds its WAVs bit-for-bit from `kit.json`, and an engine that cannot serialize would break that promise.

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Patches.kt`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/FathomTest.kt`

**Interfaces:**
- Consumes: `Fathom.macrosFor`, `Fathom.render`, `FathomVoice` from Tasks 1–4; `Patch`, `Patches.validateMacros`, `Patches.decode`, `Json`, `JsonValue` — all existing in `Patches.kt`.
- Produces: `FathomPatch(name: String, voice: FathomVoice, macros: Map<String, Float>)` with `FathomPatch.ENGINE = "FATHOM"`, `FathomPatch.fromJsonValue(value: JsonValue): Patch`, `FathomPatch.fromJsonText(text: String): Patch`.

- [ ] **Step 1: Write the failing test**

Append to `FathomTest.kt`, inside the class:

```kotlin
    @Test
    fun `a FATHOM patch round-trips through JSON`() {
        val original = FathomPatch(
            name = "Sliding Sub",
            voice = FathomVoice.DEEP,
            macros = mapOf("GLIDE" to 0.8f, "DRIVE" to 0.6f),
        )
        val restored = Patches.fromJsonText(original.toJsonText())
        assertEquals(original.engine, restored.engine)
        assertEquals(original.voiceName, restored.voiceName)
        assertEquals(original.macros, restored.macros)
        assertTrue(
            original.render().samples.contentEquals(restored.render().samples),
            "a restored patch must render identical audio",
        )
    }

    @Test
    fun `a FATHOM patch rejects a macro the voice does not have`() {
        assertFailsWith<IllegalArgumentException> {
            FathomPatch("Bad", FathomVoice.DEEP, mapOf("SPREAD" to 0.5f))
        }
    }
```

Add `import kotlin.test.assertFailsWith` to the test file's imports.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :synth:test --console=plain --tests '*FathomTest*'
```

Expected: FAIL — `Unresolved reference: FathomPatch`.

- [ ] **Step 3: Add the patch type**

In `synth/src/main/kotlin/com/snipsnap/synth/Patches.kt`, add this after the `VelvetPatch` class:

```kotlin
/** A saved FATHOM sound. */
data class FathomPatch(
    override val name: String,
    val voice: FathomVoice,
    override val macros: Map<String, Float>,
) : Patch {
    init {
        Patches.validateMacros(this, Fathom.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Fathom.render(voice, macros)

    companion object {
        const val ENGINE = "FATHOM"
        fun fromJsonValue(value: JsonValue): Patch =
            Patches.decode(value, ENGINE, { n -> FathomVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                FathomPatch(name, voice, macros)
            }
        fun fromJsonText(text: String): Patch = fromJsonValue(Json.parse(text))
    }
}
```

Then register it in `Patches.fromJsonValue`'s `when`, after the `VelvetPatch.ENGINE` line:

```kotlin
            FathomPatch.ENGINE -> FathomPatch.fromJsonValue(value)
```

- [ ] **Step 4: Run the full suite**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew test --console=plain
```

Expected: BUILD SUCCESSFUL. Run the whole suite, not just `:synth` — `Patches` is consumed by `:kit`, and an unregistered engine surfaces there.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Patches.kt synth/src/test/kotlin/com/snipsnap/synth/FathomTest.kt
git commit -m "FATHOM: patch serialization, so a bass pad survives kit.json

The repo promises a kit folder rebuilds its WAVs bit-for-bit from the sidecar.
An engine that could not serialize would quietly break that, so the round-trip
test asserts identical rendered audio rather than just equal fields."
```

---

### Task 6: Document the engine

**Files:**
- Modify: `docs/SYNTH_ROADMAP.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: the finished engine. No code changes.

- [ ] **Step 1: Add the roadmap row**

In `docs/SYNTH_ROADMAP.md`, add to the phasing table after the `S3.7` row:

```markdown
| S3.8 | **shipped** — FATHOM bass voices (DEEP/GRIND/GLASS: sine with a SWEEP attack blip, a SPREAD-detuned saw pair, low-tuned 2-op FM with a snapped RATIO), engine-owned DRIVE before the filter, and GLIDE on every voice | S1 |
```

- [ ] **Step 2: Add the README entry**

In `README.md`, in the `:synth` section after the VELVET paragraph, add:

```markdown
FATHOM is the bass engine, and it exists for the three things VELVET
structurally can't do: GLIDE, a pitch envelope travelling between notes and
available on every voice because a slide is a gesture rather than a timbre;
GRIND, two saws detuned by SPREAD and left to beat over a long tail, where the
hollowness *is* the interference; and GLASS, 2-op FM tuned for the bottom with
RATIO snapped so the knob can't land on a mistuning. DRIVE is owned by the
engine and sits before the filter — saturation makes harmonics and the filter
has to be downstream to shape them, which is why bass through an FX rack
distortion sounds like a blanket.
```

- [ ] **Step 3: Update the test count**

Count the tests and update the line in `README.md`:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew test --console=plain --rerun-tasks 2>&1 | grep -cE "PASSED"
```

Replace the number in `./gradlew test    # NNN tests across six modules` with the value printed.

- [ ] **Step 4: Verify the docs are consistent**

```bash
grep -n "FATHOM" README.md docs/SYNTH_ROADMAP.md
grep -rniE "808|303|reese|dx7?" synth/src/main/kotlin/com/snipsnap/synth/Fathom.kt docs/SYNTH_ROADMAP.md README.md
```

Expected: FATHOM appears in both docs. The second grep must print **nothing** — that is the naming guardrail holding.

- [ ] **Step 5: Commit**

```bash
git add README.md docs/SYNTH_ROADMAP.md
git commit -m "FATHOM lands on the roadmap and the README"
```

---

## Notes for the implementer

**Two judgement calls the spec deliberately left open**, both internal and cheap to revise:

1. **The `RATIOS` set for GLASS.** The plan starts with `0.5, 1, 1.5, 2, 3`. Pick by ear; if a value sounds like a mistuning rather than a character, replace it. Update the `RATIO snaps` test only if the set's *size* changes — it reads `Fathom.RATIOS.size`, so it follows automatically.
2. **Whether GRIND wants a third oscillator at unison** under the detuned pair. Start with two. Add a third only if the low end feels thin, and if you do, keep it at unison so it grounds the stack without adding to the beating.

**If a macro test is flaky, widen the macro's range rather than weakening the assertion.** A macro that doesn't audibly do anything across its full travel is a design bug, not a test bug — the roadmap's playability rules are explicit that every knob has to earn its place.
