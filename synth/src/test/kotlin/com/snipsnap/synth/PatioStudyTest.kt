package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.Snip
import kotlin.math.*
import kotlin.test.*

class PatioStudyTest {
    @Test fun `sixteen pads preserve the drum kit and replay complete recipes`() {
        val recipes = PatioStudy.recipes()
        assertEquals(16, recipes.size)
        assertEquals(16, recipes.map { it.id }.distinct().size)
        assertEquals(listOf(DrumClass.KICK, DrumClass.SNARE, DrumClass.HAT_CLOSED, DrumClass.HAT_OPEN), recipes.take(4).map { it.drumClass })
        for (r in recipes) {
            val replay = PatioStudy.fromJsonText(r.toJsonText())
            assertEquals(r.toJsonText(), replay.toJsonText())
            val s = r.render()
            assertContentEquals(s.samples, replay.render().samples, r.id)
            healthy(s, r.id)
        }
        val cajon = recipes.single { it.id == "cajon" }.patch as TerraPatch
        assertEquals(TerraVoice.BOX, cajon.voice)
        assertEquals(0f, cajon.macros["BUZZ"])
        val open = recipes.single { it.id == "alegre-open" }.patch!!
        val slap = recipes.single { it.id == "alegre-slap" }.patch!!
        assertEquals(open.macros["TUNE"], slap.macros["TUNE"])
    }

    @Test fun `contact plans are finite reproducible and give longer gestures more contacts`() {
        for (v in PatioTexture.Voice.entries) {
            val short = PatioTexture.contacts(v, PatioTexture.Settings(hold = 0f), 123)
            val long = PatioTexture.contacts(v, PatioTexture.Settings(hold = 1f), 123)
            assertTrue(long.size > short.size * 2)
            assertEquals(long, PatioTexture.contacts(v, PatioTexture.Settings(hold = 1f), 123))
            assertTrue(long.zipWithNext().all { (a, b) -> b.seconds > a.seconds })
            for (tune in listOf(0f, 1f)) for (touch in listOf(0f, 1f)) for (hold in listOf(0f, 1f)) {
                healthy(PatioTexture.render(v, PatioTexture.Settings(tune, touch, hold), 123), "$v corners")
            }
        }
    }

    @Test fun `texture controls and seeds move actual audio`() {
        for (v in PatioTexture.Voice.entries) {
            val m = PatioTexture.Settings()
            val reference = PatioTexture.render(v, m, 123).samples
            for (changed in listOf(m.copy(tune = .2f), m.copy(touch = .2f), m.copy(hold = .8f))) {
                assertFalse(reference.contentEquals(PatioTexture.render(v, changed, 123).samples))
            }
            assertFalse(reference.contentEquals(PatioTexture.render(v, m, 124).samples))
        }
    }

    @Test fun `tape precedes the amp and never changes its input`() {
        val r = PatioStudy.recipes().last()
        val dry = r.dry()
        val copy = dry.samples.copyOf()
        val m = PatioWear.Settings(.8f, .7f, .75f)
        val expected = PatioWear.amp(PatioWear.tape(dry, m, r.seed), m)
        assertContentEquals(expected.samples, PatioWear.process(dry, m, r.seed).samples)
        assertContentEquals(copy, dry.samples)
        val reverse = PatioWear.tape(PatioWear.amp(dry, m), m, r.seed)
        assertTrue(difference(expected.samples, reverse.samples) > .0001)
    }

