package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.synth.Chamber
import com.snipsnap.synth.ChamberConfig
import com.snipsnap.synth.ChamberPatch
import com.snipsnap.synth.ChamberVoice
import com.snipsnap.synth.PadRecipe
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ChamberUserPresetsTest {
    private fun authored(name: String = "MY ROOM") = ChamberPatch(
        name, ChamberVoice.AQUARIUM, Chamber.defaults(ChamberVoice.AQUARIUM), midi = 57,
        velocity = .42f, seed = Long.MIN_VALUE, configuration = ChamberConfig.aquarium().copy(splitHeight = .67f),
    )

    @Test
    fun `saving binning restoring and recipe replay preserve the authored room`() {
        val shelf = createTempDirectory("chamber-presets").toFile()
        try {
            val patch = authored()
            val saved = UserPresets.save(shelf, patch, nowMillis = 12L)
            assertEquals(listOf(saved), UserPresets.read(shelf))
            val binned = requireNotNull(UserPresets.forget(shelf, patch.engine, patch.voiceName, patch.name, nowMillis = 13L))
            assertEquals(patch, requireNotNull(UserPresets.unforget(shelf, binned)).patch)
            val replay = assertIs<RecipeReplay.Plan.Patch>(RecipeReplay.plan(PadRecipe(patch).toJsonValue()))
            assertEquals(patch, replay.recipe.patch)
        } finally {
            shelf.deleteRecursively()
        }
    }

    @Test
    fun `promotion embeds the complete escaped room recipe rather than rebuilding defaults`() {
        val patch = authored("ROOM \"\$\\\n\t")
        val line = UserPresets.rosterLine(patch)
        val literal = line.removePrefix("ChamberPatch.fromJsonText(").removeSuffix("),")
        val recipe = Json.parse(literal.replace("\\$", "$")).str()
        assertEquals(patch, ChamberPatch.fromJsonText(recipe))
        assertEquals("ChamberVoice", UserPresets.voiceEnum(patch.engine))
        assertEquals("ChamberPresets.kt", UserPresets.rosterFile(patch.engine))
    }
}
