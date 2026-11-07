package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.pow

/**
 * DIVE — the pitch falls after the hit. The characteristic gesture is: the pad starts at its own
 * pitch, the pitch falls fastest at the hit, and it settles DROP semitones lower and holds there.
 * On a drum that is the 808's pitch envelope (a snare or a tom becomes a kick or a boom); on a guitar
 * it is the whammy dive bomb. The rack has nothing like it: PITCH is one fixed ratio for the whole
 * pad, STOP fades the pad to silence, and no section bends the pitch over time.
 *
 * The mechanism is a sampler's own, a variable-speed read. The head reads the pad at speed
 * r(t) = 2^(-DROP/12 x (1 - e^(-t/tau))): 1 at the hit, so the attack is read at its own pitch, and
 * falling fastest at the start (an exponential, which is how an analog pitch envelope falls) toward
 * 2^(-DROP/12). The head position is the running sum of that speed, not a closed form, so the
 * pitch at output time t is exactly the speed at t. Because the head ends up slower than it began,
 * the pad comes out longer: about 2x for a 12 semitone dive and up to 4x at the top of DROP (the
 * same trade PITCH makes and the reason this is a TRANSPORT stage: only that stage may add length,
 * outside the rack's tail cap).
 *
 * DROP is 0..[MAX_SEMITONES] semitones, two octaves (continuous, not snapped: a dive is a glide, not
 * an interval), so its middle is the 12 semitone dive the owner chose for a tap. OVER is how
 * long the fall takes, 30 ms to 1 s on a log scale (the ear hears ratios of time), and means 95
 * percent of the fall (tau is a third of it). OVER's neutral is its default, so AMT, which walks a
 * character toward its neutral, shortens the dive by shrinking DROP and never moves OVER.
 *
 * Shape, not loudness: the peak is matched both ways afterwards (a late-peaking pad's peak moves
 * when the head slows down). At DROP 0 the pad comes back unchanged.
 *
 * Sits straight after PITCH, in the TRANSPORT stage, so SWELL's head, REVERSE and the whole rack
 * see the dived pad. Known hazards, not hidden: a tonal pad leaves its key (the pitch is where DROP
 * puts it, nothing re-snaps it), and a rim is 40 ms long, so the dive happens inside its click.
 *
 * For held sounds. At its gate (2026-11-07) the owner heard it on a snare, a tom, a rim and the CHUG
 * guitar, each beside PITCH down six semitones, at 3, 6 and 12 semitones and with a 60 ms and a 500 ms
 * fall: only the guitar sounded like a dive, and the drums were too short for one. So the 808-envelope
 * use the pedal brainstorm hoped for did not survive the listen, nothing here tries to rescue a drum,
 * and the chip is a pedal in front of the amp (a whammy bar's place), not a transport chip on every pad.
 * The owner chose a 12 semitone dive at the default fall (about 170 ms) for a tap, which is why DROP
 * reaches two octaves: at one octave a tap (AMT 70) could only reach 8.4 semitones.
 */
object Dive {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("DROP", 0.5f),                  // how far the pitch falls: none .. MAX_SEMITONES at the top
        MacroSpec("OVER", 0.5f, neutral = 0.5f),  // how long the fall takes: FAST_SEC .. SLOW_SEC; its own neutral, so AMT never moves it
    )

    /** The top of DROP: two octaves, so a tap (AMT 70) can reach the owner's 12 semitones with room above. */
    const val MAX_SEMITONES = 24f

    /** The ends of OVER: 95 percent of the fall happens inside this time. */
    const val FAST_SEC = 0.03f
    const val SLOW_SEC = 1f

    /** The fall is exponential; 95 percent of it is done after this many time constants. */
    const val TAU_PER_OVER = 3f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** The fall in semitones for a DROP macro. */
    fun semitones(drop: Float): Float = drop.coerceIn(0f, 1f) * MAX_SEMITONES

    /** The DROP macro that falls [semitones] (the inverse of [semitones]). */
    fun dropFor(semitones: Float): Float = (semitones / MAX_SEMITONES).coerceIn(0f, 1f)

    /** The time in seconds in which 95 percent of the fall is done, for an OVER macro (log scale). */
    fun overSec(over: Float): Float = FAST_SEC * (SLOW_SEC / FAST_SEC).pow(over.coerceIn(0f, 1f))

    /** The OVER macro whose fall takes [sec] seconds (the inverse of [overSec]). */
    fun overFor(sec: Float): Float = (kotlin.math.ln(sec / FAST_SEC) / kotlin.math.ln(SLOW_SEC / FAST_SEC)).coerceIn(0f, 1f)

    /** How far the pitch has fallen, in semitones, [seconds] after the hit. */
    fun fallenAt(seconds: Double, semitones: Float, overSec: Float): Double =
        semitones * (1.0 - exp(-seconds / (overSec / TAU_PER_OVER)))

    /** The head's reading speed [seconds] after the hit: 1 is the pad's own pitch, 0.5 an octave down. */
    fun speedAt(seconds: Double, semitones: Float, overSec: Float): Double =
        2.0.pow(-fallenAt(seconds, semitones, overSec) / 12.0)

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip {
        val m = defaults().toMutableMap()
        // A value that is not a number is left at the default: a NaN would otherwise ride through the clamp into the head.
        for ((k, v) in macros) if (m.containsKey(k) && v.isFinite()) m[k] = v.coerceIn(0f, 1f)
        val drop = semitones(m.getValue("DROP"))
        // A pad too short to read is a bypass, never a throw.
        if (drop <= 0f || snip.frameCount < 2) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val rate = snip.sampleRate
        val over = overSec(m.getValue("OVER"))
        val last = snip.frameCount - 1
        // The head can never read slower than the settled speed, so the output is at most last / settled frames.
        val settled = 2.0.pow(-drop / 12.0)
        val cap = ceil(last / settled).toInt() + 2

        // The head position of every output frame: a running sum of the speed at that frame.
        val head = DoubleArray(cap)
        var pos = 0.0
        var n = 0
        while (n < cap && pos < last) {
            head[n] = pos
            pos += speedAt(n.toDouble() / rate, drop, over)
            n++
        }
        val out = Dsp.readAt(snip, n.coerceAtLeast(1)) { k -> head[k] to 1f }

        // Shape, not loudness: matched both ways, like PITCH. A late-peaking pad's peak moves when the head slows.
        var outPeak = 0f
        for (v in out.samples) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val g = inPeak / outPeak
            for (i in out.samples.indices) out.samples[i] = (out.samples[i] * g).coerceIn(-1f, 1f)
        }
        return out
    }
}
