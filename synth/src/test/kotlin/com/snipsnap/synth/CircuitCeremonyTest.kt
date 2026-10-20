package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CircuitCeremonyTest {
    @Test
    fun `one minute preserves nineteen approved figures and leaves a passive tail`() {
        assertEquals(60.0, CircuitCeremony.seconds)
        val phrase = phrase()
        assertEquals(19, phrase.cellCount)
        assertEquals(2.92, phrase.cellSeconds, 1e-12)
        assertEquals(4.0 / 2.92, CircuitCeremony.paceHz, 1e-12)
        assertEquals(55.76, CircuitCeremony.phraseSeconds, 1e-12)
        assertTrue(CircuitCeremony.seconds - CircuitCeremony.phraseSeconds > 4.0)

        val stages = CircuitCeremony.stages
        assertEquals(0.0, stages.first().startSeconds)
        assertEquals(CircuitCeremony.seconds, stages.last().endSeconds)
        for (stage in stages) assertTrue(stage.endSeconds > stage.startSeconds)
        for ((before, after) in stages.zipWithNext()) assertEquals(before.endSeconds, after.startSeconds)
        assertEquals(CircuitCeremony.phraseSeconds, stages.last().startSeconds)
    }

    @Test
    fun `shared input intensity has one gradual crest and ends before the residual tail`() {
        val expected = listOf(
            0.0 to .42, 8.92 to .58, 23.52 to .70, 38.12 to .82,
            42.5 to 1.0, 46.88 to .80, 52.72 to .54, CircuitCeremony.phraseSeconds to .32,
        )
        for ((time, gain) in expected) assertEquals(gain, CircuitCeremony.gain(time), 1e-12)
        for ((before, after) in expected.zipWithNext()) {
            var previous = before.second
            for (step in 1..20) {
                val time = before.first + (after.first - before.first) * step / 20.0
                val gain = CircuitCeremony.gain(time)
                assertTrue(gain in 0.0..1.0)
                if (after.second > before.second) assertTrue(gain >= previous - 1e-12)
                else assertTrue(gain <= previous + 1e-12)
                previous = gain
            }
        }
        assertEquals(0.0, CircuitCeremony.gain(-.01))
        assertEquals(0.0, CircuitCeremony.gain(CircuitCeremony.phraseSeconds + .001))
        assertEquals(0.0, CircuitCeremony.gain(CircuitCeremony.seconds))
    }

    @Test
    fun `authored omissions leave opening calls clay foundation and later complete motifs`() {
        val events = phrase().targets(1.0)
        for (cell in 0 until CircuitCeremony.cells) {
            val beginning = CircuitPhrase.ORIGIN + cell * CircuitCeremony.cellSeconds
            val own = events.filter { it.timeSeconds >= beginning && it.timeSeconds < beginning + CircuitCeremony.cellSeconds }
            val permitted = own.filter(CircuitCeremony::permits)
            assertTrue(permitted.any { it.source == 4 && it.reason == "opening-wood" }, "cell $cell lost its opening")
            assertTrue(permitted.any { it.source == 5 && it.reason == "settling" }, "cell $cell lost its clay foundation")
            assertTrue(own.filter { it.source == 1 }.all(CircuitCeremony::permits), "cell $cell lost its tube cues")
            assertEquals(cell !in setOf(0, 1, 9, 11, 18), permitted.any { it.source == 6 }, "cell $cell affirmation")
            if (cell in setOf(0, 1, 9, 11) || cell >= 16) {
                assertFalse(permitted.any { it.reason == "weak-wood" || it.reason == "rising-support" })
            }
            if (cell >= 16) assertFalse(permitted.any { it.reason == "weak-support" || it.reason == "settling-support" })
            if (cell in 2..15 && cell != 9 && cell != 11) assertEquals(own, permitted, "cell $cell changed its motif")
        }
    }

    @Test
    fun `listening cells retain their selected reply opportunity rather than ordinary affirmation`() {
        assertEquals(setOf(5, 7, 9, 11, 14, 17), (0 until CircuitCeremony.cells).filter(CircuitCeremony::replyCell).toSet())
        assertFalse(CircuitCeremony.replyCell(-1))
        assertFalse(CircuitCeremony.replyCell(CircuitCeremony.cells))
        for (cell in listOf(9, 11)) {
            val time = CircuitPhrase.ORIGIN + (cell + .72) * CircuitCeremony.cellSeconds
            val ordinary = Circuit.Event(6, "GRUNT", time, .2, reason = "affirmation")
            assertFalse(CircuitCeremony.permits(ordinary))
            assertTrue(CircuitCeremony.permits(ordinary.copy(reply = true, reason = "reflected-tube-accent")))
        }
    }

    private fun phrase() = CircuitPhrase(CircuitVoice.ROOT, CircuitCeremony.phraseSeconds, 0.0,
        CircuitCeremony.paceHz, false, .55)
}
