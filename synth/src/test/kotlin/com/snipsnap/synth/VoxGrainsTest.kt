package com.snipsnap.synth

import com.snipsnap.audio.Classifier
import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.FeatureExtractor
import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VoxGrainsTest {

    // ---------- VOX ----------

    @Test
    fun `every vox renders clean audio at defaults and corners`() {
        for (voice in VoxVoice.entries) {
            for (macros in listOf(
                emptyMap(),
                Vox.macrosFor(voice).associate { it.name to 0f },
                Vox.macrosFor(voice).associate { it.name to 1f },
            )) {
                val snip = Vox.render(voice, macros)
                assertTrue(snip.samples.all { it.isFinite() && it in -1f..1f }, "$voice broke at $macros")
                assertTrue(snip.peak() > 0.5f, "$voice too quiet at $macros")
                assertTrue(snip.durationSeconds < 1.5f, "$voice must stay a one-shot")
            }
        }
    }

    @Test
    fun `vox scrambles are reproducible and never garbage`() {
        for (voice in VoxVoice.entries) {
            assertEquals(Vox.scramble(voice, Random(5)), Vox.scramble(voice, Random(5)))
            repeat(8) { seed ->
                val c = Classifier.classify(Vox.render(voice, Vox.scramble(voice, Random(seed))))
                assertTrue(
                    c.drumClass != DrumClass.KICK && c.drumClass != DrumClass.LOOP &&
                        c.drumClass != DrumClass.UNKNOWN,
                    "$voice roll $seed classified ${c.drumClass}",
                )
            }
        }
    }

    @Test
    fun `vox defaults land on the vocal shelf`() {
        // PERC for the tonal throats; GHOST is deliberately breathy, and a
        // breathy vocal honestly reads snare-shaped to a drum classifier.
        for (voice in VoxVoice.entries) {
            val c = Classifier.classify(Vox.render(voice))
            assertTrue(
                c.drumClass in setOf(DrumClass.PERC, DrumClass.SNARE),
                "$voice default read as ${c.drumClass}",
            )
        }
    }

    @Test
    fun `VOWEL moves the mouth`() {
        // A (formants high) vs U (formants low): the centroid must follow.
        val aah = FeatureExtractor.extract(Vox.render(VoxVoice.CHOIR, mapOf("VOWEL" to 0f, "BREATH" to 0f)))
        val ooh = FeatureExtractor.extract(Vox.render(VoxVoice.CHOIR, mapOf("VOWEL" to 1f, "BREATH" to 0f)))
        assertTrue(
            aah.centroidHz > ooh.centroidHz * 1.2f,
            "aah should be brighter than ooh: ${aah.centroidHz} vs ${ooh.centroidHz}",
        )
        // And the morph is continuous: the midpoint differs from both ends.
        val mid = Vox.formantsAt(0.5f)
        assertTrue(mid[0] != Vox.formantsAt(0f)[0] && mid[0] != Vox.formantsAt(1f)[0])
    }

    @Test
    fun `BREATH airs it out and TUNE tunes, snapped`() {
        val clean = FeatureExtractor.extract(Vox.render(VoxVoice.CHOIR, mapOf("BREATH" to 0f)))
        val airy = FeatureExtractor.extract(Vox.render(VoxVoice.CHOIR, mapOf("BREATH" to 1f)))
        assertTrue(airy.flatness > clean.flatness * 1.5f, "breath is noise: ${clean.flatness} -> ${airy.flatness}")

        val distinct = HashSet<Float>()
        for (i in 0..100) distinct.add(Vox.frequencyFor(VoxVoice.CHOIR, i / 100f))
        assertEquals(Vox.TUNE_SEMITONES + 1, distinct.size)

        val lowNote = Pitch.detect(Vox.render(VoxVoice.CHOIR, mapOf("TUNE" to 0f, "BREATH" to 0f)))
        val highNote = Pitch.detect(Vox.render(VoxVoice.CHOIR, mapOf("TUNE" to 1f, "BREATH" to 0f)))
        assertNotNull(lowNote); assertNotNull(highNote)
        assertTrue(
            highNote.hz > lowNote.hz * 3f && highNote.hz < lowNote.hz * 5f,
            "two octaves: ${lowNote.hz} -> ${highNote.hz}",
        )
    }

    @Test
    fun `vox patches dispatch like every other engine`() {
        val patch = VoxPatch("Mall Choir", VoxVoice.CHOIR, mapOf("VOWEL" to 0.3f))
        val back = Patches.fromJsonText(patch.toJsonText())
        assertEquals(patch, back)
        assertTrue(back.render().samples.contentEquals(patch.render().samples))
    }

    // ---------- GRAINS ----------

    private val bell = Tines.render(TinesVoice.BELL, mapOf("DECAY" to 0.8f))

    @Test
    fun `a cloud is a loop, deterministic per seed, clean on any roll`() {
        val a = Grains.render(bell, seed = 4)
        val b = Grains.render(bell, seed = 4)
        assertTrue(a.samples.contentEquals(b.samples), "same seed, same cloud")
        assertTrue(!Grains.render(bell, seed = 5).samples.contentEquals(a.samples), "new seed, new cloud")
        assertEquals(DrumClass.LOOP, Classifier.classify(a).drumClass, "a texture is honestly a loop")

        repeat(6) { seed ->
            val out = Grains.render(bell, Grains.scramble(Random(seed)), seconds = 1.8f, seed = seed)
            assertTrue(out.samples.all { it.isFinite() && it in -1f..1f }, "roll $seed broke")
            assertTrue(out.peak() > 0.5f, "roll $seed went quiet")
            assertEquals((1.8f * 44_100).toInt(), out.frameCount)
        }
    }

    @Test
    fun `PITCH re-pitches the cloud, snapped to semitones`() {
        val tone = Snip(
            FloatArray(44_100) { (0.6 * sin(2.0 * PI * 220.0 * it / 44_100)).toFloat() },
            1, 44_100,
        )
        // Big grains, no drift, no shine: the cloud is nearly the tone.
        val calm = mapOf("SIZE" to 1f, "SMEAR" to 1f, "DRIFT" to 0f, "SHINE" to 0f)
        val native = assertNotNull(Pitch.detect(Grains.render(tone, calm + mapOf("PITCH" to 0.5f), seconds = 1f)))
        val up = assertNotNull(Pitch.detect(Grains.render(tone, calm + mapOf("PITCH" to 1f), seconds = 1f)))
        assertTrue(abs(native.hz - 220f) < 8f, "native pitch should hold: ${native.hz}")
        assertTrue(abs(up.hz - 440f) < 16f, "+12 semis should double it: ${up.hz}")
        assertEquals(0, Grains.semitonesFor(0.5f))
        assertEquals(12, Grains.semitonesFor(1f))
        assertEquals(-12, Grains.semitonesFor(0f))
    }

    @Test
    fun `SIZE changes the texture and SHINE adds sparkle`() {
        val stutter = Grains.render(bell, mapOf("SIZE" to 0f), seconds = 1.5f)
        val wash = Grains.render(bell, mapOf("SIZE" to 1f), seconds = 1.5f)
        assertTrue(!stutter.samples.contentEquals(wash.samples))

        // Measure the octave layer where it must appear: granulate a 220 Hz
        // tone and look for 440 Hz energy directly (a decaying bell hides
        // the shimmer - its 2x-speed read lands on faded material).
        val tone = Snip(
            FloatArray(44_100) { (0.6 * sin(2.0 * PI * 220.0 * it / 44_100)).toFloat() },
            1, 44_100,
        )
        fun magAt(s: Snip, hz: Int): Double {
            var re = 0.0
            var im = 0.0
            for (i in 0 until s.frameCount) {
                val w = 2.0 * PI * hz * i / s.sampleRate
                re += s.samples[i] * kotlin.math.cos(w)
                im += s.samples[i] * kotlin.math.sin(w)
            }
            return Math.sqrt(re * re + im * im) / s.frameCount
        }
        val calm = mapOf("SIZE" to 1f, "SMEAR" to 0.5f, "DRIFT" to 0f, "PITCH" to 0.5f)
        val dullMag = magAt(Grains.render(tone, calm + mapOf("SHINE" to 0f), seconds = 1f), 440)
        val shinyMag = magAt(Grains.render(tone, calm + mapOf("SHINE" to 1f), seconds = 1f), 440)
        assertTrue(shinyMag > dullMag * 10, "the octave layer should appear at 440 Hz: $dullMag -> $shinyMag")
    }

    @Test
    fun `grains eat captures - the whole point`() {
        // A stand-in capture: noisy, messy, stereo. The cloud must still be
        // clean, and the granulated result flows through the kit pipeline
        // like any other sound.
        var s = 9
        val capture = Snip(
            FloatArray(88_200) { i ->
                s = (s * 1103515245 + 12345) and 0x7fffffff
                (0.4f * ((s.toFloat() / 0x3fffffff) - 1f) * sin(i / 300.0).toFloat())
            },
            2, 44_100,
        )
        val cloud = Grains.render(capture, mapOf("SIZE" to 0.7f, "DRIFT" to 0.6f))
        assertTrue(cloud.samples.all { it.isFinite() && it in -1f..1f })
        assertTrue(cloud.peak() > 0.5f)
    }
}
