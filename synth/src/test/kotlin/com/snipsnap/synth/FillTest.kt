package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.synth.Dsp.RATE
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FillTest {
    private fun macros(voice: FillVoice, vararg pairs: Pair<String, Float>) = Fill.defaults(voice) + pairs

    @Test
    fun `TEMPO is whole BPM from 60 to 180 and tempoFor inverts it`() {
        assertEquals(60, Fill.bpm(0f))
        assertEquals(120, Fill.bpm(.5f))
        assertEquals(180, Fill.bpm(1f))
        for (bpm in 60..180) assertEquals(bpm, Fill.bpm(Fill.tempoFor(bpm)))
    }

    @Test
    fun `LENGTH steps through one beat, two beats, a bar and two bars`() {
        assertEquals(listOf(1, 2, 4, 8), listOf(0f, .3f, .6f, 1f).map { Fill.beats(it) })
        for (beats in Fill.LENGTH_BEATS) assertEquals(beats, Fill.beats(Fill.lengthFor(beats)))
        assertEquals(4, Fill.beats(Fill.defaults(FillVoice.SNARE).getValue("LENGTH")))
    }

    @Test
    fun `every hit sits on the grid and the landing falls exactly LENGTH in`() {
        for (voice in FillVoice.entries) for (beats in Fill.LENGTH_BEATS) for (density in listOf(0f, .5f, 1f)) for (tempo in listOf(0f, .37f, 1f)) {
            val s = Fill.schedule(voice, macros(voice, "LENGTH" to Fill.lengthFor(beats), "DENSITY" to density, "TEMPO" to tempo))
            val label = "$voice $beats beats density $density tempo $tempo"
            assertEquals(beats * 60.0 / s.bpm, s.lengthSeconds, 1e-12, label)
            for (h in s.hits) {
                val steps = h.seconds / s.stepSeconds
                assertTrue(abs(steps - steps.roundToInt()) < 1e-6, "$label: a hit at ${h.seconds} s is off the grid")
                assertTrue(h.seconds <= s.lengthSeconds + 1e-12, label)
            }
            val landing = s.hits.filter { abs(it.seconds - s.lengthSeconds) < 1e-12 }
            assertEquals(setOf(Fill.Drum.KICK, if (voice == FillVoice.SNARE) Fill.Drum.SNARE else Fill.Drum.TOM_FLOOR),
                landing.map { it.drum }.toSet(), label)
        }
    }

    @Test
    fun `the snare roll doubles its rate at half and three quarters of the bar`() {
        // A bar at DENSITY .5: two beats of eighths, a beat of sixteenths, a beat of thirty-seconds.
        val s = Fill.schedule(FillVoice.SNARE, macros(FillVoice.SNARE, "LAND" to 0f))
        val beat = 60.0 / s.bpm
        val counts = listOf(0.0 to 2.0, 2.0 to 3.0, 3.0 to 4.0).map { (a, b) -> s.hits.count { it.seconds >= a * beat - 1e-9 && it.seconds < b * beat - 1e-9 } }
        assertEquals(listOf(4, 4, 8), counts)
        // DENSITY 0 halves every note: quarters, eighths, sixteenths.
        assertEquals(2 + 2 + 4, Fill.schedule(FillVoice.SNARE, macros(FillVoice.SNARE, "LAND" to 0f, "DENSITY" to 0f)).hits.size)
    }

    @Test
    fun `the tom run walks high to floor in equal quarters`() {
        val s = Fill.schedule(FillVoice.TOMS, macros(FillVoice.TOMS, "LAND" to 0f))
        assertEquals(16, s.hits.size)
        val toms = listOf(Fill.Drum.TOM_HIGH, Fill.Drum.TOM_MID, Fill.Drum.TOM_LOW, Fill.Drum.TOM_FLOOR)
        assertEquals(toms.flatMap { d -> List(4) { d } }, s.hits.map { it.drum })
        // The toms stay a minor third apart, high above floor, inside SKIN's tom range, at every TUNE.
        for (tune in listOf(0f, .5f, 1f)) {
            val t = toms.map { Fill.tomTune(it, tune) }
            assertTrue(t.zipWithNext().all { (a, b) -> a > b }, "TUNE $tune: $t")
            assertTrue(t.all { it in 0f..1f } && t.first() < 1f, "TUNE $tune: $t")
        }
    }

    @Test
    fun `RISE sets the crescendo, which never falls`() {
        for (voice in FillVoice.entries) {
            val flat = Fill.schedule(voice, macros(voice, "RISE" to 0f, "LAND" to 0f)).hits
            assertTrue(flat.all { it.velocity == 1f }, "$voice RISE 0 is flat")
            val rising = Fill.schedule(voice, macros(voice, "RISE" to 1f, "LAND" to 0f)).hits
            assertEquals(.2f, rising.first().velocity, 1e-6f)
            assertTrue(rising.zipWithNext().all { (a, b) -> b.velocity >= a.velocity }, "$voice")
        }
    }

    @Test
    fun `the landing starts on its downbeat frame`() {
        for (voice in FillVoice.entries) {
            val withLanding = Fill.render(voice, macros(voice, "LAND" to 1f, "TEMPO" to Fill.tempoFor(97))).samples
            val without = Fill.render(voice, macros(voice, "LAND" to 0f, "TEMPO" to Fill.tempoFor(97))).samples
            val downbeat = (4 * 60.0 / 97 * RATE).roundToInt()
            // Normalisation scales the two renders differently, so compare shapes: the first frame where they part.
            val ga = withLanding.take(downbeat).maxOf { abs(it) }
            val gb = without.take(downbeat).maxOf { abs(it) }
            // Nothing differs before the downbeat; a drum's first sample can be near silent, so allow it 1 ms to be heard.
            val first = withLanding.indices.first { abs(withLanding[it] / ga - without[it] / gb) > 1e-3f }
            assertTrue(first in downbeat..downbeat + RATE / 1000, "$voice: the landing starts ${first - downbeat} frames off the downbeat")
            assertTrue((0 until downbeat).all { abs(withLanding[it] / ga - without[it] / gb) < 1e-5f }, "$voice: the landing leaks before the downbeat")
        }
    }

    @Test
    fun `every control changes the fill`() {
        for (voice in FillVoice.entries) {
            val base = Fill.render(voice, macros(voice)).samples
            for (spec in Fill.macrosFor(voice)) {
                val moved = if (spec.default > .5f) 0f else 1f
                val other = Fill.render(voice, macros(voice, spec.name to moved)).samples
                assertTrue(other.size != base.size || other.indices.any { abs(other[it] - base[it]) > 1e-3f }, "$voice ${spec.name}")
            }
            val soft = Fill.render(voice, macros(voice), velocity = .3f).samples
            assertTrue(soft.indices.any { abs(soft[it] - base[it]) > 1e-3f }, "$voice velocity")
        }
    }

    @Test
    fun `renders are finite, bounded, ring past the landing and file as LOOP`() {
        for (voice in FillVoice.entries) for (beats in Fill.LENGTH_BEATS) for (tempo in listOf(0f, 1f)) {
            val m = macros(voice, "LENGTH" to Fill.lengthFor(beats), "TEMPO" to tempo, "DENSITY" to 1f)
            val out = Fill.render(voice, m).samples
            val label = "$voice $beats beats tempo $tempo"
            assertEquals(Fill.renderFrames(voice, m), out.size, label)
            assertTrue(out.all { it.isFinite() } && out.maxOf { abs(it) } <= 1f, label)
            assertTrue(out.size >= Fill.MIN_RENDER_SECONDS * RATE - 1, label)
            assertEquals(DrumClass.LOOP, Fill.drumClassFor(voice, m), label)
        }
    }

    @Test
    fun `bad input settles to defaults and range`() {
        val m = Fill.settled(FillVoice.SNARE, mapOf("TEMPO" to Float.NaN, "RISE" to 3f, "BOGUS" to .1f))
        assertEquals(Fill.defaults(FillVoice.SNARE).keys, m.keys)
        assertEquals(.5f, m.getValue("TEMPO"))
        assertEquals(1f, m.getValue("RISE"))
        assertTrue(Fill.render(FillVoice.TOMS, emptyMap(), velocity = Float.NaN).samples.all { it.isFinite() })
    }

    @Test
    fun `the provisional presets cover each voice at each length and survive a save`() {
        for (voice in FillVoice.entries) {
            val presets = FillPresets.forVoice(voice)
            assertEquals(Fill.LENGTH_BEATS.toList(), presets.map { Fill.beats(it.macros.getValue("LENGTH")) }, "$voice")
            for (p in presets) {
                assertEquals(voice, p.voice)
                assertEquals(p, FillPatch.fromJsonText(p.toJsonText()), p.name)
            }
        }
        assertEquals(FillPresets.all().size, FillPresets.all().map { it.name }.toSet().size)
    }
}
