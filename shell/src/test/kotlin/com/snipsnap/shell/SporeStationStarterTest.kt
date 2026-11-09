package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.*
import com.snipsnap.synth.SporeStation
import com.snipsnap.mpc3.Mpc3TrackWriter
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class SporeStationStarterTest {
    @Test fun `all genre grids survive saving playback and MPC export`() {
        val root = createTempDirectory("spore-grid").toFile()
        try {
            for (genre in SporeStation.Genre.entries) {
                val id = "spore-${genre.name.lowercase()}"
                val starter = assertNotNull(StarterKits.byId(id))
                assertEquals(1, StarterShelf.GROUPS.count { id in it.ids })
                val dir = root.resolve(id)
                val kit = starter.render("Spore ${genre.name}", dir)
                assertEquals(kit, KitStore.load(dir))
                assertEquals(genre.bpm.toFloat(), kit.tempoBpm)
                assertEquals(18, kit.pads.size)
                val expected = SporeStation.performance(genre)
                for ((i, pad) in kit.pads.withIndex()) {
                    val wav = WavReader.read(dir.resolve(pad.sampleFile))
                    val chain = assertNotNull(pad.chain)
                    assertEquals(9, chain.sliceCount)
                    for ((z, velocity) in listOf(.3f, .6f, 1f).withIndex()) {
                        for (t in 0..3) {
                            val hit = assertNotNull(PadHit.resolve(pad, velocity, t) { wav.frameCount.toLong() })
                            val original = expected[i].velocityTakes[z][t % 3]
                            if (i >= 16) {
                                assertEquals((4.0 * 60 / genre.bpm * 44100).toInt(), original.frameCount)
                                assertEquals(0f, kotlin.math.abs(original.samples.last()), "either signed zero is silence")
                            }
                            assertTrue(original.samples.all { it.isFinite() })
                            assertTrue(original.peak() in .00001f..1.00001f)
                            assertEquals(pad.level * velocity, hit.gainLeft, 1e-6f)
                            assertEquals(original.frameCount.toLong(), hit.endFrameExclusive - hit.startFrame)
                            assertEquals(chain.boundaries[z * 3 + t % 3], hit.startFrame)
                            original.samples.forEachIndexed { f, value ->
                                assertTrue(kotlin.math.abs(value - wav.samples[hit.startFrame.toInt() + f]) <= 3f / 8388608,
                                    "${genre.name} pad $i zone $z take $t frame $f")
                            }
                        }
                        assertTrue(expected[i].velocityTakes[z].zipWithNext().all { (a,b) -> !a.samples.contentEquals(b.samples) },
                            "${genre.name} pad $i zone $z needs distinct takes")
                    }
                    assertTrue(!expected[i].velocityTakes[0][0].samples.contentEquals(expected[i].velocityTakes[2][0].samples))
                }
                val sfz = SfzWriter.write(kit, dir, root.resolve("sfz-$id"))
                val imported = SfzImporter.import(sfz, root.resolve("import-$id"))
                assertTrue(imported.skipped.isEmpty(), imported.skipped.toString())
                assertEquals(18, imported.kit.pads.size)
                for ((original, restored) in kit.pads.zip(imported.kit.pads)) {
                    assertEquals(original.chain, restored.chain)
                    val before = WavReader.read(dir.resolve(original.sampleFile))
                    val after = WavReader.read(imported.directory.resolve(restored.sampleFile))
                    assertContentEquals(before.samples, after.samples)
                }
                val exported = Mpc3Exporter.exportTrack(kit, dir, root.resolve("export-$id"))
                assertFalse(ExportReadBack.verify(kit, ExportOutcome(ExportFormat.MPC3_TRACK,
                    exported.program, root.resolve("export-$id/${Mpc3TrackWriter.trackDataDirName(kit.name)}"), exported.findings)).blocked())
            }
        } finally { root.deleteRecursively() }
    }
}
