package com.snipsnap.shell

import com.snipsnap.synth.SuturePresets
import com.snipsnap.synth.SutureVoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SutureUserPresetsTest {
    @Test
    fun `factory vessel names remain protected after the user's name is normalized`() {
        val patch = SuturePresets.forVoice(SutureVoice.BLOOM).first()
        assertEquals(
            UserPresets.Check.Factory(patch.name),
            UserPresets.check("  open bronze  ", patch.engine, patch.voiceName, emptyList()),
        )
    }

    @Test
    fun `a user's edited vessel survives saving and produces a pasteable roster line`() {
        val shelf = kotlin.io.path.createTempDirectory("suture-presets").toFile()
        try {
            val source = SuturePresets.forVoice(SutureVoice.THREAD).first()
            val patch = source.copy(name = "MY VESSEL", macros = source.macros + ("CORD" to 0.3f))
            val saved = UserPresets.save(shelf, patch, nowMillis = 12L)
            assertEquals(listOf(saved), UserPresets.read(shelf))
            assertEquals(patch, UserPresets.read(shelf).single().patch)
            val line = UserPresets.rosterLine(patch)
            assertTrue(line.startsWith("p(SutureVoice.THREAD, \"MY VESSEL\", "))
            assertTrue("\"CORD\" to 0.3f" in line)
            assertEquals("SuturePresets.kt", UserPresets.rosterFile(patch.engine))
        } finally {
            shelf.deleteRecursively()
        }
    }
}
