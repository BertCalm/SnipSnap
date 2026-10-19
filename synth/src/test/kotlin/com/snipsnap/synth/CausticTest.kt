package com.snipsnap.synth

import com.snipsnap.audio.Fft
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Acceptance probes use fixed-gain raw audio so normalization cannot manufacture a result. */
class CausticTest {
    @Test
    fun `saved config is finite bounded and versioned`() {
        for (bad in listOf<() -> Caustic.Config>(
            { Caustic.Config(source = Double.NaN) }, { Caustic.Config(focus = 1.01) },
            { Caustic.Config(velocity = -.1) }, { Caustic.Config(seconds = Double.POSITIVE_INFINITY) },
            { Caustic.Config(aperture = 0.0) }, { Caustic.Config(delayCycles = 0.0) },
            { Caustic.Config(midi = 0) }, { Caustic.Config(rayCount = 4) }, { Caustic.Config(model = 999) },
        )) assertFailsWith<IllegalArgumentException> { bad() }
    }

    @Test
    fun `same recipe is deterministic and zero velocity is actual silence`() {
        val c = Caustic.Config(seconds = .6)
        val a = Caustic.inspect(c)
        val b = Caustic.inspect(c)
        assertContentEquals(a.raw, b.raw)
        assertContentEquals(a.raw, a.samples, "audition samples must retain fixed raw gain")
        assertEquals((c.seconds * Caustic.RATE).toInt(), a.raw.size)
        assertTrue(a.diagnostics.rawRms > .001)
        for (mode in Caustic.Mode.entries)
            assertTrue(Caustic.inspect(c.copy(velocity = 0.0), mode).raw.all { it == 0f }, "$mode velocity0")
    }

    @Test
    fun `exact compression preserves causal delayed transient and carrier across pitches`() {
        for (midi in listOf(24, 36, 60, 84)) for (mode in listOf(Caustic.Mode.FULL, Caustic.Mode.PHASE_DISABLED)) {
            val c = Caustic.Config(source = .5, focus = .19, midi = midi, delayCycles = if (midi == 24) 2.0 else .65)
            val maxDelay = 4 * c.delayCycles / (440.0 * Math.pow(2.0, (midi - 69) / 12.0))
            val times = doubleArrayOf(0.0, .00001) + DoubleArray(121) { maxDelay * it / 120 } +
                doubleArrayOf(.07, .2, .65, 1.3)
            val p = Caustic.transferProbe(c, times, mode)
            val peak = p.exact.maxOf { abs(it) }.coerceAtLeast(1e-20)
            val maximumError = p.exact.indices.maxOf { abs(p.exact[it] - p.compressed[it]) }
            assertTrue(maximumError / peak < 1e-10, "$midi $mode relative maximum $maximumError / $peak")
            assertTrue(p.exact.take(16).all { it == 0.0 }, "no excitation precedes a path's arrival")
        }
        // The source is still delayed when carrier phase is disabled: these conditions must differ.
        val c = Caustic.Config(source = .5, focus = .19)
        val times = DoubleArray(64) { .003 + it * .00031 }
        val normal = Caustic.transferProbe(c, times).exact
        val disabled = Caustic.transferProbe(c, times, Caustic.Mode.PHASE_DISABLED).exact
        assertTrue(relativeDifference(normal, disabled) > .3)
    }

    @Test
    fun `raw notes are finite bounded and root related at low middle and high pitches`() {
        for (midi in listOf(36, 60, 84)) for ((source, focus) in listOf(0.0 to .12, .5 to .19, 1.0 to .245, .5 to 1.0)) {
            val r = Caustic.inspect(Caustic.Config(source = source, focus = focus, midi = midi, velocity = 1.0, seconds = 1.0))
            assertTrue(r.raw.all { it.isFinite() })
            assertTrue(r.diagnostics.rawPeak in .0001.. .99,
                "source=$source focus=$focus midi=$midi peak=${r.diagnostics.rawPeak}")
            assertEquals(r.diagnostics.noteHz, r.diagnostics.modeFrequencies[0])
            assertTrue(r.modalEnergy.all { it.isFinite() && it >= 0 })
        }
    }

