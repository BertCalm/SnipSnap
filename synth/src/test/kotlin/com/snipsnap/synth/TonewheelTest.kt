package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TonewheelTest {

    /** All bars pushed in except the ones named — a controlled registration. */
    private fun bars(vararg set: Pair<Int, Float>): Map<String, Float> {
        val m = HashMap<String, Float>()
        for (i in 1..8) m["BAR$i"] = 0f
        for ((bar, level) in set) m["BAR$bar"] = level
        m["PERC"] = 0f; m["WARBLE"] = 0f; m["DIRT"] = 0f
        return m
    }

    @Test
    fun `every voice renders clean audio at defaults and both corners`() {
        for (voice in TonewheelVoice.entries) {
            for (macros in listOf(
                emptyMap(),
                Tonewheel.macrosFor(voice).associate { it.name to 1f },
            )) {
                val snip = Tonewheel.render(voice, macros)
                assertTrue(snip.frameCount > 0, "$voice rendered nothing")
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke range")
                assertTrue(snip.peak() > 0.5f, "$voice too quiet")
                assertTrue(snip.durationSeconds < 1.5f, "$voice must stay a one-shot stab")
            }
        }
    }

    @Test
    fun `scrambles are reproducible and never garbage`() {
        for (voice in TonewheelVoice.entries) {
            assertEquals(Tonewheel.scramble(voice, Random(6)), Tonewheel.scramble(voice, Random(6)))
            repeat(8) { seed ->
                val snip = Tonewheel.render(voice, Tonewheel.scramble(voice, Random(seed)))
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice roll $seed broke")
                val c = Classifier.classify(snip)
                assertTrue(
                    c.drumClass != DrumClass.KICK && c.drumClass != DrumClass.LOOP &&
                        c.drumClass != DrumClass.UNKNOWN,
                    "$voice roll $seed classified ${c.drumClass}",
                )
            }
        }
    }

    @Test
    fun `is deterministic`() {
        for (voice in TonewheelVoice.entries) {
            assertTrue(
                Tonewheel.render(voice).samples.contentEquals(Tonewheel.render(voice).samples),
                "$voice not deterministic",
            )
        }
    }

    @Test
    fun `factory registrations all classify as percussion`() {
        for (voice in TonewheelVoice.entries) {
            val c = Classifier.classify(Tonewheel.render(voice))
            assertEquals(DrumClass.PERC, c.drumClass, "$voice default read as ${c.drumClass}")
        }
    }

    @Test
    fun `a registration has twelve handles, eight of them bars`() {
        for (voice in TonewheelVoice.entries) {
            val names = Tonewheel.macrosFor(voice).map { it.name }
            assertEquals(12, names.size)
            for (i in 1..8) assertTrue("BAR$i" in names, "$voice missing BAR$i")
        }
    }

    @Test
    fun `pulling a high bar brightens`() {
        val fundamentalOnly = FeatureExtractor.extract(
            Tonewheel.render(TonewheelVoice.FULL, bars(2 to 1f)),
        )
        val withTop = FeatureExtractor.extract(
            Tonewheel.render(TonewheelVoice.FULL, bars(2 to 1f, 8 to 1f)),
        )
        assertTrue(
            withTop.centroidHz > fundamentalOnly.centroidHz * 1.5f,
            "BAR8 should add top: ${fundamentalOnly.centroidHz} -> ${withTop.centroidHz}",
        )
    }

    @Test
    fun `PERC bites the front and is gone by the body`() {
        fun headToBody(perc: Float): Float {
            val s = Tonewheel.render(TonewheelVoice.SOUL, bars(2 to 1f) + mapOf("PERC" to perc))
            fun rms(from: Float, to: Float): Float {
                var sum = 0.0
                val a = (from * s.sampleRate).toInt()
                val b = (to * s.sampleRate).toInt().coerceAtMost(s.samples.size)
                for (i in a until b) sum += (s.samples[i] * s.samples[i]).toDouble()
                return sqrt(sum / (b - a)).toFloat()
            }
            return rms(0f, 0.08f) / rms(0.3f, 0.5f)
        }
        assertTrue(
            headToBody(1f) > headToBody(0f) * 1.3f,
            "the percussion register should live in the attack",
        )
    }

    @Test
    fun `WARBLE wobbles`() {
        val still = Tonewheel.render(TonewheelVoice.FULL, mapOf("WARBLE" to 0f))
        val warbled = Tonewheel.render(TonewheelVoice.FULL, mapOf("WARBLE" to 1f))
        var diff = 0.0
        for (i in still.samples.indices) diff += Math.abs((still.samples[i] - warbled.samples[i]).toDouble())
        assertTrue(diff / still.samples.size > 0.05, "full WARBLE should audibly move the pitch")
    }

    @Test
    fun `DIRT adds harmonics, not loudness`() {
        val clean = FeatureExtractor.extract(Tonewheel.render(TonewheelVoice.SOUL, bars(2 to 1f)))
        val dirty = FeatureExtractor.extract(
            Tonewheel.render(TonewheelVoice.SOUL, bars(2 to 1f) + mapOf("DIRT" to 1f)),
        )
        assertTrue(
            dirty.centroidHz > clean.centroidHz * 1.2f,
            "drive should add upper harmonics: ${clean.centroidHz} -> ${dirty.centroidHz}",
        )
    }

    @Test
    fun `TUNE snaps to semitones`() {
        val distinct = HashSet<Float>()
        for (i in 0..100) distinct.add(Tonewheel.frequencyFor(i / 100f))
        assertEquals(Tonewheel.TUNE_SEMITONES + 1, distinct.size)
        assertEquals(110f, Tonewheel.frequencyFor(0f))
        assertEquals(440f, Tonewheel.frequencyFor(1f))
    }
}
