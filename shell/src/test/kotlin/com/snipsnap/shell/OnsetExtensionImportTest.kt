package com.snipsnap.shell

import com.snipsnap.audio.DrumClass
import com.snipsnap.audio.WavWriter
import com.snipsnap.kit.ArrangedPad
import com.snipsnap.kit.Kit
import com.snipsnap.kit.KitAssembler
import com.snipsnap.kit.KitExporter
import com.snipsnap.kit.KitStore
import com.snipsnap.kit.Mpc3Exporter
import com.snipsnap.kit.Mpc3Importer
import com.snipsnap.kit.XpnImporter
import com.snipsnap.synth.FxChain
import com.snipsnap.synth.OnsetKits
import com.snipsnap.synth.OnsetPatch
import com.snipsnap.synth.OnsetPresets
import com.snipsnap.synth.OnsetVoice
import com.snipsnap.synth.PadRecipe
import com.snipsnap.synth.Patches
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real importer and pad-editor routes, rather than a reconstruction from copied JSON. */
class OnsetExtensionImportTest {
    private val temp = kotlin.io.path.createTempDirectory("onset-extension-import").toFile()
    private enum class Route { XTD, XPM }

    @AfterTest
    fun cleanUp() { temp.deleteRecursively() }

    @Test
    fun `seven native extension recipes survive actual format import edit reopen and reexport`() {
        val source = File(temp, "source")
        val kit = KitAssembler.assembleArranged("ONSET Extension Import", OnsetKits.extensionStudy(), source)
        assertEquals(7, kit.pads.size)
        assertEquals(listOf(OnsetVoice.TOM, OnsetVoice.TOM, OnsetVoice.TOM, OnsetVoice.PERC_PLUCK,
            OnsetVoice.PERC_PLUCK, OnsetVoice.PERC_WOOD, OnsetVoice.PERC_METAL),
            OnsetPresets.extensionStudy().map { it.voice })

        for (route in Route.entries) {
            val (received, directory) = importExport(route, kit, source, "$route-first")
            assertNativeReplay(kit, received, directory)
            val model = KitBuilderModel.open(directory)
            for (pad in received.pads) {
                val saved = assertNotNull(Breed.recipeOf(assertNotNull(model.pad(pad.slot))))
                val patch = assertIs<OnsetPatch>(saved.patch)
                val changed = assertIs<OnsetPatch>(Patches.edited(patch, "Edited ${patch.name}",
                    patch.macros + ("BLEND" to .75f) + ("DECAY" to .65f)))
                assertEquals(4, changed.modelVersion)
                assertEquals(patch.voice, changed.voice)
                val edited = saved.copy(patch = changed)
                val oldAudio = File(directory, pad.sampleFile).readBytes()
                RecipeReplay.apply(model, pad.slot, edited.toJsonValue(), pad.displayName)
                assertFalse(oldAudio.contentEquals(File(directory, pad.sampleFile).readBytes()),
                    "$route must edit the imported ${patch.voice} audio")
                assertTrue("rawSha256" !in assertNotNull(model.pad(pad.slot)).source,
                    "a rewrite must invalidate the imported WAV fingerprint")
            }
            model.save()
            val edited = KitStore.load(directory)
            assertNativeReplay(edited, KitBuilderModel.open(directory).kit, directory, requireFingerprint = false)
            val (reimported, secondDirectory) = importExport(route, edited, directory, "$route-edited")
            assertNativeReplay(edited, reimported, secondDirectory)
            for (pad in edited.pads) assertContentEquals(File(directory, pad.sampleFile).readBytes(),
                File(secondDirectory, reimported.pad(pad.slot)!!.sampleFile).readBytes())
        }
    }

