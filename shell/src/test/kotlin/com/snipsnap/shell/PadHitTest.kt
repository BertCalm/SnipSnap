package com.snipsnap.shell

import com.snipsnap.kit.ChainInfo
import com.snipsnap.kit.ChainZone
import com.snipsnap.kit.KitLayer
import com.snipsnap.kit.KitPad
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PadHitTest {

    private fun near(expected: Double, actual: Double, eps: Double = 1e-6) =
        assertTrue(abs(expected - actual) < eps, "expected $expected, got $actual")

    private val frames = mapOf("kick.wav" to 4_000L, "soft.wav" to 3_000L, "hard.wav" to 3_500L, "loop.wav" to 10_000L)
    private val framesOf: (String) -> Long? = { frames[it] }

    @Test
    fun `a plain pad plays the whole sample at its level and pan`() {
        val pad = KitPad(1, "kick.wav", level = 0.8f, pan = 0.5f)
        val hit = PadHit.resolve(pad, 1f, 0, framesOf)!!
        assertEquals("kick.wav", hit.sampleFile)
        assertEquals(0L, hit.startFrame)
        assertEquals(4_000L, hit.endFrameExclusive)
        near(0.8, hit.gainLeft.toDouble()); near(0.8, hit.gainRight.toDouble())
        near(1.0, hit.pitchRatio)
    }

    @Test
    fun `pan fades the far side and velocity scales both`() {
        val (l, r) = PadHit.gains(level = 1f, pan = 0f, velocity = 1f)
        near(1.0, l.toDouble()); near(0.0, r.toDouble())
        val (l2, r2) = PadHit.gains(level = 1f, pan = 0.75f, velocity = 0.5f)
        near(0.25, l2.toDouble()); near(0.5, r2.toDouble())
        assertEquals(127, PadHit.midiVelocity(1f))
        assertEquals(64, PadHit.midiVelocity(0.5f))
        assertEquals(0, PadHit.midiVelocity(-3f))
    }

    @Test
    fun `tune is a ratio`() {
        near(2.0, PadHit.pitchRatio(12, 0))
        near(0.5, PadHit.pitchRatio(-12, 0))
        near(Math.pow(2.0, 0.5 / 12.0), PadHit.pitchRatio(0, 50))
        val pad = KitPad(1, "kick.wav", tuneCoarse = 7, tuneFine = -10)
        near(Math.pow(2.0, 6.9 / 12.0), PadHit.resolve(pad, 1f, 0, framesOf)!!.pitchRatio)
    }

    @Test
    fun `velocity picks the layer and the loudest is the fallback`() {
        val pad = KitPad(2, "hard.wav", velocityLayers = listOf(KitLayer("soft.wav", 0, 63), KitLayer("hard.wav", 64, 127)))
        assertEquals("soft.wav", PadHit.resolve(pad, 0.2f, 0, framesOf)!!.sampleFile)
        assertEquals("hard.wav", PadHit.resolve(pad, 0.9f, 0, framesOf)!!.sampleFile)
        assertEquals(3_000L, PadHit.resolve(pad, 0.2f, 0, framesOf)!!.endFrameExclusive)
        // A gap in the layers (allowed: 0..63 then 70..127) falls to the loudest.
        val gapped = KitPad(2, "hard.wav", velocityLayers = listOf(KitLayer("soft.wav", 0, 63), KitLayer("hard.wav", 70, 127)))
        assertEquals("hard.wav", PadHit.resolve(gapped, 0.52f, 0, framesOf)!!.sampleFile) // 66
    }

    @Test
    fun `a chain steps a slice per hit and the last slice runs to the end`() {
        val pad = KitPad(3, "loop.wav", chain = ChainInfo(listOf(0L, 2_000L, 4_000L, 6_000L), cycle = 3))
        val hits = (0..3).map { PadHit.resolve(pad, 1f, it, framesOf)!! }
        assertEquals(listOf(0L, 2_000L, 4_000L, 0L), hits.map { it.startFrame })
        assertEquals(listOf(2_000L, 4_000L, 6_000L, 2_000L), hits.map { it.endFrameExclusive })
        val whole = KitPad(3, "loop.wav", chain = ChainInfo(listOf(0L, 2_000L, 4_000L, 6_000L), cycle = 4))
        assertEquals(10_000L, PadHit.resolve(whole, 1f, 3, framesOf)!!.endFrameExclusive)
    }

    @Test
    fun `a chain grid anchors each velocity zone at its own slice`() {
        val pad = KitPad(
            3, "loop.wav",
            chain = ChainInfo(
                listOf(0L, 2_000L, 4_000L, 6_000L), cycle = 2,
                zones = listOf(ChainZone(0, 63, baseSlice = 0, cycle = 2), ChainZone(64, 127, baseSlice = 2, cycle = 2)),
            ),
        )
        assertEquals(0L, PadHit.resolve(pad, 0.1f, 0, framesOf)!!.startFrame)
        assertEquals(2_000L, PadHit.resolve(pad, 0.1f, 1, framesOf)!!.startFrame)
        assertEquals(4_000L, PadHit.resolve(pad, 1f, 0, framesOf)!!.startFrame)
        assertEquals(6_000L, PadHit.resolve(pad, 1f, 1, framesOf)!!.startFrame)
        assertEquals(4_000L, PadHit.resolve(pad, 1f, 2, framesOf)!!.startFrame)
    }

    @Test
    fun `a sample the engine never loaded is no hit, and refusals are in words`() {
        assertNull(PadHit.resolve(KitPad(1, "missing.wav"), 1f, 0, framesOf))
        assertNull(PadHit.resolve(KitPad(1, "kick.wav"), 1f, 0) { 0L })
        assertFailsWith<IllegalArgumentException> { PadHit.resolve(KitPad(1, "kick.wav"), 1.5f, 0, framesOf) }
        assertFailsWith<IllegalArgumentException> { PadHit.resolve(KitPad(1, "kick.wav"), 1f, -1, framesOf) }
    }
}
