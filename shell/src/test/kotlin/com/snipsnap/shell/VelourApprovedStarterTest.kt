package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.kit.*
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.VelourApprovedKits
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.*

class VelourApprovedStarterTest {
    @Test fun `three starters preserve approved PCM through saving velocity selection and both MPC exports`() {
        val root = createTempDirectory("velour-approved").toFile()
        try {
            for (t in VelourApprovedKits.Treatment.entries) {
                val id = if (t == VelourApprovedKits.Treatment.VELOUR) "velour" else "velour-${t.id}"
                val starter = assertNotNull(StarterKits.byId(id))
                assertFalse(starter.seeded)
                assertEquals(84f, starter.tempoBpm)
                assertEquals(1, StarterShelf.GROUPS.count { id in it.ids })
                val dir = root.resolve(id)
                val kit = starter.render("Velour ${t.id}", dir)
                val saved = KitStore.load(dir)
                assertEquals(kit, saved)
                val expected = VelourApprovedKits.build(t)
                assertEquals(expected.size, saved.pads.size)
                for ((index, pad) in saved.pads.withIndex()) {
                    assertEquals(expected[index].displayName, pad.displayName)
                    assertEquals(expected[index].level, pad.level)
                    for ((key, value) in expected[index].source) assertEquals(value, pad.source[key])
                    assertEquals(3, pad.velocityLayers.size)
                    val files = pad.velocityLayers.map { dir.resolve(it.sampleFile) }
                    val actual = files.map { WavReader.read(it) }
                    for ((layer, source) in (expected[index].softVariants + expected[index].snip).withIndex()) {
                        assertEquals(source.frameCount, actual[layer].frameCount)
                        assertTrue(source.samples.indices.all { abs(source.samples[it] - actual[layer].samples[it]) <= 2e-7f })
                    }
                    val framesByFile = pad.velocityLayers.mapIndexed { layer, spec ->
                        spec.sampleFile to actual[layer].frameCount.toLong()
                    }.toMap()
                    for (velocity in 1..127) {
                        val selected = pad.velocityLayers.indexOfFirst { velocity in it.velStart..it.velEnd }
                        val hit = assertNotNull(PadHit.resolve(pad, velocity / 127f, 0) { filename ->
                            framesByFile[filename]
                        })
                        assertEquals(pad.velocityLayers[selected].sampleFile, hit.sampleFile)
                        assertEquals(0L, hit.startFrame)
                        assertEquals(actual[selected].frameCount.toLong(), hit.endFrameExclusive)
                    }
                }
                assertEquals(1, saved.pads[2].muteGroup)
                assertEquals(1, saved.pads[3].muteGroup)
                assertTrue(saved.pads.takeLast(4).all { it.muteGroup == 0 })
                val dest = root.resolve("export-${t.id}")
                val result = Mpc3Exporter.exportTrack(saved, dir, dest, mpc2Twin = true)
                val data = dest.resolve(Mpc3TrackWriter.trackDataDirName(saved.name))
                for ((program, format) in listOf(result.program to ExportFormat.MPC3_TRACK,
                    data.resolve("${saved.name}.xpm") to ExportFormat.PROGRAM_FOLDER)) {
                    assertTrue(ExportReadBack.verifyProgram(saved, program, format).none { it.severity == Severity.FAIL })
                }
                for (pad in saved.pads) for (layer in pad.velocityLayers) {
                    assertContentEquals(dir.resolve(layer.sampleFile).readBytes(), data.resolve(layer.sampleFile).readBytes())
                }
            }
        } finally { root.deleteRecursively() }
    }
}
