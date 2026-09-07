package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.sqrt

/**
 * MOTION — the tape stop and the tape start, baked.
 *
 * Sampling-era honesty says motion bakes into the render: you are
 * sampling a machine, and this is the machine's transport. STOP is the
 * capstan letting go — over the sound's last stretch the pitch falls to
 * nothing and the level with it. START is the opposite: the reel spinning
 * up from rest, pitch and level climbing into the sound. Both are one
 * variable-speed read through the source with a linear-interpolated head,
 * the speed curve the physics of a flywheel (linear in time). Two macros,
 * 0..1 each mapping onto seconds, all-zeros transparent. Last in the rack:
 * the whole treated sound stops, reverb tail and all.
 */
object Motion {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("STOP", 0f),   // how long the stop takes: none .. STOP_MAX_SEC
        MacroSpec("START", 0f),  // how long the spin-up takes: none .. START_MAX_SEC
    )

    /** A full STOP glides over this long. */
    const val STOP_MAX_SEC = 2f

    /** A full START spins up over this long. */
    const val START_MAX_SEC = 1.5f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val stopSec = m.getValue("STOP") * STOP_MAX_SEC
        val startSec = m.getValue("START") * START_MAX_SEC
        if (stopSec <= 0f && startSec <= 0f) return snip
        var s = snip
        if (startSec > 0f) s = start(s, (startSec * s.sampleRate).toInt())
        if (stopSec > 0f) s = stop(s, (stopSec * s.sampleRate).toInt())
        return s
    }

    /**
     * The reel spins up over [frames] output frames: speed climbs 0 → 1, so
     * the head covers half that many source frames during the ramp; the rest
     * plays at speed. Output grows by half the ramp.
     */
    private fun start(snip: Snip, frames: Int): Snip {
        val ramp = frames.coerceAtLeast(1)
        val outFrames = snip.frameCount + ramp / 2
        return read(snip, outFrames) { k ->
            if (k < ramp) {
                val u = k.toDouble() / ramp
                // position = ∫ speed = k²/(2·ramp); level follows the speed.
                (u * k / 2.0) to sqrt(u).toFloat()
            } else {
                (k - ramp / 2.0) to 1f
            }
        }
    }

    /**
     * The capstan lets go [frames] output frames before the end: speed falls
     * 1 → 0 over the ramp, the head covering half the ramp's worth of source,
     * the level falling with the speed so the stopped tape is silent, not a
     * held sample. Output keeps the source's length.
     */
    private fun stop(snip: Snip, frames: Int): Snip {
        val ramp = frames.coerceIn(1, snip.frameCount.coerceAtLeast(1))
        val k0 = snip.frameCount - ramp
        return read(snip, snip.frameCount) { k ->
            if (k < k0) {
                k.toDouble() to 1f
            } else {
                val u = (k - k0).toDouble() / ramp
                // position = k0 + ∫ (1 − u) = k0 + (k − k0)·(1 − u/2)
                (k0 + (k - k0) * (1.0 - u / 2.0)) to sqrt(1.0 - u).toFloat()
            }
        }
    }

    /** A variable-speed head: for each output frame, where to read (fractional) and how loud. */
    private inline fun read(snip: Snip, outFrames: Int, headAt: (Int) -> Pair<Double, Float>): Snip {
        val ch = snip.channels
        val src = snip.samples
        val last = snip.frameCount - 1
        val out = FloatArray(outFrames * ch)
        for (k in 0 until outFrames) {
            val (pos, gain) = headAt(k)
            if (pos >= last || gain <= 0f) continue
            val i = pos.toInt()
            val frac = (pos - i).toFloat()
            for (c in 0 until ch) {
                val a = src[i * ch + c]
                val b = src[(i + 1) * ch + c]
                out[k * ch + c] = (a + (b - a) * frac) * gain
            }
        }
        return Snip(out, ch, snip.sampleRate)
    }
}
