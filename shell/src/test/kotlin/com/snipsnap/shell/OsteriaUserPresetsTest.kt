package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.synth.OsteriaPresets
import com.snipsnap.synth.Osteria
import com.snipsnap.synth.OsteriaPatch
import com.snipsnap.synth.OsteriaVoice
import com.snipsnap.synth.Patches
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OsteriaUserPresetsTest {
    @Test
    fun `saved bowed preset reopens under its own voice with full performance and remains editable`() {
        val root = Files.createTempDirectory("osteria-bowed-presets").toFile()
        try {
            val voice = OsteriaVoice.BOWED
            val patch = OsteriaPatch(
                "MY BOW", voice, Osteria.defaults(voice) + mapOf("MEMORY" to .87f, "WANDER" to .78f),
                midi = 55, velocity = .43f,
                config = Osteria.configurationFor(voice).copy(
                    initialShores = listOf(.17, 1.29, 2.41, 3.53),
                    roleLevels = listOf(.76, .67, .58, .49),
                    seed = 7901, seconds = 1.5, attackSeconds = .14, releaseSeconds = .25,
                    tavernShore = 2.63, direction = -1,
                ),
            )
            UserPresets.save(root, patch, nowMillis = 5678L)
            val stored = UserPresets.read(root)
            val loaded = UserPresets.forVoice(stored, patch.engine, voice.name).single().patch as OsteriaPatch
            assertEquals(patch, loaded)
            assertTrue(UserPresets.forVoice(stored, patch.engine, OsteriaVoice.REED.name).isEmpty())
            assertTrue(patch.render().samples.contentEquals(loaded.render().samples))
            val edited = Patches.edited(loaded, "MY BOW MOTION", loaded.macros + ("SHORE" to .9f)) as OsteriaPatch
            UserPresets.save(root, edited, nowMillis = 6789L)
            assertEquals(edited, UserPresets.read(root).single { it.name == edited.name }.patch)
            assertEquals(patch.config, edited.config)
            assertEquals(patch.midi, edited.midi)
            assertEquals(patch.velocity, edited.velocity)
            val line = UserPresets.rosterLine(edited)
            assertEquals(edited, decodeRosterLine(line))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `custom articulation survives shelf restart editing and roster promotion`() {
        val root = Files.createTempDirectory("osteria-touch-presets").toFile()
        try {
            val factory = OsteriaPresets.all().first()
            val patch = factory.copy(
                name = "MY TOUCH", midi = 60, velocity = .37f,
                config = factory.config.copy(seconds = 1.5, attackSeconds = .12, releaseSeconds = .25),
            )
            UserPresets.save(root, patch, nowMillis = 1234L)
            val loaded = UserPresets.read(root).single().patch as OsteriaPatch
            assertEquals(patch, loaded)
            assertTrue(patch.render().samples.contentEquals(loaded.render().samples))
            val edited = Patches.edited(loaded, "MY MOTION", loaded.macros + ("WANDER" to .8f)) as OsteriaPatch
            assertEquals(patch.config, edited.config)
            val line = UserPresets.rosterLine(edited)
            assertEquals(edited, decodeRosterLine(line))
        } finally {
            root.deleteRecursively()
        }
    }
    private fun decodeRosterLine(line: String): OsteriaPatch {
        assertTrue(line.startsWith("OsteriaPatch.fromJsonText("))
        val literal = line.removePrefix("OsteriaPatch.fromJsonText(").removeSuffix("),")
        return OsteriaPatch.fromJsonText(Json.parse(literal).str())
    }
}
