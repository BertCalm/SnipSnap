package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.*
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.synth.PatioPerformance
import java.io.File
import kotlin.test.*

class PatioDeliveryTest {
    @Test fun `all genre kits retain every velocity and take through save playback and native export`() {
        val root = java.nio.file.Files.createTempDirectory("patio-delivery").toFile()
        try {
            val kicks = mutableListOf<ByteArray>()
            for (genre in PatioPerformance.Genre.entries) {
                assertTrue(StarterShelf.GROUPS.flatMap { it.ids }.contains(genre.id))
                val dir = File(root, genre.id)
                val original = requireNotNull(StarterKits.byId(genre.id)).render(genre.title, dir)
                val kit = KitStore.load(dir)
                assertEquals(original, kit)
                assertEquals(18, kit.pads.size)
                assertEquals(1, kit.pads[2].muteGroup); assertEquals(1, kit.pads[3].muteGroup)
                kicks += File(dir, kit.pads.first().sampleFile).readBytes()
                val exported = PatioPrograms.export(kit, dir, File(root, "export-${genre.id}"), genre)
                val data = File(exported.directory, "${kit.name}_[TrackData]")
                val restored = PatioPrograms.reloadExport(data)
                assertEquals(kit, restored)
                val payload = Json.parse(Mpc3Project.read(exported.program).container.payloadText)
                val layers = mutableListOf<Map<String, JsonValue>>()
                fun visit(node: JsonValue) {
                    when (node) {
                        is JsonValue.Obj -> { if ("sliceCycleLength" in node.entries && "sampleName" in node.entries) layers += node.entries; node.entries.values.forEach(::visit) }
                        is JsonValue.Arr -> node.items.forEach(::visit)
                        else -> Unit
                    }
                }
                visit(payload)
                for (pad in restored.pads) {
                    val recipe = PatioPerformance.fromJson(pad.source.getValue("patioPerformanceRecipe"))
                    assertEquals(genre, recipe.genre)
                    val chain = requireNotNull(pad.chain)
                    assertEquals(12, chain.boundaries.size)
                    assertEquals(3, chain.zones!!.size)
                    val sample = WavReader.read(File(data, pad.sampleFile))
                    assertContentEquals(File(dir, pad.sampleFile).readBytes(), File(data, pad.sampleFile).readBytes())
                    for ((z, zone) in chain.zones!!.withIndex()) {
                        assertEquals(4, zone.cycle)
                        for (midi in listOf(maxOf(1, zone.velStart), zone.velEnd)) for (take in 0..4) {
                            val hit = requireNotNull(PadHit.resolve(pad, midi / 127f, take) { sample.frameCount.toLong() })
                            val window = chain.window(z * 4 + take % 4, sample.frameCount.toLong())
                            assertEquals(window.first, hit.startFrame)
                            assertEquals(window.last + 1, hit.endFrameExclusive)
                            assertTrue(hit.gainLeft > 0f && hit.gainRight > 0f)
                        }
                        val layer = layers.single { (it["sampleName"] as? JsonValue.Str)?.value == pad.sampleStem && (it["sliceIndex"] as? JsonValue.Num)?.value == (z * 4).toDouble() }
                        assertEquals(4.0, layer.getValue("sliceCycleLength").num())
                        assertEquals(1.0, layer.getValue("sliceIncrement").num())
                        assertEquals(zone.velStart.toDouble(), layer.getValue("velocityStart").num())
                        assertEquals(zone.velEnd.toDouble(), layer.getValue("velocityEnd").num())
                    }
                }
                for (pad in kit.pads) {
                    val note = com.snipsnap.mpc3.Mpc3Note(com.snipsnap.mpc3.Mpc3Note.noteFor(pad.slot), 0L, .95f)
                    val clip = com.snipsnap.mpc3.Mpc3Clip("Single pad", 1, listOf(note))
                    assertTrue(KitPreview.render(kit, dir, clip = clip).peak() > 0f, "pad ${pad.slot} must sound")
                }
                val before = KitPreview.render(kit, dir, clip = PatioPrograms.groove(genre), tempoBpm = 100f)
                val after = KitPreview.render(restored, data, clip = PatioPrograms.groove(genre), tempoBpm = 100f)
                assertContentEquals(before.samples, after.samples, "exported PCM playback")
                val sfz = SfzWriter.write(kit, dir, File(root, "sfz-${genre.id}"))
                val text = sfz.readText()
                assertEquals(18 * 3 * 4, Regex("seq_position=").findAll(text).count())
            }
            assertFalse(kicks[0].contentEquals(kicks[1])); assertFalse(kicks[1].contentEquals(kicks[2]))
        } finally { root.deleteRecursively() }
    }
}
