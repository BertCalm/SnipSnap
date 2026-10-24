package com.snipsnap.synth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CircuitRootScoreTest {
    @Test
    fun `a single finite figure keeps its call foundation and a quieter answer space`() {
        val events = rootPhrase(3.2, 1.3, false).targets(1.0)
        assertTrue(events.all { CircuitRootScore.permits(it, 0, 1, false) })
        assertTrue(CircuitRootScore.allowsReply(0, 1, false))
        for (event in events) {
            val gain = CircuitRootScore.energyScale(event, 0, 1, false)
            when (event.reason) {
                "opening-wood", "opening-pulse", "opening-support", "affirmation", "settling" -> assertEquals(1.0, gain)
                "weak-wood", "rising-support" -> assertEquals(.50, gain)
                "answer-invitation" -> assertEquals(1.02, gain)
            }
        }
    }

    @Test
    fun `held pairs alternate an ordinary call and a listening cell without losing the foundation`() {
        val call = event(6, "GRUNT", "affirmation")
        val answer = call.copy(reply = true, reason = "reflected-tube-accent")
        val support = listOf(event(3, "RATTLE", "rising-support"), event(3, "RATTLE", "weak-support"),
            event(3, "RATTLE", "settling-support"), event(4, "CLAPPER", "weak-wood"))
        val foundation = listOf(event(4, "CLAPPER", "opening-wood"), event(3, "RATTLE", "opening-support"),
            event(1, "TUBE_ACCENT", "answer-invitation"), event(5, "CLAY", "settling"))
        for (cell in 0 until 8) {
            assertEquals(cell % 2 == 0, CircuitRootScore.permits(call, cell, 8, true))
            assertEquals(cell % 2 == 1, CircuitRootScore.allowsReply(cell, 8, true))
            for (part in support) assertEquals(cell % 2 == 0, CircuitRootScore.permits(part, cell, 8, true))
            for (part in foundation) assertTrue(CircuitRootScore.permits(part, cell, 8, true))
            assertTrue(CircuitRootScore.permits(answer, cell, 8, true))
            assertEquals(1.0, CircuitRootScore.energyScale(answer, cell, 8, true))
        }
    }

    @Test
    fun `late held gestures retain their cell across the seam while finite events stay bounded`() {
        val clay = event(5, "CLAY", "settling")
        val support = event(3, "RATTLE", "settling-support")
        assertTrue(CircuitRootScore.permits(clay, -1, 8, true))
        assertFalse(CircuitRootScore.permits(support, -1, 8, true))
        assertTrue(CircuitRootScore.allowsReply(-1, 8, true))
        assertEquals(CircuitRootScore.allowsReply(0, 8, true), CircuitRootScore.allowsReply(8, 8, true))
        assertFalse(CircuitRootScore.permits(clay, -1, 8, false))
        assertFalse(CircuitRootScore.permits(clay, 8, 8, false))
        assertFalse(CircuitRootScore.allowsReply(0, 0, false))
    }

    @Test
    fun `the last finite figure settles and only eligible tube calls receive invitation emphasis`() {
        val support = event(4, "CLAPPER", "weak-wood")
        val clay = event(5, "CLAY", "settling")
        val invitation = event(1, "TUBE_ACCENT", "answer-invitation")
        assertTrue(CircuitRootScore.permits(support, 0, 3, false))
        assertFalse(CircuitRootScore.permits(support, 2, 3, false))
        assertEquals(0.0, CircuitRootScore.energyScale(support, 2, 3, false))
        assertTrue(CircuitRootScore.permits(clay, 2, 3, false))
        assertEquals(1.0, CircuitRootScore.cueEnergyScale(invitation, 0, 8, true))
        assertEquals(1.08, CircuitRootScore.cueEnergyScale(invitation, 1, 8, true))
        assertEquals(1.0, CircuitRootScore.cueEnergyScale(invitation.copy(reason = "opening-pulse"), 1, 8, true))
        assertEquals(1.0, CircuitRootScore.cueEnergyScale(clay, 1, 8, true))
    }

    private fun event(source: Int, kind: String, reason: String) = Circuit.Event(source, kind, 1.0, .4, reason = reason)

    private fun rootPhrase(seconds: Double, pace: Double, held: Boolean) =
        CircuitPhrase(CircuitVoice.ROOT, seconds, if (held) seconds else 0.0, pace, held, .55)
}
