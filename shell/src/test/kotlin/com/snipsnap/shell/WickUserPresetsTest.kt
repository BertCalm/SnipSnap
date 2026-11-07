package com.snipsnap.shell

import com.snipsnap.json.JsonValue
import com.snipsnap.synth.FxChain
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patches
import com.snipsnap.synth.Wick
import com.snipsnap.synth.WickPatch
import com.snipsnap.synth.WickPresets
import com.snipsnap.synth.WickVoice
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WickUserPresetsTest {
    @Test
    fun `saved restored and promoted bodies retain source root velocity and held controls`() {
        val shelf = createTempDirectory("wick-presets").toFile()
        try {
            for ((index, source) in Wick.RattleSource.entries.withIndex()) {
                val authored = WickPatch("MY BODY $index", WickVoice.RATTLE,
                    Wick.defaults(WickVoice.RATTLE) + mapOf("TIMBRE" to .67f, "HOLD" to 1f),
                    midi = 57, velocity = .43f, rattleSource = source)
                // The old JSON omitted the body; saving it must make CURRENT explicit.
                val patch = if (source == Wick.RattleSource.CURRENT) {
                    assertIs<WickPatch>(Patches.fromJsonValue(JsonValue.Obj(authored.toJsonValue().entries - "rattleSource")))
                } else authored
                assertEquals(authored, patch)
                val saved = UserPresets.save(shelf, patch, nowMillis = index.toLong())
                assertEquals(saved, UserPresets.forVoice(UserPresets.read(shelf), patch.engine, patch.voiceName)
                    .single { it.name == patch.name })
                val binned = assertNotNull(UserPresets.forget(shelf, patch.engine, patch.voiceName, patch.name, 10L))
                val restored = assertNotNull(UserPresets.unforget(shelf, binned))
                assertEquals(patch, restored.patch)
                val promoted = fromRosterLine(UserPresets.rosterLine(restored.patch))
                assertEquals(patch, promoted)
                assertEquals(source, promoted.rattleSource)
                val recipe = PadRecipe(promoted, fx = FxChain(reverse = true), treatment = "REVERSE", amount = .5f)
                val replay = assertIs<RecipeReplay.Plan.Patch>(RecipeReplay.plan(recipe.toJsonValue()))
                assertEquals(recipe, replay.recipe, "the pad's finishing rack stays beside the complete body recipe")
                assertContentEquals(recipe.render().samples, replay.recipe.render().samples)
            }
        } finally {
            shelf.deleteRecursively()
        }
    }

    @Test
    fun `promoted body JSON is a complete escaped Kotlin string`() {
        val patch = WickPatch("BODY \"\$\\\n\t", WickVoice.RATTLE, mapOf("FEED" to .37f),
            midi = 24, velocity = .42f, rattleSource = Wick.RattleSource.RUBBED_GLASS)
        assertEquals(patch, fromRosterLine(UserPresets.rosterLine(patch)))
        assertEquals("WickVoice", UserPresets.voiceEnum(patch.engine))
        assertEquals("WickPresets.kt", UserPresets.rosterFile(patch.engine))
    }

    @Test
    fun `every factory recipe name is protected on its own voice`() {
        for (patch in WickPresets.all()) {
            val name = UserPresets.normalize(patch.name)
            val refused = if (name.length > UserPresets.MAX_NAME) UserPresets.Check.TooLong(name)
                else UserPresets.Check.Factory(name)
            assertEquals(refused,
                UserPresets.check("  ${patch.name.lowercase()}  ", patch.engine, patch.voiceName, emptyList()))
        }
    }

    private fun fromRosterLine(line: String): WickPatch {
        assertTrue(line.endsWith(","), line)
        val call = assertIs<KotlinLiteral.Call>(KotlinLiteral.parse(line.removeSuffix(",")))
        assertEquals("fromJsonText", call.name)
        assertEquals("WickPatch", assertIs<KotlinLiteral.Call>(call.receiver).name)
        assertTrue(call.named.isEmpty())
        return WickPatch.fromJsonText(assertIs<KotlinLiteral.Str>(call.positional.single()).value)
    }
}
