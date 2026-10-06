package com.snipsnap.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Shared breath and score for CIRCUIT, independent of walking geometry.
 * Each complete cell repeats an opening/weak figure, affirmation and settling gesture.
 * Returned accents may invite an authored answer; causality and budgets belong to the engine.
 */
internal class CircuitPhrase(
    private val voice: CircuitVoice,
    phraseSeconds: Double,
    private val loopSeconds: Double,
    private val paceHz: Double,
    private val held: Boolean,
    private val breath: Double,
) {
    companion object {
        const val ORIGIN = .16
        const val RELEASE_PAD = .12

        fun fittedPaceHz(phraseSeconds: Double, requestedPaceHz: Double): Double {
            val available = phraseSeconds - RELEASE_PAD - ORIGIN
            return 4.0 * max(1, (available * requestedPaceHz / 4.0).roundToInt()) / available
        }
    }

    data class ResponseTarget(
        val cell: Int,
        val source: Int,
        val kind: String,
        val timeSeconds: Double,
        val cueSource: Int,
    )

    private val origin = ORIGIN
    val cellCount: Int
    val cellSeconds: Double
    private val phases = doubleArrayOf(0.0, .12, .30, .48, .62, .76, .90, 1.0)
    private val efforts = doubleArrayOf(.10, .75, 1.0, .74,
        if (voice == CircuitVoice.ANSWER) .12 else .28, .50, .22, .10)
    private val leadPhase = when (voice) {
        CircuitVoice.ROOT -> .36
        CircuitVoice.PROCESSION -> .40
        CircuitVoice.ANSWER -> .32
        CircuitVoice.VOICED -> .35
        CircuitVoice.EXPANSE -> .48
        CircuitVoice.CONFLUENCE -> .38
    }

    init {
        require(phraseSeconds.isFinite() && phraseSeconds > origin + .12)
        require(loopSeconds.isFinite() && loopSeconds >= 0.0 && (!held || loopSeconds > 0.0))
        require(paceHz.isFinite() && paceHz > 0.0)
        require(breath.isFinite() && breath in 0.0..1.0)
        // A complete four-pulse figure fits the finite breath. At ROOT defaults
        // this gives a roughly 0.73-second pulse, near the reference's 0.75 s.
        // Held clocks close eight steps, so every loop contains whole pairs of cells.
        val available = if (held) loopSeconds else phraseSeconds - RELEASE_PAD - origin
        cellCount = max(1, (available * paceHz / 4.0).roundToInt())
        cellSeconds = available / cellCount
    }

    /** A single periodic gesture shared by source pressure and score dynamics. */
    fun effort(timeSeconds: Double): Double {
        require(timeSeconds.isFinite())
        val position = (timeSeconds - origin) / cellSeconds
        val bounded = if (held) position else position.coerceIn(0.0, cellCount.toDouble())
        val archPhase = if (held) timeSeconds / loopSeconds
            else (position / cellCount).coerceIn(0.0, 1.0)
        val arch = .80 + .20 * sin(PI * archPhase).let { it * it }
        return profile(warp(bounded - floor(bounded))) * arch
    }

    fun targets(velocity: Double): List<Circuit.Event> {
        require(velocity.isFinite() && velocity in 0.0..1.0)
        val result = ArrayList<Circuit.Event>()
        val leadKind = if ((voice == CircuitVoice.ANSWER && cellSeconds >= 1.6) ||
            (voice == CircuitVoice.VOICED && cellSeconds >= 1.4))
            "UH_HUH" else "GRUNT"
        val leadDuration = if (leadKind == "UH_HUH") .58 else .28
        val leadAt = clockPhase(leadPhase) * cellSeconds
        val closingAt = clockPhase(.84) * cellSeconds
        // This analytical reservation covers both the lead-to-close interval and
        // close-to-next-lead interval, including the real held-cycle boundary.
        val closingVoice = voice == CircuitVoice.VOICED &&
            closingAt - leadAt >= leadDuration + .08 &&
            cellSeconds + leadAt - closingAt >= .28 + .08

        fun add(cell: Int, source: Int, kind: String, phase: Double, baseEnergy: Double, reason: String) {
            val time = onset(cell, phase)
            result.add(Circuit.Event(source, kind, time,
                velocity * baseEnergy * (.5 + .5 * effort(time)), reason = reason))
        }
        for (cell in 0 until cellCount) {
            add(cell, 4, "CLAPPER", .04, .66, "opening-wood")
            add(cell, 1, "TUBE_ACCENT", .04, .55 + .25 * breath, "opening-pulse")
            add(cell, 3, "RATTLE", .04, .32, "opening-support")
            add(cell, 3, "RATTLE", .29, .24, "rising-support")
            add(cell, 4, "CLAPPER", .54, .40, "weak-wood")
            add(cell, 1, "TUBE_ACCENT", .54, (.55 + .25 * breath) * .50, "weak-pulse")
            add(cell, 3, "RATTLE", .54, .18, "weak-support")
            add(cell, 6, leadKind, leadPhase, .52, "affirmation")
            add(cell, 1, "TUBE_ACCENT", .50, (.55 + .25 * breath) * .45, "answer-invitation")
            if (voice in listOf(CircuitVoice.PROCESSION, CircuitVoice.VOICED, CircuitVoice.CONFLUENCE)) {
                add(cell, 4, "CLAPPER", .50, .26, "answer-invitation")
            }
            add(cell, 5, "CLAY", .79, .60, "settling")
            add(cell, 3, "RATTLE", .79, .16, "settling-support")
            if (closingVoice) add(cell, 6, "GRUNT", .84, .26, "closing-agreement")
            if (paceHz >= 3.0) add(cell, 3, "RATTLE", .415, .16, "quiet-subdivision")
            if (paceHz >= 4.5) {
                add(cell, 4, "CLAPPER", .79, .20, "settling-wood")
                add(cell, 3, "RATTLE", .665, .14, "answer-support")
            }
        }
        return result.sortedWith(compareBy<Circuit.Event> { it.timeSeconds }.thenBy { it.source })
    }

    /** Exactly one intended opportunity per cell, not permission to invent a filler. */
    fun responseTargets(): List<ResponseTarget> {
        val vocal = voice == CircuitVoice.ROOT || voice == CircuitVoice.ANSWER || voice == CircuitVoice.EXPANSE
        val kind = if (vocal) {
            if (voice == CircuitVoice.ANSWER && cellSeconds >= 1.6) "UH_HUH" else "GRUNT"
        } else "CLAY"
        return (0 until cellCount).map { cell ->
            ResponseTarget(cell, if (vocal) 6 else 5, kind,
                onset(cell, if (vocal) .72 else .60), if (vocal) 1 else 4)
        }.sortedBy { it.timeSeconds }
    }

    private fun profile(phase: Double): Double {
        for (upper in 1 until phases.size) {
            if (phase <= phases[upper]) {
                val fraction = (phase - phases[upper - 1]) / (phases[upper] - phases[upper - 1])
                val blend = fraction * fraction * (3 - 2 * fraction)
                return efforts[upper - 1] * (1 - blend) + efforts[upper] * blend
            }
        }
        return efforts.last()
    }

    private fun warp(phase: Double) = phase + .006 * sin(2 * PI * phase)

    // Targets use the same warped clock as effort: invert the monotonic phase map
    // so their named phases and dynamics agree with the breath at their onsets.
    private fun clockPhase(phase: Double): Double {
        var value = phase
        repeat(6) {
            value -= (warp(value) - phase) / (1 + .012 * PI * cos(2 * PI * value))
        }
        return value.coerceIn(0.0, 1.0)
    }

    private fun onset(cell: Int, phase: Double): Double {
        val time = origin + (cell + clockPhase(phase)) * cellSeconds
        return if (held) time - floor(time / loopSeconds) * loopSeconds else time
    }
}
