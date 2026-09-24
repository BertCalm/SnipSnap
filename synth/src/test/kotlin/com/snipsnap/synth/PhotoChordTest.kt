package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoChordTest {

    /** A reading with only the three numbers the chord reads; the rest are held neutral. */
    private fun reading(hue: Float = 0f, luminance: Float = 0.5f, saturation: Float = 0.5f) = Snap.Reading(
        luminance = luminance,
        contrast = 0.2f,
        saturation = saturation,
        hue = hue,
        hueStrength = 1f,
        detail = 0.05f,
        meanRgb = 0,
    )

    @Test
    fun `brightness picks the third and colourfulness the seventh`() {
        assertEquals(PhotoChord.Quality.MAJOR7, PhotoChord.chordOf(reading(luminance = 0.8f, saturation = 0.6f)).quality)
        assertEquals(PhotoChord.Quality.MAJOR, PhotoChord.chordOf(reading(luminance = 0.8f, saturation = 0.1f)).quality)
        assertEquals(PhotoChord.Quality.MINOR7, PhotoChord.chordOf(reading(luminance = 0.2f, saturation = 0.6f)).quality)
        assertEquals(PhotoChord.Quality.MINOR, PhotoChord.chordOf(reading(luminance = 0.2f, saturation = 0.1f)).quality)
    }

    @Test
    fun `the root is the note SNAP's own TUNE lands on, folded to one octave`() {
        for (hue in listOf(0f, 20f, 95f, 180f, 250f, 329f, 331f)) {
            val r = reading(hue = hue)
            val tune = Snap.macrosFrom(r).getValue("TUNE")
            assertEquals(Math.round(tune * Snap.TUNE_SEMITONES) % 12, PhotoChord.chordOf(r).root, "hue $hue")
        }
    }

    @Test
    fun `a grey photo has no hue to vote with and lands on SNAP's centre detent, A`() {
        val grey = reading(saturation = 0.01f, luminance = 0.3f)
        assertEquals(PhotoChord.Chord(0, PhotoChord.Quality.MINOR), PhotoChord.chordOf(grey))
        assertEquals("Am", PhotoChord.chordOf(grey).name)
    }

    @Test
    fun `names count from C the way Scales does`() {
        assertEquals("Cmaj7", PhotoChord.Chord(3, PhotoChord.Quality.MAJOR7).name)
        assertEquals("F#m", PhotoChord.Chord(9, PhotoChord.Quality.MINOR).name)
        assertEquals("G#m7", PhotoChord.Chord(11, PhotoChord.Quality.MINOR7).name)
        assertFailsWith<IllegalArgumentException> { PhotoChord.Chord(12, PhotoChord.Quality.MAJOR) }
    }

    @Test
    fun `tones start on the root and fill SNAP's two octaves with chord tones only`() {
        assertEquals(listOf(0, 4, 7, 12, 16, 19, 24), PhotoChord.tones(PhotoChord.Chord(0, PhotoChord.Quality.MAJOR)))
        // D major: the A below D is a chord tone, but the root sits on A01.
        assertEquals(listOf(5, 9, 12, 17, 21, 24), PhotoChord.tones(PhotoChord.Chord(5, PhotoChord.Quality.MAJOR)))
        for (root in 0..11) for (quality in PhotoChord.Quality.values()) {
            val chord = PhotoChord.Chord(root, quality)
            val tones = PhotoChord.tones(chord)
            assertEquals(root, tones.first(), "$chord starts on its root")
            assertTrue(tones.all { it in 0..Snap.TUNE_SEMITONES }, "$chord stays in SNAP's range: $tones")
            assertTrue(tones.all { ((it - root) % 12) in quality.intervals }, "$chord holds only chord tones: $tones")
            // At least root, third, fifth and the root an octave up; at most one bank.
            assertTrue(tones.size in 4..16, "$chord spans an octave and fits one bank: $tones")
            assertTrue(root + 12 in tones, "$chord reaches its own octave: $tones")
        }
    }

    @Test
    fun `a pad's colour reads back through SNAP as that pad's own note`() {
        for (semis in 0..Snap.TUNE_SEMITONES) {
            val color = PhotoChord.colorFor(semis)
            val photo = Photo.of(8, 8) { _, _ -> color }
            val tune = Snap.macrosFrom(Snap.look(photo)).getValue("TUNE")
            val readBack = Math.round(tune * Snap.TUNE_SEMITONES)
            // The hue circle closes on itself, so the bottom and top A share a
            // colour: compare pitch classes, which is what the eye can tell apart.
            assertEquals(semis % 12, readBack % 12, "semitone $semis came back as $readBack")
        }
        assertFailsWith<IllegalArgumentException> { PhotoChord.colorFor(25) }
    }

    /** A grey photo whose HORIZON line is one clean cycle of a sine, around [level]. */
    private fun sinePhoto(level: Float) = Photo.grey(256, 8) { x, _ -> level + 0.3f * sin(2.0 * PI * x / 256).toFloat() }

    @Test
    fun `build lands one pad per tone from A01 up, and nothing past them`() {
        val built = PhotoChord.build(sinePhoto(0.6f), "Test")
        assertEquals(PhotoChord.Chord(0, PhotoChord.Quality.MAJOR), built.chord)
        val tones = PhotoChord.tones(built.chord)
        assertEquals((1..tones.size).toList(), built.slots)
        assertEquals(16, built.pads.size)
        for ((i, semis) in tones.withIndex()) {
            val pad = assertNotNull(built.pads[i], "slot ${i + 1}")
            assertTrue(pad.snip.peak() > 0f, "slot ${i + 1} is audible")
            val patch = PadRecipe.fromJsonValue(pad.recipe!!).patch as SnapPatch
            assertEquals(semis, Math.round(patch.macros.getValue("TUNE") * Snap.TUNE_SEMITONES), "slot ${i + 1}'s recipe")
            assertEquals("#%06x".format(PhotoChord.colorFor(semis)), pad.colorHex)
        }
        for (i in tones.size until 16) assertNull(built.pads[i], "slot ${i + 1} is past the chord")
    }

    @Test
    fun `the root pad sounds the root, and the fifth a fifth above it`() {
        val built = PhotoChord.build(sinePhoto(0.6f), "Test")
        val root = assertNotNull(Pitch.detect(built.pads[0]!!.snip), "the root pad has a pitch")
        val fifth = assertNotNull(Pitch.detect(built.pads[2]!!.snip), "the fifth pad has a pitch")
        assertTrue(abs(root.hz - Snap.frequencyFor(0f)) < 3f, "root at ${root.hz} Hz, wanted A2")
        val semitonesApart = 12.0 * ln(fifth.hz.toDouble() / root.hz) / ln(2.0)
        assertTrue(abs(semitonesApart - 7.0) < 0.3, "fifth is $semitonesApart semitones above the root")
    }

    @Test
    fun `a flat photo still fills its chord rather than refusing`() {
        val built = PhotoChord.build(Photo.grey(32, 32) { _, _ -> 0.3f }, "Wall")
        assertEquals(PhotoChord.Chord(0, PhotoChord.Quality.MINOR), built.chord)
        assertTrue(built.slots.isNotEmpty())
        assertTrue(built.slots.all { built.pads[it - 1]!!.snip.peak() > 0f })
    }
}
