package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.json.JsonValue
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The Time Machine — era-faithful sound as the *specific math of specific
 * machines*, not a crush knob. Each era is a deterministic Snip→Snip chain
 * built from exact primitives: bit truncation, character resampling
 * (deliberately un-anti-aliased, because that grit *is* the sound),
 * non-linear companding, one-pole filters, seeded wobble.
 *
 * Honest "inspired-by" language throughout: these implement the documented
 * signal-path arithmetic (12 bits, 26.04 kHz, µ-law-style companding), not
 * a claim of circuit-level cloning. `amount` interpolates each chain from
 * transparent toward the machine's full character; every application
 * returns the recipe that made it, so an aged pad stays re-ageable and
 * undoable like everything else in a kit.
 */
object Eras {

    val names: List<String> = listOf("sp1200", "mpc60", "tape", "phone")

    data class Aged(val snip: Snip, val recipe: JsonValue.Obj)

    fun apply(name: String, snip: Snip, amount: Float = 1f): Aged {
        val aged = process(name, snip, amount)
        return Aged(
            aged,
            JsonValue.Obj(
                linkedMapOf(
                    "era" to JsonValue.Str(name),
                    "amount" to JsonValue.Num(amount.toDouble()),
                ),
            ),
        )
    }

    fun process(name: String, snip: Snip, amount: Float = 1f): Snip {
        require(amount > 0f && amount <= 1f) { "amount is (0, 1], got $amount" }
        return when (name) {
            "sp1200" -> sp1200(snip, amount)
            "mpc60" -> mpc60(snip, amount)
            "tape" -> tape(snip, amount)
            "phone" -> phone(snip, amount)
            else -> throw IllegalArgumentException(
                "unknown era '$name' - try one of: ${names.joinToString(", ")}",
            )
        }
    }

    // ---- the machines ------------------------------------------------------

    /**
     * The drum machine that defined a decade of low-end: 12-bit samples at
     * 26.04 kHz. The chain: decimate *without* an anti-alias filter (the
     * folding is the crunch), truncate — not round — to 12 bits, and come
     * back up zero-order-hold (the stair-step images are the sizzle).
     */
    private fun sp1200(snip: Snip, amount: Float): Snip {
        val rate = lerp(snip.sampleRate.toFloat(), 26_040f, amount).toInt()
        val bits = lerp(16f, 12f, amount).toInt()
        val down = resampleCharacter(snip, rate, hold = false)
        val crushed = quantizeTruncate(down, bits)
        return resampleCharacter(crushed, snip.sampleRate, hold = true)
    }

    /**
     * The MPC60's path: 12-bit storage behind a non-linear companding
     * DAC at 40 kHz, with a gentle top-end from its filters. Compand,
     * truncate, expand, roll off.
     */
    private fun mpc60(snip: Snip, amount: Float): Snip {
        val bits = lerp(16f, 12f, amount).toInt()
        val mu = lerp(1f, 255f, amount)
        val companded = Snip(
            FloatArray(snip.samples.size) { i ->
                val c = compand(snip.samples[i], mu)
                expand(quantize(c, bits), mu)
            },
            snip.channels, snip.sampleRate,
        )
        val cutoff = lerp(20_000f, 11_000f, amount)
        return onePoleLowpass(companded, cutoff)
    }

    /**
     * A loved cassette: soft saturation, slow wow (a seeded, deterministic
     * pitch wobble), top-end loss, and a whisper of hiss.
     */
    private fun tape(snip: Snip, amount: Float): Snip {
        val drive = lerp(1f, 2.2f, amount)
        val saturated = Snip(
            FloatArray(snip.samples.size) { i -> (tanh(snip.samples[i] * drive) / tanh(drive)) },
            snip.channels, snip.sampleRate,
        )
        val wobbled = wow(saturated, depthCents = lerp(0f, 10f, amount), rateHz = 0.8f)
        val dulled = onePoleLowpass(wobbled, lerp(20_000f, 9_000f, amount))
        return hiss(dulled, levelDb = -54f, mix = amount)
    }

    /**
     * Down the line: the telephone band (300–3400 Hz), µ-law companded to
     * 8 bits, at 8 kHz. The lo-fi-est era, and instantly recognisable.
     */
    private fun phone(snip: Snip, amount: Float): Snip {
        // A codec's band edges are steep - cascade the one-poles to get
        // there, and clean the upsample images after the rate trip, or the
        // "phone" keeps a ghost of its highs.
        val lp = lerp(20_000f, 3_400f, amount)
        var banded = snip
        repeat(3) { banded = onePoleLowpass(banded, lp) }
        banded = onePoleHighpass(banded, lerp(20f, 300f, amount))
        val bits = lerp(16f, 8f, amount).toInt()
        val mu = lerp(1f, 255f, amount)
        val companded = Snip(
            FloatArray(banded.samples.size) { i ->
                expand(quantize(compand(banded.samples[i], mu), bits), mu)
            },
            banded.channels, banded.sampleRate,
        )
        val rate = lerp(snip.sampleRate.toFloat(), 8_000f, amount).toInt()
        val down = resampleCharacter(companded, rate, hold = false)
        val up = resampleCharacter(down, snip.sampleRate, hold = false)
        return onePoleLowpass(onePoleLowpass(up, lp), lp)
    }

