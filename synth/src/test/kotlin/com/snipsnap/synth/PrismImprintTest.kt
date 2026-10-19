package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PrismImprintTest {
    @Test fun measuredColourIsLevelIndependentAndCanBeSavedWithoutDonor() {
        val frequencies = List(Prism.PARTIALS) { (it + 1) * 220f }
        val samples = FloatArray(44_100) { n ->
            (.2 * sin(2 * PI * 220 * n / 44_100) + .5 * sin(2 * PI * 1760 * n / 44_100)).toFloat()
        }
        val endpoint = PrismImprint.endpoint(Snip(samples, 1, 44_100), frequencies)
        val quieter = PrismImprint.endpoint(Snip(FloatArray(samples.size) { samples[it] * .1f }, 1, 44_100), frequencies)
        assertEquals(Prism.PARTIALS, endpoint.size)
        assertTrue(endpoint.all { it.isFinite() && it in 0f..1f })
        assertTrue(endpoint[0] >= .12f)
        assertTrue(endpoint[7] > endpoint[3] * 2f, "the donor's upper colour must survive the smoothing")
        assertTrue(endpoint.indices.all { kotlin.math.abs(endpoint[it] - quieter[it]) < 1e-4f })
    }

    @Test fun silenceAndInvalidCarrierMapsAreRefused() {
        val silence = Snip(FloatArray(1024), 1, 44_100)
        assertFailsWith<IllegalArgumentException> { PrismImprint.endpoint(silence, List(32) { 220f }) }
        assertFailsWith<IllegalArgumentException> { PrismImprint.endpoint(silence, listOf(220f)) }
    }
}
