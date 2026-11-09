package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.KitStore
import com.snipsnap.synth.CosmicBreaksKits
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class CosmicBreaksStarterTest {
    @Test fun `starter preserves approved pad order tempo provenance and hat choke`() {
        val dir = createTempDirectory("cosmic-breaks").toFile()
        try {
            val starter = assertNotNull(StarterKits.byId("cosmic-breaks"))
            assertFalse(starter.seeded)
            assertEquals(170f, starter.tempoBpm)
            val kit = starter.render("Cosmic Breaks", dir)
            assertEquals(kit, KitStore.load(dir))
            val expected = CosmicBreaksKits.build()
            assertEquals(16, kit.pads.size)
            for ((i, pad) in kit.pads.withIndex()) {
                assertEquals(expected[i].displayName, pad.displayName)
                assertEquals(expected[i].source, pad.source)
                assertNull(pad.recipe)
                val wav = WavReader.read(dir.resolve(pad.sampleFile))
                assertEquals(expected[i].snip.frameCount, wav.frameCount)
                assertTrue(wav.samples.indices.all { n ->
                    kotlin.math.abs(expected[i].snip.samples[n] - wav.samples[n]) < 2e-7f
                }, "${pad.displayName}: PCM24 writer round-trip")
            }
            assertEquals(1, kit.pads[2].muteGroup)
            assertEquals(1, kit.pads[3].muteGroup)
            assertEquals(0, kit.pads[12].muteGroup)
            assertEquals(0, kit.pads[13].muteGroup)
            assertEquals(1, StarterShelf.GROUPS.count { "cosmic-breaks" in it.ids })
        } finally { dir.deleteRecursively() }
    }
}
