package com.snipsnap.shell

import com.snipsnap.kit.KitPreview
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * SURFACE's modulators: what lets a sound keep moving while the finger is
 * elsewhere, and what makes a print a performance rather than a loop.
 *
 * Two slots ([SLOTS]), each a [Shape] at a tempo-snapped rate with a depth,
 * aimed at one [Target] - one of the seven macros, one of GRAIN's own
 * knobs, or the finger itself ([Target.X], [Target.Y]). Both may aim at
 * the same target, which is how a macro gets two. A slot's output is a
 * signed offset added to whatever the mode and the finger already say for
 * that target, before its own 0..1 door - the engine's for the macros and
 * the knobs (`SurfaceEngine::applyControl`), [TouchSurface.nudged] for the
 * finger - so a sine on CUTOFF swings around the finger's cutoff in XY,
 * around the corner blend's in MORPH, and around "wide open" in GRAIN; a
 * ramp on POSITION walks the cloud through the sample on its own; and a
 * RANDOM on X lands the loop on a new pitch every bar, or on both X and Y
 * in MORPH jumps between corners on the bar - a sequencer without a
 * sequencer. The finger is never overridden, only nudged.
 *
 * Everything here is pure and runs at screen rate in the surface's frame
 * loop; the engine's own per-sample smoother (`ParameterSmoother`) turns
 * the 60 Hz steps into a glide, exactly as it does for the finger - a
 * modulator is just one more writer of targets, which is why this costs
 * the engine nothing new.
 *
 * Time is seconds from one origin shared by every slot, so two slots at
 * related rates stay locked to each other and to the bar. RANDOM is a
 * sample-and-hold, one value per cycle, drawn from the cycle's own index -
 * deterministic, so the same bar always throws the same die, and a print
 * made twice moves the same way.
 */
object Modulator {

    /**
     * What a slot moves. The first seven are the engine's macros in
     * `MacroState` order and the next four GRAIN's knobs, live only in that
     * mode: those eleven cross the bridge as one array, by ordinal
     * ([ENGINE_TARGETS], [engineOffsets]). [X] and [Y] are the finger
     * itself - the position every mode reads, and the sample blend with it
     * - nudged on this side of the bridge ([TouchSurface.nudged]) before the
     * engine ever sees it, so they never cross at all. Appended last on
     * purpose: the engine takes its eleven by ordinal, and a new target in
     * front of POSITION would silently retarget every slot ever saved.
     */
    enum class Target(
        /** Crosses the bridge as one of the engine's offsets ([engineOffsets]); false for the two the surface applies itself. */
        val onEngine: Boolean = true,
    ) {
        PITCH, CUTOFF, RESONANCE, DRIVE, CRUSH, ECHO, SPRING, SIZE, DENSITY, SPRAY, POSITION,

        /** The finger's own X, in every mode: pitch in XY, POSITION in GRAIN, the corner blend and the sample blend in MORPH and VECTOR. */
        X(onEngine = false),

        /** The finger's own Y: cutoff in XY, the pitch axis in GRAIN, the corner blend in MORPH and VECTOR. */
        Y(onEngine = false),
    }

    /**
     * How many of [Target]'s entries the engine takes, and that they are
     * the first ones: `SurfaceEngine.MOD_TARGETS` (Kotlin) and
     * `kModTargets` (`SurfaceEngine.h`) are this number by hand, and a test
     * holds it at eleven so a change here cannot drift from them unnoticed.
     */
    val ENGINE_TARGETS: Int = Target.entries.count { it.onEngine }

    /**
     * The engine's slice of [offsets]'s array: the first [ENGINE_TARGETS],
     * by ordinal. What `SurfaceEngine.setModulation` is handed; the finger's
     * two stay behind for [TouchSurface.nudged].
     */
    fun engineOffsets(offsets: FloatArray): FloatArray {
        require(offsets.size == Target.entries.size) { "one offset per target (${Target.entries.size}), got ${offsets.size}" }
        return offsets.copyOf(ENGINE_TARGETS)
    }

    enum class Shape { SINE, RAMP, RANDOM }

    /** How many slots the surface has; a fixed pool, not one per macro - two aimed at one target is "two per macro". */
    const val SLOTS = 2

    /** RATE's choices, as a fraction of a bar at the kit's tempo. */
    val RATES: List<Float> = listOf(1f / 16f, 1f / 8f, 1f / 4f, 1f / 2f, 1f, 2f, 4f)

    /** The index into [RATES] a fresh slot starts at: one bar. */
    const val DEFAULT_RATE_INDEX = 4

