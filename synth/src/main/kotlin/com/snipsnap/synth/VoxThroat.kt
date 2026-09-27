package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * THROAT: overtone singing, VOX round 3's first voice
 * (docs/SYNTH_ROADMAP.md S11, "Weird").
 *
 * One low drone, and above it a whistle: a single harmonic of the drone
 * picked out by a very narrow resonance, the way a singer shapes the
 * tongue to merge two formants into one sharp peak. Move the peak and it
 * hops from harmonic to harmonic, a melody made of the drone's own
 * overtone series, always in tune with it. Measured at the audition, the
 * whistled harmonic stands 23-54 dB over its neighbours.
 *
 * - **WHISTLE** picks the harmonic, 5th to 13th, snapped.
 * - **MELODY** walks the whistle up the harmonics and back during the
 *   note; 0 holds it still, 1 spans six harmonics.
 * - **DRONE** trades the whistle for the body under it.
 * - **GROWL** is the rasp an octave down: the false vocal folds flap at
 *   half the pitch, so every other pulse is damped. At full, the half
 *   harmonics sit within 2 dB of the note.
 * - **YODEL** flips the voice from chest to head and back, up a sixth
 *   then an octave, in a lilt. The vowel follows the register ("oh" low,
 *   "ee" high) and the whistle steps aside while the voice is up: left
 *   ringing where it was, the first audition heard it as strange.
 *
 * The whistle's width is fixed: 15, 25 and 45 Hz measured 1.2-1.7 apart
 * on the audition's difference measure, inside its noise floor, and
 * could not be told apart by ear. There is no SIZE: THROAT's seven knobs
 * went to the singing instead.
 */
internal object VoxThroat {

    /** The whistle harmonics WHISTLE snaps across. */
    const val LOWEST_WHISTLE = 5
    const val HIGHEST_WHISTLE = 13

    /** MELODY's reach, harmonics, and the walk it takes: up, over the top, down through the middle, home. */
    const val MELODY_SPAN = 6
    private val MELODY_TIMES = floatArrayOf(0f, 0.17f, 0.33f, 0.5f, 0.67f, 0.83f, 1f)
    private val MELODY_STEPS = floatArrayOf(0f, 0.5f, 1f, 0.67f, 0.33f, 0.5f, 0f)

    /** The whistle never rises past this: a throat's resonance does not reach much higher. */
    private const val WHISTLE_CEILING_HZ = 4000f

    /** The whistle resonance's width. Narrower or wider could not be heard (see the class doc). */
    private const val WHISTLE_BW = 25.0

    /** GROWL: how far the damped pulses fall, and how much rasp. Deeper (0.9, 0.7) measured 1.1 apart: the same. */
    private const val GROWL_DAMP = 0.8f
    private const val RASP = 0.35f

    /** The yodel's high notes, semitones over the chest note, in turn: a sixth, then the octave. */
    val HEAD_SEMIS = floatArrayOf(9f, 12f)

    /** How long the voice takes to flip register: quick, a break rather than a slide. */
    private const val FLIP_SECONDS = 0.03f

    /** The vowel each register sings, F1 and F2 in Hz: chest on "oh", head on "ee". */
    private val CHEST_VOWEL = floatArrayOf(480f, 850f)
    private val HEAD_VOWEL = floatArrayOf(310f, 2250f)

    /** Held head notes get a singer's vibrato; the drone stays steady, as in throat singing. */
    private const val HEAD_VIBRATO_CENTS = 30f
    private const val HEAD_VIBRATO_HZ = 5.5f

    private const val CONTROL_BLOCK = 16

    /** A double-precision two-pole bandpass: a 25 Hz peak at the 4x render rate needs more pole than a float holds. */
    private class Reson {
        private var b0 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

        /** RBJ constant-peak bandpass, Q = hz / bandwidth. */
        fun set(hz: Double, bandwidth: Double, rate: Int) {
            val w0 = 2 * PI * hz / rate
            val alpha = sin(w0) / (2 * (hz / bandwidth).coerceAtLeast(0.5))
            val a0 = 1 + alpha
            b0 = alpha / a0; a1 = -2 * cos(w0) / a0; a2 = (1 - alpha) / a0
        }

