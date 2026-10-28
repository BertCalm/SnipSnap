package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import com.snipsnap.json.Json
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
import kotlin.test.assertTrue

/** The playable instrument keeps the heard source; controls change the return, not its exciter. */
class NacreTest {
    private val base = Nacre.defaults()
    private fun energy(x: FloatArray, from: Int = 0): Double = (from until x.size).sumOf { x[it].toDouble() * x[it] }
    private fun rms(x: FloatArray): Double = sqrt(energy(x) / x.size)
    private fun distance(a: FloatArray, b: FloatArray): Double = sqrt(a.indices.sumOf {
        (a[it].toDouble() - b[it]).let { d -> d * d }
    } / a.size) / rms(a).coerceAtLeast(1e-20)

    @Test
    fun `heard endpoint samples and returns survive the production port`() {
        val reference = Json.parse(checkNotNull(javaClass.getResourceAsStream("/nacre-presence-reference.json"))
            .bufferedReader().use { it.readText() }).obj()
        for (value in reference.getValue("endpoints").arr()) {
            val endpoint = value.obj()
            val macros = endpoint.getValue("macros").obj().mapValues { it.value.num().toFloat() }
            val played = Nacre.inspect(macros = macros)
            assertEquals(endpoint.getValue("prelevelSamplesSha256").str(), hash(played.raw), "${endpoint.getValue("id").str()} changed")
            assertEquals(endpoint.getValue("returnSamplesSha256").str(), hash(played.stems.getValue("venue")))
            assertEquals(1.0, played.diagnostics.getValue("peakSafetyGain"))
            assertEquals(Nacre.REFERENCE_OUTPUT_GAIN, played.diagnostics.getValue("effectiveOutputGain"))
        }
    }

    @Test
    fun `acoustic controls preserve the preferred direct instrument and event schedule`() {
        val legacy = NacreModel1.inspect()
        for (macro in listOf("VENUE", "CURRENT", "BLOOM")) for (value in listOf(0f, .5f, 1f)) {
            val p = Nacre.inspect(macros = base + (macro to value))
            assertContentEquals(legacy.stems.getValue("bass"), p.stems.getValue("bass"))
            assertContentEquals(legacy.stems.getValue("upper"), p.stems.getValue("upper"))
            assertEquals(legacy.events.map { it.timeSeconds }, p.events.map { it.timeSeconds })
            assertEquals(legacy.events.map { it.frequencyHz }, p.events.map { it.frequencyHz })
            if (macro != "BLOOM") assertContentEquals(legacy.stems.getValue("sympathetic"), p.stems.getValue("sympathetic"))
        }
    }

    @Test
    fun `venue morph is bounded and seaweed reveals a later response`() {
        val coral = Nacre.inspect(macros = base + ("VENUE" to 0f))
        val weed = Nacre.inspect(macros = base + ("VENUE" to 1f))
        val middle = Nacre.inspect(macros = base + ("VENUE" to .5f))
        val a = coral.stems.getValue("venue"); val b = weed.stems.getValue("venue"); val m = middle.stems.getValue("venue")
        val maximumError = m.indices.maxOf { abs(m[it].toDouble() - .5 * a[it] - .5 * b[it]) }
        assertTrue(maximumError < 3e-8, "morph doubled or changed the paths: $maximumError")
        assertTrue(rms(m) <= maxOf(rms(a), rms(b)) + 1e-8)
        val lateCoral = energy(a, (.8 * 44_100).toInt()) / energy(a)
        val lateWeed = energy(b, (.8 * 44_100).toInt()) / energy(b)
        assertTrue(lateWeed > lateCoral * 3.0 && lateWeed > .08, "late response is masked again: $lateCoral/$lateWeed")
        assertTrue(coral.diagnostics.getValue("energy99Seconds") < 1.6)
        assertTrue(weed.diagnostics.getValue("energy99Seconds") < 1.6)
    }

    @Test
    fun `still return is continuous and current changes transmission rather than the source`() {
        val still = Nacre.inspect(macros = base + mapOf("VENUE" to 1f, "CURRENT" to 0f))
        val tiny = Nacre.inspect(macros = base + mapOf("VENUE" to 1f, "CURRENT" to .000001f))
        val moving = Nacre.inspect(macros = base + mapOf("VENUE" to 1f, "CURRENT" to .7f))
        assertEquals(0.0, still.diagnostics.getValue("currentDepth"))
        assertEquals(0.0, still.diagnostics.getValue("currentRateHz"))
        assertEquals(.20f.toDouble(), still.diagnostics.getValue("coreCurrentMacro"))
        assertContentEquals(still.stems.getValue("bass"), moving.stems.getValue("bass"))
        assertContentEquals(still.stems.getValue("upper"), moving.stems.getValue("upper"))
        assertTrue(distance(still.stems.getValue("venue"), tiny.stems.getValue("venue")) < .0001,
            "zero CURRENT has an aperture discontinuity")
        val a = still.stems.getValue("venue"); val b = moving.stems.getValue("venue")
        val similarity = a.indices.sumOf { a[it].toDouble() * b[it] } / sqrt(energy(a) * energy(b))
        assertTrue(similarity < .98, "CURRENT only changes gain: normalized similarity=$similarity")
    }

