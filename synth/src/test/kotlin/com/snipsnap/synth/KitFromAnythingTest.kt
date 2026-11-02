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
    fun `thirds are chosen by interval, not by scale index`() {
        val minorPent = KeySpec(9, Scale.MINOR_PENTATONIC)
        assertEquals(3, Slot.BASS_THIRD.degreeSemitones(minorPent), "a minor pentatonic's third is three semitones, not its third entry (the fourth)")
        assertEquals(4, Slot.TONE_THIRD.degreeSemitones(KeySpec(0, Scale.MAJOR_PENTATONIC)))
        assertEquals(4, Slot.BASS_THIRD.degreeSemitones(KeySpec(0, Scale.MAJOR)))
        assertEquals(3, Slot.BASS_THIRD.degreeSemitones(KeySpec(0, Scale.CHROMATIC)), "chromatic takes the minor third")
        assertEquals(7, Slot.BASS_FIFTH.degreeSemitones(minorPent))
        val r = KitFromAnything.assemble(pluck, minorPent, Genre.HOUSE)
        assertEquals("C2", r.pads[9]!!.source["rootNote"])
    }

    @Test
    fun `a low key's bass sits under the pitch detector's floor and still reads`() {
        val cMinor = KeySpec(0, Scale.MINOR)                       // BASS_ROOT is C1, 32.7 Hz
        val r = KitFromAnything.assemble(pluck, cMinor, Genre.BOOM_BAP)
        assertEquals("C1", r.pads[8]!!.source["rootNote"])
        for (g in r.gates.filter { it.slot.isBass }) assertEquals(Gate.PASS, g.gate, "${g.slot.name}: ${g.detail}")
    }

    @Test
    fun `a high source's climbing siren passes the detector's ceiling and still reads`() {
        val n = (0.6f * rate).toInt()
        val high = Snip(FloatArray(n) { i -> (sin(2.0 * PI * 880 * i / rate) * exp(-i / (0.25 * rate))).toFloat() }, 1, rate)
        val r = KitFromAnything.assemble(high, key, Genre.TECHNO)   // a climb of 12 semitones: 880 to 1760 Hz
        assertTrue(r.analysis.pitched, "an 880 Hz sine is a note")
        val g = r.gates.first { it.slot == Slot.SIREN }
        assertEquals(Gate.PASS, g.gate, g.detail)
    }

    @Test
    fun `every pad ends faded, never cut`() {
        val r = KitFromAnything.assemble(pluck, key, Genre.HOUSE)
        for ((i, pad) in r.pads.withIndex()) {
            val x = pad!!.snip.samples
            assertTrue(kotlin.math.abs(x[x.size - 1]) < 1e-3f, "${Slot.entries[i].name} ends at ${x[x.size - 1]}")
        }
    }

    @Test
    fun `a pad that fails its gate is blended at half with a partner that says its name`() {
        val r = KitFromAnything.assemble(sweep, key, Genre.BOOM_BAP)
        assertTrue(r.fallbackSet.isNotEmpty(), "a pure sine has no noise to quarry; something should fail")
        assertEquals(r.fallbackSet.map { it.slot }, r.blends.map { it.slot }, "every failed pad is blended, nothing else is")
        for (b in r.blends) {
            val pad = r.pads[b.slot.pad - 1]!!
            assertEquals(KitFromAnything.BLEND_SHARE, b.share, "${b.slot.name} share")
            assertTrue(b.share <= 0.5f, "the source is never less than half")
            assertEquals("blend", pad.source["made"], b.slot.name)
            assertEquals("0.50", pad.source["synthShare"], b.slot.name)
            assertTrue(pad.source["synth"]!!.isNotBlank(), "${b.slot.name} names its partner")
            assertEquals(b.synth, pad.source["synth"])
            assertEquals("fail", pad.source["gate"], "${b.slot.name} keeps the purist verdict on record")
            assertNotNull(pad.source["gateAfterBlend"], "${b.slot.name} says what the gate said afterwards")
            assertTrue(pad.snip.peak() > 0.5f && pad.snip.durationSeconds < 1.5f, "${b.slot.name} is a pad")
            if (b.slot.judged || b.slot == Slot.RIDE || b.slot == Slot.CRASH) {
                assertNotNull(b.distance, "${b.slot.name}: a drum partner reports its de-sample distance")
                assertTrue(b.synth.startsWith("THUMP ") || b.synth.startsWith("SKIN "), b.synth)
            }
        }
        for (g in r.gates.filter { it.gate == Gate.PASS }) {
            assertEquals("purist", r.pads[g.slot.pad - 1]!!.source["made"], "${g.slot.name} passed and stays purist")
        }
    }

    @Test
    fun `a blend never fails more gates than the purist did, and a hat's partner is a hat`() {
        val r = KitFromAnything.assemble(sweep, key, Genre.BOOM_BAP)
        val before = r.gates.count { it.gate == Gate.FAIL }
        val after = r.finalGates.count { it.gate == Gate.FAIL }
        assertTrue(after <= before, "blending failed more: $before -> $after")
        // The cap is the rule: with the sweep's own noise held at half, its blended hats still read
        // SNARE to the classifier (measured here), and the pad says so in gateAfterBlend rather than
        // the blend taking more than half to force the verdict. What the rule does promise is the
        // partner's family.
        for (b in r.blends.filter { it.slot == Slot.HAT_CLOSED || it.slot == Slot.HAT_OPEN }) {
            assertTrue(b.synth.contains("HAT"), "${b.slot.name}'s partner is ${b.synth}")
            assertEquals(b.after.gate.name.lowercase(), r.pads[b.slot.pad - 1]!!.source["gateAfterBlend"])
        }
    }

    @Test
    fun `blends are as deterministic as the rest`() {
        val a = KitFromAnything.assemble(sweep, key, Genre.BOOM_BAP)
        val b = KitFromAnything.assemble(sweep, key, Genre.BOOM_BAP)
        assertEquals(a.blends.map { it.synth to it.distance }, b.blends.map { it.synth to it.distance })
        for (i in 0 until 16) assertContentEquals(a.pads[i]!!.snip.samples, b.pads[i]!!.snip.samples, Slot.entries[i].name)
    }

    @Test
    fun `a note with no fundamental rings at its note once blended`() {
        // noise at a 180 Hz body: the Python prototype's snare stand-in, whose forced cycle sang its
        // tones off the note. Whether or not the purist gate fails here, a RING partner at the note
        // must land the root when it is asked for.
        val n = (0.25f * rate).toInt()
        val noise = Dsp.Noise(Dsp.seedFor("snare-ish"))
        val src = Snip(FloatArray(n) { i ->
            val t = i.toFloat() / rate
            (0.7f * noise.next() * exp(-t / 0.08f) + 0.6f * sin(2.0 * PI * 180 * t).toFloat() * exp(-t / 0.05f))
        }, 1, rate)
        val r = KitFromAnything.assemble(src, key, Genre.HOUSE)
        for (b in r.blends.filter { it.slot.isTone || it.slot.isBass }) {
            assertTrue(b.synth.startsWith("RING "), b.synth)
            assertEquals(Gate.PASS, b.after.gate, "${b.slot.name} after the RING blend: ${b.after.detail}")
        }
    }

    @Test
    fun `a recipe round-trips through JSON and names a wrong shape in words`() {
        val r = KitFromAnything.Recipe(Slot.CLAP, Genre.TECHNO, KeySpec(3, Scale.MAJOR_PENTATONIC), "kitfrom-source.wav", 123456)
        val back = KitFromAnything.Recipe.fromJsonValue(r.toJsonValue())
        assertEquals(r, back)
        assertNull(KitFromAnything.Recipe.fromJsonValue(com.snipsnap.json.JsonValue.Obj(linkedMapOf("recipe" to com.snipsnap.json.JsonValue.Num(2.0)))), "another recipe shape is not ours")
        assertNull(KitFromAnything.Recipe.fromJsonValue(null))
        val e = assertFailsWith<com.snipsnap.json.JsonException> {
            KitFromAnything.Recipe.fromJsonValue(com.snipsnap.json.JsonValue.Obj(linkedMapOf(
                KitFromAnything.Recipe.KEY to com.snipsnap.json.JsonValue.Obj(linkedMapOf("version" to com.snipsnap.json.JsonValue.Num(1.0))),
            )))
        }
        assertTrue(e.message!!.contains("slot"), e.message)
    }

    @Test
    fun `every pad rebuilds bit for bit from its recipe and the source, blends included`() {
        for ((label, src, genre) in listOf(Triple("sweep", sweep, Genre.BOOM_BAP), Triple("pluck", pluck, Genre.HOUSE))) {
            val r = KitFromAnything.assemble(src, key, genre, sourceFile = KitFromAnything.SOURCE_FILE)
            for ((i, pad) in r.pads.withIndex()) {
                val recipe = assertNotNull(KitFromAnything.Recipe.fromJsonValue(pad!!.recipe), "$label ${Slot.entries[i].name} carries a recipe")
                assertEquals(Slot.entries[i], recipe.slot)
                assertEquals(KitFromAnything.SOURCE_FILE, pad.source["sourceFile"])
                val rebuilt = KitFromAnything.rerender(recipe, src)
                assertContentEquals(pad.snip.samples, rebuilt.samples, "$label ${Slot.entries[i].name} rebuilt")
            }
        }
        assertNull(KitFromAnything.assemble(pluck, key, Genre.HOUSE).pads[0]!!.recipe, "without a kept source there is no recipe to make a promise with")
    }

    @Test
    fun `a swapped source is refused in words, never rendered into a different kit`() {
        val r = KitFromAnything.assemble(sweep, key, Genre.BOOM_BAP, sourceFile = KitFromAnything.SOURCE_FILE)
        val recipe = KitFromAnything.Recipe.fromJsonValue(r.pads[0]!!.recipe)!!
        val e = assertFailsWith<IllegalArgumentException> { KitFromAnything.rerender(recipe, pluck) }
        assertTrue(e.message!!.contains("not the one this pad was made from"), e.message)
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
        val nan = Snip(FloatArray(rate) { if (it == 100) Float.NaN else 0.1f }, 1, rate)
        val e3 = assertFailsWith<IllegalArgumentException> { KitFromAnything.assemble(nan, key, Genre.HOUSE) }
        assertTrue(e3.message!!.contains("NaN"), e3.message)
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
