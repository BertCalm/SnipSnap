package com.snipsnap.shell

import com.snipsnap.synth.OsteriaPresets
import com.snipsnap.synth.OsteriaVoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OsteriaDeliveryTest {
    @Test
    fun `accepted bank is discoverable without displacing existing starters or imported presets`() {
        // Stored IDs are appended to the roster; StarterShelf decides where they show.
        val originalIds = listOf("osteria", "osteria-six-voice")
        val ids = StarterKits.ALL.map { it.id }
        for (id in originalIds + "erosion") assertEquals(1, ids.count { it == id }, "$id must remain registered exactly once")
        assertEquals(1, ids.windowed(originalIds.size).count { it == originalIds },
            "the two OSTERIA starters must remain contiguous and in order")
        for (id in listOf("osteria", "osteria-six-voice")) {
            val starter = assertNotNull(StarterKits.byId(id))
            assertFalse(starter.seeded)
            assertNotNull(starter.key)
            assertEquals(1, StarterShelf.GROUPS.sumOf { it.ids.count { entry -> entry == id } })
        }
        val imported = mapOf(
            OsteriaVoice.BASS to listOf("ANCHOR BASS", "ROAMING BASS"),
            OsteriaVoice.PLUCK to listOf("ROUNDED PLUCK", "BRIGHT PLUCK"),
            OsteriaVoice.REED to listOf("STILL REED", "REMEMBER REED"),
            OsteriaVoice.PAD to listOf("WARM SWELL", "MEMORY DRIFT"),
            OsteriaVoice.BOWED to listOf("WARM BOW", "ROSIN BOW"),
            OsteriaVoice.QUARTET to listOf("ANCHOR NOTE", "LINGER COLOR"),
        )
        for ((voice, names) in imported) {
            assertTrue(OsteriaPresets.forVoice(voice).size >= 2)
            for (name in names) assertTrue(UserPresets.check(name, "OSTERIA", voice.name, emptyList()) is UserPresets.Check.Fresh)
        }
    }
}
