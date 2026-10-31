package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Fft
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Regression gates for the opt-in comparison, not a claim of perceptual approval. */
class AlloyContrastTest {
    private val drums = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
        DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)

    @Test
    fun `the twelve proposals are complete opt-in recipes outside the released bank`() {
        val candidates = AlloyContrastPresets.all()
        assertEquals(12, candidates.size)
        assertEquals(12, candidates.map { it.name }.toSet().size)
        assertEquals(12, candidates.map { it.config }.toSet().size)
        assertEquals(24, AlloyPresets.all().size)
        assertTrue(candidates.none { it in Presets.all() }, "a listening proposal entered the factory registry")
        for (voice in AlloyVoice.entries) {
            val roles = AlloyContrastPresets.forVoice(voice)
            assertEquals(2, roles.size)
            assertEquals(roles.first().config, AlloyContrastPresets.configFor(voice))
            val held = AlloyContrastPresets.held(voice)
            assertEquals(roles.first().config, held.config)
            assertEquals(roles.first().macros + ("HOLD" to 1f), held.macros)
            for (candidate in roles) {
                assertEquals(1, candidate.model)
                assertEquals(Alloy.macrosFor(voice).map { it.name }.toSet(), candidate.macros.keys)
                assertTrue(!Alloy.isLoop(candidate.macros.getValue("HOLD")))
                assertTrue(candidate.config != Alloy.configFor(voice))
                assertTrue(candidate.config.operators.sumOf { it.gain.toDouble() } <= 1.15)
            }
        }
    }

    @Test
    fun `candidate bodies retain pitch and classification at three registers and two velocities`() {
        var worstCents = 0.0
        var worstPeak = 0.0
        for (candidate in AlloyContrastPresets.all()) for (midi in listOf(36, 60, 84)) for (velocity in listOf(.3f, 1f)) {
            val p = probe(candidate, midi = midi, velocity = velocity, seconds = 1.1f)
            val label = "${candidate.name} MIDI$midi velocity$velocity"
            worstPeak = maxOf(worstPeak, p.diagnostics.rawPeak)
            assertTrue(p.samples.all { it.isFinite() } && p.diagnostics.rawPeak < 1.0,
                "$label exceeded raw PCM headroom: ${p.diagnostics.rawPeak}")
            assertTrue(rms(p.samples) > 1e-4, "$label became silent")
            val hz = p.diagnostics.rootHz.toFloat()
            val cents = FineTuning.cents(FineTuning.measuredHz(p.body, Dsp.RATE, hz, .25f, .45f), hz.toDouble())
            worstCents = maxOf(worstCents, abs(cents))
            assertTrue(abs(cents) < 10.0, "$label shifted the apparent root: $cents cents")
            val actual = Classifier.classify(Snip(p.samples, channels = 1, sampleRate = Dsp.RATE))
            assertTrue(actual.drumClass !in drums, "$label became ${actual.drumClass}: ${actual.features}")
        }
        println("ALLOY contrast 72 register/velocity checks: worst root $worstCents cents, raw peak $worstPeak")
    }

    @Test
    fun `candidate JSON replays exactly and zero velocity stays silent`() {
        for (candidate in AlloyContrastPresets.all()) {
            val original = candidate.copy(midi = 55, velocity = .6f)
            val restored = AlloyPatch.fromJsonText(original.toJsonText())
            assertEquals(original, restored)
            assertEquals(original.toJsonText(), restored.toJsonText())
            assertContentEquals(probe(original, seconds = .6f).samples, probe(restored, seconds = .6f).samples,
                "${candidate.name} lost deterministic saved configuration")
            val zero = AlloyPatch.fromJsonText(original.copy(velocity = 0f).toJsonText())
            assertTrue(probe(zero, seconds = .4f).samples.all { it == 0f })
            assertTrue(probe(zero.copy(macros = zero.macros + ("HOLD" to 1f)), seconds = null).samples.all { it == 0f })
        }
    }

    @Test
    fun `each candidate held recipe keeps its actual previous cycle and bounded raw headroom`() {
        for (candidate in AlloyContrastPresets.all()) {
            val held = candidate.copy(macros = candidate.macros + ("HOLD" to 1f))
            val p = probe(held, seconds = null)
            assertEquals(p.samples.size, p.previousCycle.size)
            assertTrue(p.stateError < 1e-3 && p.prerollCycles in 3..12, "${candidate.name} did not converge")
            assertTrue(p.diagnostics.rawPeak < 1.0 && p.samples.all { it.isFinite() })
            assertTrue(rms(p.samples) > 1e-4 && rms(p.conditionedAttack) > 1e-5)
            assertEquals(p.seamError, Keys.seamError(p.previousCycle + p.samples, p.previousCycle.size), 1e-12)
            assertTrue(p.seamError < Keys.MAX_SEAM_ERROR, "${candidate.name} held seam ${p.seamError}")
        }
    }

    @Test
    fun `settled neutral voices retain upper body energy and distinct spectral profiles`() {
        val neutral = mapOf("TUNE" to .5f, "MATERIAL" to .5f, "FUSE" to .5f,
            "EDGE" to .5f, "BODY" to .5f, "LENGTH" to .5f, "HOLD" to 1f)
        val profiles = AlloyVoice.entries.associateWith { voice ->
            val candidate = AlloyContrastPresets.forVoice(voice).first().copy(macros = neutral)
            val p = probe(candidate, seconds = 1.0f)
            val mass = bands(p.body, p.diagnostics.rootHz)
            val nonRoot = 1.0 - mass[0]
            println("ALLOY contrast neutral held $voice non-root mass $nonRoot")
            if (voice != AlloyVoice.BASS) assertTrue(nonRoot > .10, "$voice collapsed back to a sine: $nonRoot")
            mass
        }
        for (i in AlloyVoice.entries.indices) for (j in i + 1 until AlloyVoice.entries.size) {
            val a = AlloyVoice.entries[i]
            val b = AlloyVoice.entries[j]
            val distance = distance(profiles.getValue(a), profiles.getValue(b))
            println("ALLOY contrast neutral held $a/$b Hellinger $distance")
            assertTrue(distance > .10, "$a/$b have converged in persistent body spectrum: $distance")
        }
    }

    @Test
    fun `each pair of roles differs in body spectrum after the transient`() {
        for (voice in AlloyVoice.entries) {
            val roles = AlloyContrastPresets.forVoice(voice)
            val a = probe(roles[0], seconds = 1.1f)
            val b = probe(roles[1], seconds = 1.1f)
            val separation = distance(bands(a.body, a.diagnostics.rootHz), bands(b.body, b.diagnostics.rootHz))
            println("ALLOY contrast $voice body role Hellinger $separation")
            assertTrue(separation > .15, "$voice roles differ only in their attack, gain or phase: $separation")
        }
    }

    @Test
    fun `bright candidate graphs agree with higher rate references`() {
        var worstWave = 0.0
        var worstSpectrum = 0.0
        var worstVariation = 0.0
        for (candidate in AlloyContrastPresets.all().filter { it.name in setOf("Tine Spark", "Dry Pick", "Wire Chime", "Open Reed", "Glass Wire") })
            for (material in if (candidate.name == "Dry Pick") listOf(0f, .5f) else listOf(.5f)) {
            val bright = candidate.copy(macros = candidate.macros + mapOf("EDGE" to 1f, "FUSE" to 1f, "MATERIAL" to material))
            val four = probe(bright, midi = 84, seconds = .6f)
            val eight = probe(bright, midi = 84, seconds = .6f, oversample = 8)
            val wave = matchedDifference(four.body, eight.body)
            val spectral = distance(bands(four.body, four.diagnostics.rootHz), bands(eight.body, eight.diagnostics.rootHz))
            val variation = spectralVariation(four.body, eight.body, .02f, .45f)
            worstWave = maxOf(worstWave, wave)
            worstSpectrum = maxOf(worstSpectrum, spectral)
            worstVariation = maxOf(worstVariation, variation)
            assertTrue(wave < .12 && variation < .035 && spectral < .08,
                "${candidate.name} 4x/8x divergence waveform$wave TV$variation Hellinger$spectral")
        }
        println("ALLOY contrast five recipes/six 4x/8x cases: worst matched waveform $worstWave, spectral variation $worstVariation, Hellinger $worstSpectrum")
    }

    @Test
    fun `every candidate preserves causal fuse in finite and settled held bodies`() {
        val failures = mutableListOf<String>()
        for (candidate in AlloyContrastPresets.all()) for (held in listOf(false, true)) {
            val patch = candidate.copy(macros = candidate.macros + mapOf("FUSE" to .75f, "HOLD" to if (held) 1f else 0f))
            val fused = probe(patch, seconds = .8f)
            val ablated = probe(patch, seconds = .8f, attackModulation = false)
            val label = "${candidate.name} ${if (held) "held" else "finite"}"
            assertContentEquals(fused.directAttack, ablated.directAttack, "$label changed its audible layer during ablation")
            assertContentEquals(fused.conditionedAttack, ablated.conditionedAttack, "$label changed its generated source during ablation")
            assertTrue(rms(ablated.directAttack) > 1e-5, "$label ablation removed the direct material")
            val wave = matchedDifference(fused.body, ablated.body, Dsp.RATE / 100, Dsp.RATE / 3)
            val spectral = spectralVariation(fused.body, ablated.body, if (held) .01f else .025f, if (held) .35f else .30f)
            println("ALLOY contrast FUSE $label matched body $wave, spectral variation $spectral")
            if (held && spectral <= .004 || !held && (wave <= .02 || spectral <= .005)) failures += "$label wave$wave TV$spectral"
            val zero = patch.copy(macros = patch.macros + ("FUSE" to 0f))
            assertContentEquals(probe(zero, seconds = .6f).samples,
                probe(zero, seconds = .6f, attackModulation = false).samples, "$label FUSE zero retained hidden modulation")
        }
        assertTrue(failures.isEmpty(), "FUSE fell below the unchanged original causal gates: ${failures.joinToString()}")
    }

    private fun probe(patch: AlloyPatch, midi: Int = patch.midi, velocity: Float = patch.velocity,
        seconds: Float? = .8f, oversample: Int = 4, attackModulation: Boolean = true) = Alloy.renderProbe(patch.voice, patch.macros,
        midi, velocity, seconds = seconds, config = patch.config, oversample = oversample, attackModulation = attackModulation)

    /** Note-relative spectral power, accumulated over overlapping Hann windows. Squared
     * magnitudes discard phase; unit mass discards gain. The first band isolates the root;
     * later bands cover half-harmonic intervals and include inharmonic strands. */
    private fun bands(samples: FloatArray, hz: Double): DoubleArray {
        val n = 8192
        val mass = DoubleArray(32)
        val begin = (.30 * Dsp.RATE).toInt()
        val end = minOf((.90 * Dsp.RATE).toInt(), samples.size)
        require(end - begin >= n)
        for (start in begin..end - n step n / 2) {
            val re = FloatArray(n) { i -> (samples[start + i] * (.5 - .5 * cos(2 * PI * i / (n - 1)))).toFloat() }
            val im = FloatArray(n)
            Fft.forward(re, im)
            for (bin in 1 until n / 2) {
                val ratio = bin * Dsp.RATE.toDouble() / (n * hz)
                if (ratio < .70) continue
                val band = if (ratio < 1.30) 0 else (1 + ((ratio - 1.30) / .5).toInt()).coerceAtMost(mass.lastIndex)
                mass[band] += re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
            }
        }
        val total = mass.sum().coerceAtLeast(1e-20)
        for (i in mass.indices) mass[i] /= total
        return mass
    }

    private fun distance(a: DoubleArray, b: DoubleArray) =
        sqrt(a.indices.sumOf { val delta = sqrt(a[it]) - sqrt(b[it]); delta * delta } / 2)

    private fun rms(samples: FloatArray) = sqrt(samples.sumOf { it.toDouble() * it } / samples.size.coerceAtLeast(1))
    private fun matchedDifference(a: FloatArray, b: FloatArray, start: Int = Dsp.RATE / 50,
        until: Int = Dsp.RATE / 2): Double {
        val end = minOf(until, a.size, b.size)
        val ar = sqrt((start until end).sumOf { a[it].toDouble() * a[it] } / (end - start)).coerceAtLeast(1e-20)
        val br = sqrt((start until end).sumOf { b[it].toDouble() * b[it] } / (end - start)).coerceAtLeast(1e-20)
        return sqrt((start until end).sumOf { val d = a[it] / ar - b[it] / br; d * d } / (end - start))
    }

    /** The same normalized logarithmic spectral-power variation used by AlloyTest's
     * original FUSE gates, retaining their thresholds rather than substituting waveform
     * phase or the more permissive broad listening-profile distance. */
    private fun spectralVariation(a: FloatArray, b: FloatArray, fromSeconds: Float, seconds: Float): Double {
        fun mass(samples: FloatArray): DoubleArray {
            val from = (fromSeconds * Dsp.RATE).toInt()
            val count = minOf((seconds * Dsp.RATE).toInt(), samples.size - from)
            var n = 1
            while (n < count) n *= 2
            val re = FloatArray(n)
            val im = FloatArray(n)
            for (i in 0 until count) re[i] = (samples[from + i] * (.5 - .5 * cos(2 * PI * i / (count - 1)))).toFloat()
            Fft.forward(re, im)
            val bands = DoubleArray(32)
            for (bin in 1 until n / 2) {
                val hz = bin * Dsp.RATE.toDouble() / n
                if (hz < 30) continue
                val band = (ln(hz / 30) / ln(20000.0 / 30) * bands.size).toInt().coerceIn(0, bands.lastIndex)
                bands[band] += re[bin].toDouble() * re[bin] + im[bin].toDouble() * im[bin]
            }
            val total = bands.sum().coerceAtLeast(1e-20)
            for (i in bands.indices) bands[i] /= total
            return bands
        }
        val aa = mass(a)
        val bb = mass(b)
        return aa.indices.sumOf { abs(aa[it] - bb[it]) } / 2
    }
}
