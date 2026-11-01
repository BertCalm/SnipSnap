package com.snipsnap.synth

import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.pow

/**
 * The accepted R4 pluck-to-ring gesture, stored in the instrument rather than its audition.
 * SPEED changes the gate and source-envelope clock after the unchanged first 35 ms. The
 * oscillator and resonator clocks remain at the played pitch and physical damping times.
 * These are authored trajectories; no moving physical gate mechanism is claimed.
 */
internal object LatticeMotion {
    const val ATTACK_SECONDS = .035
    const val REFERENCE_EXCITATION_SECONDS = .46
    const val REFERENCE_DURATION_SECONDS = 2.0
    const val LAST_MOVEMENT_SECONDS = .44
    const val TAIL_SECONDS = REFERENCE_DURATION_SECONDS - REFERENCE_EXCITATION_SECONDS
    const val MAX_HOLD_SECONDS = 3.24
    private const val CACHE_LIMIT = 8

    private data class Frame(val time: Double, val turn: Double, val gates: Double,
        val twist: Double, val color: Double, val reject: Double)

    // Frozen from the owner's accepted B. The audition retains its independent saved frames.
    private val frames = listOf(
        Frame(0.0, 0.0, 2.0, .5, 0.0, 0.0),
        Frame(.035, 0.0, 2.0, .5, 0.0, 0.0),
        Frame(.12, 1.0, 4.0, .25, 1.0, .15),
        Frame(.18, 1.0, 4.0, .25, 1.0, .15),
        Frame(.24, 1.0, 16.0, 1.0, 0.0, .65),
        Frame(.34, 1.0, 16.0, 1.0, 0.0, .85),
        Frame(.44, 1.0, 2.0, 1.0, 0.0, 1.0),
        Frame(2.0, 1.0, 2.0, 1.0, 0.0, 1.0),
    )

    class Plan internal constructor(val speed: Float, val scale: Double,
        private val blocks: Array<Block>) {
        val movementSeconds: Double get() = physicalTime(LAST_MOVEMENT_SECONDS, scale)
        val compiledBlocks: Int get() = blocks.size

        /** After closure, one final transfer is shared by the remainder of every finite HOLD. */
        fun block(index: Int): Block = blocks[index.coerceIn(0, blocks.lastIndex)]

        fun sourceTime(seconds: Double): Double = referenceTime(seconds, scale)
    }

    class Block internal constructor(val geometry: Lattice.Geometry,
        val drives: Array<DoubleArray>, val reject: Double)

    private val cache = object : LinkedHashMap<Int, Plan>(CACHE_LIMIT, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Plan>?): Boolean = size > CACHE_LIMIT
    }

    fun timeScale(speed: Float): Double {
        require(speed.isFinite() && speed in 0f..1f) { "TRACE SPEED must be finite in 0..1" }
        // Preserve an exact identity clock for the accepted reference, without roundoff.
        return if (speed == .5f) 1.0 else 2.0.pow(1.0 - 2.0 * speed.toDouble())
    }

    fun referenceTime(seconds: Double, scale: Double): Double = when {
        scale == 1.0 || seconds <= ATTACK_SECONDS -> seconds
        else -> ATTACK_SECONDS + (seconds - ATTACK_SECONDS) / scale
    }

    fun physicalTime(seconds: Double, scale: Double): Double = when {
        scale == 1.0 || seconds <= ATTACK_SECONDS -> seconds
        else -> ATTACK_SECONDS + (seconds - ATTACK_SECONDS) * scale
    }

    fun settings(speed: Float, hold: Float, midi: Int, velocity: Float): Lattice.Settings {
        val scale = timeScale(speed)
        val excitation = physicalTime(REFERENCE_EXCITATION_SECONDS, scale) + MAX_HOLD_SECONDS * hold
        return Lattice.Settings(midi = midi, velocity = velocity, turn = 0.0, gateCount = 2.0,
            color = 0.0, reject = 0.0, durationSeconds = excitation + TAIL_SECONDS,
            excitationSeconds = excitation, haloEnabled = true,
            sourceProfile = Lattice.SourceProfile.PLUCK, contour = Lattice.Contour.PLUCK)
    }

    fun controls(seconds: Double, speed: Float = .5f): Lattice.Controls =
        referenceControls(referenceTime(seconds, timeScale(speed)))

    private fun referenceControls(time: Double): Lattice.Controls {
        val index = frames.indexOfFirst { it.time >= time }.let { if (it < 0) frames.lastIndex else it }
        val a = frames[(index - 1).coerceAtLeast(0)]
        val b = frames[index]
        val position = if (a.time == b.time) 0.0 else ((time - a.time) / (b.time - a.time)).coerceIn(0.0, 1.0)
        val smooth = position * position * (3.0 - 2.0 * position)
        fun mix(x: Double, y: Double) = x + (y - x) * smooth
        return Lattice.Controls(turn = mix(a.turn, b.turn), twist = mix(a.twist, b.twist),
            color = mix(a.color, b.color), reject = mix(a.reject, b.reject), gateCount = mix(a.gates, b.gates))
    }

    /** Coefficients depend only on SPEED, never the note, velocity, HOLD or elapsed render. */
    fun plan(speed: Float, cancelled: () -> Boolean = { false }): Plan {
        checkCancelled(cancelled)
        val scale = timeScale(speed)
        val key = speed.toRawBits()
        synchronized(cache) { cache[key]?.let { return it } }
        val rate = Dsp.RATE * Dsp.OVERSAMPLE
        val lastBlock = ceil(physicalTime(LAST_MOVEMENT_SECONDS, scale) * rate / Lattice.CONTROL_BLOCK).toInt()
        val blocks = Array(lastBlock + 1) { index ->
            checkCancelled(cancelled)
            val controls = referenceControls(referenceTime(index.toDouble() * Lattice.CONTROL_BLOCK / rate, scale))
            val geometry = Lattice.compile(controls)
            Block(geometry, Lattice.motionDrives(geometry), controls.reject)
        }
        checkCancelled(cancelled)
        val built = Plan(speed, scale, blocks)
        // Build outside the lock so one canceled/cold speed does not block a warm pad render.
        return synchronized(cache) { cache[key] ?: built.also { cache[key] = it } }
    }

    internal fun cachedPlanCount(): Int = synchronized(cache) { cache.size }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (Thread.currentThread().isInterrupted || cancelled()) throw CancellationException("LATTICE render interrupted")
    }
}
