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
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FerryMatchTest {
    private fun energy(state: Pair<DoubleArray, DoubleArray>, indices: IntRange) =
        indices.sumOf { state.first[it].pow(2) + state.second[it].pow(2) }

    @Test
    fun `legacy files and the aligned new model retain independent accepted Float goldens`() {
        val fixture = javaClass.getResourceAsStream("/ferry-model1-goldens.json")!!.bufferedReader().use { it.readText() }
        for (raw in Json.parse(fixture).obj().getValue("cases").arr()) {
            val c = raw.obj()
            val patch = FerryPatch("Legacy", macros = mapOf("HANDOFF" to c.getValue("handoff").num().toFloat()),
                midi = c.getValue("midi").int(), velocity = c.getValue("velocity").num().toFloat(),
                seed = c.getValue("seed").str().toLong(), model = 1)
            val legacy = Patches.fromJsonText(patch.toJsonText()).render().samples
            val bytes = ByteBuffer.allocate(legacy.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            legacy.forEach { bytes.putInt(it.toRawBits()) }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it) }
            assertEquals(c.getValue("float_le_sha256").str(), hash, "Accepted renderer changed: $patch")
            assertContentEquals(legacy, patch.copy(model = 2, macros = patch.macros + ("MATCH" to 1f)).render().samples)
            assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        }
    }

    @Test
    fun `MATCH detunes only upper B modes while shared roots and passive energy remain bounded`() {
        for (midi in listOf(36, 48, 72)) for (handoff in listOf(.25f, .5f, 1f)) for (match in listOf(0f, .25f, .5f, .75f, 1f)) {
            val plan = Ferry.plan(midi, handoff, match)
            val aligned = Ferry.plan(midi, handoff, 1f)
            val hz = 440 * 2.0.pow((midi - 69) / 12.0)
            val detune = Ferry.mistuneCents(match)
            for (i in 0..3) assertTrue(abs(1200 * ln(plan.eigenvalues[i] / (2 * PI * hz)) / ln(2.0)) < 5.0000001)
            for (i in 0 until FerryNetwork.SIZE) {
                val expected = aligned.model.h[i][i] * if (i in 5..7) 2.0.pow(detune / 1200) else 1.0
                assertTrue(abs(plan.model.h[i][i] - expected) < 1e-9, "$midi/$handoff/$match tuning $i")
            }
            assertContentEquals(aligned.model.gamma, plan.model.gamma)
            assertContentEquals(aligned.model.pickup, plan.model.pickup)
            val initial = Ferry.initial()
            assertEquals(0.0, initial.slice(4..20).sumOf { it * it })
            var previous = initial.sumOf { it * it }
            for (seconds in listOf(.1, .5, 1.0, 2.0, 4.0)) {
                val e = energy(plan.state(initial, (seconds * Ferry.INTERNAL_RATE).toInt()), 0 until FerryNetwork.SIZE)
                assertTrue(e <= previous + 1e-9, "Energy grew at $midi/$handoff/$match/$seconds")
                previous = e
            }
        }
    }

    @Test
    fun `detuning suppresses acquired wire energy and stronger HANDOFF resists it`() {
        fun peakB(handoff: Float, match: Float): Double {
            val plan = Ferry.plan(48, handoff, match)
            return (1..40).maxOf { tenth ->
                energy(plan.state(Ferry.initial(), tenth * Ferry.INTERNAL_RATE / 10), 5..7)
            }
        }
        val fractions = listOf(.25f, .5f, 1f).map { handoff ->
            val aligned = peakB(handoff, 1f)
            val apart = peakB(handoff, 0f)
            assertTrue(apart < aligned, "MATCH must reduce physical acquisition, HANDOFF=$handoff")
            apart / aligned
        }
        assertTrue(fractions.first() < .5, "At slow HANDOFF, MATCH must remove at least half the acquired upper B energy")
        assertTrue(fractions.last() > fractions.first() + .05, "HANDOFF and MATCH must interact through transfer")
    }

    @Test
    fun `the complete MATCH range retains output health and requested pitch across registers and speeds`() {
        for (midi in listOf(36, 48, 72)) for (handoff in listOf(.25f, .5f, 1f)) for (match in listOf(0f, .25f, .5f, .75f, 1f)) {
            val snip = Ferry.render(macros = mapOf("HANDOFF" to handoff, "MATCH" to match), midi = midi)
            assertEquals(1, snip.channels)
            assertEquals(44100, snip.sampleRate)
            assertEquals(176400, snip.frameCount)
            assertTrue(snip.samples.all { it.isFinite() })
            assertTrue(snip.samples.maxOf { abs(it) } in .01f..1f)
            assertTrue(abs(snip.samples.sumOf { it.toDouble() } / snip.samples.size) < .001)
            assertTrue(abs(Loudness.of(snip) - Dsp.MELODIC_LOUDNESS_TARGET) < .001)
            val root = 440 * 2.0.pow((midi - 69) / 12.0)
            for (time in listOf(.05f, 1.3f, 3.2f)) {
                val pitch = assertNotNull(Pitch.detect(snip, time), "$midi/$handoff/$match/$time missing pitch")
                assertTrue(abs(1200 * ln(pitch.hz / root) / ln(2.0)) < 20, "$midi/$handoff/$match/$time root")
                assertTrue(pitch.confidence >= .9f, "$midi/$handoff/$match/$time confidence")
            }
            assertTrue(Classifier.classify(snip).drumClass !in setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM))
        }
    }

    @Test
    fun `partial recipes model guards sanitation and cold MATCH plans replay without cross contamination`() {
        val patch = FerryPatch("Apart", macros = mapOf("MATCH" to .25f), seed = Long.MIN_VALUE)
        assertEquals(patch, Patches.fromJsonText(patch.toJsonText()))
        assertEquals(patch.copy(name = "Edited"), Patches.edited(patch, "Edited", patch.macros))
        assertContentEquals(patch.render().samples, PadRecipe.fromJsonText(PadRecipe(patch).toJsonText()).render().samples)
        val expected = patch.render().samples
        Ferry.clearCache()
        for (i in 0..35) Ferry.plan(36 + i % 37, .5f, i / 36f)
        assertContentEquals(expected, patch.render().samples)
        assertContentEquals(Ferry.render().samples, Ferry.render(macros = mapOf("MATCH" to Float.NaN, "unknown" to .5f)).samples)
        val old = FerryPatch("Old", model = 1)
        assertTrue(assertFailsWith<JsonException> {
            FerryPatch.fromJsonText(old.toJsonText().replace("\"macros\": {}", "\"macros\": {\"MATCH\": 0.5}"))
        }.message.orEmpty().contains("MATCH"))
        assertTrue(assertFailsWith<IllegalArgumentException> { old.copy(macros = mapOf("MATCH" to .5f)) }.message.orEmpty().contains("MATCH"))
        assertTrue(assertFailsWith<IllegalArgumentException> { Ferry.render(model = 1, macros = mapOf("MATCH" to 0f)) }.message.orEmpty().contains("MATCH"))
        assertTrue(assertFailsWith<IllegalArgumentException> {
            FerryNetwork.build(48, 1.0, mistuneCents = Ferry.MAX_MISTUNE_CENTS + 1)
        }.message.orEmpty().contains("mistuning"))
        val middle = Ferry.render(macros = mapOf("MATCH" to .5f)).samples
        val aligned = Ferry.render().samples
        val normalizedDistance = sqrt(middle.indices.sumOf { (middle[it] - aligned[it]).toDouble().pow(2) } / aligned.sumOf { it.toDouble().pow(2) })
        assertTrue(normalizedDistance > .1, "MATCH must change the conditioned audio")
    }
}