    @Test
    fun `bloom scales sympathetic excitation and returns without changing their decay`() {
        val low = Nacre.inspect(macros = base + ("BLOOM" to 0f))
        val anchor = Nacre.inspect()
        val high = Nacre.inspect(macros = base + ("BLOOM" to 1f))
        for (stem in listOf("bass", "upper")) {
            assertContentEquals(low.stems.getValue(stem), anchor.stems.getValue(stem))
            assertContentEquals(low.stems.getValue(stem), high.stems.getValue(stem))
        }
        for (stem in listOf("sympathetic", "venue")) {
            val lo = rms(low.stems.getValue(stem)); val mid = rms(anchor.stems.getValue(stem)); val hi = rms(high.stems.getValue(stem))
            assertTrue(lo < mid && mid < hi, "$stem lost BLOOM authority: $lo/$mid/$hi")
        }
        assertEquals(Nacre.REFERENCE_RETURN_GAIN, anchor.diagnostics.getValue("returnGain"))
        assertEquals(1.0, anchor.diagnostics.getValue("sympatheticGain"))
        assertTrue(high.diagnostics.getValue("energy99Seconds") < 1.6, "BLOOM rebuilt the sustained bed")
    }

    @Test
    fun `register velocity and acoustic extremes stay finite pitched and naturally dynamic`() {
        val forbidden = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN, DrumClass.TOM)
        for (midi in listOf(28, 36, 48, 60, 72)) for (velocity in listOf(.3f, 1f)) {
            val p = Nacre.inspect(midi = midi, velocity = velocity, macros = base + ("VENUE" to 1f))
            assertTrue(p.raw.all { it.isFinite() })
            assertTrue(p.snip.samples.all { it.isFinite() && abs(it) <= .950001f })
            assertTrue(abs(p.raw.sumOf { it.toDouble() } / p.raw.size) < .005)
            assertTrue(p.diagnostics.values.all { it.isFinite() })
            val pitch = requireNotNull(Pitch.detect(p.snip, fromSec = .45f, windowSec = .4f)) { "no pitch at MIDI $midi velocity $velocity" }
            val cents = 1200.0 * ln(pitch.hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
            assertTrue(abs(cents) < 15.0 && pitch.confidence >= .3f, "MIDI $midi velocity $velocity: $cents cents confidence=${pitch.confidence}")
            val onset = Snip(p.snip.samples.copyOfRange(0, (44_100 * .75).toInt()), 1, 44_100)
            assertTrue(Classifier.classify(onset).drumClass !in forbidden, "MIDI $midi velocity $velocity sounds percussive to the onset classifier")
        }
        val soft = Nacre.render(velocity = .3f)
        val hard = Nacre.render(velocity = 1f)
        assertTrue(rms(soft.samples) < rms(hard.samples) * .65, "per-patch leveling erased velocity")
        for (venue in listOf(0f, 1f)) {
            val extreme = Nacre.inspect(macros = base + mapOf("TOUCH" to 1f, "DUET" to 1f, "CURRENT" to 1f, "BLOOM" to 1f, "VENUE" to venue))
            assertTrue(extreme.raw.all { it.isFinite() })
            assertTrue(extreme.snip.samples.maxOf { abs(it) } <= .950001f)
        }
    }

    @Test
    fun `zero velocity and unsupported hold and timing lock are explicit`() {
        val zero = Nacre.inspect(velocity = 0f)
        assertTrue(zero.raw.all { it == 0f })
        assertTrue(zero.snip.samples.all { it == 0f })
        val hold = assertFailsWith<IllegalArgumentException> { Nacre.render(macros = base + ("HOLD" to .1f)) }
        assertTrue(hold.message.orEmpty().contains("finite"))
        val lock = assertFailsWith<IllegalArgumentException> {
            Nacre.inspect(macros = base + ("TOUCH" to .7f), options = Nacre.Options(lockStrumTiming = true))
        }
        assertTrue(lock.message.orEmpty().contains("TOUCH .30"))
    }

    private fun hash(samples: FloatArray): String {
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) bytes.putInt(sample.toRawBits())
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
