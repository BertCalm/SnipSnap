package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class RimeStarterKitTest {
    @Test fun `complete starter reaches all six voices with sixteen finite native velocity pads`() {
        val root = createTempDirectory("rime-complete-starter").toFile()
        try {
            val starter = assertNotNull(StarterKits.byId("rime-complete"))
            val kit = starter.render("Rime Complete", root)
            assertEquals(kit, KitStore.load(root))
            assertEquals((1..16).toList(), kit.pads.map { it.slot })
            assertFalse(Preflight.check(kit, root).blocked())
            val patches = kit.pads.map { assertIs<RimePatch>(PadRecipe.fromJsonValue(requireNotNull(it.recipe)).patch) }
            assertEquals(RimeKits.completePatches(),patches)
            assertEquals(RimeVoice.entries.toSet(),patches.map { it.voice }.toSet())
            for ((pad,patch) in kit.pads.zip(patches)) {
                assertTrue(pad.oneShot); assertEquals(6,patch.model); assertFalse(Rime.isLoop(patch.macros))
                assertEquals(listOf(1 to 41,42 to 84,85 to 127),pad.velocityLayers.map { it.velStart to it.velEnd })
                val expected = ByteArrayOutputStream().also { WavWriter.write(it,patch.render()) }.toByteArray()
                assertContentEquals(expected,File(root,pad.sampleFile).readBytes(),pad.displayName)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `fresh tape exposes the preserved sixteen-pad bank with native velocity layers`() {
        val root = createTempDirectory("rime-starter").toFile()
        try {
            val starter = assertNotNull(StarterKits.byId("rime"))
            assertEquals("RIME", starter.displayName); assertFalse(starter.seeded)
            assertEquals(KeySpec.parse("Cminpent"), starter.key)
            val kit = starter.render("Rime Bank", root)
            assertEquals(kit, KitStore.load(root))
            assertEquals((1..16).toList(), kit.pads.map { it.slot })
            assertFalse(Preflight.check(kit, root).blocked())
            assertEquals(RimeKits.patches(), kit.pads.map { PadRecipe.fromJsonValue(requireNotNull(it.recipe)).patch })
            for (pad in kit.pads) {
                assertTrue(pad.oneShot)
                assertEquals(0, pad.tuneCoarse); assertEquals(0, pad.tuneFine)
                assertEquals(listOf(1 to 41, 42 to 84, 85 to 127), pad.velocityLayers.map { it.velStart to it.velEnd })
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                val patch = assertIs<RimePatch>(recipe.patch)
                assertEquals(4, patch.model); assertNull(recipe.fx)
                val expected = ByteArrayOutputStream().also { WavWriter.write(it, recipe.render()) }.toByteArray()
                assertContentEquals(expected, File(root, pad.sampleFile).readBytes(), pad.displayName)
            }
        } finally { root.deleteRecursively() }
    }
}
