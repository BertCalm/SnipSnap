package com.snipsnap.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AxesProjectorTest {

    @Test
    fun `every Axis names a distinct, in-range dimension of Similar vector`() {
        assertEquals(Similar.DIMENSIONS, Axis.entries.size, "an Axis for every vector dimension, and no more")
        val indices = Axis.entries.map { it.index }
        assertEquals(indices.toSet().size, indices.size, "no two axes read the same dimension")
        assertTrue(indices.all { it in 0 until Similar.DIMENSIONS })
    }

    @Test
    fun `x reads straight off the named dimension, y sits at the centre`() {
        val vector = FloatArray(Similar.DIMENSIONS) { it / (Similar.DIMENSIONS - 1).toFloat() }
        for (axis in Axis.entries) {
            val (x, y) = AxesProjector(axis).project(vector)
            assertEquals(vector[axis.index], x, "axis $axis should read its own dimension")
            assertEquals(0.5f, y, "y is left at the map's centre; the caller maps loudness onto it")
        }
    }

    @Test
    fun `the default axis is CENTROID`() {
        val vector = FloatArray(Similar.DIMENSIONS) { 0.3f }
        vector[Axis.CENTROID.index] = 0.8f
        val (x, _) = AxesProjector().project(vector)
        assertEquals(0.8f, x)
    }

    @Test
    fun `out-of-range vector values still clamp into 0 to 1`() {
        val vector = FloatArray(Similar.DIMENSIONS) { -2f }
        val (x, _) = AxesProjector(Axis.ROLLOFF).project(vector)
        assertEquals(0f, x)
        val over = FloatArray(Similar.DIMENSIONS) { 5f }
        val (x2, _) = AxesProjector(Axis.ROLLOFF).project(over)
        assertEquals(1f, x2)
    }
}
