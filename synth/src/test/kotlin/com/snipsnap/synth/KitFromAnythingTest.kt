package com.snipsnap.synth

import com.snipsnap.audio.AutoPlace
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.audio.Snip
import com.snipsnap.synth.KitFromAnything.Gate
import com.snipsnap.synth.KitFromAnything.Genre
import com.snipsnap.synth.KitFromAnything.Slot
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gates the design brief sets, run on three deterministic stand-in
 * sources: a plucked note (pitched), a synthesized snare (noise over a
 * body) and a two-formant noise burst (a "bark"). Real captures are the
 * brief's R0 fixture and are still owed; these stand-ins are the same
 * three shapes the Python prototype was measured on, so the assertions
 * here are the agreements that prototype reached, re-earned in Kotlin
 * against the real classifier.
 */
class KitFromAnythingTest {

    private val key = KeySpec(9, Scale.MINOR)
    private val rate = KitFromAnything.RATE

    private val pluck: Snip by lazy { Presets.forVoice("PLUCK", "NYLON").first().render() }
    private val snare: Snip by lazy { Thump.render(ThumpVoice.SNARE) }
    private val bark: Snip by lazy {
        val n = (0.3f * rate).toInt()
        val noise = Dsp.Noise(Dsp.seedFor("bark"))
        val f1 = Dsp.Biquad().apply { bandpass(900f, 6f, rate) }
        val f2 = Dsp.Biquad().apply { bandpass(2200f, 8f, rate) }
        val out = FloatArray(n)
        val env = (0.12f * rate).toInt()
        for (i in 0 until n) {
            val x = noise.next()
            val v = f1.process(x) + 0.5f * f2.process(x)
            out[i] = if (i < env) v * (0.5 - 0.5 * cos(2.0 * PI * i / env)).toFloat() else 0f
        }
        Dsp.normalize(out, 0.9f)
        Snip(out, 1, rate)
    }

    /** A pure sine sweep: a kick with no noise in it, the source with nothing to give for a snare or a clap. */
    private val sweep: Snip by lazy {
        val n = (0.4f * rate).toInt()
        var phase = 0.0
        val out = FloatArray(n) { i ->
            val t = i.toFloat() / rate
            val hz = 45f + 105f * exp(-t / 0.03f)
            phase += hz / rate
            (sin(2.0 * PI * phase) * exp(-t / 0.12f)).toFloat()
        }
        Snip(out, 1, rate)
    }

    @Test
    fun `sixteen pads, every one under the LOOP line, finite, tagged and choked as the slot says`() {
        val r = KitFromAnything.assemble(pluck, key, Genre.HOUSE)
        assertEquals(16, r.pads.size)
        for ((i, slot) in Slot.entries.withIndex()) {
            val pad = assertNotNull(r.pads[i], slot.name)
            assertEquals(slot.pad, i + 1)
            assertEquals(slot.tag, pad.drumClass, slot.name)
            assertEquals(slot.muteGroup, pad.muteGroup, "${slot.name} choke group")
            assertTrue(pad.snip.durationSeconds < 1.5f, "${slot.name} is ${pad.snip.durationSeconds} s, over the LOOP line")
            assertTrue(pad.snip.samples.all { it.isFinite() }, "${slot.name} not finite")
            assertTrue(pad.snip.peak() in 0.5f..1f, "${slot.name} peak ${pad.snip.peak()}")
            assertEquals("purist", pad.source["made"])
            assertEquals(slot.name, pad.source["slot"])
        }
        assertEquals(AutoPlace.HAT_MUTE_GROUP, r.pads[2]!!.muteGroup)
        assertEquals(AutoPlace.HAT_MUTE_GROUP, r.pads[3]!!.muteGroup)
        assertEquals(0, r.pads[5]!!.muteGroup, "the ride is never choked by the closed hat")
        assertEquals(0, r.pads[6]!!.muteGroup, "the crash is never choked by the closed hat")
    }

    @Test
    fun `same source, same bytes`() {
        val a = KitFromAnything.assemble(bark, key, Genre.TECHNO)
        val b = KitFromAnything.assemble(bark, key, Genre.TECHNO)
        for (i in 0 until 16) assertContentEquals(a.pads[i]!!.snip.samples, b.pads[i]!!.snip.samples, Slot.entries[i].name)
    }

    @Test
    fun `the classifier files the five judged slots as intended on a pluck and on a snare, every genre`() {
        for (genre in Genre.entries) {
            for ((label, src) in listOf("pluck" to pluck, "snare" to snare)) {
                val r = KitFromAnything.assemble(src, key, genre)
                for (g in r.gates.filter { it.slot.judged }) {
                    assertEquals(Gate.PASS, g.gate, "$label / ${genre.name} / ${g.slot.name}: ${g.detail}")
                }
            }
        }
    }

    @Test
    fun `the classifier files the kick and both hats as intended on a formant burst, every genre`() {
        for (genre in Genre.entries) {
            val r = KitFromAnything.assemble(bark, key, genre)
            for (g in r.gates.filter { it.slot in setOf(Slot.KICK, Slot.HAT_CLOSED, Slot.HAT_OPEN, Slot.SNARE) }) {
                assertEquals(Gate.PASS, g.gate, "bark / ${genre.name} / ${g.slot.name}: ${g.detail}")
            }
        }
    }

