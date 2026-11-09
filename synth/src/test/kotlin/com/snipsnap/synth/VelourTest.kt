package com.snipsnap.synth

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import java.security.MessageDigest
import kotlin.math.*
import kotlin.test.*

class VelourTest {
    companion object {
        fun fixture(name: String): Snip = WavReader.read(checkNotNull(VelourTest::class.java.getResourceAsStream("/velour/$name.wav")).use { it.readBytes() })
        fun onsets(): DoubleArray = Json.parse(checkNotNull(VelourTest::class.java.getResourceAsStream("/velour/onsets.json")).bufferedReader().use { it.readText() })
            .arr().map { it.num() }.toDoubleArray()
        fun relativeError(actual: FloatArray, expected: FloatArray): Double =
            sqrt(actual.indices.sumOf { (actual[it].toDouble() - expected[it]).pow(2) } / expected.sumOf { it.toDouble().pow(2) })
    }

    @Test fun `both native treatments preserve the approved Python bus within minus 80 dB`() {
        val dry = fixture("dry").samples; val send = fixture("send").samples
        for ((name, treatment) in listOf("velour" to Velour.Treatment.VELOUR, "bloom" to Velour.Treatment.BLOOM)) {
            val expected = fixture(name).samples
            val actual = Velour.renderBus(dry, send, onsets(), treatment).samples
            assertEquals(expected.size, actual.size)
            val error = relativeError(actual, expected)
            println("VELOUR $name native/Python relative RMS error: ${20 * log10(error)} dB")
            assertTrue(error < 1e-4, "$name relative error $error")
            assertTrue(actual.all { it.isFinite() })
            // Raw reference buses exceed full scale before their shared presentation gain.
            assertEquals(expected.maxOf { abs(it) }, actual.maxOf { abs(it) }, 1e-5f)
            assertTrue(abs(actual.average()) < .005)
        }
    }

    @Test fun `source assets match frozen checksums and callers own their buffers`() {
        val manifest = Json.parse(Velour.resource("manifest.json").decodeToString()).obj().getValue("files").obj()
        for ((name, value) in manifest) {
            val hash = MessageDigest.getInstance("SHA-256").digest(Velour.resource(name)).joinToString("") { "%02x".format(it) }
            assertEquals(value.str(), hash, name)
        }
        val role = VelourKits.roles.first()
        val one = VelourKits.source(role); val two = VelourKits.source(role)
        assertContentEquals(one.samples, two.samples)
        one.samples.fill(0f)
        assertTrue(two.peak() > .01f)
    }

    @Test fun `board recipe roundtrips with hard bypass and independent state`() {
        val source = VelourKits.source(VelourKits.roles[1])
        for (type in listOf(BoardType.VELOUR, BoardType.VELOUR_BLOOM)) {
            assertSame(source, BoardRecipe(type, 0f).process(source))
            val recipe = BoardRecipe(type)
            val decoded = BoardRecipe.fromJsonText(recipe.toJsonText())
            assertEquals(recipe, decoded)
            val expected = recipe.process(source)
            Velour.clearCache()
            assertContentEquals(expected.samples, decoded.process(source).samples)
            assertEquals(source.frameCount + 138915, expected.frameCount)
            assertTrue(expected.samples.all { it.isFinite() && abs(it) <= .951f })
            val half = BoardRecipe(type, .5f).process(source)
            assertTrue(relativeError(half.samples, expected.samples) > .01)
        }
    }

    @Test fun `silence stays silent and invalid input is refused before allocation`() {
        val silence = Snip(FloatArray(128), 1, 44100)
        for (t in Velour.Treatment.entries) assertEquals(0f, Velour.process(silence, treatment = t).peak())
        assertFailsWith<IllegalArgumentException> { Velour.process(silence, Float.NaN) }
        assertFailsWith<IllegalArgumentException> { Velour.process(silence.copy(sampleRate = 48000)) }
        assertFailsWith<IllegalArgumentException> { Velour.process(Snip(floatArrayOf(Float.NaN), 1, 44100)) }
        assertFailsWith<IllegalArgumentException> { Velour.process(Snip(FloatArray(44100 * 16), 1, 44100)) }
        assertFailsWith<IllegalArgumentException> { Velour.renderBus(FloatArray(10), FloatArray(9), doubleArrayOf()) }
        assertFailsWith<IllegalArgumentException> { Velour.renderBus(FloatArray(10), FloatArray(10), doubleArrayOf(Double.NaN)) }
    }

    @Test fun `fixed kits preserve roles touch zones and honest source provenance`() {
        for (t in Velour.Treatment.entries) {
            val pads = VelourKits.build(t)
            assertEquals(16, pads.size)
            assertEquals(com.snipsnap.audio.DrumClass.KICK, pads[0].drumClass)
            assertEquals(com.snipsnap.audio.DrumClass.SNARE, pads[1].drumClass)
            for (pad in pads) {
                assertEquals(2, pad.softVariants.size)
                assertNull(pad.recipe, "Printed samples must not carry a recipe which processes them twice")
                assertEquals("velour", pad.source["factory"])
                for (s in pad.softVariants + pad.snip) {
                    assertTrue(s.samples.all { it.isFinite() && abs(it) <= .951f })
                    assertTrue(abs(s.samples.average()) < .005, "${pad.displayName} DC ${s.samples.average()}")
                }
            }
            for (i in pads.indices) {
                fun energy(s: Snip) = s.samples.sumOf { it.toDouble().pow(2) }
                assertTrue(energy(pads[i].softVariants[0]) < energy(pads[i].snip))
            }
        }
    }
}
