package com.snipsnap.kit

import com.snipsnap.audio.Snip
import com.snipsnap.audio.SmplChunk
import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.mpc3.Mpc3TrackWriter
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Export consumes already-baked audio; :kit neither loads Aquarium DSP nor carries its source store. */
class AquariumBakedExportTest {
    private val temp = kotlin.io.path.createTempDirectory("aquarium-baked-export").toFile()

    @AfterTest fun cleanUp() { temp.deleteRecursively() }

    private val printedRecipe = Json.parse(
        """{"aquariumPad":1,"source":"${"a".repeat(64)}","settings":{"aquarium":1,"model":1,"seed":1337},"render":{"bpm":120,"tailSeconds":0.1}}""",
    ) as JsonValue.Obj

    private val opaqueRecipe = Json.parse(
        """{"future":{"aquariumPad":1,"boardPad":{"keep":"nested opaque history"}},"aquarium":{"model":1,"seed":42},"unknown":[1,null,"preserve"]}""",
    ) as JsonValue.Obj

    private fun stereo(gain: Double, rightHz: Double): Snip {
        val frames = 4410
        return Snip(FloatArray(frames * 2) { index ->
            val frame = index / 2
            val channel = index % 2
            val hz = if (channel == 0) 220.0 else rightHz
            val level = if (channel == 0) gain else gain * .61
            (level * sin(2 * PI * hz * frame / 44100) * exp(-frame / 1500.0)).toFloat()
        }, 2, 44100)
    }

    private fun fixture(name: String): Pair<Kit, File> {
        val dir = File(temp, "$name-source").apply { mkdirs() }
        WavWriter.write(File(dir, "Aquarium_soft.wav"), stereo(.17, 330.0), WavWriter.BitDepth.PCM_24,
            smpl = SmplChunk(rootNote = 57))
        WavWriter.write(File(dir, "Aquarium_main.wav"), stereo(.57, 440.0), WavWriter.BitDepth.PCM_24,
            smpl = SmplChunk(rootNote = 57))
        WavWriter.write(File(dir, "Opaque_main.wav"), stereo(.29, 550.0), WavWriter.BitDepth.PCM_24)
        // A source-backed wrapper is opaque to :kit. Its hidden state is
        // deliberately absent: neither export nor the MPC needs it to play.
        val kit = Kit(name, listOf(
            KitPad(1, "Aquarium_main.wav", displayName = "Printed environment", recipe = printedRecipe,
                source = mapOf("origin" to "Captured source", "rawSha256" to "previous take"),
                velocityLayers = listOf(
                    KitLayer("Aquarium_soft.wav", 1, 63),
                    KitLayer("Aquarium_main.wav", 64, 127),
                )),
            KitPad(2, "Opaque_main.wav", recipe = opaqueRecipe, source = mapOf("credit" to "Original performer")),
        ))
        KitStore.save(kit, dir)
        return kit to dir
    }

    private data class RoundTrip(val sidecar: Kit, val samplesDir: File, val imported: Kit, val importedDir: File)

    private fun roundTrip(kit: Kit, source: File, native: Boolean): RoundTrip {
        val output = File(temp, "${kit.name}-export")
        val importedRoot = File(temp, "${kit.name}-import")
        if (native) {
            val result = Mpc3Exporter.exportTrack(kit, source, output)
            val samples = File(output, Mpc3TrackWriter.trackDataDirName(kit.name))
            val imported = Mpc3Importer.import(result.program, importedRoot)
            return RoundTrip(KitStore.load(samples), samples, imported.kit, imported.directory)
        }
        val result = KitExporter.exportProgramFolder(kit, source, output)
        val archive = File(temp, "${kit.name}.xpn")
        ZipOutputStream(archive.outputStream()).use { zip ->
            result.directory.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(result.directory).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        val imported = XpnImporter.import(archive, importedRoot)
        return RoundTrip(KitStore.load(result.directory), result.directory, imported.kit, imported.directory)
    }

    @Test
    fun `MPC2 and MPC3 roundtrips keep baked Aquarium stereo and zones without orphan editing history`() {
        for (native in listOf(false, true)) {
            val (kit, source) = fixture(if (native) "Aquarium Native" else "Aquarium Compatibility")
            val originalFiles = files(source)
            val trip = roundTrip(kit, source, native)
            assertEquals(originalFiles, files(source), "export/import cannot mutate the editable source kit")
            assertEquals(printedRecipe, KitStore.load(source).pad(1)!!.recipe)
            assertEquals("previous take", kit.pad(1)!!.source["rawSha256"])

            val flattened = assertNotNull(trip.sidecar.pad(1))
            val imported = assertNotNull(trip.imported.pad(1))
            assertNull(flattened.recipe, "the flat program has no Aquarium source store")
            assertNull(imported.recipe, "reimport must treat the printed WAV as its source")
            assertEquals("Captured source", imported.source["origin"])
            assertEquals(hash(File(trip.samplesDir, flattened.sampleFile)), flattened.source["rawSha256"])
            assertEquals(hash(File(trip.importedDir, imported.sampleFile)), imported.source["rawSha256"])
            assertFalse(trip.samplesDir.walkTopDown().any { it.name == ".aquarium" })
            assertEquals(kit.pad(1)!!.velocityLayers.map { it.velStart to it.velEnd },
                imported.velocityLayers.map { it.velStart to it.velEnd })

            val sourceFiles = kit.pad(1)!!.velocityLayers.map { it.sampleFile }
            for ((index, name) in sourceFiles.withIndex()) {
                val original = File(source, name)
                val exported = File(trip.samplesDir, flattened.velocityLayers[index].sampleFile)
                val reimported = File(trip.importedDir, imported.velocityLayers[index].sampleFile)
                assertStereoPreserved(original, exported)
                assertStereoPreserved(original, reimported)
            }

            // A nested marker or self-contained settings value is opaque
            // metadata, rather than a source-backed top-level pad wrapper.
            assertEquals(opaqueRecipe, trip.sidecar.pad(2)!!.recipe)
            assertEquals(opaqueRecipe, trip.imported.pad(2)!!.recipe)
            assertEquals("Original performer", trip.imported.pad(2)!!.source["credit"])
            assertStereoPreserved(File(source, kit.pad(2)!!.sampleFile),
                File(trip.importedDir, trip.imported.pad(2)!!.sampleFile))
        }
    }

    private fun assertStereoPreserved(original: File, copied: File) {
        assertContentEquals(original.readBytes(), copied.readBytes(), "exported WAV bytes stay exact")
        val expected = WavReader.read(original)
        val decoded = WavReader.read(copied)
        assertEquals(2, decoded.channels)
        assertEquals(expected.sampleRate, decoded.sampleRate)
        assertEquals(expected.frameCount, decoded.frameCount)
        assertContentEquals(expected.samples, decoded.samples, "decoding retains both channels")
        assertEquals(WavReader.readSmpl(original), WavReader.readSmpl(copied), "the finite WAV's embedded root travels unchanged")
        assertNull(WavReader.readSmpl(copied)?.loop, "a finite print must not acquire a sustain loop on export")
        assertTrue((0 until decoded.frameCount).any { frame ->
            decoded.samples[frame * 2] != decoded.samples[frame * 2 + 1]
        }, "the independent stereo channels must not be folded or duplicated")
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun files(dir: File): Map<String, String> = dir.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(dir).invariantSeparatorsPath to hash(it) }
}
