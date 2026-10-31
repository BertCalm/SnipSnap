package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Loudness
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CausticInstrumentTest {
    private val voice = CausticVoice.ALLOY

    @Test
    fun `native contract has finite controls explicit note and refused HOLD`() {
        assertEquals(1, CausticInstrument.MODEL_VERSION)
        assertEquals(mapOf("SOURCE" to .5f, "FOCUS" to .19f, "BODY" to 1f, "LENGTH" to 1f, "HOLD" to 0f),
            CausticInstrument.defaults(voice))
        assertTrue(CausticInstrument.supportsModel(1))
        assertFalse(CausticInstrument.supportsModel(2))
        val settled = CausticInstrument.settled(voice, mapOf("SOURCE" to Float.NaN, "BODY" to 8f,
            "FOCUS" to -1f, "unknown" to .3f))
        assertEquals(.5f, settled.getValue("SOURCE"))
        assertEquals(1f, settled.getValue("BODY"))
        assertEquals(0f, settled.getValue("FOCUS"))
        assertFalse("unknown" in settled)
        assertFailsWith<IllegalArgumentException> { CausticInstrument.render(voice, mapOf("HOLD" to .01f)) }
        assertFailsWith<IllegalArgumentException> { CausticInstrument.drumClassFor(voice, mapOf("HOLD" to 1f)) }
        assertFailsWith<IllegalArgumentException> { CausticInstrument.render(voice, midi = 35) }
        assertFailsWith<IllegalArgumentException> { CausticInstrument.render(voice, model = 4) }
        val a = CausticInstrument.scramble(voice, Random(701))
        assertEquals(a, CausticInstrument.scramble(voice, Random(701)))
        assertTrue(a.values.all { it.isFinite() && it in 0f..1f })
        assertEquals(0f, a.getValue("HOLD"))
    }

    @Test
    fun `raw endpoints preserve heard Alloy and Fuller body before native house gain`() {
        for ((body, legacyModel) in listOf(0f to 2, 1f to 4)) {
            val native = CausticInstrument.probe(voice, mapOf("BODY" to body), normalize = false, seconds = .8)
            val old = Caustic.inspect(Caustic.Config(source = .5, focus = .19, model = legacyModel,
                character = Caustic.Character.ALLOY, seconds = .8))
            val difference = relativeDifference(old.raw, native.raw)
            assertTrue(difference < 2e-5, "BODY$body changed legacy$legacyModel raw endpoint: $difference")
            assertTrue(native.diagnostics.internalPeak < 1)
        }
    }

    @Test
    fun `LENGTH changes actual source and body release and class follows frame duration`() {
        val short = CausticInstrument.probe(voice, mapOf("LENGTH" to 0f), normalize = false)
        val longPrefix = CausticInstrument.probe(voice, normalize = false, seconds = .5)
        assertEquals(CausticInstrument.RATE / 2, short.samples.size)
        assertEquals(.5, short.diagnostics.durationSeconds)
        assertEquals(8.0, longPrefix.diagnostics.durationSeconds)
        assertEquals(DrumClass.TONAL, CausticInstrument.drumClassFor(voice, mapOf("LENGTH" to 0f)))
        assertEquals(DrumClass.LOOP, CausticInstrument.drumClassFor(voice))
        assertTrue(relativeDifference(longPrefix.internalRaw, short.internalRaw) > .25,
            "LENGTH only truncated the unchanged source/body")
        fun endRms(x: FloatArray): Double = rms(x, (.3 * CausticInstrument.RATE).toInt(), (.45 * CausticInstrument.RATE).toInt())
        assertTrue(endRms(short.raw) < endRms(longPrefix.raw) * .4, "short release did not shorten the actual body")
    }

    @Test
    fun `normal rendering is deterministic silent at zero and retains velocity after house level`() {
        val macros = mapOf("LENGTH" to .05f)
        val a = CausticInstrument.render(voice, macros)
        assertContentEquals(a.samples, CausticInstrument.render(voice, macros).samples)
        assertContentEquals(a.samples, CausticInstrument.probe(voice, macros).samples)
        assertTrue(a.samples.all { it.isFinite() && abs(it) <= .991f })
        val soft = CausticInstrument.render(voice, macros, velocity = .25f)
        val hard = CausticInstrument.render(voice, macros, velocity = 1f)
        assertTrue(Loudness.of(hard.samples, 1, hard.sampleRate) > Loudness.of(soft.samples, 1, soft.sampleRate) * 1.4)
        assertTrue(CausticInstrument.render(voice, macros, velocity = 0f).samples.all { it == 0f })
        assertTrue(abs(a.samples.sumOf { it.toDouble() } / a.samples.size) < .005)
    }

    @Test
    fun `macro-only geometry cache is bounded and shared across pitch body and velocity`() {
        CausticInstrument.clearGeometryCache()
        val first = CausticInstrument.probe(voice, seconds = .01, normalize = false)
        val second = CausticInstrument.probe(voice, mapOf("BODY" to 0f), midi = 72, velocity = .2f,
            seconds = .01, normalize = false)
        assertFalse(first.diagnostics.geometryCacheHit)
        assertTrue(second.diagnostics.geometryCacheHit)
        for (i in 0..34) CausticInstrument.probe(voice, mapOf("FOCUS" to i / 35f), seconds = .005, normalize = false)
        assertTrue(CausticInstrument.geometryCacheSize() <= 32)
    }

    @Test
    fun `native long render honors cancellation before expensive work`() {
        Thread.currentThread().interrupt()
        try { assertFailsWith<CancellationException> { CausticInstrument.render(voice) } }
        finally { Thread.interrupted() }
    }

    private fun relativeDifference(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size)
        return sqrt(a.indices.sumOf { val d = a[it] - b[it].toDouble(); d * d } /
            a.sumOf { it.toDouble() * it }.coerceAtLeast(1e-30))
    }
    private fun rms(x: FloatArray, from: Int, to: Int): Double =
        sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))
}
