# TERRA Hook R1 (HIT in the Engine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let another pad's first 20 ms colour how hard each of a TERRA drum's modes rings (HIT: 0 is today, 0.5 subtle, 1 strong), kept in the recipe as data, while a TERRA pad with no striker renders byte for byte as today.

**Architecture:** A frozen copy of today's TERRA (`LegacyTerraBank`) and a 40-case guard land first. `Terra.strikeAndModalBank` then gains a per-mode level curve and a pitch curve, both indexed from the strike, with today's arithmetic kept verbatim when neither is given. `Terra.captureStriker` takes a safe 882-sample head, HIT turns it into the level curve by round two's COLOURED gain, `TerraPatch` carries it as an optional `Striker` written at version 2, and a generator renders R1's ten-clip page.

**Tech Stack:** Kotlin 2.0 / JVM 17, Gradle 8.14 (`./gradlew --no-daemon`), `kotlin.test`. From `:synth`: `Terra`, `Modes`, `Dsp`, `Punch`, `Fork`, `Patches`, `PadRecipe`, `Velocity`. From `:audio`: `Snip`, `Cleanup`, `Resampler`, `Fft`, `Classifier`, `WavReader`, `WavWriter`. From `:json`: `Json`, `JsonValue`. From `:shell`: `Breed`, `UserPresets`, `RecipeReplay`, `Copy`.

**Spec:** [`docs/superpowers/specs/2026-09-30-terra-hit-bend-talk-design.md`](../specs/2026-09-30-terra-hit-bend-talk-design.md), R1 only: HIT in the engine (tests 1–3, 6–8 and 11 in full, tests 4–5 with a striker only, and R1's page).

## Global Constraints

- No driver renders today's TERRA byte for byte, proved by a frozen `LegacyTerraBank` and a 40-case guard (16 `TerraKits.classic()` pads, 4 voices at defaults, 20 macro probes; `assertContentEquals`, `assertEquals(40, cases)`) that lands first and passes against the unchanged source.
- The hook is one change in `Terra.strikeAndModalBank`: a per-mode level curve as a factor on `mode.gain` and a pitch curve as a factor on the droop line.
- The null path is a separate branch that runs today's two expressions verbatim. With an input, the spec writes `fundamentalHz * (1f + droopDepth * exp(-t / DROOP_TAU_SECONDS)) * pitch` and `sin(phases[k]) * (mode.gain * level) * decay`, and says both are Phase 0's `P0Bank` association. The level half is. The pitch half is not: `P0Bank` computes `p.fundamentalHz * (droop * pm(n).coerceIn(PITCH_MIN, PITCH_MAX))` (record, Appendix B.1). This plan computes `fundamentalHz * ((1f + droopDepth * exp(-t / DROOP_TAU_SECONDS)) * p)`, `P0Bank`'s grouping. An exact 1f is still a no-op, so no R1 test moves, and R3's claim that BEND through the bank equals the prototype bit for bit can hold. The spec's wording should be corrected to match.
- Both curves are indexed from the strike (`i − onsetSamples`, so CLACK lands first) and hold their last value past their end.
- The skip guard reads the table gain only (`mode.gain == 0f`), before the phase accumulates, so a level of 0 never skips a mode's phase.
- The pitch multiplier is clamped to 0.25–4 before it reaches the skip guard.
- The bank renders at `RATE · Dsp.OVERSAMPLE` = 176.4 kHz; the striker head is stored at 44.1 kHz, exactly `Fork.STRIKER_SAMPLES` (882) long, and resampled once at render with `Resampler.resample` (3528 samples).
- HIT: `G_k(n) = (1 − c) + c · s · |P_k(n)|`, `c` = HIT; `P_k(n) = Σ_{m=0..n} x[m] · r_k^(−m) · e^(−j·θ_k·m)` in double; `θ_k = 2π · f0 · ratio_k / RR` (nominal pitch, droop ignored); `r_k = exp(−6.9078 / (t60_k · RR))`; `|P_k(n)|` constant for `n ≥ M` (last non-zero sample of `x`, plus 1).
- Level match: `s = refPeak / peak(body_s)`; `refPeak` = peak of today's `0.65 · modalSum` over the first `3·RR/f0 + 16` samples; `body_s` = TERRA's bank at gains `g_k·|P_k(n)|`, no exciter, over the first `max(3·RR/f0, M) + 64` samples.
- `c = 0` is the null path outright and never computes `s`; a non-finite `s` renders the striker as absent (today's body).
- HIT 0 = today, 0.5 = subtle, 1 = strong = COLOURED 100 % (decision 1, taken 2026-09-30: "As strong").
- HIT's amount lives inside the striker (`TerraPatch.Striker.hit`), never as a TERRA macro (decision 3).
- HIT 1's soft attack is accepted as measured (decision 4); test 8 prints it beside Phase 0's figures and does not bound it.
- The algorithms are the Phase-0 record's §8.1 (HIT) and §8.2 (the capture rule), with the prototype bank of Appendix B.1. The plan rebuilds them on the house's primitives and copies no spike function. The one thing it lifts from the spikes is data: the six synthesised strikers' macro values, which are not in the tree (the record's §3.1 names the strikers; Appendix C's evidence folder holds the values).
- `Terra.render`'s third parameter is named `drivers`, as in the spec's `Terra.render(voice, macros, drivers)`. In R1 it is typed `TerraPatch.Striker?`; R3 widens the type to carry `bend` and `talk`, and call sites stay as they are.
- Capture rule, in order: fold to mono (`Cleanup.toMono`; a non-44.1 kHz source keeps about 100 ms past its onset, then resamples to 44100) → `pk` = finite peak of the source → `onset` = first finite index with `|s| ≥ 0.01 · pk`, `start = max(0, onset − 44)` → 882 samples from `start`, zero-padded, non-finite set to 0 → head peak below `1e-4` before normalising = no striker (decision 20) → `Dsp.normalize(head, 1f)` → `Fork.striker`'s 2 ms (88-sample) raised-cosine tail fade.
- The capture is public, `Terra.captureStriker(source: Snip): FloatArray?`; `Fork.striker` is left untouched.
- `TerraPatch(name, voice, macros, striker: Striker? = null)` is a non-data class; `Striker(head, hit, from)`: head exactly 882 finite samples, hit in 0..1, from null or at most `MAX_FROM_CHARS` = 24 characters with no control characters; arrays copied in and out; every rule checked in `init` (`IllegalArgumentException`) and on decode (`JsonException`).
- `from` is display only (rendering ignores it) but part of the value (`equals`, `hashCode`, `copy`, JSON).
- A plain `TerraPatch` writes `"version": 1` and exactly today's bytes; a driven one writes `"version": 2`; the decoder accepts version 1 without a striker and version 2 with one and refuses anything else (`JsonException`); `Patches.VERSION` stays 1.
- The cavity's 75 Hz stage stays fixed; BUZZ follows the striker (decision 2's default, not owner-approved), so test 6 is printed, not pinned, until R1's page answers question 3.
- TERRA keeps `Velocity`'s `soften` fallback; HIT is not registered as a brightness override.
- Gates: at most 10 clips and 3 questions; clips mono 44.1 kHz, levelled by `AuditionLevel.level`, written by `TerraAuditionGenerator`.
- Not in R1: the 14th `Engine` entry, presets, scramble and starter kit (R2); `bend` and `talk` (R3); the chooser, the pad-sheet group and any `:app` change (R4).
- Tests use `kotlin.test`, never Jupiter. CI sweeps stay small, because CI minutes are metered, and the full ten-striker tables are opt-in.
- The opt-in is the environment variable `TERRA_FULL=1`. This departs from the spec's "system property, BORE's split". No BORE precedent for either exists in the tree (`grep -rn "getProperty\|getenv" synth/src/test` finds nothing), and Gradle's forked test JVM inherits the environment, so a variable needs no change to `synth/build.gradle.kts`. A system property would need a `systemProperty` forward in `tasks.test`.
- Always `./gradlew --no-daemon`.
- Judge green by Gradle's exit code, never by grepping output.
- Never skip, disable or loosen a test without printing the measurement and recording it in the test's comment.
- Thresholds are measured numbers with about 20 % margin; where the built code disagrees with one, print both numbers, record the measurement in the test's comment and set the threshold from it, never silently.
- Commit titles are plain declarative prose, with no `feat:` prefixes.
- No maker or product names in code, comments, commits or PR text.
- Never put a model identifier in a commit message, PR title, PR body or code comment.

## Review Focus

- **A phone recording at 48 kHz.** The new ~100 ms truncation runs before the resampler, and Phase 0's G-C1 never covered it. A NaN in the lead silence or an Inf after the window must not reach the head, and the head must still be the hit. Pinned in Task 3 (`a 48 kHz source is cut near its onset before it is resampled, and still captures the kick`).
- **A stereo source whose channels cancel.** The mono fold is exactly zero, so the pick must give no striker rather than a normalised rounding residue. Pinned in Task 3 (`every hostile source captures a real hit or nothing, never a broken head`, the "channels cancel" row).
- **A struck pad retuned afterwards.** `withMacros` must keep the striker. The projection must be recomputed from the new modes, and the result must stay finite and keep the plain render's length. Pinned in Task 5 (`retuning a struck pad keeps its striker and recomputes the colouring`).
- **The CLACKed bell under HIT 1.** The pre-roll must stay under the 50 % ceiling `Terra.kt:250-257` holds it to; Phase 0 measured 16.5 % with THUMP KICK. Pinned in Task 4 (`the CLACK pre-roll stays quiet under HIT`).
- **A hand-made head with nothing in it.** An all-zero head at HIT 1 gives a coloured body with no peak, so `s` is not finite. It must render today's body byte for byte, not NaN and not silence. Pinned in Task 4 (`a head with nothing in it renders today's body at HIT 1`).

---

## File Structure

| File | Responsibility |
|---|---|
| Create `synth/src/test/kotlin/com/snipsnap/synth/LegacyTerraBank.kt` | today's TERRA, frozen: the render, macro table, voices, exciters, bank, BUZZ and cavity stage |
| Create `synth/src/test/kotlin/com/snipsnap/synth/TerraCases.kt` | Phase 0's 40-case grid and the 16 Terra Kit pads, hard-coded so the guard does not depend on the decoder it guards |
| Create `synth/src/test/kotlin/com/snipsnap/synth/TerraFrozenTest.kt` | test 1: no driver renders the frozen TERRA, the kit renders it, plain recipes write today's bytes, identity curves change nothing |
| Modify `synth/src/test/kotlin/com/snipsnap/synth/DeterminismTest.kt:137-138` | TERRA's plain canary (Task 1) and struck canary (Task 5) |
| Modify `synth/src/test/kotlin/com/snipsnap/synth/PadRecipeTest.kt:35, :110-115` | TERRA in the one-patch-per-engine door and the factory-kit recipe check |
| Modify `synth/src/main/kotlin/com/snipsnap/synth/Terra.kt` | the bank's two inputs and their null path (Task 2), `captureStriker` (Task 3), HIT's colouring (Task 4), the striker render overload (Task 5) |
| Create `synth/src/test/kotlin/com/snipsnap/synth/TerraStrikers.kt` | the ten measured strikers, the factory-sample path, the hostile list |
| Create `synth/src/test/kotlin/com/snipsnap/synth/TerraCaptureTest.kt` | test 3's capture half: equal to `Fork.striker` where it should be, safe where it should not |
| Create `synth/src/test/kotlin/com/snipsnap/synth/TerraMeasure.kt` | the measures as Phase 0 defined them: onset, overtone balance, first-5-ms peak, f0, cavity drive, time above the BUZZ threshold |
| Modify `synth/src/test/kotlin/com/snipsnap/synth/TerraTest.kt:3-9, :353` | tests 7 (Task 2), 2 and 8, the Phase-0 reproduction and the strikers' provenance check (Task 4), the sweep, the hostile renders and test 6 printed (Task 7) |
| Modify `synth/src/main/kotlin/com/snipsnap/synth/TerraPatch.kt` (whole file) | the non-data class, `Striker`, the version rule |
| Create `synth/src/test/kotlin/com/snipsnap/synth/TerraPatchTest.kt` | tests 4–5 in `:synth`: JSON, refusals, defensive copies, `withMacros`, `Velocity`, retune, and a struck pad regenerating from a kit folder on disk |
| Create `shell/src/test/kotlin/com/snipsnap/shell/TerraStrikerCarryTest.kt` | tests 4–5 in `:shell`: `Breed`, `UserPresets`, `RecipeReplay` carry or refuse a driven pad |
| Modify `shell/src/test/kotlin/com/snipsnap/shell/SidecarFuzzTest.kt` | a struck TERRA recipe as a fuzz seed (every reader, `KitDiff` included), the spec's named hostile seeds and the four hostile `from` values |
| Modify `shell/src/test/kotlin/com/snipsnap/shell/DegenerateDoorsTest.kt:17, :202` | a struck TERRA recipe among the replay seeds |
| Modify `synth/src/test/kotlin/com/snipsnap/synth/TerraAuditionGenerator.kt:69-71` and append | the R1 page: ten clips, three questions, `R1/manifest.json` |
| Modify `synth/build.gradle.kts` (after `generateTerraAudition`, `:306-314`) | the `generateTerraR1Audition` task |

Only the BUZZ and drive table of test 6 has an opt-in full form: all ten strikers instead of three, read from the environment variable `TERRA_FULL=1` (see Global Constraints for why a variable and not a system property). The render-cost test always runs and always prints. Run the full table with `TERRA_FULL=1 ./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraTest" --rerun -i`.

---

### Task 1: Freeze today's TERRA, before anything changes

**Files:**
- Create: `synth/src/test/kotlin/com/snipsnap/synth/LegacyTerraBank.kt`
- Create: `synth/src/test/kotlin/com/snipsnap/synth/TerraCases.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/TerraFrozenTest.kt` (create)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/DeterminismTest.kt:137-138`
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/PadRecipeTest.kt:35`, `:110-115`

**Interfaces:**
- Consumes: `Terra.render(voice: TerraVoice, macros: Map<String, Float> = emptyMap()): Snip`, `Terra.macrosFor(voice): List<MacroSpec>`, `TerraKits.classic(): List<ArrangedPad?>`, `TerraPatch(name, voice, macros)` (today's data class), `Modes.atPosition`, `Modes.Mode`, `Dsp.expMap/lin/around/normalize/decimate/limitPeak/fadeTail/Noise/Biquad`, `Punch.applyOversampled(raw, amount, rate)`, `Json.write`, `JsonValue`.
- Produces:
  - `internal object LegacyTerraBank` with `fun macrosFor(voice: TerraVoice): List<MacroSpec>`, `fun defaults(voice: TerraVoice): Map<String, Float>` and `fun render(voice: TerraVoice, macros: Map<String, Float> = emptyMap()): Snip`.
  - `internal object TerraCases` with `class Case(val label: String, val voice: TerraVoice, val macros: Map<String, Float>)`, `class KitPad(val slot: String, val name: String, val voice: TerraVoice, val macros: Map<String, Float>)`, `val PROBES: List<Map<String, Float>>`, `val KIT: List<KitPad>` and `val all: List<Case>`.
  - Tasks 2, 4 and 7 consume `TerraCases.all` and `LegacyTerraBank.render`.

- [ ] **Step 1: Write the frozen copy**

`LegacyTerraBank.kt` is `Terra.kt`'s `object Terra` body at the head (`Terra.kt:46-593`) with comment-only lines removed, runs of blank lines collapsed and the object renamed. Nothing else changes. Create `synth/src/test/kotlin/com/snipsnap/synth/LegacyTerraBank.kt` with exactly this content:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * TERRA exactly as it renders before HIT, BEND and TALK
 * (docs/superpowers/specs/2026-09-30-terra-hit-bend-talk-design.md,
 * "Architecture", the neutral guard): `Terra.render`, its macro table, its
 * four voice functions, the exciters, `strikeAndModalBank`, `applyBuzz` and
 * the cavity stage, copied from `Terra.kt` with the comments removed and
 * nothing else changed. TerraFrozenTest holds the live engine to it, sample
 * for sample. Never edit this file to make that test pass: a difference is a
 * change to every saved TERRA pad.
 */
internal object LegacyTerraBank {

    fun macrosFor(voice: TerraVoice): List<MacroSpec> = when (voice) {
        TerraVoice.COMPOUND_MEMBRANE -> listOf(
            MacroSpec("TUNE", 0.35f),
            MacroSpec("DECAY", 0.5f),
            MacroSpec("FORCE", 0.5f),
            MacroSpec("POS", 0.25f),
            MacroSpec("DROOP", 0.23f),
        )
        TerraVoice.RESONANT_CAVITY -> listOf(
            MacroSpec("TUNE", 0.3f),
            MacroSpec("DECAY", 0.5f),
            MacroSpec("FORCE", 0.5f),
            MacroSpec("POS", 0.25f),
            MacroSpec("DROOP", 0.1f),
            MacroSpec("CAVITY", 0.5f),
            MacroSpec("BUZZ", 0f),
        )
        TerraVoice.CONICAL_BELL -> listOf(
            MacroSpec("TUNE", 0.4f),
            MacroSpec("DECAY", 0.5f),
            MacroSpec("FORCE", 0.5f),
            MacroSpec("POS", 0.25f),
            MacroSpec("CLACK", 0f),
        )
        TerraVoice.TUNED_BAR -> listOf(
            MacroSpec("TUNE", 0.35f),
            MacroSpec("DECAY", 0.5f),
            MacroSpec("FORCE", 0.5f),
            MacroSpec("POS", 0.25f),
            MacroSpec("BUZZ", 0f),
        )
    }

    fun defaults(voice: TerraVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    fun render(voice: TerraVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val renderRate = RATE * Dsp.OVERSAMPLE
        val (raw, clackFrames) = when (voice) {
            TerraVoice.COMPOUND_MEMBRANE -> compoundMembrane(m, renderRate) to 0
            TerraVoice.RESONANT_CAVITY -> resonantCavity(m, renderRate) to 0
            TerraVoice.CONICAL_BELL -> conicalBell(m, renderRate)
            TerraVoice.TUNED_BAR -> tunedBar(m, renderRate) to 0
        }
        Dsp.normalize(raw)
        val out = if (clackFrames > 0) {
            val preroll = Dsp.decimate(raw.copyOfRange(0, clackFrames), RATE)
            val body = Punch.applyOversampled(raw.copyOfRange(clackFrames, raw.size), TERRA_PUNCH_AMOUNT, RATE)
            preroll + body
        } else {
            Punch.applyOversampled(raw, TERRA_PUNCH_AMOUNT, RATE)
        }
        Dsp.limitPeak(out)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    private val MEMBRANE_RATIOS = floatArrayOf(1.00f, 1.99f, 2.98f, 3.99f, 4.88f, 5.92f)
    private val MEMBRANE_GAINS = floatArrayOf(1.00f, 0.65f, 0.45f, 0.25f, 0.12f, 0.08f)
    private const val MEMBRANE_GAMMA_STEP = 0.65f

    private val CAVITY_RATIOS = floatArrayOf(1.00f, 2.14f, 3.20f, 4.45f)
    private val CAVITY_GAINS = floatArrayOf(1.00f, 0.35f, 0.15f, 0.05f)
    private const val CAVITY_GAMMA_STEP = 1.2f

    private val BELL_RATIOS = floatArrayOf(1.00f, 1.48f, 2.14f, 2.87f, 3.42f, 4.15f)
    private val BELL_GAINS = floatArrayOf(1.00f, 0.72f, 0.45f, 0.30f, 0.18f, 0.09f)
    private const val BELL_GAMMA_QUADRATIC = 0.85f

    private val BAR_RATIOS = floatArrayOf(1.00f, 6.27f, 17.55f, 34.39f)
    private val BAR_GAINS = floatArrayOf(1.00f, 0.25f, 0.08f, 0.02f)
    private const val BAR_GAMMA_STEP = 1.8f

    private const val CAVITY_FREQ_HZ = 75f
    private const val CAVITY_Q = 8f
    private const val CAVITY_DRIVE = 1.15f

    private const val BUZZ_THRESHOLD = 0.12f
    private const val BUZZ_GAIN = 0.45f

    private const val FLESH_NOISE_GAIN = 0.40f

    private const val HARD_STICK_SECONDS = 0.0018f

    private const val HARD_STICK_NOISE_WEIGHT = 0.55f

    private const val TERRA_PUNCH_AMOUNT = 1.0f

    private const val CLACK_MAX_SECONDS = 0.03f

    private const val CLACK_GAIN = 0.8f

    private const val TWO_PI = (2.0 * Math.PI).toFloat()

    private const val T60_NEPERS = 6.9078f

    private const val DROOP_TAU_SECONDS = 0.020f

    private const val DECAY_MIN_SECONDS = 0.08f
    private const val DECAY_DEFAULT_SECONDS = 0.35f
    private const val DECAY_MAX_SECONDS = 0.9f
    private fun t60BaseFor(m: Map<String, Float>): Float =
        Dsp.around(m.getValue("DECAY"), DECAY_MIN_SECONDS, DECAY_DEFAULT_SECONDS, DECAY_MAX_SECONDS)

    private fun framesFor(t60Base: Float, rate: Int): Int =
        (t60Base * 1.4f * rate).toInt().coerceAtLeast(64)

    private fun fleshPalmExciter(hardness: Float, rate: Int, seed: Int): (Int) -> Float {
        val pulseLen = (rate * (0.003f + (1f - hardness) * 0.009f)).toInt().coerceAtLeast(1)
        val noise = Dsp.Noise(seed)
        return { i ->
            if (i < pulseLen) {
                val pulse = 0.5f * (1f - cos(TWO_PI * i / pulseLen))
                pulse * (1f - FLESH_NOISE_GAIN) + noise.next() * pulse * FLESH_NOISE_GAIN * hardness
            } else {
                0f
            }
        }
    }

    private fun hardStickExciter(hardness: Float, rate: Int, seed: Int): (Int) -> Float {
        val stickLen = (rate * HARD_STICK_SECONDS).toInt().coerceAtLeast(1)
        val noise = Dsp.Noise(seed)
        return { i ->
            if (i < stickLen) {
                val pulse = 0.5f * (1f - cos(TWO_PI * i / stickLen))
                pulse * (1f - HARD_STICK_NOISE_WEIGHT) + noise.next() * pulse * HARD_STICK_NOISE_WEIGHT * hardness
            } else {
                0f
            }
        }
    }

    private fun withPreStrikeClack(clackSamples: Int, seed: Int, mainExciter: (Int) -> Float): (Int) -> Float {
        if (clackSamples <= 0) return mainExciter
        val noise = Dsp.Noise(seed)
        return { i ->
            if (i < clackSamples) {
                val env = 1f - i.toFloat() / clackSamples
                noise.next() * env * CLACK_GAIN
            } else {
                mainExciter(i - clackSamples)
            }
        }
    }

    private fun strikeAndModalBank(
        modes: List<Modes.Mode>,
        fundamentalHz: Float,
        droopDepth: Float,
        frames: Int,
        rate: Int,
        exciterAt: (Int) -> Float,
        onsetSamples: Int = 0,
    ): FloatArray {
        val out = FloatArray(frames)
        val phases = FloatArray(modes.size)
        val nyquist = rate / 2f

        for (i in out.indices) {
            val exciter = exciterAt(i)
            var modalSum = 0f
            if (i >= onsetSamples) {
                val t = (i - onsetSamples).toFloat() / rate

                val currentF0 = fundamentalHz * (1f + droopDepth * exp(-t / DROOP_TAU_SECONDS))

                for (k in modes.indices) {
                    val mode = modes[k]
                    val hz = currentF0 * mode.ratio
                    if (hz <= 0f || hz >= nyquist || mode.t60 <= 0f || mode.gain == 0f) continue
                    phases[k] += TWO_PI * hz / rate
                    if (phases[k] >= TWO_PI) phases[k] -= TWO_PI
                    val decay = exp(-T60_NEPERS * t / mode.t60)
                    modalSum += sin(phases[k]) * mode.gain * decay
                }
            }
            out[i] = 0.35f * exciter + 0.65f * modalSum
        }
        return out
    }

    private fun applyBuzz(raw: FloatArray, amount: Float, seed: Int): FloatArray {
        if (amount <= 0.001f) return raw
        val noise = Dsp.Noise(seed)
        val out = raw.copyOf()
        for (i in out.indices) {
            val absS = abs(out[i])
            if (absS > BUZZ_THRESHOLD) {
                out[i] += (absS - BUZZ_THRESHOLD) * noise.next() * amount * BUZZ_GAIN
            }
        }
        return out
    }

    private fun compoundMembrane(m: Map<String, Float>, rate: Int): FloatArray {
        val fundamentalHz = Dsp.expMap(m.getValue("TUNE"), 55f, 440f)
        val t60Base = t60BaseFor(m)
        val hardness = m.getValue("FORCE")
        val position = Dsp.lin(m.getValue("POS"), 0.5f, 0.98f)
        val droopDepth = Dsp.lin(m.getValue("DROOP"), 0f, 0.65f)

        val baseModes = MEMBRANE_RATIOS.indices.map { i ->
            val gamma = 1f + i * MEMBRANE_GAMMA_STEP
            Modes.Mode(ratio = MEMBRANE_RATIOS[i], gain = MEMBRANE_GAINS[i], t60 = t60Base / gamma)
        }
        val modes = Modes.atPosition(baseModes, position)
        val frames = framesFor(t60Base, rate)
        return strikeAndModalBank(modes, fundamentalHz, droopDepth, frames, rate, fleshPalmExciter(hardness, rate, seed = 11))
    }

    private fun resonantCavity(m: Map<String, Float>, rate: Int): FloatArray {
        val fundamentalHz = Dsp.expMap(m.getValue("TUNE"), 45f, 300f)
        val t60Base = t60BaseFor(m)
        val hardness = m.getValue("FORCE")
        val position = Dsp.lin(m.getValue("POS"), 0.5f, 0.98f)
        val droopDepth = Dsp.lin(m.getValue("DROOP"), 0f, 0.65f)
        val cavityMix = m.getValue("CAVITY")
        val buzzAmount = m.getValue("BUZZ")

        val baseModes = CAVITY_RATIOS.indices.map { i ->
            val gamma = 1f + i * CAVITY_GAMMA_STEP
            Modes.Mode(ratio = CAVITY_RATIOS[i], gain = CAVITY_GAINS[i], t60 = t60Base / gamma)
        }
        val modes = Modes.atPosition(baseModes, position)
        val frames = framesFor(t60Base, rate)
        val raw = strikeAndModalBank(modes, fundamentalHz, droopDepth, frames, rate, fleshPalmExciter(hardness, rate, seed = 31))

        val cavity = Dsp.Biquad().apply { bandpass(CAVITY_FREQ_HZ, CAVITY_Q, rate) }
        val out = raw.copyOf()
        if (cavityMix > 0.001f) {
            for (i in out.indices) {
                val cavitySat = tanh(cavity.process(out[i]) * CAVITY_DRIVE)
                out[i] = out[i] * (1f - cavityMix) + cavitySat * cavityMix
            }
        }
        return applyBuzz(out, buzzAmount, seed = 13)
    }

    private fun conicalBell(m: Map<String, Float>, rate: Int): Pair<FloatArray, Int> {
        val fundamentalHz = Dsp.expMap(m.getValue("TUNE"), 500f, 950f)
        val t60Base = t60BaseFor(m)
        val hardness = m.getValue("FORCE")
        val position = Dsp.lin(m.getValue("POS"), 0.5f, 0.98f)
        val clackSamples = (m.getValue("CLACK") * CLACK_MAX_SECONDS * rate).toInt()

        val baseModes = BELL_RATIOS.indices.map { i ->
            val gamma = 1f + BELL_GAMMA_QUADRATIC * i * i
            Modes.Mode(ratio = BELL_RATIOS[i], gain = BELL_GAINS[i], t60 = t60Base / gamma)
        }
        val modes = Modes.atPosition(baseModes, position)
        val frames = framesFor(t60Base, rate) + clackSamples
        val exciter = withPreStrikeClack(clackSamples, seed = 29, hardStickExciter(hardness, rate, seed = 17))
        val raw = strikeAndModalBank(modes, fundamentalHz, droopDepth = 0f, frames, rate, exciter, onsetSamples = clackSamples)
        return raw to clackSamples
    }

    private fun tunedBar(m: Map<String, Float>, rate: Int): FloatArray {
        val fundamentalHz = Dsp.expMap(m.getValue("TUNE"), 180f, 400f)
        val t60Base = t60BaseFor(m)
        val hardness = m.getValue("FORCE")
        val position = Dsp.lin(m.getValue("POS"), 0.5f, 0.98f)
        val buzzAmount = m.getValue("BUZZ")

        val baseModes = BAR_RATIOS.indices.map { i ->
            val gamma = 1f + i * BAR_GAMMA_STEP
            Modes.Mode(ratio = BAR_RATIOS[i], gain = BAR_GAINS[i], t60 = t60Base / gamma)
        }
        val modes = Modes.atPosition(baseModes, position)
        val frames = framesFor(t60Base, rate)
        val raw = strikeAndModalBank(modes, fundamentalHz, droopDepth = 0f, frames, rate, hardStickExciter(hardness, rate, seed = 19))
        return applyBuzz(raw, buzzAmount, seed = 23)
    }
}
```

- [ ] **Step 2: Check the copy against the source mechanically**

Run:
```bash
diff <(sed -n '47,592p' synth/src/main/kotlin/com/snipsnap/synth/Terra.kt | grep -vE '^[[:space:]]*(//|/\*|\*)' | grep -v '^[[:space:]]*$') \
     <(sed -n '22,297p' synth/src/test/kotlin/com/snipsnap/synth/LegacyTerraBank.kt | grep -v '^[[:space:]]*$') && echo SAME
```
Expected: `SAME` and no diff lines. A diff line is a transcription slip in Step 1; fix the copy, never the source.

- [ ] **Step 3: Write the 40-case grid**

Create `synth/src/test/kotlin/com/snipsnap/synth/TerraCases.kt`. The pads are copied from `TerraKits.kt:52-83` with each macro map in the same order. The guard does not decode them through `PadRecipe` → `TerraPatch`, because Task 5 rewrites that decoder.

```kotlin
package com.snipsnap.synth

/**
 * Phase 0's grid for TERRA's frozen guard
 * (docs/superpowers/plans/2026-09-30-chimera-phase-0-record.md, Appendix A,
 * G-P0a): the four voices at defaults, five macro probes on each voice
 * (twenty cases), and the sixteen Terra Kit pads - the three BUZZ pads A04,
 * A07 and A16 and the CLACK pad A15 among them. Forty cases.
 *
 * The pads are written out here, copied from TerraKits.kt in the same macro
 * order, rather than read back through PadRecipe and TerraPatch: the HIT
 * work rewrites that decoder, and a guard must not lean on what it guards.
 */
internal object TerraCases {

    class Case(val label: String, val voice: TerraVoice, val macros: Map<String, Float>)

    class KitPad(val slot: String, val name: String, val voice: TerraVoice, val macros: Map<String, Float>)

    val PROBES: List<Map<String, Float>> = listOf(
        mapOf("TUNE" to 0.30f, "DECAY" to 0.55f),
        mapOf("TUNE" to 0.65f, "DECAY" to 0.30f),
        mapOf("TUNE" to 0.45f, "DECAY" to 0.55f),
        mapOf("TUNE" to 0.40f, "DECAY" to 0.60f),
        mapOf("TUNE" to 0.8f, "DECAY" to 0.1f, "POS" to 0.9f),
    )

    val KIT: List<KitPad> = listOf(
        KitPad("A01", "Udu Whoomp", TerraVoice.RESONANT_CAVITY, mapOf("TUNE" to 0.1058f, "FORCE" to 0.10f, "DROOP" to 0.0769f, "CAVITY" to 0.90f)),
        KitPad("A02", "Bayan Drag", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.0803f, "FORCE" to 0.25f, "DROOP" to 0.8462f)),
        KitPad("A03", "Djembe Bass", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.1362f, "FORCE" to 0.30f, "POS" to 0.05f, "DROOP" to 0.1846f)),
        KitPad("A04", "Cajon Low", TerraVoice.RESONANT_CAVITY, mapOf("TUNE" to 0.1516f, "FORCE" to 0.20f, "CAVITY" to 0.70f, "BUZZ" to 0.15f)),
        KitPad("A05", "Dholak Bass", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.2779f, "FORCE" to 0.35f, "DROOP" to 0.5385f)),
        KitPad("A06", "Dumbek Doum", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.3333f, "FORCE" to 0.40f, "DROOP" to 0.2769f)),
        KitPad("A07", "Cajon Slap", TerraVoice.TUNED_BAR, mapOf("TUNE" to 0.2514f, "FORCE" to 0.85f, "BUZZ" to 0.75f)),
        KitPad("A08", "Djembe Open", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.7185f, "FORCE" to 0.50f, "POS" to 0.5f, "DROOP" to 0.0769f)),
        KitPad("A09", "Tabla Tin", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.8057f, "FORCE" to 0.80f, "POS" to 0.95f, "DROOP" to 0.0308f)),
        KitPad("A10", "Tabla Tun", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.8057f, "FORCE" to 0.45f, "POS" to 0.05f)),
        KitPad("A11", "Dumbek Tek", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 1.0f, "FORCE" to 0.92f, "POS" to 0.95f)),
        KitPad("A12", "Djembe Slap", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.9167f, "FORCE" to 0.95f, "POS" to 0.9f, "DROOP" to 0.3077f, "DECAY" to 0f)),
        KitPad("A13", "Agogo Low", TerraVoice.CONICAL_BELL, mapOf("TUNE" to 0.2508f, "FORCE" to 0.85f)),
        KitPad("A14", "Agogo High", TerraVoice.CONICAL_BELL, mapOf("TUNE" to 0.8807f, "FORCE" to 0.90f)),
        KitPad("A15", "Agogo Clack", TerraVoice.CONICAL_BELL, mapOf("TUNE" to 0.6108f, "FORCE" to 0.95f, "CLACK" to 0.6667f)),
        KitPad("A16", "Balafon Key", TerraVoice.TUNED_BAR, mapOf("TUNE" to 0.7573f, "FORCE" to 0.70f, "BUZZ" to 0.60f)),
    )

    val all: List<Case> =
        TerraVoice.entries.map { Case("default ${it.name}", it, emptyMap()) } +
            TerraVoice.entries.flatMap { v -> PROBES.mapIndexed { i, m -> Case("probe ${i + 1} ${v.name}", v, m) } } +
            KIT.map { Case("pad ${it.slot} ${it.name}", it.voice, it.macros) }
}
```

- [ ] **Step 4: Write the guard**

Create `synth/src/test/kotlin/com/snipsnap/synth/TerraFrozenTest.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * TERRA with no driver renders exactly as it did before HIT, BEND and TALK
 * (docs/superpowers/specs/2026-09-30-terra-hit-bend-talk-design.md,
 * "Testing", test 1). [LegacyTerraBank] is the frozen copy and [TerraCases]
 * Phase 0's 40-case grid. Landed before any TERRA change and passing
 * against the unchanged source, which is the proof it guards something.
 */
