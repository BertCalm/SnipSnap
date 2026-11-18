package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * NACRE's held clock: what lets a NACRE voice with a sustained source (GROTTO, saved model 8) hold a real loop. Only
 * models 8 and up use it; PEARL, HARMONICS, SHELL and BUBBLE never call it, so their audio cannot move.
 *
 * Everything after a NACRE source is linear and either time-invariant or moving on the water's clocks. It settles
 * into a loop of length L once two things hold: the source repeats every L, and every water clock makes a whole
 * number of cycles in L. This object supplies the second:
 *  - **The loop length.** Whole note periods in whole frames ([Bore.planLoop]'s rule), at least
 *    [LOOP_TARGET_SECONDS] long: two whole swells of the internal water (`2 / 0.539 Hz`, about 3.71 s).
 *  - **The rates.** Every water rate is replaced by the nearest whole number of cycles per loop ([cycles]): the
 *    internal water makes exactly [FIELD_CYCLES], and the seaweed's 2.0 Hz aperture becomes 7 cycles in a 3.71 s
 *    loop, about 1.89 Hz.
 *  - **The phases.** Read from a loop-relative clock, the frame index modulo the loop ([phase], [fieldSeconds]), so
 *    the wrap is exact by construction rather than close in floating point.
 *
 * A finite note of a held voice uses the same clock, so a finite note and a held one are the same until the source
 * stops. The returns ([heldReturns]) are [Nacre.returns] on that clock; [Nacre.returns] itself is untouched.
 */
internal object NacreHeld {
    /** HOLD's top step is the loop: CAIRN's `HOLD_RESERVED` and WICK's `LOOP_THRESHOLD` value. */
    const val LOOP_THRESHOLD = .99f

    /** Whole swells of the internal water per loop. */
    const val FIELD_CYCLES = 2

    /** The shortest loop: [FIELD_CYCLES] swells of the internal water at its core rate (about 3.711 s). */
    val LOOP_TARGET_SECONDS: Float = (FIELD_CYCLES / NacreVoices.FIELD_HZ).toFloat()

    /** The frames the house seam ([Keys.seamError]) reads on each side of a wrap. */
    const val SEAM_FRAMES = 256

    fun isLoop(hold: Float): Boolean = hold >= LOOP_THRESHOLD

    /** Whole periods of [hz] in whole frames at [Dsp.RATE], at least [LOOP_TARGET_SECONDS] long. */
    fun plan(hz: Float): Bore.LoopPlan = Bore.planLoop(hz, LOOP_TARGET_SECONDS)

    /** The whole number of cycles nearest [hz] in a loop of [loopFrames] frames (0 for a clock that does not move). */
    fun cycles(hz: Double, loopFrames: Int): Int = (hz * loopFrames / Dsp.RATE).roundToInt()

    /** The rate a clock of [cycles] cycles per loop runs at, in Hz. */
    fun fittedHz(cycles: Int, loopFrames: Int): Double = cycles * Dsp.RATE.toDouble() / loopFrames

    /** The phase of a clock of [cycles] whole cycles per [period] samples at sample [i]: exactly periodic in [period]. */
    fun phase(i: Int, period: Int, cycles: Int): Double = 2.0 * PI * cycles * (i % period).toDouble() / period

    /** The loop-relative time of sample [i] at [rate] for a loop of [period] samples: what a held [NacreVoices.Field] reads. */
    fun fieldSeconds(i: Int, period: Int, rate: Int): Double = (i % period).toDouble() / rate

    /** NACRE's internal water on the held clock: its depth and phase, at exactly [FIELD_CYCLES] cycles a loop. */
    fun field(model: Int, seed: Long, loopFrames: Int): NacreVoices.Field = NacreVoices.Field(
        depth = NacreVoices.FIELD_DEPTH,
        hz = fittedHz(FIELD_CYCLES, loopFrames),
        phase = NacreVoices.fieldPhase(model, seed),
    )

    /** The seaweed apertures' rate at [current] on a loop of [loopFrames]: [Nacre.apertureRate] fitted to whole cycles. */
    fun apertureCycles(current: Float, loopFrames: Int): Int = cycles(Nacre.apertureRate(current), loopFrames)

    /**
     * [Nacre.returns] on the held clock: the same coral and seaweed paths, delays, low-passes and weights, with the
     * seaweed's aperture at [apertureCycles] whole cycles per [loopFrames] and its phase read loop-relative. Feed-forward
     * and fixed, so a source that repeats every loop gives returns that repeat every loop once the longest delay (0.64 s)
     * has passed. A finite note of a held voice uses this too, so it matches the held note until its source stops.
     */
    fun heldReturns(source: FloatArray, current: Float, seaweed: Boolean, loopFrames: Int): FloatArray {
        val rate = Dsp.RATE
        val delay = if (seaweed) doubleArrayOf(.22, .41, .64) else doubleArrayOf(.10, .21, .34)
        val cuts = if (seaweed) doubleArrayOf(1200.0, 900.0, 650.0) else doubleArrayOf(1600.0, 1200.0, 900.0)
        val weights = doubleArrayOf(.52, .31, .17)
        if (!seaweed && current != NacreVoices.CORE_CURRENT.toFloat()) {
            val flow = current.toDouble() - NacreVoices.CORE_CURRENT.toFloat()
            weights[0] -= .20 * flow; weights[1] += .05 * flow; weights[2] += .15 * flow
        }
        val depth = if (seaweed) Nacre.apertureDepth(current) else 0.0
        val cycles = if (seaweed) apertureCycles(current, loopFrames) else 0
        val peaks = doubleArrayOf(.40, .65, .85)
        val out = DoubleArray(source.size)
        for (path in 0..2) {
            val frames = (delay[path] * rate).roundToInt()
            val a = 1.0 - exp(-2.0 * PI * cuts[path] / rate)
            val peakPhase = -2.0 * PI * 2.0 * peaks[path]
            var low = 0.0
            for (i in out.indices) {
                if (i % 32768 == 0) NacreVoices.stopIfInterrupted()
                val input = if (i >= frames) source[i - frames].toDouble() else 0.0
                low += a * (input - low)
                val aperture = if (!seaweed) 1.0 else if (current == 0f) .55 else {
                    val wave = cos(phase(i, loopFrames, cycles) + peakPhase)
                    if (current == NacreVoices.CORE_CURRENT.toFloat()) 1.0 - depth + depth * (.5 + .5 * wave)
                    else .55 + .5 * depth * wave
                }
                out[i] += weights[path] * low * aperture
            }
        }
        return FloatArray(out.size) { out[it].toFloat() }
    }

    /**
     * How well a held render closes, read on the conditioned 44.1 kHz output [out] around the kept loop
     * `[start, start + loopFrames)`: the house seam ([Keys.seamError]) against the real lap before ([before]: the
     * frames leading into the wrap) and the real lap after ([after]: the frames leaving it), and the whole kept lap
     * against the next one, simulated straight on ([cycleError]: their difference's energy over the lap's own).
     */
    class Closure(val before: Double, val after: Double, val cycleError: Double) {
        val worstSeam: Double get() = maxOf(before, after)
    }

    fun closure(out: FloatArray, start: Int, loopFrames: Int): Closure {
        require(start >= SEAM_FRAMES && start + 2 * loopFrames <= out.size) {
            "a held render needs a lap on each side of its loop: start $start, loop $loopFrames, ${out.size} frames"
        }
        val before = Keys.seamError(out.copyOfRange(start - SEAM_FRAMES, start + loopFrames), SEAM_FRAMES)
        val after = Keys.seamError(out.copyOfRange(start, start + loopFrames + SEAM_FRAMES), SEAM_FRAMES)
        return Closure(before, after, cycleError(out, start, loopFrames))
    }

    /** The energy of lap `[start + loopFrames, start + 2 loopFrames)` minus lap `[start, start + loopFrames)`, over the first lap's. */
    fun cycleError(x: FloatArray, start: Int, loopFrames: Int): Double {
        var diff = 0.0
        var level = 0.0
        for (i in start until start + loopFrames) {
            val d = x[i + loopFrames] - x[i].toDouble()
            diff += d * d
            level += x[i].toDouble() * x[i]
        }
        return if (level == 0.0) 0.0 else diff / level
    }
}
