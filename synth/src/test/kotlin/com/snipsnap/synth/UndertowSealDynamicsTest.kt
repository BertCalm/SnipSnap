package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** SEAL must alternate during the draw, rather than storing a louder gesture until release. */
class UndertowSealDynamicsTest {
    private data class Draw(val label: String, val probe: Undertow.Probe, val stop: Float, val energy: Float, val tune: Float)

    private companion object {
        val natural by lazy {
            listOf(0f, .5f, 1f).flatMap { tune ->
                listOf(.25f, .6f, 1f).map { energy ->
                    val probe = Undertow.probe(
                        UndertowVoice.SEAL, mapOf("TUNE" to tune), energy = energy,
                        options = Undertow.ProbeOptions(durationSeconds = 3.5f),
                    )
                    // Work is constant after the last extraction step. Inferring the release
                    // from diagnostics keeps this check independent of the gesture's duration.
                    val lastWork = probe.snapshots.zipWithNext().last { (a, b) -> b.pistonWork > a.pistonWork + 1e-10 }.second
                    Draw("SEAL TUNE=$tune energy=$energy", probe, lastWork.timeSeconds, energy, tune)
                }
            }
        }
        val stopped by lazy {
            listOf(.25f, .6f, 1f).map { energy ->
                Draw(
                    "SEAL forced stop energy=$energy",
                    Undertow.probe(
                        UndertowVoice.SEAL, mapOf("TUNE" to .5f), energy = energy,
                        options = Undertow.ProbeOptions(durationSeconds = 2.2f, driveStopSeconds = .7f),
                    ),
                    .7f, energy, .5f,
                )
            }
        }
    }

    /** Twenty milliseconds spans several carrier periods; a ten-millisecond hop catches flams. */
    private fun rmsEnvelope(samples: FloatArray, from: Float, to: Float): List<Double> {
        val size = (Dsp.RATE * .020f).toInt()
        val hop = (Dsp.RATE * .010f).toInt()
        val first = (from * Dsp.RATE).toInt().coerceAtLeast(0)
        val end = minOf((to * Dsp.RATE).toInt(), samples.size)
        return (first..(end - size) step hop).map { start ->
            sqrt((start until start + size).sumOf { samples[it].toDouble() * samples[it] } / size)
        }
    }

    private fun percentile(values: List<Double>, fraction: Double): Double {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * fraction).toInt()]
    }

    private fun longestRootClosure(draw: Draw): Float {
        var since: Float? = null
        var longest = 0f
        for (state in draw.probe.snapshots.filter { it.timeSeconds <= draw.stop - .16f }) {
            if (state.sealed[0]) {
                if (since == null) since = state.timeSeconds
                longest = maxOf(longest, state.timeSeconds - since)
            } else {
                since = null
            }
        }
        return longest
    }

    private fun poweredReopens(draw: Draw): Int = draw.probe.snapshots.zipWithNext().count { (a, b) ->
        b.timeSeconds < draw.stop - .16f && a.sealed[0] && !b.sealed[0]
    }

    @Test
    fun `normal and strong seal draws keep reopening instead of latching until release`() {
        val failures = mutableListOf<String>()
        for (draw in natural) {
            val longest = longestRootClosure(draw)
            val reopens = poweredReopens(draw)
            // Cap occupancy is reported to explain trajectories, not used as an audio verdict.
            val powered = draw.probe.snapshots.filter { it.timeSeconds in .3f..draw.stop - .16f }
            val capped = powered.count { it.suction >= 1.99f }.toDouble() / powered.size
            println("${draw.label}: longest root closure $longest s, powered reopens $reopens, capped fraction $capped")
            if (longest > .30f || (draw.energy >= .6f && reopens < 2)) {
                failures += "${draw.label}: root closure $longest s, powered reopens $reopens"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `piston release leaves a tail instead of a louder delayed seal burst`() {
        val failures = mutableListOf<String>()
        for (draw in natural + stopped) {
            val active = rmsEnvelope(draw.probe.samples, .25f, draw.stop - .16f)
            val late = rmsEnvelope(draw.probe.samples, draw.stop + .02f, draw.stop + .8f)
            val activeCrest = percentile(active, .9)
            val latePeak = late.max()
            val ratio = latePeak / activeCrest.coerceAtLeast(1e-12)
            println("${draw.label}: post-stop peak 20 ms RMS / powered P90 RMS $ratio")
            // Compare crests because powered alternating apertures intentionally pulse.
            // A 6 dB margin allows ordinary beating without accepting a much louder late attack.
            if (!ratio.isFinite() || activeCrest <= 1e-6 || ratio > 2.0) {
                failures += "${draw.label}: post-stop/active 20 ms RMS $ratio (late=$latePeak active=$activeCrest)"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `controlled seal dynamics retain the requested low middle and high roots`() {
        val cases = natural.map { Triple(it.label, it.probe, it.tune) } + listOf(
            Triple("SEAL neutral strong C3", Undertow.probe(
                UndertowVoice.SEAL,
                mapOf("TUNE" to 0f, "DRAW" to .45f, "FLAP" to .4f, "WEIGHT" to .5f, "SPIRAL" to .4f, "LEAK" to .3f),
                options = Undertow.ProbeOptions(durationSeconds = 1.1f),
            ), 0f),
        )
        val failures = mutableListOf<String>()
        for ((label, probe, tune) in cases) {
            val wanted = Undertow.frequencyFor(UndertowVoice.SEAL, tune)
            val actual = TestPitch.estimate(Snip(probe.samples, 1, Dsp.RATE), .2f, .35f)
            val cents = if (actual > 0f) 1200 * ln(actual / wanted.toDouble()) / ln(2.0) else Double.POSITIVE_INFINITY
            if (!cents.isFinite() || abs(cents) > 10.0) failures += "$label: $actual Hz for $wanted Hz ($cents cents)"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