class TerraFrozenTest {

    @Test
    fun `the frozen copy's macro table is today's`() {
        for (voice in TerraVoice.entries) assertEquals(LegacyTerraBank.macrosFor(voice), Terra.macrosFor(voice), voice.name)
    }

    @Test
    fun `Terra render is the frozen TERRA, sample for sample, on the 40 cases`() {
        var cases = 0
        for (c in TerraCases.all) {
            assertContentEquals(LegacyTerraBank.render(c.voice, c.macros).samples, Terra.render(c.voice, c.macros).samples, c.label)
            cases++
        }
        assertEquals(40, cases)
    }

    /** The audio the kit ships with, through whatever path TerraPatch.render takes - the routing Task 5 rewrites. */
    @Test
    fun `the shipped Terra Kit is the frozen TERRA, pad for pad`() {
        val kit = TerraKits.classic()
        assertEquals(TerraCases.KIT.size, kit.size)
        for ((i, pad) in TerraCases.KIT.withIndex()) {
            val shipped = requireNotNull(kit[i]) { "pad ${pad.slot} is empty" }
            assertContentEquals(LegacyTerraBank.render(pad.voice, pad.macros).samples, shipped.snip.samples, "pad ${pad.slot} ${pad.name}")
        }
    }

    /**
     * A plain recipe keeps today's bytes, so every saved TERRA pad and the
     * Terra Kit stay byte-stable (spec, "Data flow and compatibility"; test
     * 1, "pinned as text"). Three literal pins: a synthetic patch, the BUZZ
     * pad A07 and the CLACK pad A15. The numbers are the floats widened to
     * double and spelled by Double.toString, as Json.write spells them;
     * JDK 17 and a shortest round-trip spelling agree on all six. The other
     * pads are held to [todaysText], which rebuilds today's JSON without
     * the encoder under test.
     */
    @Test
    fun `a plain TERRA patch writes today's bytes`() {
        val pinned = """
            {
              "engine": "TERRA",
              "version": 1,
              "name": "Pin",
              "voice": "COMPOUND_MEMBRANE",
              "macros": {
                "TUNE": 0.5,
                "DROOP": 0.25
              }
            }
        """.trimIndent()
        assertEquals(pinned, TerraPatch("Pin", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.5f, "DROOP" to 0.25f)).toJsonText())
        val a07 = """
            {
              "engine": "TERRA",
              "version": 1,
              "name": "Cajon Slap",
              "voice": "TUNED_BAR",
              "macros": {
                "TUNE": 0.2513999938964844,
                "FORCE": 0.8500000238418579,
                "BUZZ": 0.75
              }
            }
        """.trimIndent()
        val a15 = """
            {
              "engine": "TERRA",
              "version": 1,
              "name": "Agogo Clack",
              "voice": "CONICAL_BELL",
              "macros": {
                "TUNE": 0.61080002784729,
                "FORCE": 0.949999988079071,
                "CLACK": 0.666700005531311
              }
            }
        """.trimIndent()
        val kit = TerraKits.classic()
        assertEquals(a07, Json.write(requireNotNull(kit[6]?.recipe?.entries?.get("patch"))), "A07's stored recipe")
        assertEquals(a15, Json.write(requireNotNull(kit[14]?.recipe?.entries?.get("patch"))), "A15's stored recipe")
        assertEquals(a07, TerraPatch("Cajon Slap", TerraVoice.TUNED_BAR, mapOf("TUNE" to 0.2514f, "FORCE" to 0.85f, "BUZZ" to 0.75f)).toJsonText())
        assertEquals(a15, TerraPatch("Agogo Clack", TerraVoice.CONICAL_BELL, mapOf("TUNE" to 0.6108f, "FORCE" to 0.95f, "CLACK" to 0.6667f)).toJsonText())
        for ((i, pad) in TerraCases.KIT.withIndex()) {
            val today = todaysText(pad.name, pad.voice, pad.macros)
            assertEquals(today, TerraPatch(pad.name, pad.voice, pad.macros).toJsonText(), "pad ${pad.slot} ${pad.name}")
            val stored = requireNotNull(kit[i]?.recipe?.entries?.get("patch")) { "pad ${pad.slot} has no patch in its recipe" }
            assertEquals(today, Json.write(stored), "pad ${pad.slot}'s stored recipe")
        }
    }

    /** A plain TERRA patch's JSON as `Patches.toJsonValue` wrote it before HIT: engine, version 1, name, voice, then the macros in their own order. */
    private fun todaysText(name: String, voice: TerraVoice, macros: Map<String, Float>): String = Json.write(
        JsonValue.Obj(
            linkedMapOf<String, JsonValue>(
                "engine" to JsonValue.Str("TERRA"),
                "version" to JsonValue.Num(1.0),
                "name" to JsonValue.Str(name),
                "voice" to JsonValue.Str(voice.name),
                "macros" to JsonValue.Obj(macros.entries.associateTo(LinkedHashMap()) { (k, v) -> k to JsonValue.Num(v.toDouble()) }),
            ),
        ),
    )
}
```

- [ ] **Step 5: Run the guard against the unchanged source**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraFrozenTest"`
Expected: exit code 0, 4 tests pass. A guard must pass before the change it guards.

- [ ] **Step 6: Prove the guard is real**

Temporarily change `Terra.kt:268` from `private const val DROOP_TAU_SECONDS = 0.020f` to `private const val DROOP_TAU_SECONDS = 0.021f`.

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraFrozenTest"`
Expected: FAIL. `Terra render is the frozen TERRA…` fails on the first membrane case (`default COMPOUND_MEMBRANE`), and the kit test fails on a drooping pad.

Revert the line to `0.020f`, then run the same command again.
Expected: exit code 0. Then run `git diff synth/src/main` and confirm it is empty.

- [ ] **Step 7: Add TERRA to the determinism canaries**

In `synth/src/test/kotlin/com/snipsnap/synth/DeterminismTest.kt`, insert before the final closing brace (line 138), after the BORE test:

```kotlin

    // TERRA seeds its exciters (11, 31, 17, 19), CLACK's noise (29) and
    // BUZZ's noise (13, 23) per voice (Terra.kt), so a saved pad
    // regenerates only if all of them stay fixed. One per voice, with BUZZ
    // and CLACK up so the noise paths run.
    @Test
    fun `TERRA is byte-identical across renders, all four voices`() {
        for (voice in TerraVoice.entries) {
            val loud = when (voice) {
                TerraVoice.RESONANT_CAVITY, TerraVoice.TUNED_BAR -> mapOf("BUZZ" to 1f)
                TerraVoice.CONICAL_BELL -> mapOf("CLACK" to 1f)
                TerraVoice.COMPOUND_MEMBRANE -> emptyMap()
            }
            val patch = TerraPatch("Canary", voice, Terra.defaults(voice) + loud)
            assertContentEquals(patch.render().samples, patch.render().samples, voice.name)
        }
    }
```

- [ ] **Step 8: Add TERRA to the recipe door and the factory-kit check**

In `synth/src/test/kotlin/com/snipsnap/synth/PadRecipeTest.kt`, after line 35 (`BorePatch("Flute Test", BoreVoice.FLUTE, mapOf("BREATH" to 0.4f)),`) add:

```kotlin
        TerraPatch("Djembe Test", TerraVoice.COMPOUND_MEMBRANE, mapOf("POS" to 0.4f)),
```

and change lines 110-115 from

```kotlin
        for ((name, kit) in listOf(
            "classic" to ThumpKits.classic(),
            "melodic" to SynthKits.melodic(),
            "chip" to SynthKits.chip(),
            "tide" to SynthKits.tide(),
        )) {
```

to

```kotlin
        for ((name, kit) in listOf(
            "classic" to ThumpKits.classic(),
            "melodic" to SynthKits.melodic(),
            "chip" to SynthKits.chip(),
            "tide" to SynthKits.tide(),
            "terra" to TerraKits.classic(),
        )) {
```

- [ ] **Step 9: Run the three suites**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraFrozenTest" --tests "com.snipsnap.synth.DeterminismTest" --tests "com.snipsnap.synth.PadRecipeTest"`
Expected: exit code 0.

- [ ] **Step 10: Commit**

```bash
git add synth/src/test/kotlin/com/snipsnap/synth/LegacyTerraBank.kt synth/src/test/kotlin/com/snipsnap/synth/TerraCases.kt synth/src/test/kotlin/com/snipsnap/synth/TerraFrozenTest.kt synth/src/test/kotlin/com/snipsnap/synth/DeterminismTest.kt synth/src/test/kotlin/com/snipsnap/synth/PadRecipeTest.kt
git commit -m "Freeze TERRA's render and recipe bytes before the HIT work touches them

A verbatim copy of today's engine in test sources, held to the live one on
Phase 0's forty cases (four defaults, twenty probes, the sixteen kit pads),
plus the shipped kit's audio and recipe bytes. Shown to fail when the droop
constant moves. TERRA also joins the determinism canaries and the recipe
door."
```

---

### Task 2: The bank's two optional inputs, and the null path that is today

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Terra.kt`:
  - `:109-112` and `:120-125`: `render` becomes `renderWith`'s null case.
  - After `:160`: `bankWith` and `settled`.
  - After `:268`: `PITCH_MIN`, `PITCH_MAX`.
  - `:352-376`: the bank's KDoc, signature and branch.
  - After `:408`: `drivenBank`.
  - `:430`, `:458`, `:461`, `:493`, `:514`, `:555`, `:559`, `:590`: the voices pass the inputs through.
- Test: `synth/src/test/kotlin/com/snipsnap/synth/TerraFrozenTest.kt` (add three tests)
- Test: `synth/src/test/kotlin/com/snipsnap/synth/TerraTest.kt:3-9` (imports), `:353` (append test 7 before the closing brace)

**Interfaces:**
- Consumes: `Modes.Mode`, the private voice functions and exciters of `Terra.kt`, `TerraCases.all` and `LegacyTerraBank.render` from Task 1.
- Produces, all in `object Terra`:
  - `internal class Body(val modes: List<Modes.Mode>, val fundamentalHz: Float, val droopDepth: Float, val frames: Int, val rate: Int, val onsetSamples: Int)`
  - `internal class BankInputs(val level: Array<FloatArray>? = null, val pitch: FloatArray? = null)`
  - `internal const val PITCH_MIN = 0.25f`, `internal const val PITCH_MAX = 4f`
  - `internal fun renderWith(voice: TerraVoice, macros: Map<String, Float>, inputsFor: ((Body) -> BankInputs?)?): Snip`
  - `internal fun bankWith(voice: TerraVoice, macros: Map<String, Float>, inputsFor: ((Body) -> BankInputs?)?): FloatArray`
  - `internal fun strikeAndModalBank(modes: List<Modes.Mode>, fundamentalHz: Float, droopDepth: Float, frames: Int, rate: Int, exciterAt: (Int) -> Float, onsetSamples: Int = 0, level: Array<FloatArray>? = null, pitch: FloatArray? = null): FloatArray`
  - Task 4 builds HIT on `renderWith`, `bankWith` and `strikeAndModalBank`.

- [ ] **Step 1: Write the failing tests**

In `synth/src/test/kotlin/com/snipsnap/synth/TerraFrozenTest.kt`, replace the import block (lines 3-7) with:

```kotlin
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
```

and add these tests inside the class, after `a plain TERRA patch writes today's bytes`:

```kotlin
    // ---------- the bank's two inputs at identity ----------

    @Test
    fun `identity curves alone and together render the frozen TERRA`() {
        val ones: (Terra.Body) -> Terra.BankInputs = { b -> Terra.BankInputs(level = Array(b.modes.size) { floatArrayOf(1f) }) }
        val unity: (Terra.Body) -> Terra.BankInputs = { Terra.BankInputs(pitch = floatArrayOf(1f)) }
        val both: (Terra.Body) -> Terra.BankInputs = { b -> Terra.BankInputs(level = Array(b.modes.size) { floatArrayOf(1f) }, pitch = floatArrayOf(1f)) }
        var renders = 0
        for (c in TerraCases.all) {
            val frozen = LegacyTerraBank.render(c.voice, c.macros).samples
            for ((label, inputs) in listOf("level" to ones, "pitch" to unity, "level and pitch" to both)) {
                assertContentEquals(frozen, Terra.renderWith(c.voice, c.macros, inputs).samples, "${c.label}, identity $label")
                renders++
            }
            assertContentEquals(frozen, Terra.renderWith(c.voice, c.macros) { null }.samples, "${c.label}, inputs that decline")
            renders++
        }
        assertEquals(160, renders)
    }

    /**
     * Every finite pitch value is clamped before the skip guard, so a
     * downward curve cannot reach `hz <= 0` (Phase-0 record, §7 item 8: 0,
     * -3, 1e9 and 1e-9 all render finite once clamped). NaN passes
     * coerceIn, which is why stored curves are finite-checked where they are
     * stored, never here.
     */
    @Test
    fun `a pitch curve is clamped to a quarter and four times before the skip guard`() {
        val v = TerraVoice.COMPOUND_MEMBRANE
        fun at(p: Float) = Terra.renderWith(v, emptyMap()) { Terra.BankInputs(pitch = floatArrayOf(p)) }.samples
        val floor = at(Terra.PITCH_MIN)
        val ceiling = at(Terra.PITCH_MAX)
        assertContentEquals(floor, at(0f), "0")
        assertContentEquals(floor, at(-3f), "-3")
        assertContentEquals(floor, at(1e-9f), "1e-9")
        assertContentEquals(ceiling, at(1e9f), "1e9")
        val plain = Terra.render(v)
        for (x in listOf(floor, ceiling)) {
            assertEquals(plain.samples.size, x.size)
            assertTrue(x.all { it.isFinite() && abs(it) <= 1f }, "a clamped pitch rendered a sample out of range")
        }
    }

    @Test
    fun `a curve shorter than the render holds its last value, and every mode needs its own curve`() {
        val v = TerraVoice.CONICAL_BELL
        val m = mapOf("CLACK" to 1f)
        val held = Terra.renderWith(v, m) { b -> Terra.BankInputs(level = Array(b.modes.size) { FloatArray(10) { 0.5f } }, pitch = FloatArray(7) { 1.5f }) }
        val single = Terra.renderWith(v, m) { b -> Terra.BankInputs(level = Array(b.modes.size) { floatArrayOf(0.5f) }, pitch = floatArrayOf(1.5f)) }
        assertContentEquals(single.samples, held.samples)
        assertFailsWith<IllegalArgumentException> {
            Terra.renderWith(v, emptyMap()) { b -> Terra.BankInputs(level = Array(b.modes.size - 1) { floatArrayOf(1f) }) }
        }
        assertFailsWith<IllegalArgumentException> {
            Terra.renderWith(v, emptyMap()) { Terra.BankInputs(pitch = FloatArray(0)) }
        }
    }

    /**
     * The curves start at the strike, not at frame 0, so a CLACKed bell's
     * pre-roll lands first and the curve shapes the body (spec,
     * "Architecture", the clock). A curve indexed from frame 0 would spend
     * its silent stretch on the pre-roll, where the bank is already silent,
     * and this would see no difference at all.
     */
    @Test
    fun `curves start at the strike, so CLACK's pre-roll lands first`() {
        val v = TerraVoice.CONICAL_BELL
        val m = mapOf("CLACK" to 1f)
        var onset = -1
        val plain = Terra.bankWith(v, m) { b -> onset = b.onsetSamples; null }
        assertTrue(onset > 100, "CLACK 1 should give a pre-roll: onset $onset")
        val gated = Terra.bankWith(v, m) { b -> Terra.BankInputs(level = Array(b.modes.size) { FloatArray(101) { n -> if (n < 100) 0f else 1f } }) }
        assertContentEquals(plain.copyOfRange(0, onset), gated.copyOfRange(0, onset), "the curve reached into the pre-roll")
        assertTrue((onset until onset + 100).any { gated[it] != plain[it] }, "the curve's silent stretch did not land on the strike")
        assertContentEquals(plain.copyOfRange(onset + 100, plain.size), gated.copyOfRange(onset + 100, gated.size), "after the stretch the modes did not come back in phase")
    }
