package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The expanded bank's content and measured usability; listening still decides its character. */
class ContourPresetsTest {
    @Test
    fun `the expanded roster keeps the original order and fills the specified musical roles`() {
        val originalNames = listOf("Rounded Edge", "Moving Corner", "Dark Table", "Copper Scan",
            "Soft Digital", "Bright Margin", "Twin Outline", "Hollow Step", "Long Contour")
        assertEquals(originalNames, ContourPresets.original().map { it.name })
        assertEquals(ContourPresets.original() + ContourPresets.added(), ContourPresets.all())
        assertEquals(27, ContourPresets.added().size)
        assertEquals(36, ContourPresets.all().size)
        assertEquals(mapOf(ContourVoice.BASS to 10, ContourVoice.KEY to 4, ContourVoice.PLUCK to 4,
            ContourVoice.LEAD to 8, ContourVoice.SWELL to 6, ContourVoice.HOLLOW to 4),
            ContourPresets.all().groupingBy { it.voice }.eachCount())
        for (voice in ContourVoice.entries) {
            val old = ContourPresets.original().filter { it.voice == voice }
            assertEquals(old, ContourPresets.forVoice(voice).take(old.size),
                "$voice: existing preset indices must retain their recipes")
        }
    }

    @Test
    fun `every complete recipe has its own structure and round trips through the public dispatcher`() {
        val all = ContourPresets.all()
        assertEquals(all.size, all.map { it.name }.toSet().size, "duplicate preset names")
        assertEquals(all.size, all.map { it.config }.toSet().size,
            "new names need distinct saved structures, not only knob changes")
        for (patch in all) {
            assertFalse(PresetTestSupport.trademarkBlocklist.containsMatchIn(patch.name), patch.name)
            assertTrue(patch.name.length <= 24, "${patch.name}: name is too long for a preset menu")
            assertEquals(ContourEngine.macrosFor(patch.voice).map { it.name }.toSet(), patch.macros.keys,
                "${patch.name}: incomplete saved controls")
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()), patch.name)
            assertEquals(patch, Presets.byName("CONTOUR", patch.voiceName, patch.name))
            val description = ContourPresets.description(patch)
            assertTrue(description.role.isNotBlank() && description.gesture.isNotBlank(),
                "${patch.name}: the audition needs a musical role and a gesture to judge")
            assertEquals(patch in ContourPresets.original(), description.retained, patch.name)
        }
    }

    @Test
    fun `every recipe has healthy finite and held audio with a detected stable center root`() {
        val drums = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.TOM,
            DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN)
        for (patch in ContourPresets.all()) {
            val pair = audio.getValue(patch.name)
            for ((mode, snip) in listOf("finite" to pair.finite, "held" to pair.held)) {
                val context = "${patch.name} $mode"
                assertEquals(1, snip.channels, context)
                assertEquals(Dsp.RATE, snip.sampleRate, context)
                assertTrue(snip.frameCount in 256..Dsp.RATE * 8, "$context: unexpected duration")
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$context: unsafe output")
                val level = Loudness.of(snip)
                assertTrue(level >= .9f * Dsp.MELODIC_LOUDNESS_TARGET || snip.peak() >= .95f,
                    "$context: loudness $level and peak ${snip.peak()} miss the house output target")
                assertTrue(abs(ContourTestAudio.mean(snip.samples)) < .01, "$context: excess DC")
                requireRoot(patch, snip, .5f, context)
            }
            val classified = Classifier.classify(pair.finite).drumClass
            assertTrue(classified !in drums, "${patch.name}: finite source classified as drum $classified")
        }
    }

    @Test
    fun `every held recipe settles against a real previous period before the wrap treatment`() {
        for (patch in ContourPresets.all()) {
            val measured = audio.getValue(patch.name).measured
            assertTrue(measured.periodicError < 1e-3, "${patch.name}: consecutive-period error")
            assertTrue(measured.stateError < 1e-3, "${patch.name}: oscillator or filter state did not settle")
            assertTrue(measured.seamBeforeBlend < 1e-3, "${patch.name}: unblended loop does not close")
            assertTrue(Keys.seamError(measured.samples, measured.loopStart) < Keys.MAX_SEAM_ERROR,
                "${patch.name}: exported wrap does not close")
            assertTrue(abs(FineTuning.cents(measured.actualHz, measured.noteHz)) < .05,
                "${patch.name}: fitting a loop shifted the requested note")
        }
    }

    @Test
    fun `new finite recipes differ from same voice neighbors beyond a scalar level change`() {
        val features = ContourPresets.all().associate { it.name to features(audio.getValue(it.name).finite) }
        for (voice in ContourVoice.entries) {
            val patches = ContourPresets.forVoice(voice)
            for ((i, a) in patches.withIndex()) for (b in patches.drop(i + 1)) {
                if (a in ContourPresets.original() && b in ContourPresets.original()) continue
                val fa = features.getValue(a.name)
                val fb = features.getValue(b.name)
                val spectral = spectralDistance(fa, fb)
                val envelope = envelopeDistance(fa, fb)
                val duration = abs(fa.frames - fb.frames).toDouble() / maxOf(fa.frames, fb.frames)
                // Regression margins against duplicate recipes, not hearing thresholds.
                // All features remove scalar gain; a louder copy cannot satisfy this guard.
                assertTrue(distinct(fa, fb),
                    "$voice: ${a.name} and ${b.name} are too similar: spectrum $spectral dB, envelope $envelope, duration $duration")
            }
        }
    }

    @Test
    fun `the diversity probe identifies a deliberately gain changed duplicate`() {
        val original = FloatArray(32_768) { i ->
            val t = i.toDouble() / Dsp.RATE
            ((sin(2 * PI * 130.8128 * t) + .3 * sin(2 * PI * 392.4384 * t)) *
                (1 - i.toDouble() / 32_768)).toFloat()
        }
        val a = features(Snip(original, 1, Dsp.RATE))
        val b = features(Snip(FloatArray(original.size) { original[it] * .125f }, 1, Dsp.RATE))
        assertFalse(distinct(a, b), "the diversity probe mistakes a gain-only copy for a new preset")
        assertTrue(spectralDistance(a, b) < 1e-5, "normalized spectra retained scalar gain")
        assertTrue(envelopeDistance(a, b) < 1e-7, "normalized envelopes retained scalar gain")
    }

    @Test
    fun `one new recipe per voice remains deterministic and responds to excitation velocity`() {
        for (voice in ContourVoice.entries) {
            val patch = ContourPresets.added().first { it.voice == voice }
            val finite = patch.copy(macros = patch.macros + ("HOLD" to 0f))
            assertContentEquals(audio.getValue(patch.name).finite.samples, finite.render().samples, patch.name)
            assertContentEquals(audio.getValue(patch.name).held.samples,
                patch.copy(macros = patch.macros + ("HOLD" to 1f)).render().samples, "${patch.name} held")
            assertTrue(finite.copy(velocity = 0f).render().samples.all { it == 0f }, "${patch.name}: zero velocity")
            val strong = ContourEngine.renderRaw(voice, finite.macros, patch.velocity, patch.config)
            val soft = ContourEngine.renderRaw(voice, finite.macros, .25f, patch.config)
            assertTrue(ContourTestAudio.rms(soft) < ContourTestAudio.rms(strong) * .8,
                "${patch.name}: softer excitation does not reduce energy before loudness makeup")
        }
    }

    @Test
    fun `one ordinary new recipe per voice retains detected roots in lower and upper registers`() {
        for (voice in ContourVoice.entries) {
            val patch = ContourPresets.added().first { it.voice == voice }
            // Seven semitones leaves bass above the public detector's 40 Hz floor.
            for (tune in listOf(.5f - 7f / 48, .5f + 7f / 48)) {
                val snip = patch.copy(macros = patch.macros + mapOf("TUNE" to tune, "HOLD" to 0f)).render()
                requireRoot(patch, snip, tune, "${patch.name} TUNE $tune")
            }
        }
    }

    @Test
    fun `octave bell and band pass recipes retain detected roots at EDGE maximum and BODY extremes`() {
        val candidates = ContourPresets.added().filter {
            it.config.edgeRatio != 1f || it.config.bodyTable == ContourTable.DIGITAL_BELL ||
                it.config.filterType == ContourFilterType.BAND_PASS
        }
        assertEquals(setOf("Low Lantern", "Folded Shade", "Amber Reply", "Open Thread", "Evening Glass",
            "Glass Seed", "Soft Signal", "Paper Window"), candidates.map { it.name }.toSet(),
            "the risk matrix must include every new octave companion, bell body and band-pass recipe")
        val failures = mutableListOf<String>()
        var cases = 0
        var worstCents = 0.0
        var lowestConfidence = 1f
        for (patch in candidates) for (tune in listOf(.5f - 7f / 48, .5f + 7f / 48)) {
            for (body in listOf(0f, 1f)) for (hold in listOf(0f, 1f)) {
                val context = "${patch.name} TUNE $tune BODY $body HOLD $hold EDGE 1"
                val snip = patch.copy(macros = patch.macros + mapOf("TUNE" to tune, "BODY" to body,
                    "HOLD" to hold, "EDGE" to 1f)).render()
                cases++
                try {
                    val (cents, confidence) = requireRoot(patch, snip, tune, context)
                    worstCents = maxOf(worstCents, abs(cents))
                    lowestConfidence = minOf(lowestConfidence, confidence)
                } catch (failure: AssertionError) {
                    failures += failure.message ?: context
                }
            }
        }
        println("CONTOUR expanded risk matrix: $cases cases, ${failures.size} failed; successful cases maximum $worstCents cents, minimum confidence $lowestConfidence")
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun requireRoot(patch: ContourPatch, snip: Snip, tune: Float, context: String): Pair<Double, Float> {
        // Measure the strongest 350 ms body window, so a long swell and a short pluck
        // are both judged on their note rather than an arbitrarily placed silent tail.
        val size = minOf((.35f * Dsp.RATE).toInt(), snip.frameCount)
        val hop = Dsp.RATE / 20
        val from = (0..snip.frameCount - size step hop).maxBy {
            ContourTestAudio.rms(snip.samples, it, it + size)
        }
        val detected = assertNotNull(Pitch.detect(snip, from.toFloat() / Dsp.RATE,
            size.toFloat() / Dsp.RATE), "$context: no independently detected root")
        val requested = ContourEngine.frequencyFor(patch.voice, tune).toDouble()
        val cents = FineTuning.cents(detected.hz.toDouble(), requested)
        assertTrue(abs(cents) < 10, "$context: actual detector selected ${detected.hz} Hz, $cents cents from the root")
        assertTrue(detected.confidence >= .75f, "$context: root confidence ${detected.confidence}")
        return cents to detected.confidence
    }

    private data class Features(val spectrum: DoubleArray, val envelope: DoubleArray, val frames: Int)

    private fun features(snip: Snip): Features {
        val size = 8_192
        val count = 40
        val energy = DoubleArray(count)
        val scale = count / ln(16_000.0 / 25.0)
        for (from in 0..snip.frameCount - size step size / 2) {
            val power = ContourTestAudio.power(snip.samples, from, size)
            for (bin in 1 until power.size) {
                val hz = bin.toDouble() * Dsp.RATE / size
                if (hz >= 25 && hz < 16_000) {
                    energy[(ln(hz / 25) * scale).toInt().coerceIn(0, count - 1)] += power[bin]
                }
            }
        }
        val total = energy.sum()
        require(total > 1e-12) { "silent diversity fixture" }
        val envelope = DoubleArray(24) { n ->
            ContourTestAudio.rms(snip.samples, n * snip.frameCount / 24, (n + 1) * snip.frameCount / 24)
        }
        val maximum = envelope.max()
        return Features(DoubleArray(count) { energy[it] / total },
            DoubleArray(envelope.size) { envelope[it] / maximum }, snip.frameCount)
    }

    private fun spectralDistance(a: Features, b: Features): Double = sqrt(a.spectrum.indices.sumOf {
        val difference = 10 * log10(b.spectrum[it].coerceAtLeast(1e-10) / a.spectrum[it].coerceAtLeast(1e-10))
        (a.spectrum[it] + b.spectrum[it]) * .5 * difference * difference
    })

    private fun envelopeDistance(a: Features, b: Features): Double =
        a.envelope.indices.sumOf { abs(a.envelope[it] - b.envelope[it]) } / a.envelope.size

    private fun distinct(a: Features, b: Features): Boolean = spectralDistance(a, b) > .5 ||
        envelopeDistance(a, b) > .08 || abs(a.frames - b.frames).toDouble() / maxOf(a.frames, b.frames) > .1

    private data class Rendered(val finite: Snip, val held: Snip, val measured: ContourEngine.LoopRender)

    companion object {
        // Reuse the same independently rendered evidence across the health, pitch,
        // seam and diversity assertions; JUnit creates a new test object per method.
        private val audio by lazy {
            ContourPresets.all().associate { patch ->
                val measured = ContourEngine.renderLoopMeasured(patch.voice, patch.macros, patch.velocity, patch.config)
                val held = Snip(measured.samples.copyOfRange(measured.loopStart, measured.samples.size), 1, Dsp.RATE)
                patch.name to Rendered(patch.copy(macros = patch.macros + ("HOLD" to 0f)).render(), held, measured)
            }
        }
    }
}
