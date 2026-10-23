package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.KeySpec
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.OhmPatch
import com.snipsnap.synth.OhmPresets
import com.snipsnap.synth.PadRecipe
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OhmStarterKitTest {
    @Test
    fun `OHM is a fixed selectable starter with sixteen editable finite notes`() {
        val dir = createTempDirectory("ohm-starter").toFile()
        try {
            val starter = StarterKits.ALL.single { it.id == "ohm" }
            assertEquals("OHM", starter.displayName)
            assertFalse(starter.seeded)
            assertEquals(KeySpec.parse("Cminpent"), starter.key)
            val kit = starter.render("OHM Family Strings", dir, seed = -99)
            assertEquals((1..16).toList(), kit.pads.map { it.slot })
            assertEquals(kit, KitStore.load(dir))
            assertFalse(Preflight.check(kit, dir).blocked())
            val patches = kit.pads.map { pad ->
                assertEquals(DrumClass.TONAL, pad.drumClass)
                assertTrue(pad.oneShot)
                assertEquals(0, pad.muteGroup)
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                assertNull(recipe.fx)
                assertIs<OhmPatch>(recipe.patch).also { patch ->
                    assertEquals(2, patch.model)
                    assertEquals(731, patch.seed, "fixed starter must keep the accepted source seed")
                    assertEquals(.8f, patch.velocity)
                }
            }
            assertTrue(patches.containsAll(OhmPresets.all()))
            assertEquals(listOf(48, 51, 53, 55, 58, 60, 63, 65), patches.take(8).map { it.midi })
        } finally {
            dir.deleteRecursively()
        }
    }
}