    @Test fun `wear controls change a sustained probe and extreme chains stay finite`() {
        val probe = Snip(FloatArray(Dsp.RATE / 2) { i -> (.5 * sin(2 * PI * 997 * i / Dsp.RATE)).toFloat() }, 1, Dsp.RATE)
        val default = PatioWear.Settings()
        val ref = PatioWear.process(probe, default, 31)
        for (m in listOf(default.copy(wear = .1f), default.copy(wobble = .1f), default.copy(drive = .1f))) {
            assertTrue(difference(ref.samples, PatioWear.process(probe, m, 31).samples) > .0001)
        }
        for (wear in listOf(0f, 1f)) for (wobble in listOf(0f, 1f)) for (drive in listOf(0f, 1f)) {
            healthy(PatioWear.process(probe, PatioWear.Settings(wear, wobble, drive), 31), "chain corner")
        }
        assertContentEquals(probe.samples, PatioWear.tape(probe, PatioWear.Settings(0f, 0f, .5f), 31).samples)
        val silence = probe.copy(samples = FloatArray(probe.frameCount))
        assertTrue(PatioWear.process(silence, default, 31).samples.all { it == 0f })
    }

    @Test fun `invalid saved state is refused before narrowing or rendering`() {
        val json = PatioStudy.recipes().first().toJsonText()
        assertFails { PatioStudy.fromJsonText(json.replace("\"patioStudy\": 1", "\"patioStudy\": 2")) }
        assertFails { PatioStudy.fromJsonText(json.replace("\"wear\": 0.3", "\"wear\": 1.00000001")) }
        assertFailsWith<IllegalArgumentException> { PatioTexture.Settings(touch = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { PatioWear.Settings(wobble = Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { PatioWear.tape(Snip(floatArrayOf(Float.NaN), 1, Dsp.RATE), PatioWear.Settings(), 0) }
    }

    @Test fun `tape saturation suppresses selected folded harmonics against an eightfold reference`() {
        val hz = 5003.0
        val probe = Snip(FloatArray(Dsp.RATE) { i -> (.8 * sin(2 * PI * hz * i / Dsp.RATE)).toFloat() }, 1, Dsp.RATE)
        val m = PatioWear.Settings(1f, 0f, .35f)
        val four = PatioWear.tapeAt(probe, m, 19, 4)
        val eight = PatioWear.tapeAt(probe, m, 19, 8)
        fun amplitude(s: Snip, frequency: Double): Double {
            var re = 0.0; var im = 0.0; var weight = 0.0
            val start = (Dsp.RATE * .65).toInt()
            val size = (Dsp.RATE * .30).toInt()
            for (i in 0 until size) {
                val w = .5 - .5 * cos(2 * PI * i / (size - 1))
                val phase = 2 * PI * frequency * (start + i) / Dsp.RATE
                re += s.samples[start + i] * w * cos(phase)
                im += s.samples[start + i] * w * sin(phase)
                weight += w
            }
            return 2 * hypot(re, im) / weight
        }
        for (s in listOf(four, eight)) {
            val root = amplitude(s, hz)
            for (harmonic in listOf(5, 7)) {
                val folded = abs(Dsp.RATE - hz * harmonic)
                val db = 20 * log10((amplitude(s, folded) / root).coerceAtLeast(1e-12))
                println("PATIO ${if (s === four) "4x" else "8x"} folded harmonic $harmonic: $db dB")
                assertTrue(db < -35, "selected alias must remain below -35 dB, got $db")
            }
        }
        // Compare wanted tone magnitude separately; this is not an all-frequency alias claim.
        assertTrue(abs(20 * log10(amplitude(four, hz) / amplitude(eight, hz))) < 1.0)
    }

    private fun healthy(s: Snip, name: String) {
        assertEquals(1, s.channels, name)
        assertEquals(Dsp.RATE, s.sampleRate, name)
        assertTrue(s.samples.all { it.isFinite() }, name)
        assertTrue(s.peak() in .001f..1f, "$name peak ${s.peak()}")
        assertTrue(abs(s.samples.average()) < .01, "$name DC ${s.samples.average()}")
        assertTrue(abs(s.samples.last()) < .001, "$name ending")
    }

    private fun difference(a: FloatArray, b: FloatArray): Double =
        sqrt((0 until min(a.size, b.size)).sumOf { (a[it] - b[it]).toDouble().pow(2) } / min(a.size, b.size))
}
