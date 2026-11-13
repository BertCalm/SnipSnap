package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.*
import kotlin.test.*

class TempestStarterTest {
    @Test fun `default starter retains all accepted roles recipes and hat choke groups`() {
        val dir=kotlin.io.path.createTempDirectory("tempest-full-starter").toFile()
        try {
            val starter=assertNotNull(StarterKits.byId("tempest"))
            assertFalse(starter.seeded)
            val kit=starter.render("Tempest Full Test",dir)
            assertNull(kit.key)
            assertEquals(16,kit.pads.size)
            assertEquals(TempestPresets.fullKitPatches(),kit.pads.map { PadRecipe.fromJsonValue(it.recipe!!).patch })
            for (pad in kit.pads) {
                val recipe=PadRecipe.fromJsonValue(pad.recipe!!)
                val p=assertIs<TempestPatch>(recipe.patch)
                assertEquals(Tempest.defaultModelFor(p.voice),p.model)
                assertNull(recipe.fx)
                assertTrue(pad.oneShot && !pad.loop && pad.velocityLayers.isEmpty())
                assertEquals(if (p.voice==TempestVoice.HAT) 1 else 0,pad.muteGroup)
                assertEquals("accepted",pad.source["status"])
            }
            assertEquals(kit,KitStore.load(dir))
        } finally { dir.deleteRecursively() }
    }

    @Test fun `the original tuned starter remains available with its saved key and models`() {
        val dir=kotlin.io.path.createTempDirectory("tempest-tuned-starter").toFile()
        try {
            val starter=assertNotNull(StarterKits.byId("tempest-tuned"))
            val kit=starter.render("Tempest Tuned Test",dir)
            assertFalse(starter.seeded)
            assertEquals(KeySpec.parse("Eminpent"),kit.key)
            assertEquals(16,kit.pads.size)
            assertTrue(kit.pads.all { (PadRecipe.fromJsonValue(it.recipe!!).patch as TempestPatch).model==2 })
            val notes=kit.pads.take(8).map { (PadRecipe.fromJsonValue(it.recipe!!).patch as TempestPatch).macros.getValue("TUNE") }.map { Tempest.midiFor(it) }
            assertEquals(listOf(28,31,33,35,38,40,43,45),notes)
            assertEquals(kit,KitStore.load(dir))
        } finally { dir.deleteRecursively() }
    }
}
