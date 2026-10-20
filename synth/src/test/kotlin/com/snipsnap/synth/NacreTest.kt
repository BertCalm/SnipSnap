package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Behavioral checks for the finite listening model, before factory/preset acceptance. */
class NacreTest {
    private val base = Nacre.defaults(NacreVoice.PEARL)

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double =
        sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from).coerceAtLeast(1))

    private fun distance(a: FloatArray, b: FloatArray): Double =
        sqrt(a.indices.sumOf { (a[it].toDouble() - b[it]).let { d -> d * d } } / a.size) /
            rms(a).coerceAtLeast(1e-12)

    @Test
    fun `root seed and macro map ordering reproduce every event and sample`() {
        val a = Nacre.inspect(macros = base, seed = Long.MIN_VALUE)
        val b = Nacre.inspect(macros = base.entries.reversed().associate { it.key to it.value }, seed = Long.MIN_VALUE)
        assertContentEquals(a.raw, b.raw)
        assertContentEquals(a.snip.samples, b.snip.samples)
        assertEquals(a.events, b.events)
        for (stem in a.stems.keys) assertContentEquals(a.stems.getValue(stem), b.stems.getValue(stem))
        assertTrue(distance(a.raw, Nacre.inspect(seed = Long.MAX_VALUE).raw) > .01, "seed has no effect")
    }

    @Test
    fun `each complete gesture has four strings per performer with corresponding octave roots`() {
        for (duet in listOf(0f, .5f, 1f)) {
            val events = Nacre.inspect(macros = base + ("DUET" to duet)).events
            assertEquals(8, events.size)
            assertEquals(events.sortedBy { it.timeSeconds }, events)
            assertTrue(events.all { it.timeSeconds >= 0 && it.timeSeconds < 1.0 && it.strength > 0 })
            val bass = events.filter { it.performer == "bass" }.associateBy { it.stringId }
            val upper = events.filter { it.performer == "upper" }.associateBy { it.stringId }
            assertEquals(setOf(0, 1, 2, 3), bass.keys)
            assertEquals(bass.keys, upper.keys)
            for (id in bass.keys) assertEquals(2.0, upper.getValue(id).frequencyHz / bass.getValue(id).frequencyHz, 1e-5)
            if (duet == 1f) assertTrue(events.filter { it.performer == "bass" }.map { it.stringId } !=
                events.filter { it.performer == "upper" }.map { it.stringId }, "crossing directions are identical")
        }
    }

    @Test
    fun `lossy finite networks remain finite and decay at register and macro corners`() {
        val cases = listOf(28 to base, 48 to base.mapValues { if (it.key == "HOLD") 0f else 1f },
            72 to (base + mapOf("TOUCH" to 1f, "CURRENT" to 1f, "VENUE" to 1f, "BLOOM" to 1f)))
        for ((midi, macros) in cases) {
            val probe = Nacre.inspect(macros = macros, midi = midi)
            assertEquals(44_100, probe.snip.sampleRate)
            assertEquals(1, probe.snip.channels)
            assertTrue(probe.raw.size in 2 * 44_100..8 * 44_100)
            assertTrue(probe.raw.all { it.isFinite() })
            assertTrue(probe.snip.samples.all { it.isFinite() && abs(it) <= 1.00001f })
            assertTrue(probe.raw.maxOf { abs(it) } < 4f, "uncontrolled raw peak at MIDI $midi")
            assertTrue(abs(probe.raw.sumOf { it.toDouble() } / probe.raw.size) < .005, "DC buildup")
            for ((name, stem) in probe.stems) {
                assertTrue(stem.all { it.isFinite() }, "$name states diverged")
                assertTrue(rms(stem, stem.size - 44_100) < rms(stem, 0, 44_100) * .2 + 1e-9,
                    "$name did not decay at MIDI $midi")
            }
        }
    }

    @Test
    fun `each timbral control changes low and middle settings after leveling`() {
        for (macro in listOf("TOUCH", "DUET", "CURRENT", "VENUE", "BLOOM")) {
            val low = Nacre.render(macros = base + (macro to 0f)).samples
            val quarter = Nacre.render(macros = base + (macro to .25f)).samples
            val middle = Nacre.render(macros = base + (macro to .5f)).samples
            assertTrue(distance(low, quarter) > .002, "$macro has no low-range authority")
            assertTrue(distance(quarter, middle) > .002, "$macro has no middle-range authority")
        }
    }

    @Test
    fun `cross harp excitation reaches unplucked resonators without changing direct strings`() {
        val macros = base + ("BLOOM" to .85f)
        val on = Nacre.inspect(macros = macros, options = Nacre.Options(muteUpperPlucks = true))
        val off = Nacre.inspect(macros = macros, options = Nacre.Options(muteUpperPlucks = true, disableCrossHarp = true))
        assertContentEquals(on.stems.getValue("bass"), off.stems.getValue("bass"))
        assertContentEquals(on.stems.getValue("upper"), off.stems.getValue("upper"))
        assertTrue(distance(on.stems.getValue("sympathetic"), off.stems.getValue("sympathetic")) > .01)
        assertTrue(rms(on.stems.getValue("sympathetic")) > 1e-7)
    }

    @Test
    fun `current changes active string states even with venue bypassed`() {
        val moving = base + ("CURRENT" to .7f)
        val internal = Nacre.inspect(macros = moving, options = Nacre.Options(bypassVenue = true))
        val post = Nacre.inspect(macros = moving, options = Nacre.Options(bypassVenue = true, postCurrentOnly = true))
        assertTrue(distance(internal.stems.getValue("bass"), post.stems.getValue("bass")) > .005,
            "current only changes final pickup")
    }

    @Test
    fun `moderate gestures retain the requested fundamental and avoid drum families`() {
        val forbidden = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED,
            DrumClass.HAT_OPEN, DrumClass.TOM)
        for (midi in listOf(36, 48, 60, 72)) {
            val snip = Nacre.render(midi = midi)
            val pitch = requireNotNull(Pitch.detect(snip, fromSec = .45f, windowSec = .4f)) { "no pitch at MIDI $midi" }
            val cents = 1200.0 * ln(pitch.hz / Keys.midiHz(midi).toDouble()) / ln(2.0)
            assertTrue(abs(cents) < 15.0, "MIDI $midi: $cents cents (including octave ambiguity)")
            assertTrue(Classifier.classify(snip).drumClass !in forbidden, "MIDI $midi classified as a drum")
        }
    }

    @Test
    fun `separated crossing gestures begin as pitched plucks without a noisy drum attack`() {
        val forbidden = setOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.CLAP, DrumClass.HAT_CLOSED,
            DrumClass.HAT_OPEN, DrumClass.TOM)
        for (duet in listOf(.85f, 1f)) for (touch in listOf(.3f, .7f)) {
            val snip = Nacre.render(macros = base + mapOf("DUET" to duet, "TOUCH" to touch))
            val onset = Snip(snip.samples.copyOfRange(0, (44_100 * .75).toInt()), 1, 44_100)
            val classified = Classifier.classify(onset)
            assertTrue(classified.drumClass !in forbidden,
                "DUET $duet TOUCH $touch onset: ${classified.drumClass}, flatness ${classified.features.flatness}")
        }
    }

    @Test
    fun `zero velocity injects no energy and unsupported HOLD is refused`() {
        val silent = Nacre.inspect(velocity = 0f)
        assertTrue(silent.raw.all { it == 0f })
        assertTrue(silent.snip.samples.all { it == 0f })
        assertTrue(silent.stems.values.all { x -> x.all { it == 0f } })
        assertFailsWith<IllegalArgumentException> { Nacre.render(macros = base + ("HOLD" to 1f)) }
    }
}