        fun process(x: Double): Double {
            val y = b0 * x - b0 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    /** WHISTLE 0..1 as the harmonic it whistles. */
    fun harmonicFor(whistle: Float): Int =
        LOWEST_WHISTLE + Math.round(whistle.coerceIn(0f, 1f) * (HIGHEST_WHISTLE - LOWEST_WHISTLE))

    fun synthesize(
        baseHz: Float,
        whistle: Float,
        melody: Float,
        drone: Float,
        decay: Float,
        growl: Float,
        yodel: Float,
        rate: Int,
    ): FloatArray {
        val length = Vox.lengthFor(decay)
        val hold = length * Vox.holdFractionFor(decay)
        val frames = ((length * 1.3f).coerceAtMost(Vox.MAX_SECONDS) * rate).toInt().coerceAtLeast(64)
        val out = FloatArray(frames)
        val env = Dsp.Env(attackSeconds = 0.06f, decay2T60 = length - hold, holdSeconds = hold)
        val noise = Dsp.Noise(41)
        val rasp = Dsp.OnePole(rate)
        val soften = Dsp.OnePole(rate)

        val f1 = Reson()
        val f2 = Reson()
        val f3 = Reson()
        val whistleA = Reson()
        val whistleB = Reson()
        val droneGain = Dsp.lin(drone, 0.35f, 1.3f)
        val whistleGain = Dsp.lin(drone, 2.2f, 0.9f)

        // The whistle's walk, in harmonics, capped where the throat's resonance runs out.
        val start = harmonicFor(whistle)
        val span = Math.round(melody.coerceIn(0f, 1f) * MELODY_SPAN)
        val top = maxOf(1, floor(WHISTLE_CEILING_HZ / baseHz).toInt())
        val path = FloatArray(MELODY_STEPS.size) { minOf(start + Math.round(MELODY_STEPS[it] * span), top).toFloat() }

        // YODEL: register flips across the note's held part, long-short, long-short, like a lilt.
        val flips = if (yodel <= 0f) 0 else 1 + Math.round(yodel * 5)
        val flipSpan = maxOf(hold + 0.4f * (length - hold), 0.3f)
        val weights = FloatArray(flips + 1) { if (it % 2 == 0) 1.4f else 1f }
        val flipAt = FloatArray(flips).also { at ->
            var sum = 0f
            for (k in 0 until flips) { sum += weights[k]; at[k] = sum / weights.sum() * flipSpan }
        }

        var phase = 0.0
        var cycle = 0L
        var cycleAmp = 1f
        var head = 0f
        var headSemis = HEAD_SEMIS[0]
        var headSince = 0f
        var catch = 0f
        var f0 = baseHz
        for (i in 0 until frames) {
            val t = i.toFloat() / rate
            if (i % CONTROL_BLOCK == 0) {
                // Which register, and how far through the flip: a quick S-curve, not an easing glide.
                head = 0f
                catch = 0f
                for ((n, at) in flipAt.withIndex()) {
                    val x = ((t - at) / FLIP_SECONDS).coerceIn(0f, 1f)
                    val s = x * x * (3f - 2f * x)
                    if (t >= at) {
                        head = if (n % 2 == 0) s else 1f - s
                        if (n % 2 == 0) { headSemis = HEAD_SEMIS[(n / 2) % HEAD_SEMIS.size]; headSince = at }
                    }
                    // The catch in the voice at the break: a brief dip, deepest mid-flip.
                    val d = (t - at) / FLIP_SECONDS
                    if (d in 0f..1f) catch = maxOf(catch, sin(PI.toFloat() * d))
                }
                var cents = 100f * headSemis * head
                val held = (t - headSince - 0.12f).coerceAtLeast(0f)
                cents += head * minOf(1f, held / 0.15f) * HEAD_VIBRATO_CENTS * sin(2f * PI.toFloat() * HEAD_VIBRATO_HZ * held)
                f0 = baseHz * 2f.pow(cents / 1200f)
                // The vowel follows the register: "oh" in the chest, "ee" in the head.
                val v1 = CHEST_VOWEL[0] + (HEAD_VOWEL[0] - CHEST_VOWEL[0]) * head
                val v2 = CHEST_VOWEL[1] + (HEAD_VOWEL[1] - CHEST_VOWEL[1]) * head
                f1.set(v1.toDouble().coerceAtLeast(f0 * 1.15), 90.0, rate)
                f2.set(v2.toDouble(), 120.0, rate)
                f3.set(2600.0, 250.0, rate)
                // The whistle tracks the chest note.
                val whz = (pathAt(path, (t / (length * 0.9f)).coerceIn(0f, 1f)) * baseHz).toDouble()
                whistleA.set(whz, WHISTLE_BW, rate)
                whistleB.set(whz, WHISTLE_BW, rate)
            }
            val prev = phase
            phase += f0 / rate
            if (phase.toLong() != prev.toLong()) {
                cycle++
                // GROWL: every other pulse damped, the false folds beating at half the pitch.
                // A pulse ends closed (the glottal rest is 0), so the switch never clicks.
                cycleAmp = if (cycle % 2L == 1L) 1f - GROWL_DAMP * growl else 1f
            }
            val g = Vox.glottal(phase)
            val n = noise.next()
            // Head voice: the same pulse, softened (fewer harmonics) and breathier.
            val soft = soften.lp(g, 2.5f * f0) * 2.2f
            var src = (1f - head) * g + head * (soft + 0.08f * n)
            src *= cycleAmp * (1f - 0.4f * catch)
            // The rasp: noise shaped by the pulse.
            src += growl * RASP * rasp.lp(n, 2500f) * abs(g)
            val s = src.toDouble()
            val chest = 1.0 - head
            val body = droneGain * (f1.process(s) + 0.35 * s * chest) + 0.9 * head * f2.process(s) + 0.12 * f3.process(s)
            // The whistle steps aside while the voice is up in its head.
            val whistled = chest * whistleGain * 6.0 * whistleB.process(whistleA.process(s) * 3.0)
            out[i] = ((body + whistled) * env.at(t)).toFloat()
        }
        return out
    }

    /** The whistle's harmonic at [frac] of the note: a smoothstep between the walk's points, so it hops harmonic to harmonic. */
    private fun pathAt(path: FloatArray, frac: Float): Float {
        for (k in 0 until path.size - 1) {
            if (frac <= MELODY_TIMES[k + 1]) {
                val x = ((frac - MELODY_TIMES[k]) / (MELODY_TIMES[k + 1] - MELODY_TIMES[k])).coerceIn(0f, 1f)
                return path[k] + (path[k + 1] - path[k]) * x * x * (3f - 2f * x)
            }
        }
        return path.last()
    }
}