    @Test
    fun `moving source and focus changes normalized harmonic distribution`() {
        val c = Caustic.Config(source = 0.0, focus = .12, seconds = 1.4)
        val a = Caustic.inspect(c)
        val b = Caustic.inspect(c.copy(focus = .25))
        val ha = harmonics(a.raw, a.diagnostics.noteHz)
        val hb = harmonics(b.raw, b.diagnostics.noteHz)
        val distance = ha.indices.sumOf { abs(ha[it] - hb[it]) }
        assertTrue(distance > .08, "normalized spectral L1=$distance; geometry must do more than gain")
        val noPhase = Caustic.inspect(c, Caustic.Mode.PHASE_DISABLED)
        val hp = harmonics(noPhase.raw, a.diagnostics.noteHz)
        assertTrue(ha.indices.sumOf { abs(ha[it] - hp[it]) } > .03, "carrier phase must affect harmonic balance")
    }

    @Test
    fun `velocity changes source effort and normalized tone before modal processing`() {
        val c = Caustic.Config(source = .5, focus = .19, seconds = 1.2)
        for (mode in listOf(Caustic.Mode.FULL, Caustic.Mode.SOURCE_ONLY)) {
            val soft = Caustic.inspect(c.copy(velocity = .25), mode)
            val hard = Caustic.inspect(c.copy(velocity = 1.0), mode)
            val a = harmonics(soft.raw, soft.diagnostics.noteHz)
            val b = harmonics(hard.raw, hard.diagnostics.noteHz)
            assertTrue(a.indices.sumOf { abs(a[it] - b[it]) } > .025, "$mode velocity only changed gain")
            assertTrue(hard.diagnostics.rawRms > soft.diagnostics.rawRms * 2)
        }
    }

    @Test
    fun `gain only is a true scalar control and dry source ignores geometry`() {
        val c = Caustic.Config(source = 0.0, focus = .12, seconds = .8)
        val d = c.copy(source = 1.0, focus = .245)
        val a = Caustic.inspect(c, Caustic.Mode.GAIN_ONLY)
        val b = Caustic.inspect(d, Caustic.Mode.GAIN_ONLY)
        val scale = b.diagnostics.collection / a.diagnostics.collection
        val error = sqrt(a.raw.indices.sumOf {
            val e = b.raw[it] - a.raw[it] * scale
            e * e
        } / b.raw.sumOf { it.toDouble() * it }.coerceAtLeast(1e-30))
        assertTrue(error < 2e-6, "gain-only changed its normalized signal ($error)")
        assertContentEquals(Caustic.inspect(c, Caustic.Mode.SOURCE_ONLY).raw,
            Caustic.inspect(d, Caustic.Mode.SOURCE_ONLY).raw)
    }

    @Test
    fun `render cancellation checks interruption and caller callback`() {
        Thread.currentThread().interrupt()
        try { assertFailsWith<CancellationException> { Caustic.render() } }
        finally { Thread.interrupted() }
        var asks = 0
        assertFailsWith<CancellationException> { Caustic.inspect(cancelled = { ++asks >= 3 }) }
        assertEquals(3, asks)
    }

    private fun relativeDifference(a: DoubleArray, b: DoubleArray): Double =
        sqrt(a.indices.sumOf { val d = a[it] - b[it]; d * d } / a.sumOf { it * it }.coerceAtLeast(1e-30))

    private fun harmonics(samples: FloatArray, hz: Double): DoubleArray {
        val n = 32_768
        val start = 6_000
        val re = FloatArray(n) { i -> (samples[start + i] * (.5 - .5 * cos(2 * PI * i / (n - 1)))).toFloat() }
        val im = FloatArray(n)
        Fft.forward(re, im)
        val energy = DoubleArray(12) { h ->
            val center = (hz * (h + 1) * n / Caustic.RATE).toInt()
            (maxOf(1, center - 5)..minOf(n / 2 - 1, center + 5)).sumOf { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        }
        val sum = energy.sum().coerceAtLeast(1e-30)
        return DoubleArray(energy.size) { energy[it] / sum }
    }
}
