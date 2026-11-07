package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * SCRATCH AUDITION ADAPTER, NOT SHIPPED DSP. The production ConfluenceVoice source alongside
 * this file is unmodified. Its epoch/grain PSOLA stage is retained here with one explicit
 * separation: source.periodAt controls the input grain only; a new targetHz clock determines
 * output pulse spacing. This prevents A's recorded F0 motion from silently remaining B's clock.
 * No new breath, text, trained model, or real second person is generated.
 */
internal object IndependentSinger {
    const val VERSION = 1
    const val FORMANT_SCALE = 1.08f
    const val DURATION_RATIO = .992f

    /** Only elapsed time and B's constant native-median reference enter the output F0 curve. */
    fun targetHz(seconds: Double, baseHz: Double): Double {
        val grown = 1 - exp(-max(0.0, seconds - .12) * 6)
        val cents = 8.5 * grown * sin(2 * PI * (5.23 * seconds + .31)) +
            3.5 * sin(2 * PI * (.61 * seconds + .07)) +
            2.0 * sin(2 * PI * (1.37 * seconds + .62))
        return baseHz * 2.0.pow(cents / 1200)
    }

    @JvmOverloads
    fun render(source: ConfluenceVoice.Analysis, baseHz: Double, formantScale: Float = FORMANT_SCALE): FloatArray {
        require(baseHz in 130.0..300.0) { "This audition qualifies only its comfortable native register." }
        require(formantScale in .9f..1.1f)
        val length = (source.samples.size * DURATION_RATIO).roundToInt()
        val original = FloatArray(length) { i -> read(source.samples, i / DURATION_RATIO.toDouble()) }
        val out = FloatArray(length)
        val weight = FloatArray(length)
        var at = 0.0
        var epochIndex = 0
        while (at < length + 700) {
            val input = at / DURATION_RATIO
            while (epochIndex + 1 < source.epochs.size && abs(source.epochs[epochIndex + 1] - input) < abs(source.epochs[epochIndex] - input)) epochIndex++
            val center = source.epochs.getOrElse(epochIndex) { input.roundToInt() }
            val inputPeriod = source.periodAt(input).coerceIn(72.0, 680.0)
            val radius = ceil(inputPeriod / formantScale).toInt()
            for (offset in -radius..radius) {
                val destination = at.roundToInt() + offset
                if (destination !in out.indices) continue
                val w = (.5 + .5 * cos(PI * offset / radius)).toFloat()
                out[destination] += read(source.samples, center + offset * formantScale.toDouble()) * w
                weight[destination] += w
            }
            // Independent output clock, unlike production period / pitchRatio / vibrato.
            at += Confluence.RATE / targetHz(at / Confluence.RATE, baseHz)
        }
        for (i in out.indices) {
            val blend = source.voicedAt(i / DURATION_RATIO.toDouble())
            val pitched = if (weight[i] > 1e-4f) out[i] / weight[i] else original[i]
            out[i] = blend * pitched + (1 - blend) * original[i]
        }
        // The separately declared finite-vowel envelopes are applied by the audition generator.
        return out
    }

    private fun read(samples: FloatArray, at: Double): Float {
        if (at < 0 || at >= samples.lastIndex) return if (at == samples.lastIndex.toDouble()) samples.last() else 0f
        val i = at.toInt(); val f = (at - i).toFloat()
        return samples[i] + f * (samples[i + 1] - samples[i])
    }
}
