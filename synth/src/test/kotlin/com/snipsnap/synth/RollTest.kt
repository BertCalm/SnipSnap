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
        // The second repeat starts with the same audio the first one did.
        assertTrue(
            abs(out.samples[period] - out.samples[0] * Roll.DECAY) < 0.05f,
            "the second repeat is not the head again, decayed",
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
