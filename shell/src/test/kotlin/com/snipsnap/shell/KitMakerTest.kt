package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Scale
import com.snipsnap.kit.Names
import com.snipsnap.synth.KitFromAnything
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KitMakerTest {

    @Test
    fun `every genre the assembler has is a chip, in its order, in capitals`() {
        assertEquals(KitFromAnything.Genre.entries, KitMaker.GENRES)
        assertEquals(listOf("BOOM BAP", "HOUSE", "TECHNO"), KitMaker.GENRES.map { KitMaker.genreLabel(it) })
        assertTrue(KitMaker.DEFAULT_GENRE in KitMaker.GENRES)
    }

    @Test
    fun `the key opens on the kit's own, else A minor`() {
        assertEquals(KeySpec(9, Scale.MINOR), KitMaker.DEFAULT_KEY)
        assertEquals(KitMaker.DEFAULT_KEY, KitMaker.openingKey(null))
        val own = KeySpec(2, Scale.MAJOR)
        assertEquals(own, KitMaker.openingKey(own))
    }

    @Test
    fun `a kit name is MPC-safe whatever the pad was called`() {
        assertEquals("Snare Kit", KitMaker.kitName("Snare"))
        for (raw in listOf("", "   ", "a/b:c*d?", "Kick \"hard\" <1>", "ключ", "...", "x".repeat(200))) {
            val name = KitMaker.kitName(raw)
            assertTrue(Names.isMpcSafe(name), "'$name' from '$raw'")
            assertTrue(name.endsWith(" Kit"))
            assertTrue(name.length <= KitMaker.STEM_MAX + " Kit".length, "'$name'")
        }
        assertEquals("Sample Kit", KitMaker.kitName(""))
    }

    @Test
    fun `refusals use the assembler's own limits`() {
        assertNotNull(KitMaker.refusal(KitFromAnything.MIN_SOURCE_SECONDS / 2))
        assertNotNull(KitMaker.refusal(KitFromAnything.MAX_SOURCE_SECONDS + 1f))
        assertNull(KitMaker.refusal(KitFromAnything.MIN_SOURCE_SECONDS))
        assertNull(KitMaker.refusal(1f))
        assertNull(KitMaker.refusal(KitFromAnything.MAX_SOURCE_SECONDS))
    }
}
