package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SurfaceKeyTest {

    /** The mask spelled from the scale's own intervals, so a reader can check it against Scales.kt by eye. */
    private fun bits(vararg degrees: Int) = degrees.fold(0) { acc, d -> acc or (1 shl d) }

    @Test
    fun `a scale's mask has exactly its degrees set`() {
        assertEquals(bits(0, 2, 4, 5, 7, 9, 11), SurfaceKey.mask(Scale.MAJOR))
        assertEquals(bits(0, 2, 3, 5, 7, 8, 10), SurfaceKey.mask(Scale.MINOR))
        assertEquals(bits(0, 3, 5, 7, 10), SurfaceKey.mask(Scale.MINOR_PENTATONIC))
        assertEquals(bits(0, 2, 4, 7, 9), SurfaceKey.mask(Scale.MAJOR_PENTATONIC))
        assertEquals(SurfaceKey.CHROMATIC_MASK, SurfaceKey.mask(Scale.CHROMATIC))
        // Twelve bits and no more: the engine masks to twelve, and a
        // thirteenth would silently vanish rather than be refused.
        for (scale in Scale.entries) assertTrue(SurfaceKey.mask(scale) in 1..SurfaceKey.CHROMATIC_MASK)
    }

    @Test
    fun `a key and a found note snap to real notes in that key`() {
        val snap = SurfaceKey.of(KeySpec(9, Scale.MINOR), sourceMidi = 70.4f)
        assertEquals(9, snap.rootSemitone)
        assertEquals(SurfaceKey.mask(Scale.MINOR), snap.scaleMask)
        assertEquals(70.4f, snap.sourceMidi)
    }

    @Test
    fun `a key with no confident note becomes intervals from the pad itself`() {
        // Root 0 and source 0 together: the degrees the engine allows are
        // then exactly the scale's intervals above wherever the pad sits,
        // so a drum still steps through the key's shape up the pad.
        val snap = SurfaceKey.of(KeySpec(9, Scale.MINOR), sourceMidi = null)
        assertEquals(0, snap.rootSemitone)
        assertEquals(SurfaceKey.mask(Scale.MINOR), snap.scaleMask)
        assertEquals(0f, snap.sourceMidi)
    }

    @Test
    fun `no key is chromatic, around the pad's own note when it has one`() {
        assertEquals(SurfaceKey.Snap.CHROMATIC, SurfaceKey.of(null, null))
        val around = SurfaceKey.of(null, sourceMidi = 61.2f)
        assertEquals(SurfaceKey.CHROMATIC_MASK, around.scaleMask)
        assertEquals(61.2f, around.sourceMidi)
    }

    @Test
    fun `a pad's own note is found on a tone and refused on silence`() {
        val rate = 48_000
        val tone = FloatArray(rate) { i -> (0.8 * sin(2.0 * PI * 440.0 * i / rate)).toFloat() }
        val midi = SurfaceKey.sourceMidi(Snip(tone, 1, rate))
        assertNotNull(midi)
        assertTrue(abs(midi - 69f) < 0.15f, "A4 should read as MIDI 69, got $midi")
        assertNull(SurfaceKey.sourceMidi(Snip(FloatArray(rate), 1, rate)))
    }

    @Test
    fun `refusals are in words`() {
        assertFailsWith<IllegalArgumentException> { SurfaceKey.Snap(12, SurfaceKey.CHROMATIC_MASK, 0f) }
        assertFailsWith<IllegalArgumentException> { SurfaceKey.Snap(0, 0, 0f) }
        assertFailsWith<IllegalArgumentException> { SurfaceKey.Snap(0, 0x1000, 0f) }
        assertFailsWith<IllegalArgumentException> { SurfaceKey.Snap(0, SurfaceKey.CHROMATIC_MASK, Float.NaN) }
    }
}
