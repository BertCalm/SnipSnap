package com.snipsnap.synth

import com.snipsnap.audio.Fft
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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

    @Test
    fun `model2 dual-envelope compression matches independent delayed source at extreme delay`() {
        for (character in Caustic.Character.entries) for (mode in listOf(Caustic.Mode.FULL, Caustic.Mode.PHASE_DISABLED)) {
            val c = Caustic.Config(model = 2, character = character, midi = 24, delayCycles = 2.0)
            val times = DoubleArray(100) { it * .0031 } + doubleArrayOf(.5, 1.0, 2.0)
            val probe = Caustic.transferProbe(c, times, mode)
            val peak = probe.exact.maxOf { abs(it) }.coerceAtLeast(1e-20)
            val error = probe.exact.indices.maxOf { abs(probe.exact[it] - probe.compressed[it]) } / peak
            assertTrue(error < 2e-10, "$character $mode exact compression relative error $error")
        }
    }

    @Test
    fun `model2 characters change resonance spectrum and temporal depth while retaining geometry`() {
        val c = Caustic.Config(model = 2, source = .5, focus = .19, seconds = 3.8)
        val renders = Caustic.Character.entries.associateWith { Caustic.inspect(c.copy(character = it)) }
        val bands = renders.mapValues { harmonics(it.value.raw, it.value.diagnostics.noteHz) }
        for ((a, b) in listOf(Caustic.Character.BODY to Caustic.Character.REED,
            Caustic.Character.BODY to Caustic.Character.ALLOY, Caustic.Character.REED to Caustic.Character.ALLOY)) {
            val d = bands.getValue(a).indices.sumOf { abs(bands.getValue(a)[it] - bands.getValue(b)[it]) }
            assertTrue(d > .08, "$a/$b normalized harmonic difference $d")
        }
        val original = Caustic.inspect(c.copy(model = 1))
        val body = renders.getValue(Caustic.Character.BODY)
        fun lateRatio(x: FloatArray): Double {
            fun rms(a: Double, b: Double): Double {
                val from = (a * Caustic.RATE).toInt(); val to = (b * Caustic.RATE).toInt()
                return sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))
            }
            return rms(2.7, 3.3) / rms(.3, .9)
        }
        assertTrue(lateRatio(body.raw) > lateRatio(original.raw) * 1.3, "Body must deepen the actual release")
        for ((character, r) in renders) {
            assertTrue(r.diagnostics.rawPeak in .001.. .99, "$character raw peak ${r.diagnostics.rawPeak}")
            assertEquals(r.diagnostics.noteHz, r.diagnostics.modeFrequencies[0])
            assertEquals(24, r.modalEnergy.size)
            val moved = Caustic.inspect(c.copy(character = character, focus = .31))
            val a = harmonics(r.raw, r.diagnostics.noteHz)
            val b = harmonics(moved.raw, r.diagnostics.noteHz)
            assertTrue(a.indices.sumOf { abs(a[it] - b[it]) } > .025, "$character lost focus coloration")
            assertContentEquals(r.raw, Caustic.inspect(c.copy(character = character)).raw)
        }
    }

    @Test
    fun `model2 profiles stay bounded at bass and treble with silence and phase controls`() {
        for (character in Caustic.Character.entries) {
            for (midi in listOf(36, 84)) {
                val r = Caustic.inspect(Caustic.Config(model = 2, character = character, midi = midi,
                    velocity = 1.0, source = 0.0, focus = .12, seconds = 1.1))
                assertTrue(r.raw.all { it.isFinite() })
                assertTrue(r.diagnostics.rawPeak in .001.. .99, "$character MIDI$midi raw peak ${r.diagnostics.rawPeak}")
            }
            val c = Caustic.Config(model = 2, character = character, seconds = .7)
            val p = Caustic.inspect(c, Caustic.Mode.PHASE_DISABLED)
            assertTrue(p.diagnostics.rawPeak in .001.. .99)
            assertTrue(Caustic.inspect(c.copy(velocity = 0.0)).raw.all { it == 0f })
        }
        assertEquals(1, Caustic.Config().model, "old implicit configuration remains model1")
    }

    @Test
    fun `Alloy sustains upper natural rings and geometry changes their level matched balance`() {
        val c = Caustic.Config(model = 2, character = Caustic.Character.ALLOY,
            source = .5, focus = .19, seconds = 2.0)
        val a = Caustic.inspect(c)
        val b = Caustic.inspect(c.copy(focus = .85))
        fun spectralPower(x: FloatArray): DoubleArray {
            val n = 32_768
            val from = (.4 * Caustic.RATE).toInt()
            val re = FloatArray(n) { i -> (x[from + i] * (.5 - .5 * cos(2 * PI * i / (n - 1)))).toFloat() }
            val im = FloatArray(n)
            Fft.forward(re, im)
            return DoubleArray(n / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
        }
        val pa = spectralPower(a.raw)
        val pb = spectralPower(b.raw)
        val upper = pa.indices.filter { it * Caustic.RATE / 32_768.0 > a.diagnostics.noteHz * 1.5 }.sumOf { pa[it] } / pa.sum()
        assertTrue(upper > .10, "Alloy settled upper power $upper; metallic body must survive its attack")
        // Group into fractional-octave bands so different carrier phases alone cannot satisfy the gate.
        fun shares(power: DoubleArray): DoubleArray {
            val out = DoubleArray(160)
            for (k in 1 until power.size) {
                val hz = k * Caustic.RATE / 32_768.0
                if (hz < 20) continue
                val band = (kotlin.math.ln(hz / 20) / kotlin.math.ln(1000.0) * out.size).toInt().coerceIn(out.indices)
                out[band] += power[k]
            }
            val total = out.sum()
            return DoubleArray(out.size) { out[it] / total }
        }
        val sa = shares(pa); val sb = shares(pb)
        val difference = sa.indices.sumOf { abs(sa[it] - sb[it]) }
        assertTrue(difference > .08, "Alloy level matched focus spectral distance $difference")
    }

    @Test
    fun `Alloy bass retains broad perceived pitch despite strong upper natural modes`() {
        val c = Caustic.Config(model = 2, character = Caustic.Character.ALLOY,
            source = .5, focus = .19, midi = 36, seconds = 1.2)
        val r = Caustic.inspect(c)
        val estimate = assertNotNull(Pitch.detect(Snip(r.raw, 1, Caustic.RATE), fromSec = .5f, windowSec = .35f),
            "Alloy C2 must retain a detectable pitched body")
        assertTrue(estimate.confidence >= .3, "Alloy C2 pitch confidence ${estimate.confidence}")
        val cents = 1200 * kotlin.math.ln(estimate.hz / r.diagnostics.noteHz) / kotlin.math.ln(2.0)
        assertTrue(abs(cents) < 20, "Alloy C2 virtual pitch drift $cents cents")
    }

    @Test
    fun `Alloy bloom zero exactly preserves the preferred model2 in every diagnostic`() {
        val c = Caustic.Config(model = 2, character = Caustic.Character.ALLOY, seconds = .65)
        for (mode in Caustic.Mode.entries) {
            val old = Caustic.inspect(c, mode)
            val bloomZero = Caustic.inspect(c.copy(model = 3), mode)
            assertContentEquals(old.raw, bloomZero.raw, "$mode bloom0 changed preferred Alloy")
            assertContentEquals(old.modalEnergy, bloomZero.modalEnergy)
        }
        for (bad in listOf<() -> Caustic.Config>(
            { c.copy(bloom = .5) }, { c.copy(model = 3, character = Caustic.Character.BODY) },
            { c.copy(model = 3, bloom = Double.NaN) }, { c.copy(model = 3, bloom = 1.01) },
        )) assertFailsWith<IllegalArgumentException> { bad() }
    }

    @Test
    fun `Alloy bloom extends sympathetic body while preserving attack and geometry`() {
        val c = Caustic.Config(model = 3, character = Caustic.Character.ALLOY,
            source = .5, focus = .19, seconds = 4.5)
        val old = Caustic.inspect(c)
        val soft = Caustic.inspect(c.copy(bloom = .5))
        val strong = Caustic.inspect(c.copy(bloom = 1.0))
        fun rms(x: FloatArray, start: Double, end: Double): Double {
            val from = (start * Caustic.RATE).toInt(); val to = (end * Caustic.RATE).toInt()
            return sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))
        }
        fun tailRatio(x: FloatArray) = rms(x, 2.5, 4.0) / rms(x, .2, .8)
        assertTrue(tailRatio(soft.raw) > tailRatio(old.raw) * 1.05, "soft bloom must extend the actual body")
        assertTrue(tailRatio(strong.raw) > tailRatio(old.raw) * 1.15, "strong bloom must extend the actual body")
        val attack = rms(strong.raw, .02, .12) / rms(old.raw, .02, .12)
        assertTrue(abs(attack - 1.0) < .08, "bloom changed attack level ($attack)")
        assertContentEquals(strong.raw, Caustic.inspect(c.copy(bloom = 1.0)).raw)
        assertTrue(strong.diagnostics.rawPeak < .99)
        val sparse = Caustic.inspect(c.copy(bloom = 1.0, focus = .85))
        val a = harmonics(strong.raw, strong.diagnostics.noteHz)
        val b = harmonics(sparse.raw, sparse.diagnostics.noteHz)
        assertTrue(a.indices.sumOf { abs(a[it] - b[it]) } > .08, "bloom lost geometric coloration")
        assertTrue(Caustic.inspect(c.copy(bloom = 1.0, seconds = .4, velocity = 0.0)).raw.all { it == 0f })
    }

    @Test
    fun `Alloy strong bloom keeps bass middle and treble pitch and fixed headroom`() {
        for (midi in listOf(36, 60, 84)) {
            val r = Caustic.inspect(Caustic.Config(model = 3, character = Caustic.Character.ALLOY,
                bloom = 1.0, midi = midi, source = .5, focus = .19, velocity = 1.0, seconds = 1.2))
            assertTrue(r.raw.all { it.isFinite() })
            assertTrue(r.diagnostics.rawPeak in .001.. .99, "MIDI$midi bloom peak ${r.diagnostics.rawPeak}")
            val pitch = assertNotNull(Pitch.detect(Snip(r.raw, 1, Caustic.RATE), fromSec = .5f, windowSec = .35f))
            assertTrue(pitch.confidence >= .3)
            val cents = 1200 * kotlin.math.ln(pitch.hz / r.diagnostics.noteHz) / kotlin.math.ln(2.0)
            assertTrue(abs(cents) < 20, "MIDI$midi bloom virtual pitch drift $cents cents")
        }
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
