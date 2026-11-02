package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/**
 * VIBRATO — the pitch sings. A short delay swept by one sine, wet only, so the pitch rises and
 * falls and the level does not: a straight choir becomes sung, a guitar gets the shimmer of an
 * amp's vibrato channel, a chop becomes a little more human. The rack has nothing like it:
 * ENSEMBLE is a chorus (it mixes moving copies with the dry sound), TAPE's wobble is two fixed
 * rates of wow and flutter at depths set by a tape machine, and the level-moving effects leave the
 * pitch alone.
 *
 * The delay is d(t) = A (1 - cos(2 pi f t)): zero at the first frame, so there is no latency and
 * nothing to trim, swinging up to 2A and back. The pitch of the output is the input's times
 * (1 - d'(t)), so its peak swing is A x 2 pi f. DEPTH is that swing in cents (0 to [MAX_CENTS]) and
 * A is solved from it for the RATE in use, which keeps the swing the same at every rate: a faster
 * vibrato does not get deeper, only quicker. LATE is how long the vibrato takes to arrive, a raised
 * cosine ramp on A, 0 to [LATE_MAX_SEC]; a singer's vibrato comes in after the note starts.
 *
 * Shape, not loudness: the peak is matched afterwards, and at DEPTH 0 the pad comes back unchanged.
 *
 * Sits after TAPE and before ENSEMBLE, after the amp, where a guitar amp's own vibrato lives.
 */
object Vibrato {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("DEPTH", 0.5f),                  // how far the pitch swings: none .. MAX_CENTS at the top
        MacroSpec("RATE", 0.5f, neutral = 0.5f),   // how fast it sings: SLOW_HZ .. FAST_HZ; its own neutral, so AMT never moves it
        MacroSpec("LATE", 0f),                     // how long it takes to arrive: none .. LATE_MAX_SEC
    )

    /** The top of DEPTH: a peak pitch swing of 60 cents, a little over half a semitone. */
    const val MAX_CENTS = 60f

    /** The ends of RATE, in cycles a second. A sung vibrato sits near 5 to 6. */
    const val SLOW_HZ = 2f
    const val FAST_HZ = 9f

    /** The top of LATE. */
    const val LATE_MAX_SEC = 0.6f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** The vibrato's rate in Hz for a RATE macro. */
    fun hz(rate: Float): Float = Dsp.lin(rate, SLOW_HZ, FAST_HZ)

    /** The peak upward pitch swing in cents for a DEPTH macro. */
    fun cents(depth: Float): Float = depth.coerceIn(0f, 1f) * MAX_CENTS

    /**
     * The half-range of the delay's swing in samples that gives [cents] of upward pitch swing at [hz]:
     * the output pitch is the input's times (1 - d'), so the swing is A x 2 pi f and A is that over 2 pi f.
     */
    fun swingSamples(cents: Float, hz: Float, sampleRate: Int): Float =
        ((2.0.pow(cents / 1200.0) - 1.0) / (2.0 * PI * hz) * sampleRate).toFloat()

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        // A value that is not a number is left at the default: a NaN would otherwise ride through the clamp into the delay.
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        val depth = m.getValue("DEPTH")
        val rate = snip.sampleRate
        val lateFrames = (m.getValue("LATE") * LATE_MAX_SEC * rate).toInt()
        if (depth <= 0f || snip.frameCount == 0) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val hz = hz(m.getValue("RATE"))
        val swing = swingSamples(cents(depth), hz, rate)
        val omega = 2.0 * PI * hz / rate
        // The line holds the longest delay the swing can ask for, with room to interpolate.
        val line = FloatArray(2 * (2 * swing).toInt() + 8)

        val ch = snip.channels
        val out = FloatArray(snip.samples.size)
        for (c in 0 until ch) {
            line.fill(0f)
            var w = 0
            for (f in 0 until snip.frameCount) {
                line[w] = snip.samples[f * ch + c]
                val arrive = if (lateFrames <= 0 || f >= lateFrames) 1f else (0.5 * (1.0 - cos(PI * f / lateFrames))).toFloat()
                // One sine for every channel, so a stereo pair sings together.
                val delay = swing * arrive * (1.0 - cos(omega * f)).toFloat()
                out[f * ch + c] = Dsp.tap(line, w, delay)
                w = (w + 1) % line.size
            }
        }

        // Shape, not loudness: the interpolation softens the top a little, so match the input's peak.
        val inPeak = snip.peak()
        val outPeak = Snip(out, ch, rate).peak()
        if (inPeak > 0f && outPeak > 1e-9f) {
            val g = inPeak / outPeak
            for (i in out.indices) out[i] = (out[i] * g).coerceIn(-1f, 1f)
        }
        return Snip(out, ch, rate)
    }
}
