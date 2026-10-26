package com.snipsnap.synth

import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An authored acoustic capture, not a disk that rolls without losing energy.
 * A contact-anchored excerpt of the 4x modal output is overlapped with its own
 * continuation. Whole-note-period spacing protects the root while a smooth,
 * unity-sum overlap retains the irregular rolling texture. Repeating this
 * capture assumes an external supply; the finite solver's work ledger never
 * claims to fund it.
 */
internal object SkirlHold {
    const val LOOP_THRESHOLD = .99f
    private const val MAX_EXTRA_CYCLES = 6
    // Classifier's private LOOP boundary is strictly greater than 1.5 seconds.
    // Retain complete captures, not silent padding or a different oscillator.
    private const val LOOP_CLASS_SECONDS = 1.5

    data class Diagnostics(
        val captureStartSeconds: Double,
        val cycleSeconds: Double,
        val overlapSeconds: Double,
        val insertedCycles: Int,
        val insertedSeconds: Double,
        val originalRollingSeconds: Double,
        /** Measured on independently conditioned consecutive cycles, before leveling. */
        val seamError: Double = 0.0,
        val cycleDifference: Double = 0.0,
        val exportSpanCycles: Int = 0,
        val exportSpanSeconds: Double = 0.0,
    )

    data class Plan(
        val raw: FloatArray,
        val diagnostics: Diagnostics,
        /** Output-rate frames; zero denotes a finite extended runout. */
        val loopStart: Int = 0,
        val loopEnd: Int = 0,
    )

    fun isLoop(hold: Float): Boolean = hold.isFinite() && hold >= LOOP_THRESHOLD

    private fun interrupted() {
        if (Thread.currentThread().isInterrupted)
            throw CancellationException("SKIRL capture cancelled")
    }

    fun plan(raw: FloatArray, motion: SkirlDynamics.Trace,
        acoustics: SkirlAcoustics.Diagnostics, hz: Double, hold: Float, model: Int): Plan {
        require(hold.isFinite() && hold in 0f..1f && hz.isFinite() && hz > 0)
        require(model in 2..Skirl.MODEL_VERSION)
        val rate = Dsp.RATE
        val oversample = Dsp.OVERSAMPLE
        val target = min(.60, motion.duration * .30)
        val cycles = (target * hz).roundToInt().coerceAtLeast(4)
        // Plan at the output rate: every boundary stays on the same decimator phase.
        val hop = (cycles * rate / hz).roundToInt().coerceAtLeast(512)
        val overlapCycles = (min(.12, hop.toDouble() / rate * .20) * hz)
            .roundToInt().coerceAtLeast(1)
        val overlap = (overlapCycles * rate / hz).roundToInt().coerceIn(4, hop / 3)
        val latestStart = ((motion.duration - .02) * rate).toInt() - hop - overlap
        check(latestStart >= 0) { "SKIRL rolling history is too short for an acoustic capture" }
        val anchor = acoustics.rollingEvents.asSequence().filter { it.feature == 0 }
            .minByOrNull { abs(it.time - motion.duration * .35) }?.time ?: 0.0
        val start = (anchor * rate).roundToInt().coerceIn(0, latestStart)
        val begin = start * oversample
        val length = hop * oversample
        val blend = overlap * oversample
        val core = FloatArray(length)
        for (i in core.indices) {
            if (i and 16383 == 0) interrupted()
            core[i] = if (i < blend) {
                // At the wrap, retain the real next sample of the excerpt;
                // then blend back into its beginning without a level bump.
                val w = .5 - .5 * cos(PI * i / (blend - 1))
                ((1 - w) * raw[begin + length + i] + w * raw[begin + i]).toFloat()
            } else raw[begin + i]
        }
        val held = isLoop(hold)
        // Model 2's four-cycle preparation and one-cycle cut remain exact for
        // saved recipes. Current exports contain a genuine multi-capture span:
        // settle -> preceding span -> kept span -> true sinc lookahead.
        val span = if (!held) 0 else if (model == 2) 1 else
            maxOf(3, floor(LOOP_CLASS_SECONDS * rate / hop).toInt() + 1)
        val settling = if (model == 2) 1 else 2
        val repeats = if (held) settling + 2 * span + 1 else
            (MAX_EXTRA_CYCLES * hold / LOOP_THRESHOLD).roundToInt()
                .coerceIn(0, MAX_EXTRA_CYCLES)
        val prefix = begin + length
        val result = if (held) FloatArray(prefix + repeats * length) else
            FloatArray(raw.size + repeats * length)
        raw.copyInto(result, endIndex = prefix)
        for (cycle in 0 until repeats) {
            interrupted()
            core.copyInto(result, prefix + cycle * length)
        }
        if (!held) raw.copyInto(result, prefix + repeats * length, prefix)
        val marker = if (held) prefix / oversample + (settling + span) * hop else 0
        return Plan(result, Diagnostics(start.toDouble() / rate, hop.toDouble() / rate,
            overlap.toDouble() / rate, if (held) 0 else repeats,
            if (held) 0.0 else repeats * hop.toDouble() / rate, motion.duration,
            exportSpanCycles = span, exportSpanSeconds = span * hop.toDouble() / rate),
            marker, if (held) marker + span * hop else 0)
        // One further true cycle remains beyond loopEnd for the sinc decimator's
        // lookahead. It is conditioned, then discarded, never zero-padded at export.
    }

    fun cycleDifference(previous: FloatArray, current: FloatArray): Double {
        require(previous.size == current.size)
        var difference = 0.0
        var energy = 0.0
        for (i in previous.indices) {
            val d = current[i] - previous[i].toDouble()
            difference += d * d
            energy += previous[i].toDouble() * previous[i]
        }
        return if (energy == 0.0) if (difference == 0.0) 0.0 else Double.POSITIVE_INFINITY
            else difference / energy
    }
}