    @Test
    fun `native wood recipe retains FX metadata while program controls remain authoritative`() {
        val patch = OnsetPresets.extensionStudy().single { it.voice == OnsetVoice.PERC_WOOD }
        val recipe = PadRecipe(patch, FxChain(reverse = true), treatment = "reverse", amount = .4f, alias = true)
        val source = File(temp, "fx-source")
        val raw = KitAssembler.assembleArranged("ONSET Wood FX", listOf(
            ArrangedPad(recipe.render(), DrumClass.PERC, recipe.toJsonValue(), level = .67f, displayName = patch.name)), source)
        val shaped = raw.copy(pads = raw.pads.map { it.copy(pan = .29f, tuneCoarse = 2, tuneFine = 11,
            attack = .02f, decay = .72f, cutoff = .41f, resonance = .37f, muteGroup = 2) })
        KitStore.save(shaped, source)
        for (route in Route.entries) {
            val (received, directory) = importExport(route, shaped, source, "$route-fx")
            val pad = received.pads.single()
            assertEquals(recipe, Breed.recipeOf(pad))
            assertEquals(.67f, pad.level, .0001f)
            assertEquals(.29f, pad.pan, .0001f)
            assertEquals(2, pad.tuneCoarse)
            assertEquals(11, pad.tuneFine)
            assertEquals(.02f, assertNotNull(pad.attack), .0001f)
            assertEquals(.72f, assertNotNull(pad.decay), .0001f)
            assertEquals(.41f, assertNotNull(pad.cutoff), .0001f)
            assertEquals(.37f, assertNotNull(pad.resonance), .0001f)
            assertEquals(2, pad.muteGroup)
            assertTrue(pad.oneShot)
            assertNativeReplay(shaped, received, directory)
            val (again, secondDirectory) = importExport(route, received, directory, "$route-fx-again")
            assertEquals(recipe, Breed.recipeOf(again.pads.single()))
            assertNativeReplay(received, again, secondDirectory)
        }
    }

    private fun importExport(route: Route, kit: Kit, source: File, label: String): Pair<Kit, File> {
        val destination = File(temp, "$label-export")
        val shelf = File(temp, "$label-shelf")
        return when (route) {
            Route.XTD -> {
                val result = Mpc3Exporter.exportTrack(kit, source, destination)
                val imported = Mpc3Importer.import(result.program, shelf)
                imported.kit to imported.directory
            }
            Route.XPM -> {
                val result = KitExporter.exportProgramFolder(kit, source, destination)
                val archive = File(temp, "$label.zip")
                ZipOutputStream(archive.outputStream()).use { zip ->
                    result.directory.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { file ->
                        zip.putNextEntry(ZipEntry("${result.directory.name}/${file.relativeTo(result.directory).invariantSeparatorsPath}"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                val imported = XpnImporter.import(archive, shelf)
                imported.kit to imported.directory
            }
        }
    }

    private fun assertNativeReplay(expected: Kit, received: Kit, directory: File, requireFingerprint: Boolean = true) {
        assertEquals(expected.pads.size, received.pads.size)
        for (original in expected.pads) {
            val pad = assertNotNull(received.pad(original.slot))
            val recipe = assertNotNull(Breed.recipeOf(pad), "pad ${pad.slot} lost its native recipe")
            assertEquals(PadRecipe.fromJsonValue(assertNotNull(original.recipe)), recipe)
            val patch = assertIs<OnsetPatch>(recipe.patch)
            assertEquals(4, patch.modelVersion)
            assertEquals(6, patch.macros.size)
            assertTrue("setupId" !in patch.toJsonValue().entries)
            assertEquals(original.drumClass, pad.drumClass)
            val replay = ByteArrayOutputStream().also { WavWriter.write(it, recipe.render()) }.toByteArray()
            val sample = File(directory, pad.sampleFile)
            assertContentEquals(replay, sample.readBytes(), "${patch.voice} replay changed through format import")
            if (requireFingerprint) assertEquals(hash(sample), pad.source["rawSha256"],
                "the exported sidecar must fingerprint current audio")
        }
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
