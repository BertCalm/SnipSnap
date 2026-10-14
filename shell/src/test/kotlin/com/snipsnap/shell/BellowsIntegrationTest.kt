package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.synth.Bellows
import com.snipsnap.synth.BellowsGesture
import com.snipsnap.synth.BellowsPatch
import com.snipsnap.synth.BellowsPresets
import com.snipsnap.synth.BellowsVoice
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Presets
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class BellowsIntegrationTest {
    @Test
    fun `the fixed bellows starter exposes all four dry preset identities through saved recipes`() {
        val starter = requireNotNull(StarterKits.byId("bellows"))
        assertEquals("BELLOWS", starter.displayName)
        assertFalse(starter.seeded)
        assertEquals(KeySpec.parse("Cminpent"), starter.key)
        val presets = BellowsPresets.all()
        assertEquals(8, presets.size)
        assertEquals(BellowsVoice.entries.toSet(), presets.map { it.voice }.toSet())
        for (voice in BellowsVoice.entries) {
            assertEquals(2, Presets.forVoice(BellowsPatch.ENGINE, voice.name).size)
        }
        // The all-starters suite renders the complete kit. Exercise its recipe
        // and shelf dispatch here without regenerating sixteen audio buffers.
        for (patch in presets) {
            val decoded = PadRecipe.fromJsonText(PadRecipe(patch).toJsonText())
            assertEquals(patch, assertIs<BellowsPatch>(decoded.patch))
            assertNull(decoded.fx, "${patch.name}: chamber and motion belong to the dry engine")
            assertFalse(decoded.alias)
            val factoryName = UserPresets.normalize(patch.name)
            assertEquals(UserPresets.Check.Factory(factoryName),
                UserPresets.check(factoryName, patch.engine, patch.voiceName, emptyList()))
        }
    }

    @Test
    fun `editing a shelf preset keeps the saved note velocity model and independent player gesture`() {
        val directory = createTempDirectory("bellows-shelf").toFile()
        try {
            val source = BellowsPatch("MY AIR", BellowsVoice.COUNTER,
                Bellows.defaults(BellowsVoice.COUNTER) + ("TUNE" to 0f),
                midi = 66, velocity = .3f, gesture = BellowsGesture.PULSE)
            UserPresets.save(directory, source, 97L)
            val loaded = assertIs<BellowsPatch>(UserPresets.read(directory).single().patch)
            val edits = loaded.macros + ("PRESSURE" to .82f) + ("TUNE" to .125f)
            val edited = assertIs<BellowsPatch>(Patches.edited(loaded, source.name, edits))
            assertEquals(source.copy(macros = edits), edited,
                "a macro edit must retain the high MIDI base and non-default gesture")
            UserPresets.save(directory, edited, 313L)
            val saved = UserPresets.read(directory).single()
            assertEquals(edited, saved.patch)
            assertEquals(313L, saved.savedAt)
            assertEquals(listOf(saved), UserPresets.forVoice(UserPresets.read(directory), "BELLOWS", "COUNTER"))
            val recipe = PadRecipe.fromJsonText(PadRecipe(saved.patch).toJsonText())
            assertEquals(edited, recipe.patch, "placing the shelf preset keeps its entire recipe")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `factory promotion carries the saved fields alongside the edited macro map`() {
        val directory = createTempDirectory("bellows-promotion").toFile()
        try {
            val patch = BellowsPatch("MY PULSE", BellowsVoice.COUNTER,
                linkedMapOf("TUNE" to .125f, "PRESSURE" to .82f),
                midi = 66, velocity = .3f, model = 1, gesture = BellowsGesture.PULSE)
            UserPresets.save(directory, patch, 817L)
            val saved = UserPresets.read(directory).single()
            val expected = "p(BellowsVoice.COUNTER, \"MY PULSE\", \"TUNE\" to 0.125f, \"PRESSURE\" to 0.82f)" +
                ".copy(midi = 66, velocity = 0.3f, model = 1, gesture = BellowsGesture.PULSE),"
            assertEquals(expected, UserPresets.rosterLine(saved.patch))
            assertEquals("BellowsPresets.kt\n  $expected\n", UserPresets.renderAll(listOf(saved)))
        } finally {
            directory.deleteRecursively()
        }
    }
}
