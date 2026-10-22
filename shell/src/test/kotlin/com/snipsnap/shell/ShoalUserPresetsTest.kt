package com.snipsnap.shell

import com.snipsnap.synth.Patches
import com.snipsnap.synth.Shoal
import com.snipsnap.synth.ShoalMaterial
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
    fun `saved edited and promoted populations retain root energy model and material`() {
        val shelf = kotlin.io.path.createTempDirectory("shoal-presets").toFile()
        try {
            val source = ShoalPatch("MY SHOAL", ShoalVoice.RIPPLE,
                Shoal.defaults(ShoalVoice.RIPPLE) + ("CURRENT" to .63f), midi = 57, velocity = .4f,
                material = ShoalMaterial.BOWL)
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
            assertTrue(line.startsWith("ShoalPatch(\"MY CURRENT\", ShoalVoice.RIPPLE, "))
            for (field in listOf("\"CURRENT\" to 0.63f", "\"COHESION\" to 0.82f",
                "midi = 57", "velocity = 0.4f", "model = ${Shoal.MODEL_VERSION}", "material = ShoalMaterial.BOWL")) {
                assertTrue(field in line, "promotion lost $field: $line")
            }
            assertEquals("ShoalPresets.kt", UserPresets.rosterFile(edited.engine))
        } finally {
            shelf.deleteRecursively()
        }
    }

    @Test
    fun `legacy harmonic held sounds retain their model and promote without finite material defaults`() {
        val shelf = kotlin.io.path.createTempDirectory("shoal-legacy-presets").toFile()
        try {
            for (model in listOf(Shoal.LEGACY_MODEL_VERSION, Shoal.HARMONIC_MODEL_VERSION)) {
                val source = ShoalPatch("MY V$model", ShoalVoice.GLASS,
                    Shoal.defaults(ShoalVoice.GLASS) + ("HOLD" to 1f), midi = 60, velocity = .5f,
                    model = model, material = ShoalMaterial.HARMONIC)
                UserPresets.save(shelf, source, nowMillis = model.toLong())
                val restored = assertIs<ShoalPatch>(UserPresets.read(shelf).single { it.name == source.name }.patch)
                assertEquals(source, restored)
                val edited = assertIs<ShoalPatch>(Patches.edited(restored, source.name, restored.macros + ("GLOW" to .6f)))
                assertEquals(source.copy(macros = source.macros + ("GLOW" to .6f)), edited)
                val line = UserPresets.rosterLine(edited)
                assertTrue(line.startsWith("ShoalPatch("), "promotion must construct the complete held recipe at once")
                assertTrue("model = $model" in line)
                assertTrue("material = ShoalMaterial.HARMONIC" in line)
                assertTrue("\"HOLD\" to 1.0f" in line)
            }
        } finally {
            shelf.deleteRecursively()
        }
    }
}
