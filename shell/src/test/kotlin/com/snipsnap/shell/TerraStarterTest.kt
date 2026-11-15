package com.snipsnap.shell

import com.snipsnap.kit.KitStore
import com.snipsnap.synth.*
import kotlin.test.*

/** TERRA's first starter: the classic kit the owner kept in full (terra-kit-1, 2026-11-14). */
class TerraStarterTest {
    @Test fun `the TERRA starter renders the kept classic kit with its recipes and soft strikes`() {
        val dir=kotlin.io.path.createTempDirectory("terra-starter").toFile()
        try {
            val starter=assertNotNull(StarterKits.byId("terra"))
            assertEquals("TERRA",starter.displayName)
            assertFalse(starter.seeded)
            val kit=starter.render("Terra Test",dir)
            assertNull(kit.key)
            assertEquals(16,kit.pads.size)
            val kept=TerraKits.classic().map { PadRecipe.fromJsonValue(it!!.recipe!!).patch }
            assertEquals(kept,kit.pads.map { PadRecipe.fromJsonValue(it.recipe!!).patch })
            for (pad in kit.pads) {
                val recipe=PadRecipe.fromJsonValue(pad.recipe!!)
                assertIs<TerraPatch>(recipe.patch)
                assertNull(recipe.fx); assertNull(recipe.board)
                assertTrue(pad.oneShot && !pad.loop && pad.chain==null)
                // The two softer strikes each pad carries become velocity zones under the hard one.
                assertEquals(3,pad.velocityLayers.size,"${pad.slot}")
            }
            assertEquals(kit,KitStore.load(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun `TERRA is shelved under SKINS right after the TEMPEST kits`() {
        val skins=StarterShelf.GROUPS.single { it.heading==Copy.STARTER_HEADING_SKINS }.ids
        assertEquals("terra",skins[skins.indexOf("tempest-step")+1])
        assertEquals(1,StarterShelf.GROUPS.sumOf { g -> g.ids.count { it=="terra" } })
    }
}
