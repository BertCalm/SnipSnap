package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Presets
import com.snipsnap.synth.Wick
import com.snipsnap.synth.WickPatch
import com.snipsnap.synth.WickVoice
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WickStarterTest {
    @Test
    fun `fresh tape retains the named sixteen-pad bank and exactly replays saved bodies`() {
        val dir = createTempDirectory("wick-starter").toFile()
        try {
            val starter = assertNotNull(StarterKits.byId("wick"))
            assertEquals("WICK", starter.displayName)
            assertFalse(starter.seeded)
            assertEquals(KeySpec.parse("Cminpent"), starter.key)
            val kit = starter.render("Wick Bodies", dir, seed = 99)
            assertEquals(kit, KitStore.load(dir))
            assertEquals(starter.key, kit.key)
            assertFalse(Preflight.check(kit, dir).blocked())
            assertEquals((1..16).toList(), kit.pads.map { it.slot })
            assertEquals(listOf("Small Spark", "Pluck C4", "Dry Wood", "Wood C4", "Bubble C3", "Bubble Chain",
                "Breath C3", "Open Air", "Flicker C3", "Metal Flash", "Warm Wire", "Hollow Reed", "Rubbed Glass",
                "Held Bubbles", "Held Breath", "Held Rattle"), kit.pads.map { it.displayName })
            val patches = kit.pads.map { pad ->
                val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
                val patch = assertIs<WickPatch>(recipe.patch)
                assertEquals(pad.displayName, patch.name)
                assertEquals(Wick.drumClassFor(patch.voice, patch.macros), pad.drumClass)
                assertTrue(pad.oneShot)
                assertEquals(0, pad.tuneCoarse)
                assertEquals(0, pad.tuneFine)
                assertTrue(pad.velocityLayers.isEmpty())
                assertNull(recipe.fx)
                assertNull(recipe.board)
                assertNull(recipe.pedals)
                assertNull(recipe.terminalFadeMs)
                patch
            }
            assertEquals(WickVoice.entries.toSet(), patches.map { it.voice }.toSet())
            assertEquals(List(5) { listOf(48, 60) }.flatten(), patches.take(10).map { it.midi })
            assertEquals(listOf(Wick.RattleSource.WARM_WIRE, Wick.RattleSource.HOLLOW_REED, Wick.RattleSource.RUBBED_GLASS),
                patches.subList(10, 13).map { it.rattleSource })
            assertTrue(patches.subList(10, 13).all { it.voice == WickVoice.RATTLE && it.midi == 60 && !Wick.isLoop(it.macros.getValue("HOLD")) })
            assertTrue(kit.pads.take(13).all { it.drumClass == DrumClass.TONAL })
            assertTrue(kit.pads.takeLast(3).all { it.drumClass == DrumClass.LOOP })
            assertEquals(Wick.RattleSource.WARM_WIRE, patches.last().rattleSource)
            for (patch in patches) {
                assertNull(Presets.landingFor(patch.engine, patch.voiceName, patch.macros))
            }

            val model = KitBuilderModel.open(dir)
            assertEquals(kit, model.kit, "opening the saved source kit retains every body field")
            for (source in listOf(kit.pads.first()) + kit.pads.subList(10, 13) + kit.pads.last()) {
                val recipe = PadRecipe.fromJsonValue(assertNotNull(source.recipe))
                val regenerated = ByteArrayOutputStream().also { WavWriter.write(it, recipe.render()) }.toByteArray()
                val original = dir.resolve(source.sampleFile).readBytes()
                assertContentEquals(original, regenerated, source.displayName)
                val replayed = RecipeReplay.apply(model, 2, source.recipe!!, source.displayName)
                assertEquals(source.recipe, replayed.pad.recipe)
                assertContentEquals(original, dir.resolve(replayed.pad.sampleFile).readBytes(),
                    "${source.displayName} replays its saved source byte for byte on another pad")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `spread uses WICK authored root below detector range even for a held body`() {
        val silent = Snip(FloatArray(64), 1, 44_100)
        assertNull(Spread.detect(silent))
        for (voice in WickVoice.entries) {
            val patch = WickPatch("Low $voice", voice, Wick.defaults(voice) + ("HOLD" to 1f), midi = 24,
                rattleSource = if (voice == WickVoice.RATTLE) Wick.RattleSource.RUBBED_GLASS else Wick.RattleSource.WARM_WIRE)
            assertEquals(24f, Spread.detect(silent, patch), "$voice keeps its authored C1 root")
        }
    }
}
