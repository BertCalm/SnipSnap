package com.snipsnap.synth

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Preflight
import com.snipsnap.kit.blocked
import com.snipsnap.xpm.WavInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApsisKitTest {
    @Test
    fun `one authoritative bank retains six presets and distinct musical pad roles`() {
        val bank = ApsisKitBank.all()
        assertEquals((1..16).toList(), bank.map { it.slot })
        assertEquals(16, bank.map { it.id }.toSet().size)
        assertEquals(ApsisPresets.all().map { it.name }.toSet(), bank.map { it.presetName }.toSet())
        assertEquals(mapOf(ApsisPadRole.BASS to 4, ApsisPadRole.KEY to 4, ApsisPadRole.CHORD_TONE to 4,
            ApsisPadRole.COLOR to 2, ApsisPadRole.HELD to 2), bank.groupingBy { it.role }.eachCount())
        assertEquals(listOf(36, 39, 43, 46), bank.take(4).map { it.patch.midi })
        assertEquals(listOf(48, 55), bank.takeLast(2).map { it.patch.midi })
        for (entry in bank) {
            assertEquals(3, entry.patch.config.modelVersion)
            assertEquals(1f, entry.level)
            assertEquals(entry.slot, entry.muteGroup)
            assertEquals(entry.role == ApsisPadRole.HELD, entry.held)
            assertEquals(entry.patch, entry.layers().last().patch)
            if (entry.slot <= 12) {
                assertEquals(listOf(1 to 41, 42 to 84, 85 to 127), entry.layers().map { it.velStart to it.velEnd })
                assertTrue(entry.layers().zipWithNext().all { (a, b) -> a.patch.velocity < b.patch.velocity })
            } else assertEquals(listOf(1 to 127), entry.layers().map { it.velStart to it.velEnd })
        }
    }

    @Test
    fun `native twins retain forty dry PCM samples complete recipes and genuine held flags`() {
        val root = createTempDirectory("apsis-kit").toFile()
        try {
            val archive = ApsisKitGenerator.generate(root)
            val destination = File(root, ApsisKitGenerator.KIT_NAME)
            val data = File(destination, "${ApsisKitGenerator.KIT_NAME}_[TrackData]")
            val kit = KitStore.load(data)
            assertEquals(16, kit.pads.size)
            assertFalse(Preflight.check(kit, data).blocked())
            ApsisKitGenerator.verifyPrograms(destination, kit)
            val reexported = File(root, "generic-reexport")
            Mpc3Exporter.exportTrack(kit, data, reexported, mpc2Twin = true)
            ApsisKitGenerator.verifyPrograms(reexported, kit)
            val wavs = requireNotNull(data.listFiles { it.extension == "wav" })
            assertEquals(40, wavs.size)
            assertFalse(File(destination, "Source Kit").exists(), "the archive must not duplicate all PCM payloads")
            for (pad in kit.pads) {
                val entry = ApsisKitBank.pad(pad.slot)
                val recipe = PadRecipe.fromJsonValue(requireNotNull(pad.recipe))
                assertEquals(entry.patch, recipe.patch)
                assertEquals(null, recipe.fx)
                assertEquals(null, recipe.board)
                assertEquals(!entry.held, pad.oneShot)
                assertEquals(entry.held, pad.loop)
                assertEquals(if (entry.held) DrumClass.LOOP else DrumClass.TONAL, pad.drumClass)
                assertEquals(entry.slot, pad.muteGroup)
                assertEquals(1f, pad.level)
                assertEquals(.5f, pad.pan)
                assertEquals(0, pad.tuneCoarse)
                assertEquals(0, pad.tuneFine)
                assertEquals(null, pad.humanize)
                assertTrue(pad.source.getValue("nativeExport").contains("generateApsisKit"))
                for ((index, layer) in pad.velocityLayers.withIndex()) {
                    val sample = File(data, layer.sampleFile)
                    val info = WavInfo.read(sample)
                    assertEquals(44_100, info.sampleRate)
                    assertEquals(1, info.channels)
                    assertEquals(24, info.bitsPerSample)
                    assertEquals(SmplChunk(entry.patch.midi,
                        if (entry.held) SmplChunk.Loop(0, info.frameCount) else null), WavReader.readSmpl(sample))
                    val saved = PadRecipe.fromJsonText(File(destination, "recipes/${entry.label}-v${index + 1}.json").readText())
                    assertEquals(entry.layers()[index].patch, saved.patch)
                }
                if (entry.held) {
                    val decoded = WavReader.read(File(data, pad.sampleFile))
                    assertTrue(Keys.seamError(decoded.samples + decoded.samples, decoded.frameCount) < 1e-3,
                        "quantized held pad ${entry.label} lost its wrap")
                }
            }
            // A low native velocity zone and both exported held notes replay from their complete recipes.
            for ((slot, layerIndex) in listOf(1 to 0, 15 to 0, 16 to 0)) {
                val pad = kit.pads[slot - 1]
                val saved = PadRecipe.fromJsonText(File(destination, "recipes/${ApsisKitBank.pad(slot).label}-v${layerIndex + 1}.json").readText())
                val encoded = ByteArrayOutputStream().also { WavWriter.write(it, saved.render()) }.toByteArray()
                assertContentEquals(WavReader.read(encoded).samples,
                    WavReader.read(File(data, pad.velocityLayers[layerIndex].sampleFile)).samples,
                    "recipe replay must match PCM independently of added RIFF metadata")
            }
            val manifest = Json.parse(File(destination, "kit-manifest.json").readText()).obj()
            assertEquals(16, manifest.getValue("padCount").int())
            assertEquals(40, manifest.getValue("sampleCount").int())
            assertTrue(manifest.getValue("reexportLimit").str().contains("generic kit exporter"))
            ZipFile(archive).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertEquals(40, names.count { it.endsWith(".wav") })
                assertTrue(names.all { it.startsWith("${kit.name}/") && ".." !in it.split('/') })
                assertTrue("${kit.name}/${kit.name}.xtd" in names)
                assertTrue("${kit.name}/${kit.name}_[TrackData]/${kit.name}.xpm" in names)
                assertTrue("${kit.name}/${kit.name}_[TrackData]/kit.json" in names)
            }
            assertFalse(File(root, ".apsis-work").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
