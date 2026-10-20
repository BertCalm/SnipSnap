package com.snipsnap.shell

import com.snipsnap.audio.KeySpec
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.TrioKits
import com.snipsnap.synth.TrioPatch
import java.io.ByteArrayOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TrioStarterKitTest {
    @Test
    fun `TRIO starter saves a playable finite bank with exact regenerable recipes`() {
        val dir = createTempDirectory("trio-starter").toFile()
        try {
            val starter = StarterKits.ALL.single { it.id == "trio" }
            assertFalse(starter.seeded)
            assertEquals(KeySpec.parse("Cminpent"), starter.key)
            val kit = starter.render("TRIO Conversations", dir)
            assertFalse(Preflight.check(kit, dir).blocked())
            assertEquals(kit, KitStore.load(dir))
            assertEquals((1..16).toList(), kit.pads.map { it.slot })
            for ((pad, patch) in kit.pads.zip(TrioKits.patches())) {
                assertTrue(pad.oneShot)
                assertEquals(0, pad.muteGroup, "reply tails must ring through")
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                assertEquals(patch, assertIs<TrioPatch>(recipe.patch))
                val replay = ByteArrayOutputStream().also { WavWriter.write(it, recipe.render()) }
                assertContentEquals(replay.toByteArray(), dir.resolve(pad.sampleFile).readBytes(), pad.displayName)
            }
        } finally { dir.deleteRecursively() }
    }
}
