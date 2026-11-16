package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonException
import com.snipsnap.json.JsonValue
import kotlin.math.abs

/** Optional bass treatments. Off is an exact bypass, including the saved recipe. */
object BristleEffects {
    data class Settings(val compress: Float = 0f, val fuzz: Float = 0f) {
        init {
            require(compress.isFinite() && compress in 0f..1f)
            require(fuzz.isFinite() && fuzz in 0f..1f)
        }
        val isOff get() = compress.toRawBits() == 0 && fuzz.toRawBits() == 0
        fun toJsonValue() = JsonValue.Obj(linkedMapOf(
            "version" to JsonValue.Num(1.0),
            "compress" to JsonValue.Num(compress.toDouble()),
            "fuzz" to JsonValue.Num(fuzz.toDouble()),
        ))
        companion object {
            fun fromJsonValue(value: JsonValue): Settings {
                val o = value.obj()
                if (o.keys != setOf("version", "compress", "fuzz") || o.getValue("version").int() != 1)
                    throw JsonException("Unsupported BRISTLE effects recipe")
                fun amount(key: String): Float {
                    val v = o.getValue(key).num()
                    if (!v.isFinite() || v !in 0.0..1.0) throw JsonException("Invalid BRISTLE effect $key")
                    return v.toFloat()
                }
                return Settings(amount("compress"), amount("fuzz"))
            }
        }
    }

    /** Bass-body compression uses the existing RMS detector and lookahead gain stage.
     * Parallel blending keeps COMP continuous from bypass; peak matching preserves touch gain. */
    internal fun compress(snip: Snip, amount: Float): Snip {
        if (amount == 0f) return snip
        val wet = Compressor.process(snip, mapOf(
            "AMOUNT" to (.15f + .6f * amount), "ATTACK" to .28f, "RECOVER" to .72f,
        ))
        // Time-varying gain can give a low, asymmetric bass gesture a DC offset.
        // Clean only the wet path, below the bass register, then restore its peak;
        // blending toward bypass must not impose a fixed filter on the dry note.
        val dc = Array(snip.channels) { Dsp.FixedOnePole(5f, snip.sampleRate) }
        var wetPeak = 0f
        var inPeak = 0f
        for (i in wet.samples.indices) {
            val x = wet.samples[i]
            wet.samples[i] = x - dc[i % snip.channels].lp(x)
            wetPeak = maxOf(wetPeak, abs(wet.samples[i]))
            inPeak = maxOf(inPeak, abs(snip.samples[i]))
        }
        val match = if (wetPeak > 1e-9f) inPeak / wetPeak else 1f
        return Snip(FloatArray(snip.samples.size) { i ->
            snip.samples[i] + amount * (wet.samples[i] * match - snip.samples[i])
        }, snip.channels, snip.sampleRate)
    }

    /** The clean fundamental never enters this path. First-order ADAA rounds the
     * biased clipper's hard corners before the 4x house band-limit/decimator. */
    internal class Fuzz(private val amount: Float, rate: Int) {
        private val low = Dsp.FixedOnePole(120f, rate)
        private val dc = Dsp.FixedOnePole(20f, rate)
        private val tone1 = Dsp.FixedOnePole(3500f, rate)
        private val tone2 = Dsp.FixedOnePole(3500f, rate)
        private var previous = .18

        private fun integral(x: Double): Double = when {
            x > 1.0 -> x - .5
            x < -1.0 -> -x - .5
            else -> .5 * x * x
        }

        fun process(edge: Double): Double {
            val high = edge - low.lp(edge.toFloat())
            val x = high * (8.0 + 16.0 * amount) + .18
            val delta = x - previous
            val clipped = if (abs(delta) < 1e-8) ((x + previous) * .5).coerceIn(-1.0, 1.0)
                else (integral(x) - integral(previous)) / delta
            previous = x
            val biased = (clipped - .18).toFloat()
            val cleanDc = biased - dc.lp(biased)
            val rasp = tone2.lp(tone1.lp(cleanDc))
            return edge + .45 * amount * rasp
        }
    }
}
