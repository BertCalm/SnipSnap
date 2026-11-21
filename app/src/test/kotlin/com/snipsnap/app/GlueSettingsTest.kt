package com.snipsnap.app

import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitGlue
import com.snipsnap.kit.KitPad
import com.snipsnap.shell.PadHit
import com.snipsnap.shell.StarterKits
import com.snipsnap.synth.BoroughKits
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The kit-to-glue mapping the pads' player publishes when a kit loads (docs/BOROUGH.md, round 3.1): a kit's
 * [KitGlue] on, with the live calibration in its trim; [GlueSettings.OFF] for a kit without one.
 */
class GlueSettingsTest {

    private fun near(expected: Float, actual: Float, eps: Float = 1e-4f) =
        assertTrue(abs(expected - actual) < eps, "expected $expected, got $actual")

    private val pads = listOf(KitPad(1, "A01_Kick_01.wav"))

    @Test
    fun `a kit without glue loads with the glue off, bit for bit the old mix`() {
        assertEquals(GlueSettings.OFF, GlueSettings.forKit(Kit("Plain", pads)))
        assertFalse(GlueSettings.OFF.enabled)
        assertEquals(0f, GlueSettings.OFF.inputTrimDb)
        assertFalse(GlueSettings.OFF.saturate)
    }

    @Test
    fun `the live calibration is the default pad's 3 dB, taken back`() {
        near(3.0f, GlueSettings.LIVE_TRIM_CALIBRATION_DB)
        assertEquals((-PadHit.DEFAULT_HIT_GAIN_DB).toFloat(), GlueSettings.LIVE_TRIM_CALIBRATION_DB)
        // A default pad at full velocity, centred, really is that far under its sample.
        val hit = PadHit.resolve(pads.single(), 1f, 0) { 100L }!!
        near(1f, hit.gainLeft * Math.pow(10.0, GlueSettings.LIVE_TRIM_CALIBRATION_DB / 20.0).toFloat(), 1e-5f)
    }

    @Test
    fun `a kit's glue maps field for field, on, with the calibration in the trim`() {
        val glue = KitGlue(inputTrimDb = 0.79f, saturationDb = 2.7f, thresholdDbfs = -10f, ratio = 4f, makeupDb = 0f,
            limiterDriveDb = 7.13f, ceilingDbfs = -1f)
        val s = GlueSettings.forKit(Kit("Stoop", pads, glue = glue))
        assertTrue(s.enabled)
        near(0.79f + 3.0f, s.inputTrimDb)
        assertTrue(s.saturate)
        assertEquals(2.7f, s.saturationDb)
        assertEquals(-10f, s.thresholdDbfs)
        assertEquals(4f, s.ratio)
        assertEquals(0f, s.makeupDb)
        assertEquals(7.13f, s.driveDb)
        assertEquals(-1f, s.ceilingDbfs)
    }

    @Test
    fun `no saturator in the kit is no saturator live`() {
        val s = GlueSettings.of(KitGlue(inputTrimDb = -2.37f, saturationDb = null, limiterDriveDb = 7f))
        assertTrue(s.enabled)
        assertFalse(s.saturate)
        near(-2.37f + 3.0f, s.inputTrimDb)
    }

    @Test
    fun `a trim at the edge of the range stays inside the bus's range`() {
        assertEquals(24f, GlueSettings.of(KitGlue(inputTrimDb = 23f, saturationDb = null)).inputTrimDb)
        near(-21f, GlueSettings.of(KitGlue(inputTrimDb = -24f, saturationDb = null)).inputTrimDb)
    }

    @Test
    fun `every BOROUGH starter maps to the owner's chain and every other starter to off`() {
        for (k in BoroughKits.Kit.entries) {
            val starter = StarterKits.byId(k.id)!!
            val s = GlueSettings.of(starter.glue!!)
            assertTrue(s.enabled, k.id)
            assertEquals(k != BoroughKits.Kit.RIMS, s.saturate, k.id)
            near(BoroughKits.glueOf(k).inputTrimDb + 3.0f, s.inputTrimDb)
        }
        assertTrue(StarterKits.ALL.filter { it.glue == null }.size > 100)
    }

    @Test
    fun `out of range settings are refused`() {
        assertFailsWith<IllegalArgumentException> { GlueSettings(inputTrimDb = 25f) }
        assertFailsWith<IllegalArgumentException> { GlueSettings(saturationDb = 12.5f) }
        assertFailsWith<IllegalArgumentException> { GlueSettings(saturationDb = Float.NaN) }
    }
}
