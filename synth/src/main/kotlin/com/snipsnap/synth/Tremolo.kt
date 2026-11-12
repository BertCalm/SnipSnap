package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * TREMOLO (saved key `tremolo`): the level pulses. The characteristic gesture is: the hit arrives at full level,
 * then the sound throbs, loud, quiet, loud, at RATE, from a smooth swell (a sine) to a hard chop (a square).
 *
 * Nothing in the rack makes that. RING multiplies by a carrier that swings negative (sum-and-difference, never
 * slower than 30 Hz), VIBRATO moves the pitch and leaves the level, and the keyed GATE chops on the kit's grid,
 * not at a free rate. This is the pedal board spec's Stage 4 (`docs/superpowers/specs/2026-10-04-pedal-board-design.md`).
 *
 * The gain is g(t) = 1 - DEPTH x w(t), with w running 0 to 1, so the gain moves between 1 and 1 - DEPTH and
 * never inverts (that is what separates it from RING). w starts at 0: the sound **starts open, at full level**, as
 * GATE and WOBBLE do, so a hit's attack is never eaten (PHASE, RING and TAPE start at sin 0, mid-swing).
 *
 * SHAPE steepens the swing. w = clamp(0.5 - 0.5 k cos(2 pi f t), 0, 1): at k = 1 that is the plain sine,
 * (1 - cos) / 2; as k grows the cosine is steepened and clipped flat at both ends, so the swing spends longer
 * fully open and fully closed and moves between them faster: a square with sloped edges. The steepest k is set
 * per rate so the edge between open and closed always takes [EDGE_SEC], at 1 Hz and at 12 Hz alike: the chop
 * is the same chop at every speed, and a gain that never moves faster than DEPTH over [EDGE_SEC] cannot click.
 * SHAPE walks k from 1 to that steepest value on a log law, so the middle of the knob is the middle of the way.
 *
 * Why not the spec's one-pole on a square: a one-pole lags, so at 12 Hz it would not quite reach the trough
 * before turning back, and the depth would no longer be what DEPTH says. The clipped cosine reaches 0 and 1 on
 * every cycle at every SHAPE, so the peak-to-trough is exactly DEPTH (pinned by TremoloTest), and its edges have
 * a bounded slope, which is all the one-pole was there to give.
 *
 * Free-running from the first frame (the rack has no tempo; GATE is the tempo-locked chop). One modulator for
 * every channel, so a stereo pair throbs together and mono stays mono. Shape, not loudness: the output is matched
 * to the pad's peak, as PHASE's is, and at DEPTH 0 the pad comes back unchanged. No tail.
 *
 * Sits after FLANGER and before ECHO: after the amp, where an amp's own tremolo lives, and in front of the delay,
 * so the repeats carry the throb.
 *
 * For held sounds. At its gate (2026-11-12) the owner heard it on ONSET's snare, JANGLE and the choir: it throbs like
 * an amp's tremolo, the chop is clean, before or after the spring made no difference (so it stays before), and the ends
 * are extreme enough. They chose Gentle for the tap, so the TREM chip's tap is DEPTH 0.5, RATE 0.5 and SHAPE 0 (the
 * `tremoloed` character).
 */
object Tremolo {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("DEPTH", 0.6f),                  // how far the level dips: none .. silence at the trough
        MacroSpec("RATE", 0.5f, neutral = 0.5f),   // SLOW_HZ .. FAST_HZ (log); its own neutral, so AMT never moves it
        MacroSpec("SHAPE", 0f),                    // 0 a smooth sine .. 1 a square with EDGE_SEC edges
    )

    /** RATE's ends, cycles a second. An amp's tremolo runs about 2 to 10. */
    const val SLOW_HZ = 1f
    const val FAST_HZ = 12f

    /** At SHAPE 1, how long the level takes to go from open to closed (and back). */
    const val EDGE_SEC = 0.004f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** The tremolo's rate in Hz for a RATE macro. */
    fun hz(rate: Float): Float = Dsp.expMap(rate, SLOW_HZ, FAST_HZ)

    /**
     * The cosine's steepness for a SHAPE macro at [hz]: 1 (the plain sine) at SHAPE 0, and at SHAPE 1 the k whose
     * clipped edge, 2 asin(1 / k) radians wide, lasts [EDGE_SEC].
     */
    fun steepness(shape: Float, hz: Float): Double {
        val half = (PI * hz * EDGE_SEC).coerceAtMost(PI / 2)
        val square = 1.0 / sin(half)
        return Math.pow(square, shape.coerceIn(0f, 1f).toDouble())
    }

    /** The swing, 0 open .. 1 closed, at [phase] radians for steepness [k]. */
    fun swing(phase: Double, k: Double): Float = (0.5 - 0.5 * k * cos(phase)).coerceIn(0.0, 1.0).toFloat()

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        // A value that is not a number is left at the default: a NaN would otherwise ride through the clamp into the gain.
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        val depth = m.getValue("DEPTH")
        if (depth <= 0f || snip.frameCount == 0) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val hz = hz(m.getValue("RATE"))
        val k = steepness(m.getValue("SHAPE"), hz)
        val omega = 2.0 * PI * hz / snip.sampleRate

        val ch = snip.channels
        val out = FloatArray(snip.samples.size)
        for (f in 0 until snip.frameCount) {
            // One gain for every channel, so a stereo pair throbs together.
            val g = 1f - depth * swing(omega * f, k)
            for (c in 0 until ch) out[f * ch + c] = snip.samples[f * ch + c] * g
        }

        // Shape, not loudness: a held pad whose loudest moment fell in a trough is lifted back to its peak.
        val inPeak = snip.peak()
        val outPeak = Snip(out, ch, snip.sampleRate).peak()
        if (inPeak > 0f && outPeak > 1e-9f) {
            val g = inPeak / outPeak
            for (i in out.indices) out[i] = (out[i] * g).coerceIn(-1f, 1f)
        }
        return Snip(out, ch, snip.sampleRate)
    }
}
