package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max

/**
 * WAH - a resonant band-pass swept between a heel and a toe, the way a wah pedal's foot sweeps it,
 * by one of three moves: TOUCH (the pad's own envelope opens it on the hit and closes it as the
 * note dies), AUTO (a sine with a flat top) or ROCK (one heel-to-toe-to-heel gesture, then it waits).
 * The three are weights, not a switch: the app has no per-section dials, and AMT fades every
 * macro of a recipe toward its neutral, which would flip a stepped MODE macro part-way; a recipe
 * chooses a move by setting its weight to 1, and AMT shrinks the sweep smoothly.
 *
 * Sits after CONTOUR and before SQUASH: a wah is the first pedal on the board, in front of the
 * compressor and the amp. The filter is linear, so there is nothing to oversample. No tail.
 *
 * The moves are the owner's round-two prototypes (docs/superpowers/specs/2026-10-17-wah-section-design.md):
 * the shipped CONTOUR recipe this replaces settled in about 30 ms; these keep a resonant
 * filter travelling for hundreds of milliseconds. TOUCH and ROCK park at the heel after their
 * move (about 3.25 to 3.5 s of a 4 s guitar note at the default SPEED); AUTO never parks.
 * The position is stretched by [REACH_GAIN] and clamped, so AUTO rests at the toe for 39 percent of
 * each cycle at AMT 100 and 14 percent at AMT 70, and ROCK at full weight is at the toe from 152 ms to
 * 446 ms at the default SPEED: its audible rise is 152 ms, not 250.
 */
object Wah {

    val MACROS: List<MacroSpec> = listOf(
        MacroSpec("TOUCH", 0f),                       // the envelope-follower move's weight
        MacroSpec("AUTO", 0f),                        // the LFO move's weight
        MacroSpec("ROCK", 0f),                        // the one-rock gesture's weight
        MacroSpec("SPEED", 0.5f, neutral = 0.5f),     // LFO rate SLOW_HZ..FAST_HZ; the follower's release and the rock's length scale with the LFO period
        MacroSpec("Q", 0.6f, neutral = 0.6f),         // resonance: damping OPEN..TIGHT
    )

    /** The sweep's ends (shape): a decade, the spike's. */
    const val HEEL_HZ = 280f
    const val TOE_HZ = 2_800f

    /** The unfiltered signal kept under the band-pass (shape): minus 26 dB. */
    const val DRY = 0.05f

    /** SPEED 0 to 1 (exponential): 2 Hz at the default 0.5. */
    const val SLOW_HZ = 0.5f
    const val FAST_HZ = 8f
    private const val DEFAULT_HZ = 2f

    /** The envelope follower (shape): slow, so a pluck's own decay does not cancel the sweep. */
    private const val FOLLOW_ATTACK_S = 0.020f
    private const val FOLLOW_RELEASE_S = 0.100f

    /** TOUCH: the pad's own loudest moment is the toe, this many dB below it is the heel. */
    private const val FOLLOW_SPAN_DB = 8f

    /** ROCK: heel to toe, then back (shape), at the default SPEED. */
    private const val ROCK_UP_S = 0.250f
    private const val ROCK_DOWN_S = 0.500f

    /** Q 0..1 maps to damping OPEN (gentle) .. TIGHT (squealing); 0.6 is about 0.23, a Q of about 4.35 (the owner's prototype used 0.2, a Q of 5). */
    private const val DAMPING_OPEN = 0.8f
    private const val DAMPING_TIGHT = 0.1f

    /** The weights times this, capped at 1: the chip's first tap at the pad sheet's default AMT 0.7 is full strength (shape), and it stretches the position too, which holds AUTO and ROCK at the toe for part of each move. */
    private const val REACH_GAIN = 1.5f

    /** Weights summing under this are a bypass. */
    private const val BYPASS = 0.001f

    /** The centre never goes above this share of the sample rate. */
    private const val CENTRE_CEILING = 0.45f

    fun defaults(): Map<String, Float> = MACROS.associate { it.name to it.default }

    fun scramble(random: kotlin.random.Random): Map<String, Float> =
        MACROS.associate { it.name to random.nextFloat() }

    /** TOUCH + AUTO + ROCK times [REACH_GAIN], capped at 1: how much of the filtered signal is heard, and how far the sweep travels. */
    internal fun reach(macros: Map<String, Float>): Float =
        (REACH_GAIN * ((macros["TOUCH"] ?: 0f) + (macros["AUTO"] ?: 0f) + (macros["ROCK"] ?: 0f))).coerceIn(0f, 1f)

    /** AUTO's position, 0 (heel) to 1 (toe): a sine that starts at the heel, which the stretch by [REACH_GAIN] flat-tops at the toe (39 percent of each cycle at weight 1, 14 percent at 0.7). */
    internal fun autoAt(t: Float, hz: Float): Float = (0.5 - 0.5 * cos(2.0 * PI * hz * t)).toFloat()

    /** ROCK's position at [t] seconds: up over 250 ms times [scale], down over 500 ms times [scale], then the heel; at weight 1 the stretch by [REACH_GAIN] holds the toe from 152 ms to 446 ms at scale 1. */
    internal fun rockAt(t: Float, scale: Float): Float {
        val up = ROCK_UP_S * scale
        val down = ROCK_DOWN_S * scale
        return when {
            t < up -> (0.5 - 0.5 * cos(PI * t / up)).toFloat()
            t < up + down -> (0.5 + 0.5 * cos(PI * (t - up) / down)).toFloat()
            else -> 0f
        }
    }

