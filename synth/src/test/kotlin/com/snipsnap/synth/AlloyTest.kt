package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Dry numerical and causal gates; the generated audition remains the timbral approval gate. */
class AlloyTest {
    private val drums = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
        DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)

    @Test
    fun `an interrupted render stops instead of finishing beside its replacement`() {
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<java.util.concurrent.CancellationException> { Alloy.render(AlloyVoice.KEY) }
        } finally {
            Thread.interrupted()
        }
        assertTrue(Alloy.render(AlloyVoice.KEY).samples.any { it != 0f }, "an uninterrupted render still sounds")
    }

    @Test
    fun `six dry voices reproduce complete inputs and ignore macro insertion order`() {
        assertEquals(setOf("BASS", "KEY", "PLUCK", "BRASS", "STRING", "SOFT"), AlloyVoice.entries.map { it.name }.toSet())
        for (voice in AlloyVoice.entries) {
            val macros = Alloy.defaults(voice)
            assertTrue(macros.keys.containsAll(listOf("MATERIAL", "FUSE", "EDGE", "BODY", "LENGTH", "HOLD")))
            val a = Alloy.renderProbe(voice, macros, midi = 48, seconds = 0.8f)
            val b = Alloy.renderProbe(voice, macros, midi = 48, seconds = 0.8f)
            val reordered = Alloy.renderProbe(voice, macros.entries.reversed().associate { it.key to it.value }, midi = 48, seconds = 0.8f)
            assertContentEquals(a.samples, b.samples, "$voice was not reproducible")
            assertContentEquals(a.samples, reordered.samples, "$voice depends on macro insertion order")
            assertContentEquals(a.conditionedAttack, b.conditionedAttack)
            assertTrue(rms(a.samples) > 1e-4, "$voice default is silent")
            assertTrue(rms(a.body) > 1e-4 && rms(a.directAttack) > 1e-5, "$voice lacks a body or its original audible attack")
        }
    }

    @Test
    fun `velocity changes bounded excitation energy and timbre before leveling`() {
        for (voice in AlloyVoice.entries) {
            val macros = mapOf("FUSE" to 0.6f, "EDGE" to 0.6f)
            val soft = Alloy.renderProbe(voice, macros, midi = 48, velocity = 0.25f, seconds = 0.8f)
            val hard = Alloy.renderProbe(voice, macros, midi = 48, velocity = 1f, seconds = 0.8f)
            val silent = Alloy.renderProbe(voice, macros, midi = 48, velocity = 0f, seconds = 0.8f)
            assertTrue(silent.samples.all { it == 0f }, "$voice zero velocity still sounds")
            assertTrue(rms(soft.directAttack) < rms(hard.directAttack) * 0.8, "$voice velocity did not scale original excitation")
            assertTrue(rms(soft.samples) < rms(hard.samples) * 0.8, "$voice raw velocity had no dynamic effect")
            val distance = spectralDistance(soft.body, hard.body)
            println("ALLOY $voice raw velocity: RMS ${rms(soft.samples)} -> ${rms(hard.samples)}, body spectral distance $distance")
            assertTrue(distance > 0.005, "$voice velocity was only an output gain")
        }
    }

    @Test
    fun `finite extremes are bounded before limiting and end quietly`() {
        for (voice in AlloyVoice.entries) for (level in listOf(0f, 1f)) {
            val macros = Alloy.defaults(voice).mapValues { (key, _) -> if (key == "HOLD") 0f else level }
            val p = Alloy.renderProbe(voice, macros, midi = 48)
            assertTrue(p.samples.all { it.isFinite() }, "$voice $level produced nonfinite audio")
            assertTrue(p.body.all { it.isFinite() } && p.conditionedAttack.all { it.isFinite() })
            assertTrue(p.samples.size in Dsp.RATE..(7 * Dsp.RATE), "$voice $level finite duration escaped host bounds: ${p.samples.size / Dsp.RATE.toFloat()} s")
            assertTrue(p.diagnostics.rawPeak.isFinite() && p.diagnostics.rawPeak < 2.0, "$voice $level raw peak ${p.diagnostics.rawPeak}")
            assertTrue(p.diagnostics.maxConditionedAttack <= 1.001, "$voice $level unbounded transient conditioning")
            assertTrue(p.diagnostics.maxPhaseModulation.isFinite() && p.diagnostics.maxPhaseModulation < 12.0,
                "$voice $level unbounded phase modulation ${p.diagnostics.maxPhaseModulation}")
            val average = p.samples.sumOf { it.toDouble() } / p.samples.size
            assertTrue(abs(average) < max(1e-5, rms(p.samples) * 0.01), "$voice $level DC $average")
            val tail = rms(p.samples, p.samples.size - Dsp.RATE / 10)
            assertTrue(tail < rms(p.samples) * 0.08, "$voice $level finite tail never settled: $tail")
            val leveled = Alloy.renderProbe(voice, macros, midi = 48, normalize = true)
            assertTrue(leveled.samples.maxOf { abs(it) } <= 0.991f, "$voice leveled output exceeds sample peak ceiling")
        }
    }

    @Test
    fun `requested roots survive dry ordinary and fused bodies across three registers`() {
        var worst = 0.0
        for (voice in AlloyVoice.entries) for (midi in listOf(36, 60, 84)) for (fuse in listOf(0f, 0.8f)) {
            val p = Alloy.renderProbe(voice, mapOf("FUSE" to fuse, "EDGE" to 0.5f), midi = midi, seconds = 1f)
            val hz = p.diagnostics.rootHz.toFloat()
            val measured = FineTuning.measuredHz(p.body, Dsp.RATE, hz, 0.25f, 0.45f)
            val cents = FineTuning.cents(measured, hz.toDouble())
            worst = max(worst, abs(cents))
            assertTrue(abs(cents) < 10.0, "$voice MIDI $midi FUSE $fuse root $cents cents off")
            val share = rootShare(p.body, hz)
            assertTrue(share > 0.025, "$voice MIDI $midi FUSE $fuse root energy vanished: $share")
        }
        println("ALLOY root tuning: worst $worst cents")
    }

    @Test
    fun `short wood and breath gestures retain pitched classifier guards`() {
        for (voice in AlloyVoice.entries) for (midi in listOf(36, 60, 84)) for (material in listOf(0f, 1f)) {
            val macros = mapOf("MATERIAL" to material, "LENGTH" to 0f, "FUSE" to 0.7f, "EDGE" to 0.6f)
            val samples = Alloy.renderProbe(voice, macros, midi = midi, seconds = 1.1f, normalize = true).samples
            val actual = Classifier.classify(Snip(samples, channels = 1, sampleRate = Dsp.RATE))
            assertTrue(actual.drumClass !in drums, "$voice MIDI $midi MATERIAL $material read as ${actual.drumClass}: ${actual.features}")
        }
    }

    @Test
    fun `timbral macros work in the middle of every ordinary voice and length changes the phrase`() {
        val controls = listOf("MATERIAL", "FUSE", "EDGE", "BODY")
        for (voice in AlloyVoice.entries) {
            for (macro in controls) {
                val points = listOf(0.25f, 0.5f, 0.75f).map { value ->
                    Alloy.renderProbe(voice, mapOf("FUSE" to 0.6f, "EDGE" to 0.6f, macro to value), midi = 48, seconds = 0.8f).samples
                }
                for (pair in points.zipWithNext()) {
                    val change = matchedDifference(pair.first, pair.second)
                    assertTrue(change > 0.005, "$voice $macro middle adjacent step was inactive ($change)")
                }
                assertTrue(spectralDistance(points.first(), points.last()) > 0.003, "$voice $macro changed only gain or phase")
            }
            val short = Alloy.renderProbe(voice, mapOf("LENGTH" to 0.25f), midi = 48)
            val long = Alloy.renderProbe(voice, mapOf("LENGTH" to 0.75f), midi = 48)
            assertTrue(long.samples.size > short.samples.size + Dsp.RATE / 4, "$voice LENGTH did not lengthen the gesture")
        }
    }

    @Test
    fun `fuse enters the body through the attack port while ablation retains the audible layer`() {
        for (voice in AlloyVoice.entries) for (material in listOf(0f, 0.5f, 1f)) {
            val macros = mapOf("MATERIAL" to material, "FUSE" to 0.75f, "EDGE" to 0.4f)
            val fused = Alloy.renderProbe(voice, macros, midi = 48, seconds = 0.8f)
            val ablated = Alloy.renderProbe(voice, macros, midi = 48, seconds = 0.8f, attackModulation = false)
            assertContentEquals(fused.directAttack, ablated.directAttack, "$voice $material ablation changed the direct layer")
            assertContentEquals(fused.conditionedAttack, ablated.conditionedAttack, "$voice $material ablation changed the generated material")
            assertTrue(rms(ablated.directAttack) > 1e-5, "$voice $material ablation removed its audible attack")
            val difference = matchedDifference(fused.body, ablated.body, Dsp.RATE / 100, Dsp.RATE / 3)
            val spectrum = spectralDistance(fused.body, ablated.body)
            assertTrue(difference > 0.02 && spectrum > 0.005,
                "$voice MATERIAL $material FUSE only changed the detached mix: matched body $difference, spectrum $spectrum")
            val zero = Alloy.renderProbe(voice, macros + ("FUSE" to 0f), midi = 48, seconds = 0.8f)
            val zeroAblated = Alloy.renderProbe(voice, macros + ("FUSE" to 0f), midi = 48, seconds = 0.8f, attackModulation = false)
            assertContentEquals(zero.samples, zeroAblated.samples, "$voice FUSE 0 retained hidden attack modulation")
        }
    }

    @Test
    fun `edge remains active without fuse and selected material changes the fused carrier`() {
        for (voice in AlloyVoice.entries) {
            val rounded = Alloy.renderProbe(voice, mapOf("FUSE" to 0f, "EDGE" to 0.2f), midi = 48, seconds = 0.8f)
            val bright = Alloy.renderProbe(voice, mapOf("FUSE" to 0f, "EDGE" to 0.8f), midi = 48, seconds = 0.8f)
            assertTrue(spectralDistance(rounded.body, bright.body) > 0.015, "$voice EDGE depends on FUSE")
            val wood = Alloy.renderProbe(voice, mapOf("FUSE" to 0.65f, "MATERIAL" to 0f), midi = 48, seconds = 0.8f)
            val breath = Alloy.renderProbe(voice, mapOf("FUSE" to 0.65f, "MATERIAL" to 1f), midi = 48, seconds = 0.8f)
            assertTrue(spectralDistance(wood.body, breath.body) > 0.005, "$voice MATERIAL did not reach the fused tonal source")
        }
    }

    @Test
    fun `held excitation settles before wrapping and keeps material specific fusion audible`() {
        for (voice in AlloyVoice.entries) {
            val macros = mapOf("HOLD" to 1f, "FUSE" to 0.65f, "EDGE" to 0.55f, "MATERIAL" to 0.5f)
            val p = Alloy.renderProbe(voice, macros, midi = 48)
            assertTrue(p.previousCycle.size == p.samples.size && p.previousCycle.size > 256, "$voice held render lost the real preceding cycle")
            assertTrue(p.prerollCycles in 2..32, "$voice held preroll is unbounded or absent: ${p.prerollCycles}")
            assertTrue(p.stateError.isFinite() && p.stateError < 1e-3, "$voice oscillator/source/filter state did not settle: ${p.stateError}")
            val seam = Keys.seamError(p.previousCycle + p.samples, p.previousCycle.size)
            assertTrue(seam.isFinite() && seam < Keys.MAX_SEAM_ERROR, "$voice held seam $seam")
            assertEquals(seam, p.seamError, 1e-12, "$voice reported an artificial seam")
            assertTrue(rms(p.conditionedAttack) > 1e-5, "$voice HOLD discarded material excitation")
            val repeated = FloatArray(p.samples.size * 4) { p.samples[it % p.samples.size] }
            val hz = p.diagnostics.rootHz.toFloat()
            val cents = FineTuning.cents(FineTuning.measuredHz(repeated, Dsp.RATE, hz, 0.05f, 0.65f), hz.toDouble())
            assertTrue(abs(cents) < 10.0 && rootShare(repeated, hz) > 0.025,
                "$voice held wraps lost the note: $cents cents, root share ${rootShare(repeated, hz)}")
            val ablated = Alloy.renderProbe(voice, macros, midi = 48, attackModulation = false)
            assertContentEquals(p.directAttack, ablated.directAttack)
            assertTrue(spectralDistance(p.body, ablated.body, 0.01f, 0.35f) > 0.004, "$voice held FUSE vanished after the initial transient")
            val wood = Alloy.renderProbe(voice, macros + ("MATERIAL" to 0f), midi = 48)
            val breath = Alloy.renderProbe(voice, macros + ("MATERIAL" to 1f), midi = 48)
            assertTrue(spectralDistance(wood.body, breath.body, 0.01f, 0.35f) > 0.004, "$voice settled held MATERIAL was inaudible")
        }
    }

    @Test
    fun `high register all high controls stay finite and do not concentrate near nyquist`() {
        val started = System.nanoTime()
        var worst = 0.0
        var audioSeconds = 0.0
        for (voice in AlloyVoice.entries) {
            val macros = Alloy.defaults(voice).mapValues { (name, _) -> if (name == "HOLD") 0f else 1f }
            val p = Alloy.renderProbe(voice, macros, midi = 84, seconds = 0.8f)
            assertTrue(p.samples.all { it.isFinite() })
            assertEquals(Dsp.RATE * Dsp.OVERSAMPLE, p.diagnostics.internalRate)
            val power = spectrum(p.samples)
            val nearNyquist = power.indices.filter { it * Dsp.RATE / (2.0 * power.size) >= 16000 }
                .sumOf { power[it] } / power.sum().coerceAtLeast(1e-20)
            worst = max(worst, nearNyquist)
            assertTrue(nearNyquist < 0.02, "$voice high-note sidebands concentrate near Nyquist: $nearNyquist")
            audioSeconds += p.samples.size.toDouble() / Dsp.RATE
        }
        val wallSeconds = (System.nanoTime() - started) / 1e9
        println("ALLOY upper spectrum smoke probe: worst >16 kHz energy share $worst; $audioSeconds audio s in $wallSeconds wall s")
    }

    @Test
    fun `difficult held corners converge with bounded low and high register excitation`() {
        for (voice in AlloyVoice.entries) for (midi in listOf(36, 96)) {
            val macros = Alloy.defaults(voice).mapValues { (name, _) -> if (name == "TUNE") 0.5f else 1f }
            val p = Alloy.renderProbe(voice, macros, midi = midi, velocity = 0.6f)
            assertTrue(p.samples.all { it.isFinite() } && p.body.all { it.isFinite() })
            assertTrue(p.diagnostics.rawPeak < 2 && p.diagnostics.maxConditionedAttack <= 1.001,
                "$voice MIDI $midi held excitation exceeded its bounds")
            assertTrue(p.stateError < 1e-3 && p.prerollCycles <= 32,
                "$voice MIDI $midi held all-high state did not settle: ${p.stateError}")
            val seam = Keys.seamError(p.previousCycle + p.samples, p.previousCycle.size)
            assertTrue(seam < Keys.MAX_SEAM_ERROR, "$voice MIDI $midi held all-high seam $seam")
            assertTrue(rms(p.samples) > 1e-4, "$voice MIDI $midi settled by becoming silent")
        }
    }

    @Test
    fun `high note phase modulation agrees with a higher rate reference`() {
        var worstWaveform = 0.0
        var worstSpectrum = 0.0
        for (voice in AlloyVoice.entries) for (material in listOf(0f, 0.5f)) {
            // The finite modal projections contain no stochastic breath noise, so the same
            // excitation is compared at both rates. This probes the PM path, not two noise seeds.
            val macros = Alloy.defaults(voice).mapValues { (name, _) -> if (name == "HOLD") 0f else 1f } +
                ("MATERIAL" to material)
            val four = Alloy.renderProbe(voice, macros, midi = 84, seconds = 0.6f, oversample = 4)
            val eight = Alloy.renderProbe(voice, macros, midi = 84, seconds = 0.6f, oversample = 8)
            assertEquals(Dsp.RATE * 8, eight.diagnostics.internalRate)
            val waveform = matchedDifference(four.body, eight.body, Dsp.RATE / 50, Dsp.RATE / 2)
            val spectrum = spectralDistance(four.body, eight.body, 0.02f, 0.45f)
            worstWaveform = max(worstWaveform, waveform)
            worstSpectrum = max(worstSpectrum, spectrum)
            assertTrue(waveform < 0.12 && spectrum < 0.035,
                "$voice MATERIAL $material high-note 4x/8x divergence: waveform $waveform, normalized spectrum $spectrum")
        }
        println("ALLOY upper-register 4x/8x PM reference: worst matched waveform $worstWaveform, spectral mass distance $worstSpectrum")
    }

    @Test
    fun `dense custom operator routing retains the root and agrees with the high rate reference`() {
        val voice = AlloyVoice.KEY
        val config = Alloy.configFor(voice).copy(routing = (1..3).flatMap { from ->
            (0 until from).map { to -> AlloyConfig.Route(from, to, 3f) }
        })
        val macros = Alloy.defaults(voice).mapValues { (name, _) -> if (name == "HOLD") 0f else 1f } +
            mapOf("MATERIAL" to 0.5f, "TUNE" to 0.5f)
        for (midi in listOf(72, 84)) {
            val four = Alloy.renderProbe(voice, macros, midi = midi, seconds = 0.8f, config = config, oversample = 4)
            val eight = Alloy.renderProbe(voice, macros, midi = midi, seconds = 0.8f, config = config, oversample = 8)
            assertTrue(four.samples.all { it.isFinite() } && four.diagnostics.rawPeak < 2)
            val waveform = matchedDifference(four.body, eight.body, Dsp.RATE / 50, Dsp.RATE / 2)
            val spectrum = spectralDistance(four.body, eight.body, 0.02f, 0.45f)
            val hz = four.diagnostics.rootHz.toFloat()
            val cents = FineTuning.cents(FineTuning.measuredHz(four.body, Dsp.RATE, hz, 0.25f, 0.45f), hz.toDouble())
            println("ALLOY dense custom graph MIDI $midi 4x/8x: matched waveform $waveform, spectral mass distance $spectrum, root $cents cents")
            assertTrue(waveform < 0.12 && spectrum < 0.035,
                "dense custom PM graph MIDI $midi exceeds the supported reference divergence: waveform $waveform, spectrum $spectrum")
            assertTrue(abs(cents) < 10.0 && rootShare(four.body, hz) > 0.025,
                "dense custom PM graph MIDI $midi lost its apparent root: $cents cents")
        }
    }

    private fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double {
        val end = to.coerceAtMost(samples.size)
        val start = from.coerceIn(0, end)
        return sqrt((start until end).sumOf { samples[it].toDouble() * samples[it] } /
            (end - start).coerceAtLeast(1))
    }

    /** Relative waveform change after independent RMS matching, so output gain cannot pass a gate. */
    private fun matchedDifference(a: FloatArray, b: FloatArray, from: Int = 0,
        to: Int = minOf(a.size, b.size)): Double {
        val end = minOf(to, a.size, b.size)
        val start = from.coerceIn(0, end)
        val ar = rms(a, start, end).coerceAtLeast(1e-12)
        val br = rms(b, start, end).coerceAtLeast(1e-12)
        return sqrt((start until end).sumOf {
            val d = a[it] / ar - b[it] / br
            d * d
        } / (end - start).coerceAtLeast(1))
    }

    private fun spectrum(samples: FloatArray, fromSeconds: Float = 0.025f,
        seconds: Float = 0.3f): DoubleArray {
        val from = (fromSeconds * Dsp.RATE).toInt().coerceIn(0, samples.size)
        val count = minOf((seconds * Dsp.RATE).toInt(), samples.size - from)
        require(count > 8)
        var n = 1
        while (n < count) n *= 2
        val re = FloatArray(n)
        val im = FloatArray(n)
        for (i in 0 until count) {
            re[i] = (samples[from + i] * (0.5 - 0.5 * cos(2 * PI * i / (count - 1)))).toFloat()
        }
        Fft.forward(re, im)
        return DoubleArray(n / 2) { re[it].toDouble() * re[it] + im[it].toDouble() * im[it] }
    }

    /** Total variation of normalized logarithmic spectral mass, independent of overall level. */
    private fun spectralDistance(a: FloatArray, b: FloatArray, fromSeconds: Float = 0.025f,
        seconds: Float = 0.3f): Double {
        fun mass(samples: FloatArray): DoubleArray {
            val power = spectrum(samples, fromSeconds, seconds)
            val bands = DoubleArray(32)
            val binHz = Dsp.RATE.toDouble() / (2 * power.size)
            for (bin in 1 until power.size) {
                val hz = bin * binHz
                if (hz < 30) continue
                val band = (ln(hz / 30) / ln(20000.0 / 30) * bands.size).toInt().coerceIn(0, bands.lastIndex)
                bands[band] += power[bin]
            }
            val total = bands.sum().coerceAtLeast(1e-20)
            for (i in bands.indices) bands[i] /= total
            return bands
        }
        val aa = mass(a)
        val bb = mass(b)
        return aa.indices.sumOf { abs(aa[it] - bb[it]) } / 2
    }

    private fun rootShare(samples: FloatArray, hz: Float, fromSeconds: Float = 0.25f): Double {
        val power = spectrum(samples, fromSeconds, 0.45f)
        val binHz = Dsp.RATE.toDouble() / (2 * power.size)
        val lo = ((hz * 0.95) / binHz).toInt().coerceAtLeast(1)
        val hi = ((hz * 1.05) / binHz).toInt().coerceAtMost(power.lastIndex)
        return (lo..hi).sumOf { power[it] } / power.sum().coerceAtLeast(1e-20)
    }
}
