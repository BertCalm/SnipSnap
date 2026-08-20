package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.pow
import kotlin.random.Random

/**
 * PLUCK — Karplus-Strong physical modeling, 1983 and era-correct.
 *
 * The best fun-per-parameter ratio in synthesis: a burst of noise in a tuned
 * feedback loop *is* a plucked string, and the one knob that matters (DAMP)
 * always sounds good. Voices are body characters — the loop brightness, the
 * exciter colour and the ring length are voiced per instrument — and macros
 * refine within that character, never out of it.
 *
 * TUNE snaps to semitones across two octaves from the voice's root: pads get
 * notes, not frequencies, which is what makes a pluck kit playable as music.
 */
enum class PluckVoice { KALIMBA, NYLON, HARP, KOTO }

object Pluck {

    /** Semitone span of the TUNE macro. Root at 0, two octaves up at 1. */
    const val TUNE_SEMITONES = 24

    fun macrosFor(voice: PluckVoice): List<MacroSpec> = when (voice) {
        PluckVoice.KALIMBA -> listOf(
            MacroSpec("TUNE", 0.5f), MacroSpec("DAMP", 0.6f), MacroSpec("PICK", 0.55f),
            MacroSpec("DOUBLE", 0.1f),
        )
        PluckVoice.NYLON -> listOf(
            MacroSpec("TUNE", 0.4f), MacroSpec("DAMP", 0.45f), MacroSpec("PICK", 0.4f),
            MacroSpec("DOUBLE", 0.15f),
        )
        PluckVoice.HARP -> listOf(
            MacroSpec("TUNE", 0.55f), MacroSpec("DAMP", 0.2f), MacroSpec("PICK", 0.6f),
            MacroSpec("DOUBLE", 0.2f),
        )
        PluckVoice.KOTO -> listOf(
            MacroSpec("TUNE", 0.45f), MacroSpec("DAMP", 0.4f), MacroSpec("PICK", 0.75f),
            MacroSpec("DOUBLE", 0.45f),
        )
    }

    fun defaults(voice: PluckVoice): Map<String, Float> =
        macrosFor(voice).associate { it.name to it.default }

    fun scramble(voice: PluckVoice, random: Random): Map<String, Float> =
        macrosFor(voice).associate { it.name to random.nextFloat() }

    /** The snapped note frequency the TUNE macro lands on for [voice]. */
    fun frequencyFor(voice: PluckVoice, tune: Float): Float {
        val root = when (voice) {
            PluckVoice.KALIMBA -> 220f
            PluckVoice.NYLON -> 110f
            PluckVoice.HARP -> 165f
            PluckVoice.KOTO -> 147f
        }
        val semis = Math.round(tune.coerceIn(0f, 1f) * TUNE_SEMITONES)
        return root * 2f.pow(semis / 12f)
    }

    fun render(voice: PluckVoice, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults(voice).toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val freq = frequencyFor(voice, m.getValue("TUNE"))
        val damp = m.getValue("DAMP")
        val pick = m.getValue("PICK")
        val double = m.getValue("DOUBLE")

        // The body characters. loopHz is the low-pass inside the feedback
        // loop (what makes a kalimba woody and a harp glassy); pickLo/pickHi
        // bound the exciter colour; ring is the undamped tail length.
        val loopHz: Float
        val pickLo: Float
        val pickHi: Float
        val ring: Float
        when (voice) {
            PluckVoice.KALIMBA -> { loopHz = 2600f; pickLo = 900f; pickHi = 4500f; ring = 0.9f }
            PluckVoice.NYLON -> { loopHz = 3400f; pickLo = 1200f; pickHi = 6000f; ring = 1.1f }
            PluckVoice.HARP -> { loopHz = 5200f; pickLo = 1800f; pickHi = 9000f; ring = 1.3f }
            PluckVoice.KOTO -> { loopHz = 4200f; pickLo = 1500f; pickHi = 8000f; ring = 1.0f }
        }

        val seconds = (ring * Dsp.lin(1f - damp, 0.35f, 1f)).coerceAtMost(1.35f)
        val out = ks(freq, seconds, damp, loopHz, Dsp.expMap(pick, pickLo, pickHi), seed = 11)
        if (double > 0.01f) {
            // The 12-string trick: a second, slightly sharp string under the
            // first. Detune grows with the macro so it goes chorus -> honky.
            val det = ks(
                freq * Dsp.lin(double, 1.002f, 1.012f), seconds, damp, loopHz,
                Dsp.expMap(pick, pickLo, pickHi), seed = 23,
            )
            val g = double * 0.7f
            for (i in out.indices) out[i] += det[i] * g
        }

        Dsp.normalize(out)
        Dsp.fadeTail(out)
        return Snip(out, channels = 1, sampleRate = RATE)
    }

    /**
     * The 1983 algorithm itself: one period of filtered noise, then a delay
     * line feeding back through a low-pass. DAMP closes the loop filter and
     * pulls the feedback gain down together — one knob, two parameters,
     * always musical.
     */
    private fun ks(
        freq: Float,
        seconds: Float,
        damp: Float,
        bodyLoopHz: Float,
        pickHz: Float,
        seed: Int,
    ): FloatArray {
        val n = (RATE / freq).toInt().coerceAtLeast(2)
        val out = FloatArray((seconds * RATE).toInt().coerceAtLeast(n + 2))

        val noise = Dsp.Noise(seed)
        val pickLp = Dsp.OnePole()
        val head = minOf(n, out.size)
        for (i in 0 until head) out[i] = pickLp.lp(noise.next(), pickHz)
        // Zero-mean the exciter: the loop filter passes DC untouched, so any
        // net offset in the burst survives as a sub-thump long after the
        // string content is damped away — a dark pluck decayed into a fake
        // kick until this subtraction.
        var mean = 0f
        for (i in 0 until head) mean += out[i]
        mean /= head
        for (i in 0 until head) out[i] -= mean

        val loopLp = Dsp.OnePole()
        val loopHz = bodyLoopHz * Dsp.lin(1f - damp, 0.35f, 1.6f)
        val fb = Dsp.lin(1f - damp, 0.94f, 0.998f)
        for (i in n + 1 until out.size) {
            out[i] += fb * loopLp.lp(0.5f * (out[i - n] + out[i - n - 1]), loopHz)
        }
        return out
    }
}
