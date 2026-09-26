# PLUCK Depth Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give PLUCK a STRIKE (pick position) macro, a render that follows the string's decay up to four seconds, velocity re-synthesised through PICK and the fleet's seed convention; give TINES a KALIMBA voice built from three FM strikes at a clamped-free bar's ratios; and render the audition set that lets Josh judge both against what shipped.

**Architecture:** PLUCK stays one Karplus-Strong engine; the pick-position comb is applied to the noise burst before the loop (the loop and its tuning budget are untouched), and the render buffer is sized by a DAMP-driven budget and then cut where the string's envelope has fallen 60 dB. TINES gains a voice that is three `Tines.strike` calls at 1×, 6.267× and 17.548× the note plus an amplitude-gated rattle, with TUNE snapped to semitones from A3 so the melodic kit can replay it. A test-side generator renders the audition clips at one RMS beside the versioned listening page.

**Tech Stack:** Kotlin/JVM (`:synth` module, Gradle, JDK 17, JUnit via `kotlin("test")`), Android Compose in `:app` (one `when` branch), the existing `Dsp`/`Modes`/`Velocity`/`WavWriter` helpers.

**Spec:** `docs/superpowers/specs/2026-09-25-pluck-depth-design.md` — sections "The string path", "Macros", "Velocity, seeds, and the cheap wins", "KALIMBA moves to TINES", "Phasing and gates" (row 1), "Testing".

## Global Constraints

- **Naming:** no trademarked names, model numbers or near-misses on any product surface (engine and voice names, preset names, descriptions, commit messages) — `docs/SYNTH_ROADMAP.md`, "sound yes, names never". `PresetTestSupport.trademarkBlocklist` enforces the letter; the reviewer enforces the spirit.
- **Measure, never guess:** every threshold in a test is a starting number the plan may tighten after the audition, never loosen without saying why in the commit. Ratios that come from physics are recorded with citations before they land in code.
- **Every engine renders at `Dsp.RATE * Dsp.OVERSAMPLE` and decimates** (`Dsp.decimate`); nothing here changes that.
- **`Pluck.ks` keeps its `require(exact >= MIN_LOOP_SAMPLES)`** and STRIKE must not be able to lower `exact`.
- **The 48 PLUCK presets are disposable** and only have to keep rendering clean (`PluckPresetsTest`); do not tune them.
- **Per-voice defaults for new macros are placeholders** until the audition; write them as a table with a comment naming the spec, the way `LOUDNESS_OFFSET` is.
- **Commands run from the repo root** `/Users/joshuacramblet/SnipSnap`; JDK 17 is on the path. `./gradlew :synth:test --tests "com.snipsnap.synth.PluckTest"` runs one class.
- **Every commit ends with these two lines** (project attribution rule):
  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01DsZ2eJRrwVXd4cwMqvAGam
  ```
- **Nothing is pushed** and no PR is opened; the branch is `claude/mobile-mpc-drum-sampler-t58x74`.

---

## File Structure

| File | Responsibility |
|---|---|
| `synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt` | the engine: macro table, `synthesize`, `ks`; gains STRIKE, the decay-following length, seeds |
| `synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt` | `brightnessOverride` gains the PLUCK → PICK line |
| `synth/src/main/kotlin/com/snipsnap/synth/Tines.kt` | gains `TinesVoice.KALIMBA`, `frequencyFor`, `kalimba()`, `rattle()` |
| `synth/src/main/kotlin/com/snipsnap/synth/TinesPresets.kt` | gains twelve KALIMBA presets |
| `app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt` | one `when` branch: `TinesVoice.KALIMBA -> DrumClass.TONAL` |
| `synth/src/test/kotlin/com/snipsnap/synth/PluckSpectra.kt` | **new** — Goertzel tone-energy helpers shared by PLUCK and TINES tests |
| `synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt` | STRIKE, ring-length, PICK-monotonic and velocity tests; the one-shot bound moves to the ceiling |
| `synth/src/test/kotlin/com/snipsnap/synth/TinesTest.kt` | KALIMBA snapping, partials, BUZZ, BRIGHT-monotonic |
| `synth/src/test/kotlin/com/snipsnap/synth/TuningAccuracyTest.kt` | KALIMBA's five-cent bound |
| `synth/src/test/kotlin/com/snipsnap/synth/PluckAuditionGenerator.kt` | **new** — renders the audition clips and copies the page |
| `synth/src/test/resources/audition/pluck-audition.html` | the listening page (already committed); its clip lists move to the Phase 1 set |
| `synth/build.gradle.kts` | `generatePluckAudition` JavaExec task |
| `.gitignore` | `testkit/pluck-audition/` |
| `synth/src/test/kotlin/com/snipsnap/synth/PluckBodySpike.kt` | **deleted** (untracked throwaway from the spike) |

---

### Task 1: STRIKE — the pick-position comb

**Files:**
- Create: `synth/src/test/kotlin/com/snipsnap/synth/PluckSpectra.kt`
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt:50-67` (macro table), `:116-152` (`synthesize`), `:188-309` (`ks`)
- Test: `synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt`
- Delete: `synth/src/test/kotlin/com/snipsnap/synth/PluckBodySpike.kt`

**Interfaces:**
- Consumes: `Dsp.expMap(macro, lo, hi)`, `Dsp.Noise`, `Dsp.OnePole`, `Pluck.frequencyFor(voice, tune)`, `Snip.peak()`.
- Produces: `Pluck.STRIKE_BRIDGE: Float = 0.03f`, `Pluck.STRIKE_CENTRE: Float = 0.5f` (internal consts); `Pluck.ks(..., position: Float = 0f)` — a new trailing parameter with a default, so both existing `ks` tests compile unchanged; `PluckSpectra.toneEnergy(snip: Snip, hz: Float, seconds: Float = 0.25f): Double` and `PluckSpectra.fundamentalShare(snip: Snip, f0: Float): Double` for later tasks.

- [ ] **Step 1: Delete the spike**

```bash
rm synth/src/test/kotlin/com/snipsnap/synth/PluckBodySpike.kt
```

It was never tracked, so nothing to `git rm`.

- [ ] **Step 2: Create the spectral helper**

Create `synth/src/test/kotlin/com/snipsnap/synth/PluckSpectra.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * Narrow-band energy at a tone, for the PLUCK and TINES tests: a Goertzel
 * filter over the first [seconds] of a mono snip, summed across ±8 Hz in
 * 2 Hz steps so a harmonic that sits a few cents off its nominal frequency
 * (the tuning bound allows five) still lands inside the measure.
 */
internal object PluckSpectra {

    fun toneEnergy(snip: Snip, hz: Float, seconds: Float = 0.25f): Double {
        require(snip.channels == 1) { "PluckSpectra measures mono snips" }
        val n = min(snip.frameCount, (seconds * snip.sampleRate).toInt())
        var total = 0.0
        var offset = -8
        while (offset <= 8) {
            total += goertzel(snip.samples, n, hz + offset, snip.sampleRate)
            offset += 2
        }
        return total
    }

    /** The fundamental's energy against harmonics 2–4 together. */
    fun fundamentalShare(snip: Snip, f0: Float): Double {
        val h1 = toneEnergy(snip, f0)
        val rest = toneEnergy(snip, 2 * f0) + toneEnergy(snip, 3 * f0) + toneEnergy(snip, 4 * f0)
        return h1 / (rest + 1e-12)
    }

    private fun goertzel(x: FloatArray, n: Int, hz: Float, rate: Int): Double {
        val w = 2.0 * PI * hz / rate
        val coeff = 2.0 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until n) {
            val s = x[i] + coeff * s1 - s2
            s2 = s1
            s1 = s
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2
    }
}
```

- [ ] **Step 3: Write the failing tests**

Append inside `class PluckTest` in `synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt` (before the final `}`):

