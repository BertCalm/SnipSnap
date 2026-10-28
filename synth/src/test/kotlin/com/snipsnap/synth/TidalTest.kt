package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import java.util.concurrent.CancellationException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Finite mechanism/stability checks; these do not establish musical preference. */
class TidalTest {
    private val diagnostic = Tidal.Options(oversample = 1, particleCount = 256)
    private val strong = mapOf("APPROACH" to .35f, "PASSAGE" to .90f,
        "TILT" to .15f, "BIND" to .10f, "TAIL" to .80f, "HOLD" to 0f)
    private fun distance(a: FloatArray, b: FloatArray): Double =
        sqrt(a.indices.sumOf { (a[it].toDouble() - b[it]).let { d -> d * d } } / a.size)

    @Test
    fun `finite recipe has five timbral macros independent of note and refuses fractional hold`() {
        for (voice in TidalVoice.entries) {
            assertEquals(listOf("APPROACH", "PASSAGE", "TILT", "BIND", "TAIL", "HOLD"), Tidal.macrosFor(voice).map { it.name })
            assertEquals(0f, Tidal.defaults(voice).getValue("HOLD"))
            assertEquals(DrumClass.LOOP, Tidal.drumClassFor(voice))
            assertFailsWith<IllegalArgumentException> { Tidal.probe(voice, mapOf("HOLD" to .01f), options = diagnostic) }
        }
        assertFailsWith<IllegalArgumentException> { Tidal.probe(TidalVoice.PAIR, midi = 35, options = diagnostic) }
        assertFailsWith<IllegalArgumentException> { Tidal.probe(TidalVoice.PAIR, model = 3, options = diagnostic) }
        assertFailsWith<IllegalArgumentException> { Tidal.probe(TidalVoice.PAIR, velocity = Float.NaN, options = diagnostic) }
        assertFailsWith<IllegalArgumentException> { Tidal.probe(TidalVoice.PAIR, mapOf("TAIL" to -1f), options = diagnostic) }
        assertFailsWith<IllegalArgumentException> { Tidal.probe(TidalVoice.PAIR, mapOf("TUNE" to .5f), options = diagnostic) }
    }

