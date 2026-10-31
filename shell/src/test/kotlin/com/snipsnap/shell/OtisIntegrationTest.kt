package com.snipsnap.shell

import com.snipsnap.synth.*
import com.snipsnap.audio.WavReader
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.json.Json
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class OtisIntegrationTest {
    @Test fun `starter kit keeps all sixteen accepted performances finite and editable`() {
        val starter = assertNotNull(StarterKits.byId("otis"))
        val directory = createTempDirectory("otis-starter").toFile()
        try {
            val kit = starter.render("Otis proof", directory)
            assertFalse(Preflight.check(kit, directory).blocked())
            val pads = KitBuilderModel.open(directory).kit.pads
            assertEquals(16, pads.size)
            assertTrue(pads.all { it.oneShot && !it.loop })
            for (pad in pads) {
                val snip = WavReader.read(File(directory, pad.sampleFile))
                assertEquals(1, snip.channels)
                assertEquals(44100, snip.sampleRate)
            }
            val patches = pads.map { assertIs<OtisPatch>(PadRecipe.fromJsonValue(assertNotNull(it.recipe)).patch) }
            assertEquals(OtisPresets.all(), patches)
            assertEquals(OtisVoice.entries.toSet(), patches.map { it.voice }.toSet())
        } finally { directory.deleteRecursively() }
        assertTrue(EngineCatalog.ENTRIES.getValue("OTIS").categories.contains(EngineCategory.KEYS))
    }

    @Test fun `navigation drafts and user presets retain a chord after controls are edited`() {
        val source = OtisPresets.forVoice(OtisVoice.SQUEEZE).first { it.name == "SQUEEZE DRAW" }
        val edited = source.withMacros(source.macros + mapOf("PULSE" to .75f, "LENGTH" to .3f))
            .copy(name = "MY DRAW", midi = 57, velocity = .63f, seed = -7)
        val bag = mutableMapOf("engine" to "OTIS")
        SynthDrafts.write(bag, listOf(edited))
        val restored = assertIs<OtisPatch>(SynthDrafts.read(bag).single())
        assertEquals(edited, restored)
        assertEquals(source.profile, restored.profile)
        assertTrue(restored.profile.events.size == 4 && restored.profile.events.all { it.gateSeconds == 3.6 })
        assertContentEquals(edited.render().samples, restored.render().samples)
        assertEquals("OTIS", bag["engine"])
        val shelf = createTempDirectory("otis-presets").toFile()
        try {
            UserPresets.save(shelf, restored, 1L)
            val saved = UserPresets.read(shelf).single().patch
            assertEquals(edited, saved)
            val literal = UserPresets.rosterLine(saved).removePrefix("OtisPatch.fromJsonText(").removeSuffix("),")
            val promoted = OtisPatch.fromJsonText(Json.parse(literal).str())
            assertEquals(edited, promoted)
            assertContentEquals(edited.render().samples, promoted.render().samples)
        } finally { shelf.deleteRecursively() }
    }
}
