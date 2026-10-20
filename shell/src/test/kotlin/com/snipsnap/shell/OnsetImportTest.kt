package com.snipsnap.shell

import com.snipsnap.audio.WavReader
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.mpc3.Mpc3Project
import com.snipsnap.mpc3.Mpc3TrackWriter
import com.snipsnap.synth.OnsetKits
import com.snipsnap.synth.OnsetPatch
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patches
import com.snipsnap.xpm.XpmWriter
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The downloaded audition kit must reach SYNTH through the real share-sheet landing path. */
class OnsetImportTest {
    private val temp = kotlin.io.path.createTempDirectory("onset-import").toFile()

    @AfterTest
    fun cleanUp() { temp.deleteRecursively() }

    private fun source(): Pair<Kit, File> {
        val dir = File(temp, "source")
        val assembled = KitAssembler.assembleArranged("ONSET Import", OnsetKits.acceptance(), dir)
        val kit = assembled.copy(pads = assembled.pads.map { pad ->
            val recipe = PadRecipe.fromJsonValue(assertNotNull(pad.recipe))
            val patch = recipe.patch as OnsetPatch
            // Exercise the exact accepted legacy model through import, not
            // merely a current-version reconstruction with the same macros.
            val saved = if (pad.slot == 1) recipe.copy(patch = patch.copy(modelVersion = 2)) else recipe
            pad.copy(recipe = saved.toJsonValue(), source = mapOf("renderer" to "ONSET", "rawSha256" to hash(File(dir, pad.sampleFile))))
        })
        KitStore.save(kit, dir)
        return kit to dir
    }

    /** The audition download's real layout: XTD plus XPM and kit.json beside the WAVs. */
    private fun export(kit: Kit, source: File, name: String, twin: Boolean): Pair<File, File> {
        val root = File(temp, name)
        val data = File(root, Mpc3TrackWriter.trackDataDirName(kit.name))
        val program = Mpc3Exporter.stageTrack(kit, source, data).copy(level = 1f)
        Mpc3TrackWriter().writeTo(root, program)
        if (twin) XpmWriter().writeTo(data, program.copy(pads = program.pads.map { it?.copy(decay = 0f) }))
        KitStore.save(kit, data)
        return root to data
    }

