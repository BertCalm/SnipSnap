package com.snipsnap.synth

import com.snipsnap.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Authored for the isolated profile; not a voice, bank-policy, listening, or device gate. */
class NacreContactStudyTest {
    private fun hash(x: FloatArray): String {
        val bytes = ByteBuffer.allocate(x.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in x) bytes.putInt(v.toRawBits())
        return MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Test
    fun `zero contact loss keeps independent accepted endpoint Float witnesses`() {
        val reference = Json.parse(checkNotNull(javaClass.getResourceAsStream("/nacre-presence-reference.json"))
            .bufferedReader().use { it.readText() }).obj()
        for (value in reference.getValue("endpoints").arr()) {
            val endpoint = value.obj()
            val macros = endpoint.getValue("macros").obj().mapValues { it.value.num().toFloat() }
            val p = NacreContactStudy.inspect(NacreContactStudy.Profile(0f), macros = macros)
            assertEquals(endpoint.getValue("prelevelSamplesSha256").str(), hash(p.raw))
            assertEquals(endpoint.getValue("returnSamplesSha256").str(), hash(p.stems.getValue("venue")))
            assertEquals(1.0, p.diagnostics.getValue("peakSafetyGain"))
        }
        assertEquals(3, Nacre.MODEL_VERSION)
        assertEquals(false, NacreModels.supports(5))
    }

    @Test
    fun `passive contact changes direct decay without rescheduling or final gain matching`() {
        val ordinary = Nacre.inspect()
        val damped = NacreContactStudy.inspect(NacreContactStudy.Profile(.8f))
        assertEquals(ordinary.events, damped.events)
        assertEquals(ordinary.snip.frameCount, damped.snip.frameCount)
        assertTrue(damped.snip.samples.all { it.isFinite() && abs(it) <= .950001f })
        assertEquals(Nacre.REFERENCE_OUTPUT_GAIN, damped.diagnostics.getValue("referenceOutputGain"))
        assertEquals(0.0, damped.diagnostics.getValue("contactStudyRegisteredOrSaved"))
        fun direct(p: Nacre.Probe) = FloatArray(p.raw.size) {
            p.stems.getValue("bass")[it] + p.stems.getValue("upper")[it]
        }
        fun energy(x: FloatArray, from: Double, to: Double): Double =
            ((from * Dsp.RATE).toInt() until (to * Dsp.RATE).toInt()).sumOf { x[it].toDouble() * x[it] }
        val a = direct(ordinary); val b = direct(damped)
        val earlyA = energy(a, .05, .25); val earlyB = energy(b, .05, .25)
        assertTrue(earlyA > 0.0 && earlyB > 0.0, "silence cannot demonstrate articulation")
        // A scalar final-gain change leaves this ratio invariant. This guard fails in that case.
        assertTrue(energy(b, .8, 2.0) / earlyB < .5 * energy(a, .8, 2.0) / earlyA,
            "contact loading must shorten the actual direct-string tail, not just lower its gain")
    }

    @Test
    fun `contact profiles own separate cached cores while return edits reuse them`() {
        Nacre.clearCoreCache()
        val ordinary = Nacre.inspect()
        val original = hash(ordinary.raw)
        val profile = NacreContactStudy.Profile(.5f)
        val first = NacreContactStudy.inspect(profile)
        val direct = first.stems.getValue("bass").copyOf()
        assertEquals(2L, Nacre.coreCacheStats().builds)
        first.stems.getValue("bass").fill(99f)
        val changedReturns = NacreContactStudy.inspect(profile,
            macros = Nacre.defaults() + mapOf("VENUE" to 1f, "BLOOM" to .85f))
        assertEquals(2L, Nacre.coreCacheStats().builds)
        assertContentEquals(direct, changedReturns.stems.getValue("bass"))
        assertEquals(original, hash(Nacre.inspect().raw), "study core replaced the accepted cache key")
    }

    @Test
    fun `unsupported profiles invalid loss and cancellation are explicit`() {
        for (loss in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, 1.1f)) {
            assertFailsWith<IllegalArgumentException> { NacreContactStudy.Profile(loss) }
        }
        assertFailsWith<IllegalArgumentException> { NacreContactStudy.Profile(.5f, version = 2) }
        assertFailsWith<IllegalArgumentException> {
            NacreContactStudy.inspect(NacreContactStudy.Profile(.5f), macros = mapOf("HOLD" to 1f))
        }
        Thread.currentThread().interrupt()
        try {
            assertFailsWith<CancellationException> { NacreContactStudy.inspect(NacreContactStudy.Profile(.5f)) }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
}
