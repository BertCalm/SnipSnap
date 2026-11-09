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
                val takes = expected[i].velocityTakes.flatten()
                assertEquals(takes.sumOf { it.frameCount }, wav.frameCount)
                val chain = assertNotNull(pad.chain)
                assertEquals(12, chain.sliceCount)
                for ((takeIndex, take) in takes.withIndex()) {
                    val window = chain.window(takeIndex, wav.frameCount.toLong())
                    assertTrue(take.samples.indices.all { n -> kotlin.math.abs(take.samples[n] - wav.samples[window.first.toInt() + n]) < 2e-7f })
                }
                for (v in 1..127) {
                    val zone = assertNotNull(chain.zoneFor(v))
                    assertEquals(if (v < 42) 0 else if (v < 85) 4 else 8, zone.baseSlice)
                    assertEquals(4, zone.cycle)
                    for (hitIndex in 0..8) {
                        val hit = assertNotNull(PadHit.resolve(pad, v / 127f, hitIndex) { wav.frameCount.toLong() })
                        val window = chain.window(zone.baseSlice + hitIndex % 4, wav.frameCount.toLong())
                        assertEquals(window.first, hit.startFrame)
                        assertEquals(window.last + 1, hit.endFrameExclusive)
                    }
                }
            }
            assertEquals(1, kit.pads[2].muteGroup)
            assertEquals(1, kit.pads[3].muteGroup)
            assertEquals(0, kit.pads[12].muteGroup)
            assertEquals(0, kit.pads[13].muteGroup)
            assertEquals(1, StarterShelf.GROUPS.count { "cosmic-breaks" in it.ids })
        } finally { dir.deleteRecursively() }
    }
}