    private fun zip(root: File, name: String): File = File(temp, "$name.zip").also { archive ->
        ZipOutputStream(archive.outputStream()).use { zip ->
            root.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                zip.putNextEntry(ZipEntry("${root.name}/${file.relativeTo(root).invariantSeparatorsPath}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    @Test
    fun `dual format and native only downloads retain editable recipes through landing update and reexport`() {
        val (sourceKit, sourceDir) = source()
        for (twin in listOf(true, false)) {
            val label = if (twin) "dual" else "native"
            val (exportRoot, _) = export(sourceKit, sourceDir, label, twin)
            val archive = zip(exportRoot, label)
            val landed = ShelfImport.land(archive, archive.name, File(temp, "$label-shelf"))
            assertEquals(1, landed.kits.size, "XTD and its compatibility twin are one kit")
            assertTrue(landed.skipped.isEmpty(), landed.skipped.toString())
            val landedDir = landed.kits.single().second
            val restored = KitStore.load(landedDir)
            assertEquals(sourceKit.pads.size, restored.pads.size)
            for (original in sourceKit.pads) {
                val pad = assertNotNull(restored.pad(original.slot))
                val saved = assertNotNull(Breed.recipeOf(pad), "${pad.slot} lost its editable recipe through $label import")
                val patch = assertIs<OnsetPatch>(saved.patch)
                assertEquals(PadRecipe.fromJsonValue(assertNotNull(original.recipe)), saved)
                assertEquals(if (pad.slot == 1) 2 else 3, patch.modelVersion)
                assertEquals(6, patch.macros.size)
                assertEquals(original.drumClass, pad.drumClass)
                assertEquals(original.displayName, pad.displayName)
                assertContentEquals(File(sourceDir, original.sampleFile).readBytes(), File(landedDir, pad.sampleFile).readBytes())
                val regenerated = File(temp, "$label-${pad.slot}.wav")
                WavWriter.write(regenerated, saved.render())
                assertContentEquals(regenerated.readBytes(), File(landedDir, pad.sampleFile).readBytes())
            }

            val model = KitBuilderModel.open(landedDir)
            val saved = assertNotNull(Breed.recipeOf(assertNotNull(model.pad(1))))
            val patch = saved.patch as OnsetPatch
            val changed = Patches.edited(patch, patch.name, patch.macros + ("BLEND" to 1f) + ("DECAY" to .7f)) as OnsetPatch
            assertEquals(2, changed.modelVersion)
            val edited = saved.copy(patch = changed)
            RecipeReplay.apply(model, 1, edited.toJsonValue(), "Kick")
            model.save()
            val updated = KitStore.load(landedDir)
            assertEquals(edited, Breed.recipeOf(assertNotNull(updated.pad(1))))
            assertTrue("rawSha256" !in updated.pad(1)!!.source, "rewriting invalidates the previous WAV fingerprint")
            assertEquals("ONSET", updated.pad(1)!!.source["renderer"], "the source's provenance remains")
            val expected = edited.render()
            assertEquals(expected.frameCount, WavReader.read(File(landedDir, updated.pad(1)!!.sampleFile)).frameCount)
            val reexported = File(temp, "$label-edited")
            val result = Mpc3Exporter.exportTrack(updated, landedDir, reexported, mpc2Twin = true)
            val data = File(reexported, Mpc3TrackWriter.trackDataDirName(updated.name))
            assertTrue(Mpc3Project.read(result.program).isTrack)
            assertTrue(File(data, "${updated.name}.xpm").isFile)
            val exported = KitStore.load(data)
            assertEquals(edited, Breed.recipeOf(assertNotNull(exported.pad(1))))
            for (pad in updated.pads) {
                assertContentEquals(File(landedDir, pad.sampleFile).readBytes(), File(data, pad.sampleFile).readBytes())
            }
            val editedArchive = zip(reexported, "$label-edited")
            val relanded = ShelfImport.land(editedArchive, editedArchive.name, File(temp, "$label-edited-shelf"))
            val relandedKit = KitStore.load(relanded.kits.single().second)
            assertEquals(edited, Breed.recipeOf(assertNotNull(relandedKit.pad(1))))
            val compatibility = KitExporter.exportProgramFolder(updated, landedDir, File(temp, "$label-program"))
            val compatibilityZip = zip(compatibility.directory, "$label-program")
            val compatibilityLanding = ShelfImport.land(compatibilityZip, compatibilityZip.name, File(temp, "$label-program-shelf"))
            val compatibilityKit = KitStore.load(compatibilityLanding.kits.single().second)
            assertEquals(edited, Breed.recipeOf(assertNotNull(compatibilityKit.pad(1))))
        }
    }

    @Test
    fun `malformed mismapped and stale sidecars refuse instead of attaching a wrong recipe`() {
        val (kit, sourceDir) = source()
        for (twin in listOf(true, false)) {
            val label = if (twin) "dual-bad" else "native-bad"
            val (root, data) = export(kit, sourceDir, label, twin)
            val sidecar = File(data, KitStore.FILE_NAME)
            val valid = sidecar.readText()
            val bad = listOf(
                "malformed" to "{broken",
                "mapping" to run {
                    KitStore.save(kit.copy(pads = kit.pads.map { if (it.slot == 1) it.copy(sampleFile = "Other.wav") else it }), data)
                    sidecar.readText()
                },
                "hash" to run {
                    KitStore.save(kit.copy(pads = kit.pads.map {
                        if (it.slot == 1) it.copy(source = it.source + ("rawSha256" to "0".repeat(64))) else it
                    }), data)
                    sidecar.readText()
                },
            )
            for ((kind, text) in bad) {
                sidecar.writeText(text)
                val archive = zip(root, "$label-$kind")
                val shelf = File(temp, "$label-$kind-shelf")
                assertFails("$label $kind sidecar must not attach a different recipe") {
                    ShelfImport.land(archive, archive.name, shelf)
                }
                assertTrue(KitStore.list(shelf).isEmpty(), "a refused sidecar leaves no half-landed kit")
            }
            sidecar.writeText(valid)
        }
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
