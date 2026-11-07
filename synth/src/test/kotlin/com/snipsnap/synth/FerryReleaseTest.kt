package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FerryReleaseTest {
    private fun rms(a: FloatArray, start: Int, end: Int) = sqrt((start until end).sumOf { a[it].toDouble().pow(2) } / (end - start))

    @Test
    fun `published model three Float goldens replay exactly and unchanged settings preserve the approved sound`() {
        val fixture = javaClass.getResourceAsStream("/ferry-model3-goldens.json")!!.bufferedReader().use { it.readText() }
        for (raw in Json.parse(fixture).obj().getValue("cases").arr()) {
            val c = raw.obj()
            val old = FerryPatch("Published LOSS", model = 3, midi = c.getValue("midi").int(),
                velocity = c.getValue("velocity").num().toFloat(), seed = c.getValue("seed").str().toLong(),
                macros = mapOf("HANDOFF" to c.getValue("handoff").num().toFloat(), "MATCH" to c.getValue("match").num().toFloat(),
                    "LOSS" to c.getValue("loss").num().toFloat()))
            val samples = Patches.fromJsonText(old.toJsonText()).render().samples
            val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { bytes.putInt(it.toRawBits()) }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
            assertEquals(c.getValue("float_le_sha256").str(), hash, "Published model three changed: $old")
            assertEquals(c.getValue("frames").int(), samples.size)
        }
        for (loss in listOf(0f, .25f, .5f)) {
            val old = FerryPatch("Accepted window", model = 3, macros = mapOf("LOSS" to loss))
            assertContentEquals(old.render().samples, old.copy(model = 4).render().samples)
        }
    }

    @Test
    fun `dry release continues the network then reaches an inaudible ending without the old abrupt cut`() {
        val loss = 1f
        val plan = Ferry.plan(48, .5f, 1f, loss)
        val start = Ferry.observationFrames(loss)
        val n = Ferry.releaseFrames(loss)
        val raw = plan.render(Ferry.initial(), Ferry.outputFrames(loss) * Dsp.OVERSAMPLE)
        val oldRaw = plan.render(Ferry.initial(), start * Dsp.OVERSAMPLE)
        assertContentEquals(oldRaw, raw.copyOf(oldRaw.size), "Release must continue the same physical gesture")
        val released = Ferry.finish(raw.copyOf(), Ferry.releaseSeconds(loss))
        assertContentEquals(released, Ferry.render(macros = mapOf("LOSS" to loss)).samples,
            "The actual renderer must apply the designed release")
        val unshaped = Ferry.finish(raw.copyOf())
        val gain = released[1000] / unshaped[1000]
        // Independent cosine points, excluding the final shared 4 ms hygiene fade.
        for (fraction in listOf(0.0, .25, .5, .75, .95)) {
            val index = start + (fraction * n).toInt()
            val expected = unshaped[index] * gain * (.5 + .5 * cos(PI * (index - start) / n))
            assertTrue(abs(released[index] - expected) < 2e-7, "Release does not follow the designed contour at $fraction")
        }
        val old = Ferry.render(model = 3, macros = mapOf("LOSS" to loss)).samples
        val tail = Dsp.RATE / 20
        val oldTail = rms(old, old.size - tail, old.size)
        val newTail = rms(released, released.size - tail, released.size)
        assertTrue(oldTail > .005, "The retained example must expose the audible old cut")
        assertTrue(newTail < .001, "Release must reach below -60 dBFS before the file ends")
        assertTrue(newTail < oldTail / 20, "Dry ending must become substantially quieter")
        assertEquals(0f, released.last())
        println("FERRY RELEASE dry last50ms RMS old=$oldTail new=$newTail frames=${released.size}")
    }

    @Test
    fun `short LOSS settings keep pitch output health and classification throughout the revised register grid`() {
        var maximumCents = 0.0
        var minimumConfidence = 1f
        for (midi in listOf(36, 48, 72)) for (handoff in listOf(.25f, .5f, 1f)) {
            for (match in listOf(0f, .5f, 1f)) for (loss in listOf(.625f, .75f, 1f)) {
                val snip = Ferry.render(midi = midi, macros = mapOf("HANDOFF" to handoff, "MATCH" to match, "LOSS" to loss))
                assertEquals(Ferry.outputFrames(loss), snip.frameCount)
                assertEquals(44100, snip.sampleRate)
                assertEquals(1, snip.channels)
                assertTrue(snip.samples.all { it.isFinite() })
                assertTrue(snip.peak() in .01f..1f)
                assertTrue(abs(snip.samples.sumOf { it.toDouble() } / snip.samples.size) < .001)
                assertTrue(abs(Loudness.of(snip) - Dsp.MELODIC_LOUDNESS_TARGET) < .001)
                val seconds = Ferry.observationFrames(loss).toFloat() / Dsp.RATE
                val hz = 440 * 2.0.pow((midi - 69) / 12.0)
                for (from in listOf(.05f, seconds * .325f, seconds * .8f)) {
                    val pitch = assertNotNull(Pitch.detect(snip, from))
                    val cents = abs(1200 * ln(pitch.hz / hz) / ln(2.0))
                    maximumCents = maxOf(maximumCents, cents)
                    minimumConfidence = minOf(minimumConfidence, pitch.confidence)
                    assertTrue(cents < 20, "$midi/$handoff/$match/$loss root at $from: $cents")
                    assertTrue(pitch.confidence >= .9f, "$midi/$handoff/$match/$loss confidence at $from")
                }
                assertTrue(Classifier.classify(snip).drumClass !in setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP,
                    DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM))
            }
        }
        println("FERRY RELEASE 81 cases: maximum cents $maximumCents; minimum confidence $minimumConfidence")
    }

    @Test
    fun `versioned release duration and exact seeded recipes survive cold cache edits and silent velocity`() {
        val patch = FerryPatch("Released dry", midi = 57, velocity = .6f, seed = Long.MIN_VALUE,
            macros = mapOf("LOSS" to 1f, "MATCH" to .25f))
        val samples = patch.render().samples
        assertEquals(4, patch.model)
        assertEquals(59535, samples.size)
        assertEquals(44100, patch.copy(model = 3).render().frameCount)
        assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        assertContentEquals(samples, PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).render().samples)
        Ferry.clearCache()
        assertContentEquals(samples, Patches.edited(patch, "Edited", patch.macros).render().samples)
        for (loss in listOf(0f, .5f, .75f, 1f)) for (model in 3..4) {
            assertContentEquals(FloatArray(Ferry.outputFrames(loss, model)), Ferry.render(model = model, velocity = 0f, macros = mapOf("LOSS" to loss)).samples)
        }
        assertContentEquals(Ferry.render().samples, Ferry.render(macros = mapOf("LOSS" to Float.NaN)).samples)
    }
}
