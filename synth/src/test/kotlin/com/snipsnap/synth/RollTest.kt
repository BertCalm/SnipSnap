package com.snipsnap.synth

import com.snipsnap.audio.Snip
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RollTest {

    private val kick = Thump.render(ThumpVoice.KICK)

    @Test
    fun `AMOUNT zero is a copy`() {
        val out = Roll.roll(kick, 92f, "1/16", 0f)
        assertTrue(out.samples.contentEquals(kick.samples), "ROLL at 0 is not a copy")
    }

    @Test
    fun `a roll keeps the hit's length and repeats its head`() {
        val out = Roll.roll(kick, 92f, "1/16", 1f)
        assertEquals(kick.frameCount, out.frameCount, "ROLL changed the hit's length")
        val period = (Wobble.periodSec(92f, "1/16") * kick.sampleRate).toInt()
        val head = out.samples[0]
        assertTrue(abs(head) > 1e-6f, "the head is silent, the ratio check is meaningless")
        // Each repeat is the head again, decayed by DECAY compounding - a
        // relative ratio, not an absolute difference against a small sample
        // value, so a regression that drops the decay entirely cannot pass.
        assertTrue(
            abs(out.samples[period] / head - Roll.DECAY) < 0.02f,
            "first repeat is not the head decayed: ${out.samples[period] / head} vs ${Roll.DECAY}",
        )
        assertTrue(
            abs(out.samples[2 * period] / head - Roll.DECAY * Roll.DECAY) < 0.02f,
            "second repeat does not compound the decay: ${out.samples[2 * period] / head} vs ${Roll.DECAY * Roll.DECAY}",
        )
    }

    @Test
    fun `a hit shorter than one division is refused in words`() {
        val tiny = Snip(FloatArray(64), 1, 44_100)
        val why = Roll.refusal(tiny, 92f, "1/4")
        assertTrue(why != null && why.contains("1/4"), "the refusal does not name the division: $why")
        assertNull(Roll.refusal(kick, 92f, "1/16"), "a long enough hit was refused")
    }

    @Test
    fun `a roll is the same roll every time`() {
        val a = Roll.roll(kick, 92f, "1/16", 1f)
        val b = Roll.roll(kick, 92f, "1/16", 1f)
        assertTrue(a.samples.contentEquals(b.samples), "ROLL is not deterministic")
    }
}
