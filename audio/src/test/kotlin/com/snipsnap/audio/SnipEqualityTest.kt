package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Snip.equals is what Compose's mutableStateOf and remember keys use to
 * decide whether new audio arrived. A re-render that happens to keep the
 * same length must not compare equal to the audio it replaces, or the UI
 * keeps auditioning and drawing the old render.
 */
class SnipEqualityTest {

    @Test
    fun `a re-render of the same length is not equal to the old one`() {
        val old = Snip(FloatArray(4) { 0.1f * it }, 1, 44_100)
        val new = Snip(FloatArray(4) { -0.1f * it }, 1, 44_100)
        assertNotEquals(old, new)
    }

    @Test
    fun `identical content in a different array is still a different snip`() {
        val a = Snip(floatArrayOf(0.25f, -0.25f), 1, 44_100)
        val b = Snip(floatArrayOf(0.25f, -0.25f), 1, 44_100)
        assertNotEquals(a, b)
    }

    @Test
    fun `a copy shares the array and stays equal with the same hash`() {
        val a = Snip(floatArrayOf(0.5f, -0.5f, 0.5f, -0.5f), 2, 48_000)
        val b = a.copy()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `the same array under a different format is not equal`() {
        val samples = floatArrayOf(0.5f, -0.5f)
        assertNotEquals(Snip(samples, 1, 44_100), Snip(samples, 2, 44_100))
        assertNotEquals(Snip(samples, 1, 44_100), Snip(samples, 1, 48_000))
    }
}