```kotlin
    @Test
    fun `STRIKE is a macro on every voice`() {
        for (voice in PluckVoice.entries) {
            assertTrue(Pluck.macrosFor(voice).any { it.name == "STRIKE" }, "$voice has no STRIKE")
        }
    }

    @Test
    fun `STRIKE at the bridge thins the fundamental against the harmonics`() {
        // The comb's gain at harmonic k is 2*sin(pi*k*p): near the bridge (p
        // small) the fundamental is the most attenuated harmonic, at the
        // centre (p = 0.5) the least. The audition read both ends as CLOSER
        // to the instrument; this pins that they are ends.
        for (voice in PluckVoice.entries) {
            val f0 = Pluck.frequencyFor(voice, 0.5f)
            val bridge = Pluck.render(voice, mapOf("TUNE" to 0.5f, "STRIKE" to 0f, "DOUBLE" to 0f))
            val centre = Pluck.render(voice, mapOf("TUNE" to 0.5f, "STRIKE" to 1f, "DOUBLE" to 0f))
            val atBridge = PluckSpectra.fundamentalShare(bridge, f0)
            val atCentre = PluckSpectra.fundamentalShare(centre, f0)
            assertTrue(
                atBridge < atCentre,
                "$voice: fundamental share at the bridge ($atBridge) should sit below the centre ($atCentre)",
            )
        }
    }

    @Test
    fun `STRIKE at the centre removes the second harmonic`() {
        for (voice in PluckVoice.entries) {
            val f0 = Pluck.frequencyFor(voice, 0.5f)
            val bridge = Pluck.render(voice, mapOf("TUNE" to 0.5f, "STRIKE" to 0f, "DOUBLE" to 0f))
            val centre = Pluck.render(voice, mapOf("TUNE" to 0.5f, "STRIKE" to 1f, "DOUBLE" to 0f))
            val h2Bridge = PluckSpectra.toneEnergy(bridge, 2 * f0)
            val h2Centre = PluckSpectra.toneEnergy(centre, 2 * f0)
            assertTrue(
                h2Centre < h2Bridge * 0.1,
                "$voice: 2nd harmonic at the centre ($h2Centre) should be 20 dB under the bridge ($h2Bridge)",
            )
        }
    }

    @Test
    fun `the default STRIKE keeps the shipped balance`() {
        // The audition read the quarter position as SAME as the shipped
        // engine, so the default lands there: within a factor of two of the
        // comb-less exciter on the fundamental's share.
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        fun share(position: Float): Double {
            val raw = Pluck.ks(
                freq = 220f, seconds = 0.6f, damp = 0.4f, bodyLoopHz = 3400f, pickHz = 2500f,
                seed = 11, rate = rate, position = position,
            )
            val snip = Snip(Dsp.decimate(raw, Dsp.RATE), channels = 1, sampleRate = Dsp.RATE)
            return PluckSpectra.fundamentalShare(snip, 220f)
        }
        val plain = share(0f)
        val quarter = share(Dsp.expMap(0.75f, Pluck.STRIKE_BRIDGE, Pluck.STRIKE_CENTRE))
        assertTrue(
            quarter > plain * 0.5 && quarter < plain * 2.0,
            "default STRIKE share $quarter should be within 2x of the comb-less $plain",
        )
    }
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.PluckTest" --console=plain 2>&1 | tail -30`

Expected: compilation FAILS in `PluckTest.kt` — `position` is not a parameter of `Pluck.ks` and `Pluck.STRIKE_BRIDGE` does not exist. (Once those exist, before the DSP is in, `STRIKE at the bridge…` and `…centre…` fail because STRIKE does nothing yet.)

- [ ] **Step 5: Add the macro and the constants to `Pluck.kt`**

Replace the whole `macrosFor` function (`Pluck.kt:50-67`) with:

```kotlin
    /**
     * STRIKE's ends as a fraction of the string: the bridge and the centre.
     * The map between them is exponential because positions near the bridge
     * change fast (spec, "Macros"). Defaults sit on the quarter position the
     * audition read as SAME as the pre-STRIKE engine — placeholders until
     * the gate, like [LOUDNESS_OFFSET].
     */
    internal const val STRIKE_BRIDGE = 0.03f
    internal const val STRIKE_CENTRE = 0.5f

    fun macrosFor(voice: PluckVoice): List<MacroSpec> = when (voice) {
        PluckVoice.KALIMBA -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("DAMP", 0.6f), MacroSpec("PICK", 0.55f),
            MacroSpec("STRIKE", 0.75f), MacroSpec("DOUBLE", 0.1f),
        )
        PluckVoice.NYLON -> listOf(
            MacroSpec("TUNE", 0.4f), MacroSpec("DAMP", 0.45f), MacroSpec("PICK", 0.4f),
            MacroSpec("STRIKE", 0.75f), MacroSpec("DOUBLE", 0.15f),
        )
        PluckVoice.HARP -> listOf(
            MacroSpec("TUNE", 0.55f), MacroSpec("DAMP", 0.2f), MacroSpec("PICK", 0.6f),
            MacroSpec("STRIKE", 0.75f), MacroSpec("DOUBLE", 0.2f),
        )
        // A koto is played with a pick close to the bridge.
        PluckVoice.KOTO -> listOf(
            MacroSpec("TUNE", 0.45f), MacroSpec("DAMP", 0.4f), MacroSpec("PICK", 0.75f),
            MacroSpec("STRIKE", 0.6f), MacroSpec("DOUBLE", 0.45f),
        )
    }
```

- [ ] **Step 6: Thread the position through `synthesize`**

In `synthesize` (`Pluck.kt:116-152`), after `val double = m.getValue("DOUBLE")` add:

```kotlin
        val position = Dsp.expMap(m.getValue("STRIKE"), STRIKE_BRIDGE, STRIKE_CENTRE)
```

and change both `ks(...)` calls to pass it. The first becomes:

```kotlin
        val out = ks(freq, seconds, damp, loopHz, Dsp.expMap(pick, pickLo, pickHi), seed = 11, rate = rate, position = position)
```

and the second (inside `if (double > 0.01f)`):

```kotlin
            val det = ks(
                freq * Dsp.lin(double, 1.002f, 1.012f), seconds, damp, loopHz,
                Dsp.expMap(pick, pickLo, pickHi), seed = 23, rate = rate, position = position,
            )
```

- [ ] **Step 7: Add the comb to `ks`**

Add `position: Float = 0f` as the last parameter of `ks` (after `rate: Int`), and add `import kotlin.math.roundToInt` to the imports. Then replace the exciter block — from `val noise = Dsp.Noise(seed)` through the end of the zero-mean loop `for (i in 0 until head) out[i] -= mean` — with:

```kotlin
        val noise = Dsp.Noise(seed)
        val pickLp = Dsp.OnePole(rate)
        val burst = FloatArray(n)
        for (i in 0 until n) burst[i] = pickLp.lp(noise.next(), pickHz)
        // Zero-mean the exciter: the loop filter passes DC untouched, so any
        // net offset in the burst survives as a sub-thump long after the
        // string content is damped away — a dark pluck decayed into a fake
        // kick until this subtraction.
        var mean = 0f
        for (v in burst) mean += v
        mean /= n
        for (i in 0 until n) burst[i] -= mean

        // Pick position (Jaffe & Smith 1983): the burst minus a copy of
        // itself delayed by `position` of one period. The comb's notches
        // fall on every harmonic k where k*position is a whole number: the
        // centre kills the even harmonics, the bridge thins the low ones.
        // The exciter grows to n + d samples, and the extra samples enter
        // the loop as INPUT through the `+=` below, not as initial state -
        // the loop's own length and tuning budget are untouched. position
        // = 0 reproduces the pre-STRIKE exciter sample for sample.
        val d = if (position > 0f) (position * n).roundToInt().coerceIn(1, n / 2) else 0
        val excLen = min(n + d, out.size)
        for (i in 0 until excLen) {
            val x = if (i < n) burst[i] else 0f
            val xd = if (d > 0 && i - d in 0 until n) burst[i - d] else 0f
            out[i] = x - xd
        }
```

Leave the `val loopLp = Dsp.OnePole(rate)` loop after it exactly as it is. (`head` is no longer used; `out.size` is always at least `n + 2`, so the old `minOf(n, out.size)` was `n`.)

- [ ] **Step 8: Run the PLUCK tests to verify they pass**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.PluckTest" --console=plain 2>&1 | tail -30`

Expected: all PluckTest cases PASS, including the four new ones and the two existing `ks` boundary tests (which now call the defaulted `position`).

- [ ] **Step 9: Pin the tuning at STRIKE's ends, then run the tuning bound and the preset suite**

Append inside `class TuningAccuracyTest` in `synth/src/test/kotlin/com/snipsnap/synth/TuningAccuracyTest.kt`:

```kotlin
    @Test
    fun `STRIKE at either end keeps every Pluck voice within five cents`() {
        // The comb sits before the loop and cannot touch its length; this
        // pins that at the octave, at both extremes.
        for (voice in PluckVoice.entries) {
            for (strike in listOf(0f, 1f)) {
                val snip = Pluck.render(voice, mapOf("TUNE" to 0.5f, "DOUBLE" to 0f, "STRIKE" to strike))
                val want = Pluck.frequencyFor(voice, 12)
                val measured = measuredHz(snip, want)
                val err = abs(cents(measured, want.toDouble()))
                assertTrue(err <= 5.0, "$voice at STRIKE $strike is $err cents off (want $want, got $measured)")
            }
        }
    }
```

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.TuningAccuracyTest" --tests "com.snipsnap.synth.PluckPresetsTest" --console=plain 2>&1 | tail -20`

Expected: PASS. The comb does not move the fundamental, and the 48 presets carry no STRIKE key so they take the default. Add `synth/src/test/kotlin/com/snipsnap/synth/TuningAccuracyTest.kt` to the commit in Step 10.