    /** TOUCH's position for a follower level [db] under the pad's own envelope peak: 0 dB is the toe, [FOLLOW_SPAN_DB] under is the heel. */
    internal fun touchAt(db: Float): Float = (1f + db / FOLLOW_SPAN_DB).coerceIn(0f, 1f)

    /** The band-pass centre for a position 0..1: exponential from the heel to the toe, under [CENTRE_CEILING] of [rate]. */
    internal fun centreHz(pos: Float, rate: Int): Float = minOf(Dsp.expMap(pos, HEEL_HZ, TOE_HZ), CENTRE_CEILING * rate)

    /** TOUCH's position for every frame: the larger channel's envelope, in dB under the pad's own peak (a first pass: the rack renders whole pads). */
    internal fun touchPositions(snip: Snip, scale: Float): FloatArray {
        val rate = snip.sampleRate
        val attack = 1f - exp(-1.0 / (rate * FOLLOW_ATTACK_S)).toFloat()
        val release = 1f - exp(-1.0 / (rate * FOLLOW_RELEASE_S * scale)).toFloat()
        val env = FloatArray(snip.frameCount)
        var e = 0f
        var peak = 0f
        for (f in 0 until snip.frameCount) {
            var a = 0f
            for (ch in 0 until snip.channels) a = max(a, abs(snip.samples[f * snip.channels + ch]))
            e += (if (a > e) attack else release) * (a - e)
            env[f] = e
            if (e > peak) peak = e
        }
        if (peak <= 0f) return FloatArray(snip.frameCount)
        return FloatArray(snip.frameCount) { f -> touchAt(20f * log10((env[f] / peak).coerceAtLeast(1e-6f))) }
    }

    fun process(snip: Snip, macros: Map<String, Float> = emptyMap()): Snip = run(snip, macros, minCycles = 0f)

    /**
     * AUTO's rate with a floor of [minCycles] cycles over a pad of [frames] frames at [rate]: the
     * shipped [lfoHz] where the pad is long enough to hear that many cycles, and the rate that fits
     * exactly that many where it is not. Zero cycles is no floor at all.
     */
    internal fun autoHz(lfoHz: Float, frames: Int, rate: Int, minCycles: Float): Float =
        if (minCycles <= 0f || frames <= 0) lfoHz else max(lfoHz, minCycles * rate / frames)

    /**
     * [process] with a floor on AUTO's rate (see [autoHz]), so a short pad hears at least [minCycles]
     * of the sweep: on an 85 ms hat at the default 2 Hz the shipped move completes a sixth of a
     * cycle, a dull band-pass, and the floor makes it one whole wah. A listening door for the
     * pedal board's Stage 1b (docs/superpowers/specs/2026-10-19-pedal-board-brainstorm.md), not a
     * macro: only AUTO's rate moves; TOUCH's release and ROCK's length keep the SPEED they have.
     * At [minCycles] 0 it is [process] itself, sample for sample.
     */
    internal fun fitted(snip: Snip, macros: Map<String, Float>, minCycles: Float): Snip = run(snip, macros, minCycles)

    private fun run(snip: Snip, macros: Map<String, Float>, minCycles: Float): Snip {
        val m = defaults().toMutableMap()
        for ((k, v) in macros) if (m.containsKey(k)) m[k] = v.coerceIn(0f, 1f)
        val touch = m.getValue("TOUCH")
        val auto = m.getValue("AUTO")
        val rock = m.getValue("ROCK")
        if (touch + auto + rock < BYPASS) return Snip(snip.samples.copyOf(), snip.channels, snip.sampleRate)

        val rate = snip.sampleRate
        val reach = reach(m)
        val lfoHz = Dsp.expMap(m.getValue("SPEED"), SLOW_HZ, FAST_HZ)
        val scale = DEFAULT_HZ / lfoHz
        val autoHz = autoHz(lfoHz, snip.frameCount, rate, minCycles)
        val k = Dsp.expMap(1f - m.getValue("Q"), DAMPING_TIGHT, DAMPING_OPEN)
        val touched = if (touch > 0f) touchPositions(snip, scale) else null
        val filters = Array(snip.channels) { Dsp.TptSvf(rate) }

        val out = FloatArray(snip.samples.size)
        for (f in 0 until snip.frameCount) {
            // One position across the frame, so a stereo pair sweeps together.
            val t = f.toFloat() / rate
            var move = 0f
            if (touched != null) move += touch * touched[f]
            if (auto > 0f) move += auto * autoAt(t, autoHz)
            if (rock > 0f) move += rock * rockAt(t, scale)
            val centre = centreHz((REACH_GAIN * move).coerceIn(0f, 1f), rate)
            for (ch in 0 until snip.channels) {
                val i = f * snip.channels + ch
                val x = snip.samples[i]
                filters[ch].process(x, centre, k)
                val y = k * filters[ch].band + DRY * x
                out[i] = (1f - reach) * x + reach * y
            }
        }

        var outPeak = 0f
        for (v in out) { val a = abs(v); if (a > outPeak) outPeak = a }
        val inPeak = snip.peak()
        if (inPeak > 0f && outPeak > 0f) {
            val gain = inPeak / outPeak
            for (i in out.indices) out[i] *= gain
        }
        return Snip(out, snip.channels, rate)
    }
}