    @Test
    fun `model one retains the published samples and its original defaults`() {
        fun hash(samples: FloatArray): String {
            val buffer = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) buffer.putFloat(sample)
            return MessageDigest.getInstance("SHA-256").digest(buffer.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        for ((voice, expected) in listOf(
            TidalVoice.PAIR to "e1d06a18be54bed9b546d9cf6765f1f987cb7ad1e725d03cb84494e8c0766f63",
            TidalVoice.STREAM to "96c4fe54ed29e0453a09f0e9d5fe5acf0bdc304706a68173a8abeb980dc2d7a2",
        )) {
            val original = Tidal.probe(voice, midi = 48, seed = 41L, model = 1)
            assertEquals(expected, hash(original.rawMix), "$voice model1 changed the published source")
            val explicit = Tidal.probe(voice, Tidal.defaults(voice, 1), midi = 48, seed = 41L, model = 1)
            assertContentEquals(original.rawMix, explicit.rawMix)
        }
        assertEquals(.35f, Tidal.defaults(TidalVoice.PAIR, 1).getValue("PASSAGE"))
        assertEquals(.78f, Tidal.defaults(TidalVoice.PAIR, 2).getValue("PASSAGE"))
    }

    @Test
    fun `new sources keep distinct evolving spectra even without a tidal encounter`() {
        // The musical listening verdict stays outside this check. This prevents the
        // replacement from collapsing back to the original steady eight-partial tone.
        val opts = diagnostic.copy(noTidalGradient = true)
        val current = Tidal.probe(TidalVoice.STREAM, midi = 48, options = opts)
        val original = Tidal.probe(TidalVoice.STREAM, midi = 48, model = 1, options = opts)
        assertTrue(distance(current.rawMix, original.rawMix) > .003)
        assertTrue(current.diagnostics.maxFrequencyHz > original.diagnostics.maxFrequencyHz * 1.8)
        assertEquals(32, current.trace.first().modeAmplitudes.size)
        assertEquals(16, original.trace.first().modeAmplitudes.size)
        fun energy(samples: FloatArray, start: Double, end: Double): Double =
            ((start * Dsp.RATE).toInt() until (end * Dsp.RATE).toInt()).sumOf { samples[it].toDouble().let { x -> x * x } }
        val earlyA = energy(current.populationA, .15, .40)
        val lateA = energy(current.populationA, 1.4, 1.65)
        val earlyB = energy(current.populationB, .15, .40)
        val lateB = energy(current.populationB, 1.4, 1.65)
        assertTrue(earlyB / lateB > earlyA / lateA * 1.5,
            "metal strike and dragged string lost their different source time structures")
        assertTrue(current.diagnostics.tailRms < 1e-8, "new contact source invented a tail without tidal extension")
    }

    @Test
    fun `same saved performance is sample identical regardless of macro order`() {
        val a = Tidal.probe(TidalVoice.STREAM, strong, seed = Long.MIN_VALUE, options = diagnostic)
        val b = Tidal.probe(TidalVoice.STREAM, strong.entries.reversed().associate { it.key to it.value },
            seed = Long.MIN_VALUE, options = diagnostic)
        assertContentEquals(a.rawMix, b.rawMix)
        assertContentEquals(a.samples, b.samples)
        assertContentEquals(a.populationA, b.populationA)
        assertContentEquals(a.populationB, b.populationB)
        assertEquals(a.trace, b.trace)
        val changed = Tidal.probe(TidalVoice.STREAM, strong, seed = Long.MAX_VALUE, options = diagnostic)
        assertTrue(distance(a.rawMix, changed.rawMix) > .0001)
    }

    @Test
    fun `source allocation stems and raw headroom stay bounded and excitation ends`() {
        val p = Tidal.probe(TidalVoice.STREAM, strong, options = diagnostic)
        assertEquals((9.8 * Dsp.RATE).toInt(), p.rawMix.size)
        assertTrue(p.rawMix.all { it.isFinite() })
        assertTrue(p.diagnostics.rawPeak in .001.. .96)
        assertTrue(abs(p.diagnostics.rawDc) < .0001)
        assertTrue(p.diagnostics.maxCents <= 12.001)
        assertTrue(p.trace.all { abs(it.coreAllocation + it.tailAllocation - 1) < 1e-9 })
        assertTrue(p.diagnostics.maxModalAmplitude < .25)
        for (i in p.rawMix.indices step 113) {
            assertEquals(p.rawMix[i], p.core[i] + p.tail[i])
            assertEquals(p.rawMix[i].toDouble(), (p.populationA[i] + p.populationB[i]).toDouble(), 1e-7)
        }
        val last = p.rawMix.takeLast(Dsp.RATE / 4)
        val previous = p.rawMix.slice((7.5 * Dsp.RATE).toInt() until (7.75 * Dsp.RATE).toInt())
        val energyLast = last.sumOf { it.toDouble() * it }
        val energyPrevious = previous.sumOf { it.toDouble() * it }
        assertTrue(energyLast < energyPrevious * .01, "source did not stop and decay")
        assertEquals(0f, p.samples.last())
        assertTrue(distance(p.populationA, p.populationB) > .001, "two source spectra collapsed")
    }

    @Test
    fun `tails require differential companion force and weak material is not normalized`() {
        val full = Tidal.probe(TidalVoice.STREAM, strong, options = diagnostic)
        val noGradient = Tidal.probe(TidalVoice.STREAM, strong, options = diagnostic.copy(noTidalGradient = true))
        assertTrue(full.diagnostics.tailRms > .0001)
        assertTrue(noGradient.diagnostics.tailRms < full.diagnostics.tailRms * .15,
            "removing tidal gradients left a generic tail")
        val weak = Tidal.probe(TidalVoice.STREAM, strong + mapOf("PASSAGE" to 0f, "BIND" to 1f), options = diagnostic)
        assertTrue(weak.diagnostics.tailRms < full.diagnostics.tailRms * .25,
            "weak structural tails were amplified into a separate instrument")
        val fixed = Tidal.probe(TidalVoice.STREAM, strong, options = diagnostic.copy(fixedTailEnvelope = true))
        val collapsed = Tidal.probe(TidalVoice.STREAM, strong, options = diagnostic.copy(singleDescriptor = true))
        assertTrue(distance(full.rawMix, fixed.rawMix) > .0001, "tail evolution is just a fixed passage decay")
        assertTrue(distance(full.rawMix, collapsed.rawMix) > .0001, "persistent cohort detail has no output")
    }

    @Test
    fun `each timbral macro changes a finite performance and pickup does not change geometry`() {
        for (macro in listOf("APPROACH", "PASSAGE", "TILT", "BIND", "TAIL")) {
            val a = Tidal.probe(TidalVoice.STREAM, strong + (macro to 0f), options = diagnostic)
            val b = Tidal.probe(TidalVoice.STREAM, strong + (macro to .5f), options = diagnostic)
            assertTrue(distance(a.rawMix, b.rawMix) > .0001, "$macro has no low-to-middle effect")
            if (macro == "TAIL") {
                assertEquals(a.dynamics.diagnostics, b.dynamics.diagnostics)
                assertEquals(a.trace.map { it.extension }, b.trace.map { it.extension })
            }
        }
    }

    @Test
    fun `zero velocity is silent and cancellation works on cached preparation`() {
        val silent = Tidal.probe(TidalVoice.PAIR, velocity = 0f, options = diagnostic)
        assertTrue(silent.samples.all { it == 0f })
        assertTrue(silent.tail.all { it == 0f })
        assertTrue(silent.populationA.all { it == 0f })
        assertFailsWith<CancellationException> {
            Tidal.probe(TidalVoice.PAIR, velocity = 0f, options = diagnostic, cancelled = { true })
        }
    }

    @Test
    fun `standard rendering retains root across the register and brighter faster audio converges`() {
        for (midi in listOf(36, 60, 84)) {
            val p = Tidal.probe(TidalVoice.STREAM, strong, midi = midi,
                options = Tidal.Options(particleCount = 256))
            for (start in listOf(.3f, 4.6f)) {
                val pitch = assertNotNull(Pitch.detect(Snip(p.samples, 1, Dsp.RATE), start, .4f))
                val cents = abs(1200 * ln(pitch.hz / Keys.midiHz(midi).toDouble()) / ln(2.0))
                assertTrue(cents < 15, "MIDI$midi at $start: $cents cents")
                assertTrue(pitch.confidence > .5)
            }
            // Keep the classifier below its 1.5-second duration shortcut.
            val drum = Classifier.classify(Snip(p.samples.copyOfRange(0, (1.25 * Dsp.RATE).toInt()), 1, Dsp.RATE)).drumClass
            assertTrue(drum !in setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
                DrumClass.HAT_OPEN, DrumClass.HAT_CLOSED, DrumClass.TOM), "MIDI$midi was $drum")
        }
        val bright = strong + mapOf("APPROACH" to 1f, "PASSAGE" to 1f)
        val a = Tidal.probe(TidalVoice.STREAM, bright, midi = 84, options = Tidal.Options(particleCount = 256))
        val b = Tidal.probe(TidalVoice.STREAM, bright, midi = 84, options = Tidal.Options(particleCount = 256, oversample = 8))
        assertTrue(distance(a.rawMix, b.rawMix) / a.diagnostics.rawRms < .20,
            "bright 4x audio differs excessively from 8x reference")
    }
}
