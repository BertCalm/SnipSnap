package com.snipsnap.shell

import com.snipsnap.json.Json
import com.snipsnap.synth.OpenSky
import com.snipsnap.synth.OpenSkyPatch
import com.snipsnap.synth.OpenSkyPresets
import com.snipsnap.synth.OpenSkyTimbre
import com.snipsnap.synth.OpenSkyVoice
import com.snipsnap.synth.PadRecipe
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpenSkyUserPresetsTest {
    private fun authored(name: String = "MY SKY") = OpenSkyPatch(
        name, OpenSkyVoice.SOAR, OpenSky.defaults(OpenSkyVoice.SOAR) + ("RISE" to .31f),
        midi = 73, velocity = .42f, gateSeconds = .15f, wet = false,
        timbre = OpenSkyTimbre(
            pulseMix = .28f, pulseWidth = .37f, sineMix = .16f, octaveMix = .11f,
            centerWeightScale = 1.4f, detuneScale = .7f, toneScale = .8f,
            attackScale = 1.2f, sustainLevel = .64f, decaySeconds = .3f,
            releaseScale = .8f, bellBrightnessScale = 1.3f, bellDecayScale = .7f,
        ),
    )

    @Test
    fun `saving and restoring a sky retains the entire sound and generic recipe replay`() {
        val shelf = createTempDirectory("opensky-presets").toFile()
        try {
            val patch = authored()
            val saved = UserPresets.save(shelf, patch, nowMillis = 12L)
            assertEquals(listOf(saved), UserPresets.read(shelf))
            assertEquals(patch, UserPresets.read(shelf).single().patch)
            val binned = requireNotNull(UserPresets.forget(shelf, patch.engine, patch.voiceName, patch.name, nowMillis = 13L))
            val restored = requireNotNull(UserPresets.unforget(shelf, binned))
            assertEquals(patch, restored.patch)
            val replay = assertIs<RecipeReplay.Plan.Patch>(RecipeReplay.plan(PadRecipe(patch).toJsonValue()))
            assertEquals(patch, replay.recipe.patch)
            assertContentEquals(patch.render().samples, replay.recipe.render().samples)
        } finally {
            shelf.deleteRecursively()
        }
    }

    @Test
    fun `promoted sky JSON is escaped Kotlin and reconstructs every authored field`() {
        val patch = authored("SKY \"\$\\\n\t")
        val line = UserPresets.rosterLine(patch)
        val literal = line.removePrefix("OpenSkyPatch.fromJsonText(").removeSuffix("),")
        // Kotlin additionally escapes dollar signs; the other escapes are JSON string escapes.
        val recipe = Json.parse(literal.replace("\\$", "$")).str()
        assertEquals(patch, OpenSkyPatch.fromJsonText(recipe))
        assertEquals("OpenSkyVoice", UserPresets.voiceEnum(patch.engine))
        assertEquals("OpenSkyPresets.kt", UserPresets.rosterFile(patch.engine))
    }

    @Test
    fun `accepted sky names are protected after normalization`() {
        val patch = OpenSkyPresets.forVoice(OpenSkyVoice.ANTHEM).first()
        assertEquals(UserPresets.Check.Factory(UserPresets.normalize(patch.name)),
            UserPresets.check("  ${patch.name.lowercase()}  ", patch.engine, patch.voiceName, emptyList()))
    }
}
