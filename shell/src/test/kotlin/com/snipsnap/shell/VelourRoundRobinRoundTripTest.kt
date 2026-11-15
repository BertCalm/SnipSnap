package com.snipsnap.shell

import com.snipsnap.kit.*
import com.snipsnap.synth.VelourApprovedKits
import com.snipsnap.synth.VelourRoundRobinKits
import com.snipsnap.audio.WavReader
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.*

class VelourRoundRobinRoundTripTest {
    @Test fun `all Velour candidates retain every strike through save SFZ import and playback`() {
        val root = createTempDirectory("velour-rr").toFile()
        try {
            for (t in VelourApprovedKits.Treatment.entries) {
                val dir = root.resolve(t.id)
                val rendered = VelourRoundRobinKits.build(t)
                val kit = KitAssembler.assembleArranged("Velour ${t.id} RR", rendered, dir, tempoBpm = 84f)
                assertEquals(kit, KitStore.load(dir))
                val sfz = SfzWriter.write(kit, dir, root.resolve("export-${t.id}"))
                val imported = SfzImporter.import(sfz, root.resolve("import-${t.id}"))
                assertTrue(imported.skipped.isEmpty())
                assertEquals(16, imported.kit.pads.size)
                for ((i, pad) in kit.pads.withIndex()) {
                    val back = imported.kit.pads[i]
                    val chain = assertNotNull(pad.chain)
                    assertEquals(chain, back.chain)
                    assertEquals(pad.muteGroup, back.muteGroup)
                    val wav = WavReader.read(dir.resolve(pad.sampleFile))
                    assertContentEquals(dir.resolve(pad.sampleFile).readBytes(), imported.directory.resolve(back.sampleFile).readBytes())
                    for ((n, take) in rendered[i].velocityTakes.flatten().withIndex()) {
                        val w = chain.window(n, wav.frameCount.toLong())
                        assertEquals(take.frameCount.toLong(), w.last + 1 - w.first)
                        assertTrue(take.samples.indices.all { abs(take.samples[it] - wav.samples[w.first.toInt()+it]) <= 2e-7f })
                    }
                    for (v in 1..127) for (hitIndex in 0..8) {
                        val before = assertNotNull(PadHit.resolve(pad, v/127f, hitIndex) { wav.frameCount.toLong() })
                        val after = assertNotNull(PadHit.resolve(back, v/127f, hitIndex) { wav.frameCount.toLong() })
                        val zone = assertNotNull(chain.zoneFor(v))
                        val expectedZone = if (v < 42) 0 else if (v < 85) 1 else 2
                        assertEquals(expectedZone * 4, zone.baseSlice)
                        assertEquals(4, zone.cycle)
                        val w = chain.window(zone.baseSlice + hitIndex%4, wav.frameCount.toLong())
                        assertEquals(w.first, after.startFrame)
                        assertEquals(w.last+1, after.endFrameExclusive)
                        assertEquals(before.gainLeft, after.gainLeft, 1e-6f)
                        assertEquals(before.gainRight, after.gainRight, 1e-6f)
                        assertEquals(before.pitchRatio, after.pitchRatio)
                    }
                }
            }
        } finally { root.deleteRecursively() }
    }
}