    @Test
    fun `ride and crash clear their gates on every source and genre`() {
        for (genre in Genre.entries) {
            for ((label, src) in listOf("pluck" to pluck, "snare" to snare, "bark" to bark, "sweep" to sweep)) {
                val r = KitFromAnything.assemble(src, key, genre)
                for (g in r.gates.filter { it.slot == Slot.RIDE || it.slot == Slot.CRASH }) {
                    assertEquals(Gate.PASS, g.gate, "$label / ${genre.name} / ${g.slot.name}: ${g.detail}")
                }
            }
        }
    }

    @Test
    fun `bass and tones land on the key's notes on a pitched source`() {
        val r = KitFromAnything.assemble(pluck, key, Genre.HOUSE)
        for (g in r.gates.filter { it.slot.isBass || it.slot.isTone }) {
            assertEquals(Gate.PASS, g.gate, "${g.slot.name}: ${g.detail}")
        }
        assertEquals("A1", r.pads[8]!!.source["rootNote"])
        assertEquals("C2", r.pads[9]!!.source["rootNote"], "the minor third of A")
        assertEquals("E2", r.pads[10]!!.source["rootNote"])
        assertEquals("A2", r.pads[11]!!.source["rootNote"])
        assertEquals("A3", r.pads[12]!!.source["rootNote"])
    }

    @Test
    fun `the siren follows its trajectory on a pitched source`() {
        for (genre in Genre.entries) {
            val g = KitFromAnything.assemble(pluck, key, genre).gates.first { it.slot == Slot.SIREN }
            assertEquals(Gate.PASS, g.gate, "${genre.name}: ${g.detail}")
        }
    }

    @Test
    fun `a source with nothing to give keeps its pads and names the gate it failed`() {
        val r = KitFromAnything.assemble(sweep, key, Genre.BOOM_BAP)
        for ((i, pad) in r.pads.withIndex()) {
            assertNotNull(pad, Slot.entries[i].name)
            assertTrue(pad.snip.peak() > 0.5f, "${Slot.entries[i].name} went quiet instead of saying so")
        }
        assertTrue(r.fallbackSet.isNotEmpty(), "a pure sine has no noise for a clap; the gate should say so")
        for (g in r.fallbackSet) {
            val pad = r.pads[g.slot.pad - 1]!!
            assertEquals("fail", pad.source["gate"], g.slot.name)
            assertTrue(pad.source["gateDetail"]!!.isNotBlank())
        }
        assertEquals(Gate.PASS, r.gates.first { it.slot == Slot.KICK }.gate, "a sine sweep makes a kick: ${r.gates.first { it.slot == Slot.KICK }.detail}")
        assertTrue(r.gates.none { it.gate == Gate.UNMEASURED && it in r.fallbackSet }, "unmeasured is never counted as failed")
    }

    @Test
    fun `refusals come in words`() {
        val short = Snip(FloatArray((0.02f * rate).toInt()) { 0.5f }, 1, rate)
        val e1 = assertFailsWith<IllegalArgumentException> { KitFromAnything.assemble(short, key, Genre.HOUSE) }
        assertTrue(e1.message!!.contains("ms"), e1.message)
        val wrongRate = Snip(FloatArray(48_000) { 0.1f }, 1, 48_000)
        val e2 = assertFailsWith<IllegalArgumentException> { KitFromAnything.assemble(wrongRate, key, Genre.HOUSE) }
        assertTrue(e2.message!!.contains("44100"), e2.message)
        val silent = Snip(FloatArray(rate), 1, rate)
        assertFailsWith<IllegalArgumentException> { KitFromAnything.assemble(silent, key, Genre.HOUSE) }
    }

    @Test
    fun `genres read by their plain names`() {
        assertEquals(Genre.BOOM_BAP, Genre.parse("boom bap"))
        assertEquals(Genre.BOOM_BAP, Genre.parse("Boom-Bap"))
        assertEquals(Genre.BOOM_BAP, Genre.parse("BOOM_BAP"))
        assertEquals(Genre.HOUSE, Genre.parse(" house "))
        assertEquals(Genre.TECHNO, Genre.parse("techno"))
        assertNull(Genre.parse("jungle"))
    }

    @Test
    fun `the forced cycle read as a wavetable is periodic at the note and alias-free`() {
        val a = KitFromAnything.analyse(bark)
        assertTrue(!a.pitched, "a formant burst is not a note to the classifier")
        val track = FloatArray(rate) { 261.63f }
        val x = KitFromAnything.cycleAt(a.cycle, track)
        val est = com.snipsnap.audio.Pitch.detect(Snip(x, 1, rate))
        assertNotNull(est)
        val cents = 1200.0 * kotlin.math.log2(est.hz / 261.63)
        assertTrue(kotlin.math.abs(cents) < 20, "read $cents cents off C4, confidence ${est.confidence}")
        assertTrue(est.confidence >= 0.3f, "confidence ${est.confidence}")
        // mip-mapped: the table for C4 keeps only harmonics under 20 kHz, so bins above that are empty
        val table = KitFromAnything.tableFor(a.cycle, 261.63f)
        val re = table.copyOf(); val im = FloatArray(table.size)
        com.snipsnap.audio.Fft.forward(re, im)
        val kmax = (20_000f / 261.63f).toInt()
        for (k in kmax + 1 until table.size / 2) {
            assertTrue(kotlin.math.hypot(re[k].toDouble(), im[k].toDouble()) < 1e-3, "harmonic $k survived the mip-map")
        }
    }
}
