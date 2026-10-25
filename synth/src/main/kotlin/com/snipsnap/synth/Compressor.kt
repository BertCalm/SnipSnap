package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * COMPRESSOR — sustain in a knob: the body pressed down, the decay held up,
 * the note still ringing where it used to be gone. The SUSTAIN chip.
 *
 * SQUASH next door is the punch compressor (a shallow threshold, a fixed
 * 90 ms release); this one is built for a one-shot's tail. Peak matched like
 * every pass here, and that is where its make-up comes from: it presses the
 * loud body down, the peak-match brings the peak back, and everything under
 * the body comes up with it.
 *
 * AMOUNT is threshold and ratio together (10 dB under the pad's loudest level
 * at 2:1, up to 40 dB at 10:1). ATTACK is how fast the squeeze lands and how
 * much room the hit keeps above the body. RECOVER is how fast the sound comes
 * back up after the hit: up means sooner, so more sustain.
 *
 * Designed and prototyped in docs/superpowers/specs/2026-10-23-compressor-section-design.md
 * (the prototype beside it is the reference these constants were measured on).
 * The detector-then-gain-smoother form follows Giannoulis, Massberg and Reiss,
 * "Digital Dynamic Range Compressor Design: A Tutorial and Analysis", JAES 60(6), 2012.
 */
