package com.snipsnap.shell

import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.AerostatPatch
import com.snipsnap.synth.BallastPatch
import com.snipsnap.synth.TremorPatch
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewSynthStarterTest {
    @Test
    fun `the new starter entries write complete editable kits whose samples replay exactly`() {
        val root = Files.createTempDirectory("new-synth-starters").toFile()
        try {
            val engines = mapOf(
                "aerostat" to AerostatPatch.ENGINE,
                "tremor" to TremorPatch.ENGINE,
                "ballast" to BallastPatch.ENGINE,
            )
            for ((id, engine) in engines) {
                val starter = assertNotNull(StarterKits.byId(id), "missing FRESH TAPE entry $id")
                assertEquals(engine, starter.displayName)
                assertFalse(starter.seeded, "$id is an authored kit, not a reroll")
                val dir = File(root, id)
                val kit = starter.render("My $id", dir)
                assertEquals((1..16).toList(), kit.pads.map { it.slot }, "$id must fill bank A")
                assertFalse(Preflight.check(kit, dir).blocked(), "$id fails export preflight")
                for (pad in kit.pads) {
                    assertTrue(File(dir, pad.sampleFile).length() > 44, "$id ${pad.slot} is missing audio")
                    val recipe = assertNotNull(Breed.recipeOf(pad.recipe), "$id ${pad.slot} lost its recipe")
                    assertEquals(engine, assertNotNull(recipe.patch).engine)
                    assertNull(recipe.fx, "$id's source model must remain dry")
                }
                val model = KitBuilderModel.open(dir)
                val first = model.kit.pads.first()
                val last = model.kit.pads.last()
                val expected = File(dir, first.sampleFile).readBytes()
                val replay = RecipeReplay.apply(model, last.slot, first.recipe!!, last.displayName)
                assertEquals(first.recipe, replay.pad.recipe, "$id recipe changed during replay")
                assertTrue(expected.contentEquals(File(dir, replay.pad.sampleFile).readBytes()), "$id replay changed its sound")
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