- [ ] **Step 10: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt \
        synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt \
        synth/src/test/kotlin/com/snipsnap/synth/PluckSpectra.kt \
        synth/src/test/kotlin/com/snipsnap/synth/TuningAccuracyTest.kt
git commit -F - <<'EOF'
Give PLUCK a STRIKE macro: the Jaffe-Smith pick-position comb on the exciter

The audition (spec 2026-09-25) found pick position outranks the body and
only its extremes register. STRIKE runs the comb from the bridge (p 0.03)
to the string's centre (p 0.5), exponentially, and defaults to the quarter
position that read the same as the pre-STRIKE engine. The comb sits before
the loop, so the tuning budget from Phase 0b is untouched.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DsZ2eJRrwVXd4cwMqvAGam
EOF
```

---

### Task 2: The render follows the string's decay

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt` (`synthesize`, new `trimToDecay` and `fadeCeiling`)
- Test: `synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt`

**Interfaces:**
- Consumes: `Dsp.expMap`, `Dsp.fadeTail(buf, ms, rate, channels)`, `Snip.peak()`, `Snip.durationSeconds`.
- Produces: `Pluck.RING_FLOOR_SECONDS: Float = 0.25f`, `Pluck.RING_CEILING_SECONDS: Float = 4.0f` (internal consts); `Pluck.trimToDecay(buf: FloatArray, rate: Int): FloatArray` (internal). `synthesize`'s signature is unchanged.

- [ ] **Step 1: Move the one-shot bound and write the failing tests**

In `PluckTest.kt`, inside `every voice renders clean audio at defaults and both corners`, replace

```kotlin
                assertTrue(snip.durationSeconds < 1.5f, "$voice must stay a one-shot")
```

with

```kotlin
                assertTrue(
                    snip.durationSeconds <= Pluck.RING_CEILING_SECONDS + 0.05f,
                    "$voice must stay inside the ring ceiling",
                )
```

Then append inside the class:

```kotlin
    @Test
    fun `DAMP at zero rings past three and a half seconds and fades out clean`() {
        // The three string voices at their default notes (196-350 Hz) lose
        // under 9 dB per second through the loop filter at DAMP 0 and reach
        // the ceiling. KALIMBA's default is 440 Hz, where the same filter
        // costs ~29 dB per second and the string is gone by ~2 s; it leaves
        // PLUCK in Phase 2 and is covered by the reach test below instead.
        for (voice in listOf(PluckVoice.NYLON, PluckVoice.KOTO, PluckVoice.HARP)) {
            val snip = Pluck.render(voice, mapOf("DAMP" to 0f))
            assertTrue(snip.durationSeconds >= 3.5f, "$voice: ${snip.durationSeconds}s is not a ring")
            assertTrue(snip.durationSeconds <= Pluck.RING_CEILING_SECONDS + 0.05f, "$voice: past the ceiling")
            assertTrue(tailDb(snip) < -55f, "$voice: last 10 ms at ${tailDb(snip)} dB should be inaudible")
        }
    }

    @Test
    fun `DAMP zero rings at least twice as long as DAMP half on every voice`() {
        for (voice in PluckVoice.entries) {
            val open = Pluck.render(voice, mapOf("DAMP" to 0f)).durationSeconds
            val half = Pluck.render(voice, mapOf("DAMP" to 0.5f)).durationSeconds
            assertTrue(open >= half * 2f, "$voice: DAMP 0 ${open}s vs DAMP 0.5 ${half}s is not enough reach")
            assertTrue(tailDb(Pluck.render(voice, mapOf("DAMP" to 0f))) < -55f, "$voice: DAMP 0 tail is audible")
        }
    }

    @Test
    fun `DAMP at one is a short thud`() {
        for (voice in PluckVoice.entries) {
            val snip = Pluck.render(voice, mapOf("DAMP" to 1f))
            assertTrue(snip.durationSeconds < 0.5f, "$voice: ${snip.durationSeconds}s is not a thud")
        }
    }

    @Test
    fun `the render ends where the string does, not at the budget`() {
        // HARP at DAMP 0.6 gets a budget near a second and stops ringing well
        // before it; the file must follow the string, and the cut must land
        // on inaudible signal.
        val snip = Pluck.render(PluckVoice.HARP, mapOf("DAMP" to 0.6f))
        assertTrue(snip.durationSeconds >= Pluck.RING_FLOOR_SECONDS, "under the floor: ${snip.durationSeconds}s")
        assertTrue(snip.durationSeconds < 0.9f, "padded to the budget: ${snip.durationSeconds}s")
        assertTrue(tailDb(snip) < -50f, "tail at ${tailDb(snip)} dB: the cut landed on audible signal")
    }

    /** Level of the last 10 ms against the render's peak, in dB. */
    private fun tailDb(snip: Snip): Float {
        val peak = snip.peak()
        val from = (snip.frameCount - (0.010f * snip.sampleRate).toInt()).coerceAtLeast(0)
        var tail = 0f
        for (i in from until snip.frameCount) tail = maxOf(tail, kotlin.math.abs(snip.samples[i]))
        return 20f * kotlin.math.log10(tail / peak + 1e-9f)
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.PluckTest" --console=plain 2>&1 | tail -30`

Expected: compilation FAILS on `Pluck.RING_CEILING_SECONDS` / `RING_FLOOR_SECONDS`. (After the constants exist and before the DSP is in, `DAMP at zero…` fails at `>= 3.5f` because the render is capped at 1.35 s.)

- [ ] **Step 3: Add the constants and the budget**

In `object Pluck`, next to `STRIKE_BRIDGE`, add:

```kotlin
    /**
     * The render follows the string's own decay between these bounds
     * (spec, "Macros"): the ceiling is a file-size judgement (≈ 350–530 KB
     * per pad at 24-bit mono), the floor keeps a muted pluck from becoming
     * a click. `Dsp.levelTo`'s ceiling and `Dsp.fadeTail` are unchanged.
     */
    internal const val RING_FLOOR_SECONDS = 0.25f
    internal const val RING_CEILING_SECONDS = 4.0f
```

In `synthesize`, replace

```kotlin
        val seconds = (ring * Dsp.lin(1f - damp, 0.35f, 1f)).coerceAtMost(1.35f)
```

with

```kotlin
        // The budget, not the length: DAMP 1 keeps today's thud (0.3 x the
        // voice's ring), DAMP 0 reaches the ceiling, and trimToDecay below
        // then cuts the buffer where the string actually stops ringing, so a
        // muted pluck stays a short file and a DAMP 0 harp gets its ring.
        val seconds = Dsp.expMap(1f - damp, 0.3f * ring, RING_CEILING_SECONDS)
            .coerceIn(RING_FLOOR_SECONDS, RING_CEILING_SECONDS)
```

and change the function's final `return out` to `return trimToDecay(out, rate)`.

- [ ] **Step 4: Add `trimToDecay` and `fadeCeiling`**

Add `import kotlin.math.sqrt` to the imports, then add inside `object Pluck` (after `render`):

```kotlin
    /**
     * Cuts [buf] where its 5 ms RMS envelope has fallen 60 dB below its
     * peak, never under [RING_FLOOR_SECONDS]. A string that reaches the end
     * of its budget still ringing (DAMP near 0 at the ceiling) is not cut at
     * all but given a long squared fade, so the render's end is inaudible
     * either way; `render`'s own 4 ms `Dsp.fadeTail` then has nothing
     * audible left to touch.
     */
    internal fun trimToDecay(buf: FloatArray, rate: Int): FloatArray {
        val block = (rate * 0.005f).toInt().coerceAtLeast(1)
        val blocks = (buf.size + block - 1) / block
        if (blocks == 0) return buf
        val rms = DoubleArray(blocks)
        for (b in 0 until blocks) {
            val start = b * block
            val end = min(buf.size, start + block)
            var acc = 0.0
            for (i in start until end) acc += buf[i].toDouble() * buf[i]
            rms[b] = sqrt(acc / (end - start))
        }
        val peak = rms.max()
        if (peak <= 0.0) return buf
        val floorBlocks = ((RING_FLOOR_SECONDS * rate) / block).toInt()
        var last = blocks - 1
        while (last > floorBlocks && rms[last] < peak * 0.001) last--
        val end = min(buf.size, (last + 2) * block)
        if (end < buf.size) return buf.copyOf(end)
        fadeCeiling(buf, ms = 400f, rate = rate)
        return buf
    }

    /**
     * A squared fade over the last [ms]. At the ring ceiling the string is
     * still moving, and a linear fade's last few milliseconds would sit
     * only ~30 dB down; squaring it puts them past -60 dB.
     */
    private fun fadeCeiling(buf: FloatArray, ms: Float, rate: Int) {
        val n = min(buf.size, (ms / 1000f * rate).toInt())
        if (n <= 0) return
        val start = buf.size - n
        for (i in 0 until n) {
            val g = 1f - i.toFloat() / n
            buf[start + i] *= g * g
        }
    }
```