    // ---- primitives --------------------------------------------------------

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /**
     * Character resampling: linear interpolation down (no anti-alias — the
     * folding is deliberate), and either linear or zero-order hold up (ZOH
     * keeps the stair-step images the originals imaged through their
     * reconstruction). Frame count scales by the rate ratio and the result
     * is stamped with [targetRate], so duration is preserved.
     */
    private fun resampleCharacter(snip: Snip, targetRate: Int, hold: Boolean): Snip {
        if (targetRate == snip.sampleRate) return snip
        val ratio = snip.sampleRate.toDouble() / targetRate
        val outFrames = (snip.frameCount / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outFrames * snip.channels)
        for (f in 0 until outFrames) {
            val srcPos = f * ratio
            val i0 = floor(srcPos).toInt().coerceAtMost(snip.frameCount - 1)
            val i1 = (i0 + 1).coerceAtMost(snip.frameCount - 1)
            val frac = (srcPos - i0).toFloat()
            for (c in 0 until snip.channels) {
                val a = snip.samples[i0 * snip.channels + c]
                out[f * snip.channels + c] = if (hold) {
                    a
                } else {
                    a + (snip.samples[i1 * snip.channels + c] - a) * frac
                }
            }
        }
        return Snip(out, snip.channels, targetRate)
    }

    /** Truncation toward zero at [bits] — the SP's converter rounds nothing. */
    private fun quantizeTruncate(snip: Snip, bits: Int): Snip {
        val levels = (1 shl (bits - 1)).toFloat()
        return Snip(
            FloatArray(snip.samples.size) { i ->
                val s = snip.samples[i].coerceIn(-1f, 1f)
                (s * levels).toInt() / levels
            },
            snip.channels, snip.sampleRate,
        )
    }

    /** Rounded quantization at [bits], for the companded paths. */
    private fun quantize(sample: Float, bits: Int): Float {
        val levels = (1 shl (bits - 1)).toFloat()
        return Math.round(sample.coerceIn(-1f, 1f) * levels) / levels
    }

    /** µ-law-style compression into [-1, 1]. */
    private fun compand(sample: Float, mu: Float): Float {
        if (mu <= 1f) return sample
        val s = sample.coerceIn(-1f, 1f)
        return sign(s) * (ln(1 + mu * abs(s)) / ln(1 + mu.toDouble())).toFloat()
    }

    /** The inverse of [compand]. */
    private fun expand(sample: Float, mu: Float): Float {
        if (mu <= 1f) return sample
        val s = sample.coerceIn(-1f, 1f)
        return sign(s) * ((exp(abs(s) * ln(1 + mu.toDouble())) - 1) / mu).toFloat()
    }

    private fun onePoleLowpass(snip: Snip, cutoffHz: Float): Snip {
        if (cutoffHz >= snip.sampleRate / 2f) return snip
        val a = (1.0 - exp(-2.0 * Math.PI * cutoffHz / snip.sampleRate)).toFloat()
        val out = FloatArray(snip.samples.size)
        for (c in 0 until snip.channels) {
            var y = 0f
            for (f in 0 until snip.frameCount) {
                val i = f * snip.channels + c
                y += a * (snip.samples[i] - y)
                out[i] = y
            }
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }

    private fun onePoleHighpass(snip: Snip, cutoffHz: Float): Snip {
        if (cutoffHz <= 1f) return snip
        val low = onePoleLowpass(snip, cutoffHz)
        return Snip(
            FloatArray(snip.samples.size) { i -> snip.samples[i] - low.samples[i] },
            snip.channels, snip.sampleRate,
        )
    }

    /**
     * Slow pitch wobble via a modulated read position — wow. Deterministic:
     * the modulator is a fixed-phase sine, no randomness.
     */
    internal fun wow(snip: Snip, depthCents: Float, rateHz: Float): Snip {
        if (depthCents <= 0f) return snip
        val depth = Math.pow(2.0, depthCents / 1200.0) - 1.0 // fractional speed swing
        val out = FloatArray(snip.samples.size)
        var pos = 0.0
        for (f in 0 until snip.frameCount) {
            val speed = 1.0 + depth * sin(2.0 * Math.PI * rateHz * f / snip.sampleRate)
            val i0 = floor(pos).toInt().coerceIn(0, snip.frameCount - 1)
            val i1 = (i0 + 1).coerceAtMost(snip.frameCount - 1)
            val frac = (pos - i0).toFloat()
            for (c in 0 until snip.channels) {
                val a = snip.samples[i0 * snip.channels + c]
                val b = snip.samples[i1 * snip.channels + c]
                out[f * snip.channels + c] = a + (b - a) * frac
            }
            pos = (pos + speed).coerceAtMost((snip.frameCount - 1).toDouble())
        }
        return Snip(out, snip.channels, snip.sampleRate)
    }

    /** A whisper of seeded, deterministic noise under the signal. */
    internal fun hiss(snip: Snip, levelDb: Float, mix: Float): Snip {
        if (mix <= 0f) return snip
        val level = Math.pow(10.0, levelDb / 20.0).toFloat() * mix
        val rnd = kotlin.random.Random(1200)
        return Snip(
            FloatArray(snip.samples.size) { i ->
                snip.samples[i] + (rnd.nextFloat() * 2 - 1) * level
            },
            snip.channels, snip.sampleRate,
        )
    }
}