object Compressor {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("AMOUNT", 0.5f),                    // threshold depth and ratio together; 0 is a copy
        MacroSpec("ATTACK", 0.5f, neutral = 0.5f),    // squeeze speed and the hit's headroom
        MacroSpec("RECOVER", 0.66f, neutral = 0.66f), // how fast the sound comes back up: up is more sustain
    )

    /** The level detector's window: short, so the gain stage sees the decay, not a smoothed copy of it. */
    const val LEVEL_SECONDS = 0.005f

    /** How far ahead the gain is applied (SQUASH's lookahead): the squeeze is down before the hit arrives. */
    const val LOOKAHEAD_SECONDS = 0.002f

    /** Under the pad's loudest level by this much, the make-up is taken back out, over [FLOOR_TAPER_DB]. */
    const val FLOOR_DB = 50f
    const val FLOOR_TAPER_DB = 12f

    /** The most the escaped hit may stand over the compressed body, at ATTACK 1. */
    const val HEADROOM_MAX_DB = 12f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** The threshold, in dB under the pad's loudest level, at [amount]. */
    fun thresholdDepthDb(amount: Float): Float = Dsp.lin(amount, 10f, 40f)

    fun ratio(amount: Float): Float = Dsp.lin(amount, 2f, 10f)

    /** 0.5 ms to 40 ms. */
    fun attackSeconds(attack: Float): Float = Dsp.expMap(attack, 0.0005f, 0.04f)

    /**
     * 4 s at RECOVER 0 (the note fades almost on its own) down to 8 ms at 1 (the most sustain).
     * It stops at 8 ms because a faster recovery loses sustain on a snare (the prototype: 186 ms
     * of ring at 1.5 ms against 258 at 8 ms); 66 ms at the default.
     */
    fun recoverSeconds(recover: Float): Float = Dsp.expMap(recover, 4f, 0.008f)

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)

        val amount = m.getValue("AMOUNT")
        if (amount <= 0.001f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)
        var inPeak = 0f
        for (v in snip.samples) { val a = if (v < 0) -v else v; if (a > inPeak) inPeak = a }
        if (inPeak <= 1e-9f) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val rate = snip.sampleRate
        val frames = snip.frameCount
        val channels = snip.channels
        val attack = m.getValue("ATTACK")
        val depth = thresholdDepthDb(amount).toDouble()
        val slope = 1.0 - 1.0 / ratio(amount)
        val threshold = -depth

        // One linked detector for the whole frame, fed by the louder channel (SQUASH's reason: per-channel
        // envelopes against a shared threshold narrow the image as the hit lands), in dB under the loudest level.
        val meanSquare = DoubleArray(frames)
        val kLevel = 1.0 - exp(-1.0 / (LEVEL_SECONDS * rate))
        var e = -1.0
        var loudest = 0.0
        for (f in 0 until frames) {
            var a = 0.0
            for (ch in 0 until channels) {
                val x = snip.samples[f * channels + ch].toDouble()
                if (x * x > a) a = x * x
            }
            e = if (e < 0) a else e + kLevel * (a - e)
            meanSquare[f] = e
            if (e > loudest) loudest = e
        }
        val loudestDb = 10.0 * log10(maxOf(loudest, 1e-18))

        // The static curve, then the floor: under it the make-up the peak-match will add is taken back out,
        // so silence, noise and the pad's own end fade stay where they were.
        val makeup = depth * slope
        val target = DoubleArray(frames)
        for (f in 0 until frames) {
            val level = 10.0 * log10(maxOf(meanSquare[f], 1e-18)) - loudestDb
            val reduction = if (level > threshold) (threshold - level) * slope else 0.0
            val keep = ((level + FLOOR_DB + FLOOR_TAPER_DB) / FLOOR_TAPER_DB).coerceIn(0.0, 1.0)
            target[f] = reduction - makeup * (1.0 - keep)
        }

        // The gain smoothed in dB: ATTACK while it falls, RECOVER while it rises.
        val kAttack = 1.0 - exp(-1.0 / (attackSeconds(attack) * rate))
        val kRecover = 1.0 - exp(-1.0 / (recoverSeconds(m.getValue("RECOVER")) * rate))
        val gainDb = DoubleArray(frames)
        var g = 0.0
        for (f in 0 until frames) {
            val t = target[f]
            g += (if (t < g) kAttack else kRecover) * (t - g)
            gainDb[f] = g
        }

        val look = (LOOKAHEAD_SECONDS * rate).toInt()
        val out = FloatArray(snip.samples.size)
        val framePeak = DoubleArray(frames)
        for (f in 0 until frames) {
            val gain = 10.0.pow(gainDb[minOf(f + look, frames - 1)] / 20.0)
            var p = 0.0
            for (ch in 0 until channels) {
                val i = f * channels + ch
                val y = snip.samples[i] * gain
                out[i] = y.toFloat()
                val a = if (y < 0) -y else y
                if (a > p) p = a
            }
            framePeak[f] = p
        }

        // A lookahead limiter in front of the peak-match. Without it the hit's first milliseconds escape
        // the squeeze, stay the peak, and the peak-match has no make-up to give: the tail never rises.
        // Its ceiling is the compressor's own output at the loudest level, plus ATTACK's headroom and a
        // sine's crest, so ATTACK decides how far the hit may stand over the body.
        val ceiling = sqrt(loudest) * 10.0.pow((threshold * slope + HEADROOM_MAX_DB * attack + 3.0) / 20.0)
        val limitLook = (0.001f * rate).toInt()
        val need = DoubleArray(frames) { f -> if (framePeak[f] > ceiling) ceiling / framePeak[f] else 1.0 }
        val kLimitRecover = 1.0 - exp(-1.0 / (0.03 * rate))
        var lg = 1.0
        for (f in 0 until frames) {
            var ahead = 1.0
            for (j in f..minOf(f + limitLook, frames - 1)) if (need[j] < ahead) ahead = need[j]
            lg = if (ahead < lg) ahead else lg + kLimitRecover * (ahead - lg)
            if (lg < 1.0) for (ch in 0 until channels) out[f * channels + ch] = (out[f * channels + ch] * lg).toFloat()
        }

        var outPeak = 0f
        for (v in out) { val a = if (v < 0) -v else v; if (a > outPeak) outPeak = a }
        if (outPeak > 1e-9f) {
            val k = inPeak / outPeak
            for (i in out.indices) out[i] = (out[i] * k).coerceIn(-1f, 1f)
        }
        return Snip(out, channels, rate)
    }
}