- [ ] **Step 5: Run the PLUCK tests**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.PluckTest" --console=plain 2>&1 | tail -30`

Expected: PASS. If `factory defaults all classify as percussion` fails for a voice because its longer default render now reads as something else, raise that voice's default DAMP in `macrosFor` by 0.1 and re-run, repeating until it passes; the defaults are placeholders for the gate and the commit message names the voice and the value.

- [ ] **Step 6: Run the rest of the synth suite that renders PLUCK**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.TuningAccuracyTest" --tests "com.snipsnap.synth.PluckPresetsTest" --tests "com.snipsnap.synth.SynthKitTest" --tests "com.snipsnap.synth.ExportRegressionTest" --console=plain 2>&1 | tail -30`

Expected: PASS. If a test outside `PluckTest` fails on a length or a classification of a PLUCK render, report the assertion text and stop rather than loosening it; that is a decision for the reviewer.

- [ ] **Step 7: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt \
        synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt
git commit -F - <<'EOF'
Let a PLUCK render follow the string's decay, up to four seconds

The 1.35 s cap was the file, not the string: at DAMP 0 the loop's feedback
would ring a harp for twenty seconds and the render cut it at -26 dB. The
budget now runs from today's thud at DAMP 1 to a 4 s ceiling at DAMP 0,
and the buffer is cut where the 5 ms envelope has fallen 60 dB, so muted
plucks stay short files. A string still ringing at the ceiling gets a
squared 400 ms fade instead of a cut.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DsZ2eJRrwVXd4cwMqvAGam
EOF
```

---

### Task 3: Velocity through PICK

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt:215-233` (`brightnessOverride` and its KDoc)
- Test: `synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt`

**Interfaces:**
- Consumes: `Velocity.atVelocity(patch: Patch, velocity: Float): Snip`, `Velocity.soften(snip: Snip, amount: Float): Snip`, `PluckPresets.forVoice(voice)`, `FeatureExtractor.extract(snip).centroidHz`.
- Produces: nothing new; `brightnessOverride` answers `"PICK"` for any `PluckPatch`.

- [ ] **Step 1: Write the failing tests**

Append inside `class PluckTest`:

```kotlin
    @Test
    fun `PICK moves the centroid at every step of its travel`() {
        // The precondition for routing velocity through PICK: the same
        // sweep the snare's SNAP had to pass before its override line.
        for (voice in PluckVoice.entries) {
            val points = (0..10).map { it / 10f }
            val measured = points.map { p ->
                FeatureExtractor.extract(Pluck.render(voice, mapOf("PICK" to p))).centroidHz
            }
            for (i in 0 until measured.size - 1) {
                assertTrue(
                    measured[i + 1] > measured[i] * 0.98f,
                    "$voice: PICK fell between ${points[i]} and ${points[i + 1]}: $measured",
                )
                assertTrue(
                    kotlin.math.abs(measured[i + 1] - measured[i]) > 1f,
                    "$voice: PICK is dead between ${points[i]} and ${points[i + 1]}: $measured",
                )
            }
        }
    }

    @Test
    fun `a soft PLUCK is re-synthesised through PICK, not low-passed`() {
        val patch = PluckPresets.forVoice(PluckVoice.NYLON).first()
        val soft = Velocity.atVelocity(patch, 0.2f)
        val hard = Velocity.atVelocity(patch, 1f)
        val softened = Velocity.soften(patch.render(), 0.8f)
        assertTrue(
            FeatureExtractor.extract(soft).centroidHz < FeatureExtractor.extract(hard).centroidHz,
            "a soft strike should be darker than a hard one",
        )
        assertTrue(
            !soft.samples.contentEquals(softened.samples),
            "soft velocity must be a re-render through PICK, not the soften() fallback",
        )
    }
```

- [ ] **Step 2: Run to verify the second test fails**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.PluckTest" --console=plain 2>&1 | tail -20`

Expected: `PICK moves the centroid…` PASSES (PICK is already monotonic), `a soft PLUCK is re-synthesised…` FAILS at the `contentEquals` assertion — today PLUCK falls back to `soften`, so the two buffers are identical. If the sweep test fails for a voice, stop and report the measured list: the override must not be added over a non-monotonic macro.

- [ ] **Step 3: Add the override**

In `Velocity.kt`, replace

```kotlin
    private fun brightnessOverride(patch: Patch): String? =
        if (patch is ThumpPatch && patch.voice == ThumpVoice.SNARE) "SNAP" else null
```

with

```kotlin
    private fun brightnessOverride(patch: Patch): String? = when {
        patch is ThumpPatch && patch.voice == ThumpVoice.SNARE -> "SNAP"
        // PICK is the exciter's low-pass; PluckTest's `PICK moves the centroid
        // at every step of its travel` is the sweep behind this line (spec
        // 2026-09-25, "Velocity, seeds, and the cheap wins").
        patch is PluckPatch -> "PICK"
        else -> null
    }
```

and in the KDoc above it change `THUMP SNARE is the only entry` to `THUMP SNARE and PLUCK are the entries`.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.PluckTest" --tests "com.snipsnap.synth.InstrumentSuite*" --tests "com.snipsnap.synth.VelocityGrooveShuffleTest" --console=plain 2>&1 | tail -20`

Expected: PASS. The harp instrument's soft layer still uses `Velocity.soften` explicitly in `InstrumentSuite.renderHarp`, so it is unaffected.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Velocity.kt \
        synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt
git commit -F - <<'EOF'
Route PLUCK's velocity through PICK, after proving PICK is monotonic

