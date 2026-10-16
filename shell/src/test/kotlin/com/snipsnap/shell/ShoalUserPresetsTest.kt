package com.snipsnap.shell

import com.snipsnap.synth.Patches
import com.snipsnap.synth.Shoal
import com.snipsnap.synth.ShoalPatch
import com.snipsnap.synth.ShoalPresets
import com.snipsnap.synth.ShoalVoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ShoalUserPresetsTest {
    @Test
    fun `both voices reserve factory names within the saved name limit after normalization`() {
        for (voice in ShoalVoice.entries) {
            val factory = ShoalPresets.forVoice(voice).first {
                UserPresets.normalize(it.name).length <= UserPresets.MAX_NAME
            }
            assertEquals(
                UserPresets.Check.Factory(UserPresets.normalize(factory.name)),
                UserPresets.check("  ${factory.name.lowercase()}  ", factory.engine, factory.voiceName, emptyList()),
            )
        }
    }

    @Test
    fun `saved edited and promoted populations retain independent root energy and model`() {
        val shelf = kotlin.io.path.createTempDirectory("shoal-presets").toFile()
        try {
            val source = ShoalPatch("MY SHOAL", ShoalVoice.RIPPLE,
                Shoal.defaults(ShoalVoice.RIPPLE) + ("CURRENT" to .63f), midi = 57, velocity = .4f)
            val saved = UserPresets.save(shelf, source, nowMillis = 12L)
            assertEquals(listOf(saved), UserPresets.read(shelf))
            val restored = assertIs<ShoalPatch>(UserPresets.read(shelf).single().patch)
            assertEquals(source, restored)

            val macros = restored.macros + ("COHESION" to .82f)
            val edited = assertIs<ShoalPatch>(Patches.edited(restored, "MY CURRENT", macros))
            UserPresets.save(shelf, edited, nowMillis = 13L)
            assertEquals(source.copy(name = "MY CURRENT", macros = macros),
                UserPresets.read(shelf).single { it.name == "MY CURRENT" }.patch)
            val line = UserPresets.rosterLine(edited)
            assertTrue(line.startsWith("p(ShoalVoice.RIPPLE, \"MY CURRENT\", "))
            for (field in listOf("\"CURRENT\" to 0.63f", "\"COHESION\" to 0.82f",
                "midi = 57", "velocity = 0.4f", "model = ${Shoal.MODEL_VERSION}")) {
                assertTrue(field in line, "promotion lost $field: $line")
            }
            assertEquals("ShoalPresets.kt", UserPresets.rosterFile(edited.engine))
        } finally {
            shelf.deleteRecursively()
        }
    }
}
