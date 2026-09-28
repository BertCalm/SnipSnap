package com.snipsnap.synth

import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SilkScalesTest {

    @Test
    fun `every row starts at 0 and stays inside its own period`() {
        for (scale in SilkScales.TABLE) {
            assertEquals(0f, scale.cents.first(), "a scale's first degree must be the root")
            for (c in scale.cents) {
                assertTrue(c >= 0f && c < scale.period, "degree $c outside [0, ${scale.period})")
            }
        }
    }

    @Test
    fun `snap at the ends returns the first and last row`() {
        assertSame(SilkScales.CHROMATIC, SilkScales.snap(0f))
        assertSame(SilkScales.BOHLEN_PIERCE, SilkScales.snap(1f))
    }

    @Test
    fun `snap lands on each voice's own default row`() {
        val last = (SilkScales.TABLE.size - 1).toFloat()
        assertSame(SilkScales.RAST, SilkScales.snap(SilkScales.TABLE.indexOf(SilkScales.RAST) / last))
        assertSame(SilkScales.SHUR, SilkScales.snap(SilkScales.TABLE.indexOf(SilkScales.SHUR) / last))
        assertSame(SilkScales.PENTATONIC, SilkScales.snap(SilkScales.TABLE.indexOf(SilkScales.PENTATONIC) / last))
        assertSame(SilkScales.MIYAKO_BUSHI, SilkScales.snap(SilkScales.TABLE.indexOf(SilkScales.MIYAKO_BUSHI) / last))
    }

    @Test
    fun `TUNE 0 is the root, TUNE 1 is two periods up, for every scale`() {
        for (scale in SilkScales.TABLE) {
            val root = 220f
            assertEquals(root, SilkScales.frequencyFor(root, scale, 0f), root * 1e-5f)
            val twoPeriodsUp = root * 2f.pow(2f * scale.period / 1200f)
            assertEquals(twoPeriodsUp, SilkScales.frequencyFor(root, scale, 1f), twoPeriodsUp * 1e-5f)
        }
    }

    @Test
    fun `CHROMATIC reproduces PLUCK's own semitone formula bit for bit`() {
        val root = 196f // an arbitrary root, PLUCK's own BANJO root - the value doesn't matter, only that both sides use the same one
        for (tune in listOf(0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 1f)) {
            val semitone = Math.round(tune.coerceIn(0f, 1f) * Pluck.TUNE_SEMITONES)
            val expected = root * 2f.pow(semitone / 12f)
            val actual = SilkScales.frequencyFor(root, SilkScales.CHROMATIC, tune)
            assertEquals(expected, actual, "tune=$tune: expected PLUCK's own formula bit for bit")
        }
    }

    @Test
    fun `INFLECT is +-50 cents, linear, with an exact detent at centre`() {
        val root = 300f
        val degree = SilkScales.frequencyFor(root, SilkScales.RAST, 0f, 0.5f)
        val minus50 = SilkScales.frequencyFor(root, SilkScales.RAST, 0f, 0f)
        val plus50 = SilkScales.frequencyFor(root, SilkScales.RAST, 0f, 1f)
        assertEquals(degree * 2f.pow(-50f / 1200f), minus50, degree * 1e-6f)
        assertEquals(degree * 2f.pow(50f / 1200f), plus50, degree * 1e-6f)

        // The detent: every inflect in [0.48, 0.52] is exactly the degree, no tolerance.
        for (inflect in listOf(0.48f, 0.49f, 0.5f, 0.51f, 0.52f)) {
            assertEquals(degree, SilkScales.frequencyFor(root, SilkScales.RAST, 0f, inflect), 0f, "inflect=$inflect must be exactly the degree")
        }

        // Just outside the detent band, the offset is not zeroed.
        assertTrue(abs(SilkScales.frequencyFor(root, SilkScales.RAST, 0f, 0.5301f) - degree) > 1e-4f, "0.5301 is outside the +-0.02 detent band and must not read as the bare degree")
    }

    @Test
    fun `INFLECT defaults to the degree exactly`() {
        val root = 150f
        for (scale in SilkScales.TABLE) {
            for (tune in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
                assertEquals(
                    SilkScales.frequencyFor(root, scale, tune, 0.5f),
                    SilkScales.frequencyFor(root, scale, tune),
                    0f,
                )
            }
        }
    }

    @Test
    fun `BOHLEN_PIERCE's period is a tritave, not an octave`() {
        // 3/1 above the root, exactly - the point a period-vs-1200 mixup would miss.
        val root = 100f
        val tritaveUp = SilkScales.frequencyFor(root, SilkScales.BOHLEN_PIERCE, 0.5f)
        assertEquals(3f, tritaveUp / root, 1e-4f, "one period of BOHLEN_PIERCE must be a 3:1 tritave")
    }
}
