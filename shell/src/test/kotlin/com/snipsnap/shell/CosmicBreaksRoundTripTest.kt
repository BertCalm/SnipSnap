package com.snipsnap.shell

import com.snipsnap.kit.*
import com.snipsnap.synth.CosmicBreaksGenres
import com.snipsnap.audio.WavReader
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class CosmicBreaksRoundTripTest {
    @Test fun `every genre keeps velocity and cycling through shelf save SFZ export import and playback`() {
        val root = createTempDirectory("cosmic-grid-roundtrip").toFile()
        try {
            for (genre in CosmicBreaksGenres.Genre.entries) {
                val dir = root.resolve(genre.id)
                val starter = assertNotNull(StarterKits.byId(genre.id))
                assertEquals(genre.bpm, starter.tempoBpm)
                val kit = starter.render(genre.title, dir)
                assertEquals(kit, KitStore.load(dir))
                assertEquals(18, kit.pads.size)
                val sfz = SfzWriter.write(kit, dir, root.resolve("export"))
                val imported = SfzImporter.import(sfz, root.resolve("import"))
                assertTrue(imported.skipped.isEmpty())
                assertEquals(18, imported.kit.pads.size)
                for ((i, pad) in kit.pads.withIndex()) {
                    val back = imported.kit.pads[i]
                    assertEquals(pad.chain, back.chain)
                    val wav = WavReader.read(dir.resolve(pad.sampleFile))
                    assertTrue(dir.resolve(pad.sampleFile).readBytes().contentEquals(imported.directory.resolve(back.sampleFile).readBytes()))
                    for (v in 1..127) for (hitIndex in 0..8) {
                        val before = assertNotNull(PadHit.resolve(pad, v / 127f, hitIndex) { wav.frameCount.toLong() })
                        val after = assertNotNull(PadHit.resolve(back, v / 127f, hitIndex) { wav.frameCount.toLong() })
                        val zone = assertNotNull(pad.chain?.zoneFor(v))
                        val window = requireNotNull(pad.chain).window(zone.baseSlice + hitIndex % 4, wav.frameCount.toLong())
                        assertEquals(window.first, after.startFrame)
                        assertEquals(window.last + 1, after.endFrameExclusive)
                        assertEquals(before.gainLeft, after.gainLeft, 1e-6f)
                        assertEquals(before.gainRight, after.gainRight, 1e-6f)
                        assertEquals(before.pitchRatio, after.pitchRatio)
                    }
                }
                assertEquals(1, StarterShelf.GROUPS.count { genre.id in it.ids })
            }
        } finally { root.deleteRecursively() }
    }
}
