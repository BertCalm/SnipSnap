package com.snipsnap.shell

import com.snipsnap.kit.ChainInfo
import com.snipsnap.kit.ChainZone
import com.snipsnap.kit.KitLayer
import com.snipsnap.kit.KitPad
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StrikeStripTest {
    @Test
    fun `disabled strip preserves both velocities regardless of its saved settings`() {
        for (velocity in listOf(0.2f, 0.6f, 1f)) {
            assertEquals(StrikeStrip.Hit(velocity, velocity), StrikeStrip.hit(velocity, false, 0f, false))
        }
    }

    @Test
    fun `every existing layer is reachable while pad level and pan stay fixed`() {
        val pad = KitPad(1, "hard.wav", level = 0.8f, pan = 0.75f, velocityLayers = listOf(
            KitLayer("soft.wav", 1, 31), KitLayer("mid.wav", 32, 63),
            KitLayer("firm.wav", 64, 95), KitLayer("hard.wav", 96, 127),
        ))
        val hits = (0..100).map { step ->
            val strike = StrikeStrip.hit(1f, true, step / 100f, false)
            PadHit.resolve(pad, strike.velocity, 0, strike.gainVelocity) { 100L }!!
        }
        assertEquals(pad.velocityLayers.map { it.sampleFile }.toSet(), hits.map { it.sampleFile }.toSet())
        assertTrue(hits.all { it.gainLeft == 0.4f && it.gainRight == 0.8f })
    }

    @Test
    fun `linked volume varies gain without changing which layer the strike selects`() {
        val pad = KitPad(1, "hard.wav", level = 1f, velocityLayers = listOf(KitLayer("soft.wav", 1, 63), KitLayer("hard.wav", 64, 127)))
        fun hit(volume: Boolean): PadHit.Hit {
            val strike = StrikeStrip.hit(1f, true, 0f, volume)
            return PadHit.resolve(pad, strike.velocity, 0, strike.gainVelocity) { 100L }!!
        }
        assertEquals("soft.wav", hit(false).sampleFile)
        assertEquals(hit(false).sampleFile, hit(true).sampleFile)
        assertEquals(1f, hit(false).gainLeft)
        assertEquals(PadHit.SOFTEST, hit(true).gainLeft)
    }

    @Test
    fun `chain zone also follows strike rather than the independent volume`() {
        val pad = KitPad(1, "chain.wav", level = 1f, chain = ChainInfo(
            listOf(0L, 100L), cycle = 2,
            zones = listOf(ChainZone(0, 63, 0, 1), ChainZone(64, 127, 1, 1)),
        ))
        val soft = PadHit.resolve(pad, 0.2f, 0, 1f) { 200L }!!
        val hard = PadHit.resolve(pad, 1f, 0, 0.2f) { 200L }!!
        assertEquals(0L, soft.startFrame)
        assertEquals(100L, hard.startFrame)
        assertEquals(1f, soft.gainLeft)
        assertEquals(0.2f, hard.gainLeft)
    }

    @Test
    fun `fader direction clamps and unknown geometry is centred`() {
        assertEquals(1f, StrikeStrip.strengthAt(-5f, 100f))
        assertEquals(0f, StrikeStrip.strengthAt(105f, 100f))
        assertEquals(0.5f, StrikeStrip.strengthAt(50f, 100f))
        assertEquals(0.5f, StrikeStrip.strengthAt(0f, 0f))
        assertEquals(0.5f, StrikeStrip.strengthAt(Float.NaN, 100f))
        assertEquals(PadHit.CENTER, StrikeStrip.hit(1f, true, Float.NaN, false).velocity)
    }

    @Test
    fun `invalid independent gain is refused before resolving a sample`() {
        for (gain in listOf(-1f, 2f, Float.NaN)) {
            val error = assertFailsWith<IllegalArgumentException> { PadHit.resolve(KitPad(1, "a.wav"), 1f, 0, gain) { 100L } }
            assertTrue(error.message.orEmpty().startsWith("gain velocity"))
        }
    }
}