A quiet pluck excites fewer highs; it is not a loud pluck with a blanket
over it. PLUCK was on the soften() fallback because PICK was not a
registered brightness macro. The override is scoped to PluckPatch, the
way the snare's SNAP is, with the eleven-step centroid sweep as its proof.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DsZ2eJRrwVXd4cwMqvAGam
EOF
```

---

### Task 4: The seed convention

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt` (`synthesize`'s two `ks` calls)
- Test: `synth/src/test/kotlin/com/snipsnap/synth/PluckTest.kt` (existing `is deterministic`, `scrambled hits usually avoid the DC-thump fake-kick decay`)

**Interfaces:**
- Consumes: `Dsp.seedFor(vararg parts: Any): Int`.
- Produces: nothing new.

- [ ] **Step 1: Replace the literal seeds**

In `synthesize`, change `seed = 11` to `seed = Dsp.seedFor("PLUCK", voice.name)` and `seed = 23` to `seed = Dsp.seedFor("PLUCK", voice.name, "DOUBLE")`. Leave `PluckTest`'s direct `ks` calls with their literal `seed = 11` / `seed = 1`: they drive the loop directly and their seeds are test data.

- [ ] **Step 2: Run the PLUCK tests**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.PluckTest" --tests "com.snipsnap.synth.PluckPresetsTest" --console=plain 2>&1 | tail -20`

Expected: PASS. `is deterministic` proves the seed is stable per voice. If `scrambled hits usually avoid the DC-thump fake-kick decay` fails (it is a 30-roll statistical guard and the noise changed), revert this task's edit and report it: a seed convention is not worth a flaky guard, and the reviewer decides.

- [ ] **Step 3: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Pluck.kt
git commit -F - <<'EOF'
Seed PLUCK's exciter through Dsp.seedFor like the other engines

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DsZ2eJRrwVXd4cwMqvAGam
EOF
```

---

### Task 5: TINES KALIMBA — the voice, its presets, its tests

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Tines.kt` (enum `:21`, `macrosFor` `:33-53`, `render` `:76-82`, new `frequencyFor`, `kalimba`, `rattle`)
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/TinesPresets.kt` (`forVoice` `:19-25`, new `kalimbaPresets`)
- Modify: `app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt:1145-1149` (`TinesVoice.drumClass`)
- Test: `synth/src/test/kotlin/com/snipsnap/synth/TinesTest.kt`, `synth/src/test/kotlin/com/snipsnap/synth/TuningAccuracyTest.kt`

**Interfaces:**
- Consumes: `Tines.strike(out, carrierHz, ratio, index, t60, bite, gain, rate)`, `Dsp.Env`, `Dsp.Noise`, `Dsp.seedFor`, `Dsp.lin`, `Dsp.expMap`, `PluckSpectra.toneEnergy` (Task 1), `TuningAccuracyTest`'s private `measuredHz(snip, wantHz)` and `cents(a, b)`.
- Produces: `TinesVoice.KALIMBA`; `Tines.KALIMBA_TUNE_SEMITONES: Int = 24`, `Tines.KALIMBA_ROOT_HZ: Float = 220f`, `Tines.KALIMBA_PARTIALS: FloatArray = [1f, 6.267f, 17.548f]` (internal); `Tines.frequencyFor(voice: TinesVoice, tune: Float): Float` (public) and `Tines.frequencyFor(voice: TinesVoice, semitone: Int): Float` (internal), both requiring `voice == KALIMBA`; macros `TUNE, BUZZ, BRIGHT, DECAY`.

- [ ] **Step 1: Write the failing tests**

Append inside `class TinesTest` in `TinesTest.kt`:

```kotlin
    @Test
    fun `KALIMBA TUNE snaps to semitones from A3`() {
        // Two octaves inclusive, so the melodic kit's pads land on notes
        // that a pad recipe can replay as a macro value.
        val distinct = HashSet<Float>()
        for (i in 0..100) distinct.add(Tines.frequencyFor(TinesVoice.KALIMBA, i / 100f))
        assertEquals(Tines.KALIMBA_TUNE_SEMITONES + 1, distinct.size)
        assertEquals(220f, Tines.frequencyFor(TinesVoice.KALIMBA, 0f))
        assertEquals(880f, Tines.frequencyFor(TinesVoice.KALIMBA, 1f))
    }

    @Test
    fun `only KALIMBA snaps`() {
        assertFailsWith<IllegalArgumentException> { Tines.frequencyFor(TinesVoice.BELL, 0.5f) }
    }

    @Test
    fun `KALIMBA rings the bar's partials, not a harmonic series`() {
        // A clamped-free bar's second partial sits at 6.267 f0, between the
        // sixth and seventh harmonics; the voice must put energy there and
        // not on the harmonics either side.
        val f0 = Tines.frequencyFor(TinesVoice.KALIMBA, 0.5f)
        val snip = Tines.render(TinesVoice.KALIMBA, mapOf("TUNE" to 0.5f, "BRIGHT" to 0.8f, "BUZZ" to 0f))
        val atBar = PluckSpectra.toneEnergy(snip, f0 * Tines.KALIMBA_PARTIALS[1], seconds = 0.1f)
        val atSixth = PluckSpectra.toneEnergy(snip, f0 * 6f, seconds = 0.1f)
        val atSeventh = PluckSpectra.toneEnergy(snip, f0 * 7f, seconds = 0.1f)
        assertTrue(
            atBar > atSixth * 4 && atBar > atSeventh * 4,
            "second partial should sit at 6.267 f0: bar=$atBar h6=$atSixth h7=$atSeventh",
        )
    }

    @Test
    fun `BUZZ rattles`() {
        val clean = FeatureExtractor.extract(Tines.render(TinesVoice.KALIMBA, mapOf("BUZZ" to 0f)))
        val buzzed = FeatureExtractor.extract(Tines.render(TinesVoice.KALIMBA, mapOf("BUZZ" to 1f)))
        assertTrue(
            buzzed.flatness > clean.flatness * 1.5f,
            "BUZZ should add noise: flatness ${clean.flatness} -> ${buzzed.flatness}",
        )
    }

    @Test
    fun `KALIMBA BRIGHT moves at every step of its travel`() {
        // The engine's velocity path is BRIGHT; the new voice has to keep it
        // monotonic, the way the snare's SNAP sweep is written.
        val points = (0..8).map { it / 8f }
        val measured = points.map { b ->
            FeatureExtractor.extract(Tines.render(TinesVoice.KALIMBA, mapOf("BRIGHT" to b, "BUZZ" to 0f))).centroidHz
        }
        for (i in 0 until measured.size - 1) {
            assertTrue(
                measured[i + 1] > measured[i] + 1f,
                "BRIGHT is dead or reversed between ${points[i]} and ${points[i + 1]}: $measured",
            )
        }
    }
```

Add `import com.snipsnap.audio.FeatureExtractor`, `import kotlin.test.assertEquals` and `import kotlin.test.assertFailsWith` to `TinesTest.kt` if they are not already imported (check the file's import block first).

Append inside `class TuningAccuracyTest` in `TuningAccuracyTest.kt`:

```kotlin
    @Test
    fun `every TINES KALIMBA semitone lands within five cents`() {
        for (semi in 0..Tines.KALIMBA_TUNE_SEMITONES) {
            val macro = semi.toFloat() / Tines.KALIMBA_TUNE_SEMITONES
            val snip = Tines.render(TinesVoice.KALIMBA, mapOf("TUNE" to macro, "BUZZ" to 0f))
            val want = Tines.frequencyFor(TinesVoice.KALIMBA, semi)
            val measured = measuredHz(snip, want)
            val err = abs(cents(measured, want.toDouble()))
            assertTrue(err <= 5.0, "KALIMBA semitone $semi is $err cents off (want $want, got $measured)")
        }
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.TinesTest" --tests "com.snipsnap.synth.TuningAccuracyTest" --console=plain 2>&1 | tail -20`

Expected: compilation FAILS — `TinesVoice.KALIMBA`, `Tines.frequencyFor`, `Tines.KALIMBA_TUNE_SEMITONES`, `Tines.KALIMBA_PARTIALS` do not exist.

- [ ] **Step 3: Add the voice to `Tines.kt`**

Change the enum to:

```kotlin
enum class TinesVoice { BELL, CHIME, BLOCK, ZAP, TOY, KALIMBA }
```

Add `import kotlin.math.abs` and `import kotlin.math.pow` to the imports. Inside `object Tines`, after `val RATIOS = ...`, add:

```kotlin
    /**
     * KALIMBA is TINES' one melodic voice, so its TUNE snaps to semitones
     * from a root of A3 over two octaves — PLUCK's convention
     * ([Pluck.TUNE_SEMITONES]), applied here because `SynthKits.melodic()`'s
     * kalimba pads are pad recipes that replay through macro values and
     * the note has to be reachable as one. The root and span match the
     * PLUCK voice this one replaces, so the kit's notes do not move.
     */
    const val KALIMBA_TUNE_SEMITONES = 24
    const val KALIMBA_ROOT_HZ = 220f

    /**
     * A clamped-free bar's partials — a kalimba tine — from the
     * Euler–Bernoulli eigenvalues βL = 1.8751, 4.6941, 7.8548 squared and
     * normalised to the first. The free-free bar in [Modes.tableFor] is
     * the same family. Citations: Fletcher & Rossing, The Physics of
     * Musical Instruments (bars); Rossing, Science of Percussion
     * Instruments (mbira) — recorded in the plan workspace before landing.
     * Two overtones only: the fourth (34.4 f0) would sit above 15 kHz over
     * most of the range and above Nyquist at the top.
     */
    internal val KALIMBA_PARTIALS = floatArrayOf(1f, 6.267f, 17.548f)

    /** The snapped note KALIMBA's TUNE lands on; every other TINES voice has a continuous carrier range. */
    fun frequencyFor(voice: TinesVoice, tune: Float): Float =
        frequencyFor(voice, Math.round(tune.coerceIn(0f, 1f) * KALIMBA_TUNE_SEMITONES))

    internal fun frequencyFor(voice: TinesVoice, semitone: Int): Float {
        require(voice == TinesVoice.KALIMBA) { "only KALIMBA snaps TUNE to semitones; $voice has a continuous carrier range" }
        return KALIMBA_ROOT_HZ * 2f.pow(semitone / 12f)
    }
```

In `macrosFor`, add a branch before the closing brace of the `when`:

```kotlin
        TinesVoice.KALIMBA -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("BUZZ", 0.15f), MacroSpec("BRIGHT", 0.5f),
            MacroSpec("DECAY", 0.45f),
        )
```

In `render`'s `when (voice)`, add:

```kotlin
            TinesVoice.KALIMBA -> kalimba(m, renderRate)
```

After `toy(...)` at the end of the object, add:

```kotlin
    /**
     * A plucked tine: a harmonic strike for the tongue, then two near-pure
     * partials at the bar's own ratios, each dying faster than the one
     * below it, then the buzzers. DECAY tops out at 1.0 s so the voice stays
     * under the 1.5 s one-shot bound every TINES voice keeps.
     */
    private fun kalimba(m: Map<String, Float>, rate: Int): FloatArray {
        val hz = frequencyFor(TinesVoice.KALIMBA, m.getValue("TUNE"))
        val bright = m.getValue("BRIGHT")
        val buzz = m.getValue("BUZZ")
        val t60 = Dsp.expMap(m.getValue("DECAY"), 0.3f, 1.0f)

        val out = FloatArray(frames(t60 * 1.3f, rate))
        // The tongue: its index is the thumb's hardness, and the bite keeps
        // the pluck at the front.
        strike(out, hz, ratio = 1f, index = Dsp.lin(bright, 0.3f, 1.6f), t60 = t60, bite = 2.5f, rate = rate)
        // The bar's overtones, each a near-pure partial (ratio 1, tiny
        // index) that BRIGHT brings up and that die faster than the tongue.
        val upper = Dsp.lin(bright, 0.15f, 0.5f)
        strike(out, hz * KALIMBA_PARTIALS[1], ratio = 1f, index = 0.2f, t60 = t60 * 0.25f, bite = 2f, gain = upper, rate = rate)
        strike(out, hz * KALIMBA_PARTIALS[2], ratio = 1f, index = 0.1f, t60 = t60 * 0.10f, bite = 2f, gain = upper * 0.35f, rate = rate)
        if (buzz > 0.01f) rattle(out, buzz, Dsp.seedFor("TINES", TinesVoice.KALIMBA.name, "BUZZ"))
        return out
    }

    /**
     * The mbira's buzzers — bottle caps, shells on the soundboard — rattle
     * at the peaks of the vibration, so the buzz lives in the attack and
     * dies with the note. Amplitude-gated noise: wherever the tongue swings
     * past a threshold BUZZ lowers, add seeded noise scaled by the excess.
     * BUZZ 0 is a clean thumb piano; BUZZ 1 is a full rattle, the ugly end.
     */
    private fun rattle(out: FloatArray, buzz: Float, seed: Int) {
        val noise = Dsp.Noise(seed)
        var peak = 0f
        for (v in out) peak = maxOf(peak, abs(v))
        if (peak <= 0f) return
        val threshold = peak * Dsp.lin(buzz, 0.9f, 0.15f)
        val gain = Dsp.lin(buzz, 0.5f, 2.5f)
        for (i in out.indices) {
            val excess = abs(out[i]) - threshold
            if (excess > 0f) out[i] += gain * excess * noise.next()
        }
    }
```

- [ ] **Step 4: Add the presets**

In `TinesPresets.kt`, add to `forVoice`'s `when`:

```kotlin
        TinesVoice.KALIMBA -> kalimbaPresets
```

and after `toyPresets` (the last preset list) add:

```kotlin
    // The kalimba names carried over from PLUCK's voice of the same name,
    // mapped onto TUNE/BUZZ/BRIGHT/DECAY; authored from the DSP like every
    // other list here, to be re-authored by ear.
    private val kalimbaPresets = listOf(
        p(TinesVoice.KALIMBA, "THUMBPIANO", "TUNE" to 0.3f, "BUZZ" to 0.1f, "BRIGHT" to 0.4f, "DECAY" to 0.5f),
        p(TinesVoice.KALIMBA, "RUSTY TINE", "TUNE" to 0.2f, "BUZZ" to 0.35f, "BRIGHT" to 0.3f, "DECAY" to 0.4f),
        p(TinesVoice.KALIMBA, "BRASS TINE", "TUNE" to 0.4f, "BUZZ" to 0.05f, "BRIGHT" to 0.7f, "DECAY" to 0.6f),
        p(TinesVoice.KALIMBA, "WOOD BODY", "TUNE" to 0.35f, "BUZZ" to 0.0f, "BRIGHT" to 0.3f, "DECAY" to 0.45f),
        p(TinesVoice.KALIMBA, "GLASSY MBIRA", "TUNE" to 0.5f, "BUZZ" to 0.2f, "BRIGHT" to 0.85f, "DECAY" to 0.7f),
        p(TinesVoice.KALIMBA, "SOFT PLUCK", "TUNE" to 0.45f, "BUZZ" to 0.0f, "BRIGHT" to 0.2f, "DECAY" to 0.35f),
        p(TinesVoice.KALIMBA, "TWANG", "TUNE" to 0.6f, "BUZZ" to 0.45f, "BRIGHT" to 0.6f, "DECAY" to 0.3f),
        p(TinesVoice.KALIMBA, "DUSTY KEYS", "TUNE" to 0.55f, "BUZZ" to 0.25f, "BRIGHT" to 0.45f, "DECAY" to 0.5f),
        p(TinesVoice.KALIMBA, "BRIGHT TINE", "TUNE" to 0.7f, "BUZZ" to 0.1f, "BRIGHT" to 0.95f, "DECAY" to 0.55f),
        p(TinesVoice.KALIMBA, "MUTED THUMB", "TUNE" to 0.25f, "BUZZ" to 0.0f, "BRIGHT" to 0.15f, "DECAY" to 0.15f),
        p(TinesVoice.KALIMBA, "FULL RATTLE", "TUNE" to 0.65f, "BUZZ" to 0.9f, "BRIGHT" to 0.6f, "DECAY" to 0.5f),
        p(TinesVoice.KALIMBA, "HIGH TINE", "TUNE" to 0.85f, "BUZZ" to 0.15f, "BRIGHT" to 0.8f, "DECAY" to 0.8f),
    )
```

- [ ] **Step 5: Add the SYNTH screen's drum class**

In `app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt`, the `when` at lines 1145-1149 reads:

```kotlin
private val TinesVoice.drumClass: DrumClass
    get() = when (this) {
        TinesVoice.BELL, TinesVoice.CHIME -> DrumClass.TONAL
        TinesVoice.BLOCK, TinesVoice.ZAP, TinesVoice.TOY -> DrumClass.PERC
    }
```

Change the first branch to `TinesVoice.BELL, TinesVoice.CHIME, TinesVoice.KALIMBA -> DrumClass.TONAL`. Nothing else in `:app`, `:shell`, `:kit` or `:cli` enumerates TINES voices by name (`TinesVoice.entries` at `SynthScreen.kt:988` and `UserPresetsTest.kt:105` pick the new one up automatically).

- [ ] **Step 6: Run the TINES tests and the tuning bound**

Run: `./gradlew :synth:test --tests "com.snipsnap.synth.TinesTest" --tests "com.snipsnap.synth.TinesPresetsTest" --tests "com.snipsnap.synth.TuningAccuracyTest" --console=plain 2>&1 | tail -30`

Expected: PASS, including the existing `every voice is a one-shot, not a phrase` (KALIMBA at full DECAY renders 1.3 s) and `every voice renders clean audio at defaults and both corners`. If `factory defaults all classify as percussion` fails for KALIMBA, open that test: it asserts a set of `DrumClass` values; add `DrumClass.TONAL` to the accepted set with a comment that KALIMBA is the engine's melodic voice and the SYNTH screen files it as TONAL. If `KALIMBA rings the bar's partials…` fails, print the three energies and stop — the partial placement is the point of the voice and the reviewer decides, not a tolerance change.

- [ ] **Step 7: Compile the app module**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | tail -15`

Expected: BUILD SUCCESSFUL. If the Android SDK is not configured on this machine the task fails before compiling; report that verbatim and continue — the `when` was made exhaustive by hand in Step 5.

- [ ] **Step 8: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Tines.kt \
        synth/src/main/kotlin/com/snipsnap/synth/TinesPresets.kt \
        app/src/main/kotlin/com/snipsnap/app/ui/SynthScreen.kt \
        synth/src/test/kotlin/com/snipsnap/synth/TinesTest.kt \
        synth/src/test/kotlin/com/snipsnap/synth/TuningAccuracyTest.kt
git commit -F - <<'EOF'
Give TINES a KALIMBA voice: three FM strikes at a clamped-free bar's ratios, plus BUZZ

A kalimba tine is not a string, so PLUCK's KALIMBA stayed flat under every
body the audition tried (spec 2026-09-25, decision 5). TINES' strike can
be placed at any multiple of the note, so the voice puts near-pure
partials at 6.267 and 17.548 times the fundamental - the Euler-Bernoulli
clamped-free ratios - under a harmonic tongue strike, and BUZZ adds the
mbira's bottle-cap rattle as amplitude-gated noise. TUNE snaps to
semitones from A3 so the melodic kit can replay the notes as macros.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DsZ2eJRrwVXd4cwMqvAGam
EOF
```

---

### Task 6: The audition set — generator, gradle task, page

**Files:**
- Create: `synth/src/test/kotlin/com/snipsnap/synth/PluckAuditionGenerator.kt`
- Modify: `synth/build.gradle.kts:41-49` (add a sibling of `generateThumpKit`), `.gitignore`, `synth/src/test/resources/audition/pluck-audition.html`

**Interfaces:**
- Consumes: `Pluck.render`, `Pluck.defaults`, `Pluck.TUNE_SEMITONES`, `PluckPatch(name, voice, macros)`, `Velocity.atVelocity(patch, velocity)`, `Tines.render`, `Tines.KALIMBA_TUNE_SEMITONES`, `WavWriter.write(file, snip, depth)`, `WavWriter.BitDepth.PCM_16`.
- Produces: `./gradlew :synth:generatePluckAudition` writing `testkit/pluck-audition/{NYLON,KOTO,HARP}/p1_*.wav`, `testkit/pluck-audition/KALIMBA_AB/*.wav` and `testkit/pluck-audition/index.html`. The page is then published by the session that owns the artifact (see the handoff after Task 7); the artifact keeps the `VOICE/00_shipped.wav` files from the spike publish, which are the pre-Phase-1 renders the page compares against.

- [ ] **Step 1: Ignore the generated folder**

Append to `.gitignore`:

```
# The PLUCK audition clips and page, rendered by :synth:generatePluckAudition
# and published as the listening artifact; never committed.
testkit/pluck-audition/
```

- [ ] **Step 2: Write the generator**

Create `synth/src/test/kotlin/com/snipsnap/synth/PluckAuditionGenerator.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Renders the Phase 1 audition set of
 * docs/superpowers/specs/2026-09-25-pluck-depth-design.md under
 * testkit/pluck-audition/ (gitignored): 16-bit clips at one RMS, plus the
 * listening page copied from the test resources. Run via
 * `./gradlew :synth:generatePluckAudition`.
 *
 * The folder is then published as the listening artifact the spec names.
 * The artifact keeps the `VOICE/00_shipped.wav` clips from the spike
 * publish — the pre-Phase-1 renders — because a republish keeps files it
 * is not handed; nothing here can render the old engine.
 */
object PluckAuditionGenerator {

    /** Quiet on purpose: low enough that no clip needs the peak guard. */
    private const val AUDITION_RMS = 0.03f

    /** The melodic kit's five kalimba notes as semitones above A3 (A07..A11 in SynthKits.melodic). */
    private val KIT_NOTES = listOf("C4" to 3, "D4" to 5, "E4" to 7, "G4" to 10, "A4" to 12)

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.firstOrNull() ?: "../testkit/pluck-audition")
        root.mkdirs()
        var count = 0

        for (voice in listOf(PluckVoice.NYLON, PluckVoice.KOTO, PluckVoice.HARP)) {
            val dir = File(root, voice.name)
            val patch = PluckPatch("AUDITION", voice, Pluck.defaults(voice))
            fun write(name: String, snip: Snip) {
                WavWriter.write(File(dir, "$name.wav"), level(snip), WavWriter.BitDepth.PCM_16)
                count++
            }
            write("p1_default", Pluck.render(voice))
            write("p1_strike_bridge", Pluck.render(voice, mapOf("STRIKE" to 0f)))
            write("p1_strike_centre", Pluck.render(voice, mapOf("STRIKE" to 1f)))
            write("p1_ring", Pluck.render(voice, mapOf("DAMP" to 0f)))
            write("p1_thud", Pluck.render(voice, mapOf("DAMP" to 1f)))
            write("p1_soft", Velocity.atVelocity(patch, 0.3f))
            write("p1_hard", Velocity.atVelocity(patch, 1f))
        }

        val ab = File(root, "KALIMBA_AB")
        fun writeAb(name: String, snip: Snip) {
            WavWriter.write(File(ab, "$name.wav"), level(snip), WavWriter.BitDepth.PCM_16)
            count++
        }
        for ((note, semi) in KIT_NOTES) {
            // Same root (A3) and span (24) on both engines, so one macro value is the same note.
            val tune = semi / Pluck.TUNE_SEMITONES.toFloat()
            writeAb("pluck_$note", Pluck.render(PluckVoice.KALIMBA, mapOf("TUNE" to tune)))
            writeAb("tines_$note", Tines.render(TinesVoice.KALIMBA, mapOf("TUNE" to tune)))
        }
        val a4 = 12 / Tines.KALIMBA_TUNE_SEMITONES.toFloat()
        writeAb("tines_buzz_0", Tines.render(TinesVoice.KALIMBA, mapOf("TUNE" to a4, "BUZZ" to 0f)))
        writeAb("tines_buzz_1", Tines.render(TinesVoice.KALIMBA, mapOf("TUNE" to a4, "BUZZ" to 1f)))
        writeAb("tines_bright_0", Tines.render(TinesVoice.KALIMBA, mapOf("TUNE" to a4, "BRIGHT" to 0f)))
        writeAb("tines_bright_1", Tines.render(TinesVoice.KALIMBA, mapOf("TUNE" to a4, "BRIGHT" to 1f)))

        val page = PluckAuditionGenerator::class.java.getResourceAsStream("/audition/pluck-audition.html")
            ?: error("the listening page is missing from synth/src/test/resources/audition/")
        File(root, "index.html").outputStream().use { out -> page.use { it.copyTo(out) } }
        println("wrote $count clips + index.html under ${root.absolutePath}")
    }

    /**
     * One RMS for every clip, for a fair A/B: the spike measured shipped
     * PLUCK as peak-limited, so loudness would decide the comparison
     * otherwise. A peak guard keeps the file in range.
     */
    private fun level(snip: Snip): Snip {
        val out = snip.samples.copyOf()
        var acc = 0.0
        for (v in out) acc += v.toDouble() * v
        val rms = sqrt(acc / out.size.coerceAtLeast(1)).toFloat()
        var g = AUDITION_RMS / rms.coerceAtLeast(1e-9f)
        var peak = 0f
        for (v in out) peak = maxOf(peak, abs(v))
        if (peak * g > 0.99f) g = 0.99f / peak
        for (i in out.indices) out[i] *= g
        return Snip(out, channels = 1, sampleRate = snip.sampleRate)
    }
}
```

- [ ] **Step 3: Register the gradle task**

In `synth/build.gradle.kts`, directly after the `generateThumpKit` registration (ends at line 49), add:

```kotlin
/** Render the PLUCK depth audition clips and page under testkit/pluck-audition/. See PluckAuditionGenerator. */
tasks.register<JavaExec>("generatePluckAudition") {
    group = "distribution"
    description = "Render the PLUCK depth audition clips and listening page under testkit/pluck-audition/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.PluckAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/pluck-audition")
}
```

- [ ] **Step 4: Point the page at the Phase 1 clips**

Edit `synth/src/test/resources/audition/pluck-audition.html`. Five edits, all inside the file's single `<script>` or the markup above it.

(a) In the titlebar markup, change `BODY SPIKE &middot; 2026-09-25` to `PHASE 1 &middot; STRIKE, RING, KALIMBA`.

(b) Replace the guide's three `<p>` paragraphs with:

```html
    <p>Every clip sits at the same RMS, deliberately quiet: turn up and use headphones.</p>
    <p>Strings: BEFORE is the engine as it shipped; the PHASE 1 clips are the new engine. Mark each clip against the instrument. STRIKE and DAMP are shown at their ends on purpose.</p>
    <p>KALIMBA: the same five notes from the PLUCK string and the new TINES voice. Pick the instrument, not the louder one.</p>