```

In `synth/src/test/kotlin/com/snipsnap/synth/TerraTest.kt`, add `import kotlin.test.assertContentEquals` after `import kotlin.test.Test` (line 7), and insert before the class's closing brace (line 353):

```kotlin

    // ---------- the bank's level input (spec "Testing", test 7) ----------

    /**
     * A mode held at exactly 0 must keep its phase running, so it reopens
     * where it would have been. The wrong guard (skipping on gain x level,
     * before the phase accumulates) shows as a phase error after reopening,
     * not a click: Phase 0 measured up to 0.33 of a 0.82 bank peak, with a
     * largest first difference of 0.0004 against 0.0003. So this compares
     * waveforms after the window, sample for sample, not a step.
     */
    @Test
    fun `a mode held at zero level reopens in phase`() {
        var captured: Terra.Body? = null
        Terra.bankWith(TerraVoice.COMPOUND_MEMBRANE, emptyMap()) { captured = it; null }
        val body = requireNotNull(captured)
        val a = (0.020f * body.rate).toInt()
        val b = (0.060f * body.rate).toInt()
        val k = 2
        val window = FloatArray(b + 1) { n -> if (n in a until b) 0f else 1f }
        val level = Array(body.modes.size) { m -> if (m == k) window else floatArrayOf(1f) }
        fun bank(modes: List<Modes.Mode>, level: Array<FloatArray>?) =
            Terra.strikeAndModalBank(modes, body.fundamentalHz, body.droopDepth, body.frames, body.rate, { 0f }, body.onsetSamples, level = level)
        val reference = bank(body.modes, null)
        val zeroed = bank(body.modes, level)
        val without = bank(body.modes.filterIndexed { m, _ -> m != k }, null)
        assertContentEquals(reference.copyOfRange(0, a), zeroed.copyOfRange(0, a), "a curve of ones changed the bank before the window")
        assertContentEquals(without.copyOfRange(a, b), zeroed.copyOfRange(a, b), "the zeroed mode still sounded inside the window")
        assertContentEquals(reference.copyOfRange(b, reference.size), zeroed.copyOfRange(b, zeroed.size), "the mode did not reopen in phase")
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraFrozenTest" --tests "com.snipsnap.synth.TerraTest"`
Expected: compilation FAILS with `Unresolved reference 'Body'` and `Unresolved reference 'renderWith'`.

- [ ] **Step 3: Give the bank its two inputs and the driven branch**

All of Task 2's edits are in `synth/src/main/kotlin/com/snipsnap/synth/Terra.kt`, in three steps (3, 4 and 5) that each end in a compile of the main source set. Make the edits in each step bottom-up, and match the quoted text, not only the line number. Each step names the head's line numbers and, where an earlier step has moved them, the numbers after it.

**(a) After line 408** (the bank's closing brace, just after `return out`), insert:

```kotlin

    /**
     * [strikeAndModalBank] with at least one input. The level factor
     * multiplies the table gain before the sine, `sin * (gain * level) *
     * decay`, and the pitch factor multiplies the droop line before f0,
     * `f0 * (droop * pitch)`. Both groupings are Phase 0's `P0Bank`'s
     * (Phase-0 record, Appendix B.1), so an input of exactly 1f changes no
     * bit and a curve through this bank matches the prototype's. (The spec
     * writes the pitch half left to right; `P0Bank` does not, and this
     * follows `P0Bank`.) The skip guard reads the table gain only: a mode
     * whose level is 0 still advances its phase and reopens where it would
     * have been (spec, "Architecture"; TerraTest's `a mode held at zero
     * level reopens in phase`).
     */
    private fun drivenBank(
        modes: List<Modes.Mode>,
        fundamentalHz: Float,
        droopDepth: Float,
        frames: Int,
        rate: Int,
        exciterAt: (Int) -> Float,
        onsetSamples: Int,
        level: Array<FloatArray>?,
        pitch: FloatArray?,
    ): FloatArray {
        if (level != null) require(level.size == modes.size && level.all { it.isNotEmpty() }) { "a level curve per mode (${modes.size}), each at least one value long" }
        if (pitch != null) require(pitch.isNotEmpty()) { "a pitch curve has at least one value" }
        val out = FloatArray(frames)
        val phases = FloatArray(modes.size)
        val nyquist = rate / 2f
        for (i in out.indices) {
            val exciter = exciterAt(i)
            var modalSum = 0f
            if (i >= onsetSamples) {
                val n = i - onsetSamples
                val t = n.toFloat() / rate
                val currentF0 = if (pitch == null) {
                    fundamentalHz * (1f + droopDepth * exp(-t / DROOP_TAU_SECONDS))
                } else {
                    val p = pitch[minOf(n, pitch.size - 1)].coerceIn(PITCH_MIN, PITCH_MAX)
                    fundamentalHz * ((1f + droopDepth * exp(-t / DROOP_TAU_SECONDS)) * p)
                }
                for (k in modes.indices) {
                    val mode = modes[k]
                    val hz = currentF0 * mode.ratio
                    if (hz <= 0f || hz >= nyquist || mode.t60 <= 0f || mode.gain == 0f) continue
                    phases[k] += TWO_PI * hz / rate
                    if (phases[k] >= TWO_PI) phases[k] -= TWO_PI
                    val decay = exp(-T60_NEPERS * t / mode.t60)
                    modalSum += if (level == null) {
                        sin(phases[k]) * mode.gain * decay
                    } else {
                        val curve = level[k]
                        sin(phases[k]) * (mode.gain * curve[minOf(n, curve.size - 1)]) * decay
                    }
                }
            }
            out[i] = 0.35f * exciter + 0.65f * modalSum
        }
        return out
    }
```

**(b) Lines 365-376**, the end of the bank's KDoc through its first body line, from

```kotlin
     * than a bell already partway through decaying.
     */
    private fun strikeAndModalBank(
        modes: List<Modes.Mode>,
        fundamentalHz: Float,
        droopDepth: Float,
        frames: Int,
        rate: Int,
        exciterAt: (Int) -> Float,
        onsetSamples: Int = 0,
    ): FloatArray {
        val out = FloatArray(frames)
```

to

```kotlin
     * than a bell already partway through decaying.
     *
     * [level] (one curve per mode, a factor on `mode.gain`) and [pitch] (a
     * factor on the droop line) are the two optional inputs HIT, BEND and
     * TALK fill, both indexed from [onsetSamples]; see [drivenBank]. With
     * neither, the loop below runs today's two expressions verbatim - the
     * only form that is byte-identical by construction.
     */
    internal fun strikeAndModalBank(
        modes: List<Modes.Mode>,
        fundamentalHz: Float,
        droopDepth: Float,
        frames: Int,
        rate: Int,
        exciterAt: (Int) -> Float,
        onsetSamples: Int = 0,
        level: Array<FloatArray>? = null,
        pitch: FloatArray? = null,
    ): FloatArray {
        if (level != null || pitch != null) return drivenBank(modes, fundamentalHz, droopDepth, frames, rate, exciterAt, onsetSamples, level, pitch)
        val out = FloatArray(frames)
```

**(c) After line 268** (`private const val DROOP_TAU_SECONDS = 0.020f`), insert:

```kotlin

    // The pitch input's clamp, applied before the skip guard: two octaves
    // either way, so a downward curve can never reach hz <= 0 and the top
    // mode at TUNE 1 stays far under the render rate's Nyquist (440 Hz x
    // 5.92 x 4 x 1.65 is about 17 kHz against 88.2 kHz).
    internal const val PITCH_MIN = 0.25f
    internal const val PITCH_MAX = 4f
```

Run: `./gradlew --no-daemon :synth:compileKotlin`
Expected: exit code 0. Nothing calls the new parameters yet, and every existing call keeps its meaning through the defaults. (The test source set does not compile until Step 5; that is expected.)

These three edits move the voices (head lines 430-592) down by 77 lines: 7 from (c), 9 from (b) and 61 from (a).

- [ ] **Step 4: Name what a voice hands the bank, and thread it through the four voices**

**(d) The four voices**, bottom-up. Head line 590 (now 667), from

```kotlin
        val raw = strikeAndModalBank(modes, fundamentalHz, droopDepth = 0f, frames, rate, hardStickExciter(hardness, rate, seed = 19))
```

to

```kotlin
        val inputs = inputsFor?.invoke(Body(modes, fundamentalHz, 0f, frames, rate, onsetSamples = 0))
        val raw = strikeAndModalBank(modes, fundamentalHz, droopDepth = 0f, frames, rate, hardStickExciter(hardness, rate, seed = 19), level = inputs?.level, pitch = inputs?.pitch)
        if (bankOnly) return raw
```

Head line 559 (now 636), from

```kotlin
    private fun tunedBar(m: Map<String, Float>, rate: Int): FloatArray {
```

to

```kotlin
    private fun tunedBar(m: Map<String, Float>, rate: Int, inputsFor: ((Body) -> BankInputs?)? = null, bankOnly: Boolean = false): FloatArray {
```

Head line 555 (now 632), from

```kotlin
        val raw = strikeAndModalBank(modes, fundamentalHz, droopDepth = 0f, frames, rate, exciter, onsetSamples = clackSamples)
```

to

```kotlin
        val inputs = inputsFor?.invoke(Body(modes, fundamentalHz, 0f, frames, rate, onsetSamples = clackSamples))
        val raw = strikeAndModalBank(modes, fundamentalHz, droopDepth = 0f, frames, rate, exciter, onsetSamples = clackSamples, level = inputs?.level, pitch = inputs?.pitch)
```

Head line 514 (now 591), from

```kotlin
    private fun conicalBell(m: Map<String, Float>, rate: Int): Pair<FloatArray, Int> {
```

to

```kotlin
    private fun conicalBell(m: Map<String, Float>, rate: Int, inputsFor: ((Body) -> BankInputs?)? = null): Pair<FloatArray, Int> {
```

Head line 493 (now 570), from

```kotlin
        val raw = strikeAndModalBank(modes, fundamentalHz, droopDepth, frames, rate, fleshPalmExciter(hardness, rate, seed = 31))
```

to

```kotlin
        val inputs = inputsFor?.invoke(Body(modes, fundamentalHz, droopDepth, frames, rate, onsetSamples = 0))
        val raw = strikeAndModalBank(modes, fundamentalHz, droopDepth, frames, rate, fleshPalmExciter(hardness, rate, seed = 31), level = inputs?.level, pitch = inputs?.pitch)
        if (bankOnly) return raw
```

Head line 461 (now 538), from

```kotlin
    private fun resonantCavity(m: Map<String, Float>, rate: Int): FloatArray {
```

to

```kotlin
    private fun resonantCavity(m: Map<String, Float>, rate: Int, inputsFor: ((Body) -> BankInputs?)? = null, bankOnly: Boolean = false): FloatArray {
```

Head line 458 (now 535), from

```kotlin
        return strikeAndModalBank(modes, fundamentalHz, droopDepth, frames, rate, fleshPalmExciter(hardness, rate, seed = 11))
```

to

```kotlin
        val inputs = inputsFor?.invoke(Body(modes, fundamentalHz, droopDepth, frames, rate, onsetSamples = 0))
        return strikeAndModalBank(modes, fundamentalHz, droopDepth, frames, rate, fleshPalmExciter(hardness, rate, seed = 11), level = inputs?.level, pitch = inputs?.pitch)
```

Head line 430 (now 507), from

```kotlin
    private fun compoundMembrane(m: Map<String, Float>, rate: Int): FloatArray {
```

to

```kotlin
    private fun compoundMembrane(m: Map<String, Float>, rate: Int, inputsFor: ((Body) -> BankInputs?)? = null): FloatArray {
```

**(e) Before line 109** (`/** Render [voice] with [macros]; missing macros fall back to defaults. */`, unmoved by Step 3), insert:

```kotlin
    /**
     * What one voice hands the shared bank, after POS ([Modes.atPosition])
     * and before any input: its modes, its note, its droop depth, its buffer
     * length (a CLACK pre-roll included), the render rate and where the
     * strike lands. HIT's colouring is computed from this at render, so a
     * driven pad that is retuned or re-pitched is recoloured from its new
     * modes.
     */
    internal class Body(
        val modes: List<Modes.Mode>,
        val fundamentalHz: Float,
        val droopDepth: Float,
        val frames: Int,
        val rate: Int,
        val onsetSamples: Int,
    )

    /**
     * The bank's two optional inputs, both indexed from the strike and both
     * holding their last value past their end: [level] is one curve per
     * mode, a factor on that mode's table gain; [pitch] is a factor on the
     * droop line, clamped to [PITCH_MIN]..[PITCH_MAX].
     */
    internal class BankInputs(val level: Array<FloatArray>? = null, val pitch: FloatArray? = null)

```

Run: `./gradlew --no-daemon :synth:compileKotlin`
Expected: exit code 0. `render` still calls each voice with two arguments, and the defaults keep today's path.

This step's insert moves everything from line 109 down by 25.

- [ ] **Step 5: Route `render` through `renderWith`, and add `bankWith`**

**(f) Head line 160, now 185** (the closing brace of `render`, just after `return Snip(out, channels = 1, sampleRate = RATE)`), insert after it:

```kotlin

    /**
     * The bank alone for [voice], with the same optional inputs as
     * [renderWith]: before the cavity stage, BUZZ and the output chain, at the
     * render rate. The physics claims read this, as ForkTest reads `Fork.bank`.
     */
    internal fun bankWith(voice: TerraVoice, macros: Map<String, Float>, inputsFor: ((Body) -> BankInputs?)?): FloatArray {
        val m = settled(voice, macros)
        val renderRate = RATE * Dsp.OVERSAMPLE
        return when (voice) {
            TerraVoice.COMPOUND_MEMBRANE -> compoundMembrane(m, renderRate, inputsFor)
            TerraVoice.RESONANT_CAVITY -> resonantCavity(m, renderRate, inputsFor, bankOnly = true)
            TerraVoice.CONICAL_BELL -> conicalBell(m, renderRate, inputsFor).first
            TerraVoice.TUNED_BAR -> tunedBar(m, renderRate, inputsFor, bankOnly = true)
        }
    }

    /** [macros] over [voice]'s defaults, each clamped to 0..1; names the voice does not have are ignored. */
    private fun settled(voice: TerraVoice, macros: Map<String, Float>): Map<String, Float> {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        return m
    }
```

**(g) Head lines 120-125, now 145-150**, from

```kotlin
        val (raw, clackFrames) = when (voice) {
            TerraVoice.COMPOUND_MEMBRANE -> compoundMembrane(m, renderRate) to 0
            TerraVoice.RESONANT_CAVITY -> resonantCavity(m, renderRate) to 0
            TerraVoice.CONICAL_BELL -> conicalBell(m, renderRate)
            TerraVoice.TUNED_BAR -> tunedBar(m, renderRate) to 0
        }
```

to

```kotlin
        val (raw, clackFrames) = when (voice) {
            TerraVoice.COMPOUND_MEMBRANE -> compoundMembrane(m, renderRate, inputsFor) to 0
            TerraVoice.RESONANT_CAVITY -> resonantCavity(m, renderRate, inputsFor) to 0
            TerraVoice.CONICAL_BELL -> conicalBell(m, renderRate, inputsFor)
            TerraVoice.TUNED_BAR -> tunedBar(m, renderRate, inputsFor) to 0
        }
```

**(h) Head lines 109-112, now 134-137**, from

```kotlin
    /** Render [voice] with [macros]; missing macros fall back to defaults. */
    fun render(voice: TerraVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
```

to

```kotlin
    /** Render [voice] with [macros]; missing macros fall back to defaults. */
    fun render(voice: TerraVoice, macros: Map<String, Float> = emptyMap()): Snip = renderWith(voice, macros, inputsFor = null)

    /**
     * [render] with the bank's two optional inputs, built from the voice's own
     * [Body] by [inputsFor]. A null [inputsFor], or one that returns null, is
     * today's render, byte for byte (TerraFrozenTest).
     */
    internal fun renderWith(voice: TerraVoice, macros: Map<String, Float>, inputsFor: ((Body) -> BankInputs?)?): Snip {
        val m = settled(voice, macros)
```

The rest of the old `render` body, from `val renderRate = RATE * Dsp.OVERSAMPLE` to `return Snip(out, channels = 1, sampleRate = RATE)`, is now `renderWith`'s body, unchanged apart from (g).

Run: `./gradlew --no-daemon :synth:compileKotlin`
Expected: exit code 0.

- [ ] **Step 6: Run the guard, the new tests and TERRA's own suite**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraFrozenTest" --tests "com.snipsnap.synth.TerraTest" --tests "com.snipsnap.synth.TerraKitsTest" --tests "com.snipsnap.synth.DeterminismTest" --tests "com.snipsnap.synth.PadRecipeTest"`
Expected: exit code 0.

The 40-case guard from Task 1 is the null path's proof and must pass unchanged. If it fails, the null branch is no longer today's arithmetic. Compare it with `LegacyTerraBank.strikeAndModalBank` token by token, and never touch `LegacyTerraBank.kt`.

- [ ] **Step 7: Prove the zero-level test sees the wrong guard**

Temporarily add `|| (level != null && mode.gain * level[k][minOf(n, level[k].size - 1)] == 0f)` to the end of the `continue` condition in `drivenBank`.

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraTest.a mode held at zero level reopens in phase"`
Expected while the change is in place: FAIL, `the mode did not reopen in phase`. Expected after the revert: PASS.

- [ ] **Step 8: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Terra.kt synth/src/test/kotlin/com/snipsnap/synth/TerraFrozenTest.kt synth/src/test/kotlin/com/snipsnap/synth/TerraTest.kt
git commit -m "TERRA's bank takes a per-mode level curve and a pitch curve, and none renders today

Both curves are indexed from the strike and hold their last value. The skip
guard reads the table gain only, so a muted mode reopens in phase, and the
pitch factor is clamped to a quarter and four times before the guard. With
neither curve, today's two expressions run verbatim: the forty-case frozen
guard passes unchanged, as do identity curves on all forty cases."
```

---

### Task 3: The safe capture rule, `Terra.captureStriker`

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Terra.kt:3-9` (imports) and append before the object's closing brace (after `tunedBar`)
- Create: `synth/src/test/kotlin/com/snipsnap/synth/TerraStrikers.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/TerraCaptureTest.kt` (create)

**Interfaces:**
- Consumes: `Cleanup.toMono(snip: Snip): Snip`, `Resampler.resample(snip: Snip, targetRate: Int): Snip`, `Dsp.normalize(buf, target)`, `Fork.STRIKER_SAMPLES: Int` (882), `Fork.striker(source: Snip): FloatArray` and `Fork.excite(voice, hz, strikeM, striker, frames, rate)` (internal; the tests' noise hammer and equality reference), `Thump.render`, `Vox.render`, `WavReader.read(file: File): Snip`.
- Produces:
  - In `object Terra`: `const val SILENT_HEAD_PEAK = 1e-4f` and `fun captureStriker(source: Snip): FloatArray?`, public, because `:shell` calls it in R4.
  - `internal object TerraStrikers` with `class Source(val id: String, val name: String, val snip: Snip)`, `class Hostile(val label: String, val snip: Snip, val expectsHit: Boolean)`, `fun factoryDir(): File`, `fun factory(file: String, dir: File = factoryDir()): Snip`, `fun ten(dir: File = factoryDir()): List<Source>`, `val TEN: List<Source>`, `fun source(id: String): Snip`, `fun head(id: String): FloatArray` and `fun hostile(): List<Hostile>`.
  - Tasks 4, 5, 7 and 8 use `TerraStrikers`.

- [ ] **Step 1: Write the strikers helper**

Create `synth/src/test/kotlin/com/snipsnap/synth/TerraStrikers.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * The strikers HIT is measured and heard with.
 *
 * [ten] are struck-shape's ten (Phase-0 record §3.1, which names them): six
 * synthesised from engine recipes and four factory samples. The record
 * gives no macro values, and nor does anything else in the tree. The
 * values below are copied from Phase 0's harness, `Phase0.kt:54-63` in the
 * evidence folder the record's Appendix C names (local, not in the tree),
 * and match it value for value. TerraTest's `the ten strikers reproduce
 * Phase 0's overtone spread at subtle - recipe provenance` is the in-tree
 * check that they are still the strikers Phase 0 measured.
 *
 * Phase 0's G-C1 counted thirteen strikers: round two's three plus these
 * ten. Round two's THUMP KICK, THUMP SNARE and WRAITH WORD are the same
 * recipes as three of the ten (`TerraStruckR2.kt:39-44` in the same evidence
 * folder), so the ten
 * distinct sources here cover all thirteen.
 *
 * [hostile] is struck-motion M7.1's list (spec, "Testing", test 3), plus:
 * the 1e-4 boundary read both ways, the decision-20 source, the cancelling
 * stereo pair, and four foreign-rate sources (a 48 kHz kick after 500 ms of
 * silence, 48 kHz silence, a 3-sample 48 kHz source and a 22.05 kHz kick).
 */
internal object TerraStrikers {

    class Source(val id: String, val name: String, val snip: Snip)

    class Hostile(val label: String, val snip: Snip, val expectsHit: Boolean)

    /** The factory samples: from `:synth`'s test working directory, or from the repository root. Fails loudly, never silently skips. */
    fun factoryDir(): File {
        val candidates = listOf(File("../testkit/Expansions/SnipSnap Factory/Samples"), File("testkit/Expansions/SnipSnap Factory/Samples"))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("the factory samples are missing; looked in ${candidates.joinToString { it.absolutePath }}")
    }

    fun factory(file: String, dir: File = factoryDir()): Snip {
        val f = File(dir, file)
        require(f.isFile) { "factory sample missing: ${f.absolutePath}" }
        return WavReader.read(f)
    }

    fun ten(dir: File = factoryDir()): List<Source> = listOf(
        Source("bbkick", "BEATBOX KICK", Vox.render(VoxVoice.BEATBOX, mapOf("HIT" to 0f, "TUNE" to 0.30f, "DECAY" to 0.20f))),
        Source("bbrim", "BEATBOX RIM", Vox.render(VoxVoice.BEATBOX, mapOf("HIT" to 1f, "TUNE" to 0.65f, "DECAY" to 0.20f))),
        Source("tkick", "THUMP KICK", Thump.render(ThumpVoice.KICK, mapOf("TUNE" to 0.45f, "DECAY" to 0.22f, "CLICK" to 0.85f, "DRIVE" to 0.4f))),
        Source("tsnare", "THUMP SNARE", Thump.render(ThumpVoice.SNARE, mapOf("TUNE" to 0.50f, "DECAY" to 0.25f, "SNAP" to 0.80f))),
        Source(
            "wraith", "WRAITH WORD",
            Vox.render(VoxVoice.WRAITH, mapOf("WORD" to 0.2f, "TUNE" to 0.40f, "DECAY" to 0.60f, "TUNED" to 0.5f, "ALIEN" to 0.3f, "BREATH" to 0.2f)),
        ),
        Source("noise", "NOISE HAMMER", Snip(Fork.excite(ForkVoice.TINE, 220f, 0.5f, null, Fork.STRIKER_SAMPLES, Dsp.RATE), 1, Dsp.RATE)),
        Source("kick01", "FACTORY KICK", factory("A01_Kick_01.wav", dir)),
        Source("snare01", "FACTORY SNARE", factory("A02_Snare_01.wav", dir)),
        Source("hat01", "FACTORY HAT", factory("A03_HatClosed_01.wav", dir)),
        Source("clap01", "FACTORY CLAP", factory("A06_Clap_01.wav", dir)),
    )

    val TEN: List<Source> by lazy { ten() }

    fun source(id: String): Snip = TEN.first { it.id == id }.snip

    fun head(id: String): FloatArray = Terra.captureStriker(source(id)) ?: error("striker $id captured as silence")

    /** White noise scaled so its finite peak is exactly [peak]. */
    private fun noise(peak: Float, seconds: Float, seed: Int): FloatArray {
        val r = Random(seed)
        val x = FloatArray((seconds * Dsp.RATE).toInt()) { r.nextFloat() * 2f - 1f }
        var pk = 0f
        for (v in x) pk = maxOf(pk, abs(v))
        for (i in x.indices) x[i] = x[i] / pk * peak
        return x
    }

    private fun mono(x: FloatArray) = Snip(x, 1, Dsp.RATE)

    private fun silence(ms: Int) = FloatArray(ms * Dsp.RATE / 1000)

    private fun spike(at: Int) = FloatArray(6000).also { it[at] = 0.5f }

    private fun square(hz: Float) = FloatArray(Dsp.RATE / 5) { i -> if (sin(2.0 * PI * hz * i / Dsp.RATE) >= 0.0) 1f else -1f }

    /**
     * Decision 20's source: a click under the floor (6e-5), then 50 ms later
     * a hit whose peak (5e-3) is between 1e-4 and 1e-2. The onset is the
     * click, because 6e-5 is above 1 % of 5e-3. The aligned head's peak is
     * the click's, under 1e-4, so the head rule refuses it. The whole-source
     * rule would have captured the click as a hammer.
     */
    private fun quietClickThenHit(): FloatArray {
        val x = FloatArray(6000)
        x[0] = 6e-5f
        for (i in 0 until 2205) x[2205 + i] = (5e-3 * sin(2.0 * PI * 300.0 * i / Dsp.RATE)).toFloat()
        return x
    }

    /**
     * White noise at half of [peak], with one sample of exactly [peak] at
     * [at]. The onset is within the first few samples, so the head covers
     * samples 0 to about 880: a peak at 100 is inside it, one at 5000 is not.
     */
    private fun noisePeakingAt(peak: Float, at: Int, seed: Int): FloatArray =
        noise(peak / 2f, 0.2f, seed).also { it[at] = peak }

    /**
     * The 1e-4 boundary. Spec test 3 says white noise "at 1e-4 and below"
     * falls back. The capture rule it tests says a head peak *below* 1e-4
     * gives no striker (decision 20; Phase 0's `SILENT_PEAK` test is a
     * strict `<` too). The two disagree at exactly 1e-4, and this list
     * follows the rule:
     * - a head whose own peak is exactly 1e-4 is a hit;
     * - noise peaking at 1e-4 only after the head falls back, because the
     *   head's own peak is half that.
     * The disagreement is flagged for the spec.
     */
    fun hostile(): List<Hostile> {
        val kick = source("tkick")
        require(kick.channels == 1) { "THUMP KICK renders mono at WIDTH 0" }
        val k = kick.samples
        val nan = k.copyOf().also { it[10] = Float.NaN }
        val inf = k.copyOf().also { it[5] = Float.POSITIVE_INFINITY }
        val at48 = Resampler.resample(kick, 48_000).samples
        val at22 = Resampler.resample(kick, 22_050).samples
        return listOf(
            Hostile("digital silence", mono(FloatArray(4410)), false),
            Hostile("an empty source", mono(FloatArray(0)), false),
            Hostile("white noise just under 1e-4", mono(noise(0.9e-4f, 0.2f, 1)), false),
            Hostile("white noise peaking at exactly 1e-4 inside the head", mono(noisePeakingAt(1e-4f, 100, 6)), true),
            Hostile("white noise peaking at exactly 1e-4 after the head", mono(noisePeakingAt(1e-4f, 5000, 7)), false),
            Hostile("white noise at 1e-5", mono(noise(1e-5f, 0.2f, 2)), false),
            Hostile("white noise at 1e-3", mono(noise(1e-3f, 0.2f, 3)), true),
            Hostile("white noise at 1e-2", mono(noise(1e-2f, 0.2f, 4)), true),
            Hostile("a kick with a NaN at sample 10", mono(nan), true),
            Hostile("a kick with +Inf at sample 5", mono(inf), true),
            Hostile("a kick after 20 ms of silence", mono(silence(20) + k), true),
            Hostile("a kick after 40 ms of silence", mono(silence(40) + k), true),
            Hostile("a kick after 100 ms of silence", mono(silence(100) + k), true),
            Hostile("a kick after 30 ms of -90 dBFS noise", mono(noise(3.16e-5f, 0.03f, 5) + k), true),
            Hostile("a single sample at 0", mono(spike(0)), true),
            Hostile("a single sample at 881", mono(spike(881)), true),
            Hostile("a single sample at 882", mono(spike(882)), true),
            Hostile("a single sample at 5000", mono(spike(5000)), true),
            Hostile("a DC step", mono(FloatArray(5000) { if (it < 1000) 0f else 0.5f }), true),
            Hostile("a 5 ms DC pulse", mono(FloatArray(5000) { if (it in 1000 until 1000 + 5 * Dsp.RATE / 1000) 0.5f else 0f }), true),
            Hostile("a clipped square at 100 Hz", mono(square(100f)), true),
            Hostile("a clipped square at 1000 Hz", mono(square(1000f)), true),
            Hostile("a clipped square at 4000 Hz", mono(square(4000f)), true),
            Hostile("a quiet click before a louder hit (decision 20)", mono(quietClickThenHit()), false),
            Hostile("a stereo source whose channels cancel", Snip(FloatArray(k.size * 2) { i -> if (i % 2 == 0) k[i / 2] else -k[i / 2] }, 2, Dsp.RATE), false),
            Hostile("a 48 kHz kick after 500 ms of silence", Snip(FloatArray(24_000) + at48 + FloatArray(48_000 * 3), 1, 48_000), true),
            Hostile("48 kHz digital silence", Snip(FloatArray(48_000), 1, 48_000), false),
            Hostile("a 48 kHz source of three samples, one of them 0.5", Snip(floatArrayOf(0f, 0.5f, 0f), 1, 48_000), true),
            Hostile("a 22.05 kHz kick", Snip(at22, 1, 22_050), true),
        )
    }
}
```

- [ ] **Step 2: Write the failing tests**

Create `synth/src/test/kotlin/com/snipsnap/synth/TerraCaptureTest.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HIT's capture rule (spec, "HIT, the design", the capture rule; "Testing",
 * test 3, the capture half). It is FORK's striker wherever FORK's is safe,
 * and safe where FORK's has its five hazards: silence, lead silence, NaN or
 * Inf, a noise lead-in, and a whole foreign-rate file.
 */
class TerraCaptureTest {

    private val rate = Dsp.RATE
    private val kick = TerraStrikers.source("tkick")

    private fun leadZeros(n: Int, s: Snip) = Snip(FloatArray(n) + s.samples, 1, s.sampleRate)

    @Test
    fun `the capture is FORK's striker on the measured strikers`() {
        for (s in TerraStrikers.TEN) {
            val head = assertNotNull(Terra.captureStriker(s.snip), "${s.name} captured as silence")
            assertContentEquals(Fork.striker(s.snip), head, s.name)
        }
        assertEquals(10, TerraStrikers.TEN.size)
    }

    /** By construction (spec): any mono 44.1 kHz source above the floor whose onset is within 44 samples of the start reads from sample 0, as FORK's does. */
    @Test
    fun `the capture is FORK's striker on any mono source whose onset is within 1 ms of the start`() {
        var cases = 0
        for (seed in 0 until 50) {
            val r = Random(seed)
            val lead = r.nextInt(0, 45)
            val peak = 0.01f + 0.99f * r.nextFloat()
            val x = FloatArray(lead + 900 + r.nextInt(5000)) { i -> if (i < lead) 0f else (r.nextFloat() * 2f - 1f) * peak }
            x[lead] = peak
            val s = Snip(x, 1, rate)
            assertContentEquals(Fork.striker(s), assertNotNull(Terra.captureStriker(s)), "seed $seed, onset $lead")
            cases++
        }
        assertEquals(50, cases)
    }

    @Test
    fun `every hostile source captures a real hit or nothing, never a broken head`() {
        for (h in TerraStrikers.hostile()) {
            val head = Terra.captureStriker(h.snip)
            println("TERRA capture: ${h.label} -> ${if (head == null) "no striker (the pad keeps today's body)" else "a hit"}")
            assertEquals(h.expectsHit, head != null, h.label)
            if (head != null) {
                assertEquals(Fork.STRIKER_SAMPLES, head.size, h.label)
                assertTrue(head.all { it.isFinite() && abs(it) <= 1.000001f }, "${h.label}: a head sample is not finite or is over full scale")
                assertTrue(head.any { it != 0f }, "${h.label}: an all-zero head")
            }
        }
    }

    @Test
    fun `a non-finite sample is zeroed where it stood and the rest is the kick's own head`() {
        for ((at, bad) in listOf(10 to Float.NaN, 5 to Float.POSITIVE_INFINITY)) {
            val broken = kick.samples.copyOf().also { it[at] = bad }
            val zeroed = kick.samples.copyOf().also { it[at] = 0f }
            assertContentEquals(Terra.captureStriker(Snip(zeroed, 1, rate)), Terra.captureStriker(Snip(broken, 1, rate)), "$bad at $at")
        }
    }

    /** Onset alignment: 1 ms of lead before the hit, however much silence came first. */
    @Test
    fun `lead silence of any length captures the kick's own head`() {
        val reference = assertNotNull(Terra.captureStriker(leadZeros(44, kick)))
        for (ms in listOf(20, 40, 100, 3000)) {
            assertContentEquals(reference, Terra.captureStriker(leadZeros(ms * rate / 1000, kick)), "$ms ms of silence")
        }
    }

    /**
     * A -90 dBFS lead-in (3.16e-5) sits under the 1 % onset line, so the head
     * starts 1 ms before the kick. The noise can only differ in those 44
     * lead samples, by at most 3.16e-5 over the head's own peak. The THUMP
     * KICK's head peak is above 0.5, so 1e-4 is a bound derived from the
     * signal, not a guess; the measured difference is printed.
     */
    @Test
    fun `a -90 dBFS lead-in does not become the hammer`() {
        val noisy = TerraStrikers.hostile().first { it.label == "a kick after 30 ms of -90 dBFS noise" }.snip
        val reference = assertNotNull(Terra.captureStriker(leadZeros(44, kick)))
        val head = assertNotNull(Terra.captureStriker(noisy))
        var worst = 0f
        for (i in head.indices) worst = maxOf(worst, abs(head[i] - reference[i]))
        println("TERRA capture: -90 dBFS lead-in, largest difference from the clean head ${"%.2e".format(worst)}")
        assertTrue(worst <= 1e-4f, "the lead-in moved the head by $worst")
    }

    @Test
    fun `a single sample lands 1 ms into the head, alone, at full scale`() {
        for (at in listOf(0, 881, 882, 5000)) {
            val x = FloatArray(6000).also { it[at] = 0.5f }
            val head = assertNotNull(Terra.captureStriker(Snip(x, 1, rate)), "spike at $at")
            val where = minOf(at, 44)
            assertEquals(1f, head[where], "spike at $at")
            assertTrue(head.indices.all { it == where || head[it] == 0f }, "spike at $at: something besides the spike in the head")
        }
    }

    @Test
    fun `a quiet click before a louder hit is refused by the head's own peak (decision 20)`() {
        val source = TerraStrikers.hostile().first { it.label == "a quiet click before a louder hit (decision 20)" }.snip
        assertTrue(source.peak() >= 1e-4f, "the source as a whole must clear the floor, or it cannot tell the two rules apart")
        assertNull(Terra.captureStriker(source))
    }

    @Test
    fun `a stereo source whose channels cancel gives no striker`() {
        val k = kick.samples
        val cancel = Snip(FloatArray(k.size * 2) { i -> if (i % 2 == 0) k[i / 2] else -k[i / 2] }, 2, rate)
        assertNull(Terra.captureStriker(cancel))
        val same = Snip(FloatArray(k.size * 2) { i -> k[i / 2] }, 2, rate)
        assertContentEquals(Terra.captureStriker(kick), Terra.captureStriker(same), "identical channels fold to the mono kick")
    }

    /**
     * New in R1, and outside G-C1 (spec, "HIT, the design", step 1): a
     * foreign-rate source is cut to 10 ms before its onset through 100 ms
     * after, and only then resampled. Phase 0 measured 61 ms to resample a
     * whole 3 s file at 48 kHz. A NaN in the lead silence and an Inf long
     * after the window fall outside the cut and change nothing. The match to
     * the 44.1 kHz capture is the best normalised correlation within three
     * samples' lag; 0.95 is the starting bar, printed. If it fails, record
     * the printed value and set the bar from it with about 20 % margin, never
     * below 0.9.
     */
    @Test
    fun `a 48 kHz source is cut near its onset before it is resampled, and still captures the kick`() {
        val at48 = Resampler.resample(kick, 48_000).samples
        val long = Snip(FloatArray(24_000) + at48 + FloatArray(48_000 * 3), 1, 48_000)
        val head = assertNotNull(Terra.captureStriker(long))
        val reference = assertNotNull(Terra.captureStriker(leadZeros(44, kick)))
        var best = -1.0
        for (lag in -3..3) {
            var ab = 0.0
            var aa = 0.0
            var bb = 0.0
            for (i in head.indices) {
                val j = i + lag
                if (j !in reference.indices) continue
                ab += head[i].toDouble() * reference[j]
                aa += head[i].toDouble() * head[i]
                bb += reference[j].toDouble() * reference[j]
            }
            best = maxOf(best, ab / sqrt(aa * bb))
        }
        println("TERRA capture: 48 kHz kick against the 44.1 kHz capture, best NCC within 3 samples ${"%.4f".format(best)}")
        assertTrue(best >= 0.95, "the 48 kHz capture is not the kick: NCC $best")
        val dirty = long.samples.copyOf().also { it[100] = Float.NaN; it[it.size - 10] = Float.POSITIVE_INFINITY }
        assertContentEquals(head, Terra.captureStriker(Snip(dirty, 1, 48_000)), "a NaN before the cut or an Inf after it reached the head")
    }
}
```

- [ ] **Step 3: Run the tests to see them fail**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraCaptureTest"`
Expected: compilation FAILS with `Unresolved reference 'captureStriker'`.

- [ ] **Step 4: Write the capture**

In `synth/src/main/kotlin/com/snipsnap/synth/Terra.kt`, replace the import block (lines 3-9) with:

```kotlin
import com.snipsnap.audio.Cleanup
import com.snipsnap.audio.Resampler
import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh
```

Then insert before the closing brace of `object Terra` (after `tunedBar`'s closing brace):

```kotlin

    // ---- HIT's capture: another pad's first 20 ms, once, at pick time ----

    /**
     * Below this a captured head is silence, not a hit: -80 dBFS, above a
     * 16-bit file's own floor of about -96 dBFS. Tested on the aligned head's
     * peak before normalising (decision 20: the brief's wording). The
     * whole-source peak struck-motion M7 measured differs only for a quiet
     * source that is louder after its first 20 ms.
     */
    const val SILENT_HEAD_PEAK = 1e-4f

    /** The onset: the first finite sample at or above this fraction of the finite peak (within 40 dB). */
    private const val ONSET_FRACTION = 0.01f

    /** 1 ms of lead kept before the onset. */
    private const val ONSET_LEAD_SAMPLES = RATE / 1000

    /** How much of a foreign-rate source is resampled: from 10 ms before its onset to 100 ms after. */
    private const val FOREIGN_LEAD_SECONDS = 0.01f
    private const val FOREIGN_KEEP_SECONDS = 0.1f

    /** FORK's striker fade, 2 ms at [RATE]: 88 samples (`Fork.kt:353`, `:864-868`). */
    private val STRIKER_FADE_SAMPLES = (2f / 1000f * RATE).roundToInt()

    /**
     * Another pad's hit as a TERRA striker: [Fork.STRIKER_SAMPLES] samples at
     * [RATE], peak-normalised, with FORK's 2 ms raised-cosine tail. Null when
     * there is no hit to take; the pick is then refused, and a patch built
     * without one renders today's body.
     *
     * The order is struck-motion M7's:
     * 1. Fold to mono. A source at another rate is cut near its onset and
     *    only then resampled.
     * 2. Align to 1 ms before the onset, so lead silence of any length
     *    still captures the hit.
     * 3. Zero any non-finite sample.
     * 4. Refuse a head under [SILENT_HEAD_PEAK].
     * 5. Normalise and fade.
     *
     * On a mono 44.1 kHz source whose onset is within 1 ms of the start, the
     * result equals [Fork.striker] bit for bit. [Fork.striker] itself is left
     * untouched.
     */
    fun captureStriker(source: Snip): FloatArray? {
        val mono = if (source.channels == 1) source else Cleanup.toMono(source)
        val pk = finitePeak(mono.samples)
        val x = if (mono.sampleRate == RATE) mono.samples else nearOnset(mono, pk)
        val start = maxOf(0, onsetOf(x, pk) - ONSET_LEAD_SAMPLES)
        val head = FloatArray(Fork.STRIKER_SAMPLES) { i -> x.getOrElse(start + i) { 0f }.let { v -> if (v.isFinite()) v else 0f } }
        var headPeak = 0f
        for (v in head) headPeak = maxOf(headPeak, abs(v))
        if (headPeak < SILENT_HEAD_PEAK) return null
        Dsp.normalize(head, 1f)
        val fadeN = STRIKER_FADE_SAMPLES
        for (i in 0 until fadeN) {
            val g = 0.5f * (1f + cos(Math.PI.toFloat() * i / fadeN))
            head[head.size - fadeN + i] *= g
        }
        return head
    }

    /** The loudest finite sample's magnitude: step 2's `pk`, always read on the whole source at its own rate. */
    private fun finitePeak(x: FloatArray): Float {
        var pk = 0f
        for (v in x) if (v.isFinite()) pk = maxOf(pk, abs(v))
        return pk
    }

    /** The first finite sample of [x] at or above [ONSET_FRACTION] of the source's finite peak [pk], or 0. */
    private fun onsetOf(x: FloatArray, pk: Float): Int {
        for (i in x.indices) if (x[i].isFinite() && abs(x[i]) >= ONSET_FRACTION * pk) return i
        return 0
    }

    /**
     * A source at another rate, cut to its own onset's neighbourhood with
     * non-finite samples zeroed, and only then resampled to [RATE]: a whole
     * 3 s file at 48 kHz cost 61 ms to resample, and the resampler would
     * smear one NaN across its kernel. The onset is found again on the
     * resampled window against the whole source's [pk], so a source that is
     * loudest after its first 100 ms keeps the same 1 % line at both rates.
     */
    private fun nearOnset(mono: Snip, pk: Float): FloatArray {
        val s = mono.samples
        val onset = onsetOf(s, pk)
        val from = maxOf(0, onset - (FOREIGN_LEAD_SECONDS * mono.sampleRate).toInt())
        val to = minOf(s.size, onset + (FOREIGN_KEEP_SECONDS * mono.sampleRate).toInt())
        if (to <= from) return FloatArray(0)
        val window = FloatArray(to - from) { i -> s[from + i].let { v -> if (v.isFinite()) v else 0f } }
        return Resampler.resample(Snip(window, 1, mono.sampleRate), RATE).samples
    }
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraCaptureTest" --tests "com.snipsnap.synth.TerraFrozenTest" -i`
Expected: exit code 0, and `TerraCaptureTest` reports 10 tests passed. Read the printed lines: `TERRA capture:` for every hostile row, the lead-in's largest difference and the 48 kHz NCC. Keep them for the commit message.

If `the capture is FORK's striker on the measured strikers` fails for a factory sample, print that sample's onset index. G-C1 measured all thirteen bit-identical, so an onset past 44 samples means the factory WAV changed under the test. Do not loosen the comparison; stop and report it.

- [ ] **Step 6: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Terra.kt synth/src/test/kotlin/com/snipsnap/synth/TerraStrikers.kt synth/src/test/kotlin/com/snipsnap/synth/TerraCaptureTest.kt
git commit -m "TERRA captures another pad's hit safely, or refuses it

Terra.captureStriker is FORK's striker where FORK's is safe: on the ten
measured strikers and on any mono source that starts within 1 ms. Silence,
lead silence, NaN and Inf, a quiet lead-in, cancelling channels and a 48 kHz
file are each pinned. The silence floor tests the aligned head's own peak
(decision 20)."
```

Before running it, add a second `-m "..."` argument that holds the two `TERRA capture:` figures Step 5 printed: the lead-in's largest difference and the 48 kHz NCC, copied from the test output.

---

### Task 4: HIT's coloured gain, and the claims that it couples, stays in tune and is monotone

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Terra.kt` (imports; append before the object's closing brace, after `nearOnset`)
- Create: `synth/src/test/kotlin/com/snipsnap/synth/TerraMeasure.kt`
- Test: `synth/src/test/kotlin/com/snipsnap/synth/TerraTest.kt` (imports; append before the class's closing brace)

**Interfaces:**
- Consumes: `Terra.Body`, `Terra.BankInputs`, `Terra.renderWith`, `Terra.bankWith` and `Terra.strikeAndModalBank(…, level = …)` from Task 2. `TerraStrikers.TEN` and `TerraStrikers.head(id)` from Task 3. `TerraCases.all` and `LegacyTerraBank.render` from Task 1. `Resampler.resample`, `Fft.forward(re, im)`, `Fft.binToHz(bin, fftSize, sampleRate): Float`, `Classifier.classify(snip).drumClass`, `Dsp.Biquad().bandpass(f0, q, rate)` / `process(x)`.
- Produces, in `object Terra`:
  - `internal fun upsample(head: FloatArray): FloatArray`
  - `internal fun hitLevel(body: Body, x: FloatArray, c: Float): Array<FloatArray>?`
  - `internal fun renderStruck(voice: TerraVoice, macros: Map<String, Float>, head: FloatArray, hit: Float): Snip`
  - `internal fun renderStruckAt(voice: TerraVoice, macros: Map<String, Float>, x: FloatArray, hit: Float): Snip`
  - `internal fun bankStruckAt(voice: TerraVoice, macros: Map<String, Float>, x: FloatArray, hit: Float): FloatArray`
- Produces, in test sources: `internal object TerraMeasure` with `onsetOf`, `bandDb`, `ob(snip, nominalHz): Float`, `firstFiveMsPeak(snip): Float`, `peakHz`, `f0(x, rate, nominalHz): Float`, `cents(f, ref): Double`, `bodyOf(voice, macros = emptyMap()): Terra.Body`, `cavityStage(bank, mix, rate): FloatArray`, `tanhInput(bank, rate): Float` and `msAbove(x, threshold, rate): Double`.
- Task 5's `TerraPatch.render()` calls `renderStruck`. Task 7 uses `bankStruckAt`, `upsample` and `TerraMeasure`.

- [ ] **Step 1: Write the measures, defined exactly as Phase 0 read them**

Create `synth/src/test/kotlin/com/snipsnap/synth/TerraMeasure.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The measures HIT's claims tests read, defined as Phase 0 read them
 * (docs/superpowers/plans/2026-09-30-chimera-phase-0-record.md, §7 and
 * Appendix A), so a printed figure can be set beside Phase 0's own.
 * Specifically:
 * - Onset: the first sample at 1 % of the finite peak.
 * - Overtone balance (OB): from onset + 882 frames to the end, Hann over the
 *   slice, zero-padded to a power of two. Energy at or above 1.5 x the
 *   nominal f0 over energy below it, bin 0 excluded. The split is the
 *   code's, `binToHz < split` (`StruckMetrics.ob` in the evidence folder);
 *   the record's §3.1 prose says "at or below". They differ only for a bin
 *   landing exactly on 1.5 x f0, a float-equality event.
 * - f0: a 2^17-point Hann peak, interpolated on the log magnitude, over
 *   100-300 ms after the onset.
 */
internal object TerraMeasure {

    /** 20 ms at 44.1 kHz: the head is over; what is left is the body. */
    const val BODY_FROM_FRAMES = 882

    fun onsetOf(x: FloatArray): Int {
        var pk = 0f
        for (v in x) if (v.isFinite()) pk = maxOf(pk, abs(v))
        val thr = 0.01f * pk
        for (i in x.indices) if (abs(x[i]) >= thr && thr > 0f) return i
        return 0
    }

    private fun hann(i: Int, n: Int): Float = (0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))).toFloat()

    fun bandDb(x: FloatArray, from: Int, splitHz: Float, rate: Int): Float {
        val n = x.size - from
        if (n < 8) return 0f
        var size = 1
        while (size < n) size = size shl 1
        val re = FloatArray(size)
        val im = FloatArray(size)
        for (i in 0 until n) re[i] = x[from + i] * hann(i, n)
        Fft.forward(re, im)
        var lo = 0.0
        var hi = 0.0
        for (b in 1..size / 2) {
            val pw = (re[b] * re[b] + im[b] * im[b]).toDouble()
            if (Fft.binToHz(b, size, rate) < splitHz) lo += pw else hi += pw
        }
        return (10.0 * log10((hi + 1e-20) / (lo + 1e-20))).toFloat()
    }

    /** OB of a rendered pad, split at 1.5 x [nominalHz] - the f0 the bank actually used ([bodyOf]), never one derived again from a macro. */
    fun ob(snip: Snip, nominalHz: Float): Float =
        bandDb(snip.samples, onsetOf(snip.samples) + BODY_FROM_FRAMES, nominalHz * 1.5f, snip.sampleRate)

    /** The peak in the first 5 ms after the onset over the whole clip's peak (Phase 0's stable attack figure). */
    fun firstFiveMsPeak(snip: Snip): Float {
        val x = snip.samples
        val on = onsetOf(x)
        var pk5 = 0f
        for (i in on until minOf(x.size, on + 5 * snip.sampleRate / 1000)) pk5 = maxOf(pk5, abs(x[i]))
        val peak = snip.peak()
        return if (peak > 0f) pk5 / peak else 0f
    }

    fun peakHz(x: FloatArray, from: Int, to: Int, lo: Float, hi: Float, rate: Int): Float {
        val end = minOf(x.size, to)
        val n = end - from
        if (n < 64) return 0f
        val size = 1 shl 17
        val re = FloatArray(size)
        val im = FloatArray(size)
        for (i in 0 until n) re[i] = x[from + i] * hann(i, n)
        Fft.forward(re, im)
        val mag = FloatArray(size / 2 + 1) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        val binHz = rate.toFloat() / size
        val i0 = (lo / binHz).toInt().coerceAtLeast(1)
        val i1 = (hi / binHz).toInt().coerceAtMost(mag.size - 2)
        var best = i0
        for (i in i0..i1) if (mag[i] > mag[best]) best = i
        val l = ln(mag[best - 1] + 1e-12)
        val c = ln(mag[best] + 1e-12)
        val r = ln(mag[best + 1] + 1e-12)
        val d = 0.5 * (l - r) / (l - 2 * c + r)
        return ((best + d) * binHz).toFloat()
    }

    fun f0(x: FloatArray, rate: Int, nominalHz: Float): Float {
        val on = onsetOf(x)
        return peakHz(x, on + rate / 10, on + 3 * rate / 10, nominalHz * 0.7f, nominalHz * 1.5f, rate)
    }

    fun cents(f: Float, ref: Float): Double = 1200.0 * ln(f.toDouble() / ref) / ln(2.0)

    /** The [Terra.Body] the bank receives for [voice] at [macros]. */
    fun bodyOf(voice: TerraVoice, macros: Map<String, Float> = emptyMap()): Terra.Body {
        var body: Terra.Body? = null
        Terra.bankWith(voice, macros) { body = it; null }
        return requireNotNull(body)
    }

    /** RESONANT_CAVITY's stage as Terra.kt runs it (75 Hz band-pass, Q 8, into tanh(x * 1.15), crossfaded by CAVITY): the signal BUZZ sees, for the printed BUZZ figures. */
    fun cavityStage(bank: FloatArray, mix: Float, rate: Int): FloatArray {
        val bp = Dsp.Biquad().apply { bandpass(75f, 8f, rate) }
        val out = bank.copyOf()
        for (i in out.indices) {
            val sat = tanh(bp.process(out[i]) * 1.15f)
            out[i] = out[i] * (1f - mix) + sat * mix
        }
        return out
    }

    /** The cavity tanh's largest input: the peak of 1.15 x the 75 Hz band-pass of the whole bank (today 0.3075, Phase 0). */
    fun tanhInput(bank: FloatArray, rate: Int): Float {
        val bp = Dsp.Biquad().apply { bandpass(75f, 8f, rate) }
        var pk = 0f
        for (v in bank) pk = maxOf(pk, abs(bp.process(v) * 1.15f))
        return pk
    }

    /** Time above [threshold], in ms at [rate] (BUZZ gates on 0.12). */
    fun msAbove(x: FloatArray, threshold: Float, rate: Int): Double = x.count { abs(it) > threshold } * 1000.0 / rate
}
```

- [ ] **Step 2: Write the failing tests**

In `synth/src/test/kotlin/com/snipsnap/synth/TerraTest.kt`, add after `import kotlin.test.assertEquals`:

```kotlin
import kotlin.test.assertNotNull
import kotlin.test.assertNull
```

and after `import kotlin.math.abs`:

```kotlin
import kotlin.math.log10
```

Then insert before the class's closing brace, after Task 2's test:

```kotlin

    // ---------- HIT (spec "HIT, the design"; "Testing", tests 1, 2 and 8) ----------

    private val impulse = floatArrayOf(1f)

    /** Phase 0's G-P0d and G-P0e on the forty cases: HIT 0 with any head, and an impulse at subtle and strong, are today's TERRA bit for bit. */
    @Test
    fun `HIT 0 with any head and an impulse at subtle and strong render the frozen TERRA`() {
        val heads = listOf("tkick", "tsnare", "wraith").map { TerraStrikers.head(it) }
        var renders = 0
        for (c in TerraCases.all) {
            val frozen = LegacyTerraBank.render(c.voice, c.macros).samples
            for (h in heads) {
                assertContentEquals(frozen, Terra.renderStruck(c.voice, c.macros, h, 0f).samples, "${c.label}, HIT 0")
                renders++
            }
            for (hit in listOf(0.5f, 1f)) {
                assertContentEquals(frozen, Terra.renderStruckAt(c.voice, c.macros, impulse, hit).samples, "${c.label}, an impulse at HIT $hit")
                renders++
            }
        }
        assertEquals(200, renders)
    }

    /** Elsewhere (1 - c) + c may round once (spec); every gain an impulse gives stays within one ulp of 1. */
    @Test
    fun `an impulse leaves every gain within one ulp of 1 at any strength`() {
        for (voice in TerraVoice.entries) {
            val body = TerraMeasure.bodyOf(voice)
            for (c in listOf(0.1f, 0.25f, 0.3f, 0.7f, 0.9f)) {
                val gains = assertNotNull(Terra.hitLevel(body, impulse, c), "$voice c=$c")
                for (curve in gains) for (g in curve) assertTrue(abs(g - 1f) <= Math.ulp(1f), "$voice c=$c: gain $g")
            }
        }
    }

    /** A coloured body with no peak gives a non-finite `s`; the striker renders as absent (spec, "Failure handling"). */
    @Test
    fun `a head with nothing in it renders today's body at HIT 1`() {
        for (voice in TerraVoice.entries) {
            val plain = Terra.render(voice).samples
            val nothing = FloatArray(Fork.STRIKER_SAMPLES)
            assertNull(Terra.hitLevel(TerraMeasure.bodyOf(voice), Terra.upsample(nothing), 1f), "$voice: a level match from nothing")
            assertContentEquals(plain, Terra.renderStruck(voice, emptyMap(), nothing, 1f).samples, "$voice: an empty head changed the drum")
        }
    }

    /**
     * Recipe provenance, read before any claim below is blamed on HIT. The
     * six synthesised strikers' macro values are not in the tree (see
     * TerraStrikers), so this checks that the ten are still the ten Phase 0
     * measured. It uses Phase 0's T2 (record, Appendix A): OB against
     * unstruck, at defaults, over the ten strikers, as mean / min / max per
     * voice. Every one of the ten moves the mean, so a drifted recipe or a
     * changed factory WAV shows here even when the three named strikers'
     * rows in the next test still pass.
     *
     * Asserted at HIT 0.5 within 0.5 dB, Phase 0's materiality rule: T2 may
     * come from struck-shape's float path, which differs from the
     * prototype's by up to 6e-4 per sample on the cavity (G-P0h). At HIT 1
     * the extremes are printed only, because several sit within about 3 dB
     * of T3's OB floor (cavity -14.61, bar -33.78), where a reading is
     * distortion products, not mode energy.
     *
     * Each striker's source format, peak and onset are printed first, so a
     * failure names which source changed.
     */
    @Test
    fun `the ten strikers reproduce Phase 0's overtone spread at subtle - recipe provenance`() {
        class Spread(val voice: TerraVoice, val at05: DoubleArray, val at1: DoubleArray)
        val t2 = listOf(
            Spread(TerraVoice.COMPOUND_MEMBRANE, doubleArrayOf(-0.33, -8.08, 3.48), doubleArrayOf(-0.67, -15.70, 6.87)),
            Spread(TerraVoice.RESONANT_CAVITY, doubleArrayOf(2.99, -7.00, 12.13), doubleArrayOf(6.33, -14.61, 24.49)),
            Spread(TerraVoice.CONICAL_BELL, doubleArrayOf(-1.12, -5.66, 3.31), doubleArrayOf(-2.86, -15.04, 8.41)),
            Spread(TerraVoice.TUNED_BAR, doubleArrayOf(-1.61, -7.34, 6.74), doubleArrayOf(-9.45, -33.78, 27.10)),
        )
        for (s in TerraStrikers.TEN) {
            val src = s.snip
            println(
                "TERRA striker ${s.id} ${s.name}: ${src.channels} ch, ${src.sampleRate} Hz, ${src.frameCount} frames, " +
                    "peak ${"%.4f".format(src.peak())}, onset at sample ${TerraMeasure.onsetOf(src.samples)}",
            )
        }
        for (row in t2) {
            val nominal = TerraMeasure.bodyOf(row.voice).fundamentalHz
            val today = TerraMeasure.ob(Terra.render(row.voice), nominal).toDouble()
            for ((hit, phase0) in listOf(0.5f to row.at05, 1f to row.at1)) {
                val moves = TerraStrikers.TEN.map { s ->
                    TerraMeasure.ob(Terra.renderStruck(row.voice, emptyMap(), TerraStrikers.head(s.id), hit), nominal).toDouble() - today
                }
                val got = doubleArrayOf(moves.average(), moves.min(), moves.max())
                println("TERRA HIT T2 ${row.voice} HIT $hit per striker: " + TerraStrikers.TEN.zip(moves).joinToString { (s, m) -> "${s.id} ${"%.2f".format(m)}" })
                println(
                    "TERRA HIT T2 ${row.voice} HIT $hit: mean / min / max ${got.joinToString(" / ") { "%.2f".format(it) }} dB " +
                        "(Phase 0 ${phase0.joinToString(" / ") { "%.2f".format(it) }})",
                )
                if (hit == 0.5f) {
                    for ((i, what) in listOf("mean", "min", "max").withIndex()) {
                        assertEquals(phase0[i], got[i], 0.5, "${row.voice} HIT 0.5: the ten strikers' $what OB move is not Phase 0's")
                    }
                }
            }
        }
    }

    /**
     * The build is the algorithm Phase 0 measured. OB is read from 20 ms
     * on the float render, at defaults; the figures are spec "Testing" test
     * 2's and the Phase-0 record's T3 and Appendix B. 0.5 dB is Phase 0's
     * own materiality rule. A slip in the running projection, the level
     * match's two windows or the hold-last lookup moves these by whole dB.
     * A reading more than 0.05 dB off but inside 0.5 is printed, and should
     * be explained before Task 5 starts.
     */
    @Test
    fun `HIT reproduces the overtone balance Phase 0 measured`() {
        class Row(val voice: TerraVoice, val striker: String?, val hit: Float, val ob: Double)
        val rows = listOf(
            Row(TerraVoice.COMPOUND_MEMBRANE, null, 0f, -18.12),
            Row(TerraVoice.RESONANT_CAVITY, null, 0f, -37.83),
            Row(TerraVoice.CONICAL_BELL, null, 0f, -46.10),
            Row(TerraVoice.TUNED_BAR, null, 0f, -41.42),
            Row(TerraVoice.COMPOUND_MEMBRANE, "tkick", 0.5f, -21.07),
            Row(TerraVoice.COMPOUND_MEMBRANE, "tsnare", 0.5f, -15.23),
            Row(TerraVoice.COMPOUND_MEMBRANE, "wraith", 0.5f, -14.64),
            Row(TerraVoice.COMPOUND_MEMBRANE, "tkick", 1f, -25.23),
            Row(TerraVoice.COMPOUND_MEMBRANE, "wraith", 1f, -11.25),
            Row(TerraVoice.RESONANT_CAVITY, "tkick", 0.5f, -44.84),
            Row(TerraVoice.RESONANT_CAVITY, "wraith", 0.5f, -29.67),
            Row(TerraVoice.CONICAL_BELL, "tkick", 0.5f, -47.78),
            Row(TerraVoice.CONICAL_BELL, "tsnare", 0.5f, -43.91),
            Row(TerraVoice.TUNED_BAR, "tkick", 0.5f, -47.82),
            Row(TerraVoice.TUNED_BAR, "wraith", 0.5f, -43.28),
        )
        for (r in rows) {
            val nominal = TerraMeasure.bodyOf(r.voice).fundamentalHz
            val snip = if (r.striker == null) Terra.render(r.voice) else Terra.renderStruck(r.voice, emptyMap(), TerraStrikers.head(r.striker), r.hit)
            val ob = TerraMeasure.ob(snip, nominal).toDouble()
            println("TERRA HIT OB ${r.voice} ${r.striker ?: "unstruck"} HIT ${r.hit}: ${"%.2f".format(ob)} dB (Phase 0 ${"%.2f".format(r.ob)})")
            assertEquals(r.ob, ob, 0.5, "${r.voice} ${r.striker} HIT ${r.hit}")
        }
    }

    /**
     * Coupling, not layering (spec "Testing", test 2), measured as a band
     * ratio and never the centroid, which barely moves (struck-r1). Phase 0,
     * HIT 0.5:
     * - membrane: THUMP KICK -21.07 against THUMP SNARE -15.23 dB;
     * - cavity: THUMP KICK -44.84 against WRAITH WORD -29.67 dB;
     * - bell: THUMP KICK -47.78 against THUMP SNARE -43.91 dB;
     * - bar: THUMP KICK -47.82 against WRAITH WORD -43.28 dB.
     *
     * 3 dB is the spec's proposed bar. The fundamental moves 0.00 cents on
     * membrane, bell and bar. The cavity's final render reads up to 2.11
     * cents, because its fixed 75 Hz stage and a one-peak estimator move the
     * reading while the bank is exact; so the cavity's claim is asserted on
     * the bank, and the final reading is printed.
     */
    @Test
    fun `HIT couples - a dull and a bright head move the overtone balance after 20 ms, not the tuning or the length`() {
        val pairs = listOf(
            Triple(TerraVoice.COMPOUND_MEMBRANE, "tkick", "tsnare"),
            Triple(TerraVoice.RESONANT_CAVITY, "tkick", "wraith"),
            Triple(TerraVoice.CONICAL_BELL, "tkick", "tsnare"),
            Triple(TerraVoice.TUNED_BAR, "tkick", "wraith"),
        )
        for ((voice, dull, bright) in pairs) {
            val body = TerraMeasure.bodyOf(voice)
            val today = Terra.render(voice)
            val struck = listOf(dull, bright).associateWith { Terra.renderStruck(voice, emptyMap(), TerraStrikers.head(it), 0.5f) }
            val obDull = TerraMeasure.ob(struck.getValue(dull), body.fundamentalHz)
            val obBright = TerraMeasure.ob(struck.getValue(bright), body.fundamentalHz)
            println("TERRA HIT coupling $voice: $dull ${"%.2f".format(obDull)} dB, $bright ${"%.2f".format(obBright)} dB, ${"%.2f".format(obBright - obDull)} apart")
            assertTrue(abs(obBright - obDull) >= 3.0, "$voice: the two heads moved the overtone balance only ${obBright - obDull} dB apart")
            for ((id, s) in struck) assertEquals(today.frameCount, s.frameCount, "$voice $id changed the length")
            val f0Today = TerraMeasure.f0(today.samples, today.sampleRate, body.fundamentalHz)
            if (voice != TerraVoice.RESONANT_CAVITY) {
                for ((id, s) in struck) {
                    val cents = TerraMeasure.cents(TerraMeasure.f0(s.samples, s.sampleRate, body.fundamentalHz), f0Today)
                    println("TERRA HIT tuning $voice $id: ${"%.2f".format(cents)} cents")
                    assertTrue(abs(cents) <= 2.0, "$voice $id moved the fundamental $cents cents")
                }
            } else {
                val bankToday = Terra.bankWith(voice, emptyMap(), null)
                val f0BankToday = TerraMeasure.f0(bankToday, body.rate, body.fundamentalHz)
                for ((id, s) in struck) {
                    val bank = Terra.bankStruckAt(voice, emptyMap(), Terra.upsample(TerraStrikers.head(id)), 0.5f)
                    val bankCents = TerraMeasure.cents(TerraMeasure.f0(bank, body.rate, body.fundamentalHz), f0BankToday)
                    val finalCents = TerraMeasure.cents(TerraMeasure.f0(s.samples, s.sampleRate, body.fundamentalHz), f0Today)
                    println("TERRA HIT tuning $voice $id: bank ${"%.2f".format(bankCents)} cents, final render ${"%.2f".format(finalCents)} cents (Phase 0: up to 2.11 at HIT 0.5)")
                    assertTrue(abs(bankCents) <= 2.0, "$voice $id moved the bank's fundamental $bankCents cents")
                }
            }
        }
    }

    /**
     * Spec "Testing", test 8, over the ten strikers and c in {0, .25, .5,
     * .75, 1}:
     * - Every striker's OB moves monotonically on every voice (Phase 0: 10 of
     *   10).
     * - At 0.5 the drum class never changes, and the first-5-ms peak is
     *   today's. Phase 0 read 1.000 on every voice; 0.005 is that figure's
     *   rounding.
     * - At 1 the class changes and the attack are printed beside Phase 0's
     *   (record, Appendix A, T11/T12): 5 of 10 membrane renders TOM to PERC;
     *   the first-5-ms peak's mean over the ten strikers, as a ratio and in
     *   dB, is 0.815 / -2.22 dB on the membrane, 0.832 / -1.85 dB on the
     *   cavity, 0.970 on the bell and 1.000 on the bar.
     *
     * Any future velocity registration of HIT needs this sweep first.
     */
    @Test
    fun `HIT is monotone in its amount and keeps today's attack and class at subtle`() {
        val strengths = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val phase0AtStrong = mapOf(
            TerraVoice.COMPOUND_MEMBRANE to "0.815 / -2.22 dB",
            TerraVoice.RESONANT_CAVITY to "0.832 / -1.85 dB",
            TerraVoice.CONICAL_BELL to "0.970",
            TerraVoice.TUNED_BAR to "1.000",
        )
        var flipsAtStrong = 0
        var renders = 0
        for (voice in TerraVoice.entries) {
            val body = TerraMeasure.bodyOf(voice)
            val today = Terra.render(voice)
            val todayClass = Classifier.classify(today).drumClass
            val todayPk5 = TerraMeasure.firstFiveMsPeak(today)
            var strongRatioSum = 0.0
            var strongDbSum = 0.0
            var strongChangeDbSum = 0.0
            for (s in TerraStrikers.TEN) {
                val head = TerraStrikers.head(s.id)
                val sweep = strengths.map { c -> if (c == 0f) today else Terra.renderStruck(voice, emptyMap(), head, c) }
                renders += 4
                val obs = sweep.map { TerraMeasure.ob(it, body.fundamentalHz).toDouble() }
                val steps = obs.zipWithNext { x, y -> y - x }
                println("TERRA HIT sweep $voice ${s.name}: OB ${obs.joinToString(" / ") { "%.2f".format(it) }}")
                assertTrue(steps.all { it >= -0.01 } || steps.all { it <= 0.01 }, "$voice ${s.name}: OB is not monotone in HIT: $obs")
                val subtle = sweep[2]
                assertEquals(todayClass, Classifier.classify(subtle).drumClass, "$voice ${s.name}: HIT 0.5 changed the drum class")
                val pk5 = TerraMeasure.firstFiveMsPeak(subtle)
                assertTrue(abs(pk5 - todayPk5) <= 0.005f, "$voice ${s.name}: HIT 0.5 moved the first-5-ms peak to $pk5 from $todayPk5")
                val strong = sweep[4]
                val strongClass = Classifier.classify(strong).drumClass
                if (strongClass != todayClass) flipsAtStrong++
                val strongPk5 = TerraMeasure.firstFiveMsPeak(strong)
                strongRatioSum += strongPk5
                strongDbSum += 20.0 * log10(maxOf(strongPk5, 1e-6f).toDouble())
                strongChangeDbSum += 20.0 * log10(maxOf(strongPk5, 1e-6f).toDouble() / maxOf(todayPk5, 1e-6f))
                println("TERRA HIT 1 $voice ${s.name}: class $strongClass (today $todayClass), first-5-ms peak ${"%.3f".format(strongPk5)}")
            }
            val n = TerraStrikers.TEN.size
            println(
                "TERRA HIT 1 $voice attack: first-5-ms peak mean ${"%.3f".format(strongRatioSum / n)} / ${"%.2f".format(strongDbSum / n)} dB, " +
                    "${"%.2f".format(strongChangeDbSum / n)} dB from today's ${"%.3f".format(todayPk5)} (Phase 0: ${phase0AtStrong.getValue(voice)})",
            )
        }
        println("TERRA HIT 1: $flipsAtStrong class changes in 40 renders (Phase 0: 5, all membrane TOM to PERC)")
        assertEquals(160, renders)
    }

    /** The pre-roll keeps under the 50 % ceiling `clack is quiet, and the bell only starts ringing after it` holds it to (Phase 0: 5.9 % today, 16.5 % at HIT 1 with THUMP KICK). */
    @Test
    fun `the CLACK pre-roll stays quiet under HIT`() {
        val plainLength = Terra.render(TerraVoice.CONICAL_BELL, mapOf("CLACK" to 0f)).frameCount
        for (id in listOf("tkick", "tsnare")) {
            for (hit in listOf(0.5f, 1f)) {
                val out = Terra.renderStruck(TerraVoice.CONICAL_BELL, mapOf("CLACK" to 1f), TerraStrikers.head(id), hit).samples
                val clack = out.size - plainLength
                assertTrue(clack > 0, "CLACK 1 should lengthen the render")
                val preroll = out.copyOfRange(0, clack).maxOf { abs(it) }
                val body = out.copyOfRange(clack, minOf(out.size, clack + 2000)).maxOf { abs(it) }
                println("TERRA HIT CLACK $id HIT $hit: pre-roll ${"%.1f".format(100 * preroll / body)} % of the body")
                assertTrue(preroll < body * 0.5f, "$id HIT $hit: the pre-roll is ${preroll / body} of the body")
            }
        }
    }
```

- [ ] **Step 3: Run the tests to see them fail**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraTest"`
Expected: compilation FAILS with `Unresolved reference 'renderStruck'` and `Unresolved reference 'hitLevel'`.

- [ ] **Step 4: Write HIT**

In `synth/src/main/kotlin/com/snipsnap/synth/Terra.kt`, add `import kotlin.math.sqrt` after `import kotlin.math.sin`. Then insert before the closing brace of `object Terra`, after `nearOnset`:

```kotlin

    // ---- HIT: the other pad's first 20 ms colours how hard each mode rings ----

    /** -60 dB in nepers, in double: HIT's running projection runs in double precision, as Phase 0 measured it. */
    private const val T60_NEPERS_DOUBLE = 6.9078

    /** A stored striker head ([Fork.STRIKER_SAMPLES] at [RATE]) at the render rate: 3528 samples, resampled once per render. */
    internal fun upsample(head: FloatArray): FloatArray =
        Resampler.resample(Snip(head, 1, RATE), RATE * Dsp.OVERSAMPLE).samples

    /** [voice] struck by a stored [head] at HIT [hit], 0..1. HIT 0 is today's render, byte for byte, and computes nothing. */
    internal fun renderStruck(voice: TerraVoice, macros: Map<String, Float>, head: FloatArray, hit: Float): Snip =
        if (!(hit > 0f)) render(voice, macros) else renderStruckAt(voice, macros, upsample(head), hit)

    /** [renderStruck] with the striker [x] already at the render rate; an impulse there is `floatArrayOf(1f)`. */
    internal fun renderStruckAt(voice: TerraVoice, macros: Map<String, Float>, x: FloatArray, hit: Float): Snip =
        renderWith(voice, macros, hitInputs(x, hit))

    /** The bank alone ([bankWith]) struck by [x], already at the render rate, at HIT [hit]. */
    internal fun bankStruckAt(voice: TerraVoice, macros: Map<String, Float>, x: FloatArray, hit: Float): FloatArray =
        bankWith(voice, macros, hitInputs(x, hit))

    /** HIT as the bank's input builder: null at HIT 0, so [renderWith] takes today's path without computing anything. */
    private fun hitInputs(x: FloatArray, hit: Float): ((Body) -> BankInputs?)? {
        if (!(hit > 0f)) return null
        val inputs: (Body) -> BankInputs? = { body -> hitLevel(body, x, hit)?.let { BankInputs(level = it) } }
        return inputs
    }

    /**
     * HIT's level curve on [body]: round two's COLOURED candidate, in the
     * gain domain, on TERRA's own bank (spec, "HIT, the design").
     *
     * Each mode's gain is multiplied by `G_k(n) = (1 - c) + c * s * |P_k(n)|`:
     * - `|P_k(n)|` is the running magnitude of the striker [x] projected onto
     *   mode k at its nominal pitch, droop ignored. It grows while the
     *   striker plays and holds after its last non-zero sample, so a
     *   short-lived mode is never inflated during the head.
     * - `s` is the level match: the peak of today's body over the first three
     *   periods, divided by the peak of the coloured body.
     *
     * The receiver's modes come from [body] at render, so a retuned pad is
     * recoloured. Returns null - today's body - when [c] is not above 0, or
     * when `s` is not finite because the coloured body has no peak (spec,
     * "Failure handling").
     *
     * The arithmetic follows Phase 0's operation for operation, as the
     * Phase-0 record's §8.1 quotes it (`TerraStruckR2.running` and
     * `levelS`), which is what makes it the measured algorithm:
     * - the projection in double precision;
     * - a zero striker sample skipped, but its decay weight still advanced;
     * - `s` divided in float and then widened.
     * One deliberate difference: where the coloured body has no peak,
     * Phase 0's `levelS` returned `s = 0.0` (every gain `1 - c`); this
     * returns null, today's body, as the spec's failure rule asks. No
     * Phase-0 case reached that branch.
     */
    internal fun hitLevel(body: Body, x: FloatArray, c: Float): Array<FloatArray>? {
        if (!(c > 0f) || x.isEmpty() || body.modes.isEmpty()) return null
        val run = runningMagnitudes(body, x)
        val m = run[0].size
        val ringFrames = body.frames - body.onsetSamples
        val periods = (3f * body.rate / body.fundamentalHz).toInt()
        val reference = strikeAndModalBank(body.modes, body.fundamentalHz, body.droopDepth, minOf(ringFrames, periods + 16), body.rate, { 0f })
        val magnitudes = Array(run.size) { k -> FloatArray(m) { n -> run[k][n].toFloat() } }
        val coloured = strikeAndModalBank(
            body.modes, body.fundamentalHz, body.droopDepth, minOf(ringFrames, maxOf(periods, m) + 64), body.rate, { 0f },
            level = magnitudes,
        )
        val colouredPeak = peakOf(coloured)
        if (!(colouredPeak > 0f)) return null
        val s = (peakOf(reference) / colouredPeak).toDouble()
        if (!s.isFinite()) return null
        val cd = c.coerceAtMost(1f).toDouble()
        return Array(run.size) { k -> FloatArray(m) { n -> ((1.0 - cd) + cd * s * run[k][n]).toFloat() } }
    }

    /**
     * `|P_k(n)|` for n below M (the striker's last non-zero sample, plus 1).
     * A mode the bank would skip (at or past Nyquist, or with no t60) gets
     * zeros.
     */
    private fun runningMagnitudes(body: Body, x: FloatArray): Array<DoubleArray> {
        var last = 0
        for (i in x.indices) if (x[i] != 0f) last = i
        val m = last + 1
        return Array(body.modes.size) { k ->
            val mode = body.modes[k]
            val hz = body.fundamentalHz * mode.ratio
            val out = DoubleArray(m)
            if (hz <= 0f || hz >= body.rate / 2f || mode.t60 <= 0f) return@Array out
            val invR = 1.0 / exp(-T60_NEPERS_DOUBLE / (mode.t60.toDouble() * body.rate))
            val theta = 2.0 * Math.PI * hz / body.rate
            var re = 0.0
            var im = 0.0
            var w = 1.0
            for (n in 0 until m) {
                if (x[n] != 0f) {
                    val phi = theta * n.toDouble()
                    re += x[n] * w * cos(phi)
                    im -= x[n] * w * sin(phi)
                }
                w *= invR
                out[n] = sqrt(re * re + im * im)
            }
            out
        }
    }

    private fun peakOf(x: FloatArray): Float {
        var p = 0f
        for (v in x) p = maxOf(p, abs(v))
        return p
    }
```

- [ ] **Step 5: Check that the strikers are Phase 0's, before reading any claim**

The six synthesised recipes are not in the tree, so check where they came from before the algorithm is blamed for anything. If the evidence folder is on this machine, run:

```bash
E=~/Documents/snipsnap-chimera-evidence-2026-09-29/spikes/phase0/new-files/synth/src/test/kotlin/com/snipsnap/synth/Phase0.kt
if [ -f "$E" ]; then sed -n '54,59p' "$E"; else echo "evidence folder absent"; fi
```

Expected, when present: the six `Vox.render`, `Thump.render` and `Fork.excite` calls, each with the same arguments as `TerraStrikers.ten`. When absent (a cloud session, CI), the in-tree check below is the only one.

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraTest.the ten strikers reproduce Phase 0's overtone spread at subtle - recipe provenance" --tests "com.snipsnap.synth.TerraTest.HIT reproduces the overtone balance Phase 0 measured" -i`
Expected: exit code 0. Read the `TERRA striker` lines (each source's format, peak and onset), the `TERRA HIT T2` lines (each striker's OB move, and the mean, min and max beside Phase 0's) and the `TERRA HIT OB` rows.

Both tests render through `renderStruck`, so a failure of the T2 check alone does not prove a recipe drifted. Read the two together:
- **Both pass:** go on to Step 6.
- **The named rows pass and T2 fails:** the three recorded strikers and HIT are Phase 0's, so one of the seven sources with no row of its own drifted (a BEATBOX or NOISE HAMMER recipe, or a factory WAV). The per-striker `TERRA HIT T2` lines show which one carries the min or max that is off. Compare it with the evidence, or with the record's §3.1 description, and stop and report. No bound in this task may be widened to absorb it.
- **The named rows fail and T2 passes:** the strikers are Phase 0's as a set, so this is not a recipe problem. Go to Step 6's diagnosis.
- **Both fail:** the recipes are not shown to be the cause. If the evidence check above printed the same six calls, provenance is settled; go to Step 6's diagnosis, which starts with the measure. If the evidence is absent, still go to Step 6, and report the recipes as unconfirmed beside whatever Step 6 finds.

- [ ] **Step 6: Run the tests to see them pass**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraTest" --tests "com.snipsnap.synth.TerraFrozenTest" --tests "com.snipsnap.synth.TerraCaptureTest" -i`
Expected: exit code 0. Read the `TERRA HIT` lines: OB beside Phase 0's figures, the T2 spread, coupling gaps, cents, the sweep, HIT 1's class changes and attack means, and the CLACK ratios. Keep them for the commit message.

If `HIT 0 with any head and an impulse…` or `an impulse leaves every gain within one ulp of 1…` fails on an impulse case, `s` is not 1 there. For an impulse, `M` is 1 and `|P_k|` is exactly 1, so the coloured body equals today's bank and `s` is the ratio of two peaks of the same signal: the reference window (`periods + 16` frames) and the coloured window (`periods + 64`). `s != 1` means the bank's peak falls in the 48 extra frames. Print `Terra.hitLevel(TerraMeasure.bodyOf(voice, macros), floatArrayOf(1f), 1f)!![0][0]` for each failing case (at `c = 1` it is `s` itself), and stop and report. Phase 0's G-P0e passed on the same forty cases, so this is a slip in the windows, not a property of TERRA.

If `HIT reproduces the overtone balance Phase 0 measured` fails, find the cause in this order, each one ruling out the next:
1. Recipe provenance, as Step 5 read it. Only "the named rows pass and T2 fails" points at a striker, and Step 5 already stopped there. In every other case go on.
2. The measure. If an unstruck row (striker `null`) is off, `TerraMeasure.ob` or the onset differs from Phase 0's, and neither the recipes nor HIT is involved.
3. The bank's structure. If the HIT-0 or impulse bit-identity tests fail, the null path or the driven branch is wrong (Task 2), not the colouring.
4. The algorithm, as §8.1 quotes it:
   - `s` is divided in float and only then widened;
   - a zero striker sample is skipped but `w` still advances;
   - the two windows are `periods + 16` and `max(periods, M) + 64`, both from the strike with a zero exciter;
   - the lookup holds the last value;
   - `T60_NEPERS_DOUBLE` is used in double.

Never widen the 0.5 dB. If none of the four explains the reading, print it beside Phase 0's and stop and report.

If only `HIT couples…` fails on the bell (Phase 0's gap there is 3.9 dB, against the 3 dB proposed bar), print the measured gap. Record it in the test's comment and set that voice's bar to the measurement less 20 %. Keep 3 dB for the others.

If only the first-5-ms check in `HIT is monotone…` fails, read the printed value. Phase 0's 1.000 is a mean over ten strikers, so one striker a hair under it is consistent with Phase 0. Record the worst measured difference in the test's comment and set the bound to it plus about 20 %. Never drop the check, and keep the class check exact.

- [ ] **Step 7: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/Terra.kt synth/src/test/kotlin/com/snipsnap/synth/TerraMeasure.kt synth/src/test/kotlin/com/snipsnap/synth/TerraTest.kt
git commit -m "HIT colours how hard each of TERRA's modes rings from another pad's first 20 ms

Round two's COLOURED gain on TERRA's own bank: the striker's running
projection per mode at the nominal pitch, levelled to today's body by peak,
blended by HIT. HIT 0, and an impulse at 0.5 and 1, render today's TERRA bit
for bit on all forty cases. A head with nothing in it renders today's body.
The overtone balance reproduces Phase 0's figures, a dull and a bright head
couple on every voice without moving the tuning or the length, and the sweep
is monotone."
```

Before running it, add a second `-m "..."` argument that holds the `TERRA HIT` lines Steps 5 and 6 printed, copied from the test output: the OB readings beside Phase 0's, the T2 spread, the four coupling gaps, the cents, HIT 1's class changes and attack means, and the CLACK ratios.

---

### Task 5: `TerraPatch` carries a striker, as data, under the version rule

**Files:**
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/TerraPatch.kt` (whole file, 34 lines)
- Modify: `synth/src/main/kotlin/com/snipsnap/synth/Terra.kt` (append the striker overload before the object's closing brace, after `peakOf`)
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/DeterminismTest.kt` (append after Task 1's TERRA canary)
- Test: `synth/src/test/kotlin/com/snipsnap/synth/TerraPatchTest.kt` (create)

**Interfaces:**
- Consumes:
  - `Patches.decode(value, engine, voiceOf, build)` (internal, `Patches.kt:75-93`), `Patches.toJsonValue(patch)` (internal, `:60-72`), `Patches.validateMacros` and `Patches.VERSION` (1).
  - `Fork.STRIKER_SAMPLES`, `Terra.renderStruck`, `Terra.hitLevel` and `Terra.upsample` from Task 4.
  - `Velocity.atVelocity(patch, velocity)` (`Velocity.kt:268-269`), `Velocity.soften(snip, amount)` (`:26`), `Velocity.variantsAt(snip, patch, fx = null, count = 2)` (`:87`) and `Velocity.brightnessSpec(patch)` (`:312`).
  - `PadRecipe(patch = …)`, `TerraStrikers.head` and `TerraMeasure.bodyOf`.
  - From `:kit`: `ArrangedPad(snip, drumClass, recipe)`, `KitAssembler.assembleArranged(name, arranged, dir)` (`KitAssembler.kt:79`) and `KitStore.load(dir): Kit` (`KitStore.kt:33`), as `PadRecipeTest.kt:78-100` uses them.
- Produces:
  - `class TerraPatch(name: String, voice: TerraVoice, macros: Map<String, Float>, striker: TerraPatch.Striker? = null) : Patch`, with `fun copy(name, voice, macros, striker): TerraPatch` and hand-written `equals`, `hashCode` and `toString`.
  - `class TerraPatch.Striker(head: FloatArray, val hit: Float, val from: String? = null)`, with `val head: FloatArray` (a copy) and `fun copy(head, hit, from): Striker`.
  - In `TerraPatch`'s companion: `const val DRIVEN_VERSION = 2`, `const val MAX_FROM_CHARS = 24` and `internal fun isLabel(s: String): Boolean`.
  - In `object Terra`: `internal fun render(voice: TerraVoice, macros: Map<String, Float>, drivers: TerraPatch.Striker?): Snip`. R3 widens the parameter's type to a carrier of all three drivers.
  - Task 6 and Task 8 build `TerraPatch(…, Striker(…))`.

- [ ] **Step 1: Write the failing tests**

Create `synth/src/test/kotlin/com/snipsnap/synth/TerraPatchTest.kt`:

```kotlin
package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitStore
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A struck TERRA pad as recipe data (spec, "Data flow and compatibility";
 * "Testing", tests 4 and 5 with a striker only - R3 extends both to BEND
 * and TALK). It covers the version rule, the refusals in init and on
 * decode, copies in and out, and the striker riding through `withMacros`
 * and `Velocity`. `:shell`'s BREED, presets and replay are
 * TerraStrikerCarryTest's.
 */
class TerraPatchTest {

    private val head = TerraStrikers.head("tsnare")
    private val struck = TerraPatch("Struck", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.4f), TerraPatch.Striker(head, 0.5f, "A02"))
    private val plain = TerraPatch("Struck", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.4f))

    private fun version(p: Patch) = (p.toJsonValue().entries.getValue("version") as JsonValue.Num).value

    @Test
    fun `a struck patch writes version 2 with its striker, and a plain one version 1 without`() {
        assertEquals(2.0, version(struck))
        assertEquals(listOf("engine", "version", "name", "voice", "macros", "striker"), struck.toJsonValue().entries.keys.toList())
        val st = (struck.toJsonValue().entries.getValue("striker") as JsonValue.Obj).entries
        assertEquals(listOf("from", "hit", "head"), st.keys.toList())
        assertEquals(Fork.STRIKER_SAMPLES, st.getValue("head").arr().size)
        assertEquals(1.0, version(plain))
        assertFalse("striker" in plain.toJsonValue().entries)
        val unlabelled = struck.copy(striker = TerraPatch.Striker(head, 0.5f))
        assertFalse("from" in (unlabelled.toJsonValue().entries.getValue("striker") as JsonValue.Obj).entries, "a null label is written")
    }

    @Test
    fun `a struck patch round-trips by content and re-encodes to the same bytes`() {
        val variants = listOf(struck, struck.copy(striker = TerraPatch.Striker(head, 1f)), struck.copy(striker = TerraPatch.Striker(head, 0f, "SOUL A03")))
        for (p in variants) {
            val text = p.toJsonText()
            val back = TerraPatch.fromJsonText(text)
            assertEquals(p, back)
            assertEquals(p.hashCode(), back.hashCode())
            assertEquals(text, back.toJsonText())
            assertEquals(p, Patches.fromJsonText(text), "through the dispatcher")
            val recipe = PadRecipe(patch = p)
            val again = PadRecipe.fromJsonText(recipe.toJsonText())
            assertEquals(recipe, again)
            assertContentEquals(recipe.render().samples, again.render().samples)
        }
    }

    @Test
    fun `equals and hashCode compare the striker by content, label included`() {
        val twin = TerraPatch("Struck", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.4f), TerraPatch.Striker(head.copyOf(), 0.5f, "A02"))
        assertEquals(struck, twin)
        assertEquals(struck.hashCode(), twin.hashCode())
        val relabelled = struck.copy(striker = TerraPatch.Striker(head, 0.5f, "SOUL A03"))
        assertNotEquals(struck, relabelled)
        assertNotEquals(struck.toJsonText(), relabelled.toJsonText())
        assertNotEquals(struck, plain)
        assertNotEquals(struck, struck.copy(striker = TerraPatch.Striker(head, 0.75f, "A02")))
    }

    @Test
    fun `the head is copied in and out`() {
        val raw = head.copyOf()
        val s = TerraPatch.Striker(raw, 0.5f)
        raw[0] = 99f
        assertEquals(head[0], s.head[0], "a change to the array passed in reached the striker")
        s.head[1] = 99f
        assertEquals(head[1], s.head[1], "a change to the array read out reached the striker")
    }

    @Test
    fun `a broken striker is refused when it is built`() {
        assertFailsWith<IllegalArgumentException> { TerraPatch.Striker(FloatArray(881), 0.5f) }
        assertFailsWith<IllegalArgumentException> { TerraPatch.Striker(FloatArray(883), 0.5f) }
        assertFailsWith<IllegalArgumentException> { TerraPatch.Striker(head.copyOf().also { it[3] = Float.NaN }, 0.5f) }
        assertFailsWith<IllegalArgumentException> { TerraPatch.Striker(head.copyOf().also { it[3] = Float.POSITIVE_INFINITY }, 0.5f) }
        for (bad in listOf(Float.NaN, -0.01f, 1.01f)) assertFailsWith<IllegalArgumentException>("HIT $bad") { TerraPatch.Striker(head, bad) }
        assertFailsWith<IllegalArgumentException> { TerraPatch.Striker(head, 0.5f, "A".repeat(TerraPatch.MAX_FROM_CHARS + 1)) }
        assertFailsWith<IllegalArgumentException> { TerraPatch.Striker(head, 0.5f, "A\nB") }
        assertEquals("A".repeat(TerraPatch.MAX_FROM_CHARS), TerraPatch.Striker(head, 0.5f, "A".repeat(TerraPatch.MAX_FROM_CHARS)).from)
    }

    private fun edited(edit: (LinkedHashMap<String, JsonValue>) -> Unit): JsonValue {
        val obj = LinkedHashMap(struck.toJsonValue().entries)
        edit(obj)
        return JsonValue.Obj(obj)
    }

    private fun strikerEdited(edit: (LinkedHashMap<String, JsonValue>) -> Unit): JsonValue = edited { o ->
        val s = LinkedHashMap((o.getValue("striker") as JsonValue.Obj).entries)
        edit(s)
        o["striker"] = JsonValue.Obj(s)
    }

    /** A hand-edited sidecar is refused, not silently played: the door SNAP's and FORK's arrays guard (spec "Testing", test 5). */
    @Test
    fun `a broken or mislabelled struck recipe is refused on decode`() {
        val items = (struck.toJsonValue().entries.getValue("striker") as JsonValue.Obj).entries.getValue("head").arr()
        val cases = linkedMapOf(
            "a head one sample short" to strikerEdited { it["head"] = JsonValue.Arr(items.dropLast(1)) },
            "a head one sample long" to strikerEdited { it["head"] = JsonValue.Arr(items + JsonValue.Num(0.0)) },
            "a head value past float range" to strikerEdited { it["head"] = JsonValue.Arr(items.toMutableList().also { l -> l[3] = JsonValue.Num(1e300) }) },
            "a head value that is a string" to strikerEdited { it["head"] = JsonValue.Arr(items.toMutableList().also { l -> l[3] = JsonValue.Str("NaN") }) },
            "a head that is an object" to strikerEdited { it["head"] = JsonValue.Obj(emptyMap()) },
            "no head" to strikerEdited { it.remove("head") },
            "HIT above 1" to strikerEdited { it["hit"] = JsonValue.Num(1.5) },
            "HIT below 0" to strikerEdited { it["hit"] = JsonValue.Num(-0.25) },
            "no HIT" to strikerEdited { it.remove("hit") },
            "a from that is a number" to strikerEdited { it["from"] = JsonValue.Num(3.0) },
            "a from that is an object" to strikerEdited { it["from"] = JsonValue.Obj(emptyMap()) },
            "a from of 25 characters" to strikerEdited { it["from"] = JsonValue.Str("A".repeat(25)) },
            "a from with a control character" to strikerEdited { it["from"] = JsonValue.Str("A\u0001") },
            "a striker that is an array" to edited { it["striker"] = JsonValue.Arr(emptyList()) },
            "version 1 carrying a striker" to edited { it["version"] = JsonValue.Num(1.0) },
            "version 2 carrying none" to edited { it.remove("striker") },
            "version 2 with a null striker" to edited { it["striker"] = JsonValue.Null },
            "version 3" to edited { it["version"] = JsonValue.Num(3.0) },
        )
        for ((label, json) in cases) {
            assertFailsWith<JsonException>(label) { TerraPatch.fromJsonValue(json) }
            assertFailsWith<JsonException>("$label, through the dispatcher") { Patches.fromJsonValue(json) }
        }
        val plainWithNull = JsonValue.Obj(LinkedHashMap(plain.toJsonValue().entries).also { it["striker"] = JsonValue.Null })
        assertEquals(plain, TerraPatch.fromJsonValue(plainWithNull), "an explicit null striker on a plain patch reads as none")
    }

    /** What an older build runs: the shared version-1 decode refuses a struck patch by its version (`Patches.kt:84-85`). */
    @Test
    fun `the decode an older build runs refuses a struck patch by version`() {
        val thrown = assertFailsWith<JsonException> {
            Patches.decode(struck.toJsonValue(), TerraPatch.ENGINE, { n -> TerraVoice.entries.firstOrNull { it.name == n } }) { name, voice, macros ->
                TerraPatch(name, voice, macros)
            }
        }
        assertTrue("unsupported patch version 2" in (thrown.message ?: ""), "the refusal must name the version: ${thrown.message}")
    }

    @Test
    fun `a striker renders through HIT, its label is never heard, and HIT 0 is today's pad`() {
        assertContentEquals(Terra.renderStruck(struck.voice, struck.macros, head, 0.5f).samples, struck.render().samples)
        assertFalse(struck.render().samples.contentEquals(plain.render().samples), "the striker did nothing")
        val relabelled = struck.copy(striker = TerraPatch.Striker(head, 0.5f, "SOUL A03"))
        assertContentEquals(struck.render().samples, relabelled.render().samples, "the label changed the sound")
        assertContentEquals(plain.render().samples, struck.copy(striker = TerraPatch.Striker(head, 0f)).render().samples, "HIT 0 is not today's pad")
    }

    @Test
    fun `withMacros keeps the striker`() {
        assertEquals(struck.striker, struck.withMacros(mapOf("TUNE" to 0.4f, "DECAY" to 0.8f)).striker)
    }

    /** The striker stores the hit, not its projection; the receiver's modes are recomputed at render (spec, "HIT, the design"). */
    @Test
    fun `retuning a struck pad keeps its striker and recomputes the colouring`() {
        val retuned = struck.withMacros(mapOf("TUNE" to 0.9f))
        val plainRetuned = plain.withMacros(mapOf("TUNE" to 0.9f))
        assertEquals(struck.striker, retuned.striker)
        val out = retuned.render().samples
        assertTrue(out.all { it.isFinite() && abs(it) <= 1f }, "the retuned struck pad left the range")
        assertEquals(plainRetuned.render().samples.size, out.size, "the retuned struck pad changed length")
        assertFalse(out.contentEquals(plainRetuned.render().samples), "the retuned pad lost its striker's colour")
        val x = Terra.upsample(head)
        val before = Terra.hitLevel(TerraMeasure.bodyOf(struck.voice, struck.macros), x, 0.5f)!!
        val after = Terra.hitLevel(TerraMeasure.bodyOf(retuned.voice, retuned.macros), x, 0.5f)!!
        assertTrue(before.indices.any { !before[it].contentEquals(after[it]) }, "the colouring did not follow the new tuning")
    }

    /**
     * The spec's central data-flow claim ("Data flow and compatibility"): a
     * struck pad is an ordinary synth pad, saved verbatim in kit.json and
     * regenerated by PadRecipe.render(). This is PadRecipeTest's `the whole
     * point - a kit on disk regenerates itself from its sidecar`, with a
     * struck pad beside a plain one. The recipe's size is printed beside the
     * spec's estimate of about 31 KB for a striker (inferred there, never
     * measured).
     */
    @Test
    fun `a struck pad on disk regenerates itself from its sidecar`() {
        val dir = java.nio.file.Files.createTempDirectory("terra-struck-kit").toFile()
        try {
            val arranged = listOf(struck, plain).map { p -> ArrangedPad(p.render(), DrumClass.TOM, PadRecipe(patch = p).toJsonValue()) }
            KitAssembler.assembleArranged("Struck", arranged, dir)
            val loaded = KitStore.load(dir)
            assertEquals(2, loaded.pads.size)
            for (pad in loaded.pads) {
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe) { "pad ${pad.slot} lost its recipe" })
                assertContentEquals(arranged[pad.slot - 1].snip.samples, recipe.render().samples, "pad ${pad.slot} did not regenerate bit for bit")
            }
            val back = PadRecipe.fromJsonValue(requireNotNull(loaded.pads.first { it.slot == 1 }.recipe)).patch
            assertEquals(struck, back, "the striker did not survive kit.json")
        } finally {
            dir.deleteRecursively()
        }
        println("TERRA struck recipe: ${PadRecipe(patch = struck).toJsonText().length} characters of JSON (spec: about 31 KB for a striker, inferred, not measured)")
    }

    /** TERRA keeps the soften fallback, and the render it softens is the struck one; HIT is not a brightness override (spec, "HIT, the design", Velocity). */
    @Test
    fun `velocity layers soften the struck render, and HIT is not a brightness macro`() {
        assertNull(Velocity.brightnessSpec(struck))
        assertContentEquals(Velocity.soften(struck.render(), 0.5f).samples, Velocity.atVelocity(struck, 0.5f).samples)
        assertFalse(Velocity.atVelocity(struck, 0.5f).samples.contentEquals(Velocity.atVelocity(plain, 0.5f).samples))
        val reference = plain.render()
        val layered = Velocity.variantsAt(reference, struck)
        val unstruck = Velocity.variantsAt(reference, plain)
        assertEquals(2, layered.size)
        assertTrue(layered.indices.any { !layered[it].samples.contentEquals(unstruck[it].samples) }, "the velocity layers ignored the striker")
    }
}
```

In `synth/src/test/kotlin/com/snipsnap/synth/DeterminismTest.kt`, after Task 1's `TERRA is byte-identical across renders, all four voices`, add:

```kotlin

    // A struck TERRA pad renders from the head stored in its recipe; the
    // capture is data, so nothing else is read and every render must agree.
    @Test
    fun `TERRA struck by a stored head is byte-identical across renders`() {
        val head = Terra.captureStriker(Thump.render(ThumpVoice.SNARE)) ?: error("a snare is not silent")
        for (voice in TerraVoice.entries) {
            val patch = TerraPatch("Canary", voice, Terra.defaults(voice), TerraPatch.Striker(head, 0.75f, "A02"))
            assertContentEquals(patch.render().samples, patch.render().samples, voice.name)
        }
    }
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraPatchTest" --tests "com.snipsnap.synth.DeterminismTest"`
Expected: compilation FAILS with `Unresolved reference 'Striker'`.

- [ ] **Step 3: Write the patch**

This step is two edits that compile only together: `TerraPatch.render` calls the new `Terra.render` overload, and the overload takes a `TerraPatch.Striker`. Replace the whole of `synth/src/main/kotlin/com/snipsnap/synth/TerraPatch.kt` with:

```kotlin
package com.snipsnap.synth

import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue

/**
 * A saved TERRA sound: voice (topology), macro settings and a hand-written
 * name, the same shape as every other engine's patch (see [Patches]). It may
 * also carry a [Striker]: another pad's captured hit, and how hard it
 * strikes this drum (HIT).
 *
 * The striker is data inside the recipe, never a pointer to a pad. A struck
 * pad therefore regenerates bit for bit from `kit.json` however its source
 * pad changes later. This is [ForkPatch.striker]'s precedent
 * (docs/superpowers/specs/2026-09-30-terra-hit-bend-talk-design.md, "Data
 * flow and compatibility").
 *
 * Not a data class: an array member would compare by identity there. A
 * plain patch writes `"version": 1` and exactly the bytes TERRA has always
 * written. A struck one writes `"version": [DRIVEN_VERSION]`. Every older
 * build refuses that by name, rather than playing plain TERRA and dropping
 * the striker on re-save.
 */
class TerraPatch(
    override val name: String,
    val voice: TerraVoice,
    override val macros: Map<String, Float>,
    val striker: Striker? = null,
) : Patch {

    /**
     * Another pad's hit:
     * - [head] is [Fork.STRIKER_SAMPLES] samples at [Dsp.RATE], every one
     *   finite (what `Terra.captureStriker` returns). It is copied in and
     *   out.
     * - [hit] is how much of it strikes this drum: 0 is today, 0.5 subtle,
     *   1 strong.
     * - [from] is the source pad's label as the chooser showed it. It is
     *   display only, because rendering never reads it, but it is part of
     *   the value: two patches that differ only in it are different
     *   recipes.
     */
    class Striker(head: FloatArray, val hit: Float, val from: String? = null) {
        private val samples: FloatArray = head.copyOf()

        /** A copy: the striker cannot be changed through it. */
        val head: FloatArray get() = samples.copyOf()

        init {
            require(samples.size == Fork.STRIKER_SAMPLES) { "a TERRA striker has ${Fork.STRIKER_SAMPLES} samples, got ${samples.size}" }
            for ((i, v) in samples.withIndex()) require(v.isFinite()) { "striker head[$i] is not finite: $v" }
            require(hit in 0f..1f) { "HIT is 0..1, got $hit" }
            require(from == null || TerraPatch.isLabel(from)) {
                "a striker's from is at most ${TerraPatch.MAX_FROM_CHARS} characters with no control characters: '$from'"
            }
        }

        fun copy(head: FloatArray = samples, hit: Float = this.hit, from: String? = this.from): Striker = Striker(head, hit, from)

        override fun equals(other: Any?): Boolean =
            other is Striker && hit == other.hit && from == other.from && samples.contentEquals(other.samples)

        // + 0f folds -0 into 0, which equals already treats as the same HIT.
        override fun hashCode(): Int = (samples.contentHashCode() * 31 + (hit + 0f).hashCode()) * 31 + (from?.hashCode() ?: 0)

        override fun toString(): String = "Striker(hit=$hit, from=$from, head=[${samples.size} samples])"
    }

    init {
        Patches.validateMacros(this, Terra.macrosFor(voice))
    }

    override val engine get() = ENGINE
    override val voiceName get() = voice.name
    override fun render() = Terra.render(voice, macros, striker)
    override fun withMacros(macros: Map<String, Float>) = copy(macros = macros)

    fun copy(
        name: String = this.name,
        voice: TerraVoice = this.voice,
        macros: Map<String, Float> = this.macros,
        striker: Striker? = this.striker,
    ): TerraPatch = TerraPatch(name, voice, macros, striker)

    override fun toJsonValue(): JsonValue.Obj {
        val base = Patches.toJsonValue(this)
        val s = striker ?: return base
        val obj = LinkedHashMap(base.entries)
        obj["version"] = JsonValue.Num(DRIVEN_VERSION.toDouble())
        val st = LinkedHashMap<String, JsonValue>()
        s.from?.let { st["from"] = JsonValue.Str(it) }
        st["hit"] = JsonValue.Num(s.hit.toDouble())
        st["head"] = JsonValue.Arr(s.head.map { JsonValue.Num(it.toDouble()) })
        obj["striker"] = JsonValue.Obj(st)
        return JsonValue.Obj(obj)
    }

    override fun equals(other: Any?): Boolean =
        other is TerraPatch && name == other.name && voice == other.voice && macros == other.macros && striker == other.striker

    override fun hashCode(): Int =
        ((name.hashCode() * 31 + voice.hashCode()) * 31 + macros.hashCode()) * 31 + (striker?.hashCode() ?: 0)

    override fun toString(): String =
        "TerraPatch(name=$name, voice=$voice, macros=$macros" + (striker?.let { ", striker=$it" } ?: "") + ")"

    companion object {
        const val ENGINE = "TERRA"

        /** The version a struck patch writes. A plain one writes [Patches.VERSION] and today's bytes. */
        const val DRIVEN_VERSION = 2

        /** The longest source label a striker keeps; the chooser cuts a longer one before it is stored. */
        const val MAX_FROM_CHARS = 24

        internal fun isLabel(s: String): Boolean = s.length <= MAX_FROM_CHARS && s.none { it.isISOControl() }

        /**
         * The version rule: version 1 without a striker, version
         * [DRIVEN_VERSION] with one, and nothing else; no writer produces
         * any other combination.
         *
         * Engine, version and the common fields still go through the shared
         * [Patches.decode], so a wrong engine or a later version names
         * itself first. A version-2 object reaches it relabelled version 1,
         * once its striker has been read.
         *
         * An explicit `"striker": null` reads as no striker, as SNAP's and
         * FORK's optional fields do.
         */
        fun fromJsonValue(value: JsonValue): Patch {
            val obj = value.obj()
            val strikerField = obj["striker"]?.takeUnless { it is JsonValue.Null }
            val version = obj["version"]
            if (obj["engine"] == JsonValue.Str(ENGINE) && version is JsonValue.Num && version.value == DRIVEN_VERSION.toDouble()) {
                if (strikerField == null) throw JsonException("a version-$DRIVEN_VERSION TERRA patch carries no driver")
                val striker = readStriker(strikerField)
                val common = LinkedHashMap(obj)
                common["version"] = JsonValue.Num(Patches.VERSION.toDouble())
                return Patches.decode(JsonValue.Obj(common), ENGINE, { n -> voiceOf(n) }) { name, voice, macros -> TerraPatch(name, voice, macros, striker) }
            }
            return Patches.decode(value, ENGINE, { n -> voiceOf(n) }) { name, voice, macros ->
                if (strikerField != null) throw JsonException("a version-${Patches.VERSION} TERRA patch carries no striker; a struck one is version $DRIVEN_VERSION")
                TerraPatch(name, voice, macros)
            }
        }

        fun fromJsonText(text: String): TerraPatch = fromJsonValue(Json.parse(text)) as TerraPatch

        private fun voiceOf(name: String): TerraVoice? = TerraVoice.entries.firstOrNull { it.name == name }

        /** The striker object, every rule of [Striker]'s `init` checked first so a bad file is a [JsonException], never a bare [IllegalArgumentException]. */
        private fun readStriker(raw: JsonValue): Striker {
            val o = (raw as? JsonValue.Obj)?.entries ?: throw JsonException("a TERRA striker is not an object")
            val points = (o["head"] ?: throw JsonException("a TERRA striker has no head")).arr()
            if (points.size != Fork.STRIKER_SAMPLES) throw JsonException("TERRA striker head has ${points.size} samples, not ${Fork.STRIKER_SAMPLES}")
            val head = FloatArray(points.size) { i ->
                val v = points[i].num().toFloat()
                if (!v.isFinite()) throw JsonException("TERRA striker head[$i] is not finite: $v")
                v
            }
            val hit = (o["hit"] ?: throw JsonException("a TERRA striker has no hit")).num().toFloat()
            if (!(hit in 0f..1f)) throw JsonException("TERRA striker hit is not 0..1: $hit")
            val from = when (val f = o["from"]) {
                null, JsonValue.Null -> null
                is JsonValue.Str -> {
                    if (!isLabel(f.value)) throw JsonException("TERRA striker from is over $MAX_FROM_CHARS characters or holds a control character")
                    f.value
                }
                else -> throw JsonException("TERRA striker from is not a string")
            }
            return Striker(head, hit, from)
        }
    }
}
```

In `synth/src/main/kotlin/com/snipsnap/synth/Terra.kt`, insert before the closing brace of `object Terra`, after `peakOf`:

```kotlin

    /**
     * A TERRA pad's render: today's when [drivers] is null, otherwise struck
     * by the striker's head at its HIT. The striker's `from` label is never
     * read here. Named `drivers` as in the spec's `Terra.render(voice,
     * macros, drivers)`: in R1 the only driver is the striker, and R3 widens
     * the type to carry `bend` and `talk` without renaming the parameter.
     */
    internal fun render(voice: TerraVoice, macros: Map<String, Float>, drivers: TerraPatch.Striker?): Snip =
        if (drivers == null) render(voice, macros) else renderStruck(voice, macros, drivers.head, drivers.hit)
```

- [ ] **Step 4: Run the patch tests, the guard and the whole `:synth` suite**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraPatchTest" --tests "com.snipsnap.synth.TerraFrozenTest" --tests "com.snipsnap.synth.DeterminismTest" --tests "com.snipsnap.synth.PadRecipeTest" --tests "com.snipsnap.synth.TerraKitsTest"`
Expected: exit code 0. `TerraFrozenTest`'s `a plain TERRA patch writes today's bytes` and `the shipped Terra Kit is the frozen TERRA` are the proof that the conversion changed nothing for plain pads.

Run: `./gradlew --no-daemon :synth:test`, then `echo "exit=$?"`
Expected: `exit=0`.

- [ ] **Step 5: Commit**

```bash
git add synth/src/main/kotlin/com/snipsnap/synth/TerraPatch.kt synth/src/main/kotlin/com/snipsnap/synth/Terra.kt synth/src/test/kotlin/com/snipsnap/synth/TerraPatchTest.kt synth/src/test/kotlin/com/snipsnap/synth/DeterminismTest.kt
git commit -m "A TERRA recipe carries another pad's hit as data, and an older build refuses it by version

TerraPatch is a non-data class with an optional striker: an 882-sample head,
HIT, and a display-only source label. A struck patch writes version 2; a
plain one keeps version 1 and today's bytes. Every rule is checked when the
striker is built and again on decode, the head is copied in and out, and
withMacros, the velocity layers and a retune all keep the striker."
```

---

### Task 6: `:shell`'s rewrites carry the striker, or refuse what they cannot read

**Files:**
- Test: `shell/src/test/kotlin/com/snipsnap/shell/TerraStrikerCarryTest.kt` (create)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/SidecarFuzzTest.kt:25-28` (imports), `:544` (append before the class's closing brace)
- Test: `shell/src/test/kotlin/com/snipsnap/shell/DegenerateDoorsTest.kt:17-19` (imports), `:202` (a struck TERRA seed recipe)

**Interfaces:**
- Consumes:
  - `Breed.cross(a: PadRecipe?, b: PadRecipe?, rng: java.util.Random): PadRecipe` (internal to `:shell`, `Breed.kt:248`) and `Breed.recipeOf(recipe: JsonValue.Obj?): PadRecipe?` (`:147`).
  - `UserPresets.save(shelfRoot, patch, nowMillis)` (`UserPresets.kt:165`), `UserPresets.read(shelfRoot)` (`:157`), `UserPresets.forget(shelfRoot, engine, voice, name, nowMillis): Binned?` (`:241`) and `UserPresets.unforget(shelfRoot, binned): Saved?` (`:257`). The private `renamed` (`:307-311`) is reached through forget → save → unforget.
  - `RecipeReplay.plan(recipe: JsonValue.Obj?): Plan` (`RecipeReplay.kt:61`), `RecipeReplay.Plan.Patch(recipe)`, `RecipeReplay.Plan.Refused(reason)` and `Copy.REPLAY_NO_DOOR`.
  - `KitDiff.recipeName(recipe: JsonValue.Obj): String` (`KitDiff.kt:104`), `KitDiff.changes(from: Kit, to: Kit)` (`:43`), `KitDiff.headline(changes)` (`:89`) and `RecipeReplay.clip(recipe, kitName, slot)` (`RecipeReplay.kt:34`), called as `SidecarFuzzTest.kt:393-400` calls them.
  - `Terra.captureStriker` and `TerraPatch(…, Striker(…))` from Tasks 3 and 5. SidecarFuzzTest's own `fuzz`, `mutateTree` and `ROUNDS`.
- Produces: no production code. `:shell` needs no change: BREED rewrites macros through the patch's own JSON (`Breed.kt:283-288`), the shelf renames through it (`UserPresets.kt:307-311`), and DO IT AGAIN decodes through `PadRecipe` (`RecipeReplay.kt:84-87`). These tests pin that, so a later change cannot drop the striker silently.

- [ ] **Step 1: Write the tests**

Create `shell/src/test/kotlin/com/snipsnap/shell/TerraStrikerCarryTest.kt`:

```kotlin
package com.snipsnap.shell

import com.snipsnap.json.JsonValue
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Terra
import com.snipsnap.synth.TerraPatch
import com.snipsnap.synth.TerraVoice
import com.snipsnap.synth.Thump
import com.snipsnap.synth.ThumpVoice
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A struck TERRA pad through `:shell`'s rewrites (spec, "Data flow and
 * compatibility", "Drivers survive the house's own rewrites"; "Testing",
 * tests 4 and 5 with a striker only). Nothing in `:shell` changes for
 * this:
 * - BREED rewrites macros through the patch's own JSON;
 * - the presets shelf renames through it;
 * - DO IT AGAIN plans through PadRecipe.
 *
 * So each one carries the striker, or refuses a recipe it cannot read, by
 * construction. These tests pin that.
 */
class TerraStrikerCarryTest {

    private val head = Terra.captureStriker(Thump.render(ThumpVoice.KICK)) ?: error("a kick is not silent")
    private val striker = TerraPatch.Striker(head, 0.75f, "A01")
    private val struck = TerraPatch("STRUCK", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.3f, "DECAY" to 0.4f), striker)
    private val plain = TerraPatch("PLAIN", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.7f, "DECAY" to 0.9f))

    @Test
    fun `BREED keeps parent A's striker and never crosses HIT`() {
        val child = Breed.cross(PadRecipe(patch = struck), PadRecipe(patch = plain), Random(3)).patch as TerraPatch
        assertEquals(striker, child.striker, "the child lost or changed A's striker")
        val reverse = Breed.cross(PadRecipe(patch = plain), PadRecipe(patch = struck), Random(3)).patch as TerraPatch
        assertNull(reverse.striker, "a child of a plain A took B's striker")
        val softer = struck.copy(striker = TerraPatch.Striker(head, 0.25f, "A01"))
        val both = Breed.cross(PadRecipe(patch = struck), PadRecipe(patch = softer), Random(5)).patch as TerraPatch
        assertEquals(0.75f, both.striker?.hit, "HIT was crossed like a macro")
    }

    @Test
    fun `the presets shelf keeps a striker through save, reload and a rename`() {
        val root = java.nio.file.Files.createTempDirectory("terra-presets").toFile()
        try {
            val mine = struck.copy(name = "MY DRUM")
            UserPresets.save(root, mine, nowMillis = 1_700_000_000_000L)
            val back = UserPresets.read(root).single().patch
            assertEquals(mine, back, "the reloaded preset is not the saved one")
            assertContentEquals(mine.render().samples, back.render().samples, "the reloaded preset renders other bytes")
            val binned = requireNotNull(UserPresets.forget(root, "TERRA", "COMPOUND_MEMBRANE", "MY DRUM", nowMillis = 1_700_000_100_000L))
            UserPresets.save(root, plain.copy(name = "MY DRUM"), nowMillis = 1_700_000_200_000L)
            val restored = requireNotNull(UserPresets.unforget(root, binned))
            assertEquals("MY DRUM 2", restored.name, "the restore should land under a fresh name")
            assertEquals(striker, (restored.patch as TerraPatch).striker, "the rename dropped the striker")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `DO IT AGAIN replays a struck recipe whole, and refuses one it cannot read`() {
        val recipe = PadRecipe(patch = struck)
        assertEquals(RecipeReplay.Plan.Patch(recipe), RecipeReplay.plan(recipe.toJsonValue()))
        assertEquals(struck, Breed.recipeOf(recipe.toJsonValue())?.patch)
        val later = LinkedHashMap(struck.toJsonValue().entries).also { it["version"] = JsonValue.Num(3.0) }
        val unreadable = JsonValue.Obj(LinkedHashMap(recipe.toJsonValue().entries).also { it["patch"] = JsonValue.Obj(later) })
        assertEquals(RecipeReplay.Plan.Refused(Copy.REPLAY_NO_DOOR), RecipeReplay.plan(unreadable))
        assertNull(Breed.recipeOf(unreadable), "BREED read a recipe it cannot decode")
    }
}
```

In `shell/src/test/kotlin/com/snipsnap/shell/SidecarFuzzTest.kt`, after `import com.snipsnap.synth.PadRecipe` (line 26) add:

```kotlin
import com.snipsnap.synth.Terra
import com.snipsnap.synth.TerraPatch
import com.snipsnap.synth.TerraVoice
import com.snipsnap.synth.Thump
```

and insert before the class's closing brace (line 544):

```kotlin

    // ---- a struck TERRA recipe (docs/superpowers/specs/2026-09-30-terra-hit-bend-talk-design.md, "Testing", the rest) ----

    /** 882 head numbers that mutation can shorten, stringify or nest, a HIT, and a display label. */
    private fun struckTerraJson(): String = PadRecipe(
        patch = TerraPatch(
            "Seed", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.3f),
            TerraPatch.Striker(requireNotNull(Terra.captureStriker(Thump.render(ThumpVoice.SNARE))), 0.5f, "A02"),
        ),
    ).toJsonText()

    /**
     * The struck recipe under random mutation, through every reader the spec
     * names except R4's `TerraSheet.read`: `PadRecipe` (a parse or a typed
     * refusal), and `Breed.recipeOf`, `RecipeReplay.plan` and `KitDiff`
     * (never a throwable at all). The `KitDiff` calls are the ones
     * `the replay planner and the diff read any recipe without throwing`
     * makes. They run here rather than as a seventh seed in that test's
     * list, because a seventh seed would cut the existing six seeds' rounds
     * from 250 each to 214.
     */
    @Test
    fun `a struck TERRA recipe survives mutation, and every reader reads it or refuses it`() {
        val valid = struckTerraJson()
        fuzz("PadRecipe.fromJsonText (struck TERRA)", valid, seed = 39) { PadRecipe.fromJsonText(it) }
        val tree = Json.parse(valid)
        val rnd = Random(40)
        val untouched = KitPad(slot = 1, sampleFile = "a.wav")
        for (i in 0 until ROUNDS) {
            val mutant = mutateTree(tree, rnd) as? JsonValue.Obj
            try {
                Breed.recipeOf(mutant)
                RecipeReplay.plan(mutant)
                if (mutant != null) {
                    KitDiff.recipeName(mutant)
                    RecipeReplay.clip(mutant, "K", 1)
                    val changed = KitPad(slot = 1, sampleFile = "a.wav", recipe = mutant)
                    KitDiff.headline(KitDiff.changes(Kit("A", listOf(untouched)), Kit("A", listOf(changed))))
                    KitDiff.headline(KitDiff.changes(Kit("A", listOf(changed)), Kit("A", listOf(untouched))))
                }
            } catch (t: Throwable) {
                fail("struck TERRA round $i: threw ${t::class.simpleName}: ${t.message}\n${Json.write(mutant ?: JsonValue.Null)}")
            }
        }
    }

    /**
     * The spec's named hostile seeds for a driven recipe ("Testing", the
     * rest, Fuzz), built deterministically rather than left to random
     * mutation: a head one sample short, a head one sample long, NaN and
     * Infinity as strings in the head, an object where the head's array
     * belongs, and a version-1 patch carrying a striker. `PadRecipe` refuses
     * each with a `JsonException`, DO IT AGAIN refuses it, BREED reads
     * nothing, and the takes diff still names the pad without throwing.
     */
    @Test
    fun `the spec's hostile struck TERRA seeds are refused by every reader`() {
        val tree = Json.parse(struckTerraJson()).obj()
        val patch = tree.getValue("patch").obj()
        val st = patch.getValue("striker").obj()
        val head = st.getValue("head").arr()
        fun withStriker(edit: (LinkedHashMap<String, JsonValue>) -> Unit): JsonValue.Obj = JsonValue.Obj(
            LinkedHashMap(tree).also { r ->
                r["patch"] = JsonValue.Obj(LinkedHashMap(patch).also { p -> p["striker"] = JsonValue.Obj(LinkedHashMap(st).also(edit)) })
            },
        )
        val seeds = linkedMapOf(
            "a head one sample short" to withStriker { it["head"] = JsonValue.Arr(head.dropLast(1)) },
            "a head one sample long" to withStriker { it["head"] = JsonValue.Arr(head + JsonValue.Num(0.0)) },
            "NaN as a string in the head" to withStriker { it["head"] = JsonValue.Arr(head.toMutableList().also { l -> l[7] = JsonValue.Str("NaN") }) },
            "Infinity as a string in the head" to withStriker { it["head"] = JsonValue.Arr(head.toMutableList().also { l -> l[7] = JsonValue.Str("Infinity") }) },
            "an object where the head belongs" to withStriker { it["head"] = JsonValue.Obj(mapOf("0" to JsonValue.Num(0.5))) },
            "a version-1 patch carrying a striker" to JsonValue.Obj(
                LinkedHashMap(tree).also { r -> r["patch"] = JsonValue.Obj(LinkedHashMap(patch).also { p -> p["version"] = JsonValue.Num(1.0) }) },
            ),
        )
        for ((label, bad) in seeds) {
            try {
                PadRecipe.fromJsonText(Json.write(bad))
                fail("$label was read")
            } catch (e: JsonException) {
                // the typed refusal this test asks for
            }
            assertEquals(null, Breed.recipeOf(bad), "BREED read $label")
            assertTrue(RecipeReplay.plan(bad) is RecipeReplay.Plan.Refused, "DO IT AGAIN planned $label")
            assertTrue(KitDiff.recipeName(bad).isNotEmpty(), "the takes diff could not name $label")
        }
        assertEquals(6, seeds.size)
    }

    /** The four hostile labels the spec names: a number, an object, 25 characters, a control character. Each is refused typed, everywhere. */
    @Test
    fun `a struck TERRA recipe with a hostile label is refused by every reader`() {
        val tree = Json.parse(struckTerraJson()).obj()
        val patch = tree.getValue("patch").obj()
        val st = patch.getValue("striker").obj()
        val labels = listOf(JsonValue.Num(3.0), JsonValue.Obj(emptyMap()), JsonValue.Str("A".repeat(25)), JsonValue.Str("A\u0001"))
        for (label in labels) {
            val bad = JsonValue.Obj(
                LinkedHashMap(tree).also { r ->
                    r["patch"] = JsonValue.Obj(LinkedHashMap(patch).also { p -> p["striker"] = JsonValue.Obj(LinkedHashMap(st).also { it["from"] = label }) })
                },
            )
            try {
                PadRecipe.fromJsonText(Json.write(bad))
                fail("a struck recipe with from = ${Json.write(label)} was read")
            } catch (e: JsonException) {
                // the typed refusal this test asks for
            }
            assertEquals(null, Breed.recipeOf(bad), "BREED read from = ${Json.write(label)}")
            assertTrue(RecipeReplay.plan(bad) is RecipeReplay.Plan.Refused, "DO IT AGAIN planned from = ${Json.write(label)}")
        }
    }
```

In `shell/src/test/kotlin/com/snipsnap/shell/DegenerateDoorsTest.kt`, after `import com.snipsnap.synth.PadRecipe` (line 17) add:

```kotlin
import com.snipsnap.synth.Terra
import com.snipsnap.synth.TerraPatch
import com.snipsnap.synth.TerraVoice
```

and in `seedRecipes()` change line 202 from

```kotlin
        return listOf("era" to era, "character" to character, "smear" to smear, "keyed" to keyed, "patch" to patch, "robin x3" to robin)
```

to

```kotlin
        // DO IT AGAIN on a struck TERRA pad renders the striker from the recipe alone, on every degenerate destination.
        val struck = PadRecipe(
            patch = TerraPatch(
                "Seed", TerraVoice.COMPOUND_MEMBRANE, mapOf("TUNE" to 0.3f),
                TerraPatch.Striker(requireNotNull(Terra.captureStriker(DrumSynth.snare())), 0.5f, "A01"),
            ),
        ).toJsonValue()
        return listOf("era" to era, "character" to character, "smear" to smear, "keyed" to keyed, "patch" to patch, "struck terra" to struck, "robin x3" to robin)
```

- [ ] **Step 2: Run them**

Run: `./gradlew --no-daemon :shell:test --tests "com.snipsnap.shell.TerraStrikerCarryTest" --tests "com.snipsnap.shell.SidecarFuzzTest" --tests "com.snipsnap.shell.DegenerateDoorsTest"`
Expected: exit code 0 on the first run. Nothing in `:shell` changes, and these tests pin behaviour that Task 5's patch gives by construction.

- [ ] **Step 3: Prove the carry tests can fail**

Temporarily change `TerraPatch.toJsonValue` in `synth/src/main/kotlin/com/snipsnap/synth/TerraPatch.kt` so its second line reads `val s = striker ?: return base` followed immediately by `return base`. This makes the patch drop its striker from its own JSON.

Run: `./gradlew --no-daemon :shell:test --tests "com.snipsnap.shell.TerraStrikerCarryTest"`
Expected while the change is in place: FAIL in all three tests, including `the child lost or changed A's striker` and `the rename dropped the striker`. Revert, re-run, and expect exit code 0. Then run `git diff synth/src/main` and confirm it shows nothing from this step.

- [ ] **Step 4: Run the whole JVM suite**

`:shell` iterates recipes in many places: `BreedTest`, `ConventionTest`, `DegenerateDoorsTest` and the pad-sheet readers.

Run: `./gradlew --no-daemon test`, then `echo "exit=$?"`
Expected: `exit=0`. In a session with an Android SDK, run `./gradlew --no-daemon test -x :app:test`, which is what CI runs.

- [ ] **Step 5: Commit**

```bash
git add shell/src/test/kotlin/com/snipsnap/shell/TerraStrikerCarryTest.kt shell/src/test/kotlin/com/snipsnap/shell/SidecarFuzzTest.kt shell/src/test/kotlin/com/snipsnap/shell/DegenerateDoorsTest.kt
git commit -m "Pin that BREED, the presets shelf and DO IT AGAIN carry a TERRA striker or refuse it

None of them changes: each reads and rewrites the patch through its own JSON,
so a struck pad's striker rides along and an unreadable one is refused by
name. Shown to fail when the patch drops its striker from its JSON. A struck
recipe joins the sidecar fuzz through every reader, the takes diff included,
with the spec's named hostile seeds and the four hostile labels, and joins
the degenerate-door replay seeds."
```

---

### Task 7: The robustness sweep, the hostile renders, and the drive figures printed for R1's page

**Files:**
- Test: `synth/src/test/kotlin/com/snipsnap/synth/TerraTest.kt` (imports; append before the class's closing brace)

**Interfaces:**
- Consumes: `TerraStrikers.hostile()`, `TerraStrikers.head`, `TerraStrikers.TEN`, `TerraMeasure.cavityStage`, `TerraMeasure.tanhInput`, `TerraMeasure.msAbove`, `Terra.bankWith`, `Terra.bankStruckAt`, `Terra.upsample`, `Terra.renderStruck`, `TerraPatch(…, Striker(…))`, `TerraCases.all`, `LegacyTerraBank.render`, `Thump.scramble(voice: ThumpVoice, random: kotlin.random.Random): Map<String, Float>` (`Thump.kt:100`) and `Terra.macrosFor`.
- Produces: no production code. These are the spec's test 3 (render half) and test 6 (printed), plus Phase 0's G-P0f. The printed lines feed R1's page and decision 2.

- [ ] **Step 1: Write the tests**

In `synth/src/test/kotlin/com/snipsnap/synth/TerraTest.kt`, add to the imports:

```kotlin
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
```

and insert before the class's closing brace, after Task 4's tests:

```kotlin

    // ---------- robustness, and the drive HIT hands the cavity and BUZZ (spec "Testing", tests 3 and 6) ----------

    /** All ten strikers in the printed BUZZ and drive table when TERRA_FULL=1; the three named ones otherwise (CI minutes are metered). The cost test does not read it. */
    private val full = System.getenv("TERRA_FULL") == "1"

    @Test
    fun `every hostile source renders a real hit or today's body, on every voice`() {
        var renders = 0
        for (h in TerraStrikers.hostile()) {
            val head = Terra.captureStriker(h.snip)
            for (voice in TerraVoice.entries) {
                val plain = Terra.render(voice)
                val out = TerraPatch("Hostile", voice, emptyMap(), head?.let { TerraPatch.Striker(it, 1f) }).render()
                assertEquals(plain.frameCount, out.frameCount, "${h.label} on $voice changed the length")
                assertTrue(out.samples.all { it.isFinite() && abs(it) <= 1f }, "${h.label} on $voice: a sample is not finite or is over full scale")
                assertTrue(out.peak() > 0f, "${h.label} on $voice rendered silence")
                if (head == null) assertContentEquals(plain.samples, out.samples, "${h.label} on $voice: no striker, yet not today's body")
                renders++
            }
        }
        println("TERRA hostile renders: $renders at HIT 1")
    }

    /** One random striker source: a THUMP voice at scrambled macros, noise and sine bursts at any level after any lead silence, a single sample, NaN- and Inf-laced noise, or silence and DC. */
    private fun randomSource(r: Random, kind: Int): Snip {
        val rate = Dsp.RATE
        fun level() = Math.pow(10.0, -6.0 + 6.0 * r.nextDouble()).toFloat()
        fun lead() = FloatArray(r.nextInt(0, rate / 5))
        return when (kind) {
            0 -> {
                val v = ThumpVoice.entries[r.nextInt(ThumpVoice.entries.size)]
                Thump.render(v, Thump.scramble(v, r))
            }
            1 -> {
                val a = level()
                Snip(lead() + FloatArray(r.nextInt(rate / 200, rate / 3)) { (r.nextFloat() * 2f - 1f) * a }, 1, rate)
            }
            2 -> {
                val a = level()
                val hz = 40.0 + 7960.0 * r.nextDouble()
                Snip(lead() + FloatArray(r.nextInt(rate / 100, rate / 2)) { i -> (a * sin(2.0 * PI * hz * i / rate)).toFloat() }, 1, rate)
            }
            3 -> Snip(FloatArray(10_000).also { it[r.nextInt(10_000)] = level() }, 1, rate)
            4 -> {
                val a = level()
                Snip(FloatArray(rate / 5) { if (r.nextInt(50) == 0) Float.NaN else if (r.nextInt(200) == 0) Float.POSITIVE_INFINITY else (r.nextFloat() * 2f - 1f) * a }, 1, rate)
            }
            else -> when (r.nextInt(3)) {
                0 -> Snip(FloatArray(0), 1, rate)
                1 -> Snip(FloatArray(rate / 10), 1, rate)
                else -> Snip(FloatArray(rate / 10) { 0.4f }, 1, rate)
            }
        }
    }

    /**
     * Struck-motion M7.2's sweep: 200 random strikers on random TERRA pads at
     * random HIT, through the patch the phone would save. The capture rule's
     * promise: no render is non-finite, silent, off-length or over full scale
     * (M7.2: 0 / 0 / 0 with the rule, against 14 silent for the raw capture).
     * Fallbacks - a source with no hit to take - are counted and printed.
     */
    @Test
    fun `200 random strikers on random TERRA pads - never non-finite, silent, off-length or over full scale`() {
        var nonFinite = 0
        var silent = 0
        var offLength = 0
        var over = 0
        var fallbacks = 0
        for (i in 0 until 200) {
            val r = Random(9_000 + i)
            val voice = TerraVoice.entries[r.nextInt(TerraVoice.entries.size)]
            val macros = Terra.macrosFor(voice).associate { it.name to r.nextFloat() }
            val head = Terra.captureStriker(randomSource(r, i % 6))
            val hit = r.nextFloat()
            if (head == null) fallbacks++
            val plain = Terra.render(voice, macros)
            val out = TerraPatch("Sweep", voice, macros, head?.let { TerraPatch.Striker(it, hit) }).render()
            if (!out.samples.all { it.isFinite() }) nonFinite++
            if (out.peak() <= 0f) silent++
            if (out.frameCount != plain.frameCount) offLength++
            if (out.samples.any { abs(it) > 1f }) over++
            if (head == null) assertContentEquals(plain.samples, out.samples, "case $i: no striker, yet not today's body")
        }
        println("TERRA sweep: 200 cases, $nonFinite non-finite, $silent silent, $offLength off-length, $over over full scale, $fallbacks fell back to today's body (M7.2 had 15 on its own random set)")
        assertEquals(0, nonFinite, "non-finite renders")
        assertEquals(0, silent, "silent renders")
        assertEquals(0, offLength, "renders whose length moved")
        assertEquals(0, over, "renders over full scale")
    }

    /** Phase 0's G-P0f: HIT 1 with THUMP KICK on the forty cases keeps TERRA's length, finite and within full scale. */
    @Test
    fun `HIT 1 with a kick head keeps all forty cases finite, in range and the same length`() {
        val head = TerraStrikers.head("tkick")
        for (c in TerraCases.all) {
            val frozen = LegacyTerraBank.render(c.voice, c.macros)
            val out = Terra.renderStruck(c.voice, c.macros, head, 1f)
            assertEquals(frozen.frameCount, out.frameCount, c.label)
            assertTrue(out.samples.all { it.isFinite() && abs(it) <= 1f }, c.label)
        }
    }

    /**
     * Spec "Testing", test 6, and decision 2. The level match `s` matches
     * the body's peak, not the 75 Hz band-passed level the cavity's tanh
     * sees nor the 0.12 threshold BUZZ gates on. So under HIT, BUZZ follows
     * the striker. That is the default, and it is not owner-approved.
     *
     * This is printed, not pinned, until R1's page answers question 3. It is
     * then rewritten to pin each striker's ratio to today's within ±0.05
     * (BUZZ follows the striker), or to "within a stated ratio of today's"
     * (a band-passed level match).
     *
     * Phase 0's figures at BUZZ 1, THUMP KICK / THUMP SNARE / WRAITH WORD:
     * - cavity, today 55.6 ms above: 63.7 / 40.0 / 32.2 ms at HIT 0.5, and
     *   69.3 / 24.7 / 14.2 ms at HIT 1;
     * - bar, today 53.2 ms: 55.2 / 47.8 / 35.7 ms at HIT 0.5, and 54.2 /
     *   41.3 / 13.3 ms at HIT 1;
     * - the cavity's tanh input, today 0.3075: 0.3260 / 0.2468 / 0.2381 at
     *   HIT 0.5, and 0.3571 / 0.2049 / 0.1867 at HIT 1. At 0.357, tanh is
     *   within 4 % of linear.
     */
    @Test
    fun `BUZZ and the cavity's drive under HIT are printed until R1's page answers decision 2`() {
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val buzz = mapOf("BUZZ" to 1f)
        val mix = Terra.defaults(TerraVoice.RESONANT_CAVITY).getValue("CAVITY")
        val cavityToday = TerraMeasure.msAbove(TerraMeasure.cavityStage(Terra.bankWith(TerraVoice.RESONANT_CAVITY, buzz, null), mix, rate), 0.12f, rate)
        val barToday = TerraMeasure.msAbove(Terra.bankWith(TerraVoice.TUNED_BAR, buzz, null), 0.12f, rate)
        val tanhToday = TerraMeasure.tanhInput(Terra.bankWith(TerraVoice.RESONANT_CAVITY, emptyMap(), null), rate)
        println("TERRA drive today: cavity ${"%.1f".format(cavityToday)} ms and bar ${"%.1f".format(barToday)} ms above 0.12 at BUZZ 1, cavity tanh input ${"%.4f".format(tanhToday)} (Phase 0: 55.6, 53.2, 0.3075)")
        val ids = if (full) TerraStrikers.TEN.map { it.id } else listOf("tkick", "tsnare", "wraith")
        for (id in ids) {
            val x = Terra.upsample(TerraStrikers.head(id))
            for (hit in listOf(0.5f, 1f)) {
                val cavity = TerraMeasure.msAbove(TerraMeasure.cavityStage(Terra.bankStruckAt(TerraVoice.RESONANT_CAVITY, buzz, x, hit), mix, rate), 0.12f, rate)
                val bar = TerraMeasure.msAbove(Terra.bankStruckAt(TerraVoice.TUNED_BAR, buzz, x, hit), 0.12f, rate)
                val tanhIn = TerraMeasure.tanhInput(Terra.bankStruckAt(TerraVoice.RESONANT_CAVITY, emptyMap(), x, hit), rate)
                println(
                    "TERRA drive $id HIT $hit: cavity ${"%.1f".format(cavity)} ms (x${"%.2f".format(cavity / cavityToday)}), " +
                        "bar ${"%.1f".format(bar)} ms (x${"%.2f".format(bar / barToday)}), tanh input ${"%.4f".format(tanhIn)} (x${"%.2f".format(tanhIn / tanhToday)})",
                )
                assertTrue(cavity.isFinite() && bar.isFinite() && tanhIn.isFinite(), "$id HIT $hit: a drive figure is not finite")
            }
        }
    }

    /**
     * Render time beside Phase 0's two figures (spec, "Architecture", Cost;
     * record, Appendix A): HIT at 1.4-1.7x an unstruck render, capture work
     * counted, and the neutral path at 0.98-1.05x. Neutral here is today's
     * `Terra.render`, now routed through `renderWith`, against the frozen
     * `LegacyTerraBank.render`, which is the claim the null path rests on.
     * Median of 7 after 3 warm-ups, as Phase 0 timed it. Printed, not
     * asserted: timing on a shared runner is not a property of the code.
     */
    @Test
    fun `the cost of a struck render is printed`() {
        val head = TerraStrikers.head("tkick")
        fun medianMs(block: () -> Unit): Double {
            repeat(3) { block() }
            val times = (0 until 7).map { val t0 = System.nanoTime(); block(); (System.nanoTime() - t0) / 1e6 }.sorted()
            return times[3]
        }
        for (voice in TerraVoice.entries) {
            val legacy = medianMs { LegacyTerraBank.render(voice, emptyMap()) }
            val plain = medianMs { Terra.render(voice) }
            val struck = medianMs { Terra.renderStruck(voice, emptyMap(), head, 0.5f) }
            println("TERRA cost $voice: neutral ${"%.1f".format(plain)} ms against the frozen copy's ${"%.1f".format(legacy)} ms (${"%.2f".format(plain / legacy)}x; Phase 0 0.98-1.05x)")
            println("TERRA cost $voice: unstruck ${"%.1f".format(plain)} ms, HIT 0.5 ${"%.1f".format(struck)} ms (${"%.2f".format(struck / plain)}x; Phase 0 1.4-1.7x)")
        }
    }
```

- [ ] **Step 2: Run them**

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraTest" -i`
Expected: exit code 0. These pin behaviour Tasks 3–5 built, so they pass on the first run. Read and keep the lines starting `TERRA hostile renders`, `TERRA sweep`, `TERRA drive` and `TERRA cost`: the drive lines are the figures R1's page puts beside clip 10, and decision 2 is answered against them.

If the sweep finds a silent or non-finite render, that is a bug in `captureStriker` or `hitLevel`. Print the case number, its voice, macros, source kind and HIT, and find the cause before changing anything. Never exclude a case.

- [ ] **Step 3: Prove the capture floor is pinned**

Temporarily replace the line `if (headPeak < SILENT_HEAD_PEAK) return null` in `Terra.captureStriker` with nothing, so silence is normalised into a "hit".

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraCaptureTest.every hostile source captures a real hit or nothing, never a broken head"`
Expected while the change is in place: FAIL on `digital silence`. Revert, re-run, and expect PASS.

A normalised all-zero head is still zeros, so the render-side tests survive this mutation by the level-match fallback; only the capture half of test 3 can see it. Step 4 shows the render half can fail too.

- [ ] **Step 4: Prove the sweep and the hostile renders see a broken render and a broken capture**

First mutation, the render. Temporarily change the last line of `Terra.hitLevel` from

```kotlin
        return Array(run.size) { k -> FloatArray(m) { n -> ((1.0 - cd) + cd * s * run[k][n]).toFloat() } }
```

to

```kotlin
        return Array(run.size) { FloatArray(m) { Float.NaN } }
```

Run: `./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraTest.200 random strikers on random TERRA pads - never non-finite, silent, off-length or over full scale" --tests "com.snipsnap.synth.TerraTest.every hostile source renders a real hit or today's body, on every voice"`
Expected while the change is in place: both FAIL. The sweep fails on `non-finite renders`. The hostile test fails on its first row with a hit, `white noise peaking at exactly 1e-4 inside the head on COMPOUND_MEMBRANE: a sample is not finite or is over full scale`. Revert.

Second mutation, the capture's sanitising. Temporarily change, in `Terra.captureStriker`,

```kotlin
        val head = FloatArray(Fork.STRIKER_SAMPLES) { i -> x.getOrElse(start + i) { 0f }.let { v -> if (v.isFinite()) v else 0f } }
```

to

```kotlin
        val head = FloatArray(Fork.STRIKER_SAMPLES) { i -> x.getOrElse(start + i) { 0f } }
```

Run the same command.
Expected while the change is in place: both FAIL with an `IllegalArgumentException` whose message starts `striker head[`. The sweep throws on its first NaN-laced source (kind 4), and the hostile test throws on `a kick with a NaN at sample 10`: a NaN reaches the head, `Striker`'s init refuses it, and the patch the phone would save cannot be built. Revert, re-run, and expect exit code 0. Then run `git diff synth/src/main` and confirm it shows nothing from Steps 3 and 4.

- [ ] **Step 5: Print the full tables once**

Run: `TERRA_FULL=1 ./gradlew --no-daemon :synth:test --tests "com.snipsnap.synth.TerraTest.BUZZ and the cavity's drive under HIT are printed until R1's page answers decision 2" --rerun -i`
Expected: exit code 0, and a `TERRA drive` line for each of the ten strikers at HIT 0.5 and 1. Compare the extremes with Phase 0's ranges and keep them for the commit message:
- cavity BUZZ 0.47–1.14 at HIT 0.5 and 0.05–1.25 at HIT 1;
- bar BUZZ 0.50–1.14 at HIT 0.5 and 0.05–1.24 at HIT 1;
- tanh input 0.56–1.06 at HIT 0.5 and 0.19–1.16 at HIT 1.

- [ ] **Step 6: Commit**

```bash
git add synth/src/test/kotlin/com/snipsnap/synth/TerraTest.kt
git commit -m "Every hostile and random striker renders TERRA finite, in range and the right length

The hostile list on every voice at HIT 1, a 200-case random sweep through the
patch the phone saves, and HIT 1 on all forty cases. With no hit to take, the
pad is today's body byte for byte. BUZZ's and the cavity's drive under HIT are
printed beside Phase 0's figures for R1's page and decision 2, as is the
render cost."
```

Before running it, add a second `-m "..."` argument that holds the figures Steps 2 and 5 printed, copied from the test output: the sweep's counts and fallbacks, the drive lines for the three named strikers, the ten-striker extremes, and the struck and neutral cost multiples.

---

### Task 8: R1's listening page, then stop for the owner

**Files:**
- Modify: `synth/src/test/kotlin/com/snipsnap/synth/TerraAuditionGenerator.kt`:
  - `:20-21`: KDoc;
  - after `:70`: the `r1` branch;
  - before `:168`: `renderR1`.
- Modify: `synth/build.gradle.kts` (after the `generateTerraAudition` block, `:306-314`)

**Interfaces:**
- Consumes:
  - `TerraStrikers.ten(dir)`, `Terra.captureStriker`, `TerraPatch(…).copy(striker = …)` and `TerraPatch.Striker`.
  - `TerraKits.classic()` and `PadRecipe.fromJsonValue`.
  - `AuditionLevel.level(snip)` and `WavWriter.write(file, snip, WavWriter.BitDepth.PCM_16)`.
  - The generator's own `DOT` and `q`.
- Produces:
  - `testkit/terra-audition/R1/<id>.wav` (ten clips, mono 44.1 kHz, levelled, 16-bit) and `testkit/terra-audition/R1/manifest.json` (clips and three questions), which the orchestrator turns into R1's page.
  - The Gradle task `generateTerraR1Audition`.

- [ ] **Step 1: Confirm the output folder is ignored**

Run: `git check-ignore -v testkit/terra-audition/R1/x.wav`
Expected: `.gitignore:65:testkit/terra-audition/	testkit/terra-audition/R1/x.wav`.

- [ ] **Step 2: Write the R1 page's generator**

In `synth/src/test/kotlin/com/snipsnap/synth/TerraAuditionGenerator.kt`, change lines 20-21 from

```kotlin
 * listening artifact.
 */
```

to

```kotlin
 * listening artifact. With a second argument `r1` it renders R1's page
 * instead ([renderR1]): HIT in the engine, ten clips and three questions.
 */
```

After line 70 (`root.mkdirs()`), insert:

```kotlin
        if (args.getOrNull(1) == "r1") {
            renderR1(root)
            return
        }
```

Before the object's closing brace (line 168, after `q`), insert:

```kotlin

    /**
     * R1's page (docs/superpowers/specs/2026-09-30-terra-hit-bend-talk-design.md,
     * "Phasing and gates", "R1's page: HIT in the engine"): ten clips and
     * three questions under [root]/R1, with their own manifest. Every clip
     * is the render a struck pad's recipe gives, `TerraPatch.render`. Each
     * striker is captured by `Terra.captureStriker`, as the phone will
     * capture it. The BUZZ pad is A07 CAJON SLAP, the kit's most BUZZ: 0.75,
     * against A16's 0.60 and A04's 0.15.
     */
    private fun renderR1(root: File) {
        val dir = File(root, "R1").apply { mkdirs() }
        val sources = TerraStrikers.ten(File(root.parentFile, "Expansions/SnipSnap Factory/Samples")).associateBy { it.id }
        fun struck(id: String, hit: Float): TerraPatch.Striker {
            val s = sources.getValue(id)
            val head = Terra.captureStriker(s.snip) ?: error("${s.name} captured as silence")
            return TerraPatch.Striker(head, hit, s.name)
        }
        val a07 = requireNotNull(TerraKits.classic()[6]) { "the terra kit has no A07" }
        val buzzPad = PadRecipe.fromJsonValue(requireNotNull(a07.recipe) { "A07 carries no recipe" }).patch as TerraPatch
        check(buzzPad.name == "Cajon Slap" && buzzPad.macros["BUZZ"] == 0.75f) { "A07 is ${buzzPad.name} ${buzzPad.macros}, not the BUZZ pad this page names" }
        val membrane = TerraPatch("Membrane", TerraVoice.COMPOUND_MEMBRANE, emptyMap())
        val cavity = TerraPatch("Cavity", TerraVoice.RESONANT_CAVITY, emptyMap())
        val bell = TerraPatch("Bell", TerraVoice.CONICAL_BELL, emptyMap())
        val bar = TerraPatch("Bar", TerraVoice.TUNED_BAR, emptyMap())
        class R1Clip(val id: String, val label: String, val why: String, val patch: TerraPatch)
        val clips = listOf(
            R1Clip("01_membrane_today", "MEMBRANE $DOT TODAY", "the anchor", membrane),
            R1Clip("02_membrane_hit05_thump_snare", "MEMBRANE $DOT HIT .5 $DOT THUMP SNARE", "subtle, bright head", membrane.copy(striker = struck("tsnare", 0.5f))),
            R1Clip("03_membrane_hit1_thump_snare", "MEMBRANE $DOT HIT 1 $DOT THUMP SNARE", "strong, bright head", membrane.copy(striker = struck("tsnare", 1f))),
            R1Clip("04_membrane_hit1_factory_kick", "MEMBRANE $DOT HIT 1 $DOT FACTORY KICK", "strong, bass head (the soft attack)", membrane.copy(striker = struck("kick01", 1f))),
            R1Clip("05_cavity_today", "CAVITY $DOT TODAY", "the anchor", cavity),
            R1Clip("06_cavity_hit05_wraith_word", "CAVITY $DOT HIT .5 $DOT WRAITH WORD", "the cavity under a bright head at subtle (+8 dB; THUMP SNARE moves it +12)", cavity.copy(striker = struck("wraith", 0.5f))),
            R1Clip("07_bell_hit05_factory_clap", "BELL $DOT HIT .5 $DOT FACTORY CLAP", "a comb-shaped head on a bright voice", bell.copy(striker = struck("clap01", 0.5f))),
            R1Clip("08_bar_hit05_beatbox_rim", "BAR $DOT HIT .5 $DOT BEATBOX RIM", "the bar's brightest striker", bar.copy(striker = struck("bbrim", 0.5f))),
            R1Clip("09_kit_a07_today", "TERRA KIT A07 CAJON SLAP $DOT TODAY", "BUZZ as tuned (0.75, the kit's most)", buzzPad),
            R1Clip("10_kit_a07_hit1_factory_hat", "THE SAME PAD $DOT HIT 1 $DOT FACTORY HAT", "BUZZ following a bright, light head (decision 2's default)", buzzPad.copy(striker = struck("hat01", 1f))),
        )
        val questions = listOf(
            "Does HIT move from today through subtle to strong in steps you can hear, on every voice?",
            "Is any voice wrong at HIT .5 (the bell or bar going thin under a dark head), so that HIT needs a floor?",
            "Should the rattle follow the striker (clip 10, the default) or stay as today (decision 2)?",
        )
        check(clips.size <= 10 && questions.size <= 3) { "a gate page leads with at most ten clips and three questions" }
        val entries = clips.map { c ->
            WavWriter.write(File(dir, "${c.id}.wav"), AuditionLevel.level(c.patch.render()), WavWriter.BitDepth.PCM_16)
            "{\"id\":${q(c.id)},\"file\":${q("${c.id}.wav")},\"label\":${q(c.label)},\"why\":${q(c.why)}}"
        }
        File(dir, "manifest.json").writeText(
            "{\"page\":\"R1\",\"title\":\"HIT IN THE ENGINE\",\"clips\":[\n" + entries.joinToString(",\n") +
                "\n],\"questions\":[\n" + questions.joinToString(",\n") { q(it) } + "\n]}\n",
        )
        println("terra R1: ${clips.size} clips and ${questions.size} questions under ${dir.absolutePath}")
    }
```

- [ ] **Step 3: Register the Gradle task**

In `synth/build.gradle.kts`, after the `tasks.register<JavaExec>("generateTerraAudition") { … }` block (ending at line 314), add:

```kotlin

/** Render TERRA R1's listening clips (HIT in the engine) and manifest under testkit/terra-audition/R1/. See TerraAuditionGenerator.renderR1. */
tasks.register<JavaExec>("generateTerraR1Audition") {
    group = "distribution"
    description = "Render the TERRA R1 (HIT) listening clips and manifest under testkit/terra-audition/R1/."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.snipsnap.synth.TerraAuditionGenerator")
    workingDir = projectDir
    args("${rootDir}/testkit/terra-audition", "r1")
}
```

- [ ] **Step 4: Run it**

Run: `./gradlew --no-daemon :synth:generateTerraR1Audition`, then `echo "exit=$?"; ls testkit/terra-audition/R1/; grep -c '"id"' testkit/terra-audition/R1/manifest.json`
Expected:
- `exit=0`;
- `terra R1: 10 clips and 3 questions` in the output;
- ten `.wav` files and `manifest.json` in the listing;
- the count `10`.

Also run `./gradlew --no-daemon :synth:generateTerraAudition` and confirm exit code 0. The existing page still renders unchanged.

- [ ] **Step 5: Commit**

```bash
git add synth/src/test/kotlin/com/snipsnap/synth/TerraAuditionGenerator.kt synth/build.gradle.kts
git commit -m "The TERRA R1 listening page: HIT from today through subtle to strong, ten clips

Renders under testkit/terra-audition/R1/ with a manifest of its own: the
membrane today and struck by a snare at 0.5 and 1 and by a factory kick at
1, the cavity, the bell and the bar at 0.5, and the kit's BUZZ pad today and
struck by a factory hat. Three questions: steps you can hear, a floor at
0.5, and whether the rattle follows the striker."
```

- [ ] **Step 6: Run the whole JVM suite, open the PR, and stop for R1's gate**

Run: `./gradlew --no-daemon test`, then `echo "exit=$?"`
Expected: `exit=0`. In a session with an Android SDK, run `./gradlew --no-daemon test -x :app:test`.

Push the branch and open a PR against `claude/mobile-mpc-drum-sampler-t58x74`, the default branch. Give it a title in plain declarative prose. The body should carry:
- the printed measurements from Tasks 3, 4 and 7;
- the note that test 6 is printed, not pinned, pending R1's page question 3;
- the note that the listening page follows from `testkit/terra-audition/R1/manifest.json`.

**Stop here for R1's gate.** The orchestrator publishes the page from `testkit/terra-audition/R1/`. The owner's three answers decide what happens next:
1. Whether HIT's steps read on every voice.
2. Whether HIT needs a floor at 0.5. If it does, the floor is a new decision and its own plan.
3. Decision 2. With "follow" (the default), test 6 is rewritten to pin each named striker's BUZZ and tanh ratio to today's within ±0.05, using the printed values. With "stay", a band-passed level match on the cavity is designed and measured before test 6 is pinned to "within a stated ratio of today's".

R2 may run alongside. R3 (BEND and TALK) and R4 (the group and the chooser) do not start before this verdict.

---

## Self-review

- **Spec coverage.** Every R1 test has a task; R2 (Engine entry, presets, scramble, starter kit), R3 (`bend`, `talk`) and R4 (chooser, `:app`) are out.

  | Spec | Where |
  |---|---|
  | Test 1, and the 16 pads' bytes pinned as text (A07 and A15 literally) | Tasks 1, 2 and 4 |
  | Test 2, the Phase-0 reproduction and the T2 recipe-provenance check | Task 4 |
  | Test 3 (capture half; render half and the 200-case sweep) | Tasks 3 and 7 |
  | Tests 4 and 5, striker only; a struck pad regenerating from `kit.json` | Tasks 5 and 6 |
  | Test 6, printed until R1's page answers decision 2 | Task 7 |
  | Test 7 | Task 2 |
  | Test 8, with HIT 1's attack means beside Phase 0's | Task 4 |
  | Test 11 | Tasks 1 and 5 |
  | Fuzz: the spec's seeds, every reader including `KitDiff` | Task 6 |
  | Render time, struck and neutral | Task 7 |
  | R1's page | Task 8 |

- **Placeholders.** None: no `TBD`, `TODO`, "similar to" or angle-bracket fill-ins. Each commit that should carry printed figures says so in a sentence after its command.
- **Type consistency.** `Body`, `BankInputs`, `renderWith`, `bankWith` and `PITCH_MIN`/`PITCH_MAX` come from Task 2; `captureStriker` and `SILENT_HEAD_PEAK` from Task 3; `upsample`, `hitLevel`, `renderStruck`, `renderStruckAt` and `bankStruckAt` from Task 4; `TerraPatch.Striker` and `Terra.render(voice, macros, drivers)` from Task 5. Every other symbol was read at the head with its visibility: `Patches.decode`/`toJsonValue` and `Fork.excite` are internal to `:synth`, `Breed.cross` is internal to `:shell` and takes `java.util.Random`, `Thump.scramble` takes `kotlin.random.Random`, and `KitDiff`, `RecipeReplay.clip`, `KitAssembler.assembleArranged` and `KitStore.load` match their files. `hitInputs` uses a typed local `val` for its lambda, because braces after `else` parse as a block, not a lambda.
- **Review Focus.** Each line's pinning test is in the task that owns the code: 48 kHz truncation and cancelling stereo in Task 3, the CLACKed bell and the empty head in Task 4, the retune in Task 5.
- **Line numbers** are the head's (`01b1ff9f`). `Terra.kt`, `TerraPatch.kt`, `Patches.kt`, `Fork.kt`, `TerraKits.kt`, `Thump.kt` and `Vox.kt` are byte-identical to the spec's `75b550c1`. Task 2's later steps give both the head's numbers and the numbers after the earlier steps' inserts.
- **Departures from the spec's wording, for the owner:** the pitch factor's grouping follows `P0Bank`, not the spec's left-to-right text; the full tables use an environment variable, not a system property; spec test 3's "white noise at 1e-4 and below falls back" disagrees with the capture rule at exactly 1e-4, and the plan follows the rule; the spec's "phase0 §2.4" for the strikers is §3.1.
