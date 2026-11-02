package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import com.snipsnap.audio.Pitch
import com.snipsnap.json.Json
import com.snipsnap.json.JsonException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FerryLossTest {
    private fun energy(state: Pair<DoubleArray, DoubleArray>) =
        state.first.indices.sumOf { state.first[it].pow(2) + state.second[it].pow(2) }

    @Test
    fun `model two Float goldens and new neutral LOSS retain the accepted seeded MATCH audio`() {
        val fixture = javaClass.getResourceAsStream("/ferry-model2-goldens.json")!!.bufferedReader().use { it.readText() }
        for (raw in Json.parse(fixture).obj().getValue("cases").arr()) {
            val c = raw.obj()
            val old = FerryPatch("Accepted MATCH", model = 2, midi = c.getValue("midi").int(),
                velocity = c.getValue("velocity").num().toFloat(), seed = c.getValue("seed").str().toLong(),
                macros = mapOf("HANDOFF" to c.getValue("handoff").num().toFloat(), "MATCH" to c.getValue("match").num().toFloat()))
            val samples = Patches.fromJsonText(old.toJsonText()).render().samples
            val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { bytes.putInt(it.toRawBits()) }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
            assertEquals(c.getValue("float_le_sha256").str(), hash, "Accepted model two changed: $old")
            assertContentEquals(samples, old.copy(model = 3).render().samples)
            assertContentEquals(samples, old.copy(model = 3, macros = old.macros + ("LOSS" to .5f)).render().samples)
        }
    }

    @Test
    fun `LOSS changes only physical damping and competes with reciprocal transfer`() {
        val baseline = Ferry.plan(48, .5f, .5f)
        val initial = Ferry.initial()
        val e0 = initial.sumOf { it * it }
        val energies = mutableListOf<Double>()
        for (loss in listOf(0f, .25f, .5f, .75f, 1f)) {
            val plan = Ferry.plan(48, .5f, .5f, loss)
            val scale = Ferry.lossScale(loss)
            for (i in 0 until FerryNetwork.SIZE) assertContentEquals(baseline.model.h[i], plan.model.h[i])
            assertContentEquals(baseline.model.pickup, plan.model.pickup)
            assertContentEquals(baseline.eigenvalues, plan.eigenvalues)
            for (i in plan.model.gamma.indices) assertEquals(baseline.model.gamma[i] * scale, plan.model.gamma[i])
            var previous = e0
            for (seconds in listOf(.1, .5, 1.0, 2.0, 4.0, 8.0)) {
                val e = energy(plan.state(initial, (seconds * Ferry.INTERNAL_RATE).toInt()))
                assertTrue(e <= previous + 1e-9, "Unforced energy grew: $loss/$seconds")
                assertTrue(e <= e0 * exp(-2 * baseline.model.gamma.min() * scale * seconds) + 1e-9)
                previous = e
            }
            energies += energy(plan.state(initial, Ferry.INTERNAL_RATE))
        }
        assertTrue(energies.zipWithNext().all { (a, b) -> a > b }, "LOSS must remove energy inside the network")
        assertTrue(energies.last() < energies[2] * .5, "Dry LOSS must substantially change physical decay")
        assertEquals(0.0, initial.slice(4..20).sumOf { it * it })
    }

    @Test
    fun `equal material losses retain the exact analytic norm and handoff under every LOSS setting`() {
        val omega = 2 * PI * 130.8127826502993
        val g = PI / .8
        for (loss in listOf(0f, .25f, .5f, .75f, 1f)) {
            val gamma = .4 * Ferry.lossScale(loss)
            val model = FerryNetwork.Model(arrayOf(doubleArrayOf(omega, g), doubleArrayOf(g, omega)),
                doubleArrayOf(gamma, gamma), doubleArrayOf(1.0, 1.0))
            val plan = FerryNetwork.Compiled(model, Ferry.INTERNAL_RATE)
            for (t in listOf(.1, .4, .8, 2.0)) {
                val state = plan.state(doubleArrayOf(1.0, 0.0), (t * Ferry.INTERNAL_RATE).toInt())
                assertTrue(abs(energy(state) - exp(-2 * gamma * t)) < 1e-8)
                if (t == .4) assertTrue(abs(state.first[1].pow(2) + state.second[1].pow(2) - exp(-2 * gamma * t)) < 1e-8)
            }
        }
    }

    @Test
    fun `all LOSS and MATCH corners retain pitch output health and their saved finite duration`() {
        var maxCents = 0.0
        var minConfidence = 1f
        for (midi in listOf(36, 48, 72)) for (handoff in listOf(.25f, .5f, 1f)) {
            for (match in listOf(0f, .25f, .5f, .75f, 1f)) for (loss in listOf(0f, .25f, .5f, .75f, 1f)) {
                val snip = Ferry.render(midi = midi, macros = mapOf("HANDOFF" to handoff, "MATCH" to match, "LOSS" to loss))
                assertEquals(Ferry.outputFrames(loss), snip.frameCount)
                assertEquals(44100, snip.sampleRate)
                assertEquals(1, snip.channels)
                assertTrue(snip.samples.all { it.isFinite() })
                assertTrue(snip.peak() in .01f..1f)
                assertTrue(abs(snip.samples.sumOf { it.toDouble() } / snip.samples.size) < .001)
                assertTrue(abs(Loudness.of(snip) - Dsp.MELODIC_LOUDNESS_TARGET) < .001)
                val seconds = snip.frameCount.toDouble() / Dsp.RATE
                val hz = 440 * 2.0.pow((midi - 69) / 12.0)
                for (start in listOf(.05f, (seconds * .325).toFloat(), (seconds * .8).toFloat())) {
                    val pitch = assertNotNull(Pitch.detect(snip, start), "$midi/$handoff/$match/$loss no root")
                    val cents = abs(1200 * ln(pitch.hz / hz) / ln(2.0))
                    maxCents = maxOf(maxCents, cents)
                    minConfidence = minOf(minConfidence, pitch.confidence)
                    assertTrue(cents < 20, "$midi/$handoff/$match/$loss root at $start")
                    assertTrue(pitch.confidence >= .9f, "$midi/$handoff/$match/$loss confidence at $start")
                }
                assertTrue(Classifier.classify(snip).drumClass !in setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM))
            }
        }
        assertEquals(8 * Dsp.RATE, Ferry.outputFrames(0f))
        assertEquals(4 * Dsp.RATE, Ferry.outputFrames(.5f))
        assertEquals(Dsp.RATE, Ferry.outputFrames(1f))
        println("FERRY LOSS 225 native cases: maximum cents $maxCents; minimum confidence $minConfidence")
    }

    @Test
    fun `saved LOSS recipes survive edits and cold cache while old models refuse the new control`() {
        val patch = FerryPatch("Long bloom", midi = 57, velocity = .6f, seed = Long.MIN_VALUE, macros = mapOf("LOSS" to 0f, "MATCH" to .25f))
        val samples = patch.render().samples
        assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        assertEquals(patch.copy(name = "Edited"), Patches.edited(patch, "Edited", patch.macros))
        assertContentEquals(samples, PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).render().samples)
        Ferry.clearCache()
        for (i in 0..35) Ferry.plan(36 + i, .5f, .5f, i / 35f)
        assertContentEquals(samples, patch.render().samples)
        assertContentEquals(Ferry.render().samples, Ferry.render(macros = mapOf("LOSS" to Float.NaN, "unknown" to .3f)).samples)
        for (model in 1..2) {
            val old = FerryPatch("Old", model = model)
            val text = old.toJsonText().replace("\"macros\": {}", "\"macros\": {\"LOSS\": 0.5}")
            assertTrue(assertFailsWith<JsonException> { FerryPatch.fromJsonText(text) }.message.orEmpty().contains("LOSS"))
            assertTrue(assertFailsWith<IllegalArgumentException> { old.copy(macros = mapOf("LOSS" to .5f)) }.message.orEmpty().contains("LOSS"))
            assertTrue(assertFailsWith<IllegalArgumentException> { Ferry.render(model = model, macros = mapOf("LOSS" to .5f)) }.message.orEmpty().contains("LOSS"))
        }
        for (scale in listOf(0.0, -.5, Double.NaN, Double.POSITIVE_INFINITY, 4.1)) {
            assertTrue(assertFailsWith<IllegalArgumentException> { FerryNetwork.build(48, 1.0, lossScale = scale) }.message.orEmpty().contains("loss scale"))
        }
        for (loss in listOf(0f, .5f, 1f)) assertContentEquals(FloatArray(Ferry.outputFrames(loss)), Ferry.render(velocity = 0f, macros = mapOf("LOSS" to loss)).samples)
    }
}
