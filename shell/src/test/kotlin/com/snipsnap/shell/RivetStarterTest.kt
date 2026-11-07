package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.RivetKits
import com.snipsnap.synth.RivetPatch
import com.snipsnap.synth.RivetVoice
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.*

class RivetStarterTest {
    @Test fun `industrial room search finds RIVET under drums`() {
        assertEquals(EngineCategory.DRUMS, EngineCatalog.entry("RIVET")?.primary)
        val names = EngineCatalog.ENTRIES.keys.toList()
        assertTrue("RIVET" in filterEngines(names, { emptyList() }, "warehouse", setOf(EngineCategory.DRUMS)))
    }

    @Test fun `all three rooms deliver the accepted bank with three layers and hat chokes`() {
        val temp = Files.createTempDirectory("rivet-starters").toFile()
        try {
            for ((room, size) in listOf("workshop" to .10f, "factory" to .55f, "warehouse" to 1f)) {
                val starter = assertNotNull(StarterKits.byId("rivet-$room"))
                assertFalse(starter.seeded)
                assertNull(starter.key)
                val dir = File(temp, room)
                val kit = starter.render("Rivet $room", dir)
                assertEquals(kit, KitStore.load(dir))
                assertFalse(Preflight.check(kit, dir).blocked())
                assertEquals((1..16).toList(), kit.pads.map { it.slot })
                val accepted = RivetKits.patches(size)
                for ((i, pad) in kit.pads.withIndex()) {
                    val patch = assertIs<RivetPatch>(PadRecipe.fromJsonValue(assertNotNull(pad.recipe)).patch)
                    assertEquals(accepted[i], patch)
                    assertNull(SynthRoot.of(patch))
                    assertEquals(3, pad.velocityLayers.size)
                    assertEquals(127, pad.velocityLayers.last().velEnd)
                    val hats = patch.voice in setOf(RivetVoice.HAT_CLOSED, RivetVoice.HAT_OPEN)
                    assertEquals(if (hats) 1 else 0, pad.muteGroup)
                    val actual = WavReader.read(File(dir, pad.sampleFile))
                    val expected = patch.render()
                    assertEquals(expected.frameCount, actual.frameCount)
                    assertTrue(actual.samples.indices.all { abs(actual.samples[it] - expected.samples[it]) < 2e-7f })
                }
            }
        } finally { temp.deleteRecursively() }
    }
}
