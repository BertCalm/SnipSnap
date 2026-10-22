package com.snipsnap.shell

import com.snipsnap.audio.Snip
import com.snipsnap.audio.WavWriter
import com.snipsnap.json.Json
import com.snipsnap.json.JsonValue
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.KitLayer
import com.snipsnap.kit.KitPad
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Mpc3Importer
import com.snipsnap.kit.XpnImporter
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.BoardRecipe
import com.snipsnap.synth.BoardType
import com.snipsnap.synth.PadRecipe
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

/** Flat MPC programs carry the printed board sound, without dangling source-backed editing history. */
class BoardBakedExportTest {
    private val temp = kotlin.io.path.createTempDirectory("board-baked-export").toFile()
    @AfterTest fun cleanUp() { temp.deleteRecursively() }

    private fun tone(frequency: Double, gain: Double = .4): Snip = Snip(FloatArray(2400) { i ->
        (gain * sin(2 * PI * frequency * i / 44100) * exp(-i / 800.0)).toFloat()
    }, 1, 44100)

    private fun opaque(): JsonValue.Obj = Json.parse(
        """{"future":{"boardPad":{"nested":"keep exactly"}},"unknown":[1,null,"source history"]}""",
    ) as JsonValue.Obj

    private fun fixture(name: String, layered: Boolean): KitBuilderModel {
        val dir = File(temp, name).apply { mkdirs() }
        WavWriter.write(File(dir, "main.wav"), tone(220.0))
        if (layered) WavWriter.write(File(dir, "soft.wav"), tone(330.0, .15))
        val layers = if (layered) listOf(KitLayer("soft.wav", 1, 63), KitLayer("main.wav", 64, 127)) else emptyList()
        val pad = KitPad(1, "main.wav", displayName = "KEPT HIT", recipe = opaque(),
            velocityLayers = layers, source = mapOf("origin" to "Audition source"))
        KitStore.save(Kit(name, listOf(pad)), dir)
        return KitBuilderModel.open(dir)
    }

    private data class RoundTrip(val sidecar: Kit, val exportedSamples: File, val imported: Kit, val importedDir: File)

    private fun roundTrip(model: KitBuilderModel, label: String, native: Boolean): RoundTrip {
        val output = File(temp, "$label-export")
        return if (native) {
            val result = Mpc3Exporter.exportTrack(model.kit, model.kitDir, output)
            val samples = File(output, Mpc3TrackWriter.trackDataDirName(model.kit.name))
            val imported = Mpc3Importer.import(result.program, File(temp, "$label-import"))
            RoundTrip(KitStore.load(samples), samples, imported.kit, imported.directory)
        } else {
            val result = KitExporter.exportProgramFolder(model.kit, model.kitDir, output)
            val archive = File(temp, "$label.xpn")
            ZipOutputStream(archive.outputStream()).use { zip ->
                result.directory.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.relativeTo(result.directory).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            val imported = XpnImporter.import(archive, File(temp, "$label-import"))
            RoundTrip(KitStore.load(result.directory), result.directory, imported.kit, imported.directory)
        }
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun files(dir: File): Map<String, String> = dir.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(dir).invariantSeparatorsPath to hash(it) }

    @Test
    fun `native and XPN roundtrips keep WIDE and RUSH audio without orphan board wrappers`() {
        for (type in BoardType.entries) for (native in listOf(false, true)) {
            val label = "Baked ${type.name} ${if (native) "Native" else "XPN"}"
            val model = fixture(label, layered = true)
            val kept = BoardSheet.apply(model, 1, .7f, type)
            model.save()
            val before = files(model.kitDir)
            val trip = roundTrip(model, label, native)
            assertEquals(before, files(model.kitDir), "export and import leave the editable parent untouched")
            assertEquals(kept.recipe, model.pad(1)!!.recipe)
            assertEquals(type, BoardSheet.read(model.pad(1)!!.recipe)?.type)

            val sidecarPad = assertNotNull(trip.sidecar.pad(1))
            val importedPad = assertNotNull(trip.imported.pad(1))
            assertNull(sidecarPad.recipe, "a flat export cannot carry the board's immutable .boards source")
            assertNull(importedPad.recipe, "reimport must not advertise an editable board with no source")
            assertNull(BoardSheet.read(importedPad.recipe))
            assertFalse(File(trip.exportedSamples, ".boards").exists())
            assertFalse(File(trip.importedDir, ".boards").exists())
            assertEquals("Audition source", importedPad.source["origin"])
            assertEquals(hash(File(trip.exportedSamples, sidecarPad.sampleFile)), sidecarPad.source["rawSha256"])
            assertContentEquals(File(model.kitDir, kept.sampleFile).readBytes(),
                File(trip.exportedSamples, sidecarPad.sampleFile).readBytes())
            val baked = File(trip.importedDir, importedPad.sampleFile).readBytes()
            assertContentEquals(File(model.kitDir, kept.sampleFile).readBytes(), baked)
            assertEquals(kept.velocityLayers.size, importedPad.velocityLayers.size)
            assertEquals(kept.velocityLayers.map { it.velStart to it.velEnd },
                importedPad.velocityLayers.map { it.velStart to it.velEnd })
            for ((old, imported) in kept.velocityLayers.zip(importedPad.velocityLayers)) {
                assertContentEquals(File(model.kitDir, old.sampleFile).readBytes(),
                    File(trip.importedDir, imported.sampleFile).readBytes())
            }

            // This flattened sound is a new source if treated again. REMOVE
            // must return that sound, without reaching back to the parent kit.
            val reopened = KitBuilderModel.open(trip.importedDir)
            assertFalse(BoardSheet.canUndo(reopened, 1))
            BoardSheet.apply(reopened, 1, .35f, type)
            val restored = BoardSheet.remove(reopened, 1)
            assertNull(restored.recipe)
            assertContentEquals(baked, File(trip.importedDir, restored.sampleFile).readBytes())
        }
    }

    @Test
    fun `flat sidecars preserve ordinary opaque and typed board recipes unchanged`() {
        for (type in BoardType.entries) for (native in listOf(false, true)) {
            val label = "Recipes ${type.name} ${if (native) "Native" else "XPN"}"
            val model = fixture(label, layered = false)
            val typed = PadRecipe(board = BoardRecipe(type, .7f)).toJsonValue()
            model.assign(2, tone(440.0))
            model.update(2) { it.copy(recipe = typed) }
            model.save()
            val before = files(model.kitDir)
            val trip = roundTrip(model, label, native)
            assertEquals(before, files(model.kitDir))
            assertEquals(opaque(), trip.sidecar.pad(1)!!.recipe, "nested boardPad text is opaque history")
            assertEquals(typed, trip.sidecar.pad(2)!!.recipe, "typed boards have no external baseline references")
            assertEquals(opaque(), trip.imported.pad(1)!!.recipe)
            assertEquals(typed, trip.imported.pad(2)!!.recipe)
            for (pad in model.kit.pads) {
                val imported = trip.imported.pad(pad.slot)!!
                assertContentEquals(File(model.kitDir, pad.sampleFile).readBytes(),
                    File(trip.importedDir, imported.sampleFile).readBytes())
            }
        }
    }
}
