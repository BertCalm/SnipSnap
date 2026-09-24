package com.snipsnap.synth

import com.snipsnap.audio.Pitch
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TelephoneTest {

    private val rate = Dsp.RATE

    private fun tone(hz: Float, seconds: Float = 1f, until: Float = seconds): Snip {
        val n = (seconds * rate).toInt()
        val stop = (until * rate).toInt()
        return Snip(FloatArray(n) { i -> if (i < stop) 0.5f * sin(2.0 * PI * hz * i / rate).toFloat() else 0f }, 1, rate)
    }

    /** Single-bin Goertzel energy at [hz], the same local probe SpectrogramTest uses. */
    private fun energyAt(samples: FloatArray, hz: Float): Double {
        val n = samples.size
        val k = (0.5 + n * hz / rate).toInt()
        val coeff = 2.0 * cos(2.0 * PI * k / n)
        var q1 = 0.0
        var q2 = 0.0
        for (s in samples) {
            val q0 = coeff * q1 - q2 + s
            q2 = q1
            q1 = q0
        }
        return q1 * q1 + q2 * q2 - q1 * q2 * coeff
    }

    /** The row [Spectrogram.read] plays [hz] from, mirrored as SpectrogramTest mirrors it. */
    private fun rowFor(hz: Float, height: Int): Int {
        val high = ln(hz / 40f) / ln((rate / 2f) / 40f)
        return Math.round((1f - high) * (height - 1)).coerceIn(0, height - 1)
    }

    private fun rowBrightness(photo: Photo, y: Int): Float = (0 until photo.width).map { photo.luminance(it, y) }.average().toFloat()

    @Test
    fun `a tone paints its brightest row where read would play that tone from`() {
        val photo = Spectrogram.portrait(tone(1000f))
        val brightest = (0 until photo.height).maxBy { rowBrightness(photo, it) }
        assertTrue(abs(brightest - rowFor(1000f, photo.height)) <= 1, "brightest row $brightest, expected ${rowFor(1000f, photo.height)}")
    }

    @Test
    fun `silence paints black`() {
        val photo = Spectrogram.portrait(Snip(FloatArray(rate / 2), 1, rate))
        assertTrue(photo.argb.all { (it and 0xFFFFFF) == 0 }, "a silent sound has nothing to paint")
    }

    @Test
    fun `a tone that stops halfway lights only the left half`() {
        val photo = Spectrogram.portrait(tone(500f, seconds = 1f, until = 0.5f), width = 64)
        val row = rowFor(500f, photo.height)
        val left = (4 until 28).map { photo.luminance(it, row) }.average()
        val right = (36 until 60).map { photo.luminance(it, row) }.average()
        assertTrue(left > 0.5 && right < 0.05, "left=$left right=$right")
    }

    @Test
    fun `a steady tone reaches both edges, with no dark margin read would play as extra time`() {
        val photo = Spectrogram.portrait(tone(500f), width = 128)
        val row = rowFor(500f, photo.height)
        val middle = photo.luminance(64, row)
        // An edge frame is centred on the first (or last) sample, so half its
        // window is past the audio and it reads somewhat darker; the padding
        // frames Spectral adds would read black.
        for (x in listOf(0, photo.width - 1)) {
            assertTrue(photo.luminance(x, row) > middle * 0.5f, "column $x at ${photo.luminance(x, row)} against the middle's $middle")
        }
    }

    @Test
    fun `the tint carries colour without changing the brightness a reader sees`() {
        for (hue in listOf(0f, 45f, 120f, 200f, 270f, 330f)) {
            for (lum in listOf(0f, 0.05f, 0.3f, 0.6f, 0.9f, 1f)) {
                val tinted = Photo.tint(Photo.hue(hue), lum)
                assertTrue(abs(Photo.luminance(tinted) - lum) < 1.5f / 255f, "hue $hue at $lum came out ${Photo.luminance(tinted)}")
            }
        }
    }

    @Test
    fun `SNAP reads a tone's portrait as that tone's own note`() {
        // A3 is 12 semitones above SNAP's A2 root: the middle of its TUNE knob.
        val photo = Spectrogram.portrait(tone(220f))
        val tune = Snap.macrosFrom(Snap.look(photo)).getValue("TUNE")
        val semitones = tune * Snap.TUNE_SEMITONES
        assertTrue(abs(semitones - 12f) <= 1f, "a 220 Hz portrait reads as $semitones semitones above A2")
    }

    @Test
    fun `one pass keeps the pitch`() {
        val heard = Telephone.pass(tone(440f)).sound
        assertEquals(tone(440f).frameCount, heard.frameCount, "a pass keeps the sound's length")
        val pitch = assertNotNull(Pitch.detect(heard, fromSec = 0.2f), "the pass still has a pitch")
        assertTrue(abs(pitch.hz - 440f) < 440f * 0.03f, "heard ${pitch.hz} Hz after one pass")
        assertTrue(energyAt(heard.samples, 440f) > energyAt(heard.samples, 1320f) * 5)
    }

    @Test
    fun `the line is the same whispers for the same seed, and every generation drifts`() {
        val start = PhotoChord.build(Photo.grey(256, 8) { x, _ -> 0.6f + 0.3f * sin(2.0 * PI * x / 256).toFloat() }, "T").pads[0]!!.snip
        val a = Telephone.chain(start, 3, width = 96, height = 96)
        val b = Telephone.chain(start, 3, width = 96, height = 96)
        assertEquals(3, a.size)
        for (g in a.indices) assertTrue(a[g].sound.samples.contentEquals(b[g].sound.samples), "generation $g repeats")
        var previous = start.samples
        for ((g, generation) in a.withIndex()) {
            assertTrue(generation.sound.peak() > 0f, "generation $g is still heard")
            assertTrue(!generation.sound.samples.contentEquals(previous), "generation $g changed something")
            previous = generation.sound.samples
        }
    }

    @Test
    fun `the line refuses no generations, too many, and an empty sound`() {
        val start = tone(440f, seconds = 0.2f)
        assertFailsWith<IllegalArgumentException> { Telephone.chain(start, 0) }
        assertFailsWith<IllegalArgumentException> { Telephone.chain(start, Telephone.MAX_GENERATIONS + 1) }
        assertFailsWith<IllegalArgumentException> { Telephone.pass(Snip(FloatArray(0), 1, rate)) }
    }
}
