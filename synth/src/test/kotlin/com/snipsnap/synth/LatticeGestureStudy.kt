package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Authored audition motion, not a native patch or a physical claim about automatic gate motion.
 * One PLUCK source charges the existing reject collector while the direct path closes.
 * The final two crossed gates have COLOR zero, so every direct harmonic group is actually shut.
 */
internal object LatticeGestureStudy {
    data class Frame(val time: Double, val turn: Double, val gates: Double, val twist: Double,
        val color: Double, val reject: Double)

    val frames = listOf(
        Frame(0.0, 0.0, 2.0, .5, 0.0, 0.0),
        Frame(.035, 0.0, 2.0, .5, 0.0, 0.0),
        Frame(.12, 1.0, 4.0, .25, 1.0, .15),
        Frame(.18, 1.0, 4.0, .25, 1.0, .15),
        Frame(.24, 1.0, 16.0, 1.0, 0.0, .65),
        Frame(.34, 1.0, 16.0, 1.0, 0.0, .85),
        Frame(.44, 1.0, 2.0, 1.0, 0.0, 1.0),
        Frame(2.0, 1.0, 2.0, 1.0, 0.0, 1.0),
    )

    fun settings(midi: Int = 48, velocity: Float = .75f) = Lattice.Settings(
        midi = midi, velocity = velocity, turn = 0.0, gateCount = 2.0,
        color = 0.0, reject = 0.0, durationSeconds = 2.0, excitationSeconds = .46,
        haloEnabled = true, sourceProfile = Lattice.SourceProfile.PLUCK,
        contour = Lattice.Contour.PLUCK,
    )

    /** Saved frames are authoritative; an old recipe never takes a newly edited study curve. */
    fun controls(frames: List<Frame>, duration: Double): (Double) -> Lattice.Controls {
        require(frames.size in 2..64 && frames.first().time == 0.0) { "motion needs 2..64 frames beginning at zero" }
        require(frames.all { it.time.isFinite() && it.time in 0.0..duration }) { "motion time exceeds note duration" }
        require(frames.zipWithNext().all { (a, b) -> a.time < b.time }) { "motion times must increase" }
        frames.forEach { Lattice.Controls(it.turn, twist = it.twist, color = it.color, reject = it.reject, gateCount = it.gates) }
        return { time ->
            val index = frames.indexOfFirst { it.time >= time }.let { if (it < 0) frames.lastIndex else it }
            val a = frames[(index - 1).coerceAtLeast(0)]
            val b = frames[index]
            val position = if (a.time == b.time) 0.0 else ((time - a.time) / (b.time - a.time)).coerceIn(0.0, 1.0)
            val smooth = position * position * (3.0 - 2.0 * position)
            fun mix(x: Double, y: Double) = x + (y - x) * smooth
            Lattice.Controls(turn = mix(a.turn, b.turn), twist = mix(a.twist, b.twist),
                color = mix(a.color, b.color), reject = mix(a.reject, b.reject), gateCount = mix(a.gates, b.gates))
        }
    }

    fun note(midi: Int = 48, velocity: Float = .75f): Lattice.Probe =
        Lattice.probe(settings(midi, velocity), controls(frames, 2.0))

    fun rms(snip: Snip, from: Double, until: Double): Double {
        val start = (from * Dsp.RATE).toInt().coerceIn(0, snip.samples.size)
        val end = (until * Dsp.RATE).toInt().coerceIn(start, snip.samples.size)
        return sqrt((start until end).sumOf { snip.samples[it].toDouble() * snip.samples[it] } / (end - start).coerceAtLeast(1))
    }

    /** Relative harmonic-power balance ignores the natural pluck decay and one scalar gain. */
    fun normalizedHarmonics(snip: Snip, midi: Int, from: Double = .12, until: Double = .18): DoubleArray {
        val start = (from * Dsp.RATE).toInt()
        val count = ((until - from) * Dsp.RATE).toInt()
        val hz = Keys.midiHz(midi).toDouble()
        val powers = DoubleArray(8) { harmonic ->
            var re = 0.0
            var im = 0.0
            for (j in 0 until count) {
                val window = .5 - .5 * cos(2.0 * PI * j / (count - 1))
                val phase = 2.0 * PI * hz * (harmonic + 1) * (start + j) / Dsp.RATE
                val value = snip.samples[start + j] * window
                re += value * cos(phase)
                im += value * sin(phase)
            }
            re * re + im * im
        }
        val total = powers.sum().coerceAtLeast(1e-30)
        return DoubleArray(powers.size) { powers[it] / total }
    }

    fun evidence(probe: Lattice.Probe, settings: Lattice.Settings): Map<String, Any?> {
        require(settings.durationSeconds >= 1.2) { "moving study evidence needs a note at least 1.2 seconds long" }
        val earlyRms = rms(probe.snip, .005, .03)
        val tailRms = rms(probe.snip, .60, 1.20)
        val postSourceRms = rms(probe.snip, .48, .75)
        val plain = Lattice.probe(settings.copy(haloEnabled = false))
        val original = normalizedHarmonics(plain.snip, settings.midi)
        val passing = normalizedHarmonics(probe.dry, settings.midi)
        val l1 = original.indices.sumOf { abs(original[it] - passing[it]) }
        return linkedMapOf(
            "earlyWindowSeconds" to listOf(.005, .03), "tailWindowSeconds" to listOf(.60, 1.20),
            "earlyOutputRms" to earlyRms,
            "tailOutputRms" to tailRms,
            "tailToEarlyRmsRatio" to tailRms / earlyRms.coerceAtLeast(1e-30),
            "postSourceWindowSeconds" to listOf(.48, .75),
            "postSourceOutputRms" to postSourceRms,
            "postSourceToEarlyRmsRatio" to postSourceRms / earlyRms.coerceAtLeast(1e-30),
            "renderMs" to probe.diagnostics.renderMillis,
            "tailPassingRms" to rms(probe.dry, .60, 1.20),
            "tailRejectedRms" to rms(probe.halo, .60, 1.20),
            "plainHarmonicPowerFractions" to original.toList(),
            "passingHarmonicPowerFractions" to passing.toList(),
            "harmonicPowerBalanceL1" to l1,
            "harmonicWindowSeconds" to listOf(.12, .18),
            "sourceGain" to probe.diagnostics.sourceGain,
            "allocationResidual" to probe.diagnostics.allocationResidual,
            "tailIsMeasuredBeforeFinalFade" to true,
            "externalEffects" to false, "outputNormalization" to false,
            "note" to "Authored motion through the existing projection and collector. Spectral and branch differences are measurements, not a listening verdict.",
        )
    }
}
