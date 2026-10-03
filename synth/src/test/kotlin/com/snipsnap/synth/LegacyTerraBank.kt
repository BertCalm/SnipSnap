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