```

(c) Replace the whole block from `var VOICES = [` through the end of the `var GROUPS = [ ... ];` statement (the `REACTIONS` declaration that follows stays) with:

```js
  // [file id, name, description]
  var STRING_GROUPS = [
    { label: 'BEFORE AND AFTER', key: true, clips: [
      ['00_shipped', 'BEFORE PHASE 1', 'the engine as it shipped, from the spike publish'],
      ['p1_default', 'PHASE 1 DEFAULT', 'STRIKE at its default; the render follows the decay']
    ]},
    { label: 'STRIKE', clips: [
      ['p1_strike_bridge', 'STRIKE' + DOT + 'BRIDGE', 'thin and bright, low harmonics cut'],
      ['p1_strike_centre', 'STRIKE' + DOT + 'CENTRE', 'hollow, even harmonics gone']
    ]},
    { label: 'DAMP', clips: [
      ['p1_ring', 'DAMP 0', 'rings out to the 4 s ceiling'],
      ['p1_thud', 'DAMP 1', 'the dead end']
    ]},
    { label: 'VELOCITY THROUGH PICK', clips: [
      ['p1_soft', 'SOFT', 'velocity 0.3, re-rendered darker through PICK'],
      ['p1_hard', 'HARD', 'velocity 1.0, the preset as written']
    ]}
  ];

  var KALIMBA_GROUPS = [
    { label: 'PLUCK VERSUS TINES' + DOT + 'C4', key: true, clips: [
      ['pluck_C4', 'PLUCK C4', 'the string model'],
      ['tines_C4', 'TINES C4', 'three FM strikes at the bar\'s ratios']
    ]},
    { label: 'D4', clips: [['pluck_D4', 'PLUCK D4', ''], ['tines_D4', 'TINES D4', '']] },
    { label: 'E4', clips: [['pluck_E4', 'PLUCK E4', ''], ['tines_E4', 'TINES E4', '']] },
    { label: 'G4', clips: [['pluck_G4', 'PLUCK G4', ''], ['tines_G4', 'TINES G4', '']] },
    { label: 'A4', clips: [['pluck_A4', 'PLUCK A4', ''], ['tines_A4', 'TINES A4', '']] },
    { label: 'TINES EXTREMES' + DOT + 'A4', clips: [
      ['tines_buzz_0', 'BUZZ 0', 'clean thumb piano'],
      ['tines_buzz_1', 'BUZZ 1', 'full rattle, the ugly end'],
      ['tines_bright_0', 'BRIGHT 0', 'flesh'],
      ['tines_bright_1', 'BRIGHT 1', 'thumbnail']
    ]}
  ];

  // Factory TUNE snapped to a semitone from each voice's root.
  var VOICES = [
    { id: 'NYLON',      display: 'NYLON',   note: 'G3',  hz: '196 HZ',   root: 'A2' + DOT + '110 HZ', macros: 'DAMP .45' + DOT + 'PICK .40' + DOT + 'STRIKE .75' + DOT + 'DOUBLE .15', body: 'no body until phase 2', groups: STRING_GROUPS },
    { id: 'KOTO',       display: 'KOTO',    note: 'C#4', hz: '278 HZ',   root: 'D3' + DOT + '147 HZ', macros: 'DAMP .40' + DOT + 'PICK .75' + DOT + 'STRIKE .60' + DOT + 'DOUBLE .45', body: 'no body until phase 2', groups: STRING_GROUPS },
    { id: 'HARP',       display: 'HARP',    note: 'F4',  hz: '350 HZ',   root: 'E3' + DOT + '165 HZ', macros: 'DAMP .20' + DOT + 'PICK .60' + DOT + 'STRIKE .75' + DOT + 'DOUBLE .20', body: 'no body until phase 2', groups: STRING_GROUPS },
    { id: 'KALIMBA_AB', display: 'KALIMBA', note: 'C4 TO A4', hz: 'the kit\'s five notes', root: 'A3' + DOT + '220 HZ', macros: 'TINES: BUZZ .15' + DOT + 'BRIGHT .50' + DOT + 'DECAY .45', body: 'PLUCK string versus TINES tine', groups: KALIMBA_GROUPS }
  ];
```

(d) In the card-building loop, make these four replacements:
- `var h2 = el('h2', null, v.id);` → `var h2 = el('h2', null, v.display);`
- `ro.innerHTML = '<span><b>' + v.note + '</b>' + DOT + v.hz + ' HZ</span>...` → `ro.innerHTML = '<span><b>' + v.note + '</b>' + DOT + v.hz + '</span><span>ROOT ' + v.root + '</span><span>' + v.macros + '</span>';` (the literal `' HZ'` is dropped; `hz` now carries its own unit)
- `GROUPS.forEach(function (g) {` (inside the per-voice loop) → `v.groups.forEach(function (g) {`
- `var fid = c[0], name = c[1], desc = c[2], dur = c[3] || v.dur;` → `var fid = c[0], name = c[1], desc = c[2];` and, three lines below, `meta.appendChild(el('span', 'dur', dur.toFixed(2) + ' S'));` → `var durEl = el('span', 'dur', ''); meta.appendChild(durEl); row.durEl = durEl;`

Then in `paintVoice`, replace `GROUPS.forEach(function (g) { g.clips.forEach(function (c) { paintChips(voiceId, c[0]); }); });` with:

```js
    var voice = VOICES.filter(function (x) { return x.id === voiceId; })[0];
    if (voice) voice.groups.forEach(function (g) { g.clips.forEach(function (c) { paintChips(voiceId, c[0]); }); });
```

And in `ensureAudio()`, after the `'error'` listener, add a listener that fills the duration once the clip is known:

```js
    audio.addEventListener('loadedmetadata', function () {
      if (current && current.row.durEl && isFinite(audio.duration)) {
        current.row.durEl.textContent = audio.duration.toFixed(2) + ' S';
      }
    });
```

(e) Replace the four verdict questions. In the markup, the four `.q` blocks become:

```html
    <div class="q">
      <div class="ask">1 &middot; Do STRIKE's ends earn their place on NYLON, KOTO and HARP?</div>
      <div class="sub">BRIDGE and CENTRE against BEFORE.</div>
      <textarea id="q_strike" placeholder="&hellip;"></textarea>
    </div>
    <div class="q">
      <div class="ask">2 &middot; Does DAMP 0 ring long enough, and is DAMP 1 still the dead thud?</div>
      <textarea id="q_damp" placeholder="&hellip;"></textarea>
    </div>
    <div class="q">
      <div class="ask">3 &middot; PLUCK or TINES for the kalimba, and does BUZZ read as a rattle?</div>
      <textarea id="q_kalimba" placeholder="&hellip;"></textarea>
    </div>
    <div class="q">
      <div class="ask">Anything else</div>
      <textarea id="q_else" placeholder="&hellip;"></textarea>
    </div>
```

and in the script, every occurrence of the array `['q_body', 'q_pick', 'q_dominant', 'q_else']` — there are three: in `paintOverall`, in `apply` (the `key === 'overall'` branch), and the input-listener block — becomes `['q_strike', 'q_damp', 'q_kalimba', 'q_else']`. Two object literals use the same four names as keys and change with them: the initialiser becomes `var state = { overall: { q_strike: '', q_damp: '', q_kalimba: '', q_else: '' } };` and `snapshotOf`'s `overall` branch becomes `{ q_strike: state.overall.q_strike, q_damp: state.overall.q_damp, q_kalimba: state.overall.q_kalimba, q_else: state.overall.q_else }`. After the edit, `grep -c "q_body\|q_pick\|q_dominant" synth/src/test/resources/audition/pluck-audition.html` prints `0`.

Finally, in the footer `.foot`, change `RENDERED FROM PluckBodySpike.kt &middot; 44 CLIPS &middot;` to `RENDERED BY generatePluckAudition &middot; PHASE 1 &middot;` and drop the words `BODY TABLES ARE BALLPARK GUESSES, NOT SOURCED.`

- [ ] **Step 5: Confirm the page is still pure ASCII and references only files the generator writes**

Run:

```bash
LC_ALL=C grep -c -P '[^\x00-\x7F]' synth/src/test/resources/audition/pluck-audition.html
grep -o "'[a-z0-9_]*'" synth/src/test/resources/audition/pluck-audition.html | grep -E "^'(p1_|pluck_|tines_|00_shipped)" | sort -u
```

Expected: the first prints `0`. The second lists exactly `00_shipped`, the seven `p1_*` ids, `pluck_{C4,D4,E4,G4,A4}`, `tines_{C4,D4,E4,G4,A4}`, `tines_buzz_0`, `tines_buzz_1`, `tines_bright_0`, `tines_bright_1` — every id except `00_shipped` is a file the generator writes.

- [ ] **Step 6: Render the set**

Run: `./gradlew :synth:generatePluckAudition --console=plain 2>&1 | tail -5 && find testkit/pluck-audition -type f | sort`

Expected: `wrote 35 clips + index.html under .../testkit/pluck-audition`, and the listing shows 7 clips in each of `NYLON/`, `KOTO/`, `HARP/`, 14 in `KALIMBA_AB/`, and `index.html`. `git status --short` shows the folder is not listed (ignored).

- [ ] **Step 7: Commit**

```bash
git add .gitignore synth/build.gradle.kts \
        synth/src/test/kotlin/com/snipsnap/synth/PluckAuditionGenerator.kt \
        synth/src/test/resources/audition/pluck-audition.html
git commit -F - <<'EOF'
Render the PLUCK Phase 1 audition set with the listening page

generatePluckAudition writes the strings' STRIKE, DAMP and velocity clips
and the PLUCK-versus-TINES kalimba pairs at one RMS, 16-bit, beside the
page whose chips and verdict form save to the artifact's store. The gate
is Josh's ears, on the phone, before Phase 2 starts.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DsZ2eJRrwVXd4cwMqvAGam
EOF
```

---

### Task 7: Whole-suite verification

**Files:** none modified.

- [ ] **Step 1: Run every synth test**

Run: `./gradlew :synth:test --console=plain 2>&1 | grep -E "FAILED|BUILD|tests completed" | head -20`

Expected: `BUILD SUCCESSFUL` and no `FAILED` lines. If anything fails, report the test name and assertion message verbatim and stop; do not edit a test outside the instructions in Tasks 2 and 5.

- [ ] **Step 2: Build the CLI**

Run: `./gradlew :cli:installDist --console=plain 2>&1 | tail -3 && ./cli/build/install/cli/bin/cli synth PLUCK HARP --preset 1 --out /tmp/pluck-check && ls /tmp/pluck-check`

Expected: BUILD SUCCESSFUL and one WAV written — the CLI's synth verb still renders PLUCK through `Patches`.

- [ ] **Step 3: Report**

Print `git log --oneline -8` and the clip count from Task 6 Step 6. No commit.

---

## Handoff after Task 7 (the session that owns the artifact does this, not an executor)

Publish `testkit/pluck-audition/index.html` to the existing artifact `https://claude.ai/artifact/Hyiby2DKd4A2FWX6ZtC7VR` with `root = testkit/pluck-audition` and the 35 clips as `files`, keeping `capabilities: {db: {}}`. The `VOICE/00_shipped.wav` clips already on the artifact are kept because they are not in the new file map. Then send Josh the link; his chips and the `verdicts/{NYLON,KOTO,HARP,KALIMBA_AB,overall}` documents are Phase 1's gate, and Phase 2's plan is written from them.
