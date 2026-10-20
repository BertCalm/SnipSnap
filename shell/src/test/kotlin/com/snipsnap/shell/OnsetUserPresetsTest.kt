package com.snipsnap.shell

import com.snipsnap.synth.Onset
import com.snipsnap.synth.OnsetPatch
import com.snipsnap.synth.OnsetPresets
import com.snipsnap.synth.OnsetVoice
import com.snipsnap.synth.Patches
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnsetUserPresetsTest {
    @Test
    fun `factory names stay reserved and a saved earlier model survives backup merge and promotion`() {
        val shelf = kotlin.io.path.createTempDirectory("onset-presets").toFile()
        val restored = kotlin.io.path.createTempDirectory("onset-restored").toFile()
        try {
            for (factory in OnsetPresets.all()) {
                assertEquals(
                    UserPresets.Check.Factory(UserPresets.normalize(factory.name)),
                    UserPresets.check(" ${factory.name.lowercase()} ", factory.engine, factory.voiceName, emptyList()),
                )
            }
            val original = OnsetPatch("MY MEMBRANE", OnsetVoice.KICK,
                Onset.defaults(OnsetVoice.KICK) + ("BLEND" to .75f), modelVersion = 2)
            UserPresets.save(shelf, original, 12L)
            val loaded = UserPresets.read(shelf).single().patch as OnsetPatch
            assertEquals(original, loaded)
            val edited = Patches.edited(loaded, loaded.name, loaded.macros + ("DECAY" to .7f)) as OnsetPatch
            assertEquals(2, edited.modelVersion, "a slider edit keeps the saved renderer")
            UserPresets.save(shelf, edited, 13L)
            val merged = UserPresets.merge(restored, UserPresets.file(shelf).readText(), 14L)
            assertEquals(listOf(edited), merged.landed.map { it.patch })
            assertEquals(edited, UserPresets.read(restored).single().patch)
            assertEquals(1, UserPresets.merge(restored, UserPresets.file(shelf).readText(), 15L).identical)
            val line = UserPresets.rosterLine(edited)
            assertTrue(line.startsWith("OnsetPatch(\"MY MEMBRANE\", OnsetVoice.KICK, mapOf("), line)
            assertTrue("modelVersion = 2" in line, "promotion must not adopt the current renderer: $line")
            assertEquals("OnsetPresets.kt", UserPresets.rosterFile(edited.engine))
        } finally {
            shelf.deleteRecursively()
            restored.deleteRecursively()
        }
    }
}
