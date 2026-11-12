package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/**
 * FLANGER (saved key `flanger`): the jet. The characteristic gesture is: the sound plays, then a comb of
 * notches sweeps down through it and back up, the whoosh of two tape machines drifting against each other, with
 * FEEDBACK turning the whoosh into a ringing, metallic zoom (positive) or a hollow, boxed-in one (negative).
 *
 * Nothing in the rack makes that. PHASE is four allpasses, two notches spaced by the filters; a comb has dozens,
 * evenly spaced, which is the jet. ENSEMBLE's taps are chorus-length (10 ms and up) and mix several copies, so
 * they thicken rather than sweep. This is the pedal board spec's Stage 4
 * (`docs/superpowers/specs/2026-10-04-pedal-board-design.md`).
 *
 * One short delay, swept by one sine, summed with the dry. A copy delayed by d cancels the dry at every odd
 * multiple of 1 / (2d), so the notches sit at 1 / (2d), 3 / (2d), ...; as d grows they slide down together. The
 * delay sweeps on a log law from [MIN_DELAY_MS] (the first notch at 5 kHz) up to a top DEPTH sets, as far as
 * [MAX_DELAY_MS] (the first notch at 50 Hz): equal knob steps sound like equal steps, as PHASE's corner does.
 * The sweep is d(t) = min x (top / min)^w(t), w = (1 - cos(2 pi f t)) / 2, so it starts at the shortest delay,
 * the notches up out of the way, and the hit arrives nearly untouched before the sweep comes down.
 *
 * FEEDBACK feeds the delayed copy back into the line, read before it writes (which is what allows feedback in a
 * loop at least one sample long): two-way, neutral at the middle. Positive feedback sharpens peaks between the
 * notches (the jet, metallic); negative puts the peaks where the notches were, the odd harmonics of 1 / (2d) (the
 * hollow, boxed sound). Capped at [MAX_FEEDBACK]: at the longest delay a ring then falls 60 dB in about 0.3 s,
 * so it rings out in tens to a few hundred ms and never needs a tail; the pad keeps its length (ECHO and SPRING
 * are the sections that extend one).
 *
 * The read is the rack's interpolated tap ([Dsp.tap], linear), as VIBRATO's and ENSEMBLE's are. Linear
 * interpolation rolls the top off a little at fractional delays, and inside the feedback loop that loss repeats on
 * every pass, so the ring is a little darker than an ideal comb's: at half a sample the interpolator takes about
 * 2.4 dB off 10 kHz a pass (its response, cos(pi f / fs), computed, not measured). The spec left whether that needs
 * a better read to the listen.
 *
 * Free-running from the first frame (the rack has no tempo). One sweep for every channel, so a stereo pair moves
 * together and mono stays mono. Not through-zero (out of scope in the spec): the delay never reaches the dry.
 * Shape, not loudness: the sum is matched to the pad's peak, as PHASE's is, and at DEPTH 0 the pad comes back
 * unchanged.
 *
 * Sits after PHASE and before TREMOLO: after the amp, beside the phaser, and in front of the tremolo and the
 * delay, so the throb and the repeats carry the sweep.
 *
 * Chip-less until the owner's listening gate.
 */
object Flanger {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("DEPTH", 0.7f),                       // how far the notches sweep down: none .. MAX_DELAY_MS at the bottom
        MacroSpec("RATE", 0.4f, neutral = 0.4f),        // SLOW_HZ .. FAST_HZ (log); its own neutral, so AMT never moves it
        MacroSpec("FEEDBACK", 0.7f, neutral = 0.5f),    // 0 hollow (-MAX_FEEDBACK) .. 0.5 none .. 1 metallic (+MAX_FEEDBACK)
    )

    /** RATE's ends, cycles a second: a slow jet takes ten seconds a sweep, a fast one is a warble. */
    const val SLOW_HZ = 0.1f
    const val FAST_HZ = 5f

    /** The sweep's shortest delay (the first notch at 5 kHz) and DEPTH 1's longest (the first notch at 50 Hz). */
    const val MIN_DELAY_MS = 0.1f
    const val MAX_DELAY_MS = 10f

    /** FEEDBACK's ends, either sign. */
    const val MAX_FEEDBACK = 0.8f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** The sweep's rate in Hz for a RATE macro. */
    fun hz(rate: Float): Float = Dsp.expMap(rate, SLOW_HZ, FAST_HZ)

    /** The longest delay the sweep reaches, ms, for a DEPTH macro. */
    fun topMs(depth: Float): Float = Dsp.expMap(depth, MIN_DELAY_MS, MAX_DELAY_MS)

    /** The loop's feedback for a FEEDBACK macro: two-way about the middle. */
    fun feedback(macro: Float): Float = (2f * macro.coerceIn(0f, 1f) - 1f) * MAX_FEEDBACK

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        // A value that is not a number is left at the default: a NaN would otherwise ride through the clamp into the delay.
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        val depth = m.getValue("DEPTH")
        if (depth <= 0f || snip.frameCount == 0) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val rate = snip.sampleRate
        val minFrames = MIN_DELAY_MS / 1000f * rate
        val ratio = (topMs(depth) / MIN_DELAY_MS).toDouble()
        val omega = 2.0 * PI * hz(m.getValue("RATE")) / rate
        val fb = feedback(m.getValue("FEEDBACK"))
        // The line holds the longest delay with room to interpolate.
        val line = FloatArray((MAX_DELAY_MS / 1000f * rate).toInt() + 8)

        val ch = snip.channels
        val out = FloatArray(snip.samples.size)
        for (c in 0 until ch) {
            line.fill(0f)
            var w = 0
            for (f in 0 until snip.frameCount) {
                val x = snip.samples[f * ch + c]
                // One sweep for every channel, so a stereo pair moves together. Read before writing: the newest
                // sample in the line is the last frame's, so the loop is at least one sample long.
                val sweep = 0.5 * (1.0 - cos(omega * f))
                val delay = (minFrames * ratio.pow(sweep)).toFloat()
                val wet = Dsp.tap(line, (w - 1 + line.size) % line.size, delay - 1f)
                line[w] = x + fb * wet
                out[f * ch + c] = x + wet
                w = (w + 1) % line.size
            }
        }

        // Shape, not loudness: the comb's peaks and the feedback's ring change the peak, so match the input's.
        val inPeak = snip.peak()
        val outPeak = Snip(out, ch, rate).peak()
        if (inPeak > 0f && outPeak > 1e-9f) {
            val g = inPeak / outPeak
            for (i in out.indices) out[i] = (out[i] * g).coerceIn(-1f, 1f)
        }
        return Snip(out, ch, rate)
    }
}