    /**
     * DEPTH 1 swings half the target's whole travel either way - the finger
     * in the middle of the pad then reaches both rails. Bipolar rather than
     * one-sided so a modulator at rest sits *on* the finger, not above it.
     */
    const val HALF_SWING = 0.5f

    data class Slot(
        val target: Target = Target.CUTOFF,
        val shape: Shape = Shape.SINE,
        val rateIndex: Int = DEFAULT_RATE_INDEX,
        /** 0 is off - the slot exists but moves nothing. */
        val depth: Float = 0f,
    ) {
        init {
            require(rateIndex in RATES.indices) { "rate is an index into RATES (0..${RATES.lastIndex}), got $rateIndex" }
            require(depth.isFinite() && depth in 0f..1f) { "depth is 0..1, got $depth" }
        }
    }

    /** Every slot present and silent: what a kit has before the MOD row is touched. */
    val OFF: List<Slot> = List(SLOTS) { Slot() }

    fun rateLabel(rateIndex: Int): String {
        val bars = RATES[rateIndex]
        return if (bars >= 1f) Copy.countOf(bars.toInt(), "BAR", "BARS") else "1/${(1f / bars).toInt()} BAR"
    }

    /**
     * One cycle of [RATES] at [bpm], in seconds - one bar of [PrintLength]'s
     * own arithmetic, scaled by the fraction, so BARS on PRINT and RATE here
     * cannot disagree about how long a bar is. A kit with no tempo runs at
     * [KitPreview.DEFAULT_BPM], the same stand-in GROOVE plays such a kit at.
     */
    fun periodSeconds(rateIndex: Int, bpm: Float?): Float =
        PrintLength.seconds(1, bpm ?: KitPreview.DEFAULT_BPM) * RATES[rateIndex]

    /**
     * The wave, -1..1, at [phase] 0..1 of cycle number [cycle]. SINE starts
     * at zero and rises; RAMP rises from -1 to 1 and drops back; RANDOM
     * holds one value for the whole cycle, drawn from [cycle] and [seed]
     * so it is the same value every time that cycle comes round.
     */
    fun wave(shape: Shape, phase: Float, cycle: Long, seed: Int = 0): Float {
        val p = phase.coerceIn(0f, 1f)
        return when (shape) {
            Shape.SINE -> sin(2.0 * PI * p).toFloat()
            Shape.RAMP -> 2f * p - 1f
            Shape.RANDOM -> hash01(cycle, seed) * 2f - 1f
        }
    }

    /**
     * [slot]'s offset at [seconds] from the shared origin: the wave at its
     * rate, scaled by depth and [HALF_SWING]. Exactly 0 at depth 0 whatever
     * the time, so an unused slot changes nothing - not even by float dust.
     */
    fun offset(slot: Slot, seconds: Double, bpm: Float?, seed: Int = 0): Float {
        if (slot.depth <= 0f) return 0f
        val period = periodSeconds(slot.rateIndex, bpm).toDouble()
        val cycles = (if (seconds.isFinite() && seconds > 0.0) seconds else 0.0) / period
        val cycle = floor(cycles)
        val phase = (cycles - cycle).toFloat()
        return wave(slot.shape, phase, cycle.toLong(), seed) * slot.depth * HALF_SWING
    }

    /**
     * Every slot summed onto its target, one float per [Target] in ordinal
     * order, clamped to -1..1 - the array the engine adds to its macros.
     * A slot's own index is its RANDOM seed, so two RANDOM slots on one
     * target throw two dice rather than the same one twice.
     */
    fun offsets(slots: List<Slot>, seconds: Double, bpm: Float?): FloatArray {
        val out = FloatArray(Target.entries.size)
        slots.forEachIndexed { i, slot ->
            val t = slot.target.ordinal
            out[t] = (out[t] + offset(slot, seconds, bpm, seed = i)).coerceIn(-1f, 1f)
        }
        return out
    }

    /** A 0..1 value from (cycle, seed), spread well enough that neighbouring cycles do not walk. */
    private fun hash01(cycle: Long, seed: Int): Float {
        var x = cycle * -7046029254386353131L + seed * 0x9E3779B97F4A7C15uL.toLong() + 0x2545F4914F6CDD1DL
        x = x xor (x ushr 33)
        x *= -49064778989728563L
        x = x xor (x ushr 29)
        // 24 bits: exact in a float, and never quite 1.
        return ((x ushr 40) and 0xFFFFFF).toFloat() / 16777216f
    }
}
